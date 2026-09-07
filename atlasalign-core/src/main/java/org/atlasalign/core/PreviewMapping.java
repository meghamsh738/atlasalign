package org.atlasalign.core;

/**
 * Reversible mapping between a full-resolution source plane and a bounded
 * registration preview.
 *
 * <p>The mapping is defined at pixel centers. It does not assume that rounded
 * preview dimensions preserve an ideal scalar downsampling factor; the
 * effective X and Y factors are stored independently.</p>
 */
public record PreviewMapping(
        int sourceWidth,
        int sourceHeight,
        int previewWidth,
        int previewHeight) {

    public PreviewMapping {
        requirePositive(sourceWidth, "sourceWidth");
        requirePositive(sourceHeight, "sourceHeight");
        requirePositive(previewWidth, "previewWidth");
        requirePositive(previewHeight, "previewHeight");
        if (previewWidth > sourceWidth || previewHeight > sourceHeight) {
            throw new IllegalArgumentException("A registration preview must not upsample the source");
        }
    }

    public static PreviewMapping bounded(
            final int sourceWidth,
            final int sourceHeight,
            final int maximumDimension) {
        requirePositive(sourceWidth, "sourceWidth");
        requirePositive(sourceHeight, "sourceHeight");
        requirePositive(maximumDimension, "maximumDimension");

        final int largest = Math.max(sourceWidth, sourceHeight);
        if (largest <= maximumDimension) {
            return new PreviewMapping(sourceWidth, sourceHeight, sourceWidth, sourceHeight);
        }

        final double requestedScale = (double) maximumDimension / largest;
        final int width = Math.max(1, (int) Math.round(sourceWidth * requestedScale));
        final int height = Math.max(1, (int) Math.round(sourceHeight * requestedScale));
        return new PreviewMapping(sourceWidth, sourceHeight, width, height);
    }

    public double scaleX() {
        return (double) previewWidth / sourceWidth;
    }

    public double scaleY() {
        return (double) previewHeight / sourceHeight;
    }

    public Point2D sourceToPreview(final Point2D source) {
        return new Point2D(
                (source.x() + 0.5) * scaleX() - 0.5,
                (source.y() + 0.5) * scaleY() - 0.5);
    }

    public Point2D previewToSource(final Point2D preview) {
        return new Point2D(
                (preview.x() + 0.5) / scaleX() - 0.5,
                (preview.y() + 0.5) / scaleY() - 0.5);
    }

    private static void requirePositive(final int value, final String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
