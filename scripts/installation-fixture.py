#!/usr/bin/env python3
"""Prepare two distinct compiled versions for signed native acceptance; never read a key."""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil

spec = importlib.util.spec_from_file_location('release_bundle', Path(__file__).with_name('release-bundle.py'))
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


def prepare(first, second, output, target):
    records = []
    for source in (first, second):
        if not 1 <= (source / 'manifest.json').stat().st_size <= 16 * 1024:
            raise ValueError('Invalid bounded release manifest.')
        manifest = json.loads((source / 'manifest.json').read_bytes())
        asset = manifest['assets'][target]
        name = bundle.asset_name(manifest['version'], target)
        binary = source / name
        if asset['fileName'] != name or binary.is_symlink() or not binary.is_file() or not 1 <= binary.stat().st_size <= bundle.MAX_BINARY:
            raise ValueError('Invalid acceptance asset.')
        if binary.stat().st_size != asset['size'] or bundle.digest(binary) != asset['sha256'] or bundle.binary_target(binary) != target:
            raise ValueError('Acceptance asset integrity or architecture mismatch.')
        records.append((manifest, name, binary))
    if records[0][0]['sourceCommit'] != records[1][0]['sourceCommit']:
        raise ValueError('Both acceptance builds must describe the same source commit.')
    if records[0][0]['version'] == records[1][0]['version'] or records[0][0]['assets'][target]['sha256'] == records[1][0]['assets'][target]['sha256']:
        raise ValueError('Acceptance requires distinct compiled versions and binary bytes.')
    output.mkdir(parents=True, exist_ok=False)
    for label, metadata, (_, name, binary) in zip(('first', 'second'), (first, second), records):
        destination = output / label
        destination.mkdir()
        for filename, maximum in (('manifest.json', 16 * 1024), ('manifest.sig', 2048)):
            data = (metadata / filename).read_bytes()
            if not 1 <= len(data) <= maximum:
                raise ValueError('Acceptance metadata exceeds its bound.')
            (destination / filename).write_bytes(data)
        shutil.copyfile(binary, destination / name)
        (destination / name).chmod(0o755)
    # Native smoke verifies both publisher signatures and exact executable versions.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--first-bundle', type=Path, required=True)
    parser.add_argument('--second-bundle', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--target', choices=bundle.TARGETS, required=True)
    args = parser.parse_args()
    prepare(args.first_bundle, args.second_bundle, args.output, args.target)
    print('Prepared distinct compiled versions for native installation acceptance.')


if __name__ == '__main__': main()
