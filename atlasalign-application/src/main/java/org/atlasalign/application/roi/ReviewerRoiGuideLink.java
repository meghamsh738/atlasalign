package org.atlasalign.application.roi;

import java.util.Objects;

/** Optional guidance/provenance link; it never controls manual ROI export. */
public record ReviewerRoiGuideLink(
        int regionId,
        String acronym,
        String name,
        boolean includesDescendants,
        String atlasIdentityHash,
        int coronalLevel,
        long alignmentRevision) {

    public ReviewerRoiGuideLink {
        if (regionId <= 0 || coronalLevel < 0 || alignmentRevision < 0) {
            throw new IllegalArgumentException(
                    "Guide identity, plane, and revision must be non-negative");
        }
        acronym = requireText(acronym, "acronym");
        name = requireText(name, "name");
        atlasIdentityHash = requireText(
                atlasIdentityHash, "atlasIdentityHash");
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
