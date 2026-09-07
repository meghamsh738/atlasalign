package org.atlasalign.plugin.review;

/**
 * Immutable display-only intensity window for a copied review preview.
 *
 * <p>The values never change source or registration pixels. They are retained
 * only so the rendered grayscale mapping is explicit and auditable.</p>
 */
public record PreviewDisplayWindow(
        float lower,
        float upper,
        double lowerPercentile,
        double upperPercentile,
        PreviewDisplayStrategy strategy) {

    public PreviewDisplayWindow {
        strategy = java.util.Objects.requireNonNull(
                strategy, "strategy");
        if (!Float.isFinite(lower)
                || !Float.isFinite(upper)
                || upper < lower) {
            throw new IllegalArgumentException(
                    "Preview display limits must be finite and ordered");
        }
        if (!Double.isFinite(lowerPercentile)
                || !Double.isFinite(upperPercentile)
                || lowerPercentile < 0
                || upperPercentile > 1
                || lowerPercentile > upperPercentile) {
            throw new IllegalArgumentException(
                    "Preview display percentiles must be ordered in [0, 1]");
        }
    }

    public double range() {
        return upper > lower ? (double) upper - lower : 1.0;
    }
}
