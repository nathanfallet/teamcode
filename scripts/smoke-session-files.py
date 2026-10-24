#!/usr/bin/env python3
"""Files sent to a session, end to end, read by the real Claude Code client.

The native CLI and the server jar run against an isolated MySQL and an S3-compatible bucket (Versity S3 Gateway), both in
docker. A team-wide bucket is saved and tested, a remote session is hosted by the CLI with the actual Claude executable, and
`teamcode attach` sends `/attach NOTE "..."`. The host downloads the file, writes it outside the working directory and puts
its path in the message; the real Claude reads that path with its Read tool, without any permission request. Inference is a
deterministic local fixture, so no provider is contacted and no subscription is used: it answers the first request with a
Read tool call on the path it finds in the message, and the script checks that the tool result carried the file's content.

    python3 scripts/smoke-session-files.py --binary cli/build/bin/macosArm64/debugExecutable/teamcode.kexe \\
        --jar app/build/libs/app-all.jar --claude /absolute/path/to/the/actual/claude/executable

Requires docker and a real Claude Code executable (not a shim). Opt-in, like the other real-client fixtures: pull-request CI
has no Claude executable.
"""
import argparse
import base64
import datetime
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--binary', required=True)
parser.add_argument('--jar', required=True)
parser.add_argument('--claude', required=True, help='The actual Claude executable, not a terminal integration shim')
args = parser.parse_args()
binary, jar, claude = str(Path(args.binary).resolve()), str(Path(args.jar).resolve()), str(Path(args.claude).resolve())
assert os.name == 'posix', 'This real-client fixture runs on macOS/Linux'

MARKER = 'FILE_CONTENT_MARKER_' + secrets.token_hex(8)
S3_USER, S3_PASSWORD, BUCKET = 'teamcode-smoke', secrets.token_urlsafe(24), 'artifacts'
suffix = secrets.token_hex(6)
mysql_name, s3_name = 'teamcode-files-smoke-mysql-' + suffix, 'teamcode-files-smoke-s3-' + suffix
containers = []
server = None
inference = None
session_id = None
state = {'requests': 0, 'read_path': None, 'tool_result': False, 'permission_asked': False, 'errors': []}
lock = threading.Lock()


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


def sign(key, message):
    return hmac.new(key, message.encode(), hashlib.sha256).digest()


def create_bucket(endpoint, certificate):
    """CreateBucket is not part of TeamCode: a bucket always exists before it is saved. SigV4, header form."""
    host = endpoint.split('//')[1]
    now = datetime.datetime.now(datetime.timezone.utc)
    stamp, day = now.strftime('%Y%m%dT%H%M%SZ'), now.strftime('%Y%m%d')
    empty = hashlib.sha256(b'').hexdigest()
    headers = f'host:{host}\nx-amz-content-sha256:{empty}\nx-amz-date:{stamp}\n'
    request = f'PUT\n/{BUCKET}\n\n{headers}\nhost;x-amz-content-sha256;x-amz-date\n{empty}'
    scope = f'{day}/us-east-1/s3/aws4_request'
    to_sign = f'AWS4-HMAC-SHA256\n{stamp}\n{scope}\n{hashlib.sha256(request.encode()).hexdigest()}'
    key = sign(sign(sign(sign(('AWS4' + S3_PASSWORD).encode(), day), 'us-east-1'), 's3'), 'aws4_request')
    signature = hmac.new(key, to_sign.encode(), hashlib.sha256).hexdigest()
    authorization = (f'AWS4-HMAC-SHA256 Credential={S3_USER}/{scope}, SignedHeaders=host;x-amz-content-sha256;x-amz-date, '
                     f'Signature={signature}')
    put = urllib.request.Request(f'{endpoint}/{BUCKET}', method='PUT', headers={
        'x-amz-content-sha256': empty, 'x-amz-date': stamp, 'Authorization': authorization})
    with urllib.request.urlopen(put, timeout=10, context=ssl.create_default_context(cafile=str(certificate))) as response:
        assert response.status == 200, response.status


class Inference(BaseHTTPRequestHandler):
    """Anthropic Messages fixture: Read the attached file's path, then say so. Stateless, so side requests do no harm."""
    def log_message(self, *_): pass

    def do_POST(self):
        try:
            body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))) or b'{}')
            route = self.path.split('?')[0]
            if route == '/v1/messages/count_tokens':
                self.reply({'input_tokens': 100}); return
            assert route == '/v1/messages', 'Unexpected fixture path ' + self.path
            serialized = json.dumps(body)
            with lock:
                state['requests'] += 1
                result = MARKER in serialized and '"tool_result"' in serialized
                found = re.search(r'\[Attached file: (/[^\]]*?) \(text/plain, \d+ bytes\)\]', serialized)
                if result:
                    state['tool_result'] = True
                elif found and state['read_path'] is None:
                    state['read_path'] = found.group(1)
                path = state['read_path']
            if result:
                block, reason = {'type': 'text', 'text': 'FILE_READ_OK'}, 'end_turn'
            elif path and not state['tool_result']:
                block, reason = {'type': 'tool_use', 'id': 'toolu_read', 'name': 'Read', 'input': {'file_path': path}}, 'tool_use'
            else:
                block, reason = {'type': 'text', 'text': 'ready'}, 'end_turn'
            if not body.get('stream'):
                self.reply({'id': 'msg_fixture', 'type': 'message', 'role': 'assistant', 'model': body['model'], 'content': [block],
                            'stop_reason': reason, 'stop_sequence': None, 'usage': {'input_tokens': 1, 'output_tokens': 1}}); return
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()

            def event(name, value):
                self.wfile.write(('event: ' + name + '\ndata: ' + json.dumps(value) + '\n\n').encode()); self.wfile.flush()
            tool = block['type'] == 'tool_use'
            event('message_start', {'type': 'message_start', 'message': {'id': 'msg_fixture', 'type': 'message', 'role': 'assistant', 'model': body['model'],
                                                                         'content': [], 'stop_reason': None, 'stop_sequence': None, 'usage': {'input_tokens': 1, 'output_tokens': 0}}})
            start = dict(block)
            start['input' if tool else 'text'] = {} if tool else ''
            event('content_block_start', {'type': 'content_block_start', 'index': 0, 'content_block': start})
            delta = {'type': 'input_json_delta', 'partial_json': json.dumps(block['input'])} if tool else {'type': 'text_delta', 'text': block['text']}
            event('content_block_delta', {'type': 'content_block_delta', 'index': 0, 'delta': delta})
            event('content_block_stop', {'type': 'content_block_stop', 'index': 0})
            event('message_delta', {'type': 'message_delta', 'delta': {'stop_reason': reason, 'stop_sequence': None}, 'usage': {'output_tokens': 1}})
            event('message_stop', {'type': 'message_stop'})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as error:
            state['errors'].append(f'{type(error).__name__}: {error}')
            self.reply({}, 500)

    def reply(self, payload, status=200):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


with tempfile.TemporaryDirectory(prefix='teamcode-files-smoke-') as directory:
    root = Path(directory)
    password, database_password = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    origin = f'http://127.0.0.1:{port}'
    inference = ThreadingHTTPServer(('127.0.0.1', 0), Inference)
    threading.Thread(target=inference.serve_forever, daemon=True).start()
    (root / 'bin').mkdir()
    (root / 'claude-home').mkdir()
    # The host points the agent at TeamCode's own inference gateway, which needs a provider account; this launcher keeps the actual
    # executable and sends its inference to the fixture instead.
    (root / 'bin/claude').write_text(f'#!/bin/sh\nexport ANTHROPIC_BASE_URL=http://127.0.0.1:{inference.server_port}\n'
                                     f'export ANTHROPIC_API_KEY=sk-ant-fixture-not-a-secret\nunset ANTHROPIC_AUTH_TOKEN\nexec \'{claude}\' "$@"\n')
    (root / 'bin/claude').chmod(0o755)
    env = {key: value for key, value in os.environ.items() if not key.startswith(('ANTHROPIC_', 'CLAUDE_', 'TEAMCODE_')) and key != 'CLAUDECODE'}
    env.update(TEAMCODE_MODE='server', TEAMCODE_PORT=str(port), TEAMCODE_PUBLIC_URL=origin, TEAMCODE_DATABASE_USER='teamcode',
               TEAMCODE_DATABASE_PASSWORD=database_password, TEAMCODE_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
               TEAMCODE_DISABLE_AUTOUPDATE='1', XDG_CONFIG_HOME=str(root / 'config'),
               PATH=str(root / 'bin') + os.pathsep + os.environ['PATH'],
               # The real client, isolated from the user's account.
               CLAUDE_CONFIG_DIR=str(root / 'claude-home'), CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC='1', DISABLE_AUTOUPDATER='1',
               DISABLE_TELEMETRY='1', DISABLE_ERROR_REPORTING='1')
    server_log = root / 'server.log'
    project = root / 'work' / 'files-project'
    project.mkdir(parents=True)
    note = root / 'note.txt'
    note.write_text(f'The attached note says {MARKER}.\n')
    try:
        run(['docker', 'run', '-d', '--name', mysql_name, '--tmpfs', '/var/lib/mysql:rw,nosuid,size=1g', '-p', '127.0.0.1::3306',
             '-e', 'MYSQL_DATABASE=teamcode', '-e', 'MYSQL_USER=teamcode', '-e', f'MYSQL_PASSWORD={database_password}',
             '-e', f'MYSQL_ROOT_PASSWORD={database_password}', 'mysql:8.4'])
        containers.append(mysql_name)
        # Versity S3 Gateway stands for an S3-compatible bucket (the same image as the S3 adapter's test).
        # Artifact storage requires https: a one-day self-signed certificate for 127.0.0.1, trusted by the server's JVM only.
        certs = root / 'certs'
        certs.mkdir()
        run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1', '-subj', '/CN=127.0.0.1', '-addext', 'subjectAltName=IP:127.0.0.1',
             '-keyout', str(certs / 'key.pem'), '-out', str(certs / 'cert.pem')])
        for file in certs.iterdir():
            file.chmod(0o644)
        run(['keytool', '-importcert', '-noprompt', '-alias', 'smoke', '-file', str(certs / 'cert.pem'), '-keystore', str(certs / 'trust.jks'), '-storepass', 'changeit'])
        env['JAVA_TOOL_OPTIONS'] = f'-Djavax.net.ssl.trustStore={certs / "trust.jks"} -Djavax.net.ssl.trustStorePassword=changeit'
        run(['docker', 'run', '-d', '--name', s3_name, '--tmpfs', '/data:rw', '-v', f'{certs}:/certs:ro', '-p', '127.0.0.1::7070',
             '-e', f'ROOT_ACCESS_KEY={S3_USER}', '-e', f'ROOT_SECRET_KEY={S3_PASSWORD}', 'versity/versitygw:v1.0.16',
             '--cert', '/certs/cert.pem', '--key', '/certs/key.pem', 'posix', '/data'])
        containers.append(s3_name)

        def mapped(name, internal):
            def port_of():
                lines = subprocess.run(['docker', 'port', name, internal], capture_output=True, text=True).stdout.strip().splitlines()
                return lines[0].rsplit(':', 1)[-1] if lines and lines[0].rsplit(':', 1)[-1].isdigit() else None
            return wait(port_of, 'the port of ' + name, 30)
        env['TEAMCODE_DATABASE_URL'] = f'jdbc:mysql://127.0.0.1:{mapped(mysql_name, "3306/tcp")}/teamcode'
        s3_endpoint = f'https://127.0.0.1:{mapped(s3_name, "7070/tcp")}'
        wait(lambda: subprocess.run(['docker', 'exec', mysql_name, 'mysqladmin', 'ping', '-h', '127.0.0.1', '--silent'], capture_output=True).returncode == 0, 'MySQL', 90)

        def bucket_ready():
            try:
                create_bucket(s3_endpoint, certs / 'cert.pem'); return True
            except (OSError, urllib.error.URLError):
                return False
        wait(bucket_ready, 'the bucket', 60)

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

        def api(path, data=None, method=None, raw=None, headers=None, allow=()):
            request = urllib.request.Request(origin + path, raw if raw is not None else (json.dumps(data).encode() if data is not None else None),
                                             {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', **(headers or {})}, method=method)
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    return json.loads(response.read() or b'null')
            except urllib.error.HTTPError as error:
                body = error.read()
                if error.code in allow:
                    return json.loads(body or b'null')
                raise RuntimeError(f'{path}: HTTP {error.code} {body[:500]}') from None

        runtime = api('/api/v1/cloud/runtime')
        api('/api/v1/cloud/runtime', dict(runtime, enabled=True, runnerImage=runtime.get('runnerImage') or 'registry.example/teamcode-runner:1', serverUrl='https://teamcode.example'), 'PUT')

        def cli(*arguments, stdin=None, timeout=90):
            return run([binary, *arguments], env=env, input=stdin, timeout=timeout, cwd=str(root))

        # 1. Without a verified bucket the capability is off for this team and an upload says why.
        started = cli('remote', 'start', str(project), '--detach', '--json')
        id = session_id = json.loads(started.stdout)['id']
        wait(lambda: api(f'/api/v1/cloud/sessions/{id}')['host']['online'], 'the host', 90)
        policy = api('/api/v1/cloud/files/policy')
        assert policy['available'] is False and policy['reason'] == 'file_storage_not_configured', policy
        refused = api(f'/api/v1/cloud/sessions/{id}/files?name=note.txt', raw=note.read_bytes(), headers={'Content-Type': 'application/octet-stream'}, allow=(409,))
        assert refused['error']['type'] =='file_storage_not_configured', refused

        # 2. Save the bucket and pass its connection test: files are available, kept under session-files/.
        api('/api/v1/artifacts/storage', {'version': 0, 'endpoint': s3_endpoint, 'region': 'us-east-1', 'bucket': BUCKET, 'prefix': 'teamcode', 'pathStyle': True,
                                          'accessKeyId': S3_USER, 'secretAccessKey': S3_PASSWORD, 'serverSideEncryption': 'none'}, 'PUT')
        tested = api('/api/v1/artifacts/storage/test', {}, 'POST')
        assert tested.get('ok') is True, tested
        policy = api('/api/v1/cloud/files/policy')
        assert policy['available'] is True, policy

        # 3. Send the file from the terminal with a message; the real Claude reads the path the host wrote.
        attached = cli('attach', id, stdin=f'/attach {note}\nRead the attached note and tell me what it says.\n', timeout=120)
        assert 'note.txt' in attached.stdout + attached.stderr, attached.stdout + attached.stderr
        events = lambda: api(f'/api/v1/cloud/sessions/{id}/events?after=0&limit=200')['events']
        try:
            wait(lambda: 'FILE_READ_OK' in json.dumps(events()) or state['errors'], 'the agent answer', 120)
        except RuntimeError:
            records = root / 'config/teamcode/remote'
            raise RuntimeError(f'No answer. Fixture: {state}. Host log: {[f.read_text()[-1500:] for f in records.glob("*.log")]}. '
                               f'Events: {json.dumps(events())[-3000:]}') from None
        assert not state['errors'], state['errors']
        path = state['read_path']
        assert path, 'The message the agent received did not carry an attached file path'
        assert state['tool_result'], 'The real Read tool did not return the file content to the model'
        assert re.fullmatch(r'.*/teamcode-\d+/' + re.escape(id) + r'/[^/]+-note\.txt', path), path
        assert not Path(path).resolve().is_relative_to(project.resolve()), 'The file was written under the working directory'
        assert not any(project.iterdir()), 'The working directory was touched'
        assert (Path(path).stat().st_mode & 0o777) == 0o600 and Path(path).read_text() == note.read_text(), 'Mode or content of the written file'

        # 4. The transcript names the file but never the host path.
        user = next(e for e in events() if e['type'] == 'message.user' and e['data'].get('files'))
        assert [(f['name'], f['size'], f['contentType']) for f in user['data']['files']] == [('note.txt', len(note.read_bytes()), 'text/plain')], user
        # The agent's own tool call shows the path it read; the message the member sent never does.
        assert path not in json.dumps(user) and str(root) not in json.dumps(user), 'A host path reached the server in the message'
        assert token not in server_log.read_text() and password not in server_log.read_text()

        # 5. The session's files go with the session.
        cli('remote', 'stop', id)
        wait(lambda: (lambda s: s['state'] == 'paused' and not s['host']['online'])(api(f'/api/v1/cloud/sessions/{id}')), 'the pause')
        assert Path(path).exists(), 'A pause removed the files of the session'
        version = subprocess.run([claude, '--version'], capture_output=True, text=True, timeout=10).stdout.strip()
        print(f'PASS: real {version} read an attached file by the path the host wrote (outside the working directory, 0600), '
              'through the CLI /attach, the server and an S3-compatible bucket; storage-off refusal, then the verified bucket; '
              'the sent message names the file without a host path. No real provider calls.')
    finally:
        if session_id is not None:
            # Hosts this script started and a failure left running: their exact PIDs, found by the session id.
            for pid in subprocess.run(['pgrep', '-f', f'remote host {session_id}'], capture_output=True, text=True).stdout.split():
                subprocess.run(['kill', pid], capture_output=True)
        if server is not None:
            server.terminate()
            try:
                server.wait(timeout=15)
            except subprocess.TimeoutExpired:
                server.kill()  # The exact server this script started.
        if inference is not None:
            inference.shutdown(); inference.server_close()
        for name in containers:
            subprocess.run(['docker', 'rm', '-f', '-v', name], capture_output=True)
