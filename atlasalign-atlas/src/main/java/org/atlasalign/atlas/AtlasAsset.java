package org.atlasalign.atlas;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One immutable asset in a pinned atlas distribution.
 */
public record AtlasAsset(
        String role,
        String relativePath,
        URI url,
        long sizeBytes,
        String sha256) {

    public AtlasAsset {
        role = requireText(role, "role");
        relativePath = requireText(relativePath, "relativePath");
        url = Objects.requireNonNull(url, "url");
        sha256 = requireText(sha256, "sha256").toLowerCase();
        final Path path = Path.of(relativePath);
        if (path.isAbsolute() || relativePath.contains("..")
                || path.getNameCount() != 1) {
            throw new IllegalArgumentException(
                    "Atlas assets must use a single safe relative filename");
        }
        if (!"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException("Atlas asset URL must use HTTPS");
        }
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("Atlas asset size must be positive");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Atlas asset SHA-256 must contain 64 hexadecimal characters");
        }
    }

    private static String requireText(final String value, final String field) {
        final String checked = Objects.requireNonNull(value, field).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return checked;
    }
}
