package org.atlasalign.plugin.validation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.atlasalign.atlas.VerifiedAtlas;

/** Stable identity of the exact verified Allen assets used for prior capture. */
public final class GuidedAtlasIdentity {

    private GuidedAtlasIdentity() {
    }

    public static String sha256(final VerifiedAtlas atlas) {
        Objects.requireNonNull(atlas, "atlas");
        final StringBuilder text = new StringBuilder()
                .append("schema=atlasalign-guided-atlas-identity-v1\n")
                .append("atlas_id=").append(atlas.manifest().atlasId()).append('\n')
                .append("atlas_version=").append(atlas.manifest().atlasVersion()).append('\n')
                .append("resolution_um=")
                .append(atlas.manifest().resolutionMicrometers()).append('\n')
                .append("coordinate_space=")
                .append(atlas.manifest().coordinateSpace()).append('\n')
                .append("dimensions=").append(atlas.manifest().dimensions()).append('\n');
        atlas.manifest().assets().stream()
                .sorted(java.util.Comparator.comparing(
                        org.atlasalign.atlas.AtlasAsset::role))
                .forEach(asset -> text.append("asset.")
                        .append(asset.role()).append(".size=")
                        .append(asset.sizeBytes()).append('\n')
                        .append("asset.").append(asset.role()).append(".sha256=")
                        .append(asset.sha256()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(text.toString().getBytes(
                            StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
