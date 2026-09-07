package org.atlasalign.plugin.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.ImagePlus;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.process.ByteProcessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.application.roi.ReviewerRoiSide;
import org.atlasalign.application.roi.RoiPartOperation;
import org.atlasalign.core.Point2D;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.plugin.review.ManualRoiSessionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchManualRoiExporterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void unsupportedAtomicPublicationDoesNotFallBackToAnOrdinaryMove() throws Exception {
        final Path staged = Files.createDirectory(temporaryDirectory.resolve("staged"));
        final Path zipPath = temporaryDirectory.resolve("other-provider.zip");
        try (var zip = java.nio.file.FileSystems.newFileSystem(
                java.net.URI.create("jar:" + zipPath.toUri()), java.util.Map.of("create", "true"))) {
            final Path destination = zip.getPath("/published");
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                    () -> BatchManualRoiExporter.publish(staged, destination));
            assertTrue(Files.isDirectory(staged));
            org.junit.jupiter.api.Assertions.assertFalse(Files.exists(destination));
        }
    }

    @Test
    void publishesAllAutosavedPolygonsInSectionAndWholeSlideCoordinates()
            throws Exception {
        final ImagePlus slide = slide();
        final var original = new ImagePlusSourceImage(slide).snapshot();
        final List<BatchReviewItem> items = new BatchInputFactory()
                .markedSections(slide, List.of(
                        rectangle("Section A", 5, 7, 20, 18),
                        rectangle("Section B", 40, 25, 24, 20)),
                        250, 2);
        final BatchProjectSession project = new BatchProjectSession(
                "two-sections", temporaryDirectory.resolve("project"),
                items);
        for (final BatchReviewItem item : items) {
            saveOneRoi(project, item);
        }

        final BatchManualRoiExporter.Result result =
                new BatchManualRoiExporter().export(temporaryDirectory,
                        project, 1, () -> false,
                        (message, fraction) -> { });

        assertEquals(2, result.exportedSections());
        assertEquals(2, result.exportedRois());
        assertTrue(result.skippedSections().isEmpty());
        assertTrue(Files.isRegularFile(result.publishedDirectory().resolve(
                "batch-export-index.json")));
        final var index = new ObjectMapper().readTree(
                result.publishedDirectory().resolve(
                        "batch-export-index.json").toFile());
        assertEquals("atlasalign-manual-roi-batch-index-v1",
                index.path("schema").asText());
        assertEquals(2, index.withArray("exports").size());

        final List<Roi> wholeSlideRois = new ArrayList<>();
        try (var paths = Files.walk(result.publishedDirectory())) {
            for (final Path zip : paths.filter(path -> path.getFileName()
                    .toString().endsWith(
                            "__manual-rois-whole-slide-coordinates.zip"))
                    .toList()) {
                wholeSlideRois.add(decodeFirst(zip));
            }
        }
        assertEquals(2, wholeSlideRois.size());
        assertTrue(wholeSlideRois.stream().anyMatch(roi ->
                roi.contains(6, 8) && roi.contains(10, 12)));
        assertTrue(wholeSlideRois.stream().anyMatch(roi ->
                roi.contains(41, 26) && roi.contains(45, 30)));
        assertTrue(project.items().stream().allMatch(item ->
                item.section().status() == BatchReviewStatus.COMPLETE));
        assertEquals(original, new ImagePlusSourceImage(slide).snapshot());
    }

    private static void saveOneRoi(
            final BatchProjectSession project,
            final BatchReviewItem item) {
        final ImagePlus crop = new SectionImageExtractor().extract(item);
        final String hash = new ImagePlusSourceImage(crop).snapshot()
                .pixelSha256();
        final ReviewerRoiSession session = new ReviewerRoiSession(
                item.section().id(), crop.getWidth(), crop.getHeight());
        session.newPolygon("DG left", ReviewerRoiSide.LEFT,
                RoiPartOperation.ADD);
        session.addVertex(new Point2D(1, 1));
        session.addVertex(new Point2D(6, 1));
        session.addVertex(new Point2D(6, 6));
        session.addVertex(new Point2D(1, 6));
        session.finishActivePart();
        new ManualRoiSessionStore(
                BatchManualRoiExporter.draftFile(project, item.section()),
                item.section().id(), crop.getWidth(), crop.getHeight(), hash)
                .saveNow(session.snapshot());
    }

    private static Roi decodeFirst(final Path zipPath) throws Exception {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            final var entry = zip.entries().nextElement();
            final byte[] encoded;
            try (var input = zip.getInputStream(entry)) {
                encoded = input.readAllBytes();
            }
            return new RoiDecoder(encoded, entry.getName()).getRoi();
        }
    }

    private static ImagePlus slide() {
        final ByteProcessor pixels = new ByteProcessor(80, 60);
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = 0; x < pixels.getWidth(); x++) {
                pixels.set(x, y, (x * 3 + y * 5) & 0xff);
            }
        }
        return new ImagePlus("whole slide.tif", pixels);
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
