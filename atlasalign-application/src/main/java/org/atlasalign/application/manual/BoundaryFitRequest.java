package org.atlasalign.application.manual;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;

/** Immutable, provenance-bound request for one assisted boundary fit. */
public record BoundaryFitRequest(
        long contentRevision,
        BoundaryFitModel model,
        ReviewSectionMode sectionMode,
        Optional<ManualHemisphereWarp2D.AtlasSide> targetSide,
        List<BoundaryFitSample> atlasBoundary,
        List<BoundaryFitSample> tissueBoundary,
        List<BoundaryFitMatch> matches,
        AffineTransform2D currentOrientedAtlasToPreview,
        double sourceAxisRadians,
        int previewWidth,
        int previewHeight,
        String planeHash,
        String placementHash,
        String tissueSupportHash,
        String sourceHash,
        String atlasHash) {

    public BoundaryFitRequest {
        if (contentRevision < 0 || previewWidth <= 0 || previewHeight <= 0
                || !Double.isFinite(sourceAxisRadians)) {
            throw new IllegalArgumentException(
                    "Boundary-fit request geometry is invalid");
        }
        model = Objects.requireNonNull(model, "model");
        sectionMode = Objects.requireNonNull(sectionMode, "sectionMode");
        targetSide = Objects.requireNonNull(targetSide, "targetSide");
        if (sectionMode == ReviewSectionMode.DISJOINED
                != targetSide.isPresent()) {
            throw new IllegalArgumentException(
                    "Only a Disjoined boundary fit targets one atlas side");
        }
        atlasBoundary = List.copyOf(Objects.requireNonNull(
                atlasBoundary, "atlasBoundary"));
        tissueBoundary = List.copyOf(Objects.requireNonNull(
                tissueBoundary, "tissueBoundary"));
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        currentOrientedAtlasToPreview = Objects.requireNonNull(
                currentOrientedAtlasToPreview,
                "currentOrientedAtlasToPreview");
        if (currentOrientedAtlasToPreview.sourceSpace()
                != CoordinateSpace2D.ATLAS_PLANE_PIXEL
                || currentOrientedAtlasToPreview.destinationSpace()
                != CoordinateSpace2D.PREVIEW_PIXEL) {
            throw new IllegalArgumentException(
                    "Boundary-fit current placement must map oriented atlas pixels to preview pixels");
        }
        if (currentOrientedAtlasToPreview.determinant() <= 0) {
            throw new IllegalArgumentException(
                    "Boundary-fit oriented placement must preserve orientation parity");
        }
        final HashSet<String> identifiers = new HashSet<>();
        if (matches.stream().anyMatch(match -> match == null
                || !identifiers.add(match.id()))) {
            throw new IllegalArgumentException(
                    "Boundary-fit match ids must be unique");
        }
        planeHash = requireHash(planeHash, "planeHash");
        placementHash = requireHash(placementHash, "placementHash");
        tissueSupportHash = requireHash(
                tissueSupportHash, "tissueSupportHash");
        sourceHash = requireHash(sourceHash, "sourceHash");
        atlasHash = requireHash(atlasHash, "atlasHash");
    }

    public BoundaryFitRequest withMatches(
            final List<BoundaryFitMatch> nextMatches) {
        return new BoundaryFitRequest(contentRevision, model, sectionMode,
                targetSide, atlasBoundary, tissueBoundary, nextMatches,
                currentOrientedAtlasToPreview, sourceAxisRadians, previewWidth,
                previewHeight, planeHash, placementHash, tissueSupportHash,
                sourceHash, atlasHash);
    }

    public BoundaryFitRequest withModel(final BoundaryFitModel nextModel) {
        return new BoundaryFitRequest(contentRevision, nextModel,
                sectionMode, targetSide, atlasBoundary, tissueBoundary,
                matches, currentOrientedAtlasToPreview, sourceAxisRadians,
                previewWidth, previewHeight, planeHash, placementHash,
                tissueSupportHash, sourceHash, atlasHash);
    }

    private static String requireHash(final String value,
            final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
