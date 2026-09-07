package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeepSlicePhysicalDisagreementTest {

    @Test
    void identicalPlanesHaveZeroPhysicalDisagreement() {
        final DeepSlicePlaneGeometry plane =
                new DeepSlicePlaneGeometry(240, 2, -3);
        final DeepSlicePhysicalDisagreement result =
                DeepSlicePhysicalDisagreement.between(
                        plane, plane, 456, 320, 25);

        assertEquals(0, result.centerAnteriorPosteriorMicrometers());
        assertEquals(0, result.normalAngleDegrees(), 1e-9);
        assertEquals(0, result.maximumGridDisplacementMicrometers());
    }

    @Test
    void reportsCenterAndEdgeDisagreementInPhysicalUnits() {
        final DeepSlicePhysicalDisagreement result =
                DeepSlicePhysicalDisagreement.between(
                        new DeepSlicePlaneGeometry(240, 0, 0),
                        new DeepSlicePlaneGeometry(252, 3, -4),
                        456, 320, 25);

        assertEquals(300,
                result.centerAnteriorPosteriorMicrometers());
        assertTrue(result.normalAngleDegrees() > 0);
        assertTrue(result.maximumGridDisplacementMicrometers()
                > result.centerAnteriorPosteriorMicrometers());
    }
}
