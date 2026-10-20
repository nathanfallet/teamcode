#!/usr/bin/env python3
"""Stage verified native release assets and assemble their bounded public manifest."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess

TARGETS = ('macos-arm64', 'macos-x64', 'linux-x64', 'linux-arm64', 'windows-x64')
NUMBER = r'(?:0|[1-9][0-9]*)'
PRERELEASE_PART = rf'(?:{NUMBER}|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)'
VERSION = re.compile(rf'{NUMBER}\.{NUMBER}\.{NUMBER}(?:-{PRERELEASE_PART}(?:\.{PRERELEASE_PART})*)?')
MAX_BINARY = 256 * 1024 * 1024


def version(value):
    if len(value) > 80 or not VERSION.fullmatch(value):
        raise ValueError('Use a bounded semantic version without a tag prefix.')
    return value


def commit(value):
    if not re.fullmatch(r'[0-9a-f]{40}', value):
        raise ValueError('Use the complete lowercase source commit SHA.')
    return value


def asset_name(revision, target):
    version(revision)
    if target not in TARGETS:
        raise ValueError('Unsupported native target.')
    return f'teamcode-{revision}-{target}' + ('.exe' if target == 'windows-x64' else '')


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def binary_target(path):
    with path.open('rb') as source:
        data = source.read(4096)
    if len(data) >= 20 and data[:4] == b'\x7fELF' and data[4:6] == b'\x02\x01':
        machine = struct.unpack_from('<H', data, 18)[0]
        if machine in (62, 183):
            return 'linux-x64' if machine == 62 else 'linux-arm64'
    if len(data) >= 8 and data[:4] == b'\xcf\xfa\xed\xfe':
        cpu = struct.unpack_from('<I', data, 4)[0]
        if cpu in (0x1000007, 0x100000C):
            return 'macos-x64' if cpu == 0x1000007 else 'macos-arm64'
    if data[:2] == b'MZ' and len(data) >= 64:
        offset = struct.unpack_from('<I', data, 60)[0]
        if offset + 6 <= len(data) and data[offset:offset + 4] == b'PE\0\0' and struct.unpack_from('<H', data, offset + 4)[0] == 0x8664:
            return 'windows-x64'
    raise ValueError('The asset is not a supported 64-bit native executable.')


def stage(binary, target, revision, source_commit, output):
    version(revision); commit(source_commit)
    binary = binary.resolve(strict=True)
    if not 1 <= binary.stat().st_size <= MAX_BINARY or binary_target(binary) != target:
        raise ValueError('Native binary target or size does not match the release.')
    os_name = {'Darwin': 'macos', 'Linux': 'linux', 'Windows': 'windows'}.get(platform.system())
    arch = {'arm64': 'arm64', 'aarch64': 'arm64', 'amd64': 'x64', 'x86_64': 'x64'}.get(platform.machine().lower())
    if f'{os_name}-{arch}' != target:
        raise ValueError('Stage assets on their actual target OS and architecture.')
    output.mkdir(parents=True, exist_ok=True)
    name = asset_name(revision, target)
    destination = output / name
    if destination.exists() or (output / f'{target}.json').exists():
        raise ValueError('Refusing to replace an existing staged asset.')
    shutil.copyfile(binary, destination)
    destination.chmod(0o755)
    try:
        probe = subprocess.run([str(destination.resolve()), 'version'], capture_output=True, text=True, timeout=15, check=True)
        if probe.stdout.strip() != f'TeamCode {revision}':
            raise ValueError('The executable version does not match the release.')
    except (OSError, subprocess.SubprocessError):
        raise ValueError('The staged executable did not pass its version probe.') from None
    record = {'target': target, 'version': revision, 'sourceCommit': source_commit,
              'fileName': name, 'sha256': digest(destination), 'size': destination.stat().st_size}
    (output / f'{target}.json').write_text(json.dumps(record, sort_keys=True) + '\n')
    return record


def assemble(inputs, revision, source_commit, output):
    version(revision); commit(source_commit)
    assets = {}
    for target in TARGETS:
        path = inputs / f'{target}.json'
        if path.stat().st_size > 4096:
            raise ValueError('Asset metadata exceeds the size limit.')
        metadata = json.loads(path.read_text())
        name = asset_name(revision, target)
        binary = inputs / name
        if metadata.get('target') != target or metadata.get('version') != revision or metadata.get('sourceCommit') != source_commit or metadata.get('fileName') != name:
            raise ValueError('Mixed target, version, source commit or asset name.')
        size = binary.stat().st_size
        if binary.is_symlink() or not 1 <= size <= MAX_BINARY or type(metadata.get('size')) is not int or metadata.get('size') != size or metadata.get('sha256') != digest(binary) or binary_target(binary) != target:
            raise ValueError('Release asset integrity or native target check failed.')
        assets[target] = {key: metadata[key] for key in ('fileName', 'sha256', 'size')}
    expected = {f'{target}.json' for target in TARGETS} | {asset_name(revision, target) for target in TARGETS}
    if {p.name for p in inputs.iterdir()} != expected:
        raise ValueError('Unexpected or missing files in the release bundle.')
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError('Assemble into an empty output directory.')
    for asset in assets.values():
        shutil.copyfile(inputs / asset['fileName'], output / asset['fileName'])
    manifest = {'schemaVersion': 1, 'version': revision, 'tag': revision, 'sourceCommit': source_commit, 'assets': assets}
    (output / 'manifest.json').write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
    sums = [f"{asset['sha256']}  {asset['fileName']}" for asset in assets.values()]
    sums.append(f"{digest(output / 'manifest.json')}  manifest.json")
    (output / 'SHA256SUMS').write_text('\n'.join(sorted(sums)) + '\n')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    staged = sub.add_parser('stage')
    staged.add_argument('--binary', type=Path, required=True)
    staged.add_argument('--target', choices=TARGETS, required=True)
    combined = sub.add_parser('assemble')
    combined.add_argument('--inputs', type=Path, required=True)
    for command_parser in (staged, combined):
        command_parser.add_argument('--version', required=True)
        command_parser.add_argument('--commit', required=True)
        command_parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'stage':
        stage(args.binary, args.target, args.version, args.commit, args.output)
    else:
        assemble(args.inputs, args.version, args.commit, args.output)
    print('Verified native release bundle: ' + args.command)


if __name__ == '__main__':
    main()
