package org.atlasalign.application.c01;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;

/**
 * Immutable identity of every non-AP condition held fixed during C01.
 *
 * <p>The canonical text is deliberately dependency-free and mirrors the
 * validation-harness representation. Floating-point values are encoded as
 * their exact IEEE-754 bit patterns, so a candidate cannot silently vary
 * tilt or transform while retaining the same identity.</p>
 */
public record C01SearchContext(
        String sourceSha256,
        String atlasIdentitySha256,
        String runtimeReleaseId,
        String runtimeManifestSha256,
        AllenCoronalLevel r3Level,
        AtlasPlaneTilt fixedTilt,
        AffineTransform2D fixedAtlasToFeature,
        AtlasOrientation fixedOrientation,
        String featureGenerationId) {

    public static final String SCHEMA =
            "atlasalign-phase5-c01-search-context-v1";
    public static final String R3_RELEASE_ID =
            "deepslice-1.2.8-py3.11.15-tf2.21.0-macos-arm64-r3";
    public static final String FEATURE_GENERATION_ID =
            "C01_ALLEN25UM_SOBEL_ORIENTATION_V1";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._-]+");

    public C01SearchContext {
        requireSha256(sourceSha256, "sourceSha256");
        requireSha256(atlasIdentitySha256, "atlasIdentitySha256");
        requireToken(runtimeReleaseId, "runtimeReleaseId");
        requireSha256(runtimeManifestSha256, "runtimeManifestSha256");
        r3Level = Objects.requireNonNull(r3Level, "r3Level");
        fixedTilt = Objects.requireNonNull(fixedTilt, "fixedTilt");
        fixedAtlasToFeature = Objects.requireNonNull(
                fixedAtlasToFeature, "fixedAtlasToFeature");
        fixedOrientation = Objects.requireNonNull(
                fixedOrientation, "fixedOrientation");
        requireToken(featureGenerationId, "featureGenerationId");
        if (!R3_RELEASE_ID.equals(runtimeReleaseId)) {
            throw new IllegalArgumentException(
                    "C01 requires the immutable verified r3 runtime");
        }
        if (!FEATURE_GENERATION_ID.equals(featureGenerationId)) {
            throw new IllegalArgumentException(
                    "C01 feature-generation identity is not frozen v1");
        }
        if (fixedAtlasToFeature.sourceSpace()
                        != CoordinateSpace2D.ATLAS_PLANE_PIXEL
                || fixedAtlasToFeature.destinationSpace()
                        != CoordinateSpace2D.PREVIEW_PIXEL) {
            throw new IllegalArgumentException(
                    "C01 fixed transform must map atlas-plane to preview pixels");
        }
    }

    /** Exact serialized identity used in Java evidence and the Python gate. */
    public String canonicalIdentityText() {
        return String.join("\n",
                "schema=" + SCHEMA,
                "source_sha256=" + sourceSha256,
                "atlas_identity_sha256=" + atlasIdentitySha256,
                "runtime_release_id=" + runtimeReleaseId,
                "runtime_manifest_sha256=" + runtimeManifestSha256,
                "r3_level=" + r3Level.zeroBasedAnteriorPosteriorIndex(),
                "sagittal_tilt_bits=" + bits(
                        fixedTilt.sagittalDegrees()),
                "horizontal_tilt_bits=" + bits(
                        fixedTilt.horizontalDegrees()),
                "transform_source_space="
                        + fixedAtlasToFeature.sourceSpace().name(),
                "transform_destination_space="
                        + fixedAtlasToFeature.destinationSpace().name(),
                "transform_m00_bits=" + bits(fixedAtlasToFeature.m00()),
                "transform_m01_bits=" + bits(fixedAtlasToFeature.m01()),
                "transform_m02_bits=" + bits(fixedAtlasToFeature.m02()),
                "transform_m10_bits=" + bits(fixedAtlasToFeature.m10()),
                "transform_m11_bits=" + bits(fixedAtlasToFeature.m11()),
                "transform_m12_bits=" + bits(fixedAtlasToFeature.m12()),
                "orientation=" + fixedOrientation.name(),
                "feature_generation_id=" + featureGenerationId)
                + "\n";
    }

    public String identitySha256() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(canonicalIdentityText().getBytes(
                            StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String bits(final double value) {
        return String.format(Locale.ROOT, "%016x",
                Double.doubleToRawLongBits(value));
    }

    private static void requireSha256(
            final String value,
            final String label) {
        Objects.requireNonNull(value, label);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    label + " must be a lowercase SHA-256");
        }
    }

    private static void requireToken(
            final String value,
            final String label) {
        Objects.requireNonNull(value, label);
        if (!TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    label + " must be a stable single-line token");
        }
    }
}
