#!/usr/bin/env python3
"""Read-only packaged-command smoke using an existing Fiji's baseline libraries.

Uses a temporary home/preferences root. Does not start or modify native Fiji.
"""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
from verify_bundle import verify

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--bundle", type=Path, required=True)
parser.add_argument("--fiji", type=Path, required=True)
args = parser.parse_args()
bundle, fiji = args.bundle.resolve(), args.fiji.resolve()
verify(bundle)
jars = [*sorted((bundle / "plugins").glob("*.jar")), *sorted((bundle / "jars").glob("*.jar"))]
# Fiji provides these APIs; do not include its installed third-party plugins.
for pattern in ("ij-*.jar", "scijava-common-*.jar", "parsington-*.jar"):
    jars += sorted((fiji / "jars").glob(pattern))
classpath = os.pathsep.join(map(str, jars))
source = '''
import ij.ImagePlus;
import ij.process.ShortProcessor;
import org.atlasalign.plugin.*;
import org.atlasalign.plugin.setup.RuntimeSettings;
import org.atlasalign.plugin.ui.PluginBranding;
import org.scijava.Context;
import org.scijava.command.CommandService;
import java.nio.file.Path;
public class FreshFijiSmoke {
 public static void main(String[] args) throws Exception {
  var settings = new RuntimeSettings().paths();
  if (!settings.atlasCache().startsWith(Path.of(System.getProperty("user.home")))) throw new AssertionError("old settings leaked");
  try (var context = new Context(CommandService.class)) {
   var commands = context.service(CommandService.class);
   for (var type : new Class[]{PrepareSafePreviewCommand.class, SingleSectionReviewCommand.class, BatchReviewCommand.class, AtlasSetupCommand.class, AboutAtlasAlignCommand.class}) {
    if (commands.getCommand(type) == null) throw new AssertionError("missing command " + type);
   }
   var pixels = new short[128*96]; pixels[20] = 1234;
   var input = new ImagePlus("Synthetic fresh smoke", new ShortProcessor(128,96,pixels,null));
   var result = commands.run(PrepareSafePreviewCommand.class, false, "sourceImage",input,"registrationChannel",1,"maximumPreviewDimension",64).get();
   var preview = (ImagePlus) result.getOutput("registrationPreview");
   if (preview == null || preview.getWidth() > 64 || preview == input || pixels[20] != 1234) throw new AssertionError("preview/source integrity");
   if (!PluginBranding.version().equals("0.1.0-beta.1")) throw new AssertionError("old plugin loaded");
   System.out.println("PASS fresh settings, packaged SciJava command discovery, bounded preview, source integrity and beta version");
  }
 }
}
'''
with tempfile.TemporaryDirectory(prefix="AtlasAlign fresh home ") as temporary:
    home = Path(temporary)
    path = home / "FreshFijiSmoke.java"
    path.write_text(source)
    subprocess.run(["java", "-Djava.awt.headless=true", "-Duser.home=" + str(home), "-Djava.util.prefs.userRoot=" + str(home / "prefs"), "-cp", classpath, str(path)], check=True)
