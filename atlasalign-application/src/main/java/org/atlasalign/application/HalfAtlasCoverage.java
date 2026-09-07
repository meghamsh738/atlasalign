package org.atlasalign.application;

/**
 * Reviewer-controlled atlas-side footprint for a Half-section review.
 *
 * <p>The default is deliberately limited to the explicitly confirmed
 * anatomical side. A contralateral remnant is accepted only after the
 * reviewer opts in to geometry that was independently shown to overlap the
 * reviewed tissue support.</p>
 */
public enum HalfAtlasCoverage {
    VISIBLE_SIDE_ONLY,
    INCLUDE_OPPOSITE_REMNANT;

    public boolean includesOppositeRemnant() {
        return this == INCLUDE_OPPOSITE_REMNANT;
    }
}
