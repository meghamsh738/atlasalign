package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class TissueMaskEnvelopeTest {

    private final TissueMaskEnvelope envelope = new TissueMaskEnvelope();

    @Test
    void fillsOnlyInteriorHoleAndLeavesInputUnchanged() {
        final BinaryMask input = mask(
                ".....",
                ".###.",
                ".#.#.",
                ".###.",
                ".....");

        final BinaryMask result = envelope.fillInteriorHoles(input);

        assertFalse(input.contains(2, 2));
        assertTrue(result.contains(2, 2));
        assertEquals(8, input.foregroundCount());
        assertEquals(9, result.foregroundCount());
    }

    @Test
    void eightNeighbourExteriorOpeningIsNotFilled() {
        final BinaryMask input = mask(
                ".....",
                ".###.",
                ".#.#.",
                ".##..",
                ".....");

        final BinaryMask result = envelope.fillInteriorHoles(input);

        assertFalse(result.contains(2, 2));
        assertEquals(input, result);
    }

    @Test
    void doesNotBridgeSeparatedPiecesThroughExterior() {
        final BinaryMask input = mask(
                ".....",
                ".#.#.",
                ".#.#.",
                ".#.#.",
                ".....");

        assertEquals(input, envelope.fillInteriorHoles(input));
    }

    @Test
    void emptyMaskRemainsEmpty() {
        final BinaryMask input = BinaryMask.empty(4, 3);

        assertEquals(input, envelope.fillInteriorHoles(input));
    }

    @Test
    void rejectsNull() {
        assertThrows(NullPointerException.class,
                () -> envelope.fillInteriorHoles(null));
    }

    private static BinaryMask mask(final String... rows) {
        final int height = rows.length;
        final int width = rows[0].length();
        final boolean[] values = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            assertEquals(width, rows[y].length());
            for (int x = 0; x < width; x++) {
                values[y * width + x] = rows[y].charAt(x) == '#';
            }
        }
        return BinaryMask.fromBooleans(width, height, values);
    }
}
