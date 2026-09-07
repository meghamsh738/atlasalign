package org.atlasalign.plugin.review;

import java.util.Arrays;
import org.atlasalign.application.RegistrationPreview;

/**
 * A defensive, display-only copy of a registration preview.
 */
public final class ReviewPreview {

    private final int width;
    private final int height;
    private final float[] pixels;
    private final PreviewDisplayWindow displayWindow;

    public ReviewPreview(
            final int width,
            final int height,
            final float[] pixels) {
        if (width <= 0 || height <= 0
                || pixels == null
                || pixels.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException(
                    "Review preview dimensions do not match its pixels");
        }
        for (final float pixel : pixels) {
            if (!Float.isFinite(pixel)) {
                throw new IllegalArgumentException(
                        "Review preview contains a non-finite intensity");
            }
        }
        this.width = width;
        this.height = height;
        this.pixels = pixels.clone();
        displayWindow = PreviewContrast.window(this.pixels);
    }

    public static ReviewPreview copyOf(
            final RegistrationPreview preview) {
        return new ReviewPreview(
                preview.mapping().previewWidth(),
                preview.mapping().previewHeight(),
                preview.pixels());
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public float[] pixels() {
        return Arrays.copyOf(pixels, pixels.length);
    }

    public PreviewDisplayWindow displayWindow() {
        return displayWindow;
    }
}
