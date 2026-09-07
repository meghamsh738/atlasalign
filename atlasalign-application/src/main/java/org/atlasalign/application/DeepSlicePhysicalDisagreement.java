package org.atlasalign.application;

/**
 * Physical comparison of the two application-derived DeepSlice planes.
 * Values are diagnostics, not calibrated confidence or accuracy estimates.
 */
public record DeepSlicePhysicalDisagreement(
        double centerAnteriorPosteriorMicrometers,
        double normalAngleDegrees,
        double mediolateralGradientMicrometersPerMillimeter,
        double dorsoventralGradientMicrometersPerMillimeter,
        double maximumGridDisplacementMicrometers) {

    public DeepSlicePhysicalDisagreement {
        requireNonnegative(centerAnteriorPosteriorMicrometers, "center AP");
        requireNonnegative(normalAngleDegrees, "normal angle");
        requireNonnegative(
                mediolateralGradientMicrometersPerMillimeter,
                "mediolateral gradient");
        requireNonnegative(
                dorsoventralGradientMicrometersPerMillimeter,
                "dorsoventral gradient");
        requireNonnegative(
                maximumGridDisplacementMicrometers,
                "maximum grid displacement");
    }

    public static DeepSlicePhysicalDisagreement between(
            final DeepSlicePlaneGeometry first,
            final DeepSlicePlaneGeometry second,
            final int planeWidth,
            final int planeHeight,
            final double voxelMicrometers) {
        if (planeWidth <= 0 || planeHeight <= 0
                || !Double.isFinite(voxelMicrometers)
                || voxelMicrometers <= 0) {
            throw new IllegalArgumentException(
                    "Physical disagreement geometry must be positive");
        }
        final double firstSagittal = Math.tan(Math.toRadians(
                first.sagittalTiltDegrees()));
        final double secondSagittal = Math.tan(Math.toRadians(
                second.sagittalTiltDegrees()));
        final double firstHorizontal = Math.tan(Math.toRadians(
                first.horizontalTiltDegrees()));
        final double secondHorizontal = Math.tan(Math.toRadians(
                second.horizontalTiltDegrees()));
        final double centerDifference = Math.abs(
                first.zeroBasedAnteriorPosteriorIndex()
                        - second.zeroBasedAnteriorPosteriorIndex())
                * voxelMicrometers;
        final double mlGradient = Math.abs(
                firstSagittal - secondSagittal) * 1_000;
        final double dvGradient = Math.abs(
                firstHorizontal - secondHorizontal) * 1_000;
        final double[] firstNormal = normal(
                firstSagittal, firstHorizontal);
        final double[] secondNormal = normal(
                secondSagittal, secondHorizontal);
        final double cosine = Math.max(-1, Math.min(1,
                firstNormal[0] * secondNormal[0]
                        + firstNormal[1] * secondNormal[1]
                        + firstNormal[2] * secondNormal[2]));
        final double normalAngle = Math.toDegrees(Math.acos(cosine));

        final double halfWidth = (planeWidth - 1) * 0.5
                * voxelMicrometers;
        final double halfHeight = (planeHeight - 1) * 0.5
                * voxelMicrometers;
        double maximum = 0;
        for (final double x : new double[]{-halfWidth, 0, halfWidth}) {
            for (final double y : new double[]{-halfHeight, 0, halfHeight}) {
                final double firstAp = first
                        .zeroBasedAnteriorPosteriorIndex()
                        * voxelMicrometers + firstSagittal * x
                        + firstHorizontal * y;
                final double secondAp = second
                        .zeroBasedAnteriorPosteriorIndex()
                        * voxelMicrometers + secondSagittal * x
                        + secondHorizontal * y;
                maximum = Math.max(maximum, Math.abs(firstAp - secondAp));
            }
        }
        return new DeepSlicePhysicalDisagreement(
                centerDifference, normalAngle, mlGradient, dvGradient,
                maximum);
    }

    private static double[] normal(
            final double sagittalGradient,
            final double horizontalGradient) {
        final double length = Math.sqrt(1
                + sagittalGradient * sagittalGradient
                + horizontalGradient * horizontalGradient);
        return new double[]{
            1 / length,
            -horizontalGradient / length,
            -sagittalGradient / length};
    }

    private static void requireNonnegative(
            final double value, final String label) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(
                    label + " must be finite and nonnegative");
        }
    }
}
