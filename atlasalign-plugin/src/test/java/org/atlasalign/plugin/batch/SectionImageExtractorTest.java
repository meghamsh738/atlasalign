package org.atlasalign.plugin.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import java.util.List;
import org.atlasalign.core.Point2D;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.junit.jupiter.api.Test;

class SectionImageExtractorTest {

    @Test
    void extractionPreservesEveryCztPixelAndDoesNotModifySource() {
        final ImagePlus source = hyperstack();
        final var original = new ImagePlusSourceImage(source).snapshot();
        final BatchSection section = new BatchSection(
                "section-001", "Section 1", source.getTitle(),
                original.pixelSha256(), source.getWidth(),
                source.getHeight(), 2, 1, 3, 3,
                List.of(new Point2D(2, 1), new Point2D(5, 1),
                        new Point2D(5, 4), new Point2D(2, 4)),
                264, BatchReviewStatus.PENDING, "");

        final ImagePlus crop = new SectionImageExtractor().extract(
                new BatchReviewItem(source, original, section));

        assertEquals(3, crop.getWidth());
        assertEquals(3, crop.getHeight());
        assertEquals(2, crop.getNChannels());
        assertEquals(2, crop.getNSlices());
        assertEquals(2, crop.getNFrames());
        for (int plane = 1; plane <= source.getStackSize(); plane++) {
            for (int y = 0; y < crop.getHeight(); y++) {
                for (int x = 0; x < crop.getWidth(); x++) {
                    assertEquals(source.getStack().getProcessor(plane)
                                    .get(x + 2, y + 1),
                            crop.getStack().getProcessor(plane).get(x, y));
                }
            }
        }
        assertEquals(-1.5, crop.getCalibration().xOrigin, 1e-12);
        assertEquals(1.5, crop.getCalibration().yOrigin, 1e-12);
        assertEquals(original, new ImagePlusSourceImage(source).snapshot());
        assertTrue(crop.getTitle().contains("Section 1"));
    }

    private static ImagePlus hyperstack() {
        final int width = 6;
        final int height = 5;
        final ImageStack stack = new ImageStack(width, height);
        for (int plane = 1; plane <= 8; plane++) {
            final byte[] pixels = new byte[width * height];
            for (int index = 0; index < pixels.length; index++) {
                pixels[index] = (byte) (plane * 20 + index);
            }
            stack.addSlice("plane " + plane,
                    new ByteProcessor(width, height, pixels));
        }
        final ImagePlus source = new ImagePlus("whole slide", stack);
        source.setDimensions(2, 2, 2);
        source.setOpenAsHyperStack(true);
        final Calibration calibration = source.getCalibration();
        calibration.pixelWidth = 1.3;
        calibration.pixelHeight = 1.4;
        calibration.xOrigin = 0.5;
        calibration.yOrigin = 2.5;
        return source;
    }
}
