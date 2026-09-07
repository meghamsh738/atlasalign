package org.atlasalign.application.c01;

import java.util.OptionalDouble;

/** Raw C01 score and its independently visible feature-support counts. */
public record C01GradientOrientationScore(
        OptionalDouble orientationAgreement,
        int internalPixelCount,
        int eligibleGradientPixelCount,
        int scoredFeaturePixelCount,
        double featureSupportFraction) {

    public C01GradientOrientationScore {
        if (orientationAgreement == null) {
            throw new NullPointerException("orientationAgreement");
        }
        if (internalPixelCount < 0
                || eligibleGradientPixelCount < 0
                || scoredFeaturePixelCount < 0
                || eligibleGradientPixelCount > internalPixelCount
                || scoredFeaturePixelCount > eligibleGradientPixelCount) {
            throw new IllegalArgumentException(
                    "C01 feature-support counts are inconsistent");
        }
        if (!Double.isFinite(featureSupportFraction)
                || featureSupportFraction < 0
                || featureSupportFraction > 1) {
            throw new IllegalArgumentException(
                    "C01 feature-support fraction must be in 0..1");
        }
        final double expected = internalPixelCount == 0
                ? 0 : scoredFeaturePixelCount
                / (double) internalPixelCount;
        if (Math.abs(expected - featureSupportFraction) > 1e-15) {
            throw new IllegalArgumentException(
                    "C01 feature-support fraction does not match counts");
        }
        if (orientationAgreement.isPresent()) {
            final double value = orientationAgreement.getAsDouble();
            if (!Double.isFinite(value) || value < 0 || value > 1
                    || scoredFeaturePixelCount == 0) {
                throw new IllegalArgumentException(
                        "C01 orientation agreement must be a finite 0..1 "
                                + "value backed by scored pixels");
            }
        } else if (scoredFeaturePixelCount != 0) {
            throw new IllegalArgumentException(
                    "An unavailable C01 score cannot have scored pixels");
        }
    }

    public boolean assessable(final double minimumSupportFraction) {
        if (!Double.isFinite(minimumSupportFraction)
                || minimumSupportFraction < 0
                || minimumSupportFraction > 1) {
            throw new IllegalArgumentException(
                    "Minimum support fraction must be in 0..1");
        }
        return orientationAgreement.isPresent()
                && featureSupportFraction >= minimumSupportFraction;
    }
}
