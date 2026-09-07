package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class OutlineAffineFitterTest {

    private static final double TOLERANCE = 1e-12;
    private static final List<Point2D> ATLAS = List.of(
            new Point2D(-4, -2),
            new Point2D(5, -2),
            new Point2D(6, 0),
            new Point2D(3, 3),
            new Point2D(-2, 2),
            new Point2D(-5, 0));

    @Test
    void identityUsesNamedPixelSpacesAndPositiveDeterminant() {
        final OutlineAffineFitter.FitResult result =
                OutlineAffineFitter.fit(ATLAS, ATLAS);

        assertEquals(CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                result.orientedAtlasToSource().sourceSpace());
        assertEquals(CoordinateSpace2D.SOURCE_PIXEL,
                result.orientedAtlasToSource().destinationSpace());
        assertEquals(1, result.orientedAtlasToSource().m00(), TOLERANCE);
        assertEquals(0, result.orientedAtlasToSource().m01(), TOLERANCE);
        assertEquals(0, result.orientedAtlasToSource().m02(), TOLERANCE);
        assertEquals(0, result.orientedAtlasToSource().m10(), TOLERANCE);
        assertEquals(1, result.orientedAtlasToSource().m11(), TOLERANCE);
        assertEquals(0, result.orientedAtlasToSource().m12(), TOLERANCE);
        assertEquals(1, result.orientedAtlasToSource().determinant(),
                TOLERANCE);
        assertFalse(result.atlasReflected());
    }

    @Test
    void alignsBoundsCentersWithIndependentXAndYStretch() {
        final List<Point2D> tissue = transform(
                ATLAS, 1.75, 0.6, 21.25, -8.5);

        final OutlineAffineFitter.FitResult result =
                OutlineAffineFitter.fit(ATLAS, tissue);

        assertEquals(1.75, result.diagnostics().scaleX(), TOLERANCE);
        assertEquals(0.6, result.diagnostics().scaleY(), TOLERANCE);
        assertEquals(1.75 / 0.6,
                result.diagnostics().anisotropyRatio(), TOLERANCE);
        assertPoint(result.diagnostics().tissueBounds().center(),
                result.orientedAtlasToSource().apply(
                        result.diagnostics().atlasBounds().center()));
        for (int index = 0; index < ATLAS.size(); index++) {
            assertPoint(tissue.get(index),
                    result.applyAtlasPoint(ATLAS.get(index)));
        }
    }

    @Test
    void keepsReflectionExplicitAndFittedTransformPositive() {
        final OutlineAffineFitter.Bounds atlasBounds =
                OutlineAffineFitter.fit(ATLAS, ATLAS)
                        .diagnostics().atlasBounds();
        final double centerX = atlasBounds.center().x();
        final List<Point2D> tissue = ATLAS.stream()
                .map(point -> new Point2D(
                        1.2 * (2 * centerX - point.x()) + 9,
                        0.8 * point.y() - 3))
                .toList();

        final OutlineAffineFitter.FitResult result =
                OutlineAffineFitter.fit(ATLAS, tissue, true);

        assertTrue(result.atlasReflected());
        assertTrue(result.orientedAtlasToSource().determinant() > 0);
        assertTrue(result.atlasToSource().determinant() < 0);
        for (int index = 0; index < ATLAS.size(); index++) {
            assertPoint(tissue.get(index),
                    result.applyAtlasPoint(ATLAS.get(index)));
            assertPoint(result.applyAtlasPoint(ATLAS.get(index)),
                    result.atlasToSource().apply(ATLAS.get(index)));
        }
    }

    @Test
    void diagnosticsAndHashAreDeterministicAndBindReflection() {
        final List<Point2D> tissue = transform(ATLAS, 1.1, 0.9, 3, 7);

        final OutlineAffineFitter.FitResult first =
                OutlineAffineFitter.fit(ATLAS, tissue);
        final OutlineAffineFitter.FitResult second =
                OutlineAffineFitter.fit(ATLAS, tissue);
        final OutlineAffineFitter.FitResult reflected =
                OutlineAffineFitter.fit(ATLAS, tissue, true);

        assertEquals(first, second);
        assertEquals("outline-bounds-affine-v1",
                first.diagnostics().methodId());
        assertEquals(64, first.diagnostics().contentSha256().length());
        assertNotEquals(first.diagnostics().contentSha256(),
                reflected.diagnostics().contentSha256());
    }

    @Test
    void doesNotInferRotationOrShear() {
        final List<Point2D> tissue = ATLAS.stream()
                .map(point -> new Point2D(-point.y() + 10, point.x() - 4))
                .toList();

        final OutlineAffineFitter.FitResult result =
                OutlineAffineFitter.fit(ATLAS, tissue);

        assertEquals(0, result.orientedAtlasToSource().m01(), TOLERANCE);
        assertEquals(0, result.orientedAtlasToSource().m10(), TOLERANCE);
    }

    @Test
    void rejectsMissingInsufficientAndDegenerateExtents() {
        final List<Point2D> verticalLine = List.of(
                new Point2D(1, 0),
                new Point2D(1, 2),
                new Point2D(1, 4));
        assertThrows(NullPointerException.class,
                () -> OutlineAffineFitter.fit(null, ATLAS));
        assertThrows(IllegalArgumentException.class,
                () -> OutlineAffineFitter.fit(ATLAS.subList(0, 2), ATLAS));
        assertThrows(IllegalArgumentException.class,
                () -> OutlineAffineFitter.fit(verticalLine, ATLAS));
        assertThrows(IllegalArgumentException.class,
                () -> OutlineAffineFitter.fit(ATLAS, verticalLine));

        final List<Point2D> excessiveAnisotropy =
                transform(ATLAS, 5.01, 1, 0, 0);
        assertThrows(IllegalArgumentException.class,
                () -> OutlineAffineFitter.fit(ATLAS, excessiveAnisotropy));
    }

    private static List<Point2D> transform(
            final List<Point2D> points,
            final double scaleX,
            final double scaleY,
            final double translationX,
            final double translationY) {
        return points.stream()
                .map(point -> new Point2D(
                        scaleX * point.x() + translationX,
                        scaleY * point.y() + translationY))
                .toList();
    }

    private static void assertPoint(
            final Point2D expected,
            final Point2D actual) {
        assertEquals(expected.x(), actual.x(), TOLERANCE);
        assertEquals(expected.y(), actual.y(), TOLERANCE);
    }
}
