package org.atlasalign.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.core.BinaryMask;

/**
 * Deterministic multi-candidate Otsu segmentation on an independent preview.
 *
 * <p>Ordinary and percentile-clipped histograms are evaluated in both
 * intensity polarities. Thresholds are always applied to the original copied
 * preview values. The selected candidate favors a connected foreground that
 * does not occupy the image border. Small isolated components below one
 * percent of the largest component are removed.</p>
 */
public final class TissueSegmenter {

    private static final int HISTOGRAM_BINS = 256;
    private static final double LOWER_PERCENTILE = 0.005;
    private static final double UPPER_PERCENTILE = 0.995;
    private static final double MINIMUM_FOREGROUND_FRACTION = 0.01;
    private static final double MAXIMUM_FOREGROUND_FRACTION = 0.90;
    private static final double MINIMUM_LARGEST_COMPONENT_FRACTION =
            0.125;
    private static final double MAXIMUM_BORDER_FOREGROUND_FRACTION =
            0.90;
    private static final double BORDER_SATURATION_AREA = 0.85;
    private static final double BORDER_SATURATION_FRACTION = 0.80;
    private static final double MINIMUM_EXTERIOR_BACKGROUND_FRACTION = 0.05;
    private static final double MINIMUM_EXTERIOR_BACKGROUND_BORDER_FRACTION =
            0.80;

    public TissueSegmentationResult segment(
            final int width,
            final int height,
            final float[] previewPixels) {
        Objects.requireNonNull(previewPixels, "previewPixels");
        if (width <= 0 || height <= 0
                || previewPixels.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException(
                    "Preview geometry does not match its pixels");
        }
        final IntensityWindow ordinary =
                finiteIntensityWindow(previewPixels);
        final IntensityWindow clipped =
                percentileWindow(previewPixels, ordinary);
        final List<Candidate> candidates = new ArrayList<>(8);
        final List<String> auditNotes = new ArrayList<>(2);
        addCandidates(
                candidates,
                auditNotes,
                width,
                height,
                previewPixels,
                TissueSegmentationMethod.ORDINARY_OTSU,
                ordinary);
        addCandidates(
                candidates,
                auditNotes,
                width,
                height,
                previewPixels,
                TissueSegmentationMethod.PERCENTILE_CLIPPED_OTSU,
                clipped);
        Candidate selected = null;
        for (final Candidate candidate : candidates) {
            if (candidate.diagnostic().valid()
                    && (selected == null
                    || candidate.diagnostic().score()
                    > selected.diagnostic().score())) {
                selected = candidate;
            }
        }
        final List<TissueSegmentationCandidateDiagnostic> diagnostics =
                candidates.stream()
                        .map(Candidate::diagnostic)
                        .toList();
        if (selected == null) {
            throw new TissueSegmentationException(
                    "No plausible tissue foreground was detected. "
                            + candidateSummary(diagnostics)
                            + " Try another registration channel; "
                            + "the source image was not modified.",
                    diagnostics,
                    auditNotes);
        }
        final TissueSegmentationCandidateDiagnostic chosen =
                selected.diagnostic();
        final TissueSupportDetector.Suggestion support =
                new TissueSupportDetector().suggest(
                        width, height, previewPixels, selected.mask());
        return new TissueSegmentationResult(
                selected.mask(),
                chosen.polarity(),
                chosen.threshold(),
                chosen.foregroundFraction(),
                chosen.largestComponentFraction(),
                chosen.borderForegroundFraction(),
                chosen.method(),
                chosen.histogramLowerBound(),
                chosen.histogramUpperBound(),
                chosen.percentileWindowFallback(),
                chosen.score(),
                diagnostics,
                auditNotes,
                support.mask(),
                support.method());
    }

    private static IntensityWindow finiteIntensityWindow(
            final float[] values) {
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (final float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Preview contains a non-finite intensity");
            }
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        if (!(maximum > minimum)) {
            throw new TissueSegmentationException(
                    "Preview has no finite intensity contrast; "
                            + "try another registration channel. "
                            + "The source image was not modified.",
                    List.of());
        }
        return new IntensityWindow(
                (float) minimum,
                (float) maximum,
                false);
    }

    private static IntensityWindow percentileWindow(
            final float[] values,
            final IntensityWindow ordinary) {
        final float[] sorted = values.clone();
        Arrays.sort(sorted);
        final int last = sorted.length - 1;
        float lower = sorted[(int) Math.floor(
                last * LOWER_PERCENTILE)];
        float upper = sorted[(int) Math.ceil(
                last * UPPER_PERCENTILE)];
        boolean fallback = false;
        if (!(upper > lower)) {
            lower = ordinary.lower();
            upper = ordinary.upper();
            fallback = true;
        }
        return new IntensityWindow(lower, upper, fallback);
    }

    private static Histogram histogram(
            final float[] values,
            final IntensityWindow window) {
        final long[] counts = new long[HISTOGRAM_BINS];
        for (final float value : values) {
            final double clipped = Math.max(
                    window.lower(),
                    Math.min(window.upper(), value));
            final int bin = Math.min(
                    HISTOGRAM_BINS - 1,
                    (int) ((clipped - window.lower())
                            / (window.upper() - window.lower())
                            * HISTOGRAM_BINS));
            counts[bin]++;
        }
        return new Histogram(
                window.lower(), window.upper(), counts);
    }

    private static void addCandidates(
            final List<Candidate> candidates,
            final List<String> auditNotes,
            final int width,
            final int height,
            final float[] pixels,
            final TissueSegmentationMethod method,
            final IntensityWindow window) {
        final Histogram histogram = histogram(pixels, window);
        final int thresholdBin =
                otsuThreshold(histogram.counts());
        final float threshold = (float) (histogram.minimum()
                + (thresholdBin + 1.0)
                * (histogram.maximum() - histogram.minimum())
                / HISTOGRAM_BINS);
        candidates.add(candidate(
                width,
                height,
                pixels,
                threshold,
                Optional.empty(),
                true,
                method,
                window,
                false));
        candidates.add(candidate(
                width,
                height,
                pixels,
                threshold,
                Optional.empty(),
                false,
                method,
                window,
                false));

        final TissueSegmentationMethod multiMethod =
                method == TissueSegmentationMethod.ORDINARY_OTSU
                        ? TissueSegmentationMethod.ORDINARY_MULTI_OTSU
                        : TissueSegmentationMethod
                                .PERCENTILE_CLIPPED_MULTI_OTSU;
        final MultiOtsuThresholds multi =
                multiOtsuThresholds(histogram.counts());
        if (multi == null) {
            auditNotes.add(multiMethod
                    + ": three-class histogram has fewer than "
                    + "three non-empty classes");
            return;
        }
        final float lowerThreshold = physicalThreshold(
                histogram, multi.lowerThresholdBin());
        final float upperThreshold = physicalThreshold(
                histogram, multi.upperThresholdBin());
        candidates.add(candidate(
                width,
                height,
                pixels,
                lowerThreshold,
                Optional.of(upperThreshold),
                true,
                multiMethod,
                window,
                true));
        candidates.add(candidate(
                width,
                height,
                pixels,
                upperThreshold,
                Optional.of(lowerThreshold),
                false,
                multiMethod,
                window,
                true));
    }

    private static float physicalThreshold(
            final Histogram histogram,
            final int thresholdBin) {
        return (float) (histogram.minimum()
                + (thresholdBin + 1.0)
                * (histogram.maximum() - histogram.minimum())
                / HISTOGRAM_BINS);
    }

    private static int otsuThreshold(final long[] histogram) {
        long total = 0;
        double totalMoment = 0;
        for (int bin = 0; bin < histogram.length; bin++) {
            total += histogram[bin];
            totalMoment += (double) bin * histogram[bin];
        }
        long lowerCount = 0;
        double lowerMoment = 0;
        double bestVariance = -1;
        int bestThreshold = 0;
        for (int threshold = 0;
                threshold < histogram.length - 1;
                threshold++) {
            lowerCount += histogram[threshold];
            if (lowerCount == 0) {
                continue;
            }
            final long upperCount = total - lowerCount;
            if (upperCount == 0) {
                break;
            }
            lowerMoment += (double) threshold * histogram[threshold];
            final double lowerMean = lowerMoment / lowerCount;
            final double upperMean =
                    (totalMoment - lowerMoment) / upperCount;
            final double difference = lowerMean - upperMean;
            final double variance =
                    (double) lowerCount * upperCount * difference * difference;
            if (variance > bestVariance) {
                bestVariance = variance;
                bestThreshold = threshold;
            }
        }
        return bestThreshold;
    }

    private static MultiOtsuThresholds multiOtsuThresholds(
            final long[] histogram) {
        final long[] prefixCounts = new long[histogram.length + 1];
        final double[] prefixMoments =
                new double[histogram.length + 1];
        for (int bin = 0; bin < histogram.length; bin++) {
            prefixCounts[bin + 1] =
                    prefixCounts[bin] + histogram[bin];
            prefixMoments[bin + 1] =
                    prefixMoments[bin] + (double) bin * histogram[bin];
        }
        final long totalCount = prefixCounts[histogram.length];
        final double totalMean =
                prefixMoments[histogram.length] / totalCount;
        double bestVariance = -1;
        MultiOtsuThresholds best = null;
        for (int lower = 0; lower <= 253; lower++) {
            for (int upper = lower + 1; upper <= 254; upper++) {
                final long lowCount = prefixCounts[lower + 1];
                final long middleCount =
                        prefixCounts[upper + 1]
                                - prefixCounts[lower + 1];
                final long highCount =
                        totalCount - prefixCounts[upper + 1];
                if (lowCount == 0 || middleCount == 0
                        || highCount == 0) {
                    continue;
                }
                final double lowMean =
                        prefixMoments[lower + 1] / lowCount;
                final double middleMean =
                        (prefixMoments[upper + 1]
                                - prefixMoments[lower + 1])
                                / middleCount;
                final double highMean =
                        (prefixMoments[histogram.length]
                                - prefixMoments[upper + 1])
                                / highCount;
                final double lowDifference = lowMean - totalMean;
                final double middleDifference = middleMean - totalMean;
                final double highDifference = highMean - totalMean;
                final double variance =
                        lowCount * lowDifference * lowDifference
                                + middleCount * middleDifference
                                        * middleDifference
                                + highCount * highDifference
                                        * highDifference;
                if (variance > bestVariance) {
                    bestVariance = variance;
                    best = new MultiOtsuThresholds(lower, upper);
                }
            }
        }
        return best;
    }

    private static Candidate candidate(
            final int width,
            final int height,
            final float[] pixels,
            final float threshold,
            final Optional<Float> companionThreshold,
            final boolean bright,
            final TissueSegmentationMethod method,
            final IntensityWindow window,
            final boolean exteriorBackgroundRequired) {
        final boolean[] foreground = new boolean[pixels.length];
        int rawCount = 0;
        for (int index = 0; index < pixels.length; index++) {
            foreground[index] =
                    bright ? pixels[index] > threshold
                            : pixels[index] <= threshold;
            if (foreground[index]) {
                rawCount++;
            }
        }
        final Components components =
                connectedComponents(width, height, foreground);
        final int minimumComponentSize =
                Math.max(4, (int) Math.ceil(components.largestSize() * 0.01));
        final BitSet retained = new BitSet(pixels.length);
        for (int index = 0; index < foreground.length; index++) {
            final int label = components.labels()[index];
            if (label >= 0
                    && components.sizes().get(label) >= minimumComponentSize) {
                retained.set(index);
            }
        }
        final BinaryMask mask =
                BinaryMask.fromBitSet(width, height, retained);
        final double foregroundFraction =
                (double) retained.cardinality() / pixels.length;
        final double largestFraction = rawCount == 0
                ? 0
                : (double) components.largestSize() / rawCount;
        final double borderFraction =
                borderFraction(width, height, retained);
        final double plausibleAreaPenalty =
                Math.abs(foregroundFraction - 0.35);
        final double score =
                0.25 * largestFraction
                        - 2 * borderFraction
                        - plausibleAreaPenalty;
        String rejectionReason = rejectionReason(
                foregroundFraction,
                largestFraction,
                borderFraction);
        Optional<Double> exteriorBackgroundFraction = Optional.empty();
        Optional<Double> exteriorBackgroundBorderFraction = Optional.empty();
        if (exteriorBackgroundRequired) {
            final ExteriorBackground exterior = exteriorBackground(
                    width, height, pixels, threshold, bright);
            exteriorBackgroundFraction =
                    Optional.of(exterior.areaFraction());
            exteriorBackgroundBorderFraction =
                    Optional.of(exterior.borderFraction());
            if (rejectionReason == null
                    && exterior.areaFraction()
                            < MINIMUM_EXTERIOR_BACKGROUND_FRACTION) {
                rejectionReason =
                        "exterior background below 5%";
            }
            if (rejectionReason == null
                    && exterior.borderFraction()
                            < MINIMUM_EXTERIOR_BACKGROUND_BORDER_FRACTION) {
                rejectionReason =
                        "exterior background covers below 80% "
                                + "of the image border";
            }
        }
        final TissueSegmentationCandidateDiagnostic diagnostic =
                new TissueSegmentationCandidateDiagnostic(
                        method,
                        bright
                                ? TissuePolarity.BRIGHT_ON_DARK
                                : TissuePolarity.DARK_ON_BRIGHT,
                        threshold,
                        companionThreshold,
                        window.lower(),
                        window.upper(),
                        window.percentileFallback(),
                        foregroundFraction,
                        largestFraction,
                        borderFraction,
                        exteriorBackgroundFraction,
                        exteriorBackgroundBorderFraction,
                        score,
                        Optional.ofNullable(rejectionReason));
        return new Candidate(
                mask,
                diagnostic);
    }

    private static ExteriorBackground exteriorBackground(
            final int width,
            final int height,
            final float[] pixels,
            final float threshold,
            final boolean brightTissue) {
        final boolean[] excluded = new boolean[pixels.length];
        for (int index = 0; index < pixels.length; index++) {
            excluded[index] = brightTissue
                    ? pixels[index] <= threshold
                    : pixels[index] > threshold;
        }
        final boolean[] reached = new boolean[pixels.length];
        final int[] queue = new int[pixels.length];
        int tail = 0;
        for (int x = 0; x < width; x++) {
            tail = enqueueExterior(x, excluded, reached, queue, tail);
            if (height > 1) {
                tail = enqueueExterior(
                        (height - 1) * width + x,
                        excluded, reached, queue, tail);
            }
        }
        for (int y = 1; y < height - 1; y++) {
            tail = enqueueExterior(
                    y * width, excluded, reached, queue, tail);
            if (width > 1) {
                tail = enqueueExterior(
                        y * width + width - 1,
                        excluded, reached, queue, tail);
            }
        }
        int head = 0;
        while (head < tail) {
            final int current = queue[head++];
            final int x = current % width;
            final int y = current / width;
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    if (offsetX == 0 && offsetY == 0) {
                        continue;
                    }
                    final int neighborX = x + offsetX;
                    final int neighborY = y + offsetY;
                    if (neighborX < 0 || neighborX >= width
                            || neighborY < 0 || neighborY >= height) {
                        continue;
                    }
                    tail = enqueueExterior(
                            neighborY * width + neighborX,
                            excluded, reached, queue, tail);
                }
            }
        }
        int borderReached = 0;
        for (int x = 0; x < width; x++) {
            if (reached[x]) {
                borderReached++;
            }
            if (height > 1
                    && reached[(height - 1) * width + x]) {
                borderReached++;
            }
        }
        for (int y = 1; y < height - 1; y++) {
            if (reached[y * width]) {
                borderReached++;
            }
            if (width > 1
                    && reached[y * width + width - 1]) {
                borderReached++;
            }
        }
        final int perimeter = width == 1 || height == 1
                ? width * height
                : 2 * width + 2 * height - 4;
        return new ExteriorBackground(
                (double) tail / pixels.length,
                (double) borderReached / perimeter);
    }

    private static int enqueueExterior(
            final int index,
            final boolean[] excluded,
            final boolean[] reached,
            final int[] queue,
            final int tail) {
        if (!excluded[index] || reached[index]) {
            return tail;
        }
        reached[index] = true;
        queue[tail] = index;
        return tail + 1;
    }

    private static String rejectionReason(
            final double foregroundFraction,
            final double largestComponentFraction,
            final double borderFraction) {
        if (foregroundFraction < MINIMUM_FOREGROUND_FRACTION) {
            return "foreground below 1%";
        }
        if (foregroundFraction > MAXIMUM_FOREGROUND_FRACTION) {
            return "foreground above 90%";
        }
        if (largestComponentFraction
                < MINIMUM_LARGEST_COMPONENT_FRACTION) {
            return "no component contains at least 12.5% of raw foreground";
        }
        if (borderFraction
                > MAXIMUM_BORDER_FOREGROUND_FRACTION) {
            return "more than 90% of the image border is foreground";
        }
        if (foregroundFraction > BORDER_SATURATION_AREA
                && borderFraction > BORDER_SATURATION_FRACTION) {
            return "foreground and image border are saturated";
        }
        return null;
    }

    private static String candidateSummary(
            final List<TissueSegmentationCandidateDiagnostic> diagnostics) {
        return diagnostics.stream()
                .map(value -> value.method() + "/"
                        + value.polarity() + ": "
                        + value.rejectionReason()
                                .orElse("valid"))
                .reduce((left, right) -> left + "; " + right)
                .orElse("No threshold candidates were available.");
    }

    private static Components connectedComponents(
            final int width,
            final int height,
            final boolean[] foreground) {
        final int[] labels = new int[foreground.length];
        Arrays.fill(labels, -1);
        final List<Integer> sizes = new ArrayList<>();
        final int[] queue = new int[foreground.length];
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
                        final int neighborX = x + offsetX;
                        final int neighborY = y + offsetY;
                        if (neighborX < 0 || neighborX >= width
                                || neighborY < 0 || neighborY >= height) {
                            continue;
                        }
                        final int neighbor =
                                neighborY * width + neighborX;
                        if (foreground[neighbor] && labels[neighbor] < 0) {
                            labels[neighbor] = label;
                            queue[tail++] = neighbor;
                        }
                    }
                }
            }
            sizes.add(tail);
            largest = Math.max(largest, tail);
        }
        return new Components(labels, List.copyOf(sizes), largest);
    }

    private static double borderFraction(
            final int width,
            final int height,
            final BitSet mask) {
        int foreground = 0;
        final int borderPixels = width == 1 || height == 1
                ? width * height
                : 2 * width + 2 * height - 4;
        for (int x = 0; x < width; x++) {
            if (mask.get(x)) {
                foreground++;
            }
            if (height > 1 && mask.get((height - 1) * width + x)) {
                foreground++;
            }
        }
        for (int y = 1; y < height - 1; y++) {
            if (mask.get(y * width)) {
                foreground++;
            }
            if (width > 1 && mask.get(y * width + width - 1)) {
                foreground++;
            }
        }
        return (double) foreground / borderPixels;
    }

    private record Histogram(
            double minimum,
            double maximum,
            long[] counts) {
    }

    private record IntensityWindow(
            float lower,
            float upper,
            boolean percentileFallback) {
    }

    private record Components(
            int[] labels,
            List<Integer> sizes,
            int largestSize) {
    }

    private record Candidate(
            BinaryMask mask,
            TissueSegmentationCandidateDiagnostic diagnostic) {
    }

    private record MultiOtsuThresholds(
            int lowerThresholdBin,
            int upperThresholdBin) {
    }

    private record ExteriorBackground(
            double areaFraction,
            double borderFraction) {
    }
}
