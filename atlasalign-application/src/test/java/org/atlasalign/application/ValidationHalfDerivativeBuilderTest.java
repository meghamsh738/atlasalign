package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.BitSet;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.MaskBounds;
import org.atlasalign.core.PreviewMapping;
import org.junit.jupiter.api.Test;

class ValidationHalfDerivativeBuilderTest {

    private final ValidationHalfDerivativeBuilder builder =
            new ValidationHalfDerivativeBuilder();

    @Test
    void buildsTheFrozenOrderedPanelDeterministicallyAndDefensively() {
        final int width = 5;
        final int height = 3;
        final BinaryMask tissue = mask(width, height,
                0, 1, 2, 1, 4, 1);
        final RegistrationPreview source = preview(
                width, height, sequentialPixels(width, height));

        final ValidationHalfDerivativePreparationOutcome.Ready first = ready(
                source, tissue, geometry(SectionGeometry.FULL, tissue));
        final ValidationHalfDerivativePreparationOutcome.Ready second = ready(
                source, tissue, geometry(SectionGeometry.FULL, tissue));

        assertEquals(List.of(ValidationHalfDerivativeCondition.values()),
                first.derivatives().stream()
                        .map(ValidationHalfDerivative::condition)
                        .toList());
        assertEquals(first.derivatives().stream()
                        .map(derivative -> derivative.provenance()
                                .derivativeIdentitySha256())
                        .toList(),
                second.derivatives().stream()
                        .map(derivative -> derivative.provenance()
                                .derivativeIdentitySha256())
                        .toList());
        assertThrows(UnsupportedOperationException.class,
                () -> first.derivatives().add(first.derivatives().get(0)));

        final ValidationHalfDerivativePreparationOutcome.Ready bilateral = ready(
                source,
                tissue,
                geometry(SectionGeometry.BILATERAL_REVIEW_REQUIRED, tissue));
        assertEquals(SectionGeometry.BILATERAL_REVIEW_REQUIRED,
                bilateral.derivatives().get(0).provenance().sourceGeometry());
    }

    @Test
    void splitsEvenWidthAndSharesOddWidthCenterColumn() {
        final ValidationHalfDerivativePreparationOutcome.Ready even = ready(
                preview(4, 1, new float[]{1, 2, 3, 4}),
                mask(4, 1, 0, 0, 3, 0),
                geometry(SectionGeometry.FULL, mask(4, 1, 0, 0, 3, 0)));
        final ValidationHalfDerivative leftEven = even.derivative(
                ValidationHalfDerivativeCondition.IMAGE_LEFT_FULL_CANVAS);
        final ValidationHalfDerivative rightEven = even.derivative(
                ValidationHalfDerivativeCondition.IMAGE_RIGHT_FULL_CANVAS);
        assertTrue(leftEven.observedHalfMask().contains(0, 0));
        assertFalse(leftEven.observedHalfMask().contains(3, 0));
        assertFalse(rightEven.observedHalfMask().contains(0, 0));
        assertTrue(rightEven.observedHalfMask().contains(3, 0));
        assertTrue(leftEven.controlMask().contains(2, 0));
        assertTrue(rightEven.controlMask().contains(1, 0));

        final BinaryMask oddMask = mask(5, 1, 1, 0, 2, 0, 3, 0);
        final ValidationHalfDerivativePreparationOutcome.Ready odd = ready(
                preview(5, 1, new float[]{1, 2, 3, 4, 5}),
                oddMask,
                geometry(SectionGeometry.FULL, oddMask));
        final ValidationHalfDerivative leftOdd = odd.derivative(
                ValidationHalfDerivativeCondition.IMAGE_LEFT_FULL_CANVAS);
        final ValidationHalfDerivative rightOdd = odd.derivative(
                ValidationHalfDerivativeCondition.IMAGE_RIGHT_FULL_CANVAS);
        assertTrue(leftOdd.observedHalfMask().contains(1, 0));
        assertTrue(leftOdd.observedHalfMask().contains(2, 0));
        assertTrue(rightOdd.observedHalfMask().contains(2, 0));
        assertTrue(rightOdd.observedHalfMask().contains(3, 0));
        assertFalse(leftOdd.controlMask().contains(2, 0));
        assertFalse(rightOdd.controlMask().contains(2, 0));
    }

    @Test
    void preservesRawRetainedFloatBitsIncludingNaNPayloadAndSignedZero() {
        final float rawNaN = Float.intBitsToFloat(0x7fc01234);
        final float[] sourcePixels = new float[]{
                rawNaN, -0.0f, 3.0f, 4.0f, 5.0f,
                6.0f, 7.0f, 8.0f, 9.0f, 10.0f};
        final BinaryMask tissue = mask(5, 2, 0, 0, 4, 1);
        final ValidationHalfDerivativePreparationOutcome.Ready panel = ready(
                preview(5, 2, sourcePixels),
                tissue,
                geometry(SectionGeometry.FULL, tissue));
        final ValidationHalfDerivative full = panel.derivative(
                ValidationHalfDerivativeCondition.IMAGE_LEFT_FULL_CANVAS);
        final ValidationHalfDerivative tight = panel.derivative(
                ValidationHalfDerivativeCondition.IMAGE_LEFT_TIGHT_CROP);

        assertRawBits(rawNaN, full.pixels()[0]);
        assertRawBits(-0.0f, full.pixels()[1]);
        assertRawBits(rawNaN, tight.pixels()[0]);
        assertRawBits(-0.0f, tight.pixels()[1]);
    }

    @Test
    void recordsOneCommonBorderMedianAndExactRemovedSideControlMask() {
        final int width = 4;
        final int height = 2;
        final float[] sourcePixels = new float[]{
                -0.0f, 2.0f, 4.0f, 6.0f,
                8.0f, 10.0f, 12.0f, 14.0f};
        final BinaryMask tissue = mask(width, height, 0, 0, 3, 1);
        final ValidationHalfDerivativePreparationOutcome.Ready panel = ready(
                preview(width, height, sourcePixels),
                tissue,
                geometry(SectionGeometry.FULL, tissue));
        final ValidationHalfDerivative left = panel.derivative(
                ValidationHalfDerivativeCondition.IMAGE_LEFT_FULL_CANVAS);
        final ValidationHalfDerivativeProvenance provenance = left.provenance();

        assertEquals(VirtualHalfBackgroundStrategy.BORDER_NON_TISSUE,
                provenance.backgroundStrategy());
        assertEquals(6, provenance.backgroundSampleCount());
        assertRawBits(7.0f, provenance.backgroundValue());
        assertEquals(4, left.controlMask().foregroundCount());
        assertTrue(left.controlMask().contains(2, 0));
        assertTrue(left.controlMask().contains(3, 1));
        assertFalse(left.controlMask().contains(1, 0));
        assertRawBits(7.0f, left.pixels()[2]);
        assertRawBits(7.0f, left.pixels()[7]);
        assertEquals(VirtualHalfPayloadHashes.maskSha256(left.controlMask()),
                provenance.controlMaskSha256());
    }

    @Test
    void usesAllNonTissueSignedZeroAndFailsClosedForOnlyNonFiniteCandidates() {
        final int width = 3;
        final int height = 3;
        final float[] signedZeroSource = sequentialPixels(width, height);
        signedZeroSource[4] = -0.0f;
        final BinaryMask allButCenter = mask(width, height,
                0, 0, 1, 0, 2, 0,
                0, 1, 2, 1,
                0, 2, 1, 2, 2, 2);
        final ValidationHalfDerivativePreparationOutcome.Ready signedZero =
                ready(
                        preview(width, height, signedZeroSource),
                        allButCenter,
                        geometry(SectionGeometry.FULL, allButCenter));
        final ValidationHalfDerivative left = signedZero.derivative(
                ValidationHalfDerivativeCondition.IMAGE_LEFT_FULL_CANVAS);
        assertEquals(VirtualHalfBackgroundStrategy.ALL_NON_TISSUE,
                left.provenance().backgroundStrategy());
        assertRawBits(-0.0f, left.provenance().backgroundValue());
        assertRawBits(-0.0f, left.pixels()[5]);

        final float[] nonFiniteSource = sequentialPixels(width, height);
        nonFiniteSource[4] = Float.intBitsToFloat(0x7fc04567);
        final ValidationHalfDerivativePreparationOutcome.Failed failed =
                assertInstanceOf(
                        ValidationHalfDerivativePreparationOutcome.Failed.class,
                        builder.build(
                                preview(width, height, nonFiniteSource),
                                allButCenter,
                                geometry(SectionGeometry.FULL, allButCenter)));
        assertEquals(ValidationHalfDerivativeFailureReason.NO_FINITE_BACKGROUND,
                failed.reason());
    }

    @Test
    void expandsAndClampsTightCropsWithTranslatedOffsetsAndMasks() {
        final int width = 7;
        final int height = 5;
        final float[] sourcePixels = sequentialPixels(width, height);
        final BinaryMask tissue = mask(width, height, 0, 0, 6, 4);
        final ValidationHalfDerivativePreparationOutcome.Ready panel = ready(
                preview(width, height, sourcePixels),
                tissue,
                geometry(SectionGeometry.FULL, tissue));
        final ValidationHalfDerivative left = panel.derivative(
                ValidationHalfDerivativeCondition.IMAGE_LEFT_TIGHT_CROP);
        final ValidationHalfDerivative right = panel.derivative(
                ValidationHalfDerivativeCondition.IMAGE_RIGHT_TIGHT_CROP);

        assertCrop(left.provenance(), 0, 0, 1, 1, 0, 0, 2, 2, 3.0d);
        assertTrue(left.observedHalfMask().contains(0, 0));
        assertRawBits(sourcePixels[0], left.pixels()[0]);
        assertCrop(right.provenance(), 5, 3, 6, 4, -5, -3, 2, 2, -2.0d);
        assertTrue(right.observedHalfMask().contains(1, 1));
        assertRawBits(sourcePixels[4 * width + 6], right.pixels()[3]);
    }

    @Test
    void translatesObservedAndControlMasksInTightCrops() {
        final int width = 7;
        final int height = 5;
        final BinaryMask tissue = mask(width, height, 3, 2, 4, 2);
        final ValidationHalfDerivativePreparationOutcome.Ready panel = ready(
                preview(width, height, sequentialPixels(width, height)),
                tissue,
                geometry(SectionGeometry.FULL, tissue));
        final ValidationHalfDerivative left = panel.derivative(
                ValidationHalfDerivativeCondition.IMAGE_LEFT_TIGHT_CROP);
        final ValidationHalfDerivative right = panel.derivative(
                ValidationHalfDerivativeCondition.IMAGE_RIGHT_TIGHT_CROP);

        assertCrop(left.provenance(), 2, 1, 4, 3, -2, -1, 3, 3, 1.0d);
        assertTrue(left.observedHalfMask().contains(1, 1));
        assertTrue(left.controlMask().contains(2, 1));
        assertCrop(right.provenance(), 2, 1, 5, 3, -2, -1, 4, 3, 1.0d);
        assertTrue(right.observedHalfMask().contains(1, 1));
        assertTrue(right.observedHalfMask().contains(2, 1));
        assertTrue(right.controlMask().contains(0, 1));
    }

    @Test
    void failsTheWholePanelForInvalidInputsAndHasOverflowSafeCapBoundaries() {
        final RegistrationPreview source = preview(4, 2, sequentialPixels(4, 2));
        final BinaryMask tissue = mask(4, 2, 0, 0, 3, 1);
        final ValidationHalfDerivativePreparationOutcome.Failed invalidGeometry =
                failed(source, tissue, geometry(SectionGeometry.IMAGE_LEFT_HALF, tissue));
        assertEquals(ValidationHalfDerivativeFailureReason.INVALID_SOURCE_GEOMETRY,
                invalidGeometry.reason());

        final ValidationHalfDerivativePreparationOutcome.Failed mismatchedBounds =
                failed(source, tissue, geometry(
                        SectionGeometry.FULL, new MaskBounds(0, 0, 2, 1)));
        assertEquals(ValidationHalfDerivativeFailureReason.INVALID_SOURCE_GEOMETRY,
                mismatchedBounds.reason());

        final BinaryMask mismatched = mask(3, 2, 0, 0);
        final ValidationHalfDerivativePreparationOutcome.Failed mismatch =
                failed(source, mismatched, geometry(SectionGeometry.FULL, mismatched));
        assertEquals(ValidationHalfDerivativeFailureReason.MASK_DIMENSION_MISMATCH,
                mismatch.reason());

        final BinaryMask empty = BinaryMask.empty(4, 2);
        final ValidationHalfDerivativePreparationOutcome.Failed emptySource =
                failed(source, empty, geometry(
                        SectionGeometry.FULL, new MaskBounds(0, 0, 0, 0)));
        assertEquals(ValidationHalfDerivativeFailureReason.EMPTY_FULL_SOURCE_MASK,
                emptySource.reason());

        final BinaryMask onlyLeft = mask(4, 2, 0, 0);
        final ValidationHalfDerivativePreparationOutcome.Failed emptyHalf =
                failed(source, onlyLeft, geometry(SectionGeometry.FULL, onlyLeft));
        assertEquals(ValidationHalfDerivativeFailureReason.EMPTY_OBSERVED_HALF,
                emptyHalf.reason());

        assertFalse(ValidationHalfDerivativeBuilder.exceedsPixelCap(
                DeepSliceInput.MAX_PIXELS, 1));
        assertTrue(ValidationHalfDerivativeBuilder.exceedsPixelCap(
                (long) DeepSliceInput.MAX_PIXELS + 1L, 1));
        assertTrue(ValidationHalfDerivativeBuilder.exceedsPixelCap(
                Long.MAX_VALUE, 2));
    }

    @Test
    void leavesSourcePixelsMaskAndMetadataUnchangedAndHasNoDeepSliceControlPath() {
        final float[] callerPixels = sequentialPixels(5, 3);
        final RegistrationPreview source = preview(5, 3, callerPixels);
        final BinaryMask tissue = mask(5, 3, 0, 1, 2, 1, 4, 1);
        final float[] sourceSnapshot = source.pixels();
        final BitSet maskSnapshot = tissue.copyBits();
        final PreviewMapping mapping = source.mapping();

        final ValidationHalfDerivativePreparationOutcome.Ready panel = ready(
                source, tissue, geometry(SectionGeometry.FULL, tissue));
        final ValidationHalfDerivative derivative = panel.derivative(
                ValidationHalfDerivativeCondition.IMAGE_LEFT_FULL_CANVAS);
        final float[] returned = derivative.pixels();
        returned[0] = -999.0f;

        assertArrayEquals(sourceSnapshot, source.pixels());
        assertEquals(maskSnapshot, tissue.copyBits());
        assertEquals(mapping, source.mapping());
        assertEquals(1, source.channel());
        assertEquals(1, source.sourceSlice());
        assertEquals(1, source.sourceFrame());
        assertRawBits(sourceSnapshot[0], derivative.pixels()[0]);
        assertTrue(derivative.controlMask().contains(3, 1));
        for (final Method method : ValidationHalfDerivative.class.getMethods()) {
            assertFalse(method.getReturnType() == DeepSliceInput.class);
            assertFalse(method.getName().toLowerCase().contains("synthetic"));
        }
    }

    private ValidationHalfDerivativePreparationOutcome.Ready ready(
            final RegistrationPreview source,
            final BinaryMask tissue,
            final TissueGeometryResult geometry) {
        return assertInstanceOf(
                ValidationHalfDerivativePreparationOutcome.Ready.class,
                builder.build(source, tissue, geometry));
    }

    private ValidationHalfDerivativePreparationOutcome.Failed failed(
            final RegistrationPreview source,
            final BinaryMask tissue,
            final TissueGeometryResult geometry) {
        return assertInstanceOf(
                ValidationHalfDerivativePreparationOutcome.Failed.class,
                builder.build(source, tissue, geometry));
    }

    private static RegistrationPreview preview(
            final int width,
            final int height,
            final float[] pixels) {
        return new RegistrationPreview(
                1,
                1,
                1,
                new PreviewMapping(width, height, width, height),
                pixels);
    }

    private static float[] sequentialPixels(final int width, final int height) {
        final float[] pixels = new float[Math.multiplyExact(width, height)];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = index + 1.0f;
        }
        return pixels;
    }

    private static BinaryMask mask(
            final int width,
            final int height,
            final int... coordinates) {
        final BitSet bits = new BitSet(Math.multiplyExact(width, height));
        for (int index = 0; index < coordinates.length; index += 2) {
            bits.set(coordinates[index + 1] * width + coordinates[index]);
        }
        return BinaryMask.fromBitSet(width, height, bits);
    }

    private static TissueGeometryResult geometry(
            final SectionGeometry geometry,
            final BinaryMask mask) {
        return geometry(geometry, mask.bounds());
    }

    private static TissueGeometryResult geometry(
            final SectionGeometry geometry,
            final MaskBounds bounds) {
        return new TissueGeometryResult(
                geometry,
                bounds,
                1.5,
                0.8,
                0.01,
                0.01,
                0.9,
                0.9,
                1,
                1,
                OptionalDouble.empty());
    }

    private static void assertCrop(
            final ValidationHalfDerivativeProvenance provenance,
            final int minimumX,
            final int minimumY,
            final int maximumX,
            final int maximumY,
            final int offsetX,
            final int offsetY,
            final int width,
            final int height,
            final double medialEdgeDerivative) {
        assertEquals(OptionalInt.of(minimumX), provenance.cropMinimumX());
        assertEquals(OptionalInt.of(minimumY), provenance.cropMinimumY());
        assertEquals(OptionalInt.of(maximumX), provenance.cropMaximumX());
        assertEquals(OptionalInt.of(maximumY), provenance.cropMaximumY());
        assertEquals(offsetX, provenance.sourceToDerivativeOffsetX());
        assertEquals(offsetY, provenance.sourceToDerivativeOffsetY());
        assertEquals(width, provenance.derivativeWidth());
        assertEquals(height, provenance.derivativeHeight());
        assertEquals(medialEdgeDerivative, provenance.medialEdgeDerivative());
    }

    private static void assertRawBits(final float expected, final float actual) {
        assertEquals(Float.floatToRawIntBits(expected),
                Float.floatToRawIntBits(actual));
    }
}
