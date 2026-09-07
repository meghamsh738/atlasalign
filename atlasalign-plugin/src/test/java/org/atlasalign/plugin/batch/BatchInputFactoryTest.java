package org.atlasalign.plugin.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import java.util.List;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.junit.jupiter.api.Test;

class BatchInputFactoryTest {

    @Test
    void roiManagerMarkersBecomeOrderedSourceCoordinateSections() {
        final ImagePlus source = new ImagePlus("slide.tif",
                new ByteProcessor(100, 80));
        final var snapshot = new ImagePlusSourceImage(source).snapshot();
        final Roi first = rectangle("Section A", 5, 7, 30, 20);
        final Roi second = rectangle("Section B", 50, 40, 35, 25);

        final List<BatchReviewItem> items = new BatchInputFactory()
                .markedSections(source, List.of(first, second),
                        250, 3);

        assertEquals(2, items.size());
        assertEquals("Section A", items.get(0).section().name());
        assertEquals(5, items.get(0).section().minimumX());
        assertEquals(7, items.get(0).section().minimumY());
        assertEquals(30, items.get(0).section().width());
        assertEquals(250,
                items.get(0).section().initialCoronalLevel());
        assertEquals(253,
                items.get(1).section().initialCoronalLevel());
        assertEquals(snapshot,
                new ImagePlusSourceImage(source).snapshot());
    }

    private static Roi rectangle(
            final String name,
            final int x,
            final int y,
            final int width,
            final int height) {
        final PolygonRoi roi = new PolygonRoi(
                new int[]{x, x + width, x + width, x},
                new int[]{y, y, y + height, y + height},
                4, Roi.POLYGON);
        roi.setName(name);
        return roi;
    }
}
