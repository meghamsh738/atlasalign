package org.atlasalign.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class PreviewMappingTest {

    @Test
    void boundsLargestDimensionWithoutUpsampling() {
        final PreviewMapping mapping = PreviewMapping.bounded(12_001, 7_999, 2_048);

        assertEquals(2_048, mapping.previewWidth());
        assertEquals(1_365, mapping.previewHeight());
    }

    @Test
    void sourcePreviewRoundTripIsExactWithinFloatingPointTolerance() {
        final PreviewMapping mapping = PreviewMapping.bounded(12_001, 7_999, 2_048);
        final Point2D source = new Point2D(10_234.25, 6_777.75);

        final Point2D recovered = mapping.previewToSource(mapping.sourceToPreview(source));

        assertEquals(source.x(), recovered.x(), 1e-10);
        assertEquals(source.y(), recovered.y(), 1e-10);
    }

    @Test
    void mapsEdgePixelCentersUsingEffectiveAxisScales() {
        final PreviewMapping mapping = new PreviewMapping(5, 3, 2, 1);

        assertEquals(-0.3, mapping.sourceToPreview(new Point2D(0, 0)).x(), 1e-12);
        assertEquals(1.3, mapping.sourceToPreview(new Point2D(4, 2)).x(), 1e-12);
        assertEquals(1.0 / 3.0, mapping.scaleY(), 1e-12);
    }

    @Test
    void rejectsUpsampling() {
        assertThrows(IllegalArgumentException.class,
                () -> new PreviewMapping(10, 10, 11, 10));
    }

    @Test
    void largeImageEdgeCoordinatesRoundTripWithoutOverflow() {
        final PreviewMapping mapping =
                PreviewMapping.bounded(2_000_000_000, 1_500_000_000, 2_048);
        final Point2D edge = new Point2D(1_999_999_999, 1_499_999_999);

        final Point2D recovered =
                mapping.previewToSource(mapping.sourceToPreview(edge));

        assertEquals(edge.x(), recovered.x(), 1e-6);
        assertEquals(edge.y(), recovered.y(), 1e-6);
    }
}
