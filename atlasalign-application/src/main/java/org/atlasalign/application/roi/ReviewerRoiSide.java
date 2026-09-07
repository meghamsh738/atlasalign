package org.atlasalign.application.roi;

/** Reviewer-supplied anatomical side metadata for one exact manual ROI. */
public enum ReviewerRoiSide {
    LEFT("L"),
    RIGHT("R"),
    BILATERAL("Bilateral"),
    UNKNOWN("Unknown");

    private final String displayName;

    ReviewerRoiSide(final String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
