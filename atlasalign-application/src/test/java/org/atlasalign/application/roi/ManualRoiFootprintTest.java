package org.atlasalign.application.roi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ManualRoiFootprintTest {

    @Test
    void rasterizesExactAddMinusSubtractPixelCentreMembership() {
        final ReviewerRoi roi = new ReviewerRoi(
                "roi-1", "DG left", ReviewerRoiSide.LEFT,
                Optional.empty(), List.of(
                        part("add", RoiPartOperation.ADD,
                                point(1, 1), point(6, 1),
                                point(6, 6), point(1, 6)),
                        part("hole", RoiPartOperation.SUBTRACT,
                                point(3, 3), point(5, 3),
                                point(5, 5), point(3, 5))),
                true, true);

        final ManualRoiFootprint footprint =
                ManualRoiFootprint.rasterize(roi, 9, 8);

        assertEquals(new ManualRoiFootprint.Bounds(1, 1, 6, 6),
                footprint.bounds());
        assertTrue(footprint.containsSourcePixel(1, 1));
        assertTrue(footprint.containsSourcePixel(2, 2));
        assertTrue(footprint.containsSourcePixel(6, 6));
        assertFalse(footprint.containsSourcePixel(4, 4));
        assertFalse(footprint.containsSourcePixel(0, 0));
        assertEquals(27, footprint.pixelCount());
        for (int y = -1; y <= 8; y++) for (int x = -1; x <= 9; x++) {
            assertEquals(footprint.containsSourcePixel(x, y), ManualRoiFootprint.containsSourcePixel(roi, x, y, 9, 8));
        }
    }

    @Test
    void unionsMultipleAddPiecesWithoutAtlasOrWarpInputs() {
        final ReviewerRoi roi = new ReviewerRoi(
                "roi-1", "Two pieces", ReviewerRoiSide.BILATERAL,
                Optional.empty(), List.of(
                        part("a", RoiPartOperation.ADD,
                                point(0, 0), point(2, 0),
                                point(2, 2), point(0, 2)),
                        part("b", RoiPartOperation.ADD,
                                point(5, 4), point(7, 4),
                                point(7, 6), point(5, 6))),
                true, true);

        final ManualRoiFootprint footprint =
                ManualRoiFootprint.rasterize(roi, 8, 7);

        assertEquals(18, footprint.pixelCount());
        assertTrue(footprint.containsSourcePixel(1, 1));
        assertTrue(footprint.containsSourcePixel(6, 5));
        assertFalse(footprint.containsSourcePixel(4, 3));
        for (int y = 0; y < 7; y++) for (int x = 0; x < 8; x++) {
            assertEquals(footprint.containsSourcePixel(x, y), ManualRoiFootprint.containsSourcePixel(roi, x, y, 8, 7));
        }
    }

    private static ReviewerRoiPart part(final String id,
            final RoiPartOperation operation, final Point2D... points) {
        return new ReviewerRoiPart(id, operation,
                java.util.stream.IntStream.range(0, points.length)
                        .mapToObj(index -> new ReviewerRoiVertex(
                                id + "-" + index, points[index]))
                        .toList(), true);
    }

    private static Point2D point(final double x, final double y) {
        return new Point2D(x, y);
    }
}
