package org.atlasalign.plugin.review;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable display topology for one Fiji-style Structure outline.
 *
 * <p>The ordered control identifiers are transient review geometry only. They
 * preserve the verified exterior-loop order while thickness-pair identities
 * remain free to use a different ordering. No ROI-only transform is persisted
 * or used by export.</p>
 */
public record StructureOutlinePath(
        String id,
        List<String> controlIds,
        boolean closed) {

    public StructureOutlinePath {
        id = Objects.requireNonNull(id, "id").trim();
        controlIds = List.copyOf(Objects.requireNonNull(
                controlIds, "controlIds"));
        if (id.isEmpty() || controlIds.size() < 2
                || new HashSet<>(controlIds).size()
                        != controlIds.size()
                || controlIds.stream().anyMatch(value -> value == null
                        || value.isBlank())) {
            throw new IllegalArgumentException(
                    "A Structure outline path needs a stable ID and at least two unique controls");
        }
    }
}
