package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class VirtualHalfPreparationOutcomeTest {

    @Test
    void exposesAllExplicitPreparationVariants() {
        final VirtualHalfPreparationOutcome notApplicable =
                new VirtualHalfPreparationOutcome.NotApplicable();
        final DeepSliceInput input = input(2, 2);
        final VirtualHalfPreparationOutcome ready =
                new VirtualHalfPreparationOutcome.Ready(
                        input, provenance(input));
        final VirtualHalfPreparationOutcome manual =
                new VirtualHalfPreparationOutcome.ManualRequired(
                        VirtualHalfPreparationManualReason.NO_FINITE_BACKGROUND,
                        "No finite non-tissue background is available");

        assertInstanceOf(VirtualHalfPreparationOutcome.NotApplicable.class,
                notApplicable);
        assertInstanceOf(VirtualHalfPreparationOutcome.Ready.class, ready);
        assertInstanceOf(VirtualHalfPreparationOutcome.ManualRequired.class,
                manual);
        assertEquals(VirtualHalfPreparationManualReason.NO_FINITE_BACKGROUND,
                ((VirtualHalfPreparationOutcome.ManualRequired) manual).reason());
    }

    @Test
    void rejectsInvalidManualReasonOrAuditMessage() {
        assertThrows(NullPointerException.class, () ->
                new VirtualHalfPreparationOutcome.ManualRequired(
                        null, "A visible fallback reason"));
        assertThrows(IllegalArgumentException.class, () ->
                new VirtualHalfPreparationOutcome.ManualRequired(
                        VirtualHalfPreparationManualReason.INVALID_GEOMETRY,
                        " \t\n"));
    }

    @Test
    void readyRequiresInputDimensionsToMatchItsProvenance() {
        final DeepSliceInput input = input(2, 2);

        assertThrows(IllegalArgumentException.class, () ->
                new VirtualHalfPreparationOutcome.Ready(
                        input, provenance(input(3, 2))));
    }

    @Test
    void readyRejectsSameDimensionPixelOrSyntheticMaskMismatches() {
        final DeepSliceInput matching = input(2, 2);
        final DeepSliceInput differentPixels = new DeepSliceInput(
                2,
                2,
                new float[]{1, 0, 0, 0},
                BinaryMask.empty(2, 2));
        final DeepSliceInput differentMask = new DeepSliceInput(
                2,
                2,
                new float[4],
                BinaryMask.fromBooleans(
                        2, 2, new boolean[]{false, true, false, false}));
        final VirtualHalfPreparationProvenance provenance = provenance(
                matching);

        assertThrows(IllegalArgumentException.class, () ->
                new VirtualHalfPreparationOutcome.Ready(
                        differentPixels, provenance));
        assertThrows(IllegalArgumentException.class, () ->
                new VirtualHalfPreparationOutcome.Ready(
                        differentMask, provenance));
    }

    private static DeepSliceInput input(final int width, final int height) {
        return new DeepSliceInput(
                width,
                height,
                new float[width * height],
                BinaryMask.empty(width, height));
    }

    private static VirtualHalfPreparationProvenance provenance(
            final DeepSliceInput input) {
        return new VirtualHalfPreparationProvenance(
                "virtual-half-r1",
                VirtualHalfMode.FULL_CANVAS,
                "0".repeat(64),
                "1".repeat(64),
                VirtualHalfPayloadHashes.pixelsSha256(input.pixels()),
                VirtualHalfPayloadHashes.syntheticMaskSha256(
                        input.syntheticPixelMask()),
                input.width(),
                input.height(),
                input.width(),
                input.height(),
                0,
                0,
                0,
                0,
                VirtualHalfBackgroundStrategy.NOT_USED,
                Optional.empty(),
                0,
                SectionGeometry.IMAGE_LEFT_HALF);
    }
}
