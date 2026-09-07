package org.atlasalign.core;

import java.util.BitSet;
import java.util.Objects;

/**
 * Immutable two-dimensional binary mask in pixel-center coordinates.
 */
public final class BinaryMask {

    private final int width;
    private final int height;
    private final BitSet pixels;

    private BinaryMask(
            final int width,
            final int height,
            final BitSet pixels) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Mask dimensions must be positive");
        }
        if (pixels.length() > Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException(
                    "Mask contains pixels outside its dimensions");
        }
        this.width = width;
        this.height = height;
        this.pixels = (BitSet) Objects.requireNonNull(
                pixels, "pixels").clone();
    }

    public static BinaryMask fromBooleans(
            final int width,
            final int height,
            final boolean[] values) {
        Objects.requireNonNull(values, "values");
        if (values.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException(
                    "Mask value count does not match its dimensions");
        }
        final BitSet bits = new BitSet(values.length);
        for (int index = 0; index < values.length; index++) {
            if (values[index]) {
                bits.set(index);
            }
        }
        return new BinaryMask(width, height, bits);
    }

    public static BinaryMask fromBitSet(
            final int width,
            final int height,
            final BitSet values) {
        return new BinaryMask(width, height, values);
    }

    public static BinaryMask empty(final int width, final int height) {
        return new BinaryMask(width, height, new BitSet());
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean contains(final int x, final int y) {
        return x >= 0 && x < width && y >= 0 && y < height
                && pixels.get(y * width + x);
    }

    public int foregroundCount() {
        return pixels.cardinality();
    }

    public boolean isEmpty() {
        return pixels.isEmpty();
    }

    public BitSet copyBits() {
        return (BitSet) pixels.clone();
    }

    public MaskBounds bounds() {
        if (pixels.isEmpty()) {
            throw new IllegalStateException("Empty mask has no bounds");
        }
        int minimumX = width;
        int minimumY = height;
        int maximumX = -1;
        int maximumY = -1;
        for (int index = pixels.nextSetBit(0);
                index >= 0;
                index = pixels.nextSetBit(index + 1)) {
            final int x = index % width;
            final int y = index / width;
            minimumX = Math.min(minimumX, x);
            minimumY = Math.min(minimumY, y);
            maximumX = Math.max(maximumX, x);
            maximumY = Math.max(maximumY, y);
        }
        return new MaskBounds(
                minimumX, minimumY, maximumX, maximumY);
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BinaryMask mask)) {
            return false;
        }
        return width == mask.width
                && height == mask.height
                && pixels.equals(mask.pixels);
    }

    @Override
    public int hashCode() {
        return Objects.hash(width, height, pixels);
    }
}
