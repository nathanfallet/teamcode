import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('release_request', Path(__file__).with_name('release-request.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseRequestTest(unittest.TestCase):
    def test_publication_requires_explicit_full_stable_main_request(self):
        good = dict(publish_release=True, full_validation=True, validation_version='0.1.0')
        self.assertTrue(release.validate_request('workflow_dispatch', 'refs/heads/main', good, '0.1.0'))
        self.assertTrue(release.validate_request('workflow_dispatch', 'refs/heads/main', dict(good, publish_release='true', full_validation='true'), '0.1.0'))
        for ref, inputs, version in [
            ('refs/heads/feature', good, '0.1.0'),
            ('refs/heads/main', dict(good, full_validation=False), '0.1.0'),
            ('refs/heads/main', dict(good, validation_version=''), '0.1.0'),
            *[('refs/heads/main', dict(good, validation_version=v), v) for v in ['v0.1.0', '0.1.0-SNAPSHOT', '0.1.0-alpha.1', '00.1.0', '0.1.0/escape']],
        ]:
            with self.subTest(ref=ref, version=version), self.assertRaises(ValueError):
                release.validate_request('workflow_dispatch', ref, inputs, version)

    def test_ordinary_ci_and_acceptance_do_not_publish(self):
        self.assertFalse(release.validate_request('push', 'refs/heads/main', {}, '0.1.0-SNAPSHOT'))
        self.assertFalse(release.validate_request('pull_request', 'refs/pull/1', dict(publish_release=True), '0.1.0'))
        self.assertFalse(release.validate_request('workflow_dispatch', 'refs/heads/main', dict(full_validation=True), '0.1.0'))

    def test_release_identity_binds_exact_tag_version_and_source(self):
        good = dict(version='0.1.0', tag='0.1.0', sourceCommit='a' * 40)
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'manifest.json'
            path.write_text(json.dumps(good))
            release.validate_manifest(path, '0.1.0', 'a' * 40)
            for change in [dict(tag='v0.1.0'), dict(version='0.2.0'), dict(sourceCommit='b' * 40)]:
                path.write_text(json.dumps(dict(good, **change)))
                with self.subTest(change=change), self.assertRaises(ValueError):
                    release.validate_manifest(path, '0.1.0', 'a' * 40)
