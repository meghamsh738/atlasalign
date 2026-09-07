package org.atlasalign.core;

import java.util.Objects;

/**
 * Reversible named affine transform using pixel-center coordinates.
 */
public record AffineTransform2D(
        CoordinateSpace2D sourceSpace,
        CoordinateSpace2D destinationSpace,
        double m00,
        double m01,
        double m02,
        double m10,
        double m11,
        double m12) {

    private static final double MINIMUM_NORMALIZED_DETERMINANT = 1e-12;

    public AffineTransform2D {
        sourceSpace = Objects.requireNonNull(sourceSpace, "sourceSpace");
        destinationSpace = Objects.requireNonNull(
                destinationSpace, "destinationSpace");
        if (!allFinite(m00, m01, m02, m10, m11, m12)) {
            throw new IllegalArgumentException(
                    "Affine coefficients must be finite");
        }
        final double maximumLinear = Math.max(
                Math.max(Math.abs(m00), Math.abs(m01)),
                Math.max(Math.abs(m10), Math.abs(m11)));
        final double normalizedDeterminant = maximumLinear == 0
                ? 0
                : m00 / maximumLinear * (m11 / maximumLinear)
                - m01 / maximumLinear * (m10 / maximumLinear);
        final double determinant = m00 * m11 - m01 * m10;
        if (!Double.isFinite(determinant)
                || determinant == 0
                || Math.abs(normalizedDeterminant)
                < MINIMUM_NORMALIZED_DETERMINANT) {
            throw new IllegalArgumentException(
                    "Affine transform is not safely invertible");
        }
        final double inverse00 = m11 / determinant;
        final double inverse01 = -m01 / determinant;
        final double inverse10 = -m10 / determinant;
        final double inverse11 = m00 / determinant;
        final double inverseDeterminant =
                inverse00 * inverse11 - inverse01 * inverse10;
        if (!allFinite(
                inverse00,
                inverse01,
                inverse10,
                inverse11,
                inverseDeterminant,
                -(inverse00 * m02 + inverse01 * m12),
                -(inverse10 * m02 + inverse11 * m12))
                || inverseDeterminant == 0) {
            throw new IllegalArgumentException(
                    "Affine inverse coefficients must be finite");
        }
    }

    public Point2D apply(final Point2D point) {
        Objects.requireNonNull(point, "point");
        return new Point2D(
                m00 * point.x() + m01 * point.y() + m02,
                m10 * point.x() + m11 * point.y() + m12);
    }

    public double determinant() {
        return m00 * m11 - m01 * m10;
    }

    public AffineTransform2D inverse() {
        final double determinant = determinant();
        final double inverse00 = m11 / determinant;
        final double inverse01 = -m01 / determinant;
        final double inverse10 = -m10 / determinant;
        final double inverse11 = m00 / determinant;
        return new AffineTransform2D(
                destinationSpace,
                sourceSpace,
                inverse00,
                inverse01,
                -(inverse00 * m02 + inverse01 * m12),
                inverse10,
                inverse11,
                -(inverse10 * m02 + inverse11 * m12));
    }

    public AffineTransform2D andThen(final AffineTransform2D next) {
        Objects.requireNonNull(next, "next");
        if (destinationSpace != next.sourceSpace) {
            throw new IllegalArgumentException(
                    "Cannot compose transforms across mismatched spaces");
        }
        return new AffineTransform2D(
                sourceSpace,
                next.destinationSpace,
                next.m00 * m00 + next.m01 * m10,
                next.m00 * m01 + next.m01 * m11,
                next.m00 * m02 + next.m01 * m12 + next.m02,
                next.m10 * m00 + next.m11 * m10,
                next.m10 * m01 + next.m11 * m11,
                next.m10 * m02 + next.m11 * m12 + next.m12);
    }

    private static boolean allFinite(final double... values) {
        for (final double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }
}
