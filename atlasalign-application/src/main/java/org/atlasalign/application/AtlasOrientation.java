package org.atlasalign.application;

/**
 * Explicit atlas-to-image left/right orientation.
 *
 * <p>Image-side geometry never establishes anatomical laterality by itself.
 * The unconfirmed value renders provisionally without reflection but blocks
 * acceptance for half or damaged tissue.</p>
 */
public enum AtlasOrientation {
    UNCONFIRMED_PROVISIONAL_DIRECT(false, false),
    CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT(false, true),
    CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT(true, true);

    private final boolean reflected;
    private final boolean confirmed;

    AtlasOrientation(
            final boolean reflected,
            final boolean confirmed) {
        this.reflected = reflected;
        this.confirmed = confirmed;
    }

    public boolean reflected() {
        return reflected;
    }

    public boolean confirmed() {
        return confirmed;
    }
}
