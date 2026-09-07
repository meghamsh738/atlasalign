package org.atlasalign.application.manual;

import java.util.Objects;
import org.atlasalign.core.Point2D;

/** One ordered boundary sample and its unit outward normal in preview space. */
public record BoundaryFitSample(Point2D point, Point2D normal) {

    public BoundaryFitSample {
        point = Objects.requireNonNull(point, "point");
        normal = Objects.requireNonNull(normal, "normal");
        final double magnitude = Math.hypot(normal.x(), normal.y());
        if (!Double.isFinite(magnitude) || magnitude < 1e-12) {
            throw new IllegalArgumentException(
                    "Boundary-fit sample normal must be finite and non-zero");
        }
        normal = new Point2D(normal.x() / magnitude,
                normal.y() / magnitude);
    }
}
