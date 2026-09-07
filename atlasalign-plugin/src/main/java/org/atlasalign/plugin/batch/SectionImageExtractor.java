package org.atlasalign.plugin.batch;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.Blitter;
import ij.process.ImageProcessor;
import java.util.Objects;
import org.atlasalign.io.imagej.ImagePlusSourceImage;

/** Creates an independent C/Z/T-preserving section crop without source writes. */
final class SectionImageExtractor {

    ImagePlus extract(final BatchReviewItem item) {
        final BatchReviewItem checked = Objects.requireNonNull(item, "item");
        final ImagePlus source = checked.source();
        final BatchSection section = checked.section();
        requireUnchanged(checked);
        final ImageStack resultStack = new ImageStack(
                section.width(), section.height());
        for (int plane = 1; plane <= source.getStackSize(); plane++) {
            final ImageProcessor sourceProcessor = source.getStack()
                    .getProcessor(plane);
            final ImageProcessor resultProcessor = sourceProcessor
                    .createProcessor(section.width(), section.height());
            resultProcessor.copyBits(sourceProcessor,
                    -section.minimumX(), -section.minimumY(), Blitter.COPY);
            resultStack.addSlice(source.getStack().getSliceLabel(plane),
                    resultProcessor);
        }
        final ImagePlus result = new ImagePlus(section.name() + " — "
                + source.getTitle(), resultStack);
        result.setDimensions(source.getNChannels(), source.getNSlices(),
                source.getNFrames());
        result.setOpenAsHyperStack(source.isHyperStack()
                || source.getNChannels() > 1 || source.getNSlices() > 1
                || source.getNFrames() > 1);
        final Calibration calibration = (Calibration) source
                .getCalibration().clone();
        calibration.xOrigin -= section.minimumX();
        calibration.yOrigin -= section.minimumY();
        result.setCalibration(calibration);
        result.setPositionWithoutUpdate(
                Math.min(source.getC(), result.getNChannels()),
                Math.min(source.getZ(), result.getNSlices()),
                Math.min(source.getT(), result.getNFrames()));
        result.setDisplayRange(source.getDisplayRangeMin(),
                source.getDisplayRangeMax());
        result.setProperty("AtlasAlign.batch.sectionId", section.id());
        result.setProperty("AtlasAlign.batch.sectionName", section.name());
        result.setProperty("AtlasAlign.batch.parentSource",
                section.sourceName());
        result.setProperty("AtlasAlign.batch.parentSha256",
                section.sourcePixelSha256());
        result.setProperty("AtlasAlign.batch.sourceOffsetX",
                section.minimumX());
        result.setProperty("AtlasAlign.batch.sourceOffsetY",
                section.minimumY());
        requireUnchanged(checked);
        return result;
    }

    private static void requireUnchanged(final BatchReviewItem item) {
        if (!item.verifiedSource().equals(
                new ImagePlusSourceImage(item.source()).snapshot())) {
            throw new IllegalStateException(
                    "The batch source changed after the section queue was created");
        }
    }
}
