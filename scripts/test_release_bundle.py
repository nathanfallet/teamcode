import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release_bundle', Path(__file__).with_name('release-bundle.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


def native_header(target):
    data = bytearray(256)
    if target.startswith('linux'):
        data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', data, 18, 183 if target.endswith('arm64') else 62)
    elif target.startswith('macos'):
        data[:4] = b'\xcf\xfa\xed\xfe'
        struct.pack_into('<I', data, 4, 0x100000C if target.endswith('arm64') else 0x1000007)
    else:
        data[:2] = b'MZ'; struct.pack_into('<I', data, 60, 128)
        data[128:132] = b'PE\0\0'; struct.pack_into('<H', data, 132, 0x8664)
    return data


class ReleaseBundleTest(unittest.TestCase):
    revision = '0.1.0-alpha.1'
    source_commit = 'a' * 40

    def fixture(self, inputs):
        inputs.mkdir()
        for target in release.TARGETS:
            name = release.asset_name(self.revision, target)
            binary = inputs / name; binary.write_bytes(native_header(target))
            metadata = dict(target=target, version=self.revision, sourceCommit=self.source_commit,
                            fileName=name, sha256=release.digest(binary), size=binary.stat().st_size)
            (inputs / f'{target}.json').write_text(json.dumps(metadata))

    def test_assembled_manifest_identifies_all_five_targets_and_every_hash(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); inputs = root / 'inputs'; self.fixture(inputs)
            manifest = release.assemble(inputs, self.revision, self.source_commit, root / 'output')
            self.assertEqual(set(release.TARGETS), set(manifest['assets']))
            self.assertEqual(self.revision, manifest['tag'])
            sums = (root / 'output/SHA256SUMS').read_text().splitlines()
            self.assertEqual(6, len(sums))
            for line in sums:
                checksum, name = line.split('  ')
                self.assertEqual(checksum, release.digest(root / 'output' / name))
            self.assertEqual(7, len(list((root / 'output').iterdir())))
            with self.assertRaises(ValueError):
                release.assemble(inputs, self.revision, self.source_commit, root / 'output')

    def test_corrupt_mixed_and_unexpected_assets_are_rejected_before_copying(self):
        for mutation in ('bytes', 'target', 'version', 'commit', 'path', 'extra', 'missing', 'symlink'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); inputs = root / 'inputs'; self.fixture(inputs)
                target = 'linux-x64'; metadata_path = inputs / f'{target}.json'
                metadata = json.loads(metadata_path.read_text()); binary = inputs / metadata['fileName']
                if mutation == 'bytes': binary.write_bytes(binary.read_bytes() + b'tampered')
                elif mutation == 'target':
                    binary.write_bytes(native_header('linux-arm64')); metadata['sha256'] = release.digest(binary)
                elif mutation == 'version': metadata['version'] = '0.2.0'
                elif mutation == 'commit': metadata['sourceCommit'] = 'b' * 40
                elif mutation == 'path': metadata['fileName'] = '../outside'
                elif mutation == 'extra': (inputs / 'credential-file').write_text('unexpected')
                elif mutation == 'missing': binary.unlink()
                else:
                    binary.rename(root / 'outside'); binary.symlink_to(root / 'outside')
                metadata_path.write_text(json.dumps(metadata))
                with self.assertRaises((ValueError, FileNotFoundError)):
                    release.assemble(inputs, self.revision, self.source_commit, root / 'output')
                self.assertFalse((root / 'output').exists())

    def test_version_commit_target_and_binary_format_are_validated(self):
        for invalid in ('../escape', 'v1.0.0', '1.2', '1.2.3+unsupported', '1.2.3\n', '01.2.3', '1.2.3-01', '1.2.3-alpha.01', 'a' * 81):
            with self.assertRaises(ValueError): release.version(invalid)
        for invalid in ('main', 'a' * 39, 'A' * 40):
            with self.assertRaises(ValueError): release.commit(invalid)
        with self.assertRaises(ValueError): release.asset_name(self.revision, '../target')
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'binary'
            path.write_bytes(b'not a native executable')
            with self.assertRaises(ValueError): release.binary_target(path)
            for target in release.TARGETS:
                path.write_bytes(native_header(target)); self.assertEqual(target, release.binary_target(path))


if __name__ == '__main__':
    unittest.main()
