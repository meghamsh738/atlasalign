package org.atlasalign.application.roi;

import java.util.Objects;
import org.atlasalign.core.Point2D;

/** Stable reviewer vertex in full-resolution SOURCE_PIXEL coordinates. */
public record ReviewerRoiVertex(String id, Point2D sourcePoint) {

    public ReviewerRoiVertex {
        id = requireText(id, "id");
        sourcePoint = Objects.requireNonNull(sourcePoint, "sourcePoint");
        if (!Double.isFinite(sourcePoint.x())
                || !Double.isFinite(sourcePoint.y())) {
            throw new IllegalArgumentException(
                    "ROI vertex coordinates must be finite");
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
