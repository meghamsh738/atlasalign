package org.atlasalign.core;

/**
 * A coordinate in an explicitly named space. Pixel coordinates use pixel
 * centers: the center of the first pixel is (0, 0).
 */
public record Point2D(double x, double y) {

    public Point2D {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Coordinates must be finite");
        }
    }
}
