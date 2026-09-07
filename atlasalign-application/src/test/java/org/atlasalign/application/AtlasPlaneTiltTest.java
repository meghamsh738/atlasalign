package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AtlasPlaneTiltTest {

    @Test
    void deltasAreExplicitAndBounded() {
        final AtlasPlaneTilt tilt = new AtlasPlaneTilt(10, -4)
                .withSagittalDelta(2)
                .withHorizontalDelta(3);
        assertEquals(12, tilt.sagittalDegrees());
        assertEquals(-1, tilt.horizontalDegrees());
    }

    @Test
    void outOfRangeAndNonFiniteAnglesFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AtlasPlaneTilt(46, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AtlasPlaneTilt(0, Double.POSITIVE_INFINITY));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AtlasPlaneTilt(45, 1)
                        .withSagittalDelta(1));
    }
}
