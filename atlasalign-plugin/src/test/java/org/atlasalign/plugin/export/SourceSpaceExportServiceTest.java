package org.atlasalign.plugin.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipFile;
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
        }
        final Path manifest = result.publishedDirectory().resolve(
                "sample__atlasalign-export.json");
        final var json = new ObjectMapper().readTree(manifest.toFile());
        assertEquals("atlasalign-source-space-export-v1",
                json.path("schema").asText());
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
