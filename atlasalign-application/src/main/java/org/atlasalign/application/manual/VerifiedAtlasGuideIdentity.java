package org.atlasalign.application.manual;

import java.util.Objects;

/** Exact ontology and verified-atlas identity for a named anatomical guide. */
public record VerifiedAtlasGuideIdentity(
        String atlasIdentitySha256,
        String ontologyName,
        String ontologyVersion,
        int structureId,
        String acronym) {

    public VerifiedAtlasGuideIdentity {
        atlasIdentitySha256 = Objects.requireNonNull(
                atlasIdentitySha256, "atlasIdentitySha256");
        ontologyName = requireText(ontologyName, "ontologyName");
        ontologyVersion = requireText(ontologyVersion, "ontologyVersion");
        acronym = requireText(acronym, "acronym");
        if (!atlasIdentitySha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "atlasIdentitySha256 must be a lowercase SHA-256 value");
        }
        if (structureId < 0) {
            throw new IllegalArgumentException("structureId must be non-negative");
        }
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
