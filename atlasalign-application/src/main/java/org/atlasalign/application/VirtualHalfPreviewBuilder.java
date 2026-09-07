package org.atlasalign.application;

import java.util.BitSet;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.core.BinaryMask;

/** Builds an inference-only virtual mirror of an observed half-section. */
public final class VirtualHalfPreviewBuilder {

    /** Frozen identifier for this virtual-half preparation algorithm. */
    public static final String ALGORITHM_REVISION = "phase5-virtual-half-r1";

    private static final long INVALID_DESTINATION = Long.MIN_VALUE;
    private static final double LONG_MAGNITUDE_LIMIT = 0x1.0p63;

    public VirtualHalfPreparationOutcome build(
            final RegistrationPreview preview,
            final BinaryMask observedTissue,
            final TissueGeometryResult geometry) {
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(observedTissue, "observedTissue");
        Objects.requireNonNull(geometry, "geometry");
        final int originalWidth = preview.mapping().previewWidth();
        final int originalHeight = preview.mapping().previewHeight();
        final long originalPixelCount = checkedPixelCount(
                originalWidth, originalHeight);
        final SectionGeometry observedGeometry = geometry.geometry();
        if (observedGeometry == SectionGeometry.FULL
                || observedGeometry
                == SectionGeometry.BILATERAL_REVIEW_REQUIRED) {
            return new VirtualHalfPreparationOutcome.NotApplicable();
        }
        if (observedGeometry == SectionGeometry.PARTIAL_OR_DAMAGED) {
            return manual(
                    VirtualHalfPreparationManualReason.PARTIAL_OR_DAMAGED,
                    "automatic virtual-half preparation is disabled for partial or damaged tissue");
        }
        if (observedGeometry != SectionGeometry.IMAGE_LEFT_HALF
                && observedGeometry != SectionGeometry.IMAGE_RIGHT_HALF) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_GEOMETRY,
                    "observed geometry is not a supported half-section");
        }
        if (observedTissue.width() != originalWidth
                || observedTissue.height() != originalHeight) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "observed mask does not match preview dimensions");
        }
        if (originalPixelCount > DeepSliceInput.MAX_PIXELS) {
            return manual(
                    VirtualHalfPreparationManualReason.PIXEL_CAP_EXCEEDED,
                    "original preview exceeds the shared DeepSlice pixel cap");
        }

        final float[] sourcePixels = preview.pixels();
        if (sourcePixels.length != originalPixelCount
                || !allFinite(sourcePixels)) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "source preview is not a finite raster matching its dimensions");
        }
        if (geometry.medialEdgeX().isEmpty()) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_GEOMETRY,
                    "half-section geometry has no medial-edge coordinate");
        }
        final double medialEdge = geometry.medialEdgeX().getAsDouble();
        if (!Double.isFinite(medialEdge)
                || medialEdge < 0
                || medialEdge > originalWidth - 1.0d) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_GEOMETRY,
                    "medial-edge coordinate is not inside the source raster");
        }

        final ReflectionSummary reflections = summarizeReflections(
                observedTissue,
                originalWidth,
                originalHeight,
                medialEdge,
                observedGeometry);
        if (reflections == null
                || reflections.eligibleSourceCount() == 0) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "retained-side tissue cannot be reflected safely");
        }
        if (!reflections.hasSyntheticMirror()) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "reflection would not produce a synthetic absent-side pixel");
        }

        final VirtualHalfMode mode;
        final int inferenceWidth;
        final int originalToInferenceOffsetX;
        final float[] inferencePixels;
        final BitSet syntheticPixels;
        final VirtualHalfBackgroundStrategy backgroundStrategy;
        final Optional<Float> backgroundValue;
        final int backgroundSampleCount;

        if (reflections.everyDestinationFitsOriginalRaster()) {
            mode = VirtualHalfMode.FULL_CANVAS;
            inferenceWidth = originalWidth;
            originalToInferenceOffsetX = 0;
            inferencePixels = Arrays.copyOf(sourcePixels, sourcePixels.length);
            syntheticPixels = new BitSet((int) originalPixelCount);
            backgroundStrategy = VirtualHalfBackgroundStrategy.NOT_USED;
            backgroundValue = Optional.empty();
            backgroundSampleCount = 0;
        } else {
            final ExpandedExtent extent = expandedExtent(
                    originalWidth, reflections);
            if (extent == null
                    || exceedsPixelCap(extent.width(), originalHeight)) {
                return manual(
                        VirtualHalfPreparationManualReason.PIXEL_CAP_EXCEEDED,
                        "expanded virtual-half raster exceeds the shared pixel cap");
            }
            final BackgroundEstimate background = finiteBackgroundMedian(
                    sourcePixels,
                    observedTissue,
                    originalWidth,
                    originalHeight);
            if (background == null) {
                return manual(
                        VirtualHalfPreparationManualReason.NO_FINITE_BACKGROUND,
                        "no finite non-tissue background pixel is available");
            }

            mode = VirtualHalfMode.TIGHT_CROP;
            inferenceWidth = extent.width();
            originalToInferenceOffsetX = extent.originalOffsetX();
            final int inferencePixelCount = Math.toIntExact(
                    (long) inferenceWidth * originalHeight);
            inferencePixels = new float[inferencePixelCount];
            Arrays.fill(inferencePixels, background.value());
            syntheticPixels = new BitSet(inferencePixelCount);
            syntheticPixels.set(0, inferencePixelCount);
            copyOriginalRaster(
                    sourcePixels,
                    originalWidth,
                    originalHeight,
                    inferencePixels,
                    inferenceWidth,
                    originalToInferenceOffsetX,
                    syntheticPixels);
            backgroundStrategy = background.strategy();
            backgroundValue = Optional.of(background.value());
            backgroundSampleCount = background.sampleCount();
        }

        if (mirrorRetainedTissue(
                sourcePixels,
                observedTissue,
                originalWidth,
                originalHeight,
                medialEdge,
                observedGeometry,
                inferencePixels,
                inferenceWidth,
                originalToInferenceOffsetX,
                syntheticPixels) <= 0) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "reflection did not produce a synthetic tissue pixel");
        }

        final BinaryMask syntheticMask = BinaryMask.fromBitSet(
                inferenceWidth, originalHeight, syntheticPixels);
        try {
            final DeepSliceInput inferenceInput = new DeepSliceInput(
                    inferenceWidth,
                    originalHeight,
                    inferencePixels,
                    syntheticMask);
            final VirtualHalfPreparationProvenance provenance =
                    new VirtualHalfPreparationProvenance(
                            ALGORITHM_REVISION,
                            mode,
                            VirtualHalfPayloadHashes.pixelsSha256(sourcePixels),
                            VirtualHalfPayloadHashes.syntheticMaskSha256(
                                    observedTissue),
                            VirtualHalfPayloadHashes.pixelsSha256(
                                    inferencePixels),
                            VirtualHalfPayloadHashes.syntheticMaskSha256(
                                    syntheticMask),
                            originalWidth,
                            originalHeight,
                            inferenceWidth,
                            originalHeight,
                            originalToInferenceOffsetX,
                            0,
                            medialEdge,
                            medialEdge + originalToInferenceOffsetX,
                            backgroundStrategy,
                            backgroundValue,
                            backgroundSampleCount,
                            observedGeometry);
            return new VirtualHalfPreparationOutcome.Ready(
                    inferenceInput, provenance);
        } catch (final IllegalArgumentException error) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "generated inference payload failed immutable validation");
        }
    }

    private static VirtualHalfPreparationOutcome.ManualRequired manual(
            final VirtualHalfPreparationManualReason reason,
            final String detail) {
        return new VirtualHalfPreparationOutcome.ManualRequired(
                reason, reason + ": " + detail);
    }

    private static long checkedPixelCount(
            final int width, final int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Preview dimensions must be positive");
        }
        return Math.multiplyExact((long) width, (long) height);
    }

    private static boolean allFinite(final float[] pixels) {
        for (final float pixel : pixels) {
            if (!Float.isFinite(pixel)) {
                return false;
            }
        }
        return true;
    }

    private static ReflectionSummary summarizeReflections(
            final BinaryMask observedTissue,
            final int width,
            final int height,
            final double medialEdge,
            final SectionGeometry observedGeometry) {
        long eligibleSourceCount = 0;
        long minimumReflectedX = Long.MAX_VALUE;
        long maximumReflectedX = Long.MIN_VALUE;
        boolean everyDestinationFitsOriginalRaster = true;
        boolean hasSyntheticMirror = false;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!observedTissue.contains(x, y)
                        || !isRetainedSide(
                        x, medialEdge, observedGeometry)) {
                    continue;
                }
                final long reflectedX = reflectedDestination(
                        medialEdge, x);
                if (reflectedX == INVALID_DESTINATION) {
                    return null;
                }
                eligibleSourceCount++;
                minimumReflectedX = Math.min(
                        minimumReflectedX, reflectedX);
                maximumReflectedX = Math.max(
                        maximumReflectedX, reflectedX);
                if (reflectedX < 0 || reflectedX >= width) {
                    everyDestinationFitsOriginalRaster = false;
                }
                if (isAbsentSideCoordinate(
                        reflectedX, medialEdge, observedGeometry)
                        && !isObservedOriginalDestination(
                        observedTissue, width, reflectedX, y)) {
                    hasSyntheticMirror = true;
                }
            }
        }
        return new ReflectionSummary(
                eligibleSourceCount,
                minimumReflectedX,
                maximumReflectedX,
                everyDestinationFitsOriginalRaster,
                hasSyntheticMirror);
    }

    private static long reflectedDestination(
            final double medialEdge, final int sourceX) {
        final double doubledEdge = 2.0d * medialEdge;
        final double reflected = doubledEdge - sourceX;
        final double rounded = reflected + 0.5d;
        if (!Double.isFinite(doubledEdge)
                || !Double.isFinite(reflected)
                || !Double.isFinite(rounded)) {
            return INVALID_DESTINATION;
        }
        final double floored = Math.floor(rounded);
        if (!Double.isFinite(floored)
                || floored < -LONG_MAGNITUDE_LIMIT
                || floored >= LONG_MAGNITUDE_LIMIT) {
            return INVALID_DESTINATION;
        }
        final long destination = (long) floored;
        if (destination < Integer.MIN_VALUE
                || destination > Integer.MAX_VALUE) {
            return INVALID_DESTINATION;
        }
        return destination;
    }

    private static boolean isRetainedSide(
            final int x,
            final double medialEdge,
            final SectionGeometry observedGeometry) {
        return observedGeometry == SectionGeometry.IMAGE_LEFT_HALF
                ? x <= medialEdge : x >= medialEdge;
    }

    private static boolean isAbsentSideCoordinate(
            final long x,
            final double medialEdge,
            final SectionGeometry observedGeometry) {
        return observedGeometry == SectionGeometry.IMAGE_LEFT_HALF
                ? x > medialEdge : x < medialEdge;
    }

    private static boolean isObservedOriginalDestination(
            final BinaryMask observedTissue,
            final int originalWidth,
            final long x,
            final int y) {
        return x >= 0 && x < originalWidth
                && observedTissue.contains((int) x, y);
    }

    private static ExpandedExtent expandedExtent(
            final int originalWidth,
            final ReflectionSummary reflections) {
        final long minimumX = Math.min(
                0L, reflections.minimumReflectedX());
        final long maximumX = Math.max(
                (long) originalWidth - 1L,
                reflections.maximumReflectedX());
        final long width;
        final long offsetX;
        try {
            width = Math.addExact(Math.subtractExact(maximumX, minimumX), 1L);
            offsetX = Math.negateExact(minimumX);
        } catch (final ArithmeticException error) {
            return null;
        }
        if (width <= 0 || width > Integer.MAX_VALUE
                || offsetX < 0 || offsetX > Integer.MAX_VALUE
                || offsetX + originalWidth > width) {
            return null;
        }
        return new ExpandedExtent((int) width, (int) offsetX);
    }

    static boolean exceedsPixelCap(
            final long width, final int height) {
        if (width <= 0 || height <= 0) {
            return true;
        }
        try {
            return Math.multiplyExact(width, (long) height)
                    > DeepSliceInput.MAX_PIXELS;
        } catch (final ArithmeticException error) {
            return true;
        }
    }

    private static VirtualHalfPreparationOutcome prepareHalf(
            final RegistrationPreview preview,
            final BinaryMask observedTissue,
            final TissueGeometryResult geometry,
            final int originalWidth,
            final int originalHeight,
            final long originalPixelCount,
            final SectionGeometry observedGeometry) {
        if (originalPixelCount > DeepSliceInput.MAX_PIXELS) {
            return manual(
                    VirtualHalfPreparationManualReason.PIXEL_CAP_EXCEEDED,
                    "original preview exceeds the shared DeepSlice pixel cap");
        }
        final float[] sourcePixels = preview.pixels();
        if (sourcePixels.length != originalPixelCount) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "preview pixel count does not match its dimensions");
        }
        if (!allFinite(sourcePixels)) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "source preview contains non-finite pixel values");
        }
        if (geometry.medialEdgeX().isEmpty()) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_GEOMETRY,
                    "half-section geometry has no medial-edge coordinate");
        }
        final double medialEdge = geometry.medialEdgeX().getAsDouble();
        if (!Double.isFinite(medialEdge)
                || medialEdge < 0
                || medialEdge > originalWidth - 1.0d) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_GEOMETRY,
                    "medial-edge coordinate is not finite and inside the source raster");
        }

        final ReflectionSummary reflections = summarizeReflections(
                observedTissue,
                originalWidth,
                originalHeight,
                medialEdge,
                observedGeometry);
        if (reflections == null) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "a reflected destination cannot be represented exactly as an integer pixel coordinate");
        }
        if (reflections.eligibleSourceCount() == 0) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "no observed tissue pixels lie on the retained side of the medial edge");
        }
        if (!reflections.hasSyntheticMirror()) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "reflection would not produce any non-observed absent-side synthetic pixels");
        }

        final VirtualHalfMode mode;
        final int inferenceWidth;
        final int originalToInferenceOffsetX;
        final float[] inferencePixels;
        final BitSet syntheticPixels;
        final VirtualHalfBackgroundStrategy backgroundStrategy;
        final Optional<Float> backgroundValue;
        final int backgroundSampleCount;

        if (reflections.everyDestinationFitsOriginalRaster()) {
            mode = VirtualHalfMode.FULL_CANVAS;
            inferenceWidth = originalWidth;
            originalToInferenceOffsetX = 0;
            inferencePixels = Arrays.copyOf(
                    sourcePixels, sourcePixels.length);
            syntheticPixels = new BitSet((int) originalPixelCount);
            backgroundStrategy = VirtualHalfBackgroundStrategy.NOT_USED;
            backgroundValue = Optional.empty();
            backgroundSampleCount = 0;
        } else {
            final ExpandedExtent extent = expandedExtent(
                    originalWidth, reflections);
            if (extent == null) {
                return manual(
                        VirtualHalfPreparationManualReason.PIXEL_CAP_EXCEEDED,
                        "expanded virtual-half raster cannot be represented within the shared DeepSlice pixel cap");
            }
            if (exceedsPixelCap(extent.width(), originalHeight)) {
                return manual(
                        VirtualHalfPreparationManualReason.PIXEL_CAP_EXCEEDED,
                        "expanded virtual-half raster exceeds the shared DeepSlice pixel cap");
            }
            final BackgroundEstimate background = finiteBackgroundMedian(
                    sourcePixels,
                    observedTissue,
                    originalWidth,
                    originalHeight);
            if (background == null) {
                return manual(
                        VirtualHalfPreparationManualReason.NO_FINITE_BACKGROUND,
                        "no finite non-tissue pixel is available for expanded-raster background");
            }

            mode = VirtualHalfMode.TIGHT_CROP;
            inferenceWidth = extent.width();
            originalToInferenceOffsetX = extent.originalOffsetX();
            final long inferencePixelCount = (long) inferenceWidth
                    * originalHeight;
            inferencePixels = new float[(int) inferencePixelCount];
            Arrays.fill(inferencePixels, background.value());
            syntheticPixels = new BitSet((int) inferencePixelCount);
            syntheticPixels.set(0, (int) inferencePixelCount);
            copyOriginalRaster(
                    sourcePixels,
                    originalWidth,
                    originalHeight,
                    inferencePixels,
                    inferenceWidth,
                    originalToInferenceOffsetX,
                    syntheticPixels);
            backgroundStrategy = background.strategy();
            backgroundValue = Optional.of(background.value());
            backgroundSampleCount = background.sampleCount();
        }

        final int syntheticMirrorCount = mirrorRetainedTissue(
                sourcePixels,
                observedTissue,
                originalWidth,
                originalHeight,
                medialEdge,
                observedGeometry,
                inferencePixels,
                inferenceWidth,
                originalToInferenceOffsetX,
                syntheticPixels);
        if (syntheticMirrorCount <= 0) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "reflection did not produce a synthetic tissue pixel");
        }

        final BinaryMask syntheticMask = BinaryMask.fromBitSet(
                inferenceWidth, originalHeight, syntheticPixels);
        try {
            final DeepSliceInput inferenceInput = new DeepSliceInput(
                    inferenceWidth,
                    originalHeight,
                    inferencePixels,
                    syntheticMask);
            final VirtualHalfPreparationProvenance provenance =
                    new VirtualHalfPreparationProvenance(
                            ALGORITHM_REVISION,
                            mode,
                            VirtualHalfPayloadHashes.pixelsSha256(
                                    sourcePixels),
                            VirtualHalfPayloadHashes.syntheticMaskSha256(
                                    observedTissue),
                            VirtualHalfPayloadHashes.pixelsSha256(
                                    inferencePixels),
                            VirtualHalfPayloadHashes.syntheticMaskSha256(
                                    syntheticMask),
                            originalWidth,
                            originalHeight,
                            inferenceWidth,
                            originalHeight,
                            originalToInferenceOffsetX,
                            0,
                            medialEdge,
                            medialEdge + originalToInferenceOffsetX,
                            backgroundStrategy,
                            backgroundValue,
                            backgroundSampleCount,
                            observedGeometry);
            return new VirtualHalfPreparationOutcome.Ready(
                    inferenceInput, provenance);
        } catch (final IllegalArgumentException error) {
            return manual(
                    VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                    "generated inference payload did not satisfy immutable input validation");
        }
    }

    private static BackgroundEstimate finiteBackgroundMedian(
            final float[] pixels,
            final BinaryMask observedTissue,
            final int width,
            final int height) {
        final int borderCount = countFiniteNonTissue(
                pixels, observedTissue, width, height, true);
        final boolean borderOnly = borderCount > 0;
        final int sampleCount = borderOnly ? borderCount
                : countFiniteNonTissue(
                pixels, observedTissue, width, height, false);
        if (sampleCount == 0) {
            return null;
        }
        final float[] samples = new float[sampleCount];
        collectFiniteNonTissue(
                pixels,
                observedTissue,
                width,
                height,
                borderOnly,
                samples);
        Arrays.sort(samples);
        final int middle = sampleCount / 2;
        final float median = sampleCount % 2 == 1
                ? samples[middle]
                : (float) (((double) samples[middle - 1]
                + samples[middle]) / 2.0d);
        if (!Float.isFinite(median)) {
            return null;
        }
        return new BackgroundEstimate(
                borderOnly
                        ? VirtualHalfBackgroundStrategy.BORDER_NON_TISSUE
                        : VirtualHalfBackgroundStrategy.ALL_NON_TISSUE,
                median,
                sampleCount);
    }

    private static int countFiniteNonTissue(
            final float[] pixels,
            final BinaryMask observedTissue,
            final int width,
            final int height,
            final boolean borderOnly) {
        int count = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (observedTissue.contains(x, y)
                        || borderOnly && !isBorder(x, y, width, height)
                        || !Float.isFinite(pixels[y * width + x])) {
                    continue;
                }
                count++;
            }
        }
        return count;
    }

    private static void collectFiniteNonTissue(
            final float[] pixels,
            final BinaryMask observedTissue,
            final int width,
            final int height,
            final boolean borderOnly,
            final float[] samples) {
        int sampleIndex = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final float value = pixels[y * width + x];
                if (observedTissue.contains(x, y)
                        || borderOnly && !isBorder(x, y, width, height)
                        || !Float.isFinite(value)) {
                    continue;
                }
                samples[sampleIndex++] = value;
            }
        }
        if (sampleIndex != samples.length) {
            throw new IllegalStateException(
                    "Background sample collection did not match its count");
        }
    }

    private static boolean isBorder(
            final int x, final int y, final int width, final int height) {
        return x == 0 || y == 0 || x == width - 1 || y == height - 1;
    }

    private static void copyOriginalRaster(
            final float[] sourcePixels,
            final int originalWidth,
            final int originalHeight,
            final float[] inferencePixels,
            final int inferenceWidth,
            final int originalToInferenceOffsetX,
            final BitSet syntheticPixels) {
        for (int y = 0; y < originalHeight; y++) {
            final int sourceOffset = y * originalWidth;
            final int inferenceOffset = y * inferenceWidth
                    + originalToInferenceOffsetX;
            System.arraycopy(
                    sourcePixels,
                    sourceOffset,
                    inferencePixels,
                    inferenceOffset,
                    originalWidth);
            syntheticPixels.clear(
                    inferenceOffset, inferenceOffset + originalWidth);
        }
    }

    private static int mirrorRetainedTissue(
            final float[] sourcePixels,
            final BinaryMask observedTissue,
            final int originalWidth,
            final int originalHeight,
            final double medialEdge,
            final SectionGeometry observedGeometry,
            final float[] inferencePixels,
            final int inferenceWidth,
            final int originalToInferenceOffsetX,
            final BitSet syntheticPixels) {
        int syntheticMirrorCount = 0;
        for (int y = 0; y < originalHeight; y++) {
            for (int x = 0; x < originalWidth; x++) {
                if (!observedTissue.contains(x, y)
                        || !isRetainedSide(
                        x, medialEdge, observedGeometry)) {
                    continue;
                }
                final long reflectedX = reflectedDestination(
                        medialEdge, x);
                if (reflectedX == INVALID_DESTINATION
                        || !isAbsentSideCoordinate(
                        reflectedX, medialEdge, observedGeometry)
                        || isObservedOriginalDestination(
                        observedTissue, originalWidth, reflectedX, y)) {
                    continue;
                }
                final long inferenceX = reflectedX
                        + originalToInferenceOffsetX;
                if (inferenceX < 0 || inferenceX >= inferenceWidth) {
                    return 0;
                }
                final int sourceIndex = y * originalWidth + x;
                final int destinationIndex = y * inferenceWidth
                        + (int) inferenceX;
                inferencePixels[destinationIndex] = sourcePixels[sourceIndex];
                syntheticPixels.set(destinationIndex);
                syntheticMirrorCount++;
            }
        }
        return syntheticMirrorCount;
    }

    private record ReflectionSummary(
            long eligibleSourceCount,
            long minimumReflectedX,
            long maximumReflectedX,
            boolean everyDestinationFitsOriginalRaster,
            boolean hasSyntheticMirror) {
    }

    private record ExpandedExtent(int width, int originalOffsetX) {
    }

    private record BackgroundEstimate(
            VirtualHalfBackgroundStrategy strategy,
            float value,
            int sampleCount) {
    }
}
