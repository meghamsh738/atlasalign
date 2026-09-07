package org.atlasalign.io.imagej;

import ij.ImagePlus;
import java.util.Objects;
import org.atlasalign.application.export.SourcePixelReader;
import org.atlasalign.core.SourceImageSnapshot;

/** Direct-indexing, read-only ImageJ adapter for source-space crop export. */
public final class ImagePlusSourcePixelReader implements SourcePixelReader {

    private final ImagePlus source;

    public ImagePlusSourcePixelReader(final ImagePlus source) {
        this.source = Objects.requireNonNull(source, "source");
        if (source.getBitDepth() != 8
                && source.getBitDepth() != 16
                && source.getBitDepth() != 32) {
            throw new IllegalArgumentException(
                    "Source-space export supports 8-bit, unsigned 16-bit, and 32-bit float images");
        }
    }

    @Override
    public SourceImageSnapshot snapshot() {
        return new ImagePlusSourceImage(source).snapshot();
    }

    @Override
    public PixelBlock readPlane(
            final int oneBasedChannel,
            final int oneBasedSlice,
            final int oneBasedFrame,
            final Bounds bounds) {
        final Bounds checked = Objects.requireNonNull(bounds, "bounds");
        requireIndex(oneBasedChannel, source.getNChannels(), "channel");
        requireIndex(oneBasedSlice, source.getNSlices(), "slice");
        requireIndex(oneBasedFrame, source.getNFrames(), "frame");
        if (checked.maximumXExclusive() > source.getWidth()
                || checked.maximumYExclusive() > source.getHeight()) {
            throw new IllegalArgumentException(
                    "Requested source crop is outside the image");
        }
        final int stackIndex = source.getStackIndex(
                oneBasedChannel, oneBasedSlice, oneBasedFrame);
        final Object sourcePixels = source.getStack().getPixels(stackIndex);
        if (sourcePixels instanceof byte[] bytes) {
            return new ByteBlock(checked.width(), checked.height(),
                    copyRows(bytes, source.getWidth(), checked));
        }
        if (sourcePixels instanceof short[] shorts) {
            return new UnsignedShortBlock(
                    checked.width(), checked.height(),
                    copyRows(shorts, source.getWidth(), checked));
        }
        if (sourcePixels instanceof float[] floats) {
            return new FloatBlock(checked.width(), checked.height(),
                    copyRows(floats, source.getWidth(), checked));
        }
        throw new IllegalArgumentException(
                "Unsupported ImageJ source buffer: "
                        + sourcePixels.getClass().getName());
    }

    private static byte[] copyRows(
            final byte[] source,
            final int sourceWidth,
            final Bounds bounds) {
        final byte[] result = new byte[bounds.pixelCount()];
        for (int y = 0; y < bounds.height(); y++) {
            System.arraycopy(source,
                    (bounds.minimumY() + y) * sourceWidth + bounds.minimumX(),
                    result, y * bounds.width(), bounds.width());
        }
        return result;
    }

    private static short[] copyRows(
            final short[] source,
            final int sourceWidth,
            final Bounds bounds) {
        final short[] result = new short[bounds.pixelCount()];
        for (int y = 0; y < bounds.height(); y++) {
            System.arraycopy(source,
                    (bounds.minimumY() + y) * sourceWidth + bounds.minimumX(),
                    result, y * bounds.width(), bounds.width());
        }
        return result;
    }

    private static float[] copyRows(
            final float[] source,
            final int sourceWidth,
            final Bounds bounds) {
        final float[] result = new float[bounds.pixelCount()];
        for (int y = 0; y < bounds.height(); y++) {
            System.arraycopy(source,
                    (bounds.minimumY() + y) * sourceWidth + bounds.minimumX(),
                    result, y * bounds.width(), bounds.width());
        }
        return result;
    }

    private static void requireIndex(
            final int oneBased,
            final int maximum,
            final String name) {
        if (oneBased < 1 || oneBased > maximum) {
            throw new IllegalArgumentException(
                    "Source " + name + " is outside the image");
        }
    }
}
