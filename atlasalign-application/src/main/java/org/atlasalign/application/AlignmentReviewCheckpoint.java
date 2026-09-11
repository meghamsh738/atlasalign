package org.atlasalign.application;

import java.util.List;
import java.util.Objects;

/** Complete committed geometry and audit summaries; never transient or undo state. */
public record AlignmentReviewCheckpoint(AlignmentReviewBasis basis,
        AlignmentReviewContent initialContent, AlignmentReviewContent content,
        boolean adjustedInitialPlacement, long contentRevision,
        List<ReviewAuditSummary> auditHistory) {
    public AlignmentReviewCheckpoint {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(initialContent, "initialContent");
        Objects.requireNonNull(content, "content");
        if (contentRevision < 0) throw new IllegalArgumentException("Negative review revision");
        auditHistory = List.copyOf(auditHistory);
        long previous = 0;
        for (final ReviewAuditSummary event : auditHistory) {
            if (event.sequence() <= previous) throw new IllegalArgumentException("Review audit sequence is not increasing");
            previous = event.sequence();
        }
        new AlignmentReviewState(basis, initialContent, 0);
        new AlignmentReviewState(basis, content, contentRevision);
    }
}
