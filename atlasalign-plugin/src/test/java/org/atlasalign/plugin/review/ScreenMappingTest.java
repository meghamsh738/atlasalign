package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ScreenMappingTest {

    @Test
    void letterboxMappingPreservesPixelCentersAndRoundTrips() {
        final ScreenMapping mapping =
                ScreenMapping.fit(100, 50, 300, 300);
        final Point2D preview = new Point2D(23.25, 41.75);

        final Point2D screen =
                mapping.previewToScreen(preview);
        final Point2D restored =
                mapping.screenToPreview(screen);

        assertEquals(3, mapping.scale(), 0);
        assertEquals(75, mapping.offsetY(), 0);
        assertEquals(preview.x(), restored.x(), 1e-12);
        assertEquals(preview.y(), restored.y(), 1e-12);
        assertEquals(4, mapping.screenDeltaToPreview(12), 0);
    }

    @Test
    void zoomAboutAnchorPreservesTheAnchoredPixelCenter() {
        final ScreenMapping mapping =
                ScreenMapping.fit(100, 50, 300, 300);
        final Point2D anchor = new Point2D(117.25, 92.75);
        final Point2D previewBefore = mapping.screenToPreview(anchor);

        final ScreenMapping zoomed = mapping.zoomedAbout(4, anchor);

        assertEquals(mapping.scale() * 4, zoomed.scale(), 0);
        assertEquals(previewBefore.x(),
                zoomed.screenToPreview(anchor).x(), 1e-12);
        assertEquals(previewBefore.y(),
                zoomed.screenToPreview(anchor).y(), 1e-12);
    }

    @Test
    void panChangesOnlyOffsetsAndRemainsReversible() {
        final ScreenMapping mapping =
                ScreenMapping.fit(100, 50, 300, 300);
        final ScreenMapping panned = mapping.translated(17.5, -9.25);
        final Point2D preview = new Point2D(19.5, 7.25);

        assertEquals(mapping.scale(), panned.scale(), 0);
        assertEquals(mapping.offsetX() + 17.5, panned.offsetX(), 0);
        assertEquals(mapping.offsetY() - 9.25, panned.offsetY(), 0);
        final Point2D restored = panned.screenToPreview(
                panned.previewToScreen(preview));
        assertEquals(preview.x(), restored.x(), 1e-12);
        assertEquals(preview.y(), restored.y(), 1e-12);
    }
}
