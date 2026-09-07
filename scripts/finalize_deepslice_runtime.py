#!/usr/bin/env python3
"""Create a self-contained, fully inventoried AtlasAlign DeepSlice runtime."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import shutil
import tempfile

PYTHON_VERSION = "3.11.15"
DEEPSLICE_VERSION = "1.2.8"
TENSORFLOW_VERSION = "2.21.0"
MODEL_RELEASE = "ebrains-mouse-ensemble-2025-01-31"
PLATFORM_ARCHITECTURE = "mac-os-x-aarch64"
PROTOCOL_VERSION = 2
MODEL_ASSETS = {
    "lib/python3.11/site-packages/DeepSlice/metadata/weights/Allen_Mixed_Best.h5":
        ("MODEL_PRIMARY",
         84_925_432,
         "da34a5bca0245314a68daff30c1446758c0851b21370ef9797d5288018b08717"),
    "lib/python3.11/site-packages/DeepSlice/metadata/weights/Synthetic_data_final.hdf5":
        ("MODEL_SECONDARY",
         84_925_052,
         "b85b7325158d117b2ac7559495c0b50dfcf3545aa29281a345f9ef1e33542e42"),
    "lib/python3.11/site-packages/DeepSlice/metadata/weights/xception_weights_tf_dim_ordering_tf_kernels.h5":
        ("MODEL_BACKBONE",
         91_884_032,
         "c5bf1c05b020c4177164039b854c3ef92b16b73384734647c1f0ad5cf79f6975"),
}


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(64 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def role(relative_path: str) -> str:
    if relative_path == "bin/python3.11":
        return "PYTHON_EXECUTABLE"
    if relative_path == "worker/deepslice_worker.py":
        return "WORKER_SCRIPT"
    if relative_path in MODEL_ASSETS:
        return MODEL_ASSETS[relative_path][0]
    if relative_path == "runtime-provenance.json":
        return "METADATA"
    if relative_path.endswith((".so", ".dylib")):
        return "NATIVE_LIBRARY"
    if relative_path.startswith(
        "lib/python3.11/site-packages/DeepSlice/"
    ):
        return "DEEPSLICE_PACKAGE"
    return "PYTHON_RUNTIME"


def verify_models(root: pathlib.Path) -> None:
    for relative_path, (_, expected_size, expected_hash) in MODEL_ASSETS.items():
        path = root / relative_path
        if (
            not path.is_file()
            or path.stat().st_size != expected_size
            or sha256(path) != expected_hash
        ):
            raise ValueError(f"model identity mismatch: {relative_path}")


def wheel_inventory(wheelhouse: pathlib.Path) -> list[dict]:
    wheels = []
    for path in sorted(wheelhouse.glob("*.whl")):
        wheels.append({
            "filename": path.name,
            "sizeBytes": path.stat().st_size,
            "sha256": sha256(path),
        })
    if not any(
        item["filename"] == "deepslice-1.2.8-py3-none-any.whl"
        for item in wheels
    ):
        raise ValueError("DeepSlice 1.2.8 wheel is missing")
    return wheels


def write_provenance(
    root: pathlib.Path,
    wheelhouse: pathlib.Path,
) -> None:
    provenance = {
        "python": {
            "version": PYTHON_VERSION,
            "source": (
                "https://github.com/astral-sh/python-build-standalone/"
                "releases/download/20260728/"
                "cpython-3.11.15%2B20260728-aarch64-apple-darwin-"
                "install_only.tar.gz"
            ),
            "sizeBytes": 27_243_366,
            "sha256": (
                "7dc10e31eede05a6ab1ec9e0b961f521078b0959f838ed1d7452597d529ff802"
            ),
        },
        "packages": {
            "deepSliceVersion": DEEPSLICE_VERSION,
            "tensorflowVersion": TENSORFLOW_VERSION,
            "offlineWheelhouse": wheel_inventory(wheelhouse),
        },
        "models": [
            {
                "relativePath": path,
                "role": identity[0],
                "sizeBytes": identity[1],
                "sha256": identity[2],
            }
            for path, identity in MODEL_ASSETS.items()
        ],
        "modelRelease": MODEL_RELEASE,
        "platformArchitecture": PLATFORM_ARCHITECTURE,
        "inferencePolicy": {
            "network": "macOS sandbox deny network*",
            "childProcesses": "macOS sandbox deny process-fork",
            "proposalOnly": True,
        },
    }
    (root / "runtime-provenance.json").write_text(
        json.dumps(provenance, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def validate_candidate_tree(root: pathlib.Path) -> None:
    """Reject candidate entries that could materialize data from elsewhere."""
    trusted_root = root.resolve(strict=True)
    for directory, directory_names, file_names in os.walk(
        trusted_root, followlinks=False
    ):
        parent = pathlib.Path(directory)
        for name in directory_names + file_names:
            path = parent / name
            if path.is_symlink():
                try:
                    target = path.resolve(strict=True)
                except (OSError, RuntimeError) as error:
                    raise ValueError(
                        f"broken candidate symbolic link: "
                        f"{path.relative_to(trusted_root)}"
                    ) from error
                if not target.is_relative_to(trusted_root):
                    raise ValueError(
                        f"candidate symbolic link escapes root: "
                        f"{path.relative_to(trusted_root)}"
                    )
                if target.is_dir():
                    raise ValueError(
                        f"candidate directory symbolic links are unsupported: "
                        f"{path.relative_to(trusted_root)}"
                    )
                if not target.is_file():
                    raise ValueError(
                        f"unsafe candidate symbolic-link target: "
                        f"{path.relative_to(trusted_root)}"
                    )
            elif path.is_file():
                if path.stat().st_nlink != 1:
                    raise ValueError(
                        f"hard-linked candidate file: "
                        f"{path.relative_to(trusted_root)}"
                    )
            elif not path.is_dir():
                raise ValueError(
                    f"unsafe candidate entry: "
                    f"{path.relative_to(trusted_root)}"
                )


def resolve_planned_path(path: pathlib.Path) -> pathlib.Path:
    """Resolve existing ancestors without requiring the destination to exist."""
    path = path.absolute()
    missing_parts = []
    cursor = path
    while not cursor.exists() and not cursor.is_symlink():
        missing_parts.append(cursor.name)
        parent = cursor.parent
        if parent == cursor:
            break
        cursor = parent
    resolved = cursor.resolve(strict=True)
    for part in reversed(missing_parts):
        resolved /= part
    return resolved.resolve(strict=False)


def is_at_or_beneath(
    path: pathlib.Path,
    directory: pathlib.Path,
) -> bool:
    """Use file identity so containment works on case-insensitive volumes."""
    cursor = path
    while not cursor.exists():
        parent = cursor.parent
        if parent == cursor:
            return False
        cursor = parent
    for ancestor in (cursor, *cursor.parents):
        try:
            if os.path.samefile(ancestor, directory):
                return True
        except OSError:
            continue
    return False


def validate_output_location(
    candidate: pathlib.Path,
    output: pathlib.Path,
) -> pathlib.Path:
    if output.is_symlink():
        raise ValueError(f"output is a symbolic link: {output}")
    resolved_output = resolve_planned_path(output)
    trusted_candidate = candidate.resolve(strict=True)
    if is_at_or_beneath(resolved_output, trusted_candidate):
        raise ValueError("output must be outside the candidate tree")
    return resolved_output


def build_manifest(root: pathlib.Path) -> dict:
    assets = []
    file_keys = set()
    for path in sorted(root.rglob("*")):
        relative_path = path.relative_to(root).as_posix()
        if path.is_symlink():
            raise ValueError(f"symbolic link remains: {relative_path}")
        if path.is_dir():
            continue
        if not path.is_file() or relative_path == "installation-manifest.json":
            raise ValueError(f"unsafe runtime entry: {relative_path}")
        stat = path.stat()
        if stat.st_nlink != 1:
            raise ValueError(f"hard-linked runtime entry: {relative_path}")
        file_key = (stat.st_dev, stat.st_ino)
        if file_key in file_keys:
            raise ValueError(f"aliased runtime entry: {relative_path}")
        file_keys.add(file_key)
        assets.append({
            "relativePath": relative_path,
            "sizeBytes": stat.st_size,
            "sha256": sha256(path),
            "role": role(relative_path),
            "executable": os.access(path, os.X_OK),
        })
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "pythonVersion": PYTHON_VERSION,
        "deepSliceVersion": DEEPSLICE_VERSION,
        "tensorflowVersion": TENSORFLOW_VERSION,
        "modelRelease": MODEL_RELEASE,
        "platformArchitecture": PLATFORM_ARCHITECTURE,
        "assets": assets,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--worker", type=pathlib.Path, required=True)
    parser.add_argument("--wheelhouse", type=pathlib.Path, required=True)
    arguments = parser.parse_args()

    candidate = arguments.candidate.resolve(strict=True)
    worker = arguments.worker.resolve(strict=True)
    wheelhouse = arguments.wheelhouse.resolve(strict=True)
    output = validate_output_location(
        candidate, arguments.output.absolute()
    )
    if output.exists():
        raise ValueError(f"output already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    staging = pathlib.Path(tempfile.mkdtemp(
        prefix=f".{output.name}.partial-",
        dir=output.parent,
    ))
    try:
        validate_candidate_tree(candidate)
        shutil.copytree(
            candidate,
            staging,
            symlinks=False,
            dirs_exist_ok=True,
            copy_function=shutil.copy2,
        )
        (staging / "worker").mkdir()
        shutil.copy2(worker, staging / "worker/deepslice_worker.py")
        verify_models(staging)
        write_provenance(staging, wheelhouse)
        manifest = build_manifest(staging)
        (staging / "installation-manifest.json").write_text(
            json.dumps(manifest, separators=(",", ":"), sort_keys=True),
            encoding="utf-8",
        )
        staging.rename(output)
    except Exception:
        shutil.rmtree(staging)
        raise
    print(output)
    print(
        f"manifest_bytes={output.joinpath('installation-manifest.json').stat().st_size}"
    )
    print(
        f"manifest_sha256={sha256(output / 'installation-manifest.json')}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
