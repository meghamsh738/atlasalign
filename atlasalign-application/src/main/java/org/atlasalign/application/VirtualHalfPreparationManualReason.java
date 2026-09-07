package org.atlasalign.application;

/**
 * Stable, auditable reason that virtual half-section preparation was stopped.
 */
public enum VirtualHalfPreparationManualReason {
    PARTIAL_OR_DAMAGED,
    INVALID_GEOMETRY,
    REFLECTION_OUT_OF_BOUNDS,
    NO_FINITE_BACKGROUND,
    PIXEL_CAP_EXCEEDED,
    INVALID_INFERENCE_INPUT
}
