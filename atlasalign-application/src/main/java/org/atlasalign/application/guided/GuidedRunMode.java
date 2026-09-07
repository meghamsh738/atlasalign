package org.atlasalign.application.guided;

/**
 * Provenance category for validation-only bounded anatomical searches.
 *
 * <p>These values are deliberately separate from the production automatic
 * confidence categories. In particular, a prespecified guided run is never a
 * fully automatic result.</p>
 */
public enum GuidedRunMode {
    UNGUIDED_AUTOMATIC,
    PRESPECIFIED_GUIDED_AUTOMATIC,
    REVIEW_INFORMED_RERUN,
    MANUAL_ANATOMICAL_RANKING
}
