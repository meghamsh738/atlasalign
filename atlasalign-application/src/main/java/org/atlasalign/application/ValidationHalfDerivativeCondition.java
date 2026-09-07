package org.atlasalign.application;

/**
 * Frozen ordering and image-side labels for controlled validation half inputs.
 * These labels do not assert anatomical laterality.
 */
public enum ValidationHalfDerivativeCondition {
    IMAGE_LEFT_FULL_CANVAS("image-left full-canvas", false,
            SectionGeometry.IMAGE_LEFT_HALF),
    IMAGE_RIGHT_FULL_CANVAS("image-right full-canvas", false,
            SectionGeometry.IMAGE_RIGHT_HALF),
    IMAGE_LEFT_TIGHT_CROP("image-left tight-crop", true,
            SectionGeometry.IMAGE_LEFT_HALF),
    IMAGE_RIGHT_TIGHT_CROP("image-right tight-crop", true,
            SectionGeometry.IMAGE_RIGHT_HALF);

    private final String canonicalLabel;
    private final boolean tightCrop;
    private final SectionGeometry observedGeometry;

    ValidationHalfDerivativeCondition(
            final String canonicalLabel,
            final boolean tightCrop,
            final SectionGeometry observedGeometry) {
        this.canonicalLabel = canonicalLabel;
        this.tightCrop = tightCrop;
        this.observedGeometry = observedGeometry;
    }

    /** Canonical ASCII label used in derivative provenance. */
    public String canonicalLabel() {
        return canonicalLabel;
    }

    /** Alias for the canonical human-readable condition label. */
    public String label() {
        return canonicalLabel;
    }

    /** Whether this condition crops its corresponding full-canvas half. */
    public boolean isTightCrop() {
        return tightCrop;
    }

    /** Image-side geometry of the observed derivative pixels. */
    public SectionGeometry observedGeometry() {
        return observedGeometry;
    }

    /** True when retained columns are on the displayed image-left side. */
    public boolean retainsImageLeft() {
        return observedGeometry == SectionGeometry.IMAGE_LEFT_HALF;
    }

    @Override
    public String toString() {
        return canonicalLabel;
    }
}
