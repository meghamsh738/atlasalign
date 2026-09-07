package org.atlasalign.application.manual;

/** Continuous pixel-centre coordinate in the immutable source image. */
public record SourcePixelPoint(double x, double y) {
    public SourcePixelPoint {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("Source-pixel coordinates must be finite");
        }
    }
}
