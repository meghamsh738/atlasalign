package org.atlasalign.application;

import java.util.Objects;

/**
 * One application-owned model vector and the geometry derived from it.
 */
public record DeepSliceModelPrediction(
        DeepSliceOuv ouv,
        DeepSlicePlaneGeometry geometry) {

    public DeepSliceModelPrediction {
        ouv = Objects.requireNonNull(ouv, "ouv");
        geometry = Objects.requireNonNull(geometry, "geometry");
        if (!DeepSlicePlaneGeometry.from(ouv).equals(geometry)) {
            throw new IllegalArgumentException(
                    "DeepSlice model geometry must be derived from its O/U/V vector");
        }
    }

    /** Derives a model prediction entirely in the application layer. */
    public static DeepSliceModelPrediction from(final DeepSliceOuv ouv) {
        return new DeepSliceModelPrediction(
                ouv, DeepSlicePlaneGeometry.from(ouv));
    }
}
