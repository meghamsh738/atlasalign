package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualWarpException;
import org.atlasalign.application.manual.ManualSidePlacement2D;
import org.atlasalign.application.manual.ManualWarpControl;
import org.atlasalign.application.manual.ManualWarpControlOrigin;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ReviewSectionModeManualWarpTest {

    @Test
    void reviewerSectionOverrideNormalizesOnlyManualLateralityState() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final var automaticProposal = session.state().basis().proposal();

        session.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.HALF));

        assertEquals(ObservedAnatomicalHemisphere.UNSURE,
                session.state().content().observedHemisphere());
        assertEquals(automaticProposal, session.state().basis().proposal(),
                "reviewer workflow geometry cannot rewrite automatic evidence");
        session.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT));
        session.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.FULL));
        assertEquals(ObservedAnatomicalHemisphere.BOTH,
                session.state().content().observedHemisphere());
        assertEquals(automaticProposal, session.state().basis().proposal());
    }

    @Test
    void halfCoverageDefaultsVisibleOnlyAndReplaysWithoutChangingEvidence() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.IMAGE_LEFT_HALF);
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        assertEquals(HalfAtlasCoverage.VISIBLE_SIDE_ONLY,
                session.state().content().halfAtlasCoverage());
        session.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT));
        final ReviewConfidenceReport confidenceBefore = session.confidence();
        final ManualWarpPrecondition strictPrecondition =
                ManualWarpPrecondition.capture(session.state());
        final long beforeCoverage = session.state().contentRevision();
        session.apply(new ReviewEdit.SetHalfAtlasCoverage(
                HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT));

        assertEquals(beforeCoverage + 1,
                session.state().contentRevision());
        assertEquals(HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT,
                session.state().content().halfAtlasCoverage());
        assertEquals(confidenceBefore, session.confidence(),
                "manual footprint scope cannot alter automatic evidence");
        assertThrows(ManualWarpException.class,
                () -> strictPrecondition.requireMatches(
                        session.state().content(), basis),
                "an asynchronous strict-Half solve cannot install after remnant scope changes");
        session.undo();
        assertEquals(HalfAtlasCoverage.VISIBLE_SIDE_ONLY,
                session.state().content().halfAtlasCoverage());
        session.redo();
        assertEquals(HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT,
                session.state().content().halfAtlasCoverage());

        final AcceptedAlignmentSnapshot accepted = session.accept(
                ReviewTestFixtures.verifier(basis), true);
        assertTrue(accepted.includesAtlasSide(
                ManualHemisphereWarp2D.AtlasSide.LEFT));
        assertTrue(accepted.includesAtlasSide(
                ManualHemisphereWarp2D.AtlasSide.RIGHT));
        assertEquals(HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT,
                accepted.halfAtlasCoverage());

        session.apply(new ReviewEdit.SetCoronalLevel(
                new AllenCoronalLevel(241)));
        assertEquals(HalfAtlasCoverage.VISIBLE_SIDE_ONLY,
                session.state().content().halfAtlasCoverage(),
                "a new plane must require remnant eligibility to be reviewed again");
    }

    @Test
    void halfCoverageRequiresHalfModeAndConfirmedVisibleLaterality() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);

        assertThrows(IllegalArgumentException.class,
                () -> session.apply(new ReviewEdit.SetHalfAtlasCoverage(
                        HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT)));
        session.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.HALF));
        assertThrows(IllegalArgumentException.class,
                () -> session.apply(new ReviewEdit.SetHalfAtlasCoverage(
                        HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT)));
        session.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.RIGHT));
        final AcceptedAlignmentSnapshot accepted = session.accept(
                ReviewTestFixtures.verifier(basis), true);
        assertFalse(accepted.includesAtlasSide(
                ManualHemisphereWarp2D.AtlasSide.LEFT));
        assertTrue(accepted.includesAtlasSide(
                ManualHemisphereWarp2D.AtlasSide.RIGHT));
    }

    @Test
    void fullAndHalfFieldsKeepTheOppositeSideExactAndSurvivePlaneChanges() {
        for (final ReviewSectionMode mode : List.of(
                ReviewSectionMode.FULL, ReviewSectionMode.HALF)) {
            final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                    SectionGeometry.FULL);
            final AlignmentReviewSession session =
                    new AlignmentReviewSession(basis);
            if (mode != ReviewSectionMode.FULL) {
                session.apply(new ReviewEdit.SetReviewSectionMode(mode));
            }
            final List<ManualWarpControl> initial = leftControls(0);
            install(session, initial, mode);
            final Point2D opposite = new Point2D(75, 40);
            assertEquals(opposite, session.state().content().hemisphereWarp()
                    .orElseThrow().apply(
                            ManualHemisphereWarp2D.AtlasSide.RIGHT,
                            opposite));

            final String hash = session.state().content().hemisphereWarp()
                    .orElseThrow().diagnostics().contentSha256();
            session.apply(new ReviewEdit.SetCoronalLevel(
                    new AllenCoronalLevel(241)));
            session.apply(new ReviewEdit.SetAtlasPlaneTilt(
                    new AtlasPlaneTilt(3, -2)));

            assertEquals(hash, session.state().content().hemisphereWarp()
                    .orElseThrow().diagnostics().contentSha256());
            assertEquals(initial, session.state().content().hemisphereWarp()
                    .orElseThrow().controls());
        }
    }

    @Test
    void disjoinedPlacementMovesOnlyOneRawAtlasHalfAndReplaysExactly() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        session.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.DISJOINED));
        final Point2D leftAtlas = new Point2D(10, 20);
        final Point2D rightAtlas = new Point2D(300, 20);
        final Point2D leftBefore = session.state().mapAtlasToPreview(leftAtlas);
        final Point2D rightBefore = session.state().mapAtlasToPreview(rightAtlas);
        final AffineTransform2D translated = new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 7, 0, 1, -3);
        final ManualSidePlacement2D placement = ManualSidePlacement2D
                .identity().withTransform(
                        ManualHemisphereWarp2D.AtlasSide.LEFT, translated);

        session.apply(new ReviewEdit.SetManualSidePlacement(
                ManualHemisphereWarp2D.AtlasSide.LEFT, placement));

        assertEquals(new Point2D(leftBefore.x() + 7, leftBefore.y() - 3),
                session.state().mapAtlasToPreview(leftAtlas));
        assertEquals(rightBefore, session.state().mapAtlasToPreview(rightAtlas));
        final Point2D mapped = session.state().mapAtlasToPreview(leftAtlas);
        assertEquals(leftAtlas, session.state().mapPreviewToAtlas(mapped));
        session.undo();
        assertEquals(leftBefore, session.state().mapAtlasToPreview(leftAtlas));
        session.redo();
        assertEquals(mapped, session.state().mapAtlasToPreview(leftAtlas));

        final AcceptedAlignmentSnapshot accepted = session.accept(
                ReviewTestFixtures.verifier(basis), true);
        assertEquals("REVIEWER_CONTROLLED_MANUAL_WARP",
                accepted.outputMethodLabel());
        assertEquals(leftAtlas, accepted.mapPreviewToAtlas(
                accepted.mapAtlasToPreview(leftAtlas)));
    }

    @Test
    void disjoinedReducerRejectsEffectiveShearOutsideTheController() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        session.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.DISJOINED));
        final AffineTransform2D shear = new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0.2, 0, 0, 1, 0);
        final ManualSidePlacement2D placement = ManualSidePlacement2D
                .identity().withTransform(
                        ManualHemisphereWarp2D.AtlasSide.LEFT, shear);
        final long before = session.state().contentRevision();

        final IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> session.apply(new ReviewEdit.SetManualSidePlacement(
                        ManualHemisphereWarp2D.AtlasSide.LEFT,
                        placement)));

        assertTrue(rejected.getMessage().contains("no shear"));
        assertEquals(before, session.state().contentRevision());
        assertTrue(session.state().content().manualSidePlacement()
                .isIdentity());
    }

    @Test
    void disjoinedConnectedPathsRequireAndHonorAnExplicitRawSide() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        session.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.DISJOINED));
        final AffineTransform2D translated = new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 6, 0, 1, -2);
        final ManualSidePlacement2D placement = ManualSidePlacement2D
                .identity().withTransform(
                        ManualHemisphereWarp2D.AtlasSide.LEFT, translated);
        session.apply(new ReviewEdit.SetManualSidePlacement(
                ManualHemisphereWarp2D.AtlasSide.LEFT, placement));
        final List<Point2D> leftPath = List.of(
                new Point2D(10, 20), new Point2D(20, 20));

        assertThrows(IllegalArgumentException.class,
                () -> session.state().mapAtlasPathToPreview(leftPath, false));
        final List<Point2D> explicit = session.state().mapAtlasPathToPreview(
                ManualHemisphereWarp2D.AtlasSide.LEFT, leftPath, false);
        assertEquals(session.state().mapAtlasToPreview(leftPath.get(0)),
                explicit.get(0));
        assertEquals(session.state().mapAtlasToPreview(leftPath.get(1)),
                explicit.get(explicit.size() - 1));
    }

    @Test
    void changingModeIsOneRevisionAndClearsOnlyIncompatibleManualGeometry() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        install(session, leftControls(1), ReviewSectionMode.FULL);
        final long before = session.state().contentRevision();
        final AllenCoronalLevel level = session.state().content().coronalLevel();
        final AtlasPlaneTilt tilt = session.state().content().atlasPlaneTilt();

        session.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.DISJOINED));

        assertEquals(before + 1, session.state().contentRevision());
        assertEquals(ReviewSectionMode.DISJOINED,
                session.state().content().reviewSectionMode());
        assertTrue(session.state().content().hemisphereWarp().isEmpty());
        assertTrue(session.state().content().manualSidePlacement().isIdentity());
        assertTrue(session.state().content().outlineWarp().isEmpty());
        assertEquals(level, session.state().content().coronalLevel());
        assertEquals(tilt, session.state().content().atlasPlaneTilt());
        assertFalse(session.state().content().orientation().reflected());
    }

    private static void install(
            final AlignmentReviewSession session,
            final List<ManualWarpControl> controls,
            final ReviewSectionMode mode) {
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(session.state());
        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                controls, session.state().content().orientation(), mode,
                session.state().basis().previewDimensions().width(),
                session.state().basis().previewDimensions().height());
        session.apply(new ReviewEdit.ReplaceManualWarpControlGroup(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                "structure-guide", controls, precondition, warp));
    }

    private static List<ManualWarpControl> leftControls(final double dx) {
        return List.of(
                control("l1", 10, 10, dx), control("l2", 35, 10, dx),
                control("l3", 10, 60, dx), control("l4", 35, 60, dx));
    }

    private static ManualWarpControl control(
            final String id,
            final double x,
            final double y,
            final double dx) {
        return new ManualWarpControl(id,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.STRUCTURE_GUIDE,
                "structure-guide", "DG",
                new Point2D(x, y), new Point2D(x + dx, y));
    }
}
