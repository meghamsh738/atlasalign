package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.atlasalign.application.manual.BoundaryFitModel;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class AssistedBoundaryFitReviewEditTest {

    private static final String INPUT_HASH = "a".repeat(64);

    @Test
    void joinedFitIsOneManualOnlyRevisionWithExactUndoAndRedo() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(SectionGeometry.FULL);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final AlignmentReviewContent before = session.state().content();
        final ReviewConfidenceReport confidenceBefore = session.confidence();
        final long revisionBefore = session.state().contentRevision();
        final AffineTransform2D correction = correction(
                1.10, 0, 4,
                0, 1.10, -3);

        session.apply(edit(
                ReviewSectionMode.FULL, Optional.empty(), correction));

        final AlignmentReviewContent fitted = session.state().content();
        assertEquals(revisionBefore + 1,
                session.state().contentRevision());
        assertEquals(before.manualPreviewAdjustment().andThen(correction),
                fitted.manualPreviewAdjustment());
        assertEquals(before.landmarks(), fitted.landmarks());
        assertEquals(before.coronalLevel(), fitted.coronalLevel());
        assertEquals(before.atlasPlaneTilt(), fitted.atlasPlaneTilt());
        assertEquals(before.orientation(), fitted.orientation());
        assertEquals(before.observedHemisphere(),
                fitted.observedHemisphere());
        final ReviewConfidenceReport confidenceAfter = session.confidence();
        assertEquals(confidenceBefore.category(), confidenceAfter.category(),
                "Manual match geometry must not upgrade confidence");
        assertEquals(confidenceBefore.reasons(), confidenceAfter.reasons());
        assertEquals(confidenceBefore.evidence().stream()
                        .filter(item -> item.metric()
                                != ConfidenceMetric.TRANSFORM_PLAUSIBILITY)
                        .toList(),
                confidenceAfter.evidence().stream()
                        .filter(item -> item.metric()
                                != ConfidenceMetric.TRANSFORM_PLAUSIBILITY)
                        .toList(),
                "Automatic and observational evidence must remain unchanged");
        assertEquals(ReviewOperation.APPLY_ASSISTED_BOUNDARY_FIT,
                session.auditTrail().get(0).operation());
        assertTrue(session.auditTrail().get(0).description()
                .contains("manual geometry only, confidence unchanged"));

        assertTrue(session.undo());
        assertEquals(before, session.state().content());
        assertTrue(session.redo());
        assertEquals(fitted, session.state().content());
    }

    @Test
    void disjoinedFitMovesOnlyTheSelectedSide() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewTestFixtures.basis(SectionGeometry.FULL));
        session.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.DISJOINED));
        final AffineTransform2D rightBefore = session.state().content()
                .manualSidePlacement()
                .transform(ManualHemisphereWarp2D.AtlasSide.RIGHT);
        final AffineTransform2D correction = correction(
                1, 0, 7,
                0, 1, -2);

        session.apply(edit(
                ReviewSectionMode.DISJOINED,
                Optional.of(ManualHemisphereWarp2D.AtlasSide.LEFT),
                correction));

        assertEquals(correction, session.state().content()
                .manualSidePlacement()
                .transform(ManualHemisphereWarp2D.AtlasSide.LEFT));
        assertEquals(rightBefore, session.state().content()
                .manualSidePlacement()
                .transform(ManualHemisphereWarp2D.AtlasSide.RIGHT));
    }

    @Test
    void staleAsynchronousApplyInstallsNoRevisionOrAuditEvent() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewTestFixtures.basis(SectionGeometry.FULL));
        final long requestRevision = session.state().contentRevision();
        session.apply(new ReviewEdit.SetAtlasPlaneTilt(
                new AtlasPlaneTilt(2, -1)));
        final int auditSize = session.auditTrail().size();
        final AlignmentReviewContent current = session.state().content();

        assertFalse(session.applyIfCurrentRevision(
                requestRevision,
                edit(ReviewSectionMode.FULL, Optional.empty(), correction(
                        1, 0, 3,
                        0, 1, 2))));

        assertEquals(current, session.state().content());
        assertEquals(auditSize, session.auditTrail().size());
    }

    @Test
    void independentAxisFitComposesAfterRotatedManualResizeWithoutShear() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewTestFixtures.basis(SectionGeometry.FULL));
        final Point2D pivot = new Point2D(20, 15);
        final double sourceAxis = Math.toRadians(12);
        session.apply(new ReviewEdit.Rotate(sourceAxis, pivot));
        session.apply(new ReviewEdit.ScaleAxes(
                1.15, 0.85, sourceAxis, pivot));
        final AffineTransform2D correction = orthogonalCorrection(
                sourceAxis, Math.toRadians(17), 1.05, 0.95);

        session.apply(edit(ReviewSectionMode.FULL, Optional.empty(),
                BoundaryFitModel.ORTHOGONAL_XY, correction));

        final AffineTransform2D fitted = session.state()
                .effectiveAtlasToPreview();
        final double dot = fitted.m00() * fitted.m01()
                + fitted.m10() * fitted.m11();
        assertEquals(0, dot, 1e-8,
                "guided width/height fitting must retain perpendicular axes");
    }

    private static ReviewEdit.ApplyAssistedBoundaryFit edit(
            final ReviewSectionMode mode,
            final Optional<ManualHemisphereWarp2D.AtlasSide> side,
            final AffineTransform2D correction) {
        return edit(mode, side, BoundaryFitModel.SIMILARITY, correction);
    }

    private static ReviewEdit.ApplyAssistedBoundaryFit edit(
            final ReviewSectionMode mode,
            final Optional<ManualHemisphereWarp2D.AtlasSide> side,
            final BoundaryFitModel model,
            final AffineTransform2D correction) {
        return new ReviewEdit.ApplyAssistedBoundaryFit(
                mode, side, model,
                correction, 4, "boundary-assist-test-v1", INPUT_HASH,
                "plane-test", "placement-test", "support-test");
    }

    private static AffineTransform2D orthogonalCorrection(
            final double sourceAxis,
            final double targetAxis,
            final double scaleX,
            final double scaleY) {
        final double sourceCosine = Math.cos(sourceAxis);
        final double sourceSine = Math.sin(sourceAxis);
        final double targetCosine = Math.cos(targetAxis);
        final double targetSine = Math.sin(targetAxis);
        return correction(
                targetCosine * scaleX * sourceCosine
                        + targetSine * scaleY * sourceSine,
                targetCosine * scaleX * sourceSine
                        - targetSine * scaleY * sourceCosine,
                0,
                targetSine * scaleX * sourceCosine
                        - targetCosine * scaleY * sourceSine,
                targetSine * scaleX * sourceSine
                        + targetCosine * scaleY * sourceCosine,
                0);
    }

    private static AffineTransform2D correction(
            final double m00,
            final double m01,
            final double m02,
            final double m10,
            final double m11,
            final double m12) {
        return new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                m00, m01, m02,
                m10, m11, m12);
    }
}
