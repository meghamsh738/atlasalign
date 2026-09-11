package org.atlasalign.plugin.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.io.RoiDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;
import loci.formats.MetadataTools;
import loci.formats.in.OMETiffReader;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.ReviewAcceptanceVerification;
import org.atlasalign.core.SourceImageSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceSpaceExportServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void publishesCheckedMultiregionAndUnionExportAtomically()
            throws Exception {
        final SourceImageSnapshot source = ExportTestFixtures.sourceSnapshot(
                8, 2, 2, 2);
        final var accepted = ExportTestFixtures.accepted(
                source, Optional.empty(), false);
        final var reader = new ExportTestFixtures.MemoryReader(source);
        final var service = new SourceSpaceExportService(
                reader,
                ignored -> ExportTestFixtures.annotationPlane(),
                () -> new ReviewAcceptanceVerification(
                        source, ExportTestFixtures.atlasProvenance()),
                () -> Optional.of(accepted));
        final List<ExportRegionSelection> selections = List.of(
                ExportTestFixtures.region(1, "LEFT"),
                ExportTestFixtures.region(2, "RIGHT"));

        final SourceSpaceExportService.Result result = service.export(
                temporaryDirectory, "sample.tif", accepted, selections,
                true, () -> false, (stage, progress) -> {
                });

        assertEquals("sample__atlasalign_exports",
                result.publishedDirectory().getFileName().toString());
        assertTrue(Files.isDirectory(result.publishedDirectory()));
        assertTrue(Files.exists(result.publishedDirectory().resolve(
                "sample__LEFT__crop.ome.tif")));
        assertTrue(Files.exists(result.publishedDirectory().resolve(
                "sample__RIGHT__mask.ome.tif")));
        assertTrue(Files.exists(result.publishedDirectory().resolve(
                "sample__LEFT+RIGHT__combined.ome.tif")));
        assertTrue(Files.exists(result.publishedDirectory().resolve(
                "sample__atlasalign-rois.zip")));
        try (ZipFile zip = new ZipFile(result.publishedDirectory().resolve(
                "sample__atlasalign-rois.zip").toFile())) {
            assertEquals(2, zip.size());
            final var entry = zip.entries().nextElement();
            try (var input = zip.getInputStream(entry)) {
                final var roi = new RoiDecoder(input.readAllBytes(), entry.getName()).getRoi();
                assertEquals(0, roi.getCPosition());
                assertEquals(0, roi.getZPosition());
                assertEquals(0, roi.getTPosition());
            }
        }
        try (OMETiffReader tif = new OMETiffReader()) {
            tif.setId(result.publishedDirectory().resolve("sample__LEFT__crop.ome.tif").toString());
            assertEquals(2, tif.getSizeC());
            assertEquals(2, tif.getSizeZ());
            assertEquals(2, tif.getSizeT());
            assertEquals(8, tif.getImageCount());
        }
        final Path manifest = result.publishedDirectory().resolve(
                "sample__atlasalign-export.json");
        final var json = new ObjectMapper().readTree(manifest.toFile());
        assertEquals("atlasalign-source-space-export-v1",
                json.path("schema").asText());
        assertEquals("legacy-all-czt", json.path("scope").asText());
        assertFalse(json.has("exportSelection"));
        assertEquals("MANUAL_REVIEW",
                json.path("acceptedAlignment").path("method").asText());
        assertEquals("VISIBLE_SIDE_ONLY", json.path("acceptedAlignment")
                .path("halfAtlasCoverage").asText());
        assertEquals(List.of("LEFT", "RIGHT"),
                new ObjectMapper().convertValue(
                        json.path("acceptedAlignment")
                                .path("includedAtlasSides"),
                        new com.fasterxml.jackson.core.type.TypeReference<
                                List<String>>() {
                        }));
        assertEquals(8, json.path("source").path("channels").asInt()
                * json.path("source").path("slices").asInt()
                * json.path("source").path("frames").asInt());
        assertEquals(7, json.path("outputs").size());
        assertEquals(source, reader.snapshot());
        try (var children = Files.list(temporaryDirectory)) {
            assertFalse(children.anyMatch(path -> path.getFileName()
                    .toString().contains(".tmp-")));
        }

        final SourceSpaceExportService.Result second = service.export(
                temporaryDirectory, "sample.tif", accepted, selections,
                false, () -> false, (stage, progress) -> {
                });
        assertEquals("sample__atlasalign_exports_002",
                second.publishedDirectory().getFileName().toString());
    }

    @Test
    void halfManifestRecordsTheExactAcceptedAtlasSideFootprint()
            throws Exception {
        final SourceImageSnapshot source = ExportTestFixtures.sourceSnapshot(
                8, 1, 1, 1);
        final var accepted = ExportTestFixtures
                .acceptedHalfWithJoinedPlacement(source);
        final var service = new SourceSpaceExportService(
                new ExportTestFixtures.MemoryReader(source),
                ignored -> ExportTestFixtures.annotationPlane(),
                () -> new ReviewAcceptanceVerification(
                        source, ExportTestFixtures.atlasProvenance()),
                () -> Optional.of(accepted));

        final SourceSpaceExportService.Result result = service.export(
                temporaryDirectory, "half.tif", accepted,
                List.of(ExportTestFixtures.region(1, "LEFT")),
                false, () -> false, (stage, progress) -> {
                });

        final var json = new ObjectMapper().readTree(
                result.publishedDirectory().resolve(
                        "half__atlasalign-export.json").toFile());
        assertEquals("HALF", json.path("acceptedAlignment")
                .path("sectionMode").asText());
        assertEquals("VISIBLE_SIDE_ONLY", json.path("acceptedAlignment")
                .path("halfAtlasCoverage").asText());
        assertEquals(1, json.path("acceptedAlignment")
                .path("includedAtlasSides").size());
        assertEquals("LEFT", json.path("acceptedAlignment")
                .path("includedAtlasSides").get(0).asText());
    }

    @Test
    void optionalMaskedAndFullSourceOutputsRemainSourceFaithfulAndExplicit()
            throws Exception {
        final SourceImageSnapshot source = ExportTestFixtures.sourceSnapshot(
                8, 2, 2, 2);
        final var accepted = ExportTestFixtures.accepted(
                source, Optional.empty(), false);
        final var reader = new ExportTestFixtures.MemoryReader(source);
        final var service = new SourceSpaceExportService(
                reader,
                ignored -> ExportTestFixtures.annotationPlane(),
                () -> new ReviewAcceptanceVerification(
                        source, ExportTestFixtures.atlasProvenance()),
                () -> Optional.of(accepted));
        final List<ExportRegionSelection> selections = List.of(
                ExportTestFixtures.region(1, "LEFT"),
                ExportTestFixtures.region(2, "RIGHT"));

        final SourceSpaceExportService.Result result = service.export(
                temporaryDirectory, "optional.tif", accepted, selections,
                new SourceSpaceExportOptions(true, true, true),
                () -> false, (stage, progress) -> {
                });

        assertEquals(14, result.fileNames().size());
        for (final String name : List.of(
                "optional__LEFT__crop.ome.tif",
                "optional__LEFT__mask.ome.tif",
                "optional__LEFT__mask-full.ome.tif",
                "optional__LEFT__masked.ome.tif",
                "optional__RIGHT__mask-full.ome.tif",
                "optional__RIGHT__masked.ome.tif",
                "optional__LEFT+RIGHT__combined.ome.tif",
                "optional__LEFT+RIGHT__combined__mask-full.ome.tif",
                "optional__LEFT+RIGHT__combined__masked.ome.tif")) {
            assertTrue(Files.exists(result.publishedDirectory().resolve(
                    name)), name + " was not published");
        }

        final var json = new ObjectMapper().readTree(
                result.publishedDirectory().resolve(
                        "optional__atlasalign-export.json").toFile());
        assertEquals(13, json.path("outputs").size());
        assertEquals(3, outputCount(json, "crop"));
        assertEquals(3, outputCount(json, "mask"));
        assertEquals(3, outputCount(json, "mask_full"));
        assertEquals(3, outputCount(json, "masked_crop"));
        assertEquals(1, outputCount(json, "roi_zip"));
        assertEquals("SOURCE_PIXEL", json.path("footprintApplication")
                .path("coordinateSpace").asText());
        assertEquals("XY", json.path("footprintApplication")
                .path("axes").asText());
        assertTrue(json.path("footprintApplication")
                .path("reusedUnchangedAcrossAllChannelsSlicesFrames")
                .asBoolean());

        final var fullMask = output(json,
                "optional__RIGHT__mask-full.ome.tif");
        assertEquals(ExportTestFixtures.SOURCE_WIDTH,
                fullMask.path("rasterWidth").asInt());
        assertEquals(ExportTestFixtures.SOURCE_HEIGHT,
                fullMask.path("rasterHeight").asInt());
        assertEquals(0, fullMask.path("sourceOriginX").asInt());
        assertEquals(0, fullMask.path("sourceOriginY").asInt());
        assertEquals("full-source-mask",
                fullMask.path("rasterExtent").asText());
        assertTrue(fullMask.path("derived").asBoolean());
        assertFalse(fullMask.path(
                "footprintAppliedAcrossAllSourceCztPlanes").asBoolean());

        final var masked = output(json,
                "optional__RIGHT__masked.ome.tif");
        assertEquals(4, masked.path("sourceOriginX").asInt());
        assertEquals(0, masked.path("sourceOriginY").asInt());
        assertEquals("exact source values and raw float bits",
                masked.path("insidePixelSemantics").asText());
        assertEquals("numeric positive zero on every C/Z/T plane",
                masked.path("outsidePixelSemantics").asText());
        assertTrue(masked.path("derived").asBoolean());
        assertTrue(masked.path(
                "footprintAppliedAcrossAllSourceCztPlanes").asBoolean());

        final var raw = output(json,
                "optional__RIGHT__crop.ome.tif");
        assertFalse(raw.path("derived").asBoolean());
        assertEquals("unmasked rectangular source values retained",
                raw.path("outsidePixelSemantics").asText());
        assertEquals(source, reader.snapshot());
    }

    @Test
    void clippedExportManifestRecordsTheExactOptimizedFootprint()
            throws Exception {
        final SourceImageSnapshot source = ExportTestFixtures.sourceSnapshot(
                8, 1, 1, 1);
        final var accepted = ExportTestFixtures.accepted(
                source, Optional.of(ExportTestFixtures.leftSupport()), true);
        final var reader = new ExportTestFixtures.MemoryReader(source);
        final var service = new SourceSpaceExportService(
                reader,
                ignored -> ExportTestFixtures.annotationPlane(),
                () -> new ReviewAcceptanceVerification(
                        source, ExportTestFixtures.atlasProvenance()),
                () -> Optional.of(accepted));

        final SourceSpaceExportService.Result result = service.export(
                temporaryDirectory, "clipped.tif", accepted,
                List.of(ExportTestFixtures.region(1, "LEFT")),
                new SourceSpaceExportOptions(false, true, true),
                () -> false, (stage, progress) -> {
                });

        final var json = new ObjectMapper().readTree(
                result.publishedDirectory().resolve(
                        "clipped__atlasalign-export.json").toFile());
        for (final String fileName : List.of(
                "clipped__LEFT__crop.ome.tif",
                "clipped__LEFT__mask.ome.tif",
                "clipped__LEFT__mask-full.ome.tif",
                "clipped__LEFT__masked.ome.tif")) {
            final var artifact = output(json, fileName);
            assertEquals(24, artifact.path("includedSourcePixels").asInt());
            assertEquals(0, artifact.path("sourceBounds")
                    .path("minimumX").asInt());
            assertEquals(4, artifact.path("sourceBounds")
                    .path("width").asInt());
        }
        assertEquals(source, reader.snapshot());
    }

    @Test
    void cancellationDuringOptionalMaskedWritePublishesNothing()
            throws Exception {
        final SourceImageSnapshot source = ExportTestFixtures.sourceSnapshot(
                8, 2, 2, 2);
        final var accepted = ExportTestFixtures.accepted(
                source, Optional.empty(), false);
        final var reader = new ExportTestFixtures.MemoryReader(source);
        final var service = new SourceSpaceExportService(
                reader,
                ignored -> ExportTestFixtures.annotationPlane(),
                () -> new ReviewAcceptanceVerification(
                        source, ExportTestFixtures.atlasProvenance()),
                () -> Optional.of(accepted));
        final AtomicBoolean cancel = new AtomicBoolean();

        assertThrows(ExportCancelledException.class, () -> service.export(
                temporaryDirectory, "cancel-optional.tif", accepted,
                List.of(ExportTestFixtures.region(1, "LEFT")),
                new SourceSpaceExportOptions(false, true, true),
                cancel::get, (stage, progress) -> {
                    if (stage.contains("masked image")) {
                        cancel.set(true);
                    }
                }));

        assertFalse(Files.exists(temporaryDirectory.resolve(
                "cancel-optional__atlasalign_exports")));
        try (var children = Files.list(temporaryDirectory)) {
            assertFalse(children.anyMatch(path -> path.getFileName()
                    .toString().contains(".tmp-")));
        }
        assertEquals(source, reader.snapshot());
    }

    @Test
    void cancellationAndLiveHashMismatchPublishNothing() throws Exception {
        final SourceImageSnapshot source = ExportTestFixtures.sourceSnapshot(
                8, 1, 1, 1);
        final var accepted = ExportTestFixtures.accepted(
                source, Optional.empty(), false);
        final var reader = new ExportTestFixtures.MemoryReader(source);
        final var cancelledService = new SourceSpaceExportService(
                reader,
                ignored -> ExportTestFixtures.annotationPlane(),
                () -> new ReviewAcceptanceVerification(
                        source, ExportTestFixtures.atlasProvenance()),
                () -> Optional.of(accepted));

        assertThrows(ExportCancelledException.class,
                () -> cancelledService.export(
                        temporaryDirectory, "cancel.tif", accepted,
                        List.of(ExportTestFixtures.region(1, "LEFT")),
                        false, () -> true, (stage, progress) -> {
                        }));
        assertFalse(Files.exists(temporaryDirectory.resolve(
                "cancel__atlasalign_exports")));

        final SourceImageSnapshot changed = new SourceImageSnapshot(
                source.metadata(), "b".repeat(64));
        final var mismatchService = new SourceSpaceExportService(
                reader,
                ignored -> ExportTestFixtures.annotationPlane(),
                () -> new ReviewAcceptanceVerification(
                        changed, ExportTestFixtures.atlasProvenance()),
                () -> Optional.of(accepted));
        final IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> mismatchService.export(
                        temporaryDirectory, "mismatch.tif", accepted,
                        List.of(ExportTestFixtures.region(1, "LEFT")),
                        false, () -> false, (stage, progress) -> {
                        }));
        assertTrue(failure.getMessage().contains(
                "Source pixels or metadata changed"));
        assertFalse(Files.exists(temporaryDirectory.resolve(
                "mismatch__atlasalign_exports")));
    }

    @Test
    void manifestRecordsExactReviewerWarpReplayGeometry() throws Exception {
        final SourceImageSnapshot source = ExportTestFixtures.sourceSnapshot(
                512, 384, 8, 1, 1, 1);
        final var accepted = ExportTestFixtures
                .acceptedWithNonIdentityWarp(source);
        final var service = new SourceSpaceExportService(
                new ExportTestFixtures.MemoryReader(source),
                ignored -> ExportTestFixtures.filledAnnotationPlane(1),
                () -> new ReviewAcceptanceVerification(
                        source, ExportTestFixtures.atlasProvenance()),
                () -> Optional.of(accepted));

        final SourceSpaceExportService.Result result = service.export(
                temporaryDirectory, "warped.tif", accepted,
                List.of(ExportTestFixtures.region(1, "ALL")),
                false, () -> false, (stage, progress) -> {
                });

        final var json = new ObjectMapper().readTree(
                result.publishedDirectory().resolve(
                        "warped__atlasalign-export.json").toFile());
        final var warp = json.path("acceptedAlignment")
                .path("hemisphereWarp");
        assertEquals("REVIEWER_CONTROLLED_MANUAL_WARP",
                json.path("acceptedAlignment").path("method").asText());
        assertEquals(8, warp.path("controls").size());
        assertEquals("l1", warp.path("controls").get(0)
                .path("id").asText());
        assertEquals(70.0, warp.path("controls").get(0)
                .path("sourcePoint").path("x").asDouble());
        assertEquals(74.0, warp.path("controls").get(0)
                .path("targetPoint").path("x").asDouble());
        assertEquals(2, warp.path("imageMidlinePath").size());
        assertEquals(64, warp.path("contentSha256").asText().length());
    }

    @Test
    void selectedAtlasCropsCombinedOutputRoisAndManifestRetainSourceMapping() throws Exception {
        final var source = ExportTestFixtures.sourceSnapshot(16, 4, 3, 2);
        final var reader = new ExportTestFixtures.MemoryReader(source);
        final var accepted = ExportTestFixtures.accepted(source, Optional.empty(), false);
        final var service = new SourceSpaceExportService(reader,
                ignored -> ExportTestFixtures.annotationPlane(),
                () -> new ReviewAcceptanceVerification(source, ExportTestFixtures.atlasProvenance()),
                () -> Optional.of(accepted));
        final var selection = new ExportSelection(List.of(2, 4), 3, 2);
        final var result = service.export(temporaryDirectory, "selected.tif", accepted,
                List.of(ExportTestFixtures.region(1, "LEFT"), ExportTestFixtures.region(2, "RIGHT")),
                new SourceSpaceExportOptions(true, true, true), selection,
                () -> false, (stage, fraction) -> { });

        for (final String name : List.of("selected__LEFT__crop.ome.tif", "selected__RIGHT__crop.ome.tif",
                "selected__LEFT__masked.ome.tif", "selected__RIGHT__masked.ome.tif",
                "selected__LEFT+RIGHT__combined.ome.tif", "selected__LEFT+RIGHT__combined__masked.ome.tif")) {
            final boolean combined = name.contains("__combined");
            final int expectedWidth = combined ? 8 : 4;
            final int sourceX = name.contains("__RIGHT__") ? 4 : 0;
            final var metadata = MetadataTools.createOMEXMLMetadata();
            try (OMETiffReader tif = new OMETiffReader()) {
                tif.setMetadataStore(metadata);
                tif.setId(result.publishedDirectory().resolve(name).toString());
                assertEquals(expectedWidth, tif.getSizeX(), name);
                assertEquals(6, tif.getSizeY(), name);
                assertEquals(2, tif.getSizeC(), name);
                assertEquals(1, tif.getSizeZ(), name);
                assertEquals(1, tif.getSizeT(), name);
                assertEquals(16, tif.getBitsPerPixel(), name);
                assertEquals(2, tif.getImageCount(), name);
                assertEquals("Channel 2", metadata.getChannelName(0, 0));
                assertEquals("Channel 4", metadata.getChannelName(0, 1));
                assertEquals(0.65, metadata.getPixelsPhysicalSizeX(0).value().doubleValue());
                assertEquals(0.65, metadata.getPixelsPhysicalSizeY(0).value().doubleValue());
                final var mapping = metadata.getMapAnnotationValue(0).stream()
                        .collect(java.util.stream.Collectors.toMap(pair -> pair.getName(), pair -> pair.getValue()));
                assertEquals("2,4", mapping.get("atlasalign.sourceChannels"));
                assertEquals("3", mapping.get("atlasalign.sourceSlice"));
                assertEquals("2", mapping.get("atlasalign.sourceFrame"));
                assertEquals(Integer.toString(sourceX), mapping.get("atlasalign.sourceOriginX"));
                assertEquals("0", mapping.get("atlasalign.sourceOriginY"));
                for (int outputPlane = 0; outputPlane < 2; outputPlane++) {
                    assertEquals(outputPlane, metadata.getPlaneTheC(0, outputPlane).getValue());
                    assertEquals(0, metadata.getPlaneTheZ(0, outputPlane).getValue());
                    assertEquals(0, metadata.getPlaneTheT(0, outputPlane).getValue());
                    assertEquals(4.0, metadata.getPlanePositionZ(0, outputPlane).value().doubleValue());
                    assertEquals(3.0, metadata.getPlaneDeltaT(0, outputPlane).value().doubleValue());
                    final ByteBuffer pixels = ByteBuffer.wrap(tif.openBytes(outputPlane))
                            .order(tif.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                    final int sourcePlane = 21 + 2 * outputPlane;
                    for (int y = 0; y < 6; y++) {
                        for (int x = 0; x < expectedWidth; x++) {
                            assertEquals(40_000 + sourcePlane * 97 + y * 8 + sourceX + x,
                                    pixels.getShort() & 0xffff, name);
                        }
                    }
                }
            }
        }
        try (ZipFile zip = new ZipFile(result.publishedDirectory()
                .resolve("selected__atlasalign-rois.zip").toFile())) {
            assertEquals(2, zip.size());
            for (final String name : List.of("LEFT", "RIGHT")) {
                final var entry = zip.getEntry(name + ".roi");
                try (var input = zip.getInputStream(entry)) {
                    final var roi = new RoiDecoder(input.readAllBytes(), entry.getName()).getRoi();
                    assertEquals(0, roi.getCPosition());
                    assertEquals(3, roi.getZPosition());
                    assertEquals(2, roi.getTPosition());
                    assertEquals(new java.awt.Rectangle(name.equals("LEFT") ? 0 : 4, 0, 4, 6),
                            roi.getBounds());
                    assertEquals(name.equals("LEFT"), roi.contains(1, 2));
                    assertEquals(name.equals("RIGHT"), roi.contains(5, 2));
                }
            }
        }
        final var json = new ObjectMapper().readTree(result.publishedDirectory()
                .resolve("selected__atlasalign-export.json").toFile());
        assertEquals("selected-channels-pinned-plane", json.path("scope").asText());
        assertEquals(4, json.path("source").path("channels").asInt());
        assertEquals(3, json.path("source").path("slices").asInt());
        assertEquals(2, json.path("source").path("frames").asInt());
        final var scope = json.path("exportSelection");
        assertEquals(List.of(2, 4), new ObjectMapper().convertValue(
                scope.path("sourceChannelsOneBased"),
                new com.fasterxml.jackson.core.type.TypeReference<List<Integer>>() { }));
        assertEquals(3, scope.path("sourceSliceOneBased").asInt());
        assertEquals(2, scope.path("sourceFrameOneBased").asInt());
        assertEquals(2, scope.path("outputSizeC").asInt());
        assertEquals(1, scope.path("outputSizeZ").asInt());
        assertEquals(1, scope.path("outputSizeT").asInt());
        assertFalse(json.path("footprintApplication")
                .path("reusedUnchangedAcrossAllChannelsSlicesFrames").asBoolean());
        for (final var artifact : json.path("outputs")) {
            assertFalse(artifact.path("footprintAppliedAcrossAllSourceCztPlanes").asBoolean(),
                    artifact.path("fileName").asText());
        }
        assertEquals(source, reader.snapshot());
    }

    private static int outputCount(
            final com.fasterxml.jackson.databind.JsonNode manifest,
            final String kind) {
        int count = 0;
        for (final var output : manifest.path("outputs")) {
            if (kind.equals(output.path("kind").asText())) {
                count++;
            }
        }
        return count;
    }

    private static com.fasterxml.jackson.databind.JsonNode output(
            final com.fasterxml.jackson.databind.JsonNode manifest,
            final String fileName) {
        for (final var output : manifest.path("outputs")) {
            if (fileName.equals(output.path("fileName").asText())) {
                return output;
            }
        }
        throw new AssertionError("Missing manifest output " + fileName);
    }
}
