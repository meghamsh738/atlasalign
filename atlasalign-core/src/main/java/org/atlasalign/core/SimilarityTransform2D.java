package org.atlasalign.core;

import java.util.Objects;

/**
 * Uniform-scale rotation and translation with an explicit direction.
 */
public record SimilarityTransform2D(
        CoordinateSpace2D sourceSpace,
        CoordinateSpace2D destinationSpace,
        double scale,
        double rotationRadians,
        double translationX,
        double translationY) {

    private static final double MINIMUM_SAFE_SCALE = 1e-150;
    private static final double MAXIMUM_SAFE_SCALE = 1e150;

    public SimilarityTransform2D {
        sourceSpace = Objects.requireNonNull(sourceSpace, "sourceSpace");
        destinationSpace = Objects.requireNonNull(
                destinationSpace, "destinationSpace");
        if (!Double.isFinite(scale)
                || scale < MINIMUM_SAFE_SCALE
                || scale > MAXIMUM_SAFE_SCALE
                || !Double.isFinite(rotationRadians)
                || !Double.isFinite(translationX)
                || !Double.isFinite(translationY)) {
            throw new IllegalArgumentException(
                    "Similarity parameters must be finite with positive scale");
        }
        final double cosine = Math.cos(rotationRadians);
        final double sine = Math.sin(rotationRadians);
        final double inverseTranslationX =
                -(cosine * translationX + sine * translationY) / scale;
        final double inverseTranslationY =
                -(-sine * translationX + cosine * translationY) / scale;
        if (!Double.isFinite(inverseTranslationX)
                || !Double.isFinite(inverseTranslationY)) {
            throw new IllegalArgumentException(
                    "Similarity inverse translation must be finite");
        }
    }

    public AffineTransform2D asAffine() {
        final double cosine = Math.cos(rotationRadians);
        final double sine = Math.sin(rotationRadians);
        return new AffineTransform2D(
                sourceSpace,
                destinationSpace,
                scale * cosine,
                -scale * sine,
                translationX,
                scale * sine,
                scale * cosine,
                translationY);
    }

    public Point2D apply(final Point2D point) {
        return asAffine().apply(point);
    }

    public AffineTransform2D inverse() {
        return asAffine().inverse();
    }
}
