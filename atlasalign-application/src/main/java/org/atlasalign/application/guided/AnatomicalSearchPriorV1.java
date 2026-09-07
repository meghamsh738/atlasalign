package org.atlasalign.application.guided;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.atlasalign.application.AllenCoronalLevel;

/**
 * Immutable, hash-bound anatomical AP interval captured before any automatic
 * result is visible.
 *
 * <p>The interval only controls candidate placement. It is not a score,
 * ground truth, confidence value, or accepted coordinate.</p>
 */
public final class AnatomicalSearchPriorV1 {

    public static final String SCHEMA =
            "atlasalign-phase5-anatomical-search-prior-v1";
    public static final int MINIMUM_WIDTH_INDICES = 32;
    public static final int MAXIMUM_WIDTH_INDICES = 160;
    public static final int MICROMETERS_PER_INDEX = 25;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PROVIDER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final String providerId;
    private final String sourceSha256;
    private final String atlasIdentitySha256;
    private final AllenCoronalLevel inclusiveStart;
    private final AllenCoronalLevel inclusiveEnd;
    private final AnatomicalTissueClass tissueClass;
    private final Instant capturedAt;
    private final Instant lockedAt;
    private final Optional<String> revisionParentSha256;
    private final String priorSha256;

    private AnatomicalSearchPriorV1(
            final String providerId,
            final String sourceSha256,
            final String atlasIdentitySha256,
            final AllenCoronalLevel inclusiveStart,
            final AllenCoronalLevel inclusiveEnd,
            final AnatomicalTissueClass tissueClass,
            final Instant capturedAt,
            final Instant lockedAt,
            final Optional<String> revisionParentSha256) {
        this.providerId = requireProvider(providerId);
        this.sourceSha256 = requireSha256(sourceSha256, "sourceSha256");
        this.atlasIdentitySha256 = requireSha256(
                atlasIdentitySha256, "atlasIdentitySha256");
        this.inclusiveStart = Objects.requireNonNull(
                inclusiveStart, "inclusiveStart");
        this.inclusiveEnd = Objects.requireNonNull(
                inclusiveEnd, "inclusiveEnd");
        this.tissueClass = Objects.requireNonNull(tissueClass, "tissueClass");
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        this.lockedAt = Objects.requireNonNull(lockedAt, "lockedAt");
        this.revisionParentSha256 = Objects.requireNonNull(
                revisionParentSha256, "revisionParentSha256")
                .map(value -> requireSha256(value, "revisionParentSha256"));
        if (lockedAt.isBefore(capturedAt)) {
            throw new IllegalArgumentException(
                    "The prior cannot be locked before it is captured");
        }
        final int width = widthIndices();
        if (width < MINIMUM_WIDTH_INDICES
                || width > MAXIMUM_WIDTH_INDICES) {
            throw new IllegalArgumentException(
                    "A guided AP interval must span 32..160 Allen indices");
        }
        this.priorSha256 = sha256(canonicalIdentityText());
    }

    public static AnatomicalSearchPriorV1 lockBeforeAutomaticDisplay(
            final String providerId,
            final String sourceSha256,
            final String atlasIdentitySha256,
            final AllenCoronalLevel inclusiveStart,
            final AllenCoronalLevel inclusiveEnd,
            final AnatomicalTissueClass tissueClass,
            final Instant capturedAt,
            final Instant lockedAt,
            final Optional<String> revisionParentSha256) {
        return new AnatomicalSearchPriorV1(
                providerId,
                sourceSha256,
                atlasIdentitySha256,
                inclusiveStart,
                inclusiveEnd,
                tissueClass,
                capturedAt,
                lockedAt,
                revisionParentSha256);
    }

    public String providerId() {
        return providerId;
    }

    public String sourceSha256() {
        return sourceSha256;
    }

    public String atlasIdentitySha256() {
        return atlasIdentitySha256;
    }

    public AllenCoronalLevel inclusiveStart() {
        return inclusiveStart;
    }

    public AllenCoronalLevel inclusiveEnd() {
        return inclusiveEnd;
    }

    public int startMicrometersFromAnteriorOrigin() {
        return inclusiveStart.anteriorOriginMicrometers();
    }

    public int endMicrometersFromAnteriorOrigin() {
        return inclusiveEnd.anteriorOriginMicrometers();
    }

    public int widthIndices() {
        return inclusiveEnd.zeroBasedAnteriorPosteriorIndex()
                - inclusiveStart.zeroBasedAnteriorPosteriorIndex();
    }

    public AnatomicalTissueClass tissueClass() {
        return tissueClass;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public Instant lockedAt() {
        return lockedAt;
    }

    public Optional<String> revisionParentSha256() {
        return revisionParentSha256;
    }

    public boolean capturedBeforeAutomaticDisplay() {
        return true;
    }

    public String priorSha256() {
        return priorSha256;
    }

    /** Stable newline-delimited representation used by external validators. */
    public String canonicalIdentityText() {
        return String.join("\n",
                "schema=" + SCHEMA,
                "provider_id=" + providerId,
                "source_sha256=" + sourceSha256,
                "atlas_identity_sha256=" + atlasIdentitySha256,
                "ap_start_index="
                        + inclusiveStart.zeroBasedAnteriorPosteriorIndex(),
                "ap_end_index="
                        + inclusiveEnd.zeroBasedAnteriorPosteriorIndex(),
                "ap_start_um=" + startMicrometersFromAnteriorOrigin(),
                "ap_end_um=" + endMicrometersFromAnteriorOrigin(),
                "tissue_class=" + tissueClass.name(),
                "captured_at=" + capturedAt,
                "locked_at=" + lockedAt,
                "captured_before_automatic_display=true",
                "revision_parent_sha256="
                        + revisionParentSha256.orElse("NONE")) + "\n";
    }

    private static String requireProvider(final String value) {
        final String checked = Objects.requireNonNull(
                value, "providerId").trim();
        if (!PROVIDER.matcher(checked).matches()) {
            throw new IllegalArgumentException(
                    "providerId must be a stable pseudonymous token");
        }
        return checked;
    }

    private static String requireSha256(
            final String value,
            final String label) {
        Objects.requireNonNull(value, label);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    label + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static String sha256(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
