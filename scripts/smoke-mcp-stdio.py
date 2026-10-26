#!/usr/bin/env python3
"""Hosted MCP servers (transport stdio) end to end with the real jars: two server replicas and the MCP host against one
isolated MySQL, all on loopback. An administrator declares a SHARED npm server and a PER_MEMBER PyPI server through the
API; a member lists and calls them through the gateway exactly as an agent does (Streamable HTTP, one JSON-RPC message
per POST); the host is stopped and started again. Then operations: the state sweep records each process's state, which
`GET /api/v1/mcp/servers` reports; a command that cannot start becomes `failed` with one `mcp.stdio.failed` event in the
team feed and in a webhook's delivery log; the dashboard shows it with its stderr and restarts it. The packages are real
(npx and uvx download them, network needed); `--fixture` installs the host's Node.js stdio fixture server as npx and uvx
instead, offline. No provider, agent or cluster. `--dashboard FILE` saves the dashboard page before the restart there,
and after it next to it (`FILE.restarted.html`), for screenshots.

`--cli BINARY` also checks `teamcode mcp list` with the native CLI, signed in as the member in a temporary configuration.

    python3 scripts/smoke-mcp-stdio.py --jar app/build/libs/app-all.jar --host-jar mcp/build/libs/mcp-all.jar [--fixture] [--dashboard page.html] [--cli teamcode]
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
import urllib.parse
import urllib.request

NPM = ("npm", "@modelcontextprotocol/server-everything@2.0.0", "echo", {"message": "hello from the smoke"})
PYPI = ("pypi", "mcp-server-fetch==2026.10.24")
FIXTURE_NPM = ("npm", "fixture@1.0.0", "echo", {"text": "hello from the smoke"})
FIXTURE_PYPI = ("pypi", "fixture==1.0.0")
# A version that does not exist (npm answers notarget), or the fixture told to exit before initialize: the start fails.
BROKEN = ("npm", "@modelcontextprotocol/server-everything@99.0.0", {})
FIXTURE_BROKEN = ("npm", "fixture@1.0.0", {"FIXTURE_MODE": "exit-at-start"})

parser = argparse.ArgumentParser()
parser.add_argument('--jar', required=True)
parser.add_argument('--host-jar', required=True)
parser.add_argument('--fixture', action='store_true', help="run the host's Node.js fixture server instead of real packages")
parser.add_argument('--dashboard', help='save the /dashboard/mcp page to this file')
parser.add_argument('--cli', help='a native teamcode binary: `teamcode mcp list` is checked as the member')
args = parser.parse_args()
jar, host_jar = str(Path(args.jar).resolve()), str(Path(args.host_jar).resolve())
npm, pypi, broken = (FIXTURE_NPM, FIXTURE_PYPI, FIXTURE_BROKEN) if args.fixture else (NPM, PYPI, BROKEN)
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


def page(origin, path, session, form=None):
    """A dashboard page with the session cookie; [form] posts it like the browser does (same origin)."""
    headers = {'Cookie': 'teamcode_session=' + session}
    data = None
    if form is not None:
        headers.update({'Origin': origin, 'Content-Type': 'application/x-www-form-urlencoded'})
        data = urllib.parse.urlencode(form).encode()

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *_):
            return None
    try:
        with urllib.request.build_opener(NoRedirect).open(urllib.request.Request(origin + path, data, headers), timeout=60) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def eventually(what, check, seconds=150):
    """The sweep runs once a minute on one replica: wait for its next pass."""
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = check()
        if value:
            return value
        time.sleep(2)
    raise RuntimeError(f'timed out waiting for {what}')


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
                      TEAMCODE_MCP_URL=host_origin, TEAMCODE_MCP_TOKEN=host_token, TEAMCODE_WEBHOOK_PRIVATE_TARGETS='true')
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

        # Operations. A webhook subscribed to the hosted-server events (a loopback target that never answers: the delivery
        # log is what proves the event was queued for it).
        status, body = request(origins[0], '/api/v1/webhooks', {'url': f'https://127.0.0.1:{free_port()}/teamcode',
                                                                'types': ['mcp.stdio.failed', 'mcp.stdio.recovered', 'mcp.unreachable']}, admin)
        assert status == 201, body
        webhook_id = json.loads(body)['webhook']['id']

        def servers():
            return {server['name']: server for server in json.loads(request(origins[1], '/api/v1/mcp/servers', token=member)[1])}
        listed = eventually('the sweep to record everything as ready', lambda: (lambda s: s if s['everything'].get('state') == 'ready' else None)(servers()))
        assert listed['everything']['transport'] == 'stdio' and listed['fetch']['transport'] == 'stdio', listed
        print(f"state: everything {listed['everything']['state']}, fetch {listed['fetch'].get('state')} (GET /api/v1/mcp/servers)")

        # A command that cannot start: failed, one event in the feed and the webhook's log, shown on the dashboard.
        status, body = request(origins[0], '/api/v1/variants', {
            'kind': 'MCP', 'target': 'broken', 'name': 'team', 'binding': 'SHARED', 'transport': 'stdio',
            'launcher': broken[0], 'package': broken[1], 'config': broken[2], 'secretVariables': {'SMOKE_SECRET': shared_secret}}, admin)
        assert status == 201, body
        broken_id = json.loads(body)['id']
        status, body = request(origins[1], f'/api/v1/members/{member_id}/variants', {'variantId': broken_id}, admin, method='PUT')
        assert status == 200, body
        assert rpc(origins[0], 'broken', 'tools/list')['result']['tools'] == [], 'a command that does not start lists no tool'
        listed = eventually('the sweep to record broken as failed', lambda: (lambda s: s if s['broken'].get('state') in ('failed', 'backoff') else None)(servers()))
        print(f"state: broken {listed['broken']['state']} ({listed['broken'].get('failure')})")
        assert listed['broken'].get('failure'), listed['broken']

        def feed_events():
            # The SSE feed from the start: read until the failure event shows up or the read times out.
            req = urllib.request.Request(origins[0] + '/api/v1/cloud/events/stream?after=0&types=mcp.stdio.failed', headers={'Authorization': 'Bearer ' + admin})
            seen = []
            try:
                with urllib.request.urlopen(req, timeout=5) as stream:
                    for raw in stream:
                        line = raw.decode().strip()
                        if line.startswith('data:'):
                            seen.append(json.loads(line[5:]))
                            break
            except (TimeoutError, OSError):
                pass
            return seen
        event = eventually('mcp.stdio.failed in the team feed', feed_events, seconds=30)[0]
        assert event['type'] == 'mcp.stdio.failed' and event['subject'] == 'mcp' and event['data']['server'] == 'broken', event
        print(f"feed: {event['type']} {json.dumps(event['data'])}")
        deliveries = eventually('the webhook delivery', lambda: [d for d in json.loads(request(origins[0], f'/api/v1/webhooks/{webhook_id}/deliveries', token=admin)[1])
                                                                 if d['eventType'] == 'mcp.stdio.failed'], seconds=60)
        print(f"webhook: {deliveries[0]['eventType']} queued, state {deliveries[0]['state']}, attempts {deliveries[0]['attempts']}")

        status, html = page(origins[0], '/dashboard/mcp', admin)
        assert status == 200 and 'Hosted commands' in html and 'broken' in html and 'Restart' in html, html[:400]
        bodies.append(html)
        if args.dashboard:
            Path(args.dashboard).write_text(html)
        csrf = re.search(r"name='csrf' value='([^']+)'", html).group(1)
        status, _ = page(origins[0], '/dashboard/mcp/hosted/restart', admin, {'csrf': csrf, 'variantId': broken_id})
        assert status == 302, status
        print('dashboard: hosted commands listed with state and stderr, restart accepted (302)')
        status, html = page(origins[0], '/dashboard/mcp', admin)
        bodies.append(html)
        if args.dashboard:
            Path(args.dashboard).with_suffix('.restarted.html').write_text(html)

        if args.cli:
            cli_env = dict(os.environ, XDG_CONFIG_HOME=str(root / 'cli-config'), HOME=str(root))
            subprocess.run([args.cli, 'login', '--server', origins[1], '--username', 'smoke-member', '--password-stdin', '--device', 'Smoke CLI'],
                           input=member_password + '\n', env=cli_env, check=True, capture_output=True, text=True, timeout=60)
            listed = subprocess.run([args.cli, 'mcp', 'list'], env=cli_env, check=True, capture_output=True, text=True, timeout=60).stdout
            # The terminal expands the tabs between columns: split on the known transport labels instead.
            line_shape = re.compile(r'^(\S+)\s+(hosted command|remote \(HTTPS\)|-)\s+(.+)$')
            rows = {match.group(1): [match.group(2), match.group(3).strip()] for match in map(line_shape.match, listed.splitlines()) if match}
            assert {'teamcode-everything', 'teamcode-fetch', 'teamcode-broken'} <= rows.keys(), listed
            assert rows['teamcode-everything'] == ['hosted command', 'ready'], listed
            transport, state = rows['teamcode-broken']
            assert transport == 'hosted command' and (state in ('idle', 'starting') or state.startswith('unavailable')), listed
            as_json = json.loads(subprocess.run([args.cli, 'mcp', 'list', '--json'], env=cli_env, check=True, capture_output=True, text=True, timeout=60).stdout)
            assert {server['name'] for server in as_json} == {'everything', 'fetch', 'broken'}, as_json
            bodies.append(listed)
            print('cli: teamcode mcp list\n' + '\n'.join('  ' + line for line in listed.splitlines()))

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
        print('Hosted MCP servers verified end to end: declaration, warm-up, list and call on both replicas, host restart, '
              'states, failure event in the feed and a webhook, dashboard restart, no secret leaked.')
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
