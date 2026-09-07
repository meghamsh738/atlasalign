package org.atlasalign.plugin.validation;

import java.util.Objects;
import org.atlasalign.application.c01.C01BoundFeatureGrid;
import org.atlasalign.application.c01.C01ObservedSupportMask;

/** Tissue feature grid and real-pixel support prepared on the Allen grid. */
public record C01PreparedTissueFeatures(
        C01BoundFeatureGrid tissueFeature,
        C01ObservedSupportMask support) {

    public C01PreparedTissueFeatures {
        tissueFeature = Objects.requireNonNull(tissueFeature, "tissueFeature");
        support = Objects.requireNonNull(support, "support");
        if (tissueFeature.role()
                != C01BoundFeatureGrid.Role.TISSUE) {
            throw new IllegalArgumentException(
                    "Prepared C01 tissue evidence must carry the tissue role");
        }
    }
}
