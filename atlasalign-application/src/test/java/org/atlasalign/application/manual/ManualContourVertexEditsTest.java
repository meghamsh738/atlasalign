package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ManualContourVertexEditsTest {

    @Test
    void secondVertexCanBeAppendedToClosedDraftWithoutInventingSelfEdge() {
        final ManualContour oneVertex = ManualContourContractTest.contour(
                "closed-draft", ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.DRAFT,
                List.of(vertex("a", 10, 10)), Set.of());

        final ManualContour changed = ManualContourVertexEdits.insertAfter(
                oneVertex, "a", vertex("b", 20, 10));

        assertEquals(List.of("a", "b"), ids(changed));
        assertTrue(changed.excludedGapSegments().isEmpty());
        assertEquals(ContourCaptureStatus.DRAFT, changed.captureStatus());
    }

    @Test
    void insertionSplitsExcludedOpenEdgeIntoTwoExcludedEdges() {
        final ManualContour contour = open(Set.of(segment("a", "b")));

        final ManualContour changed = ManualContourVertexEdits.insertAfter(
                contour, "a", vertex("x", 15, 10));

        assertEquals(List.of("a", "x", "b", "c"), ids(changed));
        assertEquals(Set.of(segment("a", "x"), segment("x", "b")),
                changed.excludedGapSegments());
    }

    @Test
    void insertionSplitsExcludedClosedWraparoundEdge() {
        final ManualContour contour = closed(Set.of(segment("c", "a")));

        final ManualContour changed = ManualContourVertexEdits.insertAfter(
                contour, "c", vertex("x", 15, 20));

        assertEquals(Set.of(segment("c", "x"), segment("x", "a")),
                changed.excludedGapSegments());
    }

    @Test
    void deletingOpenInteriorVertexMergesGapIfEitherAdjacentEdgeWasExcluded() {
        for (final Set<ContourSegment> gaps : List.of(
                Set.of(segment("a", "b")),
                Set.of(segment("b", "c")),
                Set.of(segment("a", "b"), segment("b", "c")))) {
            final ManualContour changed = ManualContourVertexEdits.delete(
                    open(gaps), "b");
            assertEquals(Set.of(segment("a", "c")),
                    changed.excludedGapSegments());
        }
        assertTrue(ManualContourVertexEdits.delete(open(Set.of()), "b")
                .excludedGapSegments().isEmpty());
    }

    @Test
    void deletingClosedVertexPreservesMissingEvidenceAcrossWraparound() {
        final ManualContour changed = ManualContourVertexEdits.delete(
                closed(Set.of(segment("c", "a"))), "a");

        assertEquals(List.of("b", "c"), ids(changed));
        assertEquals(Set.of(segment("c", "b")),
                changed.excludedGapSegments());
        assertEquals(ContourCaptureStatus.DRAFT, changed.captureStatus());
        assertFalse(changed.eligibleForPreview());
    }

    private static ManualContour open(final Set<ContourSegment> gaps) {
        return ManualContourContractTest.contour(
                "open", ManualContourKind.VISIBLE_TISSUE_BOUNDARY,
                ContourTopology.OPEN, ContourCaptureStatus.COMPLETE,
                List.of(vertex("a", 10, 10), vertex("b", 20, 10),
                        vertex("c", 30, 10)), gaps);
    }

    private static ManualContour closed(final Set<ContourSegment> gaps) {
        return ManualContourContractTest.contour(
                "closed", ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.COMPLETE,
                List.of(vertex("a", 10, 10), vertex("b", 30, 10),
                        vertex("c", 20, 30)), gaps);
    }

    private static ContourVertex vertex(
            final String id, final double x, final double y) {
        return ManualContourContractTest.vertex(id, x, y);
    }

    private static ContourSegment segment(
            final String from, final String to) {
        return new ContourSegment(from, to);
    }

    private static List<String> ids(final ManualContour contour) {
        return contour.vertices().stream().map(ContourVertex::id).toList();
    }
}
