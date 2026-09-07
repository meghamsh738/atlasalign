package org.atlasalign.application.roi;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import org.atlasalign.core.Point2D;

/**
 * Reduces editable ROI polygons by retaining their most informative existing
 * vertices. No point is interpolated or moved, and polygon parts are never
 * merged or removed.
 */
public final class ReviewerRoiVertexReducer {

    private static final int[] AUTOMATIC_TARGETS = {
        4, 6, 8, 12, 16, 24, 32, 48, 64
    };
    private static final double GEOMETRY_EPSILON = 1e-10;
    private static final double MAXIMUM_RELATIVE_AREA_CHANGE = 0.08;
    private static final double MAXIMUM_COMBINED_SYMMETRIC_DIFFERENCE = 0.08;

    private ReviewerRoiVertexReducer() { }

    /** Returns the number of editable vertices across every polygon part. */
    public static int vertexCount(final ReviewerRoi roi) {
        return Objects.requireNonNull(roi, "roi").parts().stream()
                .mapToInt(part -> part.vertices().size()).sum();
    }

    /**
     * Chooses the smallest compact total that preserves the visible shape at
     * the scale of this source image. Curved or intricate outlines require
     * more vertices to remain within the same deviation bound.
     */
    public static int automaticTarget(
            final ReviewerRoi roi,
            final int sourceWidth,
            final int sourceHeight) {
        requireSourceDimensions(sourceWidth, sourceHeight);
        final ReviewerRoi checked = Objects.requireNonNull(roi, "roi");
        final List<List<Point2D>> loops = exactLoops(checked);
        final List<ReductionHierarchy> hierarchies = hierarchies(loops);
        final SpatialRelationship[][] relationships =
                relationshipMatrix(loops);
        final Area originalCombinedArea = combinedArea(checked);
        final double originalCombinedMeasure = areaMeasure(
                originalCombinedArea);
        final int current = vertexCount(checked);
        for (final int target : automaticTargets(loops, current)) {
            final int[] allocations = allocateCounts(loops, target);
            final ReviewerRoi candidate = reducedRoi(
                    checked, hierarchies, allocations);
            if (!sameLoopRelationships(relationships,
                    pointLoops(candidate))) {
                continue;
            }
            if (withinAutomaticTolerance(loops,
                    pointLoops(candidate), sourceWidth, sourceHeight)
                    && combinedGeometryWithinAutomaticTolerance(
                            originalCombinedArea,
                            originalCombinedMeasure, candidate)) {
                return target;
            }
        }
        return current;
    }

    /** Chooses an automatic compact total for source-coordinate loops. */
    public static int automaticTarget(
            final List<List<Point2D>> sourceLoops,
            final int sourceWidth,
            final int sourceHeight) {
        requireSourceDimensions(sourceWidth, sourceHeight);
        final List<List<Point2D>> loops = checkedLoops(sourceLoops);
        final int current = loops.stream().mapToInt(List::size).sum();
        final List<ReductionHierarchy> hierarchies = hierarchies(loops);
        final SpatialRelationship[][] relationships =
                relationshipMatrix(loops);
        for (final int target : automaticTargets(loops, current)) {
            final List<List<Point2D>> candidate = reducedLoops(
                    hierarchies, allocateCounts(loops, target));
            if (sameLoopRelationships(relationships, candidate)
                    && withinAutomaticTolerance(loops, candidate,
                            sourceWidth, sourceHeight)) {
                return target;
            }
        }
        return current;
    }

    /**
     * Returns a copy of {@code roi} with at most {@code totalPoints} retained
     * vertices. Stable IDs, part IDs, part order, and ADD/SUBTRACT operations
     * are preserved.
     */
    public static ReviewerRoi reduce(
            final ReviewerRoi roi,
            final int totalPoints) {
        final ReviewerRoi checked = Objects.requireNonNull(roi, "roi");
        final int current = vertexCount(checked);
        if (totalPoints >= current) {
            return checked;
        }
        final List<List<Point2D>> loops = exactLoops(checked);
        final int[] allocations = allocateCounts(loops, totalPoints);
        final ReviewerRoi reduced = reducedRoi(checked,
                hierarchies(loops), allocations);
        requireSamePartRelationships(checked, reduced);
        return reduced;
    }

    /**
     * Reduces closed loops to an exact total while retaining original points.
     * Each loop keeps at least three vertices.
     */
    public static List<List<Point2D>> reduceLoops(
            final List<List<Point2D>> sourceLoops,
            final int totalPoints) {
        final List<List<Point2D>> loops = checkedLoops(sourceLoops);
        final int[] allocations = allocateCounts(loops, totalPoints);
        return reducedLoops(hierarchies(loops), allocations);
    }

    private static List<Integer> automaticTargets(
            final List<List<Point2D>> loops,
            final int current) {
        final int minimum = 3 * loops.size();
        final int cap = Math.min(current, Math.max(minimum, 64));
        final List<Integer> targets = new ArrayList<>();
        for (final int option : AUTOMATIC_TARGETS) {
            if (option >= minimum && option <= cap) {
                targets.add(option);
            }
        }
        if (targets.isEmpty() || targets.get(targets.size() - 1) != cap) {
            targets.add(cap);
        }
        return List.copyOf(targets);
    }

    private static List<ReductionHierarchy> hierarchies(
            final List<List<Point2D>> loops) {
        return loops.stream().map(
                ReviewerRoiVertexReducer::buildHierarchy).toList();
    }

    private static List<List<Point2D>> reducedLoops(
            final List<ReductionHierarchy> hierarchies,
            final int[] allocations) {
        final List<List<Point2D>> result = new ArrayList<>();
        for (int index = 0; index < hierarchies.size(); index++) {
            result.add(hierarchies.get(index).points(allocations[index]));
        }
        return List.copyOf(result);
    }

    private static ReviewerRoi reducedRoi(
            final ReviewerRoi roi,
            final List<ReductionHierarchy> hierarchies,
            final int[] allocations) {
        final List<ReviewerRoiPart> parts = new ArrayList<>();
        for (int index = 0; index < roi.parts().size(); index++) {
            final ReviewerRoiPart part = roi.parts().get(index);
            final List<ReviewerRoiVertex> vertices = hierarchies.get(index)
                    .retainedIndices(allocations[index]).stream()
                    .map(part.vertices()::get).toList();
            parts.add(new ReviewerRoiPart(part.id(), part.operation(),
                    vertices, part.finished()));
        }
        return roi.withParts(parts);
    }

    private static List<List<Point2D>> pointLoops(final ReviewerRoi roi) {
        return roi.parts().stream().map(part -> part.vertices().stream()
                .map(ReviewerRoiVertex::sourcePoint).toList()).toList();
    }

    private static int[] allocateCounts(
            final List<List<Point2D>> loops,
            final int requestedTotal) {
        final int current = loops.stream().mapToInt(List::size).sum();
        final int minimum = 3 * loops.size();
        if (requestedTotal < minimum) {
            throw new IllegalArgumentException(
                    "At least " + minimum + " points are required for "
                    + loops.size() + " polygon parts");
        }
        final int target = Math.min(requestedTotal, current);
        final int[] result = new int[loops.size()];
        Arrays.fill(result, 3);
        final double[] weights = loops.stream().mapToDouble(
                ReviewerRoiVertexReducer::allocationWeight).toArray();
        final double totalWeight = Arrays.stream(weights).sum();
        for (int assigned = minimum; assigned < target; assigned++) {
            int best = -1;
            double bestDeficit = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < loops.size(); index++) {
                if (result[index] >= loops.get(index).size()) {
                    continue;
                }
                final double ideal = target * weights[index] / totalWeight;
                final double deficit = ideal - result[index];
                if (deficit > bestDeficit) {
                    bestDeficit = deficit;
                    best = index;
                }
            }
            if (best < 0) {
                throw new IllegalStateException(
                        "Unable to allocate the requested ROI point count");
            }
            result[best]++;
        }
        return result;
    }

    private static double allocationWeight(final List<Point2D> loop) {
        double perimeter = 0;
        double totalTurn = 0;
        for (int index = 0; index < loop.size(); index++) {
            final Point2D previous = loop.get(
                    (index + loop.size() - 1) % loop.size());
            final Point2D current = loop.get(index);
            final Point2D next = loop.get((index + 1) % loop.size());
            perimeter += distance(current, next);
            final double firstAngle = Math.atan2(current.y() - previous.y(),
                    current.x() - previous.x());
            final double secondAngle = Math.atan2(next.y() - current.y(),
                    next.x() - current.x());
            double turn = secondAngle - firstAngle;
            while (turn > Math.PI) {
                turn -= 2 * Math.PI;
            }
            while (turn < -Math.PI) {
                turn += 2 * Math.PI;
            }
            totalTurn += Math.abs(turn);
        }
        final double complexity = Math.min(4.0,
                Math.max(1.0, totalTurn / (2 * Math.PI)));
        return Math.max(GEOMETRY_EPSILON, perimeter)
                * (1.0 + 0.25 * (complexity - 1.0));
    }

    private static ReductionHierarchy buildHierarchy(
            final List<Point2D> loop) {
        final List<Node> nodes = new ArrayList<>();
        for (int index = 0; index < loop.size(); index++) {
            nodes.add(new Node(index, loop.get(index)));
        }
        for (int index = 0; index < nodes.size(); index++) {
            nodes.get(index).previous = nodes.get(
                    (index + nodes.size() - 1) % nodes.size());
            nodes.get(index).next = nodes.get((index + 1) % nodes.size());
        }
        final PriorityQueue<Candidate> candidates = new PriorityQueue<>(
                Comparator.comparingDouble(Candidate::importance)
                        .thenComparingInt(Candidate::index));
        for (final Node node : nodes) {
            enqueue(node, candidates);
        }
        final double originalArea = signedAreaTwice(loop);
        double currentArea = originalArea;
        int remaining = nodes.size();
        final List<Node> deferred = new ArrayList<>();
        final List<Integer> removalOrder = new ArrayList<>();
        while (remaining > 3) {
            Node selected = null;
            while (!candidates.isEmpty()) {
                final Candidate candidate = candidates.remove();
                final Node node = nodes.get(candidate.index());
                if (node.removed || candidate.version() != node.version) {
                    continue;
                }
                final double updatedArea = areaAfterRemoval(
                        currentArea, node);
                if (sameWinding(originalArea, updatedArea)
                        && removalKeepsSimple(node, nodes)) {
                    selected = node;
                    currentArea = updatedArea;
                    break;
                }
                deferred.add(node);
            }
            if (selected == null) {
                throw new IllegalArgumentException(
                        "The requested point count would change polygon topology");
            }
            selected.removed = true;
            selected.previous.next = selected.next;
            selected.next.previous = selected.previous;
            removalOrder.add(selected.index);
            remaining--;
            final double inherited = selected.importance;
            selected.previous.floor = Math.max(
                    selected.previous.floor, inherited);
            selected.next.floor = Math.max(selected.next.floor, inherited);
            enqueue(selected.previous, candidates);
            enqueue(selected.next, candidates);
            for (final Node node : deferred) {
                if (!node.removed) {
                    enqueue(node, candidates);
                }
            }
            deferred.clear();
        }
        return new ReductionHierarchy(loop, removalOrder);
    }

    private static void enqueue(
            final Node node,
            final PriorityQueue<Candidate> candidates) {
        node.version++;
        node.importance = Math.max(node.floor,
                Math.abs(cross(node.previous.point, node.point,
                        node.next.point)) * 0.5);
        candidates.add(new Candidate(node.index, node.version,
                node.importance));
    }

    private static double areaAfterRemoval(
            final double currentArea,
            final Node node) {
        return currentArea
                - edgeCross(node.previous.point, node.point)
                - edgeCross(node.point, node.next.point)
                + edgeCross(node.previous.point, node.next.point);
    }

    private static boolean sameWinding(
            final double originalArea,
            final double candidateArea) {
        final double epsilon = Math.max(GEOMETRY_EPSILON,
                Math.abs(originalArea) * GEOMETRY_EPSILON);
        return Math.abs(candidateArea) > epsilon
                && Math.signum(candidateArea) == Math.signum(originalArea);
    }

    private static boolean removalKeepsSimple(
            final Node removed,
            final List<Node> nodes) {
        final Point2D start = removed.previous.point;
        final Point2D end = removed.next.point;
        if (distance(start, end) <= GEOMETRY_EPSILON) {
            return false;
        }
        for (final Node edgeStart : nodes) {
            if (edgeStart.removed || edgeStart == removed
                    || edgeStart.next == removed) {
                continue;
            }
            final Node edgeEnd = edgeStart.next;
            if (edgeStart == removed.previous || edgeEnd == removed.previous
                    || edgeStart == removed.next || edgeEnd == removed.next) {
                continue;
            }
            if (segmentsIntersect(start, end,
                    edgeStart.point, edgeEnd.point)) {
                return false;
            }
        }
        return true;
    }

    private static boolean withinAutomaticTolerance(
            final List<List<Point2D>> originals,
            final List<List<Point2D>> candidates,
            final int sourceWidth,
            final int sourceHeight) {
        final double sourceDiagonal = Math.hypot(sourceWidth, sourceHeight);
        for (int index = 0; index < originals.size(); index++) {
            final List<Point2D> original = originals.get(index);
            final List<Point2D> candidate = candidates.get(index);
            final double extent = extentDiagonal(original);
            final double tolerance = Math.max(0.75,
                    Math.min(extent * 0.04, sourceDiagonal * 0.006));
            if (maximumDeviation(original, candidate) > tolerance) {
                return false;
            }
            final double originalArea = Math.abs(signedAreaTwice(original));
            final double candidateArea = Math.abs(signedAreaTwice(candidate));
            if (Math.abs(candidateArea - originalArea) / originalArea
                    > MAXIMUM_RELATIVE_AREA_CHANGE) {
                return false;
            }
        }
        return true;
    }

    private static double maximumDeviation(
            final List<Point2D> original,
            final List<Point2D> candidate) {
        double maximum = 0;
        for (final Point2D point : original) {
            double nearest = Double.POSITIVE_INFINITY;
            for (int index = 0; index < candidate.size(); index++) {
                nearest = Math.min(nearest, distanceToSegment(point,
                        candidate.get(index), candidate.get(
                                (index + 1) % candidate.size())));
            }
            maximum = Math.max(maximum, nearest);
        }
        return maximum;
    }

    private static double distanceToSegment(
            final Point2D point,
            final Point2D start,
            final Point2D end) {
        final double dx = end.x() - start.x();
        final double dy = end.y() - start.y();
        final double denominator = dx * dx + dy * dy;
        if (denominator <= GEOMETRY_EPSILON) {
            return distance(point, start);
        }
        final double fraction = Math.max(0, Math.min(1,
                ((point.x() - start.x()) * dx
                        + (point.y() - start.y()) * dy) / denominator));
        return Math.hypot(point.x() - (start.x() + fraction * dx),
                point.y() - (start.y() + fraction * dy));
    }

    private static double extentDiagonal(final List<Point2D> loop) {
        double minimumX = Double.POSITIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        for (final Point2D point : loop) {
            minimumX = Math.min(minimumX, point.x());
            minimumY = Math.min(minimumY, point.y());
            maximumX = Math.max(maximumX, point.x());
            maximumY = Math.max(maximumY, point.y());
        }
        return Math.hypot(maximumX - minimumX, maximumY - minimumY);
    }

    private static List<List<Point2D>> checkedLoops(
            final List<List<Point2D>> sourceLoops) {
        final List<List<Point2D>> supplied = List.copyOf(
                Objects.requireNonNull(sourceLoops, "sourceLoops"));
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one closed ROI polygon is required");
        }
        final List<List<Point2D>> result = new ArrayList<>();
        for (final List<Point2D> sourceLoop : supplied) {
            final List<Point2D> loop = new ArrayList<>(List.copyOf(
                    Objects.requireNonNull(sourceLoop, "sourceLoop")));
            if (loop.size() > 1
                    && distance(loop.get(0), loop.get(loop.size() - 1))
                    <= GEOMETRY_EPSILON) {
                loop.remove(loop.size() - 1);
            }
            for (int index = loop.size() - 1; index >= 0; index--) {
                final Point2D point = Objects.requireNonNull(
                        loop.get(index), "ROI point");
                if (!Double.isFinite(point.x()) || !Double.isFinite(point.y())) {
                    throw new IllegalArgumentException(
                            "ROI points must be finite");
                }
                if (loop.size() > 1 && distance(point,
                        loop.get((index + loop.size() - 1) % loop.size()))
                        <= GEOMETRY_EPSILON) {
                    loop.remove(index);
                }
            }
            if (loop.size() < 3) {
                throw new IllegalArgumentException(
                        "A closed ROI polygon requires at least three distinct points");
            }
            requireSimpleNonzero(loop);
            result.add(List.copyOf(loop));
        }
        return List.copyOf(result);
    }

    private static List<List<Point2D>> exactLoops(
            final ReviewerRoi roi) {
        final List<List<Point2D>> result = roi.parts().stream()
                .map(part -> part.vertices().stream()
                        .map(ReviewerRoiVertex::sourcePoint).toList()).toList();
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one closed ROI polygon is required");
        }
        for (final List<Point2D> loop : result) {
            if (loop.size() < 3) {
                throw new IllegalArgumentException(
                        "A closed ROI polygon requires at least three points");
            }
            requireSimpleNonzero(loop);
        }
        return result;
    }

    private static void requireSamePartRelationships(
            final ReviewerRoi original,
            final ReviewerRoi candidate) {
        if (!sameLoopRelationships(relationshipMatrix(pointLoops(original)),
                pointLoops(candidate))) {
            throw new IllegalArgumentException(
                    "The requested point count would change containment, contact, or overlap between polygon parts");
        }
    }

    private static SpatialRelationship[][] relationshipMatrix(
            final List<List<Point2D>> loops) {
        final SpatialRelationship[][] result =
                new SpatialRelationship[loops.size()][loops.size()];
        for (int first = 0; first < loops.size(); first++) {
            for (int second = first + 1; second < loops.size(); second++) {
                result[first][second] = relationship(
                        loops.get(first), loops.get(second));
            }
        }
        return result;
    }

    private static boolean sameLoopRelationships(
            final SpatialRelationship[][] original,
            final List<List<Point2D>> candidate) {
        if (original.length != candidate.size()) {
            return false;
        }
        for (int first = 0; first < original.length; first++) {
            for (int second = first + 1; second < original.length; second++) {
                if (original[first][second]
                        != relationship(candidate.get(first),
                                candidate.get(second))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static SpatialRelationship relationship(
            final List<Point2D> first,
            final List<Point2D> second) {
        final Area firstArea = new Area(path(first));
        final Area secondArea = new Area(path(second));
        final Area firstOnly = new Area(firstArea);
        firstOnly.subtract(secondArea);
        final Area secondOnly = new Area(secondArea);
        secondOnly.subtract(firstArea);
        final boolean boundariesContact = boundariesContact(first, second);
        if (firstOnly.isEmpty() && secondOnly.isEmpty()) {
            return SpatialRelationship.EQUAL;
        }
        if (secondOnly.isEmpty()) {
            return boundariesContact
                    ? SpatialRelationship.FIRST_CONTAINS_SECOND_TOUCHING
                    : SpatialRelationship.FIRST_CONTAINS_SECOND;
        }
        if (firstOnly.isEmpty()) {
            return boundariesContact
                    ? SpatialRelationship.SECOND_CONTAINS_FIRST_TOUCHING
                    : SpatialRelationship.SECOND_CONTAINS_FIRST;
        }
        final Area intersection = new Area(firstArea);
        intersection.intersect(secondArea);
        if (!intersection.isEmpty()) {
            return SpatialRelationship.OVERLAPPING;
        }
        return boundariesContact ? SpatialRelationship.TOUCHING
                : SpatialRelationship.DISJOINT;
    }

    private static boolean boundariesContact(
            final List<Point2D> first,
            final List<Point2D> second) {
        for (int firstIndex = 0; firstIndex < first.size(); firstIndex++) {
            final Point2D firstStart = first.get(firstIndex);
            final Point2D firstEnd = first.get(
                    (firstIndex + 1) % first.size());
            for (int secondIndex = 0;
                    secondIndex < second.size(); secondIndex++) {
                if (segmentsIntersect(firstStart, firstEnd,
                        second.get(secondIndex), second.get(
                                (secondIndex + 1) % second.size()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean combinedGeometryWithinAutomaticTolerance(
            final Area originalArea,
            final double originalMeasure,
            final ReviewerRoi candidate) {
        final Area candidateArea = combinedArea(candidate);
        if (originalArea.isEmpty() || candidateArea.isEmpty()) {
            return false;
        }
        if (originalMeasure <= GEOMETRY_EPSILON) {
            return false;
        }
        final Area symmetricDifference = new Area(originalArea);
        symmetricDifference.exclusiveOr(candidateArea);
        return areaMeasure(symmetricDifference) / originalMeasure
                <= MAXIMUM_COMBINED_SYMMETRIC_DIFFERENCE;
    }

    private static Area combinedArea(final ReviewerRoi roi) {
        final Area additions = new Area();
        final Area subtractions = new Area();
        for (final ReviewerRoiPart part : roi.parts()) {
            if (part.operation() == RoiPartOperation.ADD) {
                additions.add(new Area(path(part.vertices().stream()
                        .map(ReviewerRoiVertex::sourcePoint).toList())));
            } else {
                subtractions.add(new Area(path(part.vertices().stream()
                        .map(ReviewerRoiVertex::sourcePoint).toList())));
            }
        }
        additions.subtract(subtractions);
        return additions;
    }

    private static double areaMeasure(final Area area) {
        final PathIterator iterator = area.getPathIterator(null, 0.01);
        final double[] coordinates = new double[6];
        double startX = 0;
        double startY = 0;
        double previousX = 0;
        double previousY = 0;
        double signedAreaTwice = 0;
        while (!iterator.isDone()) {
            switch (iterator.currentSegment(coordinates)) {
                case PathIterator.SEG_MOVETO -> {
                    startX = coordinates[0];
                    startY = coordinates[1];
                    previousX = startX;
                    previousY = startY;
                }
                case PathIterator.SEG_LINETO -> {
                    signedAreaTwice += previousX * coordinates[1]
                            - previousY * coordinates[0];
                    previousX = coordinates[0];
                    previousY = coordinates[1];
                }
                case PathIterator.SEG_CLOSE ->
                    signedAreaTwice += previousX * startY
                            - previousY * startX;
                default -> throw new IllegalStateException(
                        "Flattened ROI area contains a curved segment");
            }
            iterator.next();
        }
        return Math.abs(signedAreaTwice) * 0.5;
    }

    private static Path2D path(final List<Point2D> points) {
        final Path2D path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (int index = 0; index < points.size(); index++) {
            final Point2D point = points.get(index);
            if (index == 0) {
                path.moveTo(point.x(), point.y());
            } else {
                path.lineTo(point.x(), point.y());
            }
        }
        path.closePath();
        return path;
    }

    private static void requireSimpleNonzero(final List<Point2D> loop) {
        final double area = signedAreaTwice(loop);
        if (Math.abs(area) <= Math.max(GEOMETRY_EPSILON,
                extentDiagonal(loop) * extentDiagonal(loop)
                        * GEOMETRY_EPSILON)) {
            throw new IllegalArgumentException(
                    "ROI polygon must enclose nonzero area");
        }
        for (int first = 0; first < loop.size(); first++) {
            final int firstEnd = (first + 1) % loop.size();
            for (int second = first + 1; second < loop.size(); second++) {
                final int secondEnd = (second + 1) % loop.size();
                if (first == second || first == secondEnd
                        || firstEnd == second || firstEnd == secondEnd) {
                    continue;
                }
                if (segmentsIntersect(loop.get(first), loop.get(firstEnd),
                        loop.get(second), loop.get(secondEnd))) {
                    throw new IllegalArgumentException(
                            "ROI polygon must not self-intersect");
                }
            }
        }
    }

    private static boolean segmentsIntersect(
            final Point2D firstStart,
            final Point2D firstEnd,
            final Point2D secondStart,
            final Point2D secondEnd) {
        final double firstSide = cross(firstStart, firstEnd, secondStart);
        final double secondSide = cross(firstStart, firstEnd, secondEnd);
        final double thirdSide = cross(secondStart, secondEnd, firstStart);
        final double fourthSide = cross(secondStart, secondEnd, firstEnd);
        final double epsilon = GEOMETRY_EPSILON * Math.max(1.0,
                Math.max(distance(firstStart, firstEnd),
                        distance(secondStart, secondEnd)));
        if (((firstSide > epsilon && secondSide < -epsilon)
                || (firstSide < -epsilon && secondSide > epsilon))
                && ((thirdSide > epsilon && fourthSide < -epsilon)
                || (thirdSide < -epsilon && fourthSide > epsilon))) {
            return true;
        }
        return Math.abs(firstSide) <= epsilon
                        && onSegment(firstStart, firstEnd, secondStart, epsilon)
                || Math.abs(secondSide) <= epsilon
                        && onSegment(firstStart, firstEnd, secondEnd, epsilon)
                || Math.abs(thirdSide) <= epsilon
                        && onSegment(secondStart, secondEnd, firstStart, epsilon)
                || Math.abs(fourthSide) <= epsilon
                        && onSegment(secondStart, secondEnd, firstEnd, epsilon);
    }

    private static boolean onSegment(
            final Point2D start,
            final Point2D end,
            final Point2D point,
            final double epsilon) {
        return point.x() >= Math.min(start.x(), end.x()) - epsilon
                && point.x() <= Math.max(start.x(), end.x()) + epsilon
                && point.y() >= Math.min(start.y(), end.y()) - epsilon
                && point.y() <= Math.max(start.y(), end.y()) + epsilon;
    }

    private static double signedAreaTwice(final List<Point2D> loop) {
        double area = 0;
        for (int index = 0; index < loop.size(); index++) {
            area += edgeCross(loop.get(index),
                    loop.get((index + 1) % loop.size()));
        }
        return area;
    }

    private static double edgeCross(
            final Point2D first,
            final Point2D second) {
        return first.x() * second.y() - first.y() * second.x();
    }

    private static double cross(
            final Point2D first,
            final Point2D second,
            final Point2D third) {
        return (second.x() - first.x()) * (third.y() - first.y())
                - (second.y() - first.y()) * (third.x() - first.x());
    }

    private static double distance(
            final Point2D first,
            final Point2D second) {
        return Math.hypot(second.x() - first.x(),
                second.y() - first.y());
    }

    private static void requireSourceDimensions(
            final int sourceWidth,
            final int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException(
                    "Source dimensions must be positive");
        }
    }

    private static final class Node {
        private final int index;
        private final Point2D point;
        private Node previous;
        private Node next;
        private boolean removed;
        private long version;
        private double floor;
        private double importance;

        private Node(final int index, final Point2D point) {
            this.index = index;
            this.point = point;
        }
    }

    private record ReductionHierarchy(
            List<Point2D> loop,
            List<Integer> removalOrder) {
        private ReductionHierarchy {
            loop = List.copyOf(loop);
            removalOrder = List.copyOf(removalOrder);
        }

        private List<Integer> retainedIndices(final int count) {
            if (count < 3 || count > loop.size()) {
                throw new IllegalArgumentException(
                        "Invalid retained ROI point count: " + count);
            }
            final boolean[] removed = new boolean[loop.size()];
            for (int index = 0; index < loop.size() - count; index++) {
                removed[removalOrder.get(index)] = true;
            }
            return java.util.stream.IntStream.range(0, loop.size())
                    .filter(index -> !removed[index]).boxed().toList();
        }

        private List<Point2D> points(final int count) {
            return retainedIndices(count).stream().map(loop::get).toList();
        }
    }

    private enum SpatialRelationship {
        DISJOINT,
        TOUCHING,
        OVERLAPPING,
        FIRST_CONTAINS_SECOND,
        FIRST_CONTAINS_SECOND_TOUCHING,
        SECOND_CONTAINS_FIRST,
        SECOND_CONTAINS_FIRST_TOUCHING,
        EQUAL
    }

    private record Candidate(int index, long version, double importance) { }
}
