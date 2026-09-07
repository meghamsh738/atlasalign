#!/usr/bin/env python3
"""Create a versioned Fiji add-on and updater input, without touching Fiji."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULES = ("core", "application", "atlas", "io", "deepslice-adapter", "plugin")
JACKSON = ("jackson-core", "jackson-annotations", "jackson-databind")
NS = {"m": "http://maven.apache.org/POM/4.0.0"}

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def package(output, repository):
    pom = ET.parse(ROOT / "pom.xml").getroot()
    version = pom.find("m:version", NS).text
    jackson_version = pom.find("m:properties/m:atlasalign.jackson.version", NS).text
    name = "atlasalign-lite-" + version
    output.mkdir(parents=True, exist_ok=True)
    final = output / name
    archive = output / (name + ".zip")
    if final.exists() or archive.exists():
        raise ValueError("Release output already exists; use a new output directory")
    with tempfile.TemporaryDirectory(prefix="atlasalign-package-") as temporary:
        bundle = Path(temporary) / name
        (bundle / "plugins").mkdir(parents=True)
        (bundle / "jars").mkdir()
        for module in MODULES:
            filename = f"atlasalign-{module}-{version}.jar"
            shutil.copy2(ROOT / f"atlasalign-{module}" / "target" / filename, bundle / "plugins" / filename)
        for artifact in JACKSON:
            filename = f"{artifact}-{jackson_version}.jar"
            source = repository / "com/fasterxml/jackson/core" / artifact / jackson_version / filename
            shutil.copy2(source, bundle / "jars" / filename)
        for filename in ("LICENSE", "THIRD_PARTY_NOTICES.md", "CITATION.cff"):
            shutil.copy2(ROOT / filename, bundle / filename)
        shutil.copytree(ROOT / "docs/community", bundle / "docs")
        shutil.copytree(ROOT / "demo", bundle / "demo")
        shutil.copy2(ROOT / "scripts/verify_bundle.py", bundle / "verify_bundle.py")
        records = []
        for path in sorted(bundle.rglob("*")):
            if path.is_file():
                records.append({"path": path.relative_to(bundle).as_posix(), "size": path.stat().st_size, "sha256": digest(path)})
        (bundle / "bundle-manifest.json").write_text(json.dumps({"version": version, "requires": "Fiji/ImageJ2, Java 17+", "files": records}, indent=2) + "\n")
        shutil.copytree(bundle, final)
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zipped:
            for path in sorted(final.rglob("*")):
                if path.is_file():
                    zipped.write(path, name + "/" + path.relative_to(final).as_posix())
    (output / "SHA256SUMS.txt").write_text(digest(archive) + "  " + archive.name + "\n")
    # This is input to the official Updater, not hand-made db.xml.gz metadata.
    updater = output / (name + "-updater-input")
    updater.mkdir()
    for folder in ("plugins", "jars"):
        shutil.copytree(final / folder, updater / folder)
    shutil.copy2(ROOT / "docs/community/UPDATE_SITE.md", updater / "README.md")
    print(archive)
    return final

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--maven-repository", type=Path, default=Path(os.environ.get("MAVEN_REPOSITORY", Path.home() / ".m2/repository")))
    args = parser.parse_args()
    package(args.output.resolve(), args.maven_repository.resolve())
