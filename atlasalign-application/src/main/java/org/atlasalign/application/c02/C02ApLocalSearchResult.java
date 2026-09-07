package org.atlasalign.application.c02;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Complete raw C02 search evidence; never an accepted production coordinate. */
public record C02ApLocalSearchResult(
        C02ApSearchPlan plan,
        List<C02ApPlaneScore> rawScores,
        Optional<C02ApPlaneScore> selected,
        List<C02ApPlaneScore> topThree,
        boolean selectedAtSearchBoundary,
        List<String> failureReasons) {

    public C02ApLocalSearchResult {
        plan = Objects.requireNonNull(plan, "plan");
        rawScores = List.copyOf(rawScores);
        selected = Objects.requireNonNull(selected, "selected");
        topThree = List.copyOf(topThree);
        failureReasons = List.copyOf(failureReasons);
        if (rawScores.size() != C02ApSearchPlan.CANDIDATE_COUNT) {
            throw new IllegalArgumentException(
                    "C02 result must preserve all 33 raw candidates");
        }
        if (failureReasons.isEmpty() != selected.isPresent()) {
            throw new IllegalArgumentException(
                    "C02 selection and failure reasons are inconsistent");
        }
        if (selected.isPresent()) {
            if (topThree.size() != 3
                    || !topThree.get(0).equals(selected.get())) {
                throw new IllegalArgumentException(
                        "C02 top-three ranking must begin with the selection");
            }
        } else if (!topThree.isEmpty() || selectedAtSearchBoundary) {
            throw new IllegalArgumentException(
                    "Failed C02 searches cannot expose a ranking or boundary flag");
        }
    }

    public boolean assessable() {
        return selected.isPresent();
    }
}
