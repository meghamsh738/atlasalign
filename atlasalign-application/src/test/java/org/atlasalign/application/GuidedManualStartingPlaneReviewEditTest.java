package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D;
import org.atlasalign.application.manual.ManualOutlineWarp2D;
import org.atlasalign.application.manual.MonotoneBoundary2D;
import org.atlasalign.application.manual.ReviewedOutlineTransform2D;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class GuidedManualStartingPlaneReviewEditTest {

    @Test
    void reviewedOutlineTypeBoundaryPermitsOnlyAuditedImplementations() {
        assertTrue(ReviewedOutlineTransform2D.class.isSealed());
        final Set<Class<?>> permitted = Set.copyOf(Arrays.asList(
                ReviewedOutlineTransform2D.class.getPermittedSubclasses()));
        assertEquals(Set.of(ManualOutlineWarp2D.class,
                        BoundaryAuthoritativeTransform2D.class),
                permitted);
    }

    @Test
    void appliesUnrankedVisualPlaneAndPreservesLandmarksAndBasis() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final LandmarkPair preserved = new LandmarkPair(
                "preserved", session.state().content().coronalLevel(),
                session.state().content().atlasPlaneTilt(),
                new Point2D(100, 80), new Point2D(25, 24));
        session.apply(new ReviewEdit.AddLandmark(preserved));
        final AffineTransform2D correction = new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1.1, 0, 2, 0, 0.9, 3);
        final AffineTransform2D chosen = basis.proposal().affine()
                .andThen(correction);

        final ReviewEdit.ApplyGuidedManualStartingPlane edit =
                new ReviewEdit.ApplyGuidedManualStartingPlane(
                new AllenCoronalLevel(293), new AtlasPlaneTilt(2, -1),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.BOTH, chosen,
                Optional.empty(), false, "visual-browser-v1", List.of(),
                basis.sourceSnapshot().pixelSha256(),
                basis.atlas().identitySha256());
        session.apply(edit);

        assertEquals(293, session.state().content().coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
        assertEquals(new AtlasPlaneTilt(2, -1),
                session.state().content().atlasPlaneTilt());
        assertEquals(correction,
                session.state().content().manualPreviewAdjustment());
        assertEquals(List.of(preserved),
                session.state().content().landmarks());
        assertEquals(basis.initialPlaneProposal(),
                session.state().basis().initialPlaneProposal());
        assertTrue(session.state().content().hemisphereWarp().isEmpty());
        assertTrue(session.state().content().localWarp().isEmpty());
        final ReviewAuditEvent event = session.auditTrail().get(
                session.auditTrail().size() - 1);
        assertEquals(ReviewOperation.APPLY_GUIDED_MANUAL_STARTING_PLANE,
                event.operation());
        assertTrue(event.description().contains("no ranking evidence"));
        assertFalse(event.description().contains("mismatch"));
        assertTrue(event.description().contains(
                basis.sourceSnapshot().pixelSha256()));
        assertTrue(event.description().contains(
                basis.atlas().identitySha256()));
        assertTrue(event.description().contains(
                edit.startingTransformSha256()));
    }

    @Test
    void suppliedOutlineMustBeConfirmedFullBilateralAndUseActiveDomain() {
        final AlignmentReviewBasis full = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final ManualOutlineWarp2D outline = outline();
        final ReviewEdit.ApplyGuidedManualStartingPlane edit =
                new ReviewEdit.ApplyGuidedManualStartingPlane(
                        full.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.BOTH,
                        full.proposal().affine(), Optional.of(outline), true,
                        "confirmed-outline-v1", List.of("tissue-outline-1"),
                        full.sourceSnapshot().pixelSha256(),
                        full.atlas().identitySha256());
        final AlignmentReviewSession fullSession =
                new AlignmentReviewSession(full);
        fullSession.apply(edit);
        assertTrue(fullSession.state().content().outlineWarp().isPresent());
        assertTrue(fullSession.state().content().outlineAnchorsConfirmed());

        assertThrows(IllegalArgumentException.class, () ->
                new ReviewEdit.ApplyGuidedManualStartingPlane(
                        full.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.BOTH,
                        full.proposal().affine(), Optional.of(outline), false,
                        "unconfirmed", List.of("tissue-outline-1"),
                        full.sourceSnapshot().pixelSha256(),
                        full.atlas().identitySha256()));

        final AlignmentReviewBasis half = ReviewTestFixtures.basis(
                SectionGeometry.IMAGE_LEFT_HALF);
        final AlignmentReviewSession halfSession =
                new AlignmentReviewSession(half);
        assertThrows(IllegalArgumentException.class, () -> halfSession.apply(
                new ReviewEdit.ApplyGuidedManualStartingPlane(
                        half.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.LEFT,
                        half.proposal().affine(), Optional.of(outline), true,
                        "half-outline", List.of("visible-boundary-1"),
                        half.sourceSnapshot().pixelSha256(),
                        half.atlas().identitySha256())));
    }

    @Test
    void exactReviewedOutlineSurvivesHandoffAndAcceptanceUnchanged() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final BoundaryAuthoritativeTransform2D outline = exactOutline();
        final AllenCoronalLevel selectedLevel = new AllenCoronalLevel(293);
        final AtlasPlaneTilt selectedTilt = new AtlasPlaneTilt(-1.0, 2.0);

        session.apply(new ReviewEdit.ApplyGuidedManualStartingPlane(
                selectedLevel, selectedTilt,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.BOTH,
                basis.proposal().affine(), Optional.of(outline), true,
                "boundary-authoritative-outline-v1",
                List.of("reviewed-outline"),
                basis.sourceSnapshot().pixelSha256(),
                basis.atlas().identitySha256()));

        assertEquals(selectedLevel,
                session.state().content().coronalLevel());
        assertEquals(selectedTilt,
                session.state().content().atlasPlaneTilt());
        assertEquals(outline.contentSha256(), session.state().content()
                .outlineWarp().orElseThrow().contentSha256());
        assertTrue(session.state().content().outlineWarp().orElseThrow()
                instanceof BoundaryAuthoritativeTransform2D);

        final AcceptedAlignmentSnapshot accepted = session.accept(
                ReviewTestFixtures.verifier(basis), true);
        assertEquals(selectedLevel, accepted.coronalLevel());
        assertEquals(selectedTilt, accepted.atlasPlaneTilt());
        assertEquals(outline.contentSha256(), accepted.outlineWarp()
                .orElseThrow().contentSha256());
        assertTrue(accepted.outlineWarp().orElseThrow()
                instanceof BoundaryAuthoritativeTransform2D);
    }

    @Test
    void rejectsUnconfirmedOrientationWrongSpacesReflectionAndIdentities() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        assertThrows(IllegalArgumentException.class, () ->
                new ReviewEdit.ApplyGuidedManualStartingPlane(
                        basis.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                        AtlasOrientation.UNCONFIRMED_PROVISIONAL_DIRECT,
                        ObservedAnatomicalHemisphere.BOTH,
                        basis.proposal().affine(), Optional.empty(), false,
                        "visual", List.of(),
                        basis.sourceSnapshot().pixelSha256(),
                        basis.atlas().identitySha256()));
        assertThrows(IllegalArgumentException.class, () ->
                new ReviewEdit.ApplyGuidedManualStartingPlane(
                        basis.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.BOTH,
                        AlignmentReviewContent.identityPreviewAdjustment(),
                        Optional.empty(), false, "wrong-space", List.of(),
                        basis.sourceSnapshot().pixelSha256(),
                        basis.atlas().identitySha256()));

        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewEdit.ApplyGuidedManualStartingPlane wrongReflection =
                new ReviewEdit.ApplyGuidedManualStartingPlane(
                        basis.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT,
                        ObservedAnatomicalHemisphere.BOTH,
                        basis.proposal().affine(), Optional.empty(), false,
                        "wrong-reflection", List.of(),
                        basis.sourceSnapshot().pixelSha256(),
                        basis.atlas().identitySha256());
        assertThrows(IllegalArgumentException.class,
                () -> session.apply(wrongReflection));
        assertThrows(IllegalArgumentException.class, () -> session.apply(
                new ReviewEdit.ApplyGuidedManualStartingPlane(
                        basis.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.BOTH,
                        basis.proposal().affine(), Optional.empty(), false,
                        "wrong-source", List.of(), "0".repeat(64),
                        basis.atlas().identitySha256())));
    }

    private static ManualOutlineWarp2D outline() {
        final List<Point2D> loop = List.of(
                new Point2D(45, 10), new Point2D(90, 40),
                new Point2D(55, 70), new Point2D(10, 40));
        final List<ManualOutlineWarp2D.AnchorPair> anchors = List.of(
                new ManualOutlineWarp2D.AnchorPair("D", 0, 0),
                new ManualOutlineWarp2D.AnchorPair("R", 1, 1),
                new ManualOutlineWarp2D.AnchorPair("V", 2, 2),
                new ManualOutlineWarp2D.AnchorPair("L", 3, 3));
        return ManualOutlineWarp2D.fit(loop, loop, anchors, 100, 80);
    }

    private static BoundaryAuthoritativeTransform2D exactOutline() {
        final List<Point2D> atlas = List.of(
                new Point2D(45, 10), new Point2D(90, 40),
                new Point2D(55, 70), new Point2D(10, 40));
        final List<Point2D> tissue = List.of(
                new Point2D(45, 8), new Point2D(92, 40),
                new Point2D(55, 72), new Point2D(8, 40));
        return BoundaryAuthoritativeTransform2D.fitFull(
                MonotoneBoundary2D.arcLengthIndexed("atlas-", atlas),
                MonotoneBoundary2D.arcLengthIndexed("tissue-", tissue),
                100, 80);
    }
}
