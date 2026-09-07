package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class TissueSupportDetectorTest {

    @Test
    void keepsCoherentPrimaryMaskExactlyForOrdinarySections() {
        final int width = 80;
        final int height = 60;
        final boolean[] values = new boolean[width * height];
        final float[] pixels = new float[values.length];
        for (int y = 8; y < 52; y++) {
            for (int x = 10; x < 70; x++) {
                values[y * width + x] = true;
                pixels[y * width + x] = 90 + (x + y) % 20;
            }
        }
        final BinaryMask primary = BinaryMask.fromBooleans(
                width, height, values);

        final TissueSupportDetector.Suggestion result =
                new TissueSupportDetector().suggest(
                        width, height, pixels, primary);

        assertSame(primary, result.mask());
        assertEquals(TissueSupportDetectionMethod.SEGMENTATION_MASK,
                result.method());
    }

    @Test
    void fluorescenceEnvelopeRecoversOneCoherentEditableSupport() {
        final int width = 192;
        final int height = 128;
        final float[] pixels = new float[width * height];
        final boolean[] fragmented = new boolean[pixels.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final double dx = (x - 92.0) / 75.0;
                final double dy = (y - 64.0) / 48.0;
                if (dx * dx + dy * dy <= 1.0) {
                    final int index = y * width + x;
                    pixels[index] = 5 + ((x * 17 + y * 29) % 9);
                    if ((x * 11 + y * 7) % 23 == 0) {
                        pixels[index] = 180;
                        fragmented[index] = true;
                    }
                }
            }
        }
        final BinaryMask primary = BinaryMask.fromBooleans(
                width, height, fragmented);

        final TissueSupportDetector.Suggestion result =
                new TissueSupportDetector().suggest(
                        width, height, pixels, primary);

        assertEquals(
                TissueSupportDetectionMethod.FLUORESCENCE_LOCAL_CONTRAST,
                result.method());
        assertEquals(1, result.retainedComponentCount());
        assertTrue(result.largestComponentFraction() > 0.99);
        assertTrue(result.foregroundFraction() > 0.30);
        assertTrue(result.foregroundFraction() < 0.75);
        assertTrue(result.mask().contains(92, 64));
        assertTrue(!result.mask().contains(0, 0));
    }

    @Test
    void fluorescenceEnvelopeFillsEnclosedDarkCavitiesButKeepsExteriorCut() {
        final int width = 220;
        final int height = 160;
        final float[] pixels = new float[width * height];
        final boolean[] fragmented = new boolean[pixels.length];
        for (int y = 15; y < 145; y++) {
            for (int x = 20; x < 200; x++) {
                final double dx = (x - 110.0) / 90.0;
                final double dy = (y - 80.0) / 65.0;
                if (dx * dx + dy * dy <= 1.0) {
                    final int index = y * width + x;
                    pixels[index] = 8 + ((x * 13 + y * 19) % 7);
                    if ((x * 7 + y * 11) % 29 == 0) {
                        pixels[index] = 200;
                        fragmented[index] = true;
                    }
                }
            }
        }
        // A dark enclosed fluorescence cavity must not become a crop hole.
        for (int y = 65; y <= 95; y++) {
            for (int x = 92; x <= 128; x++) {
                pixels[y * width + x] = 0;
            }
        }
        // A dark channel reaching the preview exterior remains exterior.
        for (int y = 0; y <= 72; y++) {
            for (int x = 155; x <= 161; x++) {
                pixels[y * width + x] = 0;
            }
        }
        final BinaryMask primary = BinaryMask.fromBooleans(
                width, height, fragmented);

        final TissueSupportDetector.Suggestion result =
                new TissueSupportDetector().suggest(
                        width, height, pixels, primary);

        assertEquals(
                TissueSupportDetectionMethod.FLUORESCENCE_LOCAL_CONTRAST,
                result.method());
        assertTrue(result.mask().contains(110, 80));
        assertTrue(!result.mask().contains(158, 35));
    }

    @Test
    void coherentButHoleyPrimaryUsesExteriorFluorescenceEnvelope() {
        final int width = 180;
        final int height = 140;
        final float[] pixels = new float[width * height];
        final boolean[] holeyPrimary = new boolean[pixels.length];
        for (int y = 15; y < 125; y++) {
            for (int x = 20; x < 160; x++) {
                final int index = y * width + x;
                pixels[index] = 9 + ((x * 5 + y * 3) % 5);
                holeyPrimary[index] = true;
            }
        }
        for (int y = 45; y < 95; y++) {
            for (int x = 65; x < 115; x++) {
                holeyPrimary[y * width + x] = false;
            }
        }
        final BinaryMask primary = BinaryMask.fromBooleans(
                width, height, holeyPrimary);

        final TissueSupportDetector.Suggestion result =
                new TissueSupportDetector().suggest(
                        width, height, pixels, primary);

        assertEquals(
                TissueSupportDetectionMethod.FLUORESCENCE_LOCAL_CONTRAST,
                result.method());
        assertTrue(result.mask().contains(90, 70));
    }
}
