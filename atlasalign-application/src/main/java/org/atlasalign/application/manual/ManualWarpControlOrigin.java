package org.atlasalign.application.manual;

/** Provenance of a reviewer-created local-warp control. */
public enum ManualWarpControlOrigin {
    /** Evenly spaced points proposed from copied-preview tissue contrast. */
    TISSUE_BOUNDARY,
    /** Points sampled from the reviewer-selected atlas structure guide. */
    STRUCTURE_GUIDE,
    /** Retained only for replaying older review histories. */
    VERIFIED_STRUCTURE_BOUNDARY,
    /** Reviewer-paired exterior atlas and tissue boundary endpoints. */
    ATLAS_TISSUE_BOUNDARY_PAIR,
    REGULAR_INTERIOR_GRID,
    USER_PLACED_INTERIOR
}
