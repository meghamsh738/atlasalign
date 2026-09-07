package org.atlasalign.application.dg;

import java.util.Objects;

/** One candidate plus its aggregate robust standardized loss. */
public record DgRankedCandidate(
        DgCandidateEvidence evidence,
        double aggregateLoss) {

    public DgRankedCandidate {
        evidence = Objects.requireNonNull(evidence, "evidence");
        if (!Double.isFinite(aggregateLoss)) {
            throw new IllegalArgumentException(
                    "D01 aggregate loss must be finite");
        }
    }
}
