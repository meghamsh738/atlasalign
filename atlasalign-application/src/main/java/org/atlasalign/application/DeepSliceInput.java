package org.atlasalign.application;

import java.util.Arrays;
import java.util.Objects;
import org.atlasalign.core.BinaryMask;

/**
 * Independent preview pixels and synthetic-pixel provenance for DeepSlice.
 */
public final class DeepSliceInput {

    /**
     * Maximum number of inference pixels accepted by every DeepSlice input
     * path. This is deliberately application-owned so all virtual-half
     * expansion paths share one bound.
     */
    public static final int MAX_PIXELS = 8_388_608;

    private final int width;
    private final int height;
    private final float[] pixels;
    private final BinaryMask syntheticPixelMask;

    public DeepSliceInput(
            final int width,
            final int height,
            final float[] pixels,
            final BinaryMask syntheticPixelMask) {
        final long pixelCount = checkedPixelCount(width, height);
        if (pixelCount > MAX_PIXELS) {
            throw new IllegalArgumentException(
                    "DeepSlice input exceeds the shared pixel cap");
        }
        if (Objects.requireNonNull(pixels, "pixels").length != pixelCount) {
            throw new IllegalArgumentException(
                    "DeepSlice input geometry does not match its pixels");
        }
        this.syntheticPixelMask = Objects.requireNonNull(
                syntheticPixelMask, "syntheticPixelMask");
        if (syntheticPixelMask.width() != width
                || syntheticPixelMask.height() != height) {
            throw new IllegalArgumentException(
                    "Synthetic mask geometry does not match the input");
        }
        for (final float pixel : pixels) {
            if (!Float.isFinite(pixel)) {
                throw new IllegalArgumentException(
                        "DeepSlice input contains non-finite pixels");
            }
        }
        this.width = width;
        this.height = height;
        this.pixels = Arrays.copyOf(pixels, pixels.length);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public float[] pixels() {
        return Arrays.copyOf(pixels, pixels.length);
    }

    public BinaryMask syntheticPixelMask() {
        return syntheticPixelMask;
    }

    /**
     * @deprecated Use {@link #syntheticPixelMask()}; the mask includes all
     * synthetic inference pixels, not only mirrored tissue.
     */
    @Deprecated(forRemoval = false)
    public BinaryMask syntheticTissueMask() {
        return syntheticPixelMask();
    }

    private static long checkedPixelCount(
            final int width, final int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "DeepSlice input dimensions must be positive");
        }
        return (long) width * height;
    }
}
