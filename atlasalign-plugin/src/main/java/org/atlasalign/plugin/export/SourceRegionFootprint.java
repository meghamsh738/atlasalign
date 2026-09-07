package org.atlasalign.plugin.export;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import org.atlasalign.application.export.SourcePixelReader;

/** Exact source-pixel footprint and its tight rectangular crop. */
public final class SourceRegionFootprint {

    private final List<ExportRegionSelection> selections;
    private final int sourceWidth;
    private final int sourceHeight;
    private final SourcePixelReader.Bounds bounds;
    private final BitSet cropMask;

    public SourceRegionFootprint(
            final List<ExportRegionSelection> selections,
            final int sourceWidth,
            final int sourceHeight,
            final SourcePixelReader.Bounds bounds,
            final BitSet cropMask) {
        this.selections = List.copyOf(Objects.requireNonNull(
                selections, "selections"));
        if (this.selections.isEmpty()) {
            throw new IllegalArgumentException(
                    "A source footprint requires at least one region");
        }
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException(
                    "Source footprint dimensions must be positive");
        }
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        if (bounds.maximumXExclusive() > sourceWidth
                || bounds.maximumYExclusive() > sourceHeight) {
            throw new IllegalArgumentException(
                    "Source footprint bounds exceed the source image");
        }
        this.cropMask = (BitSet) Objects.requireNonNull(
                cropMask, "cropMask").clone();
        if (this.cropMask.isEmpty()
                || this.cropMask.length() > bounds.pixelCount()) {
            throw new IllegalArgumentException(
                    "Source footprint mask is empty or outside its crop");
        }
    }

    public List<ExportRegionSelection> selections() {
        return selections;
    }

    public int sourceWidth() {
        return sourceWidth;
    }

    public int sourceHeight() {
        return sourceHeight;
    }

    public SourcePixelReader.Bounds bounds() {
        return bounds;
    }

    public BitSet cropMask() {
        return (BitSet) cropMask.clone();
    }

    public byte[] maskBytes() {
        final byte[] bytes = new byte[bounds.pixelCount()];
        for (int bit = cropMask.nextSetBit(0);
                bit >= 0;
                bit = cropMask.nextSetBit(bit + 1)) {
            bytes[bit] = (byte) 0xff;
        }
        return bytes;
    }

    /** Binary footprint at the untouched source dimensions for bounded uses. */
    byte[] fullSourceMaskBytes() {
        return fullSourceMaskRows(0, sourceHeight);
    }

    /**
     * Binary footprint for a contiguous set of full-width source rows.
     * Exporters use this bounded form so a large full-source mask does not
     * require one source-sized Java array.
     */
    byte[] fullSourceMaskRows(
            final int minimumSourceY,
            final int rowCount) {
        if (minimumSourceY < 0 || rowCount <= 0
                || Math.addExact(minimumSourceY, rowCount) > sourceHeight) {
            throw new IllegalArgumentException(
                    "Full-source mask row range is outside the source image");
        }
        final byte[] bytes = new byte[Math.multiplyExact(
                sourceWidth, rowCount)];
        for (int bit = cropMask.nextSetBit(0);
                bit >= 0;
                bit = cropMask.nextSetBit(bit + 1)) {
            final int localY = bit / bounds.width();
            final int localX = bit - localY * bounds.width();
            final int sourceX = bounds.minimumX() + localX;
            final int sourceY = bounds.minimumY() + localY;
            if (sourceY >= minimumSourceY
                    && sourceY < minimumSourceY + rowCount) {
                bytes[(sourceY - minimumSourceY) * sourceWidth + sourceX]
                        = (byte) 0xff;
            }
        }
        return bytes;
    }

    public int pixelCount() {
        return cropMask.cardinality();
    }

    public boolean containsSourcePixel(final int x, final int y) {
        if (x < bounds.minimumX() || x >= bounds.maximumXExclusive()
                || y < bounds.minimumY()
                || y >= bounds.maximumYExclusive()) {
            return false;
        }
        return cropMask.get((y - bounds.minimumY()) * bounds.width()
                + x - bounds.minimumX());
    }
}
