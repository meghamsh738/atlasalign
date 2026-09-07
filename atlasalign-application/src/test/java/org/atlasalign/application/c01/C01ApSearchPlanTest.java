package org.atlasalign.application.c01;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.CoordinateSpace2D;
import org.junit.jupiter.api.Test;

class C01ApSearchPlanTest {

    @Test
    void enumeratesTheExactFrozenWindowInApOrder() {
        final C01ApSearchPlan plan = C01ApSearchPlan.around(
                new AllenCoronalLevel(264));

        assertEquals(33, plan.candidates().size());
        assertEquals(-16, plan.candidates().get(0).apOffsetIndices());
        assertEquals(248, plan.candidates().get(0).coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
        assertEquals(0, plan.candidates().get(16).apOffsetIndices());
        assertEquals(264, plan.candidates().get(16).coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
        assertEquals(16, plan.candidates().get(32).apOffsetIndices());
        assertEquals(280, plan.candidates().get(32).coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
    }

    @Test
    void refusesToClipTheFrozenWindowAtAtlasBoundaries() {
        assertThrows(IllegalArgumentException.class,
                () -> C01ApSearchPlan.around(new AllenCoronalLevel(15)));
        assertThrows(IllegalArgumentException.class,
                () -> C01ApSearchPlan.around(new AllenCoronalLevel(512)));
    }

    @Test
    void rankingUsesScoreSupportDistanceThenAnteriorLevel() {
        final C01ApSearchPlan plan = C01ApSearchPlan.around(
                new AllenCoronalLevel(264));
        final C01ApLocalSearchResult supportWinner =
                new C01ApLocalSearchEngine().search(
                        plan,
                        candidate -> score(
                                Math.abs(candidate.apOffsetIndices()) <= 2
                                        ? 0.9 : 0.5,
                                candidate.apOffsetIndices() == 1
                                        ? 0.2 : 0.1));

        assertTrue(supportWinner.assessable());
        assertEquals(1, supportWinner.selected().orElseThrow()
                .candidate().apOffsetIndices());
        assertEquals(-16, supportWinner.rawScores().get(0)
                .candidate().apOffsetIndices());
        assertEquals(16, supportWinner.rawScores().get(32)
                .candidate().apOffsetIndices());

        final C01ApLocalSearchResult anteriorTieWinner =
                new C01ApLocalSearchEngine().search(
                        plan,
                        candidate -> score(
                                Math.abs(candidate.apOffsetIndices()) == 1
                                        ? 0.9 : 0.5,
                                0.1));
        assertEquals(-1, anteriorTieWinner.selected().orElseThrow()
                .candidate().apOffsetIndices());
    }

    @Test
    void reportsBoundarySelectionWithoutPromotingIt() {
        final C01ApLocalSearchResult result =
                new C01ApLocalSearchEngine().search(
                        C01ApSearchPlan.around(new AllenCoronalLevel(264)),
                        candidate -> score(
                                candidate.apOffsetIndices() == -16
                                        ? 1.0 : 0.5,
                                0.1));

        assertEquals(-16, result.selected().orElseThrow()
                .candidate().apOffsetIndices());
        assertTrue(result.selectedAtSearchBoundary());
    }

    @Test
    void oneUnassessableCandidateFailsTheWholeFrozenSearch() {
        final C01ApLocalSearchResult result =
                new C01ApLocalSearchEngine().search(
                        C01ApSearchPlan.around(new AllenCoronalLevel(264)),
                        candidate -> score(
                                0.5,
                                candidate.apOffsetIndices() == 4
                                        ? 0.04 : 0.1));

        assertFalse(result.assessable());
        assertTrue(result.selected().isEmpty());
        assertTrue(result.topThree().isEmpty());
        assertFalse(result.selectedAtSearchBoundary());
        assertEquals(1, result.failureReasons().size());
        assertTrue(result.failureReasons().get(0).contains("AP offset 4"));
    }

    @Test
    void exactlyFivePercentSupportIsAssessableButLessIsNot() {
        assertTrue(score(0.5, 0.05).assessable(0.05));
        assertFalse(score(0.5, 0.04).assessable(0.05));
    }

    @Test
    void featureGridOrchestrationRecoversTheKnownSyntheticCandidate() {
        final int width = 41;
        final int height = 41;
        final float[] horizontal = new float[width * height];
        final float[] vertical = new float[width * height];
        final boolean[] observed = new boolean[width * height];
        java.util.Arrays.fill(observed, true);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                horizontal[y * width + x] = x;
                vertical[y * width + x] = y;
            }
        }
        final C01FeatureGrid tissue = new C01FeatureGrid(
                width, height, horizontal);
        final C01SearchContext context = context(264, 1.25, -2.5);
        final C01ObservedSupportMask support =
                C01ObservedSupportMask.create(
                        BinaryMask.fromBooleans(
                                width, height, observed),
                        BinaryMask.empty(width, height),
                        SectionGeometry.FULL);

        final C01ApLocalSearchResult result =
                new C01ApLocalSearchEngine().searchFeatureGrids(
                        C01ApSearchPlan.around(
                                new AllenCoronalLevel(264)),
                        context,
                        C01BoundFeatureGrid.tissue(context, tissue),
                        support,
                        candidate -> C01BoundFeatureGrid.atlas(
                                context,
                                candidate.coronalLevel(),
                                new C01FeatureGrid(
                                        width,
                                        height,
                                        candidate.apOffsetIndices() == 3
                                                ? horizontal : vertical)));

        assertTrue(result.assessable());
        assertEquals(3, result.selected().orElseThrow()
                .candidate().apOffsetIndices());
    }

    @Test
    void featureGridOrchestrationRejectsChangedContextOrCandidateLevel() {
        final int size = 41;
        final float[] pixels = new float[size * size];
        final boolean[] observed = new boolean[size * size];
        java.util.Arrays.fill(observed, true);
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = index % size;
        }
        final C01FeatureGrid grid = new C01FeatureGrid(size, size, pixels);
        final C01SearchContext context = context(264, 1.25, -2.5);
        final C01SearchContext changedTilt = context(264, 1.26, -2.5);
        final C01ObservedSupportMask support = C01ObservedSupportMask.create(
                BinaryMask.fromBooleans(size, size, observed),
                BinaryMask.empty(size, size),
                SectionGeometry.FULL);
        final C01ApSearchPlan plan = C01ApSearchPlan.around(
                new AllenCoronalLevel(264));

        assertThrows(IllegalArgumentException.class,
                () -> new C01ApLocalSearchEngine().searchFeatureGrids(
                        plan,
                        context,
                        C01BoundFeatureGrid.tissue(changedTilt, grid),
                        support,
                        candidate -> C01BoundFeatureGrid.atlas(
                                context, candidate.coronalLevel(), grid)));
        assertThrows(IllegalArgumentException.class,
                () -> new C01ApLocalSearchEngine().searchFeatureGrids(
                        plan,
                        context,
                        C01BoundFeatureGrid.tissue(context, grid),
                        support,
                        candidate -> C01BoundFeatureGrid.atlas(
                                context, plan.center(), grid)));
    }

    @Test
    void contextIdentityChangesWithTiltTransformAndSource() {
        final C01SearchContext original = context(264, 1.25, -2.5);
        assertEquals(
                "b755b1ccfb597dc89350e282371b2f1fd3c792d433ebbccf476a53e19db117b6",
                original.identitySha256());
        assertEquals(original.identitySha256(),
                context(264, 1.25, -2.5).identitySha256());
        assertFalse(original.identitySha256().equals(
                context(264, 1.26, -2.5).identitySha256()));
        assertFalse(original.identitySha256().equals(
                context(265, 1.25, -2.5).identitySha256()));
    }

    private static C01SearchContext context(
            final int level,
            final double sagittal,
            final double horizontal) {
        return new C01SearchContext(
                "1".repeat(64),
                "2".repeat(64),
                C01SearchContext.R3_RELEASE_ID,
                "3".repeat(64),
                new AllenCoronalLevel(level),
                new AtlasPlaneTilt(sagittal, horizontal),
                new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1, 0, 2, 0, 1, 3),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                C01SearchContext.FEATURE_GENERATION_ID);
    }

    private static C01GradientOrientationScore score(
            final double agreement,
            final double supportFraction) {
        final int internal = 100;
        final int scored = (int) Math.round(
                internal * supportFraction);
        return new C01GradientOrientationScore(
                OptionalDouble.of(agreement),
                internal,
                internal,
                scored,
                scored / (double) internal);
    }
}
