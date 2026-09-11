package org.atlasalign.plugin.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.atlasalign.plugin.project.ReviewProjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchManualRoiExporterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void legacyMultichannelImageWithoutRecordedRegistrationScopeRequiresExplicitPinning() {
        final ImagePlus image = BatchCheckpointFixtures.image(true);
        image.setDimensions(4, 1, 1);
        final var project = new BatchProjectSession("channels-only", temporaryDirectory.resolve("channels-project"),
                new BatchInputFactory().openImages(List.of(image), 240, 0));
        saveOneRoi(project, project.selected());
        final var failure = assertThrows(IllegalStateException.class, () -> new BatchManualRoiExporter().export(
                temporaryDirectory, project, 1, () -> false, (message, fraction) -> { }));
        assertTrue(failure.getMessage().contains("no recorded C/Z/T scope"));
        assertEquals(BatchSectionProgress.ExportState.NOT_EXPORTED, project.selected().progress().exportState());
    }

    @Test
    void fullCheckpointTakesPrecedenceOverLegacyRoisAndExportsItsPinnedChannelAndPlane() throws Exception {
        final ImagePlus image = BatchCheckpointFixtures.image(true);
        final var originalSource = new ImagePlusSourceImage(image).snapshot();
        final var project = new BatchProjectSession("full-checkpoint", temporaryDirectory.resolve("full-project"),
                new BatchInputFactory().openImages(List.of(image), 240, 0));
        final var item = project.selected();
        final var saved = BatchCheckpointFixtures.review(item, BatchCheckpointFixtures.rois(item.section(), "Full checkpoint ROI").snapshot());
        final var checkpoint = project.defaultCheckpoint(item.section());
        new ReviewProjectStore().save(checkpoint, saved);
        final byte[] originalCheckpoint = Files.readAllBytes(checkpoint);
        project.reviewProgress(item.section().id(), saved.alignment().contentRevision(), false,
                saved.rois(), saved.exportSelection(), checkpoint, "SAVED");
        saveOneRoi(project, item); // A conflicting legacy draft must never replace the full checkpoint's ROI/scope.
        final var verifiedAtlas = new java.util.concurrent.atomic.AtomicInteger();
        final var result = new BatchManualRoiExporter(new BatchReviewCheckpoint(path -> {
            verifiedAtlas.incrementAndGet(); return saved.alignment().basis().atlas();
        })).export(temporaryDirectory, project, 1, () -> false, (message, fraction) -> { });
        assertEquals(1, verifiedAtlas.get());
        final var index = new ObjectMapper().readTree(result.publishedDirectory().resolve("batch-export-index.json").toFile());
        final var row = index.path("exports").get(0);
        assertEquals(2, row.path("exportSelection").path("channels").get(0).asInt());
        assertEquals(2, row.path("exportSelection").path("slice").asInt());
        assertEquals(checkpoint.toString(), row.path("reviewCheckpoint").asText());
        try (var paths = Files.walk(result.publishedDirectory())) {
            final Path zip = paths.filter(path -> path.getFileName().toString().endsWith("__manual-rois-section-coordinates.zip"))
                    .findFirst().orElseThrow();
            assertTrue(decodeFirst(zip).getName().contains("Full checkpoint ROI"));
        }
        try (var paths = Files.walk(result.publishedDirectory())) {
            final Path crop = paths.filter(path -> path.getFileName().toString().endsWith("__source-crop.ome.tif"))
                    .findFirst().orElseThrow();
            final var reader = new loci.formats.ImageReader();
            try {
                reader.setId(crop.toString());
                assertEquals(1, reader.getSizeC()); assertEquals(1, reader.getSizeZ()); assertEquals(1, reader.getSizeT());
                final byte[] pixels = reader.openBytes(0);
                final var sourcePlane = image.getStack().getProcessor(image.getStackIndex(2, 2, 1));
                for (int y = 0; y < reader.getSizeY(); y++) for (int x = 0; x < reader.getSizeX(); x++) {
                    assertEquals(sourcePlane.get(x + 1, y + 1), Byte.toUnsignedInt(pixels[y * reader.getSizeX() + x]));
                }
            } finally { reader.close(); }
        }
        assertEquals(BatchSectionProgress.ExportState.CURRENT, project.selected().progress().exportState());
        assertEquals(BatchReviewStatus.PENDING, project.selected().section().status());
        assertFalse(project.selected().progress().accepted());
        assertEquals(originalSource, new ImagePlusSourceImage(image).snapshot());
        org.junit.jupiter.api.Assertions.assertArrayEquals(originalCheckpoint, Files.readAllBytes(checkpoint));
    }

    @Test
    void missingCheckpointCannotFallBackToLegacyAndUnscopedMultidimensionalLegacyCannotExport() throws Exception {
        final ImagePlus image = BatchCheckpointFixtures.image(true);
        final var project = new BatchProjectSession("legacy", temporaryDirectory.resolve("legacy-project"),
                new BatchInputFactory().openImages(List.of(image), 240, 0));
        final var item = project.selected();
        saveOneRoi(project, item);
        assertThrows(IllegalStateException.class, () -> new BatchManualRoiExporter().export(
                temporaryDirectory, project, 1, () -> false, (message, fraction) -> { }));
        final var saved = BatchCheckpointFixtures.review(item, BatchCheckpointFixtures.rois(item.section(), "saved").snapshot());
        project.reviewProgress(item.section().id(), 1, false, saved.rois(), saved.exportSelection(),
                project.defaultCheckpoint(item.section()), "SAVED");
        assertThrows(IllegalStateException.class, () -> new BatchManualRoiExporter().export(
                temporaryDirectory, project, 1, () -> false, (message, fraction) -> { }));
        assertEquals(BatchSectionProgress.ExportState.NOT_EXPORTED, project.selected().progress().exportState());
        try (var children = Files.list(temporaryDirectory)) {
            assertEquals(List.of(project.projectDirectory()), children.toList(), "Failed exports must not publish or retain temporary directories");
        }
    }

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
        assertTrue(project.items().stream().allMatch(item -> item.section().status() == BatchReviewStatus.PENDING));
        assertTrue(project.items().stream().allMatch(item -> item.progress().exportState() == BatchSectionProgress.ExportState.CURRENT));
        assertTrue(project.items().stream().noneMatch(item -> item.progress().accepted()));
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
