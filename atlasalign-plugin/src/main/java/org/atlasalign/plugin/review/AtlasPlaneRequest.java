package org.atlasalign.plugin.review;

import java.util.Objects;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasPlaneTilt;

/** Immutable request for one axis-aligned or oblique coronal atlas plane. */
public record AtlasPlaneRequest(
        AllenCoronalLevel level,
        AtlasPlaneTilt tilt,
        boolean showAnatomy) {

    public AtlasPlaneRequest(
            final AllenCoronalLevel level,
            final AtlasPlaneTilt tilt) {
        this(level, tilt, false);
    }

    public AtlasPlaneRequest {
        level = Objects.requireNonNull(level, "level");
        tilt = Objects.requireNonNull(tilt, "tilt");
    }

    public int zeroBasedAnteriorPosteriorIndex() {
        return level.zeroBasedAnteriorPosteriorIndex();
    }
}
