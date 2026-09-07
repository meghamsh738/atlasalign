package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class BoundaryAuthoritativeTransform2DTest {

    @Test
    void convexIdentityIsExactAuditedAndDeterministic() {
        final MonotoneBoundary2D boundary = boundary("b-", List.of(
                p(20, 20), p(180, 20), p(180, 140), p(20, 140)));

        final BoundaryAuthoritativeTransform2D first = fit(boundary, boundary);
        final BoundaryAuthoritativeTransform2D replay = fit(boundary, boundary);

        assertPoint(p(100, 80), first.apply(p(100, 80)), 1e-9);
        assertEquals(CoordinateSpace2D.PREVIEW_PIXEL, first.sourceSpace());
        assertEquals(CoordinateSpace2D.PREVIEW_PIXEL,
                first.destinationSpace());
        assertEquals(0.0, first.diagnostics().maximumBoundaryErrorPixels(),
                1e-9);
        assertTrue(first.diagnostics().minimumSourceSignedDoubleArea() > 0);
        assertTrue(first.diagnostics().minimumTargetSignedDoubleArea() > 0);
        assertEquals(first.diagnostics().tissuePolygonArea(),
                first.diagnostics().targetMeshArea(), 1e-8);
        assertEquals(first.diagnostics().contentSha256(),
                replay.diagnostics().contentSha256());
        assertEquals(first.triangles(), replay.triangles());
        assertEquals(64, first.diagnostics().contentSha256().length());
    }

    @Test
    void affineStretchMapsBoundaryAndInteriorExactly() {
        final MonotoneBoundary2D atlas = boundary("a-", List.of(
                p(20, 20), p(180, 20), p(180, 140), p(20, 140)));
        final MonotoneBoundary2D tissue = boundary("t-", atlas.vertices()
                .stream().map(vertex -> affine(vertex.point())).toList());
        final BoundaryAuthoritativeTransform2D transform = fit(atlas, tissue);

        assertPoint(affine(p(100, 80)), transform.apply(p(100, 80)), 1e-8);
        assertPoint(affine(p(20, 80)), transform.apply(p(20, 80)), 1e-8);
        assertTrue(transform.containsTissuePoint(affine(p(100, 80))));
        assertFalse(transform.containsAtlasPoint(p(5, 5)));
        assertTrue(transform.diagnostics().maximumBoundaryErrorPixels()
                <= BoundaryAuthoritativeTransform2D
                        .MAXIMUM_BOUNDARY_ERROR_PIXELS);
    }

    @Test
    void asymmetricOutlineUsesExactMappedAtlasMidlineAndSideDomains() {
        final MonotoneBoundary2D atlas = boundary("a-", List.of(
                p(15, 20), p(80, 8), p(185, 25), p(190, 145),
                p(95, 158), p(10, 135)));
        final MonotoneBoundary2D tissue = boundary("t-", List.of(
                p(30, 35), p(210, 20), p(220, 125), p(170, 160),
                p(45, 150), p(20, 90)));
        final BoundaryAuthoritativeTransform2D transform = fit(atlas, tissue);

        final var atlasMidline = transform.atlasHemisphereMidline();
        final var tissueMidline = transform.hemisphereMidline();
        final List<Point2D> mappedPath = transform.hemisphereMidlinePath();

        assertEquals(tissueMidline.dorsal(), mappedPath.get(0));
        assertEquals(tissueMidline.ventral(),
                mappedPath.get(mappedPath.size() - 1));
        assertTrue(mappedPath.size() > 2,
                "The asymmetric exact map must exercise a bent mapped seam");
        assertTrue(mappedPath.stream().anyMatch(point ->
                Math.abs(point.x() - 120.0) > 1.0),
                "The atlas midline image must not be replaced by the tissue-bounds centreline");
        for (int index = 0; index <= 20; index++) {
            final double fraction = index / 20.0;
            final Point2D source = new Point2D(
                    atlasMidline.dorsal().x(),
                    atlasMidline.dorsal().y() + fraction
                            * (atlasMidline.ventral().y()
                                    - atlasMidline.dorsal().y()));
            final Point2D mapped = transform.apply(source);
            assertTrue(distanceToPath(mapped, mappedPath) <= 1e-7);
            assertTrue(transform.containsTissueHemispherePoint(true, mapped));
            assertTrue(transform.containsTissueHemispherePoint(false, mapped));
        }
        final Point2D mappedLeft = transform.apply(p(55, 90));
        final Point2D mappedRight = transform.apply(p(150, 90));
        assertTrue(transform.containsTissueHemispherePoint(true, mappedLeft));
        assertFalse(transform.containsTissueHemispherePoint(false, mappedLeft));
        assertTrue(transform.containsTissueHemispherePoint(false, mappedRight));
        assertFalse(transform.containsTissueHemispherePoint(true, mappedRight));
    }

    @Test
    void concaveBeanCrescentAndNotchRemainContainedAndPositive() {
        for (final List<Point2D> atlasPoints : List.of(
                bean(), crescent(), notch())) {
            final List<Point2D> tissuePoints = atlasPoints.stream()
                    .map(point -> new Point2D(
                            1.08 * point.x() + 0.06 * point.y() + 7,
                            -0.03 * point.x() + 0.93 * point.y() + 11))
                    .toList();
            final BoundaryAuthoritativeTransform2D transform = fit(
                    boundary("a-", atlasPoints),
                    boundary("t-", tissuePoints));

            assertTrue(transform.diagnostics().maximumBoundaryErrorPixels()
                    <= 0.25);
            assertTrue(transform.diagnostics()
                    .minimumSourceSignedDoubleArea() > 0);
            assertTrue(transform.diagnostics()
                    .minimumTargetSignedDoubleArea() > 0);
            for (final BoundaryAuthoritativeTransform2D.MeshTriangle triangle
                    : transform.triangles()) {
                assertTrue(transform.containsTissuePoint(
                        triangle.target().centroid()));
            }
        }
    }

    @Test
    void differingBoundarySamplesPreserveEveryReviewedCorner() {
        final MonotoneBoundary2D atlas = boundary("a-", List.of(
                p(20, 20), p(180, 20), p(180, 140), p(20, 140)));
        final MonotoneBoundary2D tissue = new MonotoneBoundary2D(List.of(
                vertex("t0", p(30, 25), 0.0),
                vertex("t1", p(190, 25), 0.25),
                vertex("t2", p(205, 85), 0.375),
                vertex("t3", p(190, 150), 0.50),
                vertex("t4", p(25, 150), 0.75)));
        final BoundaryAuthoritativeTransform2D transform = fit(atlas, tissue);

        assertEquals(5, transform.commonBoundaryParameters().size());
        for (int index = 0; index < transform.atlasBoundary().size(); index++) {
            assertPoint(transform.tissueBoundary().get(index),
                    transform.apply(transform.atlasBoundary().get(index)),
                    1e-8);
        }
    }

    @Test
    void mapsEveryPiecewiseAffineRoiSegmentWithoutFalseChords() {
        final MonotoneBoundary2D atlas = boundary("a-", notch());
        final MonotoneBoundary2D tissue = boundary("t-", List.of(
                p(18, 20), p(190, 28), p(173, 72), p(210, 122),
                p(181, 154), p(22, 145), p(48, 93), p(12, 63)));
        final BoundaryAuthoritativeTransform2D transform = fit(atlas, tissue);
        final List<Point2D> path = List.of(p(45, 45), p(150, 118));

        final List<Point2D> mapped = transform.mapPath(path, false);

        assertTrue(mapped.size() > path.size(),
                "A segment crossing mesh edges must be explicitly split");
        assertPoint(transform.apply(path.get(0)), mapped.get(0), 1e-9);
        assertPoint(transform.apply(path.get(1)),
                mapped.get(mapped.size() - 1), 1e-9);
        mapped.forEach(point -> assertTrue(transform.containsTissuePoint(point)));
    }

    @Test
    void monotoneIdentitySurvivesMoveInsertAndDelete() {
        final MonotoneBoundary2D original = boundary("b-", List.of(
                p(20, 20), p(180, 20), p(180, 140), p(20, 140)));
        final MonotoneBoundary2D moved = original.move("b-1", p(185, 25));
        final MonotoneBoundary2D inserted = moved.insertAfter(
                "b-1", "corner-extra", p(190, 80), true);
        final MonotoneBoundary2D deleted = inserted.delete("corner-extra");

        assertEquals(original.vertices().get(1).cyclicParameter(),
                moved.vertices().get(1).cyclicParameter());
        assertTrue(inserted.vertices().get(2).cyclicParameter() > 0.25);
        assertTrue(inserted.vertices().get(2).cyclicParameter() < 0.50);
        assertTrue(inserted.vertices().get(2).semanticBoundary());
        assertEquals(moved.vertices(), deleted.vertices());
        assertThrows(UnsupportedOperationException.class,
                () -> original.vertices().add(vertex("x", p(1, 1), 0.9)));
    }

    @Test
    void rejectsDuplicateCrossingPinchOrientationAndUnsupportedTopology() {
        final BoundaryGeometryException duplicate = assertThrows(
                BoundaryGeometryException.class, () -> fit(
                        boundary("a-", List.of(p(0, 0), p(5, 0),
                                p(5, 0), p(0, 5))),
                        boundary("t-", List.of(p(0, 0), p(5, 0),
                                p(5, 5), p(0, 5)))));
        assertEquals(BoundaryGeometryFailure.Kind.ZERO_LENGTH_EDGE,
                duplicate.failure().kind());

        final BoundaryGeometryException crossing = assertThrows(
                BoundaryGeometryException.class, () -> fit(
                        boundary("a-", List.of(p(0, 0), p(6, 6),
                                p(0, 6), p(6, 0))),
                        boundary("t-", square())));
        assertEquals(BoundaryGeometryFailure.Kind.SELF_CROSSING,
                crossing.failure().kind());
        assertTrue(crossing.failure().location().isPresent());

        final BoundaryGeometryException pinch = assertThrows(
                BoundaryGeometryException.class, () -> fit(
                        boundary("a-", List.of(p(0, 0), p(6, 0),
                                p(3, 3), p(6, 6), p(0, 6), p(3, 3))),
                        boundary("t-", square())));
        assertEquals(BoundaryGeometryFailure.Kind.SELF_TOUCHING_PINCH,
                pinch.failure().kind());

        final List<Point2D> reversed = new ArrayList<>(square());
        java.util.Collections.reverse(reversed);
        final BoundaryGeometryException orientation = assertThrows(
                BoundaryGeometryException.class, () -> fit(
                        boundary("a-", square()), boundary("t-", reversed)));
        assertEquals(BoundaryGeometryFailure.Kind.INCONSISTENT_ORIENTATION,
                orientation.failure().kind());

        final BoundaryGeometryException components = assertThrows(
                BoundaryGeometryException.class,
                () -> BoundaryAuthoritativeTransform2D.fitFullComponents(
                        List.of(boundary("a-", square()),
                                boundary("hole-", smallSquare())),
                        List.of(boundary("t-", square())), 256, 192));
        assertEquals(BoundaryGeometryFailure.Kind.UNSUPPORTED_TOPOLOGY,
                components.failure().kind());
    }

    @Test
    void nestedRoiIsContainedAndReplayHashChangesWithReviewedBoundary() {
        final MonotoneBoundary2D atlas = boundary("a-", bean());
        final MonotoneBoundary2D tissue = boundary("t-", bean().stream()
                .map(BoundaryAuthoritativeTransform2DTest::affine).toList());
        final BoundaryAuthoritativeTransform2D transform = fit(atlas, tissue);
        final List<Point2D> nested = List.of(
                p(65, 65), p(130, 65), p(125, 105), p(70, 110));

        final List<Point2D> mapped = transform.mapPath(nested, true);
        mapped.forEach(point -> assertTrue(transform.containsTissuePoint(point)));

        final MonotoneBoundary2D edited = tissue.move("t-2",
                new Point2D(tissue.vertices().get(2).point().x() + 2,
                        tissue.vertices().get(2).point().y()));
        final BoundaryAuthoritativeTransform2D changed = fit(atlas, edited);
        assertNotEquals(transform.diagnostics().contentSha256(),
                changed.diagnostics().contentSha256());
    }

    @Test
    void outsideCoordinatesFailWithTypedLocalDiagnostic() {
        final BoundaryAuthoritativeTransform2D transform = fit(
                boundary("a-", square()), boundary("t-", square()));
        final BoundaryGeometryException error = assertThrows(
                BoundaryGeometryException.class,
                () -> transform.apply(p(-10, -10)));
        assertEquals(BoundaryGeometryFailure.Kind.OUTSIDE_DOMAIN,
                error.failure().kind());
        assertTrue(error.failure().location().isPresent());
    }

    @Test
    void denseDifferingBoundariesCompleteTheExactOverlapAuditPromptly() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            final BoundaryAuthoritativeTransform2D transform = fit(
                    boundary("dense-a-", denseLoop(192, 0.0, 1.0, 1.0)),
                    boundary("dense-t-", denseLoop(
                            192, 0.41, 1.06, 0.86)));

            assertTrue(transform.triangles().size() > 1_000,
                    "The regression fixture must exercise a dense common refinement");
            assertEquals(transform.diagnostics().tissuePolygonArea(),
                    transform.diagnostics().targetMeshArea(), 1e-8);
        });
    }

    private static BoundaryAuthoritativeTransform2D fit(
            final MonotoneBoundary2D atlas,
            final MonotoneBoundary2D tissue) {
        return BoundaryAuthoritativeTransform2D.fitFull(
                atlas, tissue, 256, 192);
    }

    private static MonotoneBoundary2D boundary(
            final String prefix, final List<Point2D> points) {
        return MonotoneBoundary2D.indexed(prefix, points);
    }

    private static MonotoneBoundary2D.Vertex vertex(
            final String id, final Point2D point, final double parameter) {
        return new MonotoneBoundary2D.Vertex(id, point, parameter, false);
    }

    private static Point2D affine(final Point2D point) {
        return new Point2D(1.2 * point.x() + 0.15 * point.y() + 7,
                -0.1 * point.x() + 0.9 * point.y() + 11);
    }

    private static List<Point2D> square() {
        return List.of(p(10, 10), p(190, 10), p(190, 150), p(10, 150));
    }

    private static List<Point2D> smallSquare() {
        return List.of(p(50, 50), p(70, 50), p(70, 70), p(50, 70));
    }

    private static List<Point2D> bean() {
        return List.of(p(20, 70), p(38, 30), p(95, 18), p(155, 32),
                p(180, 70), p(150, 72), p(168, 125), p(105, 148),
                p(42, 132), p(55, 90));
    }

    private static List<Point2D> crescent() {
        return List.of(p(20, 20), p(180, 20), p(190, 80), p(180, 145),
                p(20, 145), p(65, 118), p(88, 80), p(65, 45));
    }

    private static List<Point2D> notch() {
        return List.of(p(20, 20), p(180, 20), p(150, 62), p(190, 105),
                p(180, 145), p(20, 145), p(48, 105), p(10, 62));
    }

    private static List<Point2D> denseLoop(
            final int count,
            final double phase,
            final double xScale,
            final double yScale) {
        final List<Point2D> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final double angle = 2.0 * Math.PI * index / count;
            final double radius = 66.0
                    + 8.0 * Math.sin(3.0 * angle + phase)
                    + 4.0 * Math.sin(11.0 * angle - 0.5 * phase);
            points.add(p(
                    128.0 + xScale * radius * Math.cos(angle),
                    96.0 + yScale * radius * Math.sin(angle)));
        }
        return List.copyOf(points);
    }

    private static Point2D p(final double x, final double y) {
        return new Point2D(x, y);
    }

    private static void assertPoint(
            final Point2D expected,
            final Point2D actual,
            final double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(expected.y(), actual.y(), tolerance);
    }

    private static double distanceToPath(
            final Point2D point,
            final List<Point2D> path) {
        double minimum = Double.POSITIVE_INFINITY;
        for (int index = 0; index + 1 < path.size(); index++) {
            minimum = Math.min(minimum, distanceToSegment(point,
                    path.get(index), path.get(index + 1)));
        }
        return minimum;
    }

    private static double distanceToSegment(
            final Point2D point,
            final Point2D first,
            final Point2D second) {
        final double dx = second.x() - first.x();
        final double dy = second.y() - first.y();
        final double lengthSquared = dx * dx + dy * dy;
        final double parameter = lengthSquared == 0 ? 0 : Math.max(0,
                Math.min(1, ((point.x() - first.x()) * dx
                        + (point.y() - first.y()) * dy) / lengthSquared));
        return Math.hypot(point.x() - first.x() - parameter * dx,
                point.y() - first.y() - parameter * dy);
    }
}
