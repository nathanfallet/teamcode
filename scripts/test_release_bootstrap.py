import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_release_bundle import native_header

spec = importlib.util.spec_from_file_location('release_bootstrap', Path(__file__).with_name('release-bootstrap.py'))
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


class BootstrapGenerationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keys = tempfile.TemporaryDirectory(prefix='teamcode-bootstrap-test-key-')
        cls.private, cls.public = Path(cls.keys.name) / 'private', Path(cls.keys.name) / 'public'
        bootstrap.signing.openssl(['genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:3072', '-out', str(cls.private)])
        bootstrap.signing.openssl(['pkey', '-in', str(cls.private), '-pubout', '-out', str(cls.public)])

    @classmethod
    def tearDownClass(cls): cls.keys.cleanup()

    def bundle(self, root):
        assets = {}
        for target in bootstrap.release.TARGETS:
            name = bootstrap.release.asset_name('0.1.0', target)
            path = root / name; path.write_bytes(native_header(target))
            assets[target] = dict(fileName=name, sha256=bootstrap.release.digest(path), size=path.stat().st_size)
        manifest = dict(schemaVersion=1, version='0.1.0', tag='0.1.0', sourceCommit='a' * 40, assets=assets)
        (root / 'manifest.json').write_text(json.dumps(manifest))
        self.sign(root)
        return manifest

    def sign(self, root):
        (root / 'manifest.sig').unlink(missing_ok=True)
        bootstrap.signing.sign(root / 'manifest.json', self.private, self.public, root / 'manifest.sig')

    def test_generation_requires_exact_signed_five_target_payloads_and_pins_every_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); manifest = self.bundle(root)
            scripts = bootstrap.generate(root, self.public)
            self.assertEqual({'install.sh', 'install.ps1'}, set(scripts))
            for name, data in scripts.items():
                self.assertEqual(data, (root / name).read_bytes())
                text = data.decode('utf-8-sig')
                self.assertNotIn('@@', text)
                self.assertIn('/releases/download/0.1.0', text)
                self.assertNotIn('/releases/download/v0.1.0', text)
                for metadata in ('manifest.json', 'manifest.sig'):
                    self.assertIn(bootstrap.release.digest(root / metadata), text)
                for target, asset in manifest['assets'].items():
                    if target.startswith('windows') == name.endswith('.ps1'):
                        self.assertIn(asset['fileName'], text)
                        self.assertIn(asset['sha256'], text)
            with self.assertRaises(ValueError): bootstrap.generate(root, self.public)

    def test_unsigned_replaced_prefixed_missing_and_non_native_inputs_cannot_emit_installers(self):
        for mutation in ('signature', 'metadata', 'prefixed', 'missing', 'traversal', 'boolean-size', 'binary', 'symlink', 'architecture'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); manifest = self.bundle(root)
                target = 'linux-x64'; asset = manifest['assets'][target]; binary = root / asset['fileName']
                if mutation == 'signature': (root / 'manifest.sig').write_text('{}')
                elif mutation == 'metadata': (root / 'manifest.json').write_text('{}')
                elif mutation == 'binary': binary.write_bytes(b'x' * asset['size'])
                elif mutation == 'symlink':
                    binary.rename(root / 'outside'); binary.symlink_to(root / 'outside')
                else:
                    if mutation == 'prefixed': manifest['tag'] = 'v0.1.0'
                    elif mutation == 'missing': del manifest['assets'][target]
                    elif mutation == 'traversal': asset['fileName'] = '../outside'
                    elif mutation == 'boolean-size': asset['size'] = True
                    elif mutation == 'architecture':
                        binary.write_bytes(native_header('linux-arm64')); asset['sha256'] = bootstrap.release.digest(binary)
                    (root / 'manifest.json').write_text(json.dumps(manifest)); self.sign(root)
                with self.assertRaises((ValueError, FileNotFoundError)): bootstrap.generate(root, self.public)
                self.assertFalse((root / 'install.sh').exists())
                self.assertFalse((root / 'install.ps1').exists())

    def test_repository_must_be_a_literal_github_owner_and_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.bundle(root)
            for value in ('https://github.com/a/b', 'a/b/c', 'a/$(whoami)', 'a/../b', 'a/b\n', "a/b';echo"):
                with self.subTest(value=value), self.assertRaises(ValueError): bootstrap.generate(root, self.public, value)
            self.assertFalse((root / 'install.sh').exists())


@unittest.skipIf(os.name == 'nt', 'POSIX bootstrap executes on macOS/Linux')
class PosixBootstrapFlowTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='teamcode-bootstrap-flow-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.bundle = self.root / 'bundle'; self.bundle.mkdir()
        self.commands = self.root / 'commands'; self.commands.mkdir()
        self.temporary = self.root / 'temporary'; self.temporary.mkdir()
        self.calls = self.root / 'calls'; self.calls.mkdir()
        self.execution = self.root / 'executed.json'
        # Unit-level executable shim isolates the shell entry point. Separate
        # generator tests require native headers/signatures; smoke-bootstrap.py
        # accepts the actual publisher-signed native executable on its real host.
        executable = b'''#!/usr/bin/env python3
import json, os, sys
with open(os.environ['TEAMCODE_TEST_EXECUTION'], 'w') as out: json.dump(sys.argv[1:], out)
with open(os.environ['TEAMCODE_TEST_EXECUTION'] + '.env', 'w') as out: json.dump({'TEAMCODE_NO_SERVICE': os.environ.get('TEAMCODE_NO_SERVICE')}, out)
sys.exit(int(os.environ.get('TEAMCODE_TEST_EXIT', '0')))
'''
        executable += b'\n#' + b'fixture-padding' * 160 + b'\n'
        assets = {}
        for target in bootstrap.release.TARGETS:
            name = bootstrap.release.asset_name('0.1.0', target)
            (self.bundle / name).write_bytes(executable)
            assets[target] = dict(fileName=name, size=len(executable), sha256=hashlib.sha256(executable).hexdigest())
        self.manifest = dict(schemaVersion=1, version='0.1.0', tag='0.1.0', sourceCommit='a' * 40, assets=assets)
        metadata = json.dumps(self.manifest).encode()
        signature = b'unit-flow-envelope'.ljust(700, b' ')
        (self.bundle / 'manifest.json').write_bytes(metadata); (self.bundle / 'manifest.sig').write_bytes(signature)
        self.installer = self.root / 'install.sh'
        self.installer.write_bytes(bootstrap.render(self.manifest, metadata, signature)['install.sh'])
        self.command('id', '#!/bin/sh\nprintf "%s\\n" "${TEAMCODE_TEST_UID:-1000}"\n')
        self.command('uname', '#!/bin/sh\nif [ "$1" = -s ]; then printf "%s\\n" "$TEAMCODE_TEST_SYSTEM"; else printf "%s\\n" "$TEAMCODE_TEST_MACHINE"; fi\n')
        self.command('sysctl', '#!/bin/sh\nprintf "%s\\n" "${TEAMCODE_TEST_ROSETTA:-0}"\n')
        self.command('curl', '''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
name = args[-1].rsplit('/', 1)[-1]
pathlib.Path(os.environ['TEAMCODE_TEST_CALLS'], name).write_text(json.dumps(args))
if os.environ.get('TEAMCODE_TEST_HTTP_FAILURE'): sys.exit(22)
destination = pathlib.Path(args[args.index('--output') + 1])
destination.write_bytes(pathlib.Path(os.environ['TEAMCODE_TEST_BUNDLE'], name).read_bytes())
''')
        self.env = {**os.environ, 'PATH': str(self.commands) + os.pathsep + os.environ['PATH'], 'TMPDIR': str(self.temporary),
                    'TEAMCODE_INSTALL_ROOT': str(self.root / "managed path ' $(literal)"),
                    'TEAMCODE_TEST_EXECUTION': str(self.execution), 'TEAMCODE_TEST_BUNDLE': str(self.bundle),
                    'TEAMCODE_TEST_CALLS': str(self.calls), 'TEAMCODE_TEST_SYSTEM': 'Linux', 'TEAMCODE_TEST_MACHINE': 'x86_64',
                    'PYTHONDONTWRITEBYTECODE': '1'}
        self.env.pop('TEAMCODE_INSTALL_BUNDLE', None)

    def command(self, name, text):
        path = self.commands / name; path.write_text(text); path.chmod(0o700)

    def run_installer(self, **env):
        result = subprocess.run(['sh', str(self.installer)], env={**self.env, **env}, capture_output=True, timeout=30)
        self.assertEqual([], list(self.temporary.iterdir()), 'Installer temporary files were not cleaned')
        return result

    def test_all_posix_targets_use_exact_pinned_https_assets_and_literal_native_arguments(self):
        for system, machine, target in [('Linux', 'x86_64', 'linux-x64'), ('Linux', 'aarch64', 'linux-arm64'),
                                        ('Darwin', 'arm64', 'macos-arm64')]:
            with self.subTest(target=target):
                for call in self.calls.iterdir(): call.unlink()
                result = self.run_installer(TEAMCODE_TEST_SYSTEM=system, TEAMCODE_TEST_MACHINE=machine)
                self.assertEqual(0, result.returncode, result.stderr.decode())
                arguments = json.loads(self.execution.read_text())
                self.assertEqual(['install', '--version', '0.1.0', '--bundle'], arguments[:4])
                self.assertEqual(['--root', self.env['TEAMCODE_INSTALL_ROOT']], arguments[-2:])
                name = self.manifest['assets'][target]['fileName']
                self.assertEqual({'manifest.json', 'manifest.sig', name}, {p.name for p in self.calls.iterdir()})
                for call in self.calls.iterdir():
                    curl = json.loads(call.read_text())
                    self.assertEqual('--disable', curl[0])
                    self.assertEqual('=https', curl[curl.index('--proto') + 1])
                    self.assertEqual('=https', curl[curl.index('--proto-redir') + 1])
                    self.assertEqual('120', curl[curl.index('--max-time') + 1])
                    self.assertEqual('5', curl[curl.index('--max-redirs') + 1])
                    self.assertEqual('https://github.com/nathanfallet/teamcode/releases/download/0.1.0/' + call.name, curl[-1])

    def test_tampered_metadata_or_binary_and_download_errors_cannot_start_an_executable(self):
        originals = {p.name: p.read_bytes() for p in self.bundle.iterdir()}
        for name in ('manifest.json', 'manifest.sig', self.manifest['assets']['linux-x64']['fileName'], 'http-error'):
            with self.subTest(name=name):
                if name != 'http-error':
                    data = originals[name]; (self.bundle / name).write_bytes(bytes([data[0] ^ 1]) + data[1:])
                result = self.run_installer(TEAMCODE_TEST_HTTP_FAILURE='1' if name == 'http-error' else '')
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.execution.exists())
                for file, data in originals.items(): (self.bundle / file).write_bytes(data)

    def test_the_native_installation_ends_with_the_service_step_unless_opted_out(self):
        # Decision 4: `teamcode install` installs and starts the login service after the binary; the script opts out
        # only through TEAMCODE_NO_SERVICE, which reaches the native installer unchanged.
        self.env.pop('TEAMCODE_NO_SERVICE', None)
        result = self.run_installer()
        self.assertEqual(0, result.returncode, result.stderr.decode())
        arguments = json.loads(self.execution.read_text())
        self.assertEqual('install', arguments[0])
        self.assertNotIn('--no-service', arguments)
        self.assertNotIn('--at-boot', arguments, 'Never boot startup, which needs elevation')
        self.assertIsNone(json.loads(Path(str(self.execution) + '.env').read_text())['TEAMCODE_NO_SERVICE'])
        result = self.run_installer(TEAMCODE_NO_SERVICE='1')
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertEqual('1', json.loads(Path(str(self.execution) + '.env').read_text())['TEAMCODE_NO_SERVICE'])

    def test_offline_installation_rosetta_root_and_native_failure(self):
        result = self.run_installer(TEAMCODE_INSTALL_BUNDLE=str(self.bundle), TEAMCODE_TEST_SYSTEM='Darwin',
                                    TEAMCODE_TEST_MACHINE='x86_64', TEAMCODE_TEST_ROSETTA='1')
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertIn(b'macos-arm64', result.stdout)
        self.assertEqual([], list(self.calls.iterdir()))
        self.execution.unlink()
        result = self.run_installer(TEAMCODE_INSTALL_BUNDLE=str(self.bundle), TEAMCODE_TEST_SYSTEM='Darwin', TEAMCODE_TEST_MACHINE='x86_64')
        self.assertNotEqual(0, result.returncode); self.assertFalse(self.execution.exists())
        self.assertIn(b'macOS on Intel is no longer supported', result.stderr)
        self.assertIn(b'1.0.1', result.stderr)
        result = self.run_installer(TEAMCODE_TEST_UID='0')
        self.assertNotEqual(0, result.returncode); self.assertFalse(self.execution.exists())
        result = self.run_installer(TEAMCODE_TEST_MACHINE='riscv64')
        self.assertNotEqual(0, result.returncode); self.assertFalse(self.execution.exists())
        result = self.run_installer(TEAMCODE_TEST_EXIT='7')
        self.assertEqual(7, result.returncode)


class PowerShellBootstrapTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which('pwsh'), 'PowerShell 7 is needed for portable HTTP-helper acceptance')
    def test_powershell_parsing_and_bounded_https_downloads_without_real_network(self):
        manifest = dict(version='0.1.0', assets={target: dict(fileName=bootstrap.release.asset_name('0.1.0', target),
                        size=256, sha256='a' * 64) for target in bootstrap.release.TARGETS})
        with tempfile.TemporaryDirectory(prefix='teamcode-bootstrap-powershell-') as directory:
            template = Path(directory) / 'install.ps1'
            template.write_bytes(bootstrap.render(manifest, b'{}', b'fixture')['install.ps1'])
            result = subprocess.run(['pwsh', '-NoProfile', '-NonInteractive', '-File',
                                     str(Path(__file__).parent / 'fixtures/bootstrap-http.ps1'),
                                     '-Template', str(template)], capture_output=True, timeout=45)
            self.assertEqual(0, result.returncode, (result.stdout + result.stderr).decode(errors='replace'))


class DocumentedOneLinerTest(unittest.TestCase):
    def test_windows_one_liner_does_not_stringify_response_bytes(self):
        # GitHub serves release assets as application/octet-stream, so Invoke-WebRequest
        # returns Content as byte[]; passing it to ScriptBlock.Create yields "239 187 191 ...".
        root = Path(__file__).resolve().parent.parent
        files = [root / 'README.md', root / 'docs/bootstrap.md']
        found = 0
        for file in files:
            for line in file.read_text(encoding='utf-8').splitlines():
                if 'install.ps1' in line and 'Invoke-WebRequest' in line:
                    found += 1
                    self.assertNotIn('.Content', line, file.name)
                    self.assertIn('-OutFile', line, file.name)
                    self.assertIn('ReadAllText', line, file.name)
                    self.assertIn('finally', line, file.name)
                    self.assertIn('GetRandomFileName', line, file.name)
        self.assertGreater(found, 0)


if __name__ == '__main__': unittest.main()
