#!/usr/bin/env python3
"""Release acceptance of TeamCode Desktop: the signed CLI installs the signed desktop packages with `teamcode ui`.

Run on each release host with the CLI binary of the same release and the directory holding `desktop-manifest.json`,
`desktop-manifest.sig` and the packages:

    python3 scripts/smoke-desktop-install.py --binary teamcode-1.0.0-linux-x64 --bundle desktop-dist

Checks, as the current user and without administrator rights: a package with one modified byte is refused and installs
nothing; the real package installs (`teamcode ui --bundle DIR --yes --no-launch`) where `teamcode ui` looks for it and
reports the CLI's version; on macOS the installed app passes `codesign`, Gatekeeper (`spctl`) and its stapled
notarization ticket; on Linux `current` and the menu entry exist; a second run finds the app and downloads nothing.
On Windows the per-user installation is removed at the end.

`--expect-macos-refusal` is for a validation run without the Developer ID: the unsigned app must be refused on macOS.
"""
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tempfile


def system():
    return {'Darwin': 'macos', 'Windows': 'windows'}.get(platform.system(), 'linux')


def target():
    machine = {'arm64': 'arm64', 'aarch64': 'arm64', 'amd64': 'x64', 'x86_64': 'x64'}[platform.machine().lower()]
    return f'{system()}-{machine}'


def desktop_name(version, target_name):
    extension = 'zip' if target_name.startswith('macos-') else 'msi' if target_name.startswith('windows-') else 'tar.gz'
    return f'teamcode-desktop-{version}-{target_name}.{extension}'


def installed_root(env):
    os_name = system()
    if os_name == 'macos':
        return Path(env['HOME']) / 'Applications' / 'TeamCode.app'
    if os_name == 'windows':
        return Path(env['LOCALAPPDATA']) / 'Programs' / 'TeamCode'
    return Path(env['XDG_DATA_HOME']) / 'teamcode-desktop' / 'current'


def version_file(root):
    os_name = system()
    if os_name == 'macos':
        return root / 'Contents' / 'app' / 'resources' / 'teamcode-desktop-version'
    if os_name == 'windows':
        return root / 'app' / 'resources' / 'teamcode-desktop-version'
    return root / 'lib' / 'app' / 'resources' / 'teamcode-desktop-version'


def require(condition, message):
    if not condition:
        raise SystemExit('Desktop acceptance failed: ' + message)


def show_installer_log(temporary):
    """The Windows installer's verbose log, before the temporary folder holding it is deleted with the run's work."""
    log = temporary / 'teamcode-desktop-msiexec.log'
    if not log.is_file():
        return
    data = log.read_bytes()
    text = data.decode('utf-16' if data[:2] in (b'\xff\xfe', b'\xfe\xff') else 'utf-8', errors='replace')
    lines = text.splitlines()
    print(f'--- {log.name}: last 120 of {len(lines)} lines ---\n' + '\n'.join(lines[-120:]) + '\n--- end ---', flush=True)
    if system() == 'windows':
        subprocess.run(['tasklist', '/FI', 'IMAGENAME eq msiexec.exe', '/V'], timeout=60)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', required=True, type=Path)
    parser.add_argument('--bundle', required=True, type=Path)
    parser.add_argument('--expect-macos-refusal', action='store_true')
    args = parser.parse_args()
    cli, bundle = args.binary.resolve(), args.bundle.resolve()
    version = subprocess.run([str(cli), 'version'], capture_output=True, text=True, check=True, timeout=30).stdout.split()[-1]
    package = desktop_name(version, target())
    require((bundle / package).is_file(), f'{package} is missing from the bundle')

    with tempfile.TemporaryDirectory(prefix='teamcode-desktop-acceptance-') as directory:
        work = Path(directory)
        home, config, temporary = work / 'home', work / 'config', work / 'tmp'
        for path in (home, config, temporary):
            path.mkdir()
        env = {**os.environ, 'TMPDIR': str(temporary), 'TEMP': str(temporary), 'TMP': str(temporary),
               'XDG_CONFIG_HOME': str(config), 'TEAMCODE_DISABLE_AUTOUPDATE': '1'}
        # The Windows installer writes to the user's real %LOCALAPPDATA%; elsewhere the app goes to a temporary HOME.
        if system() != 'windows':
            env.update(HOME=str(home), XDG_DATA_HOME=str(home / '.local' / 'share'))
        root = installed_root(env)
        require(not root.exists(), f'{root} already exists on this runner')

        def ui(source):
            result = subprocess.run([str(cli), 'ui', '--bundle', str(source), '--yes', '--no-launch'], env=env,
                                    capture_output=True, text=True, stdin=subprocess.DEVNULL, timeout=900)
            output = result.stdout + result.stderr
            print(f'$ teamcode ui --bundle {source.name} --yes --no-launch -> {result.returncode}\n{output}', flush=True)
            if result.returncode != 0:
                show_installer_log(temporary)
            return result.returncode, output

        # One modified byte in the middle of the package: refused before anything opens it, nothing installed.
        tampered = work / 'tampered'
        tampered.mkdir()
        for name in ('desktop-manifest.json', 'desktop-manifest.sig', package):
            shutil.copyfile(bundle / name, tampered / name)
        data = bytearray((tampered / package).read_bytes())
        data[len(data) // 2] ^= 0x01
        (tampered / package).write_bytes(bytes(data))
        code, output = ui(tampered)
        # A CLI built without the Team ID refuses on macOS before downloading: that refusal also installs nothing.
        refused_early = args.expect_macos_refusal and 'cannot check who signed' in output
        require(code != 0 and ('release_binary_invalid' in output or refused_early), 'a modified package was not refused as such')
        require(not root.exists(), 'a modified package left an installation')

        if args.expect_macos_refusal:
            require(system() == 'macos', '--expect-macos-refusal is for macOS')
            code, output = ui(bundle)
            require(code != 0 and ('cannot check who signed' in output or 'desktop_signature_invalid' in output),
                    'an app without the Developer ID signature was not refused')
            require(not root.exists(), 'a refused app was installed')
            print(f'Desktop acceptance passed on {target()}: modified and unsigned packages refused.')
            return

        code, output = ui(bundle)
        require(code == 0 and f'TeamCode Desktop {version} is installed' in output, 'the signed package did not install')
        require(version_file(root).read_text().strip() == version, 'the installed app reports another version')
        try:
            if system() == 'macos':
                for command in (['codesign', '--verify', '--deep', '--strict', str(root)],
                                ['spctl', '--assess', '--type', 'execute', str(root)],
                                ['xcrun', 'stapler', 'validate', str(root)]):
                    require(subprocess.run(command, timeout=120).returncode == 0, ' '.join(command[:2]) + ' refused the installed app')
            elif system() == 'linux':
                require(root.is_symlink() and os.readlink(root) == version, 'current does not name the installed version')
                require((home / '.local' / 'share' / 'applications' / 'teamcode-desktop.desktop').is_file(), 'no menu entry')
            code, output = ui(bundle)
            require(code == 0 and 'Downloading' not in output, 'an installed app of the same version was installed again')
        finally:
            if system() == 'windows':
                subprocess.run(['msiexec.exe', '/x', str(bundle / package), '/qn', '/norestart'], timeout=600)
    print(f'Desktop acceptance passed on {target()}: {package} installed by teamcode ui {version}.')


if __name__ == '__main__':
    sys.exit(main())
