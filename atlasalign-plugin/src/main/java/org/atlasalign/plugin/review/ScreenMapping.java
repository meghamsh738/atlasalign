package org.atlasalign.plugin.review;

import org.atlasalign.core.Point2D;

/**
 * Reversible letterbox mapping between preview pixel centers and component
 * screen coordinates.
 */
public record ScreenMapping(
        int previewWidth,
        int previewHeight,
        int screenWidth,
        int screenHeight,
        double scale,
        double offsetX,
        double offsetY) {

    public ScreenMapping {
        if (previewWidth <= 0 || previewHeight <= 0
                || screenWidth <= 0 || screenHeight <= 0
                || !Double.isFinite(scale) || scale <= 0
                || !Double.isFinite(offsetX)
                || !Double.isFinite(offsetY)) {
            throw new IllegalArgumentException(
                    "Screen mapping dimensions are invalid");
        }
    }

    public static ScreenMapping fit(
            final int previewWidth,
            final int previewHeight,
            final int screenWidth,
            final int screenHeight) {
        final double scale = Math.min(
                (double) screenWidth / previewWidth,
                (double) screenHeight / previewHeight);
        return new ScreenMapping(
                previewWidth,
                previewHeight,
                screenWidth,
                screenHeight,
                scale,
                (screenWidth - previewWidth * scale) * 0.5,
                (screenHeight - previewHeight * scale) * 0.5);
    }

    /**
     * Returns a mapping zoomed around a screen-space anchor. The preview
     * coordinate underneath the anchor is unchanged.
     */
    public ScreenMapping zoomedAbout(
            final double factor,
            final Point2D screenAnchor) {
        if (!Double.isFinite(factor) || factor <= 0) {
            throw new IllegalArgumentException(
                    "Zoom factor must be finite and positive");
        }
        final Point2D previewAnchor = screenToPreview(screenAnchor);
        final double nextScale = scale * factor;
        return new ScreenMapping(
                previewWidth,
                previewHeight,
                screenWidth,
                screenHeight,
                nextScale,
                screenAnchor.x() + 0.5
                        - (previewAnchor.x() + 0.5) * nextScale,
                screenAnchor.y() + 0.5
                        - (previewAnchor.y() + 0.5) * nextScale);
    }

    /** Returns a mapping panned by a screen-space delta. */
    public ScreenMapping translated(
            final double screenDeltaX,
            final double screenDeltaY) {
        if (!Double.isFinite(screenDeltaX)
                || !Double.isFinite(screenDeltaY)) {
            throw new IllegalArgumentException(
                    "Pan delta must be finite");
        }
        return new ScreenMapping(
                previewWidth,
                previewHeight,
                screenWidth,
                screenHeight,
                scale,
                offsetX + screenDeltaX,
                offsetY + screenDeltaY);
    }

    public Point2D previewToScreen(final Point2D preview) {
        return new Point2D(
                offsetX + (preview.x() + 0.5) * scale - 0.5,
                offsetY + (preview.y() + 0.5) * scale - 0.5);
    }

    public Point2D screenToPreview(final Point2D screen) {
        return new Point2D(
                (screen.x() + 0.5 - offsetX) / scale - 0.5,
                (screen.y() + 0.5 - offsetY) / scale - 0.5);
    }

    public double screenDeltaToPreview(final double delta) {
        if (!Double.isFinite(delta)) {
            throw new IllegalArgumentException(
                    "Screen delta must be finite");
        }
        return delta / scale;
    }
}
