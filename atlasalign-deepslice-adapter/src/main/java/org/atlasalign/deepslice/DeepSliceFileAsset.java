package org.atlasalign.deepslice;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;

/**
 * Exact external file identity recorded by the trusted inventory.
 */
public record DeepSliceFileAsset(
        String relativePath,
        long sizeBytes,
        String sha256,
        DeepSliceAssetRole role,
        boolean executable) {

    public DeepSliceFileAsset {
        relativePath = Objects.requireNonNull(
                relativePath, "relativePath");
        sha256 = Objects.requireNonNull(sha256, "sha256");
        role = Objects.requireNonNull(role, "role");
        final Path path = Path.of(relativePath);
        if (relativePath.isBlank()
                || path.isAbsolute()
                || relativePath.startsWith("/")
                || relativePath.endsWith("/")
                || relativePath.contains("//")
                || Arrays.stream(relativePath.split("/", -1))
                        .anyMatch(part -> part.isBlank()
                                || part.equals(".")
                                || part.equals(".."))
                || relativePath.contains("\\")
                || sizeBytes < 0
                || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Invalid DeepSlice asset identity");
        }
    }
}
