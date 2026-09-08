# Quick start

Install the plugin and finish **Atlas Setup** first. For real work open your
original image in Fiji and select the structural channel you intend to review.
Source pixels and calibration stay unchanged; exports use source coordinates.

For the recorded microscopy workflow, download the author's
[mock IF example and watch the demo](DEMO.md).

For a synthetic UI demonstration, open `demo/synthetic-slide.ijm` in Fiji's
Script Editor and run it as an ImageJ macro. It creates three synthetic ovals
in a new 16-bit image. The macro is original project code under MIT. It is a
navigation fixture, **not anatomical microscopy and not an alignment benchmark**.
Save this new image if you want to demonstrate later project resumption.

## Single section

Choose **Plugins → AtlasAlign Lite → Review Atlas Alignment**. Keep optional
DeepSlice disabled for a manual demonstration. Set the starting plane, review
orientation and tissue crop, then align the atlas using the guided controls.
Use the in-window **How to use** guide. Undo/redo preserve review history.
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
5. Save a batch project. To resume, reopen the original source and choose
   **Resume Batch Project**. Keep the original image and project together.

Exports may include source-space image crops, binary masks, ROI ZIP and an
integrity manifest. Review changes clear acceptance. Local atlas-contour
refinement is optional and does not alter source pixels or automatically certify
an alignment. No author branding is written into export products.
