from pathlib import Path
import hashlib
import importlib.util
import json
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "verify_bundle.py"
SPEC = importlib.util.spec_from_file_location("verify_bundle", SCRIPT)
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

class ReleaseIntegrityTest(unittest.TestCase):
    def test_detects_corruption_and_unexpected_files(self):
        with tempfile.TemporaryDirectory(prefix="release path ") as temporary:
            root = Path(temporary)
            (root / "payload").write_bytes(b"test")
            manifest = {"version": "fixture", "files": [{"path": "payload", "size": 4, "sha256": hashlib.sha256(b"test").hexdigest()}]}
            (root / "bundle-manifest.json").write_text(json.dumps(manifest))
            module.verify(root)
            (root / "payload").write_bytes(b"fail")
            with self.assertRaisesRegex(ValueError, "Checksum"):
                module.verify(root)
            (root / "payload").write_bytes(b"test")
            (root / "unexpected").write_text("extra")
            with self.assertRaisesRegex(ValueError, "Unexpected"):
                module.verify(root)

    def test_rejects_parent_traversal(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "bundle-manifest.json").write_text(json.dumps({"version": "fixture", "files": [{"path": "../outside", "size": 0, "sha256": ""}]}))
            with self.assertRaisesRegex(ValueError, "Unsafe"):
                module.verify(root)
