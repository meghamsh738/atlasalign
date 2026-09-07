package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class DeepSliceInputContractTest {

    @Test
    void acceptsExactlyTheSharedPixelCap() {
        final int width = 1;
        final int height = DeepSliceInput.MAX_PIXELS;
        final float[] pixels = new float[DeepSliceInput.MAX_PIXELS];

        final DeepSliceInput input = new DeepSliceInput(
                width, height, pixels, BinaryMask.empty(width, height));

        assertEquals(DeepSliceInput.MAX_PIXELS,
                (long) input.width() * input.height());
    }

    @Test
    void rejectsCapPlusOneAndOverflowSafeDimensionsBeforeUsingArrays() {
        assertThrows(IllegalArgumentException.class, () ->
                new DeepSliceInput(
                        1,
                        DeepSliceInput.MAX_PIXELS + 1,
                        new float[0],
                        BinaryMask.empty(1,
                                DeepSliceInput.MAX_PIXELS + 1)));
        assertThrows(IllegalArgumentException.class, () ->
                new DeepSliceInput(
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE,
                        new float[0],
                        BinaryMask.empty(1, 1)));
    }

    @Test
    @SuppressWarnings("deprecation")
    void usesSyntheticPixelMaskNamingAndDefensivelyCopiesPixels() {
        final float[] source = new float[]{1, 2, 3, 4};
        final BinaryMask mask = BinaryMask.fromBooleans(
                2, 2, new boolean[]{false, true, false, true});
        final DeepSliceInput input = new DeepSliceInput(2, 2, source, mask);

        source[0] = -10;
        final float[] exposed = input.pixels();
        exposed[1] = -20;

        assertArrayEquals(new float[]{1, 2, 3, 4}, input.pixels());
        assertSame(mask, input.syntheticPixelMask());
        assertSame(input.syntheticPixelMask(), input.syntheticTissueMask());
    }
}
