package org.atlasalign.plugin.review;

import java.util.List;
import java.util.Objects;
import org.atlasalign.core.Point2D;

/**
 * Stable transient identity for one paired thickness chord or one unpaired
 * terminal Structure control. Nothing in this type is serialized.
 */
public record StructureAdjustmentUnit(
        String id,
        int componentIndex,
        String componentId,
        List<String> controlIds,
        Point2D midpoint,
        Point2D separationAxis,
        double initialDistance,
        String displayLabel) {

    public StructureAdjustmentUnit {
        id = requireText(id, "id");
        componentId = requireText(componentId, "componentId");
        controlIds = List.copyOf(Objects.requireNonNull(
                controlIds, "controlIds"));
        midpoint = Objects.requireNonNull(midpoint, "midpoint");
        separationAxis = Objects.requireNonNull(
                separationAxis, "separationAxis");
        displayLabel = requireText(displayLabel, "displayLabel");
        if (componentIndex < 0 || controlIds.isEmpty()
                || controlIds.size() > 2
                || controlIds.stream().distinct().count()
                        != controlIds.size()
                || !Double.isFinite(initialDistance)
                || initialDistance < 0) {
            throw new IllegalArgumentException(
                    "A Structure unit must contain one dot or one valid pair");
        }
        final double axisLength = Math.hypot(
                separationAxis.x(), separationAxis.y());
        if (controlIds.size() == 2
                && (initialDistance <= 1e-9
                        || Math.abs(axisLength - 1.0) > 1e-6)) {
            throw new IllegalArgumentException(
                    "A paired Structure unit needs a unit separation axis");
        }
        if (controlIds.size() == 1
                && (initialDistance != 0 || axisLength > 1e-9)) {
            throw new IllegalArgumentException(
                    "An unpaired Structure dot has no thickness chord");
        }
    }

    public boolean paired() {
        return controlIds.size() == 2;
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
