# Scientific invariants

These are release-blocking rules, not preferences.

## Source identity

Preview creation must not change:

- any source pixel bit pattern;
- width, height, channels, slices, or frames;
- bit depth;
- channel order or captured channel labels;
- the exact null/empty/text label state of every stack plane;
- pixel width, height, depth, units, or frame interval.

The intake service compares immutable pre/post snapshots and fails closed if
they differ.

## Source handling

- Never call mutating operations on the source `ImagePlus`, `ImageStack`,
  `ImageProcessor`, or `Calibration`.
- Never normalize source pixels.
- Never convert source bit depth.
- Never change source display range, lookup table, active channel, Z, or T.
- Allocate all preview buffers independently.

## Coordinate integrity

- All transforms name their source and destination spaces.
- Pixel coordinates refer to pixel centers.
- Preview dimensions may round independently, so X and Y effective scales are
  stored separately.
- A source → preview → source round trip must recover the starting coordinate
  within numerical tolerance.

## Export rules

- Atlas labels use nearest-neighbour sampling only.
- Default ROI and measurement output is in source space.
- Pixel values are indexed directly from the untouched source.
- Atlas-space/resampled exports require an explicit warning.
- Sessions record versions, checksums, transform history, and user acceptance.

## Independent manual ROIs and current local warp

- Finished manual geometry is export eligibility, not scientific acceptance.
  Atlas acceptance remains separate; manual ROI anatomy is not automatically
  validated or promoted by copying a guide.
- Guide provenance records atlas identity, AP index, and alignment revision.
  Later alignment edits must not silently move an independently edited ROI.
- Current Full/Half/Disjoined safe-local warps retain their validity gates;
  movable suggested-border controls do not imply exact tissue-boundary pinning.
- Export names must preserve distinct ROI identities after sanitization, with
  collision preflight before writing and atomic publication after source checks.
