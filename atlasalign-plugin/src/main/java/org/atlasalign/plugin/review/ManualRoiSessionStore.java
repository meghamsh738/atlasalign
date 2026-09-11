package org.atlasalign.plugin.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.atlasalign.application.roi.ReviewerRoi;
import org.atlasalign.application.roi.ReviewerRoiGuideLink;
import org.atlasalign.application.roi.ReviewerRoiPart;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.application.roi.ReviewerRoiSide;
import org.atlasalign.application.roi.ReviewerRoiVertex;
import org.atlasalign.application.roi.RoiPartOperation;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.SourceImageMetadata;
import org.atlasalign.application.RegistrationInput;
import org.atlasalign.application.export.ExportSelection;

/** Debounced, atomic autosave for one batch section's exact manual ROIs. */
public final class ManualRoiSessionStore {

    public static final String SCHEMA = "atlasalign-manual-roi-draft-v1";
    public static final String SCOPED_SCHEMA = "atlasalign-manual-roi-draft-v2";

    private final Path file;
    private Path legacyFile;
    private Optional<RegistrationInput> registrationInput = Optional.empty();
    private java.util.function.Supplier<ExportSelection> exportSelection;
    private final String sectionId;
    private final int sourceWidth;
    private final int sourceHeight;
    private final String sourcePixelSha256;
    private final ObjectMapper json = new ObjectMapper();
    private final ScheduledExecutorService writer;
    private ScheduledFuture<?> pending;

    public ManualRoiSessionStore(
            final Path file,
            final String sectionId,
            final int sourceWidth,
            final int sourceHeight,
            final String sourcePixelSha256) {
        this.file = Objects.requireNonNull(file, "file")
                .toAbsolutePath().normalize();
        this.sectionId = requireText(sectionId, "sectionId");
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException(
                    "Manual ROI draft dimensions must be positive");
        }
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.sourcePixelSha256 = requireText(
                sourcePixelSha256, "sourcePixelSha256");
        if (!this.sourcePixelSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Source pixel SHA-256 must be lowercase hexadecimal");
        }
        final ThreadFactory threads = task -> {
            final Thread thread = new Thread(task,
                    "atlasalign-manual-roi-autosave");
            thread.setDaemon(true);
            return thread;
        };
        writer = Executors.newSingleThreadScheduledExecutor(threads);
    }

    /** Scoped drafts use a new filename; legacy drafts remain untouched. */
    public ManualRoiSessionStore(final Path legacyPath, final String sectionId,
            final int sourceWidth, final int sourceHeight, final String sourcePixelSha256,
            final RegistrationInput input, final java.util.function.Supplier<ExportSelection> selection) {
        this(scopedFile(legacyPath), sectionId, sourceWidth, sourceHeight, sourcePixelSha256);
        legacyFile = legacyPath.toAbsolutePath().normalize();
        registrationInput = Optional.of(Objects.requireNonNull(input, "input"));
        exportSelection = Objects.requireNonNull(selection, "selection");
    }

    public static Path scopedFile(final Path legacyPath) {
        if (legacyPath.getFileName().toString().endsWith("-v2.json")) return legacyPath;
        return legacyPath.resolveSibling(legacyPath.getFileName().toString().replaceFirst("\\.json$", "") + "-v2.json");
    }

    public static Optional<RegistrationInput> recordedInput(final Path legacyPath) {
        final Path candidate = Files.isRegularFile(scopedFile(legacyPath)) ? scopedFile(legacyPath) : legacyPath;
        if (!Files.isRegularFile(candidate)) return Optional.empty();
        try {
            final JsonNode root = new ObjectMapper().readTree(candidate.toFile());
            if (SCHEMA.equals(root.path("schema").asText())) return Optional.empty();
            requireEquals(SCOPED_SCHEMA, root.path("schema").asText(), "schema");
            return Optional.of(new ObjectMapper().treeToValue(root.required("registrationInput"), RegistrationInput.class));
        } catch (IOException invalid) {
            throw new IllegalStateException("Could not read draft image scope", invalid);
        }
    }

    public static ExportSelection recordedExportSelection(final Path legacyPath,
            final SourceImageMetadata metadata) {
        final Optional<RegistrationInput> input = recordedInput(legacyPath);
        if (input.isEmpty()) {
            if (metadata.slices() != 1 || metadata.frames() != 1) {
                throw new IllegalStateException("Legacy multidimensional ROI draft has no optical plane. Open its section review and explicitly select Z/T before exporting.");
            }
            return ExportSelection.allChannels(metadata, 1, 1);
        }
        try {
            input.orElseThrow().validateAgainst(metadata);
            final var mapper = new ObjectMapper();
            final var root = mapper.readTree(scopedFile(legacyPath).toFile());
            final ExportSelection selection = mapper.treeToValue(root.required("exportSelection"), ExportSelection.class);
            selection.validateAgainst(metadata);
            if (selection.slice() != input.orElseThrow().slice() || selection.frame() != input.orElseThrow().frame()) {
                throw new IllegalStateException("Saved export scope differs from registration Z/T");
            }
            return selection;
        } catch (IOException invalid) {
            throw new IllegalStateException("Could not read draft export scope", invalid);
        }
    }

    public ReviewerRoiSession loadOrCreate() {
        final Path readFile = Files.exists(file) || legacyFile == null ? file : legacyFile;
        if (!Files.exists(readFile)) {
            return new ReviewerRoiSession(
                    sectionId, sourceWidth, sourceHeight);
        }
        try {
            final JsonNode root = json.readTree(readFile.toFile());
            final String schema = root.path("schema").asText();
            if (!SCHEMA.equals(schema) && !SCOPED_SCHEMA.equals(schema)) {
                throw new IllegalStateException("Unsupported manual ROI draft schema");
            }
            if (SCOPED_SCHEMA.equals(schema) && registrationInput.isPresent()) {
                final RegistrationInput recorded = json.treeToValue(root.required("registrationInput"), RegistrationInput.class);
                if (!recorded.equals(registrationInput.orElseThrow())) {
                    throw new IllegalStateException("Saved ROI draft belongs to a different registration C/Z/T. Start a separate review to change input.");
                }
            }
            requireEquals(sectionId, root.path("sectionId").asText(),
                    "section ID");
            requireEquals(sourcePixelSha256,
                    root.path("sourcePixelSha256").asText(),
                    "source pixel identity");
            if (root.path("sourceWidth").asInt() != sourceWidth
                    || root.path("sourceHeight").asInt() != sourceHeight) {
                throw new IllegalStateException(
                        "Autosaved ROI dimensions do not match this section");
            }
            final List<ReviewerRoi> rois = new ArrayList<>();
            for (final JsonNode roi : root.withArray("rois")) {
                rois.add(readRoi(roi));
            }
            return ReviewerRoiSession.restore(sectionId,
                    sourceWidth, sourceHeight, rois,
                    optionalText(root.path("activeRoiId")),
                    optionalText(root.path("activePartId")),
                    root.path("revision").asLong());
        } catch (final IOException error) {
            throw new IllegalStateException(
                    "Could not read the autosaved manual ROI draft", error);
        }
    }

    public void bind(final ReviewerRoiSession session) {
        final ReviewerRoiSession checked = Objects.requireNonNull(
                session, "session");
        schedule(checked.snapshot());
        checked.addListener(() -> schedule(checked.snapshot()));
    }

    public void bind(final ReviewerRoiSession session, final ReviewController controller) {
        bind(session);
        controller.addProjectChangeListener(() -> schedule(session.snapshot()));
    }

    public synchronized void saveNow(
            final ReviewerRoiSession.Snapshot snapshot) {
        final ReviewerRoiSession.Snapshot checked = requireSnapshot(snapshot);
        try {
            final Path parent = file.getParent();
            if (parent == null) {
                throw new IllegalStateException(
                        "Manual ROI autosave needs a parent folder");
            }
            Files.createDirectories(parent);
            final Path temporary = parent.resolve(
                    file.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(
                    temporary.toFile(), document(checked));
            try {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException error) {
            throw new IllegalStateException(
                    "Could not autosave the manual ROI draft", error);
        }
    }

    private synchronized void schedule(
            final ReviewerRoiSession.Snapshot snapshot) {
        final ReviewerRoiSession.Snapshot checked = requireSnapshot(snapshot);
        if (pending != null) {
            pending.cancel(false);
        }
        pending = writer.schedule(() -> saveNow(checked),
                250, TimeUnit.MILLISECONDS);
    }

    private ReviewerRoiSession.Snapshot requireSnapshot(
            final ReviewerRoiSession.Snapshot snapshot) {
        final ReviewerRoiSession.Snapshot checked = Objects.requireNonNull(
                snapshot, "snapshot");
        if (!sectionId.equals(checked.sectionId())
                || sourceWidth != checked.sourceWidth()
                || sourceHeight != checked.sourceHeight()) {
            throw new IllegalArgumentException(
                    "Manual ROI snapshot does not belong to this section");
        }
        return checked;
    }

    private Map<String, Object> document(
            final ReviewerRoiSession.Snapshot snapshot) {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", registrationInput.isPresent() ? SCOPED_SCHEMA : SCHEMA);
        registrationInput.ifPresent(input -> {
            root.put("registrationInput", input);
            final ExportSelection selection = exportSelection.get();
            if (selection.slice() != input.slice() || selection.frame() != input.frame()) {
                throw new IllegalArgumentException("Draft export scope must use registration Z/T");
            }
            root.put("exportSelection", selection);
        });
        root.put("savedAt", Instant.now().toString());
        root.put("sectionId", sectionId);
        root.put("sourceWidth", sourceWidth);
        root.put("sourceHeight", sourceHeight);
        root.put("sourcePixelSha256", sourcePixelSha256);
        root.put("revision", snapshot.revision());
        root.put("activeRoiId", snapshot.activeRoiId().orElse(null));
        root.put("activePartId", snapshot.activePartId().orElse(null));
        root.put("rois", snapshot.rois().stream()
                .map(ManualRoiSessionStore::writeRoi).toList());
        return root;
    }

    private static Map<String, Object> writeRoi(final ReviewerRoi roi) {
        final Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", roi.id());
        value.put("name", roi.name());
        value.put("side", roi.side().name());
        value.put("visible", roi.visible());
        value.put("selectedForExport", roi.selectedForExport());
        value.put("guideLink", roi.guideLink().map(guide -> Map.of(
                "regionId", guide.regionId(),
                "acronym", guide.acronym(),
                "name", guide.name(),
                "includesDescendants", guide.includesDescendants(),
                "atlasIdentityHash", guide.atlasIdentityHash(),
                "coronalLevel", guide.coronalLevel(),
                "alignmentRevision", guide.alignmentRevision()))
                .orElse(null));
        value.put("parts", roi.parts().stream().map(part -> Map.of(
                "id", part.id(),
                "operation", part.operation().name(),
                "finished", part.finished(),
                "vertices", part.vertices().stream().map(vertex -> Map.of(
                        "id", vertex.id(),
                        "x", vertex.sourcePoint().x(),
                        "y", vertex.sourcePoint().y())).toList()))
                .toList());
        return value;
    }

    private static ReviewerRoi readRoi(final JsonNode value) {
        final List<ReviewerRoiPart> parts = new ArrayList<>();
        for (final JsonNode part : value.withArray("parts")) {
            final List<ReviewerRoiVertex> vertices = new ArrayList<>();
            for (final JsonNode vertex : part.withArray("vertices")) {
                vertices.add(new ReviewerRoiVertex(
                        vertex.path("id").asText(),
                        new Point2D(vertex.path("x").asDouble(),
                                vertex.path("y").asDouble())));
            }
            parts.add(new ReviewerRoiPart(part.path("id").asText(),
                    RoiPartOperation.valueOf(
                            part.path("operation").asText()),
                    vertices, part.path("finished").asBoolean()));
        }
        final JsonNode guide = value.path("guideLink");
        final Optional<ReviewerRoiGuideLink> link = guide.isMissingNode()
                || guide.isNull() ? Optional.empty() : Optional.of(
                        new ReviewerRoiGuideLink(
                                guide.path("regionId").asInt(),
                                guide.path("acronym").asText(),
                                guide.path("name").asText(),
                                guide.path("includesDescendants")
                                        .asBoolean(),
                                guide.path("atlasIdentityHash").asText(),
                                guide.path("coronalLevel").asInt(),
                                guide.path("alignmentRevision").asLong()));
        return new ReviewerRoi(value.path("id").asText(),
                value.path("name").asText(),
                ReviewerRoiSide.valueOf(value.path("side").asText()),
                link, parts, value.path("visible").asBoolean(),
                value.path("selectedForExport").asBoolean());
    }

    private static void requireEquals(
            final String expected,
            final String actual,
            final String label) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Autosaved ROI " + label + " does not match");
        }
    }

    private static Optional<String> optionalText(final JsonNode value) {
        if (value.isMissingNode() || value.isNull()) {
            return Optional.empty();
        }
        final String text = value.asText().trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    private static String requireText(
            final String value,
            final String label) {
        final String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return checked;
    }
}
