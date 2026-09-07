# Transform conventions

## Spaces

- **Source pixel space**: full-resolution ImageJ pixel coordinates.
- **Preview pixel space**: bounded registration-preview pixel coordinates.
- **Atlas plane pixel space**: coordinates in the selected atlas plane.
- Atlas voxel and physical coordinates name their axes and units explicitly.

Baseline registration transforms map:

```text
Allen atlas plane pixel → registration preview pixel
```

Similarity and affine objects store this direction explicitly and reject
composition across mismatched spaces. Their inverse objects swap the named
spaces. These proposals never imply user acceptance.

## Allen coronal levels

The pinned Allen 25 µm reference volume uses axis order
anterior-to-posterior, superior-to-inferior, left-to-right. A manual coronal
level is therefore a zero-based axis-0 index in `0..527`. Multiplying the index
by 25 µm gives distance from the anterior volume origin only; it must not be
reported as a bregma coordinate.

For an un-reoriented coronal slice, atlas-plane X is volume axis 2
(left-to-right), atlas-plane Y is volume axis 1 (superior-to-inferior), and
atlas-plane `(0,0)` is the center of the superior-left voxel in that plane.

Geometry labels `IMAGE_LEFT_HALF` and `IMAGE_RIGHT_HALF` refer to the displayed
image. They do not become anatomical left/right without separate orientation
confirmation.

## Pixel centers

The center of the top-left pixel is `(0, 0)`. For source width `Ws` and preview
width `Wp`:

```text
scaleX = Wp / Ws
previewX = (sourceX + 0.5) × scaleX - 0.5
sourceX  = (previewX + 0.5) / scaleX - 0.5
```

Y uses `Hp / Hs` independently. This convention remains reversible when rounded
preview width and height produce slightly different effective factors.

Preview sampling selects the nearest source pixel center. This affects only the
temporary preview; no source pixels are resampled or overwritten.
