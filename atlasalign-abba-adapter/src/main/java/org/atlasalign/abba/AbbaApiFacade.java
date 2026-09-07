package org.atlasalign.abba;

import ch.epfl.biop.atlas.aligner.MultiSlicePositioner;
import java.io.File;
import java.util.Objects;
import org.scijava.Context;

/**
 * Small, typed boundary around the pinned ABBA Java API.
 *
 * <p>Keeping these references in one class ensures that future ABBA API changes
 * fail at compile time inside this adapter rather than leaking into the
 * application or user-interface modules.</p>
 */
public final class AbbaApiFacade {

    public static final String PINNED_ABBA_VERSION = "0.20.0";

    private final MultiSlicePositioner positioner;

    public AbbaApiFacade(final MultiSlicePositioner positioner) {
        this.positioner = Objects.requireNonNull(positioner, "positioner");
    }

    public int sliceCount() {
        return positioner.getSlices().size();
    }

    public Context context() {
        return positioner.getContext();
    }

    public boolean saveState(final File destination, final boolean includeView) {
        return positioner.saveState(
                Objects.requireNonNull(destination, "destination"),
                includeView);
    }
}
