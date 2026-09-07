#!/usr/bin/env python3
"""Verify release contents without modifying an installation."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import zipfile


def verify(root):
    manifest = json.loads((root / "bundle-manifest.json").read_text())
    expected = {"bundle-manifest.json"}
    for item in manifest["files"]:
        relative = PurePosixPath(item["path"])
        if relative.is_absolute() or ".." in relative.parts or "\\" in item["path"]:
            raise ValueError("Unsafe manifest path")
        path = root.joinpath(*relative.parts)
        if path.is_symlink() or path.stat().st_size != item["size"]:
            raise ValueError("Size or symlink mismatch: " + item["path"])
        if hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError("Checksum mismatch: " + item["path"])
        expected.add(item["path"])
        if path.suffix == ".jar":
            with zipfile.ZipFile(path) as jar:
                if jar.testzip() is not None:
                    raise ValueError("Corrupt JAR: " + path.name)
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file()}
    if actual != expected:
        raise ValueError("Unexpected or missing bundle files: " + str(actual ^ expected))
    print("Verified " + str(len(expected) - 1) + " files for " + manifest["version"])

if __name__ == "__main__":
    verify(Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parent)
