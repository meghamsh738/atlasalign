package org.atlasalign.plugin.validation;

import java.util.Objects;
import org.atlasalign.application.c02.C02BoundFeatureGrid;
import org.atlasalign.application.c02.C02ObservedSupportMask;

/** Copied tissue intensities and full real-pixel support on the Allen grid. */
public record C02PreparedTissueFeatures(
        C02BoundFeatureGrid tissueFeature,
        C02ObservedSupportMask support) {

    public C02PreparedTissueFeatures {
        tissueFeature = Objects.requireNonNull(
                tissueFeature, "tissueFeature");
        support = Objects.requireNonNull(support, "support");
        if (tissueFeature.role() != C02BoundFeatureGrid.Role.TISSUE) {
            throw new IllegalArgumentException(
                    "Prepared C02 tissue evidence must carry the tissue role");
        }
        if (tissueFeature.grid().width()
                        != support.completeObserved().width()
                || tissueFeature.grid().height()
                        != support.completeObserved().height()) {
            throw new IllegalArgumentException(
                    "Prepared C02 tissue grid and support must match");
        }
    }
}
