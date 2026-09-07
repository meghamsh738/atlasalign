package org.atlasalign.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Initial level proposal that always requires visual review.
 */
public record InitialPlaneProposal(
        AllenCoronalLevel coronalLevel,
        InitialPlaneSource source,
        Optional<DeepSlicePlanePrediction> prediction,
        Optional<ManualFallbackReason> fallbackReason,
        Optional<String> fallbackMessage) {

    public InitialPlaneProposal {
        coronalLevel = Objects.requireNonNull(
                coronalLevel, "coronalLevel");
        source = Objects.requireNonNull(source, "source");
        prediction = Objects.requireNonNull(prediction, "prediction");
        fallbackReason = Objects.requireNonNull(
                fallbackReason, "fallbackReason");
        fallbackMessage = Objects.requireNonNull(
                fallbackMessage, "fallbackMessage");
        final boolean localDeepSlice = source == InitialPlaneSource.LOCAL_DEEPSLICE
                && (prediction.isEmpty()
                || fallbackReason.isPresent()
                || fallbackMessage.isPresent()
                || prediction.get().zeroBasedAnteriorPosteriorIndex()
                != coronalLevel.zeroBasedAnteriorPosteriorIndex());
        final boolean manualFallback = source == InitialPlaneSource.MANUAL_FALLBACK
                && (prediction.isPresent()
                || fallbackReason.isEmpty()
                || fallbackMessage.isEmpty());
        final boolean manualOnly = source == InitialPlaneSource.MANUAL_ONLY
                && (prediction.isPresent()
                || fallbackReason.isPresent()
                || fallbackMessage.isPresent());
        if (localDeepSlice || manualFallback || manualOnly) {
            throw new IllegalArgumentException(
                    "Initial-plane provenance is inconsistent");
        }
    }

    /**
     * Records that the reviewer deliberately opened a manual-only session.
     * This must not be used for failed automatic inference, which is instead
     * represented by {@link InitialPlaneSource#MANUAL_FALLBACK}.
     */
    public static InitialPlaneProposal manualOnly(
            final AllenCoronalLevel fallbackLevel) {
        return new InitialPlaneProposal(
                fallbackLevel,
                InitialPlaneSource.MANUAL_ONLY,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    public boolean requiresUserReview() {
        return true;
    }
}
