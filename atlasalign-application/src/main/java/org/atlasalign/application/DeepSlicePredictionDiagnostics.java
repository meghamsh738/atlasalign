package org.atlasalign.application;

import java.util.Objects;

/**
 * Normalized primary, secondary, and application-recomputed ensemble
 * diagnostics for a DeepSlice worker response.
 */
public record DeepSlicePredictionDiagnostics(
        DeepSliceModelPrediction primary,
        DeepSliceModelPrediction secondary,
        DeepSliceModelPrediction ensemble) {

    private static final double ENSEMBLE_TOLERANCE = 1e-9;

    public DeepSlicePredictionDiagnostics {
        primary = Objects.requireNonNull(primary, "primary");
        secondary = Objects.requireNonNull(secondary, "secondary");
        ensemble = Objects.requireNonNull(ensemble, "ensemble");
        final DeepSliceOuv expected = recomputeEnsemble(
                primary.ouv(), secondary.ouv());
        if (!expected.equals(ensemble.ouv())) {
            throw new IllegalArgumentException(
                    "Ensemble O/U/V must be application-recomputed from primary and secondary");
        }
    }

    /**
     * Normalizes a worker response. The reported ensemble is checked for
     * tolerance but is never used as the application-owned ensemble vector.
     */
    public DeepSlicePredictionDiagnostics(
            final DeepSliceOuv primary,
            final DeepSliceOuv secondary,
            final DeepSliceOuv reportedEnsemble) {
        this(
                DeepSliceModelPrediction.from(Objects.requireNonNull(
                        primary, "primary")),
                DeepSliceModelPrediction.from(Objects.requireNonNull(
                        secondary, "secondary")),
                normalizedEnsemble(primary, secondary, reportedEnsemble));
    }

    public int anteriorPosteriorDisagreementIndices() {
        return Math.abs(primary.geometry().zeroBasedAnteriorPosteriorIndex()
                - secondary.geometry().zeroBasedAnteriorPosteriorIndex());
    }

    public double sagittalTiltDisagreementDegrees() {
        return Math.abs(primary.geometry().sagittalTiltDegrees()
                - secondary.geometry().sagittalTiltDegrees());
    }

    public double horizontalTiltDisagreementDegrees() {
        return Math.abs(primary.geometry().horizontalTiltDegrees()
                - secondary.geometry().horizontalTiltDegrees());
    }

    /**
     * Converts model disagreement into physical atlas-plane diagnostics for
     * the supplied verified plane geometry.
     */
    public DeepSlicePhysicalDisagreement physicalDisagreement(
            final int planeWidth,
            final int planeHeight,
            final double voxelMicrometers) {
        return DeepSlicePhysicalDisagreement.between(
                primary.geometry(), secondary.geometry(),
                planeWidth, planeHeight, voxelMicrometers);
    }

    /** Warn only when primary and secondary differ by more than 20 axis-0 indices. */
    public boolean hasAnteriorPosteriorCaution() {
        return anteriorPosteriorDisagreementIndices() > 20;
    }

    /** Warn only when primary and secondary sagittal tilts differ by more than 5 degrees. */
    public boolean hasSagittalTiltCaution() {
        return sagittalTiltDisagreementDegrees() > 5;
    }

    /** Warn only when primary and secondary horizontal tilts differ by more than 5 degrees. */
    public boolean hasHorizontalTiltCaution() {
        return horizontalTiltDisagreementDegrees() > 5;
    }

    /** True when any strict primary-secondary disagreement threshold is exceeded. */
    public boolean hasPrimarySecondaryCaution() {
        return hasAnteriorPosteriorCaution() || hasSagittalTiltCaution()
                || hasHorizontalTiltCaution();
    }

    private static DeepSliceModelPrediction normalizedEnsemble(
            final DeepSliceOuv primary,
            final DeepSliceOuv secondary,
            final DeepSliceOuv reportedEnsemble) {
        final DeepSliceOuv expected = recomputeEnsemble(primary, secondary);
        final DeepSliceOuv reported = Objects.requireNonNull(
                reportedEnsemble, "reportedEnsemble");
        for (int index = 0; index < DeepSliceOuv.COMPONENT_COUNT; index++) {
            final double expectedComponent = expected.component(index);
            final double reportedComponent = reported.component(index);
            final double error = Math.abs(reportedComponent - expectedComponent);
            final double tolerance = ENSEMBLE_TOLERANCE * Math.max(
                    1, Math.abs(expectedComponent));
            if (!Double.isFinite(error) || error > tolerance) {
                throw new IllegalArgumentException(
                        "Worker-reported ensemble does not match application normalization");
            }
        }
        return DeepSliceModelPrediction.from(expected);
    }

    private static DeepSliceOuv recomputeEnsemble(
            final DeepSliceOuv primary, final DeepSliceOuv secondary) {
        final DeepSliceOuv first = Objects.requireNonNull(primary, "primary");
        final DeepSliceOuv second = Objects.requireNonNull(
                secondary, "secondary");
        final double[] components = new double[DeepSliceOuv.COMPONENT_COUNT];
        for (int index = 0; index < components.length; index++) {
            final double average = (first.component(index)
                    + second.component(index)) / 2;
            if (!Double.isFinite(average)) {
                throw new IllegalArgumentException(
                        "DeepSlice ensemble normalization produced a non-finite component");
            }
            components[index] = average;
        }
        return new DeepSliceOuv(components);
    }
}
