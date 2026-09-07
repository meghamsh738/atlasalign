package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.DeepSliceInput;
import org.atlasalign.application.DeepSliceInputCondition;
import org.atlasalign.application.DeepSlicePlanePrediction;
import org.atlasalign.application.DeepSlicePlaneProvider;
import org.atlasalign.application.InitialPlaneSource;
import org.atlasalign.application.AutomaticAlignmentOutcome;
import org.atlasalign.application.ManualFallbackReason;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.SyntheticPixelReviewPolicy;
import org.atlasalign.application.TissueGeometryResult;
import org.atlasalign.application.VirtualHalfBackgroundStrategy;
import org.atlasalign.application.VirtualHalfMode;
import org.atlasalign.application.VirtualHalfPayloadHashes;
import org.atlasalign.application.VirtualHalfPreparationManualReason;
import org.atlasalign.application.VirtualHalfPreparationOutcome;
import org.atlasalign.application.VirtualHalfPreparationProvenance;
import org.atlasalign.application.VirtualHalfPreviewBuilder;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.MaskBounds;
import org.atlasalign.core.PreviewMapping;
import org.junit.jupiter.api.Test;

/** Focused tests for Phase 5 virtual-half inference routing. */
class ReviewAlignmentVirtualHalfRoutingTest {

    @Test
    void notApplicableSendsOriginalPreviewOnceWithoutSyntheticProvenance() {
        final float[] originalPixels = new float[]{
                1, 2, 3,
                4, 5, 6};
        final RegistrationPreview preview = preview(
                3, 2, originalPixels);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<DeepSliceInput> observedInput =
                new AtomicReference<>();
        final DeepSlicePlaneProvider provider = input -> {
            calls.incrementAndGet();
            observedInput.set(input);
            return new DeepSlicePlanePrediction(321, -1.5, 2.5);
        };
        final VirtualHalfPreparationOutcome outcome =
                new VirtualHalfPreparationOutcome.NotApplicable();

        final ReviewAlignmentCommand.InferencePreparation result =
                ReviewAlignmentCommand.routeVirtualHalfPreparation(
                        preview,
                        new AllenCoronalLevel(264),
                        Optional.of(provider),
                        outcome);

        assertEquals(1, calls.get());
        final DeepSliceInput input = observedInput.get();
        assertEquals(3, input.width());
        assertEquals(2, input.height());
        assertArrayEquals(originalPixels, input.pixels());
        assertEquals(BinaryMask.empty(3, 2), input.syntheticPixelMask());
        assertEquals(
                SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                result.syntheticPixelPolicy());
        assertTrue(result.inferencePreparationProvenance().isEmpty());
        assertEquals(DeepSliceInputCondition.ORIGINAL_PREVIEW,
                result.deepSliceInputProvenance().orElseThrow()
                        .condition());
        assertEquals(
                VirtualHalfPayloadHashes.pixelsSha256(originalPixels),
                result.deepSliceInputProvenance().orElseThrow()
                        .pixelsSha256());
        assertSame(outcome, result.virtualHalfPreparationOutcome());
        assertEquals(
                InitialPlaneSource.LOCAL_DEEPSLICE,
                result.planeProposal().source());
    }

    @Test
    void readySendsOnlyVirtualHalfPayloadAndPreservesExactProvenance() {
        final float[] originalPixels = new float[]{
                10, 20, 30,
                40, 50, 60};
        final float[] inferencePixels = new float[]{
                10, 20, 999,
                40, 50, 60};
        final BinaryMask observedMask = mask(3, 2, 0, 0, 1, 0);
        final BinaryMask syntheticMask = mask(3, 2, 2, 0);
        final DeepSliceInput inferenceInput = new DeepSliceInput(
                3, 2, inferencePixels, syntheticMask);
        final VirtualHalfPreparationProvenance provenance =
                new VirtualHalfPreparationProvenance(
                        VirtualHalfPreviewBuilder.ALGORITHM_REVISION,
                        VirtualHalfMode.FULL_CANVAS,
                        VirtualHalfPayloadHashes.pixelsSha256(
                                originalPixels),
                        VirtualHalfPayloadHashes.syntheticMaskSha256(
                                observedMask),
                        VirtualHalfPayloadHashes.pixelsSha256(
                                inferencePixels),
                        VirtualHalfPayloadHashes.syntheticMaskSha256(
                                syntheticMask),
                        3,
                        2,
                        3,
                        2,
                        0,
                        0,
                        1.0,
                        1.0,
                        VirtualHalfBackgroundStrategy.NOT_USED,
                        Optional.empty(),
                        0,
                        SectionGeometry.IMAGE_LEFT_HALF);
        final VirtualHalfPreparationOutcome.Ready outcome =
                new VirtualHalfPreparationOutcome.Ready(
                        inferenceInput, provenance);
        final RegistrationPreview preview = preview(
                3, 2, originalPixels);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<DeepSliceInput> observedInput =
                new AtomicReference<>();
        final DeepSlicePlaneProvider provider = input -> {
            calls.incrementAndGet();
            observedInput.set(input);
            return new DeepSlicePlanePrediction(317, 0.5, -0.75);
        };

        final ReviewAlignmentCommand.InferencePreparation result =
                ReviewAlignmentCommand.routeVirtualHalfPreparation(
                        preview,
                        new AllenCoronalLevel(264),
                        Optional.of(provider),
                        outcome);

        assertEquals(1, calls.get());
        final DeepSliceInput routedInput = observedInput.get();
        assertEquals(inferenceInput.width(), routedInput.width());
        assertEquals(inferenceInput.height(), routedInput.height());
        assertArrayEquals(inferenceInput.pixels(), routedInput.pixels());
        assertEquals(
                inferenceInput.syntheticPixelMask(),
                routedInput.syntheticPixelMask());
        assertFalse(java.util.Arrays.equals(
                originalPixels, routedInput.pixels()));
        assertEquals(
                SyntheticPixelReviewPolicy.SYNTHETIC_PIXELS_EXCLUDED,
                result.syntheticPixelPolicy());
        assertEquals(
                Optional.of(provenance),
                result.inferencePreparationProvenance());
        assertEquals(
                DeepSliceInputCondition.VIRTUAL_HALF_FULL_CANVAS,
                result.deepSliceInputProvenance().orElseThrow()
                        .condition());
        assertEquals(provenance.inferencePixelsSha256(),
                result.deepSliceInputProvenance().orElseThrow()
                        .pixelsSha256());
        assertTrue(result.deepSliceInputProvenance().orElseThrow()
                .containsSyntheticPixels());
        assertSame(outcome, result.virtualHalfPreparationOutcome());
        assertEquals(
                InitialPlaneSource.LOCAL_DEEPSLICE,
                result.planeProposal().source());
    }

    @Test
    void partialOrDamagedPreparationNeverCallsProviderAndUsesTypedFallback() {
        final RegistrationPreview preview = preview(
                3,
                2,
                new float[]{1, 2, 3, 4, 5, 6});
        final BinaryMask observedMask = mask(3, 2, 0, 0);
        final TissueGeometryResult geometry = new TissueGeometryResult(
                SectionGeometry.PARTIAL_OR_DAMAGED,
                new MaskBounds(0, 0, 0, 0),
                1.0,
                1.0,
                0.0,
                0.0,
                0.0,
                1.0,
                1,
                1,
                OptionalDouble.empty());
        final VirtualHalfPreparationOutcome.ManualRequired outcome =
                assertInstanceOf(
                        VirtualHalfPreparationOutcome.ManualRequired.class,
                        new VirtualHalfPreviewBuilder().build(
                                preview, observedMask, geometry));
        assertEquals(
                VirtualHalfPreparationManualReason.PARTIAL_OR_DAMAGED,
                outcome.reason());
        assertTrue(outcome.auditMessage().contains(
                "PARTIAL_OR_DAMAGED"));

        final AtomicInteger calls = new AtomicInteger();
        final ReviewAlignmentCommand.InferencePreparation result =
                ReviewAlignmentCommand.routeVirtualHalfPreparation(
                        preview,
                        new AllenCoronalLevel(264),
                        Optional.of(input -> {
                            calls.incrementAndGet();
                            return new DeepSlicePlanePrediction(1, 0, 0);
                        }),
                        outcome);

        assertEquals(0, calls.get());
        assertEquals(
                InitialPlaneSource.MANUAL_FALLBACK,
                result.planeProposal().source());
        assertEquals(
                264,
                result.planeProposal().coronalLevel()
                        .zeroBasedAnteriorPosteriorIndex());
        assertEquals(
                ManualFallbackReason.VIRTUAL_HALF_PREPARATION_MANUAL_REQUIRED,
                result.planeProposal().fallbackReason().orElseThrow());
        assertEquals(
                "Virtual-half preparation reason=" + outcome.reason()
                        + "; audit=" + outcome.auditMessage(),
                result.planeProposal().fallbackMessage().orElseThrow());
        assertEquals(
                SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                result.syntheticPixelPolicy());
        assertTrue(result.inferencePreparationProvenance().isEmpty());
        assertTrue(result.deepSliceInputProvenance().isEmpty());
    }

    @Test
    void explicitManualOnlyRouteDoesNotCallAProviderOrClaimFallback() {
        final RegistrationPreview preview = preview(
                3, 2, new float[]{1, 2, 3, 4, 5, 6});
        final AtomicInteger calls = new AtomicInteger();

        final ReviewAlignmentCommand.InferencePreparation result =
                ReviewAlignmentCommand.routeVirtualHalfPreparation(
                        preview,
                        new AllenCoronalLevel(264),
                        Optional.of(input -> {
                            calls.incrementAndGet();
                            return new DeepSlicePlanePrediction(1, 0, 0);
                        }),
                        new VirtualHalfPreparationOutcome.NotApplicable(),
                        false);

        assertEquals(0, calls.get());
        assertEquals(InitialPlaneSource.MANUAL_ONLY,
                result.planeProposal().source());
        assertTrue(result.planeProposal().prediction().isEmpty());
        assertTrue(result.planeProposal().fallbackReason().isEmpty());
        assertTrue(result.planeProposal().fallbackMessage().isEmpty());
        assertInstanceOf(AutomaticAlignmentOutcome.NotAttempted.class,
                result.automaticOutcome());
        assertTrue(result.deepSliceInputProvenance().isEmpty());
    }

    @Test
    void unavailableProviderDoesNotClaimAnInputWasSubmitted() {
        final ReviewAlignmentCommand.InferencePreparation result =
                ReviewAlignmentCommand.routeVirtualHalfPreparation(
                        preview(2, 2, new float[]{1, 2, 3, 4}),
                        new AllenCoronalLevel(264),
                        Optional.empty(),
                        new VirtualHalfPreparationOutcome.NotApplicable());

        assertEquals(InitialPlaneSource.MANUAL_FALLBACK,
                result.planeProposal().source());
        assertTrue(result.deepSliceInputProvenance().isEmpty());
    }

    @Test
    void observedMaskHashRemainsSeparateFromSyntheticInferenceMask() {
        final int width = 8;
        final int height = 2;
        final float[] sourcePixels = new float[]{
                1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16};
        final BinaryMask observedMask = mask(
                width, height,
                0, 0,
                2, 0,
                3, 1);
        final RegistrationPreview preview = preview(
                width, height, sourcePixels);
        final TissueGeometryResult geometry = new TissueGeometryResult(
                SectionGeometry.IMAGE_LEFT_HALF,
                observedMask.bounds(),
                1.0,
                0.5,
                0.1,
                0.1,
                0.2,
                0.8,
                1,
                1,
                OptionalDouble.of(3.25));

        final VirtualHalfPreparationOutcome.Ready ready = assertInstanceOf(
                VirtualHalfPreparationOutcome.Ready.class,
                new VirtualHalfPreviewBuilder().build(
                        preview, observedMask, geometry));

        assertEquals(
                VirtualHalfPayloadHashes.syntheticMaskSha256(observedMask),
                ready.provenance().observedMaskSha256());
        assertEquals(
                sourcePixels.length,
                preview.pixels().length);
        assertArrayEquals(sourcePixels, preview.pixels());
        assertFalse(observedMask.equals(
                ready.inferenceInput().syntheticPixelMask()));
        assertTrue(ready.inferenceInput().syntheticPixelMask()
                .foregroundCount() > 0);
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

    private static BinaryMask mask(
            final int width,
            final int height,
            final int... coordinates) {
        final boolean[] values = new boolean[width * height];
        for (int index = 0; index < coordinates.length; index += 2) {
            values[coordinates[index + 1] * width + coordinates[index]] = true;
        }
        return BinaryMask.fromBooleans(width, height, values);
    }
}
