#!/usr/bin/env python3
"""Stage TeamCode Desktop packages, assemble their signed-manifest payload and finish the release's file list.

The packages `teamcode ui` installs (one per target, listed in `desktop-manifest.json` with `product: desktop`):
the `.app` zipped with `ditto` on macOS, the per-user `.msi` on Windows, the app's folder as a `.tar.gz` on Linux.
Their names follow `DesktopReleaseFormat.fileName` of the CLI. The other packages (`.dmg`, `.deb`, `.rpm`) are for
people downloading by hand: attached to the release and listed in `SHA256SUMS`, not in the manifest.

    release-desktop.py stage --package FILE --target T --version V --commit SHA --output DIR
    release-desktop.py extra --package FILE --target T --version V --output DIR
    release-desktop.py assemble --inputs DIR --version V --commit SHA --output DIR
    release-desktop.py sums --directory DIR
    release-desktop.py notes --directory DIR --notes FILE
"""
import argparse
import importlib.util
import json
from pathlib import Path
import shutil
import tarfile
import zipfile


def sibling(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), Path(__file__).with_name(name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


release = sibling('release-bundle')
TARGETS = release.TARGETS
MAX_PACKAGE = 512 * 1024 * 1024
MANIFEST, SIGNATURE = 'desktop-manifest.json', 'desktop-manifest.sig'
# Hand-installed packages: the extension per target family, and how people use them.
EXTRAS = {'dmg': 'macos-', 'deb': 'linux-', 'rpm': 'linux-'}
OLE = bytes.fromhex('d0cf11e0a1b11ae1')


def file_name(revision, target):
    release.version(revision)
    if target not in TARGETS:
        raise ValueError('Unsupported desktop target.')
    extension = 'zip' if target.startswith('macos-') else 'msi' if target.startswith('windows-') else 'tar.gz'
    return f'teamcode-desktop-{revision}-{target}.{extension}'


def extra_name(revision, target, kind):
    release.version(revision)
    if target not in TARGETS or kind not in EXTRAS or not target.startswith(EXTRAS[kind]):
        raise ValueError('Unsupported hand-installed package for this target.')
    return f'teamcode-desktop-{revision}-{target}.{kind}'


def check_layout(path, target):
    """The package's format, and for archives a single top-level app that `teamcode ui` expects."""
    with path.open('rb') as source:
        head = source.read(8)
    if target.startswith('windows-'):
        if head != OLE:
            raise ValueError('The Windows package is not an installer database.')
        return
    if target.startswith('macos-'):
        if not zipfile.is_zipfile(path):
            raise ValueError('The macOS package is not a zip archive.')
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
        top = {name.split('/', 1)[0] for name in names}
        if top != {'TeamCode.app'} or 'TeamCode.app/Contents/MacOS/TeamCode' not in names:
            raise ValueError('The macOS package must hold TeamCode.app only.')
        return
    if head[:2] != b'\x1f\x8b':
        raise ValueError('The Linux package is not a gzip archive.')
    with tarfile.open(path, 'r:gz') as archive:
        members = archive.getmembers()
    top = {member.name.lstrip('./').split('/', 1)[0] for member in members if member.name.lstrip('./')}
    executable = [member for member in members if member.name.lstrip('./') == 'TeamCode/bin/TeamCode' and member.isfile()]
    if top != {'TeamCode'} or not executable or any(member.issym() and member.linkname.startswith('/') for member in members):
        raise ValueError('The Linux package must hold the TeamCode folder only, with bin/TeamCode.')


def stage(package, target, revision, source_commit, output):
    release.version(revision); release.commit(source_commit)
    package = package.resolve(strict=True)
    size = package.stat().st_size
    if package.is_symlink() or not 1 <= size <= MAX_PACKAGE:
        raise ValueError('The desktop package is missing or exceeds its size bound.')
    check_layout(package, target)
    output.mkdir(parents=True, exist_ok=True)
    name = file_name(revision, target)
    destination, record_path = output / name, output / f'desktop-{target}.json'
    if destination.exists() or record_path.exists():
        raise ValueError('Refusing to replace an existing staged package.')
    shutil.copyfile(package, destination)
    record = {'target': target, 'version': revision, 'sourceCommit': source_commit,
              'fileName': name, 'sha256': release.digest(destination), 'size': size}
    record_path.write_text(json.dumps(record, sort_keys=True) + '\n')
    return record


def extra(package, target, revision, output):
    kind = package.name.rsplit('.', 1)[-1]
    name = extra_name(revision, target, kind)
    package = package.resolve(strict=True)
    if package.is_symlink() or not 1 <= package.stat().st_size <= MAX_PACKAGE:
        raise ValueError('The package is missing or exceeds its size bound.')
    output.mkdir(parents=True, exist_ok=True)
    if (output / name).exists():
        raise ValueError('Refusing to replace an existing package.')
    shutil.copyfile(package, output / name)
    return name


def assemble(inputs, revision, source_commit, output):
    """`desktop-manifest.json` for all four targets, from staged records whose files still match."""
    release.version(revision); release.commit(source_commit)
    assets = {}
    for target in TARGETS:
        path = inputs / f'desktop-{target}.json'
        if path.stat().st_size > 4096:
            raise ValueError('Package metadata exceeds the size limit.')
        metadata = json.loads(path.read_text())
        name = file_name(revision, target)
        package = inputs / name
        if (metadata.get('target') != target or metadata.get('version') != revision or
                metadata.get('sourceCommit') != source_commit or metadata.get('fileName') != name):
            raise ValueError('Mixed target, version, source commit or package name.')
        size = package.stat().st_size
        if (package.is_symlink() or not 1 <= size <= MAX_PACKAGE or type(metadata.get('size')) is not int or
                metadata['size'] != size or metadata.get('sha256') != release.digest(package)):
            raise ValueError('Desktop package integrity check failed.')
        check_layout(package, target)
        assets[target] = {key: metadata[key] for key in ('fileName', 'sha256', 'size')}
    expected = {f'desktop-{target}.json' for target in TARGETS} | {file_name(revision, target) for target in TARGETS}
    if {path.name for path in inputs.iterdir()} != expected:
        raise ValueError('Unexpected or missing files among the desktop packages.')
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError('Assemble into an empty output directory.')
    for asset in assets.values():
        shutil.copyfile(inputs / asset['fileName'], output / asset['fileName'])
    manifest = {'schemaVersion': 1, 'product': 'desktop', 'version': revision, 'tag': revision,
                'sourceCommit': source_commit, 'assets': assets}
    (output / MANIFEST).write_text(json.dumps(manifest, indent=2, sort_keys=True) + '\n')
    return manifest


def sums(directory):
    """`SHA256SUMS` over every published file of the release (the CLI's, the desktop's, the chart)."""
    files = sorted(path for path in directory.iterdir() if path.name != 'SHA256SUMS')
    if any(path.is_symlink() or not path.is_file() for path in files):
        raise ValueError('The release directory holds something other than regular files.')
    (directory / 'SHA256SUMS').write_text(''.join(f'{release.digest(path)}  {path.name}\n' for path in files))


TARGET_LABELS = {'macos-arm64': 'macOS (Apple silicon)', 'windows-x64': 'Windows (x64)',
                 'linux-x64': 'Linux (x64)', 'linux-arm64': 'Linux (arm64)'}
KIND_LABELS = {'zip': 'app (zip)', 'dmg': 'disk image', 'msi': 'per-user installer', 'tar.gz': 'folder (tar.gz)',
               'deb': 'Debian/Ubuntu package', 'rpm': 'Fedora/RHEL package'}


def notes(directory, notes_file):
    """Appends the desktop downloads, read from the signed manifest and the files present, to the release notes."""
    manifest = json.loads((directory / MANIFEST).read_text())
    revision = release.version(manifest['version'])
    lines = ['', '## TeamCode Desktop', '',
             f'Run `teamcode ui` to install or update the desktop app (it checks the signed `{MANIFEST}`), or download the package of your system:', '']
    for target in TARGETS:
        names = [file_name(revision, target)] + [extra_name(revision, target, kind) for kind in EXTRAS if target.startswith(EXTRAS[kind])]
        present = [name for name in names if (directory / name).is_file()]
        if not present:
            continue
        kinds = [name[len(f'teamcode-desktop-{revision}-{target}.'):] for name in present]
        lines.append(f'- {TARGET_LABELS[target]}: ' + ', '.join(f'`{name}` ({KIND_LABELS[kind]})' for name, kind in zip(present, kinds)))
    lines += ['', 'macOS packages are signed with a Developer ID and notarized. The Windows installer is not signed by a '
              'paid service, so SmartScreen warns on first launch ("More info", then "Run anyway"). Every file is listed '
              'in `SHA256SUMS`.', '']
    with notes_file.open('a', encoding='utf-8') as output:
        output.write('\n'.join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    staged = sub.add_parser('stage')
    staged.add_argument('--package', type=Path, required=True)
    staged.add_argument('--target', choices=TARGETS, required=True)
    staged.add_argument('--commit', required=True)
    extras = sub.add_parser('extra')
    extras.add_argument('--package', type=Path, required=True)
    extras.add_argument('--target', choices=TARGETS, required=True)
    combined = sub.add_parser('assemble')
    combined.add_argument('--inputs', type=Path, required=True)
    combined.add_argument('--commit', required=True)
    for command_parser in (staged, extras, combined):
        command_parser.add_argument('--version', required=True)
        command_parser.add_argument('--output', type=Path, required=True)
    summed = sub.add_parser('sums')
    summed.add_argument('--directory', type=Path, required=True)
    noted = sub.add_parser('notes')
    noted.add_argument('--directory', type=Path, required=True)
    noted.add_argument('--notes', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'stage':
        stage(args.package, args.target, args.version, args.commit, args.output)
    elif args.command == 'extra':
        extra(args.package, args.target, args.version, args.output)
    elif args.command == 'assemble':
        assemble(args.inputs, args.version, args.commit, args.output)
    elif args.command == 'sums':
        sums(args.directory)
    else:
        notes(args.directory, args.notes)
    print('Desktop release: ' + args.command)


if __name__ == '__main__':
    main()
