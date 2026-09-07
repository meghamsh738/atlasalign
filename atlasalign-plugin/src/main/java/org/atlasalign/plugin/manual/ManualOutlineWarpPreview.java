package org.atlasalign.plugin.manual;

import java.util.List;
import java.util.Objects;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.plugin.review.SelectedAtlasContour;

/**
 * Exact reviewed-outline geometry for the normal FULL-section path.
 */
record ManualOutlineWarpPreview(
        AtlasCoronalPlane plane,
        SelectedAtlasContour rootContour,
        SelectedAtlasContour guideContour,
        List<List<Point2D>> rootAtlasPaths,
        List<List<Point2D>> guideAtlasPaths,
        AffineTransform2D atlasToSourceAffine,
        AffineTransform2D atlasToPreviewAffine,
        PreviewMapping previewMapping,
        BoundaryAuthoritativeTransform2D outlineTransform,
        String outlineFitMethod) {

    ManualOutlineWarpPreview {
        plane = Objects.requireNonNull(plane, "plane");
        rootContour = Objects.requireNonNull(rootContour, "rootContour");
        guideContour = Objects.requireNonNull(guideContour, "guideContour");
        rootAtlasPaths = copyPaths(rootAtlasPaths, "rootAtlasPaths");
        guideAtlasPaths = copyPaths(guideAtlasPaths, "guideAtlasPaths");
        atlasToSourceAffine = Objects.requireNonNull(
                atlasToSourceAffine, "atlasToSourceAffine");
        atlasToPreviewAffine = Objects.requireNonNull(
                atlasToPreviewAffine, "atlasToPreviewAffine");
        previewMapping = Objects.requireNonNull(
                previewMapping, "previewMapping");
        outlineTransform = Objects.requireNonNull(
                outlineTransform, "outlineTransform");
        outlineFitMethod = Objects.requireNonNull(
                outlineFitMethod, "outlineFitMethod").trim();
        if (rootAtlasPaths.isEmpty() || guideAtlasPaths.isEmpty()
                || atlasToSourceAffine.sourceSpace()
                        != CoordinateSpace2D.ATLAS_PLANE_PIXEL
                || atlasToSourceAffine.destinationSpace()
                        != CoordinateSpace2D.SOURCE_PIXEL
                || atlasToPreviewAffine.sourceSpace()
                        != CoordinateSpace2D.ATLAS_PLANE_PIXEL
                || atlasToPreviewAffine.destinationSpace()
                        != CoordinateSpace2D.PREVIEW_PIXEL
                || outlineTransform.sourceSpace()
                        != CoordinateSpace2D.PREVIEW_PIXEL
                || outlineTransform.destinationSpace()
                        != CoordinateSpace2D.PREVIEW_PIXEL
                || outlineFitMethod.isEmpty()) {
            throw new IllegalArgumentException(
                    "Boundary-authoritative outline preview is invalid");
        }
    }

    private static List<List<Point2D>> copyPaths(
            final List<List<Point2D>> paths, final String name) {
        final List<List<Point2D>> checked = Objects.requireNonNull(paths, name)
                .stream().map(List::copyOf).toList();
        if (checked.stream().anyMatch(path -> path.size() < 3)) {
            throw new IllegalArgumentException(
                    name + " must contain closed paths with at least three points");
        }
        return checked;
    }

    Point2D mapAtlasToPreview(final Point2D atlasPoint) {
        return outlineTransform.apply(atlasToPreviewAffine.apply(
                Objects.requireNonNull(atlasPoint, "atlasPoint")));
    }

    Point2D mapAtlasToSource(final Point2D atlasPoint) {
        return previewMapping.previewToSource(
                mapAtlasToPreview(atlasPoint));
    }

    Point2D mapPreviewToAtlas(final Point2D previewPoint) {
        return atlasToPreviewAffine.inverse().apply(
                outlineTransform.inverse(Objects.requireNonNull(
                        previewPoint, "previewPoint")));
    }

    List<List<Point2D>> mappedRootPreviewPaths() {
        return mapClosedPaths(rootAtlasPaths);
    }

    List<List<Point2D>> mappedGuidePreviewPaths() {
        return mapClosedPaths(guideAtlasPaths);
    }

    private List<List<Point2D>> mapClosedPaths(
            final List<List<Point2D>> atlasPaths) {
        return atlasPaths.stream().map(path -> outlineTransform.mapPath(
                path.stream().map(atlasToPreviewAffine::apply).toList(), true))
                .toList();
    }

    String contentSha256() {
        return outlineTransform.contentSha256();
    }
}
