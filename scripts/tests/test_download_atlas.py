from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


def make_test_symlink(test, link, target, *, target_is_directory=False):
    try:
        link.symlink_to(target, target_is_directory=target_is_directory)
    except (OSError, NotImplementedError) as error:
        if isinstance(error, NotImplementedError) or (os.name == "nt" and getattr(error, "winerror", None) in (5, 50, 1314)):
            test.skipTest("This runner does not permit symbolic links")
        raise


SCRIPT = Path(__file__).resolve().parents[1] / "download_atlas.py"
SPEC = importlib.util.spec_from_file_location("download_atlas", SCRIPT)
download_atlas = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(download_atlas)


class DownloadAtlasTest(unittest.TestCase):
    def test_dry_run_does_not_create_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self.make_manifest(root, "https://example.invalid")
            output = root / "not-created"
            stdout = io.StringIO()
            with patch.object(
                download_atlas, "DEFAULT_MANIFEST", manifest
            ), contextlib.redirect_stdout(stdout):
                result = download_atlas.main(
                    [
                        "--atlas",
                        "allen_mouse_25um",
                        "--output",
                        str(output),
                        "--max-bytes",
                        "1000",
                        "--dry-run",
                    ]
                )
            self.assertEqual(0, result)
            self.assertFalse(output.exists())
            self.assertIn("Dry run", stdout.getvalue())

    def test_limit_is_required_and_enforced(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self.make_manifest(root, "https://example.invalid")
            stderr = io.StringIO()
            with patch.object(
                download_atlas, "DEFAULT_MANIFEST", manifest
            ), contextlib.redirect_stderr(
                stderr
            ), contextlib.redirect_stdout(io.StringIO()):
                result = download_atlas.main(
                    [
                        "--atlas",
                        "allen_mouse_25um",
                        "--output",
                        str(root / "cache"),
                        "--max-bytes",
                        "2",
                        "--dry-run",
                    ]
                )
            self.assertEqual(2, result)
            self.assertIn("above --max-bytes", stderr.getvalue())

    def test_installs_then_verifies_existing_cache_with_server_offline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = {
                "template.nrrd": b"template",
                "annotation.nrrd": b"annotation",
                "ontology.json": b'{"success":true,"msg":[]}',
            }
            manifest = self.make_manifest(
                root, "https://example.invalid", files
            )
            output = root / "cache"
            arguments = [
                "--atlas",
                "allen_mouse_25um",
                "--output",
                str(output),
                "--max-bytes",
                "1000",
                "--acknowledge-terms",
            ]
            output.mkdir()
            (output / "template.nrrd.part").write_bytes(files["template.nrrd"])
            requested_names = []

            def fake_urlopen(request, timeout):
                name = Path(request.full_url).name
                requested_names.append(name)
                response = io.BytesIO(files[name])
                response.status = 200
                response.geturl = lambda: request.full_url
                return response

            with patch.object(
                download_atlas.urllib.request,
                "urlopen",
                side_effect=fake_urlopen,
            ), patch.object(
                download_atlas, "DEFAULT_MANIFEST", manifest
            ), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, download_atlas.main(arguments))
            self.assertNotIn("template.nrrd", requested_names)

            with patch.object(
                download_atlas.urllib.request,
                "urlopen",
                side_effect=AssertionError("offline reload attempted network"),
            ), patch.object(
                download_atlas, "DEFAULT_MANIFEST", manifest
            ), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, download_atlas.main(arguments))
            self.assertTrue((output / "manifest.json").is_file())
            record = json.loads((output / "install-record.json").read_text())
            self.assertTrue(record["termsAcknowledged"])

    def test_dangling_output_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self.make_manifest(root, "https://example.invalid")
            output = root / "cache-link"
            escaped = root / "outside"
            make_test_symlink(self, output, escaped, target_is_directory=True)
            stderr = io.StringIO()
            with patch.object(
                download_atlas, "DEFAULT_MANIFEST", manifest
            ), contextlib.redirect_stderr(stderr):
                result = download_atlas.main(
                    [
                        "--atlas",
                        "allen_mouse_25um",
                        "--output",
                        str(output),
                        "--max-bytes",
                        "1000",
                        "--acknowledge-terms",
                    ]
                )
            self.assertEqual(2, result)
            self.assertFalse(escaped.exists())
            self.assertIn("symbolic link", stderr.getvalue())

    def test_dangling_partial_symlink_is_rejected_without_network(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self.make_manifest(root, "https://example.invalid")
            output = root / "cache"
            output.mkdir()
            escaped = root / "outside-file"
            make_test_symlink(self, output / "template.nrrd.part", escaped)
            stderr = io.StringIO()
            with patch.object(
                download_atlas, "DEFAULT_MANIFEST", manifest
            ), patch.object(
                download_atlas.urllib.request,
                "urlopen",
                side_effect=AssertionError("symlink check attempted network"),
            ), contextlib.redirect_stderr(
                stderr
            ), contextlib.redirect_stdout(io.StringIO()):
                result = download_atlas.main(
                    [
                        "--atlas",
                        "allen_mouse_25um",
                        "--output",
                        str(output),
                        "--max-bytes",
                        "1000",
                        "--acknowledge-terms",
                    ]
                )
            self.assertEqual(2, result)
            self.assertFalse(escaped.exists())
            self.assertIn("symbolic link", stderr.getvalue())

    def test_http_range_resume_appends_only_requested_remainder(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            content = b"template"
            asset = {
                "role": "template",
                "relativePath": "template.nrrd",
                "url": "https://example.invalid/template.nrrd",
                "sizeBytes": len(content),
                "sha256": hashlib.sha256(content).hexdigest(),
            }
            (output / "template.nrrd.part").write_bytes(b"tem")
            observed_range = []

            def fake_urlopen(request, timeout):
                observed_range.append(request.get_header("Range"))
                response = io.BytesIO(b"plate")
                response.status = 206
                response.geturl = lambda: request.full_url
                return response

            with patch.object(
                download_atlas.urllib.request,
                "urlopen",
                side_effect=fake_urlopen,
            ):
                result = download_atlas.download_asset(
                    asset, output, timeout_seconds=5, retries=1
                )
            self.assertEqual("downloaded", result)
            self.assertEqual(["bytes=3-"], observed_range)
            self.assertEqual(content, (output / "template.nrrd").read_bytes())

    def test_hash_mismatch_is_quarantined(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            asset = {
                "role": "template",
                "relativePath": "template.nrrd",
                "url": "https://example.invalid/template.nrrd",
                "sizeBytes": 4,
                "sha256": hashlib.sha256(b"good").hexdigest(),
            }

            def fake_urlopen(request, timeout):
                response = io.BytesIO(b"evil")
                response.status = 200
                response.geturl = lambda: request.full_url
                return response

            with patch.object(
                download_atlas.urllib.request,
                "urlopen",
                side_effect=fake_urlopen,
            ):
                with self.assertRaises(download_atlas.InstallError):
                    download_atlas.download_asset(
                        asset, output, timeout_seconds=5, retries=1
                    )
            self.assertFalse((output / "template.nrrd").exists())
            self.assertEqual(
                1,
                len(list(output.glob("template.nrrd.part.invalid-*"))),
            )

    @staticmethod
    def make_manifest(
        root: Path,
        base_url: str,
        files: dict[str, bytes] | None = None,
    ) -> Path:
        files = files or {
            "template.nrrd": b"t",
            "annotation.nrrd": b"a",
            "ontology.json": b"o",
        }
        roles = ["template", "annotation", "ontology"]
        assets = []
        for role, (name, content) in zip(roles, files.items()):
            assets.append(
                {
                    "role": role,
                    "relativePath": name,
                    "url": f"{base_url}/{name}",
                    "sizeBytes": len(content),
                    "sha256": hashlib.sha256(content).hexdigest(),
                }
            )
        manifest = {
            "schemaVersion": 1,
            "atlasId": "allen_mouse_25um",
            "atlasVersion": "test",
            "resolutionMicrometers": 25,
            "coordinateSpace": "left-posterior-superior",
            "dimensions": [1, 1, 1],
            "termsUrl": "https://example.invalid/terms",
            "citation": "test citation",
            "assets": assets,
        }
        path = root / "manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return path


if __name__ == "__main__":
    unittest.main()
