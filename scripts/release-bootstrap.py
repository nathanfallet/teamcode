#!/usr/bin/env python3
"""Generate version-pinned one-command installers from an already signed native bundle."""
import argparse
import importlib.util
import json
from pathlib import Path
import re
import tempfile


def sibling(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


release = sibling('release-bundle')
signing = sibling('release-signature')
TEMPLATES = Path(__file__).resolve().parent.parent / 'release/bootstrap'


def render(manifest, manifest_data, signature_data, repository='nathanfallet/teamcode'):
    revision = release.version(manifest['version'])
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,99}/[A-Za-z0-9][A-Za-z0-9_.-]{0,99}', repository):
        raise ValueError('Use a GitHub owner/repository, without a URL or shell syntax.')
    import hashlib
    windows = manifest['assets']['windows-x64']
    values = {
        'VERSION': revision, 'REPOSITORY': repository,
        'MANIFEST_SIZE': str(len(manifest_data)), 'MANIFEST_SHA256': hashlib.sha256(manifest_data).hexdigest(),
        'SIGNATURE_SIZE': str(len(signature_data)), 'SIGNATURE_SHA256': hashlib.sha256(signature_data).hexdigest(),
        'WINDOWS_ASSET': windows['fileName'], 'WINDOWS_SIZE': str(windows['size']), 'WINDOWS_SHA256': windows['sha256'],
        'POSIX_ASSETS': '\n'.join(f"    {target}) asset='{asset['fileName']}'; size={asset['size']}; hash='{asset['sha256']}' ;;"
                                  for target, asset in sorted(manifest['assets'].items()) if target != 'windows-x64'),
    }
    scripts = {}
    for name in ('install.sh', 'install.ps1'):
        template = (TEMPLATES / (name + '.in')).read_text(encoding='utf-8')
        rendered = re.sub(r'@@([A-Z0-9_]+)@@', lambda match: values[match[1]], template)
        if '@@' in rendered: raise ValueError('Unresolved installer template field.')
        # A BOM keeps non-ASCII messages intact on Windows PowerShell 5.1 too.
        scripts[name] = rendered.encode('utf-8-sig' if name.endswith('.ps1') else 'utf-8')
    return scripts


def generate(bundle, public_key, repository='nathanfallet/teamcode'):
    manifest_path, signature_path = bundle / 'manifest.json', bundle / 'manifest.sig'
    for path in (manifest_path, signature_path):
        if path.is_symlink() or not path.is_file(): raise ValueError('Use regular release metadata files.')
    manifest_data = signing.manifest_bytes(manifest_path)
    with signature_path.open('rb') as source: signature_data = source.read(signing.MAX_ENVELOPE + 1)
    # Verify private snapshots, then parse/render exactly those bytes. Reopening
    # mutable input metadata after verification could otherwise change its identity.
    with tempfile.TemporaryDirectory(prefix='teamcode-bootstrap-metadata-') as directory:
        snapshot = Path(directory)
        (snapshot / 'manifest.json').write_bytes(manifest_data)
        (snapshot / 'manifest.sig').write_bytes(signature_data)
        signing.verify(snapshot / 'manifest.json', snapshot / 'manifest.sig', public_key)
    manifest = json.loads(manifest_data)
    if (not isinstance(manifest, dict) or set(manifest) != {'schemaVersion', 'version', 'tag', 'sourceCommit', 'assets'} or
        type(manifest['schemaVersion']) is not int or manifest['schemaVersion'] != 1 or manifest['tag'] != manifest['version'] or
        not isinstance(manifest['assets'], dict) or set(manifest['assets']) != set(release.TARGETS)):
        raise ValueError('Use an exact, unprefixed five-target release manifest.')
    revision = release.version(manifest['version']); release.commit(manifest['sourceCommit'])
    for target, asset in manifest['assets'].items():
        if (not isinstance(asset, dict) or set(asset) != {'fileName', 'size', 'sha256'} or
            asset['fileName'] != release.asset_name(revision, target) or
            type(asset['size']) is not int or not 1 <= asset['size'] <= release.MAX_BINARY or
            not isinstance(asset['sha256'], str) or not re.fullmatch('[0-9a-f]{64}', asset['sha256'])):
            raise ValueError('Invalid signed native asset metadata.')
        binary = bundle / asset['fileName']
        if (binary.is_symlink() or not binary.is_file() or binary.stat().st_size != asset['size'] or
            release.digest(binary) != asset['sha256'] or release.binary_target(binary) != target):
            raise ValueError('Installer inputs differ from the signed native bundle.')
    scripts = render(manifest, manifest_data, signature_data, repository)
    if any((bundle / name).exists() or (bundle / name).is_symlink() for name in scripts):
        raise ValueError('Refusing to replace an existing bootstrap installer.')
    for name, data in scripts.items():
        with (bundle / name).open('xb') as destination: destination.write(data)
    return scripts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', type=Path, required=True)
    parser.add_argument('--public-key', type=Path, required=True)
    parser.add_argument('--repository', default='nathanfallet/teamcode')
    args = parser.parse_args()
    generate(args.bundle, args.public_key, args.repository)
    print('Generated version-pinned installers from the verified five-target bundle.')


if __name__ == '__main__': main()
