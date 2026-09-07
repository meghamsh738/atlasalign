package org.atlasalign.core;

import java.util.Objects;

/**
 * Immutable scientific identity used to prove that intake did not mutate the
 * source image.
 */
public record SourceImageSnapshot(
        SourceImageMetadata metadata,
        String pixelSha256) {

    public SourceImageSnapshot {
        metadata = Objects.requireNonNull(metadata, "metadata");
        pixelSha256 = Objects.requireNonNull(pixelSha256, "pixelSha256");
        if (!pixelSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("pixelSha256 must be a lowercase SHA-256 value");
        }
    }
}
