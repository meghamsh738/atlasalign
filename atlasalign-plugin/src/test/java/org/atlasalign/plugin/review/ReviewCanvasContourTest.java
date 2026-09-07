package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AlignmentReviewSession;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.HalfAtlasCoverage;
import org.atlasalign.application.LandmarkPair;
import org.atlasalign.application.ManualWarpPrecondition;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewEdit;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualOutlineWarp2D;
import org.atlasalign.application.manual.ManualSidePlacement2D;
import org.atlasalign.application.manual.ManualWarpControl;
import org.atlasalign.application.manual.ManualWarpControlOrigin;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.atlas.AtlasPlaneGeometry;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ReviewCanvasContourTest {

    @Test
    void contrastBoundaryIsTracedWithoutConnectingSparseWarpHandles() {
        final int width = 12;
        final int height = 10;
        final boolean[] pixels = new boolean[width * height];
        for (int y = 2; y <= 7; y++) {
            for (int x = 3; x <= 8; x++) {
                pixels[y * width + x] = true;
            }
        }

        final List<ReviewCanvas.LineSegment> segments =
                ReviewCanvas.contrastBoundarySegments(
                        BinaryMask.fromBooleans(width, height, pixels));

        assertEquals(24, segments.size());
        assertTrue(segments.stream().allMatch(segment ->
                        Math.hypot(
                                segment.second().x() - segment.first().x(),
                                segment.second().y() - segment.first().y())
                                == 1.0),
                "the visible contrast outline must contain only adjacent cell edges, never sparse-control chords");
        assertTrue(segments.stream().noneMatch(segment ->
                        segment.first().x() != segment.second().x()
                                && segment.first().y()
                                != segment.second().y()),
                "the tissue suggestion must not expose diagonal warp geometry");
    }

    @Test
    void contrastBoundaryShowsTheOuterEnvelopeNotInternalMaskHoles() {
        final int width = 9;
        final int height = 9;
        final boolean[] pixels = new boolean[width * height];
        for (int y = 1; y <= 7; y++) {
            for (int x = 1; x <= 7; x++) {
                pixels[y * width + x] = true;
            }
        }
        pixels[4 * width + 4] = false;

        final List<ReviewCanvas.LineSegment> segments =
                ReviewCanvas.contrastBoundarySegments(
                        BinaryMask.fromBooleans(width, height, pixels));

        assertEquals(28, segments.size());
        assertTrue(segments.stream().noneMatch(segment ->
                        segment.first().x() >= 4
                                && segment.first().x() <= 5
                                && segment.first().y() >= 4
                                && segment.first().y() <= 5),
                "small internal contrast holes must not be drawn as tissue boundaries");
    }

    @Test
    void placementLayerRetainsAtlasPathsOutsidePreviewBounds() {
        final ReviewCanvas.PlacementLayer layer = ReviewCanvas
                .renderPlacementLayer(List.of(
                        new ReviewCanvas.LineSegment(
                                new Point2D(-45, 12),
                                new Point2D(-20, 12)),
                        new ReviewCanvas.LineSegment(
                                new Point2D(118, 26),
                                new Point2D(145, 26))),
                        new java.awt.Color(255, 120, 20, 230),
                        1.25f, false);

        assertTrue(layer.originX() < -45,
                "offscreen atlas-left geometry must remain in the cache");
        assertTrue(layer.originX() + layer.image().getWidth() > 145,
                "offscreen atlas-right geometry must remain in the cache");
        assertTrue(opaquePixels(layer.image()) > 0);
    }

    @Test
    void tissueClippingRemovesOnlyOverlayOutsideTheEditableSupport() {
        final var basis = ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final var session = new AlignmentReviewSession(basis);
        final AtlasCoronalPlane plane = ReviewPluginFixtures.plane(240);
        final ReviewViewModel model = new ReviewViewModel(
                ReviewPluginFixtures.segmentedPreview(), session.state(),
                session.confidence(), Optional.of(plane), false,
                Optional.empty(), false, Optional.empty(), Optional.empty(),
                false, false, false, false, false);

        final BufferedImage unbounded = ReviewCanvas.overlayImage(
                model, plane, false);
        final BufferedImage clipped = ReviewCanvas.overlayImage(
                model, plane, true);
        final int before = opaquePixels(unbounded);
        final int after = opaquePixels(clipped);

        assertTrue(after > 0);
        assertTrue(after < before,
                "clipping should hide escaped atlas line work without removing the in-tissue overlay");
        assertTrue(hasOpaquePixelOnEachImageSide(clipped),
                "the editable tissue crop must retain partial contralateral anatomy when it intersects support");
    }

    @Test
    void manualWarpLeavesReviewedTissueSupportFixedInPreviewSpace() {
        final var basis = ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final var session = new AlignmentReviewSession(basis);
        final var supportBefore = session.state().content()
                .reviewedTissueSupport().orElseThrow();
        final BinaryMask maskBefore = supportBefore.supportMask();
        final List<ManualWarpControl> controls = List.of(
                warpControl("c1", 10, 10, 3),
                warpControl("c2", 30, 10, 3),
                warpControl("c3", 10, 35, 3),
                warpControl("c4", 30, 35, 3));
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(session.state());
        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                controls, session.state().content().orientation(),
                ReviewSectionMode.FULL,
                basis.previewDimensions().width(),
                basis.previewDimensions().height());
        session.apply(new ReviewEdit.ReplaceManualWarpControlGroup(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                "boundary", controls, precondition, warp));
        final var supportAfter = session.state().content()
                .reviewedTissueSupport().orElseThrow();

        assertEquals(supportBefore.contentSha256(),
                supportAfter.contentSha256());
        assertEquals(supportBefore.polygons(), supportAfter.polygons());
        final BinaryMask maskAfter = supportAfter.supportMask();
        for (int y = 0; y < maskBefore.height(); y++) {
            for (int x = 0; x < maskBefore.width(); x++) {
                assertEquals(maskBefore.contains(x, y),
                        maskAfter.contains(x, y));
            }
        }
    }

    @Test
    void disjoinedRenderingSplitsMidlineCrossingPathsBeforeSidePlacement() {
        final var basis = ReviewPluginFixtures.scaledAtlasBasis();
        final var session = new AlignmentReviewSession(basis);
        session.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.DISJOINED));
        ManualSidePlacement2D placement = ManualSidePlacement2D.identity();
        placement = placement.withTransform(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                previewTranslation(-5, 0));
        session.apply(new ReviewEdit.SetManualSidePlacement(
                ManualHemisphereWarp2D.AtlasSide.LEFT, placement));
        placement = placement.withTransform(
                ManualHemisphereWarp2D.AtlasSide.RIGHT,
                previewTranslation(5, 0));
        session.apply(new ReviewEdit.SetManualSidePlacement(
                ManualHemisphereWarp2D.AtlasSide.RIGHT, placement));
        final int width = 456;
        final int height = 320;
        final int[] labels = new int[width * height];
        for (int y = 100; y < 110; y++) {
            for (int x = 226; x < 230; x++) {
                labels[y * width + x] = 9;
            }
        }
        final AtlasCoronalPlane plane = plane(width, height, labels);
        final ReviewViewModel model = new ReviewViewModel(
                ReviewPluginFixtures.preview(), session.state(),
                session.confidence(), Optional.of(plane), false,
                Optional.empty(), false, Optional.empty(), Optional.empty(),
                false, false, false, false, false);

        final BufferedImage overlay = ReviewCanvas.overlayImage(model, plane);

        assertTrue(overlay.getRGB(40, 20) >>> 24 > 0,
                "the raw atlas-left portion must use only its left placement");
        assertTrue(overlay.getRGB(51, 20) >>> 24 > 0,
                "the raw atlas-right portion must use only its right placement");
        assertEquals(0, overlay.getRGB(46, 20) >>> 24,
                "a path crossing the raw atlas midline must be split rather than reconnecting separated halves");
    }

    @Test
    void disjoinedHardSplitDropsSeamAndCrossMidlineConnectors() {
        final List<ReviewCanvas.LineSegment> left = new java.util.ArrayList<>();
        final List<ReviewCanvas.LineSegment> right = new java.util.ArrayList<>();
        final int width = 9;

        ReviewCanvas.splitDisjoinedSegment(new ReviewCanvas.LineSegment(
                new Point2D(4, 2), new Point2D(4, 7)),
                width, left, right);
        ReviewCanvas.splitDisjoinedSegment(new ReviewCanvas.LineSegment(
                new Point2D(3, 2), new Point2D(5, 2)),
                width, left, right);
        ReviewCanvas.splitDisjoinedSegment(new ReviewCanvas.LineSegment(
                new Point2D(1, 3), new Point2D(3, 3)),
                width, left, right);
        ReviewCanvas.splitDisjoinedSegment(new ReviewCanvas.LineSegment(
                new Point2D(5, 4), new Point2D(7, 4)),
                width, left, right);

        assertEquals(List.of(new ReviewCanvas.LineSegment(
                new Point2D(1, 3), new Point2D(3, 3))), left);
        assertEquals(List.of(new ReviewCanvas.LineSegment(
                new Point2D(5, 4), new Point2D(7, 4))), right);
    }

    @Test
    void halfCanvasShowsOnlyTheConfirmedVisibleAtlasSideByDefault() {
        final var basis = ReviewPluginFixtures.scaledAtlasBasis();
        final var session = new AlignmentReviewSession(basis);
        session.apply(new ReviewEdit.SetReviewSectionMode(
                ReviewSectionMode.HALF));
        session.apply(new ReviewEdit.SetObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT));
        final AtlasCoronalPlane plane = ReviewPluginFixtures.plane(240);

        BufferedImage overlay = ReviewCanvas.overlayImage(
                model(session, plane), plane, false);
        assertTrue(hasOpaquePixelBeforeX(overlay, 45));
        assertFalse(hasOpaquePixelAfterX(overlay, 50),
                "Half must not overlay the hidden atlas hemisphere");

        session.apply(new ReviewEdit.SetHalfAtlasCoverage(
                HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT));
        overlay = ReviewCanvas.overlayImage(
                model(session, plane), plane, false);
        assertTrue(hasOpaquePixelAfterX(overlay, 50),
                "the opposite atlas side appears only after explicit opt-in");
    }

    @Test
    void selectedContourStaysOutOfTheZoomScaledRasterOverlay() {
        final var basis = ReviewPluginFixtures.basis();
        final var session = new AlignmentReviewSession(basis);
        final int width = 100;
        final int height = 80;
        final int[] labels = twoRegions(width, height);
        final AtlasCoronalPlane plane = plane(width, height, labels);
        final SelectedAtlasRegion selected = new SelectedAtlasRegion(
                10, "DG", "Dentate gyrus", Set.of(10, 11));
        final SelectedAtlasContour contour =
                SelectedAtlasContour.from(plane, selected);
        final ReviewViewModel model = new ReviewViewModel(
                ReviewPluginFixtures.preview(), session.state(),
                session.confidence(), Optional.of(plane), false,
                Optional.empty(), false, Optional.of(selected),
                Optional.of(contour), false, false, false, false, false);

        final BufferedImage overlay = ReviewCanvas.overlayImage(model, plane);

        assertRgbNear(0xff7814, overlay.getRGB(10, 10) & 0x00ffffff,
                "the selected target is drawn later as a screen-thin line");
        assertEquals(145, overlay.getRGB(10, 10) >>> 24);
        assertRgbNear(0xff7814, overlay.getRGB(50, 10) & 0x00ffffff,
                "a non-selected structure remains in the full overlay");
        assertEquals(145, overlay.getRGB(50, 10) >>> 24);
        assertEquals(0, overlay.getRGB(15, 20) >>> 24);
    }

    @Test
    void selectedContourIsThinHighContrastAndNeverFillsTheTarget() {
        final int width = 100;
        final int height = 80;
        final AtlasCoronalPlane plane = plane(
                width, height, twoRegions(width, height));
        final SelectedAtlasContour contour = SelectedAtlasContour.from(
                plane, new SelectedAtlasRegion(
                        10, "DG", "Dentate gyrus", Set.of(10, 11)));
        final int zoom = 8;
        final BufferedImage rendered = new BufferedImage(
                width * zoom, height * zoom,
                BufferedImage.TYPE_INT_ARGB);
        final var canvas = rendered.createGraphics();
        try {
            ReviewCanvas.drawSelectedRegionOutline(canvas, contour,
                    point -> new Point2D(
                            point.x() * zoom, point.y() * zoom));
        } finally {
            canvas.dispose();
        }

        int verticalThickness = 0;
        final int sampleX = 15 * zoom;
        for (int y = 10 * zoom - 8; y <= 10 * zoom + 8; y++) {
            if (rendered.getRGB(sampleX, y) >>> 24 != 0) {
                verticalThickness++;
            }
        }
        assertTrue(verticalThickness > 0);
        assertTrue(verticalThickness <= 3,
                "the target line must remain screen-thin at 8x zoom");
        assertEquals(0, rendered.getRGB(
                15 * zoom, 20 * zoom) >>> 24,
                "the selected anatomy must never be rendered as a filled halo");
        final int line = rendered.getRGB(sampleX, 10 * zoom);
        assertTrue(((line >>> 16) & 0xff) > 220
                        && (line & 0xff) > 170,
                "the thin line uses a vivid magenta target colour");
    }

    @Test
    void annotationInteriorIsFullyTransparentAndBoundaryIsOrange() {
        final var basis = ReviewPluginFixtures.basis();
        final var session = new AlignmentReviewSession(basis);
        final int width = 100;
        final int height = 80;
        final int[] labels = new int[width * height];
        for (int y = 10; y < 30; y++) {
            for (int x = 10; x < 30; x++) {
                labels[y * width + x] = 1;
            }
        }
        final AtlasCoronalPlane plane = AtlasCoronalPlane.annotationOnly(
                240,
                width,
                height,
                labels,
                AtlasPlaneGeometry.axisAligned(
                        240, width, height, height, width));
        final ReviewViewModel model = new ReviewViewModel(
                ReviewPluginFixtures.preview(),
                session.state(),
                session.confidence(),
                Optional.of(plane),
                false,
                Optional.empty(),
                false,
                false,
                false,
                false,
                false,
                false);

        final BufferedImage overlay = ReviewCanvas.overlayImage(model, plane);

        assertEquals(0, overlay.getRGB(20, 20) >>> 24,
                "label interiors must not create a blue halo");
        assertEquals(230, overlay.getRGB(10, 10) >>> 24);
        assertRgbNear(0x00ff7814,
                overlay.getRGB(10, 10) & 0x00ffffff,
                "the annotation boundary remains orange");
        assertEquals(0, overlay.getRGB(5, 5) >>> 24);
    }

    @Test
    void localWarpForwardRendersOnlyTheCopiedAtlasContour() {
        final var basis = ReviewPluginFixtures.basis();
        final var session = new AlignmentReviewSession(basis);
        final Point2D[] atlasPoints = {
            new Point2D(10, 10), new Point2D(30, 10),
            new Point2D(30, 30), new Point2D(10, 30)
        };
        for (int index = 0; index < atlasPoints.length; index++) {
            final Point2D point = atlasPoints[index];
            session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                    "fit-" + index, basis.proposal().coronalLevel(),
                    AtlasPlaneTilt.CORONAL, point,
                    new Point2D(point.x() + 3, point.y()))));
        }
        session.apply(new ReviewEdit.FitActiveLandmarksLocalWarp());
        final int width = 100;
        final int height = 80;
        final int[] labels = new int[width * height];
        for (int y = 10; y < 30; y++) {
            for (int x = 10; x < 30; x++) {
                labels[y * width + x] = 1;
            }
        }
        final AtlasCoronalPlane plane = AtlasCoronalPlane.annotationOnly(
                240, width, height, labels,
                AtlasPlaneGeometry.axisAligned(
                        240, width, height, height, width));
        final ReviewViewModel model = new ReviewViewModel(
                ReviewPluginFixtures.preview(), session.state(),
                session.confidence(), Optional.of(plane), false,
                Optional.empty(), false, false, false, false, false, false);

        final BufferedImage overlay = ReviewCanvas.overlayImage(model, plane);

        assertEquals(230, overlay.getRGB(13, 10) >>> 24);
        assertEquals(0, overlay.getRGB(20, 20) >>> 24,
                "local rendering must keep annotation interiors transparent");
        assertEquals(0, overlay.getRGB(5, 5) >>> 24);
    }

    @Test
    void selectedContourDoesNotHideFullAtlasDuringLocalWarp() {
        final var basis = ReviewPluginFixtures.basis();
        final var session = new AlignmentReviewSession(basis);
        final Point2D[] atlasPoints = {
            new Point2D(10, 10), new Point2D(70, 10),
            new Point2D(70, 50), new Point2D(10, 50)
        };
        for (int index = 0; index < atlasPoints.length; index++) {
            final Point2D point = atlasPoints[index];
            session.apply(new ReviewEdit.AddLandmark(new LandmarkPair(
                    "fit-" + index, basis.proposal().coronalLevel(),
                    AtlasPlaneTilt.CORONAL, point,
                    new Point2D(point.x() + 3, point.y()))));
        }
        session.apply(new ReviewEdit.FitActiveLandmarksLocalWarp());
        final int width = 100;
        final int height = 80;
        final AtlasCoronalPlane plane = plane(
                width, height, twoRegions(width, height));
        final SelectedAtlasRegion selected = new SelectedAtlasRegion(
                10, "DG", "Dentate gyrus", Set.of(10, 11));
        final ReviewViewModel model = new ReviewViewModel(
                ReviewPluginFixtures.preview(), session.state(),
                session.confidence(), Optional.of(plane), false,
                Optional.empty(), false, Optional.of(selected),
                Optional.of(SelectedAtlasContour.from(plane, selected)),
                false, false, false, false, false);

        final BufferedImage overlay = ReviewCanvas.overlayImage(model, plane);

        assertRgbNear(0xff7814, overlay.getRGB(13, 10) & 0x00ffffff,
                "the selected highlight is a separate screen-space line");
        assertTrue(overlay.getRGB(53, 10) >>> 24 > 0,
                "the non-selected structure must still be locally warped");
        assertRgbNear(0xff7814,
                overlay.getRGB(53, 10) & 0x00ffffff,
                "the non-selected boundary remains orange");
    }

    @Test
    void outlineWarpForwardRendersEveryCopiedAtlasBoundary() {
        final var basis = ReviewPluginFixtures.basis();
        final var session = new AlignmentReviewSession(basis);
        final List<Point2D> atlasLoop = List.of(
                new Point2D(5, 5), new Point2D(75, 5),
                new Point2D(75, 65), new Point2D(5, 65));
        final List<Point2D> tissueLoop = atlasLoop.stream()
                .map(point -> new Point2D(point.x() + 3, point.y()))
                .toList();
        final ManualOutlineWarp2D warp = ManualOutlineWarp2D.fit(
                atlasLoop, tissueLoop, List.of(
                        new ManualOutlineWarp2D.AnchorPair("D", 0, 0),
                        new ManualOutlineWarp2D.AnchorPair("R", 1, 1),
                        new ManualOutlineWarp2D.AnchorPair("V", 2, 2),
                        new ManualOutlineWarp2D.AnchorPair("L", 3, 3)),
                100, 80);
        final AffineTransform2D identity = new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0, 0, 1, 0);
        session.apply(new ReviewEdit.ApplyGuidedManualCandidate(
                new AllenCoronalLevel(240), AtlasPlaneTilt.CORONAL,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.BOTH, identity,
                warp, true, "manual-outline-test", "candidate-test",
                new AllenCoronalLevel(240), AtlasPlaneTilt.CORONAL,
                0.1, 0.1, 0.1, List.of("outline-1"),
                basis.sourceSnapshot().pixelSha256(),
                basis.atlas().identitySha256()));

        final int width = 100;
        final int height = 80;
        final AtlasCoronalPlane plane = plane(
                width, height, twoRegions(width, height));
        final ReviewViewModel model = new ReviewViewModel(
                ReviewPluginFixtures.preview(), session.state(),
                session.confidence(), Optional.of(plane), false,
                Optional.empty(), false, false, false, false, false, false);

        final BufferedImage overlay = ReviewCanvas.overlayImage(model, plane);

        assertTrue(overlay.getRGB(13, 10) >>> 24 > 0,
                "the first region must use the outline warp");
        assertTrue(overlay.getRGB(53, 10) >>> 24 > 0,
                "the second region must use the same outline warp");
        assertEquals(0, overlay.getRGB(10, 10) >>> 24,
                "the unwarped boundary must not remain in place");
    }

    private static int[] twoRegions(final int width, final int height) {
        final int[] labels = new int[width * height];
        for (int y = 10; y < 30; y++) {
            for (int x = 10; x < 30; x++) {
                labels[y * width + x] = x < 20 ? 10 : 11;
            }
            for (int x = 50; x < 70; x++) {
                labels[y * width + x] = 99;
            }
        }
        return labels;
    }

    private static int opaquePixels(final BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) >>> 24 != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    private static ManualWarpControl warpControl(
            final String identifier,
            final double x,
            final double y,
            final double displacementX) {
        return new ManualWarpControl(identifier,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.TISSUE_BOUNDARY,
                "boundary", "tissue-support",
                new Point2D(x, y), new Point2D(x + displacementX, y));
    }

    private static AffineTransform2D previewTranslation(
            final double x,
            final double y) {
        return new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, x, 0, 1, y);
    }

    private static boolean hasOpaquePixelOnEachImageSide(
            final BufferedImage image) {
        boolean left = false;
        boolean right = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) >>> 24 == 0) {
                    continue;
                }
                left |= x < image.getWidth() / 2;
                right |= x >= image.getWidth() / 2;
            }
        }
        return left && right;
    }

    private static boolean hasOpaquePixelBeforeX(
            final BufferedImage image,
            final int maximumExclusive) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < Math.min(maximumExclusive,
                    image.getWidth()); x++) {
                if (image.getRGB(x, y) >>> 24 != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasOpaquePixelAfterX(
            final BufferedImage image,
            final int minimumInclusive) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = Math.max(0, minimumInclusive);
                    x < image.getWidth(); x++) {
                if (image.getRGB(x, y) >>> 24 != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ReviewViewModel model(
            final AlignmentReviewSession session,
            final AtlasCoronalPlane plane) {
        return new ReviewViewModel(
                ReviewPluginFixtures.preview(), session.state(),
                session.confidence(), Optional.of(plane), false,
                Optional.empty(), false, Optional.empty(), Optional.empty(),
                false, false, false, false, false);
    }

    private static void assertRgbNear(
            final int expected,
            final int actual,
            final String message) {
        final int redDifference = Math.abs(
                (expected >> 16 & 0xff) - (actual >> 16 & 0xff));
        final int greenDifference = Math.abs(
                (expected >> 8 & 0xff) - (actual >> 8 & 0xff));
        final int blueDifference = Math.abs(
                (expected & 0xff) - (actual & 0xff));
        assertTrue(redDifference <= 1 && greenDifference <= 1
                        && blueDifference <= 1,
                message + ": expected RGB near 0x"
                        + Integer.toHexString(expected) + " but was 0x"
                        + Integer.toHexString(actual));
    }

    private static AtlasCoronalPlane plane(
            final int width,
            final int height,
            final int[] labels) {
        return AtlasCoronalPlane.annotationOnly(
                240, width, height, labels,
                AtlasPlaneGeometry.axisAligned(
                        240, width, height, height, width));
    }
}
