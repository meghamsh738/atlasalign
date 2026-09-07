package org.atlasalign.application;

/**
 * Auditable method used only to propose the editable cyan tissue footprint.
 *
 * <p>This is deliberately separate from the primary segmentation method:
 * changing the display/crop suggestion must not silently rewrite automatic
 * alignment evidence or confidence.</p>
 */
public enum TissueSupportDetectionMethod {
    SEGMENTATION_MASK,
    FLUORESCENCE_LOCAL_CONTRAST
}
