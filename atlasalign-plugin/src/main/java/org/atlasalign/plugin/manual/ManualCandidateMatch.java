package org.atlasalign.plugin.manual;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.manual.ManualOutlineWarp2D;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.plugin.review.SelectedAtlasContour;

/** One exploratory, reviewer-seeded atlas candidate and its shape mismatch. */
record ManualCandidateMatch(
        String id,
        int allenAxis0Index,
        AtlasPlaneTilt tilt,
        double combinedMismatch,
        double tissueOutlineMismatch,
        double guideMismatch,
        AtlasCoronalPlane plane,
        SelectedAtlasContour rootContour,
        SelectedAtlasContour guideContour,
        List<Point2D> rootOverlayPoints,
        List<Point2D> guideOverlayPoints,
        AffineTransform2D atlasToSource,
        AffineTransform2D atlasToPreviewAffine,
        PreviewMapping previewMapping,
        Optional<ManualOutlineWarp2D> outlineWarp,
        OutlineWarpAnchorState outlineWarpAnchorState,
        String outlineFitMethod) {

    ManualCandidateMatch {
        id = Objects.requireNonNull(id, "id");
        tilt = Objects.requireNonNull(tilt, "tilt");
        plane = Objects.requireNonNull(plane, "plane");
        rootContour = Objects.requireNonNull(rootContour, "rootContour");
        guideContour = Objects.requireNonNull(guideContour, "guideContour");
        rootOverlayPoints = List.copyOf(Objects.requireNonNull(
                rootOverlayPoints, "rootOverlayPoints"));
        guideOverlayPoints = List.copyOf(Objects.requireNonNull(
                guideOverlayPoints, "guideOverlayPoints"));
        atlasToSource = Objects.requireNonNull(
                atlasToSource, "atlasToSource");
        atlasToPreviewAffine = Objects.requireNonNull(
                atlasToPreviewAffine, "atlasToPreviewAffine");
        previewMapping = Objects.requireNonNull(
                previewMapping, "previewMapping");
        outlineWarp = Objects.requireNonNull(
                outlineWarp, "outlineWarp");
        outlineWarpAnchorState = Objects.requireNonNull(
                outlineWarpAnchorState, "outlineWarpAnchorState");
        outlineFitMethod = Objects.requireNonNull(
                outlineFitMethod, "outlineFitMethod").trim();
        if (id.isBlank() || allenAxis0Index < 0 || allenAxis0Index >= 528
                || !finiteNonNegative(combinedMismatch)
                || !finiteNonNegative(tissueOutlineMismatch)
                || !finiteNonNegative(guideMismatch)
                || rootOverlayPoints.isEmpty()
                || guideOverlayPoints.isEmpty()
                || outlineFitMethod.isEmpty()
                || atlasToSource.sourceSpace()
                        != CoordinateSpace2D.ATLAS_PLANE_PIXEL
                || atlasToSource.destinationSpace()
                        != CoordinateSpace2D.SOURCE_PIXEL
                || atlasToPreviewAffine.sourceSpace()
                        != CoordinateSpace2D.ATLAS_PLANE_PIXEL
                || atlasToPreviewAffine.destinationSpace()
                        != CoordinateSpace2D.PREVIEW_PIXEL
                || outlineWarp.isPresent()
                        != (outlineWarpAnchorState
                                != OutlineWarpAnchorState
                                        .NOT_APPLICABLE_PARTIAL_AFFINE)
                || outlineWarp.isPresent()
                        && (outlineWarp.orElseThrow().sourceSpace()
                                != CoordinateSpace2D.PREVIEW_PIXEL
                                || outlineWarp.orElseThrow().destinationSpace()
                                        != CoordinateSpace2D.PREVIEW_PIXEL)) {
            throw new IllegalArgumentException(
                    "Manual candidate match is invalid");
        }
    }

    Point2D mapAtlasToSource(final Point2D atlasPoint) {
        return previewMapping.previewToSource(
                mapAtlasToPreview(atlasPoint));
    }

    Point2D mapAtlasToPreview(final Point2D atlasPoint) {
        final Point2D global = atlasToPreviewAffine.apply(
                Objects.requireNonNull(atlasPoint, "atlasPoint"));
        return outlineWarp.map(warp -> warp.apply(global)).orElse(global);
    }

    Point2D mapPreviewToAtlas(final Point2D previewPoint) {
        final Point2D unwarped = outlineWarp
                .map(warp -> warp.inverse(Objects.requireNonNull(
                        previewPoint, "previewPoint")))
                .orElseGet(() -> Objects.requireNonNull(
                        previewPoint, "previewPoint"));
        return atlasToPreviewAffine.inverse().apply(unwarped);
    }

    List<Point2D> rootOverlayPreviewPoints() {
        return rootOverlayPoints.stream().map(this::mapAtlasToPreview)
                .toList();
    }

    List<Point2D> guideOverlayPreviewPoints() {
        return guideOverlayPoints.stream().map(this::mapAtlasToPreview)
                .toList();
    }

    AffineTransform2D atlasToPreview(final PreviewMapping mapping) {
        Objects.requireNonNull(mapping, "mapping");
        if (!mapping.equals(previewMapping)) {
            throw new IllegalArgumentException(
                    "Candidate mapping is bound to a different preview");
        }
        return atlasToPreviewAffine;
    }

    boolean hasProvisionalOutlineWarp() {
        return outlineWarp.isPresent()
                && outlineWarpAnchorState
                        == OutlineWarpAnchorState.PROVISIONAL_UNCONFIRMED;
    }

    String outlineWarpContentSha256() {
        return outlineWarp.map(warp ->
                warp.diagnostics().contentSha256()).orElse("");
    }

    double horizontalScale() {
        return Math.hypot(atlasToSource.m00(), atlasToSource.m10());
    }

    double verticalScale() {
        return Math.hypot(atlasToSource.m01(), atlasToSource.m11());
    }

    double outlineAnisotropy() {
        return Math.max(horizontalScale(), verticalScale())
                / Math.min(horizontalScale(), verticalScale());
    }

    private static boolean finiteNonNegative(final double value) {
        return Double.isFinite(value) && value >= 0;
    }
}
