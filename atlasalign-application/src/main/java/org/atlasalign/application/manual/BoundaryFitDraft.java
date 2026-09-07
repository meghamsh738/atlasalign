package org.atlasalign.application.manual;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, unapplied guided coarse-fit state. */
public record BoundaryFitDraft(
        BoundaryFitRequest request,
        List<BoundaryFitAnchor> anchors,
        Optional<String> activeAnchorId) {

    public BoundaryFitDraft {
        request = Objects.requireNonNull(request, "request");
        if (!request.matches().isEmpty()) {
            throw new IllegalArgumentException(
                    "A boundary-fit draft request must not contain solved matches");
        }
        anchors = List.copyOf(Objects.requireNonNull(anchors, "anchors"));
        if (anchors.size() < BoundaryFitSolver.MINIMUM_INCLUDED_MATCHES) {
            throw new IllegalArgumentException(
                    "A boundary-fit draft needs at least four atlas anchors");
        }
        final HashSet<String> identifiers = new HashSet<>();
        final HashSet<Integer> ordinals = new HashSet<>();
        if (anchors.stream().anyMatch(anchor -> anchor == null
                || !identifiers.add(anchor.id())
                || !ordinals.add(anchor.ordinal()))) {
            throw new IllegalArgumentException(
                    "Boundary-fit anchor ids and ordinals must be unique");
        }
        activeAnchorId = Objects.requireNonNull(
                activeAnchorId, "activeAnchorId");
        if (activeAnchorId.isPresent()
                && !identifiers.contains(activeAnchorId.orElseThrow())) {
            throw new IllegalArgumentException(
                    "The active boundary-fit anchor must exist in the draft");
        }
    }

    public List<BoundaryFitMatch> completedMatches() {
        return anchors.stream()
                .map(BoundaryFitAnchor::completedMatch)
                .flatMap(Optional::stream)
                .toList();
    }

    public int includedCompletedCount() {
        return (int) anchors.stream()
                .filter(BoundaryFitAnchor::included)
                .filter(anchor -> anchor.tissuePreviewPoint().isPresent())
                .count();
    }

    public Optional<BoundaryFitAnchor> activeAnchor() {
        return activeAnchorId.flatMap(identifier -> anchors.stream()
                .filter(anchor -> anchor.id().equals(identifier))
                .findFirst());
    }

    public BoundaryFitDraft withModel(final BoundaryFitModel model) {
        return new BoundaryFitDraft(request.withModel(model), anchors,
                activeAnchorId);
    }

    public BoundaryFitDraft withActiveAnchor(final String identifier) {
        return new BoundaryFitDraft(request, anchors,
                Optional.of(Objects.requireNonNull(identifier,
                        "identifier")));
    }

    /**
     * Clears the active prompt when every usable anchor is complete or
     * excluded. A selected excluded anchor remains part of the immutable
     * draft and can be explicitly restored later.
     */
    public BoundaryFitDraft withoutActiveAnchor() {
        return activeAnchorId.isEmpty()
                ? this : new BoundaryFitDraft(request, anchors,
                        Optional.empty());
    }

    public BoundaryFitDraft withAnchor(final BoundaryFitAnchor replacement) {
        final BoundaryFitAnchor checked = Objects.requireNonNull(
                replacement, "replacement");
        final List<BoundaryFitAnchor> updated = anchors.stream()
                .map(anchor -> anchor.id().equals(checked.id())
                        ? checked : anchor)
                .toList();
        if (updated.equals(anchors)) {
            final boolean known = anchors.stream().anyMatch(
                    anchor -> anchor.id().equals(checked.id()));
            if (!known) {
                throw new IllegalArgumentException(
                        "Unknown boundary-fit anchor: " + checked.id());
            }
            return this;
        }
        return new BoundaryFitDraft(request, updated, activeAnchorId);
    }

    public BoundaryFitDraft selectNextIncompleteAfter(
            final String identifier) {
        int start = -1;
        for (int index = 0; index < anchors.size(); index++) {
            if (anchors.get(index).id().equals(identifier)) {
                start = index;
                break;
            }
        }
        if (start < 0) {
            throw new IllegalArgumentException(
                    "Unknown boundary-fit anchor: " + identifier);
        }
        for (int offset = 1; offset <= anchors.size(); offset++) {
            final BoundaryFitAnchor candidate = anchors.get(
                    (start + offset) % anchors.size());
            if (candidate.included()
                    && candidate.tissuePreviewPoint().isEmpty()) {
                return withActiveAnchor(candidate.id());
            }
        }
        return withoutActiveAnchor();
    }
}
