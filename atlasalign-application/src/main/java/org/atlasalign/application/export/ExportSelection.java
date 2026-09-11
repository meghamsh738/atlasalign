package org.atlasalign.application.export;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.atlasalign.core.SourceImageMetadata;

/** Immutable source indices for one reviewed Z/T plane and its export channels. */
public record ExportSelection(List<Integer> channels, int slice, int frame) {

    public ExportSelection {
        channels = List.copyOf(Objects.requireNonNull(channels, "channels"));
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("Select at least one export channel");
        }
        int previous = 0;
        for (final int channel : channels) {
            if (channel <= previous) {
                throw new IllegalArgumentException(
                        "Export channels must be positive, distinct, ascending source indices");
            }
            previous = channel;
        }
        if (slice <= 0 || frame <= 0) {
            throw new IllegalArgumentException(
                    "Export slice and frame must be positive source indices");
        }
    }

    public void validateAgainst(final SourceImageMetadata source) {
        final SourceImageMetadata checked = Objects.requireNonNull(source, "source");
        if (channels.get(channels.size() - 1) > checked.channels()
                || slice > checked.slices() || frame > checked.frames()) {
            throw new IllegalArgumentException(
                    "Export channel, slice, or frame exceeds the source dimensions");
        }
    }

    public static ExportSelection allChannels(
            final SourceImageMetadata source, final int slice, final int frame) {
        final SourceImageMetadata checked = Objects.requireNonNull(source, "source");
        final ExportSelection selection = new ExportSelection(
                IntStream.rangeClosed(1, checked.channels()).boxed().toList(),
                slice, frame);
        selection.validateAgainst(checked);
        return selection;
    }
}
