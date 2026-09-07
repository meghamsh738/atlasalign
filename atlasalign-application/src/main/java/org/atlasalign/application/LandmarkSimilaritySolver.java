package org.atlasalign.application;

import java.util.List;
import java.util.Objects;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.SimilarityTransform2D;

/**
 * Fits a non-reflecting similarity correction in preview space from landmark
 * correspondences. The supplied source points and the returned transform both
 * use preview-pixel coordinates, so source-image pixels are never resampled
 * or modified.
 */
public final class LandmarkSimilaritySolver {

    private static final double MINIMUM_CENTERED_SPREAD_SQUARED = 1e-12;

    private LandmarkSimilaritySolver() {
    }

    /**
     * Fits the least-squares positive-scale rotation and translation mapping
     * {@code sourcePoints} to {@code targetPoints}. At least two distinct
     * source points are required; reflection is deliberately not a fit
     * parameter and remains an explicit reviewer decision.
     */
    public static SimilarityTransform2D fitPreviewCorrection(
            final List<Point2D> sourcePoints,
            final List<Point2D> targetPoints) {
        final List<Point2D> checkedSource = List.copyOf(Objects.requireNonNull(
                sourcePoints, "sourcePoints"));
        final List<Point2D> checkedTarget = List.copyOf(Objects.requireNonNull(
                targetPoints, "targetPoints"));
        if (checkedSource.size() != checkedTarget.size()
                || checkedSource.size() < 2) {
            throw new IllegalArgumentException(
                    "At least two landmark pairs are required to fit a similarity transform");
        }

        final Point2D sourceCentroid = centroid(checkedSource, "source");
        final Point2D targetCentroid = centroid(checkedTarget, "target");
        double denominator = 0;
        double dot = 0;
        double cross = 0;
        for (int index = 0; index < checkedSource.size(); index++) {
            final Point2D source = checkedSource.get(index);
            final Point2D target = checkedTarget.get(index);
            requireFinite(source, "source");
            requireFinite(target, "target");
            final double sourceX = source.x() - sourceCentroid.x();
            final double sourceY = source.y() - sourceCentroid.y();
            final double targetX = target.x() - targetCentroid.x();
            final double targetY = target.y() - targetCentroid.y();
            denominator += sourceX * sourceX + sourceY * sourceY;
            dot += sourceX * targetX + sourceY * targetY;
            cross += sourceX * targetY - sourceY * targetX;
        }
        if (!Double.isFinite(denominator)
                || denominator <= MINIMUM_CENTERED_SPREAD_SQUARED) {
            throw new IllegalArgumentException(
                    "Landmark source points must not be coincident");
        }
        final double scale = Math.hypot(dot, cross) / denominator;
        if (!Double.isFinite(scale) || scale == 0) {
            throw new IllegalArgumentException(
                    "Landmark target points do not define a positive-scale similarity transform");
        }
        final double rotation = Math.atan2(cross, dot);
        final double cosine = Math.cos(rotation);
        final double sine = Math.sin(rotation);
        final double translationX = targetCentroid.x()
                - scale * (cosine * sourceCentroid.x()
                - sine * sourceCentroid.y());
        final double translationY = targetCentroid.y()
                - scale * (sine * sourceCentroid.x()
                + cosine * sourceCentroid.y());
        return new SimilarityTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                scale,
                rotation,
                translationX,
                translationY);
    }

    private static Point2D centroid(
            final List<Point2D> points,
            final String name) {
        double x = 0;
        double y = 0;
        for (final Point2D point : points) {
            requireFinite(point, name);
            x += point.x();
            y += point.y();
        }
        final Point2D centroid = new Point2D(
                x / points.size(), y / points.size());
        requireFinite(centroid, name + " centroid");
        return centroid;
    }

    private static void requireFinite(
            final Point2D point,
            final String name) {
        Objects.requireNonNull(point, name + " point");
        if (!Double.isFinite(point.x()) || !Double.isFinite(point.y())) {
            throw new IllegalArgumentException(
                    name + " landmark point must be finite");
        }
    }
}
