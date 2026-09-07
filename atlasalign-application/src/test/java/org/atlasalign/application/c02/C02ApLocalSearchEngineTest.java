package org.atlasalign.application.c02;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class C02ApLocalSearchEngineTest {

    @Test
    void enumeratesExactlyMinusSixteenThroughPlusSixteen() {
        final C02ApSearchPlan plan = C02ApSearchPlan.around(
                new AllenCoronalLevel(264));

        assertEquals(33, plan.candidates().size());
        for (int index = 0; index < 33; index++) {
            assertEquals(index - 16,
                    plan.candidates().get(index).apOffsetIndices());
            assertEquals(248 + index,
                    plan.candidates().get(index).coronalLevel()
                            .zeroBasedAnteriorPosteriorIndex());
        }
        assertThrows(IllegalArgumentException.class,
                () -> C02ApSearchPlan.around(new AllenCoronalLevel(15)));
        assertThrows(IllegalArgumentException.class,
                () -> C02ApSearchPlan.around(new AllenCoronalLevel(512)));
    }

    @Test
    void lowerDistanceThenSupportThenAbsoluteOffsetThenAnteriorWins() {
        final C02ApSearchPlan plan = C02ApSearchPlan.around(
                new AllenCoronalLevel(264));
        final C02ApLocalSearchEngine engine = new C02ApLocalSearchEngine();

        final C02ApLocalSearchResult supportWinner = engine.search(
                plan,
                candidate -> score(
                        Math.abs(candidate.apOffsetIndices()) <= 2
                                ? 0.1 : 0.5,
                        candidate.apOffsetIndices() == 1 ? 20 : 10));
        assertEquals(1, supportWinner.selected().orElseThrow()
                .candidate().apOffsetIndices());

        final C02ApLocalSearchResult absoluteWinner = engine.search(
                plan,
                candidate -> score(
                        candidate.apOffsetIndices() == 0
                                || candidate.apOffsetIndices() == 2
                                ? 0.1 : 0.5,
                        10));
        assertEquals(0, absoluteWinner.selected().orElseThrow()
                .candidate().apOffsetIndices());

        final C02ApLocalSearchResult anteriorWinner = engine.search(
                plan,
                candidate -> score(
                        Math.abs(candidate.apOffsetIndices()) == 1
                                ? 0.1 : 0.5,
                        10));
        assertEquals(-1, anteriorWinner.selected().orElseThrow()
                .candidate().apOffsetIndices());
    }

    @Test
    void constantImageTieResolvesToTheR3Level() {
        final int size = 9;
        final float[] constant = new float[size * size];
        Arrays.fill(constant, 7);
        final C02FeatureGrid grid = new C02FeatureGrid(
                size, size, constant);
        final C02SearchContext context = C02TestFixtures.context(
                264, 1.25, -2.5);
        final C02ApSearchPlan plan = C02ApSearchPlan.around(
                context.r3Level());

        final C02ApLocalSearchResult result =
                new C02ApLocalSearchEngine().searchFeatureGrids(
                        plan,
                        context,
                        C02BoundFeatureGrid.tissue(context, grid),
                        support(size, size),
                        candidate -> C02BoundFeatureGrid.atlas(
                                context, candidate.coronalLevel(), grid));

        assertTrue(result.assessable());
        assertEquals(33, result.rawScores().size());
        assertEquals(3, result.topThree().size());
        assertEquals(0, result.selected().orElseThrow()
                .candidate().apOffsetIndices());
    }

    @Test
    void knownCentralCandidateBeatsSpatiallyShiftedPatterns() {
        final int width = 21;
        final int height = 19;
        final float[] tissuePixels = pattern(width, height);
        final C02FeatureGrid tissue = new C02FeatureGrid(
                width, height, tissuePixels);
        final C02SearchContext context = C02TestFixtures.context(
                264, 0, 0);

        final C02ApLocalSearchResult result =
                new C02ApLocalSearchEngine().searchFeatureGrids(
                        C02ApSearchPlan.around(context.r3Level()),
                        context,
                        C02BoundFeatureGrid.tissue(context, tissue),
                        support(width, height),
                        candidate -> C02BoundFeatureGrid.atlas(
                                context,
                                candidate.coronalLevel(),
                                new C02FeatureGrid(
                                        width,
                                        height,
                                        candidate.apOffsetIndices() == 0
                                                ? tissuePixels
                                                : shifted(
                                                        tissuePixels,
                                                        width,
                                                        height,
                                                        candidate
                                                                .apOffsetIndices()
                                                                < 0 ? -1 : 1))));

        assertEquals(0, result.selected().orElseThrow()
                .candidate().apOffsetIndices());
        assertEquals(0.0, result.selected().orElseThrow().mind()
                .meanSquaredDescriptorDifference().orElseThrow());
    }

    @Test
    void evidenceObserversReceiveOneTissueAndThirtyThreeAtlasDescriptors() {
        final int size = 9;
        final C02FeatureGrid grid = new C02FeatureGrid(
                size, size, pattern(size, size));
        final C02SearchContext context = C02TestFixtures.context(
                264, 0, 0);
        final AtomicInteger tissueDescriptors = new AtomicInteger();
        final AtomicInteger atlasDescriptors = new AtomicInteger();

        final C02ApLocalSearchResult result =
                new C02ApLocalSearchEngine().searchFeatureGrids(
                        C02ApSearchPlan.around(context.r3Level()),
                        context,
                        C02BoundFeatureGrid.tissue(context, grid),
                        support(size, size),
                        candidate -> C02BoundFeatureGrid.atlas(
                                context, candidate.coronalLevel(), grid),
                        ignored -> tissueDescriptors.incrementAndGet(),
                        (candidate, ignored) ->
                                atlasDescriptors.incrementAndGet());

        assertTrue(result.assessable());
        assertEquals(1, tissueDescriptors.get());
        assertEquals(C02ApSearchPlan.CANDIDATE_COUNT,
                atlasDescriptors.get());
    }

    @Test
    void changedTiltWrongLevelAndInsufficientCandidateFailClosed() {
        final int size = 9;
        final C02FeatureGrid grid = new C02FeatureGrid(
                size, size, pattern(size, size));
        final C02SearchContext context = C02TestFixtures.context(
                264, 1.25, -2.5);
        final C02SearchContext changedTilt = C02TestFixtures.context(
                264, 1.26, -2.5);
        final C02ApSearchPlan plan = C02ApSearchPlan.around(
                context.r3Level());
        final C02ApLocalSearchEngine engine = new C02ApLocalSearchEngine();

        assertTrue(score(0.1, 5).assessable(0.05));
        assertFalse(score(0.1, 4).assessable(0.05));

        assertThrows(IllegalArgumentException.class,
                () -> engine.searchFeatureGrids(
                        plan,
                        context,
                        C02BoundFeatureGrid.tissue(changedTilt, grid),
                        support(size, size),
                        candidate -> C02BoundFeatureGrid.atlas(
                                context, candidate.coronalLevel(), grid)));
        assertThrows(IllegalArgumentException.class,
                () -> engine.searchFeatureGrids(
                        plan,
                        context,
                        C02BoundFeatureGrid.tissue(context, grid),
                        support(size, size),
                        candidate -> C02BoundFeatureGrid.atlas(
                                context, plan.center(), grid)));

        final C02ApLocalSearchResult failed = engine.search(
                plan,
                candidate -> candidate.apOffsetIndices() == 4
                        ? score(0.1, 4)
                        : score(0.1, 10));
        assertFalse(failed.assessable());
        assertEquals(33, failed.rawScores().size());
        assertTrue(failed.topThree().isEmpty());
        assertEquals(1, failed.failureReasons().size());
        assertTrue(failed.failureReasons().get(0).contains("AP offset 4"));
    }

    private static C02MindScore score(
            final double distance,
            final int eligible) {
        return new C02MindScore(
                OptionalDouble.of(distance),
                100,
                eligible,
                eligible / 100.0);
    }

    private static C02ObservedSupportMask support(
            final int width,
            final int height) {
        final boolean[] observed = new boolean[width * height];
        Arrays.fill(observed, true);
        return C02ObservedSupportMask.create(
                BinaryMask.fromBooleans(width, height, observed),
                BinaryMask.empty(width, height),
                SectionGeometry.FULL);
    }

    private static float[] pattern(final int width, final int height) {
        final float[] result = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                result[y * width + x] = (float) (
                        0.11 * x * x + 0.29 * y * y + 0.17 * x * y
                                + 3 * StrictMath.sin(0.9 * x + 0.2 * y));
            }
        }
        return result;
    }

    private static float[] shifted(
            final float[] source,
            final int width,
            final int height,
            final int shiftX) {
        final float[] result = new float[source.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int sourceX = Math.max(
                        0, Math.min(width - 1, x - shiftX));
                result[y * width + x] = source[y * width + sourceX];
            }
        }
        return result;
    }
}
