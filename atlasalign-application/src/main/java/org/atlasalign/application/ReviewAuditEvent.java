package org.atlasalign.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic audit entry with immutable before/after content.
 */
public record ReviewAuditEvent(
        long sequence,
        ReviewOperation operation,
        String description,
        AlignmentReviewContent before,
        AlignmentReviewContent after,
        Optional<ReviewAcceptanceAudit> acceptanceAudit) {

    public ReviewAuditEvent {
        operation = Objects.requireNonNull(operation, "operation");
        description = Objects.requireNonNull(
                description, "description").trim();
        before = Objects.requireNonNull(before, "before");
        after = Objects.requireNonNull(after, "after");
        acceptanceAudit = Objects.requireNonNull(
                acceptanceAudit, "acceptanceAudit");
        if (sequence <= 0 || description.isEmpty()) {
            throw new IllegalArgumentException(
                    "Audit event sequence and description are required");
        }
        if ((operation == ReviewOperation.ACCEPT)
                != acceptanceAudit.isPresent()) {
            throw new IllegalArgumentException(
                    "Only acceptance events carry acceptance audit evidence");
        }
    }
}
