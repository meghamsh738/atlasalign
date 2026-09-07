package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.BitSet;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.PreviewMapping;
import org.junit.jupiter.api.Test;

class ValidationHalfDerivativeProvenanceTest {

    @Test
    void usesTheExactFrozenLfSerializationAndGoldenSha256() {
        final ValidationHalfDerivativeProvenance provenance = provenance();
        final String expected = """
                algorithmRevision=phase5-validation-half-derivative-r1
                condition=image-left full-canvas
                sourceGeometry=FULL
                sourcePixelsSha256=0000000000000000000000000000000000000000000000000000000000000000
                fullSourceMaskSha256=1111111111111111111111111111111111111111111111111111111111111111
                derivativePixelsSha256=2222222222222222222222222222222222222222222222222222222222222222
                observedHalfMaskSha256=3333333333333333333333333333333333333333333333333333333333333333
                controlMaskSha256=4444444444444444444444444444444444444444444444444444444444444444
                sourceWidth=4
                sourceHeight=2
                derivativeWidth=4
                derivativeHeight=2
                sourceToDerivativeOffsetX=0
                sourceToDerivativeOffsetY=0
                medialEdgeSourceHex=0x1.8p0
                medialEdgeDerivativeHex=0x1.8p0
                backgroundStrategy=BORDER_NON_TISSUE
                backgroundValueHex=0x1.8p0
                backgroundSampleCount=3
                fullSourceTissuePixelCount=3
                observedTissuePixelCount=2
                controlPixelCount=4
                observedGeometry=IMAGE_LEFT_HALF
                cropMinimumX-or-NONE=NONE
                cropMinimumY-or-NONE=NONE
                cropMaximumX-or-NONE=NONE
                cropMaximumY-or-NONE=NONE
                """;

        assertEquals(expected, provenance.derivativeIdentitySerialization());
        assertEquals("e7faf484d4736db1604262df7ede4b6758ff870bd09e9c3cb3e698b57f2d74e9",
                provenance.derivativeIdentitySha256());
        assertEquals(provenance.derivativeIdentitySha256(), provenance.identitySha256());
        assertEquals(expected, provenance.identitySerialization());
    }

    @Test
    void rejectsAProvidedIdentityThatDoesNotMatchFrozenFields() {
        final ValidationHalfDerivativeProvenance provenance = provenance();

        assertThrows(IllegalArgumentException.class,
                () -> new ValidationHalfDerivativeProvenance(
                        provenance.algorithmRevision(),
                        provenance.condition(),
                        provenance.sourceGeometry(),
                        provenance.sourcePixelsSha256(),
                        provenance.fullSourceMaskSha256(),
                        provenance.derivativePixelsSha256(),
                        provenance.observedHalfMaskSha256(),
                        provenance.controlMaskSha256(),
                        provenance.sourceWidth(),
                        provenance.sourceHeight(),
                        provenance.derivativeWidth(),
                        provenance.derivativeHeight(),
                        provenance.sourceToDerivativeOffsetX(),
                        provenance.sourceToDerivativeOffsetY(),
                        provenance.medialEdgeSource(),
                        provenance.medialEdgeDerivative(),
                        provenance.backgroundStrategy(),
                        provenance.backgroundValue(),
                        provenance.backgroundSampleCount(),
                        provenance.fullSourceTissuePixelCount(),
                        provenance.observedTissuePixelCount(),
                        provenance.controlPixelCount(),
                        provenance.observedGeometry(),
                        provenance.cropMinimumX(),
                        provenance.cropMinimumY(),
                        provenance.cropMaximumX(),
                        provenance.cropMaximumY(),
                        "0".repeat(64)));
    }

    @Test
    void rejectsNoncanonicalCropAndObservedGeometryFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new ValidationHalfDerivativeProvenance(
                        ValidationHalfDerivativeBuilder.ALGORITHM_REVISION,
                        ValidationHalfDerivativeCondition.IMAGE_LEFT_TIGHT_CROP,
                        SectionGeometry.FULL,
                        "0".repeat(64),
                        "1".repeat(64),
                        "2".repeat(64),
                        "3".repeat(64),
                        "4".repeat(64),
                        4,
                        2,
                        2,
                        2,
                        -1,
                        0,
                        1.5d,
                        0.5d,
                        VirtualHalfBackgroundStrategy.ALL_NON_TISSUE,
                        1.0f,
                        1,
                        3,
                        2,
                        1,
                        SectionGeometry.IMAGE_RIGHT_HALF,
                        OptionalInt.of(1),
                        OptionalInt.of(0),
                        OptionalInt.of(2),
                        OptionalInt.of(1)));
    }

    @Test
    void derivativeRecomputesAndRejectsForgedPayloadHashesAndCounts() {
        final BinaryMask tissue = mask(5, 3, 0, 1, 2, 1, 4, 1);
        final RegistrationPreview source = new RegistrationPreview(
                1,
                1,
                1,
                new PreviewMapping(5, 3, 5, 3),
                sequentialPixels(5, 3));
        final TissueGeometryResult geometry = new TissueGeometryResult(
                SectionGeometry.FULL,
                tissue.bounds(),
                1.5,
                0.8,
                0.01,
                0.01,
                0.9,
                0.9,
                1,
                1,
                OptionalDouble.empty());
        final ValidationHalfDerivative original = assertInstanceOf(
                ValidationHalfDerivativePreparationOutcome.Ready.class,
                new ValidationHalfDerivativeBuilder().build(source, tissue, geometry))
                .derivative(
                        ValidationHalfDerivativeCondition.IMAGE_LEFT_FULL_CANVAS);
        final ValidationHalfDerivativeProvenance originalProvenance =
                original.provenance();

        assertThrows(IllegalArgumentException.class,
                () -> new ValidationHalfDerivative(
                        source,
                        tissue,
                        original.condition(),
                        original.pixels(),
                        original.observedHalfMask(),
                        original.controlMask(),
                        copyWith(
                                originalProvenance,
                                "f".repeat(64),
                                originalProvenance.derivativePixelsSha256(),
                                originalProvenance.observedTissuePixelCount())));
        assertThrows(IllegalArgumentException.class,
                () -> new ValidationHalfDerivative(
                        source,
                        tissue,
                        original.condition(),
                        original.pixels(),
                        original.observedHalfMask(),
                        original.controlMask(),
                        copyWith(
                                originalProvenance,
                                originalProvenance.sourcePixelsSha256(),
                                "f".repeat(64),
                                originalProvenance.observedTissuePixelCount())));
        assertThrows(IllegalArgumentException.class,
                () -> new ValidationHalfDerivative(
                        source,
                        tissue,
                        original.condition(),
                        original.pixels(),
                        original.observedHalfMask(),
                        original.controlMask(),
                        copyWith(
                                originalProvenance,
                                originalProvenance.sourcePixelsSha256(),
                                originalProvenance.derivativePixelsSha256(),
                                originalProvenance.observedTissuePixelCount() + 1)));
    }

    private static ValidationHalfDerivativeProvenance provenance() {
        return new ValidationHalfDerivativeProvenance(
                ValidationHalfDerivativeBuilder.ALGORITHM_REVISION,
                ValidationHalfDerivativeCondition.IMAGE_LEFT_FULL_CANVAS,
                SectionGeometry.FULL,
                "0".repeat(64),
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                "4".repeat(64),
                4,
                2,
                4,
                2,
                0,
                0,
                1.5d,
                1.5d,
                VirtualHalfBackgroundStrategy.BORDER_NON_TISSUE,
                1.5f,
                3,
                3,
                2,
                4,
                SectionGeometry.IMAGE_LEFT_HALF,
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty());
    }

    private static ValidationHalfDerivativeProvenance copyWith(
            final ValidationHalfDerivativeProvenance source,
            final String sourcePixelsSha256,
            final String derivativePixelsSha256,
            final int observedTissuePixelCount) {
        return new ValidationHalfDerivativeProvenance(
                source.algorithmRevision(),
                source.condition(),
                source.sourceGeometry(),
                sourcePixelsSha256,
                source.fullSourceMaskSha256(),
                derivativePixelsSha256,
                source.observedHalfMaskSha256(),
                source.controlMaskSha256(),
                source.sourceWidth(),
                source.sourceHeight(),
                source.derivativeWidth(),
                source.derivativeHeight(),
                source.sourceToDerivativeOffsetX(),
                source.sourceToDerivativeOffsetY(),
                source.medialEdgeSource(),
                source.medialEdgeDerivative(),
                source.backgroundStrategy(),
                source.backgroundValue(),
                source.backgroundSampleCount(),
                source.fullSourceTissuePixelCount(),
                observedTissuePixelCount,
                source.controlPixelCount(),
                source.observedGeometry(),
                source.cropMinimumX(),
                source.cropMinimumY(),
                source.cropMaximumX(),
                source.cropMaximumY());
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
}
