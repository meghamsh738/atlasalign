package org.atlasalign.application;

import java.util.Objects;
import java.util.OptionalDouble;
import org.atlasalign.core.MaskBounds;

/**
 * Geometry classification with the measurements used to reach it.
 */
public record TissueGeometryResult(
        SectionGeometry geometry,
        MaskBounds bounds,
        double widthToHeightRatio,
        double foregroundToBoundsFraction,
        double imageLeftEdgeDispersion,
        double imageRightEdgeDispersion,
        double bilateralMirroredOverlap,
        double hemisphereBalance,
        int connectedComponentCount,
        int substantialComponentCount,
        OptionalDouble medialEdgeX) {

    public TissueGeometryResult {
        geometry = Objects.requireNonNull(geometry, "geometry");
        bounds = Objects.requireNonNull(bounds, "bounds");
        medialEdgeX = Objects.requireNonNull(medialEdgeX, "medialEdgeX");
        if (!positiveFinite(widthToHeightRatio)
                || !unitInterval(foregroundToBoundsFraction)
                || !unitInterval(imageLeftEdgeDispersion)
                || !unitInterval(imageRightEdgeDispersion)
                || !unitInterval(bilateralMirroredOverlap)
                || !unitInterval(hemisphereBalance)
                || connectedComponentCount < 1
                || substantialComponentCount < 0
                || substantialComponentCount > connectedComponentCount
                || medialEdgeX.isPresent()
                && !Double.isFinite(medialEdgeX.getAsDouble())) {
            throw new IllegalArgumentException(
                    "Tissue geometry diagnostics are invalid");
        }
    }

    public boolean anatomicalLateralityRequiresConfirmation() {
        return geometry != SectionGeometry.FULL;
    }

    private static boolean positiveFinite(final double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static boolean unitInterval(final double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
