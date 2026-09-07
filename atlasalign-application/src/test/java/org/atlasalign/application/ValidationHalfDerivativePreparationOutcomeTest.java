package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.OptionalDouble;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.PreviewMapping;
import org.junit.jupiter.api.Test;

class ValidationHalfDerivativePreparationOutcomeTest {

    @Test
    void readyRejectsReorderedOrMixedSourcePanelsAndCopiesItsList() {
        final BinaryMask tissue = mask(5, 3, 0, 1, 2, 1, 4, 1);
        final ValidationHalfDerivativePreparationOutcome.Ready first = ready(
                sequentialPixels(5, 3), tissue);
        final float[] secondPixels = sequentialPixels(5, 3);
        secondPixels[7] = 999.0f;
        final ValidationHalfDerivativePreparationOutcome.Ready second = ready(
                secondPixels, tissue);

        assertThrows(IllegalArgumentException.class,
                () -> new ValidationHalfDerivativePreparationOutcome.Ready(
                        List.of(
                                first.derivatives().get(1),
                                first.derivatives().get(0),
                                first.derivatives().get(2),
                                first.derivatives().get(3))));
        assertThrows(IllegalArgumentException.class,
                () -> new ValidationHalfDerivativePreparationOutcome.Ready(
                        List.of(
                                first.derivatives().get(0),
                                first.derivatives().get(1),
                                first.derivatives().get(2),
                                second.derivatives().get(3))));

        final List<ValidationHalfDerivative> mutable = new ArrayList<>(
                first.derivatives());
        final ValidationHalfDerivativePreparationOutcome.Ready copied =
                new ValidationHalfDerivativePreparationOutcome.Ready(mutable);
        mutable.clear();
        assertEquals(4, copied.derivatives().size());
    }

    @Test
    void failedRequiresAStableReasonAndNonblankAuditMessage() {
        final ValidationHalfDerivativePreparationOutcome.Failed failed =
                new ValidationHalfDerivativePreparationOutcome.Failed(
                        ValidationHalfDerivativeFailureReason.EMPTY_OBSERVED_HALF,
                        "EMPTY_OBSERVED_HALF: right image-side observation is empty");
        assertEquals(ValidationHalfDerivativeFailureReason.EMPTY_OBSERVED_HALF,
                failed.reason());
        assertThrows(IllegalArgumentException.class,
                () -> new ValidationHalfDerivativePreparationOutcome.Failed(
                        ValidationHalfDerivativeFailureReason.INVALID_DERIVATIVE,
                        " "));
    }

    private static ValidationHalfDerivativePreparationOutcome.Ready ready(
            final float[] pixels,
            final BinaryMask tissue) {
        final RegistrationPreview source = new RegistrationPreview(
                1,
                1,
                1,
                new PreviewMapping(5, 3, 5, 3),
                pixels);
        final TissueGeometryResult geometry = new TissueGeometryResult(
                SectionGeometry.FULL,
                tissue.bounds(),
                1.5,
                0.8,
                0.01,
                0.01,
                0.9,
                0.9,
                1,
                1,
                OptionalDouble.empty());
        return assertInstanceOf(
                ValidationHalfDerivativePreparationOutcome.Ready.class,
                new ValidationHalfDerivativeBuilder().build(source, tissue, geometry));
    }

    private static float[] sequentialPixels(final int width, final int height) {
        final float[] pixels = new float[Math.multiplyExact(width, height)];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = index + 1.0f;
        }
        return pixels;
    }

    private static BinaryMask mask(
            final int width,
            final int height,
            final int... coordinates) {
        final BitSet values = new BitSet(Math.multiplyExact(width, height));
        for (int index = 0; index < coordinates.length; index += 2) {
            values.set(coordinates[index + 1] * width + coordinates[index]);
        }
        return BinaryMask.fromBitSet(width, height, values);
    }
}
