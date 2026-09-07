package org.atlasalign.io.imagej;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.atlasalign.application.ReadOnlySourceImage;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.core.CalibrationMetadata;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.core.SourceImageMetadata;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.core.StackPlaneLabel;

/**
 * Read-only ImageJ 1.x source adapter. It never calls a mutating operation on
 * the source ImagePlus, its stack, processors, calibration, or display state.
 */
public final class ImagePlusSourceImage implements ReadOnlySourceImage {

    private final ImagePlus source;

    public ImagePlusSourceImage(final ImagePlus source) {
        this.source = Objects.requireNonNull(source, "source");
        if (source.getStackSize() <= 0) {
            throw new IllegalArgumentException("The source image has no pixel planes");
        }
    }

    @Override
    public SourceImageSnapshot snapshot() {
        return new SourceImageSnapshot(readMetadata(), hashPixels(source.getStack()));
    }

    @Override
    public RegistrationPreview createPreview(
            final int oneBasedChannel,
            final int maximumDimension) {
        if (source.getBitDepth() == 24) {
            throw new IllegalArgumentException(
                    "Packed RGB images are not a defined registration channel; split RGB channels first");
        }
        if (oneBasedChannel < 1 || oneBasedChannel > source.getNChannels()) {
            throw new IllegalArgumentException("Channel is outside the source image");
        }
        final int sourceSlice = source.getZ();
        final int sourceFrame = source.getT();
        final int stackIndex =
                source.getStackIndex(oneBasedChannel, sourceSlice, sourceFrame);
        final ImageProcessor processor = source.getStack().getProcessor(stackIndex);
        final PreviewMapping mapping = PreviewMapping.bounded(
                source.getWidth(), source.getHeight(), maximumDimension);
        final float[] preview = new float[
                mapping.previewWidth() * mapping.previewHeight()];

        int outputIndex = 0;
        for (int previewY = 0; previewY < mapping.previewHeight(); previewY++) {
            for (int previewX = 0; previewX < mapping.previewWidth(); previewX++) {
                final Point2D sourcePoint =
                        mapping.previewToSource(new Point2D(previewX, previewY));
                final int sourceX = clamp(
                        (int) Math.round(sourcePoint.x()), 0, source.getWidth() - 1);
                final int sourceY = clamp(
                        (int) Math.round(sourcePoint.y()), 0, source.getHeight() - 1);
                preview[outputIndex++] = processor.getf(sourceX, sourceY);
            }
        }
        return new RegistrationPreview(
                oneBasedChannel, sourceSlice, sourceFrame, mapping, preview);
    }

    private SourceImageMetadata readMetadata() {
        final Calibration calibration = source.getCalibration();
        return new SourceImageMetadata(
                source.getWidth(),
                source.getHeight(),
                source.getNChannels(),
                source.getNSlices(),
                source.getNFrames(),
                source.getBitDepth(),
                readChannelLabels(),
                readStackPlaneLabels(),
                new CalibrationMetadata(
                        calibration.pixelWidth,
                        calibration.pixelHeight,
                        calibration.pixelDepth,
                        calibration.frameInterval,
                        calibration.getUnit(),
                        calibration.getTimeUnit()));
    }

    private List<String> readChannelLabels() {
        final List<String> labels = new ArrayList<>(source.getNChannels());
        for (int channel = 1; channel <= source.getNChannels(); channel++) {
            final int index = source.getStackIndex(channel, 1, 1);
            final String label = source.getStack().getSliceLabel(index);
            labels.add(label == null || label.isBlank() ? "Channel " + channel : label);
        }
        return labels;
    }

    private List<StackPlaneLabel> readStackPlaneLabels() {
        final List<StackPlaneLabel> labels = new ArrayList<>(source.getStackSize());
        for (int plane = 1; plane <= source.getStackSize(); plane++) {
            labels.add(StackPlaneLabel.fromNullable(
                    source.getStack().getSliceLabel(plane)));
        }
        return labels;
    }

    static String hashPixels(final ImageStack stack) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime has no SHA-256 provider", impossible);
        }

        for (int plane = 1; plane <= stack.getSize(); plane++) {
            final Object pixels = stack.getPixels(plane);
            if (pixels instanceof byte[] bytes) {
                digest.update(bytes);
            } else if (pixels instanceof short[] shorts) {
                for (final short value : shorts) {
                    updateShort(digest, value);
                }
            } else if (pixels instanceof int[] integers) {
                for (final int value : integers) {
                    updateInt(digest, value);
                }
            } else if (pixels instanceof float[] floats) {
                for (final float value : floats) {
                    updateInt(digest, Float.floatToRawIntBits(value));
                }
            } else {
                throw new IllegalArgumentException(
                        "Unsupported ImageJ pixel buffer: " + pixels.getClass().getName());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateShort(final MessageDigest digest, final short value) {
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateInt(final MessageDigest digest, final int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static int clamp(final int value, final int lower, final int upper) {
        return Math.max(lower, Math.min(value, upper));
    }
}
