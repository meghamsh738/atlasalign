package org.atlasalign.application.roi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.atlasalign.core.Point2D;

/**
 * In-memory exact manual-ROI editor with a bounded, non-scientific history.
 * No operation in this class changes an alignment review revision.
 */
public final class ReviewerRoiSession {

    public static final int MAXIMUM_HISTORY = 100;

    public record Snapshot(
            String sectionId,
            int sourceWidth,
            int sourceHeight,
            List<ReviewerRoi> rois,
            Optional<String> activeRoiId,
            Optional<String> activePartId,
            long revision) {

        public Snapshot {
            sectionId = requireText(sectionId, "sectionId");
            if (sourceWidth <= 0 || sourceHeight <= 0 || revision < 0) {
                throw new IllegalArgumentException(
                        "ROI session dimensions and revision are invalid");
            }
            rois = List.copyOf(Objects.requireNonNull(rois, "rois"));
            activeRoiId = Objects.requireNonNull(
                    activeRoiId, "activeRoiId");
            activePartId = Objects.requireNonNull(
                    activePartId, "activePartId");
        }

        public Optional<ReviewerRoi> activeRoi() {
            return activeRoiId.flatMap(id -> rois.stream()
                    .filter(roi -> roi.id().equals(id)).findFirst());
        }

        public Optional<ReviewerRoiPart> activePart() {
            return activeRoi().flatMap(roi -> activePartId.flatMap(id ->
                    roi.parts().stream().filter(part -> part.id().equals(id))
                            .findFirst()));
        }

        public List<ReviewerRoi> exportableRois() {
            return rois.stream().filter(ReviewerRoi::finished)
                    .filter(ReviewerRoi::selectedForExport).toList();
        }
    }

    private record EditorState(
            List<ReviewerRoi> rois,
            Optional<String> activeRoiId,
            Optional<String> activePartId) {
        private EditorState {
            rois = List.copyOf(rois);
        }
    }

    private final String sectionId;
    private final int sourceWidth;
    private final int sourceHeight;
    private final Deque<EditorState> undo = new ArrayDeque<>();
    private final Deque<EditorState> redo = new ArrayDeque<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private List<ReviewerRoi> rois = List.of();
    private Optional<String> activeRoiId = Optional.empty();
    private Optional<String> activePartId = Optional.empty();
    private long idSequence;
    private long revision;

    public ReviewerRoiSession(
            final String sectionId,
            final int sourceWidth,
            final int sourceHeight) {
        this.sectionId = requireText(sectionId, "sectionId");
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException(
                    "Source dimensions must be positive");
        }
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
    }

    /** Restores an autosaved editor state with a fresh, empty undo history. */
    public static ReviewerRoiSession restore(
            final String sectionId,
            final int sourceWidth,
            final int sourceHeight,
            final List<ReviewerRoi> savedRois,
            final Optional<String> savedActiveRoiId,
            final Optional<String> savedActivePartId,
            final long savedRevision) {
        if (savedRevision < 0) {
            throw new IllegalArgumentException(
                    "Saved ROI revision must be non-negative");
        }
        final ReviewerRoiSession session = new ReviewerRoiSession(
                sectionId, sourceWidth, sourceHeight);
        final List<ReviewerRoi> checked = List.copyOf(
                Objects.requireNonNull(savedRois, "savedRois"));
        final Map<String, Boolean> ids = new LinkedHashMap<>();
        for (final ReviewerRoi roi : checked) {
            if (ids.put(roi.id(), Boolean.TRUE) != null) {
                throw new IllegalArgumentException(
                        "Saved ROI identifiers must be unique");
            }
            for (final ReviewerRoiPart part : roi.parts()) {
                if (ids.put(part.id(), Boolean.TRUE) != null) {
                    throw new IllegalArgumentException(
                            "Saved ROI part identifiers must be unique");
                }
                for (final ReviewerRoiVertex vertex : part.vertices()) {
                    if (ids.put(vertex.id(), Boolean.TRUE) != null) {
                        throw new IllegalArgumentException(
                                "Saved ROI vertex identifiers must be unique");
                    }
                    session.requireInsideSource(vertex.sourcePoint());
                }
            }
        }
        final long uniqueNames = checked.stream()
                .map(roi -> roi.name().toLowerCase(java.util.Locale.ROOT))
                .distinct().count();
        if (uniqueNames != checked.size()) {
            throw new IllegalArgumentException(
                    "Saved ROI names must be unique");
        }
        final Optional<String> activeRoi = Objects.requireNonNull(
                savedActiveRoiId, "savedActiveRoiId");
        final Optional<String> activePart = Objects.requireNonNull(
                savedActivePartId, "savedActivePartId");
        final ReviewerRoi selected = activeRoi.map(id -> checked.stream()
                .filter(roi -> roi.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Saved active ROI does not exist"))).orElse(null);
        if (activePart.isPresent()) {
            if (selected == null) {
                throw new IllegalArgumentException(
                        "Saved active ROI part has no active ROI");
            }
            final ReviewerRoiPart part = selected.parts().stream()
                    .filter(candidate -> candidate.id().equals(
                            activePart.orElseThrow()))
                    .findFirst().orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Saved active ROI part does not exist"));
            if (part.finished()) {
                throw new IllegalArgumentException(
                        "Saved active ROI part is already finished");
            }
        }
        session.rois = checked;
        session.activeRoiId = activeRoi;
        session.activePartId = activePart;
        session.revision = savedRevision;
        session.idSequence = maximumIdentifierSequence(ids.keySet());
        return session;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(sectionId, sourceWidth, sourceHeight, rois,
                activeRoiId, activePartId, revision);
    }

    public void addListener(final Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(final Runnable listener) {
        listeners.remove(listener);
    }

    public synchronized String newPolygon(
            final String requestedName,
            final ReviewerRoiSide side,
            final RoiPartOperation operation) {
        final String name = uniqueName(requestedName, Optional.empty());
        final String roiId = nextId("roi");
        final String partId = nextId("part");
        mutate(() -> {
            final List<ReviewerRoi> updated = new ArrayList<>(rois);
            updated.add(new ReviewerRoi(roiId, name,
                    Objects.requireNonNull(side, "side"), Optional.empty(),
                    List.of(new ReviewerRoiPart(partId,
                            Objects.requireNonNull(operation, "operation"),
                            List.of(), false)), true, true));
            rois = List.copyOf(updated);
            activeRoiId = Optional.of(roiId);
            activePartId = Optional.of(partId);
        });
        return roiId;
    }

    /** Adds one completed guide-derived ROI without invoking a warp solver. */
    public synchronized String createFromGuide(
            final String requestedName,
            final ReviewerRoiSide side,
            final ReviewerRoiGuideLink guideLink,
            final List<List<Point2D>> sourceLoops) {
        final List<List<Point2D>> loops = List.copyOf(
                Objects.requireNonNull(sourceLoops, "sourceLoops"));
        if (loops.isEmpty() || loops.stream().anyMatch(loop ->
                loop == null || loop.size() < 3)) {
            throw new IllegalArgumentException(
                    "Guide conversion requires at least one closed polygon");
        }
        final String name = uniqueName(requestedName, Optional.empty());
        final String roiId = nextId("roi");
        final List<ReviewerRoiPart> parts = new ArrayList<>();
        for (final List<Point2D> loop : loops) {
            final String partId = nextId("part");
            final List<ReviewerRoiVertex> vertices = new ArrayList<>();
            for (final Point2D point : loop) {
                vertices.add(new ReviewerRoiVertex(nextId("vertex"),
                        requireInsideSource(point)));
            }
            parts.add(new ReviewerRoiPart(partId, RoiPartOperation.ADD,
                    vertices, true));
        }
        final ReviewerRoi added = new ReviewerRoi(roiId, name,
                Objects.requireNonNull(side, "side"),
                Optional.of(Objects.requireNonNull(guideLink, "guideLink")),
                parts, true, true);
        // Reject an empty or completely clipped conversion atomically.
        ManualRoiFootprint.rasterize(added, sourceWidth, sourceHeight);
        mutate(() -> {
            final List<ReviewerRoi> updated = new ArrayList<>(rois);
            updated.add(added);
            rois = List.copyOf(updated);
            activeRoiId = Optional.of(roiId);
            activePartId = Optional.empty();
        });
        return roiId;
    }

    /** Imports one or more completed source-coordinate polygon parts. */
    public synchronized String importCompleted(
            final String requestedName,
            final ReviewerRoiSide side,
            final List<ReviewerRoiPart> importedParts) {
        final String name = uniqueName(requestedName, Optional.empty());
        final String roiId = nextId("roi");
        final List<ReviewerRoiPart> supplied = List.copyOf(importedParts);
        if (supplied.isEmpty() || supplied.stream().anyMatch(part ->
                !part.finished())) {
            throw new IllegalArgumentException(
                    "Imported ROI parts must all be finished");
        }
        final List<ReviewerRoiPart> parts = new ArrayList<>();
        for (final ReviewerRoiPart part : supplied) {
            final List<ReviewerRoiVertex> vertices = new ArrayList<>();
            for (final ReviewerRoiVertex vertex : part.vertices()) {
                vertices.add(new ReviewerRoiVertex(nextId("vertex"),
                        requireInsideSource(vertex.sourcePoint())));
            }
            parts.add(new ReviewerRoiPart(nextId("part"),
                    part.operation(), vertices, true));
        }
        final ReviewerRoi added = new ReviewerRoi(roiId, name,
                side, Optional.empty(), parts, true, true);
        ManualRoiFootprint.rasterize(added, sourceWidth, sourceHeight);
        mutate(() -> {
            final List<ReviewerRoi> updated = new ArrayList<>(rois);
            updated.add(added);
            rois = List.copyOf(updated);
            activeRoiId = Optional.of(roiId);
            activePartId = Optional.empty();
        });
        return roiId;
    }

    public synchronized void select(final String roiId) {
        requireRoi(roiId);
        activeRoiId = Optional.of(roiId);
        activePartId = requireRoi(roiId).parts().stream()
                .filter(part -> !part.finished()).map(ReviewerRoiPart::id)
                .findFirst();
        changedWithoutHistory();
    }

    public synchronized void addPart(final RoiPartOperation operation) {
        final ReviewerRoi roi = activeRoi();
        if (roi.parts().stream().anyMatch(part -> !part.finished())) {
            throw new IllegalStateException(
                    "Finish or cancel the current polygon first");
        }
        final String partId = nextId("part");
        mutate(() -> {
            final List<ReviewerRoiPart> parts = new ArrayList<>(roi.parts());
            parts.add(new ReviewerRoiPart(partId,
                    Objects.requireNonNull(operation, "operation"),
                    List.of(), false));
            replace(roi.withParts(parts));
            activePartId = Optional.of(partId);
        });
    }

    public synchronized void addVertex(final Point2D sourcePoint) {
        final ReviewerRoi roi = activeRoi();
        final ReviewerRoiPart part = activePart();
        if (part.finished()) {
            throw new IllegalStateException(
                    "Start a new part before adding vertices");
        }
        final ReviewerRoiVertex vertex = new ReviewerRoiVertex(
                nextId("vertex"), requireInsideSource(sourcePoint));
        mutate(() -> replacePart(roi, part,
                append(part.vertices(), vertex)));
    }

    /** Adds one sampled freehand stroke as a single local-history edit. */
    public synchronized void addVertices(
            final List<Point2D> sourcePoints) {
        final ReviewerRoi roi = activeRoi();
        final ReviewerRoiPart part = activePart();
        if (part.finished()) {
            throw new IllegalStateException(
                    "Start a new part before adding vertices");
        }
        final List<Point2D> checked = List.copyOf(
                Objects.requireNonNull(sourcePoints, "sourcePoints"));
        if (checked.isEmpty()) {
            return;
        }
        final List<ReviewerRoiVertex> vertices = new ArrayList<>(
                part.vertices());
        for (final Point2D point : checked) {
            vertices.add(new ReviewerRoiVertex(
                    nextId("vertex"), requireInsideSource(point)));
        }
        mutate(() -> replacePart(roi, part, vertices));
    }

    public synchronized void finishActivePart() {
        final ReviewerRoi roi = activeRoi();
        final ReviewerRoiPart part = activePart();
        if (part.vertices().size() < 3) {
            throw new IllegalArgumentException(
                    "Draw at least three polygon vertices before finishing");
        }
        mutate(() -> {
            replacePart(roi, part, part.vertices(), true);
            activePartId = Optional.empty();
        });
        final ReviewerRoi finished = requireRoi(roi.id());
        if (finished.finished()) {
            try {
                ManualRoiFootprint.rasterize(
                        finished, sourceWidth, sourceHeight);
            } catch (final RuntimeException error) {
                undo();
                throw error;
            }
        }
    }

    public synchronized void cancelDraft() {
        final ReviewerRoi roi = activeRoiId.isEmpty()
                ? null : requireRoi(activeRoiId.orElseThrow());
        if (roi == null) {
            return;
        }
        final Optional<ReviewerRoiPart> unfinished = roi.parts().stream()
                .filter(part -> !part.finished()).findFirst();
        if (unfinished.isEmpty()) {
            return;
        }
        mutate(() -> {
            final List<ReviewerRoiPart> kept = roi.parts().stream()
                    .filter(part -> !part.id().equals(
                            unfinished.orElseThrow().id())).toList();
            if (kept.isEmpty()) {
                rois = rois.stream().filter(candidate ->
                        !candidate.id().equals(roi.id())).toList();
                activeRoiId = rois.isEmpty() ? Optional.empty()
                        : Optional.of(rois.get(rois.size() - 1).id());
            } else {
                replace(roi.withParts(kept));
            }
            activePartId = Optional.empty();
        });
    }

    public synchronized void moveVertex(
            final String roiId,
            final String partId,
            final String vertexId,
            final Point2D sourcePoint) {
        final ReviewerRoi roi = requireRoi(roiId);
        final ReviewerRoiPart part = requirePart(roi, partId);
        final Point2D checked = requireInsideSource(sourcePoint);
        final List<ReviewerRoiVertex> vertices = new ArrayList<>(
                part.vertices());
        final int index = vertexIndex(part, vertexId);
        if (vertices.get(index).sourcePoint().equals(checked)) {
            return;
        }
        vertices.set(index, new ReviewerRoiVertex(vertexId, checked));
        mutate(() -> replacePart(roi, part, vertices));
    }

    public synchronized void insertAfter(
            final String roiId,
            final String partId,
            final String afterVertexId,
            final Point2D sourcePoint) {
        final ReviewerRoi roi = requireRoi(roiId);
        final ReviewerRoiPart part = requirePart(roi, partId);
        final List<ReviewerRoiVertex> vertices = new ArrayList<>(
                part.vertices());
        final int index = vertexIndex(part, afterVertexId);
        vertices.add(index + 1, new ReviewerRoiVertex(
                nextId("vertex"), requireInsideSource(sourcePoint)));
        mutate(() -> replacePart(roi, part, vertices));
    }

    public synchronized void deleteVertex(
            final String roiId,
            final String partId,
            final String vertexId) {
        final ReviewerRoi roi = requireRoi(roiId);
        final ReviewerRoiPart part = requirePart(roi, partId);
        if (part.finished() && part.vertices().size() <= 3) {
            throw new IllegalArgumentException(
                    "A finished polygon must retain at least three vertices");
        }
        final List<ReviewerRoiVertex> vertices = new ArrayList<>(
                part.vertices());
        vertices.remove(vertexIndex(part, vertexId));
        mutate(() -> replacePart(roi, part, vertices));
    }

    public synchronized void translate(
            final String roiId,
            final double dx,
            final double dy) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
            throw new IllegalArgumentException(
                    "ROI translation must be finite");
        }
        final ReviewerRoi roi = requireRoi(roiId);
        final List<ReviewerRoiPart> translated = roi.parts().stream()
                .map(part -> new ReviewerRoiPart(part.id(), part.operation(),
                        part.vertices().stream().map(vertex ->
                                new ReviewerRoiVertex(vertex.id(),
                                        requireInsideSource(new Point2D(
                                                vertex.sourcePoint().x() + dx,
                                                vertex.sourcePoint().y() + dy))))
                                .toList(), part.finished()))
                .toList();
        mutate(() -> replace(roi.withParts(translated)));
    }

    /**
     * Reduces the selected polygon to retained source-coordinate vertices as
     * one local ROI-history edit. Existing geometry is untouched until this
     * method is called explicitly.
     */
    public synchronized void reduceVertices(
            final String roiId,
            final int totalPoints) {
        final ReviewerRoi roi = requireRoi(roiId);
        if (!roi.finished()) {
            throw new IllegalStateException(
                    "Finish every ROI polygon part before reducing points");
        }
        final ReviewerRoi reduced = ReviewerRoiVertexReducer.reduce(
                roi, totalPoints);
        if (reduced.equals(roi)) {
            return;
        }
        // Fail before history changes if simplification emptied the exact
        // ADD-minus-SUBTRACT footprint or moved it outside the source.
        ManualRoiFootprint.rasterize(reduced, sourceWidth, sourceHeight);
        mutate(() -> replace(reduced));
    }

    public synchronized String duplicate(final String roiId) {
        final ReviewerRoi source = requireRoi(roiId);
        final String duplicateId = nextId("roi");
        final List<ReviewerRoiPart> parts = source.parts().stream()
                .map(part -> new ReviewerRoiPart(nextId("part"),
                        part.operation(), part.vertices().stream()
                                .map(vertex -> new ReviewerRoiVertex(
                                        nextId("vertex"),
                                        vertex.sourcePoint()))
                                .toList(), part.finished()))
                .toList();
        final ReviewerRoi duplicate = new ReviewerRoi(duplicateId,
                uniqueName(source.name() + " copy", Optional.empty()),
                source.side(), source.guideLink(), parts,
                source.visible(), source.selectedForExport());
        mutate(() -> {
            final List<ReviewerRoi> updated = new ArrayList<>(rois);
            updated.add(duplicate);
            rois = List.copyOf(updated);
            activeRoiId = Optional.of(duplicateId);
            activePartId = Optional.empty();
        });
        return duplicateId;
    }

    public synchronized void rename(
            final String roiId,
            final String requestedName) {
        final ReviewerRoi roi = requireRoi(roiId);
        final String name = uniqueName(requestedName, Optional.of(roiId));
        if (!name.equals(roi.name())) {
            mutate(() -> replace(roi.withName(name)));
        }
    }

    public synchronized void setSide(
            final String roiId,
            final ReviewerRoiSide side) {
        final ReviewerRoi roi = requireRoi(roiId);
        if (roi.side() != side) {
            mutate(() -> replace(roi.withSide(side)));
        }
    }

    public synchronized void setVisible(
            final String roiId,
            final boolean visible) {
        final ReviewerRoi roi = requireRoi(roiId);
        if (roi.visible() != visible) {
            mutate(() -> replace(roi.withVisibility(visible)));
        }
    }

    public synchronized void setSelectedForExport(
            final String roiId,
            final boolean selected) {
        final ReviewerRoi roi = requireRoi(roiId);
        if (roi.selectedForExport() != selected) {
            mutate(() -> replace(roi.withExportSelection(selected)));
        }
    }

    public synchronized void delete(final String roiId) {
        deleteAll(List.of(roiId));
    }

    /** Deletes a selection atomically, with one Undo restoring every ROI. */
    public synchronized void deleteAll(final java.util.Collection<String> roiIds) {
        final var ids = java.util.Set.copyOf(Objects.requireNonNull(roiIds, "roiIds"));
        ids.forEach(this::requireRoi);
        if (ids.isEmpty()) return;
        mutate(() -> {
            rois = rois.stream().filter(roi -> !ids.contains(roi.id())).toList();
            if (activeRoiId.filter(ids::contains).isPresent()) {
                activeRoiId = rois.isEmpty() ? Optional.empty()
                        : Optional.of(rois.get(rois.size() - 1).id());
                activePartId = Optional.empty();
            }
        });
    }

    public synchronized boolean canUndo() {
        return !undo.isEmpty();
    }

    public synchronized boolean canRedo() {
        return !redo.isEmpty();
    }

    public synchronized void undo() {
        if (undo.isEmpty()) {
            return;
        }
        redo.push(editorState());
        restore(undo.pop());
        revision++;
        notifyListeners();
    }

    public synchronized void redo() {
        if (redo.isEmpty()) {
            return;
        }
        undo.push(editorState());
        restore(redo.pop());
        revision++;
        notifyListeners();
    }

    private void mutate(final Runnable mutation) {
        final EditorState before = editorState();
        mutation.run();
        if (before.equals(editorState())) {
            return;
        }
        undo.push(before);
        while (undo.size() > MAXIMUM_HISTORY) {
            undo.removeLast();
        }
        redo.clear();
        revision++;
        notifyListeners();
    }

    private void changedWithoutHistory() {
        revision++;
        notifyListeners();
    }

    private void notifyListeners() {
        listeners.forEach(Runnable::run);
    }

    private EditorState editorState() {
        return new EditorState(rois, activeRoiId, activePartId);
    }

    private void restore(final EditorState state) {
        rois = state.rois();
        activeRoiId = state.activeRoiId();
        activePartId = state.activePartId();
    }

    private ReviewerRoi activeRoi() {
        return activeRoiId.map(this::requireRoi).orElseThrow(() ->
                new IllegalStateException("Select or create an ROI first"));
    }

    private ReviewerRoiPart activePart() {
        final ReviewerRoi roi = activeRoi();
        return activePartId.map(id -> requirePart(roi, id))
                .orElseThrow(() -> new IllegalStateException(
                        "Start a polygon part first"));
    }

    private ReviewerRoi requireRoi(final String id) {
        return rois.stream().filter(roi -> roi.id().equals(id))
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("Unknown ROI " + id));
    }

    private static ReviewerRoiPart requirePart(
            final ReviewerRoi roi,
            final String id) {
        return roi.parts().stream().filter(part -> part.id().equals(id))
                .findFirst().orElseThrow(() ->
                        new IllegalArgumentException("Unknown ROI part " + id));
    }

    private void replace(final ReviewerRoi replacement) {
        final List<ReviewerRoi> updated = new ArrayList<>(rois);
        for (int index = 0; index < updated.size(); index++) {
            if (updated.get(index).id().equals(replacement.id())) {
                updated.set(index, replacement);
                rois = List.copyOf(updated);
                return;
            }
        }
        throw new IllegalArgumentException(
                "Unknown ROI " + replacement.id());
    }

    private void replacePart(
            final ReviewerRoi roi,
            final ReviewerRoiPart original,
            final List<ReviewerRoiVertex> vertices) {
        replacePart(roi, original, vertices, original.finished());
    }

    private void replacePart(
            final ReviewerRoi roi,
            final ReviewerRoiPart original,
            final List<ReviewerRoiVertex> vertices,
            final boolean finished) {
        final List<ReviewerRoiPart> parts = new ArrayList<>(roi.parts());
        final int index = parts.indexOf(original);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "ROI part does not belong to the selected ROI");
        }
        parts.set(index, new ReviewerRoiPart(original.id(),
                original.operation(), vertices, finished));
        replace(roi.withParts(parts));
    }

    private static List<ReviewerRoiVertex> append(
            final List<ReviewerRoiVertex> current,
            final ReviewerRoiVertex vertex) {
        final List<ReviewerRoiVertex> result = new ArrayList<>(current);
        result.add(vertex);
        return List.copyOf(result);
    }

    private static int vertexIndex(
            final ReviewerRoiPart part,
            final String vertexId) {
        for (int index = 0; index < part.vertices().size(); index++) {
            if (part.vertices().get(index).id().equals(vertexId)) {
                return index;
            }
        }
        throw new IllegalArgumentException(
                "Unknown ROI vertex " + vertexId);
    }

    private Point2D requireInsideSource(final Point2D point) {
        final Point2D checked = Objects.requireNonNull(point, "point");
        if (!Double.isFinite(checked.x()) || !Double.isFinite(checked.y())
                || checked.x() < 0 || checked.x() > sourceWidth - 1.0
                || checked.y() < 0 || checked.y() > sourceHeight - 1.0) {
            throw new IllegalArgumentException(
                    "ROI point lies outside source pixel-centre bounds");
        }
        return checked;
    }

    private String uniqueName(
            final String requested,
            final Optional<String> allowedRoiId) {
        final String base = requireText(requested, "ROI name");
        if (rois.stream().noneMatch(roi -> !allowedRoiId
                .equals(Optional.of(roi.id()))
                && roi.name().equalsIgnoreCase(base))) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            final String candidate = base + " " + suffix;
            if (rois.stream().noneMatch(roi -> !allowedRoiId
                    .equals(Optional.of(roi.id()))
                    && roi.name().equalsIgnoreCase(candidate))) {
                return candidate;
            }
        }
    }

    private String nextId(final String prefix) {
        idSequence++;
        return sectionId.replaceAll("[^A-Za-z0-9._-]+", "-")
                + "-" + prefix + "-" + idSequence;
    }

    private static long maximumIdentifierSequence(
            final Iterable<String> identifiers) {
        long maximum = 0;
        for (final String identifier : identifiers) {
            final int separator = identifier.lastIndexOf('-');
            if (separator < 0 || separator + 1 >= identifier.length()) {
                continue;
            }
            try {
                maximum = Math.max(maximum, Long.parseLong(
                        identifier.substring(separator + 1)));
            } catch (final NumberFormatException ignored) {
                // Imported stable identifiers need not use the local suffix.
            }
        }
        return maximum;
    }

    private static String requireText(
            final String value,
            final String field) {
        final String checked = Objects.requireNonNull(value, field).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return checked;
    }
}
