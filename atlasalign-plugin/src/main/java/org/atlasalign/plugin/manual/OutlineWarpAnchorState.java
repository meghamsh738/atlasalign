package org.atlasalign.plugin.manual;

/** Reviewer-confirmation state of candidate outline-warp correspondences. */
enum OutlineWarpAnchorState {
    /** Deterministic search-preview proposal; not yet reviewer-confirmed. */
    PROVISIONAL_UNCONFIRMED,

    /** Reserved for a later explicit reviewer confirmation edit. */
    CONFIRMED,

    /** Partial/half-section affine preview with no full-outline warp. */
    NOT_APPLICABLE_PARTIAL_AFFINE
}
