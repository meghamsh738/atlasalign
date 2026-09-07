package org.atlasalign.application.c02;

import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;

final class C02TestFixtures {

    private C02TestFixtures() {
    }

    static C02SearchContext context(
            final int level,
            final double sagittal,
            final double horizontal) {
        return new C02SearchContext(
                "1".repeat(64),
                "2".repeat(64),
                C02SearchContext.R3_RELEASE_ID,
                "3".repeat(64),
                new AllenCoronalLevel(level),
                new AtlasPlaneTilt(sagittal, horizontal),
                new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1, 0, 2, 0, 1, 3),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                C02SearchContext.FEATURE_GENERATION_ID);
    }
}
