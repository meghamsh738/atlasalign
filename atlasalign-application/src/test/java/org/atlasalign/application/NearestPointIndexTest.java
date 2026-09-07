package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class NearestPointIndexTest {

    @Test
    void handlesSingletonDuplicatesAndAxisTies() {
        final List<Point2D> points = List.of(
                new Point2D(4, 1),
                new Point2D(4, 1),
                new Point2D(4, 5),
                new Point2D(4, 9));
        final NearestPointIndex spatialIndex =
                new NearestPointIndex(points);

        assertEquals(
                4.0,
                spatialIndex.nearestSquaredDistance(
                        new Point2D(6, 5)),
                0.0);
        assertEquals(
                25.0,
                new NearestPointIndex(List.of(
                        new Point2D(1, 2)))
                        .nearestSquaredDistance(
                                new Point2D(4, 6)),
                0.0);
    }

    @Test
    void matchesExhaustiveNearestDistanceExactly() {
        final Random random = new Random(0x41544c41534cL);
        final List<Point2D> points = new ArrayList<>();
        for (int index = 0; index < 2_048; index++) {
            points.add(new Point2D(
                    random.nextInt(456),
                    random.nextInt(320)));
        }
        final NearestPointIndex spatialIndex =
                new NearestPointIndex(points);

        for (int index = 0; index < 1_024; index++) {
            final Point2D query = new Point2D(
                    random.nextDouble(-50, 506),
                    random.nextDouble(-50, 370));
            assertEquals(
                    exhaustive(query, points),
                    spatialIndex.nearestSquaredDistance(query),
                    0.0);
            assertEquals(
                    spatialIndex.nearestSquaredDistance(query),
                    spatialIndex.nearestSquaredDistance(
                            query.x(), query.y()),
                    0.0);
        }
    }

    @Test
    void prunesDenseTreesForFarOutsideQueries() {
        final List<Point2D> points = new ArrayList<>();
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                points.add(new Point2D(x, y));
            }
        }
        final NearestPointIndex spatialIndex =
                new NearestPointIndex(points);

        assertTimeout(Duration.ofSeconds(2), () -> {
            for (int index = 0; index < 10_000; index++) {
                assertEquals(
                        2_000_000.0,
                        spatialIndex.nearestSquaredDistance(
                                new Point2D(-1_000, -1_000)),
                        0.0);
            }
        });
    }

    @Test
    void matchesExhaustiveSearchAcrossEmptyCellsAndFractionalSites() {
        final Random random = new Random(0x475249445631L);
        final List<Point2D> points = new ArrayList<>();
        for (int index = 0; index < 1_000; index++) {
            points.add(new Point2D(
                    random.nextDouble(-320, 1_280),
                    random.nextDouble(-240, 960)));
        }
        points.addAll(List.of(
                new Point2D(-320, -240),
                new Point2D(-320, -240),
                new Point2D(1_280, 960),
                new Point2D(0.5, 512.5),
                new Point2D(1_000.25, -100.75)));
        final NearestPointIndex index =
                new NearestPointIndex(points);
        final List<Point2D> fixedQueries = List.of(
                new Point2D(-10_000, -10_000),
                new Point2D(10_000, 10_000),
                new Point2D(480, 360),
                new Point2D(-320, -240),
                new Point2D(1_280, 960));
        for (final Point2D query : fixedQueries) {
            assertEquals(
                    exhaustive(query, points),
                    index.nearestSquaredDistance(
                            query.x(), query.y()),
                    0);
        }
        for (int queryIndex = 0;
                queryIndex < 2_000;
                queryIndex++) {
            final Point2D query = new Point2D(
                    random.nextDouble(-2_000, 3_000),
                    random.nextDouble(-2_000, 2_000));
            assertEquals(
                    exhaustive(query, points),
                    index.nearestSquaredDistance(
                            query.x(), query.y()),
                    0);
        }
    }

    @Test
    void searchesBothSidesOfCoordinateColumnsExactly() {
        final List<Point2D> points = List.of(
                new Point2D(-10, -10),
                new Point2D(-10, 10),
                new Point2D(0, -1),
                new Point2D(0, 1),
                new Point2D(10, -10),
                new Point2D(10, 10));
        final NearestPointIndex index =
                new NearestPointIndex(points);
        for (final Point2D query : List.of(
                new Point2D(0, 0),
                new Point2D(0, -10),
                new Point2D(0, 10),
                new Point2D(-10, 0),
                new Point2D(10, 0))) {
            assertEquals(
                    exhaustive(query, points),
                    index.nearestSquaredDistance(query),
                    0);
        }
    }

    @Test
    void matchesExhaustiveAtColumnBoundsAndExactTies() {
        final List<Point2D> points = new ArrayList<>();
        for (int y = -40; y <= 40; y += 5) {
            for (int x = -60; x <= 60; x += 5) {
                if ((x + y) % 15 != 0) {
                    points.add(new Point2D(x, y));
                }
            }
        }
        final NearestPointIndex index =
                new NearestPointIndex(points);
        for (int y = -45; y <= 45; y += 5) {
            for (int x = -65; x <= 65; x += 5) {
                final Point2D query = new Point2D(x, y);
                assertEquals(
                        exhaustive(query, points),
                        index.nearestSquaredDistance(query),
                        0);
            }
        }
    }

    @Test
    void supportsConcurrentQueriesAcrossFractionalColumns()
            throws Exception {
        final List<Point2D> points = List.of(
                new Point2D(-3.5, -8.25),
                new Point2D(-3.5, 7.75),
                new Point2D(0.5, -1.25),
                new Point2D(0.5, 1.25),
                new Point2D(4.5, -8.25),
                new Point2D(4.5, 7.75),
                new Point2D(4.5, 7.75));
        final List<Point2D> queries = List.of(
                new Point2D(-20, 0),
                new Point2D(20, 0),
                new Point2D(-1.5, 0),
                new Point2D(2.5, 0),
                new Point2D(0.5, 0),
                new Point2D(4.5, 7.75));
        final NearestPointIndex index =
                new NearestPointIndex(points);
        final var executor = Executors.newFixedThreadPool(8);
        try {
            final List<java.util.concurrent.Callable<Void>> tasks =
                    new ArrayList<>();
            for (int task = 0; task < 64; task++) {
                tasks.add(() -> {
                    for (int repetition = 0;
                            repetition < 100;
                            repetition++) {
                        for (final Point2D query : queries) {
                            assertEquals(
                                    exhaustive(query, points),
                                    index.nearestSquaredDistance(query),
                                    0);
                        }
                    }
                    return null;
                });
            }
            for (final var future : executor.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void matchesExhaustiveForSignedZeroAndExtremeFiniteValues() {
        final List<Point2D> points = List.of(
                new Point2D(-0.0, -0.0),
                new Point2D(0.0, 0.0),
                new Point2D(Double.MIN_VALUE, -Double.MIN_VALUE),
                new Point2D(Double.MAX_VALUE, Double.MAX_VALUE),
                new Point2D(-Double.MAX_VALUE, -Double.MAX_VALUE));
        final NearestPointIndex index =
                new NearestPointIndex(points);
        for (final Point2D query : List.of(
                new Point2D(-0.0, 0.0),
                new Point2D(0.0, -0.0),
                new Point2D(Double.MIN_VALUE, Double.MIN_VALUE),
                new Point2D(Double.MAX_VALUE, 0.0),
                new Point2D(-Double.MAX_VALUE, 0.0))) {
            assertEquals(
                    exhaustive(query, points),
                    index.nearestSquaredDistance(query),
                    0);
        }
    }

    @Test
    void rejectsNonFinitePrimitiveQueries() {
        final NearestPointIndex index =
                new NearestPointIndex(List.of(
                        new Point2D(0, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> index.nearestSquaredDistance(
                        Double.NaN, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> index.nearestSquaredDistance(
                        Double.POSITIVE_INFINITY, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> index.nearestSquaredDistance(
                        0, Double.NEGATIVE_INFINITY));
    }

    private static double exhaustive(
            final Point2D query,
            final List<Point2D> points) {
        double nearest = Double.POSITIVE_INFINITY;
        for (final Point2D point : points) {
            final double differenceX = query.x() - point.x();
            final double differenceY = query.y() - point.y();
            nearest = Math.min(
                    nearest,
                    differenceX * differenceX
                            + differenceY * differenceY);
        }
        return nearest;
    }
}
