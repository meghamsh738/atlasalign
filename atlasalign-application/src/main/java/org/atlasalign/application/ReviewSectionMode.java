package org.atlasalign.application;

/**
 * Reviewer-selected presentation and side-placement contract.
 *
 * <p>This is manual review geometry. It is deliberately separate from the
 * automatically observed {@link SectionGeometry} and cannot contribute
 * automatic confidence evidence.</p>
 */
public enum ReviewSectionMode {
    /** Joined bilateral atlas with independently editable local side fields. */
    FULL,
    /** Both atlas sides remain available and are clipped to observed tissue. */
    HALF,
    /** Atlas halves have independent placement and local deformation. */
    DISJOINED;

    public boolean clipsToTissueByDefault() {
        return this != FULL;
    }

    public boolean hasJoinedSeam() {
        return this != DISJOINED;
    }

    @Override
    public String toString() {
        return switch (this) {
            case FULL -> "Full";
            case HALF -> "Half";
            case DISJOINED -> "Disjoined";
        };
    }
}
