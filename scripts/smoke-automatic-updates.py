#!/usr/bin/env python3
"""Exercise an owned OS update job and native proxy with signed, already staged test builds."""
import argparse
import base64
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request


@contextmanager
def activity_lease(path):
    # The ordinary native installation smoke separately proves that a quiet
    # teamcode run holds this same OS lock for the full agent lifetime.
    with path.open('a+b') as stream:
        if os.name == 'nt':
            import msvcrt
            stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_NBRLCK, 1)
        else:
            import fcntl
            path.chmod(0o600); fcntl.flock(stream.fileno(), fcntl.LOCK_SH | fcntl.LOCK_NB)
        try: yield
        finally:
            if os.name == 'nt': stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else: fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--first-bundle', type=Path, required=True)
    parser.add_argument('--second-bundle', type=Path, required=True)
    args = parser.parse_args()
    binary = args.binary.resolve(strict=True)
    first, second = args.first_bundle.resolve(strict=True), args.second_bundle.resolve(strict=True)
    old, new = (json.loads((p / 'manifest.json').read_bytes()) for p in (first, second))
    stable = '-' not in new['version']
    workspace = Path(tempfile.mkdtemp(prefix='teamcode-automatic-native-'))
    workspace.chmod(0o700)
    root = workspace / "application é space's"
    home = workspace / 'home'; home.mkdir()
    config_root = workspace / 'configuration'
    config_path = config_root / 'teamcode/config.json'
    launcher = root / 'bin' / ('teamcode.exe' if os.name == 'nt' else 'teamcode')
    env = dict(os.environ, HOME=str(home), XDG_CONFIG_HOME=str(config_root))
    env.pop('TEAMCODE_DISABLE_AUTOUPDATE', None)
    token = secrets.token_hex(32)
    service_installed = False
    cleanup_complete = False
    next_job_trigger = 0.0

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_GET(self):
            if self.headers.get('Authorization') != 'Bearer ' + token:
                self.send_error(401); return
            data = json.dumps(dict(apiVersion=1, serverVersion='automatic-fixture', mode='fixture', features=[])).encode()
            self.send_response(200); self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data)))
            self.end_headers(); self.wfile.write(data)
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()

    def run(argv, success=True, environment=env):
        result = subprocess.run([str(value) for value in argv], env=environment, stdin=subprocess.DEVNULL, capture_output=True, timeout=90)
        if success: assert result.returncode == 0, (result.stdout + result.stderr).decode(errors='replace')
        return result

    def until(probe, seconds=120):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            result = probe()
            if result: return result
            time.sleep(.25)
        raise AssertionError('Owned automatic-update fixture did not reach the expected state')

    def result_after(previous=0):
        path = root / 'automatic-update-result.json'
        if not path.exists(): return None
        result = json.loads(path.read_bytes())
        return result if result['attemptedAtMillis'] > previous and result['outcome'] != 'STARTED' else None

    def trigger_owned_job():
        nonlocal next_job_trigger
        # Allow launchd's normal quick-exit throttle to expire; repeated
        # kickstarts must not turn this test into a busy restart loop.
        if time.monotonic() < next_job_trigger: return False
        next_job_trigger = time.monotonic() + 12
        settings = json.loads((root / 'automatic-update.json').read_bytes())
        assert settings['enabled'] and Path(settings['executable']).resolve() == launcher.resolve()
        label = 'me.nathanfallet.teamcode.updates.' + settings['id']
        if settings['os'] == 'macos':
            argv = ['/bin/launchctl', 'kickstart', f'gui/{settings["userId"]}/{label}']
        elif settings['os'] == 'linux':
            argv = ['/usr/bin/systemctl', '--user', 'start', '--no-block', label + '.service']
        else:
            argv = [str(Path(os.environ['SystemRoot']) / 'System32/schtasks.exe'), '/Run', '/TN', label]
        return run(argv, success=False).returncode == 0

    def service_version():
        settings = json.loads(config_path.with_name('service.json').read_bytes())
        request = urllib.request.Request(f'http://127.0.0.1:{settings["port"]}/_teamcode/service',
                                         headers={'Authorization': 'Bearer ' + settings['localToken']})
        try:
            with urllib.request.urlopen(request, timeout=2) as response: return json.load(response).get('version')
        except OSError: return None

    try:
        run([binary, 'install', '--no-path', '--bundle', first, '--root', root])
        assert run([launcher, 'version']).stdout.decode().strip() == 'TeamCode ' + old['version']
        run([launcher, 'update', '--notifications', 'off'])
        config_path.parent.mkdir(parents=True)
        config_path.write_text(json.dumps(dict(serverUrl=f'http://127.0.0.1:{server.server_port}', accessToken=token)))
        config_path.chmod(0o600)
        before = config_path.read_bytes()
        with socket.socket() as probe: probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]
        service_installed = True
        run([launcher, 'service', 'install', '--port', str(port)])
        until(lambda: service_version() == old['version'])

        # Stage verified bytes through the real native installer while the
        # activity lock prevents activation. No public release exists yet.
        with activity_lease(root / 'installation.lock'):
            staged = run([launcher, 'update', '--bundle', second], success=False)
            assert staged.returncode != 0 and b'release_installation_busy' in staged.stdout + staged.stderr
            digest = hashlib.sha256((second / 'manifest.json').read_bytes()).hexdigest()
            assert (root / 'versions' / digest).is_dir()
            cache = dict(schemaVersion=1, checkedAtMillis=int(time.time() * 1000), successfulObservation=True,
                         manifest=base64.b64encode((second / 'manifest.json').read_bytes()).decode(),
                         signature=base64.b64encode((second / 'manifest.sig').read_bytes()).decode())
            (root / 'update-check.json').write_text(json.dumps(cache)); (root / 'update-check.json').chmod(0o600)
            run([launcher, 'update', '--automatic', 'on', '--root', root])
            run([launcher, 'update', '--automatic', 'status'])
            def first_attempt():
                observed = result_after()
                if observed: return observed
                trigger_owned_job()
                return result_after()
            first_result = until(first_attempt)
            assert first_result['outcome'] == ('DEFERRED' if stable else 'NO_UPDATE'), first_result
            print('Owned automatic job first result: ' + first_result['outcome'], flush=True)
            if stable: assert first_result['diagnostic'] == 'release_installation_busy'
            assert service_version() == old['version'], 'A busy update interrupted the proxy'
            assert run([launcher, 'version']).stdout.decode().strip() == 'TeamCode ' + old['version']

        # Request the existing OS job without changing its policy, matching a
        # subsequent timer tick. The worker is outside the proxy service group.
        previous = first_result['attemptedAtMillis']
        def trigger_until_finished():
            observed = result_after(previous)
            if observed: return observed
            # Do not repeatedly reconfigure opt-in while a worker is starting.
            # kickstart deliberately does not replace a still-exiting worker.
            trigger_owned_job()
            return result_after(previous)
        final = until(trigger_until_finished)
        expected = new['version'] if stable else old['version']
        assert final['outcome'] == ('UPDATED' if stable else 'NO_UPDATE'), final
        until(lambda: service_version() == expected)
        assert run([launcher, 'version']).stdout.decode().strip() == 'TeamCode ' + expected
        assert config_path.read_bytes() == before, 'Automatic update changed saved authentication'
        status = run([launcher, 'update', '--automatic', 'status'])
        assert b'Automatic updates: on.' in status.stdout and token.encode() not in status.stdout + status.stderr
        run([launcher, 'update', '--automatic', 'off'])
        assert not json.loads((root / 'automatic-update.json').read_bytes())['enabled']
        assert service_version() == expected, 'Disabling updates stopped the proxy'
    except Exception:
        settings_file = root / 'automatic-update.json'
        if settings_file.exists():
            settings = json.loads(settings_file.read_bytes())
            if settings['os'] == 'macos':
                printed = run(['/bin/launchctl', 'print', 'gui/' + settings['userId'] + '/me.nathanfallet.teamcode.updates.' + settings['id']], success=False)
                markers = ('state =', 'program =', 'pid =', 'runs =', 'last exit code =', 'last terminating signal =')
                for line in printed.stdout.decode(errors='replace').splitlines():
                    if line.strip().startswith(markers): print('Owned job state: ' + line.strip(), flush=True)
        observed = root / 'automatic-update-result.json'
        if observed.exists():
            diagnostic = json.loads(observed.read_bytes())
            print('Owned automatic job diagnostic: ' + json.dumps({key: diagnostic.get(key) for key in
                  ('attemptedAtMillis', 'outcome', 'version', 'diagnostic')}), flush=True)
        raise
    finally:
        try:
            if (root / 'automatic-update.json').exists():
                scheduled = json.loads((root / 'automatic-update.json').read_bytes())
                run([launcher, 'update', '--automatic', 'off'])
                until(lambda: run([launcher, 'update', '--automatic', 'uninstall'], success=False).returncode == 0)
                assert not (root / 'automatic-update.json').exists()
                assert not Path(scheduled['registration']).exists()
                label = 'me.nathanfallet.teamcode.updates.' + scheduled['id']
                if scheduled['os'] == 'macos':
                    assert run(['/bin/launchctl', 'print', 'gui/' + scheduled['userId'] + '/' + label], success=False).returncode != 0
                elif scheduled['os'] == 'linux':
                    assert run(['/usr/bin/systemctl', '--user', 'is-enabled', label + '.timer'], success=False).returncode != 0
                else:
                    assert run([Path(os.environ['SystemRoot']) / 'System32/schtasks.exe', '/Query', '/TN', label], success=False).returncode != 0
            if service_installed: run([launcher, 'service', 'uninstall'])
            cleanup_complete = True
        finally:
            server.shutdown(); server.server_close(); thread.join(timeout=5)
            if cleanup_complete: shutil.rmtree(workspace)
            else: print('Owned fixture retained for safe recovery: ' + str(workspace), flush=True)
    print('PASS: actual owned OS updater, independent notifications, busy deferral, preserved authentication, verified OS registration removal, '
          + ('stable automatic activation and proxy restart.' if stable else 'automatic prerelease refusal (stable activation still needs a stable candidate).'), flush=True)


if __name__ == '__main__': main()
