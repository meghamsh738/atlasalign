package org.atlasalign.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Verified Allen atlas identity, dimensions, and asset checksums.
 */
public record AtlasReviewProvenance(
        String atlasId,
        String atlasVersion,
        int atlasPlaneWidth,
        int atlasPlaneHeight,
        List<AtlasAssetVerification> assets) {

    public static final int ALLEN_CORONAL_WIDTH = 456;
    public static final int ALLEN_CORONAL_HEIGHT = 320;

    public AtlasReviewProvenance {
        atlasId = Objects.requireNonNull(atlasId, "atlasId");
        atlasVersion = Objects.requireNonNull(
                atlasVersion, "atlasVersion");
        assets = List.copyOf(Objects.requireNonNull(
                assets, "assets"));
        if (!atlasId.equals(AllenCoronalLevel.ATLAS_ID)
                || !atlasVersion.equals(
                AllenCoronalLevel.ATLAS_VERSION)
                || atlasPlaneWidth != ALLEN_CORONAL_WIDTH
                || atlasPlaneHeight != ALLEN_CORONAL_HEIGHT) {
            throw new IllegalArgumentException(
                    "Review requires the pinned Allen 25 um coronal atlas");
        }
        final Set<String> roles = assets.stream()
                .map(AtlasAssetVerification::role)
                .collect(Collectors.toSet());
        if (roles.size() != assets.size()
                || !roles.containsAll(Set.of(
                "template", "annotation", "ontology"))) {
            throw new IllegalArgumentException(
                    "Atlas provenance requires unique template, annotation, and ontology assets");
        }
    }

    /** Deterministic identity of the exact atlas definition and assets. */
    public String identitySha256() {
        final StringBuilder canonical = new StringBuilder()
                .append("schema=atlasalign-manual-atlas-identity-v1\n")
                .append("atlas_id=").append(atlasId).append('\n')
                .append("atlas_version=").append(atlasVersion).append('\n')
                .append("plane=").append(atlasPlaneWidth).append('x')
                .append(atlasPlaneHeight).append('\n');
        assets.stream().sorted(Comparator.comparing(
                AtlasAssetVerification::role)).forEach(asset -> canonical
                        .append("asset.").append(asset.role()).append(".size=")
                        .append(asset.sizeBytes()).append('\n')
                        .append("asset.").append(asset.role())
                        .append(".sha256=").append(asset.sha256()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(canonical.toString().getBytes(
                            StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
