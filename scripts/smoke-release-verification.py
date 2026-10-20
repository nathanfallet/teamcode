#!/usr/bin/env python3
"""Exercise compiled publisher trust through the actual native CLI, without network or login."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', type=Path, required=True)
    args = parser.parse_args()
    binary = str(args.binary.resolve())
    fixtures = Path(__file__).resolve().parent / 'fixtures'
    payload = (fixtures / 'release-publisher-manifest.json').read_bytes()
    envelope = (fixtures / 'release-publisher-manifest.sig').read_bytes()
    release = json.loads(payload)
    signature = json.loads(envelope)

    with tempfile.TemporaryDirectory(prefix='teamcode-release-') as directory:
        root = Path(directory) / "release é space's"
        root.mkdir()
        manifest_file = root / 'manifest.json'
        signature_file = root / 'manifest.sig'
        artifact = root / 'untrusted executable'
        config_root = root / 'configuration'
        env = dict(os.environ, XDG_CONFIG_HOME=str(config_root))

        def check(data=payload, signed=envelope, extra=(), error=None):
            manifest_file.write_bytes(data)
            signature_file.write_bytes(signed)
            result = subprocess.run(
                [binary, 'verify-release', '--manifest', str(manifest_file),
                 '--signature', str(signature_file), *extra],
                cwd=root, env=env, stdin=subprocess.DEVNULL, capture_output=True, timeout=15)
            output = (result.stdout + result.stderr).decode('utf-8', errors='replace')
            if error:
                assert result.returncode != 0 and error in output, f'Expected {error}; exit={result.returncode}: {output}'
                assert 'Verified TeamCode' not in output, 'A rejected release was announced as verified'
            else:
                assert result.returncode == 0, f'Publisher root rejected: {output}'
                assert f'Verified TeamCode {release["version"]} ({release["sourceCommit"]})' in output
                assert f'Release key: {signature["keyId"]}' in output
            assert not config_root.exists(), 'Verification must not require or create team authentication'

        check()
        check(payload + b' ', error='release_signature_invalid')
        check(b'not JSON', error='release_signature_invalid')
        check(b'', error='release_metadata_size')
        check(b' ' * 16385, error='release_metadata_size')
        check(signed=b' ' * 2049, error='release_metadata_size')
        check(signed=b'\xff', error='release_signature_invalid')
        for field, value, error in [
            ('keyId', '0' * 64, 'release_key_untrusted'),
            ('algorithm', 'RSA-NONE', 'release_signature_invalid'),
            ('schemaVersion', '1', 'release_signature_invalid'),
            ('extra', 'unsupported', 'release_signature_invalid'),
            ('signature', 'AAAA', 'release_signature_invalid'),
        ]:
            check(signed=json.dumps(dict(signature, **{field: value})).encode(), error=error)
        raw = signature['signature']
        tampered = ('B' if raw[0] == 'A' else 'A') + raw[1:]
        check(signed=json.dumps(dict(signature, signature=tampered)).encode(), error='release_signature_invalid')
        check(extra=('--binary', str(artifact)), error='release_binary_target_required')
        check(extra=('--target', 'linux-x64'), error='release_binary_target_required')
        check(extra=('--binary', str(artifact), '--target', 'linux-x64'), error='release_file_unreadable')
        # No bytes from an unverified binary may execute, even if it looks executable.
        artifact.write_bytes(b'UNTRUSTED_BINARY_MUST_NEVER_EXECUTE')
        artifact.chmod(0o700)
        check(extra=('--binary', str(artifact), '--target', 'linux-x64'), error='release_binary_invalid')
        check(extra=('--binary', str(artifact), '--target', '../escape'), error='release_target_unsupported')
        manifest_file.unlink()
        result = subprocess.run([binary, 'verify-release', '--manifest', str(manifest_file),
                                 '--signature', str(signature_file)], env=env,
                                stdin=subprocess.DEVNULL, capture_output=True, timeout=15)
        assert result.returncode != 0 and b'release_file_unreadable' in result.stderr + result.stdout
        if os.name != 'nt':
            pipe = root / 'not a regular metadata file'
            os.mkfifo(pipe)
            result = subprocess.run([binary, 'verify-release', '--manifest', str(pipe), '--signature', str(signature_file)],
                                    env=env, stdin=subprocess.DEVNULL, capture_output=True, timeout=5)
            assert result.returncode != 0 and b'release_file_unreadable' in result.stderr + result.stdout
        # These guards must fail before any network request; real GitHub is not a CI dependency.
        for arguments, diagnostic in [
            (['update'], b'release_not_managed'),
            (['update', '--check', '--version', '../escape'], b'release_version_invalid'),
        ]:
            result = subprocess.run([binary, *arguments], env=env, stdin=subprocess.DEVNULL,
                                    capture_output=True, timeout=5)
            assert result.returncode != 0 and diagnostic in result.stdout + result.stderr
        compiled_version = subprocess.check_output([binary, 'version'], env=env, text=True, timeout=5).strip().split(' ', 1)[1]
        for version, api, accepted in [(compiled_version, '1', True), (compiled_version, '2', False), ('99.0.0', '1', False)]:
            result = subprocess.run([binary, '--teamcode-installation-probe', version, api], env=env,
                                    stdin=subprocess.DEVNULL, capture_output=True, timeout=5)
            assert (result.returncode == 0) == accepted, 'Native candidate version/API compatibility probe disagreed'
        assert not config_root.exists(), 'Release operations must not create team configuration'
    print('PASS: native publisher trust, Unicode paths, tampering, bounds, binary rejection, update guards and no login/configuration.')


if __name__ == '__main__':
    main()
