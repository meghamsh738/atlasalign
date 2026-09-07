package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalDouble;
import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class DeepSliceInputProvenanceTest {

    @Test
    void capturesExactImmutableInputIdentityAndSyntheticFraction() {
        final DeepSliceInput original = new DeepSliceInput(
                2, 2, new float[]{1, 2, 3, 4},
                BinaryMask.fromBooleans(
                        2, 2, new boolean[]{false, true, false, true}));
        final DeepSliceInputProvenance first =
                DeepSliceInputProvenance.capture(
                        original,
                        DeepSliceInputCondition.VIRTUAL_HALF_FULL_CANVAS,
                        OptionalDouble.of(0.25),
                        Optional.of("a".repeat(64)));
        final DeepSliceInputProvenance repeated =
                DeepSliceInputProvenance.capture(
                        original,
                        DeepSliceInputCondition.VIRTUAL_HALF_FULL_CANVAS,
                        OptionalDouble.of(0.25),
                        Optional.of("a".repeat(64)));

        assertEquals(first, repeated);
        assertEquals(2, first.syntheticPixelCount());
        assertEquals(0.5, first.syntheticPixelFraction());
        assertTrue(first.containsSyntheticPixels());

        final DeepSliceInput changed = new DeepSliceInput(
                2, 2, new float[]{1, 2, 3, 5},
                original.syntheticPixelMask());
        assertNotEquals(first.pixelsSha256(),
                DeepSliceInputProvenance.capture(
                        changed,
                        DeepSliceInputCondition.VIRTUAL_HALF_FULL_CANVAS,
                        OptionalDouble.of(0.25),
                        Optional.of("a".repeat(64))).pixelsSha256());

        final DeepSliceInput noSynthetic = new DeepSliceInput(
                2, 2, original.pixels(), BinaryMask.empty(2, 2));
        assertFalse(DeepSliceInputProvenance.capture(
                noSynthetic,
                DeepSliceInputCondition.ORIGINAL_PREVIEW,
                OptionalDouble.empty(), Optional.empty())
                .containsSyntheticPixels());
    }
}
