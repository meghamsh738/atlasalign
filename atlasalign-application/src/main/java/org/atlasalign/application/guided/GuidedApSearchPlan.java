package org.atlasalign.application.guided;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.atlasalign.application.AllenCoronalLevel;

/** Exact G01 candidate placement from one immutable anatomical interval. */
public final class GuidedApSearchPlan {

    public static final int CANDIDATE_COUNT = 33;
    private final AnatomicalSearchPriorV1 prior;
    private final AllenCoronalLevel immutableR3Proposal;
    private final List<GuidedApPlaneCandidate> candidates;

    private GuidedApSearchPlan(
            final AnatomicalSearchPriorV1 prior,
            final AllenCoronalLevel immutableR3Proposal,
            final List<GuidedApPlaneCandidate> candidates) {
        this.prior = prior;
        this.immutableR3Proposal = immutableR3Proposal;
        this.candidates = List.copyOf(candidates);
    }

    public static GuidedApSearchPlan prespecified(
            final AnatomicalSearchPriorV1 prior,
            final AllenCoronalLevel immutableR3Proposal) {
        Objects.requireNonNull(prior, "prior");
        Objects.requireNonNull(immutableR3Proposal, "immutableR3Proposal");
        final int lower = prior.inclusiveStart()
                .zeroBasedAnteriorPosteriorIndex();
        final int upper = prior.inclusiveEnd()
                .zeroBasedAnteriorPosteriorIndex();
        final int span = upper - lower;
        final ArrayList<GuidedApPlaneCandidate> candidates =
                new ArrayList<>(CANDIDATE_COUNT);
        final HashSet<Integer> levels = new HashSet<>();
        final int r3 = immutableR3Proposal
                .zeroBasedAnteriorPosteriorIndex();
        for (int i = 0; i < CANDIDATE_COUNT; i++) {
            final int level = lower + Math.floorDiv(i * span + 16, 32);
            if (!levels.add(level)) {
                throw new IllegalArgumentException(
                        "The guided interval did not produce 33 distinct levels");
            }
            candidates.add(new GuidedApPlaneCandidate(
                    i, new AllenCoronalLevel(level), level - r3));
        }
        if (candidates.get(0).coronalLevel()
                        .zeroBasedAnteriorPosteriorIndex() != lower
                || candidates.get(CANDIDATE_COUNT - 1).coronalLevel()
                        .zeroBasedAnteriorPosteriorIndex() != upper) {
            throw new IllegalStateException(
                    "Guided candidate enumeration lost an interval endpoint");
        }
        return new GuidedApSearchPlan(
                prior, immutableR3Proposal, candidates);
    }

    public AnatomicalSearchPriorV1 prior() {
        return prior;
    }

    public AllenCoronalLevel immutableR3Proposal() {
        return immutableR3Proposal;
    }

    public List<GuidedApPlaneCandidate> candidates() {
        return candidates;
    }

    public GuidedRunMode runMode() {
        return GuidedRunMode.PRESPECIFIED_GUIDED_AUTOMATIC;
    }
}
