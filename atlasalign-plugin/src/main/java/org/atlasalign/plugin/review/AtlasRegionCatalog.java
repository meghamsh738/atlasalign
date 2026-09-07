package org.atlasalign.plugin.review;

import java.util.Optional;

/** Read-only exact-region boundary for fixed Phase 5 atlas targets. */
public interface AtlasRegionCatalog {

    default java.util.List<org.atlasalign.atlas.AtlasRegion> hierarchy() {
        return java.util.List.of();
    }

    Optional<SelectedAtlasRegion> resolveExactAcronym(String acronym);

    /** Lightweight ontology search for the reviewer guide selector. */
    default java.util.List<SelectedAtlasRegion> search(
            final String query,
            final int maximumResults) {
        return resolveExactAcronym(query).stream().toList();
    }
}
