package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class InitialPlaneEstimatorTest {

    private final InitialPlaneEstimator estimator =
            new InitialPlaneEstimator();
    private final DeepSliceInput input = new DeepSliceInput(
            2, 2, new float[]{1, 2, 3, 4}, BinaryMask.empty(2, 2));

    @Test
    void usesAValidLocalPredictionAsAReviewOnlyProposal() {
        final InitialPlaneProposal result = estimator.estimate(
                new AllenCoronalLevel(200),
                input,
                Optional.of(ignored ->
                        new DeepSlicePlanePrediction(245, 1.5, -2)));

        assertEquals(245,
                result.coronalLevel()
                        .zeroBasedAnteriorPosteriorIndex());
        assertEquals(InitialPlaneSource.LOCAL_DEEPSLICE, result.source());
        assertTrue(result.prediction().isPresent());
        assertTrue(result.fallbackReason().isEmpty());
        assertTrue(result.fallbackMessage().isEmpty());
        assertTrue(result.requiresUserReview());
    }

    @Test
    void retainsVerifiedRuntimeProvenanceInTheReviewProposal() {
        final DeepSliceRuntimeProvenance provenance =
                new DeepSliceRuntimeProvenance(
                        "deepslice-test-r3",
                        Path.of("/verified/runtime").toAbsolutePath().normalize(),
                        2,
                        1,
                        "a".repeat(64),
                        "3.11.15",
                        "1.2.8",
                        "2.21.0",
                        "test-model-release",
                        "b".repeat(64),
                        "c".repeat(64),
                        "d".repeat(64));
        final DeepSliceOuv primary = new DeepSliceOuv(
                0, 100, 0, 3, 0, 0, 0, 0, -3);
        final DeepSlicePlanePrediction prediction =
                DeepSlicePlanePrediction.fromWorkerVectors(
                        primary, primary, primary, provenance);

        final InitialPlaneProposal result = estimator.estimate(
                new AllenCoronalLevel(200),
                input,
                Optional.of(ignored -> prediction));

        assertEquals(provenance, result.prediction().orElseThrow()
                .runtimeProvenance().orElseThrow());
    }

    @Test
    void preservesManualLevelWhenNoWorkerIsConfigured() {
        final AllenCoronalLevel manual = new AllenCoronalLevel(210);

        final InitialPlaneProposal result = estimator.estimate(
                manual, input, Optional.empty());

        assertEquals(manual, result.coronalLevel());
        assertEquals(InitialPlaneSource.MANUAL_FALLBACK, result.source());
        assertEquals(
                ManualFallbackReason.NOT_CONFIGURED,
                result.fallbackReason().orElseThrow());
        assertTrue(result.requiresUserReview());
    }

    @Test
    void preservesManualLevelWhenLocalInferenceFailsSafely() {
        final AllenCoronalLevel manual = new AllenCoronalLevel(215);

        final InitialPlaneProposal result = estimator.estimate(
                manual,
                input,
                Optional.of(ignored -> {
                    throw new DeepSliceUnavailableException(
                            "Verified worker timed out");
                }));

        assertEquals(manual, result.coronalLevel());
        assertEquals(InitialPlaneSource.MANUAL_FALLBACK, result.source());
        assertEquals(
                ManualFallbackReason.WORKER_FAILED,
                result.fallbackReason().orElseThrow());
        assertEquals(
                "Verified worker timed out",
                result.fallbackMessage().orElseThrow());
    }

    @Test
    void invalidOrNullWorkerOutputFallsBackInsteadOfEscaping() {
        final AllenCoronalLevel manual = new AllenCoronalLevel(220);

        final InitialPlaneProposal nullResult = estimator.estimate(
                manual, input, Optional.of(ignored -> null));
        final InitialPlaneProposal invalidResult = estimator.estimate(
                manual,
                input,
                Optional.of(ignored -> {
                    throw new IllegalArgumentException("bad worker JSON");
                }));

        assertEquals(manual, nullResult.coronalLevel());
        assertEquals(
                ManualFallbackReason.INVALID_WORKER_OUTPUT,
                nullResult.fallbackReason().orElseThrow());
        assertEquals(manual, invalidResult.coronalLevel());
        assertEquals(
                ManualFallbackReason.INVALID_WORKER_OUTPUT,
                invalidResult.fallbackReason().orElseThrow());
    }
}
