package org.atlasalign.application;

/**
 * Deterministic threshold family evaluated for a copied preview.
 */
public enum TissueSegmentationMethod {
    ORDINARY_OTSU,
    PERCENTILE_CLIPPED_OTSU,
    ORDINARY_MULTI_OTSU,
    PERCENTILE_CLIPPED_MULTI_OTSU
}
