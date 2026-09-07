package org.atlasalign.application.manual;

/** Typed, reviewer-facing failures for assisted coarse placement. */
public enum BoundaryFitFailureKind {
    INSUFFICIENT_BOUNDARY,
    INSUFFICIENT_COVERAGE,
    AMBIGUOUS_MATCH,
    UNSAFE_TRANSFORM,
    STALE_RESULT
}
