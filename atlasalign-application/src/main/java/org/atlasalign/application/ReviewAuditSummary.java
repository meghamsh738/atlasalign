package org.atlasalign.application;

import java.util.Objects;
import java.util.Optional;

/** Persisted audit evidence without geometry snapshots or an undo timeline. */
public record ReviewAuditSummary(long sequence, ReviewOperation operation,
        String description, Optional<ReviewAcceptanceAudit> acceptanceAudit) {
    public ReviewAuditSummary {
        Objects.requireNonNull(operation, "operation");
        description = Objects.requireNonNull(description, "description").trim();
        acceptanceAudit = Objects.requireNonNull(acceptanceAudit, "acceptanceAudit");
        if (sequence < 1 || description.isEmpty()
                || (operation == ReviewOperation.ACCEPT) != acceptanceAudit.isPresent()) {
            throw new IllegalArgumentException("Invalid persisted review audit");
        }
    }
    public static ReviewAuditSummary from(final ReviewAuditEvent event) {
        return new ReviewAuditSummary(event.sequence(), event.operation(),
                event.description(), event.acceptanceAudit());
    }
}
