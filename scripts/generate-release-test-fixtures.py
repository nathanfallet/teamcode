#!/usr/bin/env python3
"""Generate public verification vectors using disposable fixture keys; never use the publisher key."""
import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('signing', ROOT / 'scripts/release-signature.py')
signing = importlib.util.module_from_spec(spec); spec.loader.exec_module(signing)


def main():
    output = ROOT / 'cli/src/commonTest/kotlin/me/nathanfallet/teamcode/cli/update/ReleaseFixtures.kt'
    output.parent.mkdir(parents=True, exist_ok=True)
    code = ['package me.nathanfallet.teamcode.cli.update', '', '// Public vectors generated from disposable fixture keys. No private key is retained.',
            'internal object ReleaseFixtures {', '    const val binaryText = "TeamCode native binary fixture"']
    binary = b'TeamCode native binary fixture'
    version = '1.2.3-alpha.1'
    assets = {target: {'fileName': f'teamcode-{version}-{target}' + ('.exe' if target.startswith('windows-') else ''),
                       'sha256': hashlib.sha256(binary).hexdigest(), 'size': len(binary)}
              for target in ('macos-arm64', 'macos-x64', 'linux-arm64', 'linux-x64', 'windows-x64')}
    good = {'schemaVersion': 1, 'version': version, 'tag': version, 'sourceCommit': 'a' * 40, 'assets': assets}
    with tempfile.TemporaryDirectory(prefix='teamcode-public-crypto-vectors-') as directory:
        root = Path(directory)
        pairs = []
        for name in ('first', 'second'):
            private = root / (name + '.private'); public = root / (name + '.public')
            signing.openssl(['genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:3072', '-out', str(private)])
            signing.openssl(['pkey', '-in', str(private), '-pubout', '-out', str(public)])
            spki = signing.openssl(['pkey', '-pubin', '-in', str(public), '-outform', 'DER'])
            pkcs1 = signing.openssl(['rsa', '-pubin', '-in', str(public), '-RSAPublicKey_out', '-outform', 'DER'])
            code.append(f'    val {name}Key = ReleasePublicKey("{base64.b64encode(spki).decode()}", "{base64.b64encode(pkcs1).decode()}")')
            pairs.append((private, public))

        def vector(name, payload, pair=pairs[0], options=signing.PSS):
            raw = payload if isinstance(payload, bytes) else (json.dumps(payload, sort_keys=True) + '\n').encode()
            manifest = root / (name + '.json'); manifest.write_bytes(raw)
            signature = root / (name + '.signature')
            signing.openssl(['dgst', '-sha256', '-sign', str(pair[0]), '-out', str(signature), *options, str(manifest)])
            envelope = {'schemaVersion': 1, 'algorithm': signing.ALGORITHM, 'keyId': signing.key_id(pair[1]),
                        'signature': base64.b64encode(signature.read_bytes()).decode()}
            code.append(f'    const val {name}Payload = "{base64.b64encode(raw).decode()}"')
            code.append(f'    const val {name}Envelope = "{base64.b64encode(json.dumps(envelope, sort_keys=True).encode()).decode()}"')

        vector('valid', good)
        vector('rotated', good, pairs[1])
        vector('newer', {**good, 'version': '1.2.4', 'tag': '1.2.4', 'sourceCommit': 'b' * 40, 'assets': {target: {**asset, 'fileName': asset['fileName'].replace(version, '1.2.4')} for target, asset in assets.items()}})
        vector('salt20', good, options=['-sigopt', 'rsa_padding_mode:pss', '-sigopt', 'rsa_pss_saltlen:20'])
        vector('mgf384', good, options=['-sigopt', 'rsa_padding_mode:pss', '-sigopt', 'rsa_pss_saltlen:32', '-sigopt', 'rsa_mgf1_md:sha384'])
        vector('pkcs1', good, options=[])
        vector('nonJson', b'Authenticated public crypto fixture; not JSON.')
        vector('invalidUtf8', b'\xff')
        vector('prefixedTag', {**good, 'tag': 'v' + version})
        vector('wrongSchema', {**good, 'schemaVersion': 2})
        vector('unsafePath', {**good, 'assets': {'linux-x64': {**assets['linux-x64'], 'fileName': '../escape'}}})
        vector('oversizedAsset', {**good, 'assets': {'linux-x64': {**assets['linux-x64'], 'size': 256 * 1024 * 1024 + 1}}})
        vector('futureTarget', {**good, 'assets': {'linux-x64': assets['linux-x64'], 'linux-riscv64': {**assets['linux-x64'], 'fileName': f'teamcode-{version}-linux-riscv64'}}})
    output.write_text('\n'.join(code + ['}', '']))
    print('Public release verification vectors regenerated; disposable private keys removed.')


def desktop():
    """Vectors of the desktop app's manifest (desktop-manifest.json), with their own disposable key."""
    output = ROOT / 'cli/src/commonTest/kotlin/me/nathanfallet/teamcode/cli/update/DesktopReleaseFixtures.kt'
    code = ['package me.nathanfallet.teamcode.cli.update', '', '// Public vectors generated from a disposable fixture key. No private key is retained.',
            'internal object DesktopReleaseFixtures {', '    const val packageText = "TeamCode desktop package fixture"']
    package = b'TeamCode desktop package fixture'
    version = '1.2.3'
    extensions = {'macos': 'zip', 'windows': 'msi', 'linux': 'tar.gz'}
    targets = ('macos-arm64', 'macos-x64', 'linux-arm64', 'linux-x64', 'windows-x64')
    assets = {target: {'fileName': f'teamcode-desktop-{version}-{target}.{extensions[target.split("-")[0]]}',
                       'sha256': hashlib.sha256(package).hexdigest(), 'size': len(package)} for target in targets}
    good = {'schemaVersion': 1, 'product': 'desktop', 'version': version, 'tag': version, 'sourceCommit': 'c' * 40, 'assets': assets}
    cli = {'schemaVersion': 1, 'version': version, 'tag': version, 'sourceCommit': 'c' * 40,
           'assets': {target: {**assets[target], 'fileName': f'teamcode-{version}-{target}' + ('.exe' if target.startswith('windows-') else '')} for target in targets}}
    with tempfile.TemporaryDirectory(prefix='teamcode-public-crypto-vectors-') as directory:
        root = Path(directory)
        private = root / 'desktop.private'; public = root / 'desktop.public'
        signing.openssl(['genpkey', '-algorithm', 'RSA', '-pkeyopt', 'rsa_keygen_bits:3072', '-out', str(private)])
        signing.openssl(['pkey', '-in', str(private), '-pubout', '-out', str(public)])
        spki = signing.openssl(['pkey', '-pubin', '-in', str(public), '-outform', 'DER'])
        pkcs1 = signing.openssl(['rsa', '-pubin', '-in', str(public), '-RSAPublicKey_out', '-outform', 'DER'])
        code.append(f'    val key = ReleasePublicKey("{base64.b64encode(spki).decode()}", "{base64.b64encode(pkcs1).decode()}")')

        def vector(name, payload):
            raw = (json.dumps(payload, sort_keys=True) + '\n').encode()
            manifest = root / (name + '.json'); manifest.write_bytes(raw)
            signature = root / (name + '.signature')
            signing.openssl(['dgst', '-sha256', '-sign', str(private), '-out', str(signature), *signing.PSS, str(manifest)])
            envelope = {'schemaVersion': 1, 'algorithm': signing.ALGORITHM, 'keyId': signing.key_id(public),
                        'signature': base64.b64encode(signature.read_bytes()).decode()}
            code.append(f'    const val {name}Payload = "{base64.b64encode(raw).decode()}"')
            code.append(f'    const val {name}Envelope = "{base64.b64encode(json.dumps(envelope, sort_keys=True).encode()).decode()}"')

        vector('valid', good)
        vector('cli', cli)
        vector('wrongProduct', {**good, 'product': 'cli'})
        vector('cliNames', {**good, 'assets': cli['assets']})
    output.write_text('\n'.join(code + ['}', '']))
    print('Public desktop release verification vectors regenerated; the disposable private key was removed.')


if __name__ == '__main__':
    if sys.argv[1:] == ['--desktop']: desktop()
    else:
        main()
        desktop()
