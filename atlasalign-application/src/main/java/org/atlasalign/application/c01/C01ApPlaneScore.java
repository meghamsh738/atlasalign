package org.atlasalign.application.c01;

import java.util.Objects;

/** One raw C01 candidate score. */
public record C01ApPlaneScore(
        C01ApPlaneCandidate candidate,
        C01GradientOrientationScore gradientOrientation) {

    public C01ApPlaneScore {
        candidate = Objects.requireNonNull(candidate, "candidate");
        gradientOrientation = Objects.requireNonNull(
                gradientOrientation, "gradientOrientation");
    }
}
