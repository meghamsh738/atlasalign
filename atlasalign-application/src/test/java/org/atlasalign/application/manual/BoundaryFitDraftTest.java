package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class BoundaryFitDraftTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void keepsIncompleteAnchorsSeparateFromSolverMatches() {
        BoundaryFitDraft draft = draft();

        assertTrue(draft.completedMatches().isEmpty());
        assertEquals("anchor-1", draft.activeAnchorId().orElseThrow());

        draft = draft.withAnchor(draft.anchors().get(0)
                .withTissuePoint(new Point2D(11, 12)))
                .selectNextIncompleteAfter("anchor-1");

        assertEquals(1, draft.completedMatches().size());
        assertEquals("anchor-2", draft.activeAnchorId().orElseThrow());
        assertEquals(BoundaryFitMatchOrigin.USER_PLACED,
                draft.completedMatches().get(0).origin());
    }

    @Test
    void skippedAnchorDoesNotCountAsAnIncludedCompletedPair() {
        BoundaryFitDraft draft = draft();
        final BoundaryFitAnchor first = draft.anchors().get(0)
                .withTissuePoint(new Point2D(11, 12))
                .withIncluded(false);
        draft = draft.withAnchor(first);

        assertEquals(0, draft.includedCompletedCount());
        assertEquals(1, draft.completedMatches().size());
        assertFalse(draft.completedMatches().get(0).included());
    }

    @Test
    void skippingTheFinalUsableAnchorClearsTheActivePrompt() {
        BoundaryFitDraft draft = draft();
        for (int index = 0; index < draft.anchors().size() - 1; index++) {
            final BoundaryFitAnchor anchor = draft.anchors().get(index);
            draft = draft.withAnchor(anchor.withTissuePoint(
                    new Point2D(10 + index, 12 + index)));
        }
        final BoundaryFitAnchor last = draft.anchors().get(3);
        draft = draft.withActiveAnchor(last.id())
                .withAnchor(last.withIncluded(false))
                .selectNextIncompleteAfter(last.id());

        assertTrue(draft.activeAnchorId().isEmpty());
        assertEquals(3, draft.includedCompletedCount());
        assertFalse(draft.anchors().get(3).included());
    }

    @Test
    void excludedAnchorCanBeSelectedAndRestoredWithoutLosingItsGeometry() {
        final BoundaryFitAnchor first = draft().anchors().get(0)
                .withTissuePoint(new Point2D(11, 12))
                .withIncluded(false);
        BoundaryFitDraft draft = draft().withAnchor(first)
                .withoutActiveAnchor();

        draft = draft.withAnchor(first.withIncluded(true))
                .withActiveAnchor(first.id());

        assertEquals(first.id(), draft.activeAnchorId().orElseThrow());
        assertTrue(draft.activeAnchor().orElseThrow().included());
        assertEquals(new Point2D(11, 12), draft.activeAnchor().orElseThrow()
                .tissuePreviewPoint().orElseThrow());
    }

    private static BoundaryFitDraft draft() {
        final List<BoundaryFitSample> boundary = List.of(
                sample(0, 0), sample(20, 0), sample(20, 20),
                sample(0, 20));
        final BoundaryFitRequest request = new BoundaryFitRequest(
                0, BoundaryFitModel.SIMILARITY,
                ReviewSectionMode.FULL, Optional.empty(), boundary,
                boundary, List.of(), new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1, 0, 0, 0, 1, 0), 0, 40, 40,
                HASH, HASH, HASH, HASH, HASH);
        final List<BoundaryFitAnchor> anchors = List.of(
                anchor(1, 0, 0), anchor(2, 20, 0),
                anchor(3, 20, 20), anchor(4, 0, 20));
        return new BoundaryFitDraft(request, anchors,
                Optional.of("anchor-1"));
    }

    private static BoundaryFitAnchor anchor(
            final int ordinal,
            final double x,
            final double y) {
        return new BoundaryFitAnchor("anchor-" + ordinal, ordinal,
                new Point2D(x, y), Optional.empty(), true);
    }

    private static BoundaryFitSample sample(
            final double x,
            final double y) {
        return new BoundaryFitSample(new Point2D(x, y),
                new Point2D(1, 0));
    }
}
