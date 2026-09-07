package org.atlasalign.application.roi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ReviewerRoiSessionTest {

    @Test
    void deletingMultipleRoisIsAtomicAndUndoRestoresExactOrderAndGeometry() {
        final var session = new ReviewerRoiSession("S1", 100, 80);
        final String a = session.newPolygon("A", ReviewerRoiSide.LEFT, RoiPartOperation.ADD);
        session.addVertex(new Point2D(1, 1));
        session.addVertex(new Point2D(10, 1));
        session.addVertex(new Point2D(1, 10));
        session.finishActivePart();
        final String b = session.duplicate(a);
        final String c = session.duplicate(a);
        final var before = session.snapshot();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> session.deleteAll(java.util.List.of(a, "missing")));
        assertEquals(before, session.snapshot());
        session.deleteAll(java.util.List.of(a, c));
        assertEquals(java.util.List.of(b), session.snapshot().rois().stream().map(ReviewerRoi::id).toList());
        session.undo();
        assertEquals(before.rois(), session.snapshot().rois());
        session.redo();
        assertEquals(java.util.List.of(b), session.snapshot().rois().stream().map(ReviewerRoi::id).toList());
    }

    @Test
    void editsNamedSourceCoordinateRoisWithIndependentLocalHistory() {
        final ReviewerRoiSession session = new ReviewerRoiSession(
                "Section 1", 100, 80);
        final String id = session.newPolygon(
                "DG left", ReviewerRoiSide.LEFT, RoiPartOperation.ADD);
        session.addVertex(new Point2D(10, 10));
        session.addVertex(new Point2D(30, 10));
        session.addVertex(new Point2D(30, 30));
        session.addVertex(new Point2D(10, 30));
        session.finishActivePart();
        final String vertexId = session.snapshot().activeRoi()
                .orElseThrow().parts().get(0).vertices().get(0).id();

        session.moveVertex(id,
                session.snapshot().activeRoi().orElseThrow()
                        .parts().get(0).id(),
                vertexId, new Point2D(5, 7));
        assertEquals(new Point2D(5, 7), session.snapshot().activeRoi()
                .orElseThrow().parts().get(0).vertices().get(0)
                .sourcePoint());
        assertEquals(1, session.snapshot().exportableRois().size());

        session.undo();
        assertEquals(new Point2D(10, 10), session.snapshot().activeRoi()
                .orElseThrow().parts().get(0).vertices().get(0)
                .sourcePoint());
        session.redo();
        assertEquals(new Point2D(5, 7), session.snapshot().activeRoi()
                .orElseThrow().parts().get(0).vertices().get(0)
                .sourcePoint());

        session.setSelectedForExport(id, false);
        assertTrue(session.snapshot().exportableRois().isEmpty());
        session.setSelectedForExport(id, true);
        assertFalse(session.snapshot().exportableRois().isEmpty());
    }

    @Test
    void cancellingAnUnfinishedPartPreservesFinishedGeometry() {
        final ReviewerRoiSession session = new ReviewerRoiSession(
                "S1", 50, 50);
        session.newPolygon("ROI", ReviewerRoiSide.UNKNOWN,
                RoiPartOperation.ADD);
        session.addVertex(new Point2D(2, 2));
        session.addVertex(new Point2D(20, 2));
        session.addVertex(new Point2D(10, 20));
        session.finishActivePart();
        session.addPart(RoiPartOperation.SUBTRACT);
        session.addVertex(new Point2D(5, 5));

        session.cancelDraft();

        assertTrue(session.snapshot().activeRoi().orElseThrow().finished());
        assertEquals(1,
                session.snapshot().activeRoi().orElseThrow().parts().size());
    }
}
