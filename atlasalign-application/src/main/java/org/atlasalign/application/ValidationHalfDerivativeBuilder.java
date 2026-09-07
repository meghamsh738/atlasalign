package org.atlasalign.application;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.MaskBounds;

/**
 * Builds the frozen four-condition controlled validation derivative panel.
 * This builder is pure: it allocates only derivative payloads and does not
 * classify the derivative or infer anatomical laterality.
 */
public final class ValidationHalfDerivativeBuilder {

    /** Frozen identifier for the controlled validation derivative algorithm. */
    public static final String ALGORITHM_REVISION =
            "phase5-validation-half-derivative-r1";

    /**
     * Builds all four derivatives or returns one typed failure. No partial
     * panel is ever emitted.
     */
    public ValidationHalfDerivativePreparationOutcome build(
            final RegistrationPreview fullSource,
            final BinaryMask fullSourceTissueMask,
            final TissueGeometryResult verifiedFullGeometry) {
        Objects.requireNonNull(fullSource, "fullSource");
        Objects.requireNonNull(fullSourceTissueMask, "fullSourceTissueMask");
        Objects.requireNonNull(verifiedFullGeometry, "verifiedFullGeometry");

        final int sourceWidth = fullSource.mapping().previewWidth();
        final int sourceHeight = fullSource.mapping().previewHeight();
        final SectionGeometry sourceGeometry = verifiedFullGeometry.geometry();
        if (sourceGeometry != SectionGeometry.FULL
                && sourceGeometry != SectionGeometry.BILATERAL_REVIEW_REQUIRED) {
            return failed(
                    ValidationHalfDerivativeFailureReason.INVALID_SOURCE_GEOMETRY,
                    "source geometry is not FULL or BILATERAL_REVIEW_REQUIRED");
        }
        if (fullSourceTissueMask.width() != sourceWidth
                || fullSourceTissueMask.height() != sourceHeight) {
            return failed(
                    ValidationHalfDerivativeFailureReason.MASK_DIMENSION_MISMATCH,
                    "full source tissue mask does not match preview dimensions");
        }
        if (fullSourceTissueMask.isEmpty()) {
            return failed(
                    ValidationHalfDerivativeFailureReason.EMPTY_FULL_SOURCE_MASK,
                    "full source tissue mask is empty");
        }
        if (!verifiedFullGeometry.bounds().equals(fullSourceTissueMask.bounds())) {
            return failed(
                    ValidationHalfDerivativeFailureReason.INVALID_SOURCE_GEOMETRY,
                    "verified full-source geometry bounds do not match the tissue mask");
        }
        if (exceedsPixelCap(sourceWidth, sourceHeight)) {
            return failed(
                    ValidationHalfDerivativeFailureReason.PIXEL_CAP_EXCEEDED,
                    "full source raster exceeds the shared pixel cap");
        }

        final float[] sourcePixels = fullSource.pixels();
        final long sourcePixelCount = (long) sourceWidth * sourceHeight;
        if (sourcePixels.length != sourcePixelCount) {
            return failed(
                    ValidationHalfDerivativeFailureReason.INVALID_DERIVATIVE,
                    "full source pixels do not match preview dimensions");
        }

        final HalfObservation imageLeft = observation(
                fullSourceTissueMask, sourceWidth, sourceHeight, true);
        final HalfObservation imageRight = observation(
                fullSourceTissueMask, sourceWidth, sourceHeight, false);
        if (imageLeft.mask().isEmpty() || imageRight.mask().isEmpty()) {
            return failed(
                    ValidationHalfDerivativeFailureReason.EMPTY_OBSERVED_HALF,
                    "at least one deterministic image-side observed mask is empty");
        }

        final BackgroundEstimate background = finiteBackgroundMedian(
                sourcePixels, fullSourceTissueMask, sourceWidth, sourceHeight);
        if (background == null) {
            return failed(
                    ValidationHalfDerivativeFailureReason.NO_FINITE_BACKGROUND,
                    "no finite full-source non-tissue background value is available");
        }

        final CropBounds imageLeftCrop = cropBounds(
                imageLeft.bounds(), sourceWidth, sourceHeight);
        final CropBounds imageRightCrop = cropBounds(
                imageRight.bounds(), sourceWidth, sourceHeight);
        if (exceedsPixelCap(imageLeftCrop.width(), imageLeftCrop.height())
                || exceedsPixelCap(
                        imageRightCrop.width(), imageRightCrop.height())) {
            return failed(
                    ValidationHalfDerivativeFailureReason.PIXEL_CAP_EXCEEDED,
                    "a tight validation derivative exceeds the shared pixel cap");
        }

        try {
            final BuiltPayload leftFull = fullCanvas(
                    sourcePixels,
                    sourceWidth,
                    sourceHeight,
                    imageLeft.mask(),
                    true,
                    background.value());
            final BuiltPayload rightFull = fullCanvas(
                    sourcePixels,
                    sourceWidth,
                    sourceHeight,
                    imageRight.mask(),
                    false,
                    background.value());
            final BuiltPayload leftTight = tightCrop(
                    leftFull, sourceWidth, imageLeftCrop);
            final BuiltPayload rightTight = tightCrop(
                    rightFull, sourceWidth, imageRightCrop);

            final String sourcePixelsSha256 =
                    VirtualHalfPayloadHashes.pixelsSha256(sourcePixels);
            final String fullSourceMaskSha256 =
                    VirtualHalfPayloadHashes.maskSha256(fullSourceTissueMask);
            final int fullSourceTissuePixelCount =
                    fullSourceTissueMask.foregroundCount();
            final double sourceMidline = (sourceWidth - 1) / 2.0d;
            final List<ValidationHalfDerivative> derivatives = new ArrayList<>(4);
            derivatives.add(derivative(
                    fullSource,
                    fullSourceTissueMask,
                    ValidationHalfDerivativeCondition.IMAGE_LEFT_FULL_CANVAS,
                    sourceGeometry,
                    sourcePixelsSha256,
                    fullSourceMaskSha256,
                    fullSourceTissuePixelCount,
                    sourceMidline,
                    background,
                    leftFull,
                    CropBounds.none()));
            derivatives.add(derivative(
                    fullSource,
                    fullSourceTissueMask,
                    ValidationHalfDerivativeCondition.IMAGE_RIGHT_FULL_CANVAS,
                    sourceGeometry,
                    sourcePixelsSha256,
                    fullSourceMaskSha256,
                    fullSourceTissuePixelCount,
                    sourceMidline,
                    background,
                    rightFull,
                    CropBounds.none()));
            derivatives.add(derivative(
                    fullSource,
                    fullSourceTissueMask,
                    ValidationHalfDerivativeCondition.IMAGE_LEFT_TIGHT_CROP,
                    sourceGeometry,
                    sourcePixelsSha256,
                    fullSourceMaskSha256,
                    fullSourceTissuePixelCount,
                    sourceMidline,
                    background,
                    leftTight,
                    imageLeftCrop));
            derivatives.add(derivative(
                    fullSource,
                    fullSourceTissueMask,
                    ValidationHalfDerivativeCondition.IMAGE_RIGHT_TIGHT_CROP,
                    sourceGeometry,
                    sourcePixelsSha256,
                    fullSourceMaskSha256,
                    fullSourceTissuePixelCount,
                    sourceMidline,
                    background,
                    rightTight,
                    imageRightCrop));
            return new ValidationHalfDerivativePreparationOutcome.Ready(derivatives);
        } catch (final IllegalArgumentException error) {
            return failed(
                    ValidationHalfDerivativeFailureReason.INVALID_DERIVATIVE,
                    "generated derivative did not satisfy immutable validation: "
                    + error.getMessage());
        }
    }

    static boolean exceedsPixelCap(final long width, final long height) {
        if (width <= 0 || height <= 0) {
            return true;
        }
        return width > DeepSliceInput.MAX_PIXELS / height;
    }

    private static ValidationHalfDerivativePreparationOutcome.Failed failed(
            final ValidationHalfDerivativeFailureReason reason,
            final String detail) {
        return new ValidationHalfDerivativePreparationOutcome.Failed(
                reason, reason + ": " + detail);
    }

    private static HalfObservation observation(
            final BinaryMask fullSourceTissueMask,
            final int width,
            final int height,
            final boolean imageLeft) {
        final double midline = (width - 1) / 2.0d;
        final BitSet observed = new BitSet((int) ((long) width * height));
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (fullSourceTissueMask.contains(x, y)
                        && retains(x, midline, imageLeft)) {
                    observed.set(y * width + x);
                }
            }
        }
        final BinaryMask mask = BinaryMask.fromBitSet(width, height, observed);
        return new HalfObservation(mask, mask.isEmpty() ? null : mask.bounds());
    }

    private static CropBounds cropBounds(
            final MaskBounds observedBounds,
            final int sourceWidth,
            final int sourceHeight) {
        final int minimumX = Math.max(0, observedBounds.minimumX() - 1);
        final int minimumY = Math.max(0, observedBounds.minimumY() - 1);
        final int maximumX = Math.min(
                sourceWidth - 1, observedBounds.maximumX() + 1);
        final int maximumY = Math.min(
                sourceHeight - 1, observedBounds.maximumY() + 1);
        return new CropBounds(minimumX, minimumY, maximumX, maximumY);
    }

    private static BuiltPayload fullCanvas(
            final float[] sourcePixels,
            final int sourceWidth,
            final int sourceHeight,
            final BinaryMask observedMask,
            final boolean imageLeft,
            final float background) {
        final int pixelCount = Math.toIntExact((long) sourceWidth * sourceHeight);
        final float[] derivativePixels = new float[pixelCount];
        final BitSet control = new BitSet(pixelCount);
        final double midline = (sourceWidth - 1) / 2.0d;
        for (int y = 0; y < sourceHeight; y++) {
            final int rowOffset = y * sourceWidth;
            for (int x = 0; x < sourceWidth; x++) {
                final int index = rowOffset + x;
                if (retains(x, midline, imageLeft)) {
                    derivativePixels[index] = sourcePixels[index];
                } else {
                    derivativePixels[index] = background;
                    control.set(index);
                }
            }
        }
        return new BuiltPayload(
                sourceWidth,
                sourceHeight,
                derivativePixels,
                observedMask,
                BinaryMask.fromBitSet(sourceWidth, sourceHeight, control),
                0,
                0);
    }

    private static BuiltPayload tightCrop(
            final BuiltPayload fullCanvas,
            final int sourceWidth,
            final CropBounds crop) {
        final int width = crop.width();
        final int height = crop.height();
        final int pixelCount = Math.toIntExact((long) width * height);
        final float[] pixels = new float[pixelCount];
        final BitSet observed = new BitSet(pixelCount);
        final BitSet control = new BitSet(pixelCount);
        final int minimumX = crop.minimumX().orElseThrow();
        final int minimumY = crop.minimumY().orElseThrow();
        for (int y = 0; y < height; y++) {
            final int sourceY = minimumY + y;
            final int sourceOffset = sourceY * sourceWidth + minimumX;
            final int derivativeOffset = y * width;
            System.arraycopy(
                    fullCanvas.pixels(),
                    sourceOffset,
                    pixels,
                    derivativeOffset,
                    width);
            for (int x = 0; x < width; x++) {
                final int sourceX = minimumX + x;
                if (fullCanvas.observedMask().contains(sourceX, sourceY)) {
                    observed.set(derivativeOffset + x);
                }
                if (fullCanvas.controlMask().contains(sourceX, sourceY)) {
                    control.set(derivativeOffset + x);
                }
            }
        }
        return new BuiltPayload(
                width,
                height,
                pixels,
                BinaryMask.fromBitSet(width, height, observed),
                BinaryMask.fromBitSet(width, height, control),
                -minimumX,
                -minimumY);
    }

    private static ValidationHalfDerivative derivative(
            final RegistrationPreview fullSource,
            final BinaryMask fullSourceTissueMask,
            final ValidationHalfDerivativeCondition condition,
            final SectionGeometry sourceGeometry,
            final String sourcePixelsSha256,
            final String fullSourceMaskSha256,
            final int fullSourceTissuePixelCount,
            final double sourceMidline,
            final BackgroundEstimate background,
            final BuiltPayload payload,
            final CropBounds crop) {
        final ValidationHalfDerivativeProvenance provenance =
                new ValidationHalfDerivativeProvenance(
                        ALGORITHM_REVISION,
                        condition,
                        sourceGeometry,
                        sourcePixelsSha256,
                        fullSourceMaskSha256,
                        VirtualHalfPayloadHashes.pixelsSha256(payload.pixels()),
                        VirtualHalfPayloadHashes.maskSha256(
                                payload.observedMask()),
                        VirtualHalfPayloadHashes.maskSha256(
                                payload.controlMask()),
                        fullSource.mapping().previewWidth(),
                        fullSource.mapping().previewHeight(),
                        payload.width(),
                        payload.height(),
                        payload.offsetX(),
                        payload.offsetY(),
                        sourceMidline,
                        sourceMidline + payload.offsetX(),
                        background.strategy(),
                        background.value(),
                        background.sampleCount(),
                        fullSourceTissuePixelCount,
                        payload.observedMask().foregroundCount(),
                        payload.controlMask().foregroundCount(),
                        condition.observedGeometry(),
                        crop.minimumX(),
                        crop.minimumY(),
                        crop.maximumX(),
                        crop.maximumY());
        return new ValidationHalfDerivative(
                fullSource,
                fullSourceTissueMask,
                condition,
                payload.pixels(),
                payload.observedMask(),
                payload.controlMask(),
                provenance);
    }

    static BackgroundEstimate finiteBackgroundMedian(
            final float[] pixels,
            final BinaryMask fullSourceTissueMask,
            final int width,
            final int height) {
        final int borderSamples = countFiniteNonTissue(
                pixels, fullSourceTissueMask, width, height, true);
        final boolean borderOnly = borderSamples > 0;
        final int sampleCount = borderOnly ? borderSamples
                : countFiniteNonTissue(
                        pixels, fullSourceTissueMask, width, height, false);
        if (sampleCount == 0) {
            return null;
        }
        final float[] samples = new float[sampleCount];
        collectFiniteNonTissue(
                pixels,
                fullSourceTissueMask,
                width,
                height,
                borderOnly,
                samples);
        Arrays.sort(samples);
        final int middle = sampleCount / 2;
        final float value = sampleCount % 2 == 1
                ? samples[middle]
                : (float) (((double) samples[middle - 1]
                + samples[middle]) / 2.0d);
        if (!Float.isFinite(value)) {
            return null;
        }
        return new BackgroundEstimate(
                borderOnly
                        ? VirtualHalfBackgroundStrategy.BORDER_NON_TISSUE
                        : VirtualHalfBackgroundStrategy.ALL_NON_TISSUE,
                value,
                sampleCount);
    }

    private static int countFiniteNonTissue(
            final float[] pixels,
            final BinaryMask tissue,
            final int width,
            final int height,
            final boolean borderOnly) {
        int count = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (tissue.contains(x, y)
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
            final BinaryMask tissue,
            final int width,
            final int height,
            final boolean borderOnly,
            final float[] samples) {
        int sampleIndex = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final float value = pixels[y * width + x];
                if (tissue.contains(x, y)
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

    private static boolean retains(
            final int x, final double midline, final boolean imageLeft) {
        return imageLeft ? x <= midline : x >= midline;
    }

    private record HalfObservation(BinaryMask mask, MaskBounds bounds) {
    }

    private record BuiltPayload(
            int width,
            int height,
            float[] pixels,
            BinaryMask observedMask,
            BinaryMask controlMask,
            int offsetX,
            int offsetY) {
    }

    record BackgroundEstimate(
            VirtualHalfBackgroundStrategy strategy,
            float value,
            int sampleCount) {
    }

    private record CropBounds(
            OptionalInt minimumX,
            OptionalInt minimumY,
            OptionalInt maximumX,
            OptionalInt maximumY) {

        private CropBounds(
                final int minimumX,
                final int minimumY,
                final int maximumX,
                final int maximumY) {
            this(
                    OptionalInt.of(minimumX),
                    OptionalInt.of(minimumY),
                    OptionalInt.of(maximumX),
                    OptionalInt.of(maximumY));
        }

        static CropBounds none() {
            return new CropBounds(
                    OptionalInt.empty(),
                    OptionalInt.empty(),
                    OptionalInt.empty(),
                    OptionalInt.empty());
        }

        int width() {
            return maximumX.orElseThrow() - minimumX.orElseThrow() + 1;
        }

        int height() {
            return maximumY.orElseThrow() - minimumY.orElseThrow() + 1;
        }
    }
}
