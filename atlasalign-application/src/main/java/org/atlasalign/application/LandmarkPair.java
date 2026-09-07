package org.atlasalign.application;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.core.Point2D;

/**
 * An exact-atlas-plane to preview correspondence.
 */
public record LandmarkPair(
        String id,
        AllenCoronalLevel coronalLevel,
        AtlasPlaneTilt atlasPlaneTilt,
        Point2D atlasPoint,
        Point2D previewPoint,
        LandmarkRole role,
        Optional<AnatomicalHandleMetadata> anatomicalHandleMetadata) {

    /** Compatibility constructor for a landmark without typed handle metadata. */
    public LandmarkPair(
            final String id,
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final Point2D atlasPoint,
            final Point2D previewPoint,
            final LandmarkRole role) {
        this(id, coronalLevel, atlasPlaneTilt, atlasPoint, previewPoint, role,
                Optional.empty());
    }

    /** Constructor for a verified-boundary anatomical handle. */
    public LandmarkPair(
            final String id,
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final Point2D atlasPoint,
            final Point2D previewPoint,
            final LandmarkRole role,
            final AnatomicalHandleMetadata anatomicalHandleMetadata) {
        this(id, coronalLevel, atlasPlaneTilt, atlasPoint, previewPoint, role,
                Optional.of(Objects.requireNonNull(
                        anatomicalHandleMetadata,
                        "anatomicalHandleMetadata")));
    }

    /** Source-compatible constructor; existing landmarks participate in fits. */
    public LandmarkPair(
            final String id,
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final Point2D atlasPoint,
            final Point2D previewPoint) {
        this(id, coronalLevel, atlasPlaneTilt, atlasPoint, previewPoint,
                LandmarkRole.FIT);
    }

    /** Source-compatible constructor for an axis-aligned atlas plane. */
    public LandmarkPair(
            final String id,
            final AllenCoronalLevel coronalLevel,
            final Point2D atlasPoint,
            final Point2D previewPoint) {
        this(id, coronalLevel, AtlasPlaneTilt.CORONAL, atlasPoint, previewPoint,
                LandmarkRole.FIT);
    }

    public LandmarkPair {
        id = Objects.requireNonNull(id, "id").trim();
        coronalLevel = Objects.requireNonNull(
                coronalLevel, "coronalLevel");
        atlasPlaneTilt = Objects.requireNonNull(
                atlasPlaneTilt, "atlasPlaneTilt");
        atlasPoint = Objects.requireNonNull(atlasPoint, "atlasPoint");
        previewPoint = Objects.requireNonNull(
                previewPoint, "previewPoint");
        role = Objects.requireNonNull(role, "role");
        anatomicalHandleMetadata = Objects.requireNonNull(
                anatomicalHandleMetadata, "anatomicalHandleMetadata");
        if (id.isEmpty()) {
            throw new IllegalArgumentException(
                    "Landmark ID must not be blank");
        }
    }

    /** Returns a copy with only the tissue endpoint changed. */
    public LandmarkPair withPreviewPoint(final Point2D nextPreviewPoint) {
        return new LandmarkPair(id, coronalLevel, atlasPlaneTilt, atlasPoint,
                nextPreviewPoint, role, anatomicalHandleMetadata);
    }

    /** Returns a copy with only the atlas endpoint changed. */
    public LandmarkPair withAtlasPoint(final Point2D nextAtlasPoint) {
        return new LandmarkPair(id, coronalLevel, atlasPlaneTilt,
                nextAtlasPoint, previewPoint, role, anatomicalHandleMetadata);
    }

    /** Returns a copy with only its fit/check role changed. */
    public LandmarkPair withRole(final LandmarkRole nextRole) {
        return new LandmarkPair(id, coronalLevel, atlasPlaneTilt, atlasPoint,
                previewPoint, nextRole, anatomicalHandleMetadata);
    }
}
