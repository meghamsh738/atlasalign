package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.manual.ManualHemisphereWarp2D.AtlasSide;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ManualHemisphereWarpMeshTest {

    private static final int WIDTH = 400;
    private static final int HEIGHT = 300;
    private static final List<Point2D> OUTLINE = List.of(
            new Point2D(200, 50), new Point2D(300, 100),
            new Point2D(300, 200), new Point2D(200, 250),
            new Point2D(100, 200), new Point2D(100, 100));

    @Test
    void boundarySeamAndOppositeSideRemainExactIdentity() {
        final ManualHemisphereWarp2D warp = fit(controls(6));
        for (final Point2D boundary : OUTLINE) {
            assertEquals(boundary, warp.apply(boundary));
        }
        for (int index = 1; index < 9; index++) {
            final Point2D seam = new Point2D(200, 50 + 25 * index);
            assertEquals(seam, warp.apply(seam));
        }
        assertEquals(new Point2D(150, 150),
                warp.apply(new Point2D(150, 150)));
        assertEquals(0, warp.diagnostics().atlasLeftControlCount());
        assertTrue(warp.diagnostics().meshTriangleCount() > 0);
        assertTrue(warp.diagnostics().meshTriangleCount()
                <= ManualHemisphereWarp2D.MAXIMUM_MESH_TRIANGLES);
    }

    @Test
    void reflectedOrientationKeepsAnatomicalSidesIndependent() {
        final List<ManualWarpControl> atlasLeftOnImageRight =
                controlsOnImageRight(6, AtlasSide.LEFT);
        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                atlasLeftOnImageRight,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT,
                outline());
        for (final ManualWarpControl control : atlasLeftOnImageRight) {
            assertEquals(control.targetPoint(),
                    warp.apply(AtlasSide.LEFT, control.sourcePoint()));
        }
        final Point2D oppositeImageSide = new Point2D(150, 150);
        assertEquals(oppositeImageSide, warp.apply(oppositeImageSide));
        assertEquals(oppositeImageSide,
                warp.apply(AtlasSide.RIGHT, oppositeImageSide));
    }

    @Test
    void controlsAreExactAndInverseRoundTripIsDeterministic() {
        final List<ManualWarpControl> controls = controls(6);
        final ManualHemisphereWarp2D first = fit(controls);
        final ManualHemisphereWarp2D replay = fit(controls);
        assertEquals(first, replay);
        assertEquals(first.diagnostics().contentSha256(),
                replay.diagnostics().contentSha256());
        assertEquals(first.outlineContentSha256(), outline().contentSha256());
        for (final ManualWarpControl control : controls) {
            assertEquals(control.targetPoint(),
                    first.apply(control.sourcePoint()));
            assertEquals(control.sourcePoint(),
                    first.inverse(control.targetPoint()));
        }
        assertNotEquals(first.apply(new Point2D(250, 150)),
                new Point2D(250, 150));
        final ManualWarpControl control = controls.get(0);
        final Point2D near = new Point2D(
                control.sourcePoint().x() + 1e-5,
                control.sourcePoint().y() + 1e-5);
        assertTrue(distance(first.apply(near), control.targetPoint()) < 1e-3,
                "a connected control vertex must not create a point override discontinuity");
    }

    @Test
    void controlDensityRangeAndUnsafeRequestAreBounded() {
        for (final int count : List.of(4, 6, 12, 24)) {
            final ManualHemisphereWarp2D warp = fit(controls(count));
            assertEquals(count, warp.controls(AtlasSide.RIGHT).size());
            assertTrue(warp.diagnostics().minimumJacobianDeterminant() >= 0.20);
            assertTrue(warp.diagnostics().minimumSingularValue() >= 0.30);
            assertTrue(warp.diagnostics().maximumSingularValue() <= 3.0);
            assertTrue(warp.diagnostics().maximumAnisotropy() <= 3.0);
        }
        final List<ManualWarpControl> unsafe = controls(4);
        final ManualWarpControl first = unsafe.get(0);
        unsafe.set(0, new ManualWarpControl(first.id(), first.atlasSide(),
                first.origin(), first.groupId(), first.structureAcronym(),
                first.sourcePoint(), new Point2D(190, 150)));
        assertThrows(IllegalArgumentException.class, () -> fit(unsafe));
    }

    @Test
    void concaveReviewedSideRemainsContainedAndAreaPreserving() {
        final List<Point2D> concave = List.of(
                new Point2D(200, 40),
                new Point2D(330, 80), new Point2D(330, 120),
                new Point2D(265, 150),
                new Point2D(330, 180), new Point2D(330, 220),
                new Point2D(200, 260),
                new Point2D(70, 220), new Point2D(70, 180),
                new Point2D(135, 150),
                new Point2D(70, 120), new Point2D(70, 80));
        final BoundaryAuthoritativeTransform2D exact = exactOutline(
                concave, concave);
        final List<ManualWarpControl> controls = List.of(
                control("c-1", new Point2D(220, 90)),
                control("c-2", new Point2D(245, 110)),
                control("c-3", new Point2D(220, 190)),
                control("c-4", new Point2D(245, 210)));

        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                controls,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                exact);

        for (final Point2D boundary : exact.tissueBoundary()) {
            assertEquals(boundary, warp.apply(boundary));
        }
        assertTrue(warp.diagnostics().minimumJacobianDeterminant() >= 0.20);
        assertTrue(warp.diagnostics().meshTriangleCount()
                <= ManualHemisphereWarp2D.MAXIMUM_MESH_TRIANGLES);
    }

    @Test
    void nonIdentityOutlineMidlineEndpointsAndWholeSeamStayFixed() {
        final List<Point2D> atlas = List.of(
                new Point2D(100, 50), new Point2D(200, 50),
                new Point2D(300, 50), new Point2D(300, 250),
                new Point2D(200, 250), new Point2D(100, 250));
        final List<Point2D> tissue = List.of(
                new Point2D(90, 60), new Point2D(310, 60),
                new Point2D(310, 240), new Point2D(90, 240));
        final BoundaryAuthoritativeTransform2D exact = exactOutline(
                atlas, tissue);
        final List<ManualWarpControl> controls = new ArrayList<>();
        final List<Point2D> atlasSources = List.of(
                new Point2D(230, 90), new Point2D(265, 120),
                new Point2D(230, 180), new Point2D(265, 210));
        for (int index = 0; index < atlasSources.size(); index++) {
            controls.add(control("n-" + index,
                    exact.apply(atlasSources.get(index))));
        }
        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                controls,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                exact);
        assertEquals(exact.hemisphereMidlinePath(), warp.imageMidlinePath());
        final List<Point2D> seam = warp.imageMidlinePath();
        for (int index = 0; index < seam.size(); index++) {
            final Point2D point = seam.get(index);
            assertEquals(point, warp.apply(point));
            if (index + 1 < seam.size()) {
                final Point2D midpoint = new Point2D(
                        (point.x() + seam.get(index + 1).x()) / 2.0,
                        (point.y() + seam.get(index + 1).y()) / 2.0);
                assertEquals(midpoint, warp.apply(midpoint));
            }
        }
        assertEquals(exact.contentSha256(), warp.outlineContentSha256());
    }

    @Test
    void topologyAndHarmonicFactorizationAreReusedWhenOnlyTargetsMove() {
        ManualHemisphereWarp2D.clearTopologyCacheForTests();
        final List<ManualWarpControl> controls = controls(6);
        fit(controls);
        final long afterInitial =
                ManualHemisphereWarp2D.topologyBuildCountForTests();
        final List<ManualWarpControl> movedTargets = new ArrayList<>(controls);
        final ManualWarpControl first = movedTargets.get(0);
        movedTargets.set(0, new ManualWarpControl(
                first.id(), first.atlasSide(), first.origin(),
                first.groupId(), first.structureAcronym(),
                first.sourcePoint(), new Point2D(
                        first.targetPoint().x() + 0.1,
                        first.targetPoint().y() - 0.1)));
        fit(movedTargets);
        assertEquals(afterInitial,
                ManualHemisphereWarp2D.topologyBuildCountForTests());

        final List<ManualWarpControl> movedSource = new ArrayList<>(controls);
        movedSource.set(0, new ManualWarpControl(
                first.id(), first.atlasSide(), first.origin(),
                first.groupId(), first.structureAcronym(),
                new Point2D(first.sourcePoint().x() + 0.2,
                        first.sourcePoint().y()),
                new Point2D(first.targetPoint().x() + 0.2,
                        first.targetPoint().y())));
        fit(movedSource);
        assertTrue(ManualHemisphereWarp2D.topologyBuildCountForTests()
                > afterInitial);
    }

    @Test
    void twentyFourControlSolveMeetsBoundedReleaseTargetOnFixture() {
        final ManualHemisphereWarp2D warp = fit(controls(24));
        assertTrue(warp.diagnostics().solveMillis() < 1500,
                "fixture solve exceeded the 1.5 s worst-case release target: "
                        + warp.diagnostics().solveMillis() + " ms");
        assertFalse(warp.controls().isEmpty());
    }

    @Test
    void pathMappingSplitsAtMeshEdgesAndControlProvenanceIsTyped() {
        final ManualHemisphereWarp2D warp = fit(controls(6));
        final List<Point2D> mapped = warp.mapPath(List.of(
                new Point2D(220, 80), new Point2D(290, 190)), false);
        assertTrue(mapped.size() > 2);
        assertEquals(6, warp.controls().size());
        assertTrue(warp.controls().stream().allMatch(control ->
                control.origin() == ManualWarpControlOrigin.USER_PLACED_INTERIOR));
    }

    private static ManualHemisphereWarp2D fit(
            final List<ManualWarpControl> controls) {
        return ManualHemisphereWarp2D.fit(controls,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                outline());
    }

    private static BoundaryAuthoritativeTransform2D outline() {
        return exactOutline(OUTLINE, OUTLINE);
    }

    private static BoundaryAuthoritativeTransform2D exactOutline(
            final List<Point2D> atlas,
            final List<Point2D> tissue) {
        return BoundaryAuthoritativeTransform2D.fitFull(
                MonotoneBoundary2D.arcLengthIndexed("atlas", atlas),
                MonotoneBoundary2D.arcLengthIndexed("tissue", tissue),
                WIDTH, HEIGHT);
    }

    private static List<ManualWarpControl> controls(final int count) {
        return controlsOnImageRight(count, AtlasSide.RIGHT);
    }

    private static List<ManualWarpControl> controlsOnImageRight(
            final int count,
            final AtlasSide atlasSide) {
        final List<ManualWarpControl> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            final int column = index % 6;
            final int row = index / 6;
            final Point2D source = new Point2D(
                    220 + column * 12, 112 + row * 26);
            result.add(new ManualWarpControl("right-" + index, atlasSide,
                    ManualWarpControlOrigin.USER_PLACED_INTERIOR, "grid", "",
                    source, new Point2D(source.x() + 0.5,
                    source.y() + 0.5)));
        }
        return result;
    }

    private static ManualWarpControl control(
            final String id,
            final Point2D source) {
        return new ManualWarpControl(id, AtlasSide.RIGHT,
                ManualWarpControlOrigin.USER_PLACED_INTERIOR, "grid", "",
                source, new Point2D(source.x() + 0.25,
                source.y() + 0.25));
    }

    private static double distance(
            final Point2D first,
            final Point2D second) {
        return Math.hypot(first.x() - second.x(),
                first.y() - second.y());
    }
}
