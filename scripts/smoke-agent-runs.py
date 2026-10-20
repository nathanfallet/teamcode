#!/usr/bin/env python3
"""Actual native launchers against the owned AgentRunHttpFixture, never a real provider."""
import argparse
import json
import os
import shlex
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.request
from urllib.parse import urlsplit

FAKE = r'''import json, os, re, sys, time, urllib.request
from pathlib import Path
from urllib.parse import urlparse
agent = os.environ['TEAMCODE_RUN_FIXTURE_AGENT']
if agent == 'claude':
    base = os.environ['ANTHROPIC_BASE_URL']
    token = os.environ['ANTHROPIC_API_KEY']
    endpoint = base + '/v1/messages'
    body = {'model':'claude-test','stream':True,'max_tokens':1,'messages':[{'role':'user','content':'synthetic'}]}
else:
    config = next(arg for arg in sys.argv[1:] if arg.startswith('model_providers.teamcode='))
    base = re.search(r'base_url="([^"]+)"', config).group(1)
    token = os.environ['TEAMCODE_LOCAL_TOKEN']
    endpoint = base + '/responses'
    body = {'model':'gpt-test','stream':True,'store':False,'max_output_tokens':1,'input':'synthetic'}
assert '/_teamcode/runs/' in urlparse(base).path
headers = {'Authorization':'Bearer ' + token, 'Content-Type':'application/json', 'session-id':'native-fixture-' + agent}
with urllib.request.urlopen(urllib.request.Request(endpoint, data=json.dumps(body).encode(), headers=headers), timeout=15) as response:
    payload = response.read()
    assert response.status == 200 and (b'message_stop' in payload or b'response.completed' in payload)
Path(os.environ['TEAMCODE_RUN_FIXTURE_READY']).write_text(json.dumps({'arguments':sys.argv[1:],'prefix':urlparse(base).path}))
if agent == 'claude': time.sleep(34)
sys.exit(0 if agent == 'claude' else 7)
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', required=True)
    args = parser.parse_args()
    binary = str(Path(args.binary).resolve())
    fixture = json.loads(sys.stdin.readline())
    origins = [fixture['origin'], fixture['otherOrigin']]
    for origin in origins:
        parsed = urlsplit(origin)
        assert parsed.scheme == 'http' and parsed.hostname == '127.0.0.1' and parsed.port in range(1, 65536)
        assert parsed.username is None and parsed.password is None and not parsed.path and not parsed.query and not parsed.fragment

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    opener = urllib.request.build_opener(NoRedirect)

    def api(path, other=False):
        request = urllib.request.Request(origins[int(other)] + path, headers={'Authorization': 'Bearer ' + fixture['accessToken']})
        with opener.open(request, timeout=5) as response:
            return json.load(response)

    def until(probe, seconds=45):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = probe()
            if value:
                return value
            time.sleep(.2)
        raise AssertionError('Owned native run fixture did not reach the expected state')

    with tempfile.TemporaryDirectory(prefix='teamcode-run-native-') as scratch:
        root = Path(scratch)
        os.chmod(root, 0o700)
        fake = root / 'fake-agent.py'
        fake.write_text(FAKE)
        for agent in ('claude', 'codex'):
            wrapper = root / (agent + ('.cmd' if os.name == 'nt' else ''))
            if os.name == 'nt':
                wrapper.write_text('@echo off\r\n"' + sys.executable + '" "' + str(fake) + '" %*\r\n')
            else:
                wrapper.write_text('#!/bin/sh\nexec ' + shlex.quote(sys.executable) + ' ' + shlex.quote(str(fake)) + ' "$@"\n')
                wrapper.chmod(0o700)
        env = dict(os.environ, XDG_CONFIG_HOME=str(root / 'config'), TEAMCODE_DISABLE_AUTOUPDATE='1', PATH=str(root) + os.pathsep + os.environ['PATH'])
        configured = subprocess.run([binary, 'configure', '--server', origins[0]], input=fixture['accessToken'] + '\n', text=True, capture_output=True, env=env, timeout=15)
        assert configured.returncode == 0 and fixture['accessToken'] not in configured.stdout + configured.stderr
        launched = []
        for agent, expected, workload in [('claude', 0, 'background'), ('codex', 7, 'interactive')]:
            ready = root / (agent + '-ready.json')
            child_env = dict(env, TEAMCODE_RUN_FIXTURE_AGENT=agent, TEAMCODE_RUN_FIXTURE_READY=str(ready))
            argv = [binary, 'run', agent, '--no-guidance', '--workload', workload, '--']
            argv += ['--dangerously-skip-permissions', 'literal argument'] if agent == 'claude' else ['exec', '--', 'literal argument']
            with (root / (agent + '.log')).open('w') as log:
                child = subprocess.Popen(argv, env=child_env, stdout=log, stderr=subprocess.STDOUT)
            try:
                until(lambda: ready.exists())
                plans = json.loads(ready.read_text())
                assert '/_teamcode/runs/' in plans['prefix']
                if agent == 'claude':
                    assert plans['arguments'] == ['--dangerously-skip-permissions', 'literal argument']
                else:
                    assert plans['arguments'][-2:] == ['--', 'literal argument']
                run = until(lambda: next((run for run in api('/api/v1/runs', other=True)['runs']
                    if run['agent'] == agent and run['completed'] == 1 and run['chargedAndReservedUnits'] == 6), None))
                launched.append(run['id'])
                assert run['chargedAndReservedUnits'] == 6 and run['completed'] == 1 and run['workload'] == workload
                if agent == 'claude':
                    first_heartbeat = run['heartbeatAtMillis']
                    until(lambda: api('/api/v1/runs/' + run['id'], other=True)['run']['heartbeatAtMillis'] > first_heartbeat)
                assert child.wait(timeout=50) == expected
                final = until(lambda: (value if (value := api('/api/v1/runs/' + run['id'], other=True))['run']['state'] == 'ENDED' else None))
                assert final['run']['exitCode'] == expected and final['run']['activeRequests'] == 0
                assert len(final['conversations']) == 1 and final['conversations'][0]['pace'] is None
                assert fixture['accessToken'] not in (root / (agent + '.log')).read_text()
            finally:
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait(timeout=5)
        listed = subprocess.run([binary, 'sessions', 'list', '--json'], text=True, capture_output=True, env=env, timeout=15)
        assert listed.returncode == 0
        assert set(launched) == {run['id'] for run in json.loads(listed.stdout)['runs']}
        shown = subprocess.run([binary, 'sessions', 'show', launched[0]], text=True, capture_output=True, env=env, timeout=15)
        assert shown.returncode == 0 and json.loads(shown.stdout)['run']['id'] == launched[0]
        assert fixture['accessToken'] not in listed.stdout + listed.stderr + shown.stdout + shown.stderr
        print('Native registry passed: two launches, real quiet heartbeat, cross-replica metadata, exact arguments, synthetic streaming, nonzero exit and sessions JSON.')


if __name__ == '__main__':
    main()
