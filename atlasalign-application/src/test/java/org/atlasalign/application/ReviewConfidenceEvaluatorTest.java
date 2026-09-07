package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReviewConfidenceEvaluatorTest {

    @Test
    void exposesRawNamedEvidenceWithoutProbability() {
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.FULL));

        final ReviewConfidenceReport report =
                session.confidence();

        assertEquals(
                ConfidenceEvidenceCategory.MANUAL_ONLY,
                report.category());
        assertTrue(report.evidence().stream().anyMatch(
                item -> item.metric()
                        == ConfidenceMetric.ATLAS_TISSUE_DICE
                        && item.value().isPresent()
                        && item.value().getAsDouble() == 0.88
                        && item.status()
                        == ConfidenceEvidenceStatus.INFORMATIONAL
                        && item.unit().equals("Dice coefficient")));
        assertFalse(report.toString().toLowerCase()
                .contains("percent"));
        assertFalse(report.toString().toLowerCase()
                .contains("probability"));
    }

    @Test
    void damagedOrHalfGeometryStaysLimitedWithDirectDefault() {
        final AlignmentReviewSession half =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.IMAGE_LEFT_HALF));
        assertEquals(
                ConfidenceEvidenceCategory.MANUAL_ONLY,
                half.confidence().category());
        assertEquals(AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                half.state().content().orientation());
        assertTrue(half.confidence().reasons().stream().anyMatch(
                reason -> reason.contains("hemisphere")));
        assertTrue(half.confidence().evidence().stream().anyMatch(
                item -> item.metric()
                        == ConfidenceMetric.LATERALITY_ORIENTATION
                        && item.status()
                        == ConfidenceEvidenceStatus.CAUTION));
        half.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT));
        assertTrue(half.confidence().evidence().stream().anyMatch(
                item -> item.metric()
                        == ConfidenceMetric.LATERALITY_ORIENTATION
                        && item.status()
                        == ConfidenceEvidenceStatus.SUPPORTING));

        final AlignmentReviewSession damaged =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.PARTIAL_OR_DAMAGED));
        assertEquals(
                ConfidenceEvidenceCategory.MANUAL_ONLY,
                damaged.confidence().category());
        assertTrue(damaged.confidence().reasons().stream().anyMatch(
                reason -> reason.contains("hemisphere")));
        damaged.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT));
        assertEquals(
                ConfidenceEvidenceCategory.MANUAL_ONLY,
                damaged.confidence().category());
        assertTrue(damaged.confidence().reasons().stream().anyMatch(
                reason -> reason.contains("damaged")));
    }

    @Test
    void landmarkEvidenceRecomputesAfterLevelChange() {
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.FULL));
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "one",
                new AllenCoronalLevel(240),
                new org.atlasalign.core.Point2D(1, 1),
                new org.atlasalign.core.Point2D(4, 5))));
        assertTrue(session.confidence().evidence().stream().anyMatch(
                item -> item.metric()
                        == ConfidenceMetric.ACTIVE_LANDMARK_RESIDUAL
                        && item.value().isPresent()
                        && item.value().getAsDouble() == 5
                        && item.status()
                        == ConfidenceEvidenceStatus.INFORMATIONAL
                        && item.explanation().contains("not independent")));
        assertEquals(
                ConfidenceEvidenceCategory.MANUAL_ONLY,
                session.confidence().category());

        session.apply(new ReviewEdit.SetCoronalLevel(
                new AllenCoronalLevel(241)));
        assertTrue(session.confidence().evidence().stream().anyMatch(
                item -> item.metric()
                        == ConfidenceMetric.ACTIVE_LANDMARK_RESIDUAL
                        && item.status()
                        == ConfidenceEvidenceStatus.UNAVAILABLE));
    }

    @Test
    void modelDiagnosticsAreShownRatherThanClaimedUnavailable() {
        final AlignmentReviewBasis original = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final DeepSliceOuv model = new DeepSliceOuv(
                0, 287, 0, 3, 0, 0, 0, 0, -3);
        final DeepSlicePlanePrediction prediction = new DeepSlicePlanePrediction(
                new DeepSlicePredictionDiagnostics(model, model, model));
        final AlignmentReviewBasis detailed = new AlignmentReviewBasis(
                original.proposal(),
                Optional.of(new InitialPlaneProposal(
                        original.proposal().coronalLevel(),
                        InitialPlaneSource.LOCAL_DEEPSLICE,
                        Optional.of(prediction),
                        Optional.empty(),
                        Optional.empty())),
                original.segmentation(),
                original.sourceSnapshot(),
                original.atlas(),
                original.syntheticPixelPolicy(),
                original.inferencePreparationProvenance(),
                original.previewDimensions());
        final AlignmentReviewSession session = new AlignmentReviewSession(detailed);
        assertTrue(session.confidence().evidence().stream().anyMatch(
                item -> item.metric()
                        == ConfidenceMetric.DEEPSLICE_MODEL_DISAGREEMENT
                        && item.status() == ConfidenceEvidenceStatus.INFORMATIONAL
                        && item.explanation().contains("within")));
    }

    @Test
    void zeroDiceAndUnverifiedSyntheticProvenanceAreCautions() {
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.FULL,
                                0,
                                SyntheticPixelReviewPolicy.UNVERIFIED));

        final ReviewConfidenceReport report =
                session.confidence();

        assertEquals(
                ConfidenceEvidenceCategory.MANUAL_ONLY,
                report.category());
        assertTrue(report.requiresAcknowledgement());
        assertTrue(report.evidence().stream().anyMatch(
                item -> item.metric()
                        == ConfidenceMetric.ATLAS_TISSUE_DICE
                        && item.status()
                        == ConfidenceEvidenceStatus.CAUTION));
        assertTrue(report.evidence().stream().anyMatch(
                item -> item.metric()
                        == ConfidenceMetric.SYNTHETIC_PIXEL_EXCLUSION
                        && item.status()
                        == ConfidenceEvidenceStatus.CAUTION));
    }

    @Test
    void tinyPositiveDiceIsNotPresentedAsSupportingEvidence() {
        final ReviewConfidenceReport report =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.FULL,
                                1e-12,
                                SyntheticPixelReviewPolicy
                                        .NO_SYNTHETIC_PIXELS))
                        .confidence();

        assertEquals(
                ConfidenceEvidenceCategory.MANUAL_ONLY,
                report.category());
        assertTrue(report.evidence().stream().anyMatch(
                item -> item.metric()
                        == ConfidenceMetric.ATLAS_TISSUE_DICE
                        && item.status()
                        == ConfidenceEvidenceStatus.INFORMATIONAL));
    }

    @Test
    void classifiesAutomaticAndManualEvidenceWithoutAProbability() {
        final DeepSliceOuv model = ReviewTestFixtures.coronalOuv(287);
        final AlignmentReviewSession consistent = new AlignmentReviewSession(
                ReviewTestFixtures.automaticBasis(SectionGeometry.FULL,
                        model, model));
        assertEquals(ConfidenceEvidenceCategory.AUTOMATIC_CONSISTENT,
                consistent.confidence().category());

        final AlignmentReviewBasis automaticHalf = ReviewTestFixtures
                .automaticBasis(SectionGeometry.IMAGE_LEFT_HALF, model, model);
        assertEquals(ConfidenceEvidenceCategory.AUTOMATIC_LIMITED,
                new AlignmentReviewSession(automaticHalf).confidence().category());

        final AlignmentReviewBasis original = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final DeepSlicePlanePrediction unverified = new DeepSlicePlanePrediction(
                new DeepSlicePredictionDiagnostics(model, model, model));
        final AlignmentReviewBasis conflicting = new AlignmentReviewBasis(
                original.proposal(),
                Optional.of(new InitialPlaneProposal(original.proposal()
                        .coronalLevel(), InitialPlaneSource.LOCAL_DEEPSLICE,
                        Optional.of(unverified), Optional.empty(), Optional.empty())),
                original.segmentation(), original.sourceSnapshot(), original.atlas(),
                original.syntheticPixelPolicy(),
                original.inferencePreparationProvenance(), original.previewDimensions());
        assertEquals(ConfidenceEvidenceCategory.AUTOMATIC_CONFLICTING,
                new AlignmentReviewSession(conflicting).confidence().category());

        final AlignmentReviewContent underSpecified = new AlignmentReviewContent(
                original.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                ReviewWorkflowMode.MANUAL_REFINEMENT,
                AtlasOrientation.UNCONFIRMED_PROVISIONAL_DIRECT,
                ObservedAnatomicalHemisphere.BOTH,
                original.initialContent().manualPreviewAdjustment(), List.of());
        assertEquals(ConfidenceEvidenceCategory.NOT_ASSESSABLE,
                new ReviewConfidenceEvaluator().evaluate(new AlignmentReviewState(
                        original, underSpecified, 0)).category());
    }
}
