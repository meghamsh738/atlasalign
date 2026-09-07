package org.atlasalign.application;

import java.util.Objects;

/**
 * Exact installed atlas asset identity recorded for review acceptance.
 */
public record AtlasAssetVerification(
        String role,
        long sizeBytes,
        String sha256) {

    public AtlasAssetVerification {
        role = Objects.requireNonNull(role, "role").trim();
        sha256 = Objects.requireNonNull(sha256, "sha256");
        if (role.isEmpty()
                || sizeBytes <= 0
                || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Atlas asset verification is invalid");
        }
    }
}
