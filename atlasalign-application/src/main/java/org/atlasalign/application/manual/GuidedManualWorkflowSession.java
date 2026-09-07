package org.atlasalign.application.manual;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * In-memory revision tree. Unlike a linear undo stack, editing after undo
 * preserves every prior future as an accessible redo branch.
 */
public final class GuidedManualWorkflowSession {

    private final GuidedManualWorkflowContent baseline;
    private final Map<Long, ManualWorkflowRevision> revisions =
            new LinkedHashMap<>();
    private final Map<Long, List<Long>> children = new LinkedHashMap<>();
    private long nextRevisionId = 1;
    private long currentRevisionId;
    private ManualAlignmentStage displayedStage =
            ManualAlignmentStage.PREPARE_SECTION;

    public GuidedManualWorkflowSession() {
        this(GuidedManualWorkflowContent.empty());
    }

    public GuidedManualWorkflowSession(
            final GuidedManualWorkflowContent baseline) {
        this.baseline = Objects.requireNonNull(baseline, "baseline");
        revisions.put(0L, new ManualWorkflowRevision(
                0L, OptionalLong.empty(), ManualWorkflowOperation.INITIAL,
                ManualAlignmentStage.PREPARE_SECTION,
                "Initial guided-manual workflow content", baseline));
        children.put(0L, new ArrayList<>());
    }

    public synchronized ManualWorkflowRevision apply(
            final ManualWorkflowEdit edit) {
        Objects.requireNonNull(edit, "edit");
        final GuidedManualWorkflowContent before = content();
        final ManualAlignmentStage affectedStage = edit.affectedStage(before);
        final GuidedManualWorkflowContent after = Objects.requireNonNull(
                edit.apply(before, baseline), "edit returned null content");
        if (after.equals(before)) {
            throw new IllegalArgumentException("Workflow edit made no change");
        }
        final long id = nextRevisionId++;
        final ManualWorkflowRevision revision = new ManualWorkflowRevision(
                id, OptionalLong.of(currentRevisionId), edit.operation(),
                affectedStage, edit.description(), after);
        revisions.put(id, revision);
        children.computeIfAbsent(currentRevisionId, ignored -> new ArrayList<>())
                .add(id);
        children.put(id, new ArrayList<>());
        currentRevisionId = id;
        return revision;
    }

    public synchronized boolean back() {
        if (displayedStage.ordinal() == 0) {
            return false;
        }
        displayedStage = ManualAlignmentStage.values()[displayedStage.ordinal() - 1];
        return true;
    }

    public synchronized boolean next() {
        if (displayedStage.ordinal() + 1 == ManualAlignmentStage.values().length) {
            return false;
        }
        displayedStage = ManualAlignmentStage.values()[displayedStage.ordinal() + 1];
        return true;
    }

    public synchronized void showStage(final ManualAlignmentStage stage) {
        displayedStage = Objects.requireNonNull(stage, "stage");
    }

    public synchronized ManualAlignmentStage displayedStage() {
        return displayedStage;
    }

    public synchronized boolean undo() {
        final ManualWorkflowRevision current = currentRevision();
        if (current.parentId().isEmpty()) {
            return false;
        }
        currentRevisionId = current.parentId().orElseThrow();
        return true;
    }

    public synchronized List<ManualWorkflowRevision> redoChoices() {
        return children.getOrDefault(currentRevisionId, List.of()).stream()
                .map(revisions::get).toList();
    }

    public synchronized void redo(final long revisionId) {
        if (!children.getOrDefault(currentRevisionId, List.of())
                .contains(revisionId)) {
            throw new IllegalArgumentException(
                    "Revision is not a direct redo branch of the current revision");
        }
        currentRevisionId = revisionId;
    }

    /** Checks out any preserved history node without creating a revision. */
    public synchronized void checkout(final long revisionId) {
        if (!revisions.containsKey(revisionId)) {
            throw new IllegalArgumentException("Unknown revision " + revisionId);
        }
        currentRevisionId = revisionId;
    }

    public synchronized ManualWorkflowRevision resetCurrentStep() {
        return apply(new ManualWorkflowEdit.ResetStage(displayedStage));
    }

    public synchronized ManualWorkflowRevision resetAll() {
        return apply(new ManualWorkflowEdit.ResetAll());
    }

    public synchronized GuidedManualWorkflowContent content() {
        return currentRevision().content();
    }

    public synchronized ManualWorkflowRevision currentRevision() {
        return revisions.get(currentRevisionId);
    }

    public synchronized List<ManualWorkflowRevision> visibleHistory() {
        return List.copyOf(revisions.values());
    }
}
