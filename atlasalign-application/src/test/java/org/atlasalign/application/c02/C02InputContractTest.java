package org.atlasalign.application.c02;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.CoordinateSpace2D;
import org.junit.jupiter.api.Test;

class C02InputContractTest {

    @Test
    void featureGridDefensivelyCopiesAndHashesExactRawFloatBits() {
        final float[] pixels = {0.0f, -0.0f, 1.25f, -4.5f};
        final C02FeatureGrid grid = new C02FeatureGrid(2, 2, pixels);
        final String initialHash = grid.pixelSha256();

        pixels[0] = 99;
        final float[] returned = grid.intensity();
        returned[1] = 99;

        assertEquals(0.0f, grid.intensity()[0]);
        assertEquals(Float.floatToRawIntBits(-0.0f),
                Float.floatToRawIntBits(grid.intensity()[1]));
        assertEquals(initialHash, grid.pixelSha256());
        assertEquals(64, initialHash.length());
        assertNotEquals(initialHash,
                new C02FeatureGrid(
                        2, 2, new float[] {0, 0, 1.25f, -4.5f})
                        .pixelSha256());
    }

    @Test
    void observedSupportIsFullOnlyAndZeroSynthetic() {
        final BinaryMask tissue = full(7, 7);
        final boolean[] syntheticValues = new boolean[49];
        syntheticValues[24] = true;
        final BinaryMask synthetic = BinaryMask.fromBooleans(
                7, 7, syntheticValues);

        assertThrows(IllegalArgumentException.class,
                () -> C02ObservedSupportMask.create(
                        tissue, synthetic, SectionGeometry.FULL));
        assertThrows(IllegalArgumentException.class,
                () -> C02ObservedSupportMask.create(
                        tissue,
                        BinaryMask.empty(7, 7),
                        SectionGeometry.IMAGE_LEFT_HALF));
        assertThrows(IllegalArgumentException.class,
                () -> C02ObservedSupportMask.create(
                        BinaryMask.empty(7, 7),
                        BinaryMask.empty(7, 7),
                        SectionGeometry.FULL));
    }

    @Test
    void contextUsesC02IdentityAndExactDoubleBits() {
        final C02SearchContext original = C02TestFixtures.context(
                264, 1.25, -2.5);

        assertEquals(C02SearchContext.FEATURE_GENERATION_ID,
                original.featureGenerationId());
        assertEquals(
                "06dc04a228a623598e451b2794d37723d06985644d8b1a7d16851a19150f4118",
                original.identitySha256());
        assertEquals(original.identitySha256(),
                C02TestFixtures.context(264, 1.25, -2.5)
                        .identitySha256());
        assertNotEquals(original.identitySha256(),
                C02TestFixtures.context(264, 1.2500000000000002, -2.5)
                        .identitySha256());
        assertNotEquals(original.identitySha256(),
                C02TestFixtures.context(265, 1.25, -2.5)
                        .identitySha256());
        assertNotEquals(original.identitySha256(),
                org.atlasalign.application.c01.C01SearchContext.SCHEMA);
        assertThrows(IllegalArgumentException.class,
                () -> new C02SearchContext(
                        "1".repeat(64),
                        "2".repeat(64),
                        C02SearchContext.R3_RELEASE_ID,
                        "3".repeat(64),
                        new org.atlasalign.application.AllenCoronalLevel(264),
                        AtlasPlaneTilt.CORONAL,
                        new AffineTransform2D(
                                CoordinateSpace2D.PREVIEW_PIXEL,
                                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                                1, 0, 0, 0, 1, 0),
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        C02SearchContext.FEATURE_GENERATION_ID));
    }

    private static BinaryMask full(final int width, final int height) {
        final boolean[] values = new boolean[width * height];
        java.util.Arrays.fill(values, true);
        return BinaryMask.fromBooleans(width, height, values);
    }
}
