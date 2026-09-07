package org.atlasalign.abba;

import java.io.File;
import java.util.List;

/**
 * Detects ABBA without clicking or scraping its user interface.
 */
public final class AbbaAvailabilityProbe {

    private static final String MULTI_SLICE_POSITIONER =
            "ch.epfl.biop.atlas.aligner.MultiSlicePositioner";

    public AbbaAvailability inspect(final ClassLoader classLoader) {
        try {
            final Class<?> positioner = classLoader.loadClass(
                    MULTI_SLICE_POSITIONER);
            final Class<?> slicesType =
                    positioner.getMethod("getSlices").getReturnType();
            final Class<?> contextType =
                    positioner.getMethod("getContext").getReturnType();
            final Class<?> saveStateType = positioner.getMethod(
                    "saveState", File.class, boolean.class).getReturnType();

            if (!List.class.isAssignableFrom(slicesType)
                    || !"org.scijava.Context".equals(contextType.getName())
                    || saveStateType != boolean.class) {
                return incompatible();
            }
            return new AbbaAvailability(
                    true,
                    "ABBA Java API matches the pinned AtlasAlign adapter surface");
        } catch (final ClassNotFoundException absent) {
            return new AbbaAvailability(
                    false,
                    "ABBA is not installed in this Fiji distribution");
        } catch (final NoSuchMethodException | LinkageError
                | SecurityException incompatible) {
            return incompatible();
        }
    }

    private static AbbaAvailability incompatible() {
        return new AbbaAvailability(
                false,
                "ABBA is installed but its Java API is incompatible with the pinned AtlasAlign adapter surface");
    }
}
