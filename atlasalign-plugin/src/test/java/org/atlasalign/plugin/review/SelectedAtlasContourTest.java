package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.atlasalign.application.AtlasAnatomicalSide;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.atlas.AtlasPlaneGeometry;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class SelectedAtlasContourTest {

    private static final SelectedAtlasRegion DG = new SelectedAtlasRegion(
            10, "DG", "Dentate gyrus", Set.of(10, 11));

    @Test
    void parentAndChildLabelsFormOneOuterBoundaryWithoutInternalSeam() {
        final int width = 11;
        final int height = 9;
        final int[] labels = new int[width * height];
        for (int y = 2; y <= 6; y++) {
            for (int x = 2; x <= 8; x++) {
                labels[y * width + x] = x <= 4 ? 10 : 11;
            }
        }

        final SelectedAtlasContour contour = SelectedAtlasContour.from(
                plane(width, height, labels), DG);

        assertTrue(contour.isPresent());
        assertEquals(20, contour.boundaryCount());
        assertTrue(contour.isBoundary(2, 4));
        assertTrue(contour.isBoundary(8, 4));
        assertFalse(contour.isBoundary(4, 4),
                "the parent side of a parent/child seam is interior");
        assertFalse(contour.isBoundary(5, 4),
                "the child side of a parent/child seam is interior");
        assertEquals(List.of(
                new Point2D(1.5, 1.5), new Point2D(8.5, 1.5),
                new Point2D(8.5, 6.5), new Point2D(1.5, 6.5)),
                contour.orderedExteriorLoop(),
                "child labels must not create a seam in the ordered union");
    }

    @Test
    void nearestBoundaryUsesStableRowThenColumnTieBreakingAndRadius() {
        final int width = 7;
        final int height = 7;
        final int[] labels = new int[width * height];
        for (int y = 1; y <= 5; y++) {
            for (int x = 1; x <= 5; x++) {
                labels[y * width + x] = 10;
            }
        }
        final SelectedAtlasContour contour = SelectedAtlasContour.from(
                plane(width, height, labels), DG);

        assertEquals(new Point2D(3, 1), contour.nearestBoundary(
                new Point2D(3, 3), 2).orElseThrow(),
                "equidistant candidates are chosen in row/column order");
        assertTrue(contour.nearestBoundary(
                new Point2D(3, 3), 1.99).isEmpty());
        assertEquals(new Point2D(1, 2), contour.nearestBoundary(
                new Point2D(1.2, 2.1), 0.25).orElseThrow());
    }

    @Test
    void targetAbsentFromPlaneHasNoBoundaryOrNearestPoint() {
        final int[] labels = new int[25];
        labels[12] = 99;

        final SelectedAtlasContour contour = SelectedAtlasContour.from(
                plane(5, 5, labels), DG);

        assertFalse(contour.isPresent());
        assertEquals(0, contour.boundaryCount());
        assertTrue(contour.nearestBoundary(
                new Point2D(2, 2), 100).isEmpty());
    }

    @Test
    void boundarySamplesAreDeterministicAndSpatiallySpread() {
        final int width = 11;
        final int height = 9;
        final int[] labels = new int[width * height];
        for (int y = 2; y <= 6; y++) {
            for (int x = 2; x <= 8; x++) {
                labels[y * width + x] = 10;
            }
        }
        final SelectedAtlasContour contour = SelectedAtlasContour.from(
                plane(width, height, labels), DG);

        final List<Point2D> samples = contour.sampleBoundaryPoints(4);

        assertEquals(List.of(
                new Point2D(2, 2), new Point2D(8, 6),
                new Point2D(7, 2), new Point2D(3, 6)), samples);
        assertEquals(samples, contour.sampleBoundaryPoints(4));
        for (int first = 0; first < samples.size(); first++) {
            for (int second = first + 1; second < samples.size(); second++) {
                assertTrue(distance(samples.get(first), samples.get(second)) >= 4,
                        "four requested handles should occupy the rectangle corners");
            }
        }
    }

    @Test
    void sideAwareSamplesNeverCrossAtlasSideAndHashIsStable() {
        final int width = 21;
        final int height = 11;
        final int[] labels = new int[width * height];
        fill(labels, width, 2, 2, 7, 8, 10);
        fill(labels, width, 13, 2, 18, 8, 11);
        labels[1 * width + 10] = 10;
        labels[2 * width + 10] = 10;
        final SelectedAtlasContour first = SelectedAtlasContour.from(
                plane(width, height, labels), DG);
        final SelectedAtlasContour replay = SelectedAtlasContour.from(
                plane(width, height, labels.clone()), DG);

        final List<Point2D> left = first.sampleBoundaryPoints(
                4, AtlasAnatomicalSide.ATLAS_LEFT);
        final List<Point2D> right = first.sampleBoundaryPoints(
                4, AtlasAnatomicalSide.ATLAS_RIGHT);
        final List<Point2D> midline = first.sampleBoundaryPoints(
                2, AtlasAnatomicalSide.MIDLINE);

        assertTrue(left.stream().allMatch(point -> point.x() < 10));
        assertTrue(right.stream().allMatch(point -> point.x() > 10));
        assertTrue(midline.stream().allMatch(
                point -> Math.abs(point.x() - 10) <= 1));
        assertEquals(left, replay.sampleBoundaryPoints(
                4, AtlasAnatomicalSide.ATLAS_LEFT));
        assertEquals(first.boundarySha256(), replay.boundarySha256());
        assertTrue(first.boundarySha256().matches("[0-9a-f]{64}"));
    }

    @Test
    void sideAwareSamplingFailsClosedWhenTargetIsAbsentThere() {
        final int width = 21;
        final int height = 11;
        final int[] labels = new int[width * height];
        fill(labels, width, 2, 2, 7, 8, 10);
        final SelectedAtlasContour contour = SelectedAtlasContour.from(
                plane(width, height, labels), DG);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> contour.sampleBoundaryPoints(
                        4, AtlasAnatomicalSide.ATLAS_RIGHT));
    }

    @Test
    void rectangleLoopUsesContinuousPixelEdgesAndCanonicalClockwiseOrder() {
        final int width = 8;
        final int height = 7;
        final int[] labels = new int[width * height];
        fill(labels, width, 2, 1, 5, 4, 10);

        final SelectedAtlasContour contour = SelectedAtlasContour.from(
                plane(width, height, labels), DG);

        assertEquals(List.of(
                new Point2D(1.5, 0.5), new Point2D(5.5, 0.5),
                new Point2D(5.5, 4.5), new Point2D(1.5, 4.5)),
                contour.orderedExteriorLoop());
        assertTrue(signedArea(contour.orderedExteriorLoop()) > 0,
                "screen-coordinate clockwise loops have positive area");
    }

    @Test
    void concavityIsPreservedWithoutSelfIntersection() {
        final int width = 7;
        final int height = 7;
        final int[] labels = new int[width * height];
        fill(labels, width, 1, 1, 2, 2, 10);
        fill(labels, width, 1, 3, 4, 4, 10);

        final List<Point2D> loop = SelectedAtlasContour.from(
                plane(width, height, labels), DG).orderedExteriorLoop();

        assertEquals(List.of(
                new Point2D(0.5, 0.5), new Point2D(2.5, 0.5),
                new Point2D(2.5, 2.5), new Point2D(4.5, 2.5),
                new Point2D(4.5, 4.5), new Point2D(0.5, 4.5)), loop);
        assertSimple(loop);
    }

    @Test
    void holeIsExcludedFromOrderedExteriorLoop() {
        final int width = 7;
        final int height = 7;
        final int[] labels = new int[width * height];
        fill(labels, width, 1, 1, 5, 5, 10);
        labels[3 * width + 3] = 0;

        final SelectedAtlasContour contour = SelectedAtlasContour.from(
                plane(width, height, labels), DG);

        assertEquals(List.of(
                new Point2D(0.5, 0.5), new Point2D(5.5, 0.5),
                new Point2D(5.5, 5.5), new Point2D(0.5, 5.5)),
                contour.orderedExteriorLoop());
        assertTrue(contour.boundaryCount() > 16,
                "the legacy boundary API still includes pixels at the hole");
    }

    @Test
    void largestDisconnectedExteriorComponentWinsDeterministically() {
        final int width = 12;
        final int height = 8;
        final int[] labels = new int[width * height];
        fill(labels, width, 1, 1, 5, 5, 10);
        fill(labels, width, 9, 2, 10, 3, 11);

        final SelectedAtlasContour first = SelectedAtlasContour.from(
                plane(width, height, labels), DG);
        final SelectedAtlasContour replay = SelectedAtlasContour.from(
                plane(width, height, labels), DG);

        assertEquals(List.of(
                new Point2D(0.5, 0.5), new Point2D(5.5, 0.5),
                new Point2D(5.5, 5.5), new Point2D(0.5, 5.5)),
                first.orderedExteriorLoop());
        assertEquals(first.orderedExteriorLoop(),
                replay.orderedExteriorLoop());
        assertEquals(2, first.orderedExteriorLoops().size());
        assertEquals(List.of(
                new Point2D(8.5, 1.5), new Point2D(10.5, 1.5),
                new Point2D(10.5, 3.5), new Point2D(8.5, 3.5)),
                first.orderedExteriorLoops().get(1));
        assertEquals(first.orderedExteriorLoops(),
                replay.orderedExteriorLoops());
        assertSimple(first.orderedExteriorLoop());
    }

    @Test
    void principalSamplingSeedsBothLargeBladesAndLeavesTinyFragmentFixed() {
        final int width = 31;
        final int height = 26;
        final int[] labels = new int[width * height];
        fill(labels, width, 2, 2, 11, 6, 10);
        fill(labels, width, 3, 12, 12, 17, 10);
        fill(labels, width, 5, 22, 6, 23, 10);
        final SelectedAtlasContour first = SelectedAtlasContour.from(
                plane(width, height, labels), DG);
        final SelectedAtlasContour replay = SelectedAtlasContour.from(
                plane(width, height, labels.clone()), DG);

        final List<SelectedAtlasContour.ExteriorComponent> components =
                first.exteriorComponents(AtlasAnatomicalSide.ATLAS_LEFT);
        final List<SelectedAtlasContour.ExteriorComponent> principal =
                first.principalExteriorComponents(
                        AtlasAnatomicalSide.ATLAS_LEFT);
        final List<Point2D> samples = first.samplePrincipalExteriorPoints(
                8, AtlasAnatomicalSide.ATLAS_LEFT);

        assertEquals(3, components.size());
        assertEquals(components.subList(0, 2), principal);
        assertEquals(principal, replay.principalExteriorComponents(
                AtlasAnatomicalSide.ATLAS_LEFT),
                "transient component identities and ordering are deterministic");
        assertEquals(8, samples.size());
        assertTrue(samples.stream().filter(point -> point.y() < 9).count()
                >= 2, "the upper principal blade needs at least two controls");
        assertTrue(samples.stream().filter(point -> point.y() > 9
                && point.y() < 20).count() >= 2,
                "the lower principal blade needs at least two controls");
        assertTrue(samples.stream().noneMatch(point -> point.y() >= 20),
                "the smaller remote fragment stays fixed");
        assertTrue(samples.stream().allMatch(point -> point.x() < 15),
                "principal sampling must remain on the requested atlas side");
    }

    private static void fill(
            final int[] labels,
            final int width,
            final int minimumX,
            final int minimumY,
            final int maximumX,
            final int maximumY,
            final int label) {
        for (int y = minimumY; y <= maximumY; y++) {
            for (int x = minimumX; x <= maximumX; x++) {
                labels[y * width + x] = label;
            }
        }
    }

    private static double signedArea(final List<Point2D> loop) {
        double twiceArea = 0;
        for (int index = 0; index < loop.size(); index++) {
            final Point2D current = loop.get(index);
            final Point2D next = loop.get((index + 1) % loop.size());
            twiceArea += current.x() * next.y() - next.x() * current.y();
        }
        return twiceArea / 2;
    }

    private static void assertSimple(final List<Point2D> loop) {
        assertEquals(loop.size(), Set.copyOf(loop).size(),
                "a simple loop cannot repeat vertices");
        for (int first = 0; first < loop.size(); first++) {
            final int firstNext = (first + 1) % loop.size();
            for (int second = first + 1; second < loop.size(); second++) {
                final int secondNext = (second + 1) % loop.size();
                if (firstNext == second || secondNext == first) {
                    continue;
                }
                assertFalse(intersects(
                        loop.get(first), loop.get(firstNext),
                        loop.get(second), loop.get(secondNext)),
                        "non-adjacent loop edges must not intersect");
            }
        }
    }

    private static boolean intersects(
            final Point2D firstStart,
            final Point2D firstEnd,
            final Point2D secondStart,
            final Point2D secondEnd) {
        return orientation(firstStart, firstEnd, secondStart)
                * orientation(firstStart, firstEnd, secondEnd) <= 0
                && orientation(secondStart, secondEnd, firstStart)
                * orientation(secondStart, secondEnd, firstEnd) <= 0;
    }

    private static double orientation(
            final Point2D start,
            final Point2D end,
            final Point2D point) {
        return (end.x() - start.x()) * (point.y() - start.y())
                - (end.y() - start.y()) * (point.x() - start.x());
    }

    private static double distance(final Point2D first, final Point2D second) {
        return Math.hypot(first.x() - second.x(), first.y() - second.y());
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
