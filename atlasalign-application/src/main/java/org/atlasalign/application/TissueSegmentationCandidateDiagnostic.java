package org.atlasalign.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable evidence for one threshold/polarity candidate.
 */
public record TissueSegmentationCandidateDiagnostic(
        TissueSegmentationMethod method,
        TissuePolarity polarity,
        float threshold,
        Optional<Float> companionThreshold,
        float histogramLowerBound,
        float histogramUpperBound,
        boolean percentileWindowFallback,
        double foregroundFraction,
        double largestComponentFraction,
        double borderForegroundFraction,
        Optional<Double> exteriorBackgroundFraction,
        Optional<Double> exteriorBackgroundBorderFraction,
        double score,
        Optional<String> rejectionReason) {

    public TissueSegmentationCandidateDiagnostic {
        method = Objects.requireNonNull(method, "method");
        polarity = Objects.requireNonNull(polarity, "polarity");
        companionThreshold = Objects.requireNonNull(
                companionThreshold, "companionThreshold");
        exteriorBackgroundFraction = Objects.requireNonNull(
                exteriorBackgroundFraction, "exteriorBackgroundFraction");
        exteriorBackgroundBorderFraction = Objects.requireNonNull(
                exteriorBackgroundBorderFraction,
                "exteriorBackgroundBorderFraction");
        rejectionReason = Objects.requireNonNull(
                rejectionReason, "rejectionReason")
                .map(String::trim)
                .filter(value -> !value.isEmpty());
        if (!Float.isFinite(threshold)
                || companionThreshold.stream()
                        .anyMatch(value -> !Float.isFinite(value))
                || !Float.isFinite(histogramLowerBound)
                || !Float.isFinite(histogramUpperBound)
                || histogramUpperBound <= histogramLowerBound
                || !unitInterval(foregroundFraction)
                || !unitInterval(largestComponentFraction)
                || !unitInterval(borderForegroundFraction)
                || exteriorBackgroundFraction.stream()
                        .anyMatch(value -> !unitInterval(value))
                || exteriorBackgroundBorderFraction.stream()
                        .anyMatch(value -> !unitInterval(value))
                || exteriorBackgroundFraction.isPresent()
                        != exteriorBackgroundBorderFraction.isPresent()
                || !Double.isFinite(score)) {
            throw new IllegalArgumentException(
                    "Segmentation candidate diagnostics are invalid");
        }
    }

    public boolean valid() {
        return rejectionReason.isEmpty();
    }

    private static boolean unitInterval(final double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
