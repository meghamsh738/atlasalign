package org.atlasalign.application.c02;

import java.util.OptionalDouble;

/** Raw C02 MIND distance and independently visible support counts. */
public record C02MindScore(
        OptionalDouble meanSquaredDescriptorDifference,
        int completeObservedPixelCount,
        int eligibleCenterCount,
        double featureSupportFraction) {

    public C02MindScore {
        if (meanSquaredDescriptorDifference == null) {
            throw new NullPointerException("meanSquaredDescriptorDifference");
        }
        if (completeObservedPixelCount <= 0
                || eligibleCenterCount < 0
                || eligibleCenterCount > completeObservedPixelCount) {
            throw new IllegalArgumentException(
                    "C02 feature-support counts are inconsistent");
        }
        final double expected = eligibleCenterCount
                / (double) completeObservedPixelCount;
        if (!Double.isFinite(featureSupportFraction)
                || featureSupportFraction < 0
                || featureSupportFraction > 1
                || Math.abs(expected - featureSupportFraction) > 1e-15) {
            throw new IllegalArgumentException(
                    "C02 feature-support fraction does not match counts");
        }
        if (meanSquaredDescriptorDifference.isPresent()) {
            final double value = meanSquaredDescriptorDifference.getAsDouble();
            if (!Double.isFinite(value) || value < 0
                    || eligibleCenterCount == 0) {
                throw new IllegalArgumentException(
                        "C02 descriptor distance must be finite and non-negative");
            }
        } else if (eligibleCenterCount != 0) {
            throw new IllegalArgumentException(
                    "An unavailable C02 score cannot have eligible centers");
        }
    }

    public boolean assessable(final double minimumSupportFraction) {
        if (!Double.isFinite(minimumSupportFraction)
                || minimumSupportFraction < 0
                || minimumSupportFraction > 1) {
            throw new IllegalArgumentException(
                    "Minimum support fraction must be in 0..1");
        }
        return meanSquaredDescriptorDifference.isPresent()
                && featureSupportFraction >= minimumSupportFraction;
    }
}
