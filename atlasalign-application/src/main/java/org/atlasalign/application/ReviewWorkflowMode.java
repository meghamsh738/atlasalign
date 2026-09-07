package org.atlasalign.application;

/** Separates an immutable automatic proposal from reviewer-controlled edits. */
public enum ReviewWorkflowMode {
    AUTOMATIC_REVIEW,
    MANUAL_REFINEMENT,
    MANUAL_ONLY;

    public boolean permitsManualEdits() {
        return this != AUTOMATIC_REVIEW;
    }
}
