#!/usr/bin/env python3
"""`teamcode ui` with the native CLI and a fake desktop app, in a temporary HOME (not run by CI).

The fake app records the arguments it is opened with, so the real GUI never starts and no preference of this machine is
written. Checks: an app of the CLI's version is opened as is with the CLI's config.json and the chosen folder (through
`open` on macOS, detached elsewhere); with an older app, a desktop manifest the CLI's publisher keys do not
authenticate installs nothing and leaves the older app in place; a refused folder is said. Installing a signed package
needs a release signed with the publisher key: the release pipeline's acceptance does it, the JVM tests use a test key.

    python3 scripts/smoke-ui.py --cli cli/build/bin/macosArm64/debugExecutable/teamcode.kexe
"""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
import time


def fake_app(home, system, version, record):
    if system == 'macos':
        root = home / 'Applications' / 'TeamCode.app'
        launcher, resources = root / 'Contents' / 'MacOS' / 'TeamCode', root / 'Contents' / 'app' / 'resources'
        (root / 'Contents').mkdir(parents=True)
        (root / 'Contents' / 'Info.plist').write_text(
            '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
            '<plist version="1.0"><dict><key>CFBundleExecutable</key><string>TeamCode</string>'
            '<key>CFBundleIdentifier</key><string>me.nathanfallet.teamcode.smoke-fake</string>'
            '<key>CFBundlePackageType</key><string>APPL</string><key>LSBackgroundOnly</key><true/></dict></plist>\n')
    else:
        root = home / '.local' / 'share' / 'teamcode-desktop' / version
        launcher, resources = root / 'bin' / 'TeamCode', root / 'lib' / 'app' / 'resources'
        (root.parent / 'current').symlink_to(version)
    launcher.parent.mkdir(parents=True)
    launcher.write_text('#!/bin/sh\nprintf "%%s\\n" "$@" > %s\n' % record)
    launcher.chmod(0o755)
    resources.mkdir(parents=True)
    return resources / 'teamcode-desktop-version'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--cli', required=True, type=Path)
    cli = parser.parse_args().cli.resolve()
    system = 'macos' if platform.system() == 'Darwin' else 'linux'
    version = subprocess.run([cli, 'version'], capture_output=True, text=True, check=True).stdout.split()[-1]
    with tempfile.TemporaryDirectory(prefix='teamcode-ui-smoke-') as directory:
        work = Path(directory)
        home, config, project, temporary = work / 'home', work / 'xdg', work / 'project', work / 'tmp'
        for path in (home, config, project, temporary):
            path.mkdir()
        record = work / 'launched-args'
        version_file = fake_app(home, system, version, record)
        env = {**os.environ, 'HOME': str(home), 'XDG_CONFIG_HOME': str(config), 'XDG_DATA_HOME': str(home / '.local' / 'share'), 'TMPDIR': str(temporary)}

        def ui(*arguments):
            result = subprocess.run([cli, 'ui', *arguments], env=env, capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=60)
            return result.returncode, result.stdout + result.stderr

        # An app of the CLI's version: opened as is, nothing downloaded.
        version_file.write_text(version + '\n')
        code, output = ui(str(project), '--no-launch')
        assert code == 0 and f'TeamCode Desktop {version} is installed' in output, output
        code, output = ui(str(project))
        assert code == 0 and f'Opened TeamCode Desktop {version}.' in output, output
        deadline = time.monotonic() + 15
        while not record.exists() and time.monotonic() < deadline:
            time.sleep(0.2)
        arguments = record.read_text().splitlines()
        expected = ['--config-path', str(config / 'teamcode' / 'config.json'), '--directory', str(project.resolve())]
        assert arguments == expected, arguments
        print('opened with', json.dumps(arguments))

        # An older app and a desktop manifest the shipped publisher keys do not authenticate: nothing is installed,
        # with or without --yes, and the older app stays.
        version_file.write_text('0.0.1\n')
        bundle = work / 'bundle'
        bundle.mkdir()
        manifest = {'schemaVersion': 1, 'product': 'desktop', 'version': version, 'tag': version, 'sourceCommit': 'e' * 40, 'assets': {}}
        (bundle / 'desktop-manifest.json').write_text(json.dumps(manifest))
        (bundle / 'desktop-manifest.sig').write_text(json.dumps({'schemaVersion': 1, 'algorithm': 'RSA-PSS-SHA256', 'keyId': '0' * 64, 'signature': 'A' * 512}))
        code, output = ui('--bundle', str(bundle))
        assert code != 0 and 'release_key_untrusted' in output, output
        print('unsigned desktop manifest refused:', output.strip().splitlines()[-1])
        code, output = ui('--bundle', str(bundle), '--yes', '--no-launch')
        assert code != 0 and 'release_key_untrusted' in output, output
        assert version_file.read_text() == '0.0.1\n'
        assert not any(temporary.iterdir()), list(temporary.iterdir())

        # The folder rules of remote sessions.
        code, output = ui(str(home))
        assert code != 0 and 'may not run in' in output, output
        print('refused folder:', output.strip().splitlines()[-1])
    print('teamcode ui smoke passed')


if __name__ == '__main__':
    sys.exit(main())
