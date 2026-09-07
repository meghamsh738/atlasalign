package org.atlasalign.application.c02;

import java.util.Objects;

/** One raw C02 candidate score. */
public record C02ApPlaneScore(
        C02ApPlaneCandidate candidate,
        C02MindScore mind) {

    public C02ApPlaneScore {
        candidate = Objects.requireNonNull(candidate, "candidate");
        mind = Objects.requireNonNull(mind, "mind");
    }
}
