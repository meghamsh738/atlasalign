package org.atlasalign.application;

import java.util.Arrays;
import java.util.Objects;
import org.atlasalign.core.BinaryMask;

/**
 * Temporary mirrored preview with explicit observed/synthetic provenance.
 */
public final class VirtualHalfPreview {

    private final int width;
    private final int height;
    private final float[] pixels;
    private final BinaryMask observedTissueMask;
    private final BinaryMask syntheticTissueMask;
    private final SectionGeometry observedGeometry;

    VirtualHalfPreview(
            final int width,
            final int height,
            final float[] pixels,
            final BinaryMask observedTissueMask,
            final BinaryMask syntheticTissueMask,
            final SectionGeometry observedGeometry) {
        this.width = width;
        this.height = height;
        this.pixels = Arrays.copyOf(pixels, pixels.length);
        this.observedTissueMask = Objects.requireNonNull(
                observedTissueMask, "observedTissueMask");
        this.syntheticTissueMask = Objects.requireNonNull(
                syntheticTissueMask, "syntheticTissueMask");
        this.observedGeometry = Objects.requireNonNull(
                observedGeometry, "observedGeometry");
    }

    public DeepSliceInput deepSliceInput() {
        return new DeepSliceInput(
                width, height, pixels, syntheticTissueMask);
    }

    public BinaryMask refinementMask() {
        return observedTissueMask;
    }

    public BinaryMask exportableTissueMask() {
        return observedTissueMask;
    }

    public BinaryMask syntheticTissueMask() {
        return syntheticTissueMask;
    }

    public SectionGeometry observedGeometry() {
        return observedGeometry;
    }
}
