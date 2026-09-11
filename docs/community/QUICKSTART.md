# Quick start

Install the plugin and finish **Atlas Setup** first. For real work open your
original image in Fiji. The review intake lets you choose the structural channel,
optical Z and time point explicitly.
Source pixels and calibration stay unchanged; exports use source coordinates.

For a redistributable UI demonstration, open `demo/synthetic-slide.ijm` in Fiji's
Script Editor and run it as an ImageJ macro. It creates three synthetic ovals
in a new 16-bit image. The macro is original project code under MIT. It is a
navigation fixture, **not anatomical microscopy and not an alignment benchmark**.
Save this new image if you want to demonstrate later project resumption.

## Single section

Choose **Plugins → AtlasAlign Lite → Review Atlas Alignment**. Keep optional
DeepSlice disabled for a manual demonstration. Set the starting plane, review
orientation and tissue crop, then align the atlas using the guided controls.
Use the in-window **How to use** guide. Undo/redo preserve review history.
Choose **Move**, **Rotate** or **Scale** in Setup; proportions start locked.
Expand **Numeric adjustments** for exact source-pixel translations, angles or
percentage scales. Canvas arrow keys move the atlas by one source pixel;
Shift-arrows move it by ten. Page Up/Page Down change the atlas level.
Text fields keep their normal editing keys. Interior offers **Local points**
and **Landmarks**; Draw ROIs exposes guide-copy, polygon, freehand and Fiji ROI
Manager import tools.
Confirm the anatomical alignment before acceptance. Select atlas regions and
review the resulting source-space polygons before export.

The synthetic ovals have no anatomical ground truth: do not accept them as a
scientifically valid atlas match. Use an appropriate real image for anatomical
validation, with the required dataset permissions.

## Whole slide or batch

1. Draw one enclosing polygon per tissue section and add each to ROI Manager.
   Choose descriptive names. Markers identify sections; they are not final
   anatomical ROIs.
2. Choose **Batch / Whole-Slide Review** and inspect the queue.
3. The Whole slide view draws matching queue numbers. Click an outline or a row
   to select it; use **Open** to review. Hover shows the section name. Fit restores
   the full source; zoom inspects details. For different source images only the
   selected image's sections appear.
4. Review sections independently. **All sections** returns to the queue while
   retaining the current review. Check status before advancing.
5. Use **Save project** inside each section to save its complete alignment and
   ROI drafts, then save the batch project. To resume, reopen the original source
   and choose **Resume Batch Project**. Keep sources, batch projects and their
   section projects together. Alignment, ROI, save and export progress are separate.
   Older multidimensional drafts require an explicit C/Z/T choice. Migrated
   projects are written separately; the original project is retained.

Exports may include source-space image crops, binary masks, ROI ZIP and an
integrity manifest. Review changes clear acceptance. Local atlas-contour
refinement is optional and does not alter source pixels or automatically certify
an alignment. No author branding is written into export products.

## Channels, image scope and export

Each review pins registration to one channel and optical Z/time point. The View
bar can show a single channel or a composite, with independent colors, visibility
and contrast. These affect copied previews only. Inspecting another Z/time point
hides anatomical overlays and blocks editing; **Return to review plane** restores
the pinned plane. **New registration** saves the current single-section project
before opening a separate manual review with fresh alignment and ROIs.

Export defaults to all source channels at the pinned Z/time point. Choose channels
explicitly in the export controls; composite visibility does not choose exports.
The confirmation shows ROI names, channels, Z/time, destination and matching
source/outline/mask previews. Images retain native bit depth, channel order and
pixel values. Use the binary mask for quantitative measurements: zero-filled
pixels outside an ROI must not be included in its intensity statistics.

Initial multidimensional support covers verified in-memory OME-TIFF and ImageJ
TIFF hyperstacks. Vendor formats, projections, cross-plane ROI propagation and
large virtual-stack optimization are not part of this development version.

## Save and reopen a review

Use **Save project** to create a `.atlasalign.json` project. After that first save,
committed changes are atomically checkpointed after a one-second pause. Check the
save status before disconnecting a drive. **Save as** writes another project;
**Open review project** is available in the footer and Fiji's AtlasAlign menu.

Projects reference the source image and verified atlas cache rather than embedding
them. Reopening verifies source pixels/metadata and atlas identity, restores the
stored alignment, display settings, workflow position and unfinished ROI drafts,
and requires renewed atlas acceptance. Previous acceptance remains in the audit
history. Initialization and DeepSlice are not rerun. Undo history, pending drag
previews and running computations are not saved. Closing unsaved work offers save,
discard, or return to review.
