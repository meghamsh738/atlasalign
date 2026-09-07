package org.atlasalign.application;

/**
 * Evidence sufficiency, not a probability of scientific correctness.
 */
public enum ConfidenceEvidenceCategory {
    AUTOMATIC_CONSISTENT,
    AUTOMATIC_LIMITED,
    AUTOMATIC_CONFLICTING,
    MANUAL_ONLY,
    NOT_ASSESSABLE
}
