#!/usr/bin/env python3
"""Exercise an isolated native user service and its actual OS manager. No real credentials/providers."""
import argparse
import concurrent.futures
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--binary', type=Path, required=True)
parser.add_argument('--at-boot', action='store_true', help='Exercise an owned pre-login registration; needs OS administrator authorization')
args = parser.parse_args()
binary = str(args.binary.resolve())
expected_version = subprocess.check_output([binary, 'version'], text=True, timeout=15).strip().split(' ', 1)[1]
assert sys.platform in ('darwin', 'linux'), 'This acceptance script targets launchd/systemd services'
if args.at_boot:
    assert subprocess.run(['/usr/bin/sudo', '-n', 'true'], capture_output=True).returncode == 0, 'Boot acceptance needs pre-authorized sudo for this owned fixture'

with tempfile.TemporaryDirectory(prefix='teamcode-service-smoke-') as directory:
    root = Path(directory)
    env = dict(os.environ, HOME=str(root / 'home'), XDG_CONFIG_HOME=str(root / 'configuration with spaces'))
    Path(env['HOME']).mkdir()
    config = Path(env['XDG_CONFIG_HOME']) / 'teamcode/config.json'
    token = secrets.token_hex(32)
    online = True
    started = threading.Event()
    release = threading.Event()
    calls = []
    workloads = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            if not online:
                self.send_error(503)
                return
            if self.headers.get('Authorization') != 'Bearer ' + token:
                self.send_error(401)
                return
            payload = ({'id': 'member', 'username': 'synthetic', 'role': 'MEMBER',
                        'disabled': False, 'passwordChangeRequired': False} if self.path.endswith('/me') else
                       {'serverVersion': 'test', 'mode': 'fixture', 'features': []})
            data = json.dumps(payload).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            self.rfile.read(int(self.headers.get('Content-Length', '0')))
            if not online:
                self.send_error(503)
                return
            if self.headers.get('Authorization') != 'Bearer ' + token:
                self.send_error(401)
                return
            calls.append(self.path)
            workloads.append(self.headers.get('x-teamcode-workload'))
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            self.wfile.write(b'data: {"start":true}\n\n')
            self.wfile.flush()
            started.set()
            assert release.wait(30), 'Test did not release the active stream'
            self.wfile.write(b'data: {"done":true}\n\n')
            self.wfile.flush()

    upstream = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    origin = 'http://127.0.0.1:' + str(upstream.server_port)
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    service_origin = f'http://127.0.0.1:{port}'

    def command(*arguments, check=True, stdin=None):
        result = subprocess.run([binary, *arguments], env=env, input=stdin, text=True,
                                capture_output=True, timeout=45)
        assert token not in result.stdout + result.stderr, 'Team credential leaked'
        if check:
            assert result.returncode == 0, f'Command {arguments} failed: {result.stdout}\n{result.stderr}'
        return result

    def health(settings, expected=True):
        observed = 'not checked'
        for _ in range(150):
            try:
                request = urllib.request.Request(service_origin + '/_teamcode/service',
                                                 headers={'Authorization': 'Bearer ' + settings['localToken']})
                with urllib.request.urlopen(request, timeout=1) as response:
                    body = json.load(response)
                running = all(body.get(key) == value for key, value in {'id': settings['id'], 'serverUrl': origin, 'version': expected_version}.items()) and {'workload-routing-v1', 'run-routing-v1'} <= set(body.get('capabilities', []))
                observed = 'matching identity' if running else 'identity mismatch'
            except OSError as error:
                observed = type(error).__name__ + (f' HTTP {error.code}' if isinstance(error, urllib.error.HTTPError) else '')
                running = False
            if running == expected:
                return
            time.sleep(.1)
        print(command('service', 'logs', check=False).stdout, flush=True)
        print(command('service', 'status', check=False).stdout, flush=True)
        if sys.platform == 'darwin':
            detail = subprocess.run(['/bin/launchctl', 'print', ('system/' if args.at_boot else f'gui/{os.getuid()}/') + 'me.nathanfallet.teamcode.' + settings['id']], capture_output=True, text=True)
            print('\n'.join(line for line in detail.stdout.splitlines() if any(key in line for key in ('state =', 'pid =', 'last exit', 'runs ='))), flush=True)
        raise AssertionError('Service did not reach its expected state: ' + observed)

    def inference(settings):
        request = urllib.request.Request(service_origin + '/v1/messages', b'{"model":"fixture"}',
            {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + settings['localToken']})
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.read()

    def replace_registration(path, text):
        if args.at_boot and sys.platform == 'darwin':
            subprocess.run(['/usr/bin/sudo', '-n', '/usr/bin/tee', str(path)], input=text, text=True, capture_output=True, check=True, timeout=15)
        else:
            path.write_text(text)

    installed = False
    settings = None
    try:
        command('configure', '--server', origin, stdin=token + '\n')
        original_config = config.read_bytes()
        # Actual OS registration and native daemon; it is uniquely named for this temporary configuration.
        installed = True
        command('service', 'install', '--port', str(port), *(['--at-boot'] if args.at_boot else []))
        settings = json.loads(config.with_name('service.json').read_text())
        registration = Path(settings['registration'])
        health(settings)
        assert settings.get('atBoot', False) == args.at_boot
        if args.at_boot and sys.platform == 'darwin':
            assert registration.parent == Path('/Library/LaunchDaemons') and registration.stat().st_uid == 0
            assert registration.stat().st_mode & 0o777 == 0o644
        assert command('service', 'serve', '--config', str(config), check=False).returncode != 0, 'Duplicate daemon bound the occupied port'
        health(settings)
        assert token not in registration.read_text() and settings['localToken'] not in registration.read_text()
        assert config.with_name('service.json').stat().st_mode & 0o777 == 0o600
        assert 'Running: true' in command('service', 'status').stdout
        command('service', 'install', '--port', str(port), *(['--at-boot'] if args.at_boot else []))
        assert json.loads(config.with_name('service.json').read_text()) == settings, 'Install was not idempotent'
        try:
            urllib.request.urlopen(service_origin + '/_teamcode/service', timeout=2)
            raise AssertionError('Service identity accessible without local capability')
        except urllib.error.HTTPError as error:
            assert error.code == 401

        # Saved login changes are read per request, without restarting the daemon.
        token = secrets.token_hex(32)
        command('configure', '--server', origin, stdin=token + '\n')
        release.set()
        assert b'"done":true' in inference(settings)
        # Offline upstream does not stop the local service or launch an interactive login.
        online = False
        try:
            inference(settings)
            raise AssertionError('Offline upstream unexpectedly succeeded')
        except urllib.error.HTTPError as error:
            assert error.code == 503
        health(settings)
        online = True
        assert b'"done":true' in inference(settings)
        upstream_port = upstream.server_port
        upstream.shutdown()
        upstream.server_close()
        try:
            inference(settings)
            raise AssertionError('Disconnected upstream unexpectedly succeeded')
        except urllib.error.HTTPError as error:
            assert error.code == 500
        health(settings)
        upstream = ThreadingHTTPServer(('127.0.0.1', upstream_port), Handler)
        threading.Thread(target=upstream.serve_forever, daemon=True).start()
        assert b'"done":true' in inference(settings)

        # A token configured for another origin must never be sent to the old upstream.
        saved_config = config.read_bytes()
        calls_before = len(calls)
        replacement = json.loads(saved_config)
        replacement['serverUrl'] = 'https://different.invalid'
        replacement['accessToken'] = secrets.token_hex(32)
        config.write_text(json.dumps(replacement))
        try:
            inference(settings)
            raise AssertionError('Changed upstream origin was accepted without restart')
        except urllib.error.HTTPError as error:
            assert error.code == 500
        assert len(calls) == calls_before
        config.write_bytes(saved_config)

        # run must reuse this installation and leave it running when the child exits.
        agent = root / 'claude'
        agent.write_text('#!' + sys.executable + '\nimport os, sys, urllib.request\n'
                         f'assert os.environ["ANTHROPIC_BASE_URL"] == {service_origin!r} + os.environ.get("TEST_WORKLOAD_PREFIX", "")\n'
                         'assert len(os.environ["ANTHROPIC_API_KEY"]) == 64\n'
                         'request = urllib.request.Request(os.environ["ANTHROPIC_BASE_URL"] + "/v1/messages", b\'{"model":"fixture"}\', '
                         '{"Content-Type":"application/json", "Authorization":"Bearer " + os.environ["ANTHROPIC_API_KEY"]})\n'
                         'with urllib.request.urlopen(request, timeout=5) as response: assert b"done" in response.read()\n'
                         'print("SERVICE_REUSED")\nsys.exit(17)\n')
        agent.chmod(0o700)
        env['PATH'] = str(root) + os.pathsep + os.environ['PATH']
        result = command('run', 'claude', '--', '--literal', check=False)
        assert result.returncode == 17 and result.stdout == 'SERVICE_REUSED\n'
        env['TEST_WORKLOAD_PREFIX'] = '/_teamcode/workloads/background'
        background = command('run', 'claude', '--workload', 'background', '--', '--literal', check=False)
        assert background.returncode == 17 and background.stdout == 'SERVICE_REUSED\n'
        del env['TEST_WORKLOAD_PREFIX']
        interactive = command('run', 'claude', '--', '--literal', check=False)
        assert interactive.returncode == 17 and workloads[-3:] == [None, 'background', None]
        health(settings)

        # Stop while streaming: the already accepted response must finish before exit.
        release.clear()
        started.clear()
        with concurrent.futures.ThreadPoolExecutor() as pool:
            response = pool.submit(inference, settings)
            assert started.wait(5)
            stopping = pool.submit(command, 'service', 'stop')
            time.sleep(1)
            assert not response.done(), 'Active stream was interrupted during stop'
            release.set()
            assert b'"done":true' in response.result(timeout=10)
            stopping.result(timeout=15)
        health(settings, expected=False)
        command('service', 'start')
        health(settings)
        command('service', 'restart')
        health(settings)
        saved_config = config.read_bytes()
        config.unlink()
        command('service', 'restart')
        health(settings, expected=False)
        command('configure', '--server', origin, stdin=token + '\n')
        health(settings)

        # External edits are never overwritten/deleted, even when the label is recognizable.
        original_registration = registration.read_text()
        replace_registration(registration, original_registration + '\n# external edit\n')
        assert command('service', 'uninstall', check=False).returncode != 0
        assert registration.exists()
        replace_registration(registration, original_registration)
        saved = config.read_bytes()
        command('service', 'uninstall')
        health(settings, expected=False)
        assert config.read_bytes() == saved and saved != original_config
        assert not registration.exists() and not config.with_name('service.json').exists()
        command('service', 'uninstall')
        log = command('service', 'logs').stdout
        assert token not in log and settings['localToken'] not in log and len(log) < 65_536
        installed = False
        print('PASS: native service registration, idempotence, authenticated reuse, token reload, offline recovery, drain, restart and cleanup')
    finally:
        release.set()
        if installed:
            # Only this test's uniquely named registration and process may be stopped.
            if settings and Path(settings['registration']).exists() and 'original_registration' in locals():
                replace_registration(Path(settings['registration']), original_registration)
            command('service', 'uninstall', check=False)
        upstream.shutdown()
        upstream.server_close()
