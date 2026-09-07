package org.atlasalign.application;

import java.util.List;
import java.util.Objects;

/**
 * Visible fail-closed result when no plausible tissue candidate exists.
 */
public final class TissueSegmentationException
        extends IllegalArgumentException {

    private final List<TissueSegmentationCandidateDiagnostic> candidates;
    private final List<String> auditNotes;

    public TissueSegmentationException(
            final String message,
            final List<TissueSegmentationCandidateDiagnostic> candidates) {
        this(message, candidates, List.of());
    }

    public TissueSegmentationException(
            final String message,
            final List<TissueSegmentationCandidateDiagnostic> candidates,
            final List<String> auditNotes) {
        super(Objects.requireNonNull(message, "message"));
        this.candidates = List.copyOf(
                Objects.requireNonNull(candidates, "candidates"));
        this.auditNotes = List.copyOf(
                Objects.requireNonNull(auditNotes, "auditNotes"));
    }

    public List<TissueSegmentationCandidateDiagnostic> candidates() {
        return candidates;
    }

    public List<String> auditNotes() {
        return auditNotes;
    }
}
