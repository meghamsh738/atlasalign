package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertThrows;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import java.util.Optional;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.DeepSliceInput;
import org.atlasalign.application.DeepSlicePlanePrediction;
import org.atlasalign.application.SourceVerificationException;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.junit.jupiter.api.Test;

class ReviewAlignmentSourceIntegrityTest {

    @Test
    void rejectsSourceMutatedByProviderDuringEstimate() {
        final ByteProcessor pixels = new ByteProcessor(2, 2);
        pixels.set(0, 0, 1);
        pixels.set(1, 0, 2);
        pixels.set(0, 1, 3);
        pixels.set(1, 1, 4);
        final ImagePlus source = new ImagePlus("source", pixels);
        final SourceImageSnapshot before =
                new ImagePlusSourceImage(source).snapshot();
        final DeepSliceInput input = new DeepSliceInput(
                2,
                2,
                new float[] {1, 2, 3, 4},
                BinaryMask.empty(2, 2));

        ReviewAlignmentCommand.estimateInitialPlane(
                new AllenCoronalLevel(264),
                input,
                Optional.of(ignored -> {
                    source.getProcessor().set(0, 0, 99);
                    return new DeepSlicePlanePrediction(300, 0, 0);
                }));

        assertThrows(
                SourceVerificationException.class,
                () -> ReviewAlignmentCommand.verifySourceUnchanged(
                        source, before));
    }
}
