#!/usr/bin/env python3
"""Run two independent server processes against one isolated MySQL; no real providers or cluster."""
import argparse
import base64
import concurrent.futures
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
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--jar', required=True)
args = parser.parse_args()
jar = str(Path(args.jar).resolve())
name = 'teamcode-replicas-' + secrets.token_hex(6)
children = []
created = False

def command(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=120)

def request(origin, path, data=None, token=None, method=None, dashboard=False):
    headers = {'Content-Type': 'application/json'}
    if token:
        if dashboard:
            headers['Cookie'] = 'teamcode_session=' + token
        else:
            headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(origin + path, None if data is None else json.dumps(data).encode(), headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()

def await_ready(origin):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            if request(origin, '/ready')[0] == 200:
                return
        except OSError:
            pass
        time.sleep(.25)
    raise RuntimeError('Replica readiness timed out')

with tempfile.TemporaryDirectory(prefix='teamcode-replicas-') as directory:
    root = Path(directory)
    db_password = secrets.token_urlsafe(32)
    password = secrets.token_urlsafe(32)
    ports = []
    for _ in range(2):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            ports.append(listener.getsockname()[1])
    origins = [f'http://127.0.0.1:{port}' for port in ports]
    env = dict(os.environ, TEAMCODE_MODE='server', TEAMCODE_HOST='127.0.0.1',
               TEAMCODE_PUBLIC_URL=origins[0], TEAMCODE_DATABASE_USER='teamcode',
               TEAMCODE_DATABASE_PASSWORD=db_password,
               TEAMCODE_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode())
    logs = [root / f'replica-{i}.log' for i in range(2)]
    try:
        command('docker', 'run', '-d', '--name', name, '--tmpfs', '/var/lib/mysql:rw,nosuid,size=1g',
                '-p', '127.0.0.1::3306', '-e', 'MYSQL_DATABASE=teamcode', '-e', 'MYSQL_USER=teamcode',
                '-e', f'MYSQL_PASSWORD={db_password}', '-e', f'MYSQL_ROOT_PASSWORD={db_password}', 'mysql:8.4')
        created = True
        db_port = command('docker', 'port', name, '3306/tcp').stdout.strip().rsplit(':', 1)[1]
        env['TEAMCODE_DATABASE_URL'] = f'jdbc:mysql://127.0.0.1:{db_port}/teamcode'
        for _ in range(180):
            probe = subprocess.run(['docker', 'exec', name, 'mysqladmin', 'ping', '-h', '127.0.0.1', '--silent'], capture_output=True)
            if probe.returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Test MySQL did not start')

        def start(index):
            with logs[index].open('a') as log:
                child = subprocess.Popen(['java', '-jar', jar], env=dict(env, TEAMCODE_PORT=str(ports[index])), stdout=log, stderr=subprocess.STDOUT)
            children.append(child)
            return child

        first = start(0)
        start(1)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(await_ready, origins))
        bootstrap = [match for log in logs for match in re.findall(r'TeamCode temporary administrator password: (\S+)', log.read_text())]
        assert len(bootstrap) == 1
        status, body = request(origins[0], '/api/v1/auth/login', {'username': 'admin', 'password': bootstrap[0]})
        assert status == 200
        temporary_token = json.loads(body)['accessToken']
        assert json.loads(body)['member']['role'] == 'OWNER'
        assert request(origins[1], '/api/v1/auth/me', token=temporary_token)[0] == 200
        assert request(origins[1], '/api/v1/auth/password', {'currentPassword': bootstrap[0], 'newPassword': password}, temporary_token)[0] == 200
        assert request(origins[0], '/api/v1/auth/me', token=temporary_token)[0] == 401
        status, body = request(origins[0], '/api/v1/auth/login', {'username': 'admin', 'password': password})
        assert status == 200
        token = json.loads(body)['accessToken']
        assert json.loads(body)['member']['role'] == 'OWNER'
        owner_id = json.loads(body)['member']['id']
        status, body = request(origins[1], '/api/v1/members/' + owner_id, {'role': 'ADMIN', 'disabled': False}, token, method='PATCH')
        assert status == 409 and json.loads(body)['error']['type'] == 'last_owner'
        status, body = request(origins[1], '/api/v1/members/' + owner_id + '/reset-password', {}, token)
        assert status == 409 and json.loads(body)['error']['type'] == 'last_owner'
        status, body = request(origins[1], '/api/v1/forecasts', token=token)
        forecast = json.loads(body)
        assert status == 200 and forecast['account'] is None and forecast['windows'] == []
        assert forecast['pressureThreshold'] == .9 and not forecast['truncated']
        status, body = request(origins[0], '/dashboard/assets/forecasts.js', token=token, dashboard=True)
        assert status == 200 and 'new EventSource(url)' in body
        status, body = request(origins[1], '/dashboard/forecasts', token=token, dashboard=True)
        assert status == 200 and 'forecast-live-status' in body and '/dashboard/assets/forecasts.js' in body
        status, body = request(origins[0], '/api/v1/members', {'username': 'replica-member', 'role': 'MEMBER'}, token)
        assert status == 201
        created_member = json.loads(body)
        member_id = created_member['member']['id']
        status, body = request(origins[1], '/api/v1/members', token=token)
        assert status == 200 and len(json.loads(body)) == 2
        # Group roles, caps and pauses are shared even when a replica is replaced.
        member_password = secrets.token_urlsafe(32)
        status, body = request(origins[1], '/api/v1/auth/login', {'username': 'replica-member', 'password': created_member['temporaryPassword']})
        assert status == 200
        pending_member = json.loads(body)['accessToken']
        assert request(origins[0], '/api/v1/auth/password', {'currentPassword': created_member['temporaryPassword'], 'newPassword': member_password}, pending_member)[0] == 200
        status, body = request(origins[1], '/api/v1/auth/login', {'username': 'replica-member', 'password': member_password})
        assert status == 200
        member_token = json.loads(body)['accessToken']
        override_path = '/api/v1/members/' + member_id + '/budget-overrides'
        status, body = request(origins[0], override_path, {'dailyUnits': 750000, 'durationMinutes': 60, 'reason': 'Replica exception <audited>'}, token)
        assert status == 201
        override_id = json.loads(body)['id']
        assert request(origins[1], override_path, token=member_token)[0] == 403
        status, body = request(origins[1], '/api/v1/capacity', token=member_token)
        capacity = json.loads(body)
        assert status == 200 and capacity['dailyUnits'] == 750000 and capacity['baseDailyUnits'] == 500000
        assert capacity['budgetOverride']['id'] == override_id and 'Replica exception' not in body
        status, body = request(origins[1], '/dashboard/capacity/' + member_id + '/overrides', token=token, dashboard=True)
        assert status == 200 and 'Replica exception &lt;audited&gt;' in body
        for review_path, collection in [('/api/v1/usage/pending', 'requests'), ('/api/v1/usage/reconciliations', 'reconciliations')]:
            status, body = request(origins[0], review_path, token=token)
            assert status == 200 and json.loads(body)[collection] == []
            assert request(origins[1], review_path, token=member_token)[0] == 403
        run_id = str(uuid.uuid4())
        run_path = '/api/v1/runs/' + run_id
        status, body = request(origins[0], '/api/v1/runs', {'id': run_id, 'agent': 'claude', 'workload': 'background'}, member_token)
        assert status == 201 and 'chargedAndReservedUnits' not in json.loads(body)
        assert request(origins[1], run_path + '/heartbeat', {}, member_token)[0] == 200
        assert request(origins[1], run_path + '/policy', {'budgetUnits': 12345, 'maxConcurrent': 1}, token, method='PUT')[0] == 200
        group_policy = {'name': 'Replica group <shared>', 'dailyUnits': 2000, 'maxConcurrent': 1}
        status, body = request(origins[0], '/api/v1/groups', group_policy, token)
        assert status == 201
        group_path = '/api/v1/groups/' + json.loads(body)['group']['id']
        assignment_path = group_path + '/members/' + member_id
        assert request(origins[1], assignment_path, {'role': 'MANAGER'}, token, method='PUT')[0] == 200
        status, body = request(origins[0], group_path, token=member_token)
        assert status == 200 and json.loads(body)['group']['canManage'] is True
        assert request(origins[0], group_path, group_policy, member_token, method='PUT')[0] == 403
        assert request(origins[1], assignment_path + '/pause', {'paused': True}, member_token, method='PATCH')[0] == 200
        status, body = request(origins[0], '/api/v1/capacity', token=member_token)
        group_capacity = json.loads(body)['groups'][0]
        assert status == 200 and group_capacity['paused'] and group_capacity['dailyUnits'] == 2000
        status, body = request(origins[0], '/v1/messages', {'model': 'claude-test', 'stream': True, 'max_tokens': 1, 'messages': [{'role': 'user', 'content': 'synthetic'}]}, member_token)
        assert status == 403 and json.loads(body)['error']['type'] == 'group_paused'
        assert request(origins[1], assignment_path, {'role': 'MEMBER', 'paused': True}, token, method='PUT')[0] == 200
        assert request(origins[0], assignment_path + '/pause', {'paused': False}, member_token, method='PATCH')[0] == 403
        assert request(origins[1], assignment_path, {'role': 'MANAGER', 'paused': True}, token, method='PUT')[0] == 200
        status, body = request(origins[0], '/dashboard/groups', token=token, dashboard=True)
        assert status == 200 and 'Replica group &lt;shared&gt;' in body and 'Replica group <shared>' not in body
        # The real application wires durable controller configuration into both replicas.
        pacing_path = '/api/v1/policy/pacing'
        status, body = request(origins[0], pacing_path, token=token)
        assert status == 200 and not json.loads(body).get('observationOnly', False)
        assert request(origins[0], pacing_path, {'observationOnly': True}, token, method='PUT')[0] == 200
        status, body = request(origins[1], pacing_path, token=token)
        assert status == 200 and json.loads(body)['observationOnly'] is True
        status, body = request(origins[1], '/api/v1/directives/preview', token=token)
        assert status == 200 and json.loads(body)['settings']['observationOnly'] is True
        status, body = request(origins[0], '/api/v1/directives/current', token=token)
        assert status == 200 and json.loads(body)['directive'] is None
        # Kill only our exact child, deliberately bypassing graceful shutdown to exercise recovery.
        first.kill()
        first.wait(timeout=10)
        assert request(origins[1], '/api/v1/status', token=token)[0] == 200
        start(0)
        await_ready(origins[0])
        assert request(origins[0], '/api/v1/members', token=token)[0] == 200
        assert request(origins[0], '/api/v1/usage/pending', token=token)[0] == 200
        assert request(origins[1], '/api/v1/usage/reconciliations', token=token)[0] == 200
        status, body = request(origins[0], override_path, token=token)
        assert status == 200 and json.loads(body)['activeOverrideId'] == override_id
        assert request(origins[1], override_path + '/' + override_id + '/revoke', {'reason': 'Restart verified'}, token)[0] == 200
        status, body = request(origins[0], '/api/v1/capacity', token=member_token)
        assert status == 200 and json.loads(body)['budgetOverride'] is None and json.loads(body)['dailyUnits'] == 500000
        status, body = request(origins[0], run_path, token=member_token)
        assert status == 200 and json.loads(body)['run']['policy']['budgetUnits'] == 12345
        assert request(origins[1], run_path + '/end', {'exitCode': 7}, member_token)[0] == 200
        status, body = request(origins[0], run_path, token=member_token)
        assert status == 200 and json.loads(body)['run']['state'] == 'ENDED' and json.loads(body)['run']['exitCode'] == 7
        status, body = request(origins[0], group_path, token=member_token)
        restored_group = json.loads(body)
        assert status == 200 and restored_group['group']['canManage'] and restored_group['members'][0]['paused']
        assert restored_group['group']['dailyUnits'] == 2000
        status, body = request(origins[0], pacing_path, token=token)
        assert status == 200 and json.loads(body)['observationOnly'] is True
        assert request(origins[1], pacing_path, {'observationOnly': False}, token, method='PUT')[0] == 200
        status, body = request(origins[0], pacing_path, token=token)
        assert status == 200 and not json.loads(body).get('observationOnly', False)
        assert sum(log.read_text().count('TeamCode temporary administrator password:') for log in logs) == 1
        # Readiness must reflect shared storage availability; liveness must not trigger a database-outage restart storm.
        command('docker', 'pause', name)
        for origin in origins:
            assert request(origin, '/ready')[0] == 503
            assert request(origin, '/health')[0] == 200
        command('docker', 'unpause', name)
        for origin in origins:
            await_ready(origin)
        assert request(origins[1], '/api/v1/auth/logout', {}, token)[0] == 200
        assert request(origins[0], '/api/v1/auth/me', token=token)[0] == 401
        assert request(origins[0], pacing_path, {'observationOnly': True}, token, method='PUT')[0] == 401
        assert request(origins[0], assignment_path + '/pause', {'paused': False}, token, method='PATCH')[0] == 401
        assert all(all(value not in log.read_text() for value in (password, db_password, member_password, member_token, created_member['temporaryPassword'])) for log in logs)
        print('Replica smoke passed: concurrent startup/migration, one admin, shared sessions/members/groups/controller mode/temporary quota overrides, scoped manager revocation and pause enforcement, cross-replica revocation, killed-pod recovery, database readiness and recovery.')
    finally:
        for child in children:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
        if created:
            command('docker', 'rm', '-f', '-v', name)
