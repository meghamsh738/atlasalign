package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class LandmarkAffineSolverTest {

    private static final double TOLERANCE = 1e-9;

    @Test
    void fitsKnownNonReflectingGlobalAffineCorrection() {
        final List<Point2D> source = List.of(
                new Point2D(-2, 1),
                new Point2D(4, -3),
                new Point2D(3, 7),
                new Point2D(-1, 6));
        final AffineTransform2D expected = new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1.15, 0.12, 8.5,
                -0.08, 0.91, -4.25);
        final List<Point2D> target = source.stream()
                .map(expected::apply)
                .toList();

        final AffineTransform2D actual =
                LandmarkAffineSolver.fitPreviewCorrection(source, target);

        assertEquals(expected.m00(), actual.m00(), TOLERANCE);
        assertEquals(expected.m01(), actual.m01(), TOLERANCE);
        assertEquals(expected.m02(), actual.m02(), TOLERANCE);
        assertEquals(expected.m10(), actual.m10(), TOLERANCE);
        assertEquals(expected.m11(), actual.m11(), TOLERANCE);
        assertEquals(expected.m12(), actual.m12(), TOLERANCE);
        for (int index = 0; index < source.size(); index++) {
            assertPoint(target.get(index), actual.apply(source.get(index)));
        }
    }

    @Test
    void rejectsInsufficientCollinearAndReflectingFits() {
        final List<Point2D> source = List.of(
                new Point2D(0, 0), new Point2D(2, 0), new Point2D(4, 0));
        final List<Point2D> target = List.of(
                new Point2D(1, 1), new Point2D(3, 1), new Point2D(5, 1));
        assertThrows(IllegalArgumentException.class,
                () -> LandmarkAffineSolver.fitPreviewCorrection(
                        source.subList(0, 2), target.subList(0, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> LandmarkAffineSolver.fitPreviewCorrection(source, target));

        final List<Point2D> triangle = List.of(
                new Point2D(0, 0), new Point2D(4, 0), new Point2D(0, 3));
        final List<Point2D> reflected = triangle.stream()
                .map(point -> new Point2D(-point.x() + 4, point.y()))
                .toList();
        assertThrows(IllegalArgumentException.class,
                () -> LandmarkAffineSolver.fitPreviewCorrection(
                        triangle, reflected));
    }

    private static void assertPoint(
            final Point2D expected,
            final Point2D actual) {
        assertEquals(expected.x(), actual.x(), TOLERANCE);
        assertEquals(expected.y(), actual.y(), TOLERANCE);
    }
}
