package org.atlasalign.application.c01;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete raw C01 search evidence; never an accepted production coordinate. */
public record C01ApLocalSearchResult(
        C01ApSearchPlan plan,
        List<C01ApPlaneScore> rawScores,
        Optional<C01ApPlaneScore> selected,
        List<C01ApPlaneScore> topThree,
        boolean selectedAtSearchBoundary,
        List<String> failureReasons) {

    public C01ApLocalSearchResult {
        plan = Objects.requireNonNull(plan, "plan");
        rawScores = List.copyOf(rawScores);
        selected = Objects.requireNonNull(selected, "selected");
        topThree = List.copyOf(topThree);
        failureReasons = List.copyOf(failureReasons);
        if (rawScores.size() != C01ApSearchPlan.CANDIDATE_COUNT) {
            throw new IllegalArgumentException(
                    "C01 result must preserve all 33 raw candidates");
        }
        if (failureReasons.isEmpty() != selected.isPresent()) {
            throw new IllegalArgumentException(
                    "C01 selection and failure reasons are inconsistent");
        }
        if (selected.isPresent()) {
            if (topThree.size() != 3
                    || !topThree.get(0).equals(selected.get())) {
                throw new IllegalArgumentException(
                        "C01 top-three ranking must begin with the selection");
            }
        } else if (!topThree.isEmpty() || selectedAtSearchBoundary) {
            throw new IllegalArgumentException(
                    "Failed C01 searches cannot expose a ranking or boundary flag");
        }
    }

    public boolean assessable() {
        return selected.isPresent();
    }
}
