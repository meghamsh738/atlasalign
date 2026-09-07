package org.atlasalign.atlas;

import java.util.Arrays;

/**
 * One copied coronal plane from the pinned atlas volumes.
 */
public final class AtlasCoronalPlane {

    private final int zeroBasedAnteriorPosteriorIndex;
    private final int width;
    private final int height;
    private final int[] templateIntensity;
    private final int[] annotationId;
    private final AtlasPlaneGeometry geometry;
    private final boolean templateAvailable;

    public AtlasCoronalPlane(
            final int zeroBasedAnteriorPosteriorIndex,
            final int width,
            final int height,
            final int[] templateIntensity,
            final int[] annotationId) {
        this(
                zeroBasedAnteriorPosteriorIndex,
                width,
                height,
                templateIntensity,
                annotationId,
                AtlasPlaneGeometry.axisAligned(
                        zeroBasedAnteriorPosteriorIndex,
                        width,
                        height,
                        height,
                        width));
    }

    /** Creates an annotation-only plane for fast contour-first review. */
    public static AtlasCoronalPlane annotationOnly(
            final int zeroBasedAnteriorPosteriorIndex,
            final int width,
            final int height,
            final int[] annotationId,
            final AtlasPlaneGeometry geometry) {
        return new AtlasCoronalPlane(
                zeroBasedAnteriorPosteriorIndex,
                width,
                height,
                null,
                annotationId,
                geometry);
    }

    public AtlasCoronalPlane(
            final int zeroBasedAnteriorPosteriorIndex,
            final int width,
            final int height,
            final int[] templateIntensity,
            final int[] annotationId,
            final AtlasPlaneGeometry geometry) {
        if (zeroBasedAnteriorPosteriorIndex < 0
                || width <= 0
                || height <= 0) {
            throw new IllegalArgumentException(
                    "Atlas plane index and dimensions are invalid");
        }
        final int pixels = Math.multiplyExact(width, height);
        if (annotationId == null
                || annotationId.length != pixels) {
            throw new IllegalArgumentException(
                    "Atlas plane buffers must match its dimensions");
        }
        if (templateIntensity != null
                && templateIntensity.length != pixels) {
            throw new IllegalArgumentException(
                    "Atlas template buffer must match plane dimensions");
        }
        if (templateIntensity != null
                && Arrays.stream(templateIntensity).anyMatch(value -> value < 0)
                || Arrays.stream(annotationId).anyMatch(value -> value < 0)) {
            throw new IllegalArgumentException(
                    "Atlas plane values must be unsigned");
        }
        this.zeroBasedAnteriorPosteriorIndex =
                zeroBasedAnteriorPosteriorIndex;
        this.width = width;
        this.height = height;
        this.templateIntensity = templateIntensity == null
                ? null : templateIntensity.clone();
        this.templateAvailable = templateIntensity != null;
        this.annotationId = annotationId.clone();
        this.geometry = java.util.Objects.requireNonNull(
                geometry, "geometry");
        if (geometry.width() != width
                || geometry.height() != height
                || geometry.zeroBasedAnteriorPosteriorIndex()
                != zeroBasedAnteriorPosteriorIndex) {
            throw new IllegalArgumentException(
                    "Atlas plane geometry does not match its dimensions");
        }
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

    public int[] templateIntensity() {
        requireTemplate();
        return templateIntensity.clone();
    }

    public boolean hasTemplateIntensity() {
        return templateAvailable;
    }

    public int[] annotationId() {
        return annotationId.clone();
    }

    public int templateIntensity(final int x, final int y) {
        requireTemplate();
        return templateIntensity[index(x, y)];
    }

    public int annotationId(final int x, final int y) {
        return annotationId[index(x, y)];
    }

    public AtlasPlaneGeometry geometry() {
        return geometry;
    }

    private void requireTemplate() {
        if (!templateAvailable) {
            throw new IllegalStateException(
                    "Atlas plane was loaded in annotation-only mode");
        }
    }

    private int index(final int x, final int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException(
                    "Atlas plane coordinate is outside the image");
        }
        return y * width + x;
    }
}
