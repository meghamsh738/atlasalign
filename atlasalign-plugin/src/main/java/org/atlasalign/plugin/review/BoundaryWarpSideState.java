package org.atlasalign.plugin.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.manual.BoundaryWarpCandidate;
import org.atlasalign.application.manual.BoundaryWarpPreview;
import org.atlasalign.application.manual.BoundaryWarpRequest;
import org.atlasalign.application.manual.ManualWarpSafetyReport;

/**
 * One side's transient Border draft and its last explicitly calculated result.
 * Editing this value never changes review history or starts a nonlinear solve.
 */
public record BoundaryWarpSideState(
        Optional<BoundaryWarpRequest> draft,
        Optional<BoundaryWarpPreview> preview,
        Optional<BoundaryWarpCandidate> candidate,
        boolean dirty,
        int pendingEditCount,
        Optional<String> calculatedDraftHash,
        Optional<ManualWarpSafetyReport> safetyReport) {

    public BoundaryWarpSideState {
        draft = Objects.requireNonNull(draft, "draft");
        preview = Objects.requireNonNull(preview, "preview");
        candidate = Objects.requireNonNull(candidate, "candidate");
        calculatedDraftHash = Objects.requireNonNull(
                calculatedDraftHash, "calculatedDraftHash");
        safetyReport = Objects.requireNonNull(
                safetyReport, "safetyReport");
        if (pendingEditCount < 0) {
            throw new IllegalArgumentException(
                    "Pending Border edit count must not be negative");
        }
        if (draft.isEmpty() && (preview.isPresent() || candidate.isPresent()
                || dirty || pendingEditCount != 0
                || calculatedDraftHash.isPresent()
                || safetyReport.isPresent())) {
            throw new IllegalArgumentException(
                    "An empty Border side cannot retain transient evaluation state");
        }
        if (preview.isPresent() && !draft.equals(Optional.of(
                preview.orElseThrow().request()))) {
            throw new IllegalArgumentException(
                    "Boundary-warp preview must match its displayed draft");
        }
        if (candidate.isPresent() && !draft.equals(Optional.of(
                candidate.orElseThrow().request()))) {
            throw new IllegalArgumentException(
                    "Boundary-warp candidate must match its displayed draft");
        }
        if (dirty && (preview.isPresent() || candidate.isPresent()
                || calculatedDraftHash.isPresent()
                || safetyReport.isPresent())) {
            throw new IllegalArgumentException(
                    "A dirty Border draft cannot expose a stale calculation");
        }
        if (!dirty && draft.isPresent()
                && (preview.isPresent() || candidate.isPresent()
                        || safetyReport.isPresent())
                && calculatedDraftHash.isEmpty()) {
            throw new IllegalArgumentException(
                    "A calculated Border result requires its exact draft hash");
        }
        if (calculatedDraftHash.isPresent()
                && !calculatedDraftHash.orElseThrow().equals(
                        draftHash(draft.orElseThrow()))) {
            throw new IllegalArgumentException(
                    "Calculated Border hash must match the current draft");
        }
    }

    public static BoundaryWarpSideState empty() {
        return new BoundaryWarpSideState(Optional.empty(), Optional.empty(),
                Optional.empty(), false, 0, Optional.empty(),
                Optional.empty());
    }

    /** A freshly prepared or edited draft that has not been calculated. */
    public static BoundaryWarpSideState dirtyDraft(
            final BoundaryWarpRequest request,
            final int pendingEditCount) {
        return new BoundaryWarpSideState(Optional.of(Objects.requireNonNull(
                request, "request")), Optional.empty(), Optional.empty(),
                true, pendingEditCount, Optional.empty(), Optional.empty());
    }

    /** The result of exactly one explicit calculation for this exact draft. */
    public static BoundaryWarpSideState calculated(
            final BoundaryWarpRequest request,
            final BoundaryWarpPreview preview,
            final BoundaryWarpCandidate candidate,
            final ManualWarpSafetyReport safetyReport) {
        final BoundaryWarpRequest checked = Objects.requireNonNull(
                request, "request");
        return new BoundaryWarpSideState(Optional.of(checked),
                Optional.ofNullable(preview), Optional.ofNullable(candidate),
                false, 0, Optional.of(draftHash(checked)),
                Optional.ofNullable(safetyReport));
    }

    /** Exact transient identity; equality remains the authoritative apply gate. */
    public static String draftHash(final BoundaryWarpRequest request) {
        final String canonical = "boundary-warp-draft-v1\n"
                + Objects.requireNonNull(request, "request");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(canonical.getBytes(
                            StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable",
                    unavailable);
        }
    }
}
