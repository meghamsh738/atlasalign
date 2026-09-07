package org.atlasalign.atlas;

import java.util.List;
import java.util.Objects;

/**
 * Loads copied template and annotation planes from a verified Allen cache.
 */
public final class AtlasCoronalPlaneLoader {

    public static final int ANTERIOR_POSTERIOR_LEVELS = 528;
    public static final int CORONAL_WIDTH = 456;
    public static final int CORONAL_HEIGHT = 320;

    private final NrrdCoronalPlaneReader reader =
            new NrrdCoronalPlaneReader(
                    ANTERIOR_POSTERIOR_LEVELS,
                    CORONAL_HEIGHT,
                    CORONAL_WIDTH);

    public AtlasCoronalPlane load(
            final VerifiedAtlas atlas,
            final int zeroBasedAnteriorPosteriorIndex) {
        Objects.requireNonNull(atlas, "atlas");
        final VerifiedAtlas current =
                new AtlasRepository().open(
                        atlas.cacheDirectory(),
                        atlas.manifest());
        if (!current.manifest().equals(atlas.manifest())) {
            throw new AtlasCacheException(
                    "Atlas manifest changed after verification");
        }
        if (!current.manifest().atlasId().equals(
                "allen_mouse_25um")
                || !current.manifest().atlasVersion().equals(
                "Allen Mouse CCFv3 2017")
                || current.manifest().resolutionMicrometers() != 25
                || !current.manifest().coordinateSpace().equals(
                "left-posterior-superior")
                || !current.manifest().dimensions().equals(
                List.of(
                        ANTERIOR_POSTERIOR_LEVELS,
                        CORONAL_HEIGHT,
                        CORONAL_WIDTH))) {
            throw new AtlasCacheException(
                    "Verified atlas dimensions are not the pinned Allen volume");
        }
        final int[] template = reader.readUnsignedShortPlane(
                current.assetPath("template"),
                zeroBasedAnteriorPosteriorIndex);
        final int[] annotation = reader.readUnsignedIntPlane(
                current.assetPath("annotation"),
                zeroBasedAnteriorPosteriorIndex);
        return new AtlasCoronalPlane(
                zeroBasedAnteriorPosteriorIndex,
                CORONAL_WIDTH,
                CORONAL_HEIGHT,
                template,
                annotation);
    }
}
