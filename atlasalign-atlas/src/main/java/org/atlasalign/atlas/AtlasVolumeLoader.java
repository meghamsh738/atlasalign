package org.atlasalign.atlas;

import java.util.List;
import java.util.Objects;

/** Loads verified Allen NRRD assets into temporary immutable memory. */
public final class AtlasVolumeLoader {

    public AtlasVolume load(final VerifiedAtlas atlas) {
        final AtlasVolume annotations = loadAnnotations(atlas);
        return loadTemplate(atlas, annotations);
    }

    /** Loads only annotations for the default contour-first review path. */
    public AtlasVolume loadAnnotations(final VerifiedAtlas atlas) {
        Objects.requireNonNull(atlas, "atlas");
        final VerifiedAtlas verified = new AtlasRepository().open(
                atlas.cacheDirectory(), atlas.manifest());
        if (!verified.manifest().dimensions().equals(
                List.of(528, 320, 456))) {
            throw new AtlasCacheException(
                    "Verified atlas dimensions are not the pinned Allen volume");
        }
        final NrrdCoronalPlaneReader reader =
                new NrrdCoronalPlaneReader(528, 320, 456);
        final int[] annotation = reader.readUnsignedIntVolume(
                verified.assetPath("annotation"));
        return new AtlasVolume(528, 320, 456, null, annotation);
    }

    /** Adds the optional template without re-reading the annotation volume. */
    public AtlasVolume loadTemplate(
            final VerifiedAtlas atlas,
            final AtlasVolume annotations) {
        Objects.requireNonNull(atlas, "atlas");
        Objects.requireNonNull(annotations, "annotations");
        if (annotations.hasTemplate()) {
            return annotations;
        }
        final VerifiedAtlas verified = new AtlasRepository().open(
                atlas.cacheDirectory(), atlas.manifest());
        if (annotations.axis0() != 528
                || annotations.axis1() != 320
                || annotations.axis2() != 456) {
            throw new AtlasCacheException(
                    "Annotation volume dimensions are not the pinned Allen volume");
        }
        final short[] template = new NrrdCoronalPlaneReader(528, 320, 456)
                .readUnsignedShortVolume(verified.assetPath("template"));
        return annotations.withTemplate(template);
    }
}
