#!/usr/bin/env python3
"""Exercise the actual native stdin guidance bridge without provider calls or user configuration."""
import argparse
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
args = parser.parse_args()
binary = str(args.binary.resolve())
RUN = '11111111-2222-3333-4444-555555555555'
# A launch's own identifier in the caller's environment must not turn the guidance cases into activity reports.
BASE_ENV = {key: value for key, value in os.environ.items() if key != 'TEAMCODE_RUN_ID'}

with tempfile.TemporaryDirectory(prefix='teamcode-hook-') as directory:
    root = Path(directory)
    config = root / "configuration é space's.json"
    token = secrets.token_hex(32)
    state = {'calls': [], 'mode': 'fresh', 'emitted': False, 'errors': []}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def do_POST(self):
            try:
                assert self.headers.get('Authorization') == 'Bearer ' + token
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                state['calls'].append((self.path, body))
                assert 'PRIVATE_TOOL_INPUT' not in json.dumps(body)
                if self.path.startswith('/api/v1/runs/'):
                    # The hook's time travels with the state so the server can drop a late, older report; nothing else does.
                    assert set(body) == {'state', 'observedAtMillis'} and isinstance(body['observedAtMillis'], int) and body['observedAtMillis'] > 0, body
                    state['calls'][-1] = (self.path, {'state': body['state']})
                    response = {'id': RUN, 'state': body['state'], 'sinceMillis': 1}
                elif self.path == '/api/v1/directives/claim':
                    assert body == {'sessionId': 'native-session', 'stage': 'EMITTED_TO_HOOK'}
                    if state['mode'] == 'outage':
                        self.send_error(503, 'PRIVATE_DIAGNOSTIC'); return
                    if state['mode'] == 'slow': time.sleep(3)
                    response = {'directive': None} if state['emitted'] else {
                        'claimId': 'claim', 'directive': {
                            'id': 'directive', 'pace': 'SLOW_DOWN', 'maxConcurrent': 1,
                            'minIntervalMillis': 10000, 'message': 'Reduce optional parallel work.',
                            'createdAtMillis': 1, 'expiresAtMillis': 1 if state['mode'] == 'expired' else 9223372036854775807}}
                else:
                    assert self.path == '/api/v1/directives/receipt'
                    assert body == {'id': 'directive', 'stage': 'EMITTED_TO_HOOK', 'sessionId': 'native-session', 'claimId': 'claim'}
                    state['emitted'] = True
                    response = {'success': True}
                data = json.dumps(response).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError): pass  # Expected deadline cancellation.
            except Exception as error: state['errors'].append(type(error).__name__)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    config.write_text(json.dumps({'serverUrl': f'http://127.0.0.1:{server.server_port}', 'accessToken': token}), encoding='utf-8')
    config.chmod(0o600)
    command = [binary, 'agents', 'hook', '--config', str(config)]
    payload = {'session_id': 'native-session', 'hook_event_name': 'PreToolUse', 'tool_input': 'PRIVATE_TOOL_INPUT', 'transcript_path': 'PRIVATE_TOOL_INPUT'}

    def run(body, env=None):
        result = subprocess.run(command, input=body, capture_output=True, timeout=6, env=BASE_ENV if env is None else env)
        assert result.returncode == 0, 'Hook must never fail an agent operation'
        assert result.stderr == b'', 'Hook diagnostics must remain silent'
        assert token.encode() not in result.stdout and b'PRIVATE_' not in result.stdout
        return json.loads(result.stdout)

    try:
        data = json.dumps(payload).encode()
        result = run(data)
        assert result == {'hookSpecificOutput': {'hookEventName': 'PreToolUse', 'additionalContext': 'TeamCode capacity guidance: Reduce optional parallel work.'}}
        assert state['emitted'] and len(state['calls']) == 2
        assert run(data) == {}, 'Already emitted guidance must be suppressed'
        state['emitted'] = False
        payload['hook_event_name'] = 'UserPromptSubmit'
        data = json.dumps(payload).encode()
        assert run(data + b' ' * (65536 - len(data)))['hookSpecificOutput']['hookEventName'] == 'UserPromptSubmit'
        before = len(state['calls'])
        for body in [b'', b'{', b'\xff', b'[]', data + b' ' * (65537 - len(data)),
                     b'{"session_id":12,"hook_event_name":"PreToolUse"}',
                     b'{"session_id":"bad/session","hook_event_name":"PreToolUse"}',
                     b'{"session_id":"native-session","hook_event_name":"Stop"}']:
            assert run(body) == {}
        assert len(state['calls']) == before, 'Invalid input reached the server'
        for mode in ['expired', 'outage', 'slow']:
            state.update(mode=mode, emitted=False)
            started = time.monotonic()
            assert run(data) == {}
            assert time.monotonic() - started < 5, 'Guidance lookup exceeded its deadline'
        # With the launch's identifier, Stop and UserPromptSubmit report only whether the agent waits or works.
        env = dict(BASE_ENV, TEAMCODE_RUN_ID=RUN, TEAMCODE_ACTIVITY='1')
        state['calls'].clear()
        assert run(b'{"session_id":"native-session","hook_event_name":"Stop","last_assistant_message":"PRIVATE_TOOL_INPUT"}', env) == {}
        assert state['calls'] == [(f'/api/v1/runs/{RUN}/activity', {'state': 'waiting_for_human'})], state['calls']
        state['calls'].clear()
        assert run(b'{"session_id":"native-session","hook_event_name":"Notification","notification_type":"auth_success"}', env) == {}
        assert run(b'{"session_id":"native-session","hook_event_name":"Stop"}', dict(BASE_ENV, TEAMCODE_RUN_ID='../x', TEAMCODE_ACTIVITY='1')) == {}
        assert state['calls'] == [], 'Only a canonical launch identifier and a state-bearing event are reported'
        # The identifier alone, without the launch asking for reports (opt-out, older server), reports nothing.
        assert run(b'{"session_id":"native-session","hook_event_name":"Stop"}', dict(BASE_ENV, TEAMCODE_RUN_ID=RUN)) == {}
        assert state['calls'] == [], state['calls']
        state.update(mode='fresh', emitted=False)
        assert run(data, env)['hookSpecificOutput']['hookEventName'] == 'UserPromptSubmit'
        assert [call for call in state['calls'] if call[0].startswith('/api/v1/runs/')] == [(f'/api/v1/runs/{RUN}/activity', {'state': 'working'})]
        # A writer holding stdin open cannot stall the agent, including an otherwise complete JSON body.
        child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=BASE_ENV)
        try:
            child.stdin.write(data); child.stdin.flush()
            child.wait(timeout=3)
            assert child.returncode == 0 and json.loads(child.stdout.read()) == {} and child.stderr.read() == b''
        finally:
            child.stdin.close()
            if child.poll() is None: child.kill(); child.wait(timeout=3)
            child.stdout.close(); child.stderr.close()
        config.unlink()
        assert run(data) == {}, 'Missing configuration must not surface in agent context'
        assert not state['errors'], state['errors']
        print('PASS: native stdin guidance, both events, activity reports, scoped receipts, deduplication, Unicode config path, privacy, byte bound, input/network deadlines and silent outages.')
    finally:
        server.shutdown(); server.server_close(); worker.join(timeout=3)
