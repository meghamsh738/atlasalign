package org.atlasalign.plugin.export;

import java.util.Objects;
import java.util.Set;
import org.atlasalign.plugin.review.SelectedAtlasRegion;

/** One top-level ontology choice and the exact annotation IDs it includes. */
public record ExportRegionSelection(
        int rootRegionId,
        String acronym,
        String name,
        boolean includeDescendants,
        Set<Integer> includedRegionIds) {

    public ExportRegionSelection {
        if (rootRegionId <= 0) {
            throw new IllegalArgumentException(
                    "Export region ID must be positive");
        }
        acronym = requireText(acronym, "acronym");
        name = requireText(name, "name");
        includedRegionIds = Set.copyOf(Objects.requireNonNull(
                includedRegionIds, "includedRegionIds"));
        if (!includedRegionIds.contains(rootRegionId)
                || includedRegionIds.stream().anyMatch(
                id -> id == null || id <= 0)) {
            throw new IllegalArgumentException(
                    "Export IDs must be positive and include the root");
        }
        if (!includeDescendants && includedRegionIds.size() != 1) {
            throw new IllegalArgumentException(
                    "A root-only export may include only its root annotation ID");
        }
    }

    public static ExportRegionSelection from(
            final SelectedAtlasRegion region,
            final boolean includeDescendants) {
        final SelectedAtlasRegion checked = Objects.requireNonNull(
                region, "region");
        return new ExportRegionSelection(
                checked.rootRegionId(), checked.acronym(), checked.name(),
                includeDescendants,
                includeDescendants ? checked.includedRegionIds()
                        : Set.of(checked.rootRegionId()));
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
