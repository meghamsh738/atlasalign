package org.atlasalign.plugin.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import ij.ImagePlus;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.io.imagej.ImagePlusSourcePixelReader;
import org.atlasalign.plugin.export.ManualRoiExportService;
import org.atlasalign.plugin.review.ManualRoiSessionStore;

/** Atomically publishes every autosaved, export-selected ROI in a queue. */
final class BatchManualRoiExporter {
    private final BatchReviewCheckpoint checkpoints;

    BatchManualRoiExporter() { this(new BatchReviewCheckpoint()); }
    BatchManualRoiExporter(final BatchReviewCheckpoint checkpoints) { this.checkpoints = Objects.requireNonNull(checkpoints); }

    private record ExportedSection(String id, long alignmentRevision, ReviewerRoiSession.Snapshot rois,
            ExportSelection selection, Path relativeDirectory) { }

    record Result(
            Path publishedDirectory,
            int exportedSections,
            int exportedRois,
            List<String> skippedSections,
            List<String> warnings) {
        Result {
            skippedSections = List.copyOf(skippedSections);
            warnings = List.copyOf(warnings);
        }
    }

    Result export(
            final Path selectedFolder,
            final BatchProjectSession project,
            final int preferredChannel,
            final BooleanSupplier cancelled,
            final BiConsumer<String, Double> progress) {
        final Path parent = Objects.requireNonNull(
                selectedFolder, "selectedFolder")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(parent) || !Files.isWritable(parent)) {
            throw new IllegalArgumentException(
                    "Choose an existing writable batch export folder");
        }
        final BatchProjectSession checkedProject = Objects.requireNonNull(
                project, "project");
        final List<BatchReviewItem> items = checkedProject.items();
        final BooleanSupplier cancellation = Objects.requireNonNull(
                cancelled, "cancelled");
        final BiConsumer<String, Double> reporter = Objects.requireNonNull(
                progress, "progress");
        final String base = safeToken(checkedProject.projectDirectory()
                .getFileName().toString()) + "__manual_roi_exports";
        final Path destination = nextAvailable(parent, base);
        final Path temporary = parent.resolve("." + destination.getFileName()
                + ".tmp-" + UUID.randomUUID());
        final List<Map<String, Object>> exports = new ArrayList<>();
        final List<ExportedSection> exportedSections = new ArrayList<>();
        final List<String> skipped = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        int roiCount = 0;
        try {
            Files.createDirectory(temporary);
            for (int index = 0; index < items.size(); index++) {
                checkCancelled(cancellation);
                final BatchReviewItem item = items.get(index);
                final BatchSection section = item.section();
                reporter.accept("Reading " + section.name()
                        + " saved ROIs", (double) index / items.size());
                final ImagePlus sectionImage = new SectionImageExtractor()
                        .extract(item);
                final var sectionSnapshot = new ImagePlusSourceImage(
                        sectionImage).snapshot();
                final ReviewerRoiSession.Snapshot savedRois;
                final ExportSelection selection;
                final int registrationChannel;
                final long alignmentRevision;
                final var checkpoint = checkedProject.checkpoint(item);
                if (checkpoint.isPresent()) {
                    final var saved = checkpoints.read(checkpoint.orElseThrow(), item, sectionImage);
                    savedRois = saved.rois();
                    selection = saved.exportSelection();
                    registrationChannel = saved.registrationInput().channel();
                    alignmentRevision = saved.alignment().contentRevision();
                    if (item.progress().saveState() != BatchSectionProgress.SaveState.SAVED) {
                        warnings.add(section.name() + ": exported the saved checkpoint; unsaved edits are not included.");
                    }
                } else {
                    final Path draft = draftFile(checkedProject, section);
                    if (!Files.isRegularFile(draft) && !Files.isRegularFile(ManualRoiSessionStore.scopedFile(draft))) {
                        skipped.add(section.name()
                                + " — no autosaved manual ROI draft");
                        continue;
                    }
                    final var recordedInput = ManualRoiSessionStore.recordedInput(draft);
                    if (recordedInput.isEmpty() && (sectionImage.getNChannels() > 1
                            || sectionImage.getNSlices() > 1 || sectionImage.getNFrames() > 1)) {
                        throw new IllegalStateException("Legacy ROI draft for " + section.name()
                                + " has no recorded C/Z/T scope. Open the section review and explicitly pin the image scope before exporting.");
                    }
                    selection = ManualRoiSessionStore.recordedExportSelection(draft, sectionSnapshot.metadata());
                    final Path availableDraft = Files.isRegularFile(ManualRoiSessionStore.scopedFile(draft))
                            ? ManualRoiSessionStore.scopedFile(draft) : draft;
                    final var session = new ManualRoiSessionStore(availableDraft,
                            section.id(), section.width(), section.height(),
                            sectionSnapshot.pixelSha256()).loadOrCreate();
                    savedRois = session.snapshot();
                    registrationChannel = recordedInput.map(org.atlasalign.application.RegistrationInput::channel).orElse(1);
                    alignmentRevision = item.progress().alignmentRevision();
                }
                final var rois = savedRois.exportableRois();
                if (rois.isEmpty()) {
                    skipped.add(section.name()
                            + " — no finished ROI selected for export");
                    continue;
                }
                final var parentContext = BatchReviewCheckpoint.parentContext(section);
                final var service = new ManualRoiExportService(
                        new ImagePlusSourcePixelReader(sectionImage),
                        sectionSnapshot,
                        registrationChannel,
                        selection.slice(),
                        selection.frame(),
                        java.util.Optional.of(parentContext));
                final double start = (double) index / items.size();
                final double span = 1.0 / items.size();
                final var result = service.export(temporary,
                        section.name() + ".tif", section.id(), rois, true, selection,
                        cancellation, (message, fraction) -> reporter.accept(
                                section.name() + " — " + message,
                                start + span * fraction));
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("sectionId", section.id());
                row.put("sectionName", section.name());
                row.put("roiCount", rois.size());
                row.put("exportSelection", selection);
                row.put("alignmentRevision", alignmentRevision);
                checkpoint.ifPresent(path -> row.put("reviewCheckpoint", path.toString()));
                row.put("relativeDirectory", temporary.relativize(
                        result.publishedDirectory()).toString());
                row.put("warnings", result.warnings());
                exports.add(row);
                warnings.addAll(result.warnings());
                roiCount += rois.size();
                exportedSections.add(new ExportedSection(section.id(), alignmentRevision, savedRois, selection,
                        temporary.relativize(result.publishedDirectory())));
            }
            if (exports.isEmpty()) {
                throw new IllegalStateException(
                        "No finished autosaved manual ROIs were available to export");
            }
            final Map<String, Object> index = new LinkedHashMap<>();
            index.put("schema", "atlasalign-manual-roi-batch-index-v1");
            index.put("createdAt", Instant.now().toString());
            index.put("batchProject", checkedProject.projectFile()
                    .toString());
            index.put("exports", exports);
            index.put("skippedSections", skipped);
            index.put("warnings", warnings.stream().distinct().toList());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(
                    temporary.resolve("batch-export-index.json").toFile(),
                    index);
            checkCancelled(cancellation);
            publish(temporary, destination);
            for (final var exported : exportedSections) {
                checkedProject.exportedSnapshot(exported.id(), exported.alignmentRevision(), exported.rois(),
                        exported.selection(), destination.resolve(exported.relativeDirectory()));
            }
            reporter.accept("Batch manual ROI export complete", 1.0);
            return new Result(destination, exports.size(), roiCount,
                    skipped, warnings.stream().distinct().toList());
        } catch (final IOException | RuntimeException error) {
            deleteTree(temporary);
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    "Could not publish the batch manual ROI export", error);
        }
    }

    static Path draftFile(
            final BatchProjectSession project,
            final BatchSection section) {
        return project.projectDirectory().resolve("sections")
                .resolve(section.id()).resolve("manual-rois.json");
    }

    private static void checkCancelled(
            final BooleanSupplier cancellation) {
        if (cancellation.getAsBoolean()) {
            throw new IllegalStateException(
                    "Batch manual ROI export was cancelled");
        }
    }

    private static Path nextAvailable(
            final Path parent,
            final String base) {
        Path candidate = parent.resolve(base);
        for (int suffix = 2; Files.exists(candidate); suffix++) {
            candidate = parent.resolve(String.format(java.util.Locale.ROOT,
                    "%s_%03d", base, suffix));
        }
        return candidate;
    }

    static void publish(
            final Path temporary,
            final Path destination) throws IOException {
        try {
            Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException unsupported) {
            throw new IOException("Batch export requires atomic directory publication; "
                    + "choose an export folder that supports atomic moves", unsupported);
        }
    }

    private static void deleteTree(final Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException ignored) {
                    // Keep the original export failure as the useful error.
                }
            });
        } catch (final IOException ignored) {
            // Keep the original export failure as the useful error.
        }
    }

    private static String safeToken(final String value) {
        final String token = value.trim()
                .replaceAll("[^A-Za-z0-9._+-]+", "_")
                .replaceAll("^_+|_+$", "");
        return token.isEmpty() ? "batch" : token;
    }
}
