package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.SimilarityTransform2D;
import org.junit.jupiter.api.Test;

class LandmarkSimilaritySolverTest {

    private static final double TOLERANCE = 1e-9;

    @Test
    void fitsKnownPositiveScaleRotationAndTranslation() {
        final List<Point2D> source = List.of(
                new Point2D(-2, 1),
                new Point2D(4, -3),
                new Point2D(3, 7));
        final SimilarityTransform2D expected = new SimilarityTransform2D(
                org.atlasalign.core.CoordinateSpace2D.PREVIEW_PIXEL,
                org.atlasalign.core.CoordinateSpace2D.PREVIEW_PIXEL,
                1.3, Math.toRadians(17), 8.5, -4.25);
        final List<Point2D> target = source.stream()
                .map(expected::apply)
                .toList();

        final SimilarityTransform2D actual =
                LandmarkSimilaritySolver.fitPreviewCorrection(
                        source, target);

        assertEquals(expected.scale(), actual.scale(), TOLERANCE);
        assertEquals(expected.rotationRadians(), actual.rotationRadians(),
                TOLERANCE);
        assertEquals(expected.translationX(), actual.translationX(),
                TOLERANCE);
        assertEquals(expected.translationY(), actual.translationY(),
                TOLERANCE);
        for (int index = 0; index < source.size(); index++) {
            assertPoint(target.get(index), actual.apply(source.get(index)));
            assertPoint(source.get(index), actual.inverse().apply(
                    actual.apply(source.get(index))));
        }
    }

    @Test
    void rejectsInsufficientOrCoincidentSourcePoints() {
        assertThrows(IllegalArgumentException.class,
                () -> LandmarkSimilaritySolver.fitPreviewCorrection(
                        List.of(new Point2D(1, 2)),
                        List.of(new Point2D(3, 4))));
        assertThrows(IllegalArgumentException.class,
                () -> LandmarkSimilaritySolver.fitPreviewCorrection(
                        List.of(new Point2D(1, 2), new Point2D(1, 2)),
                        List.of(new Point2D(3, 4), new Point2D(5, 6))));
    }

    private static void assertPoint(
            final Point2D expected,
            final Point2D actual) {
        assertEquals(expected.x(), actual.x(), TOLERANCE);
        assertEquals(expected.y(), actual.y(), TOLERANCE);
    }
}
