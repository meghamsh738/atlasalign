package org.atlasalign.application;

import java.util.Objects;

/**
 * Immutable verified-atlas identity attached only to typed anatomical handles.
 */
public record AnatomicalHandleMetadata(
        int atlasRegionId,
        String guideAcronym,
        AtlasAnatomicalSide atlasSide,
        LandmarkSeedOrigin seedOrigin,
        String selectedBoundarySha256) {

    public AnatomicalHandleMetadata {
        if (atlasRegionId <= 0) {
            throw new IllegalArgumentException(
                    "Atlas region ID must be positive");
        }
        guideAcronym = requireText(guideAcronym, "guideAcronym");
        atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
        seedOrigin = Objects.requireNonNull(seedOrigin, "seedOrigin");
        selectedBoundarySha256 = Objects.requireNonNull(
                selectedBoundarySha256, "selectedBoundarySha256").trim();
        if (!selectedBoundarySha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Selected-boundary identity must be a lowercase SHA-256 value");
        }
    }

    private static String requireText(
            final String value,
            final String field) {
        final String checked = Objects.requireNonNull(value, field).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return checked;
    }
}
