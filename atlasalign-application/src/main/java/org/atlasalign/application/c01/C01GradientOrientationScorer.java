package org.atlasalign.application.c01;

import java.util.Arrays;
import java.util.Objects;
import java.util.OptionalDouble;
import org.atlasalign.core.BinaryMask;

/**
 * Frozen C01 contrast-sign-invariant Sobel gradient-orientation score.
 *
 * <p>Inputs are temporary feature arrays on the same Allen 25 µm plane grid.
 * This class neither reads nor mutates an ImageJ source.</p>
 */
public final class C01GradientOrientationScorer {

    public static final double NORMALIZATION_LOW_QUANTILE = 0.01;
    public static final double NORMALIZATION_HIGH_QUANTILE = 0.99;
    public static final double HIGH_GRADIENT_QUANTILE = 0.75;

    public C01GradientOrientationScore score(
            final int width,
            final int height,
            final float[] tissueIntensity,
            final float[] atlasIntensity,
            final C01ObservedSupportMask support) {
        Objects.requireNonNull(tissueIntensity, "tissueIntensity");
        Objects.requireNonNull(atlasIntensity, "atlasIntensity");
        Objects.requireNonNull(support, "support");
        if (width <= 0 || height <= 0
                || tissueIntensity.length != Math.multiplyExact(width, height)
                || atlasIntensity.length != tissueIntensity.length) {
            throw new IllegalArgumentException(
                    "C01 feature arrays must match positive dimensions");
        }
        if (support.completeObserved().width() != width
                || support.completeObserved().height() != height) {
            throw new IllegalArgumentException(
                    "C01 support mask does not match feature dimensions");
        }
        final double[] tissue = normalize(
                tissueIntensity, support.completeObserved());
        final double[] atlas = normalize(
                atlasIntensity, support.completeObserved());
        if (tissue == null || atlas == null) {
            return unavailable(support.internalObserved().foregroundCount(), 0);
        }

        final BinaryMask internal = support.internalObserved();
        final int pixelCount = Math.multiplyExact(width, height);
        final double[] tissueX = new double[pixelCount];
        final double[] tissueY = new double[pixelCount];
        final double[] atlasX = new double[pixelCount];
        final double[] atlasY = new double[pixelCount];
        final double[] tissueMagnitude = new double[pixelCount];
        final double[] atlasMagnitude = new double[pixelCount];
        final int[] eligibleIndices = new int[pixelCount];
        int eligible = 0;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                if (!threeByThreeInside(internal, x, y)) {
                    continue;
                }
                final int index = y * width + x;
                tissueX[index] = sobelX(tissue, width, x, y);
                tissueY[index] = sobelY(tissue, width, x, y);
                atlasX[index] = sobelX(atlas, width, x, y);
                atlasY[index] = sobelY(atlas, width, x, y);
                tissueMagnitude[index] = Math.hypot(
                        tissueX[index], tissueY[index]);
                atlasMagnitude[index] = Math.hypot(
                        atlasX[index], atlasY[index]);
                if (Double.isFinite(tissueMagnitude[index])
                        && Double.isFinite(atlasMagnitude[index])) {
                    eligibleIndices[eligible++] = index;
                }
            }
        }
        if (eligible == 0) {
            return unavailable(internal.foregroundCount(), 0);
        }
        final double tissueThreshold = quantile(
                tissueMagnitude, eligibleIndices, eligible,
                HIGH_GRADIENT_QUANTILE);
        final double atlasThreshold = quantile(
                atlasMagnitude, eligibleIndices, eligible,
                HIGH_GRADIENT_QUANTILE);
        double agreementSum = 0;
        int scored = 0;
        for (int position = 0; position < eligible; position++) {
            final int index = eligibleIndices[position];
            final double tissueLength = tissueMagnitude[index];
            final double atlasLength = atlasMagnitude[index];
            if (tissueLength <= 0 || atlasLength <= 0
                    || tissueLength < tissueThreshold
                    || atlasLength < atlasThreshold) {
                continue;
            }
            final double dot = tissueX[index] * atlasX[index]
                    + tissueY[index] * atlasY[index];
            agreementSum += Math.abs(dot / (tissueLength * atlasLength));
            scored++;
        }
        final double supportFraction = scored
                / (double) internal.foregroundCount();
        return new C01GradientOrientationScore(
                scored == 0
                        ? OptionalDouble.empty()
                        : OptionalDouble.of(agreementSum / scored),
                internal.foregroundCount(),
                eligible,
                scored,
                supportFraction);
    }

    private static C01GradientOrientationScore unavailable(
            final int internalPixels,
            final int eligiblePixels) {
        return new C01GradientOrientationScore(
                OptionalDouble.empty(),
                internalPixels,
                eligiblePixels,
                0,
                0);
    }

    private static double[] normalize(
            final float[] source,
            final BinaryMask support) {
        final float[] values = new float[support.foregroundCount()];
        int count = 0;
        for (int y = 0; y < support.height(); y++) {
            for (int x = 0; x < support.width(); x++) {
                if (!support.contains(x, y)) {
                    continue;
                }
                final float value = source[y * support.width() + x];
                if (!Float.isFinite(value)) {
                    throw new IllegalArgumentException(
                            "C01 observed feature pixels must be finite");
                }
                values[count++] = value;
            }
        }
        Arrays.sort(values);
        final double low = quantile(values, NORMALIZATION_LOW_QUANTILE);
        final double high = quantile(values, NORMALIZATION_HIGH_QUANTILE);
        if (!(high > low)) {
            return null;
        }
        final double inverseRange = 1.0 / (high - low);
        final double[] normalized = new double[source.length];
        for (int index = 0; index < source.length; index++) {
            final double value = source[index];
            normalized[index] = Math.max(0, Math.min(
                    1, (value - low) * inverseRange));
        }
        return normalized;
    }

    private static double quantile(
            final float[] sorted,
            final double probability) {
        if (sorted.length == 0) {
            throw new IllegalArgumentException(
                    "Cannot calculate a quantile of no values");
        }
        return sorted[(int) Math.floor(
                probability * (sorted.length - 1))];
    }

    private static double quantile(
            final double[] values,
            final int[] indices,
            final int count,
            final double probability) {
        final double[] copy = new double[count];
        for (int position = 0; position < count; position++) {
            copy[position] = values[indices[position]];
        }
        Arrays.sort(copy);
        return copy[(int) Math.floor(probability * (count - 1))];
    }

    private static boolean threeByThreeInside(
            final BinaryMask support,
            final int centerX,
            final int centerY) {
        for (int y = centerY - 1; y <= centerY + 1; y++) {
            for (int x = centerX - 1; x <= centerX + 1; x++) {
                if (!support.contains(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double sobelX(
            final double[] values,
            final int width,
            final int x,
            final int y) {
        return -values[(y - 1) * width + x - 1]
                + values[(y - 1) * width + x + 1]
                - 2 * values[y * width + x - 1]
                + 2 * values[y * width + x + 1]
                - values[(y + 1) * width + x - 1]
                + values[(y + 1) * width + x + 1];
    }

    private static double sobelY(
            final double[] values,
            final int width,
            final int x,
            final int y) {
        return -values[(y - 1) * width + x - 1]
                - 2 * values[(y - 1) * width + x]
                - values[(y - 1) * width + x + 1]
                + values[(y + 1) * width + x - 1]
                + 2 * values[(y + 1) * width + x]
                + values[(y + 1) * width + x + 1];
    }
}
