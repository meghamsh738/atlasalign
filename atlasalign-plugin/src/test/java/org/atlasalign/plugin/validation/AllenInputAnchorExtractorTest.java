package org.atlasalign.plugin.validation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import org.atlasalign.application.dg.DgInputAnchor;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class AllenInputAnchorExtractorTest {

    @Test
    void extractsFrozenMidlineAndSideSpecificPixelCentreRules() {
        final int width = 10;
        final int height = 12;
        final int[] labels = new int[width * height];
        labels[3 * width + 4] = 10;
        labels[2 * width + 3] = 10;
        labels[4 * width + 5] = 10;
        labels[3 * width + 1] = 20;
        labels[2 * width + 2] = 20;
        labels[1 * width + 8] = 20;
        labels[1 * width + 7] = 20;
        labels[8 * width + 4] = 30;
        labels[9 * width + 3] = 30;
        labels[9 * width + 5] = 30;
        final int[] before = labels.clone();

        final Map<DgInputAnchor, Point2D> result =
                new AllenInputAnchorExtractor().extractForRegionIds(
                        new AtlasCoronalPlane(
                                264, width, height,
                                new int[labels.length], labels),
                        Set.of(10), Set.of(20), Set.of(30));

        assertArrayEquals(before, labels);
        assertEquals(new Point2D(4, 3), result.get(
                DgInputAnchor.DORSAL_CORPUS_CALLOSUM_MIDLINE));
        assertEquals(new Point2D(2, 2), result.get(
                DgInputAnchor.LEFT_LATERAL_VENTRICLE_APEX));
        assertEquals(new Point2D(7, 1), result.get(
                DgInputAnchor.RIGHT_LATERAL_VENTRICLE_APEX));
        assertEquals(new Point2D(5, 9), result.get(
                DgInputAnchor.VENTRAL_MIDLINE_OR_THIRD_VENTRICLE));
    }

    @Test
    void missingLabelsRemainUnavailable() {
        final Map<DgInputAnchor, Point2D> result =
                new AllenInputAnchorExtractor().extractForRegionIds(
                        new AtlasCoronalPlane(
                                264, 8, 8, new int[64], new int[64]),
                        Set.of(10), Set.of(20), Set.of(30));

        assertEquals(Map.of(), result);
    }
}
