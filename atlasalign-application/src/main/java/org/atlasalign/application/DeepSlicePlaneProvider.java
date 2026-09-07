package org.atlasalign.application;

/**
 * Optional boundary for a local, verified, offline DeepSlice worker.
 */
@FunctionalInterface
public interface DeepSlicePlaneProvider {

    DeepSlicePlanePrediction estimate(DeepSliceInput input)
            throws DeepSliceUnavailableException;
}
