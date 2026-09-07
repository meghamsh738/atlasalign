#!/usr/bin/env python3
"""Export an allowlisted, history-free community source tree. Never publishes."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TOP = {"README.md", "LICENSE", "THIRD_PARTY_NOTICES.md", "CITATION.cff", "CONTRIBUTING.md", "pom.xml", ".gitignore"}
SCRIPTS = {"test.sh", "test.py", "download_atlas.py", "finalize_deepslice_runtime.py", "package_beta.py", "verify_bundle.py", "smoke_bundle.py", "fresh_fiji_smoke.py", "prepare_public_snapshot.py"}
TESTS = {"test_download_atlas.py", "test_finalize_deepslice_runtime.py", "test_deepslice_worker.py", "test_release_packaging.py"}
DOCS = {"docs/SCIENTIFIC_INVARIANTS.md", "docs/TRANSFORM_CONVENTIONS.md"}


def selected(name):
    parts = Path(name).parts
    return (name in TOP or name in DOCS
            or name.startswith(("docs/community/", ".github/", "demo/"))
            or name == "python/deepslice_worker.py"
            or (len(parts) == 2 and parts[0] == "scripts" and parts[1] in SCRIPTS)
            or (len(parts) == 3 and parts[:2] == ("scripts", "tests") and parts[2] in TESTS)
            or (parts[0].startswith("atlasalign-") and (len(parts) == 2 and parts[1] == "pom.xml"
                or len(parts) > 2 and parts[1] == "src")))


def export(destination):
    if destination.exists():
        raise ValueError("Snapshot destination must not already exist")
    names = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT).decode().split("\0")
    candidates = sorted({name for name in names if name and selected(name)})
    records = []
    for name in candidates:
        source = ROOT / name
        if source.is_symlink() or not source.is_file():
            raise ValueError("Unsafe source entry: " + name)
        data = source.read_bytes()
        text = data.decode("utf-8")
        # Literal fragments assembled here so the exporter can include itself.
        blocked = ("/" + "Users/", "/" + "Volumes/", "github.com/meghamsh738/" + "atlasalign-lite", "PRIVATE" + " KEY-----")
        if any(token in text for token in blocked):
            raise ValueError("Private-location/history marker in public candidate: " + name)
        records.append({"path": name, "sha256": hashlib.sha256(data).hexdigest()})
    destination.mkdir(parents=True)
    for item in records:
        target = destination / item["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / item["path"], target)
    (destination / "AGENTS.md").write_text("# AtlasAlign Lite contributor instructions\n\nRead docs/SCIENTIFIC_INVARIANTS.md and docs/TRANSFORM_CONVENTIONS.md before image, coordinate or export changes. Source images are read-only. Preserve contributor notices. Run ./scripts/test.sh (Windows: python scripts/test.py). Keep laboratory data, weights and runtime caches out of source control.\n")
    (destination.parent / (destination.name + "-source-manifest.json")).write_text(json.dumps({"format": 1, "files": records, "generated": ["AGENTS.md"], "historyIncluded": False}, indent=2) + "\n")
    print("Prepared " + str(len(records)) + " source files at " + str(destination))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    export(parser.parse_args().output.resolve())
