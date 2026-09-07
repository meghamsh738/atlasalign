from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


def make_test_symlink(test, link, target, *, target_is_directory=False):
    try:
        link.symlink_to(target, target_is_directory=target_is_directory)
    except (OSError, NotImplementedError) as error:
        if isinstance(error, NotImplementedError) or (os.name == "nt" and getattr(error, "winerror", None) in (5, 50, 1314)):
            test.skipTest("This runner does not permit symbolic links")
        raise


SCRIPT = (
    Path(__file__).resolve().parents[1] / "finalize_deepslice_runtime.py"
)
SPEC = importlib.util.spec_from_file_location(
    "finalize_deepslice_runtime", SCRIPT
)
finalizer = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(finalizer)


class FinalizeDeepSliceRuntimeTest(unittest.TestCase):
    def test_roles_cover_security_sensitive_assets(self):
        self.assertEqual(
            "PYTHON_EXECUTABLE", finalizer.role("bin/python3.11")
        )
        self.assertEqual(
            "WORKER_SCRIPT", finalizer.role("worker/deepslice_worker.py")
        )
        self.assertEqual(
            "MODEL_PRIMARY",
            finalizer.role(
                "lib/python3.11/site-packages/DeepSlice/metadata/"
                "weights/Allen_Mixed_Best.h5"
            ),
        )
        self.assertEqual(
            "MODEL_SECONDARY",
            finalizer.role(
                "lib/python3.11/site-packages/DeepSlice/metadata/"
                "weights/Synthetic_data_final.hdf5"
            ),
        )
        self.assertEqual(
            "MODEL_BACKBONE",
            finalizer.role(
                "lib/python3.11/site-packages/DeepSlice/metadata/weights/"
                "xception_weights_tf_dim_ordering_tf_kernels.h5"
            ),
        )
        self.assertEqual(
            "DEEPSLICE_PACKAGE",
            finalizer.role(
                "lib/python3.11/site-packages/DeepSlice/DeepSlice.py"
            ),
        )
        self.assertEqual(
            "NATIVE_LIBRARY",
            finalizer.role(
                "lib/python3.11/site-packages/tensorflow/libexample.dylib"
            ),
        )
        self.assertEqual(
            "METADATA", finalizer.role("runtime-provenance.json")
        )
        self.assertEqual(
            "PYTHON_RUNTIME", finalizer.role("lib/python3.11/json/__init__.py")
        )

    def test_manifest_is_complete_and_deterministic(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "bin").mkdir()
            executable = root / "bin/python3.11"
            executable.write_bytes(b"python")
            executable.chmod(0o700)
            (root / "ordinary.txt").write_bytes(b"ordinary")

            first = finalizer.build_manifest(root)
            second = finalizer.build_manifest(root)

            self.assertEqual(first, second)
            self.assertEqual(finalizer.PROTOCOL_VERSION, first["protocolVersion"])
            self.assertEqual(
                ["bin/python3.11", "ordinary.txt"],
                [asset["relativePath"] for asset in first["assets"]],
            )
            self.assertTrue(first["assets"][0]["executable"])
            self.assertEqual(os.access(root / "ordinary.txt", os.X_OK), first["assets"][1]["executable"])

    def test_manifest_rejects_symbolic_links(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "target").write_bytes(b"target")
            make_test_symlink(self, root / "link", "target")

            with self.assertRaisesRegex(ValueError, "symbolic link"):
                finalizer.build_manifest(root)

    def test_candidate_allows_only_contained_symbolic_links(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            contained = root / "contained"
            contained.write_bytes(b"contained")
            make_test_symlink(self, root / "contained-link", contained)

            finalizer.validate_candidate_tree(root)

            outside = root.parent / f"{root.name}-outside"
            outside.write_bytes(b"outside")
            try:
                make_test_symlink(self, root / "escaping-link", outside)
                with self.assertRaisesRegex(ValueError, "escapes root"):
                    finalizer.validate_candidate_tree(root)
            finally:
                outside.unlink(missing_ok=True)

    def test_candidate_rejects_directory_link_and_cycle(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            child = root / "child"
            child.mkdir()
            make_test_symlink(self, child / "back", root, target_is_directory=True)

            with self.assertRaisesRegex(
                ValueError, "directory symbolic links"
            ):
                finalizer.validate_candidate_tree(root)

    def test_output_must_be_outside_candidate_even_through_parent_link(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate"
            candidate.mkdir()

            with self.assertRaisesRegex(
                ValueError, "outside the candidate"
            ):
                finalizer.validate_output_location(
                    candidate, candidate / "output"
                )

            linked_parent = root / "linked-candidate"
            make_test_symlink(self, linked_parent, candidate, target_is_directory=True)
            with self.assertRaisesRegex(
                ValueError, "outside the candidate"
            ):
                finalizer.validate_output_location(
                    candidate, linked_parent / "output"
                )

            with self.assertRaisesRegex(
                ValueError, "outside the candidate"
            ):
                finalizer.validate_output_location(
                    candidate,
                    root / "missing/../candidate/output",
                )

            case_variant = candidate.with_name(candidate.name.upper())
            if case_variant.exists() and case_variant != candidate:
                with self.assertRaisesRegex(
                    ValueError, "outside the candidate"
                ):
                    finalizer.validate_output_location(
                        candidate, case_variant / "output"
                    )

            requested = root / "safe-parent/output"
            expected = root.resolve() / "safe-parent/output"
            self.assertEqual(
                expected,
                finalizer.validate_output_location(candidate, requested),
            )

    @unittest.skipUnless(hasattr(os, "link"), "hard links unavailable")
    def test_manifest_rejects_hard_links(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / "original"
            original.write_bytes(b"same inode")
            os.link(original, root / "alias")

            with self.assertRaisesRegex(ValueError, "hard-linked"):
                finalizer.build_manifest(root)

    @unittest.skipUnless(hasattr(os, "link"), "hard links unavailable")
    def test_candidate_rejects_hard_linked_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            original = root / "original"
            original.write_bytes(b"same inode")
            os.link(original, root / "alias")

            with self.assertRaisesRegex(ValueError, "hard-linked candidate"):
                finalizer.validate_candidate_tree(root)

    def test_wheel_inventory_requires_pinned_deepslice_wheel(self):
        with tempfile.TemporaryDirectory() as directory:
            wheelhouse = Path(directory)
            (wheelhouse / "other-1.0-py3-none-any.whl").write_bytes(b"x")

            with self.assertRaisesRegex(ValueError, "DeepSlice 1.2.8"):
                finalizer.wheel_inventory(wheelhouse)

    def test_finalizes_protocol_v2_at_absent_path_and_refuses_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root / "candidate"
            candidate.mkdir()
            (candidate / "bin").mkdir()
            python = candidate / "bin/python3.11"
            python.write_bytes(b"python")
            python.chmod(0o700)
            worker = root / "deepslice_worker.py"
            worker.write_text("print('worker')\n", encoding="utf-8")
            wheelhouse = root / "wheelhouse"
            wheelhouse.mkdir()
            (wheelhouse / "deepslice-1.2.8-py3-none-any.whl").write_bytes(
                b"wheel"
            )
            output = root / "new/runtime-v2"
            arguments = [
                "finalize_deepslice_runtime.py",
                "--candidate", str(candidate),
                "--output", str(output),
                "--worker", str(worker),
                "--wheelhouse", str(wheelhouse),
            ]

            with mock.patch.object(finalizer, "verify_models"), mock.patch.object(
                sys, "argv", arguments
            ):
                self.assertEqual(0, finalizer.main())

            manifest = json.loads(
                (output / "installation-manifest.json").read_text(
                    encoding="utf-8"
                )
            )
            self.assertEqual(2, manifest["protocolVersion"])
            manifest_bytes = (output / "installation-manifest.json").read_bytes()
            with mock.patch.object(finalizer, "verify_models"), mock.patch.object(
                sys, "argv", arguments
            ):
                with self.assertRaisesRegex(ValueError, "output already exists"):
                    finalizer.main()
            self.assertEqual(
                manifest_bytes,
                (output / "installation-manifest.json").read_bytes(),
            )


if __name__ == "__main__":
    unittest.main()
