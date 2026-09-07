package org.atlasalign.application;

import java.util.Objects;
import java.util.List;
import org.atlasalign.core.BinaryMask;

/**
 * Auditable preview-only segmentation output.
 */
public record TissueSegmentationResult(
        BinaryMask mask,
        TissuePolarity polarity,
        float threshold,
        double foregroundFraction,
        double largestComponentFraction,
        double borderForegroundFraction,
        TissueSegmentationMethod method,
        float histogramLowerBound,
        float histogramUpperBound,
        boolean percentileWindowFallback,
        double candidateScore,
        List<TissueSegmentationCandidateDiagnostic> candidates,
        List<String> auditNotes,
        BinaryMask supportMask,
        TissueSupportDetectionMethod supportDetectionMethod) {

    public TissueSegmentationResult(
            final BinaryMask mask,
            final TissuePolarity polarity,
            final float threshold,
            final double foregroundFraction,
            final double largestComponentFraction,
            final double borderForegroundFraction,
            final TissueSegmentationMethod method,
            final float histogramLowerBound,
            final float histogramUpperBound,
            final boolean percentileWindowFallback,
            final double candidateScore,
            final List<TissueSegmentationCandidateDiagnostic> candidates,
            final List<String> auditNotes) {
        this(mask, polarity, threshold, foregroundFraction,
                largestComponentFraction, borderForegroundFraction,
                method, histogramLowerBound, histogramUpperBound,
                percentileWindowFallback, candidateScore, candidates,
                auditNotes, mask,
                TissueSupportDetectionMethod.SEGMENTATION_MASK);
    }

    public TissueSegmentationResult {
        mask = Objects.requireNonNull(mask, "mask");
        polarity = Objects.requireNonNull(polarity, "polarity");
        method = Objects.requireNonNull(method, "method");
        candidates = List.copyOf(
                Objects.requireNonNull(candidates, "candidates"));
        auditNotes = List.copyOf(
                Objects.requireNonNull(auditNotes, "auditNotes"));
        supportMask = Objects.requireNonNull(supportMask, "supportMask");
        supportDetectionMethod = Objects.requireNonNull(
                supportDetectionMethod, "supportDetectionMethod");
        if (!Float.isFinite(threshold)
                || !Float.isFinite(histogramLowerBound)
                || !Float.isFinite(histogramUpperBound)
                || histogramUpperBound <= histogramLowerBound
                || !unitInterval(foregroundFraction)
                || !unitInterval(largestComponentFraction)
                || !unitInterval(borderForegroundFraction)
                || !Double.isFinite(candidateScore)
                || candidates.size() < 4
                || candidates.size() > 8
                || supportMask.width() != mask.width()
                || supportMask.height() != mask.height()
                || supportMask.isEmpty()
                || auditNotes.stream().anyMatch(
                        note -> note == null || note.isBlank())) {
            throw new IllegalArgumentException(
                    "Segmentation diagnostics are invalid");
        }
    }

    private static boolean unitInterval(final double value) {
        return Double.isFinite(value) && value >= 0 && value <= 1;
    }
}
