#!/usr/bin/env python3
"""Run the Windows native CLI with synthetic authentication, ACL inspection and real child processes."""
import argparse
import concurrent.futures
import contextlib
import ctypes
from ctypes import wintypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--binary', type=Path, required=True)
parser.add_argument('--allow-machine-test-certificate', action='store_true',
                    help='Allow the HTTPS fixture to temporarily trust its own certificate on a disposable elevated Windows runner.')
args = parser.parse_args()
assert os.name == 'nt', 'This acceptance test must run on Windows'
assert args.allow_machine_test_certificate, 'Run this full acceptance in a disposable elevated environment with --allow-machine-test-certificate'
binary = str(args.binary.resolve())
expected_version = subprocess.check_output([binary, 'version'], text=True, timeout=15).strip().split(' ', 1)[1]
node = Path(shutil.which('node')).resolve()
powershell = str(Path(shutil.which('pwsh')).resolve())
team_token = secrets.token_hex(32)
local_token = secrets.token_hex(32)
initial_team_token = secrets.token_hex(32)
initial_password = 'Synthetic initial ' + secrets.token_hex(12)
replacement_password = 'Synthetic replacement ' + secrets.token_hex(12)
login_password = initial_password
login_change_required = True
revoked = False
calls = []
workloads = []
checks = []
stream_started = threading.Event()
stream_release = threading.Event()
stream_release.set()

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        checks.append(self.path)
        if revoked or self.headers.get('Authorization') != 'Bearer ' + team_token:
            self.send_error(401)
            return
        payload = ({'id': 'member', 'username': 'synthetic', 'role': 'MEMBER', 'disabled': False, 'passwordChangeRequired': False}
                   if self.path.endswith('/me') else {'mode': 'fixture', 'activeRequests': 0, 'completedRequests': len(calls)}
                   if self.path.endswith('/status') else {'serverVersion': 'test', 'mode': 'fixture', 'features': []})
        data = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        global login_password, login_change_required, revoked
        body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        if self.path.startswith('/api/v1/auth/'):
            payload = json.loads(body) if body else {}
            if self.path.endswith('/login'):
                assert payload['username'] == 'synthetic' and payload['password'] == login_password
                result = {'accessToken': initial_team_token if login_change_required else team_token,
                          'expiresAt': int(time.time() * 1000) + 3_600_000,
                          'member': {'id': 'member', 'username': 'synthetic', 'role': 'MEMBER',
                                     'disabled': False, 'passwordChangeRequired': login_change_required}}
            elif self.path.endswith('/password'):
                assert self.headers.get('Authorization') == 'Bearer ' + initial_team_token
                assert payload == {'currentPassword': initial_password, 'newPassword': replacement_password}
                login_password, login_change_required = replacement_password, False
                result = {'success': True}
            elif self.path.endswith('/logout'):
                assert self.headers.get('Authorization') == 'Bearer ' + team_token
                revoked = True
                result = {'success': True}
            else:
                self.send_error(404)
                return
            data = json.dumps(result).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        assert self.headers.get('Authorization') == 'Bearer ' + team_token
        assert self.headers.get('x-api-key') is None
        if self.path == '/api/v1/directives/claim':
            assert set(json.loads(body)) == {'sessionId', 'stage'}
            data = b'{"directive":null}'
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return
        assert self.path in ('/v1/messages', '/v1/responses')
        assert json.loads(body)['model'] == 'fixture'
        calls.append(self.path)
        workloads.append(self.headers.get('x-teamcode-workload'))
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.end_headers()
        self.wfile.write(b'data: {"start":true}\n\n')
        self.wfile.flush()
        stream_started.set()
        assert stream_release.wait(30), 'Test did not release the active stream'
        time.sleep(.4)
        self.wfile.write(b'data: {"done":true}\n\n')
        self.wfile.flush()

server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
origin = f'http://127.0.0.1:{server.server_port}'
proxy = None
service_env = None
service_installed = False
directory = tempfile.mkdtemp(prefix='teamcode-windows-')
try:
    with contextlib.nullcontext(directory):
        root = Path(directory) / 'Unicode équipe with spaces'
        root.mkdir()
        binary = str(shutil.copy2(binary, root / 'teamcode.exe'))
        env = dict(os.environ, XDG_CONFIG_HOME=str(root / 'configuration'), TEAMCODE_LOCAL_TOKEN=local_token)
        config = Path(env['XDG_CONFIG_HOME']) / 'teamcode/config.json'

        def command(*arguments, stdin=None, check=True):
            # Keep stdin bytes literal: Python text mode would convert LF to CRLF on Windows.
            result = subprocess.run([binary, *arguments], env=env,
                                    input=None if stdin is None else stdin.encode('utf-8'), capture_output=True, timeout=45)
            result.stdout = result.stdout.decode('utf-8').replace('\r\n', '\n')
            result.stderr = result.stderr.decode('utf-8').replace('\r\n', '\n')
            assert all(secret not in result.stdout + result.stderr for secret in
                       (team_token, local_token, initial_team_token, initial_password, replacement_password))
            if check:
                assert result.returncode == 0, f'{arguments} (upstream checks: {len(checks)}): {result.stdout}\n{result.stderr}'
            return result

        def powershell_command(script, variables, executable=powershell):
            if executable == 'powershell.exe':
                variables = dict(variables, PSModulePath=str(Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/Modules'))
            # Windows PowerShell 5.1 can take over 30 s to start cold on a hosted runner (a 0.8.0 release timed out in Get-Acl).
            result = subprocess.run([executable, '-NoProfile', '-NonInteractive', '-Command', script],
                                    env=variables, capture_output=True, encoding='utf-8', errors='replace', timeout=120)
            assert all(secret not in result.stdout + result.stderr for secret in
                       (team_token, local_token, initial_team_token, initial_password, replacement_password))
            assert result.returncode == 0, 'Synthetic PowerShell fixture failed: ' + result.stderr
            return result

        command('configure', '--server', origin, stdin=team_token + '\n')
        command('login', '--server', origin, '--username', 'synthetic', '--password-stdin',
                stdin=initial_password + '\n' + replacement_password + '\n' + replacement_password + '\n')
        assert not login_change_required, 'Native login did not complete the required password change'
        original_config = config.read_bytes()
        assert json.loads(original_config)['accessToken'] == team_token
        command('configure', '--server', origin, stdin=team_token + '\n')
        assert config.read_bytes() == original_config, 'Atomic replacement changed the saved values'
        # The fixture answers the quota and account routes with an unrelated body: status must still succeed without them.
        status = command('status').stdout
        assert 'Mode: fixture' in status and 'Your quota: unavailable' in status and 'Pool accounts: unavailable' in status, status
        acl_script = r'''
        $ErrorActionPreference = 'Stop'
        $acl = Get-Acl -LiteralPath $env:TEAMCODE_ACL_PATH
        [pscustomobject]@{
          user = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
          owner = $acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
          protected = $acl.AreAccessRulesProtected
          rules = @($acl.Access | ForEach-Object {
            [pscustomobject]@{
              sid = $_.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
              inherited = $_.IsInherited
              type = $_.AccessControlType.ToString()
              full = ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq [Security.AccessControl.FileSystemRights]::FullControl
            }
          })
        } | ConvertTo-Json -Depth 4 -Compress
        '''
        for path in (config, config.parent):
            result = powershell_command(acl_script, dict(env, TEAMCODE_ACL_PATH=str(path)), 'powershell.exe')
            acl = json.loads(result.stdout)
            assert acl['owner'] == acl['user'] and acl['protected'], 'Ownership or protected DACL is incorrect'
            assert len(acl['rules']) == 1, 'Unexpected users/groups can access the private configuration'
            assert acl['rules'][0] == {'sid': acl['user'], 'inherited': False, 'type': 'Allow', 'full': True}

        # Exercise the native Windows HTTPS engine and its OS certificate validation.
        cert_dir = root / 'tls'
        cert_dir.mkdir()
        cert_env = dict(env, TEAMCODE_TEST_CERT_DIRECTORY=str(cert_dir))
        generate_certificate = r'''
        $ErrorActionPreference = 'Stop'
        $rsa = [Security.Cryptography.RSA]::Create(2048)
        $request = [Security.Cryptography.X509Certificates.CertificateRequest]::new(
          'CN=localhost', $rsa, [Security.Cryptography.HashAlgorithmName]::SHA256,
          [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        $san = [Security.Cryptography.X509Certificates.SubjectAlternativeNameBuilder]::new()
        $san.AddDnsName('localhost'); $san.AddIpAddress([Net.IPAddress]::Loopback)
        $request.CertificateExtensions.Add($san.Build())
        $request.CertificateExtensions.Add([Security.Cryptography.X509Certificates.X509BasicConstraintsExtension]::new($true, $false, 0, $true))
        $certificate = $request.CreateSelfSigned([DateTimeOffset]::UtcNow.AddMinutes(-5), [DateTimeOffset]::UtcNow.AddHours(1))
        [IO.File]::WriteAllText((Join-Path $env:TEAMCODE_TEST_CERT_DIRECTORY 'cert.pem'), $certificate.ExportCertificatePem())
        [IO.File]::WriteAllBytes((Join-Path $env:TEAMCODE_TEST_CERT_DIRECTORY 'cert.cer'), $certificate.Export([Security.Cryptography.X509Certificates.X509ContentType]::Cert))
        [IO.File]::WriteAllText((Join-Path $env:TEAMCODE_TEST_CERT_DIRECTORY 'key.pem'), $rsa.ExportPkcs8PrivateKeyPem())
        '''
        powershell_command(generate_certificate, cert_env)
        trust_certificate = r'''
        $ErrorActionPreference = 'Stop'
        $certificate = [Security.Cryptography.X509Certificates.X509Certificate2]::new(
          (Join-Path $env:TEAMCODE_TEST_CERT_DIRECTORY 'cert.cer'))
        # CurrentUser Root operations can open a Windows confirmation dialog even with -NonInteractive.
        # This opt-in fixture uses the disposable CI machine store and removes only its generated certificate.
        $store = [Security.Cryptography.X509Certificates.X509Store]::new('Root', 'LocalMachine')
        $store.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
        try {
          if ($env:TEAMCODE_TEST_CERT_OPERATION -eq 'add') { $store.Add($certificate) }
          else { $store.Remove($certificate) }
        } finally { $store.Close() }
        '''
        tls = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert_dir / 'cert.pem', cert_dir / 'key.pem')
        tls.socket = context.wrap_socket(tls.socket, server_side=True)
        threading.Thread(target=tls.serve_forever, daemon=True).start()
        previous_config_home = env['XDG_CONFIG_HOME']
        try:
            env['XDG_CONFIG_HOME'] = str(root / 'https configuration')
            tls_origin = f'https://127.0.0.1:{tls.server_port}'
            checks_before = len(checks)
            rejected = command('configure', '--server', tls_origin, stdin=team_token + '\n', check=False)
            assert rejected.returncode != 0 and len(checks) == checks_before, 'Untrusted HTTPS peer received a request'
            assert not (Path(env['XDG_CONFIG_HOME']) / 'teamcode/config.json').exists()
            powershell_command(trust_certificate, dict(cert_env, TEAMCODE_TEST_CERT_OPERATION='add'))
            command('configure', '--server', tls_origin, stdin=team_token + '\n')
            assert 'Mode: fixture' in command('status').stdout
        finally:
            env['XDG_CONFIG_HOME'] = previous_config_home
            tls.shutdown()
            tls.server_close()
            powershell_command(trust_certificate, dict(cert_env, TEAMCODE_TEST_CERT_OPERATION='remove'))
        print('PASS: native Windows login/password change and HTTPS trust/rejection', flush=True)

        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        with (root / 'proxy.log').open('w', encoding='utf-8') as log:
            proxy = subprocess.Popen([binary, 'proxy', 'start', '--port', str(port)], env=env, stdout=log, stderr=log)
        for _ in range(100):
            assert proxy.poll() is None, 'Native proxy exited'
            try:
                with socket.create_connection(('127.0.0.1', port), timeout=.2):
                    break
            except OSError:
                time.sleep(.1)
        else:
            raise AssertionError('Native proxy did not start')
        request = urllib.request.Request(f'http://127.0.0.1:{port}/v1/messages', b'{"model":"fixture"}',
                                         {'Content-Type': 'application/json', 'x-api-key': local_token})
        with urllib.request.urlopen(request, timeout=10) as response:
            before = time.monotonic()
            assert b'"start":true' in response.readline()
            assert b'"done":true' in response.read()
            assert time.monotonic() - before >= .25, 'SSE was buffered instead of streamed'
        try:
            urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}/v1/messages', b'{}',
                {'Content-Type': 'application/json'}), timeout=5)
            raise AssertionError('Local proxy accepted an unauthenticated request')
        except urllib.error.HTTPError as error:
            assert error.code == 401
        proxy.terminate()
        proxy.wait(timeout=10)
        proxy = None
        log_text = (root / 'proxy.log').read_text(encoding='utf-8')
        assert team_token not in log_text and local_token not in log_text

        agent_source = r'''
        const fs = require('fs'); const http = require('http'); const assert = require('assert');
        let stage = 'credentials';
        (async () => {
          const name = process.env.TEAMCODE_TEST_AGENT;
          let args = process.argv.slice(2), url, token;
          if (name === 'claude') {
            assert(!process.env.ANTHROPIC_API_KEY && !process.env.CLAUDE_CODE_OAUTH_TOKEN);
            url = process.env.ANTHROPIC_BASE_URL + '/v1/messages'; token = process.env.ANTHROPIC_AUTH_TOKEN;
            assert.strictEqual(args[0], '--plugin-dir');
            const path = require('path'), plugin = args[1];
            assert.strictEqual(JSON.parse(fs.readFileSync(path.join(plugin, '.claude-plugin', 'plugin.json'), 'utf8')).name, 'teamcode-guidance');
            const hooks = JSON.parse(fs.readFileSync(path.join(plugin, 'hooks', 'hooks.json'), 'utf8')).hooks;
            const hook = hooks.PreToolUse[0].hooks[0];
            assert(!JSON.stringify(hooks).includes(token));
            assert.strictEqual(process.env.TEAMCODE_HOOK_TOKEN, token);
            const payload = JSON.stringify({session_id:'windows-smoke', hook_event_name:'PreToolUse', tool_input:'private-tool-input'});
            const guidance = await new Promise((resolve, reject) => {
              const request = http.request(hook.url, {method:'POST',headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(payload),'Authorization':'Bearer '+token}}, response => {
                assert.strictEqual(response.statusCode, 200); let text='';
                response.on('data', chunk => text += chunk); response.on('end', () => resolve(text));
              }); request.on('error', reject); request.end(payload);
            });
            assert.deepStrictEqual(JSON.parse(guidance), {});
            args = args.slice(2);
          } else {
            let end = args.indexOf('--'); if (end < 0) end = args.length;
            assert.deepStrictEqual(args.slice(end-4, end-2), ['-c', 'model_provider="teamcode"']);
            url = /base_url="([^"]+)"/.exec(args[end-1])[1] + '/responses';
            token = process.env.TEAMCODE_LOCAL_TOKEN;
            stage = 'codex-guidance';
            for (const [offset, event] of [[8, 'UserPromptSubmit'], [6, 'PreToolUse']]) {
              stage = 'codex-hook-definition-' + event;
              assert.strictEqual(args[end-offset], '-c');
              const definition = args[end-offset+1];
              assert(definition.startsWith('hooks.' + event + '='));
              const match = /command=("(?:[^"\\]|\\.)*")/.exec(definition);
              assert(match);
              const hookCommand = JSON.parse(match[1]);
              assert(hookCommand.startsWith('powershell.exe ') && !hookCommand.includes(token));
              const output = require('child_process').spawnSync(hookCommand, {
                shell:true, input:JSON.stringify({session_id:'windows-smoke',hook_event_name:event,tool_input:'private-tool-input'}), encoding:'utf8',timeout:6000
              });
              stage = 'codex-hook-exit-' + output.status + '-signal-' + output.signal + '-error-' + (output.error?.code || 'none');
              assert.strictEqual(output.status, 0);
              stage = 'codex-hook-stderr-' + (output.stderr?.includes('CLIXML') ? 'clixml' : 'text');
              assert.strictEqual(output.stderr, '');
              stage = 'codex-hook-output-' + event;
              assert.deepStrictEqual(JSON.parse(output.stdout), {});
            }
            args = args.slice(0, end-8).concat(args.slice(end));
          }
          stage = 'stdin';
          assert.strictEqual(fs.readFileSync(0, 'utf8'), 'stdin reaches the child\n');
          stage = 'capability';
          assert.strictEqual(token.length, 64);
          const body = JSON.stringify({model:'fixture'});
          stage = 'upstream';
          const reply = await new Promise((resolve, reject) => {
            const request = http.request(url, {method:'POST', headers:{'Content-Type':'application/json',
                'Content-Length':Buffer.byteLength(body), 'Authorization':'Bearer '+token}}, response => {
              assert.strictEqual(response.statusCode, 200); let text='';
              response.on('data', chunk => text += chunk); response.on('end', () => resolve(text));
            }); request.on('error', reject); request.end(body);
          });
          stage = 'stream';
          assert(reply.includes('"done":true'));
          stage = 'descendant';
          const descendant = require('child_process').spawn(process.execPath,
            ['-e', 'setInterval(() => {}, 1000)'], {stdio:'ignore'});
          descendant.unref();
          stage = 'record';
          fs.writeFileSync(process.env.TEAMCODE_RUN_RECORD + '.tmp', JSON.stringify({args, cwd:process.cwd(), origin:new URL(url).origin, pid:process.pid, descendant:descendant.pid}));
          fs.renameSync(process.env.TEAMCODE_RUN_RECORD + '.tmp', process.env.TEAMCODE_RUN_RECORD);
          console.log('CHILD_STDOUT_OK'); console.error('CHILD_STDERR_OK');
          if (process.env.TEAMCODE_TEST_WAIT === '1') { setInterval(() => {}, 1000); return; }
          process.exit(37);
        })().catch(() => { console.error('Synthetic child failed at ' + stage); process.exit(1); });
        '''
        # Standard npm installation shape: wrappers are deliberately not executed by TeamCode.
        for name, script in [('claude', '@anthropic-ai/claude-code/cli.js'), ('codex', '@openai/codex/bin/codex.js')]:
            file = root / 'node_modules' / script
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text(agent_source, encoding='utf-8')
            (root / (name + '.cmd')).write_text('@echo THIS_SHIM_MUST_NOT_RUN\n', encoding='ascii')
        env['PATH'] = os.pathsep.join([str(root), str(node.parent), str(Path(os.environ['SystemRoot']) / 'System32')])
        env['ANTHROPIC_API_KEY'] = 'inherited-must-be-removed'
        env['CLAUDE_CODE_OAUTH_TOKEN'] = 'inherited-must-be-removed'
        arguments = ['--dangerously-skip-permissions', '', 'équipe', 'two words', 'a"b',
                     'C:\\trailing\\', '& | < > %PATH% !value!', 'line\nbreak', '--', 'literal']
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel.OpenProcess.restype = wintypes.HANDLE
        kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        kernel.WaitForSingleObject.restype = wintypes.DWORD
        kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        kernel.CloseHandle.restype = wintypes.BOOL

        def descendant_exited(pid):
            handle = kernel.OpenProcess(0x00100000, False, pid)  # SYNCHRONIZE, observation only.
            if not handle:
                assert ctypes.get_last_error() == 87, 'Could not inspect the owned synthetic child'
                return
            try:
                assert kernel.WaitForSingleObject(handle, 5000) == 0, 'Owned npm descendant survived the launcher'
            finally:
                kernel.CloseHandle(handle)

        for name, workload in [('claude', 'interactive'), ('codex', 'interactive'), ('claude', 'background'), ('codex', 'background')]:
            record = root / (name + '.json')
            env['TEAMCODE_TEST_AGENT'] = name
            env['TEAMCODE_RUN_RECORD'] = str(record)
            result = command('run', name, '--workload', workload, '--', *arguments, stdin='stdin reaches the child\n', check=False)
            assert result.returncode == 37, f'{name} failed: {result.stdout}\n{result.stderr}'
            assert result.stdout == 'CHILD_STDOUT_OK\n' and 'CHILD_STDERR_OK' in result.stderr
            captured = json.loads(record.read_text(encoding='utf-8'))
            descendant_exited(captured['descendant'])
            assert captured['args'] == arguments, 'Windows argument encoding changed the agent arguments'
            assert workloads[-1] == ('background' if workload == 'background' else None)
            assert captured['cwd'] == str(Path.cwd())
            address = urllib.parse.urlsplit(captured['origin'])
            try:
                with socket.create_connection((address.hostname, address.port), timeout=.2):
                    raise AssertionError('Ephemeral proxy remained after child exit')
            except OSError:
                pass
        # Simulate abrupt termination of this test's own launcher. The OS job must clean up
        # both the npm process and its native descendant even when Kotlin finally blocks cannot run.
        record = root / 'cancelled-agent.json'
        cancelled = subprocess.Popen([binary, 'run', 'claude', '--'],
            env=dict(env, TEAMCODE_TEST_AGENT='claude', TEAMCODE_RUN_RECORD=str(record), TEAMCODE_TEST_WAIT='1'),
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            cancelled.stdin.write(b'stdin reaches the child\n')
            cancelled.stdin.close()
            for _ in range(100):
                assert cancelled.poll() is None, 'Synthetic cancellation child exited too early'
                if record.exists():
                    break
                time.sleep(.1)
            else:
                raise AssertionError('Synthetic cancellation child did not start')
            captured = json.loads(record.read_text(encoding='utf-8'))
            cancelled.terminate()  # Exact process handle created above; never by executable name.
            cancelled.wait(timeout=10)
            descendant_exited(captured['pid'])
            descendant_exited(captured['descendant'])
        finally:
            if cancelled.poll() is None:
                cancelled.terminate()
                cancelled.wait(timeout=10)
            cancelled.stdout.close()
            cancelled.stderr.close()
        assert config.read_bytes() == original_config
        print('PASS: native Windows private ACLs, Unicode config, credential isolation, progressive SSE, literal agent arguments, npm launchers, stdio, exit code and cleanup', flush=True)

        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            service_port = listener.getsockname()[1]
        service_env = env.copy()
        service_installed = True
        command('service', 'install', '--port', str(service_port))
        settings = json.loads(config.with_name('service.json').read_text(encoding='utf-8'))
        service_origin = f'http://127.0.0.1:{service_port}'

        def service_health(expected=True):
            for _ in range(150):
                try:
                    request = urllib.request.Request(service_origin + '/_teamcode/service',
                        headers={'Authorization': 'Bearer ' + settings['localToken']})
                    with urllib.request.urlopen(request, timeout=1) as response:
                        body = json.load(response)
                        running = all(body.get(key) == value for key, value in {'id': settings['id'], 'serverUrl': origin, 'version': expected_version}.items()) and {'workload-routing-v1', 'run-routing-v1'} <= set(body.get('capabilities', []))
                except OSError:
                    running = False
                if running == expected:
                    return
                time.sleep(.2)
            print(command('service', 'logs', check=False).stdout, flush=True)
            raise AssertionError('Windows service did not reach expected state')

        service_health()
        command('service', 'install', '--port', str(service_port))
        assert json.loads(config.with_name('service.json').read_text(encoding='utf-8')) == settings
        assert 'Running: true' in command('service', 'status').stdout
        task = Path(settings['registration'])
        assert team_token not in task.read_text(encoding='utf-16') and settings['localToken'] not in task.read_text(encoding='utf-16')
        assert '<LogonType>InteractiveToken</LogonType>' in task.read_text(encoding='utf-16')
        # Reuse is checked through the actual native child, not just an identity ping.
        env['TEAMCODE_TEST_AGENT'] = 'claude'
        record = root / 'service-agent.json'
        env['TEAMCODE_RUN_RECORD'] = str(record)
        result = command('run', 'claude', '--', *arguments, stdin='stdin reaches the child\n', check=False)
        assert result.returncode == 37
        assert json.loads(record.read_text(encoding='utf-8'))['origin'] == service_origin
        for name, workload in [('claude', 'background'), ('codex', 'background'), ('claude', 'interactive')]:
            env['TEAMCODE_TEST_AGENT'] = name
            result = command('run', name, '--workload', workload, '--', *arguments, stdin='stdin reaches the child\n', check=False)
            assert result.returncode == 37
            captured = json.loads(record.read_text(encoding='utf-8'))
            assert captured['origin'] == service_origin
            descendant_exited(captured['descendant'])
            assert workloads[-1] == ('background' if workload == 'background' else None)
        service_health()

        def service_inference():
            request = urllib.request.Request(service_origin + '/v1/messages', b'{"model":"fixture"}',
                {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + settings['localToken']})
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.read()

        stream_started.clear()
        stream_release.clear()
        with concurrent.futures.ThreadPoolExecutor() as pool:
            response = pool.submit(service_inference)
            assert stream_started.wait(5)
            stop = pool.submit(command, 'service', 'stop')
            time.sleep(2)
            assert not response.done(), 'Windows service interrupted an accepted stream'
            stream_release.set()
            assert b'"done":true' in response.result(timeout=10)
            stop.result(timeout=30)
        service_health(expected=False)
        command('service', 'start')
        service_health()
        command('service', 'restart')
        service_health()
        command('service', 'uninstall')
        service_health(expected=False)
        assert config.read_bytes() == original_config and not task.exists()
        command('service', 'uninstall')
        service_installed = False
        command('logout')
        assert revoked and not config.exists(), 'Logout did not revoke the server session and remove the saved token'
        print('PASS: Windows Task Scheduler installation, idempotence, authenticated reuse, graceful drain, restart and uninstall', flush=True)
finally:
    stream_release.set()
    if service_installed:
        # Only this test's random task and isolated settings may be removed.
        cleanup = subprocess.run([binary, 'service', 'uninstall'], env=service_env, capture_output=True, timeout=60)
        service_installed = cleanup.returncode != 0
    if proxy is not None and proxy.poll() is None:
        proxy.terminate()
        proxy.wait(timeout=10)
    server.shutdown()
    server.server_close()
    if not service_installed:
        shutil.rmtree(directory)
    else:
        print('Windows service cleanup failed; isolated test directory retained for diagnosis.', flush=True)
