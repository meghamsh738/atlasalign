package org.atlasalign.plugin.review;

import java.util.Arrays;
import java.util.Objects;

/**
 * Deterministic robust contrast for display-only preview copies.
 */
final class PreviewContrast {

    static final double LOWER_PERCENTILE = 0.005;
    static final double UPPER_PERCENTILE = 0.995;

    private PreviewContrast() {
    }

    static PreviewDisplayWindow window(final float[] pixels) {
        Objects.requireNonNull(pixels, "pixels");
        if (pixels.length == 0) {
            throw new IllegalArgumentException(
                    "Preview contrast requires at least one pixel");
        }
        final float[] sorted = pixels.clone();
        for (final float pixel : sorted) {
            if (!Float.isFinite(pixel)) {
                throw new IllegalArgumentException(
                        "Preview contrast requires finite pixels");
            }
        }
        Arrays.sort(sorted);
        final int last = sorted.length - 1;
        final int lowerIndex =
                (int) Math.floor(last * LOWER_PERCENTILE);
        final int upperIndex =
                (int) Math.ceil(last * UPPER_PERCENTILE);
        float lower = sorted[lowerIndex];
        float upper = sorted[upperIndex];
        PreviewDisplayStrategy strategy =
                PreviewDisplayStrategy.PERCENTILE_0_5_TO_99_5;
        if (!(upper > lower)) {
            lower = sorted[0];
            upper = sorted[last];
            strategy =
                    PreviewDisplayStrategy.FINITE_MIN_MAX_FALLBACK;
        }
        return new PreviewDisplayWindow(
                lower,
                upper,
                LOWER_PERCENTILE,
                UPPER_PERCENTILE,
                strategy);
    }
}
