package org.atlasalign.application;

import java.util.List;
import java.util.Objects;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;

/**
 * Fits an explicit, non-reflecting global affine correction in preview space
 * from reviewer-entered landmark correspondences. This is intentionally a
 * single global transform: it can correct unequal scale and shear but cannot
 * bend individual atlas regions or alter source pixels.
 */
public final class LandmarkAffineSolver {

    private static final double MINIMUM_NORMALIZED_SOURCE_AREA = 1e-12;

    private LandmarkAffineSolver() {
    }

    /**
     * Fits the least-squares affine transform mapping {@code sourcePoints} to
     * {@code targetPoints}. Three non-collinear source points are required.
     * A fitted reflection is rejected so reflection remains the explicit atlas
     * orientation decision rather than a side effect of point fitting.
     */
    public static AffineTransform2D fitPreviewCorrection(
            final List<Point2D> sourcePoints,
            final List<Point2D> targetPoints) {
        final List<Point2D> source = List.copyOf(Objects.requireNonNull(
                sourcePoints, "sourcePoints"));
        final List<Point2D> target = List.copyOf(Objects.requireNonNull(
                targetPoints, "targetPoints"));
        if (source.size() != target.size() || source.size() < 3) {
            throw new IllegalArgumentException(
                    "At least three landmark pairs are required to fit a global affine transform");
        }

        final Point2D sourceCentroid = centroid(source, "source");
        final Point2D targetCentroid = centroid(target, "target");
        double xx = 0;
        double xy = 0;
        double yy = 0;
        double targetXSourceX = 0;
        double targetXSourceY = 0;
        double targetYSourceX = 0;
        double targetYSourceY = 0;
        for (int index = 0; index < source.size(); index++) {
            final Point2D sourcePoint = source.get(index);
            final Point2D targetPoint = target.get(index);
            requireFinite(sourcePoint, "source");
            requireFinite(targetPoint, "target");
            final double sourceX = sourcePoint.x() - sourceCentroid.x();
            final double sourceY = sourcePoint.y() - sourceCentroid.y();
            final double targetX = targetPoint.x() - targetCentroid.x();
            final double targetY = targetPoint.y() - targetCentroid.y();
            xx += sourceX * sourceX;
            xy += sourceX * sourceY;
            yy += sourceY * sourceY;
            targetXSourceX += targetX * sourceX;
            targetXSourceY += targetX * sourceY;
            targetYSourceX += targetY * sourceX;
            targetYSourceY += targetY * sourceY;
        }
        final double determinant = xx * yy - xy * xy;
        final double scale = Math.max(Math.max(Math.abs(xx), Math.abs(xy)),
                Math.abs(yy));
        if (!Double.isFinite(determinant) || scale == 0
                || Math.abs(determinant / (scale * scale))
                < MINIMUM_NORMALIZED_SOURCE_AREA) {
            throw new IllegalArgumentException(
                    "Affine landmark source points must include three non-collinear locations");
        }

        final double inverse00 = yy / determinant;
        final double inverse01 = -xy / determinant;
        final double inverse10 = -xy / determinant;
        final double inverse11 = xx / determinant;
        final double m00 = targetXSourceX * inverse00
                + targetXSourceY * inverse10;
        final double m01 = targetXSourceX * inverse01
                + targetXSourceY * inverse11;
        final double m10 = targetYSourceX * inverse00
                + targetYSourceY * inverse10;
        final double m11 = targetYSourceX * inverse01
                + targetYSourceY * inverse11;
        final double linearDeterminant = m00 * m11 - m01 * m10;
        if (!Double.isFinite(linearDeterminant) || linearDeterminant <= 0) {
            throw new IllegalArgumentException(
                    "Affine landmark fit would introduce a reflection; confirm orientation explicitly instead");
        }
        return new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                m00, m01,
                targetCentroid.x() - m00 * sourceCentroid.x()
                        - m01 * sourceCentroid.y(),
                m10, m11,
                targetCentroid.y() - m10 * sourceCentroid.x()
                        - m11 * sourceCentroid.y());
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
