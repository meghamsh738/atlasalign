package org.atlasalign.plugin.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.io.RoiDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;
import org.atlasalign.application.roi.ReviewerRoi;
import org.atlasalign.application.roi.ReviewerRoiPart;
import org.atlasalign.application.roi.ReviewerRoiSide;
import org.atlasalign.application.roi.ReviewerRoiVertex;
import org.atlasalign.application.roi.RoiPartOperation;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualRoiExportServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void stableTokensSurviveReorderingAndDuplicateIdentityFailsBeforeWriting() throws Exception {
        final var first = roi("DG left", List.of(point(1, 1), point(2, 1), point(2, 2)));
        final var second = new ReviewerRoi("other", "dg_left", first.side(),
                first.guideLink(), first.parts(), true, true);
        final var forward = ManualRoiExportService.roiTokens(List.of(first, second));
        final var reverse = ManualRoiExportService.roiTokens(List.of(second, first));
        assertEquals(forward.get(0), reverse.get(1));
        assertEquals(forward.get(1), reverse.get(0));
        final var source = ExportTestFixtures.sourceSnapshot(8, 2, 2, 2);
        final var service = new ManualRoiExportService(
                new ExportTestFixtures.MemoryReader(source), source, 1, 1, 1);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.export(temporaryDirectory, "mock.tif", "Section 1",
                        List.of(first, first), false, () -> false, (s, f) -> { }));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.export(temporaryDirectory, "mock.tif", "..",
                        List.of(first), false, () -> false, (s, f) -> { }));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.export(temporaryDirectory, "x".repeat(200) + ".tif", "Section 1",
                        List.of(first), false, () -> false, (s, f) -> { }));
        try (var files = Files.list(temporaryDirectory)) { assertEquals(0, files.count()); }
    }

    @Test
    void punctuationEquivalentNamesExportDistinctArtifactsAndRois() throws Exception {
        final var source = ExportTestFixtures.sourceSnapshot(8, 2, 2, 2);
        final var reader = new ExportTestFixtures.MemoryReader(source);
        final var service = new ManualRoiExportService(reader, source, 1, 1, 1);
        final var first = roi("DG left", List.of(point(1, 1), point(2, 1),
                point(2, 2), point(1, 2)));
        final var originalSecond = roi("DG_left", List.of(point(4, 3), point(5, 3),
                point(5, 4), point(4, 4)));
        final var second = new ReviewerRoi("different-id", originalSecond.name(),
                originalSecond.side(), originalSecond.guideLink(),
                originalSecond.parts(), true, true);
        final var result = service.export(temporaryDirectory, "mock.tif", "Section 1",
                List.of(first, second), true, () -> false, (stage, fraction) -> { });
        assertEquals(result.fileNames().size(), new java.util.HashSet<>(result.fileNames()).size());
        assertEquals(2, result.fileNames().stream().filter(n -> n.endsWith("__mask.ome.tif")).count());
        try (ZipFile zip = new ZipFile(result.publishedDirectory().resolve(
                "mock__manual-rois-source-coordinates.zip").toFile())) {
            assertEquals(2, zip.size());
            final var entries = zip.entries();
            final var one = entries.nextElement();
            final var two = entries.nextElement();
            try (var a = zip.getInputStream(one); var b = zip.getInputStream(two)) {
                final var ra = new RoiDecoder(a.readAllBytes(), one.getName()).getRoi();
                final var rb = new RoiDecoder(b.readAllBytes(), two.getName()).getRoi();
                assertEquals("DG left", ra.getName());
                assertEquals("DG_left", rb.getName());
                assertTrue(ra.contains(1, 1));
                assertFalse(ra.contains(4, 3));
                assertTrue(rb.contains(4, 3));
                assertFalse(rb.contains(1, 1));
            }
        }
        assertEquals(source, reader.snapshot());
    }

    @Test
    void exportedMaskAndFijiRoiMatchTheDrawnPolygonExactly()
            throws Exception {
        final var source = ExportTestFixtures.sourceSnapshot(8, 2, 2, 2);
        final var reader = new ExportTestFixtures.MemoryReader(source);
        final var service = new ManualRoiExportService(
                reader, source, 1, 1, 1);
        final ReviewerRoi roi = roi("DG left", List.of(
                point(1, 1), point(5, 1),
                point(5, 4), point(1, 4)));

        final ManualRoiExportService.Result result = service.export(
                temporaryDirectory, "mock image.tif", "Section 1",
                List.of(roi), false, () -> false,
                (stage, fraction) -> { });

        assertTrue(Files.isDirectory(result.publishedDirectory()));
        final Path manifest = result.publishedDirectory().resolve(
                "mock_image__manual-roi-export.json");
        final var json = new ObjectMapper().readTree(manifest.toFile());
        assertEquals(ManualRoiExportService.SCHEMA,
                json.path("schema").asText());
        assertEquals(20, json.path("sections").get(0)
                .path("rois").get(0).path("pixelCount").asInt());
        assertEquals("mock image.tif",
                json.path("source").path("name").asText());

        final Path zipPath = result.publishedDirectory().resolve(
                "mock_image__manual-rois-source-coordinates.zip");
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            final var entry = zip.entries().nextElement();
            final byte[] encoded;
            try (var input = zip.getInputStream(entry)) {
                encoded = input.readAllBytes();
            }
            final var decoded = new RoiDecoder(encoded,
                    entry.getName()).getRoi();
            assertTrue(decoded.contains(1, 1));
            assertTrue(decoded.contains(5, 4));
            assertFalse(decoded.contains(0, 0));
            assertFalse(decoded.contains(6, 5));
        }
        assertEquals(source, reader.snapshot());
    }

    @Test
    void batchSectionAlsoWritesWholeSlideCoordinateRoi() throws Exception {
        final var source = ExportTestFixtures.sourceSnapshot(8, 2, 2, 2);
        final var reader = new ExportTestFixtures.MemoryReader(source);
        final var parent = new ManualRoiExportService.ParentSourceContext(
                "whole slide.tif", "a".repeat(64), 100, 90, 20, 30);
        final var service = new ManualRoiExportService(reader, source,
                1, 1, 1, Optional.of(parent));
        final ReviewerRoi roi = roi("DG left", List.of(
                point(1, 1), point(5, 1),
                point(5, 4), point(1, 4)));

        final var result = service.export(temporaryDirectory,
                "section crop.tif", "Section 2", List.of(roi), false,
                () -> false, (stage, fraction) -> { });

        final Path zipPath = result.publishedDirectory().resolve(
                "whole_slide__manual-rois-whole-slide-coordinates.zip");
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            final var entry = zip.entries().nextElement();
            final byte[] encoded;
            try (var input = zip.getInputStream(entry)) {
                encoded = input.readAllBytes();
            }
            final var decoded = new RoiDecoder(encoded,
                    entry.getName()).getRoi();
            assertTrue(decoded.contains(21, 31));
            assertTrue(decoded.contains(25, 34));
            assertFalse(decoded.contains(1, 1));
        }
        final Path manifest = result.publishedDirectory().resolve(
                "section_crop__manual-roi-export.json");
        final var json = new ObjectMapper().readTree(manifest.toFile());
        assertEquals(20, json.path("wholeSlideSource")
                .path("sectionOffsetX").asInt());
        assertEquals(21, json.path("sections").get(0).path("rois")
                .get(0).path("wholeSlideBounds")
                .path("minimumX").asInt());
        assertEquals(source, reader.snapshot());
    }

    private static ReviewerRoi roi(final String name,
            final List<Point2D> points) {
        final ReviewerRoiPart part = new ReviewerRoiPart(
                "part", RoiPartOperation.ADD,
                java.util.stream.IntStream.range(0, points.size())
                        .mapToObj(index -> new ReviewerRoiVertex(
                                "v" + index, points.get(index)))
                        .toList(), true);
        return new ReviewerRoi("roi", name, ReviewerRoiSide.LEFT,
                Optional.empty(), List.of(part), true, true);
    }

    private static Point2D point(final double x, final double y) {
        return new Point2D(x, y);
    }
}
