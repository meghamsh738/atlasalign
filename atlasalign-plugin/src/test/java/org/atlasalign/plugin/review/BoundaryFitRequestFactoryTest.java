package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.atlasalign.application.AlignmentReviewSession;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewEdit;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.manual.BoundaryFitDraft;
import org.atlasalign.application.manual.BoundaryFitModel;
import org.atlasalign.application.manual.BoundaryFitRequest;
import org.atlasalign.application.manual.BoundaryWarpRequest;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.junit.jupiter.api.Test;

class BoundaryFitRequestFactoryTest {

    private static final double ATLAS_SCALE = 0.2;

    @Test
    void guidedFullDraftPlacesFourNumberedAnchorsPerAtlasSide() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis());

        final BoundaryFitDraft draft = BoundaryFitRequestFactory
                .createGuidedDraft(session.state(),
                        ReviewPluginFixtures.plane(240),
                        BoundaryFitModel.SIMILARITY,
                        ManualHemisphereWarp2D.AtlasSide.LEFT);

        assertEquals(8, draft.anchors().size());
        assertEquals(8, draft.anchors().stream()
                .map(anchor -> anchor.id()).distinct().count());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8),
                draft.anchors().stream().map(anchor -> anchor.ordinal())
                        .toList());
        assertTrue(draft.anchors().stream().allMatch(anchor ->
                anchor.tissuePreviewPoint().isEmpty()));
        assertTrue(draft.anchors().stream().allMatch(anchor ->
                !anchor.positionLabel().isBlank()));
        assertTrue(draft.anchors().stream()
                .map(anchor -> anchor.positionLabel()).distinct().count()
                >= 4, "anchors should provide distributed directional hints");
        assertTrue(draft.anchors().subList(0, 4).stream().allMatch(anchor ->
                anchor.atlasPreviewPoint().x() < 228 * ATLAS_SCALE));
        assertTrue(draft.anchors().subList(4, 8).stream().allMatch(anchor ->
                anchor.atlasPreviewPoint().x() >= 228 * ATLAS_SCALE));
        assertEquals(draft, BoundaryFitRequestFactory.createGuidedDraft(
                session.state(), ReviewPluginFixtures.plane(240),
                BoundaryFitModel.SIMILARITY,
                ManualHemisphereWarp2D.AtlasSide.LEFT),
                "numbered atlas anchors must replay deterministically");
    }

    @Test
    void guidedHalfAndDisjoinedDraftsPlaceSixAnchorsOnTargetArc() {
        final AlignmentReviewSession half = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis(
                        SectionGeometry.IMAGE_LEFT_HALF));
        half.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT));
        final BoundaryFitDraft halfDraft = BoundaryFitRequestFactory
                .createGuidedDraft(half.state(),
                        ReviewPluginFixtures.plane(240),
                        BoundaryFitModel.SIMILARITY,
                        ManualHemisphereWarp2D.AtlasSide.RIGHT);

        assertEquals(6, halfDraft.anchors().size());
        assertTrue(halfDraft.request().targetSide().isEmpty());
        assertTrue(halfDraft.anchors().stream().allMatch(anchor ->
                anchor.atlasPreviewPoint().x() < 228 * ATLAS_SCALE),
                "Half anchors must remain on the explicit visible exterior arc");
        assertTrue(halfDraft.request().tissueBoundary().stream()
                .mapToDouble(sample -> sample.point().x()).max()
                .orElseThrow() < 90,
                "the probable straight Half cut edge must be excluded");

        final AlignmentReviewSession disjoined = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis());
        disjoined.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.DISJOINED));
        final BoundaryFitDraft disjoinedDraft = BoundaryFitRequestFactory
                .createGuidedDraft(disjoined.state(),
                        ReviewPluginFixtures.plane(240),
                        BoundaryFitModel.SIMILARITY,
                        ManualHemisphereWarp2D.AtlasSide.RIGHT);

        assertEquals(6, disjoinedDraft.anchors().size());
        assertEquals(ManualHemisphereWarp2D.AtlasSide.RIGHT,
                disjoinedDraft.request().targetSide().orElseThrow());
        assertTrue(disjoinedDraft.anchors().stream().allMatch(anchor ->
                anchor.atlasPreviewPoint().x() >= 228 * ATLAS_SCALE),
                "Disjoined anchors must remain on the active atlas half");
    }

    @Test
    void extractsOnlyTheExteriorAtlasBoundaryAndNotInternalHoles() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis());

        final BoundaryFitRequest request = BoundaryFitRequestFactory.create(
                session.state(), planeWithInternalHole(),
                BoundaryFitModel.SIMILARITY,
                ManualHemisphereWarp2D.AtlasSide.LEFT, List.of());

        assertFalse(request.atlasBoundary().isEmpty());
        assertTrue(request.atlasBoundary().stream().allMatch(sample -> {
            final double rawX = sample.point().x() / ATLAS_SCALE;
            final double rawY = sample.point().y() / ATLAS_SCALE;
            return rawX <= 5.01 || rawX >= 53.99
                    || rawY <= 5.01 || rawY >= 33.99;
        }), "An internal annotation hole must not become a fitting target");
    }

    @Test
    void excludesABorderConnectedInterhemisphericCleft() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis());

        final BoundaryFitRequest request = BoundaryFitRequestFactory.create(
                session.state(), planeWithBorderConnectedMidlineCleft(),
                BoundaryFitModel.SIMILARITY,
                ManualHemisphereWarp2D.AtlasSide.LEFT, List.of());

        assertTrue(request.atlasBoundary().stream().noneMatch(sample -> {
            final double rawX = sample.point().x() / ATLAS_SCALE;
            final double rawY = sample.point().y() / ATLAS_SCALE;
            return rawY > 5.01 && rawY <= 20.01
                    && (Math.abs(rawX - 28) < 0.01
                    || Math.abs(rawX - 31) < 0.01);
        }), "The border-connected atlas seam must not become a fitting target");
    }

    @Test
    void halfUsesOnlyTheExplicitlyVisibleAtlasSideButKeepsJoinedPlacement() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis(
                        SectionGeometry.IMAGE_LEFT_HALF));
        session.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT));

        final BoundaryFitRequest request = BoundaryFitRequestFactory.create(
                session.state(), ReviewPluginFixtures.plane(240),
                BoundaryFitModel.SIMILARITY,
                ManualHemisphereWarp2D.AtlasSide.RIGHT, List.of());

        assertEquals(ReviewSectionMode.HALF, request.sectionMode());
        assertTrue(request.targetSide().isEmpty(),
                "Half fitting moves the complete joined atlas");
        assertTrue(request.atlasBoundary().stream().allMatch(sample ->
                sample.point().x() < 228 * ATLAS_SCALE));
    }

    @Test
    void disjoinedRequestTargetsOnlyTheActiveHalf() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis());
        session.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.DISJOINED));

        final BoundaryFitRequest request = BoundaryFitRequestFactory.create(
                session.state(), ReviewPluginFixtures.plane(240),
                BoundaryFitModel.ORTHOGONAL_XY,
                ManualHemisphereWarp2D.AtlasSide.RIGHT, List.of());

        assertEquals(ManualHemisphereWarp2D.AtlasSide.RIGHT,
                request.targetSide().orElseThrow());
        assertTrue(request.atlasBoundary().stream().allMatch(sample ->
                sample.point().x() >= 228 * ATLAS_SCALE));
    }

    @Test
    void reflectedRequestCanonicalizesAfterTheExplicitOrientation() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis());
        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT));

        final BoundaryFitRequest request = BoundaryFitRequestFactory.create(
                session.state(), ReviewPluginFixtures.plane(240),
                BoundaryFitModel.SIMILARITY,
                ManualHemisphereWarp2D.AtlasSide.LEFT, List.of());
        final var expected = session.state().basis().proposal().affine()
                .andThen(session.state().content()
                        .manualPreviewAdjustment())
                .andThen(session.state().content()
                        .postOutlinePreviewAdjustment());

        assertTrue(session.state().preOutlineAtlasToPreview()
                .determinant() < 0,
                "the rendered atlas must retain the explicit reflection");
        assertEquals(expected,
                request.currentOrientedAtlasToPreview());
        assertTrue(request.currentOrientedAtlasToPreview()
                .determinant() > 0,
                "the fit source must begin after the explicit reflection");
    }

    @Test
    void reflectedFullAnchorsKeepRawPaneCoordinatesAndMappedPreviewPoints() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis());
        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT));

        final BoundaryFitDraft draft = BoundaryFitRequestFactory
                .createGuidedDraft(session.state(),
                        ReviewPluginFixtures.plane(240),
                        BoundaryFitModel.SIMILARITY,
                        ManualHemisphereWarp2D.AtlasSide.LEFT);

        assertEquals(8, draft.anchors().size());
        assertTrue(draft.anchors().subList(0, 4).stream().allMatch(anchor ->
                anchor.atlasPlanePoint().x() < 228));
        assertTrue(draft.anchors().subList(4, 8).stream().allMatch(anchor ->
                anchor.atlasPlanePoint().x() >= 228));
        assertRawPanePointsMapToPreview(session, draft);
    }

    @Test
    void reflectedHalfAnchorsStayOnTheVisibleRawAtlasArc() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis(
                        SectionGeometry.IMAGE_LEFT_HALF));
        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT));
        session.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.RIGHT));

        final BoundaryFitDraft draft = BoundaryFitRequestFactory
                .createGuidedDraft(session.state(),
                        ReviewPluginFixtures.plane(240),
                        BoundaryFitModel.SIMILARITY,
                        ManualHemisphereWarp2D.AtlasSide.LEFT);

        assertEquals(6, draft.anchors().size());
        assertTrue(draft.anchors().stream().allMatch(anchor ->
                anchor.atlasPlanePoint().x() >= 228),
                "the raw right hemisphere must remain on the atlas pane's right side even though reflection maps it to image left");
        assertTrue(draft.anchors().stream().allMatch(anchor ->
                anchor.atlasPreviewPoint().x() < 228 * ATLAS_SCALE));
        assertRawPanePointsMapToPreview(session, draft);
    }

    @Test
    void reflectedDisjoinedAnchorsKeepRawCoordinatesForTheActiveHalf() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis());
        session.apply(new ReviewEdit.SetAtlasOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT));
        session.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.DISJOINED));

        final BoundaryFitDraft draft = BoundaryFitRequestFactory
                .createGuidedDraft(session.state(),
                        ReviewPluginFixtures.plane(240),
                        BoundaryFitModel.SIMILARITY,
                        ManualHemisphereWarp2D.AtlasSide.RIGHT);

        assertEquals(6, draft.anchors().size());
        assertEquals(ManualHemisphereWarp2D.AtlasSide.RIGHT,
                draft.request().targetSide().orElseThrow());
        assertTrue(draft.anchors().stream().allMatch(anchor ->
                anchor.atlasPlanePoint().x() >= 228));
        assertRawPanePointsMapToPreview(session, draft);
    }

    @Test
    void manualBoundaryWarpUsesDisplayedSideAndStartsManualFirst() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis());

        final BoundaryWarpRequest request = BoundaryFitRequestFactory
                .createBoundaryWarp(session.state(),
                        ReviewPluginFixtures.plane(240),
                        ManualHemisphereWarp2D.AtlasSide.LEFT, List.of());

        assertEquals(ManualHemisphereWarp2D.AtlasSide.LEFT,
                request.targetSide());
        assertTrue(request.atlasBoundary().stream().allMatch(sample ->
                sample.point().x() < 228 * ATLAS_SCALE));
        assertTrue(request.matches().isEmpty(),
                "Border starts manual-first; suggestions are optional");
        assertEquals(session.state().contentRevision(),
                request.contentRevision());
        assertEquals(session.state().content().hemisphereWarp(),
                request.priorWarp());
    }

    @Test
    void halfBoundaryWarpRetainsEveryTissueSupportedAtlasSide() {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                ReviewPluginFixtures.segmentedScaledAtlasBasis(
                        SectionGeometry.IMAGE_LEFT_HALF));
        session.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT));

        final BoundaryWarpRequest oppositeRemnant = BoundaryFitRequestFactory
                .createBoundaryWarp(session.state(),
                        ReviewPluginFixtures.plane(240),
                        ManualHemisphereWarp2D.AtlasSide.RIGHT, List.of());
        final BoundaryWarpRequest request = BoundaryFitRequestFactory
                .createBoundaryWarp(session.state(),
                        ReviewPluginFixtures.plane(240),
                        ManualHemisphereWarp2D.AtlasSide.LEFT,
                        List.of());

        assertEquals(ReviewSectionMode.HALF, request.sectionMode());
        assertEquals(ManualHemisphereWarp2D.AtlasSide.LEFT,
                request.targetSide());
        assertEquals(ManualHemisphereWarp2D.AtlasSide.RIGHT,
                oppositeRemnant.targetSide(),
                "Half keeps the complete atlas so a supported contralateral remnant can be refined");
    }

    private static void assertRawPanePointsMapToPreview(
            final AlignmentReviewSession session,
            final BoundaryFitDraft draft) {
        draft.anchors().forEach(anchor -> {
            final var mapped = BoundaryFitRequestFactory
                    .mappedAtlasPreviewPoint(session.state(),
                            draft.request().targetSide(),
                            anchor.atlasPlanePoint());
            assertEquals(mapped.x(), anchor.atlasPreviewPoint().x(), 1e-9,
                    "raw atlas-pane X must map to the displayed preview X");
            assertEquals(mapped.y(), anchor.atlasPreviewPoint().y(), 1e-9,
                    "raw atlas-pane Y must map to the displayed preview Y");
        });
    }

    private static AtlasCoronalPlane planeWithInternalHole() {
        final int width = 60;
        final int height = 40;
        final int[] template = new int[width * height];
        final int[] annotation = new int[width * height];
        for (int y = 5; y <= 34; y++) {
            for (int x = 5; x <= 54; x++) {
                final boolean hole = x >= 20 && x <= 39
                        && y >= 15 && y <= 24;
                if (!hole) {
                    template[y * width + x] = 1_000;
                    annotation[y * width + x] = 1;
                }
            }
        }
        return new AtlasCoronalPlane(
                240, width, height, template, annotation);
    }

    private static AtlasCoronalPlane planeWithBorderConnectedMidlineCleft() {
        final int width = 60;
        final int height = 40;
        final int[] template = new int[width * height];
        final int[] annotation = new int[width * height];
        for (int y = 5; y <= 34; y++) {
            for (int x = 5; x <= 54; x++) {
                final boolean cleft = x >= 29 && x <= 30 && y <= 20;
                if (!cleft) {
                    template[y * width + x] = 1_000;
                    annotation[y * width + x] = 1;
                }
            }
        }
        return new AtlasCoronalPlane(
                240, width, height, template, annotation);
    }
}
