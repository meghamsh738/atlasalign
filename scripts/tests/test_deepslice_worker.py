from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest


def make_test_symlink(test, link, target, *, target_is_directory=False):
    try:
        link.symlink_to(target, target_is_directory=target_is_directory)
    except (OSError, NotImplementedError) as error:
        if isinstance(error, NotImplementedError) or (os.name == "nt" and getattr(error, "winerror", None) in (5, 50, 1314)):
            test.skipTest("This runner does not permit symbolic links")
        raise


SCRIPT = Path(__file__).resolve().parents[2] / "python/deepslice_worker.py"
SPEC = importlib.util.spec_from_file_location("deepslice_worker", SCRIPT)
worker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(worker)


class _FakeRow:
    def __init__(self, values: list[float]):
        self._values = values

    def __getitem__(self, columns: list[str]) -> "_FakeRow":
        if tuple(columns) != worker.OUV_COLUMNS:
            raise AssertionError("worker selected an unexpected O/U/V order")
        return self

    def to_numpy(self, *, dtype: str) -> list[float]:
        if dtype != "float64":
            raise AssertionError("worker did not request float64 O/U/V values")
        return self._values


class _FakePredictions:
    def __init__(self, values: list[float]):
        self.iloc = [_FakeRow(values)]

    def __len__(self) -> int:
        return 1


class _FakeModel:
    def __init__(self, primary: list[float], secondary: list[float]):
        self._vectors = [primary, secondary]
        self.calls: list[dict] = []
        self.predictions = _FakePredictions(primary)

    def predict(self, **arguments: object) -> None:
        self.calls.append(arguments)
        self.predictions = _FakePredictions(self._vectors[len(self.calls) - 1])


class DeepSliceWorkerTest(unittest.TestCase):
    def test_exact_json_rejects_duplicate_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            request = Path(directory) / "request.json"
            request.write_text('{"protocolVersion":2,"protocolVersion":2}')

            with self.assertRaisesRegex(ValueError, "duplicate JSON key"):
                worker.exact_json(request)

    def test_exact_json_rejects_unknown_or_missing_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            request = Path(directory) / "request.json"
            request.write_text(json.dumps({"unexpected": True}))

            with self.assertRaisesRegex(ValueError, "schema mismatch"):
                worker.exact_json(request)

    def test_rejects_protocol_v1_before_any_inference_dependency_is_loaded(self):
        with self.assertRaisesRegex(ValueError, "unsupported protocol"):
            worker.require_protocol_v2({"protocolVersion": 1})

    def test_contained_file_accepts_exact_private_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            content = b"pixels"
            path = root / "pixels.f32le"
            path.write_bytes(content)

            result = worker.contained_file(
                root,
                str(path),
                len(content),
                hashlib.sha256(content).hexdigest(),
            )

            self.assertEqual(path, result)

    def test_contained_file_rejects_escape_and_identity_mismatch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            private = root / "private"
            private.mkdir()
            outside = root / "outside"
            outside.write_bytes(b"outside")

            with self.assertRaisesRegex(ValueError, "escaped"):
                worker.contained_file(
                    private,
                    str(outside),
                    outside.stat().st_size,
                    worker.sha256(outside),
                )

            inside = private / "pixels.f32le"
            inside.write_bytes(b"pixels")
            with self.assertRaisesRegex(ValueError, "identity mismatch"):
                worker.contained_file(
                    private,
                    str(inside),
                    inside.stat().st_size,
                    "0" * 64,
                )

    def test_contained_file_rejects_symbolic_link(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            target = root / "target"
            target.write_bytes(b"target")
            link = root / "link"
            make_test_symlink(self, link, target)

            with self.assertRaisesRegex(ValueError, "not absolute and regular"):
                worker.contained_file(
                    root,
                    str(link),
                    target.stat().st_size,
                    worker.sha256(target),
                )

    def test_protocol_v2_response_has_exact_identity_echoes_and_raw_vectors(self):
        request = {
            "requestId": "request-1",
            "pixelsSha256": "a" * 64,
            "modelRelease": "mouse-models",
            "primaryWeightSha256": "b" * 64,
            "secondaryWeightSha256": "c" * 64,
            "backboneWeightSha256": "d" * 64,
        }
        response = worker.response_payload(
            request,
            "3.11.15",
            "1.2.8",
            "2.21.0",
            [1.0] * 9,
            [3.0] * 9,
        )

        self.assertEqual(
            {
                "protocolVersion",
                "requestId",
                "pixelsSha256",
                "pythonVersion",
                "deepSliceVersion",
                "tensorflowVersion",
                "modelRelease",
                "primaryWeightSha256",
                "secondaryWeightSha256",
                "backboneWeightSha256",
                "primaryOuv",
                "secondaryOuv",
                "ensembleOuv",
            },
            set(response),
        )
        self.assertEqual(worker.PROTOCOL_VERSION, response["protocolVersion"])
        for key in (
            "requestId",
            "pixelsSha256",
            "modelRelease",
            "primaryWeightSha256",
            "secondaryWeightSha256",
            "backboneWeightSha256",
        ):
            self.assertEqual(request[key], response[key])
        self.assertEqual([1.0] * 9, response["primaryOuv"])
        self.assertEqual([3.0] * 9, response["secondaryOuv"])
        self.assertEqual([2.0] * 9, response["ensembleOuv"])
        self.assertNotIn("zeroBasedAnteriorPosteriorIndex", response)
        self.assertNotIn("sagittalTiltDegrees", response)
        self.assertNotIn("horizontalTiltDegrees", response)

    def test_primary_pass_precedes_secondary_and_preserves_constituents(self):
        primary = [float(index) for index in range(9)]
        secondary = [float(index + 10) for index in range(9)]
        model = _FakeModel(primary, secondary)

        observed_primary, observed_secondary = worker.predict_constituent_ouvs(
            model, Path("/private/request/inference.png")
        )

        self.assertEqual(tuple(primary), observed_primary)
        self.assertEqual(tuple(secondary), observed_secondary)
        self.assertNotEqual(observed_primary, observed_secondary)
        self.assertEqual(2, len(model.calls))
        self.assertFalse(model.calls[0]["use_secondary_model"])
        self.assertTrue(model.calls[1]["use_secondary_model"])
        for call in model.calls:
            self.assertFalse(call["ensemble"])
            self.assertFalse(call["section_numbers"])
            self.assertEqual([str(Path("/private/request/inference.png"))], call["image_list"])

    def test_ensemble_uses_component_wise_binary64_arithmetic(self):
        primary = [1.0, -3.0, 0.0, 7.0, 11.0, -13.0, 17.0, 19.0, -23.0]
        secondary = [2.0, 5.0, 4.0, -1.0, 9.0, 15.0, -7.0, 21.0, 25.0]

        self.assertEqual(
            (1.5, 1.0, 2.0, 3.0, 10.0, 1.0, 5.0, 20.0, 1.0),
            worker.arithmetic_ensemble(primary, secondary),
        )

    def test_ouv_requires_exactly_nine_finite_components(self):
        with self.assertRaisesRegex(ValueError, "exactly nine"):
            worker.finite_ouv([0.0] * 8, "test")
        with self.assertRaisesRegex(ValueError, "non-finite"):
            worker.finite_ouv([0.0] * 8 + [float("nan")], "test")
        with self.assertRaisesRegex(ValueError, "not numeric"):
            worker.finite_ouv([0.0] * 8 + ["1"], "test")


if __name__ == "__main__":
    unittest.main()
