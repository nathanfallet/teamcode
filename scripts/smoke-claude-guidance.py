#!/usr/bin/env python3
"""Run the real Claude CLI through native TeamCode with deterministic local inference and guidance.

No provider is called, no subscription is imported and no user Claude configuration is loaded.
"""
import argparse
import base64
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading
import time

parser = argparse.ArgumentParser()
parser.add_argument('--binary', type=Path, required=True)
parser.add_argument('--claude', type=Path, required=True, help='Actual Claude executable, not a terminal integration shim')
args = parser.parse_args()
binary, claude = str(args.binary.resolve()), str(args.claude.resolve())
assert os.name == 'posix', 'This real-client fixture currently runs on macOS/Linux'

with tempfile.TemporaryDirectory(prefix='teamcode-claude-guidance-') as directory:
    root = Path(directory)
    (root / 'bin').mkdir()
    (root / 'bin/claude').symlink_to(claude)
    (root / 'home').mkdir()
    (root / 'work').mkdir()
    token = secrets.token_hex(32)
    state = {'version': 1, 'turns': 0, 'emitted': set(), 'claims': 0, 'errors': [], 'first_context': False, 'tool_context': False, 'tool_result': False, 'hook_sessions':set(), 'inference_sessions':set(), 'runs':[], 'activity':[]}
    lock = threading.Lock()
    # A team Stop hook, delivered by the fixture and approved with the native CLI before the run.
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
                self.reply({'serverVersion':'synthetic','mode':'fixture','features':['team.hooks.v1', 'team.runs.v1', 'team.runs.activity.v1']})
        def do_POST(self):
            try:
                assert self.headers.get('Authorization') == 'Bearer ' + token
                size = int(self.headers.get('Content-Length', '0'))
                assert size <= 2 * 1024 * 1024
                body = json.loads(self.rfile.read(size))
                if self.path == '/api/v1/runs' or self.path.startswith('/api/v1/runs/'):
                    # The launch registry: the wrapper starts and ends it, the hooks report only whether the agent works or waits.
                    now = int(time.time()*1000)
                    if self.path == '/api/v1/runs':
                        with lock: state['runs'].append(body['id'])
                        self.reply({'id':body['id'],'startedAtMillis':now,'heartbeatAtMillis':now,'endedAtMillis':None,'state':'RUNNING'}); return
                    run, action = self.path.split('/')[4:6]
                    if action == 'activity':
                        assert set(body) == {'state', 'observedAtMillis'} and body['state'] in ('working', 'waiting_for_human') and isinstance(body['observedAtMillis'], int), body
                        with lock: state['activity'].append((run, body['state']))
                        self.reply({'id':run,'state':body['state'],'sinceMillis':now}); return
                    self.reply({'id':run,'startedAtMillis':now,'heartbeatAtMillis':now,'endedAtMillis':now if action == 'end' else None,'state':'ENDED' if action == 'end' else 'RUNNING'}); return
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
                if self.path == '/v1/messages/count_tokens':
                    self.reply({'input_tokens':100}); return
                assert self.path == '/v1/messages', 'Unexpected fixture path'
                with lock:
                    state['inference_sessions'].add(self.headers.get('x-claude-code-session-id') or self.headers.get('session-id'))
                    state['turns'] += 1
                    turn = state['turns']
                    serialized = json.dumps(body)
                    if turn == 1:
                        state['first_context'] = 'GUIDANCE_MARKER_1' in serialized
                        state['version'] = 2
                    else:
                        state['tool_context'] = 'GUIDANCE_MARKER_2' in serialized
                        state['tool_result'] = 'TEAMCODE_TOOL_OK' in serialized and 'tool_result' in serialized
                model = body['model']
                block = ({'type':'tool_use','id':'toolu_fixture','name':'Bash','input':{'command':'printf TEAMCODE_TOOL_OK','description':'Run the synthetic acceptance command'}}
                         if turn == 1 else {'type':'text','text':'GUIDANCE_FIXTURE_OK'})
                reason = 'tool_use' if turn == 1 else 'end_turn'
                if not body.get('stream'):
                    self.reply({'id':f'msg_{turn}','type':'message','role':'assistant','model':model,'content':[block],'stop_reason':reason,'stop_sequence':None,'usage':{'input_tokens':1,'output_tokens':1}})
                    return
                self.send_response(200)
                self.send_header('Content-Type','text/event-stream')
                self.end_headers()
                def event(name, value):
                    self.wfile.write(('event: '+name+'\ndata: '+json.dumps(value)+'\n\n').encode()); self.wfile.flush()
                event('message_start', {'type':'message_start','message':{'id':f'msg_{turn}','type':'message','role':'assistant','model':model,'content':[],'stop_reason':None,'stop_sequence':None,'usage':{'input_tokens':1,'output_tokens':0}}})
                initial = dict(block)
                initial['input' if turn == 1 else 'text'] = {} if turn == 1 else ''
                event('content_block_start', {'type':'content_block_start','index':0,'content_block':initial})
                delta = {'type':'input_json_delta','partial_json':json.dumps(block['input'])} if turn == 1 else {'type':'text_delta','text':block['text']}
                event('content_block_delta', {'type':'content_block_delta','index':0,'delta':delta})
                event('content_block_stop', {'type':'content_block_stop','index':0})
                event('message_delta', {'type':'message_delta','delta':{'stop_reason':reason,'stop_sequence':None},'usage':{'output_tokens':1}})
                event('message_stop', {'type':'message_stop'})
            except (BrokenPipeError, ConnectionResetError): pass
            except Exception as error:
                state['errors'].append(type(error).__name__ + ' at ' + self.path)
                self.reply({}, 500)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    env = {key:value for key,value in os.environ.items() if not key.startswith(('ANTHROPIC_', 'CLAUDE_', 'TEAMCODE_')) and key != 'CLAUDECODE'}
    env.update(HOME=str(root/'home'), XDG_CONFIG_HOME=str(root/'config'), CLAUDE_CONFIG_DIR=str(root/'claude'),
               PATH=str(root/'bin')+os.pathsep+env['PATH'], CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC='1',
               DISABLE_AUTOUPDATER='1', DISABLE_TELEMETRY='1', DISABLE_ERROR_REPORTING='1')
    try:
        configured = subprocess.run([binary,'configure','--server',f'http://127.0.0.1:{server.server_port}'],input=token+'\n',env=env,cwd=root/'work',capture_output=True,text=True,timeout=20)
        assert configured.returncode == 0, configured.stderr
        approved = subprocess.run([binary,'hooks','approve','stop-guard'],env=env,cwd=root/'work',capture_output=True,text=True,timeout=20)
        assert approved.returncode == 0, approved.stderr
        result = subprocess.run([binary,'run','claude','--','--setting-sources','','--strict-mcp-config','--mcp-config','{"mcpServers":{}}',
            '--no-session-persistence','--dangerously-skip-permissions','--tools','Bash','--model','haiku','--max-turns','3',
            '--system-prompt','Synthetic TeamCode acceptance fixture. Execute only the supplied harmless command.',
            '-p','Run printf TEAMCODE_TOOL_OK using Bash, then report completion.'],env=env,cwd=root/'work',capture_output=True,text=True,timeout=90)
        assert token not in result.stdout+result.stderr
        assert result.returncode == 0, result.stdout+'\n'+result.stderr
        assert not state['errors'], state['errors']
        assert 'GUIDANCE_FIXTURE_OK' in result.stdout, result.stdout
        assert state['first_context'] and state['tool_context'] and state['tool_result'], {key:value for key,value in state.items() if key != 'emitted'}
        assert state['inference_sessions'] == state['hook_sessions'] and None not in state['inference_sessions'], 'Real inference and hook session IDs do not correlate'
        assert state['emitted'] == {1,2}, 'Real hook emission receipts were not recorded'
        assert not list((root/'config/teamcode/runs').glob('*/hooks/hooks.json')), 'Ephemeral plugin remains after exit'
        assert 'Team hooks: stop-guard 1' in result.stderr, result.stderr
        stop_event = json.loads(stop_input.read_text())
        assert stop_event['hook_event_name'] == 'Stop', 'The approved team Stop hook did not run'
        # The real client's hooks told the server, through the local service, that it worked and then waited for a person.
        assert len(state['runs']) == 1 and {run for run, _ in state['activity']} == set(state['runs']), state['activity']
        reported = [value for _, value in state['activity']]
        assert reported[0] == 'working' and reported[-1] == 'waiting_for_human', reported
        assert all(a != b for a, b in zip(reported, reported[1:])), 'A repeated state was sent again'
        version = subprocess.run([claude,'--version'],capture_output=True,text=True,timeout=10).stdout.strip()
        print(f'PASS: real {version}, native launcher, UserPromptSubmit and PreToolUse HTTP guidance, actual Bash tool round trip, context injection, receipts, an approved team Stop hook run from the session plugin, content-free activity reports (working, then waiting for a person) from the real Stop/PostToolUse hooks, and plugin cleanup. No real provider calls.')
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=5)
