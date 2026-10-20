"""Guard the version-transition evidence: equal payloads/versions cannot pass preparation."""
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('installation_fixture', Path(__file__).with_name('installation-fixture.py'))
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)


class InstallationFixtureTest(unittest.TestCase):
    def candidate(self, directory, version, payload, commit='a' * 40):
        directory.mkdir()
        # Only the preparation guard is under test; these are never executable release candidates.
        binary = struct.pack('<II', 0xFEEDFACF, 0x100000C) + bytes(24) + payload
        name = fixtures.bundle.asset_name(version, 'macos-arm64')
        (directory / name).write_bytes(binary)
        manifest = {'schemaVersion': 1, 'version': version, 'tag': version, 'sourceCommit': commit,
                    'assets': {'macos-arm64': {'fileName': name, 'sha256': hashlib.sha256(binary).hexdigest(), 'size': len(binary)}}}
        (directory / 'manifest.json').write_text(json.dumps(manifest))
        (directory / 'manifest.sig').write_bytes(b'public unit fixture, not a publisher signature')

    def test_equal_versions_or_equal_binary_bytes_cannot_claim_a_version_transition(self):
        for versions, payloads in [(('1.0.0', '1.0.0'), (b'old', b'new')), (('1.0.0', '1.1.0'), (b'same', b'same'))]:
            with self.subTest(versions=versions), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                for label, version, payload in zip(('first', 'second'), versions, payloads):
                    self.candidate(root / label, version, payload)
                with self.assertRaises(ValueError):
                    fixtures.prepare(root / 'first', root / 'second', root / 'output', 'macos-arm64')
                self.assertFalse((root / 'output').exists())

    def test_mixed_source_or_corrupted_asset_cannot_enter_native_acceptance(self):
        for corruption in ('commit', 'binary'):
            with self.subTest(corruption=corruption), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.candidate(root / 'first', '1.0.0', b'old')
                self.candidate(root / 'second', '1.1.0', b'new', 'b' * 40 if corruption == 'commit' else 'a' * 40)
                if corruption == 'binary':
                    (root / 'second' / fixtures.bundle.asset_name('1.1.0', 'macos-arm64')).write_bytes(b'corrupt')
                with self.assertRaises(ValueError):
                    fixtures.prepare(root / 'first', root / 'second', root / 'output', 'macos-arm64')
                self.assertFalse((root / 'output').exists())

    def test_distinct_versions_keep_their_own_exact_metadata_and_asset(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.candidate(root / 'first', '1.0.0', b'old')
            self.candidate(root / 'second', '1.1.0', b'new')
            fixtures.prepare(root / 'first', root / 'second', root / 'output', 'macos-arm64')
            for label in ('first', 'second'):
                for source in (root / label).iterdir():
                    self.assertEqual(source.read_bytes(), (root / 'output' / label / source.name).read_bytes())


if __name__ == '__main__': unittest.main()
