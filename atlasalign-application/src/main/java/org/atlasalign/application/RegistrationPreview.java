package org.atlasalign.application;

import java.util.Arrays;
import java.util.Objects;
import org.atlasalign.core.PreviewMapping;

/**
 * Newly allocated preview data. Values are raw source intensities sampled from
 * one source channel; display contrast may be changed on this copy.
 */
public final class RegistrationPreview {

    private final int channel;
    private final int sourceSlice;
    private final int sourceFrame;
    private final PreviewMapping mapping;
    private final float[] pixels;

    public RegistrationPreview(
            final int channel,
            final int sourceSlice,
            final int sourceFrame,
            final PreviewMapping mapping,
            final float[] pixels) {
        if (channel <= 0 || sourceSlice <= 0 || sourceFrame <= 0) {
            throw new IllegalArgumentException("ImageJ channel, slice and frame indices are 1-based");
        }
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        if (pixels.length != mapping.previewWidth() * mapping.previewHeight()) {
            throw new IllegalArgumentException("Pixel count does not match preview geometry");
        }
        this.channel = channel;
        this.sourceSlice = sourceSlice;
        this.sourceFrame = sourceFrame;
        this.pixels = Arrays.copyOf(pixels, pixels.length);
    }

    public int channel() {
        return channel;
    }

    public int sourceSlice() {
        return sourceSlice;
    }

    public int sourceFrame() {
        return sourceFrame;
    }

    public PreviewMapping mapping() {
        return mapping;
    }

    public float[] pixels() {
        return Arrays.copyOf(pixels, pixels.length);
    }
}
