package org.atlasalign.plugin.batch;

/** Reviewer-visible state of one queued section or standalone image. */
public enum BatchReviewStatus {
    PENDING("Pending"),
    OPENING("Opening…"),
    REVIEW_OPEN("Review open"),
    COMPLETE("Complete"),
    ERROR("Needs attention");

    private final String displayName;

    BatchReviewStatus(final String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
