package org.atlasalign.plugin.review;

/**
 * Transient reviewer workflow stage. Stage navigation is display state only
 * and never creates a scientific review revision.
 */
public enum ReviewWorkflowStage {
    SETUP_AND_PLANE(0, "0 Setup & Plane"),
    MATCH(1, "Match (Advanced)"),
    BORDER(2, "1 Border"),
    INTERIOR(3, "2 Interior"),
    STRUCTURE(4, "3 Draw ROIs"),
    ACCEPT_EXPORT(5, "4 Review & Export");

    private final int index;
    private final String label;

    ReviewWorkflowStage(final int index, final String label) {
        this.index = index;
        this.label = label;
    }

    public int index() {
        return index;
    }

    public String label() {
        return label;
    }

    public String cardKey() {
        return Integer.toString(index);
    }

    public ReviewWorkflowStage previous() {
        return switch (this) {
            case SETUP_AND_PLANE -> SETUP_AND_PLANE;
            case MATCH, BORDER -> SETUP_AND_PLANE;
            case INTERIOR -> BORDER;
            case STRUCTURE -> INTERIOR;
            case ACCEPT_EXPORT -> STRUCTURE;
        };
    }

    public ReviewWorkflowStage next() {
        return switch (this) {
            case SETUP_AND_PLANE, MATCH -> BORDER;
            case BORDER -> INTERIOR;
            case INTERIOR -> STRUCTURE;
            case STRUCTURE -> ACCEPT_EXPORT;
            case ACCEPT_EXPORT -> ACCEPT_EXPORT;
        };
    }

    public static ReviewWorkflowStage at(final int index) {
        if (index < 0 || index >= values().length) {
            throw new IllegalArgumentException("Unknown workflow stage");
        }
        return values()[index];
    }
}
