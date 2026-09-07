package org.atlasalign.application.manual;

/** Ordered, reviewer-controlled stages of the guided manual workflow. */
public enum ManualAlignmentStage {
    PREPARE_SECTION,
    CHOOSE_ANATOMICAL_GUIDE,
    DRAW_TISSUE_OUTLINE,
    DRAW_STRUCTURE_ON_TISSUE,
    PREVIEW_CANDIDATES,
    REVIEW_CANDIDATES,
    REFINE_SELECTED_CANDIDATE,
    REVIEW_AND_ACCEPT
}
