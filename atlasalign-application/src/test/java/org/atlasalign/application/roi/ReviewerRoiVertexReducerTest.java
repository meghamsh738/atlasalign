package org.atlasalign.application.roi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ReviewerRoiVertexReducerTest {

    @Test
    void compactReductionRetainsRectangleAndConcaveCorners() {
        final List<Point2D> denseRectangle = List.of(
                point(10, 10), point(30, 10), point(50, 10),
                point(50, 30), point(50, 50), point(30, 50),
                point(10, 50), point(10, 30));
        final List<Point2D> denseConcavity = List.of(
                point(0, 0), point(20, 0), point(40, 0),
                point(40, 40), point(30, 40), point(30, 20),
                point(20, 20), point(20, 40), point(10, 40),
                point(0, 40), point(0, 20));

        final List<Point2D> rectangle = ReviewerRoiVertexReducer
                .reduceLoops(List.of(denseRectangle), 4).get(0);
        final List<Point2D> concavity = ReviewerRoiVertexReducer
                .reduceLoops(List.of(denseConcavity), 8).get(0);

        assertEquals(Set.of(point(10, 10), point(50, 10),
                        point(50, 50), point(10, 50)), Set.copyOf(rectangle));
        assertTrue(concavity.containsAll(List.of(
                point(30, 20), point(20, 20))),
                "the inward notch must remain editable rather than being bridged");
    }

    @Test
    void automaticCountUsesSourceScaleAndShapeComplexity() {
        final List<Point2D> denseRectangle = denseRectangle(20, 20,
                180, 120, 12);
        final List<Point2D> smallCircle = circle(120, 120, 45, 96);
        final List<Point2D> largeCircle = circle(500, 500, 380, 192);

        assertEquals(4, ReviewerRoiVertexReducer.automaticTarget(
                List.of(denseRectangle), 1000, 1000));
        final int small = ReviewerRoiVertexReducer.automaticTarget(
                List.of(smallCircle), 1000, 1000);
        final int large = ReviewerRoiVertexReducer.automaticTarget(
                List.of(largeCircle), 1000, 1000);
        assertTrue(small >= 8 && small <= 24,
                "a curved guide should keep a compact but meaningful outline");
        assertTrue(large > small,
                "the same curvature at a larger visible scale needs more handles");
        assertTrue(large <= 64, "Auto must remain bounded");
    }

    @Test
    void automaticCountKeepsCurrentComplexContourWhenCapFailsTolerance() {
        final List<Point2D> fineAlternatingBoundary =
                java.util.stream.IntStream.range(0, 96)
                        .mapToObj(index -> {
                            final double angle = 2 * Math.PI * index / 96;
                            final double radius = index % 2 == 0 ? 210 : 165;
                            return point(300 + radius * Math.cos(angle),
                                    300 + radius * Math.sin(angle));
                        }).toList();

        assertEquals(96, ReviewerRoiVertexReducer.automaticTarget(
                List.of(fineAlternatingBoundary), 1000, 1000),
                "Auto must retain the current contour when every compact candidate exceeds its verified approximation tolerance");
    }

    @Test
    void automaticCountProtectsThinAddMinusSubtractFootprint() {
        final List<Point2D> outer = circle(300, 300, 200, 96);
        final List<Point2D> hole = circle(300, 300, 190, 96);
        final ReviewerRoi thinRing = new ReviewerRoi(
                "ring", "thin ring", ReviewerRoiSide.UNKNOWN,
                Optional.empty(), List.of(
                        part("outer", RoiPartOperation.ADD, outer),
                        part("hole", RoiPartOperation.SUBTRACT, hole)),
                true, true);

        assertEquals(192, ReviewerRoiVertexReducer.automaticTarget(
                thinRing, 700, 700),
                "small per-loop errors must not be amplified into a large change to a thin final ring");
    }

    @Test
    void guideLoopAutoRejectsNewContainmentContactOrOverlap() {
        final List<Point2D> outer = circle(300, 300, 200, 96);
        final List<Point2D> nearBoundaryInner = circle(
                309.8, 300, 190, 96);

        final int target = ReviewerRoiVertexReducer.automaticTarget(
                List.of(outer, nearBoundaryInner), 700, 700);

        assertTrue(target > 24,
                "Auto must reject otherwise accurate low-count loops when their strict containment changes");
        assertEquals(192, target,
                "when no candidate through the compact cap preserves strict containment, Auto must keep the source loops");
    }

    @Test
    void nearBoundaryHoleRejectionLeavesSessionUnchanged() {
        final ReviewerRoi original = new ReviewerRoi(
                "near-hole", "near boundary hole", ReviewerRoiSide.UNKNOWN,
                Optional.empty(), List.of(
                        part("outer", RoiPartOperation.ADD,
                                circle(300, 300, 200, 96)),
                        part("hole", RoiPartOperation.SUBTRACT,
                                circle(309.8, 300, 190, 96))),
                true, true);
        final ReviewerRoiSession session = ReviewerRoiSession.restore(
                "section", 700, 700, List.of(original),
                Optional.of(original.id()), Optional.empty(), 4);
        final ReviewerRoiSession.Snapshot before = session.snapshot();

        assertThrows(IllegalArgumentException.class,
                () -> session.reduceVertices(original.id(), 12));

        assertEquals(before, session.snapshot());
        assertFalse(session.canUndo());
    }

    @Test
    void explicitReductionPreservesPiecesHoleIdsAndOneUndoStep() {
        final ReviewerRoi original = new ReviewerRoi(
                "roi", "compound", ReviewerRoiSide.BILATERAL,
                Optional.empty(), List.of(
                        part("outer", RoiPartOperation.ADD,
                                denseRectangle(5, 5, 70, 70, 12)),
                        part("piece", RoiPartOperation.ADD,
                                denseRectangle(75, 10, 95, 30, 8)),
                        part("hole", RoiPartOperation.SUBTRACT,
                                denseRectangle(25, 25, 45, 45, 8))),
                true, true);
        final ReviewerRoiSession session = ReviewerRoiSession.restore(
                "section", 110, 90, List.of(original),
                Optional.of(original.id()), Optional.empty(), 7);

        session.reduceVertices(original.id(), 12);

        final ReviewerRoi reduced = session.snapshot().activeRoi()
                .orElseThrow();
        assertEquals(12, ReviewerRoiVertexReducer.vertexCount(reduced));
        assertEquals(List.of("outer", "piece", "hole"), reduced.parts()
                .stream().map(ReviewerRoiPart::id).toList());
        assertEquals(List.of(RoiPartOperation.ADD, RoiPartOperation.ADD,
                RoiPartOperation.SUBTRACT), reduced.parts().stream()
                        .map(ReviewerRoiPart::operation).toList());
        final Set<String> originalVertexIds = original.parts().stream()
                .flatMap(part -> part.vertices().stream())
                .map(ReviewerRoiVertex::id).collect(Collectors.toSet());
        assertTrue(reduced.parts().stream()
                .flatMap(part -> part.vertices().stream())
                .map(ReviewerRoiVertex::id).allMatch(originalVertexIds::contains),
                "reduction must retain original vertices rather than invent points");
        final ManualRoiFootprint footprint = ManualRoiFootprint.rasterize(
                reduced, 110, 90);
        assertTrue(footprint.containsSourcePixel(85, 20),
                "the separate ADD piece must remain");
        assertFalse(footprint.containsSourcePixel(35, 35),
                "the SUBTRACT hole must remain effective");

        session.undo();
        assertEquals(original, session.snapshot().activeRoi().orElseThrow());
        session.redo();
        assertEquals(reduced, session.snapshot().activeRoi().orElseThrow());
    }

    @Test
    void rejectedCountLeavesLoadedRoiAndHistoryUntouched() {
        final ReviewerRoi original = new ReviewerRoi(
                "roi", "compound", ReviewerRoiSide.UNKNOWN,
                Optional.empty(), List.of(
                        part("a", RoiPartOperation.ADD,
                                denseRectangle(5, 5, 40, 40, 8)),
                        part("b", RoiPartOperation.ADD,
                                denseRectangle(50, 5, 80, 35, 8)),
                        part("hole", RoiPartOperation.SUBTRACT,
                                denseRectangle(15, 15, 25, 25, 8))),
                true, true);
        final ReviewerRoiSession session = ReviewerRoiSession.restore(
                "section", 100, 80, List.of(original),
                Optional.of(original.id()), Optional.empty(), 11);
        final ReviewerRoiSession.Snapshot before = session.snapshot();

        assertThrows(IllegalArgumentException.class,
                () -> session.reduceVertices(original.id(), 8));

        assertEquals(before, session.snapshot());
        assertFalse(session.canUndo(),
                "a rejected reduction must not create ROI history");
    }

    private static ReviewerRoiPart part(
            final String id,
            final RoiPartOperation operation,
            final List<Point2D> points) {
        return new ReviewerRoiPart(id, operation,
                java.util.stream.IntStream.range(0, points.size())
                        .mapToObj(index -> new ReviewerRoiVertex(
                                id + "-v" + index, points.get(index)))
                        .toList(), true);
    }

    private static List<Point2D> denseRectangle(
            final double minimumX,
            final double minimumY,
            final double maximumX,
            final double maximumY,
            final int count) {
        final List<Point2D> corners = List.of(
                point(minimumX, minimumY), point(maximumX, minimumY),
                point(maximumX, maximumY), point(minimumX, maximumY));
        final List<Point2D> result = new ArrayList<>();
        final int perEdge = count / 4;
        for (int edge = 0; edge < 4; edge++) {
            final Point2D start = corners.get(edge);
            final Point2D end = corners.get((edge + 1) % 4);
            for (int sample = 0; sample < perEdge; sample++) {
                final double fraction = sample / (double) perEdge;
                result.add(point(start.x() + fraction * (end.x() - start.x()),
                        start.y() + fraction * (end.y() - start.y())));
            }
        }
        return List.copyOf(result);
    }

    private static List<Point2D> circle(
            final double centreX,
            final double centreY,
            final double radius,
            final int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> {
                    final double angle = 2 * Math.PI * index / count;
                    return point(centreX + radius * Math.cos(angle),
                            centreY + radius * Math.sin(angle));
                }).toList();
    }

    private static Point2D point(final double x, final double y) {
        return new Point2D(x, y);
    }
}
