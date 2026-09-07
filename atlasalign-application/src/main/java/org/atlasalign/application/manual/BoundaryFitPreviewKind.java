package org.atlasalign.application.manual;

/** Geometry model used by one transient guided-border ghost preview. */
public enum BoundaryFitPreviewKind {
    /** One completed pair defines translation only. */
    TRANSLATION,
    /** Two or more pairs define translation, rotation, and uniform scale. */
    SIMILARITY,
    /** Three or more pairs define rotation and independent positive X/Y scale. */
    ORTHOGONAL_XY
}
