package org.atlasalign.core;

/**
 * Inclusive bounds of foreground pixels.
 */
public record MaskBounds(
        int minimumX,
        int minimumY,
        int maximumX,
        int maximumY) {

    public MaskBounds {
        if (minimumX < 0 || minimumY < 0
                || maximumX < minimumX || maximumY < minimumY) {
            throw new IllegalArgumentException("Invalid mask bounds");
        }
    }

    public int width() {
        return maximumX - minimumX + 1;
    }

    public int height() {
        return maximumY - minimumY + 1;
    }
}
