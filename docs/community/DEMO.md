# Video demo and mock IF example

## Downloads

- [Workflow demo — MP4, 3:10, 1080p, silent](https://github.com/meghamsh738/atlasalign/releases/download/v0.1.0-beta.1/atlasalign-workflow-demo.mp4)
- [Mock IF example — TIFF, 24.1 MiB](https://github.com/meghamsh738/atlasalign/releases/download/v0.1.0-beta.1/mock-if-example.tif)
- [Demo and image SHA-256 checksums](https://github.com/meghamsh738/atlasalign/releases/download/v0.1.0-beta.1/DEMO_SHA256SUMS.txt)
- [Fiji installation instructions](INSTALLATION.md)

The example is Meghamsh Teja Konda's mock immunofluorescence image used in the
single-section recording: 4864 × 5184 pixels, one channel, one plane, 8-bit.
It is a byte-for-byte copy of the provided TIFF, with a download-friendly
filename. Source-file SHA-256:

```text
a5dc5f1be881786799130c8e7e94085033a5f077926dccad44f58199d229ae3d
```

This is the only microscopy dataset supplied with the demo. No whole-slide
batch image, other lab images, or precomputed scientific ROI results are
included. The existing synthetic-oval macro remains available for UI testing.

The image is supplied by the author for trying the workflow. The project's
MIT license covers project code; it does not assign a license to this microscopy
image. Contact the author for other reuse permissions. The example and the
recorded boundaries are not anatomical ground truth or a validated benchmark.

## Follow the video

1. Open the example TIFF and start **Review Atlas Alignment** after Atlas Setup.
2. Select the section type, visible side, plane and guide, then place the atlas.
3. Review the border and interior alignment. Keep changes only after inspecting them.
4. In **Draw ROIs**, create an editable ROI from a guide or draw your own polygon.
   Adjust the cyan points and inspect the boundaries. ROI edits do not move the atlas.
5. Finish the ROIs, select which ones to include, and inspect the export mask.
6. Choose **Export selected ROIs…** and select a folder.

The video removes pauses and indicates accelerated sections. Its single-section
footage uses an earlier review UI. The batch portion is a labeled walkthrough
of verified native screenshots, not a recording of automatic batch completion.
It shows the queue, numbered overview, section selection and individual review.
The current download is beta.3; the video remains a demonstration recorded with the earlier interface.

## Where exports are saved

You select the parent folder in the export dialog. AtlasAlign creates a named
output subfolder and displays its full path when export completes. Repeated
exports can receive a numbered suffix. For an image called `mock-if-example.tif`,
look in the folder you selected for its AtlasAlign manual-ROI export directory.

| Output | Use |
| --- | --- |
| Source-coordinate ROI ZIP | Open the ROI outlines on the original image in Fiji ROI Manager. |
| Per-section source crops | Original pixel crops around the selected regions. |
| Binary masks | White pixels are included in the ROI. |
| Masked images | Source crop pixels inside the ROI; outside pixels are zero. |
| Crop-local ROI ZIPs | Use these outlines on the corresponding exported crop. |
| QC preview PNGs | Inspect the saved outline against the crop. |
| CSV index and JSON manifest | ROI names, bounds, coordinates and export details. |
| Union mask, when selected | Combined mask of the included ROIs. |

The video shows actual export previews from the recorded example. Those exports
are illustrations; generate and review your own results. The source TIFF remains
unchanged. Atlas-region exports use the reviewed alignment/acceptance workflow;
independent finished manual ROIs use the manual-ROI export action shown here.

## Batch mode

See [whole-slide/batch quick start](QUICKSTART.md#whole-slide-or-batch).
Create one section marker per section in ROI Manager, open **Batch / Whole-Slide
Review**, and select a row or numbered marker. **Open selected review** enters
that section; **All sections** returns to the queue. **Open next pending** moves
to unfinished work. Save the project, then reopen the original source and use
**Resume Batch Project** to continue later.

The single-section mock IF download is not the nine-section slide pictured in
the batch walkthrough. Use your own suitable multi-section source or the
synthetic-oval macro to try queue navigation. Each anatomical alignment still
requires individual review.
