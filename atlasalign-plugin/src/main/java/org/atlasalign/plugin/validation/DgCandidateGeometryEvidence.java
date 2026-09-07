package org.atlasalign.plugin.validation;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import org.atlasalign.application.dg.AnatomicalSide;

/** Side-preserving DG and named-anchor evidence for one candidate plane. */
public record DgCandidateGeometryEvidence(
        Map<AnatomicalSide, OptionalDouble> dgDistanceBySide,
        OptionalDouble bilateralDgDistance,
        OptionalDouble nonDgInputAnchorDistance) {

    public DgCandidateGeometryEvidence {
        final EnumMap<AnatomicalSide, OptionalDouble> checked =
                new EnumMap<>(AnatomicalSide.class);
        Objects.requireNonNull(dgDistanceBySide, "dgDistanceBySide")
                .forEach((side, distance) -> checked.put(
                        Objects.requireNonNull(side, "anatomical side"),
                        requireMetric(distance, "side DG distance")));
        if (!checked.keySet().equals(
                java.util.EnumSet.allOf(AnatomicalSide.class))) {
            throw new IllegalArgumentException(
                    "DG geometry evidence must preserve both anatomical sides");
        }
        dgDistanceBySide = Map.copyOf(checked);
        bilateralDgDistance = requireMetric(
                bilateralDgDistance, "bilateral DG distance");
        nonDgInputAnchorDistance = requireMetric(
                nonDgInputAnchorDistance, "non-DG input-anchor distance");
        final boolean bothSides = checked.values().stream()
                .allMatch(OptionalDouble::isPresent);
        if (bothSides != bilateralDgDistance.isPresent()) {
            throw new IllegalArgumentException(
                    "Bilateral DG distance must exist exactly when both sides exist");
        }
        if (bothSides) {
            final double expected = checked.values().stream()
                    .mapToDouble(OptionalDouble::getAsDouble)
                    .average().orElseThrow();
            if (Double.doubleToLongBits(expected)
                    != Double.doubleToLongBits(
                            bilateralDgDistance.getAsDouble())) {
                throw new IllegalArgumentException(
                        "Bilateral DG distance must be the equal side mean");
            }
        }
    }

    private static OptionalDouble requireMetric(
            final OptionalDouble value,
            final String label) {
        Objects.requireNonNull(value, label);
        if (value.isPresent()
                && (!Double.isFinite(value.getAsDouble())
                || value.getAsDouble() < 0)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }
}
