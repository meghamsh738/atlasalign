package org.atlasalign.application;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualSidePlacement2D;
import org.atlasalign.application.manual.ReviewedOutlineTransform2D;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;

/**
 * Immutable user-editable review content.
 */
public record AlignmentReviewContent(
        AllenCoronalLevel coronalLevel,
        AtlasPlaneTilt atlasPlaneTilt,
        ReviewWorkflowMode workflowMode,
        AtlasOrientation orientation,
        ObservedAnatomicalHemisphere observedHemisphere,
        ReviewSectionMode reviewSectionMode,
        ManualSidePlacement2D manualSidePlacement,
        AffineTransform2D manualPreviewAdjustment,
        Optional<ReviewedOutlineTransform2D> outlineWarp,
        boolean outlineAnchorsConfirmed,
        AffineTransform2D postOutlinePreviewAdjustment,
        Optional<ManualHemisphereWarp2D> hemisphereWarp,
        List<LandmarkPair> landmarks,
        Optional<ConstrainedLocalWarp2D> localWarp,
        Optional<ReviewedTissueSupport> reviewedTissueSupport,
        boolean tissueClippingEnabled,
        HalfAtlasCoverage halfAtlasCoverage) {

    /**
     * Compatibility constructor for content created before Half-side
     * coverage became explicit. Legacy Half content retains its historical
     * both-side interpretation; new review creation supplies the strict
     * visible-side default explicitly.
     */
    public AlignmentReviewContent(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final ReviewWorkflowMode workflowMode,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final ReviewSectionMode reviewSectionMode,
            final ManualSidePlacement2D manualSidePlacement,
            final AffineTransform2D manualPreviewAdjustment,
            final Optional<ReviewedOutlineTransform2D> outlineWarp,
            final boolean outlineAnchorsConfirmed,
            final AffineTransform2D postOutlinePreviewAdjustment,
            final Optional<ManualHemisphereWarp2D> hemisphereWarp,
            final List<LandmarkPair> landmarks,
            final Optional<ConstrainedLocalWarp2D> localWarp,
            final Optional<ReviewedTissueSupport> reviewedTissueSupport,
            final boolean tissueClippingEnabled) {
        this(coronalLevel, atlasPlaneTilt, workflowMode, orientation,
                observedHemisphere, reviewSectionMode, manualSidePlacement,
                manualPreviewAdjustment, outlineWarp,
                outlineAnchorsConfirmed, postOutlinePreviewAdjustment,
                hemisphereWarp, landmarks, localWarp, reviewedTissueSupport,
                tissueClippingEnabled,
                reviewSectionMode == ReviewSectionMode.HALF
                        ? HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT
                        : HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
    }

    /** Compatibility constructor for content created before section modes. */
    public AlignmentReviewContent(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final ReviewWorkflowMode workflowMode,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final AffineTransform2D manualPreviewAdjustment,
            final Optional<ReviewedOutlineTransform2D> outlineWarp,
            final boolean outlineAnchorsConfirmed,
            final AffineTransform2D postOutlinePreviewAdjustment,
            final Optional<ManualHemisphereWarp2D> hemisphereWarp,
            final List<LandmarkPair> landmarks,
            final Optional<ConstrainedLocalWarp2D> localWarp) {
        this(coronalLevel, atlasPlaneTilt, workflowMode, orientation,
                observedHemisphere, ReviewSectionMode.FULL,
                ManualSidePlacement2D.identity(),
                manualPreviewAdjustment, outlineWarp,
                outlineAnchorsConfirmed, postOutlinePreviewAdjustment,
                hemisphereWarp, landmarks, localWarp);
    }

    /**
     * Compatibility constructor for content created before reviewer tissue
     * support was persisted. Legacy content has no editable crop footprint and
     * clipping is disabled until a new review proposes one.
     */
    public AlignmentReviewContent(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final ReviewWorkflowMode workflowMode,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final ReviewSectionMode reviewSectionMode,
            final ManualSidePlacement2D manualSidePlacement,
            final AffineTransform2D manualPreviewAdjustment,
            final Optional<ReviewedOutlineTransform2D> outlineWarp,
            final boolean outlineAnchorsConfirmed,
            final AffineTransform2D postOutlinePreviewAdjustment,
            final Optional<ManualHemisphereWarp2D> hemisphereWarp,
            final List<LandmarkPair> landmarks,
            final Optional<ConstrainedLocalWarp2D> localWarp) {
        this(coronalLevel, atlasPlaneTilt, workflowMode, orientation,
                observedHemisphere, reviewSectionMode, manualSidePlacement,
                manualPreviewAdjustment, outlineWarp,
                outlineAnchorsConfirmed, postOutlinePreviewAdjustment,
                hemisphereWarp, landmarks, localWarp, Optional.empty(), false);
    }

    /** Compatibility constructor for content created before hemisphere warping. */
    public AlignmentReviewContent(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final ReviewWorkflowMode workflowMode,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final ReviewSectionMode reviewSectionMode,
            final AffineTransform2D manualPreviewAdjustment,
            final Optional<ReviewedOutlineTransform2D> outlineWarp,
            final boolean outlineAnchorsConfirmed,
            final AffineTransform2D postOutlinePreviewAdjustment,
            final Optional<ManualHemisphereWarp2D> hemisphereWarp,
            final List<LandmarkPair> landmarks,
            final Optional<ConstrainedLocalWarp2D> localWarp) {
        this(coronalLevel, atlasPlaneTilt, workflowMode, orientation,
                observedHemisphere, reviewSectionMode,
                ManualSidePlacement2D.identity(), manualPreviewAdjustment,
                outlineWarp, outlineAnchorsConfirmed,
                postOutlinePreviewAdjustment, hemisphereWarp, landmarks,
                localWarp);
    }

    /** Compatibility constructor for content created before hemisphere warping. */
    public AlignmentReviewContent(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final ReviewWorkflowMode workflowMode,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final ReviewSectionMode reviewSectionMode,
            final AffineTransform2D manualPreviewAdjustment,
            final Optional<? extends ReviewedOutlineTransform2D> outlineWarp,
            final boolean outlineAnchorsConfirmed,
            final AffineTransform2D postOutlinePreviewAdjustment,
            final List<LandmarkPair> landmarks,
            final Optional<ConstrainedLocalWarp2D> localWarp) {
        this(coronalLevel, atlasPlaneTilt, workflowMode, orientation,
                observedHemisphere, reviewSectionMode,
                ManualSidePlacement2D.identity(),
                manualPreviewAdjustment, outlineWarp.map(value -> value),
                outlineAnchorsConfirmed, postOutlinePreviewAdjustment,
                Optional.empty(), landmarks, localWarp);
    }

    /** Compatibility constructor for content created before hemisphere warping. */
    public AlignmentReviewContent(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final ReviewWorkflowMode workflowMode,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final AffineTransform2D manualPreviewAdjustment,
            final Optional<? extends ReviewedOutlineTransform2D> outlineWarp,
            final boolean outlineAnchorsConfirmed,
            final AffineTransform2D postOutlinePreviewAdjustment,
            final List<LandmarkPair> landmarks,
            final Optional<ConstrainedLocalWarp2D> localWarp) {
        this(coronalLevel, atlasPlaneTilt, workflowMode, orientation,
                observedHemisphere, manualPreviewAdjustment,
                outlineWarp.map(value -> value),
                outlineAnchorsConfirmed, postOutlinePreviewAdjustment,
                Optional.empty(), landmarks, localWarp);
    }

    /** Compatibility constructor for content created before outline warping. */
    public AlignmentReviewContent(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final ReviewWorkflowMode workflowMode,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final AffineTransform2D manualPreviewAdjustment,
            final List<LandmarkPair> landmarks,
            final Optional<ConstrainedLocalWarp2D> localWarp) {
        this(coronalLevel, atlasPlaneTilt, workflowMode, orientation,
                observedHemisphere, manualPreviewAdjustment,
                Optional.empty(), false, identityPreviewAdjustment(), landmarks,
                localWarp);
    }

    /** Compatibility constructor for content without a local warp. */
    public AlignmentReviewContent(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final ReviewWorkflowMode workflowMode,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final AffineTransform2D manualPreviewAdjustment,
            final List<LandmarkPair> landmarks) {
        this(coronalLevel, atlasPlaneTilt, workflowMode, orientation,
                observedHemisphere, manualPreviewAdjustment, landmarks,
                Optional.empty());
    }

    /** Backward-compatible axis-aligned constructor for existing callers. */
    public AlignmentReviewContent(
            final AllenCoronalLevel coronalLevel,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final AffineTransform2D manualPreviewAdjustment,
            final List<LandmarkPair> landmarks) {
        this(
                coronalLevel,
                AtlasPlaneTilt.CORONAL,
                ReviewWorkflowMode.MANUAL_REFINEMENT,
                orientation,
                observedHemisphere,
                manualPreviewAdjustment,
                landmarks,
                Optional.empty());
    }

    /** Compatibility constructor for callers that supply an oblique plane. */
    public AlignmentReviewContent(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final AffineTransform2D manualPreviewAdjustment,
            final List<LandmarkPair> landmarks) {
        this(coronalLevel, atlasPlaneTilt,
                ReviewWorkflowMode.MANUAL_REFINEMENT, orientation,
                observedHemisphere, manualPreviewAdjustment, landmarks,
                Optional.empty());
    }

    public AlignmentReviewContent {
        coronalLevel = Objects.requireNonNull(
                coronalLevel, "coronalLevel");
        atlasPlaneTilt = Objects.requireNonNull(
                atlasPlaneTilt, "atlasPlaneTilt");
        workflowMode = Objects.requireNonNull(workflowMode, "workflowMode");
        orientation = Objects.requireNonNull(
                orientation, "orientation");
        observedHemisphere = Objects.requireNonNull(
                observedHemisphere, "observedHemisphere");
        reviewSectionMode = Objects.requireNonNull(
                reviewSectionMode, "reviewSectionMode");
        manualSidePlacement = Objects.requireNonNull(
                manualSidePlacement, "manualSidePlacement");
        if (reviewSectionMode != ReviewSectionMode.DISJOINED
                && !manualSidePlacement.isIdentity()) {
            throw new IllegalArgumentException(
                    "Independent side placement is available only in Disjoined mode");
        }
        manualPreviewAdjustment = Objects.requireNonNull(
                manualPreviewAdjustment, "manualPreviewAdjustment");
        outlineWarp = Objects.requireNonNull(outlineWarp, "outlineWarp");
        postOutlinePreviewAdjustment = Objects.requireNonNull(
                postOutlinePreviewAdjustment,
                "postOutlinePreviewAdjustment");
        hemisphereWarp = Objects.requireNonNull(
                hemisphereWarp, "hemisphereWarp");
        landmarks = List.copyOf(Objects.requireNonNull(
                landmarks, "landmarks"));
        localWarp = Objects.requireNonNull(localWarp, "localWarp");
        reviewedTissueSupport = Objects.requireNonNull(
                reviewedTissueSupport, "reviewedTissueSupport");
        halfAtlasCoverage = Objects.requireNonNull(
                halfAtlasCoverage, "halfAtlasCoverage");
        if (reviewSectionMode != ReviewSectionMode.HALF
                && halfAtlasCoverage.includesOppositeRemnant()) {
            throw new IllegalArgumentException(
                    "Opposite-side remnant coverage is available only in Half mode");
        }
        if (manualPreviewAdjustment.sourceSpace()
                != CoordinateSpace2D.PREVIEW_PIXEL
                || manualPreviewAdjustment.destinationSpace()
                != CoordinateSpace2D.PREVIEW_PIXEL) {
            throw new IllegalArgumentException(
                    "Manual adjustment must map preview pixels to preview pixels");
        }
        if (postOutlinePreviewAdjustment.sourceSpace()
                != CoordinateSpace2D.PREVIEW_PIXEL
                || postOutlinePreviewAdjustment.destinationSpace()
                != CoordinateSpace2D.PREVIEW_PIXEL) {
            throw new IllegalArgumentException(
                    "Post-outline adjustment must map preview pixels to preview pixels");
        }
        if (outlineWarp.isEmpty()
                && !postOutlinePreviewAdjustment.equals(
                identityPreviewAdjustment())) {
            throw new IllegalArgumentException(
                    "A post-outline adjustment requires an outline warp");
        }
        if (outlineWarp.isPresent() != outlineAnchorsConfirmed) {
            throw new IllegalArgumentException(
                    "An outline warp requires explicit semantic-anchor confirmation");
        }
        if (hemisphereWarp.isPresent() && localWarp.isPresent()) {
            throw new IllegalArgumentException(
                    "Hemisphere-local and generic local warps are mutually exclusive");
        }
        if (outlineWarp.orElse(null)
                instanceof BoundaryAuthoritativeTransform2D) {
            if (!postOutlinePreviewAdjustment.equals(
                    identityPreviewAdjustment())) {
                throw new IllegalArgumentException(
                        "The exact reviewed boundary is authoritative; post-outline global adjustment must remain identity");
            }
            if (localWarp.isPresent()) {
                throw new IllegalArgumentException(
                        "The exact reviewed boundary permits only the boundary-pinned side mesh, not a generic local warp");
            }
        }
        final Set<String> identifiers = new HashSet<>();
        if (landmarks.stream().anyMatch(
                landmark -> landmark == null
                || !identifiers.add(landmark.id()))) {
            throw new IllegalArgumentException(
                    "Landmark identifiers must be unique");
        }
        if (landmarks.stream().anyMatch(landmark ->
                landmark.anatomicalHandleMetadata().isPresent())) {
            throw new IllegalArgumentException(
                    "Manual side-warp controls are ManualWarpControl geometry and cannot be stored as LandmarkPair evidence");
        }
        if (tissueClippingEnabled && reviewedTissueSupport.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tissue clipping requires a reviewed tissue support footprint");
        }
    }

    /** Compatibility/readability alias for callers that use the shorter term. */
    public Optional<ReviewedTissueSupport> tissueSupport() {
        return reviewedTissueSupport;
    }

    /** Compatibility/readability alias for View-model code. */
    public boolean clipToTissue() {
        return tissueClippingEnabled;
    }

    public List<LandmarkPair> activeLandmarks() {
        return landmarks.stream()
                .filter(landmark -> landmark.coronalLevel().equals(
                        coronalLevel)
                        && landmark.atlasPlaneTilt().equals(atlasPlaneTilt))
                .toList();
    }

    /** Active exact-plane correspondences permitted to influence a fit. */
    public List<LandmarkPair> activeFitLandmarks() {
        return activeLandmarks().stream()
                .filter(landmark -> landmark.role() == LandmarkRole.FIT)
                .toList();
    }

    /** Active exact-plane correspondences held out from every fit. */
    public List<LandmarkPair> activeCheckLandmarks() {
        return activeLandmarks().stream()
                .filter(landmark -> landmark.role() == LandmarkRole.CHECK)
                .toList();
    }

    /** Active exact-plane FIT controls bound to verified atlas anatomy. */
    public List<LandmarkPair> activeAnatomicalFitLandmarks() {
        return activeFitLandmarks().stream()
                .filter(pair -> pair.anatomicalHandleMetadata().isPresent())
                .toList();
    }

    /** Active exact-plane FIT controls that remain generic landmarks. */
    public List<LandmarkPair> activeGenericFitLandmarks() {
        return activeFitLandmarks().stream()
                .filter(pair -> pair.anatomicalHandleMetadata().isEmpty())
                .toList();
    }

    /**
     * Returns an immutable display/projection copy with the supplied shared
     * hemisphere field. Persistence still goes through {@link ReviewEdit};
     * this copy exists so an audited, not-yet-applied candidate can use the
     * exact same atlas-membership projection as accepted export.
     */
    public AlignmentReviewContent withHemisphereWarpForProjection(
            final Optional<ManualHemisphereWarp2D> candidateWarp) {
        final Optional<ManualHemisphereWarp2D> checked = Objects.requireNonNull(
                candidateWarp, "candidateWarp");
        return new AlignmentReviewContent(
                coronalLevel, atlasPlaneTilt, workflowMode, orientation,
                observedHemisphere, reviewSectionMode, manualSidePlacement,
                manualPreviewAdjustment, outlineWarp,
                outlineAnchorsConfirmed, postOutlinePreviewAdjustment,
                checked, landmarks, localWarp, reviewedTissueSupport,
                tissueClippingEnabled, halfAtlasCoverage);
    }

    public static AffineTransform2D identityPreviewAdjustment() {
        return new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0,
                0, 1, 0);
    }
}
