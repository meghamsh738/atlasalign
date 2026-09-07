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
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.io.imagej.ImagePlusSourcePixelReader;
import org.atlasalign.plugin.export.ManualRoiExportService;
import org.atlasalign.plugin.review.ManualRoiSessionStore;

/** Atomically publishes every autosaved, export-selected ROI in a queue. */
final class BatchManualRoiExporter {

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
        final List<String> exportedSectionIds = new ArrayList<>();
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
                        + " autosaved ROIs", (double) index / items.size());
                final ImagePlus sectionImage = new SectionImageExtractor()
                        .extract(item);
                final var sectionSnapshot = new ImagePlusSourceImage(
                        sectionImage).snapshot();
                final Path draft = draftFile(checkedProject, section);
                if (!Files.isRegularFile(draft)) {
                    skipped.add(section.name()
                            + " — no autosaved manual ROI draft");
                    continue;
                }
                final var session = new ManualRoiSessionStore(draft,
                        section.id(), section.width(), section.height(),
                        sectionSnapshot.pixelSha256()).loadOrCreate();
                final var rois = session.snapshot().exportableRois();
                if (rois.isEmpty()) {
                    skipped.add(section.name()
                            + " — no finished ROI selected for export");
                    continue;
                }
                final var parentContext =
                        new ManualRoiExportService.ParentSourceContext(
                                section.sourceName(),
                                section.sourcePixelSha256(),
                                section.sourceWidth(), section.sourceHeight(),
                                section.minimumX(), section.minimumY());
                final var service = new ManualRoiExportService(
                        new ImagePlusSourcePixelReader(sectionImage),
                        sectionSnapshot,
                        Math.max(1, Math.min(preferredChannel,
                                sectionImage.getNChannels())),
                        Math.max(1, sectionImage.getZ()),
                        Math.max(1, sectionImage.getT()),
                        java.util.Optional.of(parentContext));
                final double start = (double) index / items.size();
                final double span = 1.0 / items.size();
                final var result = service.export(temporary,
                        section.name() + ".tif", section.id(), rois, true,
                        cancellation, (message, fraction) -> reporter.accept(
                                section.name() + " — " + message,
                                start + span * fraction));
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("sectionId", section.id());
                row.put("sectionName", section.name());
                row.put("roiCount", rois.size());
                row.put("relativeDirectory", temporary.relativize(
                        result.publishedDirectory()).toString());
                row.put("warnings", result.warnings());
                exports.add(row);
                warnings.addAll(result.warnings());
                roiCount += rois.size();
                exportedSectionIds.add(section.id());
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
            for (final String sectionId : exportedSectionIds) {
                final int count = exports.stream().filter(row ->
                        sectionId.equals(row.get("sectionId")))
                        .mapToInt(row -> (Integer) row.get("roiCount"))
                        .findFirst().orElse(0);
                checkedProject.setStatus(sectionId,
                        BatchReviewStatus.COMPLETE,
                        "Batch-exported " + count
                                + " exact manual ROI(s)");
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
