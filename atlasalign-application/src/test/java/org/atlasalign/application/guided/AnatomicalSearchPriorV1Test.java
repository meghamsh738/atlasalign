package org.atlasalign.application.guided;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.atlasalign.application.AllenCoronalLevel;
import org.junit.jupiter.api.Test;

class AnatomicalSearchPriorV1Test {

    private static final Instant CAPTURED =
            Instant.parse("2026-08-09T09:00:00Z");
    private static final Instant LOCKED =
            Instant.parse("2026-08-09T09:00:12Z");

    @Test
    void locksExactIndicesPhysicalUnitsAndStableIdentity() {
        final AnatomicalSearchPriorV1 prior = prior(200, 264);

        assertEquals(64, prior.widthIndices());
        assertEquals(5_000, prior.startMicrometersFromAnteriorOrigin());
        assertEquals(6_600, prior.endMicrometersFromAnteriorOrigin());
        assertTrue(prior.capturedBeforeAutomaticDisplay());
        assertEquals(
                "4b09b73618d07fcf80c270b39dbcefce3cba319d1e0054db16e7d8dd4d3a1fe7",
                prior.priorSha256());
        assertEquals(prior.priorSha256(), prior(200, 264).priorSha256());
        assertFalse(prior.canonicalIdentityText().contains("r3"));
        assertFalse(prior.canonicalIdentityText().contains("score"));
    }

    @Test
    void rejectsIntervalsOutsideFrozenWidthAndInvalidLockOrdering() {
        assertThrows(IllegalArgumentException.class, () -> prior(200, 231));
        assertThrows(IllegalArgumentException.class, () -> prior(200, 361));
        assertThrows(IllegalArgumentException.class,
                () -> AnatomicalSearchPriorV1.lockBeforeAutomaticDisplay(
                        "provider-a",
                        "1".repeat(64),
                        "2".repeat(64),
                        new AllenCoronalLevel(200),
                        new AllenCoronalLevel(264),
                        AnatomicalTissueClass.FULL_SECTION,
                        LOCKED,
                        CAPTURED,
                        Optional.empty()));
    }

    private static AnatomicalSearchPriorV1 prior(
            final int lower,
            final int upper) {
        return AnatomicalSearchPriorV1.lockBeforeAutomaticDisplay(
                "provider-a",
                "1".repeat(64),
                "2".repeat(64),
                new AllenCoronalLevel(lower),
                new AllenCoronalLevel(upper),
                AnatomicalTissueClass.FULL_SECTION,
                CAPTURED,
                LOCKED,
                Optional.empty());
    }
}
