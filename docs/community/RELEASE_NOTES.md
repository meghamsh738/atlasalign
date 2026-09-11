# AtlasAlign Lite 0.1.0-beta.3

Community beta prepared 11 September 2026. Install into a separate Fiji copy for evaluation.

## Changes

- Multichannel and composite inspection with registration pinned to a selected C/Z/T.
- Selected-channel exports from the reviewed optical plane, with source-coordinate crops and masks.
- Complete review-project saving, Save as, autosave, exact geometry and ROI-draft restoration, plus batch checkpoints.
- Clearer manual Move/Rotate/Scale controls, numeric adjustments, and tissue-clipping control.

## Validation and limits

The underlying candidate passed 947 Java and 271 Python tests, with 16 environment skips, plus Windows/macOS/Linux CI. Fresh macOS checks covered multichannel intake, display controls, save/reopen, ROI drafts, selected-plane export and unsaved-close behavior. Independent TIFF reading verified exported source pixels, masks, calibration and C/Z/T mappings. Windows/Linux native GUI and a full native batch walkthrough were not performed; batch and migration behavior have automated coverage.

The release changes version metadata and documentation without changing alignment behavior. Release packaging verification is recorded alongside the downloadable artifacts. Atlas alignment still requires anatomical review; these checks do not establish anatomical accuracy. The earlier demo shows the workflow using the earlier UI.

---

# AtlasAlign Lite 0.1.0-beta.1

Community beta, 8 September 2026. Install into a separate Fiji copy for evaluation.

- Clickable whole-slide overview with numbered section markers, selection,
  overlap handling, Fit/zoom/pan and multiple-source filtering.
- Consistent author credit and About command; scientific exports remain unbranded.
- Portable saved runtime locations and guided, verified Allen atlas setup.
- Manual alignment without Python; DeepSlice remains optional.
- Versioned Fiji add-on bundle, hashes, updater input, portable test runner and
  Windows/macOS/Linux CI configuration.

## Validation status

Local macOS arm64 / Java 21.0.7 integration passed 881 Java tests (16 opt-in
or headless-incompatible tests skipped), plus 271 Python tests. Packaged Java
loading, fresh user settings, SciJava menu-command discovery and a bounded
source-preserving preview passed using baseline Fiji libraries without opening
or changing native Fiji. Overview screenshots use synthetic data and offscreen
Swing rendering.
Pre-release CI built the same clean source snapshot and passed tests, packaging
and Java 17 loading checks on Windows, macOS and Linux. Each platform ran 29
public Python tests; Java results were 879 passed / 18 skipped on macOS and Linux,
and 844 passed / 53 skipped on Windows (897 total per platform).

Native macOS startup was verified after installing the six beta JARs. About
reported the beta version and author credit; setup verified an existing Allen
cache offline. A saved nine-section batch resumed; marker clicks synchronized
selection and crop, zoom/Fit worked, and manual review opened successfully.
Source-file and installed-artifact hashes remained correct. This checks the
interface and source handling, not anatomical accuracy. Native GUI behavior on
Windows and Linux is unverified.

CI retains functional assertions but disables workstation timing budgets and
native optional-DeepSlice sandbox certification. Separate local timing and native
sandbox checks passed; CI is not DeepSlice runtime certification.

## Limitations

This is research beta software. Users must check atlas plane, orientation,
laterality, calibration, region boundaries and every scientific export.
Synthetic demo data demonstrates navigation only. Atlas data requires separate
terms acknowledgement and download. Source images must be available to resume a
batch. DeepSlice support is limited to separately verified runtimes; arbitrary
Python environments and all-platform prediction are not claimed.

Windows CI explicitly excludes the POSIX fake DeepSlice process suite and
Unix hard-link assertion, plus the historical test-only half-section diagnostic
harness whose evidence writer requires directory fsync unavailable through Windows
Java NIO. The writer keeps its durability checks unchanged. Symlink tests skip only when the runner cannot create
links. These exclusions do not establish Windows DeepSlice support.
