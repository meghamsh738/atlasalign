package org.atlasalign.plugin.batch;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ij.ImagePlus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.atlasalign.application.AtlasAssetVerification;
import org.atlasalign.application.AtlasReviewProvenance;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.core.Point2D;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.plugin.project.ReviewProjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BatchCheckpointIntegrationTest {
    @TempDir Path directory;

    @Test
    void legacyMigrationWritesOnlyV2AndDoesNotTreatAnOldExportAsReviewerCompletion() throws Exception {
        final var image = BatchCheckpointFixtures.image(false);
        final var project = project(image);
        final var mapper = new ObjectMapper();
        final var legacy = (ObjectNode) mapper.readTree(project.projectFile().toFile());
        legacy.put("schema", BatchProjectSession.LEGACY_SCHEMA);
        final var section = (ObjectNode) legacy.path("sections").get(0);
        section.remove("progress"); section.put("status", "COMPLETE");
        section.put("statusDetail", "Batch-exported 1 exact manual ROI(s)");
        final Path originalFile = project.projectDirectory().resolve(BatchProjectSession.LEGACY_FILE_NAME);
        mapper.writerWithDefaultPrettyPrinter().writeValue(originalFile.toFile(), legacy);
        final byte[] originalBytes = Files.readAllBytes(originalFile);
        Files.delete(project.projectFile());

        final var restored = new BatchProjectLoader().load(originalFile, List.of(image));
        assertFalse(Files.exists(restored.projectFile()), "Loading does not publish a migration implicitly");
        assertEquals(BatchReviewStatus.PENDING, restored.selected().section().status());
        assertFalse(restored.selected().progress().accepted());
        assertEquals(BatchSectionProgress.SaveState.UNSAVED, restored.selected().progress().saveState());
        restored.renameSelected("Renamed after migration");
        assertEquals("batch-project-v2.json", restored.projectFile().getFileName().toString());
        assertEquals("atlasalign-review-batch-v2", mapper.readTree(restored.projectFile().toFile()).path("schema").asText());
        assertArrayEquals(originalBytes, Files.readAllBytes(originalFile));
        final byte[] migratedBytes = Files.readAllBytes(restored.projectFile());
        final var bookmarked = new BatchProjectLoader().load(originalFile, List.of(image));
        assertEquals("Renamed after migration", bookmarked.selected().section().name(),
                "An old v1 bookmark resumes existing v2 progress instead of reverting it");
        assertArrayEquals(migratedBytes, Files.readAllBytes(restored.projectFile()));
    }

    @Test
    void progressCallbacksKeepSaveAcceptanceCompletionAndExportIndependent() {
        final ImagePlus image = BatchCheckpointFixtures.image(true);
        final var project = project(image);
        final var item = project.selected();
        final var crop = new SectionImageExtractor().extract(item);
        final var rois = BatchCheckpointFixtures.rois(item.section(), "one ROI");
        final var selection = new ExportSelection(List.of(2), 2, 1);
        final Path checkpoint = project.defaultCheckpoint(item.section());
        BatchReviewNavigation.register(crop, () -> { }, project, item.section().id());
        try {
            BatchReviewNavigation.reviewProgress(crop, 4, true, rois.snapshot(), selection, checkpoint, "SAVED");
            BatchReviewNavigation.exported(crop, directory.resolve("export-1"));
            assertEquals(BatchSectionProgress.ExportState.CURRENT, project.selected().progress().exportState());
            assertEquals(BatchReviewStatus.PENDING, project.selected().section().status());
            rois.setVisible(rois.snapshot().rois().get(0).id(), false);
            BatchReviewNavigation.reviewProgress(crop, 4, true, rois.snapshot(), selection, checkpoint, "DIRTY");
            assertEquals(BatchSectionProgress.ExportState.CURRENT, project.selected().progress().exportState(),
                    "A display-only ROI visibility edit must not stale geometry exports");
            final var part = rois.snapshot().rois().get(0).parts().get(0);
            rois.moveVertex(rois.snapshot().rois().get(0).id(), part.id(), part.vertices().get(0).id(), new Point2D(2, 1));
            BatchReviewNavigation.reviewProgress(crop, 4, false, rois.snapshot(), selection, checkpoint, "FAILED");
            assertEquals(BatchSectionProgress.ExportState.STALE, project.selected().progress().exportState());
            assertEquals(BatchSectionProgress.SaveState.FAILED, project.selected().progress().saveState());
            assertNotNull(project.selected().progress().checkpointPath(), "A failed save keeps the prior checkpoint reference");
            BatchReviewNavigation.exported(crop, directory.resolve("export-2"));
            final var exportedRois = rois.snapshot();
            BatchReviewNavigation.reviewProgress(crop, 5, false, rois.snapshot(), selection, checkpoint, "DIRTY");
            assertEquals(BatchSectionProgress.ExportState.STALE, project.selected().progress().exportState());
            BatchReviewNavigation.exported(crop, directory.resolve("worker-finished-after-newer-edit"), 4, exportedRois, selection);
            assertEquals(BatchSectionProgress.ExportState.STALE, project.selected().progress().exportState(),
                    "A completed worker records its captured revision, not the newer live revision");
            BatchReviewNavigation.exported(crop, directory.resolve("export-3"));
            BatchReviewNavigation.reviewProgress(crop, 5, false, rois.snapshot(), new ExportSelection(List.of(1), 2, 1), checkpoint, "DIRTY");
            assertEquals(BatchSectionProgress.ExportState.STALE, project.selected().progress().exportState());
            project.setStatus(item.section().id(), BatchReviewStatus.COMPLETE, "Marked complete by reviewer");
            assertFalse(project.selected().progress().accepted(), "Manual queue completion is separate from atlas acceptance");
        } finally {
            BatchReviewNavigation.unregister(crop);
        }
        BatchReviewNavigation.reviewProgress(crop, 99, true, rois.snapshot(), selection, checkpoint, "SAVED");
        assertEquals(5, project.selected().progress().alignmentRevision(), "A closed review cannot keep updating its queue");
    }

    @Test
    void savedSectionDispatchUsesExactReopenAndNeverRunsNewReviewInitialization() throws Exception {
        final var image = BatchCheckpointFixtures.image(true);
        final var project = project(image);
        final var item = project.selected();
        final var rois = BatchCheckpointFixtures.rois(item.section(), "saved ROI");
        final var saved = BatchCheckpointFixtures.review(item, rois.snapshot());
        final Path checkpoint = project.defaultCheckpoint(item.section());
        new ReviewProjectStore().save(checkpoint, saved);
        project.reviewProgress(item.section().id(), saved.alignment().contentRevision(), true,
                saved.rois(), saved.exportSelection(), checkpoint, "SAVED");
        final var crop = new SectionImageExtractor().extract(item);
        final var reopens = new AtomicInteger();
        final var launcher = new BatchSectionReviewLauncher((file, independent, atlas) -> {
            reopens.incrementAndGet(); assertEquals(checkpoint, file); assertSame(crop, independent);
            assertNotSame(image.getStack().getPixels(1), independent.getStack().getPixels(1));
            assertEquals(directory.resolve("atlas"), atlas);
        });
        launcher.open(project, project.selected(), crop, directory.resolve("atlas"), ignored -> fail("Fresh intake must not run"));
        assertEquals(1, reopens.get());
        assertEquals(checkpoint.toString(), crop.getProperty("AtlasAlign.review.defaultProjectPath"));
        final var loaded = new BatchProjectLoader().load(project.projectFile(), List.of(image));
        assertFalse(loaded.selected().progress().accepted(), "Acceptance must be renewed after reopening");
        assertEquals(checkpoint, loaded.checkpoint(loaded.selected()).orElseThrow());
        assertEquals(saved.exportSelection(), loaded.selected().progress().exportSelection());
    }

    @Test
    void missingOrCorruptCheckpointNeverFallsBackToFreshReview() throws Exception {
        final var image = BatchCheckpointFixtures.image(false);
        final var project = project(image);
        final var item = project.selected();
        final var saved = BatchCheckpointFixtures.review(item, BatchCheckpointFixtures.rois(item.section(), "ROI").snapshot());
        final Path checkpoint = project.defaultCheckpoint(item.section());
        project.reviewProgress(item.section().id(), 1, false, saved.rois(), saved.exportSelection(), checkpoint, "SAVED");
        final var crop = new SectionImageExtractor().extract(item);
        final var launcher = new BatchSectionReviewLauncher((file, independent, atlas) -> fail("Invalid checkpoint must not dispatch"));
        assertThrows(Exception.class, () -> launcher.open(project, project.selected(), crop, directory,
                ignored -> fail("Missing checkpoint must not start a new review")));
        final var missing = new BatchProjectLoader().load(project.projectFile(), List.of(image));
        assertEquals(BatchReviewStatus.ERROR, missing.selected().section().status());
        assertEquals(BatchSectionProgress.SaveState.FAILED, missing.selected().progress().saveState());
        Files.createDirectories(checkpoint.getParent()); Files.writeString(checkpoint, "broken checkpoint");
        assertThrows(IllegalArgumentException.class, () -> launcher.open(project, project.selected(), crop, directory,
                ignored -> fail("Corrupt checkpoint must not start a new review")));
    }

    @Test
    void freshSectionUsesItsOwnDefaultSavePathAndOnlyNewReviewDispatch() throws Exception {
        final var project = project(BatchCheckpointFixtures.image(false));
        final var item = project.selected();
        final var crop = new SectionImageExtractor().extract(item);
        final var launches = new AtomicInteger();
        new BatchSectionReviewLauncher((file, image, atlas) -> fail("No checkpoint exists")).open(
                project, item, crop, directory, image -> launches.incrementAndGet());
        assertEquals(1, launches.get());
        assertEquals(project.defaultCheckpoint(item.section()).toString(), crop.getProperty("AtlasAlign.review.defaultProjectPath"));
        assertFalse(Files.exists(project.defaultCheckpoint(item.section())), "The first full save remains explicit");
    }

    @Test
    void checkpointReaderVerifiesLiveSourceAndAtlasBeforeReturningSavedGeometry() throws Exception {
        final var project = project(BatchCheckpointFixtures.image(false));
        final var item = project.selected();
        final var saved = BatchCheckpointFixtures.review(item, BatchCheckpointFixtures.rois(item.section(), "ROI").snapshot());
        final var checkpoint = project.defaultCheckpoint(item.section());
        new ReviewProjectStore().save(checkpoint, saved);
        final var crop = new SectionImageExtractor().extract(item);
        final var verified = new AtomicInteger();
        final var reader = new BatchReviewCheckpoint(path -> { verified.incrementAndGet(); return saved.alignment().basis().atlas(); });
        assertEquals(saved, reader.read(checkpoint, item, crop));
        assertEquals(1, verified.get());
        final var atlas = saved.alignment().basis().atlas();
        final var changed = new AtlasReviewProvenance(atlas.atlasId(), atlas.atlasVersion(), 456, 320,
                List.of(new AtlasAssetVerification("template", 10, "8".repeat(64)), atlas.assets().get(1), atlas.assets().get(2)));
        assertThrows(IllegalArgumentException.class, () -> new BatchReviewCheckpoint(path -> changed).read(checkpoint, item, crop));
        crop.getProcessor().set(0, 99);
        assertThrows(IllegalArgumentException.class, () -> reader.read(checkpoint, item, crop));
        assertEquals(1, verified.get(), "Source mismatch stops before atlas verification or geometry decode");
        assertEquals(item.verifiedSource(), new ImagePlusSourceImage(item.source()).snapshot());
    }

    private BatchProjectSession project(final ImagePlus image) {
        return new BatchProjectSession("test", directory.resolve("project"),
                new BatchInputFactory().openImages(List.of(image), 240, 0));
    }
}
