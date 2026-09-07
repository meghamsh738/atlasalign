package org.atlasalign.application.manual;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.core.Point2D;

/**
 * One numbered, transient atlas-border anchor awaiting a reviewer-selected
 * tissue-border endpoint.
 *
 * <p>The atlas-plane coordinate is stable in the verified plane. The mapped
 * atlas and tissue coordinates use preview pixels. These anchors are manual
 * review geometry, not landmarks or automatic-confidence evidence.</p>
 */
public record BoundaryFitAnchor(
        String id,
        int ordinal,
        String positionLabel,
        Point2D atlasPlanePoint,
        Point2D atlasPreviewPoint,
        Optional<Point2D> tissuePreviewPoint,
        boolean included) {

    public BoundaryFitAnchor {
        if (id == null || id.isBlank() || ordinal <= 0) {
            throw new IllegalArgumentException(
                    "Boundary-fit anchor identity is invalid");
        }
        positionLabel = Objects.requireNonNull(
                positionLabel, "positionLabel").trim();
        if (positionLabel.isEmpty()) {
            throw new IllegalArgumentException(
                    "Boundary-fit anchor position label is invalid");
        }
        atlasPlanePoint = Objects.requireNonNull(
                atlasPlanePoint, "atlasPlanePoint");
        atlasPreviewPoint = Objects.requireNonNull(
                atlasPreviewPoint, "atlasPreviewPoint");
        tissuePreviewPoint = Objects.requireNonNull(
                tissuePreviewPoint, "tissuePreviewPoint");
    }

    /** Legacy snapshot/test constructor where atlas and preview coordinates match. */
    public BoundaryFitAnchor(
            final String id,
            final int ordinal,
            final Point2D atlasPreviewPoint,
            final Optional<Point2D> tissuePreviewPoint,
            final boolean included) {
        this(id, ordinal, "border point " + ordinal, atlasPreviewPoint,
                atlasPreviewPoint, tissuePreviewPoint, included);
    }

    public Optional<BoundaryFitMatch> completedMatch() {
        return tissuePreviewPoint.map(tissue -> new BoundaryFitMatch(
                id, atlasPreviewPoint, tissue,
                BoundaryFitMatchOrigin.USER_PLACED, included));
    }

    public BoundaryFitAnchor withAtlasPoints(
            final Point2D planePoint,
            final Point2D previewPoint) {
        return new BoundaryFitAnchor(id, ordinal, positionLabel,
                Objects.requireNonNull(planePoint, "planePoint"),
                Objects.requireNonNull(previewPoint, "previewPoint"),
                tissuePreviewPoint, included);
    }

    public BoundaryFitAnchor withTissuePoint(final Point2D point) {
        return new BoundaryFitAnchor(id, ordinal, positionLabel,
                atlasPlanePoint, atlasPreviewPoint, Optional.of(
                        Objects.requireNonNull(point, "point")), true);
    }

    public BoundaryFitAnchor withoutTissuePoint() {
        return new BoundaryFitAnchor(id, ordinal, positionLabel,
                atlasPlanePoint, atlasPreviewPoint, Optional.empty(),
                included);
    }

    public BoundaryFitAnchor withIncluded(final boolean nextIncluded) {
        return new BoundaryFitAnchor(id, ordinal, positionLabel,
                atlasPlanePoint, atlasPreviewPoint, tissuePreviewPoint,
                nextIncluded);
    }
}
