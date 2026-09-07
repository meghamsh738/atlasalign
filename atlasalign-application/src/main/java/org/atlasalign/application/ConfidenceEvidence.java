package org.atlasalign.application;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * One raw, named and auditable confidence input.
 */
public record ConfidenceEvidence(
        ConfidenceMetric metric,
        ConfidenceEvidenceStatus status,
        OptionalDouble value,
        String unit,
        String explanation) {

    public ConfidenceEvidence {
        metric = Objects.requireNonNull(metric, "metric");
        status = Objects.requireNonNull(status, "status");
        value = Objects.requireNonNull(value, "value");
        unit = Objects.requireNonNull(unit, "unit").trim();
        explanation = Objects.requireNonNull(
                explanation, "explanation").trim();
        if (value.isPresent()
                && !Double.isFinite(value.getAsDouble())
                || explanation.isEmpty()) {
            throw new IllegalArgumentException(
                    "Confidence evidence must be finite and explained");
        }
    }

    public static ConfidenceEvidence unavailable(
            final ConfidenceMetric metric,
            final String explanation) {
        return new ConfidenceEvidence(
                metric,
                ConfidenceEvidenceStatus.UNAVAILABLE,
                OptionalDouble.empty(),
                "",
                explanation);
    }
}
