package org.atlasalign.plugin.review;

import java.util.Objects;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.atlas.AtlasObliquePlaneLoader;
import org.atlasalign.atlas.AtlasVolume;
import org.atlasalign.atlas.AtlasVolumeLoader;
import org.atlasalign.atlas.VerifiedAtlas;

/**
 * Atlas source used by the review UI. The verified annotation volume is
 * loaded once for fast contour-first browsing. The larger grayscale template
 * is loaded only after an explicit anatomy request.
 */
public final class VerifiedAtlasPlaneSource
        implements AtlasPlaneSource, AtlasRegionCatalog {

    private final VerifiedAtlas atlas;
    private final AtlasVolumeLoader volumeLoader = new AtlasVolumeLoader();
    private final AtlasObliquePlaneLoader obliqueLoader =
            new AtlasObliquePlaneLoader();
    private AtlasVolume annotationVolume;
    private AtlasVolume anatomyVolume;

    public VerifiedAtlasPlaneSource(final VerifiedAtlas atlas) {
        this.atlas = Objects.requireNonNull(atlas, "atlas");
    }

    @Override
    public AtlasCoronalPlane load(final int zeroBasedAnteriorPosteriorIndex) {
        return load(new AtlasPlaneRequest(
                new org.atlasalign.application.AllenCoronalLevel(
                        zeroBasedAnteriorPosteriorIndex),
                AtlasPlaneTilt.CORONAL,
                false));
    }

    @Override
    public synchronized AtlasCoronalPlane load(
            final AtlasPlaneRequest request) {
        Objects.requireNonNull(request, "request");
        if (annotationVolume == null) {
            annotationVolume = volumeLoader.loadAnnotations(atlas);
        }
        if (request.showAnatomy() && anatomyVolume == null) {
            anatomyVolume = volumeLoader.loadTemplate(
                    atlas, annotationVolume);
        }
        final AtlasPlaneTilt tilt = request.tilt();
        return obliqueLoader.load(
                request.showAnatomy() ? anatomyVolume : annotationVolume,
                request.zeroBasedAnteriorPosteriorIndex(),
                tilt.sagittalDegrees(),
                tilt.horizontalDegrees());
    }

    @Override
    public java.util.Optional<SelectedAtlasRegion>
            resolveExactAcronym(final String acronym) {
        return atlas.ontology().search(acronym, 20).stream()
                .filter(region -> region.acronym().equals(acronym))
                .findFirst()
                .map(this::selectedRegion);
    }

    @Override
    public java.util.List<SelectedAtlasRegion> search(
            final String query,
            final int maximumResults) {
        return atlas.ontology().search(query, maximumResults).stream()
                .map(this::selectedRegion).toList();
    }

    @Override
    public java.util.List<org.atlasalign.atlas.AtlasRegion> hierarchy() {
        return atlas.ontology().regions();
    }

    private SelectedAtlasRegion selectedRegion(
            final org.atlasalign.atlas.AtlasRegion region) {
        final java.util.Set<Integer> identifiers = new java.util.HashSet<>();
        identifiers.add(region.id());
        atlas.ontology().descendants(region.id()).forEach(
                child -> identifiers.add(child.id()));
        return new SelectedAtlasRegion(region.id(), region.acronym(),
                region.name(), identifiers);
    }
}
