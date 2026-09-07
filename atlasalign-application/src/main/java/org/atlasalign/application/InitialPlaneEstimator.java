package org.atlasalign.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Uses a local DeepSlice proposal when available and otherwise preserves the
 * user's manual level.
 */
public final class InitialPlaneEstimator {

    public InitialPlaneProposal estimate(
            final AllenCoronalLevel manualLevel,
            final DeepSliceInput input,
            final Optional<DeepSlicePlaneProvider> provider) {
        Objects.requireNonNull(manualLevel, "manualLevel");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(provider, "provider");
        if (provider.isEmpty()) {
            return fallback(
                    manualLevel,
                    ManualFallbackReason.NOT_CONFIGURED,
                    "Local DeepSlice is not configured");
        }
        try {
            final DeepSlicePlanePrediction prediction =
                    Objects.requireNonNull(
                            provider.orElseThrow().estimate(input),
                            "Local DeepSlice returned no prediction");
            return new InitialPlaneProposal(
                    new AllenCoronalLevel(
                            prediction.zeroBasedAnteriorPosteriorIndex()),
                    InitialPlaneSource.LOCAL_DEEPSLICE,
                    Optional.of(prediction),
                    Optional.empty(),
                    Optional.empty());
        } catch (final DeepSliceUnavailableException error) {
            final String reason = error.getMessage() == null
                    || error.getMessage().isBlank()
                    ? "Local DeepSlice failed safely"
                    : error.getMessage();
            return fallback(manualLevel, error.reason(), reason);
        } catch (final RuntimeException error) {
            final String message = error.getMessage() == null
                    || error.getMessage().isBlank()
                    ? "Local DeepSlice returned invalid output"
                    : error.getMessage();
            return fallback(
                    manualLevel,
                    ManualFallbackReason.INVALID_WORKER_OUTPUT,
                    message);
        }
    }

    private static InitialPlaneProposal fallback(
            final AllenCoronalLevel manualLevel,
            final ManualFallbackReason reason,
            final String message) {
        return new InitialPlaneProposal(
                manualLevel,
                InitialPlaneSource.MANUAL_FALLBACK,
                Optional.empty(),
                Optional.of(reason),
                Optional.of(message));
    }
}
