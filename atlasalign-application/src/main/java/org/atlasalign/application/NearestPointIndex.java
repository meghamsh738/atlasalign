package org.atlasalign.application;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import org.atlasalign.core.Point2D;

/**
 * Immutable exact index of sorted coordinate columns.
 */
final class NearestPointIndex {

    private final double[] columnX;
    private final double[][] columnY;

    NearestPointIndex(final List<Point2D> points) {
        Objects.requireNonNull(points, "points");
        if (points.isEmpty()) {
            throw new IllegalArgumentException(
                    "Nearest-point index must not be empty");
        }
        final TreeMap<Double, List<Double>> columns =
                new TreeMap<>();
        for (final Point2D point : points) {
            final Point2D required = Objects.requireNonNull(
                    point, "point");
            columns.computeIfAbsent(
                    required.x(),
                    ignored -> new java.util.ArrayList<>())
                    .add(required.y());
        }
        columnX = new double[columns.size()];
        columnY = new double[columns.size()][];
        int column = 0;
        for (final var entry : columns.entrySet()) {
            columnX[column] = entry.getKey();
            final List<Double> ordinates = entry.getValue();
            ordinates.sort(Double::compare);
            columnY[column] = new double[ordinates.size()];
            for (int row = 0; row < ordinates.size(); row++) {
                columnY[column][row] = ordinates.get(row);
            }
            column++;
        }
    }

    double nearestSquaredDistance(final Point2D point) {
        Objects.requireNonNull(point, "point");
        return nearestSquaredDistance(point.x(), point.y());
    }

    double nearestSquaredDistance(
            final double x,
            final double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException(
                    "Nearest-point query coordinates must be finite");
        }
        final int search = Arrays.binarySearch(columnX, x);
        final int insertion = search >= 0
                ? search : -search - 1;
        int left = insertion - 1;
        int right = insertion;
        double best = Double.POSITIVE_INFINITY;
        while (left >= 0 || right < columnX.length) {
            final double leftDifference = left >= 0
                    ? x - columnX[left]
                    : Double.POSITIVE_INFINITY;
            final double rightDifference =
                    right < columnX.length
                            ? x - columnX[right]
                            : Double.POSITIVE_INFINITY;
            final double leftBound =
                    leftDifference * leftDifference;
            final double rightBound =
                    rightDifference * rightDifference;
            final boolean visitLeft = right >= columnX.length
                    || left >= 0 && leftBound <= rightBound;
            final double nextBound = visitLeft
                    ? leftBound : rightBound;
            if (nextBound > best) {
                break;
            }
            final int selected = visitLeft
                    ? left-- : right++;
            best = Math.min(
                    best,
                    nearestInColumn(
                            selected, x, y, nextBound));
        }
        return best;
    }

    private double nearestInColumn(
            final int column,
            final double queryX,
            final double queryY,
            final double xSquaredDistance) {
        final double[] ordinates = columnY[column];
        final int search = Arrays.binarySearch(
                ordinates, queryY);
        if (search >= 0) {
            return xSquaredDistance;
        }
        final int insertion = -search - 1;
        double best = Double.POSITIVE_INFINITY;
        if (insertion < ordinates.length) {
            final double differenceX =
                    queryX - columnX[column];
            final double differenceY =
                    queryY - ordinates[insertion];
            best = differenceX * differenceX
                    + differenceY * differenceY;
        }
        if (insertion > 0) {
            final double differenceX =
                    queryX - columnX[column];
            final double differenceY =
                    queryY - ordinates[insertion - 1];
            best = Math.min(
                    best,
                    differenceX * differenceX
                            + differenceY * differenceY);
        }
        return best;
    }
}
