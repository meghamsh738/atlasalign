package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ManualLocalWarpReviewTest {

    private static final double TOLERANCE = 1e-7;

    @Test
    void exactPlaneFitAppliesAtlasOnlyWarpAndPreservesGlobalDecisions() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        addMildWarpLandmarks(session, basis);
        final AffineTransform2D globalBefore = session.state()
                .effectiveAtlasToPreview();
        final AtlasPlaneTilt tiltBefore = session.state().content()
                .atlasPlaneTilt();
        final AtlasOrientation orientationBefore = session.state().content()
                .orientation();
        final Point2D probe = new Point2D(22, 24);
        final Point2D globallyMapped = globalBefore.apply(probe);

        session.apply(new ReviewEdit.FitActiveLandmarksLocalWarp());

        assertTrue(session.state().content().localWarp().isPresent());
        assertEquals(globalBefore, session.state().effectiveAtlasToPreview());
        assertEquals(tiltBefore, session.state().content().atlasPlaneTilt());
        assertEquals(orientationBefore, session.state().content().orientation());
        assertNotEquals(globallyMapped, session.state().mapAtlasToPreview(probe));
        assertPoint(probe, session.state().mapPreviewToAtlas(
                session.state().mapAtlasToPreview(probe)));
        assertEquals(4, session.state().content().activeFitLandmarks().size());
        assertEquals(1, session.state().content().activeCheckLandmarks().size());
        assertTrue(Double.isFinite(session.state()
                .activeCheckLandmarkRmsPixels()));
        assertEquals(ReviewOperation.FIT_ACTIVE_LANDMARKS_LOCAL_WARP,
                session.auditTrail().get(5).operation());
    }

    @Test
    void undoRedoAndAcceptanceRetainTheExactWarpIdentity() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        addMildWarpLandmarks(session, basis);
        session.apply(new ReviewEdit.FitActiveLandmarksLocalWarp());
        final ConstrainedLocalWarp2D fitted = session.state().content()
                .localWarp().orElseThrow();

        assertTrue(session.undo());
        assertTrue(session.state().content().localWarp().isEmpty());
        assertTrue(session.redo());
        assertEquals(fitted, session.state().content().localWarp()
                .orElseThrow());

        final AcceptedAlignmentSnapshot accepted = session.accept(
                ReviewTestFixtures.verifier(basis), true);
        assertEquals(fitted, accepted.localWarp().orElseThrow());
        assertEquals(4, accepted.activeLandmarks().stream()
                .filter(pair -> pair.role() == LandmarkRole.FIT).count());
        assertEquals(1, accepted.activeLandmarks().stream()
                .filter(pair -> pair.role() == LandmarkRole.CHECK).count());
    }

    @Test
    void changingGlobalPlaneOrLandmarksInvalidatesTheLocalWarp() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        addMildWarpLandmarks(session, basis);
        session.apply(new ReviewEdit.FitActiveLandmarksLocalWarp());

        session.apply(new ReviewEdit.Translate(1, 0));
        assertTrue(session.state().content().localWarp().isEmpty());
        assertTrue(session.undo());
        assertTrue(session.state().content().localWarp().isPresent());

        session.apply(new ReviewEdit.MoveLandmarkPreviewPoint(
                "fit-0", new Point2D(12.5, 11.5)));
        assertTrue(session.state().content().localWarp().isEmpty());
        assertTrue(session.undo());
        assertTrue(session.state().content().localWarp().isPresent());

        session.apply(new ReviewEdit.SetAtlasPlaneTilt(
                new AtlasPlaneTilt(1, 0)));
        assertTrue(session.state().content().localWarp().isEmpty());
        assertTrue(session.state().content().activeLandmarks().isEmpty());
    }

    @Test
    void localWarpEvidenceIsInformationalAndCannotPromoteCategory() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ConfidenceEvidenceCategory before = new ReviewConfidenceEvaluator()
                .evaluate(session.state()).category();
        addMildWarpLandmarks(session, basis);
        session.apply(new ReviewEdit.FitActiveLandmarksLocalWarp());

        final ReviewConfidenceReport report = new ReviewConfidenceEvaluator()
                .evaluate(session.state());
        assertEquals(before, report.category());
        final ConfidenceEvidence evidence = report.evidence().stream()
                .filter(item -> item.metric()
                        == ConfidenceMetric.LOCAL_WARP_PLAUSIBILITY)
                .findFirst().orElseThrow();
        assertEquals(ConfidenceEvidenceStatus.INFORMATIONAL,
                evidence.status());
        assertTrue(evidence.explanation().contains("never increases"));
        assertFalse(evidence.explanation().contains("%"));
    }

    @Test
    void publiclyAssembledStaleWarpContentIsRejected() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        addMildWarpLandmarks(session, basis);
        session.apply(new ReviewEdit.FitActiveLandmarksLocalWarp());
        final AlignmentReviewContent fitted = session.state().content();
        final AlignmentReviewContent stale = new AlignmentReviewContent(
                fitted.coronalLevel(), fitted.atlasPlaneTilt(),
                fitted.workflowMode(), fitted.orientation(),
                fitted.observedHemisphere(),
                new org.atlasalign.core.AffineTransform2D(
                        org.atlasalign.core.CoordinateSpace2D.PREVIEW_PIXEL,
                        org.atlasalign.core.CoordinateSpace2D.PREVIEW_PIXEL,
                        1, 0, 1, 0, 1, 0),
                fitted.landmarks(), fitted.localWarp());

        assertThrows(IllegalArgumentException.class,
                () -> new AlignmentReviewState(basis, stale, 7));
    }

    private static void addMildWarpLandmarks(
            final AlignmentReviewSession session,
            final AlignmentReviewBasis basis) {
        final List<Point2D> atlas = List.of(
                new Point2D(10, 10), new Point2D(80, 10),
                new Point2D(80, 60), new Point2D(10, 60));
        final List<Point2D> displacement = List.of(
                new Point2D(2, 1), new Point2D(-1, 2),
                new Point2D(-2, -1), new Point2D(1, -2));
        for (int index = 0; index < atlas.size(); index++) {
            final Point2D source = atlas.get(index);
            final Point2D global = session.state().effectiveAtlasToPreview()
                    .apply(source);
            final Point2D delta = displacement.get(index);
            session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                    "fit-" + index, basis.proposal().coronalLevel(),
                    AtlasPlaneTilt.CORONAL, source,
                    new Point2D(global.x() + delta.x(),
                            global.y() + delta.y()), LandmarkRole.FIT)));
        }
        final Point2D checkAtlas = new Point2D(45, 35);
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "check", basis.proposal().coronalLevel(),
                AtlasPlaneTilt.CORONAL, checkAtlas,
                new Point2D(46, 34), LandmarkRole.CHECK)));
    }

    private static void assertPoint(
            final Point2D expected,
            final Point2D actual) {
        assertEquals(expected.x(), actual.x(), TOLERANCE);
        assertEquals(expected.y(), actual.y(), TOLERANCE);
    }
}
