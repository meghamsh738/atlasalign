package org.atlasalign.plugin.review;

/**
 * Auditable strategy used only to map copied preview intensities to display.
 */
public enum PreviewDisplayStrategy {
    PERCENTILE_0_5_TO_99_5,
    FINITE_MIN_MAX_FALLBACK
}
