#!/usr/bin/env python3
"""Opt-in real provider acceptance: isolated server, native import/proxy and actual agents.

Never run in CI. Uses existing access tokens only, never refreshes, and removes its
own encrypted test database on exit. No source configuration is modified.
"""
import argparse
import base64
import hashlib
import shutil
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
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--binary', required=True)
parser.add_argument('--jar', required=True)
parser.add_argument('--source', type=Path, required=True, help='Authorized TeamClaude configuration')
parser.add_argument('--model', default='claude-haiku-4-5-20251001')
parser.add_argument('--skip-claude', action='store_true')
parser.add_argument('--skip-reimport', action='store_true', help='Skip the previously verified upsert check during routing diagnostics')
parser.add_argument('--translation-smoke', action='store_true', help='Run two small translated function-tool turns in each direction')
parser.add_argument('--translation-direction', choices=('both','messages','responses'), default='both')
parser.add_argument('--launcher', action='store_true', help='Use teamcode run to manage the agent proxy')
parser.add_argument('--codex-model', help='Also run an isolated real Codex process using this model')
parser.add_argument('--coding-task', choices=('claude', 'codex', 'both'), help='Run real agents on an isolated coding task through a native or experimental translated route')
parser.add_argument('--coding-route', choices=('native', 'translated'), default='native',
                    help='Coding tasks use native subscription routes by default; translated explicitly opts into experimental conversion')
parser.add_argument('--coding-reasoning', choices=('portable', 'default'), default='portable',
                    help='With --coding-task: portable explicitly disables reasoning; default leaves client reasoning settings unchanged')
parser.add_argument('--coding-fallback', action='store_true', help='With --coding-task, remove native capacity at a tool-result boundary to exercise configured fallback')
parser.add_argument('--claude', type=Path, help='Actual Claude executable, avoiding terminal integration shims')
parser.add_argument('--codex', type=Path, help='Actual Codex executable, avoiding terminal integration shims')
args = parser.parse_args()
if args.coding_reasoning != 'portable' and not args.coding_task:
    parser.error('--coding-reasoning requires --coding-task')
if args.coding_fallback and not args.coding_task:
    parser.error('--coding-fallback requires --coding-task')
if args.coding_fallback and args.coding_route != 'translated':
    parser.error('--coding-fallback requires --coding-route translated')
if args.coding_route != 'native' and not args.coding_task:
    parser.error('--coding-route requires --coding-task')
binary, jar = str(Path(args.binary).resolve()), str(Path(args.jar).resolve())
name = 'teamcode-acceptance-' + secrets.token_hex(6)
process = None
proxy = None
container_created = False

def command(arguments, **kwargs):
    try:
        return subprocess.run(arguments, check=True, text=True, capture_output=True, **kwargs)
    except (subprocess.TimeoutExpired, subprocess.CalledProcessError) as error:
        raise RuntimeError('Acceptance subprocess failed; output withheld to protect credentials.') from None

def stop_own_process():
    global process
    if process is not None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()  # Exact child started by this test only.
            process.wait(timeout=5)
        process = None

with tempfile.TemporaryDirectory(prefix='teamcode-provider-acceptance-') as directory:
    root = Path(directory)
    password = secrets.token_urlsafe(32)
    database_password = secrets.token_urlsafe(32)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    origin = f'http://127.0.0.1:{port}'
    env = dict(os.environ, TEAMCODE_MODE='server', TEAMCODE_PORT=str(port), TEAMCODE_PUBLIC_URL=origin,
               TEAMCODE_DATABASE_USER='teamcode', TEAMCODE_DATABASE_PASSWORD=database_password,
               TEAMCODE_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
               XDG_CONFIG_HOME=str(root / 'config'))
    log = root / 'server.log'
    try:
        command(['docker', 'run', '-d', '--name', name, '--tmpfs', '/var/lib/mysql:rw,nosuid,size=1g', '-p', '127.0.0.1::3306',
                 '-e', 'MYSQL_DATABASE=teamcode', '-e', 'MYSQL_USER=teamcode',
                 '-e', f'MYSQL_PASSWORD={database_password}', '-e', f'MYSQL_ROOT_PASSWORD={database_password}',
                 'mysql:8.4'])
        container_created = True
        db_port = command(['docker', 'port', name, '3306/tcp']).stdout.strip().rsplit(':', 1)[1]
        env['TEAMCODE_DATABASE_URL'] = f'jdbc:mysql://127.0.0.1:{db_port}/teamcode'
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            # Test only our own isolated container, never an existing database.
            check = subprocess.run(['docker', 'exec', name, 'mysqladmin', 'ping', '-h', '127.0.0.1', '--silent'], capture_output=True)
            if check.returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Isolated MySQL did not start')

        def start():
            global process
            with log.open('w') as output:
                process = subprocess.Popen(['java', '-jar', jar], env=env, stdout=output, stderr=subprocess.STDOUT)
            for _ in range(120):
                if process.poll() is not None:
                    raise RuntimeError('TeamCode exited before readiness; inspect the isolated smoke log locally')
                try:
                    with urllib.request.urlopen(origin + '/health', timeout=1) as response:
                        if response.status == 200:
                            return
                except (OSError, urllib.error.URLError):
                    time.sleep(.25)
            raise RuntimeError('TeamCode readiness timed out')

        def request(path, data=None, token=None, opener=None, form=False, csrf_origin=None, method=None):
            headers = {}
            body = None
            if data is not None:
                body = (urllib.parse.urlencode(data) if form else json.dumps(data)).encode()
                headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
            if token:
                headers['Authorization'] = 'Bearer ' + token
            if csrf_origin:
                headers['Origin'] = csrf_origin
            req = urllib.request.Request(origin + path, body, headers, method=method)
            try:
                with (opener or urllib.request.build_opener()).open(req, timeout=20) as response:
                    return response.status, response.read().decode()
            except urllib.error.HTTPError as error:
                return error.code, error.read().decode()

        start()
        initial = re.search(r'TeamCode temporary administrator password: (\S+)', log.read_text()).group(1)
        assert log.read_text().count('TeamCode temporary administrator password:') == 1
        # Actual native binary performs temporary-password replacement and persists only its session.
        command([binary, 'login', '--server', origin, '--username', 'admin', '--password-stdin'],
                env=env, input=f'{initial}\n{password}\n{password}\n', timeout=30)
        saved = json.loads((root / 'config/teamcode/config.json').read_text())
        token = saved['accessToken']
        assert saved['serverUrl'] == origin
        assert password not in json.dumps(saved) and initial not in json.dumps(saved)
        assert request('/api/v1/members', token=token)[0] == 200
        source_digest = hashlib.sha256(args.source.read_bytes()).hexdigest()
        # Import the complete source, including unsupported/expired entries. Partial failure is explicit.
        imported = subprocess.run([binary, 'accounts', 'import', '--from', 'teamclaude', '--config',
                                   str(args.source.resolve()), '--access-only'], env=env, text=True,
                                  capture_output=True, timeout=180)
        status, raw_accounts = request('/api/v1/accounts', token=token)
        assert status == 200
        first = {a['id']: a for a in json.loads(raw_accounts)}
        assert first and all(a['state'] == 'ACCESS_ONLY' for a in first.values())
        print(f'Native import: {len(first)} verified accounts; partial failure={imported.returncode != 0}.', flush=True)
        if not args.skip_reimport:
            repeated = subprocess.run([binary, 'accounts', 'import', '--from', 'teamclaude', '--config',
                                       str(args.source.resolve()), '--access-only'], env=env, text=True,
                                      capture_output=True, timeout=180)
            second = {a['id']: a for a in json.loads(request('/api/v1/accounts', token=token)[1])}
            assert first.keys() == second.keys()
            assert all(second[key]['credentialVersion'] == first[key]['credentialVersion'] + 1 for key in first)
            assert hashlib.sha256(args.source.read_bytes()).hexdigest() == source_digest, 'Source changed during acceptance.'
            print('Repeated native import preserved account IDs and increased credential versions.', flush=True)
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            local_port = listener.getsockname()[1]
        local_token = secrets.token_urlsafe(32)
        env['TEAMCODE_LOCAL_TOKEN'] = local_token
        with (root / 'proxy.log').open('w') as output:
            proxy = subprocess.Popen([binary, 'proxy', 'start', '--port', str(local_port)], env=env,
                                     stdout=output, stderr=subprocess.STDOUT)
        for _ in range(100):
            if proxy.poll() is not None:
                raise RuntimeError('Native proxy exited before readiness.')
            try:
                with socket.create_connection(('127.0.0.1', local_port), timeout=.2):
                    break
            except OSError:
                time.sleep(.1)
        else:
            raise RuntimeError('Native proxy readiness timed out.')
        # This exact child uses no personal hooks, tools, keychain or conversation history.
        agent_env = {key: value for key, value in env.items()
                     if not key.startswith(('ANTHROPIC_', 'CLAUDE_', 'CLAUDECODE', 'CODEX_', 'OPENAI_', 'CMUX_'))}
        agent_env.update(ANTHROPIC_BASE_URL=f'http://127.0.0.1:{local_port}',
                         ANTHROPIC_AUTH_TOKEN=local_token, CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC='1',
                         CLAUDE_CONFIG_DIR=str(root / 'claude-config'), CODEX_HOME=str(root / 'codex-config'),
                         TEAMCODE_DISABLE_AUTOUPDATE='1', DISABLE_AUTOUPDATER='1', DISABLE_TELEMETRY='1', DISABLE_ERROR_REPORTING='1')
        Path(agent_env['CLAUDE_CONFIG_DIR']).mkdir()
        Path(agent_env['CODEX_HOME']).mkdir()
        agent_bin = root / 'agent-bin'; agent_bin.mkdir()
        for agent in ('claude', 'codex'):
            executable = getattr(args, agent)
            if executable is not None:
                (agent_bin / agent).symlink_to(executable.resolve(strict=True))
        agent_env['PATH'] = str(agent_bin) + os.pathsep + agent_env['PATH']
        expected_launches = []
        if not args.skip_claude and not args.coding_task:
            claude = shutil.which('claude', path=agent_env['PATH'])
            assert claude, 'Claude Code must be installed for this acceptance test.'
            launcher = [binary, 'run', 'claude', '--no-guidance', '--'] if args.launcher else [claude]
            result = subprocess.run(launcher + ['--bare', '--setting-sources', '', '--strict-mcp-config',
                                     '--tools', '', '--no-session-persistence', '--disable-slash-commands',
                                     '--model', args.model, '--output-format', 'json',
                                     '--system-prompt', 'Reply briefly.', '-p', 'Reply with exactly TEAMCODE_OK.'],
                                    env=agent_env, cwd=root, text=True, capture_output=True, timeout=90)
            events = [line for line in log.read_text().splitlines() if line.startswith('TeamCode inference ')]
            assert result.returncode == 0, 'Claude Code failed; agent output deliberately withheld.'
            parsed = json.loads(result.stdout)
            assert parsed.get('is_error') is not True and 'TEAMCODE_OK' in parsed.get('result', ''), 'Claude Code did not complete successfully.'
            assert any('provider=anthropic model=' + args.model in line for line in events)
            assert any('status=200' in line for line in events)
            assert any('settled' in line and 'units=unknown' not in line for line in events)
            assert hashlib.sha256(args.source.read_bytes()).hexdigest() == source_digest
            print('Real Claude Code acceptance passed; usage settled.', flush=True)
            if args.launcher: expected_launches.append(('claude', 'anthropic', args.model))
        if args.codex_model and not args.coding_task:
            codex = shutil.which('codex', path=agent_env['PATH'])
            assert codex, 'Codex CLI must be installed for this acceptance test.'
            provider_toml = ('model_providers.teamcode={name="TeamCode",base_url="http://127.0.0.1:'
                             + str(local_port) + '/v1",env_key="TEAMCODE_LOCAL_TOKEN",wire_api="responses",'
                             'request_max_retries=0,stream_max_retries=0}')
            launcher = [binary, 'run', 'codex', '--no-guidance', '--'] if args.launcher else [codex]
            overrides = [] if args.launcher else ['-c', 'model_provider="teamcode"', '-c', provider_toml]
            result = subprocess.run(launcher + ['--no-daemon', 'exec', *overrides, '--ignore-user-config', '--ignore-rules', '--ephemeral',
                                     '--skip-git-repo-check', '--sandbox', 'read-only', '--json',
                                     '-c', 'model_reasoning_effort="low"', '-c', 'web_search="disabled"',
                                     '--model', args.codex_model, 'Do not use tools. Reply with exactly TEAMCODE_OK.'],
                                    cwd=root, env=agent_env, capture_output=True, text=True, timeout=90)
            metadata = [line for line in log.read_text().splitlines() if line.startswith('TeamCode inference ')]
            for event in metadata:
                print(event, flush=True)
            if result.returncode != 0:
                diagnostics = (result.stdout + result.stderr).lower()
                markers = ['missing field', 'unexpected', 'stream disconnected', 'response.completed', 'response.failed', 'response.done', 'websocket', 'unsupported', 'model', 'quota', 'rate limit', 'unauthorized', 'instructions', 'invalid', 'permission', 'sandbox', 'timeout', 'connection', 'deserialize', 'tool', 'originator', 'service tier', 'reconnect', 'retry limit', 'error', 'turn.failed']
                print('Codex diagnostic categories:', [m for m in markers if m in diagnostics], flush=True)
                for line in result.stdout.splitlines():
                    try:
                        event = json.loads(line)
                        print('Codex event type:', event.get('type'), flush=True)
                    except ValueError:
                        pass
            assert result.returncode == 0, 'Codex CLI failed; agent output deliberately withheld.'
            items = [json.loads(line) for line in result.stdout.splitlines() if line.strip().startswith('{')]
            assert any(i.get('type') == 'turn.completed' for i in items), 'Codex turn did not complete.'
            assert any('TEAMCODE_OK' in i.get('item', {}).get('text', '') for i in items), 'Codex did not return the expected response.'
            dispatch = next(line for line in metadata if 'provider=codex model=' + args.codex_model in line)
            request_id = re.search(r'request=([a-zA-Z0-9-]+)', dispatch).group(1)
            assert any('settled request=' + request_id in line and 'units=unknown' not in line for line in metadata)
            print('Real Codex CLI acceptance passed through native proxy and subscription pool.', flush=True)
            if args.launcher: expected_launches.append(('codex', 'codex', args.codex_model))
        completed_launch_requests = 0
        for agent, provider, model in expected_launches:
            deadline = time.monotonic() + 5
            while True:
                status, payload = request('/api/v1/runs', token=token)
                assert status == 200
                runs = [run for run in json.loads(payload)['runs'] if run['agent'] == agent]
                if len(runs) == 1 and runs[0]['state'] == 'ENDED' and runs[0]['activeRequests'] == 0: break
                assert time.monotonic() < deadline, 'The real agent launch did not finish in the durable registry'
                time.sleep(.1)
            run = runs[0]
            print(f'Real {agent} registry metadata: state={run["state"]}, exit={run["exitCode"]}, '
                  f'attempts={run["attempts"]}, completed={run["completed"]}, '
                  f'charged_credits={run["chargedAndReservedUnits"]}.', flush=True)
            assert run['exitCode'] == 0 and run['completed'] >= 1 and run['attempts'] >= run['completed']
            assert run['chargedAndReservedUnits'] > 0 and run['lastRequestAtMillis'] is not None
            status, payload = request('/api/v1/runs/' + run['id'], token=token)
            assert status == 200
            detail = json.loads(payload)
            assert any(conversation['provider'] == provider and conversation['model'] == model and
                       conversation['requestedModel'] == model for conversation in detail['conversations']), \
                'The real client did not preserve its native run prefix/session attribution'
            shown = command([binary, 'sessions', 'show', run['id']], env=env, timeout=15)
            assert json.loads(shown.stdout)['run']['id'] == run['id']
            completed_launch_requests += run['completed']
            print(f'Real {agent} launch registry passed: ended/exit 0, attributed native conversation, '
                  f'completed={run["completed"]}, charged_credits={run["chargedAndReservedUnits"]}, sessions JSON.', flush=True)
        if expected_launches:
            status, payload = request('/api/v1/status', token=token)
            assert status == 200
            shared = json.loads(payload)
            assert shared['activeRequests'] == 0 and shared['completedRequests'] == completed_launch_requests
        if args.coding_task:
            from acceptance_coding import run_coding_tasks
            run_coding_tasks(args, binary, root, env, request, token, log)
        if args.translation_smoke:
            # This portable profile explicitly disables reasoning. Opaque reasoning replay is tested separately.
            policy = json.loads(request('/api/v1/policy/models', token=token)[1])
            policy['allowedEfforts'] = list(dict.fromkeys(policy['allowedEfforts'] + ['none']))
            policy['experimentalCrossProviderTranslation'] = True
            assert request('/api/v1/policy/models', policy, token, method='PUT')[0] == 200
            schema = {'type':'object', 'properties':{'a':{'type':'integer'}, 'b':{'type':'integer'}}, 'required':['a','b'], 'additionalProperties':False}
            for incoming, target in ((args.model, 'gpt-6-luna'), ('gpt-6-luna', args.model)):
                messages = incoming.startswith('claude-')
                if args.translation_direction != 'both' and messages != (args.translation_direction == 'messages'): continue
                rule = {'id':'test-messages' if messages else 'test-responses', 'pattern':incoming,
                        'targets':[{'provider':'codex' if messages else 'anthropic', 'model':target}]}
                assert request('/api/v1/routes/' + rule['id'], rule, token, method='PUT')[0] == 200
                prompt = 'Call sum with a=2 and b=3. After its result, reply with exactly 5.'
                tool = {'name':'sum','description':'Add two integers.', 'input_schema':schema} if messages else {'type':'function','name':'sum','description':'Add two integers.','parameters':schema}
                body = {'model':incoming,'stream':True,'tools':[tool]}
                if messages:
                    body.update(max_tokens=256, thinking={'type':'disabled'}, system='Reply with only the computed integer after the tool result. No formatting.', messages=[{'role':'user','content':prompt}], tool_choice={'type':'tool','name':'sum'})
                else:
                    body.update(max_output_tokens=256, reasoning={'effort':'none'}, instructions='Reply with only the computed integer after the tool result. No formatting.', input=[{'role':'user','content':prompt}], tool_choice={'type':'function','name':'sum'})
                def translated_call():
                    url = f'http://127.0.0.1:{local_port}/v1/' + ('messages' if messages else 'responses')
                    req = urllib.request.Request(url, json.dumps(body).encode(), {'Content-Type':'application/json','Authorization':'Bearer '+local_token})
                    try:
                        with urllib.request.urlopen(req, timeout=90) as response:
                            raw = response.read().decode()
                    except urllib.error.HTTPError as error:
                        diagnostic = error.read().decode()
                        try:
                            category = json.loads(diagnostic).get('error', {}).get('code', 'upstream_error')
                        except ValueError:
                            category = 'invalid_response'
                        raise RuntimeError(f'Translated request rejected: HTTP {error.code}, category {category}') from None
                    events = [json.loads(line[6:]) for line in raw.splitlines() if line.startswith('data: ') and line[6:] != '[DONE]']
                    if not events or any(event.get('type') in ('error','response.failed') for event in events) or events[-1]['type'] != ('message_stop' if messages else 'response.completed'):
                        time.sleep(.2)  # Let settlement metadata reach the isolated log.
                        print('Translated event types:', sorted({event.get('type', 'unknown') for event in events}), flush=True)
                        for line in log.read_text().splitlines():
                            if line.startswith('TeamCode inference '): print(line, flush=True)
                    assert not any(event.get('type') in ('error','response.failed') for event in events), 'Translated stream failed'
                    assert events[-1]['type'] == ('message_stop' if messages else 'response.completed')
                    return events
                events = translated_call()
                if messages:
                    starts = [event['content_block'] for event in events if event['type'] == 'content_block_start' and event['content_block']['type'] == 'tool_use']
                    assert len(starts) == 1 and starts[0]['name'] == 'sum'
                    arguments = json.loads(''.join(event['delta']['partial_json'] for event in events if event['type'] == 'content_block_delta' and event['delta']['type'] == 'input_json_delta'))
                    call_id = starts[0]['id']
                    assert arguments == {'a':2,'b':3}
                    body['messages'] += [{'role':'assistant','content':[{'type':'tool_use','id':call_id,'name':'sum','input':arguments}]}, {'role':'user','content':[{'type':'tool_result','tool_use_id':call_id,'content':'5'}]}]
                    body['tool_choice'] = {'type':'none'}
                else:
                    output = events[-1]['response']['output']
                    calls = [item for item in output if item['type'] == 'function_call']
                    assert len(calls) == 1 and calls[0]['name'] == 'sum'
                    assert json.loads(calls[0]['arguments']) == {'a':2,'b':3}
                    body['input'] += output + [{'type':'function_call_output','call_id':calls[0]['call_id'],'output':'5'}]
                    body['tool_choice'] = 'none'
                final = translated_call()
                text = ''.join(event.get('delta', {}).get('text', '') for event in final if event['type'] == 'content_block_delta') if messages else ''.join(part.get('text','') for item in final[-1]['response']['output'] if item['type'] == 'message' for part in item['content'])
                assert text.strip() == '5', 'Translated tool result did not round-trip'
                print(f'Real translated tool loop passed: {incoming} -> {target}.', flush=True)
        # Raw SSE consumers can finish just before the server commits usage settlement.
        deadline = time.monotonic() + 5
        while True:
            events = [line for line in log.read_text().splitlines() if line.startswith('TeamCode inference ')]
            dispatched = {re.search(r'request=([a-zA-Z0-9-]+)', line).group(1) for line in events if ' dispatch ' in line}
            settled = {re.search(r'request=([a-zA-Z0-9-]+)', line).group(1) for line in events if ' settled ' in line}
            if dispatched <= settled: break
            assert time.monotonic() < deadline, 'An acceptance request did not settle'
            time.sleep(.05)
        if not args.codex_model and not args.coding_task:
            for event in events:
                print(event, flush=True)
        assert hashlib.sha256(args.source.read_bytes()).hexdigest() == source_digest
    finally:
        if proxy is not None:
            proxy.terminate()
            try:
                proxy.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proxy.kill()  # Exact child owned by this test only.
                proxy.wait(timeout=5)
        stop_own_process()
        if container_created:
            subprocess.run(['docker', 'rm', '-f', '-v', name], capture_output=True, check=True)
