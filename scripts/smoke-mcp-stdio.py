#!/usr/bin/env python3
"""Hosted MCP servers (transport stdio) end to end with the real jars: two server replicas and the MCP host against one
isolated MySQL, all on loopback. An administrator declares a SHARED npm server and a PER_MEMBER PyPI server through the
API; a member lists and calls them through the gateway exactly as an agent does (Streamable HTTP, one JSON-RPC message
per POST); the host is stopped and started again. The packages are real (npx and uvx download them, network needed);
`--fixture` installs the host's Node.js stdio fixture server as npx and uvx instead, offline. No provider, agent or cluster.

    python3 scripts/smoke-mcp-stdio.py --jar app/build/libs/app-all.jar --host-jar mcp/build/libs/mcp-all.jar [--fixture]
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

NPM = ("npm", "@modelcontextprotocol/server-everything@2.0.0", "echo", {"message": "hello from the smoke"})
PYPI = ("pypi", "mcp-server-fetch==2026.10.24")
FIXTURE_NPM = ("npm", "fixture@1.0.0", "echo", {"text": "hello from the smoke"})
FIXTURE_PYPI = ("pypi", "fixture==1.0.0")

parser = argparse.ArgumentParser()
parser.add_argument('--jar', required=True)
parser.add_argument('--host-jar', required=True)
parser.add_argument('--fixture', action='store_true', help="run the host's Node.js fixture server instead of real packages")
args = parser.parse_args()
jar, host_jar = str(Path(args.jar).resolve()), str(Path(args.host_jar).resolve())
npm, pypi = (FIXTURE_NPM, FIXTURE_PYPI) if args.fixture else (NPM, PYPI)
name = 'teamcode-mcp-stdio-' + secrets.token_hex(6)
children = []
created = False


def command(*argv):
    return subprocess.run(argv, check=True, capture_output=True, text=True, timeout=120)


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def request(origin, path, data=None, token=None, method=None, timeout=120):
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(origin + path, None if data is None else json.dumps(data).encode(), headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def await_ready(origin, path='/ready', seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if request(origin, path, timeout=5)[0] == 200:
                return
        except OSError:
            pass
        time.sleep(.25)
    raise RuntimeError(f'{origin}{path} did not become ready')


with tempfile.TemporaryDirectory(prefix='teamcode-mcp-stdio-') as directory:
    root = Path(directory)
    db_password = secrets.token_urlsafe(32)
    host_token = secrets.token_urlsafe(36)
    shared_secret = 'synthetic-smoke-shared-' + secrets.token_hex(8)
    member_secret = 'synthetic-smoke-member-' + secrets.token_hex(8)
    ports = [free_port(), free_port()]
    host_port = free_port()
    origins = [f'http://127.0.0.1:{port}' for port in ports]
    host_origin = f'http://127.0.0.1:{host_port}'
    tools_path = shutil.which('npx'), shutil.which('uvx'), shutil.which('node')
    if args.fixture:
        bin_dir = root / 'bin'
        bin_dir.mkdir()
        fixture = (Path(__file__).resolve().parents[1] / 'mcp/src/test/resources/stdio-fixture-server.js').read_text()
        for tool in ('npx', 'uvx'):
            (bin_dir / tool).write_text(fixture)
            (bin_dir / tool).chmod(0o755)
        npx, uvx = str(bin_dir / 'npx'), str(bin_dir / 'uvx')
    else:
        if not all(tools_path):
            raise SystemExit('npx, uvx and node must be on PATH (or use --fixture).')
        npx, uvx = tools_path[0], tools_path[1]
    path = ':'.join(dict.fromkeys([str(Path(npx).parent), str(Path(uvx).parent), str(Path(tools_path[2] or 'node').parent), '/usr/bin', '/bin']))
    host_env = {
        'PATH': os.environ.get('PATH', ''), 'HOME': str(root), 'TEAMCODE_MCP_TOKEN': host_token, 'TEAMCODE_MCP_HOST': '127.0.0.1',
        'TEAMCODE_MCP_PORT': str(host_port), 'TEAMCODE_MCP_DATA_DIR': str(root / 'mcp'), 'TEAMCODE_MCP_PATH': path,
        'TEAMCODE_MCP_PRLIMIT': 'prlimit' if shutil.which('prlimit') else 'none', 'TEAMCODE_MCP_DRAIN_SECONDS': '2',
    }
    server_env = dict(os.environ, TEAMCODE_MODE='server', TEAMCODE_HOST='127.0.0.1', TEAMCODE_PUBLIC_URL=origins[0],
                      TEAMCODE_DATABASE_USER='teamcode', TEAMCODE_DATABASE_PASSWORD=db_password,
                      TEAMCODE_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
                      TEAMCODE_MCP_URL=host_origin, TEAMCODE_MCP_TOKEN=host_token)
    logs = [root / f'replica-{i}.log' for i in range(2)]
    host_log = root / 'host.log'

    def start_host():
        with host_log.open('a') as log:
            child = subprocess.Popen(['java', '-jar', host_jar], env=host_env, stdout=log, stderr=subprocess.STDOUT)
        children.append(child)
        await_ready(host_origin, '/ready')
        return child

    try:
        command('docker', 'run', '-d', '--name', name, '--tmpfs', '/var/lib/mysql:rw,nosuid,size=1g',
                '-p', '127.0.0.1::3306', '-e', 'MYSQL_DATABASE=teamcode', '-e', 'MYSQL_USER=teamcode',
                '-e', f'MYSQL_PASSWORD={db_password}', '-e', f'MYSQL_ROOT_PASSWORD={db_password}', 'mysql:8.4')
        created = True
        db_port = command('docker', 'port', name, '3306/tcp').stdout.strip().splitlines()[0].rsplit(':', 1)[1]
        server_env['TEAMCODE_DATABASE_URL'] = f'jdbc:mysql://127.0.0.1:{db_port}/teamcode'
        for _ in range(180):
            if subprocess.run(['docker', 'exec', name, 'mysqladmin', 'ping', '-h', '127.0.0.1', '--silent'], capture_output=True).returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Test MySQL did not start')
        host = start_host()
        for index in range(2):
            with logs[index].open('a') as log:
                children.append(subprocess.Popen(['java', '-jar', jar], env=dict(server_env, TEAMCODE_PORT=str(ports[index])), stdout=log, stderr=subprocess.STDOUT))
        for origin in origins:
            await_ready(origin)

        # Administrator and member, as smoke-replicas.py does.
        bootstrap = [match for log in logs for match in re.findall(r'TeamCode temporary administrator password: (\S+)', log.read_text())]
        assert len(bootstrap) == 1
        password = secrets.token_urlsafe(32)
        temporary = json.loads(request(origins[0], '/api/v1/auth/login', {'username': 'admin', 'password': bootstrap[0]})[1])['accessToken']
        assert request(origins[0], '/api/v1/auth/password', {'currentPassword': bootstrap[0], 'newPassword': password}, temporary)[0] == 200
        admin = json.loads(request(origins[0], '/api/v1/auth/login', {'username': 'admin', 'password': password})[1])['accessToken']
        status, body = request(origins[0], '/api/v1/members', {'username': 'smoke-member', 'role': 'MEMBER'}, admin)
        assert status == 201, body
        created_member = json.loads(body)
        member_id, member_password = created_member['member']['id'], secrets.token_urlsafe(32)
        pending = json.loads(request(origins[1], '/api/v1/auth/login', {'username': 'smoke-member', 'password': created_member['temporaryPassword']})[1])['accessToken']
        assert request(origins[1], '/api/v1/auth/password', {'currentPassword': created_member['temporaryPassword'], 'newPassword': member_password}, pending)[0] == 200
        member = json.loads(request(origins[1], '/api/v1/auth/login', {'username': 'smoke-member', 'password': member_password})[1])['accessToken']

        for origin in origins:
            assert 'mcp.gateway.stdio.v1' in request(origin, '/api/v1/capabilities', token=admin)[1], 'both replicas announce hosted commands'

        # Declaration on replica 0 (launchers read from the host), assignment on replica 1.
        status, body = request(origins[0], '/api/v1/variants', {
            'kind': 'MCP', 'target': 'everything', 'name': 'team', 'binding': 'SHARED', 'transport': 'stdio',
            'launcher': npm[0], 'package': npm[1], 'config': {'SMOKE_VISIBLE': 'visible-value'},
            'secretVariables': {'SMOKE_SECRET': shared_secret}}, admin)
        assert status == 201, body
        shared_id = json.loads(body)['id']
        status, body = request(origins[0], '/api/v1/variants', {
            'kind': 'MCP', 'target': 'fetch', 'name': 'own', 'binding': 'PER_MEMBER', 'transport': 'stdio',
            'launcher': pypi[0], 'package': pypi[1]}, admin)
        assert status == 201, body
        personal_id = json.loads(body)['id']
        status, body = request(origins[1], f'/api/v1/members/{member_id}/variants', {'variantId': shared_id}, admin, method='PUT')
        assert status == 200, body
        status, body = request(origins[1], f'/api/v1/members/{member_id}/variants', {'variantId': personal_id, 'memberSecretVariables': {'SMOKE_MEMBER_SECRET': member_secret}}, admin, method='PUT')
        assert status == 200, body

        bodies = []
        next_id = [1]

        def rpc(origin, server, method, params=None):
            message = {'jsonrpc': '2.0', 'id': next_id[0], 'method': method}
            next_id[0] += 1
            if params is not None:
                message['params'] = params
            status, text = request(origin, '/mcp/' + server, message, member, timeout=150)
            bodies.append(text)
            assert status == 200, (status, text)
            return json.loads(text)

        # The member's client reads the list (warming both processes up), then lists and calls on the other replica.
        status, body = request(origins[0], '/api/v1/mcp/servers', token=member)
        assert status == 200 and sorted(server['name'] for server in json.loads(body)) == ['everything', 'fetch'], body
        rpc(origins[1], 'everything', 'initialize', {'protocolVersion': '2025-06-18', 'capabilities': {}, 'clientInfo': {'name': 'smoke', 'version': '1'}})
        timings = {}
        for server in ('everything', 'fetch'):
            started = time.time()
            tools = [tool['name'] for tool in rpc(origins[1], server, 'tools/list')['result']['tools']]
            timings[server] = time.time() - started
            assert tools, f'{server} listed no tools'
            print(f'{server}: {len(tools)} tools through the gateway, first list {timings[server]:.1f} s after the warm-up')
        result = rpc(origins[0], 'everything', 'tools/call', {'name': npm[2], 'arguments': npm[3]})['result']
        assert not result.get('isError') and 'hello from the smoke' in json.dumps(result), result
        print(f'everything: {npm[2]} answered {json.dumps(result)[:120]}')

        # The host goes away: a call is a tool error, a list is empty; once back, the next list starts the process again.
        host.terminate()
        host.wait(timeout=30)
        down = rpc(origins[0], 'everything', 'tools/call', {'name': npm[2], 'arguments': npm[3]})['result']
        assert down['isError'] and down['content'][0]['text'] == 'The MCP host is unreachable.', down
        assert rpc(origins[1], 'everything', 'tools/list')['result']['tools'] == []
        host = start_host()
        started = time.time()
        assert rpc(origins[1], 'everything', 'tools/list')['result']['tools']
        print(f'everything: listed again {time.time() - started:.1f} s after the host restarted (cold start)')
        assert not rpc(origins[0], 'everything', 'tools/call', {'name': npm[2], 'arguments': npm[3]})['result'].get('isError')

        # No secret in any answer; the journal holds outcomes only.
        for text in bodies:
            for secret in (shared_secret, member_secret, host_token):
                assert secret not in text, 'a secret reached a member'
        status, body = request(origins[1], '/api/v1/mcp/calls', token=admin)
        outcomes = {call['outcome'] for call in json.loads(body)['calls']}
        assert {'OK', 'UPSTREAM_ERROR'} <= outcomes and 'synthetic' not in body, outcomes
        for log in logs + [host_log]:
            text = log.read_text()
            for secret in (shared_secret, member_secret, host_token):
                assert secret not in text, f'a secret reached {log.name}'
        print('Hosted MCP servers verified end to end: declaration, warm-up, list and call on both replicas, host restart, no secret leaked.')
    finally:
        # Only the exact processes and container created by this run are stopped.
        for child in children:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    child.kill()
        if created:
            subprocess.run(['docker', 'rm', '--force', name], capture_output=True)
