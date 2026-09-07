package org.atlasalign.application.manual;

import java.util.List;
import java.util.Objects;
import org.atlasalign.core.AffineTransform2D;

/** One safe, unapplied assisted-placement preview. */
public record BoundaryFitCandidate(
        BoundaryFitRequest request,
        List<BoundaryFitMatch> matches,
        AffineTransform2D previewCorrection,
        double rootMeanSquareResidual,
        String solverRevision,
        String inputHash) {

    public BoundaryFitCandidate {
        request = Objects.requireNonNull(request, "request");
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        previewCorrection = Objects.requireNonNull(
                previewCorrection, "previewCorrection");
        if (!Double.isFinite(rootMeanSquareResidual)
                || rootMeanSquareResidual < 0) {
            throw new IllegalArgumentException(
                    "Boundary-fit residual must be finite and non-negative");
        }
        if (solverRevision == null || solverRevision.isBlank()
                || inputHash == null || inputHash.isBlank()) {
            throw new IllegalArgumentException(
                    "Boundary-fit solver and input identities are required");
        }
    }

    public long contentRevision() {
        return request.contentRevision();
    }

    public int includedMatchCount() {
        return (int) matches.stream().filter(
                BoundaryFitMatch::included).count();
    }
}
