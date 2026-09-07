package org.atlasalign.application;

import java.util.List;
import java.util.Objects;

/**
 * Auditable evidence report with no percentage or probability.
 */
public record ReviewConfidenceReport(
        ConfidenceEvidenceCategory category,
        List<ConfidenceEvidence> evidence,
        List<String> reasons) {

    public ReviewConfidenceReport {
        category = Objects.requireNonNull(category, "category");
        evidence = List.copyOf(Objects.requireNonNull(
                evidence, "evidence"));
        reasons = List.copyOf(Objects.requireNonNull(
                reasons, "reasons"));
        if (evidence.isEmpty()
                || evidence.stream().anyMatch(Objects::isNull)
                || reasons.stream().anyMatch(
                reason -> reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(
                    "Confidence report evidence and reasons are invalid");
        }
    }

    public boolean requiresAcknowledgement() {
        return category
                != ConfidenceEvidenceCategory.AUTOMATIC_CONSISTENT
                || evidence.stream().anyMatch(item ->
                item.status() == ConfidenceEvidenceStatus.CAUTION);
    }
}
