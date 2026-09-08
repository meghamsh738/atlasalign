# Install or remove the beta

Target: Fiji/ImageJ2 with Java 17 or newer. Start with a separate Fiji copy for
beta evaluation. The release bundle is an add-on, not a complete Fiji distribution.
Use this online guide for current compatibility notes; the beta ZIP includes
an earlier snapshot of these instructions.
The desktop app may be called `Fiji.app`; its root contains `jars` and `plugins`.

## Get Fiji and the plugin

1. Download **Latest Fiji** for your operating system and CPU from the
   [official Fiji downloads page](https://imagej.net/software/fiji/downloads).
   Use a download with its bundled Java runtime. AtlasAlign needs Java 17+;
   an older Java 8 Fiji installation is unsuitable. Extract Fiji into a writable
   folder, and keep track of which copy you will use.
2. Download **[atlasalign-lite-0.1.0-beta.1.zip](https://github.com/meghamsh738/atlasalign/releases/download/v0.1.0-beta.1/atlasalign-lite-0.1.0-beta.1.zip)**.
   This is the plugin bundle. GitHub's automatic **Source code** archives are
   for developers and cannot be installed as the plugin.
3. Keep [the demo and mock IF image](DEMO.md) separately from Fiji's installation.
   They are optional practice material, not plugin dependencies.

## Copy the plugin files

1. Close that Fiji copy. Extract `atlasalign-lite-0.1.0-beta.1.zip` elsewhere.
2. Check the archive SHA-256 against the release `SHA256SUMS.txt`. If Python is
   available, also run `python verify_bundle.py` from the extracted bundle.
   Python is optional for file verification and is not needed to run manual alignment.
3. Copy the six `plugins/atlasalign-*-0.1.0-beta.1.jar` files into Fiji's
   `plugins` directory. Remove earlier **AtlasAlign** versions from that directory
   and `jars` first, keeping a backup outside Fiji.
4. Check Fiji's `jars` directory for **`jackson-core`**, **`jackson-annotations`**
   and **`jackson-databind`** before copying any dependencies:
   - **Latest Fiji with all three at 2.19.2:** keep Fiji's copies and skip the
     bundle's `jars` directory. The released plugin was checked with this trio
     for command loading, safe previews and mock-image ROI exports.
   - **All three at 2.18.0 with the bundle's exact filenames and hashes:** keep
     Fiji's copies.
   - **None of those three libraries present:** copy the bundle's three 2.18.0
     JARs into Fiji's `jars` directory.
   - **Missing members, mixed versions or another version:** do not add a
     second version or downgrade Fiji's libraries. Use a separate Fiji copy
     with a supported trio above; report the filenames if you need help.
   Other Jackson modules (such as `jackson-dataformat-xml`) are separate
   libraries; leave them unchanged. Do not replace the entire `jars` directory.
5. Restart Fiji. **Plugins → AtlasAlign Lite → About AtlasAlign Lite** should
   report `0.1.0-beta.1`. Use **Atlas Setup** to configure the atlas.

Fiji supplies ImageJ, SciJava and Bio-Formats; this bundle does not replace them.
The experimental ABBA adapter is not part of the user plugin bundle.

Find the installation directory that actually contains `plugins` and `jars`.
Depending on the Fiji distribution, these folders may be beside `Fiji.app` or
inside it (Finder → right-click `Fiji.app` → **Show Package Contents**).
Copy JAR files into the matching folders; do not replace the entire `plugins`
or `jars` folder, and do not put the bundle ZIP into `plugins`.

If multiple Fiji copies are installed, launch the one you just changed. Verify
its **About AtlasAlign Lite** version before following the demo. The public
release covered by these instructions is **0.1.0-beta.1**.

## Try the mock IF image and export

1. Complete Atlas Setup below, then download [mock-if-example.tif](https://github.com/meghamsh738/atlasalign/releases/download/v0.1.0-beta.1/mock-if-example.tif).
2. In the verified Fiji copy, choose **File → Open** and select the TIFF.
3. Choose **Plugins → AtlasAlign Lite → Review Atlas Alignment**. For the manual
   workflow, leave optional DeepSlice disabled.
4. Follow [the demo guide](DEMO.md). In **Draw ROIs**, finish the desired ROIs,
   include them in export, then choose **Export selected ROIs…**.
5. Choose a destination folder in the folder dialog. The completion message
   gives the exact output subfolder. Export files are not placed into the Fiji
   plugin directory. See [what the exported files contain](DEMO.md#where-exports-are-saved).

## Atlas setup

The default is `.atlasalign/atlas/allen_mouse_25um` in your home directory.
Choose an existing cache to verify it offline, or acknowledge the displayed
Allen terms before downloading. The pinned compressed assets total 37,672,058
bytes (about 35.9 MiB); allow extra space for temporary staging and expanded
in-memory atlas data. Download status is shown in the dialog. Cancel retains
partial transfers for resume. Only a completely verified cache is activated.
A corrupt existing directory is not silently overwritten; select a new directory
or repair your copy deliberately. Keep removable-drive paths available when used.

Settings are saved per user in Java Preferences under `/org/atlasalign/runtime`.
Advanced launch overrides are `-Datlasalign.atlasCache=...`,
`-Datlasalign.deepSliceRuntime=...`, and `-Datlasalign.deepSliceWork=...`.
Saved locations remain selected, including temporarily unavailable drives.
For an earlier developer build with hard-coded locations, select those existing
locations once in setup before reviewing; no atlas re-download is necessary.

## Removal

Close Fiji and remove the six AtlasAlign JARs from `plugins`. Leave shared
Jackson dependencies if another plugin uses them. User atlas caches and saved
batch projects are not deleted. Removing `.atlasalign` is optional and should
only be done after preserving any runtime data you wish to keep.

## Troubleshooting

- **No menu command:** confirm Fiji/ImageJ2, Java 17+, all six JARs, and restart.
- **Class/version error:** remove duplicate AtlasAlign versions; check Java and
  Jackson dependencies. Include the full error text in a bug report.
- **Atlas rejected:** checksum or manifest verification failed. Re-select a valid
  cache or download into a new directory. Never edit hashes to bypass validation.
- **Resume cannot find source:** open the original source image before resuming
  its batch project; source identity must match.
- **Download interrupted:** reopen setup and select the same target to resume.
- **DeepSlice absent:** leave the option disabled and use manual alignment.
