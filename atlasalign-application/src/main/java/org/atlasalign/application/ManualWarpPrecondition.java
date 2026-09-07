package org.atlasalign.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.manual.ManualWarpException;
import org.atlasalign.application.manual.ManualWarpFailureKind;
import org.atlasalign.core.AffineTransform2D;

/**
 * Immutable upstream identities captured before an asynchronous manual-mesh
 * solve. The content revision is checked atomically by
 * {@link AlignmentReviewSession#applyIfCurrentRevision(long, ReviewEdit)};
 * these hashes independently bind the result to its immutable preview domain,
 * plane, and prior side warp.
 */
public record ManualWarpPrecondition(
        String previewDomainSha256,
        String planeSha256,
        Optional<String> reviewedSupportSha256,
        boolean tissueClippingEnabled,
        HalfAtlasCoverage halfAtlasCoverage,
        Optional<String> outlineWarpSha256,
        Optional<String> priorWarpSha256) {

    /** Compatibility constructor for requests created before Half scope. */
    public ManualWarpPrecondition(
            final String previewDomainSha256,
            final String planeSha256,
            final Optional<String> reviewedSupportSha256,
            final boolean tissueClippingEnabled,
            final Optional<String> outlineWarpSha256,
            final Optional<String> priorWarpSha256) {
        this(previewDomainSha256, planeSha256, reviewedSupportSha256,
                tissueClippingEnabled, HalfAtlasCoverage.VISIBLE_SIDE_ONLY,
                outlineWarpSha256, priorWarpSha256);
    }

    public ManualWarpPrecondition {
        previewDomainSha256 = requireSha256(
                previewDomainSha256, "previewDomainSha256");
        planeSha256 = requireSha256(planeSha256, "planeSha256");
        reviewedSupportSha256 = requireOptionalSha256(
                reviewedSupportSha256, "reviewedSupportSha256");
        halfAtlasCoverage = Objects.requireNonNull(
                halfAtlasCoverage, "halfAtlasCoverage");
        outlineWarpSha256 = requireOptionalSha256(
                outlineWarpSha256, "outlineWarpSha256");
        priorWarpSha256 = Objects.requireNonNull(
                priorWarpSha256, "priorWarpSha256")
                .map(value -> requireSha256(value, "priorWarpSha256"));
    }

    public static ManualWarpPrecondition capture(
            final AlignmentReviewState state) {
        Objects.requireNonNull(state, "state");
        return new ManualWarpPrecondition(
                domainSha256(state.basis()),
                planeSha256(state.basis(), state.content()),
                state.content().reviewedTissueSupport()
                        .map(ReviewedTissueSupport::contentSha256),
                state.content().tissueClippingEnabled(),
                state.content().halfAtlasCoverage(),
                state.content().outlineWarp()
                        .map(warp -> warp.contentSha256()),
                state.content().hemisphereWarp().map(warp ->
                        warp.diagnostics().contentSha256()));
    }

    /** Fails closed when any upstream identity changed during the solve. */
    public void requireMatches(
            final AlignmentReviewContent current,
            final AlignmentReviewBasis basis) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(basis, "basis");
        final Optional<String> currentWarp = current.hemisphereWarp()
                .map(warp -> warp.diagnostics().contentSha256());
        final Optional<String> currentSupport = current.reviewedTissueSupport()
                .map(ReviewedTissueSupport::contentSha256);
        final Optional<String> currentOutline = current.outlineWarp()
                .map(warp -> warp.contentSha256());
        if (!previewDomainSha256.equals(domainSha256(basis))
                || !planeSha256.equals(planeSha256(basis, current))
                || !reviewedSupportSha256.equals(currentSupport)
                || tissueClippingEnabled != current.tissueClippingEnabled()
                || halfAtlasCoverage != current.halfAtlasCoverage()
                || !outlineWarpSha256.equals(currentOutline)
                || !priorWarpSha256.equals(currentWarp)) {
            throw new ManualWarpException(
                    ManualWarpFailureKind.STALE_RESULT,
                    "The image, plane, crop, Half coverage, outline, or warp changed while this move was being checked, so the older result was discarded. Try the move again.",
                    "Stale manual-warp result: preview domain, plane, reviewed support, clipping, Half coverage, outline, or prior warp changed while the field was being solved");
        }
    }

    private static Optional<String> requireOptionalSha256(
            final Optional<String> value,
            final String label) {
        return Objects.requireNonNull(value, label)
                .map(hash -> requireSha256(hash, label));
    }

    public static String planeSha256(
            final AlignmentReviewBasis basis,
            final AlignmentReviewContent content) {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(content, "content");
        final MessageDigest digest = sha256();
        update(digest, basis.atlas().identitySha256());
        update(digest, content.coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
        update(digest, content.atlasPlaneTilt().sagittalDegrees());
        update(digest, content.atlasPlaneTilt().horizontalDegrees());
        update(digest, content.orientation().name());
        update(digest, content.observedHemisphere().name());
        update(digest, content.reviewSectionMode().name());
        update(digest, content.halfAtlasCoverage().name());
        update(digest, content.manualSidePlacement().atlasLeft());
        update(digest, content.manualSidePlacement().atlasRight());
        update(digest, content.workflowMode().name());
        update(digest, content.manualPreviewAdjustment());
        update(digest, content.postOutlinePreviewAdjustment());
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String domainSha256(final AlignmentReviewBasis basis) {
        final MessageDigest digest = sha256();
        update(digest, basis.sourceSnapshot().pixelSha256());
        update(digest, basis.previewDimensions().width());
        update(digest, basis.previewDimensions().height());
        basis.previewDimensions().pixelsSha256()
                .ifPresent(value -> update(digest, value));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(
            final MessageDigest digest,
            final AffineTransform2D transform) {
        update(digest, transform.m00());
        update(digest, transform.m01());
        update(digest, transform.m02());
        update(digest, transform.m10());
        update(digest, transform.m11());
        update(digest, transform.m12());
    }

    private static void update(
            final MessageDigest digest,
            final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void update(
            final MessageDigest digest,
            final int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value).array());
    }

    private static void update(
            final MessageDigest digest,
            final double value) {
        digest.update(ByteBuffer.allocate(Long.BYTES)
                .putLong(Double.doubleToLongBits(value)).array());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requireSha256(
            final String value,
            final String name) {
        final String checked = Objects.requireNonNull(value, name);
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be a lowercase SHA-256");
        }
        return checked;
    }
}
