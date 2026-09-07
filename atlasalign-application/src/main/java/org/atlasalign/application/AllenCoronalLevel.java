package org.atlasalign.application;

/**
 * A manually selected coronal plane in the pinned 25 micrometer Allen CCF.
 *
 * <p>The Allen reference volume axes are explicitly interpreted as
 * anterior-to-posterior, superior-to-inferior, and left-to-right. Coronal
 * planes therefore use axis zero. The coordinate reported here is measured
 * from the anterior volume origin; it is not a stereotaxic bregma value.</p>
 */
public record AllenCoronalLevel(int zeroBasedAnteriorPosteriorIndex) {

    public static final String ATLAS_ID = "allen_mouse_25um";
    public static final String ATLAS_VERSION = "Allen Mouse CCFv3 2017";
    public static final int PLANE_COUNT = 528;
    public static final int MICROMETERS_PER_VOXEL = 25;

    public AllenCoronalLevel {
        if (zeroBasedAnteriorPosteriorIndex < 0
                || zeroBasedAnteriorPosteriorIndex >= PLANE_COUNT) {
            throw new IllegalArgumentException(
                    "Coronal level must be in the inclusive range 0..527");
        }
    }

    public int anteriorOriginMicrometers() {
        return zeroBasedAnteriorPosteriorIndex * MICROMETERS_PER_VOXEL;
    }
}
