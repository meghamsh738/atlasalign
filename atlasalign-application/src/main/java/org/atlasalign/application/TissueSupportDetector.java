package org.atlasalign.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import org.atlasalign.core.BinaryMask;

/**
 * Builds a reviewer-editable tissue-support suggestion from copied pixels.
 *
 * <p>Dense IHC sections keep the primary segmentation mask unchanged. When
 * that mask is fragmented, a fluorescence-specific candidate compresses the
 * copied contrast logarithmically and evaluates a deterministic local-average
 * envelope. The enhanced candidate is installed only when it is plausible
 * and materially less fragmented. Source pixels are never modified and the
 * primary segmentation remains the evidence-bearing result.</p>
 */
public final class TissueSupportDetector {

    private static final double LOWER_PERCENTILE = 0.005;
    private static final double UPPER_PERCENTILE = 0.995;
    private static final int HISTOGRAM_BINS = 256;
    private static final double MAXIMUM_SUPPORT_FRACTION = 0.85;
    private static final double MAXIMUM_BORDER_FRACTION = 0.85;
    private static final double FRAGMENTED_LARGEST_FRACTION = 0.80;
    private static final double MINIMUM_SCORE_IMPROVEMENT = 0.10;
    private static final double MAXIMUM_PRIMARY_INTERIOR_HOLE_FRACTION =
            0.015;

    /** Immutable support-only result and concise audit measurements. */
    public record Suggestion(
            BinaryMask mask,
            TissueSupportDetectionMethod method,
            int retainedComponentCount,
            double largestComponentFraction,
            double foregroundFraction,
            double borderForegroundFraction,
            double score) {

        public Suggestion {
            mask = Objects.requireNonNull(mask, "mask");
            method = Objects.requireNonNull(method, "method");
            if (retainedComponentCount < 1
                    || !unitInterval(largestComponentFraction)
                    || !unitInterval(foregroundFraction)
                    || !unitInterval(borderForegroundFraction)
                    || !Double.isFinite(score)) {
                throw new IllegalArgumentException(
                        "Tissue-support suggestion diagnostics are invalid");
            }
        }

        private static boolean unitInterval(final double value) {
            return Double.isFinite(value) && value >= 0 && value <= 1;
        }
    }

    public Suggestion suggest(
            final int width,
            final int height,
            final float[] copiedPreviewPixels,
            final BinaryMask primarySegmentationMask) {
        Objects.requireNonNull(copiedPreviewPixels, "copiedPreviewPixels");
        Objects.requireNonNull(
                primarySegmentationMask, "primarySegmentationMask");
        if (width <= 0 || height <= 0
                || copiedPreviewPixels.length
                        != Math.multiplyExact(width, height)
                || primarySegmentationMask.width() != width
                || primarySegmentationMask.height() != height) {
            throw new IllegalArgumentException(
                    "Tissue-support inputs do not share preview geometry");
        }
        for (final float value : copiedPreviewPixels) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Tissue-support preview contains a non-finite value");
            }
        }
        final Suggestion primary = describe(
                primarySegmentationMask,
                TissueSupportDetectionMethod.SEGMENTATION_MASK);
        final double primaryInteriorHoleFraction = interiorHoleFraction(
                width, height, primarySegmentationMask);
        final boolean holeyPrimary = primaryInteriorHoleFraction
                > MAXIMUM_PRIMARY_INTERIOR_HOLE_FRACTION;
        if (primary.retainedComponentCount() <= 2
                && primary.largestComponentFraction()
                        >= FRAGMENTED_LARGEST_FRACTION
                && !holeyPrimary) {
            return primary;
        }

        final BinaryMask enhanced = fluorescenceEnvelope(
                width, height, copiedPreviewPixels);
        if (enhanced.isEmpty()) {
            return primary;
        }
        final Suggestion fluorescence = describe(
                enhanced,
                TissueSupportDetectionMethod.FLUORESCENCE_LOCAL_CONTRAST);
        final boolean plausible = fluorescence.foregroundFraction() >= 0.01
                && fluorescence.foregroundFraction()
                        <= MAXIMUM_SUPPORT_FRACTION
                && fluorescence.borderForegroundFraction()
                        <= MAXIMUM_BORDER_FRACTION
                && fluorescence.retainedComponentCount()
                        <= Math.max(4, primary.retainedComponentCount());
        final boolean lessFragmented = fluorescence.retainedComponentCount()
                        < primary.retainedComponentCount()
                || fluorescence.largestComponentFraction()
                        >= primary.largestComponentFraction() + 0.10
                || holeyPrimary;
        final boolean adequateScore = fluorescence.score()
                        >= primary.score() + MINIMUM_SCORE_IMPROVEMENT
                || holeyPrimary
                        && fluorescence.foregroundFraction()
                                >= primary.foregroundFraction()
                        && fluorescence.score() >= primary.score() - 0.05;
        if (plausible && lessFragmented
                && adequateScore) {
            return fluorescence;
        }
        return primary;
    }

    private static BinaryMask fluorescenceEnvelope(
            final int width,
            final int height,
            final float[] pixels) {
        final float[] sorted = pixels.clone();
        Arrays.sort(sorted);
        final int last = sorted.length - 1;
        final double lower = sorted[(int) Math.floor(
                last * LOWER_PERCENTILE)];
        final double upper = sorted[(int) Math.ceil(
                last * UPPER_PERCENTILE)];
        if (!(upper > lower)) {
            return BinaryMask.empty(width, height);
        }
        final double logMaximum = Math.log1p(255.0);
        final double[] enhanced = new double[pixels.length];
        for (int index = 0; index < pixels.length; index++) {
            final double normalized = Math.max(0, Math.min(1,
                    (pixels[index] - lower) / (upper - lower)));
            enhanced[index] = Math.log1p(255.0 * normalized) / logMaximum;
        }
        final int radius = Math.max(2,
                Math.min(32, (int) Math.round(
                        Math.min(width, height) / 128.0)));
        double[] smoothed = enhanced;
        for (int pass = 0; pass < 3; pass++) {
            smoothed = boxBlur(width, height, smoothed, radius);
        }
        final double threshold = otsuThreshold(smoothed);
        final boolean[] foreground = new boolean[smoothed.length];
        for (int index = 0; index < smoothed.length; index++) {
            foreground[index] = smoothed[index] > threshold;
        }
        return fillInteriorHoles(
                width, height,
                retainComponents(width, height, foreground));
    }

    /**
     * Fills only background islands that are completely enclosed by the
     * fluorescence envelope. Background connected to the preview edge is
     * retained, so genuine exterior notches, torn edges, and separated tissue
     * components are not bridged. This keeps the automatic cyan suggestion an
     * exterior support envelope instead of tracing every dark fluorescence
     * cavity as an editable crop hole.
     */
    private static BinaryMask fillInteriorHoles(
            final int width,
            final int height,
            final BinaryMask mask) {
        final boolean[] exteriorBackground = new boolean[
                Math.multiplyExact(width, height)];
        final int[] queue = new int[exteriorBackground.length];
        int head = 0;
        int tail = 0;
        for (int x = 0; x < width; x++) {
            tail = enqueueBackground(
                    width, height, mask, exteriorBackground, queue,
                    x, 0, tail);
            if (height > 1) {
                tail = enqueueBackground(
                        width, height, mask, exteriorBackground, queue,
                        x, height - 1, tail);
            }
        }
        for (int y = 1; y < height - 1; y++) {
            tail = enqueueBackground(
                    width, height, mask, exteriorBackground, queue,
                    0, y, tail);
            if (width > 1) {
                tail = enqueueBackground(
                        width, height, mask, exteriorBackground, queue,
                        width - 1, y, tail);
            }
        }
        while (head < tail) {
            final int current = queue[head++];
            final int x = current % width;
            final int y = current / width;
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    if (offsetX == 0 && offsetY == 0) {
                        continue;
                    }
                    tail = enqueueBackground(
                            width, height, mask, exteriorBackground, queue,
                            x + offsetX, y + offsetY, tail);
                }
            }
        }
        final BitSet filled = new BitSet(exteriorBackground.length);
        for (int index = 0; index < exteriorBackground.length; index++) {
            if (!exteriorBackground[index]) {
                filled.set(index);
            }
        }
        return BinaryMask.fromBitSet(width, height, filled);
    }

    private static double interiorHoleFraction(
            final int width,
            final int height,
            final BinaryMask mask) {
        final BinaryMask filled = fillInteriorHoles(width, height, mask);
        return (double) (filled.foregroundCount() - mask.foregroundCount())
                / Math.multiplyExact(width, height);
    }

    private static int enqueueBackground(
            final int width,
            final int height,
            final BinaryMask mask,
            final boolean[] exteriorBackground,
            final int[] queue,
            final int x,
            final int y,
            final int tail) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return tail;
        }
        final int index = y * width + x;
        if (mask.contains(x, y) || exteriorBackground[index]) {
            return tail;
        }
        exteriorBackground[index] = true;
        queue[tail] = index;
        return tail + 1;
    }

    private static double[] boxBlur(
            final int width,
            final int height,
            final double[] source,
            final int radius) {
        final double[] horizontal = new double[source.length];
        final double[] result = new double[source.length];
        for (int y = 0; y < height; y++) {
            double sum = 0;
            int start = 0;
            int end = Math.min(width - 1, radius);
            for (int x = start; x <= end; x++) {
                sum += source[y * width + x];
            }
            for (int x = 0; x < width; x++) {
                horizontal[y * width + x] = sum / (end - start + 1);
                final int nextStart = Math.max(0, x + 1 - radius);
                final int nextEnd = Math.min(width - 1, x + 1 + radius);
                if (nextStart > start) {
                    sum -= source[y * width + start];
                }
                if (nextEnd > end) {
                    sum += source[y * width + nextEnd];
                }
                start = nextStart;
                end = nextEnd;
            }
        }
        for (int x = 0; x < width; x++) {
            double sum = 0;
            int start = 0;
            int end = Math.min(height - 1, radius);
            for (int y = start; y <= end; y++) {
                sum += horizontal[y * width + x];
            }
            for (int y = 0; y < height; y++) {
                result[y * width + x] = sum / (end - start + 1);
                final int nextStart = Math.max(0, y + 1 - radius);
                final int nextEnd = Math.min(height - 1, y + 1 + radius);
                if (nextStart > start) {
                    sum -= horizontal[start * width + x];
                }
                if (nextEnd > end) {
                    sum += horizontal[nextEnd * width + x];
                }
                start = nextStart;
                end = nextEnd;
            }
        }
        return result;
    }

    private static double otsuThreshold(final double[] values) {
        final long[] histogram = new long[HISTOGRAM_BINS];
        for (final double value : values) {
            final int bin = Math.min(HISTOGRAM_BINS - 1,
                    Math.max(0, (int) Math.floor(value * HISTOGRAM_BINS)));
            histogram[bin]++;
        }
        long total = values.length;
        double totalMoment = 0;
        for (int bin = 0; bin < histogram.length; bin++) {
            totalMoment += (double) bin * histogram[bin];
        }
        long lowerCount = 0;
        double lowerMoment = 0;
        double bestVariance = -1;
        int bestThreshold = 0;
        for (int threshold = 0;
                threshold < histogram.length - 1; threshold++) {
            lowerCount += histogram[threshold];
            lowerMoment += (double) threshold * histogram[threshold];
            final long upperCount = total - lowerCount;
            if (lowerCount == 0 || upperCount == 0) {
                continue;
            }
            final double difference = lowerMoment / lowerCount
                    - (totalMoment - lowerMoment) / upperCount;
            final double variance = (double) lowerCount * upperCount
                    * difference * difference;
            if (variance > bestVariance) {
                bestVariance = variance;
                bestThreshold = threshold;
            }
        }
        return (bestThreshold + 1.0) / HISTOGRAM_BINS;
    }

    private static BinaryMask retainComponents(
            final int width,
            final int height,
            final boolean[] foreground) {
        final Components components = connectedComponents(
                width, height, foreground);
        if (components.sizes().isEmpty()) {
            return BinaryMask.empty(width, height);
        }
        final int minimum = Math.max(4,
                (int) Math.ceil(components.largestSize() * 0.01));
        final BitSet retained = new BitSet(foreground.length);
        for (int index = 0; index < foreground.length; index++) {
            final int label = components.labels()[index];
            if (label >= 0 && components.sizes().get(label) >= minimum) {
                retained.set(index);
            }
        }
        return BinaryMask.fromBitSet(width, height, retained);
    }

    private static Suggestion describe(
            final BinaryMask mask,
            final TissueSupportDetectionMethod method) {
        final boolean[] values = new boolean[
                Math.multiplyExact(mask.width(), mask.height())];
        for (int y = 0; y < mask.height(); y++) {
            for (int x = 0; x < mask.width(); x++) {
                values[y * mask.width() + x] = mask.contains(x, y);
            }
        }
        final Components components = connectedComponents(
                mask.width(), mask.height(), values);
        if (components.sizes().isEmpty()) {
            throw new IllegalArgumentException(
                    "Primary tissue-support mask must not be empty");
        }
        final int foreground = mask.foregroundCount();
        final int minimum = Math.max(1,
                (int) Math.ceil(components.largestSize() * 0.01));
        int retainedComponents = 0;
        for (final int size : components.sizes()) {
            if (size >= minimum) {
                retainedComponents++;
            }
        }
        final double area = (double) foreground
                / Math.multiplyExact(mask.width(), mask.height());
        final double largest = (double) components.largestSize()
                / foreground;
        final double border = borderFraction(mask);
        final double score = largest
                - 2 * border
                - 0.2 * Math.abs(area - 0.50)
                - 0.08 * Math.max(0, retainedComponents - 2);
        return new Suggestion(mask, method, retainedComponents,
                largest, area, border, score);
    }

    private static Components connectedComponents(
            final int width,
            final int height,
            final boolean[] foreground) {
        final int[] labels = new int[foreground.length];
        Arrays.fill(labels, -1);
        final int[] queue = new int[foreground.length];
        final List<Integer> sizes = new ArrayList<>();
        int largest = 0;
        for (int start = 0; start < foreground.length; start++) {
            if (!foreground[start] || labels[start] >= 0) {
                continue;
            }
            final int label = sizes.size();
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            labels[start] = label;
            while (head < tail) {
                final int current = queue[head++];
                final int x = current % width;
                final int y = current / width;
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    for (int offsetX = -1; offsetX <= 1; offsetX++) {
                        if (offsetX == 0 && offsetY == 0) {
                            continue;
                        }
                        final int nextX = x + offsetX;
                        final int nextY = y + offsetY;
                        if (nextX < 0 || nextX >= width
                                || nextY < 0 || nextY >= height) {
                            continue;
                        }
                        final int next = nextY * width + nextX;
                        if (foreground[next] && labels[next] < 0) {
                            labels[next] = label;
                            queue[tail++] = next;
                        }
                    }
                }
            }
            sizes.add(tail);
            largest = Math.max(largest, tail);
        }
        return new Components(labels, List.copyOf(sizes), largest);
    }

    private static double borderFraction(final BinaryMask mask) {
        final int width = mask.width();
        final int height = mask.height();
        int foreground = 0;
        for (int x = 0; x < width; x++) {
            foreground += mask.contains(x, 0) ? 1 : 0;
            if (height > 1) {
                foreground += mask.contains(x, height - 1) ? 1 : 0;
            }
        }
        for (int y = 1; y < height - 1; y++) {
            foreground += mask.contains(0, y) ? 1 : 0;
            if (width > 1) {
                foreground += mask.contains(width - 1, y) ? 1 : 0;
            }
        }
        final int perimeter = width == 1 || height == 1
                ? width * height : 2 * width + 2 * height - 4;
        return (double) foreground / perimeter;
    }

    private record Components(
            int[] labels,
            List<Integer> sizes,
            int largestSize) {
    }
}
