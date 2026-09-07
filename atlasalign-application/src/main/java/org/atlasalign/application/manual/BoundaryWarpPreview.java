package org.atlasalign.application.manual;

import java.util.Objects;

/**
 * Unapplied nonlinear ghost for a partial outer-border draft.
 * It is display geometry only and is never accepted as evidence.
 */
public record BoundaryWarpPreview(
        BoundaryWarpRequest request,
        BoundaryWarpPreviewField field,
        String inputHash) {

    public BoundaryWarpPreview {
        request = Objects.requireNonNull(request, "request");
        field = Objects.requireNonNull(field, "field");
        inputHash = Objects.requireNonNull(inputHash, "inputHash");
        if (request.matches().stream().noneMatch(
                BoundaryFitMatch::included)) {
            throw new IllegalArgumentException(
                    "A boundary-warp preview requires an included pair");
        }
    }
}
