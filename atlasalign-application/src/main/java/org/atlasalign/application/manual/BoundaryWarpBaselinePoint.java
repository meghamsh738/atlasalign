package org.atlasalign.application.manual;

import java.util.Objects;
import org.atlasalign.core.Point2D;

/**
 * One immutable outer-boundary control at the explicitly audited installed
 * baseline. Requested endpoints are interpolated from this target, never
 * from a reconstructed atlas sample.
 */
public record BoundaryWarpBaselinePoint(
        String matchId,
        String controlId,
        Point2D sourcePoint,
        Point2D targetPoint) {

    public BoundaryWarpBaselinePoint {
        matchId = requireText(matchId, "matchId");
        controlId = requireText(controlId, "controlId");
        sourcePoint = Objects.requireNonNull(sourcePoint, "sourcePoint");
        targetPoint = Objects.requireNonNull(targetPoint, "targetPoint");
    }

    private static String requireText(
            final String value,
            final String label) {
        final String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
