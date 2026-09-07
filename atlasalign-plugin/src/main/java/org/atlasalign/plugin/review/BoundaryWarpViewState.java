package org.atlasalign.plugin.review;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.manual.ManualWarpSafetyReport;
import org.atlasalign.application.manual.BoundaryWarpCandidate;
import org.atlasalign.application.manual.BoundaryWarpPreview;
import org.atlasalign.application.manual.BoundaryWarpRequest;

/** Transient, unapplied state for manual outer-border pairing. */
public record BoundaryWarpViewState(
        boolean loading,
        BoundaryWarpSideState primary,
        BoundaryWarpSideState secondary,
        Optional<String> message,
        boolean recheckSuggested) {

    public BoundaryWarpViewState {
        primary = Objects.requireNonNull(primary, "primary");
        secondary = Objects.requireNonNull(secondary, "secondary");
        message = Objects.requireNonNull(message, "message")
                .map(String::trim).filter(value -> !value.isEmpty());
        if (primary.draft().isPresent() && secondary.draft().isPresent()
                && primary.draft().orElseThrow().targetSide()
                == secondary.draft().orElseThrow().targetSide()) {
            throw new IllegalArgumentException(
                    "Primary and secondary boundary drafts must target different sides");
        }
    }

    /** Compatibility constructor used while callers migrate to side records. */
    public BoundaryWarpViewState(
            final boolean loading,
            final Optional<BoundaryWarpRequest> draft,
            final Optional<BoundaryWarpPreview> preview,
            final Optional<BoundaryWarpCandidate> candidate,
            final Optional<BoundaryWarpRequest> secondaryDraft,
            final Optional<BoundaryWarpPreview> secondaryPreview,
            final Optional<BoundaryWarpCandidate> secondaryCandidate,
            final Optional<String> message,
            final Optional<ManualWarpSafetyReport> safetyReport,
            final boolean recheckSuggested) {
        this(loading,
                legacySide(draft, preview, candidate, safetyReport),
                legacySide(secondaryDraft, secondaryPreview,
                        secondaryCandidate, Optional.empty()),
                message, recheckSuggested);
    }

    public BoundaryWarpViewState(
            final boolean loading,
            final Optional<BoundaryWarpRequest> draft,
            final Optional<BoundaryWarpPreview> preview,
            final Optional<BoundaryWarpCandidate> candidate,
            final Optional<BoundaryWarpRequest> secondaryDraft,
            final Optional<BoundaryWarpPreview> secondaryPreview,
            final Optional<BoundaryWarpCandidate> secondaryCandidate,
            final Optional<String> message,
            final boolean recheckSuggested) {
        this(loading, draft, preview, candidate, secondaryDraft,
                secondaryPreview, secondaryCandidate, message,
                Optional.empty(), recheckSuggested);
    }

    public BoundaryWarpViewState(
            final boolean loading,
            final Optional<BoundaryWarpRequest> draft,
            final Optional<BoundaryWarpPreview> preview,
            final Optional<BoundaryWarpCandidate> candidate,
            final Optional<String> message,
            final boolean recheckSuggested) {
        this(loading, draft, preview, candidate,
                Optional.empty(), Optional.empty(), Optional.empty(),
                message, Optional.empty(), recheckSuggested);
    }

    public static BoundaryWarpViewState inactive() {
        return new BoundaryWarpViewState(false,
                BoundaryWarpSideState.empty(),
                BoundaryWarpSideState.empty(), Optional.empty(), false);
    }

    public boolean active() {
        return loading || draft().isPresent() || secondaryDraft().isPresent();
    }

    public java.util.List<BoundaryWarpRequest> drafts() {
        return java.util.stream.Stream.concat(
                draft().stream(), secondaryDraft().stream()).toList();
    }

    public Optional<BoundaryWarpRequest> draft() {
        return primary.draft();
    }

    public Optional<BoundaryWarpPreview> preview() {
        return primary.preview();
    }

    public Optional<BoundaryWarpCandidate> candidate() {
        return primary.candidate();
    }

    public Optional<BoundaryWarpRequest> secondaryDraft() {
        return secondary.draft();
    }

    public Optional<BoundaryWarpPreview> secondaryPreview() {
        return secondary.preview();
    }

    public Optional<BoundaryWarpCandidate> secondaryCandidate() {
        return secondary.candidate();
    }

    public Optional<ManualWarpSafetyReport> safetyReport() {
        return primary.safetyReport();
    }

    public boolean dirty() {
        return primary.dirty();
    }

    public int pendingEditCount() {
        return primary.pendingEditCount();
    }

    public boolean secondaryDirty() {
        return secondary.dirty();
    }

    public int secondaryPendingEditCount() {
        return secondary.pendingEditCount();
    }

    private static BoundaryWarpSideState legacySide(
            final Optional<BoundaryWarpRequest> draft,
            final Optional<BoundaryWarpPreview> preview,
            final Optional<BoundaryWarpCandidate> candidate,
            final Optional<ManualWarpSafetyReport> safetyReport) {
        if (draft.isEmpty()) {
            return BoundaryWarpSideState.empty();
        }
        final BoundaryWarpRequest request = draft.orElseThrow();
        if (preview.isEmpty() && candidate.isEmpty()
                && safetyReport.isEmpty()) {
            return new BoundaryWarpSideState(draft, preview, candidate,
                    false, 0, Optional.empty(), Optional.empty());
        }
        return BoundaryWarpSideState.calculated(request,
                preview.orElse(null), candidate.orElse(null),
                safetyReport.orElse(null));
    }
}
