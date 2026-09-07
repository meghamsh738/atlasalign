package org.atlasalign.application;

/**
 * Anatomical side in the verified atlas coordinate system.
 *
 * <p>This identity is independent of the current display reflection. A handle
 * seeded on {@link #ATLAS_LEFT} remains atlas-left even when the reviewer
 * explicitly reflects the atlas onto image-right.</p>
 */
public enum AtlasAnatomicalSide {
    ATLAS_LEFT,
    ATLAS_RIGHT,
    MIDLINE
}
