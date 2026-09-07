package org.atlasalign.application.manual;

import java.util.Objects;

public record ContourVertex(String id, SourcePixelPoint point) {
    public ContourVertex {
        id = Objects.requireNonNull(id, "id").trim();
        point = Objects.requireNonNull(point, "point");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Vertex id must not be blank");
        }
    }
}
