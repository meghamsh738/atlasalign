package org.atlasalign.io.imagej;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import ij.ImagePlus;
import ij.ImageStack;
import java.util.Arrays;
import org.atlasalign.application.export.SourcePixelReader;
import org.junit.jupiter.api.Test;

class ImagePlusSourcePixelReaderTest {

    @Test
    void copiesUnsignedShortHyperstackBlocksByDirectIndexing() {
        final int width = 6;
        final int height = 5;
        final ImageStack stack = new ImageStack(width, height);
        for (int plane = 1; plane <= 8; plane++) {
            final short[] pixels = new short[width * height];
            for (int index = 0; index < pixels.length; index++) {
                pixels[index] = (short) (plane * 1_000 + index);
            }
            stack.addSlice("plane-" + plane, pixels);
        }
        final ImagePlus image = new ImagePlus("C2-Z2-T2", stack);
        image.setDimensions(2, 2, 2);
        image.setOpenAsHyperStack(true);
        final short[] sourceBefore = ((short[]) stack.getPixels(8)).clone();
        final ImagePlusSourcePixelReader reader =
                new ImagePlusSourcePixelReader(image);

        final SourcePixelReader.UnsignedShortBlock block =
                (SourcePixelReader.UnsignedShortBlock) reader.readPlane(
                        2, 2, 2,
                        new SourcePixelReader.Bounds(1, 2, 3, 2));

        assertArrayEquals(new short[]{
                (short) 8_013, (short) 8_014, (short) 8_015,
                (short) 8_019, (short) 8_020, (short) 8_021},
                block.pixels());
        assertArrayEquals(sourceBefore, (short[]) stack.getPixels(8));
        assertNotSame(stack.getPixels(8), block.pixels());
        final short[] callerCopy = block.pixels();
        Arrays.fill(callerCopy, (short) 0);
        assertEquals(8_013, block.pixels()[0] & 0xffff);
    }

    @Test
    void preservesRawFloatBitsInNewBlocks() {
        final int width = 3;
        final int height = 2;
        final float[] pixels = new float[]{
                Float.intBitsToFloat(0x7fc01234), -0.0f, 1.25f,
                Float.NEGATIVE_INFINITY, 9.5f, Float.MIN_VALUE};
        final ImageStack stack = new ImageStack(width, height);
        stack.addSlice("raw-float", pixels);
        final ImagePlus image = new ImagePlus("float", stack);
        final ImagePlusSourcePixelReader reader =
                new ImagePlusSourcePixelReader(image);

        final SourcePixelReader.FloatBlock block =
                (SourcePixelReader.FloatBlock) reader.readPlane(
                        1, 1, 1,
                        new SourcePixelReader.Bounds(0, 0, width, height));

        for (int index = 0; index < pixels.length; index++) {
            assertEquals(Float.floatToRawIntBits(pixels[index]),
                    Float.floatToRawIntBits(block.pixels()[index]));
        }
        assertNotSame(pixels, block.pixels());
    }
}
