package org.atlasalign.application.c02;

import java.util.Objects;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.core.BinaryMask;

/** Complete, full-section observed support containing no synthetic pixels. */
public final class C02ObservedSupportMask {

    private final BinaryMask completeObserved;

    private C02ObservedSupportMask(final BinaryMask completeObserved) {
        this.completeObserved = BinaryMask.fromBitSet(
                completeObserved.width(),
                completeObserved.height(),
                completeObserved.copyBits());
    }

    public static C02ObservedSupportMask create(
            final BinaryMask tissueMask,
            final BinaryMask syntheticPixels,
            final SectionGeometry geometry) {
        Objects.requireNonNull(tissueMask, "tissueMask");
        Objects.requireNonNull(syntheticPixels, "syntheticPixels");
        Objects.requireNonNull(geometry, "geometry");
        if (geometry != SectionGeometry.FULL) {
            throw new IllegalArgumentException(
                    "C02 is restricted to independently classified FULL sections");
        }
        if (tissueMask.width() != syntheticPixels.width()
                || tissueMask.height() != syntheticPixels.height()) {
            throw new IllegalArgumentException(
                    "C02 tissue and synthetic masks must share dimensions");
        }
        if (tissueMask.isEmpty()) {
            throw new IllegalArgumentException(
                    "C02 observed tissue support must not be empty");
        }
        if (!syntheticPixels.isEmpty()) {
            throw new IllegalArgumentException(
                    "C02 rejects every synthetic pixel");
        }
        return new C02ObservedSupportMask(tissueMask);
    }

    public BinaryMask completeObserved() {
        return completeObserved;
    }
}
