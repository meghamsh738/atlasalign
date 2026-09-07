package org.atlasalign.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AffineTransform2DTest {

    private static final double TOLERANCE = 1e-9;

    @Test
    void inverseRoundTripPreservesPixelCenterCoordinates() {
        final AffineTransform2D transform = new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1.08,
                0.07,
                13.5,
                -0.04,
                0.94,
                -6.25);
        final Point2D original = new Point2D(81.125, 44.75);

        final Point2D restored =
                transform.inverse().apply(transform.apply(original));

        assertEquals(original.x(), restored.x(), TOLERANCE);
        assertEquals(original.y(), restored.y(), TOLERANCE);
    }

    @Test
    void compositionPreservesDirectionAndOrder() {
        final AffineTransform2D first = new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 4, 0, 1, 7);
        final AffineTransform2D second = new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.SOURCE_PIXEL,
                2, 0, 1, 0, 3, -1);
        final Point2D point = new Point2D(5, 6);

        final Point2D expected = second.apply(first.apply(point));
        final Point2D actual = first.andThen(second).apply(point);

        assertEquals(expected.x(), actual.x(), TOLERANCE);
        assertEquals(expected.y(), actual.y(), TOLERANCE);
    }

    @Test
    void rejectsCompositionAcrossMismatchedSpaces() {
        final AffineTransform2D first = new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0, 0, 1, 0);
        final AffineTransform2D wrongNext = new AffineTransform2D(
                CoordinateSpace2D.SOURCE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0, 0, 1, 0);

        assertThrows(
                IllegalArgumentException.class,
                () -> first.andThen(wrongNext));
    }

    @Test
    void rejectsOverflowingAndIllConditionedTransforms() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1e308, 0, 0, 0, 1e308, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1, 1, 0, 1, 1 + 1e-14, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1e-200, 0, 0, 0, 1e-200, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        2e-162, 0, 0, 0, 2e-162, 0));
    }

    @Test
    void rejectsSimilarityScalesThatCannotInvertSafely() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimilarityTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        Double.MIN_VALUE, 0, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimilarityTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        Double.MAX_VALUE, 0, 0, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SimilarityTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1e-150, 0, Double.MAX_VALUE, 0));
    }
}
