package org.atlasalign.application.dg;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.atlasalign.core.Point2D;

/** Deterministic arc-length resampling for source and atlas DG curves. */
public final class DgCurveResampler {

    public static final int FROZEN_POINT_COUNT = 128;

    public List<Point2D> resample(final List<Point2D> polyline) {
        return resample(polyline, FROZEN_POINT_COUNT);
    }

    public List<Point2D> resample(
            final List<Point2D> polyline,
            final int outputCount) {
        Objects.requireNonNull(polyline, "polyline");
        if (polyline.size() < 2 || outputCount < 2) {
            throw new IllegalArgumentException(
                    "DG resampling requires two input and output points");
        }
        final double[] cumulative = new double[polyline.size()];
        for (int index = 1; index < polyline.size(); index++) {
            final Point2D previous = Objects.requireNonNull(
                    polyline.get(index - 1), "polyline point");
            final Point2D current = Objects.requireNonNull(
                    polyline.get(index), "polyline point");
            cumulative[index] = cumulative[index - 1]
                    + Math.hypot(
                            current.x() - previous.x(),
                            current.y() - previous.y());
        }
        final double total = cumulative[cumulative.length - 1];
        if (!(total > 0) || !Double.isFinite(total)) {
            throw new IllegalArgumentException(
                    "DG polyline must have positive finite arc length");
        }
        final ArrayList<Point2D> output = new ArrayList<>(outputCount);
        int segment = 1;
        for (int index = 0; index < outputCount; index++) {
            final double target = total * index / (outputCount - 1.0);
            while (segment < cumulative.length - 1
                    && cumulative[segment] < target) {
                segment++;
            }
            final Point2D first = polyline.get(segment - 1);
            final Point2D second = polyline.get(segment);
            final double length = cumulative[segment]
                    - cumulative[segment - 1];
            final double fraction = length == 0 ? 0
                    : (target - cumulative[segment - 1]) / length;
            output.add(new Point2D(
                    first.x() + fraction * (second.x() - first.x()),
                    first.y() + fraction * (second.y() - first.y())));
        }
        return List.copyOf(output);
    }
}
