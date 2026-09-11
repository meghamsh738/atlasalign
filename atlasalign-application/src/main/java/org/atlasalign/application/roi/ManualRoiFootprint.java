package org.atlasalign.application.roi;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * Exact level-0 source-pixel footprint for one reviewer ROI.
 *
 * <p>A source pixel is included when its pixel centre lies inside at least
 * one completed ADD polygon and inside no completed SUBTRACT polygon. The
 * result is clipped only to the source image bounds.</p>
 */
public final class ManualRoiFootprint {

    public record Bounds(int minimumX, int minimumY, int width, int height) {
        public Bounds {
            if (minimumX < 0 || minimumY < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                        "Manual ROI bounds must be positive and non-negative");
            }
            Math.addExact(minimumX, width);
            Math.addExact(minimumY, height);
        }

        public int maximumXExclusive() {
            return minimumX + width;
        }

        public int maximumYExclusive() {
            return minimumY + height;
        }

        public int pixelCount() {
            return Math.multiplyExact(width, height);
        }
    }

    private final int sourceWidth;
    private final int sourceHeight;
    private final Bounds bounds;
    private final BitSet cropMask;

    private ManualRoiFootprint(
            final int sourceWidth,
            final int sourceHeight,
            final Bounds bounds,
            final BitSet cropMask) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.bounds = bounds;
        this.cropMask = (BitSet) cropMask.clone();
    }

    public static ManualRoiFootprint rasterize(
            final ReviewerRoi roi,
            final int sourceWidth,
            final int sourceHeight) {
        final ReviewerRoi checked = Objects.requireNonNull(roi, "roi");
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException(
                    "Source dimensions must be positive");
        }
        if (!checked.finished()) {
            throw new IllegalArgumentException(
                    "Finish every polygon part before exporting "
                            + checked.name());
        }
        final BitSet included = new BitSet(
                Math.multiplyExact(sourceWidth, sourceHeight));
        for (final ReviewerRoiPart part : checked.parts()) {
            if (part.operation() == RoiPartOperation.ADD) {
                rasterizePart(part.vertices(), sourceWidth, sourceHeight,
                        included, true);
            }
        }
        for (final ReviewerRoiPart part : checked.parts()) {
            if (part.operation() == RoiPartOperation.SUBTRACT) {
                rasterizePart(part.vertices(), sourceWidth, sourceHeight,
                        included, false);
            }
        }
        if (included.isEmpty()) {
            throw new IllegalArgumentException(
                    "The finished ADD-minus-SUBTRACT ROI is empty: "
                            + checked.name());
        }
        return tight(sourceWidth, sourceHeight, included);
    }

    private static void rasterizePart(
            final List<ReviewerRoiVertex> vertices,
            final int sourceWidth,
            final int sourceHeight,
            final BitSet target,
            final boolean add) {
        double minimumX = Double.POSITIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        for (final ReviewerRoiVertex vertex : vertices) {
            minimumX = Math.min(minimumX, vertex.sourcePoint().x());
            minimumY = Math.min(minimumY, vertex.sourcePoint().y());
            maximumX = Math.max(maximumX, vertex.sourcePoint().x());
            maximumY = Math.max(maximumY, vertex.sourcePoint().y());
        }
        final int firstX = Math.max(0, (int) Math.ceil(minimumX));
        final int firstY = Math.max(0, (int) Math.ceil(minimumY));
        final int lastX = Math.min(sourceWidth - 1,
                (int) Math.floor(maximumX));
        final int lastY = Math.min(sourceHeight - 1,
                (int) Math.floor(maximumY));
        for (int y = firstY; y <= lastY; y++) {
            for (int x = firstX; x <= lastX; x++) {
                if (!contains(vertices, x, y)) {
                    continue;
                }
                final int bit = y * sourceWidth + x;
                if (add) {
                    target.set(bit);
                } else {
                    target.clear(bit);
                }
            }
        }
    }

    /** The same ADD-minus-SUBTRACT rule for bounded display sampling without allocating a full-image mask. */
    public static boolean containsSourcePixel(final ReviewerRoi roi, final int x, final int y,
            final int sourceWidth, final int sourceHeight) {
        if (!roi.finished()) throw new IllegalArgumentException("Finish every polygon part before previewing an export");
        if (x < 0 || y < 0 || x >= sourceWidth || y >= sourceHeight) return false;
        boolean included = false;
        for (final var part : roi.parts()) {
            if (part.operation() == RoiPartOperation.ADD && contains(part.vertices(), x, y)) { included = true; break; }
        }
        if (!included) return false;
        for (final var part : roi.parts()) {
            if (part.operation() == RoiPartOperation.SUBTRACT && contains(part.vertices(), x, y)) return false;
        }
        return true;
    }

    /** Even-odd containment with polygon edges included deterministically. */
    static boolean contains(
            final List<ReviewerRoiVertex> vertices,
            final double x,
            final double y) {
        boolean inside = false;
        for (int current = 0, previous = vertices.size() - 1;
                current < vertices.size(); previous = current++) {
            final var a = vertices.get(previous).sourcePoint();
            final var b = vertices.get(current).sourcePoint();
            if (onSegment(a.x(), a.y(), b.x(), b.y(), x, y)) {
                return true;
            }
            final boolean crosses = (a.y() > y) != (b.y() > y)
                    && x < (b.x() - a.x()) * (y - a.y())
                            / (b.y() - a.y()) + a.x();
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean onSegment(
            final double ax,
            final double ay,
            final double bx,
            final double by,
            final double x,
            final double y) {
        final double cross = (x - ax) * (by - ay)
                - (y - ay) * (bx - ax);
        final double tolerance = 1e-9 * Math.max(1.0,
                Math.hypot(bx - ax, by - ay));
        return Math.abs(cross) <= tolerance
                && x >= Math.min(ax, bx) - tolerance
                && x <= Math.max(ax, bx) + tolerance
                && y >= Math.min(ay, by) - tolerance
                && y <= Math.max(ay, by) + tolerance;
    }

    private static ManualRoiFootprint tight(
            final int sourceWidth,
            final int sourceHeight,
            final BitSet full) {
        int minimumX = sourceWidth;
        int minimumY = sourceHeight;
        int maximumX = -1;
        int maximumY = -1;
        for (int bit = full.nextSetBit(0);
                bit >= 0; bit = full.nextSetBit(bit + 1)) {
            final int x = bit % sourceWidth;
            final int y = bit / sourceWidth;
            minimumX = Math.min(minimumX, x);
            minimumY = Math.min(minimumY, y);
            maximumX = Math.max(maximumX, x);
            maximumY = Math.max(maximumY, y);
        }
        final Bounds bounds = new Bounds(minimumX, minimumY,
                maximumX - minimumX + 1, maximumY - minimumY + 1);
        final BitSet crop = new BitSet(bounds.pixelCount());
        for (int bit = full.nextSetBit(0);
                bit >= 0; bit = full.nextSetBit(bit + 1)) {
            final int x = bit % sourceWidth;
            final int y = bit / sourceWidth;
            crop.set((y - minimumY) * bounds.width() + x - minimumX);
        }
        return new ManualRoiFootprint(
                sourceWidth, sourceHeight, bounds, crop);
    }

    public int sourceWidth() {
        return sourceWidth;
    }

    public int sourceHeight() {
        return sourceHeight;
    }

    public Bounds bounds() {
        return bounds;
    }

    public BitSet cropMask() {
        return (BitSet) cropMask.clone();
    }

    public byte[] maskBytes() {
        final byte[] result = new byte[bounds.pixelCount()];
        for (int bit = cropMask.nextSetBit(0);
                bit >= 0; bit = cropMask.nextSetBit(bit + 1)) {
            result[bit] = (byte) 0xff;
        }
        return result;
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
