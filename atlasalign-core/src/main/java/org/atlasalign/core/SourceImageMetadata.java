package org.atlasalign.core;

import java.util.List;
import java.util.Objects;

public record SourceImageMetadata(
        int width,
        int height,
        int channels,
        int slices,
        int frames,
        int bitDepth,
        List<String> channelLabels,
        List<StackPlaneLabel> stackPlaneLabels,
        CalibrationMetadata calibration) {

    public SourceImageMetadata {
        requirePositive(width, "width");
        requirePositive(height, "height");
        requirePositive(channels, "channels");
        requirePositive(slices, "slices");
        requirePositive(frames, "frames");
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            throw new IllegalArgumentException("Unsupported ImageJ bit depth: " + bitDepth);
        }
        channelLabels = List.copyOf(channelLabels);
        if (channelLabels.size() != channels) {
            throw new IllegalArgumentException("There must be one label per channel");
        }
        stackPlaneLabels = List.copyOf(stackPlaneLabels);
        final long expectedPlaneCount = (long) channels * slices * frames;
        if (stackPlaneLabels.size() != expectedPlaneCount) {
            throw new IllegalArgumentException("There must be one exact label state per stack plane");
        }
        calibration = Objects.requireNonNull(calibration, "calibration");
    }

    private static void requirePositive(final int value, final String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
