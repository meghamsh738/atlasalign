package org.atlasalign.application.c01;

import java.util.Objects;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.core.BinaryMask;

/**
 * Complete, non-synthetic observed support and its fixed internal erosion.
 */
public final class C01ObservedSupportMask {

    public static final int ALLEN_GRID_MICROMETERS = 25;
    public static final int OUTER_EROSION_MICROMETERS = 200;
    public static final int OUTER_EROSION_PIXELS =
            OUTER_EROSION_MICROMETERS / ALLEN_GRID_MICROMETERS;

    private final BinaryMask completeObserved;
    private final BinaryMask internalObserved;
    private final int erosionRadiusPixels;

    private C01ObservedSupportMask(
            final BinaryMask completeObserved,
            final BinaryMask internalObserved,
            final int erosionRadiusPixels) {
        this.completeObserved = completeObserved;
        this.internalObserved = internalObserved;
        this.erosionRadiusPixels = erosionRadiusPixels;
    }

    public static C01ObservedSupportMask create(
            final BinaryMask tissueMask,
            final BinaryMask syntheticPixels,
            final SectionGeometry geometry) {
        return create(
                tissueMask,
                syntheticPixels,
                geometry,
                OUTER_EROSION_PIXELS);
    }

    static C01ObservedSupportMask create(
            final BinaryMask tissueMask,
            final BinaryMask syntheticPixels,
            final SectionGeometry geometry,
            final int erosionRadiusPixels) {
        Objects.requireNonNull(tissueMask, "tissueMask");
        Objects.requireNonNull(syntheticPixels, "syntheticPixels");
        Objects.requireNonNull(geometry, "geometry");
        if (geometry != SectionGeometry.FULL) {
            throw new IllegalArgumentException(
                    "C01 is restricted to independently classified FULL sections");
        }
        if (tissueMask.width() != syntheticPixels.width()
                || tissueMask.height() != syntheticPixels.height()) {
            throw new IllegalArgumentException(
                    "C01 tissue and synthetic masks must share dimensions");
        }
        if (tissueMask.isEmpty()) {
            throw new IllegalArgumentException(
                    "C01 tissue support must not be empty");
        }
        if (!syntheticPixels.isEmpty()) {
            throw new IllegalArgumentException(
                    "C01 rejects every synthetic pixel");
        }
        if (erosionRadiusPixels < 0) {
            throw new IllegalArgumentException(
                    "C01 erosion radius must be non-negative");
        }
        final BinaryMask internal = erodeDisk(
                tissueMask, erosionRadiusPixels);
        if (internal.isEmpty()) {
            throw new IllegalArgumentException(
                    "C01 200 micrometer erosion removed all observed support");
        }
        return new C01ObservedSupportMask(
                tissueMask, internal, erosionRadiusPixels);
    }

    public BinaryMask completeObserved() {
        return completeObserved;
    }

    public BinaryMask internalObserved() {
        return internalObserved;
    }

    public int erosionRadiusPixels() {
        return erosionRadiusPixels;
    }

    /**
     * Returns the preregistered complete-mask diagnostic for the exact same
     * already-validated full, non-synthetic observation.
     *
     * <p>This does not create a new input variant. It only removes the frozen
     * 200 micrometer outer-band exclusion so the evaluator can measure whether
     * that exclusion changes the selected AP plane. The complete observed mask
     * is immutable and is reused without changing source pixels or feature
     * grids.</p>
     */
    public C01ObservedSupportMask completeMaskDiagnostic() {
        return new C01ObservedSupportMask(
                completeObserved,
                erodeDisk(completeObserved, 0),
                0);
    }

    private static BinaryMask erodeDisk(
            final BinaryMask source,
            final int radius) {
        if (radius == 0) {
            return BinaryMask.fromBitSet(
                    source.width(), source.height(), source.copyBits());
        }
        final int width = source.width();
        final int height = source.height();
        final boolean[] values = new boolean[
                Math.multiplyExact(width, height)];
        final int radiusSquared = radius * radius;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!source.contains(x, y)) {
                    continue;
                }
                boolean retained = true;
                for (int dy = -radius; dy <= radius && retained; dy++) {
                    final int maximumDx = (int) Math.floor(Math.sqrt(
                            radiusSquared - dy * dy));
                    for (int dx = -maximumDx;
                            dx <= maximumDx;
                            dx++) {
                        if (!source.contains(x + dx, y + dy)) {
                            retained = false;
                            break;
                        }
                    }
                }
                values[y * width + x] = retained;
            }
        }
        return BinaryMask.fromBooleans(width, height, values);
    }
}
