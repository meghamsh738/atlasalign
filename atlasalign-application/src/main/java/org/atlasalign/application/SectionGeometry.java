package org.atlasalign.application;

/**
 * Image-side geometry only. Half labels do not assert anatomical laterality.
 */
public enum SectionGeometry {
    FULL,
    BILATERAL_REVIEW_REQUIRED,
    IMAGE_LEFT_HALF,
    IMAGE_RIGHT_HALF,
    PARTIAL_OR_DAMAGED
}
