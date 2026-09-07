#!/usr/bin/env python3
"""Strict AtlasAlign Lite protocol worker for local DeepSlice 1.2.8."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import math
import numbers
import pathlib
import sys

MAX_PIXELS = 5_000_000
PROTOCOL_VERSION = 2
OUV_COLUMNS = ("ox", "oy", "oz", "ux", "uy", "uz", "vx", "vy", "vz")
EXPECTED_REQUEST_KEYS = {
    "protocolVersion",
    "requestId",
    "width",
    "height",
    "pixelsFile",
    "pixelsSha256",
    "syntheticMaskFile",
    "syntheticMaskSha256",
    "pythonVersion",
    "deepSliceVersion",
    "tensorflowVersion",
    "modelRelease",
    "primaryWeightSha256",
    "secondaryWeightSha256",
    "backboneWeightSha256",
}


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def exact_json(path: pathlib.Path) -> dict:
    def reject_constant(value: str) -> None:
        raise ValueError(f"non-finite JSON value: {value}")

    def reject_duplicates(pairs: list[tuple[str, object]]) -> dict:
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON key: {key}")
            result[key] = value
        return result

    with path.open("r", encoding="utf-8") as stream:
        value = json.load(
            stream,
            parse_constant=reject_constant,
            object_pairs_hook=reject_duplicates,
        )
    if not isinstance(value, dict) or set(value) != EXPECTED_REQUEST_KEYS:
        raise ValueError("request schema mismatch")
    return value


def require_protocol_v2(request: dict) -> None:
    if request["protocolVersion"] != PROTOCOL_VERSION:
        raise ValueError("unsupported protocol")


def finite_ouv(values: object, label: str) -> tuple[float, ...]:
    """Return one exact-order finite binary64 O/U/V vector."""
    try:
        components = tuple(values)
    except TypeError as error:
        raise ValueError(f"{label} O/U/V is not an array") from error
    if len(components) != len(OUV_COLUMNS):
        raise ValueError(f"{label} O/U/V must have exactly nine components")
    normalized = []
    for component in components:
        if isinstance(component, bool) or not isinstance(component, numbers.Real):
            raise ValueError(f"{label} O/U/V component is not numeric")
        binary64 = float(component)
        if not math.isfinite(binary64):
            raise ValueError(f"{label} O/U/V component is non-finite")
        normalized.append(binary64)
    return tuple(normalized)


def prediction_ouv(predictions: object, label: str) -> tuple[float, ...]:
    """Read exactly one raw float64 DeepSlice row without geometry conversion."""
    if len(predictions) != 1:
        raise ValueError(f"DeepSlice {label} pass returned the wrong row count")
    row = predictions.iloc[0]
    return finite_ouv(
        row[list(OUV_COLUMNS)].to_numpy(dtype="float64"),
        label,
    )


def predict_constituent_ouvs(
    model: object,
    inference_image: pathlib.Path,
) -> tuple[tuple[float, ...], tuple[float, ...]]:
    """Run primary first, then the genuine secondary constituent model pass."""
    arguments = {
        "image_list": [str(inference_image)],
        "ensemble": False,
        "section_numbers": False,
    }
    model.predict(**arguments, use_secondary_model=False)
    primary = prediction_ouv(model.predictions, "primary")
    model.predict(**arguments, use_secondary_model=True)
    secondary = prediction_ouv(model.predictions, "secondary")
    return primary, secondary


def arithmetic_ensemble(
    primary: object,
    secondary: object,
) -> tuple[float, ...]:
    """Compute the component-wise binary64 mean required by protocol v2."""
    first = finite_ouv(primary, "primary")
    second = finite_ouv(secondary, "secondary")
    return finite_ouv(
        tuple((first[index] + second[index]) / 2.0
              for index in range(len(OUV_COLUMNS))),
        "ensemble",
    )


def response_payload(
    request: dict,
    python_version: str,
    deep_slice_version: str,
    tensorflow_version: str,
    primary: object,
    secondary: object,
) -> dict:
    """Build the exact protocol-v2 identity echo and raw-vector response."""
    primary_ouv = finite_ouv(primary, "primary")
    secondary_ouv = finite_ouv(secondary, "secondary")
    ensemble_ouv = arithmetic_ensemble(primary_ouv, secondary_ouv)
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "requestId": request["requestId"],
        "pixelsSha256": request["pixelsSha256"],
        "pythonVersion": python_version,
        "deepSliceVersion": deep_slice_version,
        "tensorflowVersion": tensorflow_version,
        "modelRelease": request["modelRelease"],
        "primaryWeightSha256": request["primaryWeightSha256"],
        "secondaryWeightSha256": request["secondaryWeightSha256"],
        "backboneWeightSha256": request["backboneWeightSha256"],
        "primaryOuv": list(primary_ouv),
        "secondaryOuv": list(secondary_ouv),
        "ensembleOuv": list(ensemble_ouv),
    }


def contained_file(
    request_directory: pathlib.Path,
    raw_path: object,
    expected_size: int,
    expected_hash: object,
) -> pathlib.Path:
    if not isinstance(raw_path, str) or not isinstance(expected_hash, str):
        raise ValueError("invalid file identity")
    path = pathlib.Path(raw_path)
    if not path.is_absolute() or path.is_symlink():
        raise ValueError("request file is not absolute and regular")
    resolved = path.resolve(strict=True)
    if resolved.parent != request_directory or not resolved.is_file():
        raise ValueError("request file escaped its private directory")
    if resolved.stat().st_size != expected_size or sha256(resolved) != expected_hash:
        raise ValueError("request file identity mismatch")
    return resolved


def weight_paths() -> tuple[pathlib.Path, pathlib.Path, pathlib.Path]:
    from DeepSlice.metadata import metadata_loader

    config, metadata_path = metadata_loader.load_config()
    primary = metadata_loader.get_data_path(
        config["weight_file_paths"]["mouse"]["primary"], metadata_path
    )
    secondary = metadata_loader.get_data_path(
        config["weight_file_paths"]["mouse"]["secondary"], metadata_path
    )
    backbone = metadata_loader.get_data_path(
        config["weight_file_paths"]["xception_imagenet"], metadata_path
    )
    return tuple(pathlib.Path(value).resolve(strict=True) for value in (
        primary,
        secondary,
        backbone,
    ))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--request", required=True)
    parser.add_argument("--response", required=True)
    arguments = parser.parse_args()
    request_path = pathlib.Path(arguments.request).resolve(strict=True)
    response_path = pathlib.Path(arguments.response)
    request_directory = request_path.parent
    if (
        not response_path.is_absolute()
        or response_path.parent.resolve(strict=True) != request_directory
        or response_path.exists()
    ):
        raise ValueError("unsafe response path")

    request = exact_json(request_path)
    require_protocol_v2(request)
    width = request["width"]
    height = request["height"]
    if (
        not isinstance(width, int)
        or isinstance(width, bool)
        or not isinstance(height, int)
        or isinstance(height, bool)
        or width <= 0
        or height <= 0
        or width * height > MAX_PIXELS
    ):
        raise ValueError("invalid image dimensions")

    pixels_path = contained_file(
        request_directory,
        request["pixelsFile"],
        width * height * 4,
        request["pixelsSha256"],
    )
    mask_path = contained_file(
        request_directory,
        request["syntheticMaskFile"],
        width * height,
        request["syntheticMaskSha256"],
    )

    import numpy
    from PIL import Image
    import tensorflow

    python_version = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
    deep_slice_version = importlib.metadata.version("DeepSlice")
    if (
        request["pythonVersion"] != python_version
        or request["deepSliceVersion"] != deep_slice_version
        or request["tensorflowVersion"] != tensorflow.__version__
    ):
        raise ValueError("runtime version mismatch")

    primary, secondary, backbone = weight_paths()
    for path, key in (
        (primary, "primaryWeightSha256"),
        (secondary, "secondaryWeightSha256"),
        (backbone, "backboneWeightSha256"),
    ):
        if sha256(path) != request[key]:
            raise ValueError("model weight mismatch")

    pixels = numpy.fromfile(pixels_path, dtype="<f4").reshape((height, width))
    synthetic_mask = numpy.fromfile(mask_path, dtype="u1")
    if not numpy.isfinite(pixels).all() or not numpy.isin(
        synthetic_mask, (0, 1)
    ).all():
        raise ValueError("invalid input raster")
    low = float(pixels.min())
    high = float(pixels.max())
    if not high > low:
        raise ValueError("constant images cannot be estimated")
    image_bytes = numpy.rint(
        numpy.clip((pixels - low) / (high - low), 0.0, 1.0) * 255.0
    ).astype("u1")
    inference_image = request_directory / "inference.png"
    Image.fromarray(image_bytes, mode="L").save(inference_image)

    try:
        from DeepSlice import DSModel

        model = DSModel("mouse")
        primary, secondary = predict_constituent_ouvs(
                model, inference_image)
        response = response_payload(
            request,
            python_version,
            deep_slice_version,
            tensorflow.__version__,
            primary,
            secondary,
        )
        with response_path.open("x", encoding="utf-8") as stream:
            json.dump(
                response,
                stream,
                allow_nan=False,
                separators=(",", ":"),
                sort_keys=True,
            )
    finally:
        inference_image.unlink(missing_ok=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"AtlasAlign DeepSlice worker failed: {error}", file=sys.stderr)
        raise SystemExit(1)
