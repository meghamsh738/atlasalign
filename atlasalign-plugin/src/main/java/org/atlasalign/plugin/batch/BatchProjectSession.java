package org.atlasalign.plugin.batch;

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
import java.util.concurrent.CopyOnWriteArrayList;
import org.atlasalign.core.Point2D;

/**
 * Small persistent queue for standalone images or marked whole-slide
 * sections. Pixel data never enters this file; every item is tied to the
 * verified source SHA-256 and original source-coordinate bounds.
 */
public final class BatchProjectSession {

    static final String SCHEMA = "atlasalign-review-batch-v1";
    static final String FILE_NAME = "batch-project.json";

    private final String projectName;
    private final String createdAt;
    private final Path projectDirectory;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private List<BatchReviewItem> items;
    private int selectedIndex;

    public BatchProjectSession(
            final String projectName,
            final Path projectDirectory,
            final List<BatchReviewItem> items) {
        this(projectName, Instant.now().toString(), projectDirectory,
                items, 0, true);
    }

    static BatchProjectSession restore(
            final String projectName,
            final String createdAt,
            final Path projectDirectory,
            final List<BatchReviewItem> items,
            final int selectedIndex) {
        return new BatchProjectSession(projectName, createdAt,
                projectDirectory, items, selectedIndex, false);
    }

    private BatchProjectSession(
            final String projectName,
            final String createdAt,
            final Path projectDirectory,
            final List<BatchReviewItem> items,
            final int selectedIndex,
            final boolean saveImmediately) {
        this.projectName = requireText(projectName, "projectName");
        this.projectDirectory = Objects.requireNonNull(
                projectDirectory, "projectDirectory")
                .toAbsolutePath().normalize();
        this.items = List.copyOf(Objects.requireNonNull(items, "items"));
        if (this.items.isEmpty()) {
            throw new IllegalArgumentException(
                    "A batch project requires at least one review item");
        }
        requireUniqueIdsAndNames(this.items);
        this.createdAt = requireText(createdAt, "createdAt");
        if (selectedIndex < 0 || selectedIndex >= this.items.size()) {
            throw new IllegalArgumentException(
                    "Saved batch selection is outside the queue");
        }
        this.selectedIndex = selectedIndex;
        if (saveImmediately) {
            save();
        }
    }

    synchronized List<BatchReviewItem> items() {
        return items;
    }

    synchronized BatchReviewItem selected() {
        return items.get(selectedIndex);
    }

    synchronized int selectedIndex() {
        return selectedIndex;
    }

    synchronized void select(final int index) {
        if (index < 0 || index >= items.size()) {
            throw new IllegalArgumentException(
                    "Batch selection is outside the queue");
        }
        selectedIndex = index;
        notifyListeners();
    }

    synchronized Optional<Integer> nextPendingIndex() {
        for (int offset = 1; offset <= items.size(); offset++) {
            final int index = (selectedIndex + offset) % items.size();
            if (items.get(index).section().status()
                    == BatchReviewStatus.PENDING
                    || items.get(index).section().status()
                    == BatchReviewStatus.ERROR) {
                return Optional.of(index);
            }
        }
        return Optional.empty();
    }

    synchronized void renameSelected(final String name) {
        final String checked = uniqueName(requireText(name, "name"),
                selected().section().id());
        replaceSelected(selected().section().withName(checked));
    }

    synchronized void setSelectedCoronalLevel(final int level) {
        replaceSelected(selected().section()
                .withInitialCoronalLevel(level));
    }

    synchronized void setStatus(
            final String sectionId,
            final BatchReviewStatus status,
            final String detail) {
        final int index = indexOf(sectionId);
        final List<BatchReviewItem> updated = new ArrayList<>(items);
        updated.set(index, updated.get(index).withSection(
                updated.get(index).section().withStatus(status, detail)));
        items = List.copyOf(updated);
        save();
        notifyListeners();
    }

    synchronized void moveSelected(final int delta) {
        final int target = selectedIndex + delta;
        if (target < 0 || target >= items.size()) {
            return;
        }
        final List<BatchReviewItem> updated = new ArrayList<>(items);
        final BatchReviewItem item = updated.remove(selectedIndex);
        updated.add(target, item);
        items = List.copyOf(updated);
        selectedIndex = target;
        save();
        notifyListeners();
    }

    Path projectDirectory() {
        return projectDirectory;
    }

    public Path projectFile() {
        return projectDirectory.resolve(FILE_NAME);
    }

    void addListener(final Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public synchronized void save() {
        try {
            Files.createDirectories(projectDirectory);
            final Path target = projectFile();
            final Path temporary = projectDirectory.resolve(
                    FILE_NAME + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(
                    temporary.toFile(), document());
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException error) {
            throw new IllegalStateException(
                    "Could not save the AtlasAlign batch project", error);
        }
    }

    private Map<String, Object> document() {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("projectName", projectName);
        root.put("createdAt", createdAt);
        root.put("updatedAt", Instant.now().toString());
        root.put("selectedIndex", selectedIndex);
        final List<Map<String, Object>> sections = new ArrayList<>();
        for (int order = 0; order < items.size(); order++) {
            final BatchReviewItem item = items.get(order);
            final BatchSection section = item.section();
            final Map<String, Object> value = new LinkedHashMap<>();
            value.put("order", order + 1);
            value.put("id", section.id());
            value.put("name", section.name());
            value.put("sourceName", section.sourceName());
            value.put("sourcePixelSha256", section.sourcePixelSha256());
            value.put("sourceWidth", section.sourceWidth());
            value.put("sourceHeight", section.sourceHeight());
            value.put("verifiedSource", item.verifiedSource());
            value.put("bounds", Map.of(
                    "minimumX", section.minimumX(),
                    "minimumY", section.minimumY(),
                    "width", section.width(),
                    "height", section.height()));
            value.put("markerVertices", section.markerVertices().stream()
                    .map(point -> Map.of("x", point.x(), "y", point.y()))
                    .toList());
            value.put("initialCoronalLevel",
                    section.initialCoronalLevel());
            value.put("status", section.status().name());
            value.put("statusDetail", section.statusDetail());
            sections.add(value);
        }
        root.put("sections", sections);
        return root;
    }

    private void replaceSelected(final BatchSection section) {
        final List<BatchReviewItem> updated = new ArrayList<>(items);
        updated.set(selectedIndex,
                updated.get(selectedIndex).withSection(section));
        items = List.copyOf(updated);
        save();
        notifyListeners();
    }

    private int indexOf(final String sectionId) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).section().id().equals(sectionId)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unknown batch section");
    }

    private String uniqueName(
            final String requested,
            final String excludedId) {
        String candidate = requested;
        int suffix = 2;
        while (nameExists(candidate, excludedId)) {
            candidate = requested + " " + suffix++;
        }
        return candidate;
    }

    private boolean nameExists(
            final String candidate,
            final String excludedId) {
        return items.stream().map(BatchReviewItem::section)
                .anyMatch(section -> !section.id().equals(excludedId)
                        && section.name().equalsIgnoreCase(candidate));
    }

    private void notifyListeners() {
        listeners.forEach(Runnable::run);
    }

    private static void requireUniqueIdsAndNames(
            final List<BatchReviewItem> values) {
        final long uniqueIds = values.stream().map(item ->
                item.section().id()).distinct().count();
        final long uniqueNames = values.stream().map(item ->
                item.section().name().toLowerCase(java.util.Locale.ROOT))
                .distinct().count();
        if (uniqueIds != values.size() || uniqueNames != values.size()) {
            throw new IllegalArgumentException(
                    "Batch section identifiers and names must be unique");
        }
    }

    private static String requireText(
            final String value,
            final String name) {
        final String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
