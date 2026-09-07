package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.atlasalign.application.roi.ReviewerRoiPart;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.application.roi.ReviewerRoiSide;
import org.atlasalign.application.roi.ReviewerRoiVertex;
import org.atlasalign.application.roi.RoiPartOperation;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManualRoiSessionStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void autosaveRoundTripRestoresExactGeometryAndFreshIdentifiers() {
        final Path file = temporaryDirectory.resolve("manual-rois.json");
        final String sourceHash = "b".repeat(64);
        final ManualRoiSessionStore store = new ManualRoiSessionStore(
                file, "section-001", 100, 80, sourceHash);
        final ReviewerRoiSession original = new ReviewerRoiSession(
                "section-001", 100, 80);
        original.newPolygon("DG left", ReviewerRoiSide.LEFT,
                RoiPartOperation.ADD);
        original.addVertex(new Point2D(10, 10));
        original.addVertex(new Point2D(50, 10));
        original.addVertex(new Point2D(50, 40));
        original.addVertex(new Point2D(10, 40));
        original.finishActivePart();
        final ReviewerRoiPart imported = new ReviewerRoiPart(
                "external-part", RoiPartOperation.ADD, List.of(
                        new ReviewerRoiVertex("external-v1",
                                new Point2D(60, 10)),
                        new ReviewerRoiVertex("external-v2",
                                new Point2D(80, 10)),
                        new ReviewerRoiVertex("external-v3",
                                new Point2D(80, 30)),
                        new ReviewerRoiVertex("external-v4",
                                new Point2D(60, 30))), true);
        original.importCompleted("Imported A", ReviewerRoiSide.LEFT,
                List.of(imported));
        original.importCompleted("Imported B", ReviewerRoiSide.LEFT,
                List.of(imported));
        store.saveNow(original.snapshot());

        final ReviewerRoiSession restored = store.loadOrCreate();

        assertEquals(original.snapshot().rois(), restored.snapshot().rois());
        assertEquals(original.snapshot().revision(),
                restored.snapshot().revision());
        assertEquals(original.snapshot().activeRoiId(),
                restored.snapshot().activeRoiId());
        assertEquals(original.snapshot().activePartId(),
                restored.snapshot().activePartId());
        final Set<String> oldIds = ids(original);
        restored.newPolygon("CA1", ReviewerRoiSide.LEFT,
                RoiPartOperation.ADD);
        final Set<String> newIds = ids(restored);
        assertNotEquals(oldIds, newIds);
        assertEquals(oldIds.size(), newIds.stream()
                .filter(oldIds::contains).count());
        assertThrows(IllegalStateException.class,
                () -> new ManualRoiSessionStore(file, "section-001",
                        100, 80, "c".repeat(64)).loadOrCreate());
    }

    @Test
    void unfinishedPolygonResumesAsTheActiveDraft() {
        final Path file = temporaryDirectory.resolve("unfinished.json");
        final ManualRoiSessionStore store = new ManualRoiSessionStore(
                file, "section-002", 40, 30, "d".repeat(64));
        final ReviewerRoiSession original = new ReviewerRoiSession(
                "section-002", 40, 30);
        original.newPolygon("DG right", ReviewerRoiSide.RIGHT,
                RoiPartOperation.ADD);
        original.addVertex(new Point2D(4, 5));
        original.addVertex(new Point2D(20, 6));
        store.saveNow(original.snapshot());

        final ReviewerRoiSession restored = store.loadOrCreate();

        assertEquals(original.snapshot().activeRoiId(),
                restored.snapshot().activeRoiId());
        assertEquals(original.snapshot().activePartId(),
                restored.snapshot().activePartId());
        restored.addVertex(new Point2D(18, 20));
        restored.finishActivePart();
        assertEquals(1, restored.snapshot().exportableRois().size());
    }

    private static Set<String> ids(final ReviewerRoiSession session) {
        return session.snapshot().rois().stream().flatMap(roi ->
                java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(roi.id()),
                        roi.parts().stream().flatMap(part ->
                                java.util.stream.Stream.concat(
                                        java.util.stream.Stream.of(part.id()),
                                        part.vertices().stream().map(
                                                vertex -> vertex.id())))))
                .collect(Collectors.toSet());
    }
}
