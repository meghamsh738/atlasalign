package org.atlasalign.application.guided;

import java.util.Objects;
import org.atlasalign.application.c01.C01GradientOrientationScore;

/** Raw image-only score for one guided candidate. */
public record GuidedApPlaneScore(
        GuidedApPlaneCandidate candidate,
        C01GradientOrientationScore gradientOrientation) {

    public GuidedApPlaneScore {
        candidate = Objects.requireNonNull(candidate, "candidate");
        gradientOrientation = Objects.requireNonNull(
                gradientOrientation, "gradientOrientation");
    }
}
