#!/usr/bin/env python3
"""Exercise actual native installation/update/rollback using two already signed local bundles."""
import argparse
import base64
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import platform
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import secrets
import signal
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--first-bundle', type=Path, required=True)
    parser.add_argument('--second-bundle', type=Path, required=True)
    parser.add_argument('--service', action='store_true', help='Also exercise the actual owned OS service; Linux needs a user manager.')
    parser.add_argument('--notification-bundle', type=Path, help='Optionally verify service notifications from fresh signed newer metadata without network checks.')
    args = parser.parse_args()
    binary = str(args.binary.resolve())
    target = ('windows-x64' if os.name == 'nt' else
              ('macos-' if platform.system() == 'Darwin' else 'linux-') +
              ('arm64' if platform.machine().lower() in ('arm64', 'aarch64') else 'x64'))
    first, second = args.first_bundle.resolve(), args.second_bundle.resolve()
    manifests = [json.loads((directory / 'manifest.json').read_bytes()) for directory in (first, second)]
    digests = [hashlib.sha256((directory / 'manifest.json').read_bytes()).hexdigest() for directory in (first, second)]
    assert digests[0] != digests[1], 'Use distinct signed manifests to exercise selection and rollback'
    inputs = {path: hashlib.sha256(path.read_bytes()).hexdigest() for directory, manifest in zip((first, second), manifests)
              for path in (directory / 'manifest.json', directory / 'manifest.sig', directory / manifest['assets'][target]['fileName'])}

    with tempfile.TemporaryDirectory(prefix='teamcode-installation-') as directory:
        root = Path(directory) / "application é space's"
        install_root = root / 'installation'
        config_root = root / 'configuration'
        home = root / 'home'
        # Installation links the command under HOME: never let this smoke touch the real one.
        # This smoke registers its own service on a free port; the automatic setup is smoke-service-setup.py's subject.
        env = dict(os.environ, HOME=str(home), XDG_CONFIG_HOME=str(config_root), TEAMCODE_DISABLE_AUTOUPDATE='1', TEAMCODE_NO_SERVICE='1')
        env['PATH'] = os.pathsep.join(entry for entry in env['PATH'].split(os.pathsep) if Path(entry) != home / '.local' / 'bin')
        root.mkdir()
        home.mkdir()
        launcher = install_root / 'bin' / ('teamcode.exe' if os.name == 'nt' else 'teamcode')

        def run(executable, arguments, error=None, input_text=None):
            io = {'stdin': subprocess.DEVNULL} if input_text is None else {'input': input_text}
            result = subprocess.run([str(executable), *arguments], env=env, **io,
                                    capture_output=True, text=True, timeout=45)
            output = result.stdout + result.stderr
            if error:
                assert result.returncode != 0 and error in output, f'Expected {error}; exit={result.returncode}: {output}'
            else:
                assert result.returncode == 0, f'Native installation operation failed: {output}'
            return output

        def state(): return json.loads((install_root / 'current.json').read_bytes())
        def selected(index):
            assert run(launcher, ['version']).strip() == 'TeamCode ' + manifests[index]['version']
            assert state()['current']['manifestSha256'] == digests[index]
            assert not (install_root / 'transaction.json').exists(), 'A completed operation left a pending journal'

        if os.name == 'nt':
            import winreg
            def user_path():
                with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Environment') as key:
                    try: return winreg.QueryValueEx(key, 'Path')
                    except FileNotFoundError: return None
            def launcher_directories(): return [entry.rstrip('\\').lower() for entry in (user_path() or ('',))[0].split(';')]
            original_user_path = user_path()
            def restore_user_path():
                # The Windows install edits the real HKCU PATH of the CI user: put it back exactly.
                with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Environment', 0, winreg.KEY_SET_VALUE) as key:
                    if original_user_path is None: winreg.DeleteValue(key, 'Path')
                    else: winreg.SetValueEx(key, 'Path', 0, original_user_path[1], original_user_path[0])
            import atexit; atexit.register(restore_user_path)
        installed = run(binary, ['install', '--bundle', str(first), '--root', str(install_root)])
        selected(0)
        link_directory = home / '.local' / 'bin'
        command_directory = launcher.parent if os.name == 'nt' else link_directory
        def by_name():
            # Resolved through PATH like an interactive shell would; never through the launcher's full path.
            found = shutil.which('teamcode', path=str(command_directory))
            assert found, 'teamcode is not found by name'
            result = subprocess.run([found, 'version'], env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=45)
            assert result.returncode == 0, result.stdout + result.stderr
            return result.stdout.strip()
        if os.name == 'nt':
            assert str(launcher.parent).lower() in launcher_directories(), 'Install did not add the launcher directory to the user PATH'
            assert launcher_directories().count(str(launcher.parent).lower()) == 1
        else:
            link = link_directory / 'teamcode'
            assert link.is_symlink() and os.path.realpath(link) == os.path.realpath(launcher), 'The command must link to the stable launcher'
            assert 'export PATH="$HOME/.local/bin:$PATH"' in installed, 'Missing the exact PATH line: ' + installed
        assert by_name() == 'TeamCode ' + manifests[0]['version']
        original_launcher = hashlib.sha256(launcher.read_bytes()).hexdigest()
        before_settings = state()
        def notifications(mode):
            return run(binary, ['update', '--root', str(install_root), '--notifications', mode])
        assert 'notifications: on.' in notifications('status')
        notifications('off')
        assert 'notifications: off.' in notifications('status')
        notifications('on')
        assert 'notifications: on.' in notifications('status')
        # OS managers do not inherit the invoking shell's environment; persist the CI opt-out.
        notifications('off')
        assert state() == before_settings
        run(binary, ['install', '--bundle', str(first), '--root', str(install_root)])
        selected(0)
        if os.name != 'nt':
            assert os.path.realpath(link) == os.path.realpath(launcher) and len(list(link_directory.iterdir())) == 1, 'Re-running install changed the command link'
        run(launcher, ['update', '--bundle', str(second)])
        selected(1)
        assert by_name() == 'TeamCode ' + manifests[1]['version'], 'The command did not follow the update'
        assert state()['previous']['manifestSha256'] == digests[0]
        assert hashlib.sha256(launcher.read_bytes()).hexdigest() == original_launcher, 'The running launcher was replaced'
        run(launcher, ['update', '--rollback'])
        selected(0)
        assert by_name() == 'TeamCode ' + manifests[0]['version'], 'The command did not follow the rollback'
        assert state()['previous']['manifestSha256'] == digests[1]
        for digest in digests: assert (install_root / 'versions' / digest).is_dir(), 'A previous payload was deleted'

        # Reconstruct the durable state left by an interrupted selection before its startup commit.
        before = state()
        after = dict(before, current=before['previous'], previous=before['current'])
        (install_root / 'transaction.json').write_text(json.dumps({'schemaVersion': 1, 'before': before, 'after': after,
                                                                  'serviceConfig': None, 'restartService': False}), encoding='utf-8')
        (install_root / 'current.json').write_text(json.dumps(after), encoding='utf-8')
        (install_root / 'transaction.json').chmod(0o600)
        selected(0)
        assert state() == before, 'Interrupted transaction did not restore the exact previous selection'

        corrupt = root / 'corrupt bundle'
        corrupt.mkdir()
        for name in ('manifest.json', 'manifest.sig'): shutil.copyfile(first / name, corrupt / name)
        (corrupt / manifests[0]['assets'][target]['fileName']).write_bytes(b'UNTRUSTED_EXECUTABLE_MUST_NEVER_RUN')
        rejected_root = root / 'rejected installation'
        run(binary, ['install', '--bundle', str(corrupt), '--root', str(rejected_root)], error='release_binary_invalid')
        assert not (rejected_root / 'current.json').exists()
        selected(0)
        assert state() == before

        if os.name != 'nt':
            # A command name owned by something else is never replaced, and the refusal says why.
            other_home = root / 'other home'
            (other_home / '.local' / 'bin').mkdir(parents=True)
            occupied = other_home / '.local' / 'bin' / 'teamcode'
            occupied.write_text('not ours')
            result = subprocess.run([str(binary), 'install', '--bundle', str(first), '--root', str(root / 'other installation')],
                                    env=dict(env, HOME=str(other_home)), stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=45)
            assert result.returncode != 0 and 'already exists and was not created by TeamCode' in result.stderr + result.stdout, result.stdout + result.stderr
            assert occupied.read_text() == 'not ours' and not occupied.is_symlink()
            result = subprocess.run([str(binary), 'install', '--no-path', '--bundle', str(first), '--root', str(root / 'other installation')],
                                    env=dict(env, HOME=str(other_home)), stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=45)
            assert result.returncode == 0 and occupied.read_text() == 'not ours', result.stdout + result.stderr

        foreign = root / 'unrelated directory'
        foreign.mkdir()
        (foreign / 'keep.txt').write_bytes(b'preserve this user file')
        run(binary, ['install', '--bundle', str(first), '--root', str(foreign)], error='release_directory_not_owned')
        assert (foreign / 'keep.txt').read_bytes() == b'preserve this user file'
        assert sorted(path.name for path in foreign.iterdir()) == ['keep.txt']
        assert not config_root.exists(), 'Installation without login created team configuration'
        if args.service:
            token = secrets.token_hex(32)
            online = True
            api_version = 1
            calls = []
            stream_started = threading.Event()
            stream_release = threading.Event()
            class Handler(BaseHTTPRequestHandler):
                def log_message(self, *_): pass
                def do_GET(self):
                    calls.append(self.path)
                    if not online or self.headers.get('Authorization') != 'Bearer ' + token:
                        self.send_error(503); return
                    body = ({'id': 'fixture-member', 'username': 'fixture', 'role': 'MEMBER', 'disabled': False,
                             'passwordChangeRequired': False} if self.path.endswith('/me') else
                            {'apiVersion': api_version, 'serverVersion': 'fixture', 'mode': 'fixture', 'features': []})
                    data = json.dumps(body).encode()
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.send_header('Content-Length', str(len(data)))
                    self.end_headers(); self.wfile.write(data)
                def do_POST(self):
                    self.rfile.read(int(self.headers.get('Content-Length', '0')))
                    if not online or self.headers.get('Authorization') != 'Bearer ' + token:
                        self.send_error(503); return
                    calls.append(self.path)
                    self.send_response(200)
                    self.send_header('Content-Type', 'text/event-stream')
                    self.end_headers()
                    self.wfile.write(b'data: {"start":true}\n\n'); self.wfile.flush()
                    stream_started.set()
                    assert stream_release.wait(180), 'Test did not release its synthetic active stream'
                    self.wfile.write(b'data: {"done":true}\n\n'); self.wfile.flush()
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            worker = threading.Thread(target=server.serve_forever, daemon=True)
            worker.start()
            origin = f'http://127.0.0.1:{server.server_port}'
            agent = None
            release = root / 'release-quiet-agent'
            installed_service = False
            try:
                run(launcher, ['configure', '--server', origin], input_text=token + '\n')
                configuration = config_root / 'teamcode/config.json'
                saved_configuration = configuration.read_bytes()
                with socket.socket() as listener:
                    listener.bind(('127.0.0.1', 0)); local_port = listener.getsockname()[1]
                installed_service = True
                notification_cache = None
                if args.notification_bundle:
                    notification_manifest = (args.notification_bundle / 'manifest.json').read_bytes()
                    notification_signature = (args.notification_bundle / 'manifest.sig').read_bytes()
                    notice_version = json.loads(notification_manifest)['version']
                    # Native install roots enforce a private inherited Windows DACL as well.
                    notification_cache = json.dumps({'schemaVersion': 1, 'checkedAtMillis': int(time.time() * 1000),
                        'manifest': base64.b64encode(notification_manifest).decode('ascii'),
                        'signature': base64.b64encode(notification_signature).decode('ascii')}).encode()
                    cache_path = install_root / 'update-check.json'
                    cache_path.write_bytes(notification_cache)
                    cache_path.chmod(0o600)
                    notifications('on')
                run(launcher, ['service', 'install', '--port', str(local_port)])
                settings_path = config_root / 'teamcode/service.json'
                saved_settings = settings_path.read_bytes()
                settings = json.loads(saved_settings)
                assert Path(settings['executable']).resolve() == launcher.resolve(), 'Service registered a versioned payload instead of the stable launcher'
                def identity(version):
                    for _ in range(80):
                        try:
                            request = urllib.request.Request(f'http://127.0.0.1:{local_port}/_teamcode/service',
                                headers={'Authorization': 'Bearer ' + settings['localToken']})
                            with urllib.request.urlopen(request, timeout=1) as response: body = json.load(response)
                            if body.get('version') == version and body.get('id') == settings['id']: return
                        except OSError: pass
                        time.sleep(.1)
                    raise AssertionError('Managed service did not start the selected version')
                def start_marker(previous=None):
                    # A new startup log proves restart even for two manifests of one build.
                    for _ in range(50):
                        try:
                            lines = settings_path.with_name('service.log').read_text(encoding='utf-8').splitlines()
                            marker = next((line for line in lines if 'Local proxy is listening.' in line), None)
                            if marker is not None and marker != previous: return marker
                        except OSError: pass
                        time.sleep(.1)
                    raise AssertionError('No fresh owned-service startup was observed')
                identity(manifests[0]['version'])
                first_start = start_marker()
                if notification_cache is not None:
                    for _ in range(80):
                        logs = settings_path.with_name('service.log').read_text(encoding='utf-8')
                        if f'TeamCode {notice_version} is available.' in logs: break
                        time.sleep(.1)
                    else: raise AssertionError('The actual managed service did not report its authenticated cached release')
                    assert cache_path.read_bytes() == notification_cache, 'A fresh cached check made a release fetch or rewrote metadata'
                    notifications('off')
                api_version = 2
                try: run(launcher, ['update', '--bundle', str(second)], error='release_candidate_incompatible')
                finally: api_version = 1
                selected(0); identity(manifests[0]['version'])
                assert start_marker() == first_start, 'Incompatible update restarted the service'
                # Stop only our updater at the durable journal while its own service drains.
                # This proves OS lock release/recovery after a real process death, not a fake PID.
                def inference():
                    request = urllib.request.Request(f'http://127.0.0.1:{local_port}/v1/messages', b'{"model":"fixture"}',
                        {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + settings['localToken']})
                    with urllib.request.urlopen(request, timeout=120) as response: return response.read()
                before_interruption = state()
                updater = None
                with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                    streaming = pool.submit(inference)
                    try:
                        assert stream_started.wait(10), 'Synthetic stream did not reach the owned service'
                        updater = subprocess.Popen([str(launcher), 'update', '--bundle', str(second)], env=env,
                            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                        for _ in range(400):
                            assert updater.poll() is None, 'Updater exited before its interruption point'
                            if (install_root / 'transaction.json').exists(): break
                            time.sleep(.05)
                        else: raise AssertionError('Updater did not persist its recovery journal')
                        # Wait for actual cooperative stop/drain, leaving its request unfinished.
                        for _ in range(600):
                            if 'Stopping: draining existing requests' in settings_path.with_name('service.log').read_text(encoding='utf-8'): break
                            assert updater.poll() is None, 'Updater exited before service drainage'
                            time.sleep(.1)
                        else: raise AssertionError('Owned service did not enter its drain phase')
                        assert not streaming.done(), 'Update interrupted the active response before its commit'
                        assert updater.poll() is None, 'Updater exited before the owned process interruption'
                        updater.kill()  # Exact PID created above; never find/stop a process by name.
                        # Wait for that PID, not pipe EOF: a draining manager child can inherit stdio.
                        updater.wait(timeout=10)
                        assert updater.returncode != 0
                        if os.name != 'nt': assert updater.returncode == -signal.SIGKILL
                        assert (install_root / 'transaction.json').exists(), 'Interrupted update lost its journal'
                    finally:
                        stream_release.set()
                        if updater is not None and updater.poll() is None:
                            updater.kill(); updater.wait(timeout=10)
                    assert b'"done":true' in streaming.result(timeout=15), 'Updater death lost the active response'
                    updater.communicate(timeout=15)
                selected(0); identity(manifests[0]['version'])
                assert state() == before_interruption, 'Real interruption did not restore the exact selection'
                first_start = start_marker(first_start)
                agents = root / 'fake agents'; agents.mkdir()
                ready = root / 'quiet-agent-ready'
                if os.name == 'nt':
                    # Match TeamClaude's supported npm layout; do not execute a cmd shim.
                    stub = agents / 'node_modules/@anthropic-ai/claude-code/cli.js'
                    stub.parent.mkdir(parents=True)
                    stub.write_text('const fs = require("fs");\n'
                                    'fs.writeFileSync(process.env.TEAMCODE_INSTALLATION_READY, "");\n'
                                    'const timer = setInterval(() => {\n'
                                    'if (fs.existsSync(process.env.TEAMCODE_INSTALLATION_RELEASE)) clearInterval(timer);\n'
                                    '}, 50);\n', encoding='utf-8')
                    (agents / 'claude.cmd').write_text('@echo THIS_SHIM_MUST_NOT_RUN\n', encoding='ascii')
                else:
                    stub = agents / 'claude'
                    stub.write_text('#!' + sys.executable + '\nimport os,pathlib,time\n'
                                    'pathlib.Path(os.environ["TEAMCODE_INSTALLATION_READY"]).touch()\n'
                                    'while not pathlib.Path(os.environ["TEAMCODE_INSTALLATION_RELEASE"]).exists(): time.sleep(.05)\n')
                    stub.chmod(0o700)
                agent_env = dict(env, PATH=str(agents) + os.pathsep + os.environ['PATH'],
                    TEAMCODE_INSTALLATION_READY=str(ready), TEAMCODE_INSTALLATION_RELEASE=str(release))
                agent = subprocess.Popen([str(launcher), 'run', 'claude', '--no-guidance'], env=agent_env,
                                         stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                for _ in range(200):
                    if ready.exists(): break
                    assert agent.poll() is None, 'Synthetic quiet agent exited before taking its lease'
                    time.sleep(.05)
                else: raise AssertionError('Synthetic quiet agent did not start')
                # The update selects the new payload at once; the quiet agent keeps its executable and the
                # service it depends on, which restarts only when that last session ends.
                deferred = run(launcher, ['update', '--bundle', str(second)])
                assert '1 running agent session keeps running on ' + manifests[0]['version'] in deferred, deferred
                assert 'restarts on ' + manifests[1]['version'] + ' when they end' in deferred, deferred
                selected(1); identity(manifests[0]['version'])
                assert agent.poll() is None, 'Update terminated an active agent'
                assert start_marker() == first_start, 'An update restarted the service under an active agent'
                assert 'restarts on ' + manifests[1]['version'] in run(launcher, ['update', '--bundle', str(second)])
                assert start_marker() == first_start, 'A repeated update restarted the service under an active agent'
                release.touch()
                agent.communicate(timeout=90)
                assert agent.returncode == 0
                identity(manifests[1]['version'])
                second_start = start_marker(first_start)
                assert not list((install_root / 'sessions').glob('*.lock')), 'An ended session left its record'
                assert json.loads((install_root / 'service.json').read_bytes())['version']['manifestSha256'] == digests[1]
                online = False
                before_offline = len(calls)
                run(launcher, ['update', '--rollback'])
                selected(0); identity(manifests[0]['version'])
                start_marker(second_start)
                assert len(calls) == before_offline, 'Offline rollback contacted the central server'
                # A stopped service must stay stopped when the selected payload changes.
                run(launcher, ['service', 'stop'])
                run(launcher, ['update', '--rollback'])
                selected(1)
                try:
                    urllib.request.urlopen(f'http://127.0.0.1:{local_port}/_teamcode/service', timeout=2)
                    raise AssertionError('Rollback unexpectedly started a stopped service')
                except urllib.error.HTTPError as error:
                    raise AssertionError('Stopped service still accepted an HTTP connection') from error
                except OSError: pass
                run(launcher, ['service', 'start'])
                identity(manifests[1]['version'])
                run(launcher, ['update', '--rollback'])
                selected(0); identity(manifests[0]['version'])
                assert len(calls) == before_offline, 'Stopped-service rollback contacted the central server'
                assert configuration.read_bytes() == saved_configuration
                assert settings_path.read_bytes() == saved_settings
            finally:
                stream_release.set()
                release.touch()
                if agent is not None and agent.poll() is None:
                    try: agent.communicate(timeout=10)
                    except subprocess.TimeoutExpired: agent.terminate(); agent.communicate(timeout=5)
                try:
                    if installed_service: run(launcher, ['service', 'uninstall'])
                finally:
                    server.shutdown(); server.server_close(); worker.join(timeout=3)
        if os.name != 'nt':
            assert install_root.stat().st_mode & 0o077 == 0
            assert launcher.stat().st_mode & 0o777 == 0o700
            record = install_root / 'current.json'
            record.chmod(0o644)
            try: run(launcher, ['version'], error='release_private_path_invalid')
            finally: record.chmod(0o600)
            selected(0)
        assert hashlib.sha256(launcher.read_bytes()).hexdigest() == original_launcher
        for path, digest in inputs.items(): assert hashlib.sha256(path.read_bytes()).hexdigest() == digest, 'Source bundle changed'
    extra = ', actual service restart/version checks, real updater interruption with drained-response recovery and quiet-agent protection' if args.service else ''
    print('PASS: native signed installation, immutable launcher, update, offline rollback, interrupted-selection recovery, integrity refusal, private Unicode paths and preserved input/configuration' + extra + '.')


if __name__ == '__main__':
    main()
