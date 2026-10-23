#!/usr/bin/env python3
"""Remote sessions end to end: the native CLI against the server jar and an isolated MySQL, with a stand-in Claude Code.

`teamcode remote start --detach`, `list`, `stop`, `resume --detach`, `teamcode attach` (following, sending, waking a
paused session, which starts its host in the background) and `cloud list` columns. The stand-in agent speaks Claude
Code's stream-json and answers `echo: <message>`; no provider is contacted. Requires docker.

    python3 scripts/smoke-remote.py --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe --jar app/build/libs/app-all.jar
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
import sys
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
name = 'teamcode-remote-smoke-' + secrets.token_hex(6)
server = None
container_created = False
session_id = None


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


with tempfile.TemporaryDirectory(prefix='teamcode-remote-smoke-') as directory:
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
    agent_log = root / 'agent.jsonl'
    env = dict(os.environ, TEAMCODE_MODE='server', TEAMCODE_PORT=str(port), TEAMCODE_PUBLIC_URL=origin,
               TEAMCODE_DATABASE_USER='teamcode', TEAMCODE_DATABASE_PASSWORD=database_password,
               TEAMCODE_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
               XDG_CONFIG_HOME=str(root / 'config'), PATH=f'{bin_dir}{os.pathsep}{os.environ["PATH"]}',
               FAKE_AGENT_LOG=str(agent_log), TEAMCODE_DISABLE_AUTOUPDATE='1')
    server_log = root / 'server.log'
    project = root / 'work' / 'mon-projet'
    project.mkdir(parents=True)
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
        run([binary, 'login', '--server', origin, '--username', 'admin', '--password-stdin', '--device', 'Smoke laptop'],
            env=env, input=f'{initial}\n{password}\n{password}\n', timeout=60)
        token = json.loads((root / 'config/teamcode/config.json').read_text())['accessToken']

        def api(path, data=None, method=None):
            request = urllib.request.Request(origin + path, json.dumps(data).encode() if data is not None else None,
                                             {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}, method=method)
            try:
                with urllib.request.urlopen(request, timeout=20) as response:
                    return json.loads(response.read() or b'null')
            except urllib.error.HTTPError as error:
                raise RuntimeError(f'{path}: HTTP {error.code} {error.read()[:500]}') from None

        # Remote sessions are a cloud feature: the runtime is turned on (no pod ever starts for a remote session).
        runtime = api('/api/v1/cloud/runtime')
        api('/api/v1/cloud/runtime', dict(runtime, enabled=True, runnerImage=runtime.get('runnerImage') or 'registry.example/teamcode-runner:1', serverUrl='https://teamcode.example'), 'PUT')

        def cli(*arguments, stdin=None, timeout=60):
            return run([binary, *arguments], env=env, input=stdin, timeout=timeout, cwd=str(root))

        def session(id):
            return api(f'/api/v1/cloud/sessions/{id}')

        def texts(id):
            events = api(f'/api/v1/cloud/sessions/{id}/events?after=0&limit=200')['events']
            return [event['data'].get('text') for event in events if event['type'] == 'message.agent']

        def online(id, epoch):
            current = session(id)
            return current['host']['online'] and current['currentEpoch'] > epoch and current

        def launches():
            return [json.loads(line) for line in agent_log.read_text().splitlines()] if agent_log.exists() else []

        # 1. Start in the background with a first message.
        started = cli('remote', 'start', str(project), '--detach', '--json', '--message', 'hello')
        created = json.loads(started.stdout)
        id = session_id = created['id']
        assert created['kind'] == 'remote', created
        hosted = wait(lambda: online(id, 0), 'the first host')
        assert hosted['host']['directoryName'] == 'mon-projet' and hosted['host']['permissionMode'] == 'ask', hosted['host']
        assert str(root) not in json.dumps(hosted), 'The full path reached the server'
        wait(lambda: 'echo: hello' in texts(id), 'the first answer')
        first = launches()[0]
        assert '--dangerously-skip-permissions' not in first['argv'] and '--permission-prompt-tool' in first['argv'], first
        assert Path(first['cwd']).resolve() == project.resolve(), first

        # 2. Listed on this device, with its full local path; cloud list carries the kind and the host.
        listed = cli('remote', 'list').stdout
        assert id in listed and 'online' in listed and str(project.resolve()) in listed, listed
        row = next(line for line in cli('cloud', 'list').stdout.splitlines() if line.startswith(id))
        # Columns are tab-separated; the terminal layer may expand tabs.
        assert re.search(r'\sremote\s+on Smoke laptop \(\w+\), online$', row), row
        assert 'Kind:         remote' in cli('cloud', 'show', id).stdout

        # 3. Attach: the transcript, then a message from this terminal.
        attached = cli('attach', id, stdin='second\n', timeout=60)
        assert 'echo: hello' in attached.stdout, attached.stdout
        wait(lambda: 'echo: second' in texts(id), 'the attached message')

        # 4. Stop: the host checkpoints and exits.
        cli('remote', 'stop', id)
        paused = wait(lambda: (lambda s: s['state'] == 'paused' and not s['host']['online'] and s)(session(id)), 'the pause')
        wait(lambda: subprocess.run(['pgrep', '-f', f'remote host {id}'], capture_output=True).returncode != 0, 'the host to exit', 30)

        # 5. Resume in the background: a new epoch; resuming again while it runs does nothing.
        cli('remote', 'resume', id, '--detach')
        resumed = wait(lambda: online(id, paused['currentEpoch']), 'the resumed host')
        assert 'already hosted' in cli('remote', 'resume', id, '--detach').stderr

        # 6. A message from an attached terminal wakes a paused session and starts its host here.
        cli('remote', 'stop', id)
        paused = wait(lambda: (lambda s: s['state'] == 'paused' and not s['host']['online'] and s)(session(id)), 'the second pause')
        wait(lambda: subprocess.run(['pgrep', '-f', f'remote host {id}'], capture_output=True).returncode != 0, 'the second host to exit', 30)
        waking = subprocess.Popen([binary, 'attach', id], env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, cwd=str(root))
        time.sleep(3)
        waking.stdin.write('third\n'); waking.stdin.flush()
        wait(lambda: online(id, paused['currentEpoch']), 'the host started by the wake', 90)
        wait(lambda: 'echo: third' in texts(id), 'the answer after the wake', 60)
        # The agent starts with the first message of an execution, resuming the conversation kept beside config.json.
        argv = launches()[-1]['argv']
        assert len(launches()) == 2 and '--resume' in argv and argv[argv.index('--resume') + 1].startswith('fake-'), launches()
        waking.stdin.write('/detach\n'); waking.stdin.close()
        out, err = waking.communicate(timeout=30)
        assert waking.returncode == 0 and 'starts it here' in err, err

        # 7. Delete; the next list drops this device's record.
        cli('remote', 'stop', id)
        wait(lambda: (lambda s: s['state'] == 'paused' and not s['host']['online'])(session(id)), 'the last pause')
        wait(lambda: subprocess.run(['pgrep', '-f', f'remote host {id}'], capture_output=True).returncode != 0, 'the last host to exit', 30)
        records = root / 'config/teamcode/remote'
        host_log = (records / f'{id}.log').read_text()
        assert 'Hosting mon-projet' in host_log and token not in host_log and password not in host_log and 'hello' not in host_log, 'The host log'
        cli('cloud', 'delete', id, '--yes')
        wait(lambda: session(id)['state'] == 'terminated', 'the deletion')
        cli('remote', 'list')
        assert not (records / f'{id}.json').exists(), 'The record of a deleted session was kept'
        assert project.exists() and not any(project.iterdir()), 'The directory was touched'

        # The server prints the first-run temporary password once, by design; nothing else may carry a credential.
        for private in [server_log, *records.glob('*')]:
            text = private.read_text()
            assert token not in text and password not in text and (private == server_log or initial not in text), f'A credential reached {private.name}'
        print('Remote smoke passed: start --detach with a first message (folder name only on the server, permissions asked), '
              'list and cloud list columns, attach and send, stop, resume --detach with the same conversation, wake from attach '
              'starting the host, delete and record pruning.')
    finally:
        # Hosts this script started and a failure left running: their exact PIDs, found by the session id.
        if session_id is not None:
            found = subprocess.run(['pgrep', '-f', f'remote host {session_id}'], capture_output=True, text=True).stdout.split()
            for pid in found:
                subprocess.run(['kill', pid], capture_output=True)
        if server is not None:
            server.terminate()
            try:
                server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                server.kill()  # The exact server this script started.
        if container_created:
            subprocess.run(['docker', 'rm', '-f', '-v', name], capture_output=True)
