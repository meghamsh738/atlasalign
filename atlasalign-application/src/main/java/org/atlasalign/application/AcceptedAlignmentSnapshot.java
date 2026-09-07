package org.atlasalign.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualSidePlacement2D;
import org.atlasalign.application.manual.ReviewedOutlineTransform2D;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.core.SourceImageSnapshot;

/**
 * Immutable result created only by explicit acceptance.
 */
public record AcceptedAlignmentSnapshot(
        AllenCoronalLevel coronalLevel,
        AtlasPlaneTilt atlasPlaneTilt,
        AffineTransform2D preOutlineAtlasToPreview,
        boolean joinedManualPlacementApplied,
        Optional<ReviewedOutlineTransform2D> outlineWarp,
        boolean outlineAnchorsConfirmed,
        AffineTransform2D postOutlinePreviewAdjustment,
        Optional<ManualHemisphereWarp2D> hemisphereWarp,
        Optional<ConstrainedLocalWarp2D> localWarp,
        AtlasOrientation orientation,
        ObservedAnatomicalHemisphere observedHemisphere,
        ReviewSectionMode reviewSectionMode,
        ManualSidePlacement2D manualSidePlacement,
        List<LandmarkPair> activeLandmarks,
        ReviewConfidenceReport confidence,
        SourceImageSnapshot verifiedSource,
        AtlasReviewProvenance verifiedAtlas,
        ReviewWorkflowMode workflowMode,
        Optional<InitialPlaneProposal> immutableAutomaticProposal,
        boolean warningsAcknowledged,
        long contentRevision,
        long acceptanceAuditSequence,
        PreviewMapping previewMapping,
        Optional<ReviewedTissueSupport> reviewedTissueSupport,
        boolean tissueClippingEnabled,
        HalfAtlasCoverage halfAtlasCoverage)
        implements AtlasMembershipProjection {

    /**
     * Compatibility constructor for accepted snapshots created before Half
     * atlas coverage was explicit. Legacy Half snapshots preserve their
     * historical both-side interpretation.
     */
    public AcceptedAlignmentSnapshot(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final AffineTransform2D preOutlineAtlasToPreview,
            final boolean joinedManualPlacementApplied,
            final Optional<ReviewedOutlineTransform2D> outlineWarp,
            final boolean outlineAnchorsConfirmed,
            final AffineTransform2D postOutlinePreviewAdjustment,
            final Optional<ManualHemisphereWarp2D> hemisphereWarp,
            final Optional<ConstrainedLocalWarp2D> localWarp,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final ReviewSectionMode reviewSectionMode,
            final ManualSidePlacement2D manualSidePlacement,
            final List<LandmarkPair> activeLandmarks,
            final ReviewConfidenceReport confidence,
            final SourceImageSnapshot verifiedSource,
            final AtlasReviewProvenance verifiedAtlas,
            final ReviewWorkflowMode workflowMode,
            final Optional<InitialPlaneProposal> immutableAutomaticProposal,
            final boolean warningsAcknowledged,
            final long contentRevision,
            final long acceptanceAuditSequence,
            final PreviewMapping previewMapping,
            final Optional<ReviewedTissueSupport> reviewedTissueSupport,
            final boolean tissueClippingEnabled) {
        this(coronalLevel, atlasPlaneTilt, preOutlineAtlasToPreview,
                joinedManualPlacementApplied, outlineWarp,
                outlineAnchorsConfirmed, postOutlinePreviewAdjustment,
                hemisphereWarp, localWarp, orientation, observedHemisphere,
                reviewSectionMode, manualSidePlacement, activeLandmarks,
                confidence, verifiedSource, verifiedAtlas, workflowMode,
                immutableAutomaticProposal, warningsAcknowledged,
                contentRevision, acceptanceAuditSequence, previewMapping,
                reviewedTissueSupport, tissueClippingEnabled,
                reviewSectionMode == ReviewSectionMode.HALF
                        ? HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT
                        : HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
    }

    /** Compatibility constructor for snapshots before crop-support state. */
    public AcceptedAlignmentSnapshot(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final AffineTransform2D preOutlineAtlasToPreview,
            final Optional<ReviewedOutlineTransform2D> outlineWarp,
            final boolean outlineAnchorsConfirmed,
            final AffineTransform2D postOutlinePreviewAdjustment,
            final Optional<ManualHemisphereWarp2D> hemisphereWarp,
            final Optional<ConstrainedLocalWarp2D> localWarp,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final ReviewSectionMode reviewSectionMode,
            final ManualSidePlacement2D manualSidePlacement,
            final List<LandmarkPair> activeLandmarks,
            final ReviewConfidenceReport confidence,
            final SourceImageSnapshot verifiedSource,
            final AtlasReviewProvenance verifiedAtlas,
            final ReviewWorkflowMode workflowMode,
            final Optional<InitialPlaneProposal> immutableAutomaticProposal,
            final boolean warningsAcknowledged,
            final long contentRevision,
            final long acceptanceAuditSequence) {
        this(coronalLevel, atlasPlaneTilt, preOutlineAtlasToPreview, false,
                outlineWarp, outlineAnchorsConfirmed,
                postOutlinePreviewAdjustment, hemisphereWarp, localWarp,
                orientation, observedHemisphere, reviewSectionMode,
                manualSidePlacement, activeLandmarks, confidence,
                verifiedSource, verifiedAtlas, workflowMode,
                immutableAutomaticProposal, warningsAcknowledged,
                contentRevision, acceptanceAuditSequence,
                compatibilityPreviewMapping(verifiedSource),
                Optional.empty(), false);
    }

    /** Compatibility constructor for crop-support snapshots before joined-placement provenance. */
    public AcceptedAlignmentSnapshot(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final AffineTransform2D preOutlineAtlasToPreview,
            final Optional<ReviewedOutlineTransform2D> outlineWarp,
            final boolean outlineAnchorsConfirmed,
            final AffineTransform2D postOutlinePreviewAdjustment,
            final Optional<ManualHemisphereWarp2D> hemisphereWarp,
            final Optional<ConstrainedLocalWarp2D> localWarp,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final ReviewSectionMode reviewSectionMode,
            final ManualSidePlacement2D manualSidePlacement,
            final List<LandmarkPair> activeLandmarks,
            final ReviewConfidenceReport confidence,
            final SourceImageSnapshot verifiedSource,
            final AtlasReviewProvenance verifiedAtlas,
            final ReviewWorkflowMode workflowMode,
            final Optional<InitialPlaneProposal> immutableAutomaticProposal,
            final boolean warningsAcknowledged,
            final long contentRevision,
            final long acceptanceAuditSequence,
            final PreviewMapping previewMapping,
            final Optional<ReviewedTissueSupport> reviewedTissueSupport,
            final boolean tissueClippingEnabled) {
        this(coronalLevel, atlasPlaneTilt, preOutlineAtlasToPreview, false,
                outlineWarp, outlineAnchorsConfirmed,
                postOutlinePreviewAdjustment, hemisphereWarp, localWarp,
                orientation, observedHemisphere, reviewSectionMode,
                manualSidePlacement, activeLandmarks, confidence,
                verifiedSource, verifiedAtlas, workflowMode,
                immutableAutomaticProposal, warningsAcknowledged,
                contentRevision, acceptanceAuditSequence, previewMapping,
                reviewedTissueSupport, tissueClippingEnabled);
    }

    /** Compatibility constructor for snapshots created before section modes. */
    public AcceptedAlignmentSnapshot(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final AffineTransform2D preOutlineAtlasToPreview,
            final Optional<ReviewedOutlineTransform2D> outlineWarp,
            final boolean outlineAnchorsConfirmed,
            final AffineTransform2D postOutlinePreviewAdjustment,
            final Optional<ManualHemisphereWarp2D> hemisphereWarp,
            final Optional<ConstrainedLocalWarp2D> localWarp,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final List<LandmarkPair> activeLandmarks,
            final ReviewConfidenceReport confidence,
            final SourceImageSnapshot verifiedSource,
            final AtlasReviewProvenance verifiedAtlas,
            final ReviewWorkflowMode workflowMode,
            final Optional<InitialPlaneProposal> immutableAutomaticProposal,
            final boolean warningsAcknowledged,
            final long contentRevision,
            final long acceptanceAuditSequence) {
        this(coronalLevel, atlasPlaneTilt, preOutlineAtlasToPreview, false,
                outlineWarp, outlineAnchorsConfirmed,
                postOutlinePreviewAdjustment, hemisphereWarp, localWarp,
                orientation, observedHemisphere, ReviewSectionMode.FULL,
                ManualSidePlacement2D.identity(),
                activeLandmarks, confidence, verifiedSource, verifiedAtlas,
                workflowMode, immutableAutomaticProposal,
                warningsAcknowledged, contentRevision,
                acceptanceAuditSequence,
                compatibilityPreviewMapping(verifiedSource),
                Optional.empty(), false);
    }

    /** Backward-compatible constructor for axis-aligned acceptance callers. */
    public AcceptedAlignmentSnapshot(
            final AllenCoronalLevel coronalLevel,
            final AffineTransform2D effectiveAtlasToPreview,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final List<LandmarkPair> activeLandmarks,
            final ReviewConfidenceReport confidence,
            final SourceImageSnapshot verifiedSource,
            final AtlasReviewProvenance verifiedAtlas,
            final boolean warningsAcknowledged,
            final long contentRevision,
            final long acceptanceAuditSequence) {
        this(
                coronalLevel,
                AtlasPlaneTilt.CORONAL,
                effectiveAtlasToPreview,
                false,
                Optional.empty(),
                false,
                AlignmentReviewContent.identityPreviewAdjustment(),
                Optional.empty(),
                Optional.empty(),
                orientation,
                observedHemisphere,
                ReviewSectionMode.FULL,
                ManualSidePlacement2D.identity(),
                activeLandmarks,
                confidence,
                verifiedSource,
                verifiedAtlas,
                ReviewWorkflowMode.MANUAL_ONLY,
                Optional.empty(),
                warningsAcknowledged,
                contentRevision,
                acceptanceAuditSequence,
                compatibilityPreviewMapping(verifiedSource),
                Optional.empty(), false);
    }

    /** Compatibility constructor for snapshots created before Phase 5L. */
    public AcceptedAlignmentSnapshot(
            final AllenCoronalLevel coronalLevel,
            final AtlasPlaneTilt atlasPlaneTilt,
            final AffineTransform2D effectiveAtlasToPreview,
            final AtlasOrientation orientation,
            final ObservedAnatomicalHemisphere observedHemisphere,
            final List<LandmarkPair> activeLandmarks,
            final ReviewConfidenceReport confidence,
            final SourceImageSnapshot verifiedSource,
            final AtlasReviewProvenance verifiedAtlas,
            final ReviewWorkflowMode workflowMode,
            final Optional<InitialPlaneProposal> immutableAutomaticProposal,
            final boolean warningsAcknowledged,
            final long contentRevision,
            final long acceptanceAuditSequence) {
        this(coronalLevel, atlasPlaneTilt, effectiveAtlasToPreview, false,
                Optional.empty(),
                false,
                AlignmentReviewContent.identityPreviewAdjustment(),
                Optional.empty(), Optional.empty(), orientation,
                observedHemisphere,
                ReviewSectionMode.FULL,
                ManualSidePlacement2D.identity(),
                activeLandmarks, confidence, verifiedSource, verifiedAtlas,
                workflowMode, immutableAutomaticProposal,
                warningsAcknowledged, contentRevision,
                acceptanceAuditSequence,
                compatibilityPreviewMapping(verifiedSource),
                Optional.empty(), false);
    }

    public AcceptedAlignmentSnapshot {
        coronalLevel = Objects.requireNonNull(
                coronalLevel, "coronalLevel");
        atlasPlaneTilt = Objects.requireNonNull(
                atlasPlaneTilt, "atlasPlaneTilt");
        preOutlineAtlasToPreview = Objects.requireNonNull(
                preOutlineAtlasToPreview, "preOutlineAtlasToPreview");
        outlineWarp = Objects.requireNonNull(outlineWarp, "outlineWarp");
        if (outlineWarp.isPresent() != outlineAnchorsConfirmed) {
            throw new IllegalArgumentException(
                    "Accepted outline warp requires confirmed semantic anchors");
        }
        postOutlinePreviewAdjustment = Objects.requireNonNull(
                postOutlinePreviewAdjustment,
                "postOutlinePreviewAdjustment");
        hemisphereWarp = Objects.requireNonNull(
                hemisphereWarp, "hemisphereWarp");
        localWarp = Objects.requireNonNull(localWarp, "localWarp");
        if (hemisphereWarp.isPresent() && localWarp.isPresent()) {
            throw new IllegalArgumentException(
                    "Accepted hemisphere and generic local warps are mutually exclusive");
        }
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
                    "Accepted independent side placement requires Disjoined mode");
        }
        activeLandmarks = List.copyOf(Objects.requireNonNull(
                activeLandmarks, "activeLandmarks"));
        confidence = Objects.requireNonNull(confidence, "confidence");
        verifiedSource = Objects.requireNonNull(
                verifiedSource, "verifiedSource");
        previewMapping = Objects.requireNonNull(
                previewMapping, "previewMapping");
        reviewedTissueSupport = Objects.requireNonNull(
                reviewedTissueSupport, "reviewedTissueSupport");
        halfAtlasCoverage = Objects.requireNonNull(
                halfAtlasCoverage, "halfAtlasCoverage");
        if (reviewSectionMode != ReviewSectionMode.HALF
                && halfAtlasCoverage.includesOppositeRemnant()) {
            throw new IllegalArgumentException(
                    "Accepted opposite-side remnant coverage requires Half mode");
        }
        if (previewMapping.sourceWidth() != verifiedSource.metadata().width()
                || previewMapping.sourceHeight()
                != verifiedSource.metadata().height()) {
            throw new IllegalArgumentException(
                    "Accepted preview mapping must match the verified source dimensions");
        }
        if (reviewedTissueSupport.isPresent()) {
            final ReviewedTissueSupport support =
                    reviewedTissueSupport.orElseThrow();
            if (support.width() != previewMapping.previewWidth()
                    || support.height() != previewMapping.previewHeight()) {
                throw new IllegalArgumentException(
                        "Accepted tissue support must match the preview mapping");
            }
        }
        if (tissueClippingEnabled && reviewedTissueSupport.isEmpty()) {
            throw new IllegalArgumentException(
                    "Accepted tissue clipping requires a reviewed support footprint");
        }
        verifiedAtlas = Objects.requireNonNull(
                verifiedAtlas, "verifiedAtlas");
        workflowMode = Objects.requireNonNull(workflowMode, "workflowMode");
        immutableAutomaticProposal = Objects.requireNonNull(
                immutableAutomaticProposal, "immutableAutomaticProposal");
        if (workflowMode == ReviewWorkflowMode.AUTOMATIC_REVIEW
                || workflowMode == ReviewWorkflowMode.MANUAL_REFINEMENT) {
            if (immutableAutomaticProposal.isEmpty()
                    || immutableAutomaticProposal.orElseThrow().source()
                    != InitialPlaneSource.LOCAL_DEEPSLICE) {
                throw new IllegalArgumentException(
                        "Automatic workflow snapshots require the immutable verified automatic proposal");
            }
        }
        final AllenCoronalLevel acceptedLevel = coronalLevel;
        final AtlasPlaneTilt acceptedTilt = atlasPlaneTilt;
        if (contentRevision < 0 || acceptanceAuditSequence <= 0) {
            throw new IllegalArgumentException(
                    "Accepted alignment revision and sequence are invalid");
        }
        if (preOutlineAtlasToPreview.sourceSpace()
                != CoordinateSpace2D.ATLAS_PLANE_PIXEL
                || preOutlineAtlasToPreview.destinationSpace()
                != CoordinateSpace2D.PREVIEW_PIXEL
                || postOutlinePreviewAdjustment.sourceSpace()
                != CoordinateSpace2D.PREVIEW_PIXEL
                || postOutlinePreviewAdjustment.destinationSpace()
                != CoordinateSpace2D.PREVIEW_PIXEL
                || activeLandmarks.stream().anyMatch(
                landmark -> landmark == null
                || !landmark.coronalLevel().equals(acceptedLevel)
                || !landmark.atlasPlaneTilt().equals(acceptedTilt))) {
            throw new IllegalArgumentException(
                    "Accepted transform direction or landmarks are invalid");
        }
        if (localWarp.isPresent()
                && activeLandmarks.stream()
                .filter(pair -> pair.role() == LandmarkRole.FIT)
                .count() < 4) {
            throw new IllegalArgumentException(
                    "Accepted local warp requires at least four exact-plane FIT landmarks");
        }
        if (hemisphereWarp.isPresent()
                && (!orientation.confirmed()
                || hemisphereWarp.orElseThrow().reviewSectionMode()
                != reviewSectionMode
                || hemisphereWarp.orElseThrow().controls().size()
                < ManualHemisphereWarp2D.MINIMUM_CONTROLS_PER_SIDE)) {
            throw new IllegalArgumentException(
                    "Accepted reviewer-controlled manual warp requires confirmed orientation, matching section mode, and typed manual controls");
        }
        if (hemisphereWarp.isPresent()) {
            requireAcceptedHemisphereConsistency(
                    outlineWarp, postOutlinePreviewAdjustment,
                    hemisphereWarp.orElseThrow(), orientation);
        }
    }

    /** Compatibility name for the pre-outline global affine. */
    public AffineTransform2D effectiveAtlasToPreview() {
        return preOutlineAtlasToPreview;
    }

    /** Stable provenance label for downstream review displays and exports. */
    public String outputMethodLabel() {
        if (joinedManualPlacementApplied
                || hemisphereWarp.isPresent()
                || !manualSidePlacement.isIdentity()) {
            return "REVIEWER_CONTROLLED_MANUAL_WARP";
        }
        if (localWarp.isPresent()) {
            return "MANUAL_LOCAL_WARP";
        }
        if (outlineWarp.isPresent() || !activeLandmarks.isEmpty()) {
            return "REVIEWER_CONTROLLED_MANUAL_ALIGNMENT";
        }
        return workflowMode == ReviewWorkflowMode.AUTOMATIC_REVIEW
                ? "AUTOMATIC_REVIEW" : "MANUAL_REVIEW";
    }

    /** Whether one immutable raw atlas side belongs to this accepted review. */
    public boolean includesAtlasSide(
            final ManualHemisphereWarp2D.AtlasSide side) {
        final ManualHemisphereWarp2D.AtlasSide checked = Objects.requireNonNull(
                side, "side");
        if (reviewSectionMode != ReviewSectionMode.HALF
                || halfAtlasCoverage.includesOppositeRemnant()) {
            return true;
        }
        return checked == switch (observedHemisphere) {
            case LEFT -> ManualHemisphereWarp2D.AtlasSide.LEFT;
            case RIGHT -> ManualHemisphereWarp2D.AtlasSide.RIGHT;
            case BOTH, UNSURE -> throw new IllegalStateException(
                    "Accepted Half alignment requires confirmed visible laterality");
        };
    }

    /** Whether one raw atlas coordinate belongs to the accepted side set. */
    public boolean includesAtlasPoint(final Point2D atlasPoint) {
        final Point2D checked = Objects.requireNonNull(
                atlasPoint, "atlasPoint");
        if (reviewSectionMode != ReviewSectionMode.HALF) {
            return true;
        }
        return rawAtlasSide(checked).map(this::includesAtlasSide)
                .orElse(false);
    }

    /** Applies the exact accepted atlas-to-preview chain to any atlas ROI point. */
    public Point2D mapAtlasToPreview(final Point2D atlasPoint) {
        final Point2D global = preOutlineAtlasToPreview.apply(
                Objects.requireNonNull(atlasPoint, "atlasPoint"));
        final Point2D outlined = outlineWarp
                .map(warp -> warp.apply(global)).orElse(global);
        final Point2D adjusted = postOutlinePreviewAdjustment.apply(outlined);
        final Optional<ManualHemisphereWarp2D.AtlasSide> rawSide =
                rawAtlasSide(atlasPoint);
        final Point2D placed = rawSide.map(side -> manualSidePlacement
                .apply(side, adjusted)).orElse(adjusted);
        final Point2D hemisphereAdjusted = hemisphereWarp
                .map(warp -> rawSide
                        .map(side -> warp.apply(side, placed))
                        .orElse(placed)).orElse(placed);
        return localWarp.map(warp -> warp.apply(hemisphereAdjusted))
                .orElse(hemisphereAdjusted);
    }

    /** Reverses {@link #mapAtlasToPreview(Point2D)} in exact reverse order. */
    public Point2D mapPreviewToAtlas(final Point2D previewPoint) {
        final Point2D reviewed = Objects.requireNonNull(
                previewPoint, "previewPoint");
        final Point2D hemisphereInverse = localWarp
                .map(warp -> warp.inverse(reviewed)).orElse(reviewed);
        if (reviewSectionMode != ReviewSectionMode.DISJOINED
                && hemisphereWarp.isPresent()) {
            // Joined side domains retain a fixed seam, so the target point
            // selects exactly one side without probing and replaying every
            // raw atlas-side candidate. This is the full-resolution export
            // hot path.
            return reverseBeforeHemisphere(
                    hemisphereWarp.orElseThrow().inverse(
                            hemisphereInverse));
        }
        if (hemisphereWarp.isPresent()
                || !manualSidePlacement.isIdentity()) {
            return inverseSideChain(hemisphereInverse);
        }
        final Point2D localInverse = hemisphereInverse;
        final Point2D postInverse = postOutlinePreviewAdjustment.inverse()
                .apply(localInverse);
        final Point2D outlineInverse = outlineWarp
                .map(warp -> warp.inverse(postInverse)).orElse(postInverse);
        return preOutlineAtlasToPreview.inverse().apply(outlineInverse);
    }

    /**
     * Returns every exact atlas inverse represented by one accepted preview
     * point. Joined Full/Half geometry is single-valued. Disjoined halves may
     * deliberately overlap after independent placement, so source export must
     * sample both valid raw atlas sides instead of silently favoring one.
     */
    public List<Point2D> mapPreviewToAtlasCandidates(
            final Point2D previewPoint) {
        final Point2D reviewed = Objects.requireNonNull(
                previewPoint, "previewPoint");
        if (reviewSectionMode != ReviewSectionMode.DISJOINED
                || (hemisphereWarp.isEmpty()
                && manualSidePlacement.isIdentity())) {
            return List.of(mapPreviewToAtlas(reviewed));
        }
        final Point2D hemisphereInverse = localWarp
                .map(warp -> warp.inverse(reviewed)).orElse(reviewed);
        return inverseSideCandidates(hemisphereInverse).stream()
                .map(InverseCandidate::atlasPoint).toList();
    }

    private Optional<ManualHemisphereWarp2D.AtlasSide> rawAtlasSide(
            final Point2D atlasPoint) {
        final double centre = (verifiedAtlas.atlasPlaneWidth() - 1.0) * 0.5;
        if (Double.doubleToLongBits(atlasPoint.x())
                == Double.doubleToLongBits(centre)) {
            return Optional.empty();
        }
        return Optional.of(atlasPoint.x() < centre
                ? ManualHemisphereWarp2D.AtlasSide.LEFT
                : ManualHemisphereWarp2D.AtlasSide.RIGHT);
    }

    private Point2D inverseSideChain(final Point2D previewPoint) {
        return inverseSideCandidates(previewPoint).get(0).atlasPoint();
    }

    private List<InverseCandidate> inverseSideCandidates(
            final Point2D previewPoint) {
        final List<InverseCandidate> candidates = new java.util.ArrayList<>();
        final List<Optional<ManualHemisphereWarp2D.AtlasSide>> choices =
                List.of(Optional.empty(),
                        Optional.of(ManualHemisphereWarp2D.AtlasSide.LEFT),
                        Optional.of(ManualHemisphereWarp2D.AtlasSide.RIGHT));
        for (final Optional<ManualHemisphereWarp2D.AtlasSide> choice
                : choices) {
            final Point2D beforePlacement;
            try {
                final Point2D beforeHemisphere = choice
                        .map(side -> hemisphereWarp.filter(field ->
                                        field.hasControls(side))
                                .map(field -> field.inverse(
                                        side, previewPoint))
                                .orElse(previewPoint))
                        .orElse(previewPoint);
                beforePlacement = choice.map(side -> manualSidePlacement
                        .inverse(side, beforeHemisphere))
                        .orElse(beforeHemisphere);
            } catch (final IllegalArgumentException unsafeInverse) {
                continue;
            }
            final Point2D atlas = reverseBeforeHemisphere(beforePlacement);
            final Optional<ManualHemisphereWarp2D.AtlasSide> rawSide =
                    rawAtlasSide(atlas);
            final boolean validChoice = choice.isPresent()
                    ? rawSide.equals(choice)
                    : rawSide.isEmpty()
                    || !sideHasEffect(rawSide.orElseThrow());
            if (!validChoice) {
                continue;
            }
            final Point2D replay = mapAtlasToPreview(atlas);
            final double residual = Math.hypot(
                    replay.x() - previewPoint.x(),
                    replay.y() - previewPoint.y());
            if (Double.isFinite(residual) && residual <= 1e-6
                    && candidates.stream().noneMatch(existing ->
                            Math.hypot(existing.atlasPoint().x() - atlas.x(),
                                    existing.atlasPoint().y() - atlas.y())
                            <= 1e-9)) {
                candidates.add(new InverseCandidate(atlas, residual));
            }
        }
        candidates.sort(java.util.Comparator
                .comparingDouble(InverseCandidate::residual)
                .thenComparingDouble(candidate ->
                        candidate.atlasPoint().x())
                .thenComparingDouble(candidate ->
                        candidate.atlasPoint().y()));
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Accepted hemisphere-warp inverse could not preserve raw atlas-side semantics");
        }
        return List.copyOf(candidates);
    }

    private record InverseCandidate(
            Point2D atlasPoint,
            double residual) {
    }

    private boolean sideHasEffect(
            final ManualHemisphereWarp2D.AtlasSide side) {
        final AffineTransform2D identity = new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0, 0, 1, 0);
        return !manualSidePlacement.transform(side).equals(identity)
                || hemisphereWarp.map(field -> field.hasControls(side))
                        .orElse(false);
    }

    private Point2D reverseBeforeHemisphere(final Point2D previewPoint) {
        final Point2D outlined = postOutlinePreviewAdjustment.inverse()
                .apply(previewPoint);
        final Point2D global = outlineWarp
                .map(warp -> warp.inverse(outlined)).orElse(outlined);
        return preOutlineAtlasToPreview.inverse().apply(global);
    }

    private static void requireAcceptedHemisphereConsistency(
            final Optional<ReviewedOutlineTransform2D> outlineOptional,
            final AffineTransform2D postOutline,
            final ManualHemisphereWarp2D hemisphere,
            final AtlasOrientation acceptedOrientation) {
        if (hemisphere.orientation() != acceptedOrientation) {
            throw new IllegalArgumentException(
                    "Accepted hemisphere warp orientation is stale");
        }
        if (hemisphere.controls().isEmpty()) {
            throw new IllegalArgumentException(
                    "Accepted reviewer-controlled warp controls are incomplete");
        }
    }

    private static PreviewMapping compatibilityPreviewMapping(
            final SourceImageSnapshot source) {
        final SourceImageSnapshot checked = Objects.requireNonNull(
                source, "verifiedSource");
        return new PreviewMapping(
                checked.metadata().width(), checked.metadata().height(),
                checked.metadata().width(), checked.metadata().height());
    }
}
