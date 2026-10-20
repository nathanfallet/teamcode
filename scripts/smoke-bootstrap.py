#!/usr/bin/env python3
"""Run the generated bootstrap with a publisher-signed native bundle on its actual target host."""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import platform
import secrets
import shutil
import subprocess
import tempfile
import threading


def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    args = parser.parse_args()
    bundle = args.bundle.resolve(strict=True)
    target = ('windows-x64' if os.name == 'nt' else
              ('macos-' if platform.system() == 'Darwin' else 'linux-') +
              ('arm64' if platform.machine().lower() in ('arm64', 'aarch64') else 'x64'))
    manifest = json.loads((bundle / 'manifest.json').read_bytes())
    asset = manifest['assets'][target]
    script = bundle / ('install.ps1' if os.name == 'nt' else 'install.sh')
    assert script.is_file(), 'Use the generated installer from the same signed bundle'
    original = {path: digest(path) for path in (script, bundle / 'manifest.json', bundle / 'manifest.sig', bundle / asset['fileName'])}
    shells = ([executable for executable in (shutil.which('powershell.exe'), shutil.which('pwsh')) if executable]
              if os.name == 'nt' else [shutil.which('sh')])
    assert shells and all(shells), 'Install the platform shell before bootstrap acceptance'

    with tempfile.TemporaryDirectory(prefix='teamcode-bootstrap-native-') as directory:
        workspace = Path(directory)
        token = secrets.token_hex(32)
        calls = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_): pass
            def do_GET(self):
                if self.path != '/api/v1/capabilities' or self.headers.get('Authorization') != 'Bearer ' + token:
                    self.send_error(403); return
                calls.append(self.path)
                body = json.dumps(dict(apiVersion=1, serverVersion='bootstrap-fixture', mode='fixture', features=[])).encode()
                self.send_response(200); self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
        try:
            for index, shell in enumerate(shells):
                root = workspace / ("application é space's " + str(index)); root.mkdir()
                install_root = root / 'installation'
                home = root / 'home'; home.mkdir()
                temporary = root / 'temporary'; temporary.mkdir()
                config_root = root / 'configuration'
                env = dict(os.environ, HOME=str(home), XDG_CONFIG_HOME=str(config_root),
                           TMPDIR=str(temporary), TEMP=str(temporary), TMP=str(temporary),
                           TEAMCODE_INSTALL_ROOT=str(install_root), TEAMCODE_INSTALL_BUNDLE=str(bundle),
                           TEAMCODE_DISABLE_AUTOUPDATE='1')
                launcher = install_root / 'bin' / ('teamcode.exe' if os.name == 'nt' else 'teamcode')
                invoke = [shell, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(script)] if os.name == 'nt' else [shell, str(script)]

                def run(command, environment=env, success=True):
                    result = subprocess.run(command, env=environment, stdin=subprocess.DEVNULL, capture_output=True, timeout=60)
                    if success:
                        assert result.returncode == 0, (result.stdout + result.stderr).decode(errors='replace')
                    else: assert result.returncode != 0, 'Bootstrap unexpectedly accepted modified release bytes'
                    return result

                if os.name == 'nt':
                    # Exercise the same HTTP helper on both .NET Framework /
                    # Windows PowerShell and modern PowerShell, without network.
                    run([shell, '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
                         str(Path(__file__).resolve().parent / 'fixtures/bootstrap-http.ps1'), '-Template', str(script)])

                # A clean install needs no account, provider token or central server.
                run(invoke)
                assert run([str(launcher), 'version']).stdout.decode().strip() == 'TeamCode ' + manifest['version']
                state = json.loads((install_root / 'current.json').read_bytes())
                assert state['current']['manifestSha256'] == digest(bundle / 'manifest.json')
                assert not config_root.exists(), 'Fresh bootstrap created a login'
                assert not list(temporary.glob('teamcode-install*')), 'Bootstrap left downloaded temporary files'
                launcher_hash = digest(launcher)

                # Re-running uses the same installation while preserving a saved
                # synthetic self-hosted URL, login/device identity and agent files.
                config = config_root / 'teamcode/config.json'; config.parent.mkdir(parents=True)
                config.write_text(json.dumps(dict(serverUrl=f'http://127.0.0.1:{server.server_port}', accessToken=token)))
                config.chmod(0o600)
                identity = config.with_name('installation-id'); identity.write_text('00000000-0000-4000-8000-000000000001')
                preserved = [config, identity]
                for relative in ('.claude/settings.json', '.codex/config.toml'):
                    path = home / relative; path.parent.mkdir(parents=True); path.write_text('bootstrap-preservation-marker'); preserved.append(path)
                before = {path: path.read_bytes() for path in preserved}
                previous_calls = len(calls)
                run(invoke)
                assert len(calls) > previous_calls, 'Saved self-hosted server compatibility was not checked'
                assert {path: path.read_bytes() for path in preserved} == before
                assert digest(launcher) == launcher_hash, 'Idempotent bootstrap replaced the stable launcher'
                assert run([str(launcher), 'version']).stdout.decode().strip() == 'TeamCode ' + manifest['version']

                corrupt = root / 'corrupt'; corrupt.mkdir()
                for filename in ('manifest.json', 'manifest.sig', asset['fileName']): shutil.copyfile(bundle / filename, corrupt / filename)
                binary = corrupt / asset['fileName']
                with binary.open('r+b') as stream:
                    byte = stream.read(1); stream.seek(0); stream.write(bytes([byte[0] ^ 1]))
                rejected = root / 'rejected'
                run(invoke, dict(env, TEAMCODE_INSTALL_BUNDLE=str(corrupt), TEAMCODE_INSTALL_ROOT=str(rejected)), success=False)
                assert not rejected.exists(), 'Corrupted bootstrap executed the native installer'
                assert not list(temporary.glob('teamcode-install*')), 'Failed bootstrap left temporary files'
                assert {path: path.read_bytes() for path in preserved} == before
        finally:
            server.shutdown(); server.server_close(); worker.join(timeout=5)
        assert {path: digest(path) for path in original} == original, 'Bootstrap modified its signed source bundle'
    print('PASS: generated bootstrap with actual native install/idempotence, version selection, login/device/agent preservation, corrupt-byte refusal and cleanup on ' + target + '.')


if __name__ == '__main__': main()
