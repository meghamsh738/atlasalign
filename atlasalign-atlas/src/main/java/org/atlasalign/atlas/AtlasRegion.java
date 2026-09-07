package org.atlasalign.atlas;

import java.util.List;
import java.util.Objects;

/**
 * One Allen ontology node. Hemisphere ID 3 denotes a bilateral structure in
 * the downloaded Allen structure graph.
 */
public record AtlasRegion(
        int id,
        String acronym,
        String name,
        Integer parentId,
        int hemisphereId,
        List<Integer> childIds) {

    public AtlasRegion {
        if (id <= 0) {
            throw new IllegalArgumentException("Atlas region ID must be positive");
        }
        acronym = requireText(acronym, "acronym");
        name = requireText(name, "name");
        if (parentId != null && parentId <= 0) {
            throw new IllegalArgumentException(
                    "Parent atlas region ID must be positive");
        }
        childIds = List.copyOf(Objects.requireNonNull(childIds, "childIds"));
    }

    private static String requireText(final String value, final String field) {
        final String checked = Objects.requireNonNull(value, field).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return checked;
    }
}
