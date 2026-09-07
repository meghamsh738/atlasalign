package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.atlasalign.application.manual.ManualOutlineWarp2D;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ManualOutlineWarpReviewStateTest {

    private static final double TOLERANCE = 1e-6;

    @Test
    void mapsAndInvertsTheExactGlobalOutlinePostAndLocalChain() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ManualOutlineWarp2D outline = outlineWarp(100, 80);
        session.apply(candidate(basis, outline));

        final Point2D probe = new Point2D(45, 35);
        final Point2D afterOutline = outline.apply(probe);
        assertPoint(afterOutline, session.state().mapAtlasToPreview(probe));

        session.apply(new ReviewEdit.Translate(3, -2));
        final Point2D expected = new Point2D(
                afterOutline.x() + 3, afterOutline.y() - 2);
        assertPoint(expected, session.state().mapAtlasToPreview(probe));
        assertPoint(probe, session.state().mapPreviewToAtlas(expected));
        assertEquals(outline, session.state().content().outlineWarp()
                .orElseThrow());
        assertEquals(ReviewTestFixtures.identityAtlasToPreview(),
                session.state().preOutlineAtlasToPreview());
    }

    @Test
    void postOutlineEditsAndLandmarkChangesPreserveOutlineOnly() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ManualOutlineWarp2D outline = outlineWarp(100, 80);
        session.apply(candidate(basis, outline));
        final AffineTransform2D pre = session.state()
                .preOutlineAtlasToPreview();

        session.apply(new ReviewEdit.Rotate(0.05, new Point2D(50, 40)));
        assertEquals(pre, session.state().preOutlineAtlasToPreview());
        assertEquals(outline, session.state().content().outlineWarp()
                .orElseThrow());
        assertNotEquals(AlignmentReviewContent.identityPreviewAdjustment(),
                session.state().content().postOutlinePreviewAdjustment());

        session.apply(new ReviewEdit.AddLandmark(pair(
                "fit-0", new Point2D(20, 20),
                session.state().mapAtlasToPreview(new Point2D(20, 20)))));
        assertEquals(outline, session.state().content().outlineWarp()
                .orElseThrow());
        assertNotEquals(AlignmentReviewContent.identityPreviewAdjustment(),
                session.state().content().postOutlinePreviewAdjustment());
        assertTrue(session.state().content().localWarp().isEmpty());

        assertTrue(session.undo());
        assertTrue(session.undo());
        assertEquals(AlignmentReviewContent.identityPreviewAdjustment(),
                session.state().content().postOutlinePreviewAdjustment());
        assertTrue(session.redo());
        assertTrue(session.redo());
        assertEquals(1, session.state().content().landmarks().size());
    }

    @Test
    void planeTiltAndOrientationChangesClearAllDependentStages() {
        assertInvalidatedBy(new ReviewEdit.SetCoronalLevel(
                new AllenCoronalLevel(241)));
        assertInvalidatedBy(new ReviewEdit.SetAtlasPlaneTilt(
                new AtlasPlaneTilt(1, -1)));
        assertInvalidatedBy(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT));
    }

    @Test
    void landmarkLocalWarpFitsPostOutlineGeometryAndAcceptanceSeparatesStages() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ManualOutlineWarp2D outline = outlineWarp(100, 80);
        session.apply(candidate(basis, outline));
        session.apply(new ReviewEdit.Translate(1.5, -0.5));

        final List<Point2D> atlasPoints = List.of(
                new Point2D(20, 18), new Point2D(78, 18),
                new Point2D(78, 62), new Point2D(20, 62));
        final List<Point2D> deltas = List.of(
                new Point2D(0.5, 0.2), new Point2D(-0.4, 0.3),
                new Point2D(-0.3, -0.4), new Point2D(0.4, -0.3));
        for (int index = 0; index < atlasPoints.size(); index++) {
            final Point2D atlas = atlasPoints.get(index);
            final Point2D base = session.state().mapAtlasToPreview(atlas);
            final Point2D delta = deltas.get(index);
            session.apply(new ReviewEdit.AddLandmark(pair(
                    "fit-" + index, atlas,
                    new Point2D(base.x() + delta.x(),
                            base.y() + delta.y()))));
        }
        session.apply(new ReviewEdit.FitActiveLandmarksLocalWarp());
        final ConstrainedLocalWarp2D local = session.state().content()
                .localWarp().orElseThrow();
        for (int index = 0; index < atlasPoints.size(); index++) {
            final Point2D global = session.state()
                    .preOutlineAtlasToPreview().apply(atlasPoints.get(index));
            final Point2D expectedSource = session.state().content()
                    .postOutlinePreviewAdjustment().apply(
                            outline.apply(global));
            assertPoint(expectedSource, local.fitSourcePoints().get(index));
        }

        final AcceptedAlignmentSnapshot accepted = session.accept(
                ReviewTestFixtures.verifier(basis), true);
        assertEquals(session.state().preOutlineAtlasToPreview(),
                accepted.preOutlineAtlasToPreview());
        assertEquals(outline, accepted.outlineWarp().orElseThrow());
        assertEquals(session.state().content()
                        .postOutlinePreviewAdjustment(),
                accepted.postOutlinePreviewAdjustment());
        assertEquals(local, accepted.localWarp().orElseThrow());
        final Point2D probe = new Point2D(45, 35);
        assertPoint(session.state().mapAtlasToPreview(probe),
                accepted.mapAtlasToPreview(probe));
        assertPoint(probe, accepted.mapPreviewToAtlas(
                accepted.mapAtlasToPreview(probe)));
    }

    @Test
    void candidateRejectsMismatchedIdentitiesAndPreviewDomain() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ManualOutlineWarp2D valid = outlineWarp(100, 80);

        assertThrows(IllegalArgumentException.class, () -> session.apply(
                candidate(basis, valid, "0".repeat(64),
                        basis.atlas().identitySha256())));
        assertThrows(IllegalArgumentException.class, () -> session.apply(
                candidate(basis, valid,
                        basis.sourceSnapshot().pixelSha256(),
                        "0".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> session.apply(
                candidate(basis, outlineWarp(120, 80))));
        assertThrows(IllegalArgumentException.class, () ->
                new ReviewEdit.ApplyGuidedManualCandidate(
                        basis.proposal().coronalLevel(),
                        AtlasPlaneTilt.CORONAL,
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.BOTH,
                        ReviewTestFixtures.identityAtlasToPreview(), valid,
                        false, "manual-outline-warp-v1", "unconfirmed",
                        basis.proposal().coronalLevel(),
                        AtlasPlaneTilt.CORONAL,
                        0.1, 0.1, 0.1, List.of("outline"),
                        basis.sourceSnapshot().pixelSha256(),
                        basis.atlas().identitySha256()));
    }

    @Test
    void outlineWarpFailsClosedForEveryNonFullReviewBasis() {
        for (final SectionGeometry geometry : SectionGeometry.values()) {
            if (geometry == SectionGeometry.FULL) {
                continue;
            }
            final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                    geometry);
            final AlignmentReviewSession session =
                    new AlignmentReviewSession(basis);
            assertThrows(IllegalArgumentException.class, () -> session.apply(
                    candidate(basis, outlineWarp(100, 80))),
                    () -> "Outline warp unexpectedly accepted for " + geometry);
        }
    }

    @Test
    void externallyAssembledNonFullOutlineStateAlsoFailsClosed() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.PARTIAL_OR_DAMAGED);
        final AlignmentReviewContent content = new AlignmentReviewContent(
                basis.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                ReviewWorkflowMode.MANUAL_ONLY,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.LEFT,
                AlignmentReviewContent.identityPreviewAdjustment(),
                Optional.of(outlineWarp(100, 80)), true,
                AlignmentReviewContent.identityPreviewAdjustment(),
                List.of(), Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> new AlignmentReviewState(basis, content, 1));
    }

    @Test
    void legacyContentAndCandidateRemainAffineOnlyCompatible() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewContent legacy = new AlignmentReviewContent(
                basis.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                ReviewWorkflowMode.MANUAL_ONLY,
                AtlasOrientation.UNCONFIRMED_PROVISIONAL_DIRECT,
                ObservedAnatomicalHemisphere.BOTH,
                AlignmentReviewContent.identityPreviewAdjustment(),
                List.of(), Optional.empty());
        assertTrue(legacy.outlineWarp().isEmpty());
        assertEquals(AlignmentReviewContent.identityPreviewAdjustment(),
                legacy.postOutlinePreviewAdjustment());

        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.UNCONFIRMED_PROVISIONAL_DIRECT));
        session.apply(candidate(basis, null));
        assertTrue(session.state().content().outlineWarp().isEmpty());
    }

    private static void assertInvalidatedBy(final ReviewEdit edit) {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        session.apply(candidate(basis, outlineWarp(100, 80)));
        session.apply(new ReviewEdit.Translate(1, 1));
        session.apply(edit);
        assertTrue(session.state().content().outlineWarp().isEmpty());
        assertEquals(AlignmentReviewContent.identityPreviewAdjustment(),
                session.state().content().postOutlinePreviewAdjustment());
        assertTrue(session.state().content().localWarp().isEmpty());
    }

    private static ReviewEdit.ApplyGuidedManualCandidate candidate(
            final AlignmentReviewBasis basis,
            final ManualOutlineWarp2D outline) {
        return candidate(basis, outline,
                basis.sourceSnapshot().pixelSha256(),
                basis.atlas().identitySha256());
    }

    private static ReviewEdit.ApplyGuidedManualCandidate candidate(
            final AlignmentReviewBasis basis,
            final ManualOutlineWarp2D outline,
            final String sourceSha256,
            final String atlasSha256) {
        if (outline == null) {
            return new ReviewEdit.ApplyGuidedManualCandidate(
                    basis.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                    AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                    ObservedAnatomicalHemisphere.BOTH,
                    ReviewTestFixtures.identityAtlasToPreview(),
                    "outline-bounds-affine-v1", "legacy-candidate",
                    basis.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                    0.1, 0.1, 0.1, List.of("outline"),
                    sourceSha256, atlasSha256);
        }
        return new ReviewEdit.ApplyGuidedManualCandidate(
                basis.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.BOTH,
                ReviewTestFixtures.identityAtlasToPreview(), outline,
                true,
                "manual-outline-warp-v1", "warped-candidate",
                basis.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                0.1, 0.1, 0.1, List.of("outline"),
                sourceSha256, atlasSha256);
    }

    private static LandmarkPair pair(
            final String id,
            final Point2D atlas,
            final Point2D preview) {
        return new LandmarkPair(id, new AllenCoronalLevel(240),
                AtlasPlaneTilt.CORONAL, atlas, preview, LandmarkRole.FIT);
    }

    private static ManualOutlineWarp2D outlineWarp(
            final int width,
            final int height) {
        final double left = width * 0.1;
        final double right = width * 0.9;
        final double top = height * 0.1;
        final double bottom = height * 0.9;
        final List<Point2D> atlas = rectangleLoop(left, right, top, bottom);
        final List<Point2D> tissue = atlas.stream()
                .map(point -> new Point2D(
                        point.x() + 0.001 * (point.x() - width / 2.0)
                                * (point.y() - height / 2.0),
                        point.y() + 0.001 * (point.x() - width / 2.0)
                                * (point.x() - width / 2.0) - 0.5))
                .toList();
        return ManualOutlineWarp2D.fit(atlas, tissue, List.of(
                new ManualOutlineWarp2D.AnchorPair("dorsal-left", 0, 0),
                new ManualOutlineWarp2D.AnchorPair("dorsal-right", 4, 4),
                new ManualOutlineWarp2D.AnchorPair("ventral-right", 8, 8),
                new ManualOutlineWarp2D.AnchorPair("ventral-left", 12, 12)),
                width, height);
    }

    private static List<Point2D> rectangleLoop(
            final double left,
            final double right,
            final double top,
            final double bottom) {
        final double quarterX = (right - left) / 4;
        final double quarterY = (bottom - top) / 4;
        return List.of(
                new Point2D(left, top),
                new Point2D(left + quarterX, top),
                new Point2D(left + 2 * quarterX, top),
                new Point2D(left + 3 * quarterX, top),
                new Point2D(right, top),
                new Point2D(right, top + quarterY),
                new Point2D(right, top + 2 * quarterY),
                new Point2D(right, top + 3 * quarterY),
                new Point2D(right, bottom),
                new Point2D(right - quarterX, bottom),
                new Point2D(right - 2 * quarterX, bottom),
                new Point2D(right - 3 * quarterX, bottom),
                new Point2D(left, bottom),
                new Point2D(left, bottom - quarterY),
                new Point2D(left, bottom - 2 * quarterY),
                new Point2D(left, bottom - 3 * quarterY));
    }

    private static void assertPoint(
            final Point2D expected,
            final Point2D actual) {
        assertEquals(expected.x(), actual.x(), TOLERANCE);
        assertEquals(expected.y(), actual.y(), TOLERANCE);
    }
}
