package org.atlasalign.application.manual;

import java.util.Objects;
import org.atlasalign.core.Point2D;

/**
 * One manual-only atlas-border to tissue-border correspondence.
 *
 * <p>Both endpoints are expressed in preview pixels. The atlas endpoint is
 * the current, pre-fit placement of one exterior atlas sample. These matches
 * are deliberately separate from {@code LandmarkPair}: they are reviewer
 * geometry and never confidence evidence.</p>
 */
public record BoundaryFitMatch(
        String id,
        Point2D atlasPreviewPoint,
        Point2D tissuePreviewPoint,
        BoundaryFitMatchOrigin origin,
        boolean included) {

    public BoundaryFitMatch {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Boundary-fit match id must not be blank");
        }
        atlasPreviewPoint = Objects.requireNonNull(
                atlasPreviewPoint, "atlasPreviewPoint");
        tissuePreviewPoint = Objects.requireNonNull(
                tissuePreviewPoint, "tissuePreviewPoint");
        origin = Objects.requireNonNull(origin, "origin");
    }

    public BoundaryFitMatch withAtlasPoint(final Point2D point) {
        return new BoundaryFitMatch(id, point, tissuePreviewPoint,
                origin, included);
    }

    public BoundaryFitMatch withTissuePoint(final Point2D point) {
        return new BoundaryFitMatch(id, atlasPreviewPoint, point,
                origin, included);
    }

    public BoundaryFitMatch withIncluded(final boolean nextIncluded) {
        return new BoundaryFitMatch(id, atlasPreviewPoint,
                tissuePreviewPoint, origin, nextIncluded);
    }
}
