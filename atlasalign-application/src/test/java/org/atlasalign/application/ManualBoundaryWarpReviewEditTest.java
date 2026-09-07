package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.atlasalign.application.manual.BoundaryWarpSolver;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualWarpControl;
import org.atlasalign.application.manual.ManualWarpControlOrigin;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ManualBoundaryWarpReviewEditTest {

    private static final String INPUT_HASH = "b".repeat(64);

    @Test
    void appliedBorderGroupIsOneManualOnlyRevisionWithExactUndoRedo() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final AlignmentReviewContent before = session.state().content();
        final ReviewConfidenceReport confidenceBefore = session.confidence();
        final List<ManualWarpControl> controls = borderControls();
        final ManualWarpPrecondition precondition =
                ManualWarpPrecondition.capture(session.state());
        final ManualHemisphereWarp2D field = ManualHemisphereWarp2D.fit(
                controls, before.orientation(), before.reviewSectionMode(),
                new ManualHemisphereWarp2D.MidlineSegment(
                        new Point2D(49.5, 0), new Point2D(49.5, 79)),
                100, 80);

        session.apply(new ReviewEdit.ApplyManualBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                BoundaryWarpSolver.GROUP_ID, controls, precondition, field,
                BoundaryWarpSolver.SOLVER_REVISION, INPUT_HASH));

        final AlignmentReviewContent applied = session.state().content();
        assertEquals(1, session.state().contentRevision());
        assertEquals(ReviewOperation.APPLY_MANUAL_BOUNDARY_WARP,
                session.auditTrail().get(0).operation());
        assertTrue(session.auditTrail().get(0).description()
                .contains("manual geometry only, confidence unchanged"));
        assertEquals(controls, applied.hemisphereWarp()
                .orElseThrow().controls());
        assertTrue(applied.landmarks().isEmpty());
        assertEquals(before.coronalLevel(), applied.coronalLevel());
        assertEquals(before.atlasPlaneTilt(), applied.atlasPlaneTilt());
        assertEquals(before.orientation(), applied.orientation());
        assertEquals(before.observedHemisphere(),
                applied.observedHemisphere());
        assertEquals(confidenceBefore.category(),
                session.confidence().category());
        assertEquals(confidenceBefore.reasons(),
                session.confidence().reasons());
        assertTrue(session.state().activeLandmarkResiduals().isEmpty());

        assertTrue(session.undo());
        assertEquals(before, session.state().content());
        assertTrue(session.redo());
        assertEquals(applied, session.state().content());
    }

    @Test
    void staleBorderApplyInstallsNoRevisionOrAuditEntry() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final List<ManualWarpControl> controls = borderControls();
        final ManualWarpPrecondition captured =
                ManualWarpPrecondition.capture(session.state());
        final long capturedRevision = session.state().contentRevision();
        final ManualHemisphereWarp2D field = ManualHemisphereWarp2D.fit(
                controls, session.state().content().orientation(),
                ReviewSectionMode.FULL,
                new ManualHemisphereWarp2D.MidlineSegment(
                        new Point2D(49.5, 0), new Point2D(49.5, 79)),
                100, 80);
        session.apply(new ReviewEdit.SetAtlasPlaneTilt(
                new AtlasPlaneTilt(1, 0)));
        final AlignmentReviewContent changed = session.state().content();
        final int auditSize = session.auditTrail().size();

        assertFalse(session.applyIfCurrentRevision(capturedRevision,
                new ReviewEdit.ApplyManualBoundaryWarp(
                        ManualHemisphereWarp2D.AtlasSide.LEFT,
                        BoundaryWarpSolver.GROUP_ID, controls, captured,
                        field, BoundaryWarpSolver.SOLVER_REVISION,
                        INPUT_HASH)));

        assertEquals(changed, session.state().content());
        assertEquals(auditSize, session.auditTrail().size());
    }

    private static List<ManualWarpControl> borderControls() {
        final List<Point2D> sources = List.of(
                new Point2D(22, 10), new Point2D(10, 25),
                new Point2D(8, 50), new Point2D(25, 68),
                new Point2D(42, 55), new Point2D(40, 22));
        final List<ManualWarpControl> result = new ArrayList<>();
        for (int index = 0; index < sources.size(); index++) {
            result.add(new ManualWarpControl("border-" + index,
                    ManualHemisphereWarp2D.AtlasSide.LEFT,
                    ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR,
                    BoundaryWarpSolver.GROUP_ID,
                    sources.get(index), sources.get(index)));
        }
        return List.copyOf(result);
    }
}
