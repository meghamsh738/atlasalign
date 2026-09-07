package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ConstrainedLocalWarp2DTest {

    private static final double TOLERANCE = 1e-9;

    @Test
    void identityFitIsExactAndReportsIdentitySafety() {
        final List<Point2D> sources = square(20, 20, 80, 80);

        final ConstrainedLocalWarp2D warp =
                ConstrainedLocalWarp2D.fit(sources, sources, 120, 120);

        final Point2D point = new Point2D(47.25, 63.75);
        assertPoint(point, warp.apply(point), 0);
        final ConstrainedLocalWarp2D.Jacobian2D jacobian =
                warp.jacobian(point);
        assertEquals(1, jacobian.m00(), 0);
        assertEquals(0, jacobian.m01(), 0);
        assertEquals(0, jacobian.m10(), 0);
        assertEquals(1, jacobian.m11(), 0);
        assertEquals(4, warp.diagnostics().controlCount());
        assertEquals(CoordinateSpace2D.PREVIEW_PIXEL, warp.sourceSpace());
        assertEquals(CoordinateSpace2D.PREVIEW_PIXEL,
                warp.destinationSpace());
        assertEquals(ConstrainedLocalWarp2D.ALGORITHM_REVISION,
                warp.algorithmRevision());
        assertEquals(0.001, warp.regularization(), 0);
        assertEquals(0.6 * Math.hypot(120, 120),
                warp.supportRadius(), TOLERANCE);
        assertEquals(1, warp.diagnostics().minimumJacobianDeterminant(), 0);
        assertEquals(1, warp.diagnostics().minimumSingularValue(), 0);
        assertEquals(1, warp.diagnostics().maximumSingularValue(), 0);
        assertEquals(1, warp.diagnostics().maximumAnisotropy(), 0);
        assertEquals(0, warp.diagnostics().maximumDisplacement(), 0);
        assertEquals(0, warp.diagnostics().fitRms(), 0);
        assertTrue(warp.diagnostics().checkRms().isEmpty());
    }

    @Test
    void localizedCorrectionUsesCompactSupportAndAnalyticJacobian() {
        final List<Point2D> sources = square(30, 30, 80, 80);
        final List<Point2D> targets = List.of(
                new Point2D(34, 32),
                sources.get(1), sources.get(2), sources.get(3));

        final ConstrainedLocalWarp2D warp =
                ConstrainedLocalWarp2D.fit(sources, targets, 200, 200);

        final Point2D corrected = warp.apply(sources.get(0));
        assertTrue(corrected.x() > 33.9 && corrected.x() < 34.0);
        assertTrue(corrected.y() > 31.9 && corrected.y() < 32.0);
        assertTrue(warp.diagnostics().fitRms() < 0.004);

        final Point2D outsideEverySupport = new Point2D(199, 199);
        assertPoint(outsideEverySupport,
                warp.apply(outsideEverySupport), 0);
        final ConstrainedLocalWarp2D.Jacobian2D outsideJacobian =
                warp.jacobian(outsideEverySupport);
        assertEquals(1, outsideJacobian.m00(), 0);
        assertEquals(0, outsideJacobian.m01(), 0);
        assertEquals(0, outsideJacobian.m10(), 0);
        assertEquals(1, outsideJacobian.m11(), 0);

        final Point2D evaluation = new Point2D(51.5, 46.25);
        final double step = 1e-5;
        final Point2D plusX = warp.apply(new Point2D(
                evaluation.x() + step, evaluation.y()));
        final Point2D minusX = warp.apply(new Point2D(
                evaluation.x() - step, evaluation.y()));
        final Point2D plusY = warp.apply(new Point2D(
                evaluation.x(), evaluation.y() + step));
        final Point2D minusY = warp.apply(new Point2D(
                evaluation.x(), evaluation.y() - step));
        final ConstrainedLocalWarp2D.Jacobian2D analytic =
                warp.jacobian(evaluation);
        assertEquals((plusX.x() - minusX.x()) / (2 * step),
                analytic.m00(), 1e-8);
        assertEquals((plusY.x() - minusY.x()) / (2 * step),
                analytic.m01(), 1e-8);
        assertEquals((plusX.y() - minusX.y()) / (2 * step),
                analytic.m10(), 1e-8);
        assertEquals((plusY.y() - minusY.y()) / (2 * step),
                analytic.m11(), 1e-8);
    }

    @Test
    void deterministicReplayHasStableWarpIdentityAndImmutableInputs() {
        final ArrayList<Point2D> mutableSources = new ArrayList<>(
                square(30, 30, 80, 80));
        final List<Point2D> targets = List.of(
                new Point2D(34, 32), mutableSources.get(1),
                mutableSources.get(2), mutableSources.get(3));

        final ConstrainedLocalWarp2D first = ConstrainedLocalWarp2D.fit(
                mutableSources, targets, 200, 200);
        final ConstrainedLocalWarp2D replay = ConstrainedLocalWarp2D.fit(
                List.copyOf(mutableSources), targets, 200, 200);
        final ConstrainedLocalWarp2D withChecks = ConstrainedLocalWarp2D.fit(
                List.copyOf(mutableSources), targets,
                List.of(new Point2D(150, 150)),
                List.of(new Point2D(151, 150)), 200, 200);

        assertEquals(first.diagnostics().contentHash(),
                replay.diagnostics().contentHash());
        assertEquals(first.diagnostics().contentHash(),
                withChecks.diagnostics().contentHash());
        assertTrue(first.diagnostics().contentHash()
                .matches("[0-9a-f]{64}"));
        assertEquals(first.diagnostics().contentHash(),
                first.diagnostics().contentHashSha256());
        assertEquals(
                "50e5a2048288fe699b1de3bda350468489972a2ac3ce8121bfcb4104097b0629",
                first.diagnostics().contentHash());
        assertEquals(first.xWeights(), replay.xWeights());
        assertEquals(first.yWeights(), replay.yWeights());
        assertEquals(first, replay);
        assertEquals(first.hashCode(), replay.hashCode());
        mutableSources.set(0, new Point2D(0, 0));
        assertEquals(new Point2D(30, 30), first.fitSourcePoints().get(0));
        assertThrows(UnsupportedOperationException.class,
                () -> first.xWeights().set(0, 0.0));

        final ConstrainedLocalWarp2D changed = ConstrainedLocalWarp2D.fit(
                square(30, 30, 80, 80),
                List.of(new Point2D(35, 32), targets.get(1),
                        targets.get(2), targets.get(3)), 200, 200);
        assertNotEquals(first.diagnostics().contentHash(),
                changed.diagnostics().contentHash());
        assertNotEquals(first, changed);
    }

    @Test
    void boundedNewtonInverseRoundTripsFinitePreviewPoints() {
        final List<Point2D> sources = square(30, 30, 80, 80);
        final List<Point2D> targets = List.of(
                new Point2D(34, 32),
                sources.get(1), sources.get(2), sources.get(3));
        final ConstrainedLocalWarp2D warp =
                ConstrainedLocalWarp2D.fit(sources, targets, 200, 200);

        for (final Point2D original : List.of(
                new Point2D(30, 30),
                new Point2D(51.5, 46.25),
                new Point2D(110, 90),
                new Point2D(199, 199))) {
            final Point2D roundTrip = warp.inverse(warp.apply(original));
            assertPoint(original, roundTrip, 1e-8);
        }
    }

    @Test
    void independentCheckPairsDoNotFitButReportCheckRms() {
        final List<Point2D> sources = square(20, 20, 80, 80);

        final ConstrainedLocalWarp2D warp = ConstrainedLocalWarp2D.fit(
                sources, sources,
                List.of(new Point2D(100, 100)),
                List.of(new Point2D(103, 104)), 120, 120);

        assertTrue(warp.diagnostics().checkRms().isPresent());
        assertEquals(5, warp.diagnostics().checkRms().getAsDouble(), 0);
        assertPoint(new Point2D(100, 100),
                warp.apply(new Point2D(100, 100)), 0);
    }

    @Test
    void rejectsExcessiveAndSampledUnsafeDisplacements() {
        final List<Point2D> sources = square(20, 20, 80, 80);
        final List<Point2D> excessive = List.of(
                new Point2D(56, 20),
                sources.get(1), sources.get(2), sources.get(3));
        final IllegalArgumentException excessiveError = assertThrows(
                IllegalArgumentException.class,
                () -> ConstrainedLocalWarp2D.fit(
                        sources, excessive, 100, 100));
        assertTrue(excessiveError.getMessage().contains("25%"));

        final List<Point2D> inward = List.of(
                new Point2D(50, 20), new Point2D(50, 20),
                new Point2D(50, 80), new Point2D(50, 80));
        final IllegalArgumentException unsafeError = assertThrows(
                IllegalArgumentException.class,
                () -> ConstrainedLocalWarp2D.fit(
                        sources, inward, 100, 100));
        assertTrue(unsafeError.getMessage().contains("Jacobian"));
    }

    @Test
    void rejectsInsufficientDuplicateAndPoorlyDistributedControls() {
        final List<Point2D> square = square(20, 20, 80, 80);
        assertThrows(IllegalArgumentException.class,
                () -> ConstrainedLocalWarp2D.fit(
                        square.subList(0, 3), square.subList(0, 3),
                        100, 100));

        final List<Point2D> duplicate = List.of(
                new Point2D(20, 20), new Point2D(20, 20),
                new Point2D(80, 20), new Point2D(20, 80));
        assertThrows(IllegalArgumentException.class,
                () -> ConstrainedLocalWarp2D.fit(
                        duplicate, duplicate, 100, 100));

        final List<Point2D> collinear = List.of(
                new Point2D(10, 10), new Point2D(30, 30),
                new Point2D(50, 50), new Point2D(70, 70));
        assertThrows(IllegalArgumentException.class,
                () -> ConstrainedLocalWarp2D.fit(
                        collinear, collinear, 100, 100));

        final List<Point2D> tooSmall = square(10, 10, 15, 15);
        assertThrows(IllegalArgumentException.class,
                () -> ConstrainedLocalWarp2D.fit(
                        tooSmall, tooSmall, 100, 100));
    }

    @Test
    void validatesIndependentChecksAndRejectsNonfiniteGeometry() {
        final List<Point2D> sources = square(20, 20, 80, 80);
        assertThrows(IllegalArgumentException.class,
                () -> ConstrainedLocalWarp2D.fit(
                        sources, sources,
                        List.of(new Point2D(40, 40)), List.of(),
                        100, 100));

        final List<Point2D> overflowing = List.of(
                new Point2D(-Double.MAX_VALUE, -Double.MAX_VALUE),
                new Point2D(Double.MAX_VALUE, -Double.MAX_VALUE),
                new Point2D(Double.MAX_VALUE, Double.MAX_VALUE),
                new Point2D(-Double.MAX_VALUE, Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> ConstrainedLocalWarp2D.fit(
                        overflowing, overflowing, 100, 100));
    }

    private static List<Point2D> square(
            final double minimumX,
            final double minimumY,
            final double maximumX,
            final double maximumY) {
        return List.of(
                new Point2D(minimumX, minimumY),
                new Point2D(maximumX, minimumY),
                new Point2D(minimumX, maximumY),
                new Point2D(maximumX, maximumY));
    }

    private static void assertPoint(
            final Point2D expected,
            final Point2D actual,
            final double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(expected.y(), actual.y(), tolerance);
    }
}
