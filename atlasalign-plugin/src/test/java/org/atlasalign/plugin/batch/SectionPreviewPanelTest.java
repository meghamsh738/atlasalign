package org.atlasalign.plugin.batch;

import static org.junit.jupiter.api.Assertions.*;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.junit.jupiter.api.Test;

class SectionPreviewPanelTest {
    @Test void cropUsesSourcePixelCentersAndPreservesSourceAndDisplayState() {
        final var pixels = new ByteProcessor(1000, 600);
        pixels.set(201, 101, 255);
        final var image = new ImagePlus("source", pixels);
        image.setRoi(new Roi(1, 2, 3, 4));
        final var roi = image.getRoi();
        image.setDisplayRange(10, 200);
        final var before = new ImagePlusSourceImage(image).snapshot();
        final var item = new BatchInputFactory().markedSections(image,
                List.of(new Roi(200, 100, 640, 400)), 264, 0).get(0);
        final var result = SectionPreviewPanel.render(item, 1, 1, 1, () -> false);
        assertEquals(320, result.getWidth());
        assertEquals(200, result.getHeight());
        assertEquals(0xffffff, result.getRGB(0, 0) & 0xffffff);
        assertEquals(before, new ImagePlusSourceImage(image).snapshot());
        assertSame(roi, image.getRoi());
        assertEquals(10, image.getDisplayRangeMin());
        assertEquals(200, image.getDisplayRangeMax());
        result.setRGB(0, 0, 0);
        assertEquals(255, pixels.get(201, 101));
    }

    @Test void rapidSelectionPublishesOnlyLatestPreview() throws Exception {
        final var pixels = new ByteProcessor(40, 20);
        for (int y = 0; y < 20; y++) for (int x = 0; x < 40; x++) {
            pixels.set(x, y, x < 20 ? x : 39 - x);
        }
        final var image = new ImagePlus("source", pixels);
        final var before = new ImagePlusSourceImage(image).snapshot();
        final var items = new BatchInputFactory().markedSections(image,
                List.of(new Roi(0, 0, 20, 20), new Roi(20, 0, 20, 20)), 264, 0);
        final var panel = new SectionPreviewPanel();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            panel.showSection(items.get(0), 1);
            panel.showSection(items.get(1), 1);
        });
        final var imageField = SectionPreviewPanel.class.getDeclaredField("image");
        imageField.setAccessible(true);
        final var published = new java.util.concurrent.atomic.AtomicReference<java.awt.image.BufferedImage>();
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (published.get() == null && System.nanoTime() < deadline) {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                try { published.set((java.awt.image.BufferedImage) imageField.get(panel)); }
                catch (IllegalAccessException error) { throw new AssertionError(error); }
            });
            Thread.sleep(20);
        }
        assertNotNull(published.get());
        assertEquals(0xffffff, published.get().getRGB(0, 0) & 0xffffff);
        assertEquals(before, new ImagePlusSourceImage(image).snapshot());
    }

    @Test void sourceChangeAndCancellationFailClosed() {
        final var image = new ImagePlus("source", new ByteProcessor(20, 20));
        final var item = new BatchInputFactory().markedSections(image,
                List.of(new Roi(0, 0, 20, 20)), 264, 0).get(0);
        assertThrows(CancellationException.class,
                () -> SectionPreviewPanel.render(item, 1, 1, 1, () -> true));
        image.getProcessor().set(0, 0, 123);
        assertThrows(IllegalStateException.class,
                () -> SectionPreviewPanel.render(item, 1, 1, 1, () -> false));
    }
}
