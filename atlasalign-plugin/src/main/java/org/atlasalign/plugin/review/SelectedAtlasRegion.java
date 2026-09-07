package org.atlasalign.plugin.review;

import java.util.Objects;
import java.util.Set;

/** Display/pick target resolved from the verified Allen ontology. */
public record SelectedAtlasRegion(
        int rootRegionId,
        String acronym,
        String name,
        Set<Integer> includedRegionIds) {

    public SelectedAtlasRegion {
        if (rootRegionId <= 0) {
            throw new IllegalArgumentException(
                    "Atlas region ID must be positive");
        }
        acronym = requireText(acronym, "acronym");
        name = requireText(name, "name");
        includedRegionIds = Set.copyOf(Objects.requireNonNull(
                includedRegionIds, "includedRegionIds"));
        if (!includedRegionIds.contains(rootRegionId)
                || includedRegionIds.stream().anyMatch(
                id -> id == null || id <= 0)) {
            throw new IllegalArgumentException(
                    "Region IDs must be positive and include the root");
        }
    }

    public boolean contains(final int annotationId) {
        return includedRegionIds.contains(annotationId);
    }

    public String displayName() {
        return acronym + " — " + name;
    }

    private static String requireText(
            final String value,
            final String field) {
        final String checked = Objects.requireNonNull(value, field).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return checked;
    }
}
