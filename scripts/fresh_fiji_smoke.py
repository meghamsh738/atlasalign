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
 public static final class IsolatedPreferences implements java.util.prefs.PreferencesFactory {
  private final java.util.prefs.Preferences root = new MemoryNode(null, "");
  public java.util.prefs.Preferences userRoot() { return root; }
  public java.util.prefs.Preferences systemRoot() { return root; }
 }
 private static final class MemoryNode extends java.util.prefs.AbstractPreferences {
  private final java.util.Map<String,String> values = new java.util.HashMap<>();
  MemoryNode(java.util.prefs.AbstractPreferences parent, String name) { super(parent,name); }
  protected void putSpi(String key,String value) { values.put(key,value); }
  protected String getSpi(String key) { return values.get(key); }
  protected void removeSpi(String key) { values.remove(key); }
  protected void removeNodeSpi() { values.clear(); }
  protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
  protected String[] childrenNamesSpi() { return new String[0]; }
  protected java.util.prefs.AbstractPreferences childSpi(String name) { return new MemoryNode(this,name); }
  protected void syncSpi() { }
  protected void flushSpi() { }
 }
 public static void main(String[] args) throws Exception {
  var settings = new RuntimeSettings().paths();
  if (!settings.atlasCache().startsWith(Path.of(System.getProperty("user.home")))) throw new AssertionError("old settings leaked");
  try (var context = new Context(CommandService.class)) {
   var commands = context.service(CommandService.class);
   for (var type : new Class[]{PrepareSafePreviewCommand.class, SingleSectionReviewCommand.class, BatchReviewCommand.class, AtlasSetupCommand.class, OpenReviewProjectCommand.class, AboutAtlasAlignCommand.class}) {
    if (commands.getCommand(type) == null) throw new AssertionError("missing command " + type);
   }
   var pixels = new short[128*96]; pixels[20] = 1234;
   var input = new ImagePlus("Synthetic fresh smoke", new ShortProcessor(128,96,pixels,null));
   var result = commands.run(PrepareSafePreviewCommand.class, false, "sourceImage",input,"registrationChannel",1,"maximumPreviewDimension",64).get();
   var preview = (ImagePlus) result.getOutput("registrationPreview");
   if (preview == null || preview.getWidth() > 64 || preview == input || pixels[20] != 1234) throw new AssertionError("preview/source integrity");
   if (!PluginBranding.version().equals("0.1.0-beta.3")) throw new AssertionError("old plugin loaded");
   System.out.println("PASS fresh settings, packaged SciJava command discovery, bounded preview, source integrity and beta version");
  }
 }
}
'''
with tempfile.TemporaryDirectory(prefix="AtlasAlign fresh home ") as temporary:
    home = Path(temporary)
    path = home / "FreshFijiSmoke.java"
    path.write_text(source)
    # A process-local factory isolates native macOS preferences as well as files.
    subprocess.run(["javac", "-proc:none", "-cp", classpath, "-d", str(home), str(path)], check=True)
    subprocess.run(["java", "-Djava.util.prefs.PreferencesFactory=FreshFijiSmoke$IsolatedPreferences",
                    "-Djava.awt.headless=true", "-Duser.home=" + str(home),
                    "-cp", str(home) + os.pathsep + classpath, "FreshFijiSmoke"], check=True)
