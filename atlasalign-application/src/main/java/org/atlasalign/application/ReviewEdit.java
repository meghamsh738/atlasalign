package org.atlasalign.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D;
import org.atlasalign.application.manual.BoundaryFitModel;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualSidePlacement2D;
import org.atlasalign.application.manual.ManualWarpControl;
import org.atlasalign.application.manual.ManualOutlineWarp2D;
import org.atlasalign.application.manual.ReviewedOutlineTransform2D;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;

/**
 * Pure review edit reducer. Each edit returns new immutable content.
 */
public sealed interface ReviewEdit permits
        ReviewEdit.Translate,
        ReviewEdit.MakeAtlasUpright,
        ReviewEdit.Rotate,
        ReviewEdit.Scale,
        ReviewEdit.ScaleAxes,
        ReviewEdit.ApplyAssistedBoundaryFit,
        ReviewEdit.ApplyManualBoundaryWarp,
        ReviewEdit.EnterManualRefinement,
        ReviewEdit.SetCoronalLevel,
        ReviewEdit.SetAtlasPlaneTilt,
        ReviewEdit.ApplyGuidedManualCandidate,
        ReviewEdit.ApplyGuidedManualStartingPlane,
        ReviewEdit.SetAtlasOrientation,
        ReviewEdit.SetObservedHemisphere,
        ReviewEdit.SetReviewSectionMode,
        ReviewEdit.SetHalfAtlasCoverage,
        ReviewEdit.AddLandmark,
        ReviewEdit.AddLandmarks,
        ReviewEdit.ReplaceAnatomicalHandles,
        ReviewEdit.MoveLandmarkAtlasPoint,
        ReviewEdit.MoveLandmarkPreviewPoint,
        ReviewEdit.SetLandmarkRole,
        ReviewEdit.RemoveLandmark,
        ReviewEdit.FitActiveLandmarks,
        ReviewEdit.FitActiveLandmarksAffine,
        ReviewEdit.FitActiveLandmarksLocalWarp,
        ReviewEdit.MoveAnatomicalHandleAndRefit,
        ReviewEdit.ReplaceAllManualWarpControlsAndInstall,
        ReviewEdit.ReplaceManualWarpControlGroup,
        ReviewEdit.ReplaceStructureWarpControlGroup,
        ReviewEdit.TransformStructureWarpControlGroup,
        ReviewEdit.ClearStructureWarpControlGroup,
        ReviewEdit.AddManualWarpControl,
        ReviewEdit.MoveManualWarpControlAndInstall,
        ReviewEdit.SetManualSidePlacement,
        ReviewEdit.ResetManualWarpSide,
        ReviewEdit.ClearHemisphereWarp,
        ReviewEdit.ClearLocalWarp,
        ReviewEdit.ReplaceReviewedTissueSupport,
        ReviewEdit.ResuggestReviewedTissueSupport,
        ReviewEdit.MoveReviewedTissueSupportControl,
        ReviewEdit.InsertReviewedTissueSupportControl,
        ReviewEdit.DeleteReviewedTissueSupportControl,
        ReviewEdit.SetTissueClipping,
        ReviewEdit.ResetToProposalAndInstallManualWarp,
        ReviewEdit.ResetToInitialPlacement,
        ReviewEdit.ResetToProposal {

    ReviewOperation operation();

    String description();

    AlignmentReviewContent apply(
            AlignmentReviewContent current,
            AlignmentReviewBasis basis);

    record EnterManualRefinement() implements ReviewEdit {
        @Override public ReviewOperation operation() {
            return ReviewOperation.ENTER_MANUAL_REFINEMENT;
        }
        @Override public String description() {
            return "Enter explicit manual refinement";
        }
        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.workflowMode() != ReviewWorkflowMode.AUTOMATIC_REVIEW) {
                throw new IllegalArgumentException("Manual refinement is already active");
            }
            return new AlignmentReviewContent(current.coronalLevel(),
                    current.atlasPlaneTilt(), ReviewWorkflowMode.MANUAL_REFINEMENT,
                    current.orientation(), current.observedHemisphere(),
                    current.reviewSectionMode(),
                    current.manualSidePlacement(),
                    current.manualPreviewAdjustment(), current.outlineWarp(),
                    current.outlineAnchorsConfirmed(),
                    current.postOutlinePreviewAdjustment(),
                    current.hemisphereWarp(), current.landmarks(),
                    current.localWarp(), current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(),
                    current.halfAtlasCoverage());
        }
    }

    record Translate(double deltaX, double deltaY)
            implements ReviewEdit {

        public Translate {
            requireFinite(deltaX, "deltaX");
            requireFinite(deltaY, "deltaY");
            if (deltaX == 0 && deltaY == 0) {
                throw new IllegalArgumentException(
                        "Translation must change at least one axis");
            }
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.TRANSLATE;
        }

        @Override
        public String description() {
            return "Translate by preview pixels x="
                    + deltaX + ", y=" + deltaY;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            return withPreviewCorrection(
                    current,
                    previewAffine(
                            1, 0, deltaX,
                            0, 1, deltaY));
        }
    }

    record Rotate(double radians, Point2D previewPivot)
            implements ReviewEdit {

        public Rotate {
            requireFinite(radians, "radians");
            previewPivot = Objects.requireNonNull(
                    previewPivot, "previewPivot");
            if (radians == 0) {
                throw new IllegalArgumentException(
                        "Rotation must be non-zero");
            }
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.ROTATE;
        }

        @Override
        public String description() {
            return "Rotate by radians=" + radians
                    + " around preview " + previewPivot;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            final double cosine = Math.cos(radians);
            final double sine = Math.sin(radians);
            final double x = previewPivot.x();
            final double y = previewPivot.y();
            return withPreviewCorrection(
                    current,
                    previewAffine(
                            cosine,
                            -sine,
                            x - cosine * x + sine * y,
                            sine,
                            cosine,
                            y - sine * x - cosine * y));
        }
    }

    /** Straightens the editable affine frame without changing source-space ROIs. */
    record MakeAtlasUpright() implements ReviewEdit {
        @Override
        public ReviewOperation operation() {
            return ReviewOperation.MAKE_ATLAS_UPRIGHT;
        }

        @Override
        public String description() {
            return "Make atlas upright with rectangular axes; preserve atlas centre and axis lengths; manual placement only";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.outlineWarp().isPresent()
                    || current.hemisphereWarp().isPresent()
                    || current.localWarp().isPresent()
                    || !current.manualSidePlacement().equals(
                    ManualSidePlacement2D.identity())) {
                throw new IllegalArgumentException(
                        "Clear warp or Undo separate half placements before making the atlas upright");
            }
            // Work before the explicit laterality reflection, which remains
            // recorded in current.orientation() and must never be inferred here.
            final AffineTransform2D existing = basis.proposal().affine()
                    .andThen(current.manualPreviewAdjustment());
            final double scaleX = Math.hypot(existing.m00(), existing.m10());
            final double scaleY = Math.hypot(existing.m01(), existing.m11());
            final Point2D centre = new Point2D(
                    (basis.atlas().atlasPlaneWidth() - 1) * 0.5,
                    (basis.atlas().atlasPlaneHeight() - 1) * 0.5);
            final Point2D placedCentre = existing.apply(centre);
            final AffineTransform2D upright = new AffineTransform2D(
                    CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                    CoordinateSpace2D.PREVIEW_PIXEL,
                    scaleX, 0, placedCentre.x() - scaleX * centre.x(),
                    0, scaleY, placedCentre.y() - scaleY * centre.y());
            final double axisTolerance = 1e-12 * Math.max(scaleX, scaleY);
            if (Math.abs(existing.m01()) <= axisTolerance
                    && Math.abs(existing.m10()) <= axisTolerance
                    && existing.m00() > 0 && existing.m11() > 0) {
                return current;
            }
            return new AlignmentReviewContent(
                    current.coronalLevel(), current.atlasPlaneTilt(),
                    current.workflowMode(), current.orientation(),
                    current.observedHemisphere(), current.reviewSectionMode(),
                    current.manualSidePlacement(),
                    basis.proposal().affine().inverse().andThen(upright),
                    current.outlineWarp(), current.outlineAnchorsConfirmed(),
                    current.postOutlinePreviewAdjustment(),
                    current.hemisphereWarp(), current.landmarks(), current.localWarp(),
                    current.reviewedTissueSupport(), current.tissueClippingEnabled(),
                    current.halfAtlasCoverage());
        }
    }

    record Scale(double factor, Point2D previewPivot)
            implements ReviewEdit {

        public Scale {
            requireFinite(factor, "factor");
            previewPivot = Objects.requireNonNull(
                    previewPivot, "previewPivot");
            if (factor <= 0 || factor == 1) {
                throw new IllegalArgumentException(
                        "Scale factor must be positive and non-unit");
            }
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.SCALE;
        }

        @Override
        public String description() {
            return "Scale by factor=" + factor
                    + " around preview " + previewPivot;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            return withPreviewCorrection(
                    current,
                    previewAffine(
                            factor,
                            0,
                            previewPivot.x() * (1 - factor),
                            0,
                            factor,
                            previewPivot.y() * (1 - factor)));
        }
    }

    /**
     * Scales along reviewer-visible rotated axes without introducing shear.
     * The pivot and axis angle are stored explicitly so audit replay does not
     * depend on a later canvas selection or display state.
     */
    record ScaleAxes(
            double scaleX,
            double scaleY,
            double axisRadians,
            Point2D previewPivot) implements ReviewEdit {

        public ScaleAxes {
            requireFinite(scaleX, "scaleX");
            requireFinite(scaleY, "scaleY");
            requireFinite(axisRadians, "axisRadians");
            previewPivot = Objects.requireNonNull(
                    previewPivot, "previewPivot");
            if (scaleX <= 0 || scaleY <= 0
                    || scaleX == 1 && scaleY == 1) {
                throw new IllegalArgumentException(
                        "Axis scale factors must be positive and change at least one axis");
            }
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.SCALE;
        }

        @Override
        public String description() {
            return "Scale preview axes x=" + scaleX + ", y=" + scaleY
                    + ", angle=" + axisRadians
                    + " around preview " + previewPivot;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.reviewSectionMode() == ReviewSectionMode.DISJOINED) {
                throw new IllegalArgumentException(
                        "Joined two-axis resizing is unavailable in Disjoined mode");
            }
            if (current.hemisphereWarp().isPresent()
                    || current.localWarp().isPresent()) {
                throw new IllegalArgumentException(
                        "Clear warp or Undo local changes before resizing the whole atlas");
            }
            if (current.outlineWarp().isPresent()) {
                throw new IllegalArgumentException(
                        "Two-axis coarse resizing is unavailable for legacy outline-warp snapshots");
            }
            final double cosine = Math.cos(axisRadians);
            final double sine = Math.sin(axisRadians);
            final double m00 = cosine * cosine * scaleX
                    + sine * sine * scaleY;
            final double m01 = cosine * sine * (scaleX - scaleY);
            final double m10 = m01;
            final double m11 = sine * sine * scaleX
                    + cosine * cosine * scaleY;
            final double x = previewPivot.x();
            final double y = previewPivot.y();
            final AlignmentReviewContent updated = withPreviewCorrection(
                    current, previewAffine(
                    m00, m01, x - m00 * x - m01 * y,
                    m10, m11, y - m10 * x - m11 * y));
            AlignmentReviewState.requirePreservedAtlasAxisShear(
                    AlignmentReviewState.preOutlineTransform(basis, current),
                    AlignmentReviewState.preOutlineTransform(basis, updated),
                    "Two-axis joined placement");
            return updated;
        }
    }

    /**
     * Atomically installs one explicitly reviewed assisted coarse placement.
     * Boundary matches are audit geometry, never landmark or confidence
     * evidence. The controller separately checks request hashes and the
     * padded preview-workspace intersection immediately before applying it.
     */
    record ApplyAssistedBoundaryFit(
            ReviewSectionMode sectionMode,
            Optional<ManualHemisphereWarp2D.AtlasSide> targetSide,
            BoundaryFitModel model,
            AffineTransform2D previewCorrection,
            int includedMatchCount,
            String solverRevision,
            String inputHash,
            String planeHash,
            String placementHash,
            String tissueSupportHash) implements ReviewEdit {

        public ApplyAssistedBoundaryFit {
            sectionMode = Objects.requireNonNull(
                    sectionMode, "sectionMode");
            targetSide = Objects.requireNonNull(targetSide, "targetSide");
            model = Objects.requireNonNull(model, "model");
            previewCorrection = Objects.requireNonNull(
                    previewCorrection, "previewCorrection");
            if (sectionMode == ReviewSectionMode.DISJOINED
                    != targetSide.isPresent()) {
                throw new IllegalArgumentException(
                        "Only a Disjoined assisted fit targets one atlas side");
            }
            if (previewCorrection.sourceSpace()
                    != CoordinateSpace2D.PREVIEW_PIXEL
                    || previewCorrection.destinationSpace()
                    != CoordinateSpace2D.PREVIEW_PIXEL
                    || previewCorrection.determinant() <= 0) {
                throw new IllegalArgumentException(
                        "Assisted fit must be an orientation-preserving preview-space correction");
            }
            if (includedMatchCount < 4) {
                throw new IllegalArgumentException(
                        "Assisted fit requires at least four included matches");
            }
            solverRevision = requireText(solverRevision, "solverRevision");
            inputHash = requireSha256(inputHash, "inputHash");
            planeHash = requireText(planeHash, "planeHash");
            placementHash = requireText(placementHash, "placementHash");
            tissueSupportHash = requireText(
                    tissueSupportHash, "tissueSupportHash");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.APPLY_ASSISTED_BOUNDARY_FIT;
        }

        @Override
        public String description() {
            return "Apply reviewer-controlled assisted boundary fit: mode="
                    + sectionMode + ", side="
                    + targetSide.map(Enum::name).orElse("JOINED")
                    + ", model=" + model + ", includedMatches="
                    + includedMatchCount + ", solver=" + solverRevision
                    + ", inputSha256=" + inputHash + ", plane="
                    + planeHash + ", placement=" + placementHash
                    + ", tissueSupport=" + tissueSupportHash
                    + ", correction=[" + previewCorrection.m00() + ","
                    + previewCorrection.m01() + ","
                    + previewCorrection.m02() + ";"
                    + previewCorrection.m10() + ","
                    + previewCorrection.m11() + ","
                    + previewCorrection.m12()
                    + "]; manual geometry only, confidence unchanged";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.reviewSectionMode() != sectionMode) {
                throw new IllegalArgumentException(
                        "Assisted fit section mode is stale");
            }
            if (current.hemisphereWarp().isPresent()
                    || current.localWarp().isPresent()) {
                throw new IllegalArgumentException(
                        "Clear warp or Undo local changes before fitting the whole atlas");
            }
            if (current.outlineWarp().isPresent()) {
                throw new IllegalArgumentException(
                        "Assisted boundary placement is unavailable for legacy outline-warp snapshots");
            }
            if (sectionMode != ReviewSectionMode.DISJOINED) {
                final AlignmentReviewContent updated = withPreviewCorrection(
                        current, previewCorrection);
                AlignmentReviewState.requirePerpendicularAtlasAxes(
                        AlignmentReviewState.preOutlineTransform(
                                basis, updated),
                        "Assisted joined placement");
                return updated;
            }
            final ManualHemisphereWarp2D.AtlasSide side = targetSide
                    .orElseThrow();
            final ManualSidePlacement2D updatedPlacement = current
                    .manualSidePlacement().withTransform(side,
                    current.manualSidePlacement().transform(side)
                            .andThen(previewCorrection));
            final AlignmentReviewContent updated = new AlignmentReviewContent(
                    current.coronalLevel(), current.atlasPlaneTilt(),
                    current.workflowMode(), current.orientation(),
                    current.observedHemisphere(), current.reviewSectionMode(),
                    updatedPlacement, current.manualPreviewAdjustment(),
                    current.outlineWarp(), current.outlineAnchorsConfirmed(),
                    current.postOutlinePreviewAdjustment(), Optional.empty(),
                    current.landmarks(), Optional.empty(),
                    current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(),
                    HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
            final AffineTransform2D shared = AlignmentReviewState
                    .preOutlineTransform(basis, updated)
                    .andThen(updated.postOutlinePreviewAdjustment());
            AlignmentReviewState.requirePerpendicularAtlasAxes(
                    shared.andThen(updatedPlacement.transform(side)),
                    "Assisted Disjoined " + side + " placement");
            return updated;
        }
    }

    record SetCoronalLevel(AllenCoronalLevel level)
            implements ReviewEdit {

        public SetCoronalLevel {
            level = Objects.requireNonNull(level, "level");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.SET_CORONAL_LEVEL;
        }

        @Override
        public String description() {
            return "Set Allen coronal level to "
                    + level.zeroBasedAnteriorPosteriorIndex();
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.coronalLevel().equals(level)) {
                return current;
            }
            final boolean preserveManualField = current.hemisphereWarp()
                    .isPresent()
                    && current.hemisphereWarp().orElseThrow()
                            .outlineContentSha256().isBlank();
            return new AlignmentReviewContent(
                    level,
                    current.atlasPlaneTilt(),
                    current.workflowMode(),
                    current.orientation(),
                    current.observedHemisphere(),
                    current.reviewSectionMode(),
                    current.manualSidePlacement(),
                    current.manualPreviewAdjustment(),
                    preserveManualField ? current.outlineWarp()
                            : Optional.empty(),
                    preserveManualField && current.outlineAnchorsConfirmed(),
                    preserveManualField
                            ? current.postOutlinePreviewAdjustment()
                            : AlignmentReviewContent.identityPreviewAdjustment(),
                    preserveManualField ? current.hemisphereWarp()
                            : Optional.empty(), current.landmarks(),
                    Optional.empty(), current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(),
                    HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
        }
    }

    record SetAtlasPlaneTilt(AtlasPlaneTilt tilt)
            implements ReviewEdit {

        public SetAtlasPlaneTilt {
            tilt = Objects.requireNonNull(tilt, "tilt");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.SET_ATLAS_PLANE_TILT;
        }

        @Override
        public String description() {
            return "Set atlas plane tilt to sagittal="
                    + tilt.sagittalDegrees() + "°, horizontal="
                    + tilt.horizontalDegrees() + "°";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.atlasPlaneTilt().equals(tilt)) {
                return current;
            }
            final boolean preserveManualField = current.hemisphereWarp()
                    .isPresent()
                    && current.hemisphereWarp().orElseThrow()
                            .outlineContentSha256().isBlank();
            return new AlignmentReviewContent(
                    current.coronalLevel(),
                    tilt,
                    current.workflowMode(),
                    current.orientation(),
                    current.observedHemisphere(),
                    current.reviewSectionMode(),
                    current.manualSidePlacement(),
                    current.manualPreviewAdjustment(),
                    preserveManualField ? current.outlineWarp()
                            : Optional.empty(),
                    preserveManualField && current.outlineAnchorsConfirmed(),
                    preserveManualField
                            ? current.postOutlinePreviewAdjustment()
                            : AlignmentReviewContent.identityPreviewAdjustment(),
                    preserveManualField ? current.hemisphereWarp()
                            : Optional.empty(), current.landmarks(),
                    Optional.empty(), current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(),
                    HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
        }
    }

    /**
     * Applies one explicitly chosen, preview-only guided-manual candidate as
     * a manual starting plane while retaining its audit provenance.
     */
    record ApplyGuidedManualCandidate(
            AllenCoronalLevel level,
            AtlasPlaneTilt tilt,
            AtlasOrientation orientation,
            ObservedAnatomicalHemisphere observedHemisphere,
            AffineTransform2D candidateAtlasToPreview,
            Optional<ReviewedOutlineTransform2D> outlineWarp,
            boolean outlineAnchorsConfirmed,
            String outlineFitMethod,
            String candidateId,
            AllenCoronalLevel visualSeedLevel,
            AtlasPlaneTilt visualSeedTilt,
            double combinedMismatch,
            double outlineMismatch,
            double guideMismatch,
            List<String> contourIds,
            String sourceSha256,
            String atlasSha256) implements ReviewEdit {

        public ApplyGuidedManualCandidate {
            level = Objects.requireNonNull(level, "level");
            tilt = Objects.requireNonNull(tilt, "tilt");
            orientation = Objects.requireNonNull(orientation, "orientation");
            observedHemisphere = Objects.requireNonNull(
                    observedHemisphere, "observedHemisphere");
            candidateAtlasToPreview = Objects.requireNonNull(
                    candidateAtlasToPreview, "candidateAtlasToPreview");
            outlineWarp = Objects.requireNonNull(outlineWarp, "outlineWarp");
            if (outlineWarp.isPresent() != outlineAnchorsConfirmed) {
                throw new IllegalArgumentException(
                        "A guided outline warp requires explicit semantic-anchor confirmation");
            }
            if (!orientation.confirmed()) {
                throw new IllegalArgumentException(
                        "A guided candidate requires an explicit atlas orientation");
            }
            if (observedHemisphere
                    == ObservedAnatomicalHemisphere.UNSURE) {
                throw new IllegalArgumentException(
                        "A guided candidate requires an explicit observed hemisphere");
            }
            if (candidateAtlasToPreview.sourceSpace()
                    != CoordinateSpace2D.ATLAS_PLANE_PIXEL
                    || candidateAtlasToPreview.destinationSpace()
                    != CoordinateSpace2D.PREVIEW_PIXEL) {
                throw new IllegalArgumentException(
                        "Guided candidate transform must map atlas pixels to preview pixels");
            }
            final boolean transformReflected =
                    candidateAtlasToPreview.determinant() < 0;
            if (transformReflected != orientation.reflected()) {
                throw new IllegalArgumentException(
                        "Guided candidate transform reflection must match the explicit orientation");
            }
            outlineFitMethod = requireText(
                    outlineFitMethod, "outlineFitMethod");
            candidateId = requireText(candidateId, "candidateId");
            visualSeedLevel = Objects.requireNonNull(
                    visualSeedLevel, "visualSeedLevel");
            visualSeedTilt = Objects.requireNonNull(
                    visualSeedTilt, "visualSeedTilt");
            requireFiniteNonNegative(combinedMismatch, "combinedMismatch");
            requireFiniteNonNegative(outlineMismatch, "outlineMismatch");
            requireFiniteNonNegative(guideMismatch, "guideMismatch");
            contourIds = List.copyOf(Objects.requireNonNull(
                    contourIds, "contourIds"));
            if (contourIds.isEmpty()
                    || contourIds.stream().anyMatch(value -> value == null
                            || value.isBlank())) {
                throw new IllegalArgumentException(
                        "Guided candidate contour IDs are required");
            }
            sourceSha256 = requireSha256(sourceSha256, "sourceSha256");
            atlasSha256 = requireSha256(atlasSha256, "atlasSha256");
        }

        /** Compatibility constructor for pre-Phase 5L-2 affine candidates. */
        public ApplyGuidedManualCandidate(
                final AllenCoronalLevel level,
                final AtlasPlaneTilt tilt,
                final AtlasOrientation orientation,
                final ObservedAnatomicalHemisphere observedHemisphere,
                final AffineTransform2D candidateAtlasToPreview,
                final String outlineFitMethod,
                final String candidateId,
                final AllenCoronalLevel visualSeedLevel,
                final AtlasPlaneTilt visualSeedTilt,
                final double combinedMismatch,
                final double outlineMismatch,
                final double guideMismatch,
                final List<String> contourIds,
                final String sourceSha256,
                final String atlasSha256) {
            this(level, tilt, orientation, observedHemisphere,
                    candidateAtlasToPreview, Optional.empty(),
                    false,
                    outlineFitMethod, candidateId, visualSeedLevel,
                    visualSeedTilt, combinedMismatch, outlineMismatch,
                    guideMismatch, contourIds, sourceSha256, atlasSha256);
        }

        /** New outline-warp path; the fitted warp is mandatory here. */
        public ApplyGuidedManualCandidate(
                final AllenCoronalLevel level,
                final AtlasPlaneTilt tilt,
                final AtlasOrientation orientation,
                final ObservedAnatomicalHemisphere observedHemisphere,
                final AffineTransform2D candidateAtlasToPreview,
                final ManualOutlineWarp2D outlineWarp,
                final boolean outlineAnchorsConfirmed,
                final String outlineFitMethod,
                final String candidateId,
                final AllenCoronalLevel visualSeedLevel,
                final AtlasPlaneTilt visualSeedTilt,
                final double combinedMismatch,
                final double outlineMismatch,
                final double guideMismatch,
                final List<String> contourIds,
                final String sourceSha256,
                final String atlasSha256) {
            this(level, tilt, orientation, observedHemisphere,
                    candidateAtlasToPreview,
                    Optional.of(Objects.requireNonNull(
                            outlineWarp, "outlineWarp")),
                    outlineAnchorsConfirmed,
                    outlineFitMethod, candidateId, visualSeedLevel,
                    visualSeedTilt, combinedMismatch, outlineMismatch,
                    guideMismatch, contourIds, sourceSha256, atlasSha256);
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.APPLY_GUIDED_MANUAL_CANDIDATE;
        }

        /** Stable identity of the exact outline transform transferred. */
        public String candidateTransformSha256() {
            return affineSha256(outlineFitMethod, candidateAtlasToPreview);
        }

        @Override public String description() {
            return "Apply preview-only guided-manual candidate "
                    + candidateId + ": level="
                    + level.zeroBasedAnteriorPosteriorIndex()
                    + ", sagittal=" + tilt.sagittalDegrees()
                    + "°, horizontal=" + tilt.horizontalDegrees()
                    + "°, orientation=" + orientation
                    + ", observedHemisphere=" + observedHemisphere
                    + ", outlineFitMethod=" + outlineFitMethod
                    + ", visualSeedLevel="
                    + visualSeedLevel.zeroBasedAnteriorPosteriorIndex()
                    + ", visualSeedSagittal="
                    + visualSeedTilt.sagittalDegrees()
                    + "°, visualSeedHorizontal="
                    + visualSeedTilt.horizontalDegrees()
                    + "°, mismatch=" + combinedMismatch
                    + " (outline=" + outlineMismatch + ", guide="
                    + guideMismatch + "), contours=" + contourIds
                    + ", sourceSha256=" + sourceSha256
                    + ", atlasSha256=" + atlasSha256
                    + ", atlasToPreview=["
                    + candidateAtlasToPreview.m00() + ","
                    + candidateAtlasToPreview.m01() + ","
                    + candidateAtlasToPreview.m02() + ";"
                    + candidateAtlasToPreview.m10() + ","
                    + candidateAtlasToPreview.m11() + ","
                    + candidateAtlasToPreview.m12() + "]"
                    + ", candidateTransformSha256="
                    + candidateTransformSha256()
                    + outlineWarp.map(warp -> ", outlineWarpSha256="
                            + warp.contentSha256()
                            + ", outlineWarpAlgorithm="
                            + warp.algorithmRevision()
                            + ", outlineAnchorsConfirmed="
                            + outlineAnchorsConfirmed).orElse(
                                    ", outlineWarp=none (compatibility)")
                    + "; manual preview only, confidence unchanged";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (!sourceSha256.equals(
                    basis.sourceSnapshot().pixelSha256())) {
                throw new IllegalArgumentException(
                        "Guided candidate source identity does not match the active review basis");
            }
            if (!atlasSha256.equals(basis.atlas().identitySha256())) {
                throw new IllegalArgumentException(
                        "Guided candidate atlas identity does not match the active review basis");
            }
            outlineWarp.ifPresent(warp -> {
                if (basis.proposal().geometry().geometry()
                        != SectionGeometry.FULL
                        || observedHemisphere
                        != ObservedAnatomicalHemisphere.BOTH) {
                    throw new IllegalArgumentException(
                            "A manual outline warp is permitted only for a complete FULL bilateral section");
                }
                if (warp.sourceSpace() != CoordinateSpace2D.PREVIEW_PIXEL
                        || warp.destinationSpace()
                        != CoordinateSpace2D.PREVIEW_PIXEL
                        || warp.previewWidth()
                        != basis.previewDimensions().width()
                        || warp.previewHeight()
                        != basis.previewDimensions().height()) {
                    throw new IllegalArgumentException(
                            "Guided outline warp must use the active review preview coordinate domain");
                }
            });
            final AffineTransform2D baselineAtlasToPreview =
                    atlasOrientation(basis, orientation)
                    .andThen(basis.proposal().affine());
            final AffineTransform2D previewAdjustment =
                    baselineAtlasToPreview.inverse()
                    .andThen(candidateAtlasToPreview);
            if (previewAdjustment.determinant() <= 0) {
                throw new IllegalArgumentException(
                        "Guided outline adjustment must preserve the explicit reflection decision");
            }
            return new AlignmentReviewContent(
                    level, tilt, current.workflowMode(),
                    orientation, observedHemisphere,
                    current.reviewSectionMode(),
                    current.manualSidePlacement(),
                    previewAdjustment, outlineWarp,
                    outlineAnchorsConfirmed,
                    AlignmentReviewContent.identityPreviewAdjustment(),
                    Optional.empty(), current.landmarks(), Optional.empty(),
                    current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(),
                    HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
        }
    }

    /**
     * Applies an explicitly reviewer-chosen visual starting plane without
     * attaching candidate ranking or mismatch evidence.
     */
    record ApplyGuidedManualStartingPlane(
            AllenCoronalLevel level,
            AtlasPlaneTilt tilt,
            AtlasOrientation orientation,
            ObservedAnatomicalHemisphere observedHemisphere,
            AffineTransform2D atlasToPreview,
            Optional<ReviewedOutlineTransform2D> outlineWarp,
            boolean outlineAnchorsConfirmed,
            String method,
            List<String> tissueContourIds,
            String sourceSha256,
            String atlasSha256) implements ReviewEdit {

        public ApplyGuidedManualStartingPlane {
            level = Objects.requireNonNull(level, "level");
            tilt = Objects.requireNonNull(tilt, "tilt");
            orientation = Objects.requireNonNull(orientation, "orientation");
            observedHemisphere = Objects.requireNonNull(
                    observedHemisphere, "observedHemisphere");
            atlasToPreview = Objects.requireNonNull(
                    atlasToPreview, "atlasToPreview");
            outlineWarp = Objects.requireNonNull(
                    outlineWarp, "outlineWarp");
            method = requireText(method, "method");
            tissueContourIds = Objects.requireNonNull(
                    tissueContourIds, "tissueContourIds").stream()
                    .map(identifier -> requireText(
                            identifier, "tissueContourId"))
                    .toList();
            if (new java.util.HashSet<>(tissueContourIds).size()
                    != tissueContourIds.size()) {
                throw new IllegalArgumentException(
                        "Tissue contour identifiers must be unique");
            }
            sourceSha256 = requireSha256(sourceSha256, "sourceSha256");
            atlasSha256 = requireSha256(atlasSha256, "atlasSha256");
            if (!orientation.confirmed()) {
                throw new IllegalArgumentException(
                        "The visual starting plane requires an explicit atlas orientation");
            }
            if (observedHemisphere == ObservedAnatomicalHemisphere.UNSURE) {
                throw new IllegalArgumentException(
                        "The visual starting plane requires an explicit observed hemisphere");
            }
            if (atlasToPreview.sourceSpace()
                    != CoordinateSpace2D.ATLAS_PLANE_PIXEL
                    || atlasToPreview.destinationSpace()
                    != CoordinateSpace2D.PREVIEW_PIXEL) {
                throw new IllegalArgumentException(
                        "Visual starting-plane transform must map atlas-plane pixels to preview pixels");
            }
            if (outlineWarp.isPresent() != outlineAnchorsConfirmed) {
                throw new IllegalArgumentException(
                        "Outline-warp presence and confirmed-anchor state must agree");
            }
            if (outlineWarp.isPresent() && tissueContourIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "A supplied outline warp requires its completed tissue contour identity");
            }
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.APPLY_GUIDED_MANUAL_STARTING_PLANE;
        }

        /** Stable identity of the exact reviewer-chosen atlas transform. */
        public String startingTransformSha256() {
            return affineSha256(method, atlasToPreview);
        }

        @Override public String description() {
            return "Apply reviewer-chosen visual starting plane: level="
                    + level.zeroBasedAnteriorPosteriorIndex()
                    + ", sagittal=" + tilt.sagittalDegrees()
                    + "°, horizontal=" + tilt.horizontalDegrees()
                    + "°, orientation=" + orientation
                    + ", observedHemisphere=" + observedHemisphere
                    + ", method=" + method
                    + ", tissueContours=" + tissueContourIds
                    + ", sourceSha256=" + sourceSha256
                    + ", atlasSha256=" + atlasSha256
                    + ", atlasToPreview=["
                    + atlasToPreview.m00() + ","
                    + atlasToPreview.m01() + ","
                    + atlasToPreview.m02() + ";"
                    + atlasToPreview.m10() + ","
                    + atlasToPreview.m11() + ","
                    + atlasToPreview.m12() + "]"
                    + ", startingTransformSha256="
                    + startingTransformSha256()
                    + outlineWarp.map(warp -> ", outlineWarpSha256="
                            + warp.contentSha256()
                            + ", outlineWarpAlgorithm="
                            + warp.algorithmRevision()
                            + ", outlineAnchorsConfirmed="
                            + outlineAnchorsConfirmed).orElse(
                                    ", outlineWarp=none")
                    + "; manual visual choice only, no ranking evidence";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (!sourceSha256.equals(basis.sourceSnapshot().pixelSha256())) {
                throw new IllegalArgumentException(
                        "Visual starting-plane source identity does not match the active review basis");
            }
            if (!atlasSha256.equals(basis.atlas().identitySha256())) {
                throw new IllegalArgumentException(
                        "Visual starting-plane atlas identity does not match the active review basis");
            }
            outlineWarp.ifPresent(warp -> {
                if (basis.proposal().geometry().geometry()
                        != SectionGeometry.FULL
                        || observedHemisphere
                        != ObservedAnatomicalHemisphere.BOTH) {
                    throw new IllegalArgumentException(
                            "A manual outline warp is permitted only for a complete FULL bilateral section");
                }
                if (warp.sourceSpace() != CoordinateSpace2D.PREVIEW_PIXEL
                        || warp.destinationSpace()
                        != CoordinateSpace2D.PREVIEW_PIXEL
                        || warp.previewWidth()
                        != basis.previewDimensions().width()
                        || warp.previewHeight()
                        != basis.previewDimensions().height()) {
                    throw new IllegalArgumentException(
                            "Guided outline warp must use the active review preview coordinate domain");
                }
            });
            final AffineTransform2D baselineAtlasToPreview =
                    atlasOrientation(basis, orientation)
                    .andThen(basis.proposal().affine());
            final AffineTransform2D previewAdjustment =
                    baselineAtlasToPreview.inverse().andThen(atlasToPreview);
            if (previewAdjustment.determinant() <= 0) {
                throw new IllegalArgumentException(
                        "Visual starting-plane adjustment must preserve the explicit reflection decision");
            }
            return new AlignmentReviewContent(
                    level, tilt, current.workflowMode(), orientation,
                    observedHemisphere, current.reviewSectionMode(),
                    current.manualSidePlacement(),
                    previewAdjustment, outlineWarp,
                    outlineAnchorsConfirmed,
                    AlignmentReviewContent.identityPreviewAdjustment(),
                    Optional.empty(), current.landmarks(), Optional.empty(),
                    current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(),
                    HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
        }
    }

    record SetAtlasOrientation(AtlasOrientation orientation)
            implements ReviewEdit {

        public SetAtlasOrientation {
            orientation = Objects.requireNonNull(
                    orientation, "orientation");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.SET_ATLAS_ORIENTATION;
        }

        @Override
        public String description() {
            return "Set atlas orientation to " + orientation;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.orientation().equals(orientation)) {
                return current;
            }
            final SectionGeometry geometry =
                    basis.proposal().geometry().geometry();
            return new AlignmentReviewContent(
                    current.coronalLevel(),
                    current.atlasPlaneTilt(),
                    current.workflowMode(),
                    orientation,
                    hemisphereForOrientation(
                            current.observedHemisphere(),
                            orientation,
                            geometry),
                    current.reviewSectionMode(),
                    ManualSidePlacement2D.identity(),
                    current.manualPreviewAdjustment(),
                    Optional.empty(),
                    false,
                    AlignmentReviewContent.identityPreviewAdjustment(),
                    Optional.empty(), current.landmarks(), Optional.empty(),
                    current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(),
                    HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
        }
    }

    record SetObservedHemisphere(
            ObservedAnatomicalHemisphere hemisphere)
            implements ReviewEdit {

        public SetObservedHemisphere {
            hemisphere = Objects.requireNonNull(
                    hemisphere, "hemisphere");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.SET_OBSERVED_HEMISPHERE;
        }

        @Override
        public String description() {
            return "Set observed anatomical hemisphere to "
                    + hemisphere;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            final SectionGeometry geometry =
                    basis.proposal().geometry().geometry();
            final boolean reviewerChoosesHemisphere =
                    current.reviewSectionMode() == ReviewSectionMode.HALF
                    || geometry == SectionGeometry.PARTIAL_OR_DAMAGED
                    || geometry == SectionGeometry.IMAGE_LEFT_HALF
                    || geometry == SectionGeometry.IMAGE_RIGHT_HALF;
            if (!reviewerChoosesHemisphere
                    || hemisphere == ObservedAnatomicalHemisphere.BOTH) {
                throw new IllegalArgumentException(
                        "Manual hemisphere choice is only valid for half, partial, or damaged tissue");
            }
            return new AlignmentReviewContent(
                    current.coronalLevel(),
                    current.atlasPlaneTilt(),
                    current.workflowMode(),
                    current.orientation(),
                    hemisphere,
                    current.reviewSectionMode(),
                    current.manualSidePlacement(),
                    current.manualPreviewAdjustment(),
                    current.outlineWarp(),
                    current.outlineAnchorsConfirmed(),
                    current.postOutlinePreviewAdjustment(),
                    current.hemisphereWarp(), current.landmarks(),
                    current.localWarp(), current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(),
                    HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
        }
    }

    /**
     * Selects the reviewer workflow geometry without rewriting automatic
     * tissue classification or confidence evidence. Incompatible local
     * geometry is cleared atomically and remains recoverable through Undo.
     */
    record SetReviewSectionMode(ReviewSectionMode mode)
            implements ReviewEdit {

        public SetReviewSectionMode {
            mode = Objects.requireNonNull(mode, "mode");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.SET_REVIEW_SECTION_MODE;
        }

        @Override
        public String description() {
            return "Set reviewer section mode to " + mode;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.reviewSectionMode() == mode) {
                return current;
            }
            final ObservedAnatomicalHemisphere reviewedHemisphere =
                    mode == ReviewSectionMode.HALF
                            ? ObservedAnatomicalHemisphere.UNSURE
                            : ObservedAnatomicalHemisphere.BOTH;
            return new AlignmentReviewContent(
                    current.coronalLevel(), current.atlasPlaneTilt(),
                    current.workflowMode(), current.orientation(),
                    reviewedHemisphere, mode,
                    ManualSidePlacement2D.identity(),
                    current.manualPreviewAdjustment(), Optional.empty(),
                    false,
                    AlignmentReviewContent.identityPreviewAdjustment(),
                    Optional.empty(), current.landmarks(), Optional.empty(),
                    current.reviewedTissueSupport(),
                    current.reviewedTissueSupport().isPresent()
                            && mode.clipsToTissueByDefault(),
                    HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
        }
    }

    /** Changes the accepted atlas-side footprint for a Half review. */
    record SetHalfAtlasCoverage(HalfAtlasCoverage coverage)
            implements ReviewEdit {

        public SetHalfAtlasCoverage {
            coverage = Objects.requireNonNull(coverage, "coverage");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.SET_HALF_ATLAS_COVERAGE;
        }

        @Override
        public String description() {
            return coverage.includesOppositeRemnant()
                    ? "Include the reviewed opposite-side Half remnant"
                    : "Limit the Half review to its confirmed visible side";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.reviewSectionMode() != ReviewSectionMode.HALF) {
                throw new IllegalArgumentException(
                        "Half atlas coverage is available only in Half mode");
            }
            if (current.observedHemisphere()
                    == ObservedAnatomicalHemisphere.UNSURE
                    || current.observedHemisphere()
                    == ObservedAnatomicalHemisphere.BOTH) {
                throw new IllegalArgumentException(
                        "Choose the visible anatomical side before changing Half atlas coverage");
            }
            if (coverage == current.halfAtlasCoverage()) {
                throw new IllegalArgumentException(
                        "Half atlas coverage is already " + coverage);
            }
            return new AlignmentReviewContent(
                    current.coronalLevel(), current.atlasPlaneTilt(),
                    current.workflowMode(), current.orientation(),
                    current.observedHemisphere(), current.reviewSectionMode(),
                    current.manualSidePlacement(),
                    current.manualPreviewAdjustment(), current.outlineWarp(),
                    current.outlineAnchorsConfirmed(),
                    current.postOutlinePreviewAdjustment(),
                    current.hemisphereWarp(), current.landmarks(),
                    current.localWarp(), current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(), coverage);
        }
    }

    record AddLandmark(LandmarkPair landmark)
            implements ReviewEdit {

        public AddLandmark {
            landmark = Objects.requireNonNull(landmark, "landmark");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.ADD_LANDMARK;
        }

        @Override
        public String description() {
            return "Add landmark " + landmark.id();
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (landmark.anatomicalHandleMetadata().isPresent()) {
                throw new IllegalArgumentException(
                        "Side-local manual controls must use ManualWarpControl, not LandmarkPair");
            }
            basis.requireValidLandmark(landmark);
            final List<LandmarkPair> landmarks =
                    new ArrayList<>(current.landmarks());
            landmarks.add(landmark);
            return withLandmarks(current, landmarks);
        }
    }

    /** Adds a deterministic set of control handles as one undoable edit. */
    record AddLandmarks(List<LandmarkPair> landmarks)
            implements ReviewEdit {

        public AddLandmarks {
            landmarks = List.copyOf(Objects.requireNonNull(
                    landmarks, "landmarks"));
            if (landmarks.isEmpty() || landmarks.stream()
                    .anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "Landmark batch must contain at least one pair");
            }
            final java.util.Set<String> ids = new java.util.HashSet<>();
            if (landmarks.stream().anyMatch(pair -> !ids.add(pair.id()))) {
                throw new IllegalArgumentException(
                        "Landmark batch identifiers must be unique");
            }
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.ADD_LANDMARKS;
        }

        @Override
        public String description() {
            return "Add " + landmarks.size()
                    + " atlas-boundary landmark handles";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (landmarks.stream().anyMatch(pair ->
                    pair.anatomicalHandleMetadata().isPresent())) {
                throw new IllegalArgumentException(
                        "Side-local manual controls must use ManualWarpControl, not LandmarkPair");
            }
            landmarks.forEach(basis::requireValidLandmark);
            final java.util.Set<String> ids = current.landmarks().stream()
                    .map(LandmarkPair::id)
                    .collect(java.util.stream.Collectors.toSet());
            if (landmarks.stream().anyMatch(pair -> !ids.add(pair.id()))) {
                throw new IllegalArgumentException(
                        "Landmark identifier already exists");
            }
            final List<LandmarkPair> combined =
                    new ArrayList<>(current.landmarks());
            combined.addAll(landmarks);
            return withLandmarks(current, combined);
        }
    }

    /**
     * Replaces one exact-plane, target-and-side set of typed FIT handles as a
     * single immutable review edit. Generic landmarks, CHECK landmarks, other
     * targets, the opposite side, and handles on other planes are retained.
     */
    record ReplaceAnatomicalHandles(List<LandmarkPair> landmarks)
            implements ReviewEdit {

        public ReplaceAnatomicalHandles {
            landmarks = List.copyOf(Objects.requireNonNull(
                    landmarks, "landmarks"));
            if (landmarks.size() < 4 || landmarks.size() > 24
                    || landmarks.stream()
                    .anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException(
                        "Replacement handle batch must contain 4 to 24 pairs");
            }
            final java.util.Set<String> ids = new java.util.HashSet<>();
            if (landmarks.stream().anyMatch(pair -> !ids.add(pair.id()))) {
                throw new IllegalArgumentException(
                        "Replacement handle identifiers must be unique");
            }
            final java.util.Set<Point2D> atlasPoints =
                    new java.util.HashSet<>();
            if (landmarks.stream().anyMatch(pair ->
                    !atlasPoints.add(pair.atlasPoint()))) {
                throw new IllegalArgumentException(
                        "Replacement handle atlas points must be unique");
            }
            final LandmarkPair first = landmarks.get(0);
            final AnatomicalHandleMetadata target = requireReplacementHandle(
                    first);
            if (target.atlasSide() == AtlasAnatomicalSide.MIDLINE) {
                throw new IllegalArgumentException(
                        "A replacement FIT batch must target atlas left or atlas right");
            }
            for (final LandmarkPair pair : landmarks) {
                final AnatomicalHandleMetadata metadata =
                        requireReplacementHandle(pair);
                if (!pair.coronalLevel().equals(first.coronalLevel())
                        || !pair.atlasPlaneTilt().equals(
                        first.atlasPlaneTilt())
                        || metadata.atlasRegionId()
                        != target.atlasRegionId()
                        || !metadata.guideAcronym().equals(
                        target.guideAcronym())
                        || metadata.atlasSide() != target.atlasSide()
                        || !metadata.selectedBoundarySha256().equals(
                        target.selectedBoundarySha256())) {
                    throw new IllegalArgumentException(
                            "Replacement handles must share one exact plane, guide, region, atlas side, and boundary identity");
                }
            }
            throw new IllegalArgumentException(
                    "Typed LandmarkPair control batches are retired; use ReplaceManualWarpControlGroup");
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.REPLACE_ANATOMICAL_HANDLES;
        }

        @Override public String description() {
            final AnatomicalHandleMetadata target = landmarks.get(0)
                    .anatomicalHandleMetadata().orElseThrow();
            return "Replace exact-plane " + target.guideAcronym()
                    + " " + target.atlasSide() + " handles with "
                    + landmarks.size() + " verified-boundary controls";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            final LandmarkPair first = landmarks.get(0);
            final AnatomicalHandleMetadata target = first
                    .anatomicalHandleMetadata().orElseThrow();
            if (!first.coronalLevel().equals(current.coronalLevel())
                    || !first.atlasPlaneTilt().equals(
                    current.atlasPlaneTilt())) {
                throw new IllegalArgumentException(
                        "Replacement handles must belong to the current exact atlas plane");
            }
            landmarks.forEach(pair -> {
                basis.requireValidLandmark(pair);
                requireTypedAtlasSide(pair, basis);
            });
            final boolean staleBoundary = current.landmarks().stream()
                    .filter(pair -> isSameActiveHandleTarget(
                            pair, current, target))
                    .map(pair -> pair.anatomicalHandleMetadata()
                            .orElseThrow())
                    .anyMatch(metadata ->
                            !metadata.selectedBoundarySha256().equals(
                            target.selectedBoundarySha256()));
            if (staleBoundary) {
                throw new IllegalArgumentException(
                        "Existing handles for this target and side use a different verified boundary identity");
            }
            final List<LandmarkPair> retained = current.landmarks().stream()
                    .filter(pair -> !isReplacedHandle(
                            pair, current, target))
                    .collect(java.util.stream.Collectors.toCollection(
                            ArrayList::new));
            final java.util.Set<String> retainedIds = retained.stream()
                    .map(LandmarkPair::id)
                    .collect(java.util.stream.Collectors.toSet());
            if (landmarks.stream().anyMatch(pair ->
                    !retainedIds.add(pair.id()))) {
                throw new IllegalArgumentException(
                        "Replacement handle identifier conflicts with a preserved landmark");
            }
            retained.addAll(landmarks);
            return withLandmarks(current, retained);
        }
    }

    record MoveLandmarkAtlasPoint(
            String landmarkId,
            Point2D atlasPoint) implements ReviewEdit {

        public MoveLandmarkAtlasPoint {
            landmarkId = requireId(landmarkId);
            atlasPoint = Objects.requireNonNull(
                    atlasPoint, "atlasPoint");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.MOVE_LANDMARK_ATLAS_POINT;
        }

        @Override
        public String description() {
            return "Move atlas point for landmark " + landmarkId;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            requireGenericLandmark(
                    current, landmarkId,
                    "Typed anatomical-handle atlas endpoints are immutable");
            basis.requireAtlasPoint(atlasPoint);
            return replaceLandmark(
                    current,
                    landmarkId,
                    old -> new LandmarkPair(
                            old.id(), old.coronalLevel(), old.atlasPlaneTilt(),
                            atlasPoint, old.previewPoint(), old.role(),
                            old.anatomicalHandleMetadata()));
        }
    }

    record MoveLandmarkPreviewPoint(
            String landmarkId,
            Point2D previewPoint) implements ReviewEdit {

        public MoveLandmarkPreviewPoint {
            landmarkId = requireId(landmarkId);
            previewPoint = Objects.requireNonNull(
                    previewPoint, "previewPoint");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.MOVE_LANDMARK_PREVIEW_POINT;
        }

        @Override
        public String description() {
            return "Move preview point for landmark " + landmarkId;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            requireGenericLandmark(
                    current, landmarkId,
                    "Typed anatomical-handle tissue endpoints must use atomic move/refit");
            basis.previewDimensions().requireContains(
                    previewPoint, "Tissue landmark point");
            return replaceLandmark(
                    current,
                    landmarkId,
                    old -> new LandmarkPair(
                            old.id(), old.coronalLevel(), old.atlasPlaneTilt(),
                            old.atlasPoint(), previewPoint, old.role(),
                            old.anatomicalHandleMetadata()));
        }
    }

    record SetLandmarkRole(
            String landmarkId,
            LandmarkRole role) implements ReviewEdit {

        public SetLandmarkRole {
            landmarkId = requireId(landmarkId);
            role = Objects.requireNonNull(role, "role");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.SET_LANDMARK_ROLE;
        }

        @Override
        public String description() {
            return "Set landmark " + landmarkId + " role to " + role;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            final LandmarkPair existing = current.landmarks().stream()
                    .filter(pair -> pair.id().equals(landmarkId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown landmark: " + landmarkId));
            if (existing.role() == LandmarkRole.FIT
                    && role == LandmarkRole.CHECK
                    && !current.manualPreviewAdjustment().equals(
                    AlignmentReviewContent.identityPreviewAdjustment())) {
                throw new IllegalArgumentException(
                        "A CHECK point must be designated before reviewer global adjustment; Undo or Reset first, or add a new held-out CHECK pair");
            }
            return replaceLandmark(
                    current,
                    landmarkId,
                    old -> new LandmarkPair(
                            old.id(), old.coronalLevel(), old.atlasPlaneTilt(),
                            old.atlasPoint(), old.previewPoint(), role,
                            old.anatomicalHandleMetadata()));
        }
    }

    record RemoveLandmark(String landmarkId)
            implements ReviewEdit {

        public RemoveLandmark {
            landmarkId = requireId(landmarkId);
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.REMOVE_LANDMARK;
        }

        @Override
        public String description() {
            return "Remove landmark " + landmarkId;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            final List<LandmarkPair> remaining = current.landmarks()
                    .stream()
                    .filter(landmark -> !landmark.id().equals(landmarkId))
                    .toList();
            if (remaining.size() == current.landmarks().size()) {
                throw new IllegalArgumentException(
                        "Unknown landmark: " + landmarkId);
            }
            return withLandmarks(current, remaining);
        }
    }

    /**
     * Fits a positive-scale preview-space similarity correction from every
     * active pair at the current level. Reflection remains controlled solely
     * by {@link SetAtlasOrientation}.
     */
    record FitActiveLandmarks() implements ReviewEdit {

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.FIT_ACTIVE_LANDMARKS;
        }

        @Override
        public String description() {
            return "Fit preview-space similarity from active landmark pairs";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.outlineWarp().orElse(null)
                    instanceof BoundaryAuthoritativeTransform2D) {
                throw new IllegalArgumentException(
                        "The finished tissue outline is authoritative; global landmark-similarity fitting is disabled");
            }
            if (!current.activeAnatomicalFitLandmarks().isEmpty()) {
                throw new IllegalArgumentException(
                        "Typed anatomical handles are reserved for hemisphere-local refinement");
            }
            final List<LandmarkPair> active =
                    current.activeGenericFitLandmarks();
            active.forEach(basis::requireValidLandmark);
            final List<Point2D> currentAtlasPoints = active.stream()
                    .map(landmark -> mapBeforeLocalWarp(
                            current, basis, landmark.atlasPoint()))
                    .toList();
            final List<Point2D> observedPreviewPoints = active.stream()
                    .map(LandmarkPair::previewPoint)
                    .toList();
            final AffineTransform2D correction =
                    LandmarkSimilaritySolver.fitPreviewCorrection(
                            currentAtlasPoints,
                            observedPreviewPoints).asAffine();
            return withPreviewCorrection(current, correction);
        }
    }

    /**
     * Fits a non-reflecting global affine preview correction from every active
     * pair. This remains a reviewer-triggered global transform; it is not
     * non-linear regional warping and cannot change the atlas plane,
     * laterality, or explicit reflection decision.
     */
    record FitActiveLandmarksAffine() implements ReviewEdit {

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.FIT_ACTIVE_LANDMARKS_AFFINE;
        }

        @Override
        public String description() {
            return "Fit global affine preview correction from active landmark pairs";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (!current.activeAnatomicalFitLandmarks().isEmpty()) {
                throw new IllegalArgumentException(
                        "Typed anatomical handles are reserved for hemisphere-local refinement");
            }
            final List<LandmarkPair> active =
                    current.activeGenericFitLandmarks();
            active.forEach(basis::requireValidLandmark);
            final List<Point2D> currentAtlasPoints = active.stream()
                    .map(landmark -> mapBeforeLocalWarp(
                            current, basis, landmark.atlasPoint()))
                    .toList();
            final List<Point2D> observedPreviewPoints = active.stream()
                    .map(LandmarkPair::previewPoint)
                    .toList();
            final AffineTransform2D correction =
                    LandmarkAffineSolver.fitPreviewCorrection(
                            currentAtlasPoints, observedPreviewPoints);
            return withPreviewCorrection(current, correction);
        }
    }

    /**
     * Fits the frozen, topology-checked atlas-only local correction from FIT
     * pairs. CHECK pairs remain excluded and are used only for diagnostics.
     */
    record FitActiveLandmarksLocalWarp() implements ReviewEdit {

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.FIT_ACTIVE_LANDMARKS_LOCAL_WARP;
        }

        @Override
        public String description() {
            return "Fit MANUAL_LOCAL_WARP from exact-plane FIT landmarks";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.outlineWarp().orElse(null)
                    instanceof BoundaryAuthoritativeTransform2D) {
                throw new IllegalArgumentException(
                        "The finished tissue outline permits only boundary-pinned side-mesh refinement; generic ConstrainedLocalWarp2D fitting is disabled");
            }
            if (!current.activeAnatomicalFitLandmarks().isEmpty()) {
                throw new IllegalArgumentException(
                        "Typed anatomical handles use the hemisphere-local warp; generic local fitting accepts only untyped FIT landmarks");
            }
            if (current.hemisphereWarp().isPresent()) {
                throw new IllegalArgumentException(
                        "Clear the hemisphere-local warp before fitting a generic local warp");
            }
            final List<LandmarkPair> fit = current.activeGenericFitLandmarks();
            final List<LandmarkPair> check = current.activeCheckLandmarks();
            fit.forEach(basis::requireValidLandmark);
            check.forEach(basis::requireValidLandmark);
            final ConstrainedLocalWarp2D warp = ConstrainedLocalWarp2D.fit(
                    fit.stream().map(pair -> mapBeforeLocalWarp(
                            current, basis, pair.atlasPoint()))
                            .toList(),
                    fit.stream().map(LandmarkPair::previewPoint).toList(),
                    check.stream().map(pair -> mapBeforeLocalWarp(
                            current, basis, pair.atlasPoint()))
                            .toList(),
                    check.stream().map(LandmarkPair::previewPoint).toList(),
                    basis.previewDimensions().width(),
                    basis.previewDimensions().height());
            return new AlignmentReviewContent(
                    current.coronalLevel(), current.atlasPlaneTilt(),
                    current.workflowMode(), current.orientation(),
                    current.observedHemisphere(),
                    current.reviewSectionMode(),
                    current.manualSidePlacement(),
                    current.manualPreviewAdjustment(), current.outlineWarp(),
                    current.outlineAnchorsConfirmed(),
                    current.postOutlinePreviewAdjustment(),
                    Optional.empty(), current.landmarks(),
                    java.util.Optional.of(warp), current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(),
                    current.halfAtlasCoverage());
        }
    }

    /**
     * Moves one verified-boundary tissue handle and atomically refits the
     * independently bounded atlas-left/atlas-right fields. CHECK points remain
     * held out. A failed topology check throws before content is returned, so
     * the session cannot append a partial revision.
     */
    record MoveAnatomicalHandleAndRefit(
            String landmarkId,
            Point2D previewPoint) implements ReviewEdit {

        public MoveAnatomicalHandleAndRefit {
            landmarkId = requireId(landmarkId);
            previewPoint = Objects.requireNonNull(
                    previewPoint, "previewPoint");
            throw new IllegalArgumentException(
                    "Typed LandmarkPair handle refitting is retired; use MoveManualWarpControlAndInstall");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.MOVE_ANATOMICAL_HANDLE_AND_REFIT;
        }

        @Override
        public String description() {
            return "Move typed anatomical handle " + landmarkId
                    + " and refit MANUAL_HEMISPHERE_WARP";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            basis.previewDimensions().requireContains(
                    previewPoint, "Anatomical handle tissue point");
            final List<LandmarkPair> moved = new ArrayList<>(
                    current.landmarks());
            boolean found = false;
            for (int index = 0; index < moved.size(); index++) {
                final LandmarkPair pair = moved.get(index);
                if (!pair.id().equals(landmarkId)) {
                    continue;
                }
                if (pair.anatomicalHandleMetadata().isEmpty()) {
                    throw new IllegalArgumentException(
                            "Only a typed anatomical handle can use atomic move/refit");
                }
                if (pair.anatomicalHandleMetadata().orElseThrow().atlasSide()
                        == AtlasAnatomicalSide.MIDLINE) {
                    throw new IllegalArgumentException(
                            "The anatomical midline is fixed; use it as a CHECK reference rather than a movable hemisphere handle");
                }
                if (!pair.coronalLevel().equals(current.coronalLevel())
                        || !pair.atlasPlaneTilt().equals(
                        current.atlasPlaneTilt())) {
                    throw new IllegalArgumentException(
                            "Anatomical handle is inactive on the current atlas plane");
                }
                moved.set(index, pair.withPreviewPoint(previewPoint));
                found = true;
                break;
            }
            if (!found) {
                throw new IllegalArgumentException(
                        "Unknown landmark: " + landmarkId);
            }
            final AlignmentReviewContent movedContent =
                    withLandmarks(current, moved);
            if (movedContent.outlineWarp().isEmpty()
                    || !movedContent.outlineAnchorsConfirmed()) {
                throw new IllegalArgumentException(
                        "Apply and confirm the full-section outline warp before local hemisphere refinement");
            }
            final List<LandmarkPair> fit =
                    movedContent.activeAnatomicalFitLandmarks();
            final List<LandmarkPair> check =
                    movedContent.activeCheckLandmarks();
            fit.forEach(basis::requireValidLandmark);
            check.forEach(basis::requireValidLandmark);
            fit.forEach(pair -> requireTypedAtlasSide(pair, basis));
            check.stream().filter(pair -> pair.anatomicalHandleMetadata()
                    .isPresent()).forEach(pair ->
                    requireTypedAtlasSide(pair, basis));
            final List<LandmarkPair> left = fit.stream().filter(pair ->
                    pair.anatomicalHandleMetadata().orElseThrow().atlasSide()
                            == AtlasAnatomicalSide.ATLAS_LEFT).toList();
            final List<LandmarkPair> right = fit.stream().filter(pair ->
                    pair.anatomicalHandleMetadata().orElseThrow().atlasSide()
                            == AtlasAnatomicalSide.ATLAS_RIGHT).toList();
            if (left.size() + right.size() != fit.size()) {
                throw new IllegalArgumentException(
                        "Only atlas-left and atlas-right handles may drive a hemisphere warp");
            }
            final ManualHemisphereWarp2D.MidlineSegment imageMidline =
                    hemisphereMidline(movedContent);
            final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                    left.stream().map(pair -> mapBeforeHemisphereWarp(
                            movedContent, basis, pair.atlasPoint())).toList(),
                    left.stream().map(LandmarkPair::previewPoint).toList(),
                    right.stream().map(pair -> mapBeforeHemisphereWarp(
                            movedContent, basis, pair.atlasPoint())).toList(),
                    right.stream().map(LandmarkPair::previewPoint).toList(),
                    movedContent.orientation(), imageMidline.dorsal(),
                    imageMidline.ventral(),
                    basis.previewDimensions().width(),
                    basis.previewDimensions().height());
            return new AlignmentReviewContent(
                    movedContent.coronalLevel(),
                    movedContent.atlasPlaneTilt(),
                    movedContent.workflowMode(),
                    movedContent.orientation(),
                    movedContent.observedHemisphere(),
                    movedContent.reviewSectionMode(),
                    movedContent.manualSidePlacement(),
                    movedContent.manualPreviewAdjustment(),
                    movedContent.outlineWarp(),
                    movedContent.outlineAnchorsConfirmed(),
                    movedContent.postOutlinePreviewAdjustment(),
                    Optional.of(warp), movedContent.landmarks(),
                    Optional.empty(), movedContent.reviewedTissueSupport(),
                    movedContent.tissueClippingEnabled(),
                    movedContent.halfAtlasCoverage());
        }
    }

    /**
     * Atomically replaces the complete reviewer-control set. This is used for
     * the initial bilateral contrast suggestion and explicit Re-suggest.
     */
    record ReplaceAllManualWarpControlsAndInstall(
            List<ManualWarpControl> replacement,
            ManualWarpPrecondition precondition,
            ManualHemisphereWarp2D validatedWarp) implements ReviewEdit {

        public ReplaceAllManualWarpControlsAndInstall {
            replacement = List.copyOf(Objects.requireNonNull(
                    replacement, "replacement"));
            precondition = Objects.requireNonNull(
                    precondition, "precondition");
            validatedWarp = Objects.requireNonNull(
                    validatedWarp, "validatedWarp");
            if (replacement.isEmpty()) {
                throw new IllegalArgumentException(
                        "The bilateral manual-warp suggestion is empty");
            }
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.REPLACE_ALL_MANUAL_WARP_CONTROLS;
        }

        @Override public String description() {
            return "Replace all reviewer-controlled manual-warp points with "
                    + replacement.size() + " contrast-suggested controls";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            precondition.requireMatches(current, basis);
            requireInstalledControlSet(replacement, validatedWarp);
            return withHemisphereWarp(current, Optional.of(validatedWarp));
        }
    }

    /**
     * Atomically replaces one origin/target control group on one anatomical
     * side with an already safety-audited reviewer-controlled local field.
     */
    record ReplaceManualWarpControlGroup(
            ManualHemisphereWarp2D.AtlasSide atlasSide,
            String groupId,
            List<ManualWarpControl> replacement,
            ManualWarpPrecondition precondition,
            ManualHemisphereWarp2D validatedWarp) implements ReviewEdit {

        public ReplaceManualWarpControlGroup {
            atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
            groupId = requireText(groupId, "groupId");
            replacement = List.copyOf(Objects.requireNonNull(
                    replacement, "replacement"));
            precondition = Objects.requireNonNull(
                    precondition, "precondition");
            validatedWarp = Objects.requireNonNull(
                    validatedWarp, "validatedWarp");
            boolean invalidControl = false;
            for (final ManualWarpControl control : replacement) {
                if (control == null || control.atlasSide() != atlasSide
                        || !control.groupId().equals(groupId)) {
                    invalidControl = true;
                    break;
                }
            }
            if (replacement.size() < 4 || replacement.size()
                    > ManualHemisphereWarp2D.MAXIMUM_CONTROLS_PER_GROUP
                    || invalidControl) {
                throw new IllegalArgumentException(
                        "A replacement manual-warp group requires 4 to 64 controls on one side with one group identity");
            }
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.REPLACE_MANUAL_WARP_CONTROL_GROUP;
        }

        @Override public String description() {
            return "Replace " + atlasSide + " manual-warp group "
                    + groupId + " with " + replacement.size()
                    + " controls and install audited REVIEWER_CONTROLLED_MANUAL_WARP";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            precondition.requireMatches(current, basis);
            final List<ManualWarpControl> expected = new ArrayList<>();
            current.hemisphereWarp().ifPresent(warp -> expected.addAll(
                    warp.controls().stream().filter(control ->
                            control.atlasSide() != atlasSide
                            || !control.groupId().equals(groupId)).toList()));
            expected.addAll(replacement);
            requireInstalledControlSet(expected, validatedWarp);
            return withHemisphereWarp(current, Optional.of(validatedWarp));
        }
    }

    /**
     * Replaces exactly one acronym-specific structure group. Legacy
     * {@code structure-guide} controls are identified by their stored
     * structure acronym, so the first edit adopts them without a snapshot
     * migration and without disturbing another structure group.
     */
    record ReplaceStructureWarpControlGroup(
            ManualHemisphereWarp2D.AtlasSide atlasSide,
            String structureAcronym,
            List<ManualWarpControl> replacement,
            ManualWarpPrecondition precondition,
            ManualHemisphereWarp2D validatedWarp) implements ReviewEdit {

        public ReplaceStructureWarpControlGroup {
            atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
            structureAcronym = requireText(
                    structureAcronym, "structureAcronym");
            replacement = List.copyOf(Objects.requireNonNull(
                    replacement, "replacement"));
            precondition = Objects.requireNonNull(precondition, "precondition");
            validatedWarp = Objects.requireNonNull(
                    validatedWarp, "validatedWarp");
            final String acronym = structureAcronym;
            final ManualHemisphereWarp2D.AtlasSide checkedSide = atlasSide;
            final boolean invalid = replacement.stream().anyMatch(control ->
                    control == null || control.atlasSide() != checkedSide
                            || control.origin()
                            != org.atlasalign.application.manual
                                    .ManualWarpControlOrigin.STRUCTURE_GUIDE
                            || !control.structureAcronym().equals(acronym));
            if (replacement.size() < 4 || replacement.size()
                    > ManualHemisphereWarp2D.MAXIMUM_CONTROLS_PER_GROUP
                    || invalid) {
                throw new IllegalArgumentException(
                        "A structure control group requires 4 to 64 controls for one acronym and side");
            }
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.REPLACE_STRUCTURE_WARP_CONTROL_GROUP;
        }

        @Override public String description() {
            return "Replace " + atlasSide + " " + structureAcronym
                    + " structure-warp group with " + replacement.size()
                    + " controls in REVIEWER_CONTROLLED_MANUAL_WARP";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            precondition.requireMatches(current, basis);
            final List<ManualWarpControl> expected = controlsExceptStructure(
                    current, atlasSide, structureAcronym);
            expected.addAll(replacement);
            requireInstalledControlSet(expected, validatedWarp);
            return withHemisphereWarp(current, Optional.of(validatedWarp));
        }
    }

    /** Installs one audited paired-dot calculation as one undoable revision. */
    record TransformStructureWarpControlGroup(
            ManualHemisphereWarp2D.AtlasSide atlasSide,
            String structureAcronym,
            List<ManualWarpControl> replacement,
            ManualWarpPrecondition precondition,
            ManualHemisphereWarp2D validatedWarp) implements ReviewEdit {

        public TransformStructureWarpControlGroup {
            atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
            structureAcronym = requireText(
                    structureAcronym, "structureAcronym");
            replacement = List.copyOf(Objects.requireNonNull(
                    replacement, "replacement"));
            precondition = Objects.requireNonNull(precondition, "precondition");
            validatedWarp = Objects.requireNonNull(
                    validatedWarp, "validatedWarp");
            final String acronym = structureAcronym;
            final ManualHemisphereWarp2D.AtlasSide checkedSide = atlasSide;
            final boolean invalid = replacement.stream().anyMatch(control ->
                    control == null || control.atlasSide() != checkedSide
                            || control.origin()
                            != org.atlasalign.application.manual
                                    .ManualWarpControlOrigin.STRUCTURE_GUIDE
                            || !control.structureAcronym().equals(acronym));
            if (replacement.size() < 4 || replacement.size()
                    > ManualHemisphereWarp2D.MAXIMUM_CONTROLS_PER_GROUP
                    || invalid) {
                throw new IllegalArgumentException(
                        "A transformed structure group requires 4 to 64 controls for one acronym and side");
            }
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.TRANSFORM_STRUCTURE_WARP_CONTROL_GROUP;
        }

        @Override public String description() {
            return "Install calculated valid preview for " + atlasSide
                    + " " + structureAcronym + " structure-warp group"
                    + " in REVIEWER_CONTROLLED_MANUAL_WARP";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            precondition.requireMatches(current, basis);
            requireTargetOnlyStructureReplacement(current, atlasSide,
                    structureAcronym, replacement);
            final List<ManualWarpControl> expected = controlsExceptStructure(
                    current, atlasSide, structureAcronym);
            expected.addAll(replacement);
            requireInstalledControlSet(expected, validatedWarp);
            return withHemisphereWarp(current, Optional.of(validatedWarp));
        }
    }

    /** Removes only one acronym-specific structure group. */
    record ClearStructureWarpControlGroup(
            ManualHemisphereWarp2D.AtlasSide atlasSide,
            String structureAcronym,
            ManualWarpPrecondition precondition,
            Optional<ManualHemisphereWarp2D> validatedRemainingWarp)
            implements ReviewEdit {

        public ClearStructureWarpControlGroup {
            atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
            structureAcronym = requireText(
                    structureAcronym, "structureAcronym");
            precondition = Objects.requireNonNull(precondition, "precondition");
            validatedRemainingWarp = Objects.requireNonNull(
                    validatedRemainingWarp, "validatedRemainingWarp");
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.CLEAR_STRUCTURE_WARP_CONTROL_GROUP;
        }

        @Override public String description() {
            return "Clear " + atlasSide + " " + structureAcronym
                    + " structure-warp controls only";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            precondition.requireMatches(current, basis);
            final List<ManualWarpControl> expected = controlsExceptStructure(
                    current, atlasSide, structureAcronym);
            if (current.hemisphereWarp().stream()
                    .flatMap(warp -> warp.controls().stream())
                    .noneMatch(control -> isStructureControl(
                            control, atlasSide, structureAcronym))) {
                throw new IllegalArgumentException(
                        "The selected structure has no controls to clear");
            }
            if (expected.isEmpty()) {
                if (validatedRemainingWarp.isPresent()) {
                    throw new IllegalArgumentException(
                            "Clearing the final structure group must clear the warp");
                }
            } else {
                requireInstalledControlSet(expected,
                        validatedRemainingWarp.orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Retained controls require a validated warp")));
            }
            return withHemisphereWarp(current, validatedRemainingWarp);
        }
    }

    /**
     * Atomically installs one reviewer-paired exterior-border control group.
     * The already audited field remains the sole side-local warp slot; these
     * pairs are manual geometry and never landmark or confidence evidence.
     */
    record ApplyManualBoundaryWarp(
            ManualHemisphereWarp2D.AtlasSide atlasSide,
            String groupId,
            List<ManualWarpControl> replacement,
            ManualWarpPrecondition precondition,
            ManualHemisphereWarp2D validatedWarp,
            String solverRevision,
            String includedPairHash) implements ReviewEdit {

        public ApplyManualBoundaryWarp {
            atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
            groupId = requireText(groupId, "groupId");
            replacement = List.copyOf(Objects.requireNonNull(
                    replacement, "replacement"));
            precondition = Objects.requireNonNull(
                    precondition, "precondition");
            validatedWarp = Objects.requireNonNull(
                    validatedWarp, "validatedWarp");
            solverRevision = requireText(
                    solverRevision, "solverRevision");
            includedPairHash = requireSha256(
                    includedPairHash, "includedPairHash");
            boolean invalid = false;
            for (final ManualWarpControl control : replacement) {
                if (control == null || control.atlasSide() != atlasSide
                        || !control.groupId().equals(groupId)
                        || control.origin()
                        != org.atlasalign.application.manual
                                .ManualWarpControlOrigin
                                .ATLAS_TISSUE_BOUNDARY_PAIR) {
                    invalid = true;
                    break;
                }
            }
            if (replacement.size() < 4 || replacement.size()
                    > org.atlasalign.application.manual
                            .ManualHemisphereWarp2D
                            .MAXIMUM_BOUNDARY_CONTROLS_PER_SIDE
                    || invalid) {
                throw new IllegalArgumentException(
                        "A manual outer-border warp requires 4 to 48 paired controls on one anatomical side");
            }
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.APPLY_MANUAL_BOUNDARY_WARP;
        }

        @Override public String description() {
            return "Apply " + replacement.size() + " reviewer-paired "
                    + atlasSide + " outer-border controls; solver="
                    + solverRevision + ", pairSha256=" + includedPairHash
                    + "; manual geometry only, confidence unchanged";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            precondition.requireMatches(current, basis);
            final List<ManualWarpControl> expected = new ArrayList<>();
            current.hemisphereWarp().ifPresent(warp -> expected.addAll(
                    warp.controls().stream().filter(control ->
                            control.atlasSide() != atlasSide
                                    || !control.groupId().equals(groupId))
                            .toList()));
            expected.addAll(replacement);
            requireInstalledControlSet(expected, validatedWarp);
            return withHemisphereWarp(current, Optional.of(validatedWarp));
        }
    }

    /** Adds one user-placed interior control and its audited field atomically. */
    record AddManualWarpControl(
            ManualWarpControl control,
            ManualWarpPrecondition precondition,
            ManualHemisphereWarp2D validatedWarp) implements ReviewEdit {

        public AddManualWarpControl {
            control = Objects.requireNonNull(control, "control");
            precondition = Objects.requireNonNull(
                    precondition, "precondition");
            validatedWarp = Objects.requireNonNull(
                    validatedWarp, "validatedWarp");
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.ADD_MANUAL_WARP_CONTROL;
        }

        @Override public String description() {
            return "Add one " + control.atlasSide()
                    + " interior manual-warp control and install audited REVIEWER_CONTROLLED_MANUAL_WARP";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            precondition.requireMatches(current, basis);
            final List<ManualWarpControl> expected = new ArrayList<>();
            current.hemisphereWarp().ifPresent(warp ->
                    expected.addAll(warp.controls()));
            if (expected.stream().filter(existing -> existing.atlasSide()
                    == control.atlasSide() && existing.origin()
                    != org.atlasalign.application.manual
                            .ManualWarpControlOrigin
                            .ATLAS_TISSUE_BOUNDARY_PAIR).count()
                    >= ManualHemisphereWarp2D
                            .MAXIMUM_REFINEMENT_CONTROLS_PER_SIDE) {
                throw new IllegalArgumentException(
                        "A manual warp supports at most "
                                + ManualHemisphereWarp2D
                                        .MAXIMUM_REFINEMENT_CONTROLS_PER_SIDE
                                + " interior and structure controls per side");
            }
            expected.add(control);
            requireInstalledControlSet(expected, validatedWarp);
            return withHemisphereWarp(current, Optional.of(validatedWarp));
        }
    }

    /**
     * Moves one tissue endpoint and installs the corresponding prevalidated
     * mesh in the same immutable review revision.
     */
    record MoveManualWarpControlAndInstall(
            String controlId,
            Point2D targetPoint,
            ManualWarpPrecondition precondition,
            ManualHemisphereWarp2D validatedWarp) implements ReviewEdit {

        public MoveManualWarpControlAndInstall {
            controlId = requireId(controlId);
            targetPoint = Objects.requireNonNull(targetPoint, "targetPoint");
            precondition = Objects.requireNonNull(
                    precondition, "precondition");
            validatedWarp = Objects.requireNonNull(
                    validatedWarp, "validatedWarp");
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.MOVE_MANUAL_WARP_CONTROL_AND_INSTALL;
        }

        @Override public String description() {
            return "Move manual-warp control " + controlId
                    + " and install audited REVIEWER_CONTROLLED_MANUAL_WARP";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            precondition.requireMatches(current, basis);
            final ManualHemisphereWarp2D prior = current.hemisphereWarp()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No reviewer-controlled manual warp is active"));
            boolean found = false;
            final List<ManualWarpControl> expected = new ArrayList<>();
            for (final ManualWarpControl existing : prior.controls()) {
                if (!existing.id().equals(controlId)) {
                    expected.add(existing);
                    continue;
                }
                expected.add(new ManualWarpControl(
                        existing.id(), existing.atlasSide(),
                        existing.origin(), existing.groupId(),
                        existing.structureAcronym(), existing.sourcePoint(),
                        targetPoint));
                found = true;
            }
            if (!found) {
                throw new IllegalArgumentException(
                        "Unknown manual-warp control: " + controlId);
            }
            requireInstalledControlSet(expected, validatedWarp);
            return withHemisphereWarp(current, Optional.of(validatedWarp));
        }
    }

    /**
     * Installs one reviewer-controlled coarse placement for a disjoined
     * atlas half. The local warp remains in tissue coordinates and is not
     * refit, so plane changes and half placement compose deterministically.
     */
    record SetManualSidePlacement(
            ManualHemisphereWarp2D.AtlasSide atlasSide,
            ManualSidePlacement2D placement) implements ReviewEdit {

        public SetManualSidePlacement {
            atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
            placement = Objects.requireNonNull(placement, "placement");
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.SET_MANUAL_SIDE_PLACEMENT;
        }

        @Override public String description() {
            return "Place disjoined " + atlasSide
                    + " atlas half independently";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.reviewSectionMode() != ReviewSectionMode.DISJOINED) {
                throw new IllegalArgumentException(
                        "Independent half placement is available only in Disjoined mode");
            }
            if (current.hemisphereWarp().isPresent()
                    || current.localWarp().isPresent()) {
                throw new IllegalArgumentException(
                        "Clear warp or Undo local changes before resizing an atlas half");
            }
            final ManualSidePlacement2D expected = current
                    .manualSidePlacement().withTransform(atlasSide,
                            placement.transform(atlasSide));
            if (!expected.equals(placement)) {
                throw new IllegalArgumentException(
                        "One half placement cannot change the opposite atlas side");
            }
            final AlignmentReviewContent updated = withManualSidePlacement(
                    current, placement,
                    current.hemisphereWarp());
            if (updated.outlineWarp().isPresent()) {
                throw new IllegalArgumentException(
                        "Disjoined side placement cannot be composed with a legacy nonlinear outline map");
            }
            final AffineTransform2D shared = AlignmentReviewState
                    .preOutlineTransform(basis, updated)
                    .andThen(updated.postOutlinePreviewAdjustment());
            AlignmentReviewState.requirePreservedAtlasAxisShear(
                    shared,
                    shared.andThen(placement.transform(atlasSide)),
                    "Disjoined " + atlasSide + " placement");
            return updated;
        }
    }

    /** Resets one anatomical side while retaining the other side exactly. */
    record ResetManualWarpSide(
            ManualHemisphereWarp2D.AtlasSide atlasSide,
            ManualWarpPrecondition precondition,
            Optional<ManualHemisphereWarp2D> validatedRemainingWarp)
            implements ReviewEdit {

        public ResetManualWarpSide {
            atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
            precondition = Objects.requireNonNull(
                    precondition, "precondition");
            validatedRemainingWarp = Objects.requireNonNull(
                    validatedRemainingWarp, "validatedRemainingWarp");
        }

        @Override public ReviewOperation operation() {
            return ReviewOperation.RESET_MANUAL_WARP_SIDE;
        }

        @Override public String description() {
            return "Reset " + atlasSide
                    + " controls in REVIEWER_CONTROLLED_MANUAL_WARP";
        }

        @Override public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            precondition.requireMatches(current, basis);
            final ManualHemisphereWarp2D prior = current.hemisphereWarp()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No reviewer-controlled manual warp is active"));
            if (!prior.hasControls(atlasSide)) {
                throw new IllegalArgumentException(
                        "The selected side has no manual-warp controls");
            }
            final List<ManualWarpControl> expected = prior.controls().stream()
                    .filter(control -> control.atlasSide() != atlasSide)
                    .toList();
            if (expected.isEmpty()) {
                if (validatedRemainingWarp.isPresent()) {
                    throw new IllegalArgumentException(
                            "Resetting the last side must clear the internal warp");
                }
            } else {
                requireInstalledControlSet(expected,
                        validatedRemainingWarp.orElseThrow(() ->
                                new IllegalArgumentException(
                                        "The retained side requires its validated mesh")));
            }
            final ManualSidePlacement2D placement = current
                    .manualSidePlacement().withTransform(atlasSide,
                            ManualSidePlacement2D.identity()
                                    .transform(atlasSide));
            return withManualSidePlacement(current, placement,
                    validatedRemainingWarp);
        }
    }

    record ClearHemisphereWarp() implements ReviewEdit {

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.CLEAR_HEMISPHERE_WARP;
        }

        @Override
        public String description() {
            return "Clear REVIEWER_CONTROLLED_MANUAL_WARP local controls";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.hemisphereWarp().isEmpty()) {
                throw new IllegalArgumentException(
                        "No reviewer-controlled warp is active");
            }
            return new AlignmentReviewContent(
                    current.coronalLevel(), current.atlasPlaneTilt(),
                    current.workflowMode(), current.orientation(),
                    current.observedHemisphere(),
                    current.reviewSectionMode(),
                    current.manualSidePlacement(),
                    current.manualPreviewAdjustment(), current.outlineWarp(),
                    current.outlineAnchorsConfirmed(),
                    current.postOutlinePreviewAdjustment(), Optional.empty(),
                    current.landmarks(), Optional.empty(),
                    current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(),
                    current.halfAtlasCoverage());
        }
    }

    record ClearLocalWarp() implements ReviewEdit {

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.CLEAR_LOCAL_WARP;
        }

        @Override
        public String description() {
            return "Clear reviewer-applied MANUAL_LOCAL_WARP";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (current.localWarp().isEmpty()) {
                throw new IllegalArgumentException("No local warp is active");
            }
            return new AlignmentReviewContent(
                    current.coronalLevel(), current.atlasPlaneTilt(),
                    current.workflowMode(), current.orientation(),
                    current.observedHemisphere(),
                    current.reviewSectionMode(),
                    current.manualSidePlacement(),
                    current.manualPreviewAdjustment(), current.outlineWarp(),
                    current.outlineAnchorsConfirmed(),
                    current.postOutlinePreviewAdjustment(),
                    current.hemisphereWarp(), current.landmarks(),
                    java.util.Optional.empty(), current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(),
                    current.halfAtlasCoverage());
        }
    }

    /** Installs an already-built support atomically; construction stays off EDT. */
    record ReplaceReviewedTissueSupport(
            ReviewedTissueSupport support,
            boolean clippingEnabled,
            String editDescription) implements ReviewEdit {

        public ReplaceReviewedTissueSupport(
                final ReviewedTissueSupport support,
                final boolean clippingEnabled) {
            this(support, clippingEnabled,
                    "Replace editable tissue support");
        }

        public ReplaceReviewedTissueSupport {
            support = Objects.requireNonNull(support, "support");
            editDescription = requireText(editDescription, "editDescription");
            if (clippingEnabled && support.controls().isEmpty()) {
                throw new IllegalArgumentException(
                        "Tissue clipping requires a non-empty reviewed support");
            }
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.REPLACE_REVIEWED_TISSUE_SUPPORT;
        }

        @Override
        public String description() {
            return editDescription;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (support.width() != basis.previewDimensions().width()
                    || support.height() != basis.previewDimensions().height()) {
                throw new IllegalArgumentException(
                        "Reviewed tissue support does not match the active preview");
            }
            return withReviewedTissueSupport(
                    current, Optional.of(support), clippingEnabled);
        }
    }

    /**
     * Installs a precomputed re-suggestion. Boundary extraction belongs in
     * the caller's worker executor; applying an edit only validates state and
     * installs the immutable result.
     */
    record ResuggestReviewedTissueSupport(
            ReviewedTissueSupport support) implements ReviewEdit {

        public ResuggestReviewedTissueSupport {
            support = Objects.requireNonNull(support, "support");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.RESUGGEST_REVIEWED_TISSUE_SUPPORT;
        }

        @Override
        public String description() {
            return "Re-suggest editable tissue support from copied-image contrast";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (support.width() != basis.previewDimensions().width()
                    || support.height() != basis.previewDimensions().height()) {
                throw new IllegalArgumentException(
                        "Reviewed tissue support does not match the active preview");
            }
            return withReviewedTissueSupport(
                    current, Optional.of(support),
                    current.tissueClippingEnabled());
        }
    }

    /** Moves one editable support node as one undoable content revision. */
    record MoveReviewedTissueSupportControl(
            String controlId,
            Point2D destination) implements ReviewEdit {

        public MoveReviewedTissueSupportControl {
            controlId = requireId(controlId);
            destination = Objects.requireNonNull(destination, "destination");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.MOVE_REVIEWED_TISSUE_SUPPORT_CONTROL;
        }

        @Override
        public String description() {
            return "Move tissue-support crop control " + controlId;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            final ReviewedTissueSupport support = current.reviewedTissueSupport()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No reviewed tissue support is active"));
            return withReviewedTissueSupport(
                    current,
                    Optional.of(support.moveControl(controlId, destination)),
                    current.tissueClippingEnabled());
        }
    }

    /** Inserts one editable support node as one undoable content revision. */
    record InsertReviewedTissueSupportControl(
            int componentIndex,
            int afterVertexIndex,
            String controlId,
            Point2D point) implements ReviewEdit {

        public InsertReviewedTissueSupportControl(
                final int componentIndex,
                final int afterVertexIndex,
                final Point2D point) {
            this(componentIndex, afterVertexIndex,
                    "tissue-support-insert-c" + componentIndex
                            + "-v" + (afterVertexIndex + 1), point);
        }

        public InsertReviewedTissueSupportControl {
            if (componentIndex < 0 || afterVertexIndex < 0) {
                throw new IllegalArgumentException(
                        "Support component and edge indices must be non-negative");
            }
            controlId = requireId(controlId);
            point = Objects.requireNonNull(point, "point");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.INSERT_REVIEWED_TISSUE_SUPPORT_CONTROL;
        }

        @Override
        public String description() {
            return "Insert tissue-support crop control " + controlId;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            final ReviewedTissueSupport support = current.reviewedTissueSupport()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No reviewed tissue support is active"));
            return withReviewedTissueSupport(
                    current,
                    Optional.of(support.insertControl(
                            componentIndex, afterVertexIndex, controlId, point)),
                    current.tissueClippingEnabled());
        }
    }

    /** Deletes one editable support node as one undoable content revision. */
    record DeleteReviewedTissueSupportControl(String controlId)
            implements ReviewEdit {

        public DeleteReviewedTissueSupportControl {
            controlId = requireId(controlId);
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.DELETE_REVIEWED_TISSUE_SUPPORT_CONTROL;
        }

        @Override
        public String description() {
            return "Delete tissue-support crop control " + controlId;
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            final ReviewedTissueSupport support = current.reviewedTissueSupport()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No reviewed tissue support is active"));
            return withReviewedTissueSupport(
                    current,
                    Optional.of(support.deleteControl(controlId)),
                    current.tissueClippingEnabled());
        }
    }

    /** Toggles the accepted crop footprint without changing any warp/evidence. */
    record SetTissueClipping(boolean enabled) implements ReviewEdit {

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.SET_TISSUE_CLIPPING;
        }

        @Override
        public String description() {
            return enabled ? "Enable tissue-support clipping"
                    : "Disable tissue-support clipping";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            if (enabled && current.reviewedTissueSupport().isEmpty()) {
                throw new IllegalArgumentException(
                        "Tissue clipping requires a reviewed tissue support");
            }
            return withReviewedTissueSupport(
                    current, current.reviewedTissueSupport(), enabled,
                    current.halfAtlasCoverage());
        }
    }

    record ResetToInitialPlacement(
            AlignmentReviewContent initialContent) implements ReviewEdit {
        public ResetToInitialPlacement {
            initialContent = Objects.requireNonNull(initialContent, "initialContent");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.RESET_TO_INITIAL_PLACEMENT;
        }

        @Override
        public String description() {
            return "Reset to initial upright manual review placement; immutable registration proposal retained in basis";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            return initialContent;
        }
    }

    record ResetToProposal(
            AlignmentReviewContent initialContent) implements ReviewEdit {

        public ResetToProposal {
            initialContent = Objects.requireNonNull(
                    initialContent, "initialContent");
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.RESET_TO_PROPOSAL;
        }

        @Override
        public String description() {
            return "Reset to immutable registration proposal";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            return initialContent;
        }
    }

    /**
     * Restores the immutable proposal and its prepared bilateral default grid
     * as one audited history entry. The solve is completed before this edit is
     * applied, so one Undo always returns to the complete pre-reset review.
     */
    record ResetToProposalAndInstallManualWarp(
            AlignmentReviewContent initialContent,
            List<ManualWarpControl> replacement,
            ManualWarpPrecondition precondition,
            ManualHemisphereWarp2D validatedWarp) implements ReviewEdit {

        public ResetToProposalAndInstallManualWarp {
            initialContent = Objects.requireNonNull(
                    initialContent, "initialContent");
            replacement = List.copyOf(Objects.requireNonNull(
                    replacement, "replacement"));
            precondition = Objects.requireNonNull(
                    precondition, "precondition");
            validatedWarp = Objects.requireNonNull(
                    validatedWarp, "validatedWarp");
            if (replacement.isEmpty()) {
                throw new IllegalArgumentException(
                        "The reset bilateral manual-warp grid is empty");
            }
        }

        @Override
        public ReviewOperation operation() {
            return ReviewOperation.RESET_TO_PROPOSAL;
        }

        @Override
        public String description() {
            return "Reset to immutable registration proposal with "
                    + replacement.size() + " default manual-warp controls";
        }

        @Override
        public AlignmentReviewContent apply(
                final AlignmentReviewContent current,
                final AlignmentReviewBasis basis) {
            precondition.requireMatches(current, basis);
            requireInstalledControlSet(replacement, validatedWarp);
            return withHemisphereWarp(
                    initialContent, Optional.of(validatedWarp));
        }
    }

    private static void requireInstalledControlSet(
            final List<ManualWarpControl> expected,
            final ManualHemisphereWarp2D installed) {
        final java.util.Set<ManualWarpControl> expectedSet =
                java.util.Set.copyOf(expected);
        final java.util.Set<ManualWarpControl> installedSet =
                java.util.Set.copyOf(installed.controls());
        if (expectedSet.size() != expected.size()
                || installedSet.size() != installed.controls().size()
                || !expectedSet.equals(installedSet)) {
            throw new IllegalArgumentException(
                    "Validated manual field does not exactly match the requested immutable controls");
        }
    }

    private static List<ManualWarpControl> controlsExceptStructure(
            final AlignmentReviewContent current,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final String structureAcronym) {
        final List<ManualWarpControl> retained = new ArrayList<>();
        current.hemisphereWarp().ifPresent(warp -> retained.addAll(
                warp.controls().stream().filter(control ->
                        !isStructureControl(control, atlasSide,
                                structureAcronym)).toList()));
        return retained;
    }

    private static boolean isStructureControl(
            final ManualWarpControl control,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final String structureAcronym) {
        return control.atlasSide() == atlasSide
                && (control.origin()
                        == org.atlasalign.application.manual
                                .ManualWarpControlOrigin.STRUCTURE_GUIDE
                        || control.origin()
                        == org.atlasalign.application.manual
                                .ManualWarpControlOrigin
                                        .VERIFIED_STRUCTURE_BOUNDARY)
                && control.structureAcronym().equalsIgnoreCase(
                        structureAcronym);
    }

    private static void requireTargetOnlyStructureReplacement(
            final AlignmentReviewContent current,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final String structureAcronym,
            final List<ManualWarpControl> replacement) {
        final ManualHemisphereWarp2D prior = current.hemisphereWarp()
                .orElseThrow(() -> new IllegalArgumentException(
                        "No reviewer-controlled manual warp is active"));
        final java.util.Map<String, ManualWarpControl> currentById = prior
                .controls().stream()
                .filter(control -> isStructureControl(control, atlasSide,
                        structureAcronym))
                .collect(java.util.stream.Collectors.toMap(
                        ManualWarpControl::id,
                        java.util.function.Function.identity()));
        if (currentById.size() != replacement.size()) {
            throw new IllegalArgumentException(
                    "A structure resize must retain every existing control identifier");
        }
        final String canonicalGroup = "structure-guide:"
                + structureAcronym;
        for (final ManualWarpControl candidate : replacement) {
            final ManualWarpControl existing = currentById.remove(
                    candidate.id());
            final boolean sameOrigin = existing != null
                    && existing.origin() == candidate.origin();
            final boolean adoptsVerifiedOrigin = existing != null
                    && existing.origin()
                    == org.atlasalign.application.manual
                            .ManualWarpControlOrigin
                                    .VERIFIED_STRUCTURE_BOUNDARY
                    && candidate.origin()
                    == org.atlasalign.application.manual
                            .ManualWarpControlOrigin.STRUCTURE_GUIDE;
            if (existing == null
                    || existing.atlasSide() != candidate.atlasSide()
                    || !sameOrigin && !adoptsVerifiedOrigin
                    || !existing.structureAcronym().equalsIgnoreCase(
                            candidate.structureAcronym())
                    || !existing.sourcePoint().equals(
                            candidate.sourcePoint())) {
                throw new IllegalArgumentException(
                        "A structure resize may change tissue targets only");
            }
            final boolean sameGroup = existing.groupId().equals(
                    candidate.groupId());
            final boolean adoptsLegacy = existing.groupId().equals(
                    "structure-guide")
                    && candidate.groupId().equals(canonicalGroup);
            final boolean adoptsVerifiedGroup = adoptsVerifiedOrigin
                    && candidate.groupId().equals(canonicalGroup);
            if (!sameGroup && !adoptsLegacy && !adoptsVerifiedGroup) {
                throw new IllegalArgumentException(
                        "A structure resize cannot change control-group metadata");
            }
        }
        if (!currentById.isEmpty()) {
            throw new IllegalArgumentException(
                    "A structure resize must retain every existing control identifier");
        }
    }

    private static AlignmentReviewContent withHemisphereWarp(
            final AlignmentReviewContent current,
            final Optional<ManualHemisphereWarp2D> warp) {
        return withManualSidePlacement(current,
                current.manualSidePlacement(), warp);
    }

    private static AlignmentReviewContent withManualSidePlacement(
            final AlignmentReviewContent current,
            final ManualSidePlacement2D placement,
            final Optional<ManualHemisphereWarp2D> warp) {
        return new AlignmentReviewContent(
                current.coronalLevel(), current.atlasPlaneTilt(),
                current.workflowMode(), current.orientation(),
                current.observedHemisphere(),
                current.reviewSectionMode(),
                placement,
                current.manualPreviewAdjustment(), current.outlineWarp(),
                current.outlineAnchorsConfirmed(),
                current.postOutlinePreviewAdjustment(), warp,
                current.landmarks(), Optional.empty(),
                current.reviewedTissueSupport(),
                current.tissueClippingEnabled(),
                current.halfAtlasCoverage());
    }

    private static AlignmentReviewContent withReviewedTissueSupport(
            final AlignmentReviewContent current,
            final Optional<ReviewedTissueSupport> support,
            final boolean clippingEnabled) {
        return withReviewedTissueSupport(current, support, clippingEnabled,
                HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
    }

    private static AlignmentReviewContent withReviewedTissueSupport(
            final AlignmentReviewContent current,
            final Optional<ReviewedTissueSupport> support,
            final boolean clippingEnabled,
            final HalfAtlasCoverage coverage) {
        return new AlignmentReviewContent(
                current.coronalLevel(), current.atlasPlaneTilt(),
                current.workflowMode(), current.orientation(),
                current.observedHemisphere(), current.reviewSectionMode(),
                current.manualSidePlacement(), current.manualPreviewAdjustment(),
                current.outlineWarp(), current.outlineAnchorsConfirmed(),
                current.postOutlinePreviewAdjustment(), current.hemisphereWarp(),
                current.landmarks(), current.localWarp(), support,
                clippingEnabled, coverage);
    }

    private static AlignmentReviewContent withPreviewCorrection(
            final AlignmentReviewContent current,
            final AffineTransform2D correction) {
        if (current.outlineWarp().orElse(null)
                instanceof BoundaryAuthoritativeTransform2D) {
            throw new IllegalArgumentException(
                    "The finished tissue outline is authoritative; global translate, rotate, scale, similarity, and affine edits are disabled until the outline is changed upstream");
        }
        if (current.outlineWarp().isPresent()) {
            return new AlignmentReviewContent(
                    current.coronalLevel(),
                    current.atlasPlaneTilt(),
                    current.workflowMode(),
                    current.orientation(),
                    current.observedHemisphere(),
                    current.reviewSectionMode(),
                    current.manualSidePlacement(),
                    current.manualPreviewAdjustment(),
                    current.outlineWarp(),
                    current.outlineAnchorsConfirmed(),
                    current.postOutlinePreviewAdjustment().andThen(
                            correction),
                    Optional.empty(), current.landmarks(), Optional.empty(),
                    current.reviewedTissueSupport(),
                    current.tissueClippingEnabled(),
                    HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
        }
        return new AlignmentReviewContent(
                current.coronalLevel(),
                current.atlasPlaneTilt(),
                current.workflowMode(),
                current.orientation(),
                current.observedHemisphere(),
                current.reviewSectionMode(),
                current.manualSidePlacement(),
                current.manualPreviewAdjustment().andThen(correction),
                Optional.empty(),
                false,
                AlignmentReviewContent.identityPreviewAdjustment(),
                Optional.empty(), current.landmarks(), Optional.empty(),
                current.reviewedTissueSupport(),
                current.tissueClippingEnabled(),
                HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
    }
    private static AlignmentReviewContent withLandmarks(
            final AlignmentReviewContent current,
            final List<LandmarkPair> landmarks) {
        return new AlignmentReviewContent(
                current.coronalLevel(),
                current.atlasPlaneTilt(),
                current.workflowMode(),
                current.orientation(),
                current.observedHemisphere(),
                current.reviewSectionMode(),
                current.manualSidePlacement(),
                current.manualPreviewAdjustment(),
                current.outlineWarp(),
                current.outlineAnchorsConfirmed(),
                current.postOutlinePreviewAdjustment(),
                Optional.empty(), landmarks, Optional.empty(),
                current.reviewedTissueSupport(),
                current.tissueClippingEnabled(),
                current.halfAtlasCoverage());
    }

    private static Point2D mapBeforeLocalWarp(
            final AlignmentReviewContent current,
            final AlignmentReviewBasis basis,
            final Point2D atlasPoint) {
        final Point2D global = atlasOrientation(basis, current.orientation())
                .andThen(basis.proposal().affine())
                .andThen(current.manualPreviewAdjustment())
                .apply(atlasPoint);
        final Point2D outline = current.outlineWarp()
                .map(warp -> warp.apply(global))
                .orElse(global);
        final Point2D postOutline = current.postOutlinePreviewAdjustment()
                .apply(outline);
        final Point2D placed = rawAtlasSide(basis, atlasPoint)
                .map(side -> current.manualSidePlacement().apply(
                        side, postOutline))
                .orElse(postOutline);
        return current.hemisphereWarp()
                .map(warp -> rawAtlasSide(basis, atlasPoint)
                        .map(side -> warp.apply(side, placed))
                        .orElse(placed))
                .orElse(placed);
    }

    private static Point2D mapBeforeHemisphereWarp(
            final AlignmentReviewContent current,
            final AlignmentReviewBasis basis,
            final Point2D atlasPoint) {
        final Point2D global = atlasOrientation(basis, current.orientation())
                .andThen(basis.proposal().affine())
                .andThen(current.manualPreviewAdjustment())
                .apply(atlasPoint);
        final Point2D outline = current.outlineWarp()
                .map(warp -> warp.apply(global))
                .orElse(global);
        final Point2D postOutline = current.postOutlinePreviewAdjustment()
                .apply(outline);
        return rawAtlasSide(basis, atlasPoint)
                .map(side -> current.manualSidePlacement().apply(
                        side, postOutline))
                .orElse(postOutline);
    }

    /** Midline fixed by the confirmed dorsal/ventral outline anchors. */
    private static ManualHemisphereWarp2D.MidlineSegment hemisphereMidline(
            final AlignmentReviewContent current) {
        final ReviewedOutlineTransform2D outline = current.outlineWarp()
                .orElseThrow(() -> new IllegalArgumentException(
                        "A confirmed outline warp is required"));
        final var midline = outline.hemisphereMidline();
        final Point2D dorsal = current.postOutlinePreviewAdjustment().apply(
                midline.dorsal());
        final Point2D ventral = current.postOutlinePreviewAdjustment().apply(
                midline.ventral());
        return new ManualHemisphereWarp2D.MidlineSegment(dorsal, ventral);
    }

    private static Optional<ManualHemisphereWarp2D.AtlasSide> rawAtlasSide(
            final AlignmentReviewBasis basis,
            final Point2D atlasPoint) {
        final double centre = (basis.atlas().atlasPlaneWidth() - 1.0) * 0.5;
        if (Double.doubleToLongBits(atlasPoint.x())
                == Double.doubleToLongBits(centre)) {
            return Optional.empty();
        }
        return Optional.of(atlasPoint.x() < centre
                ? ManualHemisphereWarp2D.AtlasSide.LEFT
                : ManualHemisphereWarp2D.AtlasSide.RIGHT);
    }

    private static void requireTypedAtlasSide(
            final LandmarkPair pair,
            final AlignmentReviewBasis basis) {
        final AtlasAnatomicalSide declared = pair.anatomicalHandleMetadata()
                .orElseThrow().atlasSide();
        final Optional<ManualHemisphereWarp2D.AtlasSide> raw =
                rawAtlasSide(basis, pair.atlasPoint());
        final AtlasAnatomicalSide expected = raw.isEmpty()
                ? AtlasAnatomicalSide.MIDLINE
                : raw.orElseThrow() == ManualHemisphereWarp2D.AtlasSide.LEFT
                ? AtlasAnatomicalSide.ATLAS_LEFT
                : AtlasAnatomicalSide.ATLAS_RIGHT;
        if (declared != expected) {
            throw new IllegalArgumentException(
                    "Typed anatomical handle side does not match raw atlas X relative to the verified atlas centre");
        }
    }

    private static AffineTransform2D atlasOrientation(
            final AlignmentReviewBasis basis,
            final AtlasOrientation orientation) {
        return orientation.reflected()
                ? new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        -1, 0,
                        basis.atlas().atlasPlaneWidth() - 1.0,
                        0, 1, 0)
                : new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        1, 0, 0,
                        0, 1, 0);
    }

    private static ObservedAnatomicalHemisphere
            hemisphereForOrientation(
            final ObservedAnatomicalHemisphere current,
            final AtlasOrientation orientation,
            final SectionGeometry geometry) {
        if (geometry == SectionGeometry.FULL
                || geometry
                == SectionGeometry.BILATERAL_REVIEW_REQUIRED) {
            return ObservedAnatomicalHemisphere.BOTH;
        }
        if (geometry == SectionGeometry.PARTIAL_OR_DAMAGED
                || !orientation.confirmed()) {
            return geometry == SectionGeometry.PARTIAL_OR_DAMAGED
                    ? current : ObservedAnatomicalHemisphere.UNSURE;
        }
        return ObservedAnatomicalHemisphere.UNSURE;
    }

    private static AlignmentReviewContent replaceLandmark(
            final AlignmentReviewContent current,
            final String identifier,
            final java.util.function.UnaryOperator<LandmarkPair> replacement) {
        final List<LandmarkPair> landmarks =
                new ArrayList<>(current.landmarks());
        for (int index = 0; index < landmarks.size(); index++) {
            if (landmarks.get(index).id().equals(identifier)) {
                landmarks.set(index, replacement.apply(
                        landmarks.get(index)));
                return withLandmarks(current, landmarks);
            }
        }
        throw new IllegalArgumentException(
                "Unknown landmark: " + identifier);
    }

    private static LandmarkPair requireGenericLandmark(
            final AlignmentReviewContent current,
            final String identifier,
            final String typedHandleMessage) {
        final LandmarkPair pair = current.landmarks().stream()
                .filter(candidate -> candidate.id().equals(identifier))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown landmark: " + identifier));
        if (pair.anatomicalHandleMetadata().isPresent()) {
            throw new IllegalArgumentException(typedHandleMessage);
        }
        return pair;
    }

    private static AnatomicalHandleMetadata requireReplacementHandle(
            final LandmarkPair pair) {
        if (pair.role() != LandmarkRole.FIT) {
            throw new IllegalArgumentException(
                    "Replacement anatomical handles must all have the FIT role");
        }
        final AnatomicalHandleMetadata metadata = pair
                .anatomicalHandleMetadata()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Replacement anatomical handles require typed verified-atlas metadata"));
        if (metadata.seedOrigin()
                != LandmarkSeedOrigin.VERIFIED_ATLAS_BOUNDARY) {
            throw new IllegalArgumentException(
                    "Replacement anatomical handles must originate from VERIFIED_ATLAS_BOUNDARY");
        }
        return metadata;
    }

    private static boolean isReplacedHandle(
            final LandmarkPair pair,
            final AlignmentReviewContent current,
            final AnatomicalHandleMetadata target) {
        if (!isSameActiveHandleTarget(pair, current, target)) {
            return false;
        }
        final AnatomicalHandleMetadata metadata = pair
                .anatomicalHandleMetadata().orElseThrow();
        return metadata.selectedBoundarySha256().equals(
                target.selectedBoundarySha256());
    }

    private static boolean isSameActiveHandleTarget(
            final LandmarkPair pair,
            final AlignmentReviewContent current,
            final AnatomicalHandleMetadata target) {
        if (pair.role() != LandmarkRole.FIT
                || !pair.coronalLevel().equals(current.coronalLevel())
                || !pair.atlasPlaneTilt().equals(current.atlasPlaneTilt())
                || pair.anatomicalHandleMetadata().isEmpty()) {
            return false;
        }
        final AnatomicalHandleMetadata metadata = pair
                .anatomicalHandleMetadata().orElseThrow();
        return metadata.atlasRegionId() == target.atlasRegionId()
                && metadata.guideAcronym().equals(target.guideAcronym())
                && metadata.atlasSide() == target.atlasSide();
    }

    private static AffineTransform2D previewAffine(
            final double m00,
            final double m01,
            final double m02,
            final double m10,
            final double m11,
            final double m12) {
        return new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                m00, m01, m02,
                m10, m11, m12);
    }

    private static String affineSha256(
            final String method,
            final AffineTransform2D transform) {
        final String canonical = requireText(method, "method") + "\n"
                + transform.sourceSpace() + "->"
                + transform.destinationSpace() + "\n"
                + Double.toHexString(transform.m00()) + "\n"
                + Double.toHexString(transform.m01()) + "\n"
                + Double.toHexString(transform.m02()) + "\n"
                + Double.toHexString(transform.m10()) + "\n"
                + Double.toHexString(transform.m11()) + "\n"
                + Double.toHexString(transform.m12());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(canonical.getBytes(
                            StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", unavailable);
        }
    }

    private static void requireFinite(
            final double value,
            final String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    name + " must be finite");
        }
    }

    private static void requireFiniteNonNegative(
            final double value,
            final String name) {
        requireFinite(value, name);
        if (value < 0) {
            throw new IllegalArgumentException(
                    name + " must be non-negative");
        }
    }

    private static String requireText(
            final String value,
            final String name) {
        final String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }

    private static String requireSha256(
            final String value,
            final String name) {
        final String checked = requireText(value, name);
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be a lowercase SHA-256 value");
        }
        return checked;
    }

    private static String requireId(final String identifier) {
        final String checked = Objects.requireNonNull(
                identifier, "landmarkId").trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(
                    "Landmark ID must not be blank");
        }
        return checked;
    }
}
