#!/usr/bin/env python3
"""Verify native run wrappers with synthetic executable agents, literal arguments and a real local proxy."""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.parse

parser = argparse.ArgumentParser()
parser.add_argument('--binary', type=Path, required=True)
parser.add_argument('--jar', type=Path, required=True)
args = parser.parse_args()
binary, jar = str(args.binary.resolve()), str(args.jar.resolve())
with tempfile.TemporaryDirectory(prefix='teamcode-run-smoke-') as directory:
    root = Path(directory)
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        port = listener.getsockname()[1]
    origin = f'http://127.0.0.1:{port}'
    token = secrets.token_urlsafe(32)
    env = dict(os.environ, XDG_CONFIG_HOME=str(root / 'config'), TEAMCODE_MODE='fixture',
               TEAMCODE_HOST='127.0.0.1', TEAMCODE_PORT=str(port), TEAMCODE_ACCESS_TOKEN=token,
               ANTHROPIC_API_KEY='inherited-credential-must-be-removed',
               CLAUDE_CODE_OAUTH_TOKEN='inherited-oauth-must-be-removed',
               PATH=str(root) + os.pathsep + os.environ['PATH'])
    agent_source = r'''
import json, os, pathlib, signal, subprocess, sys, tomllib, urllib.request
name = pathlib.Path(sys.argv[0]).name
arguments = sys.argv[1:]
plugin = None
login = os.environ.get('TEAMCODE_TEST_CLAUDE_LOGIN') == '1'
if name == 'claude' and arguments == ['auth', 'status']:
    # The launcher's login probe: a claude.ai login is kept only when this exits 0.
    assert 'ANTHROPIC_API_KEY' not in os.environ and 'CLAUDE_CODE_OAUTH_TOKEN' not in os.environ
    sys.exit(0 if login else 1)
if name == 'claude':
    assert 'ANTHROPIC_API_KEY' not in os.environ
    assert 'CLAUDE_CODE_OAUTH_TOKEN' not in os.environ
    origin = os.environ['ANTHROPIC_BASE_URL']
    if login:
        # Claude Code keeps its own login: no environment credential, the capability in a custom header.
        assert 'ANTHROPIC_AUTH_TOKEN' not in os.environ
        header, _, token = os.environ['ANTHROPIC_CUSTOM_HEADERS'].splitlines()[-1].partition(': ')
        assert header == 'x-teamcode-local-token'
        credentials = {'x-teamcode-local-token': token, 'Authorization': 'Bearer sk-ant-oat01-synthetic-smoke-login'}
    else:
        token = os.environ['ANTHROPIC_AUTH_TOKEN']
        credentials = {'Authorization': 'Bearer ' + token}
    route = '/v1/messages'
    if os.environ.get('TEAMCODE_TEST_GUIDANCE', '1') == '1':
        assert arguments[0] == '--plugin-dir'
        plugin = pathlib.Path(arguments[1])
        assert json.loads((plugin / '.claude-plugin/plugin.json').read_text())['name'] == 'teamcode-guidance'
        hooks = json.loads((plugin / 'hooks/hooks.json').read_text())['hooks']
        hook = hooks['PreToolUse'][0]['hooks'][0]
        assert token not in json.dumps(hooks)
        assert os.environ['TEAMCODE_HOOK_TOKEN'] == token
        request = urllib.request.Request(hook['url'], json.dumps({'session_id':'native-smoke','hook_event_name':'PreToolUse','tool_input':'private-tool-input'}).encode(),
            {'Content-Type':'application/json','Authorization':'Bearer '+token})
        with urllib.request.urlopen(request, timeout=5) as response:
            assert json.load(response) == {}, 'A fixture without guidance should not inject context'
        arguments = arguments[2:]
    else:
        assert 'TEAMCODE_HOOK_TOKEN' not in os.environ
        assert not arguments or arguments[0] != '--plugin-dir'
else:
    end = arguments.index('--') if '--' in arguments else len(arguments)
    assert arguments[end-4:end-2] == ['-c', 'model_provider="teamcode"']
    provider = tomllib.loads(arguments[end-1])['model_providers']['teamcode']
    assert provider['wire_api'] == 'responses'
    assert provider['request_max_retries'] == provider['stream_max_retries'] == 0
    origin = provider['base_url'].removesuffix('/v1')
    token = os.environ[provider['env_key']]
    credentials = {'Authorization': 'Bearer ' + token}
    route = '/v1/responses'
    if os.environ.get('TEAMCODE_TEST_GUIDANCE', '1') == '1':
        for offset, event in [(8, 'UserPromptSubmit'), (6, 'PreToolUse')]:
            assert arguments[end-offset] == '-c'
            hook = tomllib.loads(arguments[end-offset+1])['hooks'][event][0]['hooks'][0]
            assert hook['timeout'] == 5 and token not in hook['command']
            result = subprocess.run(hook['command'], shell=True, input=json.dumps({'session_id':'native-smoke','hook_event_name':event}), capture_output=True, text=True, timeout=6)
            assert result.returncode == 0 and json.loads(result.stdout) == {} and not result.stderr
        arguments = arguments[:end-8] + arguments[end:]
    else:
        arguments = arguments[:end-4] + arguments[end:]
assert len(token) >= 32 and token != os.environ['TEAMCODE_ACCESS_TOKEN']
assert sys.stdin.read() == 'stdin reaches the child\n'
payload = {'model':'fixture','stream':True,'messages':[],'input':[]}
req = urllib.request.Request(origin + route, json.dumps(payload).encode(),
                             {'Content-Type':'application/json', **credentials})
with urllib.request.urlopen(req, timeout=15) as response:
    assert response.status == 200 and b'message_stop' in response.read()
pathlib.Path(os.environ['TEAMCODE_RUN_RECORD']).write_text(json.dumps({'args':arguments,'origin':origin,'cwd':os.getcwd(),'plugin':str(plugin) if plugin else None}))
print('CHILD_STDOUT_OK', flush=True)
print('CHILD_STDERR_OK', file=sys.stderr, flush=True)
if '--signal-test' in arguments:
    os.kill(os.getpid(), signal.SIGTERM)
sys.exit(37)
'''
    for name in ('claude', 'codex'):
        executable = root / name
        executable.write_text('#!' + sys.executable + '\n' + agent_source)
        executable.chmod(0o700)
    with (root / 'server.log').open('w') as log:
        server = subprocess.Popen(['java', '-jar', jar], env=env, stdout=log, stderr=log)
    try:
        for _ in range(150):
            if server.poll() is not None:
                raise RuntimeError('Fixture server exited')
            try:
                with urllib.request.urlopen(origin + '/health', timeout=1):
                    break
            except OSError:
                time.sleep(.2)
        else:
            raise RuntimeError('Fixture server did not start')
        subprocess.run([binary, 'configure', '--server', origin], input=token+'\n', text=True,
                       env=env, capture_output=True, check=True, timeout=30)
        config = root / 'config/teamcode/config.json'
        original_config = config.read_bytes()
        supplied = ['--dangerously-skip-permissions', '--model', 'literal model with spaces',
                    '-p', 'literal $(touch should-not-exist); "quotes"\nand a newline']
        def launch(name, arguments=supplied, expected=37, guidance=True, workload=None, login=False):
            record = root / (name + ('-signal' if expected != 37 else '') + '.json')
            result = subprocess.run([binary, 'run', name, *([] if guidance else ['--no-guidance']),
                                    *(['--workload', workload] if workload else []), '--', *arguments],
                                    input='stdin reaches the child\n', text=True, capture_output=True,
                                    env=dict(env, TEAMCODE_RUN_RECORD=str(record), TEAMCODE_TEST_GUIDANCE='1' if guidance and '--bare' not in arguments else '0',
                                             TEAMCODE_TEST_CLAUDE_LOGIN='1' if login else '0'), cwd=root, timeout=45)
            assert result.returncode == expected, f'{name} launch failed with exit code {result.returncode}'
            assert result.stdout == 'CHILD_STDOUT_OK\n', 'Wrapper logs contaminated agent stdout'
            assert 'CHILD_STDERR_OK' in result.stderr
            assert token not in result.stdout + result.stderr
            recorded = json.loads(record.read_text())
            assert recorded['args'] == arguments, 'Agent arguments changed'
            if recorded['plugin']: assert not Path(recorded['plugin']).exists(), 'Ephemeral plugin was not removed'
            assert Path(recorded['cwd']).resolve() == root.resolve(), 'Working directory changed'
            address = urllib.parse.urlsplit(recorded['origin'])
            assert address.path == ('/_teamcode/workloads/background' if workload == 'background' else '')
            local_port = address.port
            with socket.socket() as probe:
                probe.settimeout(1)
                assert probe.connect_ex(('127.0.0.1', local_port)) != 0, 'Owned proxy survived agent exit'
            return local_port
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            ports = list(pool.map(launch, ['claude', 'codex']))
        assert len(set(ports)) == 2
        launch('claude', ['--signal-test'], 143)
        launch('claude', guidance=False)
        launch('codex', guidance=False)
        launch('claude', ['--bare', 'literal'])
        launch('codex', ['exec', '--ignore-user-config', '--json', 'Literal prompt'])
        launch('codex', ['exec', 'resume', '--last', '--', '-literal prompt'])
        launch('claude', workload='background')
        launch('codex', ['exec', '--ignore-user-config', '--', 'literal'], workload='background')
        launch('claude', ['--workload', 'literal-agent-value'], workload='interactive')
        launch('claude', login=True)
        launch('claude', workload='background', login=True)
        invalid = subprocess.run([binary, 'run', 'claude', '--workload', 'urgent', '--', 'literal'],
                                 env=env, text=True, capture_output=True, timeout=15)
        assert invalid.returncode != 0 and 'CHILD_STDOUT_OK' not in invalid.stdout
        assert config.read_bytes() == original_config
        assert not (root / 'should-not-exist').exists()
        print('Native run smoke passed: Claude/Codex launch, kept claude.ai login, per-launch workload, literal arguments, inherited stdio/cwd, isolated capabilities, simultaneous proxies, exit/signal status, unchanged config and cleanup.')
    finally:
        server.terminate()
        try:
            server.wait(timeout=15)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait(timeout=5)
