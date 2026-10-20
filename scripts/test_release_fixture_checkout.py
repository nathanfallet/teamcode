"""A Windows-style Git checkout must preserve publisher-authenticated fixture bytes."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('release_signature', ROOT / 'scripts/release-signature.py')
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class SignedFixtureCheckoutTest(unittest.TestCase):
    def test_autocrlf_checkout_preserves_the_real_publisher_signature(self):
        with tempfile.TemporaryDirectory(prefix='teamcode-signed-checkout-') as directory:
            root = Path(directory)
            fixture = Path('scripts/fixtures/release-publisher-manifest.json')
            signature = Path('scripts/fixtures/release-publisher-manifest.sig')
            originals = {}
            for relative in (fixture, signature, Path('.gitattributes')):
                data = (ROOT / relative).read_bytes()
                destination = root / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(data)
                originals[relative] = data
            def git(*arguments):
                return subprocess.run(['git', '-c', 'core.autocrlf=true', *arguments], cwd=root,
                                      capture_output=True, check=True, timeout=10).stdout
            git('init', '--quiet')
            git('add', '.')
            (root / fixture).unlink()
            (root / signature).unlink()
            git('checkout-index', '--all', '--force')
            for relative in (fixture, signature):
                self.assertEqual(originals[relative], (root / relative).read_bytes())
            signing.verify(root / fixture, root / signature, ROOT / 'release/trust/teamcode-release-2026.pem')
            # CRLF conversion is a genuine signature mismatch, never something a verifier repairs.
            converted = root / 'translated.json'
            converted.write_bytes(originals[fixture].replace(b'\n', b'\r\n'))
            with self.assertRaises(ValueError):
                signing.verify(converted, root / signature, ROOT / 'release/trust/teamcode-release-2026.pem')


if __name__ == '__main__':
    unittest.main()
