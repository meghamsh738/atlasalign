package org.atlasalign.application.c01;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class C01FeatureGridTest {

    @Test
    void copiesPixelsAndHashesRawFloatBitsDeterministically() {
        final float[] pixels = {0.0f, -0.0f, 1.25f, Float.NaN};
        final C01FeatureGrid grid = new C01FeatureGrid(2, 2, pixels);
        final String initialHash = grid.pixelSha256();

        pixels[0] = 99;
        final float[] returned = grid.intensity();
        returned[1] = 99;

        assertEquals(0.0f, grid.intensity()[0]);
        assertEquals(
                Float.floatToRawIntBits(-0.0f),
                Float.floatToRawIntBits(grid.intensity()[1]));
        assertEquals(initialHash, grid.pixelSha256());
        assertEquals(64, initialHash.length());
        assertNotEquals(
                initialHash,
                new C01FeatureGrid(
                        2, 2, new float[] {0, 0, 1.25f, Float.NaN})
                        .pixelSha256());
    }
}
