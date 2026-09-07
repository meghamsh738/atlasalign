package org.atlasalign.application.manual;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.atlasalign.core.Point2D;

/**
 * Immutable reviewed boundary with a hidden strictly monotone cyclic identity.
 * Moving a vertex preserves its identity. Insertion interpolates identity and
 * deletion removes only that sample.
 */
public final class MonotoneBoundary2D {

    private static final double PARAMETER_EPSILON = 1e-14;

    private final List<Vertex> vertices;

    public MonotoneBoundary2D(final List<Vertex> values) {
        vertices = validated(values);
    }

    /** Creates an indexed cyclic identity without changing any input point. */
    public static MonotoneBoundary2D indexed(
            final String idPrefix, final List<Point2D> points) {
        final String prefix = Objects.requireNonNull(idPrefix, "idPrefix")
                .trim();
        final List<Point2D> checked = List.copyOf(
                Objects.requireNonNull(points, "points"));
        if (prefix.isEmpty()) {
            throw new IllegalArgumentException("idPrefix must not be blank");
        }
        if (checked.size() < 3) {
            throw new IllegalArgumentException(
                    "A closed boundary requires at least three vertices");
        }
        final List<Vertex> result = new ArrayList<>(checked.size());
        for (int index = 0; index < checked.size(); index++) {
            result.add(new Vertex(prefix + index,
                    Objects.requireNonNull(checked.get(index), "point"),
                    index / (double) checked.size(), false));
        }
        return new MonotoneBoundary2D(result);
    }

    /**
     * Creates a cyclic identity from normalized boundary arc length. Callers
     * must first normalize orientation and cyclic start to a stable anatomical
     * landmark; this method then preserves unequal reviewer vertex spacing.
     */
    public static MonotoneBoundary2D arcLengthIndexed(
            final String idPrefix, final List<Point2D> points) {
        final String prefix = Objects.requireNonNull(idPrefix, "idPrefix")
                .trim();
        final List<Point2D> checked = List.copyOf(
                Objects.requireNonNull(points, "points"));
        if (prefix.isEmpty() || checked.size() < 3) {
            throw new IllegalArgumentException(
                    "A named closed boundary requires at least three vertices");
        }
        final double[] cumulative = new double[checked.size() + 1];
        for (int index = 0; index < checked.size(); index++) {
            final Point2D from = Objects.requireNonNull(
                    checked.get(index), "point");
            final Point2D to = Objects.requireNonNull(
                    checked.get((index + 1) % checked.size()), "point");
            final double length = Math.hypot(
                    to.x() - from.x(), to.y() - from.y());
            if (!(length > 0)) {
                throw failure(BoundaryGeometryFailure.Kind.ZERO_LENGTH_EDGE,
                        "boundary", index, (index + 1) % checked.size(), from,
                        "Two adjacent boundary vertices occupy the same coordinate");
            }
            cumulative[index + 1] = cumulative[index] + length;
        }
        final double perimeter = cumulative[checked.size()];
        final List<Vertex> result = new ArrayList<>(checked.size());
        for (int index = 0; index < checked.size(); index++) {
            result.add(new Vertex(prefix + index, checked.get(index),
                    cumulative[index] / perimeter, false));
        }
        return new MonotoneBoundary2D(result);
    }

    public List<Vertex> vertices() {
        return vertices;
    }

    public MonotoneBoundary2D move(
            final String vertexId, final Point2D updatedPoint) {
        final int index = indexOf(vertexId);
        final List<Vertex> changed = new ArrayList<>(vertices);
        changed.set(index, changed.get(index).withPoint(updatedPoint));
        return new MonotoneBoundary2D(changed);
    }

    public MonotoneBoundary2D insertAfter(
            final String existingVertexId,
            final String insertedVertexId,
            final Point2D point,
            final boolean semanticBoundary) {
        final int index = indexOf(existingVertexId);
        final Vertex current = vertices.get(index);
        final double nextParameter = index + 1 < vertices.size()
                ? vertices.get(index + 1).cyclicParameter() : 1.0;
        final double insertedParameter = 0.5
                * (current.cyclicParameter() + nextParameter);
        if (!(insertedParameter > current.cyclicParameter()
                + PARAMETER_EPSILON)
                || !(insertedParameter < nextParameter
                        - PARAMETER_EPSILON)) {
            throw failure(BoundaryGeometryFailure.Kind.NON_MONOTONE_PARAMETER,
                    "boundary", index, index,
                    current.point(),
                    "The neighbouring cyclic identities are too close to insert another vertex");
        }
        final List<Vertex> changed = new ArrayList<>(vertices);
        changed.add(index + 1, new Vertex(insertedVertexId, point,
                insertedParameter, semanticBoundary));
        return new MonotoneBoundary2D(changed);
    }

    public MonotoneBoundary2D delete(final String vertexId) {
        if (vertices.size() <= 3) {
            throw new IllegalArgumentException(
                    "A closed boundary must retain at least three vertices");
        }
        final List<Vertex> changed = new ArrayList<>(vertices);
        changed.remove(indexOf(vertexId));
        return new MonotoneBoundary2D(changed);
    }

    Point2D interpolate(final double cyclicParameter) {
        if (!Double.isFinite(cyclicParameter)
                || cyclicParameter < 0 || cyclicParameter >= 1) {
            throw new IllegalArgumentException(
                    "cyclicParameter must lie in [0, 1)");
        }
        int startIndex = vertices.size() - 1;
        int endIndex = 0;
        double startParameter = vertices.get(startIndex).cyclicParameter();
        double endParameter = 1.0;
        for (int index = 0; index + 1 < vertices.size(); index++) {
            final double current = vertices.get(index).cyclicParameter();
            final double next = vertices.get(index + 1).cyclicParameter();
            if (cyclicParameter == current) {
                return vertices.get(index).point();
            }
            if (cyclicParameter > current && cyclicParameter < next) {
                startIndex = index;
                endIndex = index + 1;
                startParameter = current;
                endParameter = next;
                break;
            }
        }
        if (cyclicParameter == vertices.get(vertices.size() - 1)
                .cyclicParameter()) {
            return vertices.get(vertices.size() - 1).point();
        }
        final double fraction = (cyclicParameter - startParameter)
                / (endParameter - startParameter);
        final Point2D start = vertices.get(startIndex).point();
        final Point2D end = vertices.get(endIndex).point();
        return new Point2D(
                start.x() + fraction * (end.x() - start.x()),
                start.y() + fraction * (end.y() - start.y()));
    }

    boolean semanticAt(final double cyclicParameter) {
        for (final Vertex vertex : vertices) {
            if (vertex.cyclicParameter() == cyclicParameter) {
                return vertex.semanticBoundary();
            }
        }
        return false;
    }

    private int indexOf(final String value) {
        final String id = Objects.requireNonNull(value, "vertexId").trim();
        for (int index = 0; index < vertices.size(); index++) {
            if (vertices.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unknown boundary vertex: " + id);
    }

    private static List<Vertex> validated(final List<Vertex> values) {
        final List<Vertex> checked = List.copyOf(
                Objects.requireNonNull(values, "vertices"));
        if (checked.size() < 3) {
            throw new IllegalArgumentException(
                    "A closed boundary requires at least three vertices");
        }
        final Set<String> ids = new HashSet<>();
        double previous = -1;
        for (int index = 0; index < checked.size(); index++) {
            final Vertex vertex = Objects.requireNonNull(
                    checked.get(index), "vertex");
            if (!ids.add(vertex.id())) {
                throw new IllegalArgumentException(
                        "Boundary vertex IDs must be unique");
            }
            if (index == 0 && vertex.cyclicParameter() != 0.0
                    || !(vertex.cyclicParameter() > previous)) {
                throw failure(
                        BoundaryGeometryFailure.Kind.NON_MONOTONE_PARAMETER,
                        "boundary", index, index, vertex.point(),
                        "Boundary cyclic identities must start at zero and increase strictly");
            }
            previous = vertex.cyclicParameter();
        }
        return checked;
    }

    private static BoundaryGeometryException failure(
            final BoundaryGeometryFailure.Kind kind,
            final String boundaryName,
            final int first,
            final int second,
            final Point2D location,
            final String detail) {
        return new BoundaryGeometryException(BoundaryGeometryFailure.local(
                kind, boundaryName, first, second, location, detail));
    }

    public record Vertex(
            String id,
            Point2D point,
            double cyclicParameter,
            boolean semanticBoundary) {

        public Vertex {
            id = Objects.requireNonNull(id, "id").trim();
            point = Objects.requireNonNull(point, "point");
            if (id.isEmpty()) {
                throw new IllegalArgumentException(
                        "Boundary vertex id must not be blank");
            }
            if (!Double.isFinite(cyclicParameter)
                    || cyclicParameter < 0 || cyclicParameter >= 1) {
                throw new IllegalArgumentException(
                        "cyclicParameter must lie in [0, 1)");
            }
        }

        public Vertex withPoint(final Point2D updatedPoint) {
            return new Vertex(id,
                    Objects.requireNonNull(updatedPoint, "updatedPoint"),
                    cyclicParameter, semanticBoundary);
        }
    }
}
