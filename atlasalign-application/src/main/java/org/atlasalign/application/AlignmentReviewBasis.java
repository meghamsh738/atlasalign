package org.atlasalign.application;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.core.SourceImageSnapshot;

/**
 * Immutable scientific inputs captured when alignment review begins.
 */
public record AlignmentReviewBasis(
        BaselineRegistrationProposal proposal,
        Optional<InitialPlaneProposal> initialPlaneProposal,
        Optional<TissueSegmentationResult> segmentation,
        SourceImageSnapshot sourceSnapshot,
        AtlasReviewProvenance atlas,
        SyntheticPixelReviewPolicy syntheticPixelPolicy,
        Optional<VirtualHalfPreparationProvenance>
                inferencePreparationProvenance,
        ReviewPreviewDimensions previewDimensions,
        Optional<DeepSliceInputProvenance> deepSliceInputProvenance,
        AutomaticPlaneInitialization automaticPlaneInitialization) {

    /**
     * Compatibility constructor for callers without automatic-input details.
     * Such callers remain on a coronal, review-only initialization.
     */
    public AlignmentReviewBasis(
            final BaselineRegistrationProposal proposal,
            final Optional<InitialPlaneProposal> initialPlaneProposal,
            final Optional<TissueSegmentationResult> segmentation,
            final SourceImageSnapshot sourceSnapshot,
            final AtlasReviewProvenance atlas,
            final SyntheticPixelReviewPolicy syntheticPixelPolicy,
            final Optional<VirtualHalfPreparationProvenance>
                    inferencePreparationProvenance,
            final ReviewPreviewDimensions previewDimensions) {
        this(
                proposal,
                initialPlaneProposal,
                segmentation,
                sourceSnapshot,
                atlas,
                syntheticPixelPolicy,
                inferencePreparationProvenance,
                previewDimensions,
                Optional.empty(),
                compatibilityInitialization(initialPlaneProposal));
    }

    public AlignmentReviewBasis {
        proposal = Objects.requireNonNull(proposal, "proposal");
        initialPlaneProposal = Objects.requireNonNull(
                initialPlaneProposal, "initialPlaneProposal");
        segmentation = Objects.requireNonNull(
                segmentation, "segmentation");
        sourceSnapshot = Objects.requireNonNull(
                sourceSnapshot, "sourceSnapshot");
        atlas = Objects.requireNonNull(atlas, "atlas");
        syntheticPixelPolicy = Objects.requireNonNull(
                syntheticPixelPolicy, "syntheticPixelPolicy");
        inferencePreparationProvenance = Objects.requireNonNull(
                inferencePreparationProvenance,
                "inferencePreparationProvenance");
        previewDimensions = Objects.requireNonNull(
                previewDimensions, "previewDimensions");
        deepSliceInputProvenance = Objects.requireNonNull(
                deepSliceInputProvenance, "deepSliceInputProvenance");
        automaticPlaneInitialization = Objects.requireNonNull(
                automaticPlaneInitialization,
                "automaticPlaneInitialization");
        if (initialPlaneProposal.isPresent()
                && !initialPlaneProposal.get().coronalLevel().equals(
                proposal.coronalLevel())) {
            throw new IllegalArgumentException(
                    "Initial-plane and registration levels must agree");
        }
        final boolean hasInferencePreparation =
                inferencePreparationProvenance.isPresent();
        if ((syntheticPixelPolicy
                == SyntheticPixelReviewPolicy.SYNTHETIC_PIXELS_EXCLUDED)
                != hasInferencePreparation) {
            throw new IllegalArgumentException(
                    "Synthetic-pixel review policy and preparation provenance must agree");
        }
        if (hasInferencePreparation && inferencePreparationProvenance.get()
                .observedGeometry() != proposal.geometry().geometry()) {
            throw new IllegalArgumentException(
                    "Preparation provenance geometry must match review geometry");
        }
        if (automaticPlaneInitialization.predictionApplied()) {
            final InitialPlaneProposal initial = initialPlaneProposal
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Applied predicted plane requires initial-plane provenance"));
            final DeepSlicePlanePrediction prediction = initial.prediction()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Applied predicted plane requires DeepSlice prediction"));
            final AtlasPlaneTilt expected = new AtlasPlaneTilt(
                    prediction.sagittalTiltDegrees(),
                    prediction.horizontalTiltDegrees());
            if (!expected.equals(
                    automaticPlaneInitialization.appliedTilt())) {
                throw new IllegalArgumentException(
                        "Applied atlas tilt must exactly match the immutable DeepSlice ensemble prediction");
            }
            final AutomaticAlignmentEligibility recomputed =
                    AutomaticAlignmentEligibility.evaluate(
                            initial,
                            proposal.geometry().geometry(),
                            syntheticPixelPolicy,
                            deepSliceInputProvenance,
                            previewDimensions);
            if (recomputed.status()
                    != AutomaticEligibilityStatus.ELIGIBLE_FULL) {
                throw new IllegalArgumentException(
                        "Predicted atlas tilt may be applied only when the review basis independently recomputes ELIGIBLE_FULL");
            }
        }
    }

    /**
     * Rejects a landmark that is outside either visible pixel-center domain.
     * Atlas points use the verified Allen plane dimensions; tissue points use
     * the separately allocated review preview dimensions.
     */
    public void requireValidLandmark(final LandmarkPair landmark) {
        final LandmarkPair checked = Objects.requireNonNull(
                landmark, "landmark");
        requireAtlasPoint(checked.atlasPoint());
        previewDimensions.requireContains(
                checked.previewPoint(), "Tissue landmark point");
    }

    public boolean hasValidLandmark(final LandmarkPair landmark) {
        try {
            requireValidLandmark(landmark);
            return true;
        } catch (final IllegalArgumentException ignored) {
            return false;
        }
    }

    public AlignmentReviewContent initialContent() {
        final SectionGeometry geometry = proposal.geometry().geometry();
        final ReviewSectionMode sectionMode = geometry
                == SectionGeometry.IMAGE_LEFT_HALF
                || geometry == SectionGeometry.IMAGE_RIGHT_HALF
                ? ReviewSectionMode.HALF : ReviewSectionMode.FULL;
        final Optional<ReviewedTissueSupport> tissueSupport = segmentation
                .filter(result -> !result.supportMask().isEmpty())
                .map(result -> ReviewedTissueSupport.fromMask(
                        result.supportMask()));
        return new AlignmentReviewContent(
                proposal.coronalLevel(),
                automaticPlaneInitialization.appliedTilt(),
                initialPlaneProposal.isPresent()
                        && initialPlaneProposal.orElseThrow().source()
                        == InitialPlaneSource.LOCAL_DEEPSLICE
                        ? ReviewWorkflowMode.AUTOMATIC_REVIEW
                        : ReviewWorkflowMode.MANUAL_ONLY,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                isBilateral(geometry)
                        ? ObservedAnatomicalHemisphere.BOTH
                        : ObservedAnatomicalHemisphere.UNSURE,
                sectionMode,
                org.atlasalign.application.manual.ManualSidePlacement2D.identity(),
                identityPreviewAdjustment(),
                java.util.Optional.empty(), false,
                AlignmentReviewContent.identityPreviewAdjustment(),
                java.util.Optional.empty(), java.util.List.of(),
                java.util.Optional.empty(), tissueSupport,
                tissueSupport.isPresent()
                        && sectionMode.clipsToTissueByDefault(),
                HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
    }

    /** Exact source-to-preview pixel-center mapping used by acceptance/export. */
    public PreviewMapping previewMapping() {
        return new PreviewMapping(
                sourceSnapshot.metadata().width(),
                sourceSnapshot.metadata().height(),
                previewDimensions.width(),
                previewDimensions.height());
    }

    private static AutomaticPlaneInitialization compatibilityInitialization(
            final Optional<InitialPlaneProposal> initialPlaneProposal) {
        Objects.requireNonNull(
                initialPlaneProposal, "initialPlaneProposal");
        final AutomaticAlignmentEligibility eligibility =
                new AutomaticAlignmentEligibility(
                        AutomaticEligibilityStatus.REVIEW_ONLY,
                        java.util.List.of(
                                "Automatic plane initialization provenance was not supplied."));
        return new AutomaticPlaneInitialization(
                eligibility, AtlasPlaneTilt.CORONAL, false);
    }

    private static boolean isBilateral(final SectionGeometry geometry) {
        return geometry == SectionGeometry.FULL
                || geometry
                == SectionGeometry.BILATERAL_REVIEW_REQUIRED;
    }

    private static AffineTransform2D identityPreviewAdjustment() {
        return new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0,
                0, 1, 0);
    }

    public void requireAtlasPoint(final org.atlasalign.core.Point2D point) {
        Objects.requireNonNull(point, "atlasPoint");
        if (!Double.isFinite(point.x()) || !Double.isFinite(point.y())
                || point.x() < 0 || point.x() > atlas.atlasPlaneWidth() - 1.0
                || point.y() < 0 || point.y() > atlas.atlasPlaneHeight() - 1.0) {
            throw new IllegalArgumentException(
                    "Atlas landmark point must lie inside the verified Allen "
                    + "pixel-center domain [0, "
                    + (atlas.atlasPlaneWidth() - 1) + "] × [0, "
                    + (atlas.atlasPlaneHeight() - 1) + "]");
        }
    }

}
