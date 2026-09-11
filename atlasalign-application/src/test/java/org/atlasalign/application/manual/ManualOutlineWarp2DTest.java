package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ManualOutlineWarp2DTest {

    private static final int WIDTH = 400;
    private static final int HEIGHT = 400;

    @Test
    void storedOutlineRestoresExactMappingAndRejectsModifiedWeights() {
        final var warp = ManualOutlineWarp2D.fit(squareLoop(), squareLoop().stream()
                .map(ManualOutlineWarp2DTest::smoothStretch).toList(), anchors(), WIDTH, HEIGHT);
        final var restored = ManualOutlineWarp2D.restore(warp.snapshot());
        assertEquals(warp, restored);
        for (int y = 40; y < 350; y += 43) for (int x = 40; x < 350; x += 37) {
            final var point = new Point2D(x + .25, y + .75);
            assertEquals(warp.apply(point), restored.apply(point));
            assertEquals(warp.inverse(point), restored.inverse(point));
        }
        final var value = warp.snapshot();
        final var weights = new ArrayList<>(value.xWeights()); weights.set(0, weights.get(0) + .1);
        assertThrows(IllegalArgumentException.class, () -> ManualOutlineWarp2D.restore(
                new ManualOutlineWarp2D.Snapshot(value.atlasLoop(), value.tissueLoop(), value.anchors(),
                        value.sourceControlPoints(), value.targetControlPoints(), weights, value.yWeights(),
                        value.supportRadius(), value.diagnostics())));
    }

    @Test
    void identityIsDeterministicImmutableAndReversible() {
        final List<Point2D> mutableAtlas = new ArrayList<>(squareLoop());
        final List<Point2D> mutableTissue = new ArrayList<>(squareLoop());
        final ManualOutlineWarp2D first = ManualOutlineWarp2D.fit(
                mutableAtlas, mutableTissue, anchors(), WIDTH, HEIGHT);
        final ManualOutlineWarp2D replay = ManualOutlineWarp2D.fit(
                squareLoop(), squareLoop(), anchors(), WIDTH, HEIGHT);

        mutableAtlas.set(0, new Point2D(1, 1));
        mutableTissue.clear();

        assertEquals(first, replay);
        assertEquals(first.hashCode(), replay.hashCode());
        assertEquals(ManualOutlineWarp2D.ALGORITHM_REVISION,
                first.algorithmRevision());
        assertEquals(CoordinateSpace2D.PREVIEW_PIXEL, first.sourceSpace());
        assertEquals(CoordinateSpace2D.PREVIEW_PIXEL,
                first.destinationSpace());
        assertEquals(32, first.sourceControlPoints().size());
        assertEquals(0.0,
                first.diagnostics().maximumRequestedDisplacement(), 0.0);
        assertEquals(0.0, first.diagnostics().controlResidualRms(), 0.0);
        assertEquals(0.0, first.diagnostics().boundaryDistanceMean(), 0.0);
        assertEquals(0.0, first.diagnostics().boundaryDistanceP95(), 0.0);
        assertEquals(0.0,
                first.diagnostics().maximumBoundaryDistance(), 0.0);
        assertEquals(new Point2D(173.25, 241.75),
                first.apply(new Point2D(173.25, 241.75)));
        assertEquals(new Point2D(173.25, 241.75),
                first.inverse(new Point2D(173.25, 241.75)));
        assertTrue(first.diagnostics().maximumInverseRoundTripError()
                <= 1e-6);
        assertEquals(64, first.diagnostics().contentSha256().length());
        assertThrows(UnsupportedOperationException.class,
                () -> first.atlasLoop().add(new Point2D(2, 2)));
        assertThrows(UnsupportedOperationException.class,
                () -> first.xWeights().add(2.0));
    }

    @Test
    void fitsSmoothNonuniformBoundaryStretchAcrossInterior() {
        final List<Point2D> tissue = squareLoop().stream()
                .map(ManualOutlineWarp2DTest::smoothStretch).toList();
        final ManualOutlineWarp2D warp = ManualOutlineWarp2D.fit(
                squareLoop(), tissue, anchors(), WIDTH, HEIGHT);

        final Point2D source = new Point2D(260, 180);
        final Point2D mapped = warp.apply(source);
        final Point2D recovered = warp.inverse(mapped);

        assertTrue(Math.hypot(mapped.x() - source.x(),
                mapped.y() - source.y()) > 0.25,
                "Broad support should carry boundary deformation inward");
        assertEquals(source.x(), recovered.x(), 1e-6);
        assertEquals(source.y(), recovered.y(), 1e-6);
        assertTrue(warp.diagnostics().minimumJacobianDeterminant() >= 0.15);
        assertTrue(warp.diagnostics().maximumAnisotropy() <= 4.0);
        assertTrue(warp.diagnostics().maximumControlResidual()
                < 0.01 * Math.hypot(WIDTH, HEIGHT));
        assertNotEquals(
                ManualOutlineWarp2D.fit(squareLoop(), squareLoop(),
                        anchors(), WIDTH, HEIGHT)
                        .diagnostics().contentSha256(),
                warp.diagnostics().contentSha256());
    }

    @Test
    void uniformTranslationIsNotCancelledByMonotonicArcMatching() {
        final List<Point2D> atlas = List.of(
                new Point2D(5, 5), new Point2D(75, 5),
                new Point2D(75, 65), new Point2D(5, 65));
        final List<Point2D> tissue = atlas.stream()
                .map(point -> new Point2D(point.x() + 3, point.y()))
                .toList();
        final List<ManualOutlineWarp2D.AnchorPair> rectangleAnchors = List.of(
                new ManualOutlineWarp2D.AnchorPair("D", 0, 0),
                new ManualOutlineWarp2D.AnchorPair("R", 1, 1),
                new ManualOutlineWarp2D.AnchorPair("V", 2, 2),
                new ManualOutlineWarp2D.AnchorPair("L", 3, 3));

        final ManualOutlineWarp2D warp = ManualOutlineWarp2D.fit(
                atlas, tissue, rectangleAnchors, 100, 80);

        assertEquals(13.0, warp.apply(new Point2D(10, 10)).x(), 0.12);
        assertEquals(10.0, warp.apply(new Point2D(10, 10)).y(), 0.12);
        assertEquals(53.0, warp.apply(new Point2D(50, 10)).x(), 0.12);
        assertEquals(10.0, warp.apply(new Point2D(50, 10)).y(), 0.12);
        assertTrue(warp.diagnostics().minimumJacobianDeterminant() >= 0.15);
        assertTrue(warp.diagnostics().maximumAnisotropy() <= 4.0);

        final ManualOutlineWarp2D strict = ManualOutlineWarp2D.fitStrict(
                atlas, tissue, rectangleAnchors, 100, 80);
        assertEquals(13.0, strict.apply(new Point2D(10, 10)).x(), 0.20);
        assertEquals(10.0, strict.apply(new Point2D(10, 10)).y(), 0.20);
        assertEquals(53.0, strict.apply(new Point2D(50, 10)).x(), 0.20);
        assertEquals(10.0, strict.apply(new Point2D(50, 10)).y(), 0.20);
        assertTrue(strict.diagnostics().strictBoundaryQualityEnforced());
    }

    @Test
    void boundedDensityIsDeterministicAndBindsProvenance() {
        final ManualOutlineWarp2D bounded = ManualOutlineWarp2D.fit(
                squareLoop(), squareLoop(), anchors(), WIDTH, HEIGHT, 6);
        final ManualOutlineWarp2D replay = ManualOutlineWarp2D.fit(
                squareLoop(), squareLoop(), anchors(), WIDTH, HEIGHT, 6);
        final ManualOutlineWarp2D defaultDensity = ManualOutlineWarp2D.fit(
                squareLoop(), squareLoop(), anchors(), WIDTH, HEIGHT);

        assertEquals(24, bounded.sourceControlPoints().size());
        assertEquals(24, bounded.diagnostics().controlPairCount());
        assertEquals(bounded, replay);
        assertNotEquals(defaultDensity.diagnostics().contentSha256(),
                bounded.diagnostics().contentSha256());
        assertThrows(IllegalArgumentException.class,
                () -> ManualOutlineWarp2D.fit(squareLoop(), squareLoop(),
                        anchors(), WIDTH, HEIGHT, 2));
        assertThrows(IllegalArgumentException.class,
                () -> ManualOutlineWarp2D.fit(squareLoop(), squareLoop(),
                        anchors(), WIDTH, HEIGHT, 11));
    }

    @Test
    void denserMonotonicMatchCorrectsSafeButVisiblySparseBoundaryMiss() {
        final List<Point2D> atlas = ellipseLoop(64, false);
        final List<Point2D> tissue = ellipseLoop(64, true);
        final List<ManualOutlineWarp2D.AnchorPair> ellipseAnchors = List.of(
                new ManualOutlineWarp2D.AnchorPair("R", 0, 0),
                new ManualOutlineWarp2D.AnchorPair("V", 16, 16),
                new ManualOutlineWarp2D.AnchorPair("L", 32, 32),
                new ManualOutlineWarp2D.AnchorPair("D", 48, 48));
        final ManualOutlineWarp2D sparse = ManualOutlineWarp2D.fit(
                atlas, tissue, ellipseAnchors, WIDTH, HEIGHT, 3);
        final ManualOutlineWarp2D corrected = ManualOutlineWarp2D.fitStrict(
                atlas, tissue, ellipseAnchors, WIDTH, HEIGHT);
        final ManualOutlineWarp2D replay = ManualOutlineWarp2D.fitStrict(
                atlas, tissue, ellipseAnchors, WIDTH, HEIGHT);

        assertTrue(sparse.diagnostics().minimumJacobianDeterminant() >= 0.15,
                "The regression requires a sparse warp that looks safe");
        assertTrue(corrected.diagnostics().boundaryDistanceP95()
                        < 0.75 * sparse.diagnostics().boundaryDistanceP95(),
                "Monotonic controls must materially reduce the visible boundary miss");
        assertTrue(corrected.diagnostics().minimumJacobianDeterminant()
                >= 0.15);
        assertTrue(corrected.diagnostics().maximumAnisotropy() <= 4.0);
        assertTrue(corrected.diagnostics().maximumBoundaryDistance()
                >= corrected.diagnostics().boundaryDistanceP95());
        assertTrue(corrected.diagnostics().boundaryDistanceP95()
                <= Math.max(2.0, 0.0025 * Math.hypot(WIDTH, HEIGHT)));
        assertTrue(corrected.diagnostics().maximumBoundaryDistance()
                <= Math.max(5.0, 0.0075 * Math.hypot(WIDTH, HEIGHT)));
        assertTrue(corrected.diagnostics().controlPairCount()
                <= ManualOutlineWarp2D.MAXIMUM_ADAPTIVE_CONTROL_COUNT);
        assertEquals(corrected, replay,
                "Adaptive residual insertion and the selected best safe fit must replay exactly");
    }

    @Test
    void reportsNonHomologousBoundaryExcursionWithoutForcingIt() {
        final List<Point2D> atlas = ellipseLoop(64, false);
        final List<Point2D> tissue = new ArrayList<>(atlas);
        tissue.add(49, new Point2D(204, 145));
        tissue.add(50, new Point2D(210, 102));
        final List<ManualOutlineWarp2D.AnchorPair> ellipseAnchors = List.of(
                new ManualOutlineWarp2D.AnchorPair("R", 0, 0),
                new ManualOutlineWarp2D.AnchorPair("V", 16, 16),
                new ManualOutlineWarp2D.AnchorPair("L", 32, 32),
                new ManualOutlineWarp2D.AnchorPair("D", 48, 48));

        final ManualOutlineWarp2D warp = ManualOutlineWarp2D.fit(
                atlas, tissue, ellipseAnchors, WIDTH, HEIGHT);

        assertTrue(warp.diagnostics().minimumJacobianDeterminant() >= 0.15);
        assertTrue(warp.diagnostics().maximumAnisotropy() <= 4.0);
        assertTrue(warp.diagnostics().maximumBoundaryDistance() > 25,
                "A target excursion with no atlas counterpart must remain visible in diagnostics");
        assertTrue(warp.diagnostics().maximumBoundaryDistance()
                        > 3 * warp.diagnostics().boundaryDistanceP95(),
                "The outlier must not be hidden inside the ordinary boundary statistic");
        assertThrows(IllegalArgumentException.class,
                () -> ManualOutlineWarp2D.fitStrict(
                        atlas, tissue, ellipseAnchors, WIDTH, HEIGHT),
                "A non-homologous excursion must fail the strict quality gate rather than be forced");
    }

    @Test
    void strictAdaptiveFitPreservesAffineEquivarianceAndSafety() {
        final List<Point2D> atlas = ellipseLoop(64, false);
        final List<Point2D> tissue = atlas.stream()
                .map(point -> new Point2D(
                        1.03 * point.x() + 0.02 * point.y() + 4,
                        -0.01 * point.x() + 0.98 * point.y() - 3))
                .toList();
        final List<ManualOutlineWarp2D.AnchorPair> ellipseAnchors = List.of(
                new ManualOutlineWarp2D.AnchorPair("R", 0, 0),
                new ManualOutlineWarp2D.AnchorPair("V", 16, 16),
                new ManualOutlineWarp2D.AnchorPair("L", 32, 32),
                new ManualOutlineWarp2D.AnchorPair("D", 48, 48));

        final ManualOutlineWarp2D strict = ManualOutlineWarp2D.fitStrict(
                atlas, tissue, ellipseAnchors, WIDTH, HEIGHT);
        for (final Point2D point : List.of(
                atlas.get(3), atlas.get(19), atlas.get(37), atlas.get(53))) {
            final Point2D expected = new Point2D(
                    1.03 * point.x() + 0.02 * point.y() + 4,
                    -0.01 * point.x() + 0.98 * point.y() - 3);
            final Point2D mapped = strict.apply(point);
            assertEquals(expected.x(), mapped.x(), 1.0);
            assertEquals(expected.y(), mapped.y(), 1.0);
        }
        assertTrue(strict.diagnostics().minimumJacobianDeterminant() >= 0.15);
        assertTrue(strict.diagnostics().maximumAnisotropy() <= 4.0);
        assertTrue(strict.diagnostics().strictBoundaryQualityEnforced());
        assertEquals(ManualOutlineWarp2D.REQUIRED_ANCHOR_COUNT
                        * ManualOutlineWarp2D.ADAPTIVE_CORRESPONDENCES_PER_ANCHOR_ARC,
                strict.diagnostics().denseCorrespondenceCount());
    }

    @Test
    void acceptsModestlyWarpedNonconvexCoronalLikeExterior() {
        final List<Point2D> source = coronalLikeLoop();
        final List<Point2D> target = source.stream()
                .map(point -> new Point2D(
                        point.x() + 0.035 * (point.y() - 200),
                        point.y() + 0.00018
                                * (point.x() - 200) * (point.x() - 200)
                                - 3.2))
                .toList();
        final ManualOutlineWarp2D warp = ManualOutlineWarp2D.fit(
                source, target, anchors(), WIDTH, HEIGHT);

        final Point2D interior = new Point2D(245, 175);
        final Point2D recovered = warp.inverse(warp.apply(interior));
        assertEquals(interior.x(), recovered.x(), 1e-6);
        assertEquals(interior.y(), recovered.y(), 1e-6);
        assertTrue(warp.diagnostics().minimumJacobianDeterminant() >= 0.15);
    }

    @Test
    void oppositeVertexWindingUsesExplicitSemanticAnchorOrder() {
        final List<Point2D> reversed = new ArrayList<>(squareLoop());
        Collections.reverse(reversed);
        final List<ManualOutlineWarp2D.AnchorPair> reversedAnchors = List.of(
                new ManualOutlineWarp2D.AnchorPair("dorsal-left", 0, 15),
                new ManualOutlineWarp2D.AnchorPair("dorsal-right", 4, 11),
                new ManualOutlineWarp2D.AnchorPair("ventral-right", 8, 7),
                new ManualOutlineWarp2D.AnchorPair("ventral-left", 12, 3));

        final ManualOutlineWarp2D warp = ManualOutlineWarp2D.fit(
                squareLoop(), reversed, reversedAnchors, WIDTH, HEIGHT);

        assertEquals(1, warp.diagnostics().atlasTraversalDirection());
        assertEquals(-1, warp.diagnostics().tissueTraversalDirection());
        assertEquals(new Point2D(200, 200),
                warp.apply(new Point2D(200, 200)));
    }

    @Test
    void rejectsNonCyclicOrAmbiguousAnchorCorrespondence() {
        final List<ManualOutlineWarp2D.AnchorPair> bad = List.of(
                new ManualOutlineWarp2D.AnchorPair("a", 0, 0),
                new ManualOutlineWarp2D.AnchorPair("b", 4, 4),
                new ManualOutlineWarp2D.AnchorPair("c", 8, 12),
                new ManualOutlineWarp2D.AnchorPair("d", 12, 8));

        assertThrows(IllegalArgumentException.class,
                () -> ManualOutlineWarp2D.fit(
                        squareLoop(), squareLoop(), bad, WIDTH, HEIGHT));
    }

    @Test
    void rejectsSelfIntersectingOrRepeatedClosedLoopInput() {
        final List<Point2D> bowTie = List.of(
                new Point2D(100, 100), new Point2D(300, 300),
                new Point2D(300, 100), new Point2D(100, 300));
        final List<Point2D> repeatedEndpoint = new ArrayList<>(squareLoop());
        repeatedEndpoint.add(repeatedEndpoint.get(0));

        assertThrows(IllegalArgumentException.class,
                () -> ManualOutlineWarp2D.fit(
                        bowTie, squareLoop(), anchors(), WIDTH, HEIGHT));
        assertThrows(IllegalArgumentException.class,
                () -> ManualOutlineWarp2D.fit(
                        repeatedEndpoint, squareLoop(), anchors(),
                        WIDTH, HEIGHT));
    }

    @Test
    void rejectsSevereOutlineCollapseBeforeItCanFoldAtlasGeometry() {
        final List<Point2D> collapsed = squareLoop().stream()
                .map(point -> new Point2D(
                        200 + 0.05 * (point.x() - 200), point.y()))
                .toList();

        assertThrows(IllegalArgumentException.class,
                () -> ManualOutlineWarp2D.fit(
                        squareLoop(), collapsed, anchors(), WIDTH, HEIGHT));
    }

    @Test
    void provenanceHashBindsSemanticAnchorNames() {
        final ManualOutlineWarp2D baseline = ManualOutlineWarp2D.fit(
                squareLoop(), squareLoop(), anchors(), WIDTH, HEIGHT);
        final List<ManualOutlineWarp2D.AnchorPair> renamed = List.of(
                new ManualOutlineWarp2D.AnchorPair("one", 0, 0),
                new ManualOutlineWarp2D.AnchorPair("two", 4, 4),
                new ManualOutlineWarp2D.AnchorPair("three", 8, 8),
                new ManualOutlineWarp2D.AnchorPair("four", 12, 12));
        final ManualOutlineWarp2D changed = ManualOutlineWarp2D.fit(
                squareLoop(), squareLoop(), renamed, WIDTH, HEIGHT);

        assertNotEquals(baseline.diagnostics().contentSha256(),
                changed.diagnostics().contentSha256());
    }

    private static Point2D smoothStretch(final Point2D point) {
        final double x = point.x() - 200;
        final double y = point.y() - 200;
        return new Point2D(
                point.x() + 0.0006 * x * y,
                point.y() + 0.00035 * x * x - 3.5);
    }

    private static List<ManualOutlineWarp2D.AnchorPair> anchors() {
        return List.of(
                new ManualOutlineWarp2D.AnchorPair("dorsal-left", 0, 0),
                new ManualOutlineWarp2D.AnchorPair("dorsal-right", 4, 4),
                new ManualOutlineWarp2D.AnchorPair("ventral-right", 8, 8),
                new ManualOutlineWarp2D.AnchorPair("ventral-left", 12, 12));
    }

    private static List<Point2D> squareLoop() {
        return List.of(
                new Point2D(100, 100), new Point2D(150, 100),
                new Point2D(200, 100), new Point2D(250, 100),
                new Point2D(300, 100), new Point2D(300, 150),
                new Point2D(300, 200), new Point2D(300, 250),
                new Point2D(300, 300), new Point2D(250, 300),
                new Point2D(200, 300), new Point2D(150, 300),
                new Point2D(100, 300), new Point2D(100, 250),
                new Point2D(100, 200), new Point2D(100, 150));
    }

    private static List<Point2D> coronalLikeLoop() {
        return List.of(
                new Point2D(200, 120), new Point2D(170, 90),
                new Point2D(120, 85), new Point2D(80, 120),
                new Point2D(60, 180), new Point2D(70, 250),
                new Point2D(120, 310), new Point2D(170, 300),
                new Point2D(200, 270), new Point2D(230, 300),
                new Point2D(280, 310), new Point2D(330, 250),
                new Point2D(340, 180), new Point2D(320, 120),
                new Point2D(280, 85), new Point2D(230, 90));
    }

    private static List<Point2D> ellipseLoop(
            final int count,
            final boolean ruffled) {
        final List<Point2D> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final double angle = 2 * Math.PI * index / count;
            final double radial = ruffled
                    ? 1 + 0.045 * Math.sin(8 * angle + 0.3) : 1;
            result.add(new Point2D(
                    200 + 130 * radial * Math.cos(angle),
                    200 + 100 * radial * Math.sin(angle)));
        }
        return List.copyOf(result);
    }
}
