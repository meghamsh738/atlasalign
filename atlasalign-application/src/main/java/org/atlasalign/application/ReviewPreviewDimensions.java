package org.atlasalign.application;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.core.Point2D;

/**
 * Continuous pixel-center domain of the newly allocated review preview.
 * Coordinates are valid from zero through {@code width - 1} and zero through
 * {@code height - 1}, inclusive. This is distinct from the read-only source
 * image's native dimensions when preview downsampling is used.
 */
public record ReviewPreviewDimensions(
        int width,
        int height,
        Optional<String> pixelsSha256) {

    public ReviewPreviewDimensions(final int width, final int height) {
        this(width, height, Optional.empty());
    }

    public ReviewPreviewDimensions {
        pixelsSha256 = Objects.requireNonNull(
                pixelsSha256, "pixelsSha256");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Review preview dimensions must be positive");
        }
        pixelsSha256.ifPresent(value -> {
            if (!value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "Review preview pixel identity must be a lowercase SHA-256 value");
            }
        });
    }

    public static ReviewPreviewDimensions capture(
            final int width,
            final int height,
            final float[] pixels) {
        Objects.requireNonNull(pixels, "pixels");
        if (pixels.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException(
                    "Review preview pixel count must match its dimensions");
        }
        return new ReviewPreviewDimensions(
                width,
                height,
                Optional.of(VirtualHalfPayloadHashes.pixelsSha256(pixels)));
    }

    public boolean contains(final Point2D point) {
        Objects.requireNonNull(point, "point");
        return Double.isFinite(point.x())
                && Double.isFinite(point.y())
                && point.x() >= 0
                && point.x() <= width - 1.0
                && point.y() >= 0
                && point.y() <= height - 1.0;
    }

    public void requireContains(final Point2D point, final String name) {
        if (!contains(point)) {
            throw new IllegalArgumentException(
                    Objects.requireNonNull(name, "name")
                    + " must lie inside the review preview pixel-center domain [0, "
                    + (width - 1) + "] × [0, " + (height - 1) + "]");
        }
    }
}
