package org.atlasalign.io.imagej;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.LUT;
import ij.process.ColorProcessor;
import java.util.List;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.core.StackPlaneLabel;
import org.junit.jupiter.api.Test;

class ImagePlusSourceImageTest {

    @Test
    void previewLeavesPixelsDimensionsChannelsAndCalibrationUnchanged() {
        final ImagePlus image = twoChannelImage();
        final ImagePlusSourceImage source = new ImagePlusSourceImage(image);
        final short[] channelOneBefore =
                ((short[]) image.getStack().getPixels(1)).clone();
        final short[] channelTwoBefore =
                ((short[]) image.getStack().getPixels(2)).clone();
        final SourceImageSnapshot before = source.snapshot();

        final RegistrationPreview preview = source.createPreview(2, 64);
        final SourceImageSnapshot after = source.snapshot();

        assertEquals(before, after);
        assertArrayEquals(channelOneBefore, (short[]) image.getStack().getPixels(1));
        assertArrayEquals(channelTwoBefore, (short[]) image.getStack().getPixels(2));
        assertNotSame(image.getStack().getPixels(2), preview.pixels());
        assertEquals(List.of("DAPI", "GFP"), after.metadata().channelLabels());
        assertEquals(
                List.of(
                        StackPlaneLabel.fromNullable("DAPI"),
                        StackPlaneLabel.fromNullable("GFP")),
                after.metadata().stackPlaneLabels());
        assertEquals(0.65, after.metadata().calibration().pixelWidth());
        assertEquals("µm", after.metadata().calibration().spatialUnit());
        assertEquals(2, preview.channel());
    }

    @Test
    void previewSamplesTheSelectedChannelAndIsBounded() {
        final ImagePlusSourceImage source = new ImagePlusSourceImage(twoChannelImage());

        final RegistrationPreview preview = source.createPreview(2, 64);

        assertEquals(64, preview.mapping().previewWidth());
        assertEquals(32, preview.mapping().previewHeight());
        assertEquals(1_129f, preview.pixels()[0]);
        assertEquals(1_255f, preview.pixels()[preview.pixels().length - 1]);
    }

    @Test
    void previewLeavesActivePositionDisplayRangeAndLookupTableUnchanged() {
        final CompositeImage image =
                new CompositeImage(twoChannelImage(), CompositeImage.COMPOSITE);
        image.setPosition(2, 1, 1);
        image.setDisplayRange(900, 1_300);
        final int channelBefore = image.getC();
        final int sliceBefore = image.getZ();
        final int frameBefore = image.getT();
        final double minimumBefore = image.getDisplayRangeMin();
        final double maximumBefore = image.getDisplayRangeMax();
        final LUT lutBefore = image.getChannelLut();

        new ImagePlusSourceImage(image).createPreview(1, 64);

        assertEquals(channelBefore, image.getC());
        assertEquals(sliceBefore, image.getZ());
        assertEquals(frameBefore, image.getT());
        assertEquals(minimumBefore, image.getDisplayRangeMin());
        assertEquals(maximumBefore, image.getDisplayRangeMax());
        assertArrayEquals(lutBefore.getBytes(), image.getChannelLut().getBytes());
    }

    @Test
    void snapshotDistinguishesNullBlankAndGeneratedLookingLabelsAcrossPlanes() {
        final ImagePlus image = twoChannelImage();
        image.getStack().setSliceLabel(null, 1);
        image.getStack().setSliceLabel("", 2);
        final ImagePlusSourceImage source = new ImagePlusSourceImage(image);
        final SourceImageSnapshot before = source.snapshot();

        image.getStack().setSliceLabel("Channel 1", 1);
        final SourceImageSnapshot after = source.snapshot();

        assertEquals(StackPlaneLabel.fromNullable(null),
                before.metadata().stackPlaneLabels().get(0));
        assertEquals(StackPlaneLabel.fromNullable(""),
                before.metadata().stackPlaneLabels().get(1));
        org.junit.jupiter.api.Assertions.assertNotEquals(before, after);
    }

    @Test
    void hyperstackPreviewUsesRequestedChannelAtCurrentZAndT() {
        final int width = 8;
        final int height = 8;
        final ImageStack stack = new ImageStack(width, height);
        for (int plane = 1; plane <= 8; plane++) {
            final short[] pixels = new short[width * height];
            java.util.Arrays.fill(pixels, (short) plane);
            stack.addSlice("plane-" + plane, pixels);
        }
        final ImagePlus image = new ImagePlus("C2-Z2-T2", stack);
        image.setDimensions(2, 2, 2);
        image.setOpenAsHyperStack(true);
        image.setPosition(2, 2, 2);

        final RegistrationPreview preview =
                new ImagePlusSourceImage(image).createPreview(1, 64);

        assertEquals(7f, preview.pixels()[0]);
        assertEquals(1, preview.channel());
        assertEquals(2, preview.sourceSlice());
        assertEquals(2, preview.sourceFrame());
        assertEquals(2, image.getC());
        assertEquals(2, image.getZ());
        assertEquals(2, image.getT());
    }

    @Test
    void packedRgbRequiresExplicitChannelSplitting() {
        final ImagePlus rgb =
                new ImagePlus("rgb", new ColorProcessor(8, 8));

        assertThrows(IllegalArgumentException.class,
                () -> new ImagePlusSourceImage(rgb).createPreview(1, 64));
    }

    private static ImagePlus twoChannelImage() {
        final int width = 128;
        final int height = 64;
        final ImageStack stack = new ImageStack(width, height);
        final short[] dapi = new short[width * height];
        final short[] gfp = new short[width * height];
        for (int i = 0; i < dapi.length; i++) {
            dapi[i] = (short) i;
            gfp[i] = (short) (1_000 + (i % 256));
        }
        stack.addSlice("DAPI", dapi);
        stack.addSlice("GFP", gfp);
        final ImagePlus image = new ImagePlus("source", stack);
        image.setDimensions(2, 1, 1);
        image.setOpenAsHyperStack(true);
        final Calibration calibration = new Calibration();
        calibration.pixelWidth = 0.65;
        calibration.pixelHeight = 0.65;
        calibration.setUnit("um");
        image.setCalibration(calibration);
        return image;
    }
}
