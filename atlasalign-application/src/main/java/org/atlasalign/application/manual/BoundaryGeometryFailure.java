package org.atlasalign.application.manual;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.atlasalign.core.Point2D;

/**
 * Typed, image-local explanation for a boundary-authoritative geometry
 * failure. Indices refer to the immutable input boundary named by
 * {@link #boundaryName()}.
 */
public record BoundaryGeometryFailure(
        Kind kind,
        String boundaryName,
        OptionalInt firstIndex,
        OptionalInt secondIndex,
        Optional<Point2D> location,
        String detail) {

    public BoundaryGeometryFailure {
        kind = Objects.requireNonNull(kind, "kind");
        boundaryName = requireText(boundaryName, "boundaryName");
        firstIndex = Objects.requireNonNull(firstIndex, "firstIndex");
        secondIndex = Objects.requireNonNull(secondIndex, "secondIndex");
        location = Objects.requireNonNull(location, "location");
        detail = requireText(detail, "detail");
        if (firstIndex.isPresent() && firstIndex.getAsInt() < 0
                || secondIndex.isPresent() && secondIndex.getAsInt() < 0) {
            throw new IllegalArgumentException(
                    "Boundary failure indices must be non-negative");
        }
    }

    public static BoundaryGeometryFailure local(
            final Kind kind,
            final String boundaryName,
            final int firstIndex,
            final int secondIndex,
            final Point2D location,
            final String detail) {
        return new BoundaryGeometryFailure(kind, boundaryName,
                OptionalInt.of(firstIndex), OptionalInt.of(secondIndex),
                Optional.of(Objects.requireNonNull(location, "location")),
                detail);
    }

    public static BoundaryGeometryFailure global(
            final Kind kind,
            final String boundaryName,
            final String detail) {
        return new BoundaryGeometryFailure(kind, boundaryName,
                OptionalInt.empty(), OptionalInt.empty(), Optional.empty(),
                detail);
    }

    private static String requireText(
            final String value, final String name) {
        final String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }

    public enum Kind {
        ZERO_LENGTH_EDGE,
        SELF_CROSSING,
        SELF_TOUCHING_PINCH,
        NON_POSITIVE_AREA,
        INCONSISTENT_ORIENTATION,
        NON_MONOTONE_PARAMETER,
        UNSUPPORTED_TOPOLOGY,
        TRIANGULATION_FAILURE,
        NUMERICALLY_UNRESOLVABLE,
        AUDIT_FAILURE,
        OUTSIDE_DOMAIN
    }
}
