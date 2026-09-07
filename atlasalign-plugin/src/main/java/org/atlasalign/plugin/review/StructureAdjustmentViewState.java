package org.atlasalign.plugin.review;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Display-only Structure request/calculation state; never serialized. */
public record StructureAdjustmentViewState(
        boolean loading,
        Optional<StructureAdjustmentDraft> draft,
        Optional<StructureAdjustmentCandidate> candidate,
        List<StructureAdjustmentLimit> limitingReports,
        Optional<String> message) {

    public StructureAdjustmentViewState {
        draft = Objects.requireNonNull(draft, "draft");
        candidate = Objects.requireNonNull(candidate, "candidate");
        limitingReports = List.copyOf(Objects.requireNonNull(
                limitingReports, "limitingReports"));
        message = Objects.requireNonNull(message, "message")
                .map(String::trim).filter(value -> !value.isBlank());
        if (candidate.isPresent() && (draft.isEmpty()
                || !candidate.orElseThrow().draftHash().equals(
                        draft.orElseThrow().inputHash()))) {
            throw new IllegalArgumentException(
                    "Structure candidate must match its exact draft");
        }
    }

    public StructureAdjustmentViewState(
            final boolean loading,
            final Optional<StructureAdjustmentDraft> draft,
            final Optional<StructureAdjustmentCandidate> candidate,
            final Optional<String> message) {
        this(loading, draft, candidate, List.of(), message);
    }

    public static StructureAdjustmentViewState inactive() {
        return new StructureAdjustmentViewState(false, Optional.empty(),
                Optional.empty(), List.of(), Optional.empty());
    }

    public boolean active() {
        return loading || draft.isPresent();
    }

    public Optional<String> highlightedUnitId() {
        return candidate.flatMap(
                StructureAdjustmentCandidate::highlightedUnitId)
                .or(() -> limitingReports.stream().findFirst()
                        .map(StructureAdjustmentLimit::unitId));
    }
}
