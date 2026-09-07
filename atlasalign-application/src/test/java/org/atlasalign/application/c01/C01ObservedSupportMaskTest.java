package org.atlasalign.application.c01;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.atlasalign.application.SectionGeometry;
import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class C01ObservedSupportMaskTest {

    @Test
    void removesTheFrozenTwoHundredMicrometerOuterBand() {
        final BinaryMask tissue = full(25, 25);
        final C01ObservedSupportMask support =
                C01ObservedSupportMask.create(
                        tissue,
                        BinaryMask.empty(25, 25),
                        SectionGeometry.FULL);

        assertEquals(8, support.erosionRadiusPixels());
        assertEquals(200,
                C01ObservedSupportMask.OUTER_EROSION_MICROMETERS);
        assertEquals(625, support.completeObserved().foregroundCount());
        assertEquals(81, support.internalObserved().foregroundCount());
        assertTrue(support.internalObserved().contains(8, 8));
        assertTrue(support.internalObserved().contains(16, 16));
    }

    @Test
    void rejectsPartialOrSyntheticEvidenceAtTheTypeBoundary() {
        final BinaryMask tissue = full(25, 25);
        final boolean[] syntheticValues = new boolean[25 * 25];
        syntheticValues[12 * 25 + 12] = true;
        final BinaryMask synthetic = BinaryMask.fromBooleans(
                25, 25, syntheticValues);

        assertThrows(IllegalArgumentException.class,
                () -> C01ObservedSupportMask.create(
                        tissue,
                        synthetic,
                        SectionGeometry.FULL));
        assertThrows(IllegalArgumentException.class,
                () -> C01ObservedSupportMask.create(
                        tissue,
                        BinaryMask.empty(25, 25),
                        SectionGeometry.IMAGE_LEFT_HALF));
    }

    @Test
    void failsWhenTheFrozenErosionLeavesNoInternalSupport() {
        assertThrows(IllegalArgumentException.class,
                () -> C01ObservedSupportMask.create(
                        full(15, 15),
                        BinaryMask.empty(15, 15),
                        SectionGeometry.FULL));
    }

    @Test
    void completeMaskDiagnosticReusesTheValidatedObservationWithoutErosion() {
        final C01ObservedSupportMask support =
                C01ObservedSupportMask.create(
                        full(25, 25),
                        BinaryMask.empty(25, 25),
                        SectionGeometry.FULL);

        final C01ObservedSupportMask diagnostic =
                support.completeMaskDiagnostic();

        assertEquals(0, diagnostic.erosionRadiusPixels());
        assertEquals(625,
                diagnostic.completeObserved().foregroundCount());
        assertEquals(625,
                diagnostic.internalObserved().foregroundCount());
        assertEquals(support.completeObserved().copyBits(),
                diagnostic.completeObserved().copyBits());
        assertEquals(support.completeObserved().copyBits(),
                diagnostic.internalObserved().copyBits());
        assertEquals(8, support.erosionRadiusPixels());
    }

    private static BinaryMask full(
            final int width,
            final int height) {
        final boolean[] values = new boolean[width * height];
        java.util.Arrays.fill(values, true);
        return BinaryMask.fromBooleans(width, height, values);
    }
}
