package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Optional;
import java.util.OptionalDouble;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.MaskBounds;
import org.atlasalign.core.PreviewMapping;
import org.junit.jupiter.api.Test;

class VirtualHalfPreviewBuilderTest {

    private final VirtualHalfPreviewBuilder builder =
            new VirtualHalfPreviewBuilder();

    @Test
    void buildsLeftHalfFullCanvasWithFractionalMedialEdge() {
        final int width = 8;
        final int height = 2;
        final float[] source = sequentialPixels(width, height);
        final BinaryMask tissue = mask(
                width, height,
                0, 0,
                2, 0,
                3, 1);
        final RegistrationPreview preview = preview(width, height, source);

        final VirtualHalfPreparationOutcome.Ready ready = ready(
                preview,
                tissue,
                geometry(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        tissue,
                        OptionalDouble.of(3.25)));
        final DeepSliceInput input = ready.inferenceInput();
        final VirtualHalfPreparationProvenance provenance =
                ready.provenance();

        assertEquals(VirtualHalfMode.FULL_CANVAS, provenance.mode());
        assertEquals(width, input.width());
        assertEquals(height, input.height());
        assertEquals(width, provenance.originalWidth());
        assertEquals(height, provenance.originalHeight());
        assertEquals(width, provenance.inferenceWidth());
        assertEquals(height, provenance.inferenceHeight());
        assertEquals(0, provenance.originalToInferenceOffsetX());
        assertEquals(0, provenance.originalToInferenceOffsetY());
        assertEquals(3.25, provenance.medialEdgeOriginal());
        assertEquals(3.25, provenance.medialEdgeInference());
        assertEquals(SectionGeometry.IMAGE_LEFT_HALF,
                provenance.observedGeometry());
        assertEquals(
                VirtualHalfBackgroundStrategy.NOT_USED,
                provenance.backgroundStrategy());
        assertTrue(provenance.backgroundValue().isEmpty());
        assertEquals(0, provenance.backgroundSampleCount());

        final float[] inferred = input.pixels();
        assertEquals(source[index(width, 0, 0)], inferred[index(width, 7, 0)]);
        assertEquals(source[index(width, 2, 0)], inferred[index(width, 5, 0)]);
        assertEquals(source[index(width, 3, 1)], inferred[index(width, 4, 1)]);
        assertTrue(input.syntheticPixelMask().contains(7, 0));
        assertTrue(input.syntheticPixelMask().contains(5, 0));
        assertTrue(input.syntheticPixelMask().contains(4, 1));
        assertEquals(3, input.syntheticPixelMask().foregroundCount());

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (tissue.contains(x, y)
                        || !input.syntheticPixelMask().contains(x, y)) {
                    assertEquals(
                            source[index(width, x, y)],
                            inferred[index(width, x, y)]);
                }
            }
        }
        assertArrayEquals(source, preview.pixels());
    }

    @Test
    void buildsRightHalfFullCanvasWithIntegerMedialEdge() {
        final int width = 8;
        final int height = 2;
        final float[] source = sequentialPixels(width, height);
        final BinaryMask tissue = mask(
                width, height,
                7, 0,
                5, 1,
                4, 1);

        final VirtualHalfPreparationOutcome.Ready ready = ready(
                preview(width, height, source),
                tissue,
                geometry(
                        SectionGeometry.IMAGE_RIGHT_HALF,
                        tissue,
                        OptionalDouble.of(4.0)));
        final DeepSliceInput input = ready.inferenceInput();

        assertEquals(VirtualHalfMode.FULL_CANVAS, ready.provenance().mode());
        assertEquals(
                source[index(width, 7, 0)],
                input.pixels()[index(width, 1, 0)]);
        assertEquals(
                source[index(width, 5, 1)],
                input.pixels()[index(width, 3, 1)]);
        assertEquals(
                source[index(width, 4, 1)],
                input.pixels()[index(width, 4, 1)]);
        assertTrue(input.syntheticPixelMask().contains(1, 0));
        assertTrue(input.syntheticPixelMask().contains(3, 1));
        assertFalse(input.syntheticPixelMask().contains(4, 1));
        assertEquals(2, input.syntheticPixelMask().foregroundCount());
    }

    @Test
    void observedPixelsWinFullCanvasReflectionCollisions() {
        final int width = 8;
        final int height = 1;
        final float[] source = sequentialPixels(width, height);
        final BinaryMask tissue = mask(width, height, 1, 0, 2, 0, 5, 0);

        final VirtualHalfPreparationOutcome.Ready ready = ready(
                preview(width, height, source),
                tissue,
                geometry(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        tissue,
                        OptionalDouble.of(3.5)));
        final DeepSliceInput input = ready.inferenceInput();

        // x=2 would reflect to observed x=5; it must not overwrite that pixel.
        assertEquals(source[5], input.pixels()[5]);
        assertFalse(input.syntheticPixelMask().contains(5, 0));
        // x=1 reflects to the otherwise absent x=6.
        assertEquals(source[1], input.pixels()[6]);
        assertTrue(input.syntheticPixelMask().contains(6, 0));
        assertEquals(1, input.syntheticPixelMask().foregroundCount());
    }

    @Test
    void buildsLeftHalfTightCropAndCopiesOriginalAtZeroOffset() {
        final int width = 5;
        final int height = 3;
        final float[] source = new float[]{
                1, 3, 5, 7, 9,
                99, 42, 42, 42, 11,
                13, 15, 17, 19, 21};
        final BinaryMask tissue = mask(width, height, 0, 1);
        final RegistrationPreview preview = preview(width, height, source);

        final VirtualHalfPreparationOutcome.Ready ready = ready(
                preview,
                tissue,
                geometry(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        tissue,
                        OptionalDouble.of(3.0)));
        final DeepSliceInput input = ready.inferenceInput();
        final VirtualHalfPreparationProvenance provenance = ready.provenance();

        assertEquals(VirtualHalfMode.TIGHT_CROP, provenance.mode());
        assertEquals(7, input.width());
        assertEquals(height, input.height());
        assertEquals(width, provenance.originalWidth());
        assertEquals(height, provenance.originalHeight());
        assertEquals(7, provenance.inferenceWidth());
        assertEquals(height, provenance.inferenceHeight());
        assertEquals(0, provenance.originalToInferenceOffsetX());
        assertEquals(0, provenance.originalToInferenceOffsetY());
        assertEquals(3.0, provenance.medialEdgeOriginal());
        assertEquals(3.0, provenance.medialEdgeInference());
        assertEquals(SectionGeometry.IMAGE_LEFT_HALF,
                provenance.observedGeometry());
        assertEquals(
                VirtualHalfBackgroundStrategy.BORDER_NON_TISSUE,
                provenance.backgroundStrategy());
        assertEquals(Optional.of(11.0f), provenance.backgroundValue());
        assertEquals(11, provenance.backgroundSampleCount());

        final float[] inferred = input.pixels();
        assertOriginalRasterCopied(source, inferred, width, height, 7, 0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                assertFalse(input.syntheticPixelMask().contains(x, y));
            }
            assertTrue(input.syntheticPixelMask().contains(5, y));
            assertTrue(input.syntheticPixelMask().contains(6, y));
            assertEquals(11.0f, inferred[index(7, 5, y)]);
        }
        assertEquals(99.0f, inferred[index(7, 6, 1)]);
        assertArrayEquals(source, preview.pixels());
    }

    @Test
    void buildsRightHalfTightCropAndRecordsNegativeSideOffset() {
        final int width = 5;
        final int height = 3;
        final float[] source = new float[]{
                1, 3, 5, 7, 9,
                11, 42, 42, 42, 99,
                13, 15, 17, 19, 21};
        final BinaryMask tissue = mask(width, height, 4, 1);

        final VirtualHalfPreparationOutcome.Ready ready = ready(
                preview(width, height, source),
                tissue,
                geometry(
                        SectionGeometry.IMAGE_RIGHT_HALF,
                        tissue,
                        OptionalDouble.of(1.0)));
        final DeepSliceInput input = ready.inferenceInput();
        final VirtualHalfPreparationProvenance provenance = ready.provenance();

        assertEquals(VirtualHalfMode.TIGHT_CROP, provenance.mode());
        assertEquals(7, input.width());
        assertEquals(width, provenance.originalWidth());
        assertEquals(height, provenance.originalHeight());
        assertEquals(7, provenance.inferenceWidth());
        assertEquals(height, provenance.inferenceHeight());
        assertEquals(2, provenance.originalToInferenceOffsetX());
        assertEquals(0, provenance.originalToInferenceOffsetY());
        assertEquals(1.0, provenance.medialEdgeOriginal());
        assertEquals(3.0, provenance.medialEdgeInference());
        assertEquals(SectionGeometry.IMAGE_RIGHT_HALF,
                provenance.observedGeometry());
        assertEquals(
                VirtualHalfBackgroundStrategy.BORDER_NON_TISSUE,
                provenance.backgroundStrategy());
        assertEquals(Optional.of(11.0f), provenance.backgroundValue());
        assertEquals(11, provenance.backgroundSampleCount());

        final float[] inferred = input.pixels();
        assertOriginalRasterCopied(source, inferred, width, height, 7, 2);
        for (int y = 0; y < height; y++) {
            for (int x = 2; x < 7; x++) {
                assertFalse(input.syntheticPixelMask().contains(x, y));
            }
            assertTrue(input.syntheticPixelMask().contains(0, y));
            assertTrue(input.syntheticPixelMask().contains(1, y));
            assertEquals(11.0f, inferred[index(7, 1, y)]);
        }
        assertEquals(99.0f, inferred[index(7, 0, 1)]);
    }

    @Test
    void usesEvenBorderMedianComputedInDouble() {
        final int width = 5;
        final int height = 3;
        final float[] source = new float[]{
                0, 2, 4, 6, 8,
                100, 42, 42, 42, 101,
                10, 12, 14, 16, 18};
        final BinaryMask tissue = mask(width, height, 0, 1, 4, 1);

        final VirtualHalfPreparationOutcome.Ready ready = ready(
                preview(width, height, source),
                tissue,
                geometry(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        tissue,
                        OptionalDouble.of(3.0)));
        final VirtualHalfPreparationProvenance provenance = ready.provenance();

        assertEquals(
                VirtualHalfBackgroundStrategy.BORDER_NON_TISSUE,
                provenance.backgroundStrategy());
        assertEquals(Optional.of(9.0f), provenance.backgroundValue());
        assertEquals(10, provenance.backgroundSampleCount());
        assertEquals(9.0f, ready.inferenceInput().pixels()[5]);
    }

    @Test
    void fallsBackToAllNonTissueWhenBorderHasNoFiniteSamples() {
        final int width = 5;
        final int height = 5;
        final float[] source = sequentialPixels(width, height);
        final BinaryMask tissue = borderMask(width, height);

        final VirtualHalfPreparationOutcome.Ready ready = ready(
                preview(width, height, source),
                tissue,
                geometry(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        tissue,
                        OptionalDouble.of(3.0)));
        final VirtualHalfPreparationProvenance provenance = ready.provenance();

        assertEquals(
                VirtualHalfBackgroundStrategy.ALL_NON_TISSUE,
                provenance.backgroundStrategy());
        assertEquals(Optional.of(13.0f), provenance.backgroundValue());
        assertEquals(9, provenance.backgroundSampleCount());
        assertEquals(7, ready.inferenceInput().width());
        assertEquals(13.0f, ready.inferenceInput().pixels()[index(7, 5, 1)]);
    }

    @Test
    void reportsManualWhenNoFiniteNonTissueBackgroundExists() {
        final int width = 3;
        final int height = 2;
        final float[] source = sequentialPixels(width, height);
        final BinaryMask tissue = mask(
                width, height,
                0, 0, 1, 0, 2, 0,
                0, 1, 1, 1, 2, 1);

        final VirtualHalfPreparationOutcome.ManualRequired manual =
                manual(
                        preview(width, height, source),
                        tissue,
                        geometry(
                                SectionGeometry.IMAGE_LEFT_HALF,
                                tissue,
                                OptionalDouble.of(2.0)));
        assertEquals(
                VirtualHalfPreparationManualReason.NO_FINITE_BACKGROUND,
                manual.reason());
        assertTrue(manual.auditMessage().contains("NO_FINITE_BACKGROUND"));
    }

    @Test
    void returnsTypedNotApplicableAndManualGeometryOutcomes() {
        final BinaryMask tissue = mask(4, 2, 0, 0);
        final RegistrationPreview preview = preview(
                4, 2, sequentialPixels(4, 2));

        assertInstanceOf(
                VirtualHalfPreparationOutcome.NotApplicable.class,
                builder.build(
                        preview,
                        tissue,
                        geometry(
                                SectionGeometry.FULL,
                                tissue,
                                OptionalDouble.empty())));
        assertInstanceOf(
                VirtualHalfPreparationOutcome.NotApplicable.class,
                builder.build(
                        preview,
                        tissue,
                        geometry(
                                SectionGeometry.BILATERAL_REVIEW_REQUIRED,
                                tissue,
                                OptionalDouble.empty())));

        final VirtualHalfPreparationOutcome.ManualRequired damaged = manual(
                preview,
                tissue,
                geometry(
                        SectionGeometry.PARTIAL_OR_DAMAGED,
                        tissue,
                        OptionalDouble.empty()));
        assertEquals(
                VirtualHalfPreparationManualReason.PARTIAL_OR_DAMAGED,
                damaged.reason());

        final VirtualHalfPreparationOutcome.ManualRequired missingEdge =
                manual(
                        preview,
                        tissue,
                        geometry(
                                SectionGeometry.IMAGE_LEFT_HALF,
                                tissue,
                                OptionalDouble.empty()));
        assertEquals(
                VirtualHalfPreparationManualReason.INVALID_GEOMETRY,
                missingEdge.reason());

        final VirtualHalfPreparationOutcome.ManualRequired outsideEdge =
                manual(
                        preview,
                        tissue,
                        geometry(
                                SectionGeometry.IMAGE_LEFT_HALF,
                                tissue,
                                OptionalDouble.of(4.0)));
        assertEquals(
                VirtualHalfPreparationManualReason.INVALID_GEOMETRY,
                outsideEdge.reason());
    }

    @Test
    void reportsManualWhenNoRetainedSideTissueCanBeReflected() {
        final BinaryMask tissue = mask(5, 2, 4, 0);
        final VirtualHalfPreparationOutcome.ManualRequired manual = manual(
                preview(5, 2, sequentialPixels(5, 2)),
                tissue,
                geometry(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        tissue,
                        OptionalDouble.of(2.0)));

        assertEquals(
                VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                manual.reason());
    }

    @Test
    void bindsSourceMaskAndPayloadHashesIntoImmutableProvenance() {
        final int width = 8;
        final int height = 2;
        final float[] source = sequentialPixels(width, height);
        final BinaryMask tissue = mask(width, height, 0, 0, 2, 0, 3, 1);
        final RegistrationPreview preview = preview(width, height, source);

        final VirtualHalfPreparationOutcome.Ready first = ready(
                preview,
                tissue,
                geometry(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        tissue,
                        OptionalDouble.of(3.25)));
        final VirtualHalfPreparationOutcome.Ready second = ready(
                preview,
                tissue,
                geometry(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        tissue,
                        OptionalDouble.of(3.25)));
        final VirtualHalfPreparationProvenance provenance = first.provenance();

        assertEquals(provenance, second.provenance());
        assertEquals(
                VirtualHalfPreviewBuilder.ALGORITHM_REVISION,
                provenance.algorithmRevision());
        assertEquals(
                VirtualHalfPayloadHashes.pixelsSha256(source),
                provenance.sourcePixelsSha256());
        assertEquals(
                VirtualHalfPayloadHashes.syntheticMaskSha256(tissue),
                provenance.observedMaskSha256());
        assertEquals(
                VirtualHalfPayloadHashes.pixelsSha256(
                        first.inferenceInput().pixels()),
                provenance.inferencePixelsSha256());
        assertEquals(
                VirtualHalfPayloadHashes.syntheticMaskSha256(
                        first.inferenceInput().syntheticPixelMask()),
                provenance.syntheticMaskSha256());
        assertEquals(
                provenance.medialEdgeOriginal()
                        + provenance.originalToInferenceOffsetX(),
                provenance.medialEdgeInference());
        assertEquals(
                provenance.preparationIdentitySha256(),
                second.provenance().preparationIdentitySha256());
        assertTrue(provenance.preparationIdentitySerialization().contains(
                "observedGeometry=IMAGE_LEFT_HALF"));

        final VirtualHalfPreparationProvenance tampered =
                new VirtualHalfPreparationProvenance(
                        provenance.algorithmRevision(),
                        provenance.mode(),
                        provenance.sourcePixelsSha256(),
                        provenance.observedMaskSha256(),
                        "0".repeat(64),
                        provenance.syntheticMaskSha256(),
                        provenance.originalWidth(),
                        provenance.originalHeight(),
                        provenance.inferenceWidth(),
                        provenance.inferenceHeight(),
                        provenance.originalToInferenceOffsetX(),
                        provenance.originalToInferenceOffsetY(),
                        provenance.medialEdgeOriginal(),
                        provenance.medialEdgeInference(),
                        provenance.backgroundStrategy(),
                        provenance.backgroundValue(),
                        provenance.backgroundSampleCount(),
                        provenance.observedGeometry());
        assertThrows(
                IllegalArgumentException.class,
                () -> new VirtualHalfPreparationOutcome.Ready(
                        first.inferenceInput(), tampered));
    }

    @Test
    void keepsInputsAndReturnedPayloadsDefensivelyCopied() {
        final int width = 8;
        final int height = 2;
        final float[] callerPixels = sequentialPixels(width, height);
        final BitSet callerBits = new BitSet(width * height);
        callerBits.set(0);
        callerBits.set(2);
        callerBits.set(width + 3);
        final BinaryMask tissue = BinaryMask.fromBitSet(width, height, callerBits);
        final RegistrationPreview preview = preview(width, height, callerPixels);
        final float[] previewSnapshot = preview.pixels();
        final BitSet tissueSnapshot = tissue.copyBits();

        callerPixels[0] = -123.0f;
        callerBits.clear();
        assertArrayEquals(previewSnapshot, preview.pixels());
        assertTrue(tissue.contains(0, 0));
        assertTrue(tissue.contains(2, 0));

        final VirtualHalfPreparationOutcome.Ready ready = ready(
                preview,
                tissue,
                geometry(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        tissue,
                        OptionalDouble.of(3.25)));
        final DeepSliceInput input = ready.inferenceInput();
        final float[] returnedPixels = input.pixels();
        returnedPixels[0] = -999.0f;
        assertEquals(previewSnapshot[0], input.pixels()[0]);

        final BitSet returnedMaskBits =
                input.syntheticPixelMask().copyBits();
        final boolean hadSyntheticPixel = !returnedMaskBits.isEmpty();
        returnedMaskBits.clear();
        assertEquals(
                hadSyntheticPixel,
                !input.syntheticPixelMask().isEmpty());
        assertArrayEquals(previewSnapshot, preview.pixels());
        assertEquals(tissueSnapshot, tissue.copyBits());
    }

    @Test
    void ordersTypedGeometryOutcomeBeforeDimensionValidation() {
        final RegistrationPreview preview = preview(
                4, 2, sequentialPixels(4, 2));
        final BinaryMask mismatchedMask = mask(3, 2, 0, 0);

        // A non-virtual geometry never needs the mismatched mask validated.
        assertInstanceOf(
                VirtualHalfPreparationOutcome.NotApplicable.class,
                builder.build(
                        preview,
                        mismatchedMask,
                        geometry(
                                SectionGeometry.FULL,
                                mismatchedMask,
                                OptionalDouble.empty())));
        assertInstanceOf(
                VirtualHalfPreparationOutcome.NotApplicable.class,
                builder.build(
                        preview,
                        mismatchedMask,
                        geometry(
                                SectionGeometry.BILATERAL_REVIEW_REQUIRED,
                                mismatchedMask,
                                OptionalDouble.empty())));

        final VirtualHalfPreparationOutcome.ManualRequired left = manual(
                preview,
                mismatchedMask,
                geometry(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        mismatchedMask,
                        OptionalDouble.of(1.5)));
        assertEquals(
                VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                left.reason());

        final VirtualHalfPreparationOutcome.ManualRequired right = manual(
                preview,
                mismatchedMask,
                geometry(
                        SectionGeometry.IMAGE_RIGHT_HALF,
                        mismatchedMask,
                        OptionalDouble.of(1.5)));
        assertEquals(
                VirtualHalfPreparationManualReason.INVALID_INFERENCE_INPUT,
                right.reason());
    }

    @Test
    void preservesObservedCollisionDuringTightCropExpansion() {
        final int width = 5;
        final int height = 1;
        final float[] source = new float[]{7.0f, 10.0f, 20.0f, 30.0f, 99.0f};
        final BinaryMask tissue = mask(width, height, 0, 0, 2, 0, 4, 0);
        final RegistrationPreview preview = preview(width, height, source);

        final VirtualHalfPreparationOutcome.Ready ready = ready(
                preview,
                tissue,
                geometry(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        tissue,
                        OptionalDouble.of(3.0)));
        final DeepSliceInput input = ready.inferenceInput();

        assertEquals(VirtualHalfMode.TIGHT_CROP, ready.provenance().mode());
        assertEquals(7, input.width());
        assertEquals(0, ready.provenance().originalToInferenceOffsetX());
        assertEquals(Optional.of(20.0f), ready.provenance().backgroundValue());

        // x=2 reflects to observed original x=4; the observed value wins.
        assertEquals(99.0f, input.pixels()[4]);
        assertTrue(tissue.contains(4, 0));
        assertFalse(input.syntheticPixelMask().contains(4, 0));
        // x=0 reflects to the expanded, otherwise synthetic x=6.
        assertEquals(7.0f, input.pixels()[6]);
        assertTrue(input.syntheticPixelMask().contains(6, 0));
        assertTrue(input.syntheticPixelMask().contains(5, 0));
        assertEquals(2, input.syntheticPixelMask().foregroundCount());
        assertArrayEquals(source, preview.pixels());
    }

    @Test
    void rejectsOriginalRasterAboveSharedPixelCap() {
        final int width = DeepSliceInput.MAX_PIXELS + 1;
        final int height = 1;
        final BinaryMask tissue = BinaryMask.empty(width, height);
        final RegistrationPreview preview = preview(
                width, height, new float[width]);

        final VirtualHalfPreparationOutcome.ManualRequired manual = manual(
                preview,
                tissue,
                geometry(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        tissue,
                        OptionalDouble.of(1.0)));
        assertEquals(
                VirtualHalfPreparationManualReason.PIXEL_CAP_EXCEEDED,
                manual.reason());
    }

    @Test
    void pixelCapHelperHasExactBoundaryAndOverflowSafeFailure() {
        assertFalse(VirtualHalfPreviewBuilder.exceedsPixelCap(
                DeepSliceInput.MAX_PIXELS, 1));
        assertTrue(VirtualHalfPreviewBuilder.exceedsPixelCap(
                (long) DeepSliceInput.MAX_PIXELS + 1L, 1));
        assertTrue(VirtualHalfPreviewBuilder.exceedsPixelCap(
                Long.MAX_VALUE, 2));
        assertTrue(VirtualHalfPreviewBuilder.exceedsPixelCap(
                Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertTrue(VirtualHalfPreviewBuilder.exceedsPixelCap(0, 1));
    }

    private VirtualHalfPreparationOutcome.Ready ready(
            final RegistrationPreview preview,
            final BinaryMask tissue,
            final TissueGeometryResult geometry) {
        return assertInstanceOf(
                VirtualHalfPreparationOutcome.Ready.class,
                builder.build(preview, tissue, geometry));
    }

    private VirtualHalfPreparationOutcome.ManualRequired manual(
            final RegistrationPreview preview,
            final BinaryMask tissue,
            final TissueGeometryResult geometry) {
        return assertInstanceOf(
                VirtualHalfPreparationOutcome.ManualRequired.class,
                builder.build(preview, tissue, geometry));
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

    private static float[] sequentialPixels(
            final int width,
            final int height) {
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
        if ((coordinates.length & 1) != 0) {
            throw new IllegalArgumentException("Coordinates must be x/y pairs");
        }
        final BitSet bits = new BitSet(Math.multiplyExact(width, height));
        for (int index = 0; index < coordinates.length; index += 2) {
            final int x = coordinates[index];
            final int y = coordinates[index + 1];
            if (x < 0 || x >= width || y < 0 || y >= height) {
                throw new IllegalArgumentException(
                        "Mask coordinate is outside its dimensions");
            }
            bits.set(y * width + x);
        }
        return BinaryMask.fromBitSet(width, height, bits);
    }

    private static BinaryMask borderMask(
            final int width,
            final int height) {
        final BitSet bits = new BitSet(width * height);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    bits.set(y * width + x);
                }
            }
        }
        return BinaryMask.fromBitSet(width, height, bits);
    }

    private static TissueGeometryResult geometry(
            final SectionGeometry section,
            final BinaryMask tissue,
            final OptionalDouble medialEdge) {
        final MaskBounds bounds = tissue.isEmpty()
                ? new MaskBounds(0, 0, 0, 0)
                : tissue.bounds();
        return new TissueGeometryResult(
                section,
                bounds,
                1.5,
                0.8,
                0.01,
                0.01,
                0.9,
                0.9,
                1,
                1,
                medialEdge);
    }

    private static int index(
            final int width,
            final int x,
            final int y) {
        return y * width + x;
    }

    private static void assertOriginalRasterCopied(
            final float[] source,
            final float[] inferred,
            final int originalWidth,
            final int originalHeight,
            final int inferenceWidth,
            final int offsetX) {
        for (int y = 0; y < originalHeight; y++) {
            for (int x = 0; x < originalWidth; x++) {
                assertEquals(
                        source[index(originalWidth, x, y)],
                        inferred[index(
                                inferenceWidth, x + offsetX, y)]);
            }
        }
    }
}
