package org.atlasalign.application.c02;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class C02Mind4ScorerTest {

    @Test
    void identicalImagesHaveExactlyZeroDistance() {
        final int width = 11;
        final int height = 10;
        final float[] pixels = pattern(width, height);

        final C02MindScore score = scorer().score(
                new C02FeatureGrid(width, height, pixels),
                new C02FeatureGrid(width, height, pixels),
                support(full(width, height)));

        assertEquals(0.0,
                score.meanSquaredDescriptorDifference().orElseThrow());
        assertEquals((width - 4) * (height - 4),
                score.eligibleCenterCount());
    }

    @Test
    void descriptorIsInvariantToPositiveAndNegativeAffineIntensity() {
        final int width = 13;
        final int height = 12;
        final float[] original = pattern(width, height);
        final float[] positive = affine(original, 17.0f, 2.25f);
        final float[] negative = affine(original, -9.0f, -3.5f);
        final C02FeatureGrid tissue = new C02FeatureGrid(
                width, height, original);
        final C02ObservedSupportMask support = support(full(width, height));

        assertEquals(0.0, scorer().score(
                tissue,
                new C02FeatureGrid(width, height, positive),
                support).meanSquaredDescriptorDifference().orElseThrow(),
                2e-13);
        assertEquals(0.0, scorer().score(
                tissue,
                new C02FeatureGrid(width, height, negative),
                support).meanSquaredDescriptorDifference().orElseThrow(),
                2e-13);
    }

    @Test
    void constantImagesProduceAllOneDescriptorsAndZeroDistance() {
        final int width = 9;
        final int height = 9;
        final float[] first = new float[width * height];
        final float[] second = new float[width * height];
        Arrays.fill(first, 4.0f);
        Arrays.fill(second, -20.0f);
        final C02ObservedSupportMask observed = support(full(width, height));
        final C02MindDescriptorGrid descriptor = scorer().buildDescriptor(
                new C02FeatureGrid(width, height, first), observed);

        assertEquals(0.0, scorer().score(
                new C02FeatureGrid(width, height, first),
                new C02FeatureGrid(width, height, second),
                observed)
                .meanSquaredDescriptorDifference().orElseThrow());
        for (final double component : descriptor.components()) {
            assertEquals(1.0, component);
        }
    }

    @Test
    void eligibilityUsesTheExactCompleteRadiusTwoFootprint() {
        final int width = 9;
        final int height = 8;
        final C02MindScore score = scorer().score(
                new C02FeatureGrid(width, height, pattern(width, height)),
                new C02FeatureGrid(width, height, pattern(width, height)),
                support(full(width, height)));

        assertEquals(20, score.eligibleCenterCount());
        assertEquals(20.0 / 72.0, score.featureSupportFraction());

        final boolean[] withHole = fullValues(width, height);
        withHole[2 * width + 2] = false;
        final C02MindScore holeScore = scorer().score(
                new C02FeatureGrid(width, height, pattern(width, height)),
                new C02FeatureGrid(width, height, pattern(width, height)),
                support(BinaryMask.fromBooleans(width, height, withHole)));
        assertEquals(11, holeScore.eligibleCenterCount());
    }

    @Test
    void descriptorIdentityIsStableAndItsComponentsAreDefensivelyCopied() {
        final int width = 9;
        final int height = 9;
        final C02FeatureGrid feature = new C02FeatureGrid(
                width, height, pattern(width, height));
        final C02ObservedSupportMask support = support(full(width, height));
        final C02MindDescriptorGrid first = scorer().buildDescriptor(
                feature, support);
        final C02MindDescriptorGrid repeated = scorer().buildDescriptor(
                feature, support);
        final String hash = first.descriptorSha256();

        final double[] returned = first.components();
        returned[0] = 99;

        assertEquals(
                "492a3a62e94d575341a41ab3358b79ecab429d4628b8abc9aa1e790ed2593648",
                hash);
        assertEquals(hash, repeated.descriptorSha256());
        assertEquals(64, hash.length());
        assertNotEquals(99, first.components()[0]);
        assertEquals(first.eligibleCenterCount() * 4,
                first.components().length);
        assertEquals(0.0, scorer().score(first, repeated)
                .meanSquaredDescriptorDifference().orElseThrow());
    }

    @Test
    void rejectsNonfinitePixelsAndMismatchedDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new C02FeatureGrid(1, 1,
                        new float[] {Float.NaN}));
        assertThrows(IllegalArgumentException.class,
                () -> new C02FeatureGrid(1, 1,
                        new float[] {Float.POSITIVE_INFINITY}));
        assertThrows(IllegalArgumentException.class,
                () -> scorer().score(
                        new C02FeatureGrid(5, 5, new float[25]),
                        new C02FeatureGrid(6, 5, new float[30]),
                        support(full(5, 5))));
    }

    @Test
    void insufficientCompleteObservedSupportIsUnassessable() {
        final int width = 20;
        final int height = 20;
        final boolean[] observed = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                observed[y * width + x] = (x + y) % 2 == 0;
            }
        }
        for (int y = 7; y <= 11; y++) {
            for (int x = 7; x <= 11; x++) {
                observed[y * width + x] = true;
            }
        }
        final C02MindScore score = scorer().score(
                new C02FeatureGrid(width, height, pattern(width, height)),
                new C02FeatureGrid(width, height, pattern(width, height)),
                support(BinaryMask.fromBooleans(width, height, observed)));

        assertTrue(score.meanSquaredDescriptorDifference().isPresent());
        assertTrue(score.featureSupportFraction() < 0.05);
        assertFalse(score.assessable(0.05));
    }

    private static C02Mind4Scorer scorer() {
        return new C02Mind4Scorer();
    }

    private static C02ObservedSupportMask support(final BinaryMask observed) {
        return C02ObservedSupportMask.create(
                observed,
                BinaryMask.empty(observed.width(), observed.height()),
                SectionGeometry.FULL);
    }

    private static BinaryMask full(final int width, final int height) {
        return BinaryMask.fromBooleans(
                width, height, fullValues(width, height));
    }

    private static boolean[] fullValues(final int width, final int height) {
        final boolean[] values = new boolean[width * height];
        Arrays.fill(values, true);
        return values;
    }

    private static float[] pattern(final int width, final int height) {
        final float[] result = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                result[y * width + x] = (float) (
                        0.17 * x * x + 0.31 * y * y
                                + 0.23 * x * y
                                + StrictMath.sin(0.7 * x - 0.4 * y));
            }
        }
        return result;
    }

    private static float[] affine(
            final float[] values,
            final float intercept,
            final float slope) {
        final float[] result = values.clone();
        for (int index = 0; index < result.length; index++) {
            result[index] = intercept + slope * result[index];
        }
        return result;
    }
}
