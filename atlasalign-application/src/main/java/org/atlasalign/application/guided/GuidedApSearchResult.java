package org.atlasalign.application.guided;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.c01.C01ApPlaneScore;

/** Complete G01 evidence; never an accepted production coordinate. */
public record GuidedApSearchResult(
        GuidedApSearchPlan plan,
        List<GuidedApPlaneScore> rawScores,
        Optional<GuidedApPlaneScore> selected,
        List<GuidedApPlaneScore> topThree,
        C01ApPlaneScore immutableR3Sentinel,
        boolean r3OutsidePrior,
        boolean selectedAtPriorBoundary,
        boolean outsideSentinelOutscoredSelection,
        List<String> failureReasons) {

    public GuidedApSearchResult {
        plan = Objects.requireNonNull(plan, "plan");
        rawScores = List.copyOf(rawScores);
        selected = Objects.requireNonNull(selected, "selected");
        topThree = List.copyOf(topThree);
        immutableR3Sentinel = Objects.requireNonNull(
                immutableR3Sentinel, "immutableR3Sentinel");
        failureReasons = List.copyOf(failureReasons);
        if (rawScores.size() != GuidedApSearchPlan.CANDIDATE_COUNT) {
            throw new IllegalArgumentException(
                    "A G01 result must retain all 33 guided scores");
        }
        if (immutableR3Sentinel.candidate().apOffsetIndices() != 0
                || !immutableR3Sentinel.candidate().coronalLevel().equals(
                        plan.immutableR3Proposal())) {
            throw new IllegalArgumentException(
                    "The G01 sentinel must be the exact immutable r3 plane");
        }
        if (failureReasons.isEmpty() != selected.isPresent()) {
            throw new IllegalArgumentException(
                    "G01 selection and failure reasons are inconsistent");
        }
        if (selected.isPresent()) {
            if (topThree.size() != 3
                    || !topThree.get(0).equals(selected.get())) {
                throw new IllegalArgumentException(
                        "The G01 top three must begin with the selection");
            }
        } else if (!topThree.isEmpty()
                || selectedAtPriorBoundary
                || outsideSentinelOutscoredSelection) {
            throw new IllegalArgumentException(
                    "A failed G01 search cannot expose ranked conflict flags");
        }
    }

    public boolean assessable() {
        return selected.isPresent();
    }

    public GuidedRunMode runMode() {
        return GuidedRunMode.PRESPECIFIED_GUIDED_AUTOMATIC;
    }
}
