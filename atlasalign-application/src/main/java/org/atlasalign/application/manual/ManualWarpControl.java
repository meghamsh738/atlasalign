package org.atlasalign.application.manual;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.core.Point2D;

/**
 * Immutable reviewer geometry for one local atlas-side warp.
 *
 * <p>This is intentionally not a {@code LandmarkPair}: controls are manual
 * mesh geometry and never contribute automatic evidence or confidence.</p>
 */
public record ManualWarpControl(
        String id,
        ManualHemisphereWarp2D.AtlasSide atlasSide,
        ManualWarpControlOrigin origin,
        String groupId,
        String structureAcronym,
        Point2D sourcePoint,
        Point2D targetPoint) {

    public ManualWarpControl {
        id = requireText(id, "id");
        atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
        origin = Objects.requireNonNull(origin, "origin");
        groupId = requireText(groupId, "groupId");
        structureAcronym = structureAcronym == null
                ? "" : structureAcronym.trim();
        if (origin != ManualWarpControlOrigin.VERIFIED_STRUCTURE_BOUNDARY
                && origin != ManualWarpControlOrigin.STRUCTURE_GUIDE) {
            structureAcronym = "";
        } else if (structureAcronym.isBlank()) {
            throw new IllegalArgumentException(
                    "Structure-guide controls require a structure acronym");
        }
        sourcePoint = Objects.requireNonNull(sourcePoint, "sourcePoint");
        targetPoint = Objects.requireNonNull(targetPoint, "targetPoint");
    }

    /** Convenience constructor for controls without a structure target. */
    public ManualWarpControl(
            final String id,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final ManualWarpControlOrigin origin,
            final String groupId,
            final Point2D sourcePoint,
            final Point2D targetPoint) {
        this(id, atlasSide, origin, groupId, "", sourcePoint, targetPoint);
    }

    /** Optional view for callers that prefer explicit absence semantics. */
    public Optional<String> structureTarget() {
        return structureAcronym.isBlank()
                ? Optional.empty() : Optional.of(structureAcronym);
    }

    public String controlId() {
        return id;
    }

    public Point2D source() {
        return sourcePoint;
    }

    public Point2D target() {
        return targetPoint;
    }

    public String groupIdentity() {
        return groupId;
    }

    private static String requireText(
            final String value,
            final String name) {
        final String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
