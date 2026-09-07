package org.atlasalign.plugin.review;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.AlignmentReviewState;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewConfidenceReport;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.atlas.AtlasCoronalPlane;

/**
 * Complete immutable data required to draw one review revision.
 */
public record ReviewViewModel(
        ReviewPreview preview,
        AlignmentReviewState reviewState,
        ReviewConfidenceReport confidence,
        Optional<AtlasCoronalPlane> atlasPlane,
        boolean atlasPlaneLoading,
        Optional<String> atlasPlaneError,
        boolean showAtlasAnatomy,
        Optional<SelectedAtlasRegion> selectedAtlasRegion,
        Optional<SelectedAtlasContour> selectedAtlasContour,
        boolean canUndo,
        boolean canRedo,
        boolean canReset,
        boolean warningsAcknowledged,
        boolean accepted,
        BoundaryFitViewState boundaryFit,
        BoundaryWarpViewState boundaryWarp,
        StructureAdjustmentViewState structureAdjustment) {

    public ReviewViewModel {
        preview = Objects.requireNonNull(preview, "preview");
        reviewState = Objects.requireNonNull(
                reviewState, "reviewState");
        confidence = Objects.requireNonNull(
                confidence, "confidence");
        atlasPlane = Objects.requireNonNull(
                atlasPlane, "atlasPlane");
        atlasPlaneError = Objects.requireNonNull(
                atlasPlaneError, "atlasPlaneError");
        selectedAtlasRegion = Objects.requireNonNull(
                selectedAtlasRegion, "selectedAtlasRegion");
        selectedAtlasContour = Objects.requireNonNull(
                selectedAtlasContour, "selectedAtlasContour");
        boundaryFit = Objects.requireNonNull(boundaryFit, "boundaryFit");
        boundaryWarp = Objects.requireNonNull(boundaryWarp, "boundaryWarp");
        structureAdjustment = Objects.requireNonNull(
                structureAdjustment, "structureAdjustment");
        if (selectedAtlasContour.isPresent()
                && (selectedAtlasRegion.isEmpty()
                || !selectedAtlasContour.orElseThrow().region().equals(
                selectedAtlasRegion.orElseThrow()))) {
            throw new IllegalArgumentException(
                    "Selected atlas contour must match its region");
        }
    }

    /** Compatibility constructor for review models without a pick target. */
    public ReviewViewModel(
            final ReviewPreview preview,
            final AlignmentReviewState reviewState,
            final ReviewConfidenceReport confidence,
            final Optional<AtlasCoronalPlane> atlasPlane,
            final boolean atlasPlaneLoading,
            final Optional<String> atlasPlaneError,
            final boolean showAtlasAnatomy,
            final boolean canUndo,
            final boolean canRedo,
            final boolean canReset,
            final boolean warningsAcknowledged,
            final boolean accepted) {
        this(preview, reviewState, confidence, atlasPlane,
                atlasPlaneLoading, atlasPlaneError, showAtlasAnatomy,
                Optional.empty(), Optional.empty(),
                canUndo, canRedo, canReset,
                warningsAcknowledged, accepted,
                BoundaryFitViewState.inactive(),
                BoundaryWarpViewState.inactive(),
                StructureAdjustmentViewState.inactive());
    }

    /** Compatibility constructor for callers with explicit target contours. */
    public ReviewViewModel(
            final ReviewPreview preview,
            final AlignmentReviewState reviewState,
            final ReviewConfidenceReport confidence,
            final Optional<AtlasCoronalPlane> atlasPlane,
            final boolean atlasPlaneLoading,
            final Optional<String> atlasPlaneError,
            final boolean showAtlasAnatomy,
            final Optional<SelectedAtlasRegion> selectedAtlasRegion,
            final Optional<SelectedAtlasContour> selectedAtlasContour,
            final boolean canUndo,
            final boolean canRedo,
            final boolean canReset,
            final boolean warningsAcknowledged,
            final boolean accepted) {
        this(preview, reviewState, confidence, atlasPlane,
                atlasPlaneLoading, atlasPlaneError, showAtlasAnatomy,
                selectedAtlasRegion, selectedAtlasContour, canUndo, canRedo,
                canReset, warningsAcknowledged, accepted,
                BoundaryFitViewState.inactive(),
                BoundaryWarpViewState.inactive(),
                StructureAdjustmentViewState.inactive());
    }

    /** Compatibility constructor for callers that supply Border state. */
    public ReviewViewModel(
            final ReviewPreview preview,
            final AlignmentReviewState reviewState,
            final ReviewConfidenceReport confidence,
            final Optional<AtlasCoronalPlane> atlasPlane,
            final boolean atlasPlaneLoading,
            final Optional<String> atlasPlaneError,
            final boolean showAtlasAnatomy,
            final Optional<SelectedAtlasRegion> selectedAtlasRegion,
            final Optional<SelectedAtlasContour> selectedAtlasContour,
            final boolean canUndo,
            final boolean canRedo,
            final boolean canReset,
            final boolean warningsAcknowledged,
            final boolean accepted,
            final BoundaryFitViewState boundaryFit,
            final BoundaryWarpViewState boundaryWarp) {
        this(preview, reviewState, confidence, atlasPlane,
                atlasPlaneLoading, atlasPlaneError, showAtlasAnatomy,
                selectedAtlasRegion, selectedAtlasContour, canUndo, canRedo,
                canReset, warningsAcknowledged, accepted, boundaryFit,
                boundaryWarp, StructureAdjustmentViewState.inactive());
    }

    public int coronalLevel() {
        return reviewState.content().coronalLevel()
                .zeroBasedAnteriorPosteriorIndex();
    }

    public AtlasOrientation orientation() {
        return reviewState.content().orientation();
    }

    public ObservedAnatomicalHemisphere observedHemisphere() {
        return reviewState.observedAnatomicalHemisphere();
    }

    public SectionGeometry geometry() {
        return reviewState.basis().proposal().geometry().geometry();
    }
}
