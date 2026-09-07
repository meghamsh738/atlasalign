package org.atlasalign.application.manual;

import java.util.List;
import java.util.Objects;
import org.atlasalign.core.AffineTransform2D;

/**
 * Transient reviewer-only coarse-placement ghost.
 *
 * <p>A preview may be underdetermined for final application. It never enters
 * review history, evidence, confidence, or an accepted snapshot.</p>
 */
public record BoundaryFitPreview(
        BoundaryFitRequest request,
        List<BoundaryFitMatch> matches,
        AffineTransform2D previewCorrection,
        BoundaryFitPreviewKind kind,
        double rootMeanSquareResidual,
        String solverRevision,
        String inputHash) {

    public BoundaryFitPreview {
        request = Objects.requireNonNull(request, "request");
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        previewCorrection = Objects.requireNonNull(
                previewCorrection, "previewCorrection");
        kind = Objects.requireNonNull(kind, "kind");
        if (matches.stream().noneMatch(BoundaryFitMatch::included)) {
            throw new IllegalArgumentException(
                    "A boundary-fit preview needs an included match");
        }
        if (!Double.isFinite(rootMeanSquareResidual)
                || rootMeanSquareResidual < 0) {
            throw new IllegalArgumentException(
                    "Boundary-fit preview residual must be finite and non-negative");
        }
        if (solverRevision == null || solverRevision.isBlank()
                || inputHash == null || inputHash.isBlank()) {
            throw new IllegalArgumentException(
                    "Boundary-fit preview identities are required");
        }
    }

    public int includedMatchCount() {
        return (int) matches.stream().filter(
                BoundaryFitMatch::included).count();
    }
}
