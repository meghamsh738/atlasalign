package org.atlasalign.application;

/** Stable, auditable reason a complete validation derivative panel was not made. */
public enum ValidationHalfDerivativeFailureReason {
    INVALID_SOURCE_GEOMETRY,
    MASK_DIMENSION_MISMATCH,
    EMPTY_FULL_SOURCE_MASK,
    EMPTY_OBSERVED_HALF,
    NO_FINITE_BACKGROUND,
    PIXEL_CAP_EXCEEDED,
    INVALID_DERIVATIVE
}
