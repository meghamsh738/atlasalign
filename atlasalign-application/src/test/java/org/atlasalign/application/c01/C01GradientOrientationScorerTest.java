package org.atlasalign.application.c01;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.atlasalign.application.SectionGeometry;
import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class C01GradientOrientationScorerTest {

    private static final int WIDTH = 41;
    private static final int HEIGHT = 41;

    @Test
    void isInvariantToContrastInversionAndDoesNotMutateInputs() {
        final float[] tissue = texture();
        final float[] atlas = tissue.clone();
        final float[] inverted = new float[atlas.length];
        for (int index = 0; index < atlas.length; index++) {
            inverted[index] = 100 - atlas[index];
        }
        final float[] tissueBefore = tissue.clone();
        final float[] invertedBefore = inverted.clone();
        final C01GradientOrientationScorer scorer =
                new C01GradientOrientationScorer();

        final C01GradientOrientationScore direct = scorer.score(
                WIDTH, HEIGHT, tissue, atlas, support());
        final C01GradientOrientationScore contrastReversed = scorer.score(
                WIDTH, HEIGHT, tissue, inverted, support());

        assertTrue(direct.orientationAgreement().isPresent());
        assertEquals(1.0,
                direct.orientationAgreement().getAsDouble(), 1e-12);
        assertEquals(direct.orientationAgreement().getAsDouble(),
                contrastReversed.orientationAgreement().orElseThrow(),
                1e-12);
        assertTrue(direct.featureSupportFraction() >= 0.05);
        assertArrayEquals(tissueBefore, tissue);
        assertArrayEquals(invertedBefore, inverted);
    }

    @Test
    void distinguishesOrthogonalInternalGradientOrientation() {
        final float[] horizontal = new float[WIDTH * HEIGHT];
        final float[] vertical = new float[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                horizontal[y * WIDTH + x] = x;
                vertical[y * WIDTH + x] = y;
            }
        }

        final C01GradientOrientationScore score =
                new C01GradientOrientationScorer().score(
                        WIDTH, HEIGHT, horizontal, vertical, support());

        assertTrue(score.orientationAgreement().isPresent());
        assertEquals(0.0,
                score.orientationAgreement().getAsDouble(), 1e-12);
        assertTrue(score.featureSupportFraction() >= 0.05);
    }

    @Test
    void outerPixelsCannotInfluenceTheInternalScore() {
        final float[] tissue = texture();
        final float[] atlas = tissue.clone();
        final float[] changedOuter = atlas.clone();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (x < 8 || x >= WIDTH - 8
                        || y < 8 || y >= HEIGHT - 8) {
                    changedOuter[y * WIDTH + x] =
                            (float) (10_000 + x * 31 - y * 17);
                }
            }
        }
        final C01GradientOrientationScorer scorer =
                new C01GradientOrientationScorer();

        final double baseline = scorer.score(
                WIDTH, HEIGHT, tissue, atlas, support())
                .orientationAgreement().orElseThrow();
        final double outerChanged = scorer.score(
                WIDTH, HEIGHT, tissue, changedOuter, support())
                .orientationAgreement().orElseThrow();

        assertEquals(baseline, outerChanged, 1e-12);
    }

    @Test
    void constantOrNonfiniteObservedFeaturesFailClosed() {
        final float[] constant = new float[WIDTH * HEIGHT];
        final C01GradientOrientationScore unavailable =
                new C01GradientOrientationScorer().score(
                        WIDTH, HEIGHT, constant, constant, support());

        assertFalse(unavailable.orientationAgreement().isPresent());
        assertFalse(unavailable.assessable(0.05));

        final float[] nonfinite = texture();
        nonfinite[20 * WIDTH + 20] = Float.NaN;
        assertThrows(IllegalArgumentException.class,
                () -> new C01GradientOrientationScorer().score(
                        WIDTH, HEIGHT, nonfinite, texture(), support()));
    }

    private static C01ObservedSupportMask support() {
        final boolean[] values = new boolean[WIDTH * HEIGHT];
        java.util.Arrays.fill(values, true);
        return C01ObservedSupportMask.create(
                BinaryMask.fromBooleans(WIDTH, HEIGHT, values),
                BinaryMask.empty(WIDTH, HEIGHT),
                SectionGeometry.FULL);
    }

    private static float[] texture() {
        final float[] values = new float[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                values[y * WIDTH + x] = (float) (
                        30 + 7 * Math.sin(x * 0.37)
                                + 5 * Math.cos(y * 0.29)
                                + 3 * Math.sin((x + y) * 0.17));
            }
        }
        return values;
    }
}
