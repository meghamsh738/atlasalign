package org.atlasalign.application;

import java.util.BitSet;
import java.util.Objects;
import org.atlasalign.core.BinaryMask;

/**
 * Builds diagnostic tissue envelopes without modifying the supplied mask.
 */
public final class TissueMaskEnvelope {

    /**
     * Returns a new mask with background holes that cannot reach the image
     * border through eight-neighbour background filled as foreground.
     *
     * @param mask immutable source mask
     * @return newly allocated interior-filled envelope
     */
    public BinaryMask fillInteriorHoles(final BinaryMask mask) {
        Objects.requireNonNull(mask, "mask");
        final int width = mask.width();
        final int height = mask.height();
        final int size = Math.multiplyExact(width, height);
        final BitSet exterior = new BitSet(size);
        final int[] queue = new int[size];
        int tail = 0;
        for (int x = 0; x < width; x++) {
            tail = enqueueBackground(mask, x, 0, exterior, queue, tail);
            if (height > 1) {
                tail = enqueueBackground(
                        mask, x, height - 1, exterior, queue, tail);
            }
        }
        for (int y = 1; y < height - 1; y++) {
            tail = enqueueBackground(mask, 0, y, exterior, queue, tail);
            if (width > 1) {
                tail = enqueueBackground(
                        mask, width - 1, y, exterior, queue, tail);
            }
        }
        int head = 0;
        while (head < tail) {
            final int current = queue[head++];
            final int x = current % width;
            final int y = current / width;
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    if (offsetX == 0 && offsetY == 0) {
                        continue;
                    }
                    final int neighborX = x + offsetX;
                    final int neighborY = y + offsetY;
                    if (neighborX < 0 || neighborX >= width
                            || neighborY < 0 || neighborY >= height) {
                        continue;
                    }
                    tail = enqueueBackground(
                            mask,
                            neighborX,
                            neighborY,
                            exterior,
                            queue,
                            tail);
                }
            }
        }
        final BitSet filled = mask.copyBits();
        final BitSet holes = new BitSet(size);
        holes.set(0, size);
        holes.andNot(filled);
        holes.andNot(exterior);
        filled.or(holes);
        return BinaryMask.fromBitSet(width, height, filled);
    }

    private static int enqueueBackground(
            final BinaryMask mask,
            final int x,
            final int y,
            final BitSet exterior,
            final int[] queue,
            final int tail) {
        final int index = y * mask.width() + x;
        if (mask.contains(x, y) || exterior.get(index)) {
            return tail;
        }
        exterior.set(index);
        queue[tail] = index;
        return tail + 1;
    }
}
