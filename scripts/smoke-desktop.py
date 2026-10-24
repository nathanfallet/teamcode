#!/usr/bin/env python3
"""The desktop app's sessions on this device, end to end: its wiring (`DesktopSmokeTest`, on the JVM) with the native CLI
and the server jar on an isolated MySQL, with a stand-in Claude Code. The CLI signs in from a terminal; the app finds
it, shows its account, starts a session in a folder with a first message, pauses it and sees `teamcode logout`. No provider is contacted. Requires docker.

    python3 scripts/smoke-desktop.py --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe --jar app/build/libs/app-all.jar
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

FAKE_CLAUDE = r'''#!/usr/bin/env python3
import json, os, sys, uuid
with open(os.environ['FAKE_AGENT_LOG'], 'a') as log:
    log.write(json.dumps({'argv': sys.argv[1:], 'cwd': os.getcwd()}) + '\n')
arguments = sys.argv[1:]
conversation = arguments[arguments.index('--resume') + 1] if '--resume' in arguments else 'fake-' + str(uuid.uuid4())
def emit(value):
    sys.stdout.write(json.dumps(value) + '\n'); sys.stdout.flush()
emit({'type': 'system', 'subtype': 'init', 'session_id': conversation, 'model': 'fake'})
for line in sys.stdin:
    message = json.loads(line)
    if message.get('type') != 'user':
        continue
    text = message['message']['content'][0]['text']
    emit({'type': 'assistant', 'message': {'content': [{'type': 'text', 'text': 'echo: ' + text}]}, 'session_id': conversation})
    emit({'type': 'result', 'subtype': 'success', 'is_error': False, 'duration_ms': 5, 'num_turns': 1, 'total_cost_usd': 0, 'session_id': conversation})
'''

parser = argparse.ArgumentParser()
parser.add_argument('--binary', required=True)
parser.add_argument('--jar', required=True)
args = parser.parse_args()
binary, jar = str(Path(args.binary).resolve()), str(Path(args.jar).resolve())
repository = Path(__file__).resolve().parents[1]
name = 'teamcode-desktop-smoke-' + secrets.token_hex(6)
server = None
container_created = False


def run(arguments, **kwargs):
    result = subprocess.run(arguments, text=True, capture_output=True, **kwargs)
    if result.returncode != 0:
        raise RuntimeError(f'{arguments[1:3]} failed ({result.returncode}): {result.stderr[-2000:]}')
    return result


def wait(condition, what, seconds=60):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = condition()
        if value:
            return value
        time.sleep(.5)
    raise RuntimeError('Timed out waiting for ' + what)


with tempfile.TemporaryDirectory(prefix='teamcode-desktop-smoke-') as directory:
    root = Path(directory)
    password = secrets.token_urlsafe(32)
    database_password = secrets.token_urlsafe(32)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    origin = f'http://127.0.0.1:{port}'
    bin_dir = root / 'bin'
    bin_dir.mkdir()
    (bin_dir / 'claude').write_text(FAKE_CLAUDE)
    (bin_dir / 'claude').chmod(0o755)
    # The app finds the CLI on its PATH, as an installed `teamcode`.
    (bin_dir / 'teamcode').symlink_to(binary)
    home = root / 'home'
    home.mkdir()
    project = root / 'work' / 'mon-projet'
    project.mkdir(parents=True)
    env = dict(os.environ, TEAMCODE_MODE='server', TEAMCODE_PORT=str(port), TEAMCODE_PUBLIC_URL=origin,
               TEAMCODE_DATABASE_USER='teamcode', TEAMCODE_DATABASE_PASSWORD=database_password,
               TEAMCODE_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
               XDG_CONFIG_HOME=str(root / 'config'), PATH=f'{bin_dir}{os.pathsep}{os.environ["PATH"]}',
               FAKE_AGENT_LOG=str(root / 'agent.jsonl'), TEAMCODE_DISABLE_AUTOUPDATE='1')
    server_log = root / 'server.log'
    session_ids = []
    try:
        run(['docker', 'run', '-d', '--name', name, '--tmpfs', '/var/lib/mysql:rw,nosuid,size=1g', '-p', '127.0.0.1::3306',
             '-e', 'MYSQL_DATABASE=teamcode', '-e', 'MYSQL_USER=teamcode',
             '-e', f'MYSQL_PASSWORD={database_password}', '-e', f'MYSQL_ROOT_PASSWORD={database_password}', 'mysql:8.4'])
        container_created = True
        db_port = wait(lambda: (lambda b: b.rsplit(':', 1)[-1] if b and b.rsplit(':', 1)[-1].isdigit() else None)(
            run(['docker', 'port', name, '3306/tcp']).stdout.strip()), 'the MySQL port', 30)
        env['TEAMCODE_DATABASE_URL'] = f'jdbc:mysql://127.0.0.1:{db_port}/teamcode'
        wait(lambda: subprocess.run(['docker', 'exec', name, 'mysqladmin', 'ping', '-h', '127.0.0.1', '--silent'], capture_output=True).returncode == 0,
             'MySQL', 90)
        with server_log.open('w') as output:
            server = subprocess.Popen(['java', '-jar', jar], env=env, stdout=output, stderr=subprocess.STDOUT)

        def healthy():
            if server.poll() is not None:
                raise RuntimeError('The server exited; see ' + str(server_log))
            try:
                with urllib.request.urlopen(origin + '/health', timeout=1) as response:
                    return response.status == 200
            except (OSError, urllib.error.URLError):
                return False
        wait(healthy, 'the server', 90)

        initial = re.search(r'TeamCode temporary administrator password: (\S+)', server_log.read_text()).group(1)
        run([binary, 'login', '--server', origin, '--username', 'admin', '--password-stdin', '--device', 'Smoke desktop'],
            env=env, input=f'{initial}\n{password}\n{password}\n', timeout=60)
        token = json.loads((root / 'config/teamcode/config.json').read_text())['accessToken']
        request = urllib.request.Request(origin + '/api/v1/cloud/runtime', headers={'Authorization': 'Bearer ' + token})
        with urllib.request.urlopen(request, timeout=20) as response:
            runtime = json.loads(response.read())
        body = dict(runtime, enabled=True, runnerImage=runtime.get('runnerImage') or 'registry.example/teamcode-runner:1', serverUrl='https://teamcode.example')
        request = urllib.request.Request(origin + '/api/v1/cloud/runtime', json.dumps(body).encode(),
                                         {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}, method='PUT')
        urllib.request.urlopen(request, timeout=20).close()

        # The app's process environment: HOME is a fresh one, so nothing of this machine's own TeamCode is read.
        test_env = dict(env, HOME=str(home), TEAMCODE_DESKTOP_SMOKE_PROJECT=str(project))
        result = subprocess.run([str(repository / 'gradlew'), '--no-daemon', '-q', ':companion-desktop:test', '--tests', '*DesktopSmokeTest', '--rerun'],
                                env=test_env, cwd=repository, text=True, capture_output=True, timeout=900)
        session_ids = [path.stem for path in (root / 'config/teamcode/remote').glob('*.json') if '.' not in path.stem]
        if result.returncode != 0:
            raise RuntimeError('DesktopSmokeTest failed:\n' + result.stdout[-4000:] + result.stderr[-4000:])
        report = next(repository.glob('companion-desktop/build/test-results/test/*DesktopSmokeTest.xml')).read_text()
        assert 'Desktop smoke passed' in report and 'skipped="0"' in report, 'The smoke test did not run'
        records = root / 'config/teamcode/remote'
        for private in [server_log, *records.glob('*')]:
            text = private.read_text()
            assert token not in text and password not in text and (private == server_log or initial not in text), f'A credential reached {private.name}'
            assert private == server_log or 'hello' not in text, f'A prompt reached {private.name}'
        print('Desktop smoke passed: the CLI account from a terminal login, On this device with a first message answered, '
              'a pause from the app, teamcode logout seen by the app.')
    finally:
        # Hosts this script's run started and a failure left running: their exact PIDs, found by the session id.
        for session_id in session_ids:
            for pid in subprocess.run(['pgrep', '-f', f'remote host {session_id}'], capture_output=True, text=True).stdout.split():
                subprocess.run(['kill', pid], capture_output=True)
        if server is not None:
            server.terminate()
            try:
                server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                server.kill()  # The exact server this script started.
        if container_created:
            subprocess.run(['docker', 'rm', '-f', '-v', name], capture_output=True)
