package org.atlasalign.application;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable identity of the verified runtime that produced a real DeepSlice
 * proposal. This application-owned value deliberately contains no adapter
 * implementation type, so proposals and logs can retain the verified facts
 * without reconstructing or guessing them.
 */
public record DeepSliceRuntimeProvenance(
        String verifiedReleaseId,
        Path canonicalRuntimePath,
        int protocolVersion,
        long manifestSizeBytes,
        String manifestSha256,
        String pythonVersion,
        String deepSliceVersion,
        String tensorflowVersion,
        String modelRelease,
        String primaryWeightSha256,
        String secondaryWeightSha256,
        String backboneWeightSha256) {

    private static final int WORKER_PROTOCOL_VERSION = 2;
    private static final String SHA256_PATTERN = "[0-9a-f]{64}";

    public DeepSliceRuntimeProvenance {
        verifiedReleaseId = requireText(verifiedReleaseId, "verifiedReleaseId");
        canonicalRuntimePath = Objects.requireNonNull(
                canonicalRuntimePath, "canonicalRuntimePath");
        if (!canonicalRuntimePath.isAbsolute()
                || !canonicalRuntimePath.equals(
                canonicalRuntimePath.normalize())) {
            throw new IllegalArgumentException(
                    "DeepSlice runtime path must be canonical and absolute");
        }
        if (protocolVersion != WORKER_PROTOCOL_VERSION
                || manifestSizeBytes <= 0) {
            throw new IllegalArgumentException(
                    "DeepSlice runtime provenance must describe protocol v2");
        }
        manifestSha256 = requireSha256(manifestSha256, "manifestSha256");
        pythonVersion = requireText(pythonVersion, "pythonVersion");
        deepSliceVersion = requireText(deepSliceVersion, "deepSliceVersion");
        tensorflowVersion = requireText(tensorflowVersion, "tensorflowVersion");
        modelRelease = requireText(modelRelease, "modelRelease");
        primaryWeightSha256 = requireSha256(
                primaryWeightSha256, "primaryWeightSha256");
        secondaryWeightSha256 = requireSha256(
                secondaryWeightSha256, "secondaryWeightSha256");
        backboneWeightSha256 = requireSha256(
                backboneWeightSha256, "backboneWeightSha256");
    }

    private static String requireText(final String value, final String name) {
        final String required = Objects.requireNonNull(value, name);
        if (required.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return required;
    }

    private static String requireSha256(
            final String value, final String name) {
        final String hash = Objects.requireNonNull(value, name);
        if (!hash.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException(
                    name + " must be a lower-case SHA-256 value");
        }
        return hash;
    }
}
