package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AllenCoronalLevelTest {

    @Test
    void mapsTheAnteriorPosteriorIndexToAnteriorOriginDistance() {
        final AllenCoronalLevel level = new AllenCoronalLevel(200);

        assertEquals(5_000, level.anteriorOriginMicrometers());
    }

    @Test
    void rejectsIndicesOutsideThePinnedReferenceVolume() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AllenCoronalLevel(-1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new AllenCoronalLevel(528));
    }
}
