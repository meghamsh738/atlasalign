package org.atlasalign.plugin.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.atlasalign.application.AtlasAnatomicalSide;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.Point2D;

/**
 * Immutable outer boundary of one selected ontology region on one copied
 * atlas plane. Boundaries between child labels inside a selected parent are
 * deliberately excluded.
 */
public final class SelectedAtlasContour {

    private final SelectedAtlasRegion region;
    private final int width;
    private final int height;
    private final boolean[] boundary;
    private final int boundaryCount;
    private final String boundarySha256;
    private final List<Point2D> boundaryPoints;
    private final List<Point2D> orderedExteriorLoop;
    private final List<List<Point2D>> orderedExteriorLoops;

    private SelectedAtlasContour(
            final SelectedAtlasRegion region,
            final int width,
            final int height,
            final boolean[] boundary,
            final int boundaryCount,
            final List<Point2D> boundaryPoints,
            final List<List<Point2D>> orderedExteriorLoops) {
        this.region = region;
        this.width = width;
        this.height = height;
        this.boundary = boundary;
        this.boundaryCount = boundaryCount;
        this.boundaryPoints = List.copyOf(boundaryPoints);
        this.orderedExteriorLoops = orderedExteriorLoops.stream()
                .map(List::copyOf).toList();
        this.orderedExteriorLoop = this.orderedExteriorLoops.isEmpty()
                ? List.of() : this.orderedExteriorLoops.get(0);
        this.boundarySha256 = boundarySha256(
                region, width, height, this.boundaryPoints);
    }

    public static SelectedAtlasContour from(
            final AtlasCoronalPlane plane,
            final SelectedAtlasRegion region) {
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(region, "region");
        final int width = plane.width();
        final int height = plane.height();
        final int[] labels = plane.annotationId();
        final boolean[] selected = new boolean[labels.length];
        for (int index = 0; index < labels.length; index++) {
            selected[index] = region.contains(labels[index]);
        }
        final boolean[] boundary = new boolean[labels.length];
        final List<Point2D> boundaryPoints = new ArrayList<>();
        int count = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int index = y * width + x;
                if (!selected[index]) {
                    continue;
                }
                final boolean edge = x == 0 || !selected[index - 1]
                        || x + 1 == width || !selected[index + 1]
                        || y == 0 || !selected[index - width]
                        || y + 1 == height || !selected[index + width];
                if (edge) {
                    boundary[index] = true;
                    count++;
                    boundaryPoints.add(new Point2D(x, y));
                }
            }
        }
        return new SelectedAtlasContour(
                region, width, height, boundary, count, boundaryPoints,
                extractExteriorLoops(selected, width, height));
    }

    public SelectedAtlasRegion region() {
        return region;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int boundaryCount() {
        return boundaryCount;
    }

    /** Stable identity of this region's exact selected boundary pixels. */
    public String boundarySha256() {
        return boundarySha256;
    }

    public boolean isPresent() {
        return boundaryCount > 0;
    }

    public boolean isBoundary(final int x, final int y) {
        return x >= 0 && x < width && y >= 0 && y < height
                && boundary[y * width + x];
    }

    public List<Point2D> boundaryPoints() {
        return boundaryPoints;
    }

    /**
     * Returns the largest simple exterior loop of the selected region union.
     * Coordinates lie on pixel edges in continuous ATLAS_PLANE_PIXEL space;
     * the closing point is implied and is not repeated. The deterministic
     * loop starts at its topmost, then leftmost, vertex and runs clockwise in
     * screen coordinates. Holes, internal ontology seams, and smaller
     * disconnected components are excluded.
     */
    public List<Point2D> orderedExteriorLoop() {
        return orderedExteriorLoop;
    }

    /**
     * Returns every disconnected exterior component in deterministic order.
     * The largest component is first, preserving {@link #orderedExteriorLoop}
     * compatibility. Holes remain excluded.
     */
    public List<List<Point2D>> orderedExteriorLoops() {
        return orderedExteriorLoops;
    }

    /**
     * Deterministic disconnected exterior components on one anatomical side.
     * Identities are transient hashes of the current verified contour and are
     * never persisted into the review snapshot.
     */
    public List<ExteriorComponent> exteriorComponents(
            final AtlasAnatomicalSide side) {
        Objects.requireNonNull(side, "side");
        if (side == AtlasAnatomicalSide.MIDLINE) {
            return List.of();
        }
        final double middle = (width - 1.0) * 0.5;
        final List<ExteriorComponent> components = new ArrayList<>();
        for (int index = 0; index < orderedExteriorLoops.size(); index++) {
            final List<Point2D> loop = orderedExteriorLoops.get(index);
            final double centreX = loop.stream().mapToDouble(Point2D::x)
                    .average().orElse(middle);
            final boolean onRequestedSide = side
                    == AtlasAnatomicalSide.ATLAS_LEFT
                    ? centreX < middle : centreX > middle;
            if (!onRequestedSide) {
                continue;
            }
            components.add(new ExteriorComponent(
                    boundarySha256.substring(0, 16) + "-component-"
                            + index,
                    loop, closedPerimeter(loop)));
        }
        components.sort(Comparator.comparingDouble(
                ExteriorComponent::perimeter).reversed()
                .thenComparing(ExteriorComponent::id));
        return List.copyOf(components);
    }

    /** The two largest active-side exterior components, if present. */
    public List<ExteriorComponent> principalExteriorComponents(
            final AtlasAnatomicalSide side) {
        return exteriorComponents(side).stream().limit(2).toList();
    }

    /**
     * Seeds at least two controls on each of the two principal components;
     * remaining controls are distributed deterministically by perimeter.
     */
    public List<Point2D> samplePrincipalExteriorPoints(
            final int requestedCount,
            final AtlasAnatomicalSide side) {
        if (requestedCount <= 0) {
            throw new IllegalArgumentException(
                    "Boundary handle count must be positive");
        }
        final List<ExteriorComponent> principal =
                principalExteriorComponents(side);
        if (principal.isEmpty()) {
            return sampleBoundaryPoints(requestedCount, side);
        }
        if (principal.size() == 2 && requestedCount < 4) {
            throw new IllegalArgumentException(
                    "Two disconnected structure components require at least four controls");
        }
        final int[] allocation = new int[principal.size()];
        if (principal.size() == 1) {
            allocation[0] = requestedCount;
        } else {
            allocation[0] = 2;
            allocation[1] = 2;
            final double total = principal.stream().mapToDouble(
                    ExteriorComponent::perimeter).sum();
            for (int assigned = 4; assigned < requestedCount; assigned++) {
                int selected = 0;
                double bestDeficit = Double.NEGATIVE_INFINITY;
                for (int index = 0; index < principal.size(); index++) {
                    final double desired = requestedCount
                            * principal.get(index).perimeter() / total;
                    final double deficit = desired - allocation[index];
                    if (deficit > bestDeficit) {
                        selected = index;
                        bestDeficit = deficit;
                    }
                }
                allocation[selected]++;
            }
        }
        final List<List<Point2D>> sampled = new ArrayList<>(
                principal.size());
        for (int index = 0; index < principal.size(); index++) {
            sampled.add(evenlySampleClosedLoopOnSide(
                    principal.get(index).loop(), allocation[index], side,
                    (width - 1.0) * 0.5));
        }
        final List<Point2D> result = new ArrayList<>(requestedCount);
        for (int offset = 0; result.size() < requestedCount; offset++) {
            boolean added = false;
            for (final List<Point2D> component : sampled) {
                if (offset < component.size()) {
                    result.add(component.get(offset));
                    added = true;
                }
            }
            if (!added) {
                break;
            }
        }
        return List.copyOf(result);
    }

    private static List<Point2D> evenlySampleClosedLoopOnSide(
            final List<Point2D> loop,
            final int count,
            final AtlasAnatomicalSide side,
            final double middle) {
        final int candidateCount = Math.max(64, count * 24);
        final List<Point2D> candidates = evenlySampleClosedLoop(
                loop, candidateCount).stream()
                .filter(point -> side == AtlasAnatomicalSide.ATLAS_LEFT
                        ? point.x() < middle - 1e-9
                        : point.x() > middle + 1e-9)
                .toList();
        if (candidates.size() < count) {
            throw new IllegalArgumentException(
                    "Structure component has too little boundary away from the atlas seam");
        }
        final List<Point2D> result = new ArrayList<>(count);
        for (int sample = 0; sample < count; sample++) {
            final int index = Math.min(candidates.size() - 1,
                    (int) Math.floor((sample + 0.5)
                            * candidates.size() / count));
            result.add(candidates.get(index));
        }
        return List.copyOf(result);
    }

    private static List<Point2D> evenlySampleClosedLoop(
            final List<Point2D> loop,
            final int count) {
        final double perimeter = closedPerimeter(loop);
        if (loop.isEmpty() || count <= 0 || perimeter <= 0) {
            return List.of();
        }
        final List<Point2D> result = new ArrayList<>(count);
        for (int sample = 0; sample < count; sample++) {
            final double wanted = (sample + 0.5) * perimeter / count;
            double travelled = 0;
            for (int index = 0; index < loop.size(); index++) {
                final Point2D first = loop.get(index);
                final Point2D second = loop.get(
                        (index + 1) % loop.size());
                final double length = Math.hypot(
                        second.x() - first.x(),
                        second.y() - first.y());
                if (wanted <= travelled + length || index + 1
                        == loop.size()) {
                    final double fraction = length <= 0 ? 0
                            : Math.max(0, Math.min(1,
                                    (wanted - travelled) / length));
                    result.add(new Point2D(
                            first.x() + fraction
                                    * (second.x() - first.x()),
                            first.y() + fraction
                                    * (second.y() - first.y())));
                    break;
                }
                travelled += length;
            }
        }
        return List.copyOf(result);
    }

    private static double closedPerimeter(final List<Point2D> loop) {
        double perimeter = 0;
        for (int index = 0; index < loop.size(); index++) {
            final Point2D first = loop.get(index);
            final Point2D second = loop.get(
                    (index + 1) % loop.size());
            perimeter += Math.hypot(second.x() - first.x(),
                    second.y() - first.y());
        }
        return perimeter;
    }

    public record ExteriorComponent(
            String id,
            List<Point2D> loop,
            double perimeter) {

        public ExteriorComponent {
            id = Objects.requireNonNull(id, "id");
            loop = List.copyOf(Objects.requireNonNull(loop, "loop"));
            if (id.isBlank() || loop.size() < 3
                    || !Double.isFinite(perimeter) || perimeter <= 0) {
                throw new IllegalArgumentException(
                        "Exterior component geometry is invalid");
            }
        }
    }

    /** Deterministic nearest boundary point, with row/column tie-breaking. */
    public Optional<Point2D> nearestBoundary(
            final Point2D requested,
            final double maximumDistancePixels) {
        Objects.requireNonNull(requested, "requested");
        if (!Double.isFinite(maximumDistancePixels)
                || maximumDistancePixels < 0) {
            throw new IllegalArgumentException(
                    "Maximum boundary distance must be finite and non-negative");
        }
        final int radius = (int) Math.ceil(maximumDistancePixels);
        final int centerX = (int) Math.round(requested.x());
        final int centerY = (int) Math.round(requested.y());
        Point2D closest = null;
        double bestSquared = maximumDistancePixels * maximumDistancePixels;
        for (int y = Math.max(0, centerY - radius);
                y <= Math.min(height - 1, centerY + radius); y++) {
            for (int x = Math.max(0, centerX - radius);
                    x <= Math.min(width - 1, centerX + radius); x++) {
                if (!isBoundary(x, y)) {
                    continue;
                }
                final double dx = x - requested.x();
                final double dy = y - requested.y();
                final double squared = dx * dx + dy * dy;
                if (squared <= bestSquared) {
                    if (closest == null || squared < bestSquared) {
                        closest = new Point2D(x, y);
                        bestSquared = squared;
                    }
                }
            }
        }
        return Optional.ofNullable(closest);
    }

    /**
     * Samples spatially spread boundary handles deterministically. The first
     * handle is the first boundary pixel in row-major order; each subsequent
     * handle maximizes its distance from the already selected set.
     */
    public List<Point2D> sampleBoundaryPoints(final int requestedCount) {
        return sampleSpatiallySpread(boundaryPoints, requestedCount);
    }

    /**
     * Deterministically samples only one verified atlas side. Atlas side is
     * defined before any display reflection. Midline sampling is restricted
     * to a narrow central band and fails closed when that band has no target.
     */
    public List<Point2D> sampleBoundaryPoints(
            final int requestedCount,
            final AtlasAnatomicalSide side) {
        Objects.requireNonNull(side, "side");
        final double middleX = (width - 1.0) * 0.5;
        final double midlineHalfWidth = Math.max(1.0, width * 0.025);
        final List<Point2D> candidates = boundaryPoints.stream()
                .filter(point -> switch (side) {
                    case ATLAS_LEFT -> point.x() < middleX;
                    case ATLAS_RIGHT -> point.x() > middleX;
                    case MIDLINE -> Math.abs(point.x() - middleX)
                            <= midlineHalfWidth;
                })
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Selected atlas target has no boundary on " + side);
        }
        return sampleSpatiallySpread(candidates, requestedCount);
    }

    private static List<Point2D> sampleSpatiallySpread(
            final List<Point2D> candidates,
            final int requestedCount) {
        if (requestedCount <= 0) {
            throw new IllegalArgumentException(
                    "Boundary handle count must be positive");
        }
        if (candidates.size() <= requestedCount) {
            return List.copyOf(candidates);
        }
        final List<Point2D> selected = new ArrayList<>(requestedCount);
        selected.add(candidates.get(0));
        while (selected.size() < requestedCount) {
            Point2D best = null;
            double bestMinimumSquared = -1;
            for (final Point2D candidate : candidates) {
                if (selected.contains(candidate)) {
                    continue;
                }
                double minimumSquared = Double.POSITIVE_INFINITY;
                for (final Point2D existing : selected) {
                    final double dx = candidate.x() - existing.x();
                    final double dy = candidate.y() - existing.y();
                    minimumSquared = Math.min(minimumSquared,
                            dx * dx + dy * dy);
                }
                if (minimumSquared > bestMinimumSquared) {
                    best = candidate;
                    bestMinimumSquared = minimumSquared;
                }
            }
            selected.add(Objects.requireNonNull(best));
        }
        return List.copyOf(selected);
    }

    private static String boundarySha256(
            final SelectedAtlasRegion region,
            final int width,
            final int height,
            final List<Point2D> points) {
        final StringBuilder canonical = new StringBuilder()
                .append("selected-atlas-boundary-v1\n")
                .append(region.rootRegionId()).append('\n')
                .append(region.acronym()).append('\n')
                .append(width).append('x').append(height).append('\n');
        for (final Point2D point : points) {
            canonical.append((int) point.x()).append(',')
                    .append((int) point.y()).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(canonical.toString().getBytes(
                            StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", unavailable);
        }
    }

    private static List<List<Point2D>> extractExteriorLoops(
            final boolean[] selected,
            final int width,
            final int height) {
        final List<GridEdge> edges = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!selected[y * width + x]) {
                    continue;
                }
                final int left = 2 * x - 1;
                final int right = 2 * x + 1;
                final int top = 2 * y - 1;
                final int bottom = 2 * y + 1;
                if (y == 0 || !selected[(y - 1) * width + x]) {
                    edges.add(new GridEdge(left, top, right, top, 0));
                }
                if (x + 1 == width || !selected[y * width + x + 1]) {
                    edges.add(new GridEdge(right, top, right, bottom, 1));
                }
                if (y + 1 == height || !selected[(y + 1) * width + x]) {
                    edges.add(new GridEdge(right, bottom, left, bottom, 2));
                }
                if (x == 0 || !selected[y * width + x - 1]) {
                    edges.add(new GridEdge(left, bottom, left, top, 3));
                }
            }
        }
        if (edges.isEmpty()) {
            return List.of();
        }

        final Map<Long, List<GridEdge>> outgoing = new HashMap<>();
        for (final GridEdge edge : edges) {
            outgoing.computeIfAbsent(vertexKey(edge.startX(), edge.startY()),
                    ignored -> new ArrayList<>()).add(edge);
        }
        final Map<GridEdge, GridEdge> successor = new HashMap<>();
        final Map<GridEdge, Integer> predecessorCounts = new HashMap<>();
        for (final GridEdge edge : edges) {
            final GridEdge next = chooseRightmostSuccessor(edge,
                    outgoing.get(vertexKey(edge.endX(), edge.endY())));
            if (next == null) {
                return List.of();
            }
            successor.put(edge, next);
            predecessorCounts.merge(next, 1, Integer::sum);
        }
        if (edges.stream().anyMatch(edge ->
                predecessorCounts.getOrDefault(edge, 0) != 1)) {
            return List.of();
        }

        edges.sort(Comparator.comparingInt(GridEdge::startY)
                .thenComparingInt(GridEdge::startX)
                .thenComparingInt(GridEdge::direction));
        final Set<GridEdge> visited = new HashSet<>();
        final List<ExteriorLoop> exterior = new ArrayList<>();
        for (final GridEdge start : edges) {
            if (visited.contains(start)) {
                continue;
            }
            final List<GridVertex> loop = new ArrayList<>();
            final Set<Long> loopVertices = new HashSet<>();
            GridEdge current = start;
            do {
                if (!visited.add(current)) {
                    return List.of();
                }
                final long vertex = vertexKey(
                        current.startX(), current.startY());
                if (!loopVertices.add(vertex)) {
                    return List.of();
                }
                loop.add(new GridVertex(
                        current.startX(), current.startY()));
                current = successor.get(current);
                if (current == null || loop.size() > edges.size()) {
                    return List.of();
                }
            } while (!current.equals(start));
            final long doubleArea = signedDoubleArea(loop);
            if (doubleArea > 0) {
                final List<GridVertex> simplified = simplifyCollinear(loop);
                if (simplified.size() >= 4) {
                    exterior.add(new ExteriorLoop(doubleArea,
                            rotateToCanonicalStart(simplified)));
                }
            }
        }
        exterior.sort((first, second) -> {
            final int area = Long.compare(
                    second.doubleArea(), first.doubleArea());
            return area != 0 ? area
                    : compareCanonical(first.vertices(), second.vertices());
        });
        return exterior.stream().map(value -> value.vertices().stream()
                        .map(vertex -> new Point2D(
                                vertex.x() / 2.0, vertex.y() / 2.0))
                        .toList())
                .toList();
    }

    private static GridEdge chooseRightmostSuccessor(
            final GridEdge incoming,
            final List<GridEdge> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        final int[] turns = {1, 0, 3, 2};
        for (final int turn : turns) {
            final int direction = (incoming.direction() + turn) % 4;
            for (final GridEdge candidate : candidates) {
                if (candidate.direction() == direction) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static long signedDoubleArea(final List<GridVertex> loop) {
        long twiceArea = 0;
        for (int index = 0; index < loop.size(); index++) {
            final GridVertex current = loop.get(index);
            final GridVertex next = loop.get((index + 1) % loop.size());
            twiceArea += (long) current.x() * next.y()
                    - (long) next.x() * current.y();
        }
        return twiceArea;
    }

    private static List<GridVertex> simplifyCollinear(
            final List<GridVertex> loop) {
        final List<GridVertex> simplified = new ArrayList<>();
        for (int index = 0; index < loop.size(); index++) {
            final GridVertex previous = loop.get(
                    (index + loop.size() - 1) % loop.size());
            final GridVertex current = loop.get(index);
            final GridVertex next = loop.get((index + 1) % loop.size());
            final boolean vertical = previous.x() == current.x()
                    && current.x() == next.x();
            final boolean horizontal = previous.y() == current.y()
                    && current.y() == next.y();
            if (!vertical && !horizontal) {
                simplified.add(current);
            }
        }
        return simplified;
    }

    private static List<GridVertex> rotateToCanonicalStart(
            final List<GridVertex> loop) {
        int first = 0;
        for (int index = 1; index < loop.size(); index++) {
            if (compareVertex(loop.get(index), loop.get(first)) < 0) {
                first = index;
            }
        }
        final List<GridVertex> rotated = new ArrayList<>(loop.size());
        for (int offset = 0; offset < loop.size(); offset++) {
            rotated.add(loop.get((first + offset) % loop.size()));
        }
        return rotated;
    }

    private static int compareCanonical(
            final List<GridVertex> first,
            final List<GridVertex> second) {
        if (second.isEmpty()) {
            return -1;
        }
        final List<GridVertex> canonicalFirst = rotateToCanonicalStart(first);
        final List<GridVertex> canonicalSecond = rotateToCanonicalStart(second);
        final int limit = Math.min(
                canonicalFirst.size(), canonicalSecond.size());
        for (int index = 0; index < limit; index++) {
            final int comparison = compareVertex(
                    canonicalFirst.get(index), canonicalSecond.get(index));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(
                canonicalFirst.size(), canonicalSecond.size());
    }

    private static int compareVertex(
            final GridVertex first,
            final GridVertex second) {
        final int vertical = Integer.compare(first.y(), second.y());
        return vertical != 0 ? vertical
                : Integer.compare(first.x(), second.x());
    }

    private static long vertexKey(final int x, final int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private record GridVertex(int x, int y) {
    }

    private record ExteriorLoop(long doubleArea, List<GridVertex> vertices) {
    }

    private record GridEdge(
            int startX,
            int startY,
            int endX,
            int endY,
            int direction) {
    }
}
