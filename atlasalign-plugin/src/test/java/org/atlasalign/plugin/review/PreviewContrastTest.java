package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class PreviewContrastTest {

    @Test
    void ignoresAnIsolatedHotPixelWithoutChangingPreviewValues() {
        final float[] pixels = new float[1_000];
        for (int index = 499; index < pixels.length - 1; index++) {
            pixels[index] = 100;
        }
        pixels[pixels.length - 1] = 65_535;
        final float[] original = pixels.clone();

        final ReviewPreview preview =
                new ReviewPreview(100, 10, pixels);
        final BufferedImage image =
                ReviewCanvas.previewImage(preview);

        assertEquals(0, preview.displayWindow().lower());
        assertEquals(100, preview.displayWindow().upper());
        assertEquals(
                PreviewDisplayStrategy.PERCENTILE_0_5_TO_99_5,
                preview.displayWindow().strategy());
        assertEquals(0, image.getRaster().getSample(0, 0, 0));
        assertEquals(255, image.getRaster().getSample(99, 9, 0));
        assertEquals(255, image.getRaster().getSample(0, 5, 0));
        assertArrayEquals(original, pixels);
        assertArrayEquals(original, preview.pixels());
    }

    @Test
    void recordsFiniteRangeFallbackForDegeneratePercentiles() {
        final float[] pixels = new float[1_000];
        pixels[pixels.length - 1] = 201;
        final ReviewPreview preview =
                new ReviewPreview(100, 10, pixels);

        assertEquals(0, preview.displayWindow().lower());
        assertEquals(201, preview.displayWindow().upper());
        assertEquals(
                PreviewDisplayStrategy.FINITE_MIN_MAX_FALLBACK,
                preview.displayWindow().strategy());
        final BufferedImage image =
                ReviewCanvas.previewImage(preview);
        assertEquals(0, image.getRaster().getSample(0, 0, 0));
        assertEquals(255, image.getRaster().getSample(99, 9, 0));
    }
}
