package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalDouble;
import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class AutomaticAlignmentEligibilityTest {

    @Test
    void eligibleCompleteInputAppliesTheImmutableEnsembleTilt() {
        final DeepSliceOuv model = new DeepSliceOuv(
                0, 100, 0, 3, -0.75, 0, 0, -0.75, -3);
        final InitialPlaneProposal proposal = proposal(model, model);
        final DeepSliceInputProvenance input = originalInput();

        final AutomaticAlignmentEligibility eligibility =
                AutomaticAlignmentEligibility.evaluate(
                        proposal,
                        SectionGeometry.FULL,
                        SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                        Optional.of(input), originalPreview());
        final AutomaticPlaneInitialization initialization =
                AutomaticPlaneInitialization.from(proposal, eligibility);

        assertEquals(AutomaticEligibilityStatus.ELIGIBLE_FULL,
                eligibility.status());
        assertTrue(initialization.predictionApplied());
        assertEquals(proposal.prediction().orElseThrow()
                        .sagittalTiltDegrees(),
                initialization.appliedTilt().sagittalDegrees());
        assertEquals(proposal.prediction().orElseThrow()
                        .horizontalTiltDegrees(),
                initialization.appliedTilt().horizontalDegrees());
    }

    @Test
    void eligibleTiltIsTheInitialAndResetReviewPlane() {
        final DeepSliceOuv model = new DeepSliceOuv(
                0, 304, 0, 3, -0.75, 0, 0, -0.75, -3);
        final InitialPlaneProposal proposal = proposal(model, model);
        final AlignmentReviewBasis original = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        assertEquals(original.proposal().coronalLevel(),
                proposal.coronalLevel());
        final DeepSliceInputProvenance input = originalInput();
        final AutomaticAlignmentEligibility eligibility =
                AutomaticAlignmentEligibility.evaluate(
                        proposal, SectionGeometry.FULL,
                        SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                        Optional.of(input), originalPreview());
        final AutomaticPlaneInitialization initialization =
                AutomaticPlaneInitialization.from(proposal, eligibility);
        final AlignmentReviewBasis basis = new AlignmentReviewBasis(
                original.proposal(), Optional.of(proposal),
                original.segmentation(), original.sourceSnapshot(),
                original.atlas(), original.syntheticPixelPolicy(),
                original.inferencePreparationProvenance(),
                originalPreview(), Optional.of(input),
                initialization);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);

        assertEquals(initialization.appliedTilt(),
                session.state().content().atlasPlaneTilt());
        session.apply(new ReviewEdit.EnterManualRefinement());
        session.apply(new ReviewEdit.SetAtlasPlaneTilt(
                AtlasPlaneTilt.CORONAL));
        session.resetToProposal();
        assertEquals(initialization.appliedTilt(),
                session.state().content().atlasPlaneTilt());
    }

    @Test
    void originalInputMustMatchIndependentPreviewIdentityAndRevision() {
        final DeepSliceOuv model = ReviewTestFixtures.coronalOuv(287);
        final InitialPlaneProposal proposal = proposal(model, model);
        final DeepSliceInputProvenance input = originalInput();
        final float[] changedPixels = originalPixels();
        changedPixels[0] = 99;
        final ReviewPreviewDimensions changedPreview =
                ReviewPreviewDimensions.capture(
                        100, 80, changedPixels);
        final DeepSliceInputProvenance unknownRevision =
                new DeepSliceInputProvenance(
                        "unrecognized-input-identity",
                        input.condition(), input.width(), input.height(),
                        input.pixelsSha256(), input.syntheticMaskSha256(),
                        input.syntheticPixelCount(),
                        input.syntheticPixelFraction(),
                        input.observedTissueFraction(),
                        input.preparationIdentitySha256());

        assertEquals(AutomaticEligibilityStatus.REVIEW_ONLY,
                AutomaticAlignmentEligibility.evaluate(
                        proposal, SectionGeometry.FULL,
                        SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                        Optional.of(input), changedPreview).status());
        assertEquals(AutomaticEligibilityStatus.REVIEW_ONLY,
                AutomaticAlignmentEligibility.evaluate(
                        proposal, SectionGeometry.FULL,
                        SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                        Optional.of(unknownRevision),
                        originalPreview()).status());
    }

    @Test
    void partialSyntheticOrDerivedInputsRemainReviewOnly() {
        final DeepSliceOuv model = ReviewTestFixtures.coronalOuv(260);
        final InitialPlaneProposal proposal = proposal(model, model);
        final DeepSliceInput syntheticInput = new DeepSliceInput(
                2, 2, new float[]{1, 2, 3, 4},
                BinaryMask.fromBooleans(
                        2, 2, new boolean[]{false, true, false, false}));
        final DeepSliceInputProvenance provenance =
                DeepSliceInputProvenance.capture(
                        syntheticInput,
                        DeepSliceInputCondition.VIRTUAL_HALF_FULL_CANVAS,
                        OptionalDouble.of(0.25),
                        Optional.of("a".repeat(64)));

        final AutomaticAlignmentEligibility eligibility =
                AutomaticAlignmentEligibility.evaluate(
                        proposal,
                        SectionGeometry.IMAGE_LEFT_HALF,
                        SyntheticPixelReviewPolicy.SYNTHETIC_PIXELS_EXCLUDED,
                        Optional.of(provenance),
                        ReviewPreviewDimensions.capture(
                                syntheticInput.width(),
                                syntheticInput.height(),
                                syntheticInput.pixels()));

        assertEquals(AutomaticEligibilityStatus.REVIEW_ONLY,
                eligibility.status());
        assertFalse(AutomaticPlaneInitialization.from(
                proposal, eligibility).predictionApplied());
        assertTrue(eligibility.reasons().size() >= 3);
    }

    @Test
    void disagreementOverTwelveIndicesIsReviewOnly() {
        final DeepSliceOuv primary = ReviewTestFixtures.coronalOuv(250);
        final DeepSliceOuv secondary = ReviewTestFixtures.coronalOuv(263);
        final AutomaticAlignmentEligibility eligibility =
                AutomaticAlignmentEligibility.evaluate(
                        proposal(primary, secondary),
                        SectionGeometry.FULL,
                        SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                        Optional.of(originalInput()), originalPreview());

        assertEquals(13, new DeepSlicePredictionDiagnostics(
                primary, secondary, average(primary, secondary))
                .anteriorPosteriorDisagreementIndices());
        assertEquals(AutomaticEligibilityStatus.REVIEW_ONLY,
                eligibility.status());
    }

    @Test
    void threeDegreeTiltBoundaryIsEligibleAndAboveIsReviewOnly() {
        final DeepSliceOuv zero = tiltedOuv(287, 0, 0);
        final AutomaticAlignmentEligibility sagittalBoundary =
                eligibility(zero, tiltedOuv(287, 3, 0));
        final AutomaticAlignmentEligibility sagittalOutside =
                eligibility(zero, tiltedOuv(287, 3.001, 0));
        final AutomaticAlignmentEligibility horizontalBoundary =
                eligibility(zero, tiltedOuv(287, 0, 3));
        final AutomaticAlignmentEligibility horizontalOutside =
                eligibility(zero, tiltedOuv(287, 0, 3.001));

        assertEquals(AutomaticEligibilityStatus.ELIGIBLE_FULL,
                sagittalBoundary.status());
        assertEquals(AutomaticEligibilityStatus.REVIEW_ONLY,
                sagittalOutside.status());
        assertEquals(AutomaticEligibilityStatus.ELIGIBLE_FULL,
                horizontalBoundary.status());
        assertEquals(AutomaticEligibilityStatus.REVIEW_ONLY,
                horizontalOutside.status());
    }

    @Test
    void basisRejectsFabricatedEligibleTiltForHalfSection() {
        final AlignmentReviewBasis original = ReviewTestFixtures.basis(
                SectionGeometry.IMAGE_LEFT_HALF);
        final DeepSliceOuv model = ReviewTestFixtures.coronalOuv(287);
        final InitialPlaneProposal proposal = proposal(model, model);
        final AutomaticAlignmentEligibility fabricated =
                new AutomaticAlignmentEligibility(
                        AutomaticEligibilityStatus.ELIGIBLE_FULL,
                        java.util.List.of("fabricated"));

        assertThrows(IllegalArgumentException.class, () ->
                new AlignmentReviewBasis(
                        original.proposal(),
                        Optional.of(proposal),
                        original.segmentation(),
                        original.sourceSnapshot(),
                        original.atlas(),
                        original.syntheticPixelPolicy(),
                        original.inferencePreparationProvenance(),
                        originalPreview(),
                        Optional.of(originalInput()),
                        AutomaticPlaneInitialization.from(
                                proposal, fabricated)));
    }

    private static DeepSliceInputProvenance originalInput() {
        return DeepSliceInputProvenance.capture(
                new DeepSliceInput(
                        100, 80, originalPixels(),
                        BinaryMask.empty(100, 80)),
                DeepSliceInputCondition.ORIGINAL_PREVIEW,
                OptionalDouble.of(0.4), Optional.empty());
    }

    private static ReviewPreviewDimensions originalPreview() {
        return ReviewPreviewDimensions.capture(
                100, 80, originalPixels());
    }

    private static float[] originalPixels() {
        final float[] pixels = new float[8_000];
        pixels[0] = 1;
        pixels[1] = 2;
        pixels[2] = 3;
        pixels[3] = 4;
        return pixels;
    }

    private static AutomaticAlignmentEligibility eligibility(
            final DeepSliceOuv first, final DeepSliceOuv second) {
        return AutomaticAlignmentEligibility.evaluate(
                proposal(first, second), SectionGeometry.FULL,
                SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                Optional.of(originalInput()), originalPreview());
    }

    private static DeepSliceOuv tiltedOuv(
            final double centerDepth,
            final double sagittalDegrees,
            final double horizontalDegrees) {
        final double sagittalGradient = Math.tan(Math.toRadians(
                sagittalDegrees));
        final double horizontalGradient = Math.tan(Math.toRadians(
                horizontalDegrees));
        final double originDepth = centerDepth
                + 228 * sagittalGradient
                - 160 * horizontalGradient;
        return new DeepSliceOuv(
                0, originDepth, 0,
                3, -3 * sagittalGradient, 0,
                0, -3 * horizontalGradient, -3);
    }

    private static InitialPlaneProposal proposal(
            final DeepSliceOuv primary,
            final DeepSliceOuv secondary) {
        final DeepSliceOuv ensemble = average(primary, secondary);
        final DeepSlicePlanePrediction prediction =
                DeepSlicePlanePrediction.fromWorkerVectors(
                        primary, secondary, ensemble,
                        new DeepSliceRuntimeProvenance(
                                "test-r3", Path.of("/verified/runtime").toAbsolutePath().normalize(),
                                2, 1, "a".repeat(64), "3.11.15",
                                "1.2.8", "2.21.0", "test-model",
                                "b".repeat(64), "c".repeat(64),
                                "d".repeat(64)));
        return new InitialPlaneProposal(
                new AllenCoronalLevel(
                        prediction.zeroBasedAnteriorPosteriorIndex()),
                InitialPlaneSource.LOCAL_DEEPSLICE,
                Optional.of(prediction), Optional.empty(), Optional.empty());
    }

    private static DeepSliceOuv average(
            final DeepSliceOuv first, final DeepSliceOuv second) {
        final double[] values = new double[DeepSliceOuv.COMPONENT_COUNT];
        for (int index = 0; index < values.length; index++) {
            values[index] = (first.component(index)
                    + second.component(index)) / 2;
        }
        return new DeepSliceOuv(values);
    }
}
