package org.atlasalign.application;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Immutable identity of the exact application-side raster supplied to the
 * DeepSlice provider. It describes inference memory only and never the source
 * image object.
 */
public record DeepSliceInputProvenance(
        String algorithmRevision,
        DeepSliceInputCondition condition,
        int width,
        int height,
        String pixelsSha256,
        String syntheticMaskSha256,
        long syntheticPixelCount,
        double syntheticPixelFraction,
        OptionalDouble observedTissueFraction,
        Optional<String> preparationIdentitySha256) {

    public static final String ALGORITHM_REVISION =
            "phase5-deepslice-input-identity-r1";

    public DeepSliceInputProvenance {
        algorithmRevision = requireText(
                algorithmRevision, "algorithmRevision");
        condition = Objects.requireNonNull(condition, "condition");
        pixelsSha256 = requireSha256(pixelsSha256, "pixelsSha256");
        syntheticMaskSha256 = requireSha256(
                syntheticMaskSha256, "syntheticMaskSha256");
        observedTissueFraction = Objects.requireNonNull(
                observedTissueFraction, "observedTissueFraction");
        preparationIdentitySha256 = Objects.requireNonNull(
                preparationIdentitySha256,
                "preparationIdentitySha256");
        final long pixelCount = Math.multiplyExact((long) width, height);
        if (width <= 0 || height <= 0 || syntheticPixelCount < 0
                || syntheticPixelCount > pixelCount
                || !Double.isFinite(syntheticPixelFraction)
                || syntheticPixelFraction < 0
                || syntheticPixelFraction > 1
                || Double.doubleToLongBits(syntheticPixelFraction)
                != Double.doubleToLongBits(
                syntheticPixelCount / (double) pixelCount)) {
            throw new IllegalArgumentException(
                    "DeepSlice input provenance geometry is inconsistent");
        }
        observedTissueFraction.ifPresent(value -> {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException(
                        "Observed-tissue fraction must be within [0, 1]");
            }
        });
        preparationIdentitySha256.ifPresent(value ->
                requireSha256(value, "preparationIdentitySha256"));
    }

    public static DeepSliceInputProvenance capture(
            final DeepSliceInput input,
            final DeepSliceInputCondition condition,
            final OptionalDouble observedTissueFraction,
            final Optional<String> preparationIdentitySha256) {
        final DeepSliceInput checked = Objects.requireNonNull(input, "input");
        long syntheticPixels = 0;
        for (int y = 0; y < checked.height(); y++) {
            for (int x = 0; x < checked.width(); x++) {
                if (checked.syntheticPixelMask().contains(x, y)) {
                    syntheticPixels++;
                }
            }
        }
        final long pixelCount = Math.multiplyExact(
                (long) checked.width(), checked.height());
        return new DeepSliceInputProvenance(
                ALGORITHM_REVISION,
                condition,
                checked.width(),
                checked.height(),
                VirtualHalfPayloadHashes.pixelsSha256(checked.pixels()),
                VirtualHalfPayloadHashes.syntheticMaskSha256(
                        checked.syntheticPixelMask()),
                syntheticPixels,
                syntheticPixels / (double) pixelCount,
                Objects.requireNonNull(
                        observedTissueFraction, "observedTissueFraction"),
                Objects.requireNonNull(
                        preparationIdentitySha256,
                        "preparationIdentitySha256"));
    }

    public boolean containsSyntheticPixels() {
        return syntheticPixelCount > 0;
    }

    private static String requireSha256(
            final String value, final String name) {
        final String checked = requireText(value, name);
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be a lowercase SHA-256 value");
        }
        return checked;
    }

    private static String requireText(
            final String value, final String name) {
        final String checked = Objects.requireNonNull(value, name);
        if (checked.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
