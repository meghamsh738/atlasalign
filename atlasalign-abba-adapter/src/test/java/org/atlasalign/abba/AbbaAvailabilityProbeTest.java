package org.atlasalign.abba;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.epfl.biop.atlas.aligner.MultiSlicePositioner;
import org.junit.jupiter.api.Test;

class AbbaAvailabilityProbeTest {

    @Test
    void reportsAbsentWithoutAttemptingUiAutomation() {
        final ClassLoader emptyLoader = new ClassLoader(null) {
        };

        final AbbaAvailability result =
                new AbbaAvailabilityProbe().inspect(emptyLoader);

        assertFalse(result.available());
        assertTrue(result.detail().contains("not installed"));
    }

    @Test
    void pinnedReleaseLoadsAndExposesTheTypedAdapterSurface() throws Exception {
        final AbbaAvailability result = new AbbaAvailabilityProbe()
                .inspect(Thread.currentThread().getContextClassLoader());

        assertTrue(result.available());
        assertEquals("0.20.0", AbbaApiFacade.PINNED_ABBA_VERSION);
        assertNotNull(MultiSlicePositioner.class.getMethod("getSlices"));
        assertNotNull(MultiSlicePositioner.class.getMethod("getContext"));
        assertNotNull(MultiSlicePositioner.class.getMethod(
                "saveState", java.io.File.class, boolean.class));
    }

    @Test
    void rejectsAClassWithTheExpectedNameButAnIncompatibleSurface() {
        final ClassLoader incompatibleLoader = new ClassLoader(null) {
            @Override
            public Class<?> loadClass(final String name)
                    throws ClassNotFoundException {
                if ("ch.epfl.biop.atlas.aligner.MultiSlicePositioner"
                        .equals(name)) {
                    return IncompatiblePositioner.class;
                }
                return super.loadClass(name);
            }
        };

        final AbbaAvailability result =
                new AbbaAvailabilityProbe().inspect(incompatibleLoader);

        assertFalse(result.available());
        assertTrue(result.detail().contains("incompatible"));
    }

    private static final class IncompatiblePositioner {
    }
}
