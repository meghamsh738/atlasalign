package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.SimilarityTransform2D;
import org.junit.jupiter.api.Test;

class AlignmentReviewSessionTest {

    private static final double TOLERANCE = 1e-9;

    @Test
    void newManualReviewStartsUprightAndResetRetainsThatStart() {
        final AffineTransform2D baseline = atlasToPreview(0.9, -0.3, 5, 0.2, 1.1, 7);
        final AlignmentReviewBasis basis = basisWithAffine(SectionGeometry.FULL, baseline);
        final AlignmentReviewSession session = AlignmentReviewSession.forNewReview(basis);
        final AffineTransform2D upright = session.state().effectiveAtlasToPreview();
        assertEquals(0, upright.m01(), TOLERANCE);
        assertEquals(0, upright.m10(), TOLERANCE);
        assertEquals(Math.hypot(0.9, 0.2), upright.m00(), TOLERANCE);
        assertEquals(Math.hypot(-0.3, 1.1), upright.m11(), TOLERANCE);
        final Point2D centre = new Point2D(227.5, 159.5);
        assertPoint(baseline.apply(centre), upright.apply(centre));
        assertEquals(baseline, session.state().basis().proposal().affine());
        assertEquals(ReviewOperation.MAKE_ATLAS_UPRIGHT, session.auditTrail().get(0).operation());
        final AlignmentReviewContent initial = session.initialContent();
        session.apply(new ReviewEdit.Translate(5, 2));
        session.resetToProposal();
        assertEquals(initial, session.state().content());
        assertEquals(ReviewOperation.RESET_TO_INITIAL_PLACEMENT,
                session.auditTrail().get(session.auditTrail().size() - 1).operation());
        assertTrue(session.auditTrail().get(session.auditTrail().size() - 1)
                .description().contains("initial upright manual review placement"));
    }

    @Test
    void explicitUprightIsUndoableAndPreservesReflectionAndPlane() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basisWithAffine(SectionGeometry.FULL,
                        atlasToPreview(0.9, -0.3, 5, 0.2, 1.1, 7)));
        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT));
        final AlignmentReviewContent before = session.state().content();
        session.apply(new ReviewEdit.MakeAtlasUpright());
        final AlignmentReviewContent after = session.state().content();
        assertEquals(before.orientation(), after.orientation());
        assertEquals(before.coronalLevel(), after.coronalLevel());
        assertEquals(before.atlasPlaneTilt(), after.atlasPlaneTilt());
        assertEquals(before.reviewedTissueSupport(), after.reviewedTissueSupport());
        assertTrue(session.state().effectiveAtlasToPreview().m00() < 0);
        assertTrue(session.state().effectiveAtlasToPreview().m11() > 0);
        assertEquals(0, session.state().effectiveAtlasToPreview().m01(), TOLERANCE);
        assertTrue(session.undo());
        assertEquals(before, session.state().content());
        assertTrue(session.redo());
        assertEquals(after, session.state().content());
    }

    @Test
    void automaticReviewAndCompatibilityConstructorKeepOriginalPlacement() {
        final AlignmentReviewBasis automatic = ReviewTestFixtures.automaticBasis(
                SectionGeometry.FULL, ReviewTestFixtures.coronalOuv(287),
                ReviewTestFixtures.coronalOuv(287));
        assertEquals(automatic.initialContent(),
                AlignmentReviewSession.forNewReview(automatic).state().content());
        final AlignmentReviewBasis manual = basisWithAffine(SectionGeometry.FULL,
                atlasToPreview(0.9, -0.3, 5, 0.2, 1.1, 7));
        assertEquals(manual.initialContent(), new AlignmentReviewSession(manual).state().content());
    }

    @Test
    void initialContentIsConstructedOnceAndReusedByReset() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewTestFixtures.basis(SectionGeometry.FULL));
        final AlignmentReviewContent initial = session.initialContent();

        assertSame(initial, session.state().content());
        assertTrue(session.isAtInitialContent());
        session.apply(new ReviewEdit.Translate(1, 0));
        assertFalse(session.isAtInitialContent());

        session.resetToProposal();

        assertSame(initial, session.state().content(),
                "Reset must reuse the cached proposal instead of rebuilding tissue support");
        assertTrue(session.isAtInitialContent());
    }

    @Test
    void landmarkBatchIsAtomicAndOneUndoRemovesEveryPair() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewTestFixtures.basis(SectionGeometry.FULL));
        final LandmarkPair first = landmark(session, "DG-handle-1", 10, 12);
        final LandmarkPair second = landmark(session, "DG-handle-2", 20, 22);

        session.apply(new ReviewEdit.AddLandmarks(List.of(first, second)));

        assertEquals(List.of(first, second),
                session.state().content().activeLandmarks());
        assertEquals(1, session.state().contentRevision());
        assertEquals(1, session.auditTrail().size());
        assertEquals(ReviewOperation.ADD_LANDMARKS,
                session.auditTrail().get(0).operation());
        assertTrue(session.undo());
        assertTrue(session.state().content().activeLandmarks().isEmpty());
        assertEquals(2, session.state().contentRevision());
    }

    @Test
    void landmarkBatchRejectsDuplicateIdentifiersWithoutPartialEdit() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewTestFixtures.basis(SectionGeometry.FULL));
        final LandmarkPair first = landmark(session, "duplicate", 10, 12);
        final LandmarkPair second = landmark(session, "duplicate", 20, 22);

        assertThrows(IllegalArgumentException.class,
                () -> session.apply(new ReviewEdit.AddLandmarks(
                        List.of(first, second))));

        assertTrue(session.state().content().activeLandmarks().isEmpty());
        assertEquals(0, session.state().contentRevision());
        assertTrue(session.auditTrail().isEmpty());
    }

    private static LandmarkPair landmark(
            final AlignmentReviewSession session,
            final String id,
            final double atlasX,
            final double previewX) {
        return new LandmarkPair(
                id,
                session.state().content().coronalLevel(),
                session.state().content().atlasPlaneTilt(),
                new Point2D(atlasX, 10),
                new Point2D(previewX, 12));
    }

    @Test
    void landmarksDeactivateWhenTheAtlasTiltChangesAndReactivateOnReturn() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewTestFixtures.basis(SectionGeometry.FULL));
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "tilt-bound",
                session.state().content().coronalLevel(),
                session.state().content().atlasPlaneTilt(),
                new Point2D(10, 10),
                new Point2D(12, 12))));
        assertEquals(1, session.state().content().activeLandmarks().size());

        session.apply(new ReviewEdit.SetAtlasPlaneTilt(
                new AtlasPlaneTilt(1, 0)));
        assertTrue(session.state().content().activeLandmarks().isEmpty());

        session.apply(new ReviewEdit.SetAtlasPlaneTilt(AtlasPlaneTilt.CORONAL));
        assertEquals(1, session.state().content().activeLandmarks().size());
    }

    @Test
    void startsPendingFromImmutableAffineProposal() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(SectionGeometry.FULL);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);

        assertPoint(
                basis.proposal().affine().apply(
                        new Point2D(13.5, 27.25)),
                session.state().effectiveAtlasToPreview().apply(
                        new Point2D(13.5, 27.25)));
        assertEquals(0, session.state().contentRevision());
        assertTrue(session.acceptedAlignment().isEmpty());
        assertTrue(session.auditTrail().isEmpty());
        assertFalse(session.canUndo());
        assertFalse(session.canRedo());
    }

    @Test
    void appliesPreviewEditsAroundExplicitPixelCenterPivot() {
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.FULL));
        final Point2D pivot = new Point2D(10, 20);
        session.apply(new ReviewEdit.Rotate(Math.PI / 2, pivot));
        assertPoint(
                pivot,
                session.state().effectiveAtlasToPreview().apply(pivot));
        assertPoint(
                new Point2D(10, 21),
                session.state().effectiveAtlasToPreview().apply(
                        new Point2D(11, 20)));

        session.apply(new ReviewEdit.Scale(2, pivot));
        assertPoint(
                pivot,
                session.state().effectiveAtlasToPreview().apply(pivot));
        assertPoint(
                new Point2D(10, 22),
                session.state().effectiveAtlasToPreview().apply(
                        new Point2D(11, 20)));

        session.apply(new ReviewEdit.Translate(3, -4));
        assertPoint(
                new Point2D(13, 16),
                session.state().effectiveAtlasToPreview().apply(pivot));
    }

    @Test
    void appliesIndependentAxisScaleAroundExplicitRotatedAxes() {
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.FULL));
        final Point2D pivot = new Point2D(10, 20);

        session.apply(new ReviewEdit.Rotate(Math.PI / 2, pivot));
        session.apply(new ReviewEdit.ScaleAxes(
                2, 0.5, Math.PI / 2, pivot));

        assertPoint(pivot,
                session.state().effectiveAtlasToPreview().apply(pivot));
        assertPoint(new Point2D(10, 22),
                session.state().effectiveAtlasToPreview().apply(
                        new Point2D(11, 20)));
        assertPoint(new Point2D(9.5, 20),
                session.state().effectiveAtlasToPreview().apply(
                        new Point2D(10, 21)));
        assertEquals(2, session.state().contentRevision());
        assertEquals(ReviewOperation.SCALE,
                session.auditTrail().get(1).operation());
        final AffineTransform2D resized = session.state()
                .effectiveAtlasToPreview();

        session.undo();
        assertPoint(new Point2D(10, 21),
                session.state().effectiveAtlasToPreview().apply(
                        new Point2D(11, 20)));
        session.redo();
        assertEquals(resized,
                session.state().effectiveAtlasToPreview(),
                "undo/redo must replay the exact two-axis geometry");
    }

    @Test
    void rejectsTwoAxisScaleWhoseAxesWouldShearTheDisplayedAtlas() {
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.FULL));
        final AlignmentReviewContent before = session.state().content();

        final IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> session.apply(new ReviewEdit.ScaleAxes(
                        1.2, 0.8, Math.PI / 4,
                        new Point2D(10, 20))));

        assertTrue(rejected.getMessage().contains("no shear"));
        assertEquals(before, session.state().content());
        assertTrue(session.auditTrail().isEmpty(),
                "a reducer-level safety rejection must create no revision");
    }

    @Test
    void composesManualEditsAfterANonIdentityBaseline() {
        final AffineTransform2D baseline =
                atlasToPreview(2, 0, 5, 0, 2, 7);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        basisWithAffine(
                                SectionGeometry.FULL, baseline));
        final Point2D pivot = new Point2D(10, 20);

        session.apply(new ReviewEdit.Rotate(Math.PI / 2, pivot));

        assertPoint(
                pivot,
                session.state().effectiveAtlasToPreview().apply(
                        new Point2D(2.5, 6.5)));
        assertPoint(
                new Point2D(10, 21),
                session.state().effectiveAtlasToPreview().apply(
                        new Point2D(3, 6.5)));
    }

    @Test
    void reflectionIsExplicitAndPreservesAtlasToPreviewDirection() {
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.IMAGE_LEFT_HALF));

        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT));

        assertEquals(
                ObservedAnatomicalHemisphere.UNSURE,
                session.state().observedAnatomicalHemisphere());
        session.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.RIGHT));

        assertPoint(
                new Point2D(455, 0),
                session.state().effectiveAtlasToPreview().apply(
                        new Point2D(0, 0)));
        assertPoint(
                new Point2D(0, 0),
                session.state().effectiveAtlasToPreview().apply(
                        new Point2D(455, 0)));
        assertEquals(
                org.atlasalign.core.CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                session.state().effectiveAtlasToPreview().sourceSpace());
        assertEquals(
                org.atlasalign.core.CoordinateSpace2D.PREVIEW_PIXEL,
                session.state().effectiveAtlasToPreview()
                        .destinationSpace());
        assertEquals(
                ObservedAnatomicalHemisphere.RIGHT,
                session.state().observedAnatomicalHemisphere());
        final Point2D point = new Point2D(123.5, 76.25);
        assertPoint(
                point,
                session.state().effectiveAtlasToPreview().inverse()
                        .apply(session.state().effectiveAtlasToPreview()
                                .apply(point)));
    }

    @Test
    void reflectionPrecedesANonIdentityBaseline() {
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        basisWithAffine(
                                SectionGeometry.IMAGE_LEFT_HALF,
                                atlasToPreview(
                                        2, 0, 5,
                                        0, 3, 7)));

        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT));

        assertPoint(
                new Point2D(915, 7),
                session.state().effectiveAtlasToPreview().apply(
                        new Point2D(0, 0)));
        assertPoint(
                new Point2D(5, 7),
                session.state().effectiveAtlasToPreview().apply(
                        new Point2D(455, 0)));
    }

    @Test
    void halfSectionRequiresASeparateConsistentLateralityDecision() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(
                        SectionGeometry.IMAGE_LEFT_HALF);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);

        assertEquals(
                ObservedAnatomicalHemisphere.UNSURE,
                session.state().observedAnatomicalHemisphere());
        assertBlock(
                ReviewAcceptanceBlockReason.LATERALITY_UNSURE,
                () -> session.accept(
                        ReviewTestFixtures.verifier(basis), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> session.apply(
                        new ReviewEdit.SetObservedHemisphere(
                                ObservedAnatomicalHemisphere.RIGHT)));
        assertEquals(
                ObservedAnatomicalHemisphere.UNSURE,
                session.state().observedAnatomicalHemisphere());

        session.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT));
        assertEquals(
                ObservedAnatomicalHemisphere.LEFT,
                session.state().observedAnatomicalHemisphere());

        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT));
        assertEquals(
                ObservedAnatomicalHemisphere.UNSURE,
                session.state().observedAnatomicalHemisphere());
    }

    @Test
    void undoRedoResetRestoreStoredSnapshotsWithoutDrift() {
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.FULL));
        final AlignmentReviewContent initial =
                session.state().content();
        session.apply(new ReviewEdit.Translate(4, 5));
        final AlignmentReviewContent translated =
                session.state().content();
        session.apply(new ReviewEdit.SetCoronalLevel(
                new AllenCoronalLevel(241)));

        assertTrue(session.undo());
        assertEquals(translated, session.state().content());
        assertTrue(session.redo());
        assertEquals(
                241,
                session.state().content().coronalLevel()
                        .zeroBasedAnteriorPosteriorIndex());

        session.resetToProposal();
        assertEquals(initial, session.state().content());
        assertTrue(session.undo());
        assertEquals(
                241,
                session.state().content().coronalLevel()
                        .zeroBasedAnteriorPosteriorIndex());
        assertEquals(
                List.of(
                        ReviewOperation.TRANSLATE,
                        ReviewOperation.SET_CORONAL_LEVEL,
                        ReviewOperation.UNDO,
                        ReviewOperation.REDO,
                        ReviewOperation.RESET_TO_PROPOSAL,
                        ReviewOperation.UNDO),
                session.auditTrail().stream()
                        .map(ReviewAuditEvent::operation)
                        .toList());
    }

    @Test
    void newEditAfterUndoTruncatesRedoBranch() {
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.FULL));
        session.apply(new ReviewEdit.Translate(1, 0));
        session.apply(new ReviewEdit.Translate(2, 0));
        assertTrue(session.undo());
        session.apply(new ReviewEdit.Translate(3, 0));

        assertFalse(session.canRedo());
        assertFalse(session.redo());
        assertEquals(
                4,
                session.state().effectiveAtlasToPreview().m02(),
                TOLERANCE);
    }

    @Test
    void landmarksRemainBoundToTheirOriginalAtlasLevel() {
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.FULL));
        final LandmarkPair landmark = new LandmarkPair(
                "L1",
                new AllenCoronalLevel(240),
                new Point2D(5, 6),
                new Point2D(8, 10));
        session.apply(new ReviewEdit.AddLandmark(landmark));

        assertEquals(1, session.state().content()
                .activeLandmarks().size());
        assertEquals(5, session.state()
                .activeLandmarkRmsPixels(), TOLERANCE);
        session.apply(new ReviewEdit.SetCoronalLevel(
                new AllenCoronalLevel(241)));
        assertTrue(session.state().content()
                .activeLandmarks().isEmpty());
        assertTrue(Double.isNaN(
                session.state().activeLandmarkRmsPixels()));
        session.apply(new ReviewEdit.SetCoronalLevel(
                new AllenCoronalLevel(240)));
        assertEquals(List.of(landmark), session.state().content()
                .activeLandmarks());
    }

    @Test
    void fittingActiveLandmarksAppliesAnUndoablePreviewSimilarity() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(SectionGeometry.FULL);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final Point2D firstAtlas = new Point2D(12, 18);
        final Point2D secondAtlas = new Point2D(42, 27);
        final SimilarityTransform2D expectedCorrection =
                new SimilarityTransform2D(
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1.08, Math.toRadians(6), 3.5, -2.25);
        final Point2D firstPreview = expectedCorrection.apply(
                session.state().effectiveAtlasToPreview().apply(
                        firstAtlas));
        final Point2D secondPreview = expectedCorrection.apply(
                session.state().effectiveAtlasToPreview().apply(
                        secondAtlas));

        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "L1", basis.proposal().coronalLevel(), firstAtlas,
                firstPreview)));
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "L2", basis.proposal().coronalLevel(), secondAtlas,
                secondPreview)));
        final AffineTransform2D beforeFit = session.state().content()
                .manualPreviewAdjustment();

        session.apply(new ReviewEdit.FitActiveLandmarks());

        assertPoint(firstPreview, session.state()
                .effectiveAtlasToPreview().apply(firstAtlas));
        assertPoint(secondPreview, session.state()
                .effectiveAtlasToPreview().apply(secondAtlas));
        assertEquals(ReviewOperation.FIT_ACTIVE_LANDMARKS,
                session.auditTrail().get(2).operation());
        assertEquals(0, session.state().activeLandmarkRmsPixels(),
                TOLERANCE);

        assertTrue(session.undo());
        assertEquals(beforeFit, session.state().content()
                .manualPreviewAdjustment());
        assertTrue(session.redo());
        assertPoint(firstPreview, session.state()
                .effectiveAtlasToPreview().apply(firstAtlas));
    }

    @Test
    void fittingThreeLandmarksAppliesAnUndoableGlobalAffineWithoutChangingPlaneOrOrientation() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(SectionGeometry.FULL);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final AffineTransform2D correction = new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1.10, 0.08, 3.0,
                -0.04, 0.96, 2.5);
        final List<Point2D> atlasPoints = List.of(
                new Point2D(15, 15),
                new Point2D(60, 20),
                new Point2D(24, 55),
                new Point2D(70, 48));
        for (int index = 0; index < atlasPoints.size(); index++) {
            final Point2D atlas = atlasPoints.get(index);
            session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                    "affine-" + index, basis.proposal().coronalLevel(), atlas,
                    correction.apply(session.state().effectiveAtlasToPreview()
                            .apply(atlas)))));
        }
        final AtlasOrientation orientation = session.state().content()
                .orientation();
        final AtlasPlaneTilt tilt = session.state().content().atlasPlaneTilt();
        final AffineTransform2D beforeFit = session.state().content()
                .manualPreviewAdjustment();

        session.apply(new ReviewEdit.FitActiveLandmarksAffine());

        for (final LandmarkPair pair : session.state().content()
                .activeLandmarks()) {
            assertPoint(pair.previewPoint(), session.state()
                    .effectiveAtlasToPreview().apply(pair.atlasPoint()));
        }
        assertEquals(ReviewOperation.FIT_ACTIVE_LANDMARKS_AFFINE,
                session.auditTrail().get(4).operation());
        assertEquals(orientation, session.state().content().orientation());
        assertEquals(tilt, session.state().content().atlasPlaneTilt());
        assertEquals(0, session.state().activeLandmarkRmsPixels(), TOLERANCE);

        assertTrue(session.undo());
        assertEquals(beforeFit, session.state().content()
                .manualPreviewAdjustment());
        assertTrue(session.redo());
        assertEquals(0, session.state().activeLandmarkRmsPixels(), TOLERANCE);
    }

    @Test
    void checkLandmarksAreExcludedFromFitsAndRoleChangeIsUndoable() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final SimilarityTransform2D correction = new SimilarityTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1.0, 0, 4, -3);
        final Point2D first = new Point2D(15, 15);
        final Point2D second = new Point2D(60, 45);
        final Point2D check = new Point2D(30, 55);
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "fit-1", basis.proposal().coronalLevel(), first,
                correction.apply(session.state().effectiveAtlasToPreview()
                        .apply(first)))));
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "fit-2", basis.proposal().coronalLevel(), second,
                correction.apply(session.state().effectiveAtlasToPreview()
                        .apply(second)))));
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "check", basis.proposal().coronalLevel(),
                AtlasPlaneTilt.CORONAL, check, new Point2D(2, 2),
                LandmarkRole.CHECK)));

        session.apply(new ReviewEdit.FitActiveLandmarks());

        assertEquals(2, session.state().content().activeFitLandmarks().size());
        assertEquals(1, session.state().content().activeCheckLandmarks().size());
        assertEquals(0, session.state().activeFitLandmarkRmsPixels(), TOLERANCE);
        assertTrue(session.state().activeCheckLandmarkRmsPixels() > 10);
        session.apply(new ReviewEdit.SetLandmarkRole(
                "check", LandmarkRole.FIT));
        assertEquals(3, session.state().content().activeFitLandmarks().size());
        assertTrue(session.undo());
        assertEquals(LandmarkRole.CHECK, session.state().content()
                .activeLandmarks().stream()
                .filter(pair -> pair.id().equals("check"))
                .findFirst().orElseThrow().role());
    }

    @Test
    void fittedLandmarkCannotLaterImpersonateHeldOutCheckEvidence() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final Point2D first = new Point2D(15, 15);
        final Point2D second = new Point2D(60, 45);
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "fit-1", basis.proposal().coronalLevel(), first,
                new Point2D(19, 12))));
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "fit-2", basis.proposal().coronalLevel(), second,
                new Point2D(64, 42))));
        session.apply(new ReviewEdit.FitActiveLandmarks());
        final AlignmentReviewState fitted = session.state();
        final List<ReviewAuditEvent> audit = session.auditTrail();

        assertThrows(IllegalArgumentException.class,
                () -> session.apply(new ReviewEdit.SetLandmarkRole(
                        "fit-1", LandmarkRole.CHECK)));

        assertEquals(fitted, session.state());
        assertEquals(audit, session.auditTrail());
        assertTrue(session.undo());
        session.apply(new ReviewEdit.SetLandmarkRole(
                "fit-1", LandmarkRole.CHECK));
        assertEquals(LandmarkRole.CHECK, session.state().content()
                .activeLandmarks().stream()
                .filter(pair -> pair.id().equals("fit-1"))
                .findFirst().orElseThrow().role());
    }

    @Test
    void fittingLandmarksPreservesExplicitReflectionAndInvalidatesAcceptance() {
        final AlignmentReviewBasis basis = basisWithAffine(
                SectionGeometry.IMAGE_LEFT_HALF,
                atlasToPreview(0.15, 0, 10, 0, 0.15, 5));
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT));
        session.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.RIGHT));
        session.apply(new ReviewEdit.Translate(1.5, -0.75));
        final Point2D firstAtlas = new Point2D(100, 80);
        final Point2D secondAtlas = new Point2D(150, 120);
        final SimilarityTransform2D correction =
                new SimilarityTransform2D(
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1.01, Math.toRadians(2), 0.5, -0.25);
        final Point2D firstPreview = correction.apply(session.state()
                .effectiveAtlasToPreview().apply(firstAtlas));
        final Point2D secondPreview = correction.apply(session.state()
                .effectiveAtlasToPreview().apply(secondAtlas));
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "L1", basis.proposal().coronalLevel(), firstAtlas,
                firstPreview)));
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "L2", basis.proposal().coronalLevel(), secondAtlas,
                secondPreview)));
        final AffineTransform2D beforeFit = session.state()
                .effectiveAtlasToPreview();
        session.accept(ReviewTestFixtures.verifier(basis), true);

        session.apply(new ReviewEdit.FitActiveLandmarks());

        assertTrue(session.acceptedAlignment().isEmpty());
        assertTrue(session.state().effectiveAtlasToPreview().determinant() < 0);
        assertEquals(ObservedAnatomicalHemisphere.RIGHT,
                session.state().observedAnatomicalHemisphere());
        assertPoint(firstPreview, session.state()
                .effectiveAtlasToPreview().apply(firstAtlas));
        assertPoint(secondPreview, session.state()
                .effectiveAtlasToPreview().apply(secondAtlas));

        assertTrue(session.undo());
        assertEquals(beforeFit, session.state().effectiveAtlasToPreview());
        assertTrue(session.redo());
        assertPoint(firstPreview, session.state()
                .effectiveAtlasToPreview().apply(firstAtlas));
    }

    @Test
    void landmarkDomainsAndDegenerateFitFailWithoutMutatingHistory() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(SectionGeometry.FULL);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final AlignmentReviewContent initial = session.state().content();
        assertThrows(IllegalArgumentException.class, () -> session.apply(
                new ReviewEdit.AddLandmark(new LandmarkPair(
                        "outside-atlas", basis.proposal().coronalLevel(),
                        new Point2D(456, 20), new Point2D(20, 20)))));
        assertThrows(IllegalArgumentException.class, () -> session.apply(
                new ReviewEdit.AddLandmark(new LandmarkPair(
                        "outside-preview", basis.proposal().coronalLevel(),
                        new Point2D(20, 20), new Point2D(100, 20)))));
        assertEquals(initial, session.state().content());
        assertTrue(session.auditTrail().isEmpty());

        final Point2D atlas = new Point2D(30, 30);
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "L1", basis.proposal().coronalLevel(), atlas,
                new Point2D(20, 20))));
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "L2", basis.proposal().coronalLevel(), atlas,
                new Point2D(40, 40))));
        session.apply(new ReviewEdit.Translate(1, 0));
        assertTrue(session.undo());
        assertTrue(session.canRedo());
        session.accept(ReviewTestFixtures.verifier(basis), true);
        final AlignmentReviewState before = session.state();
        final List<ReviewAuditEvent> auditBefore = session.auditTrail();

        assertThrows(IllegalArgumentException.class,
                () -> session.apply(new ReviewEdit.FitActiveLandmarks()));

        assertEquals(before, session.state());
        assertEquals(auditBefore, session.auditTrail());
        assertTrue(session.canRedo());
        assertTrue(session.acceptedAlignment().isPresent());
    }

    @Test
    void explicitPreviewDimensionsProtectDownsampledReviewDomains() {
        final AlignmentReviewBasis sourceSized =
                ReviewTestFixtures.basis(SectionGeometry.FULL);
        final AlignmentReviewBasis downsampled = new AlignmentReviewBasis(
                sourceSized.proposal(),
                sourceSized.initialPlaneProposal(),
                sourceSized.segmentation(),
                sourceSized.sourceSnapshot(),
                sourceSized.atlas(),
                sourceSized.syntheticPixelPolicy(),
                sourceSized.inferencePreparationProvenance(),
                new ReviewPreviewDimensions(50, 40));
        final AlignmentReviewSession session =
                new AlignmentReviewSession(downsampled);

        assertThrows(IllegalArgumentException.class, () -> session.apply(
                new ReviewEdit.AddLandmark(new LandmarkPair(
                        "outside-downsampled-preview",
                        downsampled.proposal().coronalLevel(),
                        new Point2D(20, 20), new Point2D(50, 20)))));
        session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                "edge", downsampled.proposal().coronalLevel(),
                new Point2D(20, 20), new Point2D(49, 39))));
    }

    @Test
    void acceptanceIsExplicitAndEveryHistoryActionInvalidatesIt() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(SectionGeometry.FULL);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);

        final AcceptedAlignmentSnapshot accepted = session.accept(
                ReviewTestFixtures.verifier(basis), true);
        assertEquals(0, accepted.contentRevision());
        assertEquals(basis.sourceSnapshot(), accepted.verifiedSource());
        assertEquals(basis.atlas(), accepted.verifiedAtlas());
        assertTrue(accepted.warningsAcknowledged());
        assertEquals(1, accepted.acceptanceAuditSequence());
        assertTrue(session.acceptedAlignment().isPresent());

        session.apply(new ReviewEdit.Translate(1, 0));
        assertTrue(session.acceptedAlignment().isEmpty());
        session.accept(ReviewTestFixtures.verifier(basis), true);
        assertTrue(session.undo());
        assertTrue(session.acceptedAlignment().isEmpty());
        assertTrue(session.redo());
        assertTrue(session.acceptedAlignment().isEmpty());
    }

    @Test
    void joinedCoarsePlacementRetainsReviewerControlledWarpProvenance() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(SectionGeometry.FULL);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        session.apply(new ReviewEdit.Translate(7, -3));

        final AcceptedAlignmentSnapshot accepted = session.accept(
                ReviewTestFixtures.verifier(basis), true);

        assertTrue(accepted.joinedManualPlacementApplied());
        assertTrue(accepted.hemisphereWarp().isEmpty());
        assertEquals("REVIEWER_CONTROLLED_MANUAL_WARP",
                accepted.outputMethodLabel());
        assertEquals(session.state().preOutlineAtlasToPreview(),
                accepted.preOutlineAtlasToPreview());
    }

    @Test
    void failedReacceptanceClearsThePreviousAcceptedSnapshot() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(SectionGeometry.FULL);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        session.accept(ReviewTestFixtures.verifier(basis), true);

        assertBlock(
                ReviewAcceptanceBlockReason.SOURCE_CHANGED,
                () -> session.accept(
                        () -> new ReviewAcceptanceVerification(
                                ReviewTestFixtures.source("b"),
                                basis.atlas()),
                        true));

        assertTrue(session.acceptedAlignment().isEmpty());
    }

    @Test
    void acceptanceFailsClosedOnFreshVerificationProblems() {
        final AlignmentReviewBasis halfBasis =
                ReviewTestFixtures.basis(
                        SectionGeometry.IMAGE_RIGHT_HALF);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(halfBasis);

        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.UNCONFIRMED_PROVISIONAL_DIRECT));
        assertBlock(
                ReviewAcceptanceBlockReason.ORIENTATION_UNCONFIRMED,
                () -> session.accept(
                        ReviewTestFixtures.verifier(halfBasis),
                        true));
        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT));
        session.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.RIGHT));

        assertBlock(
                ReviewAcceptanceBlockReason.SOURCE_CHANGED,
                () -> session.accept(
                        () -> new ReviewAcceptanceVerification(
                                ReviewTestFixtures.source("b"),
                                halfBasis.atlas()),
                        true));
        assertBlock(
                ReviewAcceptanceBlockReason.VERIFICATION_FAILED,
                () -> session.accept(
                        () -> {
                            throw new IllegalStateException(
                                    "atlas checksum mismatch");
                        },
                        true));
        final AtlasReviewProvenance changedAtlas =
                new AtlasReviewProvenance(
                        AllenCoronalLevel.ATLAS_ID,
                        AllenCoronalLevel.ATLAS_VERSION,
                        AtlasReviewProvenance.ALLEN_CORONAL_WIDTH,
                        AtlasReviewProvenance.ALLEN_CORONAL_HEIGHT,
                        List.of(
                                new AtlasAssetVerification(
                                        "template",
                                        10,
                                        "4".repeat(64)),
                                new AtlasAssetVerification(
                                        "annotation",
                                        20,
                                        "2".repeat(64)),
                                new AtlasAssetVerification(
                                        "ontology",
                                        30,
                                        "3".repeat(64))));
        assertBlock(
                ReviewAcceptanceBlockReason.ATLAS_IDENTITY_CHANGED,
                () -> session.accept(
                        () -> new ReviewAcceptanceVerification(
                                halfBasis.sourceSnapshot(),
                                changedAtlas),
                        true));
        assertTrue(session.acceptedAlignment().isEmpty());
        assertEquals(4, session.auditTrail().stream()
                .filter(event -> event.operation()
                        == ReviewOperation.ACCEPT)
                .count());
        assertTrue(session.auditTrail().stream()
                .filter(event -> event.operation()
                        == ReviewOperation.ACCEPT)
                .allMatch(event ->
                        event.acceptanceAudit().isPresent()
                        && !event.acceptanceAudit()
                                .orElseThrow().succeeded()));
    }

    @Test
    void unverifiedSyntheticPixelPolicyCannotBeAccepted() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(
                        SectionGeometry.FULL,
                        0.88,
                        SyntheticPixelReviewPolicy.UNVERIFIED);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);

        assertBlock(
                ReviewAcceptanceBlockReason
                        .SYNTHETIC_PROVENANCE_UNVERIFIED,
                () -> session.accept(
                        ReviewTestFixtures.verifier(basis),
                        true));
        final ReviewAcceptanceAudit audit =
                session.auditTrail().get(0)
                        .acceptanceAudit().orElseThrow();
        assertFalse(audit.succeeded());
        assertEquals(
                ReviewAcceptanceBlockReason
                        .SYNTHETIC_PROVENANCE_UNVERIFIED,
                audit.failureReason().orElseThrow());
    }

    @Test
    void warningsRequireSeparateAcknowledgement() {
        final AlignmentReviewBasis damagedBasis =
                ReviewTestFixtures.basis(
                        SectionGeometry.PARTIAL_OR_DAMAGED);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(damagedBasis);
        assertBlock(
                ReviewAcceptanceBlockReason.LATERALITY_UNSURE,
                () -> session.accept(
                        ReviewTestFixtures.verifier(damagedBasis),
                        true));
        session.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT));
        assertBlock(
                ReviewAcceptanceBlockReason.WARNINGS_NOT_ACKNOWLEDGED,
                () -> session.accept(
                        ReviewTestFixtures.verifier(damagedBasis),
                        false));
        assertTrue(session.accept(
                ReviewTestFixtures.verifier(damagedBasis),
                true) != null);
    }

    @Test
    void sparseBilateralGeometryStillRequiresOrientationAndWarningReview() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(
                        SectionGeometry.BILATERAL_REVIEW_REQUIRED);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);

        assertEquals(
                ObservedAnatomicalHemisphere.BOTH,
                session.state().observedAnatomicalHemisphere());
        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.UNCONFIRMED_PROVISIONAL_DIRECT));
        assertBlock(
                ReviewAcceptanceBlockReason.ORIENTATION_UNCONFIRMED,
                () -> session.accept(
                        ReviewTestFixtures.verifier(basis),
                        true));
        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT));
        assertBlock(
                ReviewAcceptanceBlockReason.WARNINGS_NOT_ACKNOWLEDGED,
                () -> session.accept(
                        ReviewTestFixtures.verifier(basis),
                        false));
        assertTrue(session.accept(
                ReviewTestFixtures.verifier(basis),
                true) != null);
    }

    @Test
    void rejectsPubliclyConstructedLateralityMismatch() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(
                        SectionGeometry.IMAGE_LEFT_HALF);
        final AlignmentReviewContent initial =
                basis.initialContent();
        final AlignmentReviewContent inconsistent =
                new AlignmentReviewContent(
                        initial.coronalLevel(),
                        AtlasOrientation
                                .CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.RIGHT,
                        initial.manualPreviewAdjustment(),
                        initial.landmarks());

        assertThrows(
                IllegalArgumentException.class,
                () -> new AlignmentReviewState(
                        basis, inconsistent, 0));
    }

    @Test
    void rejectsUnsafeOrNoOpEditsWithoutChangingState() {
        final AlignmentReviewSession session =
                new AlignmentReviewSession(
                        ReviewTestFixtures.basis(
                                SectionGeometry.FULL));
        final AlignmentReviewContent before =
                session.state().content();

        assertThrows(
                IllegalArgumentException.class,
                () -> new ReviewEdit.Scale(0, new Point2D(0, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> session.apply(
                        new ReviewEdit.SetCoronalLevel(
                                new AllenCoronalLevel(240))));
        assertThrows(
                IllegalArgumentException.class,
                () -> session.apply(
                        new ReviewEdit.Scale(
                                100,
                                new Point2D(0, 0))));
        assertEquals(before, session.state().content());
        assertTrue(session.auditTrail().isEmpty());
    }

    @Test
    void automaticProposalRequiresExplicitManualRefinement() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewTestFixtures.automaticBasis(SectionGeometry.FULL,
                        ReviewTestFixtures.coronalOuv(287),
                        ReviewTestFixtures.coronalOuv(287)));
        final AlignmentReviewContent automatic = session.state().content();

        assertEquals(ReviewWorkflowMode.AUTOMATIC_REVIEW,
                automatic.workflowMode());
        assertThrows(IllegalStateException.class, () -> session.apply(
                new ReviewEdit.Translate(3, 0)));
        session.apply(new ReviewEdit.EnterManualRefinement());
        assertEquals(ReviewWorkflowMode.MANUAL_REFINEMENT,
                session.state().content().workflowMode());
        session.apply(new ReviewEdit.Translate(3, 0));
        assertEquals(automatic.coronalLevel(),
                session.state().basis().initialPlaneProposal().orElseThrow()
                        .coronalLevel());
    }

    @Test
    void guidedCandidateIsAuditedWithoutChangingImmutableAutomaticEvidence() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.automaticBasis(SectionGeometry.FULL,
                        ReviewTestFixtures.coronalOuv(287),
                        ReviewTestFixtures.coronalOuv(287));
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final var automaticProposal = basis.initialPlaneProposal();
        final var automaticInitialization = basis.automaticPlaneInitialization();
        session.apply(new ReviewEdit.EnterManualRefinement());
        final AffineTransform2D outlineAdjustment = new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1.15, 0, 4,
                0, 0.85, 3);
        final AffineTransform2D candidateAtlasToPreview =
                basis.proposal().affine().andThen(outlineAdjustment);

        session.apply(new ReviewEdit.ApplyGuidedManualCandidate(
                new AllenCoronalLevel(293), new AtlasPlaneTilt(2, -1),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.BOTH,
                candidateAtlasToPreview, "OUTLINE_BOUNDS_AFFINE_V1",
                "manual-candidate-L293-S+2.0-H-1.0",
                new AllenCoronalLevel(287), AtlasPlaneTilt.CORONAL,
                0.032, 0.041, 0.026, List.of("outline", "dg-left"),
                basis.sourceSnapshot().pixelSha256(),
                basis.atlas().identitySha256()));

        assertEquals(293, session.state().content().coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
        assertEquals(new AtlasPlaneTilt(2, -1),
                session.state().content().atlasPlaneTilt());
        assertEquals(outlineAdjustment,
                session.state().content().manualPreviewAdjustment());
        assertEquals(candidateAtlasToPreview,
                session.state().effectiveAtlasToPreview());
        assertEquals(AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                session.state().content().orientation());
        assertEquals(automaticProposal,
                session.state().basis().initialPlaneProposal());
        assertEquals(automaticInitialization,
                session.state().basis().automaticPlaneInitialization());
        final ReviewAuditEvent event = session.auditTrail().get(1);
        assertEquals(ReviewOperation.APPLY_GUIDED_MANUAL_CANDIDATE,
                event.operation());
        assertTrue(event.description().contains(
                "manual preview only, confidence unchanged"));
        assertTrue(event.description().contains("contours=[outline, dg-left]"));
        assertTrue(event.description().contains(
                "outlineFitMethod=OUTLINE_BOUNDS_AFFINE_V1"));
        assertTrue(event.description().contains(
                "candidateTransformSha256="));

        assertThrows(IllegalArgumentException.class, () ->
                new ReviewEdit.ApplyGuidedManualCandidate(
                        new AllenCoronalLevel(293),
                        new AtlasPlaneTilt(2, -1),
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT,
                        ObservedAnatomicalHemisphere.BOTH,
                        candidateAtlasToPreview,
                        "outline-bounds-affine-v1", "wrong-reflection",
                        new AllenCoronalLevel(287), AtlasPlaneTilt.CORONAL,
                        0.03, 0.04, 0.02, List.of("outline"),
                        basis.sourceSnapshot().pixelSha256(),
                        basis.atlas().identitySha256()));
        assertThrows(IllegalArgumentException.class, () ->
                new ReviewEdit.ApplyGuidedManualCandidate(
                        new AllenCoronalLevel(293),
                        new AtlasPlaneTilt(2, -1),
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.BOTH,
                        outlineAdjustment,
                        "outline-bounds-affine-v1", "wrong-space",
                        new AllenCoronalLevel(287), AtlasPlaneTilt.CORONAL,
                        0.03, 0.04, 0.02, List.of("outline"),
                        basis.sourceSnapshot().pixelSha256(),
                        basis.atlas().identitySha256()));

        assertThrows(IllegalArgumentException.class, () -> session.apply(
                new ReviewEdit.ApplyGuidedManualCandidate(
                        new AllenCoronalLevel(294),
                        new AtlasPlaneTilt(2, -1),
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.BOTH,
                        candidateAtlasToPreview,
                        "OUTLINE_BOUNDS_AFFINE_V1", "wrong-source",
                        new AllenCoronalLevel(287), AtlasPlaneTilt.CORONAL,
                        0.03, 0.04, 0.02, List.of("outline"),
                        "0".repeat(64), basis.atlas().identitySha256())));
        assertThrows(IllegalArgumentException.class, () -> session.apply(
                new ReviewEdit.ApplyGuidedManualCandidate(
                        new AllenCoronalLevel(294),
                        new AtlasPlaneTilt(2, -1),
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.BOTH,
                        candidateAtlasToPreview,
                        "OUTLINE_BOUNDS_AFFINE_V1", "wrong-atlas",
                        new AllenCoronalLevel(287), AtlasPlaneTilt.CORONAL,
                        0.03, 0.04, 0.02, List.of("outline"),
                        basis.sourceSnapshot().pixelSha256(),
                        "0".repeat(64))));
        assertEquals(293, session.state().content().coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
    }

    @Test
    void workflowTransitionIsUndoableAndResetRestoresAutomaticProposal() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewTestFixtures.automaticBasis(SectionGeometry.FULL,
                        ReviewTestFixtures.coronalOuv(287),
                        ReviewTestFixtures.coronalOuv(287)));

        session.apply(new ReviewEdit.EnterManualRefinement());
        assertTrue(session.undo());
        assertEquals(ReviewWorkflowMode.AUTOMATIC_REVIEW,
                session.state().content().workflowMode());
        assertTrue(session.redo());
        session.apply(new ReviewEdit.Translate(2, 0));
        session.resetToProposal();
        assertEquals(ReviewWorkflowMode.AUTOMATIC_REVIEW,
                session.state().content().workflowMode());
        assertEquals(session.state().basis().initialContent()
                        .manualPreviewAdjustment(),
                session.state().content().manualPreviewAdjustment());
    }

    private static void assertBlock(
            final ReviewAcceptanceBlockReason reason,
            final org.junit.jupiter.api.function.Executable action) {
        final ReviewAcceptanceException error = assertThrows(
                ReviewAcceptanceException.class, action);
        assertEquals(reason, error.reason());
    }

    private static void assertPoint(
            final Point2D expected,
            final Point2D actual) {
        assertEquals(expected.x(), actual.x(), TOLERANCE);
        assertEquals(expected.y(), actual.y(), TOLERANCE);
    }

    private static AlignmentReviewBasis basisWithAffine(
            final SectionGeometry geometry,
            final AffineTransform2D affine) {
        final AlignmentReviewBasis seed =
                ReviewTestFixtures.basis(geometry);
        final BaselineRegistrationProposal proposal =
                new BaselineRegistrationProposal(
                        seed.proposal().coronalLevel(),
                        seed.proposal().geometry(),
                        new SimilarityTransform2D(
                                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                                CoordinateSpace2D.PREVIEW_PIXEL,
                                1, 0, 0, 0),
                        affine,
                        seed.proposal().objectiveMode(),
                        seed.proposal().similarityDice(),
                        seed.proposal().affineDice());
        return new AlignmentReviewBasis(
                proposal,
                seed.initialPlaneProposal(),
                seed.segmentation(),
                seed.sourceSnapshot(),
                seed.atlas(),
                seed.syntheticPixelPolicy(),
                seed.inferencePreparationProvenance(),
                seed.previewDimensions());
    }

    private static AffineTransform2D atlasToPreview(
            final double m00,
            final double m01,
            final double m02,
            final double m10,
            final double m11,
            final double m12) {
        return new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                m00, m01, m02, m10, m11, m12);
    }
}
