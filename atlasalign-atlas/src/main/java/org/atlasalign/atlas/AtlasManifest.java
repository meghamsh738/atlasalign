package org.atlasalign.atlas;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Machine-readable identity and integrity contract for an atlas cache.
 */
public record AtlasManifest(
        int schemaVersion,
        String atlasId,
        String atlasVersion,
        int resolutionMicrometers,
        String coordinateSpace,
        List<Integer> dimensions,
        String termsUrl,
        String citation,
        List<AtlasAsset> assets) {

    public AtlasManifest {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported atlas manifest schema");
        }
        atlasId = requireText(atlasId, "atlasId");
        atlasVersion = requireText(atlasVersion, "atlasVersion");
        coordinateSpace = requireText(coordinateSpace, "coordinateSpace");
        termsUrl = requireText(termsUrl, "termsUrl");
        citation = requireText(citation, "citation");
        if (resolutionMicrometers <= 0) {
            throw new IllegalArgumentException(
                    "Atlas resolution must be positive");
        }
        dimensions = List.copyOf(Objects.requireNonNull(
                dimensions, "dimensions"));
        if (dimensions.size() != 3
                || dimensions.stream().anyMatch(value -> value == null || value <= 0)) {
            throw new IllegalArgumentException(
                    "Atlas dimensions must contain three positive axes");
        }
        assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
        if (assets.isEmpty()) {
            throw new IllegalArgumentException("Atlas manifest has no assets");
        }
        final Set<String> roles = assets.stream()
                .map(AtlasAsset::role)
                .collect(Collectors.toSet());
        final Set<String> paths = assets.stream()
                .map(AtlasAsset::relativePath)
                .collect(Collectors.toSet());
        if (roles.size() != assets.size() || paths.size() != assets.size()) {
            throw new IllegalArgumentException(
                    "Atlas asset roles and paths must be unique");
        }
        if (!roles.containsAll(Set.of("template", "annotation", "ontology"))) {
            throw new IllegalArgumentException(
                    "Atlas manifest requires template, annotation, and ontology assets");
        }
    }

    public long totalSizeBytes() {
        return assets.stream().mapToLong(AtlasAsset::sizeBytes).sum();
    }

    public AtlasAsset asset(final String role) {
        return assets.stream()
                .filter(asset -> asset.role().equals(role))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No atlas asset has role: " + role));
    }

    private static String requireText(final String value, final String field) {
        final String checked = Objects.requireNonNull(value, field).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return checked;
    }
}
