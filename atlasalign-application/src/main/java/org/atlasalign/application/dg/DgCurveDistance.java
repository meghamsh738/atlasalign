package org.atlasalign.application.dg;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import org.atlasalign.core.Point2D;

/** Symmetric mean nearest-point distance between two resampled DG curves. */
public final class DgCurveDistance {

    /**
     * Resamples and compares left-to-left and right-to-right evidence.
     * Missing or empty evidence on either anatomical side is an abstention.
     */
    public OptionalDouble bilateralSidePreservingDistance(
            final Map<AnatomicalSide, List<Point2D>> tissueBySide,
            final Map<AnatomicalSide, List<Point2D>> atlasBySide) {
        Objects.requireNonNull(tissueBySide, "tissueBySide");
        Objects.requireNonNull(atlasBySide, "atlasBySide");
        double sum = 0;
        final DgCurveResampler resampler = new DgCurveResampler();
        for (final AnatomicalSide side : AnatomicalSide.values()) {
            final List<Point2D> tissue = tissueBySide.get(side);
            final List<Point2D> atlas = atlasBySide.get(side);
            if (tissue == null || atlas == null
                    || tissue.size() < 2 || atlas.size() < 2) {
                return OptionalDouble.empty();
            }
            sum += symmetricCentrelineDistance(
                    resampler.resample(tissue),
                    resampler.resample(atlas));
        }
        return OptionalDouble.of(sum / AnatomicalSide.values().length);
    }

    public double symmetricCentrelineDistance(
            final List<Point2D> first,
            final List<Point2D> second) {
        requireCurve(first, "first");
        requireCurve(second, "second");
        return 0.5 * (directed(first, second) + directed(second, first));
    }

    private static double directed(
            final List<Point2D> source,
            final List<Point2D> target) {
        double total = 0;
        for (final Point2D point : source) {
            double nearestSquared = Double.POSITIVE_INFINITY;
            for (final Point2D candidate : target) {
                final double dx = point.x() - candidate.x();
                final double dy = point.y() - candidate.y();
                nearestSquared = Math.min(
                        nearestSquared, dx * dx + dy * dy);
            }
            total += Math.sqrt(nearestSquared);
        }
        return total / source.size();
    }

    private static void requireCurve(
            final List<Point2D> curve,
            final String label) {
        Objects.requireNonNull(curve, label);
        if (curve.size() < 2 || curve.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    label + " DG curve must contain at least two points");
        }
    }
}
