package org.atlasalign.plugin.review;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.manual.BoundaryFitCandidate;
import org.atlasalign.application.manual.BoundaryFitDraft;
import org.atlasalign.application.manual.BoundaryFitPreview;

/** Transient UI state for one unapplied assisted boundary fit. */
public record BoundaryFitViewState(
        boolean loading,
        Optional<BoundaryFitDraft> draft,
        Optional<BoundaryFitPreview> preview,
        Optional<BoundaryFitCandidate> candidate,
        Optional<String> message,
        boolean refitSuggested) {

    public BoundaryFitViewState {
        draft = Objects.requireNonNull(draft, "draft");
        preview = Objects.requireNonNull(preview, "preview");
        candidate = Objects.requireNonNull(candidate, "candidate");
        message = Objects.requireNonNull(message, "message")
                .map(String::trim).filter(value -> !value.isEmpty());
        if ((preview.isPresent() || candidate.isPresent())
                && draft.isEmpty()) {
            throw new IllegalArgumentException(
                    "A boundary-fit preview or candidate requires its editable draft");
        }
    }

    public static BoundaryFitViewState inactive() {
        return new BoundaryFitViewState(false, Optional.empty(),
                Optional.empty(), Optional.empty(),
                Optional.empty(), false);
    }

    public boolean active() {
        return loading || draft.isPresent();
    }
}
