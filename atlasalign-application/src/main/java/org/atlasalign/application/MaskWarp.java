package org.atlasalign.application;

import java.util.BitSet;
import java.util.Objects;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.CoordinateSpace2D;

/**
 * Deterministic nearest-neighbor mask warping and overlap measurement.
 */
public final class MaskWarp {

    private MaskWarp() {
    }

    public static BinaryMask warp(
            final BinaryMask source,
            final int destinationWidth,
            final int destinationHeight,
            final AffineTransform2D sourceToDestination) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceToDestination, "sourceToDestination");
        if (sourceToDestination.sourceSpace()
                != CoordinateSpace2D.ATLAS_PLANE_PIXEL
                || sourceToDestination.destinationSpace()
                != CoordinateSpace2D.PREVIEW_PIXEL) {
            throw new IllegalArgumentException(
                    "Mask warp must map atlas-plane pixels to preview pixels");
        }
        if (destinationWidth <= 0 || destinationHeight <= 0) {
            throw new IllegalArgumentException(
                    "Destination dimensions must be positive");
        }
        final AffineTransform2D destinationToSource =
                sourceToDestination.inverse();
        final BitSet result =
                new BitSet(destinationWidth * destinationHeight);
        for (int y = 0; y < destinationHeight; y++) {
            for (int x = 0; x < destinationWidth; x++) {
                final int sourceX = (int) Math.round(
                        destinationToSource.m00() * x
                                + destinationToSource.m01() * y
                                + destinationToSource.m02());
                final int sourceY = (int) Math.round(
                        destinationToSource.m10() * x
                                + destinationToSource.m11() * y
                                + destinationToSource.m12());
                if (source.contains(sourceX, sourceY)) {
                    result.set(y * destinationWidth + x);
                }
            }
        }
        return BinaryMask.fromBitSet(
                destinationWidth, destinationHeight, result);
    }

    public static double dice(
            final BinaryMask first,
            final BinaryMask second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.width() != second.width()
                || first.height() != second.height()) {
            throw new IllegalArgumentException(
                    "Dice masks must have matching dimensions");
        }
        final int total =
                first.foregroundCount() + second.foregroundCount();
        if (total == 0) {
            return 1;
        }
        final BitSet intersection = first.copyBits();
        intersection.and(second.copyBits());
        return 2.0 * intersection.cardinality() / total;
    }
}
