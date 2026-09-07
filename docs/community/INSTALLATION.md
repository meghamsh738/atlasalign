# Install or remove the beta

Target: Fiji/ImageJ2 with Java 17 or newer. Start with a separate Fiji copy for
beta evaluation. The release bundle is an add-on, not a complete Fiji distribution.
The desktop app may be called `Fiji.app`; its root contains `jars` and `plugins`.

1. Close that Fiji copy. Extract `atlasalign-lite-0.1.0-beta.1.zip` elsewhere.
2. Check the archive SHA-256 against the release checksums, then verify its files:
   `python verify_bundle.py` from the extracted bundle.
3. Copy the six `plugins/atlasalign-*-0.1.0-beta.1.jar` files into Fiji's
   `plugins` directory. Remove earlier **AtlasAlign** versions from that directory
   and `jars` first, keeping a backup outside Fiji.
4. The bundle's `jars` directory contains Jackson core, annotations and databind
   2.18.0. If none are present, copy all three JARs into Fiji's `jars` directory.
   If Fiji already has these exact filenames and hashes, keep its copies.
   If another Jackson version is present, do not create duplicate versions: use
   a separate test Fiji and resolve the dependency through Fiji's Updater before
   proceeding. Other installed plugins may rely on their current Jackson version.
5. Restart Fiji. **Plugins → AtlasAlign Lite → About AtlasAlign Lite** should
   report `0.1.0-beta.1`. Use **Atlas Setup** to configure the atlas.

Fiji supplies ImageJ, SciJava and Bio-Formats; this bundle does not replace them.
The experimental ABBA adapter is not part of the user plugin bundle.

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
