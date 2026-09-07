package org.atlasalign.application;

import java.util.Arrays;
import java.util.Objects;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.MaskBounds;

/**
 * Immutable raw validation input derived from a verified complete source.
 *
 * <p>The {@linkplain #controlMask() control mask} marks controlled removed
 * image-side pixels. It is validation-only provenance and is intentionally
 * never converted to a {@link DeepSliceInput} synthetic-pixel mask.</p>
 */
public final class ValidationHalfDerivative {

    private final RegistrationPreview fullSource;
    private final BinaryMask fullSourceTissueMask;
    private final ValidationHalfDerivativeCondition condition;
    private final float[] pixels;
    private final BinaryMask observedHalfMask;
    private final BinaryMask controlMask;
    private final ValidationHalfDerivativeProvenance provenance;

    /**
     * Creates a self-validating immutable derivative. The full source and its
     * mask are retained only for integrity re-verification; neither is
     * mutable through this type.
     */
    public ValidationHalfDerivative(
            final RegistrationPreview fullSource,
            final BinaryMask fullSourceTissueMask,
            final ValidationHalfDerivativeCondition condition,
            final float[] pixels,
            final BinaryMask observedHalfMask,
            final BinaryMask controlMask,
            final ValidationHalfDerivativeProvenance provenance) {
        this.fullSource = Objects.requireNonNull(fullSource, "fullSource");
        this.fullSourceTissueMask = Objects.requireNonNull(
                fullSourceTissueMask, "fullSourceTissueMask");
        this.condition = Objects.requireNonNull(condition, "condition");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.observedHalfMask = Objects.requireNonNull(
                observedHalfMask, "observedHalfMask");
        this.controlMask = Objects.requireNonNull(controlMask, "controlMask");
        this.pixels = Arrays.copyOf(
                Objects.requireNonNull(pixels, "pixels"), pixels.length);
        verifyIntegrity();
    }

    public ValidationHalfDerivativeCondition condition() {
        return condition;
    }

    public int width() {
        return provenance.derivativeWidth();
    }

    public int height() {
        return provenance.derivativeHeight();
    }

    /** Returns a defensive raw-float copy without bit normalization. */
    public float[] pixels() {
        return Arrays.copyOf(pixels, pixels.length);
    }

    /** Deterministic observed half tissue mask in derivative coordinates. */
    public BinaryMask observedHalfMask() {
        return observedHalfMask;
    }

    /** Alias for {@link #observedHalfMask()}. */
    public BinaryMask observedTissueMask() {
        return observedHalfMask();
    }

    /**
     * Controlled removed-side/crop mask used only by validation provenance.
     * This is not a production synthetic-pixel mask.
     */
    public BinaryMask controlMask() {
        return controlMask;
    }

    public ValidationHalfDerivativeProvenance provenance() {
        return provenance;
    }

    void verifyIntegrity() {
        final int sourceWidth = fullSource.mapping().previewWidth();
        final int sourceHeight = fullSource.mapping().previewHeight();
        if (condition != provenance.condition()
                || sourceWidth != provenance.sourceWidth()
                || sourceHeight != provenance.sourceHeight()) {
            throw new IllegalArgumentException(
                    "Derivative payload does not match its provenance geometry");
        }
        if (fullSourceTissueMask.width() != sourceWidth
                || fullSourceTissueMask.height() != sourceHeight
                || observedHalfMask.width() != width()
                || observedHalfMask.height() != height()
                || controlMask.width() != width()
                || controlMask.height() != height()
                || pixels.length != (long) width() * height()) {
            throw new IllegalArgumentException(
                    "Derivative payload dimensions do not match provenance");
        }

        final float[] sourcePixels = fullSource.pixels();
        if (sourcePixels.length != (long) sourceWidth * sourceHeight) {
            throw new IllegalArgumentException(
                    "Full source pixel payload does not match its mapping");
        }
        if (!VirtualHalfPayloadHashes.pixelsSha256(sourcePixels).equals(
                provenance.sourcePixelsSha256())
                || !VirtualHalfPayloadHashes.maskSha256(
                        fullSourceTissueMask).equals(
                        provenance.fullSourceMaskSha256())
                || fullSourceTissueMask.foregroundCount()
                != provenance.fullSourceTissuePixelCount()
                || !VirtualHalfPayloadHashes.pixelsSha256(pixels).equals(
                        provenance.derivativePixelsSha256())
                || !VirtualHalfPayloadHashes.maskSha256(observedHalfMask).equals(
                        provenance.observedHalfMaskSha256())
                || !VirtualHalfPayloadHashes.maskSha256(controlMask).equals(
                        provenance.controlMaskSha256())
                || observedHalfMask.foregroundCount()
                != provenance.observedTissuePixelCount()
                || controlMask.foregroundCount()
                != provenance.controlPixelCount()) {
            throw new IllegalArgumentException(
                    "Derivative payload hashes or counts do not match provenance");
        }
        final ValidationHalfDerivativeBuilder.BackgroundEstimate expectedBackground =
                ValidationHalfDerivativeBuilder.finiteBackgroundMedian(
                        sourcePixels,
                        fullSourceTissueMask,
                        sourceWidth,
                        sourceHeight);
        if (expectedBackground == null
                || expectedBackground.strategy()
                != provenance.backgroundStrategy()
                || Float.floatToRawIntBits(expectedBackground.value())
                != Float.floatToRawIntBits(provenance.backgroundValue())
                || expectedBackground.sampleCount()
                != provenance.backgroundSampleCount()) {
            throw new IllegalArgumentException(
                    "Derivative background does not match the full-source frozen median");
        }

        verifyConditionSemantics(sourcePixels, sourceWidth, sourceHeight);
    }

    private void verifyConditionSemantics(
            final float[] sourcePixels,
            final int sourceWidth,
            final int sourceHeight) {
        final double sourceMidline = (sourceWidth - 1) / 2.0d;
        if (Double.doubleToLongBits(sourceMidline)
                != Double.doubleToLongBits(provenance.medialEdgeSource())) {
            throw new IllegalArgumentException(
                    "Derivative provenance does not use the frozen source midline");
        }
        if (condition.isTightCrop()) {
            verifyMinimalExpandedCrop(sourceWidth, sourceHeight, sourceMidline);
        }

        final int offsetX = provenance.sourceToDerivativeOffsetX();
        final int offsetY = provenance.sourceToDerivativeOffsetY();
        for (int derivativeY = 0; derivativeY < height(); derivativeY++) {
            final int sourceY = derivativeY - offsetY;
            for (int derivativeX = 0; derivativeX < width(); derivativeX++) {
                final int sourceX = derivativeX - offsetX;
                if (sourceX < 0 || sourceX >= sourceWidth
                        || sourceY < 0 || sourceY >= sourceHeight) {
                    throw new IllegalArgumentException(
                            "Derivative crop does not map inside the full source");
                }
                final boolean retained = retains(sourceX, sourceMidline);
                final boolean expectedObserved = retained
                        && fullSourceTissueMask.contains(sourceX, sourceY);
                final boolean expectedControl = !retained;
                if (observedHalfMask.contains(derivativeX, derivativeY)
                        != expectedObserved
                        || controlMask.contains(derivativeX, derivativeY)
                        != expectedControl) {
                    throw new IllegalArgumentException(
                            "Derivative masks do not preserve controlled half semantics");
                }
                final float expectedValue = retained
                        ? sourcePixels[sourceY * sourceWidth + sourceX]
                        : provenance.backgroundValue();
                final float actualValue = pixels[derivativeY * width()
                        + derivativeX];
                if (Float.floatToRawIntBits(actualValue)
                        != Float.floatToRawIntBits(expectedValue)) {
                    throw new IllegalArgumentException(
                            "Derivative pixels do not preserve raw retained bits and background fill");
                }
            }
        }
    }

    private void verifyMinimalExpandedCrop(
            final int sourceWidth,
            final int sourceHeight,
            final double sourceMidline) {
        final MaskBounds observedBounds = observedBoundsInSource(
                sourceWidth, sourceHeight, sourceMidline);
        final int expectedMinimumX = Math.max(0, observedBounds.minimumX() - 1);
        final int expectedMinimumY = Math.max(0, observedBounds.minimumY() - 1);
        final int expectedMaximumX = Math.min(
                sourceWidth - 1, observedBounds.maximumX() + 1);
        final int expectedMaximumY = Math.min(
                sourceHeight - 1, observedBounds.maximumY() + 1);
        if (provenance.cropMinimumX().orElseThrow()
                != expectedMinimumX
                || provenance.cropMinimumY().orElseThrow()
                != expectedMinimumY
                || provenance.cropMaximumX().orElseThrow()
                != expectedMaximumX
                || provenance.cropMaximumY().orElseThrow()
                != expectedMaximumY) {
            throw new IllegalArgumentException(
                    "Derivative crop is not the minimal one-pixel expanded observed bounds");
        }
    }

    private MaskBounds observedBoundsInSource(
            final int sourceWidth,
            final int sourceHeight,
            final double sourceMidline) {
        int minimumX = sourceWidth;
        int minimumY = sourceHeight;
        int maximumX = -1;
        int maximumY = -1;
        for (int y = 0; y < sourceHeight; y++) {
            for (int x = 0; x < sourceWidth; x++) {
                if (!retains(x, sourceMidline)
                        || !fullSourceTissueMask.contains(x, y)) {
                    continue;
                }
                minimumX = Math.min(minimumX, x);
                minimumY = Math.min(minimumY, y);
                maximumX = Math.max(maximumX, x);
                maximumY = Math.max(maximumY, y);
            }
        }
        if (maximumX < 0) {
            throw new IllegalArgumentException(
                    "Derivative has no observed source tissue to crop");
        }
        return new MaskBounds(minimumX, minimumY, maximumX, maximumY);
    }

    private boolean retains(final int sourceX, final double sourceMidline) {
        return condition.retainsImageLeft()
                ? sourceX <= sourceMidline : sourceX >= sourceMidline;
    }
}
