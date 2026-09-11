package org.atlasalign.plugin.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ij.ImagePlus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.plugin.project.ReviewProjectStore;

/** Reconnects an autosaved queue to matching images already open in Fiji. */
public final class BatchProjectLoader {

    private record OpenSource(
            ImagePlus image,
            SourceImageSnapshot snapshot) {
    }

    public BatchProjectSession load(
            final Path projectFile,
            final List<ImagePlus> openImages) {
        final Path file = Objects.requireNonNull(projectFile, "projectFile")
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException(
                    "Choose an existing AtlasAlign batch project file");
        }
        final List<OpenSource> sources = List.copyOf(
                Objects.requireNonNull(openImages, "openImages")).stream()
                .filter(Objects::nonNull)
                .map(image -> new OpenSource(image,
                        new ImagePlusSourceImage(image).snapshot()))
                .toList();
        if (sources.isEmpty()) {
            throw new IllegalArgumentException(
                    "Open the original batch image(s) in Fiji before resuming");
        }
        try {
            final ObjectMapper mapper = new ObjectMapper();
            final JsonNode root = mapper.readTree(file.toFile());
            final boolean legacy = BatchProjectSession.LEGACY_SCHEMA.equals(root.path("schema").asText());
            if (!legacy && !BatchProjectSession.SCHEMA.equals(root.path("schema").asText())) {
                throw new IllegalArgumentException(
                        "This is not a supported AtlasAlign batch project");
            }
            if (legacy && file.getFileName().toString().equals(BatchProjectSession.FILE_NAME)) {
                throw new IllegalArgumentException("This legacy batch uses the reserved v2 filename. Keep it as batch-project.json before migrating, so the original cannot be overwritten.");
            }
            final Path migrated = file.getParent().resolve(BatchProjectSession.FILE_NAME);
            if (legacy && Files.exists(migrated)) {
                // Existing bookmarks to v1 must resume the current queue rather than overwrite newer v2 checkpoints.
                return load(migrated, openImages);
            }
            final List<BatchReviewItem> items = new ArrayList<>();
            for (final JsonNode saved : root.withArray("sections")) {
                final String sourceHash = saved.path(
                        "sourcePixelSha256").asText();
                final int sourceWidth = saved.path("sourceWidth").asInt();
                final int sourceHeight = saved.path("sourceHeight").asInt();
                final String sourceName = saved.path("sourceName").asText();
                final SourceImageSnapshot verifiedSource = mapper.treeToValue(
                        saved.path("verifiedSource"),
                        SourceImageSnapshot.class);
                final OpenSource source = sources.stream().filter(candidate ->
                        candidate.image().getTitle().equals(sourceName)
                                && candidate.snapshot().equals(verifiedSource)
                                && candidate.snapshot().pixelSha256()
                                .equals(sourceHash)
                                && candidate.image().getWidth() == sourceWidth
                                && candidate.image().getHeight()
                                == sourceHeight).findFirst().orElseThrow(() ->
                                        new IllegalStateException(
                                                "Open the unchanged source image '"
                                                        + sourceName
                                                        + "' before resuming this batch"));
                final JsonNode bounds = saved.path("bounds");
                final List<Point2D> marker = new ArrayList<>();
                for (final JsonNode point : saved.withArray(
                        "markerVertices")) {
                    marker.add(new Point2D(point.path("x").asDouble(),
                            point.path("y").asDouble()));
                }
                final BatchReviewStatus savedStatus =
                        BatchReviewStatus.valueOf(
                                saved.path("status").asText());
                // Legacy COMPLETE was also set automatically by export, so it is not a reviewer completion flag.
                final BatchReviewStatus restoredStatus =
                        savedStatus == BatchReviewStatus.COMPLETE && (!legacy
                                || saved.path("statusDetail").asText().startsWith("Marked complete by reviewer"))
                                ? BatchReviewStatus.COMPLETE
                                : BatchReviewStatus.PENDING;
                BatchSection section = new BatchSection(
                        saved.path("id").asText(),
                        saved.path("name").asText(),
                        saved.path("sourceName").asText(), sourceHash,
                        sourceWidth, sourceHeight,
                        bounds.path("minimumX").asInt(),
                        bounds.path("minimumY").asInt(),
                        bounds.path("width").asInt(),
                        bounds.path("height").asInt(), marker,
                        saved.path("initialCoronalLevel").asInt(),
                        restoredStatus,
                        restoredStatus == savedStatus
                                ? saved.path("statusDetail").asText()
                                : "Restored from autosave; reopen review when ready");
                BatchSectionProgress progress = legacy ? BatchSectionProgress.initial()
                        : mapper.treeToValue(saved.required("progress"), BatchSectionProgress.class);
                if (progress.checkpointPath() != null) {
                    final Path checkpoint = file.getParent().resolve(progress.checkpointPath()).toAbsolutePath().normalize();
                    try {
                        final var store = new ReviewProjectStore();
                        store.header(store.read(checkpoint));
                        progress = progress.reopened(false);
                    } catch (IOException | RuntimeException invalid) {
                        progress = progress.reopened(true);
                        section = section.withStatus(BatchReviewStatus.ERROR,
                                "Saved review is missing or unreadable: " + checkpoint + ". " + invalid.getMessage());
                    }
                } else progress = progress.reopened(false);
                items.add(new BatchReviewItem(source.image(), source.snapshot(), section, progress));
            }
            if (items.isEmpty()) {
                throw new IllegalArgumentException(
                        "The batch project contains no sections");
            }
            final int selectedIndex = Math.max(0, Math.min(
                    items.size() - 1,
                    root.path("selectedIndex").asInt()));
            return BatchProjectSession.restore(
                    root.path("projectName").asText(),
                    root.path("createdAt").asText(), file.getParent(),
                    items, selectedIndex);
        } catch (final IOException error) {
            throw new IllegalStateException(
                    "Could not read the AtlasAlign batch project", error);
        }
    }
}
