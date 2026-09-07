package org.atlasalign.application.manual;

import java.util.Objects;
import java.util.OptionalLong;

/** Deterministic revision metadata and immutable content snapshot. */
public record ManualWorkflowRevision(
        long id,
        OptionalLong parentId,
        ManualWorkflowOperation operation,
        ManualAlignmentStage stage,
        String description,
        GuidedManualWorkflowContent content) {

    public ManualWorkflowRevision {
        parentId = Objects.requireNonNull(parentId, "parentId");
        operation = Objects.requireNonNull(operation, "operation");
        stage = Objects.requireNonNull(stage, "stage");
        description = Objects.requireNonNull(description, "description").trim();
        content = Objects.requireNonNull(content, "content");
        if (id < 0 || description.isEmpty()
                || (id == 0 && parentId.isPresent())
                || (id > 0 && parentId.isEmpty())) {
            throw new IllegalArgumentException("Revision metadata is invalid");
        }
    }
}
