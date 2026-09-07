package org.atlasalign.atlas;

import java.util.Objects;

/**
 * Mapping from 2-D atlas-plane pixel centres to Allen volume voxel centres.
 * Axis-0 is anterior/posterior, axis-1 is superior/inferior, and axis-2 is
 * left/right. The zero-tilt plane is axis-0 constant with plane X=axis-2 and
 * plane Y=axis-1.
 */
public final class AtlasPlaneGeometry {

    private final int zeroBasedAnteriorPosteriorIndex;
    private final int width;
    private final int height;
    private final double sagittalDegrees;
    private final double horizontalDegrees;
    private final double centerAxis1;
    private final double centerAxis2;
    private final double[] xAxis;
    private final double[] yAxis;

    private AtlasPlaneGeometry(
            final int level,
            final int width,
            final int height,
            final double sagittalDegrees,
            final double horizontalDegrees,
            final double[] xAxis,
            final double[] yAxis,
            final double centerAxis1,
            final double centerAxis2) {
        this.zeroBasedAnteriorPosteriorIndex = level;
        this.width = width;
        this.height = height;
        this.sagittalDegrees = sagittalDegrees;
        this.horizontalDegrees = horizontalDegrees;
        this.xAxis = xAxis;
        this.yAxis = yAxis;
        this.centerAxis1 = centerAxis1;
        this.centerAxis2 = centerAxis2;
    }

    public static AtlasPlaneGeometry create(
            final int level,
            final int width,
            final int height,
            final double sagittalDegrees,
            final double horizontalDegrees,
            final int volumeAxis1,
            final int volumeAxis2) {
        if (level < 0 || width <= 0 || height <= 0
                || volumeAxis1 <= 0 || volumeAxis2 <= 0) {
            throw new IllegalArgumentException(
                    "Atlas plane dimensions and level must be positive");
        }
        requireAngle(sagittalDegrees, "sagittalDegrees");
        requireAngle(horizontalDegrees, "horizontalDegrees");
        // DeepSlice reports positive angles in its O/U/V coordinate system,
        // whose AP axis is opposite the Allen axis-0 indexing direction.
        // Negating here keeps the resliced atlas plane in the same signed
        // convention as the stored DeepSlice proposal.
        final double sagittal = -Math.toRadians(sagittalDegrees);
        final double horizontal = -Math.toRadians(horizontalDegrees);
        // Start with plane X=axis-2 and plane Y=axis-1. Apply sagittal
        // rotation around axis-1, then horizontal rotation around axis-2.
        final double[] x = rotateAxis1(
                new double[] {0, 0, 1}, sagittal);
        final double[] y = rotateAxis1(
                new double[] {0, 1, 0}, sagittal);
        rotateAxis2InPlace(x, horizontal);
        rotateAxis2InPlace(y, horizontal);
        return new AtlasPlaneGeometry(
                level,
                width,
                height,
                sagittalDegrees,
                horizontalDegrees,
                x,
                y,
                (volumeAxis1 - 1) * 0.5,
                (volumeAxis2 - 1) * 0.5);
    }

    public static AtlasPlaneGeometry axisAligned(
            final int level,
            final int width,
            final int height,
            final int volumeAxis1,
            final int volumeAxis2) {
        return create(level, width, height, 0, 0, volumeAxis1, volumeAxis2);
    }

    public int zeroBasedAnteriorPosteriorIndex() {
        return zeroBasedAnteriorPosteriorIndex;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public double sagittalDegrees() {
        return sagittalDegrees;
    }

    public double horizontalDegrees() {
        return horizontalDegrees;
    }

    /** Returns axis-0, axis-1, axis-2 for the supplied plane pixel centre. */
    public double[] voxelForPlanePixel(
            final double planeX,
            final double planeY) {
        final double dx = planeX - (width - 1) * 0.5;
        final double dy = planeY - (height - 1) * 0.5;
        return new double[] {
                zeroBasedAnteriorPosteriorIndex
                        + xAxis[0] * dx + yAxis[0] * dy,
                centerAxis1 + xAxis[1] * dx + yAxis[1] * dy,
                centerAxis2 + xAxis[2] * dx + yAxis[2] * dy};
    }

    /** Returns true when a voxel coordinate is inside the volume bounds. */
    public static boolean inside(
            final double[] voxel,
            final int axis0,
            final int axis1,
            final int axis2) {
        Objects.requireNonNull(voxel, "voxel");
        return voxel.length == 3
                && Double.isFinite(voxel[0])
                && Double.isFinite(voxel[1])
                && Double.isFinite(voxel[2])
                && voxel[0] >= 0 && voxel[0] <= axis0 - 1
                && voxel[1] >= 0 && voxel[1] <= axis1 - 1
                && voxel[2] >= 0 && voxel[2] <= axis2 - 1;
    }

    private static double[] rotateAxis1(
            final double[] vector,
            final double radians) {
        final double cosine = Math.cos(radians);
        final double sine = Math.sin(radians);
        return new double[] {
                cosine * vector[0] + sine * vector[2],
                vector[1],
                -sine * vector[0] + cosine * vector[2]};
    }

    private static void rotateAxis2InPlace(
            final double[] vector,
            final double radians) {
        final double cosine = Math.cos(radians);
        final double sine = Math.sin(radians);
        final double axis0 = vector[0];
        final double axis1 = vector[1];
        vector[0] = cosine * axis0 - sine * axis1;
        vector[1] = sine * axis0 + cosine * axis1;
    }

    private static void requireAngle(
            final double degrees,
            final String name) {
        if (!Double.isFinite(degrees)
                || Math.abs(degrees) > 45.0) {
            throw new IllegalArgumentException(
                    name + " must be finite and within +/-45 degrees");
        }
    }
}
