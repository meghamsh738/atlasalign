package org.atlasalign.deepslice;

import java.util.Objects;

/**
 * Repository-side trust anchor for an external inventory manifest.
 */
public record DeepSliceReleaseDescriptor(
        String releaseId,
        long manifestSizeBytes,
        String manifestSha256,
        int protocolVersion,
        String pythonVersion,
        String deepSliceVersion,
        String tensorflowVersion,
        String modelRelease,
        String platformArchitecture) {

    public DeepSliceReleaseDescriptor {
        releaseId = Objects.requireNonNull(releaseId, "releaseId");
        manifestSha256 = Objects.requireNonNull(
                manifestSha256, "manifestSha256");
        pythonVersion = Objects.requireNonNull(
                pythonVersion, "pythonVersion");
        deepSliceVersion = Objects.requireNonNull(
                deepSliceVersion, "deepSliceVersion");
        tensorflowVersion = Objects.requireNonNull(
                tensorflowVersion, "tensorflowVersion");
        modelRelease = Objects.requireNonNull(
                modelRelease, "modelRelease");
        platformArchitecture = Objects.requireNonNull(
                platformArchitecture, "platformArchitecture");
        if (releaseId.isBlank()
                || manifestSizeBytes <= 0
                || !manifestSha256.matches("[0-9a-f]{64}")
                || protocolVersion <= 0
                || pythonVersion.isBlank()
                || deepSliceVersion.isBlank()
                || tensorflowVersion.isBlank()
                || modelRelease.isBlank()
                || platformArchitecture.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid DeepSlice release descriptor");
        }
    }
}
