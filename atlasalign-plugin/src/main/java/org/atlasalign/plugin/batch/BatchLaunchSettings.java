package org.atlasalign.plugin.batch;

import java.io.File;
import java.util.Objects;

/** Immutable parameters reused when a queue opens each section review. */
public record BatchLaunchSettings(
        int registrationChannel,
        int maximumPreviewDimension,
        boolean useLocalDeepSlice,
        File deepSliceRuntimeDirectory,
        File deepSliceWorkDirectory,
        File atlasCacheDirectory) {

    public BatchLaunchSettings {
        if (registrationChannel < 1 || maximumPreviewDimension < 64) {
            throw new IllegalArgumentException(
                    "Batch launch channel or preview dimension is invalid");
        }
        deepSliceRuntimeDirectory = Objects.requireNonNull(
                deepSliceRuntimeDirectory, "deepSliceRuntimeDirectory");
        deepSliceWorkDirectory = Objects.requireNonNull(
                deepSliceWorkDirectory, "deepSliceWorkDirectory");
        atlasCacheDirectory = Objects.requireNonNull(
                atlasCacheDirectory, "atlasCacheDirectory");
    }
}
