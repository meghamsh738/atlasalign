package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.*;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.atlasalign.application.AlignmentReviewSession;
import org.atlasalign.application.RegistrationInput;
import org.atlasalign.application.ReviewAcceptanceVerification;
import org.atlasalign.application.ReviewEdit;
import org.atlasalign.application.roi.ManualRoiFootprint;
import org.atlasalign.application.roi.ReviewerRoi;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.application.roi.ReviewerRoiSide;
import org.atlasalign.application.roi.RoiPartOperation;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.plugin.export.ExportRegionSelection;
import org.atlasalign.plugin.export.SourceSpaceFootprintProjector;
import org.junit.jupiter.api.Test;

class ExportPreviewDialogTest {
    private static final int EDGE = 0x33ff99;
    private static final RegistrationInput INPUT = new RegistrationInput(2, 2, 1);

    @Test
    void manualPreviewPreservesNativeHolesMultipartRegionsAndSourcePixels() {
        final var image = source(20, 18);
        final var adapter = new ImagePlusSourceImage(image);
        final var before = adapter.snapshot();
        final var preview = ReviewPreview.copyOf(adapter.createPreview(INPUT, 64));
        final float[] previewBefore = preview.pixels();
        final var rois = rois(20, 18);
        final var footprint = ManualRoiFootprint.rasterize(rois.get(0), 20, 18);
        final var images = ExportPreviewDialog.manual(preview, INPUT, before.metadata(), rois);

        assertEquals(20, images.source().getWidth()); assertEquals(18, images.source().getHeight());
        assertEquals("Source C2 · Z2 · T1", images.sourceLabel());
        assertSynchronized(images, preview, new PreviewMapping(20, 18, 20, 18), footprint::containsSourcePixel, image);
        assertEquals(0, rgb(images.mask(), 6, 7), "The subtractive hole must remain empty");
        assertEquals(0xffffff, rgb(images.mask(), 16, 7), "The second ADD component must remain included");
        assertEquals(footprint.pixelCount(), whiteCount(images.mask()));
        assertEquals(footprint.bounds().minimumX(), firstWhiteX(images.mask()));
        assertEquals(footprint.bounds().minimumY(), firstWhiteY(images.mask()));
        assertEquals(before, adapter.snapshot());
        assertArrayEquals(previewBefore, preview.pixels());
        assertEquals(1, image.getC()); assertEquals(1, image.getZ()); assertEquals(1, image.getT());
    }

    @Test
    void overlappingManualRoisUnionIndividuallySoOneRoiCanFillAnotherRoisHole() {
        final var image = source(40, 36);
        final var adapter = new ImagePlusSourceImage(image);
        final var preview = ReviewPreview.copyOf(adapter.createPreview(INPUT, 64));
        final var original = rois(40, 36).get(0);
        final var second = new ReviewerRoiSession("other", 40, 36);
        second.newPolygon("Fill part of hole", ReviewerRoiSide.LEFT, RoiPartOperation.ADD);
        rectangle(second, 11, 12, 15, 17);
        second.finishActivePart();
        final List<ReviewerRoi> selected = List.of(original, second.snapshot().rois().get(0));
        final var exact = selected.stream().map(roi -> ManualRoiFootprint.rasterize(roi, 40, 36)).toList();
        final var images = ExportPreviewDialog.manual(preview, INPUT, adapter.snapshot().metadata(), selected);
        assertFalse(exact.get(0).containsSourcePixel(13, 14));
        assertTrue(exact.get(1).containsSourcePixel(13, 14));
        assertEquals(0xffffff, rgb(images.mask(), 13, 14));
        assertSynchronized(images, preview, new PreviewMapping(40, 36, 40, 36),
                (x, y) -> exact.stream().anyMatch(footprint -> footprint.containsSourcePixel(x, y)), image);
    }

    @Test
    void reducedPortraitAndLandscapePreviewsSampleTheSameNativeCentersForImageOutlineAndMask() {
        for (final int[] size : List.of(new int[]{1001, 703}, new int[]{703, 1001})) {
            final var image = source(size[0], size[1]);
            final var adapter = new ImagePlusSourceImage(image);
            final var before = adapter.snapshot();
            final var registration = adapter.createPreview(INPUT, 500);
            final var preview = ReviewPreview.copyOf(registration);
            final var selected = rois(size[0], size[1]);
            final var footprint = ManualRoiFootprint.rasterize(selected.get(0), size[0], size[1]);
            final var images = ExportPreviewDialog.manual(preview, INPUT, before.metadata(), selected);
            assertEquals(230, Math.max(images.source().getWidth(), images.source().getHeight()));
            assertTrue(images.source().getWidth() > 0 && images.source().getHeight() > 0);
            assertSynchronized(images, preview, registration.mapping(), footprint::containsSourcePixel, image);
            assertEquals(before, adapter.snapshot());
        }
    }

    @Test
    void atlasPreviewMaskMatchesTheActualClippedNativeExportUnion() {
        final var basis = ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final var session = new AlignmentReviewSession(basis);
        session.apply(new ReviewEdit.SetTissueClipping(true));
        final var accepted = session.accept(() -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()), true);
        final var plane = ReviewPluginFixtures.plane(accepted.coronalLevel().zeroBasedAnteriorPosteriorIndex());
        final var regions = List.of(new ExportRegionSelection(1, "L", "Left", false, Set.of(1)),
                new ExportRegionSelection(2, "R", "Right", false, Set.of(2)));
        final var projector = new SourceSpaceFootprintProjector();
        final var footprints = projector.project(accepted, plane, regions, () -> false, ignored -> { });
        final var union = projector.union(footprints);
        final var preview = ReviewPluginFixtures.segmentedPreview();
        final var images = ExportPreviewDialog.atlas(preview, new RegistrationInput(1, 1, 1), accepted, plane, regions);
        assertTrue(accepted.tissueClippingEnabled());
        assertSynchronized(images, preview, accepted.previewMapping(), union::containsSourcePixel, null);
        assertEquals(union.pixelCount(), whiteCount(images.mask()));
        assertEquals(union.bounds().minimumX(), firstWhiteX(images.mask()));
        assertEquals(union.bounds().minimumY(), firstWhiteY(images.mask()));
    }

    @Test
    void cancelledPreviewStopsWithoutChangingItsSourceOrPreviewBuffers() {
        final var image = source(20, 18);
        final var adapter = new ImagePlusSourceImage(image);
        final var before = adapter.snapshot();
        final var preview = ReviewPreview.copyOf(adapter.createPreview(INPUT, 64));
        final float[] pixels = preview.pixels();
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,
                    () -> ExportPreviewDialog.manual(preview, INPUT, before.metadata(), rois(20, 18)));
        } finally { Thread.interrupted(); }
        assertEquals(before, adapter.snapshot());
        assertArrayEquals(pixels, preview.pixels());
    }

    private static void assertSynchronized(final ExportPreviewDialog.Images images, final ReviewPreview preview,
            final PreviewMapping mapping, final SourceSpaceFootprintProjector.PixelMembership actualExport,
            final ImagePlus actualSource) {
        final int width = images.source().getWidth(), height = images.source().getHeight();
        assertEquals(width, images.outline().getWidth()); assertEquals(width, images.mask().getWidth());
        assertEquals(height, images.outline().getHeight()); assertEquals(height, images.mask().getHeight());
        final float[] previewPixels = preview.pixels();
        final var window = preview.displayWindow();
        final var sourcePlane = actualSource == null ? null : actualSource.getStack()
                .getProcessor(actualSource.getStackIndex(INPUT.channel(), INPUT.slice(), INPUT.frame()));
        final boolean[][] expected = new boolean[height][width];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            final int px = (int) (((x + .5) * preview.width()) / width);
            final int py = (int) (((y + .5) * preview.height()) / height);
            final int sx = (int) Math.floor((px + .5) * mapping.sourceWidth() / mapping.previewWidth());
            final int sy = (int) Math.floor((py + .5) * mapping.sourceHeight() / mapping.previewHeight());
            assertTrue(sx >= 0 && sx < mapping.sourceWidth() && sy >= 0 && sy < mapping.sourceHeight());
            final float intensity = actualSource == null ? previewPixels[py * preview.width() + px]
                    : sourcePlane.get(sx, sy);
            assertEquals(intensity, previewPixels[py * preview.width() + px], 0,
                    "Registration sample must still be the original source value at " + sx + "," + sy);
            final int grey = (int) Math.max(0, Math.min(255, Math.round((intensity - window.lower()) / window.range() * 255)));
            assertEquals(grey * 0x010101, rgb(images.source(), x, y), "Source display at " + x + "," + y);
            expected[y][x] = actualExport.contains(sx, sy);
            assertEquals(expected[y][x] ? 0xffffff : 0, rgb(images.mask(), x, y), "Mask source correspondence at " + sx + "," + sy);
        }
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            final boolean edge = expected[y][x] && (x == 0 || y == 0 || x == width - 1 || y == height - 1
                    || !expected[y][x - 1] || !expected[y][x + 1] || !expected[y - 1][x] || !expected[y + 1][x]);
            assertEquals(edge ? EDGE : rgb(images.source(), x, y), rgb(images.outline(), x, y),
                    "Outline must trace the synchronized mask at " + x + "," + y);
        }
    }

    private static ImagePlus source(final int width, final int height) {
        final var stack = new ImageStack(width, height);
        for (int plane = 0; plane < 4; plane++) {
            final short[] pixels = new short[width * height];
            for (int index = 0; index < pixels.length; index++) pixels[index] = (short) ((plane * 5003 + index * 17) & 0xffff);
            stack.addSlice("original-plane-" + plane, new ShortProcessor(width, height, pixels, null));
        }
        final var image = new ImagePlus("original source", stack);
        image.setDimensions(2, 2, 1); image.setPosition(1, 1, 1);
        image.getCalibration().pixelWidth = .625; image.getCalibration().pixelHeight = .75;
        return image;
    }

    private static List<ReviewerRoi> rois(final int width, final int height) {
        final var session = new ReviewerRoiSession("section", width, height);
        session.newPolygon("Multipart with hole", ReviewerRoiSide.LEFT, RoiPartOperation.ADD);
        rectangle(session, width * .06, height * .06, width * .58, height * .92); session.finishActivePart();
        session.addPart(RoiPartOperation.SUBTRACT);
        rectangle(session, width * .2, height * .25, width * .45, height * .62); session.finishActivePart();
        session.addPart(RoiPartOperation.ADD);
        rectangle(session, width * .7, height * .2, width * .92, height * .8); session.finishActivePart();
        return session.snapshot().rois();
    }

    private static void rectangle(final ReviewerRoiSession session, final double x0, final double y0, final double x1, final double y1) {
        session.addVertices(List.of(new Point2D(x0, y0), new Point2D(x1, y0), new Point2D(x1, y1), new Point2D(x0, y1)));
    }

    private static int rgb(final BufferedImage image, final int x, final int y) { return image.getRGB(x, y) & 0xffffff; }
    private static int whiteCount(final BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if (rgb(image, x, y) == 0xffffff) count++;
        return count;
    }
    private static int firstWhiteX(final BufferedImage image) {
        for (int x = 0; x < image.getWidth(); x++) for (int y = 0; y < image.getHeight(); y++) if (rgb(image, x, y) == 0xffffff) return x;
        throw new AssertionError("Mask is empty");
    }
    private static int firstWhiteY(final BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if (rgb(image, x, y) == 0xffffff) return y;
        throw new AssertionError("Mask is empty");
    }
}
