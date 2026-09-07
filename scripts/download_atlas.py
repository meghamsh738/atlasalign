#!/usr/bin/env python3
"""Bounded, resumable installer for the pinned Allen Mouse CCFv3 25 µm cache."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MANIFEST = (
    PROJECT_ROOT
    / "atlasalign-atlas"
    / "src"
    / "main"
    / "resources"
    / "org"
    / "atlasalign"
    / "atlas"
    / "allen_mouse_25um.json"
)
BUFFER_SIZE = 1024 * 1024


class InstallError(RuntimeError):
    """Raised when the atlas cannot be installed without weakening safety."""


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Install the pinned Allen Mouse CCFv3 25 µm template, annotation, "
            "and ontology with byte-length and SHA-256 verification."
        )
    )
    parser.add_argument("--atlas", required=True, choices=["allen_mouse_25um"])
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--max-bytes",
        required=True,
        type=positive_int,
        help="Explicit upper bound for all proposed assets.",
    )
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--acknowledge-terms",
        action="store_true",
        help="Required for downloads; confirms review of the recorded terms URL.",
    )
    parser.add_argument("--timeout-seconds", type=bounded_timeout, default=30)
    parser.add_argument("--retries", type=bounded_retries, default=3)
    return parser.parse_args(argv)


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("value must be positive")
    return parsed


def bounded_retries(value: str) -> int:
    parsed = positive_int(value)
    if parsed > 10:
        raise argparse.ArgumentTypeError("retries must not exceed 10")
    return parsed


def bounded_timeout(value: str) -> int:
    parsed = positive_int(value)
    if parsed > 300:
        raise argparse.ArgumentTypeError(
            "timeout-seconds must not exceed 300"
        )
    return parsed


def load_manifest(path: Path) -> tuple[bytes, dict]:
    try:
        raw = path.read_bytes()
        manifest = json.loads(raw)
    except (OSError, json.JSONDecodeError) as error:
        raise InstallError(f"Could not read atlas manifest: {path}") from error
    expected_fields = {
        "schemaVersion",
        "atlasId",
        "atlasVersion",
        "resolutionMicrometers",
        "coordinateSpace",
        "dimensions",
        "termsUrl",
        "citation",
        "assets",
    }
    if (
        not isinstance(manifest, dict)
        or set(manifest) != expected_fields
        or manifest["schemaVersion"] != 1
    ):
        raise InstallError("Atlas manifest schema is incomplete or unsupported")
    if manifest["atlasId"] != "allen_mouse_25um":
        raise InstallError("Only the pinned allen_mouse_25um atlas is supported")
    if (
        not isinstance(manifest["atlasVersion"], str)
        or not manifest["atlasVersion"].strip()
        or manifest["resolutionMicrometers"] != 25
        or manifest["coordinateSpace"] != "left-posterior-superior"
        or not isinstance(manifest["dimensions"], list)
        or len(manifest["dimensions"]) != 3
        or any(
            not isinstance(axis, int) or axis <= 0
            for axis in manifest["dimensions"]
        )
        or not isinstance(manifest["termsUrl"], str)
        or not manifest["termsUrl"].startswith("https://")
        or not isinstance(manifest["citation"], str)
        or not manifest["citation"].strip()
    ):
        raise InstallError("Atlas manifest metadata is invalid")
    assets = manifest["assets"]
    if not isinstance(assets, list) or len(assets) != 3:
        raise InstallError("Atlas manifest must contain exactly three assets")
    roles: set[str] = set()
    paths: set[str] = set()
    for asset in assets:
        validate_asset(asset)
        roles.add(asset["role"])
        paths.add(asset["relativePath"])
    if roles != {"template", "annotation", "ontology"} or len(paths) != 3:
        raise InstallError("Atlas assets must have unique required roles and paths")
    return raw, manifest


def validate_asset(asset: dict) -> None:
    required = {"role", "relativePath", "url", "sizeBytes", "sha256"}
    if not isinstance(asset, dict) or set(asset) != required:
        raise InstallError("Atlas asset has unexpected or missing fields")
    if (
        not isinstance(asset["role"], str)
        or not asset["role"].strip()
        or not isinstance(asset["relativePath"], str)
        or not isinstance(asset["url"], str)
    ):
        raise InstallError("Atlas asset text fields are invalid")
    relative = Path(asset["relativePath"])
    if (
        relative.is_absolute()
        or len(relative.parts) != 1
        or relative.name in {"", ".", ".."}
    ):
        raise InstallError("Atlas asset path is unsafe")
    parsed_url = urllib.parse.urlparse(asset["url"])
    is_loopback_test = (
        parsed_url.scheme == "http"
        and parsed_url.hostname in {"127.0.0.1", "localhost", "::1"}
    )
    if parsed_url.scheme != "https" and not is_loopback_test:
        raise InstallError("Atlas asset URL must use HTTPS")
    if not isinstance(asset["sizeBytes"], int) or asset["sizeBytes"] <= 0:
        raise InstallError("Atlas asset size must be positive")
    sha256 = asset["sha256"]
    if (
        not isinstance(sha256, str)
        or len(sha256) != 64
        or any(character not in "0123456789abcdef" for character in sha256)
    ):
        raise InstallError("Atlas asset SHA-256 is invalid")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(BUFFER_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


def is_verified(path: Path, asset: dict) -> bool:
    return (
        path.is_file()
        and not path.is_symlink()
        and path.stat().st_size == asset["sizeBytes"]
        and sha256(path) == asset["sha256"]
    )


def lexists(path: Path) -> bool:
    return os.path.lexists(os.fspath(path))


def download_asset(
    asset: dict,
    output: Path,
    timeout_seconds: int,
    retries: int,
) -> str:
    destination = output / asset["relativePath"]
    if destination.is_symlink():
        raise InstallError(f"Final asset path is a symbolic link: {destination}")
    if destination.exists():
        if is_verified(destination, asset):
            return "verified-existing"
        raise InstallError(
            f"Existing final asset is not the pinned file: {destination}"
        )
    partial = destination.with_name(destination.name + ".part")
    if partial.is_symlink():
        raise InstallError(f"Partial asset path is a symbolic link: {partial}")
    if lexists(partial) and not partial.is_file():
        raise InstallError(f"Partial asset path is unsafe: {partial}")
    if partial.exists() and partial.stat().st_size > asset["sizeBytes"]:
        raise InstallError(f"Partial asset exceeds pinned size: {partial}")
    if partial.exists() and partial.stat().st_size == asset["sizeBytes"]:
        if sha256(partial) == asset["sha256"]:
            partial.rename(destination)
            return "resumed-complete"
        quarantine = partial.with_name(partial.name + ".invalid-complete")
        partial.rename(quarantine)

    last_error: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            offset = partial.stat().st_size if partial.exists() else 0
            request = urllib.request.Request(
                asset["url"],
                headers=(
                    {"Range": f"bytes={offset}-", "User-Agent": "AtlasAlign-Lite/0.1"}
                    if offset
                    else {"User-Agent": "AtlasAlign-Lite/0.1"}
                ),
            )
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                final_url = urllib.parse.urlparse(response.geturl())
                final_is_loopback_test = (
                    final_url.scheme == "http"
                    and final_url.hostname in {
                        "127.0.0.1",
                        "localhost",
                        "::1",
                    }
                )
                if final_url.scheme != "https" and not final_is_loopback_test:
                    raise InstallError(
                        f"Download redirected to non-HTTPS URL for "
                        f"{asset['relativePath']}"
                    )
                status = getattr(response, "status", None)
                append = offset > 0 and status == 206
                if offset > 0 and not append:
                    offset = 0
                mode = "ab" if append else "wb"
                written = offset
                with partial.open(mode) as stream:
                    while chunk := response.read(BUFFER_SIZE):
                        written += len(chunk)
                        if written > asset["sizeBytes"]:
                            raise InstallError(
                                f"Server exceeded pinned size for {asset['relativePath']}"
                            )
                        stream.write(chunk)
            if partial.stat().st_size != asset["sizeBytes"]:
                raise InstallError(
                    f"Incomplete download for {asset['relativePath']}"
                )
            actual = sha256(partial)
            if actual != asset["sha256"]:
                quarantine = partial.with_name(
                    partial.name + f".invalid-{actual[:12]}"
                )
                partial.rename(quarantine)
                raise InstallError(
                    f"SHA-256 mismatch for {asset['relativePath']}; "
                    f"preserved as {quarantine.name}"
                )
            partial.rename(destination)
            return "downloaded"
        except (OSError, urllib.error.URLError, InstallError) as error:
            last_error = error
            if attempt < retries:
                time.sleep(min(2 ** (attempt - 1), 8))
    raise InstallError(
        f"Failed to install {asset['relativePath']} after {retries} attempts"
    ) from last_error


def atomic_write_new_or_equal(path: Path, content: bytes) -> None:
    if path.is_symlink():
        raise InstallError(f"Metadata path is a symbolic link: {path}")
    if path.exists():
        if not path.is_file() or path.is_symlink() or path.read_bytes() != content:
            raise InstallError(f"Refusing to replace existing metadata: {path}")
        return
    temporary = path.with_name(path.name + ".part")
    if lexists(temporary):
        raise InstallError(f"Metadata temporary path already exists: {temporary}")
    temporary.write_bytes(content)
    temporary.rename(path)


def install(args: argparse.Namespace) -> int:
    raw_manifest, manifest = load_manifest(DEFAULT_MANIFEST)
    total = sum(asset["sizeBytes"] for asset in manifest["assets"])
    if total > args.max_bytes:
        raise InstallError(
            f"Proposed atlas is {total} bytes, above --max-bytes {args.max_bytes}"
        )
    print(
        f"{manifest['atlasId']} ({manifest['atlasVersion']}): "
        f"{total} bytes across {len(manifest['assets'])} assets"
    )
    for asset in manifest["assets"]:
        print(
            f"  {asset['role']}: {asset['relativePath']} "
            f"({asset['sizeBytes']} bytes, sha256={asset['sha256']})"
        )
    print(f"Terms: {manifest['termsUrl']}")
    print(f"Citation: {manifest['citation']}")
    if args.dry_run:
        print("Dry run: no directories or files were created.")
        return 0
    if not args.acknowledge_terms:
        raise InstallError(
            "Download requires --acknowledge-terms after reviewing the terms URL"
        )

    requested_output = args.output.expanduser()
    if requested_output.is_symlink():
        raise InstallError(
            f"Output directory must not be a symbolic link: {requested_output}"
        )
    output = requested_output.resolve()
    if output.exists() and (not output.is_dir() or output.is_symlink()):
        raise InstallError(f"Output directory is unsafe: {output}")
    output.mkdir(parents=True, exist_ok=True)
    existing_manifest_path = output / "manifest.json"
    if (
        lexists(existing_manifest_path)
        and (
            existing_manifest_path.is_symlink()
            or not existing_manifest_path.is_file()
            or existing_manifest_path.read_bytes() != raw_manifest
        )
    ):
        raise InstallError(
            f"Existing manifest does not match the pinned cache: "
            f"{existing_manifest_path}"
        )
    results = []
    for asset in manifest["assets"]:
        result = download_asset(
            asset, output, args.timeout_seconds, args.retries
        )
        results.append({"asset": asset["relativePath"], "result": result})
        print(f"{result}: {asset['relativePath']}")

    atomic_write_new_or_equal(output / "manifest.json", raw_manifest)
    record = {
        "schemaVersion": 1,
        "atlasId": manifest["atlasId"],
        "atlasVersion": manifest["atlasVersion"],
        "installedAtUtc": dt.datetime.now(dt.timezone.utc)
        .replace(microsecond=0)
        .isoformat(),
        "manifestSha256": hashlib.sha256(raw_manifest).hexdigest(),
        "maxBytes": args.max_bytes,
        "termsUrl": manifest["termsUrl"],
        "termsAcknowledged": True,
        "assets": results,
    }
    record_bytes = (
        json.dumps(record, indent=2, sort_keys=True).encode("utf-8") + b"\n"
    )
    record_path = output / "install-record.json"
    if lexists(record_path):
        if record_path.is_symlink() or not record_path.is_file():
            raise InstallError(
                f"Existing install record is unsafe: {record_path}"
            )
        try:
            existing_record = json.loads(record_path.read_bytes())
        except (OSError, json.JSONDecodeError) as error:
            raise InstallError(
                f"Existing install record is unreadable: {record_path}"
            ) from error
        if (
            existing_record.get("manifestSha256")
            != record["manifestSha256"]
            or existing_record.get("termsAcknowledged") is not True
        ):
            raise InstallError(
                f"Existing install record does not match this cache: {record_path}"
            )
    else:
        atomic_write_new_or_equal(record_path, record_bytes)
    print(f"Installed and verified offline cache: {output}")
    return 0


def main(argv: list[str] | None = None) -> int:
    try:
        return install(parse_args(argv))
    except InstallError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
