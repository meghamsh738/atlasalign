package org.atlasalign.application;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;

/**
 * Builds an evidence-sufficiency report without claiming calibrated accuracy.
 */
public final class ReviewConfidenceEvaluator {

    private static final double ANGLE_COMPARISON_TOLERANCE = 1e-9;

    public ReviewConfidenceReport evaluate(
            final AlignmentReviewState state) {
        final List<ConfidenceEvidence> evidence = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        evidence.add(item(
                ConfidenceMetric.SOURCE_IDENTITY,
                ConfidenceEvidenceStatus.SUPPORTING,
                OptionalDouble.empty(),
                "",
                "Source pixels and metadata matched at review intake."));
        evidence.add(item(
                ConfidenceMetric.ATLAS_ASSET_IDENTITY,
                ConfidenceEvidenceStatus.INFORMATIONAL,
                OptionalDouble.empty(),
                "",
                "Atlas identity is pinned; assets are re-verified at acceptance."));
        addPlaneProvenance(state, evidence, reasons);
        addInputIdentity(state, evidence, reasons);
        addSegmentation(state, evidence);
        final double dice =
                state.basis().proposal().affineDice();
        evidence.add(item(
                ConfidenceMetric.ATLAS_TISSUE_DICE,
                dice == 0
                        ? ConfidenceEvidenceStatus.CAUTION
                        : ConfidenceEvidenceStatus.INFORMATIONAL,
                OptionalDouble.of(dice),
                "Dice coefficient",
                dice == 0
                        ? "Observed tissue and the atlas proposal have zero Dice overlap."
                        : "Raw observed-tissue overlap; no calibrated threshold makes this evidence of accuracy."));
        if (dice == 0) {
            reasons.add(
                    "The current atlas proposal has zero observed-tissue overlap.");
        }
        evidence.add(ConfidenceEvidence.unavailable(
                ConfidenceMetric.BOUNDARY_DISTANCE,
                "A calibrated real-image boundary-distance policy is unavailable."));
        evidence.add(ConfidenceEvidence.unavailable(
                ConfidenceMetric.STRUCTURAL_SIMILARITY,
                "Structural image similarity is not computed in this phase."));
        evidence.add(ConfidenceEvidence.unavailable(
                ConfidenceMetric.VALID_ATLAS_COVERAGE,
                "Atlas coverage is not yet measured by the Phase 5 overlay."));
        evidence.add(item(
                ConfidenceMetric.TRANSFORM_PLAUSIBILITY,
                ConfidenceEvidenceStatus.SUPPORTING,
                OptionalDouble.of(Math.abs(
                        state.effectiveAtlasToPreview().determinant())),
                "absolute determinant",
                "The effective affine is finite, invertible, and within manual bounds."));
        evidence.add(ConfidenceEvidence.unavailable(
                ConfidenceMetric.BEST_SECOND_LEVEL_MARGIN,
                "Nearby atlas levels are not ranked in the current baseline."));
        addModelDisagreement(state, evidence, reasons);
        addOrientation(state, evidence, reasons);
        addDamage(state, evidence, reasons);
        addLandmarks(state, evidence);
        addLocalWarp(state, evidence);
        addSyntheticPolicy(state, evidence, reasons);

        evidence.add(ConfidenceEvidence.unavailable(
                ConfidenceMetric.PARTIAL_CONDITION_SPREAD,
                "Partial-observation spread is available only from a separate validation run."));
        final ConfidenceEvidenceCategory category = classify(state, evidence,
                reasons);
        if (reasons.isEmpty()) {
            reasons.add(switch (category) {
                case AUTOMATIC_CONSISTENT ->
                        "Automatic evidence is internally consistent; visual review is still mandatory.";
                case AUTOMATIC_LIMITED ->
                        "Automatic evidence is available but limited; careful visual review is required.";
                case AUTOMATIC_CONFLICTING ->
                        "Automatic evidence conflicts and must not be treated as a reliable automatic result.";
                case MANUAL_ONLY ->
                        "No verified automatic result is available; this review is manual only.";
                case NOT_ASSESSABLE ->
                        "Available facts cannot support an evidence category.";
            });
        }
        return new ReviewConfidenceReport(
                category, evidence, reasons);
    }

    private static void addPlaneProvenance(
            final AlignmentReviewState state,
            final List<ConfidenceEvidence> evidence,
            final List<String> reasons) {
        if (state.basis().initialPlaneProposal().isEmpty()) {
            evidence.add(ConfidenceEvidence.unavailable(
                    ConfidenceMetric.INITIAL_PLANE_PROVENANCE,
                    "Initial-plane provenance was not supplied."));
            return;
        }
        final InitialPlaneProposal proposal =
                state.basis().initialPlaneProposal().orElseThrow();
        final ConfidenceEvidenceStatus status =
                proposal.source() == InitialPlaneSource.LOCAL_DEEPSLICE
                        ? ConfidenceEvidenceStatus.SUPPORTING
                        : ConfidenceEvidenceStatus.CAUTION;
        evidence.add(item(
                ConfidenceMetric.INITIAL_PLANE_PROVENANCE,
                status,
                OptionalDouble.of(
                        proposal.coronalLevel()
                                .zeroBasedAnteriorPosteriorIndex()),
                "Allen axis-0 index",
                "Initial level source: " + proposal.source() + "."));
        if (proposal.fallbackReason().isPresent()) {
            reasons.add("Initial plane used manual fallback: "
                    + proposal.fallbackReason().orElseThrow() + ".");
        }
    }

    private static void addInputIdentity(
            final AlignmentReviewState state,
            final List<ConfidenceEvidence> evidence,
            final List<String> reasons) {
        final var input = state.basis().deepSliceInputProvenance();
        if (input.isPresent()) {
            final DeepSliceInputProvenance provenance =
                    input.orElseThrow();
            evidence.add(item(
                    ConfidenceMetric.DEEPSLICE_INPUT_IDENTITY,
                    ConfidenceEvidenceStatus.SUPPORTING,
                    OptionalDouble.of(
                            provenance.syntheticPixelFraction()),
                    "synthetic fraction",
                    "Exact DeepSlice input is hashed: condition="
                            + provenance.condition() + "; dimensions="
                            + provenance.width() + "×"
                            + provenance.height() + "; pixel SHA-256="
                            + provenance.pixelsSha256() + "."));
            return;
        }
        final boolean allegedAutomatic = state.basis()
                .initialPlaneProposal()
                .map(proposal -> proposal.source()
                        == InitialPlaneSource.LOCAL_DEEPSLICE)
                .orElse(false);
        evidence.add(allegedAutomatic
                ? item(
                        ConfidenceMetric.DEEPSLICE_INPUT_IDENTITY,
                        ConfidenceEvidenceStatus.CAUTION,
                        OptionalDouble.empty(), "",
                        "An automatic proposal exists but its exact submitted input identity is unavailable.")
                : ConfidenceEvidence.unavailable(
                        ConfidenceMetric.DEEPSLICE_INPUT_IDENTITY,
                        "No automatic inference input was submitted."));
        if (allegedAutomatic) {
            reasons.add(
                    "The exact DeepSlice input identity is unavailable.");
        }
    }

    private static void addSegmentation(
            final AlignmentReviewState state,
            final List<ConfidenceEvidence> evidence) {
        if (state.basis().segmentation().isEmpty()) {
            evidence.add(ConfidenceEvidence.unavailable(
                    ConfidenceMetric.TISSUE_MASK_COMPLETENESS,
                    "Segmentation diagnostics were not supplied."));
            return;
        }
        final TissueSegmentationResult segmentation =
                state.basis().segmentation().orElseThrow();
        evidence.add(item(
                ConfidenceMetric.TISSUE_MASK_COMPLETENESS,
                ConfidenceEvidenceStatus.INFORMATIONAL,
                OptionalDouble.of(
                        segmentation.largestComponentFraction()),
                "foreground fraction in largest component",
                "Raw observed-tissue segmentation completeness diagnostic."));
    }

    private static void addOrientation(
            final AlignmentReviewState state,
            final List<ConfidenceEvidence> evidence,
            final List<String> reasons) {
        final boolean required = state.basis().proposal().geometry()
                .anatomicalLateralityRequiresConfirmation();
        final boolean orientationConfirmed =
                state.content().orientation().confirmed();
        final boolean hemisphereConfirmed =
                state.content().observedHemisphere()
                != ObservedAnatomicalHemisphere.UNSURE;
        final boolean confirmed =
                orientationConfirmed && hemisphereConfirmed;
        final ConfidenceEvidenceStatus status = required
                ? confirmed
                ? ConfidenceEvidenceStatus.SUPPORTING
                : ConfidenceEvidenceStatus.CAUTION
                : ConfidenceEvidenceStatus.INFORMATIONAL;
        evidence.add(item(
                ConfidenceMetric.LATERALITY_ORIENTATION,
                status,
                OptionalDouble.empty(),
                "",
                confirmed
                        ? "Atlas-to-image left/right orientation and observed anatomical hemisphere are explicitly confirmed."
                        : required
                        ? orientationConfirmed
                        ? "Atlas orientation is confirmed, but the observed anatomical hemisphere remains unsure."
                        : "Non-full or sparse bilateral tissue still has unconfirmed anatomical orientation."
                        : "Full-section geometry does not require a hemisphere choice for acceptance."));
        if (status == ConfidenceEvidenceStatus.CAUTION) {
            reasons.add(
                    orientationConfirmed
                            ? "Confirm the observed anatomical hemisphere before accepting this half, partial, or damaged section."
                            : "Confirm atlas left/right orientation before accepting this non-full or sparse bilateral section.");
        }
    }

    private static void addDamage(
            final AlignmentReviewState state,
            final List<ConfidenceEvidence> evidence,
            final List<String> reasons) {
        final SectionGeometry geometry = state.basis().proposal().geometry()
                .geometry();
        final boolean damaged =
                geometry == SectionGeometry.PARTIAL_OR_DAMAGED;
        final boolean bilateralReview =
                geometry == SectionGeometry.BILATERAL_REVIEW_REQUIRED;
        final boolean caution = damaged || bilateralReview;
        evidence.add(item(
                ConfidenceMetric.TISSUE_DAMAGE,
                caution
                        ? ConfidenceEvidenceStatus.CAUTION
                        : ConfidenceEvidenceStatus.INFORMATIONAL,
                OptionalDouble.empty(),
                "",
                damaged
                        ? "Tissue geometry is partial or damaged; overlap is not directly comparable with full sections."
                        : bilateralReview
                        ? "Bilateral tissue has sparse or ambiguous mask support and requires explicit review."
                        : "No partial-or-damaged geometry flag is active."));
        if (caution) {
            reasons.add(
                    damaged
                            ? "Partial or damaged tissue limits comparison with the complete atlas section."
                            : "Sparse bilateral mask support requires explicit orientation confirmation and caution.");
        }
    }

    private static void addLandmarks(
            final AlignmentReviewState state,
            final List<ConfidenceEvidence> evidence) {
        final double fitRms = state.activeFitLandmarkRmsPixels();
        if (Double.isNaN(fitRms)) {
            evidence.add(ConfidenceEvidence.unavailable(
                    ConfidenceMetric.ACTIVE_LANDMARK_RESIDUAL,
                    "No FIT landmarks are active at the current atlas plane."));
        } else {
            evidence.add(item(
                    ConfidenceMetric.ACTIVE_LANDMARK_RESIDUAL,
                    ConfidenceEvidenceStatus.INFORMATIONAL,
                    OptionalDouble.of(fitRms),
                    "preview pixels RMS",
                    "Raw in-sample residual across active exact-plane FIT "
                    + "landmarks. It is a manual-fit diagnostic, not "
                    + "independent supporting evidence; consult the audit "
                    + "trail for MANUAL_LANDMARKS fit provenance."));
        }
        final double checkRms = state.activeCheckLandmarkRmsPixels();
        if (Double.isNaN(checkRms)) {
            evidence.add(ConfidenceEvidence.unavailable(
                    ConfidenceMetric.INDEPENDENT_LANDMARK_RESIDUAL,
                    "No CHECK landmarks are active at the current atlas plane."));
        } else {
            evidence.add(item(
                    ConfidenceMetric.INDEPENDENT_LANDMARK_RESIDUAL,
                    ConfidenceEvidenceStatus.INFORMATIONAL,
                    OptionalDouble.of(checkRms),
                    "preview pixels RMS",
                    "Residual across reviewer-designated CHECK landmarks that "
                    + "were excluded from fitting. This is a useful manual QA "
                    + "diagnostic but is not calibrated accuracy evidence."));
        }
    }

    private static void addLocalWarp(
            final AlignmentReviewState state,
            final List<ConfidenceEvidence> evidence) {
        if (state.content().hemisphereWarp().isPresent()) {
            final ManualHemisphereWarp2D.Diagnostics diagnostics =
                    state.content().hemisphereWarp().orElseThrow()
                            .diagnostics();
            evidence.add(item(
                    ConfidenceMetric.LOCAL_WARP_PLAUSIBILITY,
                    ConfidenceEvidenceStatus.INFORMATIONAL,
                    OptionalDouble.of(
                            diagnostics.minimumJacobianDeterminant()),
                    "minimum sampled Jacobian determinant",
                    String.format(java.util.Locale.ROOT,
                            "REVIEWER_CONTROLLED_MANUAL_WARP passed the "
                            + "local-field distortion safety gates "
                            + "with %d atlas-left and %d atlas-right controls "
                            + "(minimum singular value %.3f; maximum singular "
                            + "value %.3f; maximum anisotropy %.3f; maximum "
                            + "inverse round-trip error %.3g preview pixels; "
                            + "content SHA-256 %s). These sampled diagnostics "
                            + "are reviewer-controlled and never increase "
                            + "automatic confidence.",
                            diagnostics.atlasLeftControlCount(),
                            diagnostics.atlasRightControlCount(),
                            diagnostics.minimumSingularValue(),
                            diagnostics.maximumSingularValue(),
                            diagnostics.maximumAnisotropy(),
                            diagnostics.maximumInverseRoundTripError(),
                            diagnostics.contentSha256())));
            return;
        }
        if (state.content().localWarp().isEmpty()) {
            evidence.add(ConfidenceEvidence.unavailable(
                    ConfidenceMetric.LOCAL_WARP_PLAUSIBILITY,
                    "No reviewer-applied local warp is active."));
            return;
        }
        final ConstrainedLocalWarp2D.Diagnostics diagnostics = state.content()
                .localWarp().orElseThrow().diagnostics();
        evidence.add(item(
                ConfidenceMetric.LOCAL_WARP_PLAUSIBILITY,
                ConfidenceEvidenceStatus.INFORMATIONAL,
                OptionalDouble.of(diagnostics.minimumJacobianDeterminant()),
                "minimum sampled Jacobian determinant",
                String.format(java.util.Locale.ROOT,
                        "MANUAL_LOCAL_WARP passed the frozen topology and "
                        + "distortion bounds (minimum singular value %.3f; "
                        + "maximum singular value %.3f; maximum anisotropy "
                        + "%.3f; content SHA-256 %s). This reviewer-applied "
                        + "diagnostic never increases automatic confidence.",
                        diagnostics.minimumSingularValue(),
                        diagnostics.maximumSingularValue(),
                        diagnostics.maximumAnisotropy(),
                        diagnostics.contentHash())));
    }

    private static void addModelDisagreement(
            final AlignmentReviewState state,
            final List<ConfidenceEvidence> evidence,
            final List<String> reasons) {
        final InitialPlaneProposal proposal = state.basis()
                .initialPlaneProposal().orElse(null);
        if (proposal == null || proposal.prediction().isEmpty()
                || proposal.prediction().orElseThrow().diagnostics().isEmpty()) {
            evidence.add(ConfidenceEvidence.unavailable(
                    ConfidenceMetric.DEEPSLICE_MODEL_DISAGREEMENT,
                    "Primary/secondary DeepSlice diagnostics were not supplied."));
            return;
        }
        final DeepSlicePredictionDiagnostics diagnostics = proposal
                .prediction().orElseThrow().diagnostics().orElseThrow();
        final int ap = diagnostics.anteriorPosteriorDisagreementIndices();
        final double sagittal = diagnostics.sagittalTiltDisagreementDegrees();
        final double horizontal = diagnostics.horizontalTiltDisagreementDegrees();
        final boolean caution = diagnostics.hasPrimarySecondaryCaution();
        evidence.add(item(
                ConfidenceMetric.DEEPSLICE_MODEL_DISAGREEMENT,
                caution ? ConfidenceEvidenceStatus.CAUTION
                        : ConfidenceEvidenceStatus.INFORMATIONAL,
                OptionalDouble.empty(),
                "",
                caution
                        ? "Primary and secondary DeepSlice models disagree beyond the strict review threshold."
                        : "Primary and secondary DeepSlice diagnostics are within the strict review thresholds."));
        if (caution) {
            reasons.add("DeepSlice primary/secondary disagreement requires careful visual review.");
        }
        evidence.add(item(
                ConfidenceMetric.DEEPSLICE_AP_DISAGREEMENT,
                disagreementStatus(ap, 12, 20), OptionalDouble.of(ap),
                "Allen indices", "Absolute primary/secondary AP disagreement."));
        evidence.add(item(
                ConfidenceMetric.DEEPSLICE_SAGITTAL_TILT_DISAGREEMENT,
                disagreementStatus(sagittal, 3, 5), OptionalDouble.of(sagittal),
                "degrees", "Absolute primary/secondary sagittal-tilt disagreement."));
        evidence.add(item(
                ConfidenceMetric.DEEPSLICE_HORIZONTAL_TILT_DISAGREEMENT,
                disagreementStatus(horizontal, 3, 5), OptionalDouble.of(horizontal),
                "degrees", "Absolute primary/secondary horizontal-tilt disagreement."));
        final DeepSlicePhysicalDisagreement physical = diagnostics
                .physicalDisagreement(
                        state.basis().atlas().atlasPlaneWidth(),
                        state.basis().atlas().atlasPlaneHeight(),
                        25);
        evidence.add(item(
                ConfidenceMetric.DEEPSLICE_PHYSICAL_PLANE_DISAGREEMENT,
                ConfidenceEvidenceStatus.INFORMATIONAL,
                OptionalDouble.of(
                        physical.maximumGridDisplacementMicrometers()),
                "µm maximum on 3×3 plane grid",
                String.format(java.util.Locale.ROOT,
                        "Physical primary/secondary plane diagnostic: center AP %.1f µm; normal angle %.3f°; mediolateral gradient %.1f µm/mm; dorsoventral gradient %.1f µm/mm. This is disagreement, not accuracy.",
                        physical.centerAnteriorPosteriorMicrometers(),
                        physical.normalAngleDegrees(),
                        physical.mediolateralGradientMicrometersPerMillimeter(),
                        physical.dorsoventralGradientMicrometersPerMillimeter())));
    }

    private static void addSyntheticPolicy(
            final AlignmentReviewState state,
            final List<ConfidenceEvidence> evidence,
            final List<String> reasons) {
        final SyntheticPixelReviewPolicy policy =
                state.basis().syntheticPixelPolicy();
        final boolean verified =
                policy != SyntheticPixelReviewPolicy.UNVERIFIED;
        evidence.add(item(
                ConfidenceMetric.SYNTHETIC_PIXEL_EXCLUSION,
                verified
                        ? ConfidenceEvidenceStatus.SUPPORTING
                        : ConfidenceEvidenceStatus.CAUTION,
                OptionalDouble.empty(),
                "",
                switch (policy) {
                    case NO_SYNTHETIC_PIXELS ->
                            "No synthetic pixels are present in the review input.";
                    case SYNTHETIC_PIXELS_EXCLUDED ->
                            "Synthetic inference pixels are explicitly excluded from review evidence.";
                    case UNVERIFIED ->
                            "Synthetic-pixel provenance was not verified.";
                }));
        if (!verified) {
            reasons.add(
                    "Synthetic-pixel exclusion must be verified before acceptance.");
        }
    }

    private static ConfidenceEvidence item(
            final ConfidenceMetric metric,
            final ConfidenceEvidenceStatus status,
            final OptionalDouble value,
            final String unit,
            final String explanation) {
        return new ConfidenceEvidence(
                metric, status, value, unit, explanation);
    }

    private static ConfidenceEvidenceStatus disagreementStatus(
            final double value, final double consistentMaximum,
            final double conflictThreshold) {
        return exceedsAngle(value, conflictThreshold)
                ? ConfidenceEvidenceStatus.CAUTION
                : exceedsAngle(value, consistentMaximum)
                ? ConfidenceEvidenceStatus.INFORMATIONAL
                : ConfidenceEvidenceStatus.SUPPORTING;
    }

    private static ConfidenceEvidenceCategory classify(
            final AlignmentReviewState state,
            final List<ConfidenceEvidence> evidence,
            final List<String> reasons) {
        final InitialPlaneProposal proposal = state.basis()
                .initialPlaneProposal().orElse(null);
        if (proposal == null || proposal.source()
                != InitialPlaneSource.LOCAL_DEEPSLICE) {
            return state.content().workflowMode()
                    == ReviewWorkflowMode.MANUAL_ONLY
                    ? ConfidenceEvidenceCategory.MANUAL_ONLY
                    : ConfidenceEvidenceCategory.NOT_ASSESSABLE;
        }
        final DeepSlicePlanePrediction prediction = proposal.prediction()
                .orElse(null);
        if (prediction == null || prediction.diagnostics().isEmpty()
                || prediction.verifiedRuntimeProvenance().isEmpty()) {
            reasons.add("The alleged automatic result lacks normalized diagnostics or verified runtime provenance.");
            return ConfidenceEvidenceCategory.AUTOMATIC_CONFLICTING;
        }
        final DeepSlicePredictionDiagnostics diagnostics = prediction
                .diagnostics().orElseThrow();
        if (diagnostics.hasPrimarySecondaryCaution()
                || state.basis().proposal().affineDice() == 0
                || state.basis().syntheticPixelPolicy()
                == SyntheticPixelReviewPolicy.UNVERIFIED) {
            return ConfidenceEvidenceCategory.AUTOMATIC_CONFLICTING;
        }
        final AutomaticEligibilityStatus eligibility = state.basis()
                .automaticPlaneInitialization().eligibility().status();
        if (eligibility == AutomaticEligibilityStatus.MANUAL_REQUIRED) {
            reasons.add(
                    "The automatic initialization gate requires a manual workflow.");
            return ConfidenceEvidenceCategory.AUTOMATIC_CONFLICTING;
        }
        final SectionGeometry geometry = state.basis().proposal().geometry()
                .geometry();
        final boolean fullObservedSection = geometry == SectionGeometry.FULL
                && state.basis().syntheticPixelPolicy()
                == SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS;
        final boolean limitedDisagreement = diagnostics
                .anteriorPosteriorDisagreementIndices() > 12
                || exceedsAngle(
                diagnostics.sagittalTiltDisagreementDegrees(), 3)
                || exceedsAngle(
                diagnostics.horizontalTiltDisagreementDegrees(), 3);
        final boolean orientationCaution = evidence.stream().anyMatch(item ->
                item.metric() == ConfidenceMetric.LATERALITY_ORIENTATION
                        && item.status() == ConfidenceEvidenceStatus.CAUTION);
        final boolean geometryCaution = evidence.stream().anyMatch(item ->
                item.metric() == ConfidenceMetric.TISSUE_DAMAGE
                        && item.status() == ConfidenceEvidenceStatus.CAUTION);
        if (eligibility != AutomaticEligibilityStatus.ELIGIBLE_FULL
                || !fullObservedSection || limitedDisagreement || orientationCaution
                || geometryCaution) {
            return ConfidenceEvidenceCategory.AUTOMATIC_LIMITED;
        }
        return ConfidenceEvidenceCategory.AUTOMATIC_CONSISTENT;
    }

    private static boolean exceedsAngle(
            final double value, final double threshold) {
        return value - threshold > ANGLE_COMPARISON_TOLERANCE;
    }
}
