package org.atlasalign.application;

import java.util.Objects;

/**
 * Records whether the immutable DeepSlice tilt was applied to the initial
 * verified atlas visualization and baseline registration.
 */
public record AutomaticPlaneInitialization(
        AutomaticAlignmentEligibility eligibility,
        AtlasPlaneTilt appliedTilt,
        boolean predictionApplied) {

    public AutomaticPlaneInitialization {
        eligibility = Objects.requireNonNull(eligibility, "eligibility");
        appliedTilt = Objects.requireNonNull(appliedTilt, "appliedTilt");
        if (predictionApplied != eligibility.appliesPredictedPlane()) {
            throw new IllegalArgumentException(
                    "Predicted-plane application must match eligibility");
        }
        if (!predictionApplied && !AtlasPlaneTilt.CORONAL.equals(appliedTilt)) {
            throw new IllegalArgumentException(
                    "Review-only and manual initialization must remain coronal");
        }
    }

    public static AutomaticPlaneInitialization from(
            final InitialPlaneProposal proposal,
            final AutomaticAlignmentEligibility eligibility) {
        Objects.requireNonNull(proposal, "proposal");
        final AutomaticAlignmentEligibility checked = Objects.requireNonNull(
                eligibility, "eligibility");
        if (!checked.appliesPredictedPlane()) {
            return new AutomaticPlaneInitialization(
                    checked, AtlasPlaneTilt.CORONAL, false);
        }
        final DeepSlicePlanePrediction prediction = proposal.prediction()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Eligible automatic initialization needs a prediction"));
        return new AutomaticPlaneInitialization(
                checked,
                new AtlasPlaneTilt(
                        prediction.sagittalTiltDegrees(),
                        prediction.horizontalTiltDegrees()),
                true);
    }
}
