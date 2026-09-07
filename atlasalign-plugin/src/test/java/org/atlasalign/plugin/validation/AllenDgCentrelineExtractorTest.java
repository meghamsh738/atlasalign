package org.atlasalign.plugin.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.atlasalign.application.dg.AnatomicalSide;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class AllenDgCentrelineExtractorTest {

    @Test
    void derivesSeparateLeftAndRightPathsFromCopiedDgLabels() {
        final int width = 80;
        final int height = 60;
        final int[] template = new int[width * height];
        final int[] annotations = new int[width * height];
        drawThickL(annotations, width, 8, 10, 30, 40, 77);
        drawThickL(annotations, width, 48, 10, 70, 40, 77);
        final int[] before = annotations.clone();

        final Map<AnatomicalSide, List<Point2D>> result =
                new AllenDgCentrelineExtractor().extractForRegionIds(
                        new AtlasCoronalPlane(
                                240, width, height, template, annotations),
                        Set.of(77));

        assertEquals(before.length, annotations.length);
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, annotations);
        assertEquals(Set.of(AnatomicalSide.LEFT, AnatomicalSide.RIGHT),
                result.keySet());
        assertTrue(result.get(AnatomicalSide.LEFT).size() > 20);
        assertTrue(result.get(AnatomicalSide.RIGHT).size() > 20);
        assertTrue(result.get(AnatomicalSide.LEFT).stream()
                .allMatch(point -> point.x() < width / 2.0));
        assertTrue(result.get(AnatomicalSide.RIGHT).stream()
                .allMatch(point -> point.x() >= width / 2.0));
    }

    @Test
    void absentOrSinglePixelDgIsUnavailable() {
        final int[] annotation = new int[20 * 20];
        annotation[3 * 20 + 3] = 77;
        final Map<AnatomicalSide, List<Point2D>> result =
                new AllenDgCentrelineExtractor().extractForRegionIds(
                        new AtlasCoronalPlane(
                                240, 20, 20,
                                new int[20 * 20], annotation),
                        Set.of(77));

        assertTrue(result.isEmpty());
        assertFalse(result.containsKey(AnatomicalSide.LEFT));
    }

    private static void drawThickL(
            final int[] annotation,
            final int width,
            final int left,
            final int top,
            final int right,
            final int bottom,
            final int id) {
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= left + 4; x++) {
                annotation[y * width + x] = id;
            }
        }
        for (int y = bottom - 4; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                annotation[y * width + x] = id;
            }
        }
    }
}
