package org.atlasalign.plugin.project;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.plugin.review.ReviewCanvas;
import org.atlasalign.plugin.review.ReviewWorkflowStage;

/** Committed workspace preferences; pending gestures and worker results are excluded. */
public record ReviewUiState(ReviewWorkflowStage stage, ReviewCanvas.InteractionTool tool,
        ReviewCanvas.ComparisonMode comparison, Viewport viewport,
        boolean landmarkLabels, boolean deformationGrid, boolean displacementLines,
        boolean singleTissuePane, int selectedGuideArgb, int dimGuideArgb,
        double regionStrokeWidth, double overlayOpacity, boolean aspectRatioLocked,
        boolean outerBoundariesOnly, ManualHemisphereWarp2D.AtlasSide activeSide,
        Optional<Integer> selectedRegionId, boolean showAtlasAnatomy, ReviewCanvas.PlacementTool placementTool) {
    public ReviewUiState(final ReviewWorkflowStage stage, final ReviewCanvas.InteractionTool tool,
            final ReviewCanvas.ComparisonMode comparison, final Viewport viewport, final boolean landmarkLabels,
            final boolean deformationGrid, final boolean displacementLines, final boolean singleTissuePane,
            final int selectedGuideArgb, final int dimGuideArgb, final double regionStrokeWidth,
            final double overlayOpacity, final boolean aspectRatioLocked, final boolean outerBoundariesOnly,
            final ManualHemisphereWarp2D.AtlasSide activeSide, final Optional<Integer> selectedRegionId,
            final boolean showAtlasAnatomy) {
        this(stage, tool, comparison, viewport, landmarkLabels, deformationGrid, displacementLines, singleTissuePane,
                selectedGuideArgb, dimGuideArgb, regionStrokeWidth, overlayOpacity, aspectRatioLocked, outerBoundariesOnly,
                activeSide, selectedRegionId, showAtlasAnatomy, ReviewCanvas.PlacementTool.ALL);
    }
    public record Viewport(double tissueZoom, double tissuePanX, double tissuePanY,
            double atlasZoom, double atlasPanX, double atlasPanY) {
        public Viewport {
            if (!Double.isFinite(tissueZoom) || tissueZoom < .25 || tissueZoom > 16
                    || !Double.isFinite(atlasZoom) || atlasZoom < .25 || atlasZoom > 16
                    || !Double.isFinite(tissuePanX) || !Double.isFinite(tissuePanY)
                    || !Double.isFinite(atlasPanX) || !Double.isFinite(atlasPanY)) {
                throw new IllegalArgumentException("Invalid saved viewport");
            }
        }
    }
    public ReviewUiState {
        Objects.requireNonNull(stage, "stage"); Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(comparison, "comparison"); Objects.requireNonNull(viewport, "viewport");
        Objects.requireNonNull(activeSide, "activeSide"); selectedRegionId = Objects.requireNonNull(selectedRegionId);
        Objects.requireNonNull(placementTool, "placementTool");
        if (!Double.isFinite(regionStrokeWidth) || regionStrokeWidth < .5 || regionStrokeWidth > 5
                || !Double.isFinite(overlayOpacity) || overlayOpacity < 0 || overlayOpacity > 1
                || selectedRegionId.filter(id -> id < 1).isPresent()) {
            throw new IllegalArgumentException("Invalid saved overlay settings");
        }
    }
    public static ReviewUiState defaults() {
        return new ReviewUiState(ReviewWorkflowStage.SETUP_AND_PLANE, ReviewCanvas.InteractionTool.TRANSFORM,
                ReviewCanvas.ComparisonMode.AFTER, new Viewport(1, 0, 0, 1, 0, 0), false, false, true, true,
                0xf5ff18cd, 0xbeff7814, 1.25, .65, true, true,
                ManualHemisphereWarp2D.AtlasSide.LEFT, Optional.empty(), false);
    }
}
