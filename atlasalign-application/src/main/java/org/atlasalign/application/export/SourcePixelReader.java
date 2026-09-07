package org.atlasalign.application.export;

import java.util.Objects;
import org.atlasalign.core.SourceImageSnapshot;

/**
 * Read-only source-pixel boundary used by source-space export.
 *
 * <p>Every returned block owns a newly allocated primitive array.  No ImageJ
 * processor or stack buffer is exposed to the export pipeline.</p>
 */
public interface SourcePixelReader {

    SourceImageSnapshot snapshot();

    PixelBlock readPlane(
            int oneBasedChannel,
            int oneBasedSlice,
            int oneBasedFrame,
            Bounds bounds);

    record Bounds(int minimumX, int minimumY, int width, int height) {
        public Bounds {
            if (minimumX < 0 || minimumY < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                        "Source pixel bounds must be positive and non-negative");
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

    sealed interface PixelBlock
            permits ByteBlock, UnsignedShortBlock, FloatBlock {

        int width();

        int height();

        int bitDepth();

        int pixelCount();
    }

    record ByteBlock(int width, int height, byte[] pixels)
            implements PixelBlock {
        public ByteBlock {
            pixels = copyAndCheck(width, height, pixels);
        }

        @Override
        public byte[] pixels() {
            return pixels.clone();
        }

        @Override
        public int bitDepth() {
            return 8;
        }

        @Override
        public int pixelCount() {
            return pixels.length;
        }
    }

    record UnsignedShortBlock(int width, int height, short[] pixels)
            implements PixelBlock {
        public UnsignedShortBlock {
            pixels = copyAndCheck(width, height, pixels);
        }

        @Override
        public short[] pixels() {
            return pixels.clone();
        }

        @Override
        public int bitDepth() {
            return 16;
        }

        @Override
        public int pixelCount() {
            return pixels.length;
        }
    }

    record FloatBlock(int width, int height, float[] pixels)
            implements PixelBlock {
        public FloatBlock {
            pixels = copyAndCheck(width, height, pixels);
        }

        @Override
        public float[] pixels() {
            return pixels.clone();
        }

        @Override
        public int bitDepth() {
            return 32;
        }

        @Override
        public int pixelCount() {
            return pixels.length;
        }
    }

    private static byte[] copyAndCheck(
            final int width,
            final int height,
            final byte[] pixels) {
        requireDimensions(width, height, Objects.requireNonNull(
                pixels, "pixels").length);
        return pixels.clone();
    }

    private static short[] copyAndCheck(
            final int width,
            final int height,
            final short[] pixels) {
        requireDimensions(width, height, Objects.requireNonNull(
                pixels, "pixels").length);
        return pixels.clone();
    }

    private static float[] copyAndCheck(
            final int width,
            final int height,
            final float[] pixels) {
        requireDimensions(width, height, Objects.requireNonNull(
                pixels, "pixels").length);
        return pixels.clone();
    }

    private static void requireDimensions(
            final int width,
            final int height,
            final int length) {
        if (width <= 0 || height <= 0
                || Math.multiplyExact(width, height) != length) {
            throw new IllegalArgumentException(
                    "Pixel block length does not match its dimensions");
        }
    }
}
