package org.atlasalign.application.manual;

import java.util.Objects;

/** A directed adjacent contour edge identified by stable vertex IDs. */
public record ContourSegment(String fromVertexId, String toVertexId) {
    public ContourSegment {
        fromVertexId = Objects.requireNonNull(fromVertexId, "fromVertexId").trim();
        toVertexId = Objects.requireNonNull(toVertexId, "toVertexId").trim();
        if (fromVertexId.isEmpty() || toVertexId.isEmpty()
                || fromVertexId.equals(toVertexId)) {
            throw new IllegalArgumentException(
                    "Segment endpoints must be distinct non-blank vertex IDs");
        }
    }
}
