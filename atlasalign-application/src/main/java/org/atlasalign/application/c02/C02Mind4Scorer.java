package org.atlasalign.application.c02;

import java.util.BitSet;
import java.util.Objects;
import java.util.OptionalDouble;
import org.atlasalign.core.BinaryMask;

/** Frozen four-neighbour, sigma-0.5 MIND descriptor distance. */
public final class C02Mind4Scorer {

    public static final int FOOTPRINT_RADIUS_PIXELS = 2;
    public static final double PATCH_SIGMA_PIXELS = 0.5;

    private static final int[][] DESCRIPTOR_OFFSETS = {
        {-1, 0},
        {1, 0},
        {0, -1},
        {0, 1}
    };
    private static final double CENTER_WEIGHT = 0x1.3d1b0dd23c4eep-1;
    private static final double CARDINAL_WEIGHT = 0x1.57531f42ff7edp-4;
    private static final double DIAGONAL_WEIGHT = 0x1.73b628c43f1bdp-7;
    private static final double[] PATCH_WEIGHTS = {
        DIAGONAL_WEIGHT, CARDINAL_WEIGHT, DIAGONAL_WEIGHT,
        CARDINAL_WEIGHT, CENTER_WEIGHT, CARDINAL_WEIGHT,
        DIAGONAL_WEIGHT, CARDINAL_WEIGHT, DIAGONAL_WEIGHT
    };

    public C02MindScore score(
            final C02FeatureGrid tissue,
            final C02FeatureGrid atlas,
            final C02ObservedSupportMask support) {
        Objects.requireNonNull(tissue, "tissue");
        Objects.requireNonNull(atlas, "atlas");
        Objects.requireNonNull(support, "support");
        if (tissue.width() != atlas.width()
                || tissue.height() != atlas.height()) {
            throw new IllegalArgumentException(
                    "C02 tissue and atlas feature dimensions must match");
        }
        if (support.completeObserved().width() != tissue.width()
                || support.completeObserved().height() != tissue.height()) {
            throw new IllegalArgumentException(
                    "C02 support mask does not match feature dimensions");
        }
        return score(
                buildDescriptor(tissue, support),
                buildDescriptor(atlas, support));
    }

    /** Builds one immutable descriptor identity for evidence serialization. */
    public C02MindDescriptorGrid buildDescriptor(
            final C02FeatureGrid feature,
            final C02ObservedSupportMask support) {
        Objects.requireNonNull(feature, "feature");
        Objects.requireNonNull(support, "support");
        final BinaryMask observed = support.completeObserved();
        if (observed.width() != feature.width()
                || observed.height() != feature.height()) {
            throw new IllegalArgumentException(
                    "C02 support mask does not match feature dimensions");
        }

        final int width = feature.width();
        final int height = feature.height();
        final float[] pixels = feature.intensity();
        final BitSet eligibleBits = new BitSet(
                Math.multiplyExact(width, height));
        final double[] allocated = new double[Math.multiplyExact(
                Math.multiplyExact(width, height),
                C02MindDescriptorGrid.COMPONENT_COUNT)];
        int eligible = 0;
        for (int y = FOOTPRINT_RADIUS_PIXELS;
                y < height - FOOTPRINT_RADIUS_PIXELS;
                y++) {
            for (int x = FOOTPRINT_RADIUS_PIXELS;
                    x < width - FOOTPRINT_RADIUS_PIXELS;
                    x++) {
                if (!footprintInside(observed, x, y)) {
                    continue;
                }
                eligibleBits.set(y * width + x);
                final double[] descriptor = descriptor(pixels, width, x, y);
                System.arraycopy(
                        descriptor,
                        0,
                        allocated,
                        eligible * C02MindDescriptorGrid.COMPONENT_COUNT,
                        C02MindDescriptorGrid.COMPONENT_COUNT);
                eligible++;
            }
        }
        return new C02MindDescriptorGrid(
                width,
                height,
                BinaryMask.fromBitSet(width, height, eligibleBits),
                observed.foregroundCount(),
                java.util.Arrays.copyOf(
                        allocated,
                        eligible * C02MindDescriptorGrid.COMPONENT_COUNT));
    }

    /** Compares two already-built descriptors without recomputing either. */
    public C02MindScore score(
            final C02MindDescriptorGrid tissue,
            final C02MindDescriptorGrid atlas) {
        Objects.requireNonNull(tissue, "tissue");
        Objects.requireNonNull(atlas, "atlas");
        if (tissue.width() != atlas.width()
                || tissue.height() != atlas.height()
                || !tissue.eligibleCenters().equals(atlas.eligibleCenters())
                || tissue.completeObservedPixelCount()
                        != atlas.completeObservedPixelCount()) {
            throw new IllegalArgumentException(
                    "C02 descriptor grids must share dimensions and support");
        }
        final int eligible = tissue.eligibleCenterCount();
        final int observed = tissue.completeObservedPixelCount();
        final double supportFraction = eligible / (double) observed;
        if (eligible == 0) {
            return new C02MindScore(
                    OptionalDouble.empty(),
                    observed,
                    0,
                    0);
        }
        final double[] tissueComponents = tissue.components();
        final double[] atlasComponents = atlas.components();
        double squaredDifferenceSum = 0;
        for (int index = 0; index < tissueComponents.length; index++) {
            final double difference = tissueComponents[index]
                    - atlasComponents[index];
            squaredDifferenceSum += difference * difference;
        }
        final double meanSquaredDifference = squaredDifferenceSum
                / tissueComponents.length;
        if (!Double.isFinite(meanSquaredDifference)) {
            throw new IllegalArgumentException(
                    "C02 descriptor distance was not finite");
        }
        return new C02MindScore(
                OptionalDouble.of(meanSquaredDifference),
                observed,
                eligible,
                supportFraction);
    }

    private static double[] descriptor(
            final float[] pixels,
            final int width,
            final int centerX,
            final int centerY) {
        final double[] distances = new double[DESCRIPTOR_OFFSETS.length];
        double variance = 0;
        for (int component = 0;
                component < DESCRIPTOR_OFFSETS.length;
                component++) {
            final int[] offset = DESCRIPTOR_OFFSETS[component];
            distances[component] = patchDistance(
                    pixels, width, centerX, centerY, offset[0], offset[1]);
            variance += distances[component];
        }
        variance /= DESCRIPTOR_OFFSETS.length;
        final double[] result = new double[DESCRIPTOR_OFFSETS.length];
        if (variance == 0) {
            java.util.Arrays.fill(result, 1);
            return result;
        }
        double maximum = 0;
        for (int component = 0;
                component < DESCRIPTOR_OFFSETS.length;
                component++) {
            result[component] = StrictMath.exp(
                    -distances[component] / variance);
            maximum = Math.max(maximum, result[component]);
        }
        for (int component = 0;
                component < DESCRIPTOR_OFFSETS.length;
                component++) {
            result[component] /= maximum;
        }
        return result;
    }

    private static double patchDistance(
            final float[] pixels,
            final int width,
            final int centerX,
            final int centerY,
            final int offsetX,
            final int offsetY) {
        double distance = 0;
        int weightIndex = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                final double difference = pixels[
                        (centerY + dy) * width + centerX + dx]
                        - (double) pixels[(centerY + offsetY + dy) * width
                                + centerX + offsetX + dx];
                distance += PATCH_WEIGHTS[weightIndex++]
                        * difference * difference;
            }
        }
        return distance;
    }

    private static boolean footprintInside(
            final BinaryMask observed,
            final int centerX,
            final int centerY) {
        for (int y = centerY - FOOTPRINT_RADIUS_PIXELS;
                y <= centerY + FOOTPRINT_RADIUS_PIXELS;
                y++) {
            for (int x = centerX - FOOTPRINT_RADIUS_PIXELS;
                    x <= centerX + FOOTPRINT_RADIUS_PIXELS;
                    x++) {
                if (!observed.contains(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

}
