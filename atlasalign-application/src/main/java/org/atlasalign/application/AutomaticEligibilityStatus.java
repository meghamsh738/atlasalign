package org.atlasalign.application;

/**
 * Whether an automatic proposal is safe to initialize as the primary review
 * result. This is a workflow gate, not an estimate of scientific accuracy.
 */
public enum AutomaticEligibilityStatus {
    ELIGIBLE_FULL,
    REVIEW_ONLY,
    MANUAL_REQUIRED
}
