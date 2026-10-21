#!/usr/bin/env python3
"""Run the real Codex CLI through native TeamCode with deterministic local inference and guidance.

No provider is called, no subscription is imported and no user Codex configuration is loaded.
"""
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import shlex
import shutil
import sys
import subprocess
import tempfile
import threading
import time

parser = argparse.ArgumentParser()
parser.add_argument('--binary', type=Path, required=True)
parser.add_argument('--codex', type=Path, required=True, help='Actual Codex executable, not a terminal integration shim')
args = parser.parse_args()
binary, codex = str(args.binary.resolve()), str(args.codex.resolve())
assert os.name == 'posix', 'This real-client fixture currently runs on macOS/Linux'

with tempfile.TemporaryDirectory(prefix='teamcode-codex-guidance-') as directory:
    root = Path(directory)
    install = root / "Team Code équipe's $literal"
    install.mkdir()
    shutil.copy2(binary, install/'teamcode')
    binary = str(install/'teamcode')
    (root / 'bin').mkdir()
    (root / 'bin/codex').symlink_to(codex)
    (root / 'home').mkdir()
    (root / 'work').mkdir()
    token = secrets.token_hex(32)
    state = {'version': 1, 'turns': 0, 'emitted': set(), 'claims': 0, 'errors': [], 'first_context': False, 'tool_context': False, 'tool_result': False, 'hook_sessions':set(), 'inference_sessions':set()}
    lock = threading.Lock()
    # A team Stop hook, delivered by the fixture and approved with the native CLI before the runs.
    stop_input = root / 'stop-hook-input.json'
    script = f"#!/bin/sh\ncat > '{stop_input}'\nexit 0\n".encode()
    script_sha = hashlib.sha256(script).hexdigest()
    definition = {'events':['Stop'], 'matcher':None, 'command':'bin/stop.sh', 'timeoutSeconds':10}
    canonical = f"hook\0Stop\0\0bin/stop.sh\x0010\nbin/stop.sh\0executable\0{len(script)}\0{script_sha}\n"
    hook_digest = 'sha256:' + hashlib.sha256(canonical.encode()).hexdigest()
    hook_manifest = {'memberId':'test-member', 'hooks':[{'name':'stop-guard', 'version':'1', 'digest':hook_digest, 'definition':definition, 'sizeBytes':len(script), 'fileCount':1}]}
    hook_artifact = {'name':'stop-guard', 'version':'1', 'digest':hook_digest, 'definition':definition, 'files':[
        {'path':'bin/stop.sh', 'mode':'executable', 'size':len(script), 'sha256':script_sha, 'contentBase64':base64.b64encode(script).decode()}]}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def reply(self, payload, status=200):
            data = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        def do_GET(self):
            if self.headers.get('Authorization') != 'Bearer ' + token:
                self.reply({}, 401); return
            if self.path.endswith('/me'):
                self.reply({'id':'test-member','username':'synthetic','role':'MEMBER','disabled':False,'passwordChangeRequired':False})
            elif self.path == '/api/v1/hook-delivery':
                self.reply(hook_manifest)
            elif self.path == '/api/v1/hook-delivery/stop-guard/1':
                self.reply(hook_artifact)
            else:
                self.reply({'serverVersion':'synthetic','mode':'fixture','features':['team.hooks.v1']})
        def do_POST(self):
            try:
                assert self.headers.get('Authorization') == 'Bearer ' + token
                size = int(self.headers.get('Content-Length', '0'))
                assert size <= 2 * 1024 * 1024
                body = json.loads(self.rfile.read(size))
                if self.path == '/api/v1/directives/claim':
                    assert set(body) == {'sessionId', 'stage'} and body['stage'] == 'EMITTED_TO_HOOK'
                    with lock:
                        state['claims'] += 1
                        state['hook_sessions'].add(body['sessionId'])
                        version = state['version']
                        if version in state['emitted']:
                            self.reply({'directive':None}); return
                    self.reply({'claimId':f'claim-{version}', 'directive':{
                        'id':f'directive-{version}', 'pace':'SLOW_DOWN', 'maxConcurrent':1, 'minIntervalMillis':10000,
                        'message':f'GUIDANCE_MARKER_{version}: reduce optional parallel work.',
                        'createdAtMillis':int(time.time()*1000), 'expiresAtMillis':int(time.time()*1000)+180000}})
                    return
                if self.path == '/api/v1/directives/receipt':
                    assert set(body) == {'id', 'stage', 'sessionId', 'claimId'}
                    with lock: state['emitted'].add(int(body['id'].split('-')[-1]))
                    self.reply({'success':True}); return
                assert self.path == '/v1/responses', 'Unexpected fixture path'
                with lock:
                    state['inference_sessions'].add(self.headers.get('session-id'))
                    state['turns'] += 1
                    turn = state['turns']
                    serialized = json.dumps(body)
                    if turn == 1:
                        state['first_context'] = 'GUIDANCE_MARKER_1' in serialized
                        state['user_hook'] = 'USER_HOOK_MARKER' in serialized
                        state['cli_hook'] = 'CLI_HOOK_MARKER' in serialized
                        state['version'] = 2
                    else:
                        state['tool_context'] = 'GUIDANCE_MARKER_2' in serialized
                        state['tool_result'] = 'TEAMCODE_TOOL_OK' in serialized and 'function_call_output' in serialized
                item = ({'type':'function_call','id':'fc_fixture','call_id':'call_fixture','name':'exec_command',
                         'arguments':json.dumps({'cmd':'printf TEAMCODE_TOOL_OK','max_output_tokens':100})}
                        if turn == 1 else {'type':'message','id':'msg_fixture','role':'assistant','status':'completed',
                        'content':[{'type':'output_text','text':'GUIDANCE_FIXTURE_OK','annotations':[]}]})
                response = {'id':f'resp_{turn}','object':'response','created_at':int(time.time()),'status':'completed',
                            'output':[item],'usage':{'input_tokens':1,'output_tokens':1,'total_tokens':2}}
                self.send_response(200)
                self.send_header('Content-Type','text/event-stream')
                self.end_headers()
                def event(name, **value):
                    self.wfile.write(('event: '+name+'\ndata: '+json.dumps({'type':name,**value})+'\n\n').encode()); self.wfile.flush()
                event('response.created', response={**response,'status':'in_progress','output':[]})
                event('response.output_item.added', output_index=0, item=item)
                event('response.output_item.done', output_index=0, item=item)
                event('response.completed', response=response)
            except (BrokenPipeError, ConnectionResetError): pass
            except Exception as error:
                state['errors'].append(type(error).__name__ + ' at ' + self.path)
                self.reply({}, 500)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    env = {key:value for key,value in os.environ.items() if not key.startswith(('ANTHROPIC_', 'CLAUDE_', 'TEAMCODE_', 'CODEX_', 'OPENAI_'))}
    env.update(HOME=str(root/'home'), XDG_CONFIG_HOME=str(root/"config équipe's $literal"), CODEX_HOME=str(root/'codex'),
               PATH=str(root/'bin')+os.pathsep+env['PATH'])
    (root/'codex').mkdir()
    user_hook = root/'user-hook.py'
    user_hook.write_text('import json,sys; data=json.load(sys.stdin); print(json.dumps({"hookSpecificOutput":{"hookEventName":data["hook_event_name"],"additionalContext":sys.argv[1] if len(sys.argv)>1 else "USER_HOOK_MARKER"}}))')
    user_command = shlex.join([sys.executable,str(user_hook)])
    original_config = '[[hooks.UserPromptSubmit]]\n[[hooks.UserPromptSubmit.hooks]]\ntype="command"\ncommand='+json.dumps(user_command)+'\n'
    (root/'codex/config.toml').write_text(original_config)
    try:
        configured = subprocess.run([binary,'configure','--server',f'http://127.0.0.1:{server.server_port}'],input=token+'\n',env=env,cwd=root/'work',capture_output=True,text=True,timeout=20)
        assert configured.returncode == 0, configured.stderr
        approved = subprocess.run([binary,'hooks','approve','stop-guard'],env=env,cwd=root/'work',capture_output=True,text=True,timeout=20)
        assert approved.returncode == 0, approved.stderr
        for mode in ['trusted', 'untrusted', 'disabled', 'ignored-config', 'custom-cli']:
            state.update(version=1,turns=0,emitted=set(),claims=0,errors=[],first_context=False,tool_context=False,tool_result=False,hook_sessions=set(),inference_sessions=set(),user_hook=False)
            stop_input.unlink(missing_ok=True)
            # This bypass is confined to this isolated fixture with three known synthetic hooks.
            # TeamCode itself never adds it or changes the user's persisted hook trust.
            trust = ['--dangerously-bypass-hook-trust'] if mode != 'untrusted' else []
            options = ['--no-guidance'] if mode == 'disabled' else []
            extra = ['--ignore-user-config'] if mode == 'ignored-config' else []
            if mode == 'custom-cli':
                extra += ['-c', 'hooks.UserPromptSubmit=[{hooks=[{type="command",command='+json.dumps(user_command+' CLI_HOOK_MARKER')+'}]}]']
            result = subprocess.run([binary,'run','codex',*options,'--','--no-daemon','exec','--skip-git-repo-check',*trust,
                '--sandbox','danger-full-access','--model','gpt-6.1-sol',*extra,
                'Run printf TEAMCODE_TOOL_OK and report completion.'],env=env,cwd=root/'work',capture_output=True,text=True,timeout=90)
            assert token not in result.stdout+result.stderr
            assert result.returncode == 0, result.stdout+'\n'+result.stderr
            assert not state['errors'], state['errors']
            assert 'GUIDANCE_FIXTURE_OK' in result.stdout and state['tool_result'], mode
            assert state['user_hook'] == (mode not in ['untrusted', 'ignored-config']), 'Existing user hook was overwritten or automatically trusted'
            if mode == 'custom-cli': assert state['cli_hook'], 'Explicit CLI hook was overwritten'
            if mode in ['trusted', 'ignored-config']:
                assert state['first_context'] and state['tool_context'], 'Guidance was not injected at both model boundaries'
                assert state['inference_sessions'] == state['hook_sessions'] and None not in state['inference_sessions'], 'Real inference and hook session IDs do not correlate'
                assert state['emitted'] == {1,2}, 'Real hook emission receipts were not recorded'
            else:
                assert not state['claims'] and not state['first_context'] and not state['tool_context'], 'Disabled or untrusted hooks executed'
            # The approved team hook runs whenever Codex trusts it, guidance or not; never untrusted or under explicit CLI hooks.
            assert stop_input.exists() == (mode in ['trusted', 'disabled', 'ignored-config']), f'Team Stop hook state is wrong in {mode} mode'
            if stop_input.exists(): assert json.loads(stop_input.read_text())['hook_event_name'] == 'Stop'
            assert (root/'codex/config.toml').read_text() == original_config, 'User configuration was changed'
        version = subprocess.run([codex,'--version'],capture_output=True,text=True,timeout=10).stdout.strip()
        print(f'PASS: real {version}, native launcher, UserPromptSubmit/PreToolUse context injection, actual exec tool round trip, correlated sessions, receipts, preserved user hooks, an approved team Stop hook, trust enforcement and opt-out. No real provider calls.')
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=5)
