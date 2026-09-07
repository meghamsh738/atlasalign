package org.atlasalign.plugin.review;

import org.atlasalign.atlas.AtlasCoronalPlane;

/**
 * Potentially slow atlas-plane input. Controllers always invoke it off the
 * Swing event-dispatch thread.
 */
@FunctionalInterface
public interface AtlasPlaneSource {

    AtlasCoronalPlane load(int zeroBasedAnteriorPosteriorIndex);

    /**
     * Loads the requested plane. Existing axis-aligned sources remain valid;
     * oblique-capable sources override this default method.
     */
    default AtlasCoronalPlane load(final AtlasPlaneRequest request) {
        return load(request.zeroBasedAnteriorPosteriorIndex());
    }
}
