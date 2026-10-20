#!/usr/bin/env python3
"""Exercise first-run admin, browser forms, native CLI and persistent sessions using an isolated MySQL."""
import argparse
import base64
import http.cookiejar
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
args = parser.parse_args()
binary, jar = str(Path(args.binary).resolve()), str(Path(args.jar).resolve())
name = 'teamcode-smoke-' + secrets.token_hex(6)
process = None
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

with tempfile.TemporaryDirectory(prefix='teamcode-team-smoke-') as directory:
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
        # Docker can return before the owned container's host port is published.
        port_deadline = time.monotonic() + 30
        while True:
            binding = command(['docker', 'port', name, '3306/tcp']).stdout.strip()
            if binding and binding.rsplit(':', 1)[-1].isdigit():
                db_port = binding.rsplit(':', 1)[-1]
                break
            state = json.loads(command(['docker', 'inspect', '--format', '{{json .State}}', name]).stdout)
            if state.get('Status') in ('exited', 'dead'):
                raise RuntimeError('Owned MySQL container exited before port publication: exit=' + str(state.get('ExitCode')) + ', oom=' + str(state.get('OOMKilled')))
            if time.monotonic() >= port_deadline:
                raise RuntimeError('Owned MySQL port was not published within 30 seconds')
            time.sleep(.1)
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
        identity_file = root / 'config/teamcode/installation-id'
        installation = identity_file.read_text()
        assert re.fullmatch(r'[0-9a-f-]{36}', installation)
        status, device_json = request('/api/v1/devices', token=token)
        devices = json.loads(device_json)
        assert status == 200 and len(devices) == 1 and devices[0]['current'] and devices[0]['activeSessions'] == 1
        device_id = devices[0]['id']
        assert 'admin (owner)' in command([binary, 'whoami'], env=env, timeout=15).stdout
        assert device_id in command([binary, 'devices', 'list'], env=env, timeout=15).stdout
        assert 'current' in command([binary, 'auth', 'sessions', 'list'], env=env, timeout=15).stdout
        command([binary, 'devices', 'rename', device_id, '--name', 'Administrator workstation'], env=env, timeout=15)
        key_result = command([binary, 'keys', 'create', '--name', "CI's native runner", '--scopes', 'inference,usage:read,guidance', '--days', '1', '--json'], env=env, timeout=15)
        service_key = json.loads(key_result.stdout)
        key_secret = service_key['accessToken']
        key_id = service_key['metadata']['id']
        assert key_secret.startswith('tcsk_') and key_secret not in key_result.stderr
        key_list = command([binary, 'keys', 'list'], env=env, timeout=15).stdout
        assert key_id in key_list and key_secret not in key_list
        ci_env = dict(env, XDG_CONFIG_HOME=str(root / 'ci-config'))
        command([binary, 'configure', '--server', origin], env=ci_env, input=key_secret + '\n', timeout=15)
        assert 'admin (owner)' in command([binary, 'whoami'], env=ci_env, timeout=15).stdout
        assert request('/api/v1/capacity', token=key_secret)[0] == 200
        for denied in ('/api/v1/service-keys', '/api/v1/accounts', '/api/v1/members', '/api/v1/quotas', '/api/v1/status', '/api/v1/audit'):
            assert request(denied, token=key_secret)[0] == 403, 'Service key escaped its owner/scopes'
        assert request('/api/v1/members', {'username': 'forbidden', 'role': 'ADMIN'}, key_secret)[0] == 403
        cookies = http.cookiejar.CookieJar()
        browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
        assert request('/dashboard/login', {'username': 'admin', 'password': password}, opener=browser, form=True, csrf_origin=origin)[0] == 200
        status, html = request('/dashboard/members', opener=browser)
        assert status == 200
        csrf = re.search(r"name='csrf' value='([^']+)'", html).group(1)
        status, html = request('/dashboard/members', {'csrf': csrf, 'username': 'alice', 'role': 'MEMBER'}, opener=browser, form=True, csrf_origin=origin)
        assert status == 200 and 'Temporary password' in html
        temporary = re.search(r'<code>([^<]+)</code>', html).group(1)
        status, login = request('/api/v1/auth/login', {'username': 'alice', 'password': temporary})
        assert status == 200
        member_token = json.loads(login)['accessToken']
        assert request('/api/v1/status', token=member_token)[0] == 403
        assert request('/api/v1/members', token=member_token)[0] == 403
        assert request('/api/v1/register', {})[0] == 401
        assert request('/dashboard/accounts', opener=browser)[0] == 200
        status, audit_json = request('/api/v1/audit', token=token)
        journal = json.loads(audit_json)
        assert status == 200 and len(journal['events']) <= 50
        actions = {event['action'] for event in journal['events']}
        assert {'member.bootstrap', 'member.created', 'service_key.created', 'device.renamed'} <= actions
        assert not any(secret in audit_json for secret in (initial, password, temporary, token, key_secret))
        assert request('/api/v1/audit', token=member_token)[0] == 403
        status, audit_html = request('/dashboard/audit', opener=browser)
        assert status == 200 and 'Audit history' in audit_html and 'member.created' in audit_html
        stop_own_process()
        start()
        assert 'temporary administrator password' not in log.read_text()
        assert request('/api/v1/members', token=token)[0] == 200
        command([binary, 'status'], env=env, timeout=15)
        assert request('/api/v1/capacity', token=key_secret)[0] == 200, 'Service key was not persisted across restart'
        command([binary, 'keys', 'revoke', key_id], env=env, timeout=15)
        assert request('/api/v1/auth/me', token=key_secret)[0] == 401
        rejected = subprocess.run([binary, 'whoami'], env=ci_env, capture_output=True, text=True, timeout=15)
        assert rejected.returncode != 0 and key_secret not in rejected.stdout + rejected.stderr
        assert key_secret not in log.read_text()
        command([binary, 'logout'], env=env, timeout=15)
        assert not (root / 'config/teamcode/config.json').exists()
        assert identity_file.read_text() == installation
        assert request('/api/v1/members', token=token)[0] == 401
        audit_status, audit_after = request('/dashboard/audit', opener=browser)
        assert audit_status == 200 and 'session.logout' in audit_after and 'service_key.revoked' in audit_after
        command([binary, 'login', '--server', origin, '--username', 'admin', '--password-stdin'], env=env, input=password + '\n', timeout=30)
        assert identity_file.read_text() == installation
        token = json.loads((root / 'config/teamcode/config.json').read_text())['accessToken']
        devices = json.loads(request('/api/v1/devices', token=token)[1])
        assert len(devices) == 1 and devices[0]['id'] == device_id and devices[0]['name'] == 'Administrator workstation'
        # A different client on the same workstation creates another token under the same device.
        extra = json.loads(request('/api/v1/auth/login', {'username': 'admin', 'password': password, 'installationId': installation})[1])['accessToken']
        assert json.loads(request('/api/v1/devices', token=extra)[1])[0]['activeSessions'] == 2
        command([binary, 'devices', 'revoke', device_id], env=env, timeout=15)
        assert request('/api/v1/auth/me', token=token)[0] == 401 and request('/api/v1/auth/me', token=extra)[0] == 401
        assert request('/dashboard/devices', opener=browser)[0] == 200, 'Revoking a CLI device signed out the independent browser'
        assert 'Administrator workstation' not in request('/dashboard/devices', opener=browser)[1]
        assert password not in log.read_text() and temporary not in log.read_text()
        print('Team smoke passed: bootstrap, password change, browser member creation, native identity/session commands, device rename, login/logout persistence, scoped CI key creation/persistence/revocation, scoped multi-session revocation and persistent admin audit history.')
    finally:
        stop_own_process()
        if container_created:
            subprocess.run(['docker', 'rm', '-f', '-v', name], capture_output=True, check=True)
