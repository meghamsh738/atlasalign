package org.atlasalign.application.manual;

import java.util.Objects;

public record VerifiedAtlasIdentity(
        String atlasName,
        String atlasVersion,
        String identitySha256) {

    public VerifiedAtlasIdentity {
        atlasName = requireText(atlasName, "atlasName");
        atlasVersion = requireText(atlasVersion, "atlasVersion");
        identitySha256 = Objects.requireNonNull(identitySha256, "identitySha256");
        if (!identitySha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "identitySha256 must be a lowercase SHA-256 value");
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
