package org.atlasalign.plugin.batch;

import static org.junit.jupiter.api.Assertions.*;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.gui.Overlay;
import ij.process.ByteProcessor;
import java.util.List;
import java.util.ArrayList;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.junit.jupiter.api.Test;

class WholeSlideOverviewPanelTest {
    @Test void selectionUsesSmallestPolygonThenQueueOrderAndFiltersSourceIdentity() {
        final var image = new ImagePlus("slide", new ByteProcessor(100, 100));
        final var items = new ArrayList<>(new BatchInputFactory().markedSections(image,
                List.of(new Roi(0, 0, 80, 80), new Roi(10, 10, 20, 20), new Roi(10, 10, 20, 20)), 264, 0));
        final var other = new ImagePlus("same pixels, different slide", new ByteProcessor(100, 100));
        items.addAll(new BatchInputFactory().markedSections(other, List.of(new Roi(0, 0, 100, 100)), 264, 0));
        var markers = WholeSlideOverviewPanel.markersFor(items, items.get(0));
        assertEquals(3, markers.size());
        assertEquals(1, WholeSlideOverviewPanel.hitTest(markers, 15, 15));
        assertEquals(0, WholeSlideOverviewPanel.hitTest(markers, 50, 50));
        assertEquals(-1, WholeSlideOverviewPanel.hitTest(markers, 95, 95));
        java.util.Collections.swap(items, 0, 1);
        markers = WholeSlideOverviewPanel.markersFor(items, items.get(0));
        assertEquals(0, WholeSlideOverviewPanel.hitTest(markers, 15, 15));
        assertEquals(1, WholeSlideOverviewPanel.hitTest(markers, 50, 50));
        assertEquals(3, WholeSlideOverviewPanel.markersFor(items, items.get(3)).get(0).index());
    }

    @Test void actualPolygonNotBoundingBoxDeterminesSelection() {
        final var image = new ImagePlus("slide", new ByteProcessor(100, 100));
        final var triangle = new ij.gui.PolygonRoi(new int[] {0, 80, 0}, new int[] {0, 0, 80}, 3, Roi.POLYGON);
        final var items = new BatchInputFactory().markedSections(image, List.of(triangle), 264, 0);
        final var markers = WholeSlideOverviewPanel.markersFor(items, items.get(0));
        assertEquals(0, WholeSlideOverviewPanel.hitTest(markers, 10, 10));
        assertEquals(-1, WholeSlideOverviewPanel.hitTest(markers, 70, 70));
    }

    @Test void mappingPreservesPixelCentersAndRoundTripsAfterZoomAndPan() throws Exception {
        for (double zoom : new double[] {1, 2.25, 16}) {
            final var mapping = WholeSlideOverviewPanel.sourceToView(3001, 1777, 1024, 606, 431, 207, zoom, 32, -18);
            final var point = new Point2D.Double(1732.25, 822.75);
            final var recovered = mapping.inverseTransform(mapping.transform(point, null), null);
            assertEquals(point.x, recovered.getX(), 1e-9);
            assertEquals(point.y, recovered.getY(), 1e-9);
            final var edge = mapping.transform(new Point2D.Double(-0.5, -0.5), null);
            final var center = mapping.transform(new Point2D.Double(0, 0), null);
            assertEquals(mapping.getScaleX() / 2, center.getX() - edge.getX(), 1e-9);
            assertEquals(mapping.getScaleY() / 2, center.getY() - edge.getY(), 1e-9);
        }
    }

    @Test void boundedWholeSlideCopyPreservesPixelsCalibrationDisplayAndOverlay() {
        final var pixels = new ByteProcessor(2048, 1200);
        pixels.set(1, 1, 255);
        final var image = new ImagePlus("source", pixels);
        image.getCalibration().pixelWidth = 0.3;
        image.getCalibration().pixelHeight = 0.4;
        image.setDisplayRange(10, 190);
        image.setRoi(new Roi(3, 4, 5, 6));
        final var roi = image.getRoi();
        final var overlay = new Overlay(roi);
        image.setOverlay(overlay);
        final var before = new ImagePlusSourceImage(image).snapshot();
        final var item = new BatchInputFactory().markedSections(image, List.of(new Roi(0, 0, 100, 100)), 264, 0).get(0);
        final var preview = SectionPreviewPanel.renderRegion(item, 1, 1, 1, () -> false, 0, 0, 2048, 1200, 1024);
        assertEquals(1024, preview.getWidth());
        assertEquals(600, preview.getHeight());
        assertEquals(0xffffff, preview.getRGB(0, 0) & 0xffffff);
        preview.setRGB(0, 0, 0);
        assertEquals(255, pixels.get(1, 1));
        assertEquals(before, new ImagePlusSourceImage(image).snapshot());
        assertSame(roi, image.getRoi()); assertSame(overlay, image.getOverlay());
        assertEquals(10, image.getDisplayRangeMin()); assertEquals(190, image.getDisplayRangeMax());
    }

    @Test void requestedPlaneDoesNotChangeActiveChannelSliceOrCalibration() {
        final var stack = new ij.ImageStack(20, 20);
        for (int plane = 0; plane < 4; plane++) {
            final var pixels = new ByteProcessor(20, 20);
            pixels.set(0, 0, plane == 2 ? 255 : 0);
            stack.addSlice("plane " + plane, pixels);
        }
        final var image = new ImagePlus("channels", stack);
        image.setDimensions(2, 2, 1);
        image.setPosition(2, 1, 1);
        final var before = new ImagePlusSourceImage(image).snapshot();
        final var item = new BatchInputFactory().markedSections(image,
                List.of(new Roi(0, 0, 20, 20)), 264, 0).get(0);
        final var preview = SectionPreviewPanel.renderRegion(item, 1, 2, 1,
                () -> false, 0, 0, 20, 20, 1024);
        assertEquals(0xffffff, preview.getRGB(0, 0) & 0xffffff);
        assertEquals(2, image.getC()); assertEquals(1, image.getZ()); assertEquals(1, image.getT());
        assertEquals(before, new ImagePlusSourceImage(image).snapshot());
    }

    @Test void mouseSelectionAndFitControlsUseTheDisplayedViewport() throws Exception {
        final var image = new ImagePlus("slide", new ByteProcessor(100, 100));
        final var items = new BatchInputFactory().markedSections(image,
                List.of(new Roi(0, 0, 40, 40), new Roi(50, 50, 40, 40)), 264, 0);
        final var selected = new java.util.concurrent.atomic.AtomicInteger(-1);
        final var panel = new WholeSlideOverviewPanel(selected::set);
        final var imageField = WholeSlideOverviewPanel.class.getDeclaredField("image");
        imageField.setAccessible(true);
        final var zoomField = WholeSlideOverviewPanel.class.getDeclaredField("zoom");
        zoomField.setAccessible(true);
        SwingUtilities.invokeAndWait(() -> {
            panel.showSections(items, 0, 1);
            try { imageField.set(panel, new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)); }
            catch (IllegalAccessException error) { throw new AssertionError(error); }
            panel.setSize(440, 280); panel.doLayout();
            final var canvas = (javax.swing.JPanel) panel.getComponent(0);
            final var controls = (javax.swing.JPanel) panel.getComponent(1);
            ((javax.swing.JButton) controls.getComponent(2)).doClick();
            final var mapping = WholeSlideOverviewPanel.sourceToView(100, 100, 100, 100,
                    canvas.getWidth(), canvas.getHeight(), 1.5, 0, 0);
            final var target = mapping.transform(new Point2D.Double(65, 65), null);
            final var event = new java.awt.event.MouseEvent(canvas,
                    java.awt.event.MouseEvent.MOUSE_RELEASED, 1, 0,
                    (int) target.getX(), (int) target.getY(), 1, false);
            assertTrue(canvas.getToolTipText(event).startsWith("2. "));
            canvas.dispatchEvent(event);
            assertEquals(1, selected.get());
            ((javax.swing.JButton) controls.getComponent(0)).doClick();
            try { assertEquals(1.0, zoomField.getDouble(panel)); }
            catch (IllegalAccessException error) { throw new AssertionError(error); }
            panel.removeNotify();
        });
    }

    @Test void wholeSlideFailsClosedOnPostReadSourceChange() {
        final var image = new ImagePlus("slide", new ByteProcessor(20, 20));
        final var item = new BatchInputFactory().markedSections(image,
                List.of(new Roi(0, 0, 20, 20)), 264, 0).get(0);
        final var checks = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalStateException.class, () -> SectionPreviewPanel.renderRegion(item,
                1, 1, 1, () -> {
                    // The final cancellation check precedes the post-render snapshot.
                    if (checks.incrementAndGet() == 23) image.getProcessor().set(0, 0, 1);
                    return false;
                }, 0, 0, 20, 20, 1024));
    }

    @Test void rapidSourceSelectionCannotPublishSupersededImage() throws Exception {
        final var first = new ImagePlus("wide", new ByteProcessor(80, 40));
        final var second = new ImagePlus("tall", new ByteProcessor(40, 80));
        final var items = new ArrayList<>(new BatchInputFactory().markedSections(first, List.of(new Roi(0, 0, 20, 20)), 264, 0));
        items.addAll(new BatchInputFactory().markedSections(second, List.of(new Roi(0, 0, 20, 20)), 264, 0));
        final var panel = new WholeSlideOverviewPanel(index -> fail("Preview must not open/select reviews"));
        SwingUtilities.invokeAndWait(() -> { panel.showSections(items, 0, 1); panel.showSections(items, 1, 1); });
        final var field = WholeSlideOverviewPanel.class.getDeclaredField("image"); field.setAccessible(true);
        final var published = new java.util.concurrent.atomic.AtomicReference<BufferedImage>();
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (published.get() == null && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> {
                try { published.set((BufferedImage) field.get(panel)); }
                catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
            Thread.sleep(20);
        }
        assertNotNull(published.get()); assertEquals(40, published.get().getWidth()); assertEquals(80, published.get().getHeight());
    }
}
