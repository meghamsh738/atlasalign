package org.atlasalign.application;

/**
 * Auditable measurements from an interior-filled copy of a tissue mask.
 */
public record TissueEnvelopeMeasurements(
        double foregroundToBoundsFraction,
        double bilateralMirroredOverlap,
        double hemisphereBalance,
        double addedPreviewFraction) {

    public TissueEnvelopeMeasurements {
        if (!unitInterval(foregroundToBoundsFraction)
                || !unitInterval(bilateralMirroredOverlap)
                || !unitInterval(hemisphereBalance)
                || !unitInterval(addedPreviewFraction)) {
            throw new IllegalArgumentException(
                    "Tissue envelope diagnostics are invalid");
        }
    }

    private static boolean unitInterval(final double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
