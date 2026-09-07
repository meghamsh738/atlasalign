package org.atlasalign.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Proposal-only output from the versioned local DeepSlice worker. Detailed
 * predictions are normalized in the application layer; legacy predictions
 * intentionally retain no fabricated O/U/V diagnostics.
 */
public record DeepSlicePlanePrediction(
        int zeroBasedAnteriorPosteriorIndex,
        double sagittalTiltDegrees,
        double horizontalTiltDegrees,
        Optional<DeepSlicePredictionDiagnostics> diagnostics,
        Optional<DeepSliceRuntimeProvenance> runtimeProvenance) {

    public DeepSlicePlanePrediction {
        diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        runtimeProvenance = Objects.requireNonNull(
                runtimeProvenance, "runtimeProvenance");
        validateConvenienceValues(
                zeroBasedAnteriorPosteriorIndex,
                sagittalTiltDegrees,
                horizontalTiltDegrees);
        if (runtimeProvenance.isPresent() && diagnostics.isEmpty()) {
            throw new IllegalArgumentException(
                    "Runtime provenance requires normalized O/U/V diagnostics");
        }
        diagnostics.ifPresent(value -> {
            final DeepSlicePlaneGeometry ensembleGeometry =
                    value.ensemble().geometry();
            if (zeroBasedAnteriorPosteriorIndex
                    != ensembleGeometry.zeroBasedAnteriorPosteriorIndex()
                    || Double.doubleToLongBits(sagittalTiltDegrees)
                    != Double.doubleToLongBits(
                    ensembleGeometry.sagittalTiltDegrees())
                    || Double.doubleToLongBits(horizontalTiltDegrees)
                    != Double.doubleToLongBits(
                    ensembleGeometry.horizontalTiltDegrees())) {
                throw new IllegalArgumentException(
                        "Prediction convenience values must exactly match the ensemble geometry");
            }
        });
    }

    /**
     * Source-compatible legacy prediction with no inferred O/U/V vector.
     */
    public DeepSlicePlanePrediction(
            final int zeroBasedAnteriorPosteriorIndex,
            final double sagittalTiltDegrees,
            final double horizontalTiltDegrees) {
        this(
                zeroBasedAnteriorPosteriorIndex,
                sagittalTiltDegrees,
                horizontalTiltDegrees,
                Optional.empty(),
                Optional.empty());
    }

    /**
     * Source-compatible diagnostics-only construction for in-memory and mock
     * providers. A real bridge must use {@link #fromWorkerVectors} so verified
     * runtime provenance cannot be omitted.
     */
    public DeepSlicePlanePrediction(
            final int zeroBasedAnteriorPosteriorIndex,
            final double sagittalTiltDegrees,
            final double horizontalTiltDegrees,
            final Optional<DeepSlicePredictionDiagnostics> diagnostics) {
        this(
                zeroBasedAnteriorPosteriorIndex,
                sagittalTiltDegrees,
                horizontalTiltDegrees,
                diagnostics,
                Optional.empty());
    }

    /**
     * Detailed normalized form. The worker-reported ensemble is checked, then
     * the application recomputes the stored ensemble vector.
     */
    public DeepSlicePlanePrediction(
            final DeepSliceOuv primary,
            final DeepSliceOuv secondary,
            final DeepSliceOuv reportedEnsemble) {
        this(new DeepSlicePredictionDiagnostics(
                primary, secondary, reportedEnsemble));
    }

    /** Creates a prediction from already-normalized application diagnostics. */
    public DeepSlicePlanePrediction(
            final DeepSlicePredictionDiagnostics diagnostics) {
        this(
                requireDiagnostics(diagnostics).ensemble().geometry()
                        .zeroBasedAnteriorPosteriorIndex(),
                diagnostics.ensemble().geometry().sagittalTiltDegrees(),
                diagnostics.ensemble().geometry().horizontalTiltDegrees(),
                Optional.of(diagnostics),
                Optional.empty());
    }

    /**
     * Detailed application-owned prediction with verified runtime facts.
     */
    public DeepSlicePlanePrediction(
            final DeepSlicePredictionDiagnostics diagnostics,
            final DeepSliceRuntimeProvenance runtimeProvenance) {
        this(
                requireDiagnostics(diagnostics).ensemble().geometry()
                        .zeroBasedAnteriorPosteriorIndex(),
                diagnostics.ensemble().geometry().sagittalTiltDegrees(),
                diagnostics.ensemble().geometry().horizontalTiltDegrees(),
                Optional.of(diagnostics),
                Optional.of(Objects.requireNonNull(
                        runtimeProvenance, "runtimeProvenance")));
    }

    /** Normalizes a primary, secondary, and worker-reported ensemble vector. */
    public static DeepSlicePlanePrediction normalized(
            final DeepSliceOuv primary,
            final DeepSliceOuv secondary,
            final DeepSliceOuv reportedEnsemble) {
        return new DeepSlicePlanePrediction(
                primary, secondary, reportedEnsemble);
    }

    /**
     * Normalizes a real protocol-v2 worker result. Geometry is derived only
     * from the worker vectors in the application layer, and the worker's
     * ensemble is checked but never trusted as the stored ensemble.
     */
    public static DeepSlicePlanePrediction fromWorkerVectors(
            final DeepSliceOuv primary,
            final DeepSliceOuv secondary,
            final DeepSliceOuv reportedEnsemble,
            final DeepSliceRuntimeProvenance runtimeProvenance) {
        return new DeepSlicePlanePrediction(
                new DeepSlicePredictionDiagnostics(
                        primary, secondary, reportedEnsemble),
                runtimeProvenance);
    }

    /** Detailed primary prediction, absent for the legacy constructor. */
    public Optional<DeepSliceModelPrediction> primaryPrediction() {
        return diagnostics.map(DeepSlicePredictionDiagnostics::primary);
    }

    /** Detailed secondary prediction, absent for the legacy constructor. */
    public Optional<DeepSliceModelPrediction> secondaryPrediction() {
        return diagnostics.map(DeepSlicePredictionDiagnostics::secondary);
    }

    /** Detailed application-recomputed ensemble, absent for legacy input. */
    public Optional<DeepSliceModelPrediction> ensemblePrediction() {
        return diagnostics.map(DeepSlicePredictionDiagnostics::ensemble);
    }

    /**
     * Verified runtime facts for a real bridge result, absent for in-memory
     * and mock predictions that did not run a verified protocol-v2 worker.
     */
    public Optional<DeepSliceRuntimeProvenance> verifiedRuntimeProvenance() {
        return runtimeProvenance;
    }

    /** True only when detailed primary-secondary diagnostics warrant caution. */
    public boolean hasPrimarySecondaryCaution() {
        return diagnostics.map(
                DeepSlicePredictionDiagnostics::hasPrimarySecondaryCaution)
                .orElse(false);
    }

    private static DeepSlicePredictionDiagnostics requireDiagnostics(
            final DeepSlicePredictionDiagnostics value) {
        return Objects.requireNonNull(value, "diagnostics");
    }

    private static void validateConvenienceValues(
            final int zeroBasedAnteriorPosteriorIndex,
            final double sagittalTiltDegrees,
            final double horizontalTiltDegrees) {
        if (zeroBasedAnteriorPosteriorIndex < 0
                || zeroBasedAnteriorPosteriorIndex
                >= AllenCoronalLevel.PLANE_COUNT
                || !Double.isFinite(sagittalTiltDegrees)
                || !Double.isFinite(horizontalTiltDegrees)
                || Math.abs(sagittalTiltDegrees) > 45
                || Math.abs(horizontalTiltDegrees) > 45) {
            throw new IllegalArgumentException(
                    "DeepSlice prediction is outside supported coronal bounds");
        }
    }
}
