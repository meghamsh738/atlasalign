package org.atlasalign.application;

import java.util.Objects;

/**
 * Application-derived coronal level and diagnostic tilts for one O/U/V model
 * prediction. This conversion is pure and has no ImageJ dependency.
 */
public record DeepSlicePlaneGeometry(
        int zeroBasedAnteriorPosteriorIndex,
        double sagittalTiltDegrees,
        double horizontalTiltDegrees) {

    public DeepSlicePlaneGeometry {
        if (zeroBasedAnteriorPosteriorIndex < 0
                || zeroBasedAnteriorPosteriorIndex
                >= AllenCoronalLevel.PLANE_COUNT
                || !Double.isFinite(sagittalTiltDegrees)
                || !Double.isFinite(horizontalTiltDegrees)
                || Math.abs(sagittalTiltDegrees) > 45
                || Math.abs(horizontalTiltDegrees) > 45) {
            throw new IllegalArgumentException(
                    "DeepSlice plane geometry is outside supported coronal bounds");
        }
    }

    /**
     * Converts a finite QuickNII O/U/V vector with the frozen
     * quicknii_center_depth and deep_slice_tilts equations.
     */
    public static DeepSlicePlaneGeometry from(final DeepSliceOuv ouv) {
        Objects.requireNonNull(ouv, "ouv");

        final double nx = finite(
                (ouv.uy() * ouv.vz() - ouv.uz() * ouv.vy()) / 9,
                "normal x");
        final double ny = finite(
                (ouv.uz() * ouv.vx() - ouv.ux() * ouv.vz()) / 9,
                "normal y");
        final double nz = finite(
                (ouv.ux() * ouv.vy() - ouv.uy() * ouv.vx()) / 9,
                "normal z");
        if (ny == 0.0) {
            throw new IllegalArgumentException(
                    "DeepSlice O/U/V has a singular normal denominator");
        }

        final double k = finite(-fsum(
                finite(ouv.ox() * nx, "origin-normal x"),
                finite(ouv.oy() * ny, "origin-normal y"),
                finite(ouv.oz() * nz, "origin-normal z")),
                "plane intercept");
        final double depth = finite(-finite(
                228 * nx + 160 * nz + k, "center-depth numerator") / ny,
                "center depth");
        final double roundedDepth = finite(Math.floor(depth + 0.5),
                "rounded center depth");
        final double axis = finite(527 - roundedDepth, "axis 0");
        if (axis < 0 || axis >= AllenCoronalLevel.PLANE_COUNT) {
            throw new IllegalArgumentException(
                    "DeepSlice center depth maps outside Allen axis 0");
        }

        final double mlDepth = finite(-finite(
                finite(ouv.ox() - 100, "medial origin x") * nx
                + finite(ouv.oz() * nz, "medial origin z") + k,
                "sagittal numerator") / ny, "medial depth");
        final double sagittal = signedAngle(
                finite(ouv.ox() - finite(ouv.ox() - 100,
                        "medial line x"), "sagittal vector x"),
                finite(ouv.oy() - mlDepth, "sagittal vector y"),
                100,
                0,
                mlDepth > ouv.oy());

        final double dvDepth = finite(-finite(
                finite(ouv.ox() * nx, "dorsal origin x")
                + finite(ouv.oz() - 100, "dorsal origin z") * nz + k,
                "horizontal numerator") / ny, "dorsal depth");
        final double horizontal = signedAngle(
                finite(ouv.oy() - dvDepth, "horizontal vector x"),
                finite(ouv.oz() - finite(ouv.oz() - 100,
                        "dorsal line y"), "horizontal vector y"),
                0,
                100,
                dvDepth < ouv.oy());
        return new DeepSlicePlaneGeometry((int) axis, sagittal, horizontal);
    }

    private static double signedAngle(
            final double ax,
            final double ay,
            final double bx,
            final double by,
            final boolean negative) {
        final double dot = finite(ax * bx + ay * by, "angle dot product");
        final double firstNorm = finite(Math.sqrt(ax * ax + ay * ay),
                "first angle norm");
        final double secondNorm = finite(Math.sqrt(bx * bx + by * by),
                "second angle norm");
        if (firstNorm == 0.0 || secondNorm == 0.0) {
            throw new IllegalArgumentException(
                    "DeepSlice O/U/V has a zero tilt-angle vector");
        }
        final double cosine = finite(dot / finite(firstNorm * secondNorm,
                "angle norm product"), "angle cosine");
        final double clamped = Math.max(-1, Math.min(1, cosine));
        final double degrees = finite(Math.toDegrees(Math.acos(clamped)),
                "tilt angle");
        return finite(negative ? -degrees : degrees, "signed tilt angle");
    }

    /**
     * A deterministic compensated summation port of Python's finite
     * {@code math.fsum} behavior for the three O-dot-normal terms.
     */
    private static double fsum(
            final double first, final double second, final double third) {
        final double[] partials = new double[3];
        int count = 0;
        for (final double input : new double[]{first, second, third}) {
            double x = input;
            int retained = 0;
            for (int index = 0; index < count; index++) {
                double y = partials[index];
                if (Math.abs(x) < Math.abs(y)) {
                    final double temporary = x;
                    x = y;
                    y = temporary;
                }
                final double high = finite(x + y, "compensated sum");
                final double roundoff = high - x;
                final double low = y - roundoff;
                if (low != 0.0) {
                    partials[retained++] = low;
                }
                x = high;
            }
            if (x != 0.0) {
                partials[retained++] = x;
            }
            count = retained;
        }
        if (count == 0) {
            return 0.0;
        }
        double high = partials[--count];
        while (count > 0) {
            final double x = high;
            final double y = partials[--count];
            high = finite(x + y, "compensated sum");
            final double roundoff = high - x;
            final double low = y - roundoff;
            if (low != 0.0) {
                if (count > 0 && ((low < 0 && partials[count - 1] < 0)
                        || (low > 0 && partials[count - 1] > 0))) {
                    final double twiceLow = low * 2;
                    final double corrected = high + twiceLow;
                    if (twiceLow == corrected - high) {
                        high = corrected;
                    }
                }
                break;
            }
        }
        return finite(high, "compensated sum");
    }

    private static double finite(final double value, final String label) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "DeepSlice " + label + " must be finite");
        }
        return value;
    }
}
