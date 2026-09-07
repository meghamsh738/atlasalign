package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.application.manual.ManualHemisphereWarp2D.AtlasSide;
import org.atlasalign.application.manual.ManualHemisphereWarp2D.ImageSide;
import org.atlasalign.application.manual.ManualHemisphereWarp2D.SideControls;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ManualHemisphereWarp2DTest {

    private static final int WIDTH = 400;
    private static final int HEIGHT = 300;
    private static final Point2D DORSAL_MIDLINE = new Point2D(185, 20);
    private static final Point2D VENTRAL_MIDLINE = new Point2D(215, 280);

    @Test
    void previewDomainUsesTheMappedJoinedAtlasMidline() {
        final ManualHemisphereWarp2D.MidlineSegment placedMidline =
                new ManualHemisphereWarp2D.MidlineSegment(
                        new Point2D(135, 15), new Point2D(155, 285));
        final List<ManualWarpControl> controls = List.of(
                typedControl("p1", 45, 55, 2, 0),
                typedControl("p2", 95, 55, 2, 0),
                typedControl("p3", 45, 220, 2, 0),
                typedControl("p4", 95, 220, 2, 0));

        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                controls,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ReviewSectionMode.HALF, placedMidline, WIDTH, HEIGHT);

        final Point2D seamPoint = placedMidline.pointAt(0.6);
        assertEquals(placedMidline, warp.imageMidline());
        assertEquals(seamPoint, warp.apply(seamPoint));
        assertEquals(new Point2D(300, 150),
                warp.apply(AtlasSide.RIGHT, new Point2D(300, 150)));
        assertPoint(new Point2D(47, 55),
                warp.apply(AtlasSide.LEFT, new Point2D(45, 55)), 0.05);
    }

    @Test
    void interiorSeedingRefinesRatherThanResolvingTheAppliedBorderField() {
        final List<ManualWarpControl> boundaryControls = List.of(
                boundaryControl("border-1", 70, 40, -5, -2),
                boundaryControl("border-2", 110, 30, -2, -4),
                boundaryControl("border-3", 150, 38, 1, -3),
                boundaryControl("border-4", 48, 66, -6, -1),
                boundaryControl("border-5", 34, 104, -7, 1),
                boundaryControl("border-6", 30, 150, -8, 0),
                boundaryControl("border-7", 36, 198, -6, 2),
                boundaryControl("border-8", 55, 238, -5, 3),
                boundaryControl("border-9", 86, 260, -2, 4),
                boundaryControl("border-10", 120, 250, 1, 3),
                boundaryControl("border-11", 146, 234, 3, 2),
                boundaryControl("border-12", 160, 208, 2, 1));
        final ManualHemisphereWarp2D borderWarp =
                ManualHemisphereWarp2D.fit(
                        boundaryControls,
                        AtlasOrientation
                                .CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ReviewSectionMode.HALF, WIDTH, HEIGHT);

        final List<ManualWarpControl> combined = new ArrayList<>(
                boundaryControls);
        int identifier = 0;
        for (final double y : List.of(60.0, 100.0, 140.0,
                180.0, 220.0, 255.0)) {
            for (final double x : List.of(58.0, 92.0, 126.0, 160.0)) {
                final Point2D source = new Point2D(x, y);
                combined.add(new ManualWarpControl(
                        "grid-" + (++identifier), AtlasSide.LEFT,
                        ManualWarpControlOrigin.REGULAR_INTERIOR_GRID,
                        "regular-interior-grid", "", source,
                        borderWarp.apply(AtlasSide.LEFT, source)));
            }
        }

        final ManualHemisphereWarp2D refined =
                ManualHemisphereWarp2D.fit(
                        combined,
                        AtlasOrientation
                                .CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ReviewSectionMode.HALF, WIDTH, HEIGHT);
        final ManualHemisphereWarp2D replay =
                ManualHemisphereWarp2D.fit(
                        combined,
                        AtlasOrientation
                                .CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ReviewSectionMode.HALF, WIDTH, HEIGHT);

        assertEquals(36, refined.manualControls(AtlasSide.LEFT).size());
        assertEquals(refined, replay);
        assertEquals(refined.diagnostics().contentSha256(),
                replay.diagnostics().contentSha256());
        for (final ManualWarpControl control : combined) {
            assertPoint(control.targetPoint(), refined.apply(
                    AtlasSide.LEFT, control.sourcePoint()), 0.03);
        }
        for (final Point2D sample : List.of(
                new Point2D(45, 125), new Point2D(75, 185),
                new Point2D(118, 75), new Point2D(150, 175))) {
            assertPoint(borderWarp.apply(AtlasSide.LEFT, sample),
                    refined.apply(AtlasSide.LEFT, sample), 0.35);
        }
        for (final Point2D oppositeSide : List.of(
                new Point2D(235, 50), new Point2D(300, 150),
                new Point2D(365, 250))) {
            assertEquals(oppositeSide,
                    refined.apply(AtlasSide.RIGHT, oppositeSide));
        }
    }

    @Test
    void uncontrolledOffscreenHalfHasNoMeshAndRemainsExactIdentity() {
        final ManualHemisphereWarp2D.MidlineSegment offscreenMidline =
                new ManualHemisphereWarp2D.MidlineSegment(
                        new Point2D(500, 0), new Point2D(500, HEIGHT - 1));
        final List<ManualWarpControl> leftControls = List.of(
                typedControl("left-1", 60, 60, 2, 0),
                typedControl("left-2", 140, 60, 2, 0),
                typedControl("left-3", 60, 220, 2, 0),
                typedControl("left-4", 140, 220, 2, 0));

        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                leftControls,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ReviewSectionMode.HALF, offscreenMidline, WIDTH, HEIGHT);

        final Point2D identity = new Point2D(350, 150);
        final List<Point2D> identityPath = List.of(
                new Point2D(330, 100), new Point2D(370, 200));
        assertTrue(warp.hasControls(AtlasSide.LEFT));
        assertFalse(warp.hasControls(AtlasSide.RIGHT));
        assertTrue(warp.manualControls(AtlasSide.RIGHT).isEmpty());
        assertEquals(identity, warp.apply(AtlasSide.RIGHT, identity));
        assertEquals(identity, warp.inverse(AtlasSide.RIGHT, identity));
        assertEquals(identityPath,
                warp.mapPath(AtlasSide.RIGHT, identityPath, false));
    }

    @Test
    void rightEditIsExactlyIdentityOnImageLeftAndMidline() {
        final ManualHemisphereWarp2D warp = directRightWarp();
        final Point2D imageLeft = new Point2D(100.25, 160.75);
        final Point2D midline = midlinePoint(0.61);

        assertEquals(imageLeft, warp.apply(imageLeft));
        assertEquals(midline, warp.apply(midline));
        assertEquals(midline, warp.inverse(midline));
        assertEquals(1, warp.jacobian(midline).m00(), 0);
        assertEquals(1, warp.jacobian(midline).m11(), 0);
        assertEquals(0, warp.jacobian(midline).m01(), 0);
        assertEquals(0, warp.jacobian(midline).m10(), 0);
    }

    @Test
    void leftEditIsExactlyIdentityOnImageRightAndMidline() {
        final ManualHemisphereWarp2D warp = directLeftWarp();
        final Point2D imageRight = new Point2D(300.25, 160.75);
        final Point2D midline = midlinePoint(0.23);

        assertEquals(imageRight, warp.apply(imageRight));
        assertEquals(midline, warp.apply(midline));
        assertTrue(warp.hasControls(AtlasSide.LEFT));
        assertTrue(!warp.hasControls(AtlasSide.RIGHT));
    }

    @Test
    void reflectedOrientationPreservesAtlasSideMeaning() {
        final List<Point2D> imageRightSources = rightSources();
        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                List.of(new SideControls(AtlasSide.LEFT,
                        imageRightSources, translated(
                        imageRightSources, -0.5, 0.5))),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT,
                DORSAL_MIDLINE, VENTRAL_MIDLINE, WIDTH, HEIGHT);
        final Point2D rightControl = imageRightSources.get(0);
        final Point2D imageLeft = new Point2D(90, 100);

        assertEquals(ImageSide.IMAGE_RIGHT,
                warp.imageSide(AtlasSide.LEFT));
        assertEquals(ImageSide.IMAGE_LEFT,
                warp.imageSide(AtlasSide.RIGHT));
        assertPoint(new Point2D(rightControl.x() - 0.5,
                rightControl.y() + 0.5),
                warp.apply(AtlasSide.LEFT, rightControl), 0.02);
        assertEquals(imageLeft, warp.apply(imageLeft));
        assertEquals(rightControl, warp.apply(AtlasSide.RIGHT, rightControl));
    }

    @Test
    void sourceControlsAreApproximatedWithoutMovingOppositeSide() {
        final ManualHemisphereWarp2D warp = directRightWarp();
        final List<Point2D> sources = rightSources();
        final List<Point2D> targets = rightTargets();

        for (int index = 0; index < sources.size(); index++) {
            assertPoint(targets.get(index), warp.apply(sources.get(index)),
                    0.03);
        }
        assertEquals(CoordinateSpace2D.PREVIEW_PIXEL, warp.sourceSpace());
        assertEquals(CoordinateSpace2D.PREVIEW_PIXEL,
                warp.destinationSpace());
        assertEquals("reviewer-controlled-side-local-compact-pwa-v4-border-preserving-refinement",
                warp.algorithmRevision());
        assertEquals(0, warp.diagnostics().atlasLeftControlCount());
        assertEquals(4, warp.diagnostics().atlasRightControlCount());
        assertEquals(ImageSide.IMAGE_RIGHT,
                warp.sideDiagnostics(AtlasSide.RIGHT).orElseThrow()
                        .imageSide());
        assertTrue(warp.sideDiagnostics(AtlasSide.RIGHT).orElseThrow()
                .controlRms() < 0.03);
        assertTrue(warp.diagnostics().minimumJacobianDeterminant() >= 0.20);
        assertTrue(warp.diagnostics().maximumAnisotropy() <= 3.0);
    }

    @Test
    void rejectsMidlineCrossingFoldingAndUnconfirmedOrientation() {
        final List<Point2D> sources = rightSources();
        final List<Point2D> crossing = new ArrayList<>(rightTargets());
        crossing.set(0, new Point2D(100, crossing.get(0).y()));
        assertThrows(IllegalArgumentException.class,
                () -> ManualHemisphereWarp2D.fit(
                        List.of(new SideControls(AtlasSide.RIGHT,
                                sources, crossing)),
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        DORSAL_MIDLINE, VENTRAL_MIDLINE, WIDTH, HEIGHT));

        final List<Point2D> foldSources = List.of(
                new Point2D(230, 90), new Point2D(330, 90),
                new Point2D(230, 210), new Point2D(330, 210));
        final List<Point2D> foldedTargets = List.of(
                new Point2D(330, 90), new Point2D(230, 90),
                new Point2D(330, 210), new Point2D(230, 210));
        final IllegalArgumentException foldError = assertThrows(
                IllegalArgumentException.class,
                () -> ManualHemisphereWarp2D.fit(
                        List.of(new SideControls(AtlasSide.RIGHT,
                                foldSources, foldedTargets)),
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        DORSAL_MIDLINE, VENTRAL_MIDLINE, WIDTH, HEIGHT));
        assertTrue(foldError.getMessage().contains("Jacobian")
                || foldError.getMessage().contains("singular")
                || foldError.getMessage().contains("residual")
                || foldError.getMessage().contains("fold"));

        assertThrows(IllegalArgumentException.class,
                () -> ManualHemisphereWarp2D.fit(
                        List.of(new SideControls(AtlasSide.RIGHT,
                                sources, rightTargets())),
                        AtlasOrientation.UNCONFIRMED_PROVISIONAL_DIRECT,
                        DORSAL_MIDLINE, VENTRAL_MIDLINE, WIDTH, HEIGHT));
    }

    @Test
    void inverseRoundTripsBothEditedAndIdentityHalves() {
        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                List.of(
                    new SideControls(AtlasSide.LEFT,
                            leftSources(), translated(
                            leftSources(), 3, -2)),
                    new SideControls(AtlasSide.RIGHT,
                            rightSources(), rightTargets())),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                DORSAL_MIDLINE, VENTRAL_MIDLINE, WIDTH, HEIGHT);

        for (final Point2D original : List.of(
                new Point2D(70, 70), new Point2D(125, 145),
                midlinePoint(0.5),
                new Point2D(275, 135), new Point2D(370, 250))) {
            assertPoint(original, warp.inverse(warp.apply(original)), 1e-7);
        }
        assertTrue(warp.diagnostics().maximumInverseRoundTripError()
                <= 1e-6);
    }

    @Test
    void deterministicReplayHasStableHashEqualityAndImmutableControls() {
        final ArrayList<Point2D> mutableSources = new ArrayList<>(
                rightSources());
        final SideControls controls = new SideControls(AtlasSide.RIGHT,
                mutableSources, rightTargets());
        final ManualHemisphereWarp2D first = ManualHemisphereWarp2D.fit(
                List.of(controls),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                DORSAL_MIDLINE, VENTRAL_MIDLINE, WIDTH, HEIGHT);
        final ManualHemisphereWarp2D replay = ManualHemisphereWarp2D.fit(
                List.of(new SideControls(AtlasSide.RIGHT,
                        rightSources(), rightTargets())),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                DORSAL_MIDLINE, VENTRAL_MIDLINE, WIDTH, HEIGHT);

        assertEquals(first, replay);
        assertEquals(first.hashCode(), replay.hashCode());
        assertEquals(first.diagnostics().contentSha256(),
                replay.diagnostics().contentSha256());
        assertEquals(first.diagnostics().contentSha256(),
                first.diagnostics().contentHash());
        assertTrue(first.diagnostics().contentSha256()
                .matches("[0-9a-f]{64}"));
        mutableSources.set(0, new Point2D(1, 1));
        assertEquals(rightSources().get(0), first.controls(AtlasSide.RIGHT)
                .orElseThrow().sourcePoints().get(0));
        assertThrows(UnsupportedOperationException.class,
                () -> first.controls(AtlasSide.RIGHT).orElseThrow()
                        .sourcePoints().add(new Point2D(250, 150)));

        final ManualHemisphereWarp2D changed = ManualHemisphereWarp2D.fit(
                List.of(new SideControls(AtlasSide.RIGHT,
                        rightSources(), translated(rightSources(), 5, 3))),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                DORSAL_MIDLINE, VENTRAL_MIDLINE, WIDTH, HEIGHT);
        assertNotEquals(first, changed);
        assertNotEquals(first.diagnostics().contentSha256(),
                changed.diagnostics().contentSha256());
    }

    @Test
    void reviewerDensityChoicesFitDeterministicallyWithoutAnOutlineMap() {
        for (final int density : List.of(4, 6, 8, 12, 16, 24, 32, 48, 64)) {
            final List<ManualWarpControl> controls = previewControls(density);
            final ManualHemisphereWarp2D first = ManualHemisphereWarp2D.fit(
                    controls,
                    AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                    WIDTH, HEIGHT);
            final ManualHemisphereWarp2D replay = ManualHemisphereWarp2D.fit(
                    controls,
                    AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                    WIDTH, HEIGHT);

            assertEquals(density, first.manualControls(
                    AtlasSide.RIGHT).size());
            assertTrue(first.outlineContentSha256().isBlank());
            assertTrue(first.diagnostics().meshTriangleCount()
                    <= ManualHemisphereWarp2D.MAXIMUM_MESH_TRIANGLES);
            assertEquals(first, replay, "density=" + density);
            assertEquals(first.diagnostics().contentSha256(),
                    replay.diagnostics().contentSha256(),
                    "density=" + density);
        }
    }

    @Test
    void supportsFourDenseGroupsUpToTwoHundredFiftySixControlsPerSide() {
        final List<ManualWarpControl> controls = capacityControls(256);
        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                controls,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                WIDTH, HEIGHT);

        assertEquals(304, ManualHemisphereWarp2D.MAXIMUM_CONTROLS_PER_SIDE);
        assertEquals(256, ManualHemisphereWarp2D
                .MAXIMUM_REFINEMENT_CONTROLS_PER_SIDE);
        assertEquals(256, warp.manualControls(AtlasSide.RIGHT).size());
        final List<ManualWarpControl> tooMany = new ArrayList<>(controls);
        tooMany.add(new ManualWarpControl(
                "capacity-256", AtlasSide.RIGHT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID,
                "capacity-group-4", "", new Point2D(390, 280),
                new Point2D(390, 280)));
        final ManualWarpException error = assertThrows(
                ManualWarpException.class,
                () -> ManualHemisphereWarp2D.fit(tooMany,
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        WIDTH, HEIGHT));
        assertEquals(ManualWarpFailureKind.CONTROL_LIMIT, error.kind());
        assertTrue(error.userMessage().contains("256"));
        assertFalse(error.userMessage().contains("Jacobian"));
    }

    @Test
    void reservesFortyEightBorderControlsBeyondDenseRefinementControls() {
        final List<ManualWarpControl> controls = new ArrayList<>(
                capacityControls(256));
        for (int index = 0; index < 48; index++) {
            final double angle = 2 * Math.PI * index / 48;
            final Point2D point = new Point2D(
                    300 + 88 * Math.cos(angle),
                    150 + 132 * Math.sin(angle));
            controls.add(new ManualWarpControl(
                    "reserved-border-" + index, AtlasSide.RIGHT,
                    ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR,
                    "outer-boundary", "", point, point));
        }

        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                controls,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                WIDTH, HEIGHT);

        assertEquals(304, warp.manualControls(AtlasSide.RIGHT).size());
        assertEquals(48, warp.manualControls(AtlasSide.RIGHT).stream()
                .filter(control -> control.origin()
                        == ManualWarpControlOrigin
                        .ATLAS_TISSUE_BOUNDARY_PAIR).count());
    }

    @Test
    void oneMovedDotHasCompactLocalInfluence() {
        final List<ManualWarpControl> controls = List.of(
                control("moved", new Point2D(240, 100),
                        new Point2D(244, 102)),
                control("fixed-2", new Point2D(300, 80),
                        new Point2D(300, 80)),
                control("fixed-3", new Point2D(340, 150),
                        new Point2D(340, 150)),
                control("fixed-4", new Point2D(280, 230),
                        new Point2D(280, 230)));
        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                controls,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                WIDTH, HEIGHT);

        assertEquals(new Point2D(244, 102),
                warp.apply(new Point2D(240, 100)));
        assertEquals(new Point2D(385, 275),
                warp.apply(new Point2D(385, 275)),
                "a point outside compact support must stay exact identity");
        assertEquals(new Point2D(80, 140),
                warp.apply(new Point2D(80, 140)),
                "the opposite anatomical side must stay exact identity");
        assertEquals(new Point2D((WIDTH - 1.0) * 0.5, 140),
                warp.apply(new Point2D((WIDTH - 1.0) * 0.5, 140)),
                "the anatomical seam must stay exact identity");
    }

    @Test
    void previewDomainReleaseSolveMeetsInteractiveTargets() {
        final List<ManualWarpControl> controls = new ArrayList<>(
                previewControls(24));
        final ManualWarpControl moved = controls.get(0);
        controls.set(0, new ManualWarpControl(
                moved.id(), moved.atlasSide(), moved.origin(),
                moved.groupId(), moved.structureAcronym(),
                moved.sourcePoint(), new Point2D(
                        moved.targetPoint().x() + 2,
                        moved.targetPoint().y() + 1)));

        ManualHemisphereWarp2D.fit(controls,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                WIDTH, HEIGHT);
        final List<Double> solveMillis = new ArrayList<>();
        for (int run = 0; run < 5; run++) {
            solveMillis.add(ManualHemisphereWarp2D.fit(controls,
                    AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                    WIDTH, HEIGHT).diagnostics().solveMillis());
        }
        Collections.sort(solveMillis);
        final double median = solveMillis.get(solveMillis.size() / 2);
        final double worst = solveMillis.get(solveMillis.size() - 1);

        System.out.println("reviewer-controlled preview solve: median="
                + median + " ms, worst=" + worst + " ms, runs="
                + solveMillis);
        if (Boolean.parseBoolean(System.getProperty("atlasalign.performanceChecks", "true"))) {
            assertTrue(median < 500,
                    "preview-domain median release solve exceeded 500 ms: "
                            + solveMillis);
            assertTrue(worst < 1500,
                    "preview-domain worst release solve exceeded 1.5 s: "
                            + solveMillis);
        } else {
            System.out.println("Preview solve timing budgets disabled; local targets remain "
                    + "median < 500 ms and worst < 1500 ms");
        }
    }

    private static List<ManualWarpControl> previewControls(
            final int density) {
        final List<ManualWarpControl> result = new ArrayList<>();
        for (int index = 0; index < density; index++) {
            final int column = index % 8;
            final int row = index / 8;
            final Point2D point = new Point2D(
                    210 + column * 24,
                    20 + row * 34);
            result.add(control("density-" + density + "-" + index,
                    point, point));
        }
        return List.copyOf(result);
    }

    private static List<ManualWarpControl> capacityControls(
            final int density) {
        final List<ManualWarpControl> result = new ArrayList<>();
        for (int index = 0; index < density; index++) {
            final int column = index % 16;
            final int row = index / 16;
            final Point2D point = new Point2D(
                    205 + column * 12,
                    5 + row * 18);
            result.add(new ManualWarpControl(
                    "capacity-" + index, AtlasSide.RIGHT,
                    ManualWarpControlOrigin.REGULAR_INTERIOR_GRID,
                    "capacity-group-" + index / 64,
                    "", point, point));
        }
        return List.copyOf(result);
    }

    private static ManualWarpControl control(
            final String id,
            final Point2D source,
            final Point2D target) {
        return new ManualWarpControl(
                id, AtlasSide.RIGHT,
                ManualWarpControlOrigin.TISSUE_BOUNDARY,
                "tissue-boundary", "", source, target);
    }

    private static ManualWarpControl typedControl(
            final String id,
            final double x,
            final double y,
            final double dx,
            final double dy) {
        return new ManualWarpControl(
                id, AtlasSide.LEFT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID,
                "regular-interior-grid", "",
                new Point2D(x, y), new Point2D(x + dx, y + dy));
    }

    private static ManualWarpControl boundaryControl(
            final String id,
            final double x,
            final double y,
            final double dx,
            final double dy) {
        return new ManualWarpControl(
                id, AtlasSide.LEFT,
                ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR,
                "atlas-tissue-boundary-pairs", "",
                new Point2D(x, y), new Point2D(x + dx, y + dy));
    }

    private static ManualHemisphereWarp2D directRightWarp() {
        return ManualHemisphereWarp2D.fit(
                List.of(new SideControls(AtlasSide.RIGHT,
                        rightSources(), rightTargets())),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                DORSAL_MIDLINE, VENTRAL_MIDLINE, WIDTH, HEIGHT);
    }

    private static ManualHemisphereWarp2D directLeftWarp() {
        return ManualHemisphereWarp2D.fit(
                List.of(new SideControls(AtlasSide.LEFT,
                        leftSources(), translated(leftSources(), 3, -2))),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                DORSAL_MIDLINE, VENTRAL_MIDLINE, WIDTH, HEIGHT);
    }

    private static List<Point2D> rightSources() {
        return List.of(
                new Point2D(250, 100), new Point2D(330, 100),
                new Point2D(250, 200), new Point2D(330, 200));
    }

    private static List<Point2D> rightTargets() {
        return List.of(
                new Point2D(255, 103), new Point2D(334, 98),
                new Point2D(253, 204), new Point2D(332, 201));
    }

    private static List<Point2D> leftSources() {
        return List.of(
                new Point2D(70, 100), new Point2D(150, 100),
                new Point2D(70, 200), new Point2D(150, 200));
    }

    private static List<Point2D> translated(
            final List<Point2D> sources,
            final double dx,
            final double dy) {
        return sources.stream().map(point -> new Point2D(
                point.x() + dx, point.y() + dy)).toList();
    }

    private static Point2D midlinePoint(final double fraction) {
        return new ManualHemisphereWarp2D.MidlineSegment(
                DORSAL_MIDLINE, VENTRAL_MIDLINE).pointAt(fraction);
    }

    private static void assertPoint(
            final Point2D expected,
            final Point2D actual,
            final double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(expected.y(), actual.y(), tolerance);
    }
}
