package org.atlasalign.atlas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AtlasPlaneGeometryTest {

    @Test
    void zeroTiltUsesAllenCoronalPixelCoordinates() {
        final AtlasPlaneGeometry geometry = AtlasPlaneGeometry.create(
                264, 456, 320, 0, 0, 320, 456);

        assertArrayEquals(
                new double[] {264, 0, 0},
                geometry.voxelForPlanePixel(0, 0));
        assertArrayEquals(
                new double[] {264, 159.5, 227.5},
                geometry.voxelForPlanePixel(227.5, 159.5));
        assertArrayEquals(
                new double[] {264, 319, 455},
                geometry.voxelForPlanePixel(455, 319));
    }

    @Test
    void tiltedPlaneKeepsThePixelCentreAtTheVolumeCentre() {
        final AtlasPlaneGeometry geometry = AtlasPlaneGeometry.create(
                264, 456, 320, 12, -8, 320, 456);

        assertArrayEquals(
                new double[] {264, 159.5, 227.5},
                geometry.voxelForPlanePixel(227.5, 159.5));
    }

    @Test
    void anglesAreFiniteAndBounded() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AtlasPlaneGeometry.create(
                        264, 456, 320, 46, 0, 320, 456));
        assertThrows(
                IllegalArgumentException.class,
                () -> AtlasPlaneGeometry.create(
                        264, 456, 320, 0, Double.NaN, 320, 456));
        assertEquals(
                45,
                AtlasPlaneGeometry.create(
                        264, 456, 320, 45, -45, 320, 456)
                        .sagittalDegrees());
    }

    @Test
    void positiveDeepSliceTiltsMoveTheExpectedAtlasEdgesAnteriorly() {
        final AtlasPlaneGeometry sagittal = AtlasPlaneGeometry.create(
                264, 456, 320, 10, 0, 320, 456);
        assertEquals(264, sagittal.voxelForPlanePixel(227.5, 159.5)[0]);
        assertTrue(sagittal.voxelForPlanePixel(455, 159.5)[0] < 264);
        assertTrue(sagittal.voxelForPlanePixel(0, 159.5)[0] > 264);

        final AtlasPlaneGeometry horizontal = AtlasPlaneGeometry.create(
                264, 456, 320, 0, 10, 320, 456);
        assertTrue(horizontal.voxelForPlanePixel(227.5, 0)[0] < 264);
        assertTrue(horizontal.voxelForPlanePixel(227.5, 319)[0] > 264);
    }
}
