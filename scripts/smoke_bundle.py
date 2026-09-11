#!/usr/bin/env python3
"""Run a platform-local Java smoke check from the packaged runtime JARs."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from verify_bundle import verify

root = Path(sys.argv[1]).resolve()
verify(root)
classpath = os.pathsep.join(str(p) for folder in ("plugins", "jars") for p in sorted((root / folder).glob("*.jar")))
source = '''
import org.atlasalign.atlas.AtlasManifests;
import org.atlasalign.plugin.ui.PluginBranding;
public class BundleSmoke {
    public static void main(String[] args) {
        if (!"allen_mouse_25um".equals(AtlasManifests.allenMouse25um().atlasId())) throw new AssertionError("manifest");
        if (!"0.1.0-beta.3".equals(PluginBranding.version())) throw new AssertionError("version");
        if (!PluginBranding.aboutText().contains("Made by Meghamsh Teja Konda")) throw new AssertionError("credit");
        System.out.println("Packaged atlas/Jackson/branding loaded on " + System.getProperty("os.name") + " / Java " + System.getProperty("java.version"));
    }
}
'''
with tempfile.TemporaryDirectory(prefix="atlasalign smoke ") as temporary:
    path = Path(temporary) / "BundleSmoke.java"
    path.write_text(source)
    subprocess.run(["java", "-Djava.awt.headless=true", "-cp", classpath, str(path)], check=True)
