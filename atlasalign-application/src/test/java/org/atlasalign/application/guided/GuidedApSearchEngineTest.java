package org.atlasalign.application.guided;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.c01.C01ApPlaneCandidate;
import org.atlasalign.application.c01.C01ApPlaneScore;
import org.atlasalign.application.c01.C01GradientOrientationScore;
import org.junit.jupiter.api.Test;

class GuidedApSearchEngineTest {

    @Test
    void enumeratesExactThirtyThreeDistinctLevelsFromLockedInterval() {
        final GuidedApSearchPlan plan = GuidedApSearchPlan.prespecified(
                prior(200, 264), new AllenCoronalLevel(250));

        assertEquals(33, plan.candidates().size());
        assertEquals(200, level(plan, 0));
        assertEquals(202, level(plan, 1));
        assertEquals(232, level(plan, 16));
        assertEquals(264, level(plan, 32));
        assertEquals(33, plan.candidates().stream()
                .map(candidate -> candidate.coronalLevel()
                        .zeroBasedAnteriorPosteriorIndex())
                .distinct().count());
        assertEquals(GuidedRunMode.PRESPECIFIED_GUIDED_AUTOMATIC,
                plan.runMode());
    }

    @Test
    void formulaHandlesMinimumAndNonDivisibleIntervalsExactly() {
        final GuidedApSearchPlan minimum = GuidedApSearchPlan.prespecified(
                prior(100, 132), new AllenCoronalLevel(120));
        for (int index = 0; index < 33; index++) {
            assertEquals(100 + index, level(minimum, index));
        }

        final GuidedApSearchPlan nonDivisible = GuidedApSearchPlan.prespecified(
                prior(100, 133), new AllenCoronalLevel(120));
        for (int index = 0; index < 33; index++) {
            assertEquals(100 + Math.floorDiv(index * 33 + 16, 32),
                    level(nonDivisible, index));
        }
    }

    @Test
    void imageScoresAloneChooseCandidateAndPriorCentreAddsNoReward() {
        final GuidedApSearchPlan plan = GuidedApSearchPlan.prespecified(
                prior(200, 264), new AllenCoronalLevel(250));
        final GuidedApSearchResult result = new GuidedApSearchEngine().search(
                plan,
                candidate -> score(candidate.ordinal() == 3 ? 0.9 : 0.5, 0.1),
                sentinel(250, 0.4));

        assertTrue(result.assessable());
        assertEquals(level(plan, 3), result.selected().orElseThrow()
                .candidate().coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
        assertEquals(33, result.rawScores().size());
        assertEquals(3, result.topThree().size());
        assertFalse(result.r3OutsidePrior());
        assertEquals(GuidedRunMode.PRESPECIFIED_GUIDED_AUTOMATIC,
                result.runMode());
    }

    @Test
    void exactExternalR3SentinelFlagsConflictWithoutJoiningCandidateBudget() {
        final GuidedApSearchPlan plan = GuidedApSearchPlan.prespecified(
                prior(200, 264), new AllenCoronalLevel(300));
        final GuidedApSearchResult result = new GuidedApSearchEngine().search(
                plan,
                candidate -> score(candidate.ordinal() == 10 ? 0.8 : 0.5, 0.1),
                sentinel(300, 0.9));

        assertTrue(result.r3OutsidePrior());
        assertTrue(result.outsideSentinelOutscoredSelection());
        assertEquals(33, result.rawScores().size());
        assertEquals(300, result.immutableR3Sentinel().candidate()
                .coronalLevel().zeroBasedAnteriorPosteriorIndex());
    }

    @Test
    void boundaryAndUnavailableEvidenceFailClosed() {
        final GuidedApSearchPlan plan = GuidedApSearchPlan.prespecified(
                prior(200, 264), new AllenCoronalLevel(250));
        final GuidedApSearchResult boundary = new GuidedApSearchEngine().search(
                plan,
                candidate -> score(candidate.ordinal() == 0 ? 0.9 : 0.5, 0.1),
                sentinel(250, 0.4));
        assertTrue(boundary.selectedAtPriorBoundary());

        final GuidedApSearchResult failed = new GuidedApSearchEngine().search(
                plan,
                candidate -> score(0.5,
                        candidate.ordinal() == 7 ? 0.049 : 0.1),
                sentinel(250, 0.4));
        assertFalse(failed.assessable());
        assertEquals(1, failed.failureReasons().size());
        assertTrue(failed.topThree().isEmpty());
    }

    @Test
    void rejectsAnythingExceptExactAssessableR3Sentinel() {
        final GuidedApSearchPlan plan = GuidedApSearchPlan.prespecified(
                prior(200, 264), new AllenCoronalLevel(250));
        assertThrows(IllegalArgumentException.class,
                () -> new GuidedApSearchEngine().search(
                        plan, candidate -> score(0.5, 0.1),
                        sentinel(251, 0.5)));
        assertThrows(IllegalArgumentException.class,
                () -> new GuidedApSearchEngine().search(
                        plan, candidate -> score(0.5, 0.1),
                        sentinel(250, 0.049)));
    }

    private static int level(
            final GuidedApSearchPlan plan,
            final int ordinal) {
        return plan.candidates().get(ordinal).coronalLevel()
                .zeroBasedAnteriorPosteriorIndex();
    }

    private static AnatomicalSearchPriorV1 prior(
            final int lower,
            final int upper) {
        return AnatomicalSearchPriorV1.lockBeforeAutomaticDisplay(
                "provider-a",
                "1".repeat(64),
                "2".repeat(64),
                new AllenCoronalLevel(lower),
                new AllenCoronalLevel(upper),
                AnatomicalTissueClass.FULL_SECTION,
                Instant.parse("2026-08-09T09:00:00Z"),
                Instant.parse("2026-08-09T09:00:10Z"),
                Optional.empty());
    }

    private static C01GradientOrientationScore score(
            final double value,
            final double supportFraction) {
        final int internal = 1_000;
        final int scored = (int) Math.round(internal * supportFraction);
        return new C01GradientOrientationScore(
                scored == 0
                        ? OptionalDouble.empty()
                        : OptionalDouble.of(value),
                internal,
                internal,
                scored,
                scored / (double) internal);
    }

    private static C01ApPlaneScore sentinel(
            final int r3Level,
            final double score) {
        return new C01ApPlaneScore(
                new C01ApPlaneCandidate(
                        0, new AllenCoronalLevel(r3Level)),
                score(score, score < 0.05 ? 0.049 : 0.1));
    }
}
