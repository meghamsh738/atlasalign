package org.atlasalign.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Deterministic gate for applying a DeepSlice proposal as an automatic full
 * section review initialization. The gate does not claim calibrated accuracy.
 */
public record AutomaticAlignmentEligibility(
        AutomaticEligibilityStatus status,
        List<String> reasons) {

    private static final double ANGLE_COMPARISON_TOLERANCE = 1e-9;

    public AutomaticAlignmentEligibility {
        status = Objects.requireNonNull(status, "status");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (reasons.isEmpty() || reasons.stream().anyMatch(
                reason -> reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(
                    "Automatic eligibility requires explicit reasons");
        }
    }

    public static AutomaticAlignmentEligibility evaluate(
            final InitialPlaneProposal proposal,
            final SectionGeometry geometry,
            final SyntheticPixelReviewPolicy syntheticPixelPolicy,
            final Optional<DeepSliceInputProvenance> inputProvenance,
            final ReviewPreviewDimensions originalPreview) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(syntheticPixelPolicy, "syntheticPixelPolicy");
        Objects.requireNonNull(inputProvenance, "inputProvenance");
        Objects.requireNonNull(originalPreview, "originalPreview");

        if (proposal.source() != InitialPlaneSource.LOCAL_DEEPSLICE) {
            return new AutomaticAlignmentEligibility(
                    AutomaticEligibilityStatus.MANUAL_REQUIRED,
                    List.of("No verified local DeepSlice proposal is available."));
        }
        final DeepSlicePlanePrediction prediction = proposal.prediction()
                .orElse(null);
        if (prediction == null || prediction.diagnostics().isEmpty()
                || prediction.verifiedRuntimeProvenance().isEmpty()) {
            return new AutomaticAlignmentEligibility(
                    AutomaticEligibilityStatus.MANUAL_REQUIRED,
                    List.of("The automatic proposal lacks normalized diagnostics or verified runtime provenance."));
        }

        final List<String> limitations = new ArrayList<>();
        if (geometry != SectionGeometry.FULL) {
            limitations.add("Only source-classified complete sections are eligible for automatic plane initialization.");
        }
        if (syntheticPixelPolicy
                != SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS) {
            limitations.add("Synthetic inference pixels restrict this proposal to review-only use.");
        }
        if (inputProvenance.isEmpty()) {
            limitations.add("The exact DeepSlice input identity is unavailable.");
        } else {
            final DeepSliceInputProvenance input =
                    inputProvenance.orElseThrow();
            if (input.containsSyntheticPixels()) {
                limitations.add("The exact DeepSlice input contains synthetic pixels.");
            }
            if (input.condition()
                    != DeepSliceInputCondition.ORIGINAL_PREVIEW) {
                limitations.add("Only the unchanged full preview input condition is eligible.");
            }
            if (input.preparationIdentitySha256().isPresent()) {
                limitations.add("A derived preparation identity restricts the result to review-only use.");
            }
            if (!DeepSliceInputProvenance.ALGORITHM_REVISION.equals(
                    input.algorithmRevision())) {
                limitations.add("The DeepSlice input identity algorithm revision is not recognized.");
            }
            if (input.width() != originalPreview.width()
                    || input.height() != originalPreview.height()) {
                limitations.add("The submitted DeepSlice input dimensions do not match the safe review preview.");
            }
            if (originalPreview.pixelsSha256().isEmpty()
                    || !originalPreview.pixelsSha256().orElseThrow()
                    .equals(input.pixelsSha256())) {
                limitations.add("The submitted DeepSlice pixel hash does not match the independently captured safe review preview.");
            }
            final String emptyMaskSha256 =
                    VirtualHalfPayloadHashes.syntheticMaskSha256(
                            org.atlasalign.core.BinaryMask.empty(
                                    input.width(), input.height()));
            if (!emptyMaskSha256.equals(input.syntheticMaskSha256())) {
                limitations.add("The claimed original preview has a non-empty or mismatched synthetic-pixel mask identity.");
            }
        }
        final DeepSlicePredictionDiagnostics diagnostics = prediction
                .diagnostics().orElseThrow();
        if (diagnostics.anteriorPosteriorDisagreementIndices() > 12) {
            limitations.add("Primary/secondary AP disagreement exceeds 12 Allen indices.");
        }
        if (exceedsAngle(
                diagnostics.sagittalTiltDisagreementDegrees(), 3)) {
            limitations.add("Primary/secondary sagittal-tilt disagreement exceeds 3 degrees.");
        }
        if (exceedsAngle(
                diagnostics.horizontalTiltDisagreementDegrees(), 3)) {
            limitations.add("Primary/secondary horizontal-tilt disagreement exceeds 3 degrees.");
        }
        if (!limitations.isEmpty()) {
            return new AutomaticAlignmentEligibility(
                    AutomaticEligibilityStatus.REVIEW_ONLY,
                    limitations);
        }
        return new AutomaticAlignmentEligibility(
                AutomaticEligibilityStatus.ELIGIBLE_FULL,
                List.of("Verified complete, non-synthetic input with internally consistent primary/secondary predictions."));
    }

    public boolean appliesPredictedPlane() {
        return status == AutomaticEligibilityStatus.ELIGIBLE_FULL;
    }

    private static boolean exceedsAngle(
            final double value, final double threshold) {
        return value - threshold > ANGLE_COMPARISON_TOLERANCE;
    }
}
