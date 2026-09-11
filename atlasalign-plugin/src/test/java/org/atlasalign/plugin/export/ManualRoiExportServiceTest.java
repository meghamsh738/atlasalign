package org.atlasalign.plugin.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.io.RoiDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipFile;
import loci.formats.MetadataTools;
import loci.formats.in.OMETiffReader;
import org.atlasalign.application.export.ExportSelection;
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
        assertEquals("legacy-all-czt", json.path("scope").asText());
        assertTrue(json.path("exportSelection").isNull());
        try (OMETiffReader tif = new OMETiffReader()) {
            tif.setId(artifact(result, "__source-crop.ome.tif").toString());
            assertEquals(2, tif.getSizeC());
            assertEquals(2, tif.getSizeZ());
            assertEquals(2, tif.getSizeT());
            assertEquals(8, tif.getImageCount());
        }

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
            assertEquals(0, decoded.getCPosition());
            assertEquals(0, decoded.getZPosition());
            assertEquals(0, decoded.getTPosition());
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

    @Test
    void selectedChannelsUsePinnedPlaneThroughoutCropsRoisAndManifest() throws Exception {
        final var source = ExportTestFixtures.sourceSnapshot(16, 3, 2, 2);
        final var reader = new ExportTestFixtures.MemoryReader(source);
        // The registration channel remains C2 while the export contains C1+C3.
        final var service = new ManualRoiExportService(reader, source, 2, 2, 2);
        final var selection = new ExportSelection(List.of(1, 3), 2, 2);
        final var triangle = roi("DG triangle", List.of(
                point(1, 1), point(5, 1), point(1, 4)));
        final var result = service.export(temporaryDirectory, "selected.tif", "Section 3",
                List.of(triangle), false, selection, () -> false, (stage, fraction) -> { });

        for (final boolean masked : new boolean[]{false, true}) {
            final Path crop = artifact(result, masked ? "__masked.ome.tif" : "__source-crop.ome.tif");
            final var metadata = MetadataTools.createOMEXMLMetadata();
            try (OMETiffReader tif = new OMETiffReader()) {
                tif.setMetadataStore(metadata);
                tif.setId(crop.toString());
                assertEquals(5, tif.getSizeX());
                assertEquals(4, tif.getSizeY());
                assertEquals(2, tif.getSizeC());
                assertEquals(1, tif.getSizeZ());
                assertEquals(1, tif.getSizeT());
                assertEquals(16, tif.getBitsPerPixel());
                assertEquals(2, tif.getImageCount());
                assertEquals("Channel 1", metadata.getChannelName(0, 0));
                assertEquals("Channel 3", metadata.getChannelName(0, 1));
                final var mapping = metadata.getMapAnnotationValue(0).stream()
                        .collect(java.util.stream.Collectors.toMap(pair -> pair.getName(), pair -> pair.getValue()));
                assertEquals("1,3", mapping.get("atlasalign.sourceChannels"));
                assertEquals("2", mapping.get("atlasalign.sourceSlice"));
                assertEquals("2", mapping.get("atlasalign.sourceFrame"));
                assertEquals("1", mapping.get("atlasalign.sourceOriginX"));
                assertEquals("1", mapping.get("atlasalign.sourceOriginY"));
                for (int outputPlane = 0; outputPlane < 2; outputPlane++) {
                    final ByteBuffer pixels = ByteBuffer.wrap(tif.openBytes(outputPlane))
                            .order(tif.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                    final int sourcePlane = 9 + 2 * outputPlane;
                    for (int y = 1; y <= 4; y++) {
                        for (int x = 1; x <= 5; x++) {
                            final boolean included = 3 * (x - 1) + 4 * (y - 1) <= 12;
                            final int expected = masked && !included ? 0
                                    : 40_000 + sourcePlane * 97 + y * 8 + x;
                            assertEquals(expected, pixels.getShort() & 0xffff);
                        }
                    }
                }
            }
        }
        try (OMETiffReader mask = new OMETiffReader()) {
            mask.setId(artifact(result, "__mask.ome.tif").toString());
            assertEquals(1, mask.getImageCount());
            final byte[] values = mask.openBytes(0);
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 5; x++) {
                    assertEquals(3 * x + 4 * y <= 12 ? 255 : 0, values[y * 5 + x] & 0xff);
                }
            }
        }
        for (final boolean cropLocal : new boolean[]{false, true}) {
            final var decoded = readSingleRoi(cropLocal
                    ? artifact(result, "__crop-local-roi.zip")
                    : result.publishedDirectory().resolve("selected__manual-rois-source-coordinates.zip"));
            assertEquals(0, decoded.getCPosition());
            assertEquals(cropLocal ? 1 : 2, decoded.getZPosition());
            assertEquals(cropLocal ? 1 : 2, decoded.getTPosition());
            assertEquals(new java.awt.Rectangle(cropLocal ? 0 : 1, cropLocal ? 0 : 1, 5, 4),
                    decoded.getBounds());
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 5; x++) {
                    assertEquals(3 * x + 4 * y <= 12,
                            decoded.contains(x + (cropLocal ? 0 : 1), y + (cropLocal ? 0 : 1)));
                }
            }
        }
        final var json = new ObjectMapper().readTree(result.publishedDirectory()
                .resolve("selected__manual-roi-export.json").toFile());
        assertEquals("selected-channels-pinned-plane", json.path("scope").asText());
        assertEquals(3, json.path("source").path("channels").asInt());
        assertEquals(2, json.path("source").path("slices").asInt());
        assertEquals(2, json.path("source").path("frames").asInt());
        final var scope = json.path("exportSelection");
        assertEquals(List.of(1, 3), new ObjectMapper().convertValue(
                scope.path("sourceChannelsOneBased"),
                new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() { }));
        assertEquals(2, scope.path("sourceSliceOneBased").asInt());
        assertEquals(2, scope.path("sourceFrameOneBased").asInt());
        assertEquals(2, scope.path("outputSizeC").asInt());
        assertEquals(1, scope.path("outputSizeZ").asInt());
        assertEquals(1, scope.path("outputSizeT").asInt());
        assertEquals(11, json.path("sections").get(0).path("rois").get(0).path("pixelCount").asInt());
        assertEquals(source, reader.snapshot());
    }

    @Test
    void selectedExportRejectsDifferentSourcePlaneBeforeWriting() throws Exception {
        final var source = ExportTestFixtures.sourceSnapshot(8, 3, 2, 2);
        final var reader = new ExportTestFixtures.MemoryReader(source);
        final var service = new ManualRoiExportService(reader, source, 2, 2, 2);
        final var triangle = roi("triangle", List.of(point(1, 1), point(5, 1), point(1, 4)));
        for (final var selection : List.of(new ExportSelection(List.of(1, 3), 1, 2),
                new ExportSelection(List.of(1, 3), 2, 1))) {
            final var error = assertThrows(IllegalArgumentException.class,
                    () -> service.export(temporaryDirectory, "invalid.tif", "Section 1",
                            List.of(triangle), false, selection, () -> false, (stage, fraction) -> { }));
            assertTrue(error.getMessage().contains("pinned registration Z/T"));
        }
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(0, files.count());
        }
        assertEquals(source, reader.snapshot());
    }

    private static Path artifact(final ManualRoiExportService.Result result, final String suffix) {
        final var matching = result.fileNames().stream().filter(name -> name.endsWith(suffix)).toList();
        assertEquals(1, matching.size(), "Expected exactly one " + suffix);
        return result.publishedDirectory().resolve(matching.get(0));
    }

    private static ij.gui.Roi readSingleRoi(final Path path) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            assertEquals(1, zip.size());
            final var entry = zip.entries().nextElement();
            try (var input = zip.getInputStream(entry)) {
                return new RoiDecoder(input.readAllBytes(), entry.getName()).getRoi();
            }
        }
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
