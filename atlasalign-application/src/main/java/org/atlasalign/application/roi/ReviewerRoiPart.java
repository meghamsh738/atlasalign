package org.atlasalign.application.roi;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** One additive component or subtractive hole of a reviewer ROI. */
public record ReviewerRoiPart(
        String id,
        RoiPartOperation operation,
        List<ReviewerRoiVertex> vertices,
        boolean finished) {

    public ReviewerRoiPart {
        id = requireText(id, "id");
        operation = Objects.requireNonNull(operation, "operation");
        vertices = List.copyOf(Objects.requireNonNull(vertices, "vertices"));
        final Set<String> vertexIds = new HashSet<>();
        for (final ReviewerRoiVertex vertex : vertices) {
            Objects.requireNonNull(vertex,
                    "ROI parts must not contain null vertices");
            if (!vertexIds.add(vertex.id())) {
                throw new IllegalArgumentException(
                        "ROI vertex IDs must be unique within a part");
            }
        }
        if (finished && vertices.size() < 3) {
            throw new IllegalArgumentException(
                    "A finished ROI polygon requires at least three vertices");
        }
    }

    public ReviewerRoiPart withVertices(
            final List<ReviewerRoiVertex> updated) {
        return new ReviewerRoiPart(id, operation, updated, finished);
    }

    public ReviewerRoiPart finish() {
        return new ReviewerRoiPart(id, operation, vertices, true);
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
