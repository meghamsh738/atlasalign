package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualWarpControl;
import org.atlasalign.application.manual.ManualWarpControlOrigin;
import org.atlasalign.application.manual.ManualWarpException;
import org.atlasalign.application.manual.ManualWarpFailureKind;
import org.atlasalign.application.manual.MonotoneBoundary2D;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ManualWarpControlReviewEditTest {

    @Test
    void replaceMoveUndoRedoAndAcceptanceUseManualGeometryNotLandmarks() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = outlinedSession(basis);
        final BoundaryAuthoritativeTransform2D outline = exactOutline();
        final List<ManualWarpControl> initial = controls(
                ManualHemisphereWarp2D.AtlasSide.LEFT, "structure:DG");
        final ManualWarpPrecondition empty = ManualWarpPrecondition.capture(
                session.state());
        final ManualHemisphereWarp2D first = ManualHemisphereWarp2D.fit(
                initial, session.state().content().orientation(), outline);
        final AllenCoronalLevel levelBefore = session.state().content()
                .coronalLevel();
        final AtlasPlaneTilt tiltBefore = session.state().content()
                .atlasPlaneTilt();
        final AtlasOrientation orientationBefore = session.state().content()
                .orientation();
        final ObservedAnatomicalHemisphere hemisphereBefore = session.state()
                .content().observedHemisphere();
        final ConfidenceEvidenceCategory confidenceBefore = session
                .confidence().category();

        session.apply(new ReviewEdit.ReplaceManualWarpControlGroup(
                ManualHemisphereWarp2D.AtlasSide.LEFT, "structure:DG",
                initial, empty, first));

        assertTrue(session.state().content().landmarks().isEmpty(),
                "manual mesh controls must never enter LandmarkPair state");
        assertTrue(session.state().activeLandmarkResiduals().isEmpty(),
                "manual controls must never enter landmark residuals");
        assertEquals(4, session.state().content().hemisphereWarp()
                .orElseThrow().manualControls(
                        ManualHemisphereWarp2D.AtlasSide.LEFT).size());
        assertEquals(levelBefore, session.state().content().coronalLevel());
        assertEquals(tiltBefore, session.state().content().atlasPlaneTilt());
        assertEquals(orientationBefore,
                session.state().content().orientation());
        assertEquals(hemisphereBefore,
                session.state().content().observedHemisphere());
        assertEquals(confidenceBefore, session.confidence().category(),
                "manual controls cannot promote or replace automatic evidence");
        assertEquals(ReviewOperation.REPLACE_MANUAL_WARP_CONTROL_GROUP,
                lastOperation(session));
        final long beforeMoveRevision = session.state().contentRevision();
        final String firstHash = first.diagnostics().contentSha256();

        final Point2D movedTarget = new Point2D(27, 31);
        final List<ManualWarpControl> moved = replaceTarget(
                initial, "left-1", movedTarget);
        final ManualWarpPrecondition prior = ManualWarpPrecondition.capture(
                session.state());
        final ManualHemisphereWarp2D second = ManualHemisphereWarp2D.fit(
                moved, session.state().content().orientation(), outline);
        session.apply(new ReviewEdit.MoveManualWarpControlAndInstall(
                "left-1", movedTarget, prior, second));

        assertEquals(beforeMoveRevision + 1,
                session.state().contentRevision());
        assertEquals(ReviewOperation.MOVE_MANUAL_WARP_CONTROL_AND_INSTALL,
                lastOperation(session));
        assertNotEquals(firstHash,
                second.diagnostics().contentSha256());
        assertEquals(movedTarget, session.state().content().hemisphereWarp()
                .orElseThrow().controls().stream()
                .filter(control -> control.id().equals("left-1"))
                .findFirst().orElseThrow().targetPoint());

        session.undo();
        assertEquals(firstHash, session.state().content().hemisphereWarp()
                .orElseThrow().diagnostics().contentSha256());
        session.redo();
        assertEquals(second.diagnostics().contentSha256(),
                session.state().content().hemisphereWarp().orElseThrow()
                        .diagnostics().contentSha256());

        final ReviewConfidenceReport confidence = session.confidence();
        assertEquals(ConfidenceEvidenceCategory.MANUAL_ONLY,
                confidence.category());
        final ConfidenceEvidence warpEvidence = confidence.evidence().stream()
                .filter(item -> item.metric()
                        == ConfidenceMetric.LOCAL_WARP_PLAUSIBILITY)
                .findFirst().orElseThrow();
        assertEquals(ConfidenceEvidenceStatus.INFORMATIONAL,
                warpEvidence.status());
        assertTrue(warpEvidence.explanation()
                .contains("REVIEWER_CONTROLLED_MANUAL_WARP"));
        assertTrue(warpEvidence.explanation()
                .contains("never increase automatic confidence"));

        final AcceptedAlignmentSnapshot accepted = session.accept(
                ReviewTestFixtures.verifier(basis), true);
        assertEquals(second, accepted.hemisphereWarp().orElseThrow());
        assertEquals("REVIEWER_CONTROLLED_MANUAL_WARP",
                accepted.outputMethodLabel());
        assertTrue(accepted.activeLandmarks().isEmpty());
    }

    @Test
    void staleAsyncRevisionAndHashesInstallNothing() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = outlinedSession(basis);
        final List<ManualWarpControl> controls = controls(
                ManualHemisphereWarp2D.AtlasSide.LEFT, "structure:DG");
        final ManualWarpPrecondition captured = ManualWarpPrecondition.capture(
                session.state());
        final long revision = session.state().contentRevision();
        final ManualHemisphereWarp2D solved = ManualHemisphereWarp2D.fit(
                controls, session.state().content().orientation(),
                exactOutline());
        final int auditBefore = session.auditTrail().size();

        session.apply(new ReviewEdit.SetAtlasPlaneTilt(
                new AtlasPlaneTilt(1, 0)));
        final boolean installed = session.applyIfCurrentRevision(revision,
                new ReviewEdit.ReplaceManualWarpControlGroup(
                        ManualHemisphereWarp2D.AtlasSide.LEFT,
                        "structure:DG", controls, captured, solved));

        assertFalse(installed);
        assertTrue(session.state().content().hemisphereWarp().isEmpty());
        assertEquals(auditBefore + 1, session.auditTrail().size(),
                "only the upstream tilt edit may enter the audit trail");
        final ManualWarpException stale = assertThrows(
                ManualWarpException.class,
                () -> captured.requireMatches(
                        session.state().content(), basis));
        assertEquals(ManualWarpFailureKind.STALE_RESULT, stale.kind());
    }

    @Test
    void directInstallRejectsStaleSupportClippingAndBindsLegacyOutline() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = outlinedSession(basis);
        final List<ManualWarpControl> controls = controls(
                ManualHemisphereWarp2D.AtlasSide.LEFT, "structure:DG");
        final ManualWarpPrecondition beforeSupport =
                ManualWarpPrecondition.capture(session.state());
        final ManualHemisphereWarp2D solved = ManualHemisphereWarp2D.fit(
                controls, session.state().content().orientation(),
                exactOutline());
        assertEquals(Optional.of(exactOutline().contentSha256()),
                beforeSupport.outlineWarpSha256());

        final boolean[] pixels = new boolean[100 * 80];
        for (int y = 10; y <= 70; y++) {
            for (int x = 10; x <= 90; x++) {
                pixels[y * 100 + x] = true;
            }
        }
        final ReviewedTissueSupport support = ReviewedTissueSupport.fromMask(
                BinaryMask.fromBooleans(100, 80, pixels));
        session.apply(new ReviewEdit.ReplaceReviewedTissueSupport(
                support, false));

        final ManualWarpException staleSupport = assertThrows(
                ManualWarpException.class,
                () -> session.apply(
                        new ReviewEdit.ReplaceManualWarpControlGroup(
                                ManualHemisphereWarp2D.AtlasSide.LEFT,
                                "structure:DG", controls, beforeSupport,
                                solved)));
        assertEquals(ManualWarpFailureKind.STALE_RESULT,
                staleSupport.kind());
        assertTrue(session.state().content().hemisphereWarp().isEmpty());

        final ManualWarpPrecondition beforeClipping =
                ManualWarpPrecondition.capture(session.state());
        session.apply(new ReviewEdit.SetTissueClipping(true));
        final ManualWarpException staleClipping = assertThrows(
                ManualWarpException.class,
                () -> session.apply(
                        new ReviewEdit.ReplaceManualWarpControlGroup(
                                ManualHemisphereWarp2D.AtlasSide.LEFT,
                                "structure:DG", controls, beforeClipping,
                                solved)));
        assertEquals(ManualWarpFailureKind.STALE_RESULT,
                staleClipping.kind());
        assertTrue(session.state().content().hemisphereWarp().isEmpty());
    }

    @Test
    void exactOutlineRejectsAllLaterGlobalAndGenericLocalFits() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = outlinedSession(basis);
        final AlignmentReviewContent before = session.state().content();
        final long revision = session.state().contentRevision();
        final int auditSize = session.auditTrail().size();

        assertThrows(IllegalArgumentException.class,
                () -> session.apply(new ReviewEdit.Translate(1, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> session.apply(new ReviewEdit.Rotate(
                        0.01, new Point2D(50, 40))));
        assertThrows(IllegalArgumentException.class,
                () -> session.apply(new ReviewEdit.Scale(
                        1.01, new Point2D(50, 40))));
        assertThrows(IllegalArgumentException.class,
                () -> session.apply(new ReviewEdit.FitActiveLandmarks()));
        assertThrows(IllegalArgumentException.class,
                () -> session.apply(
                        new ReviewEdit.FitActiveLandmarksAffine()));
        assertThrows(IllegalArgumentException.class,
                () -> session.apply(
                        new ReviewEdit.FitActiveLandmarksLocalWarp()));

        assertEquals(before, session.state().content());
        assertEquals(revision, session.state().contentRevision());
        assertEquals(auditSize, session.auditTrail().size());
    }

    @Test
    void resetOneSideRetainsOppositeControlsAndClearRemovesEverything() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = outlinedSession(basis);
        final BoundaryAuthoritativeTransform2D outline = exactOutline();
        final List<ManualWarpControl> left = controls(
                ManualHemisphereWarp2D.AtlasSide.LEFT, "structure:DG");
        ManualWarpPrecondition precondition = ManualWarpPrecondition.capture(
                session.state());
        session.apply(new ReviewEdit.ReplaceManualWarpControlGroup(
                ManualHemisphereWarp2D.AtlasSide.LEFT, "structure:DG", left,
                precondition, ManualHemisphereWarp2D.fit(left,
                        session.state().content().orientation(), outline)));
        final List<ManualWarpControl> right = controls(
                ManualHemisphereWarp2D.AtlasSide.RIGHT, "regular-interior-grid");
        final List<ManualWarpControl> both = new ArrayList<>(left);
        both.addAll(right);
        precondition = ManualWarpPrecondition.capture(session.state());
        session.apply(new ReviewEdit.ReplaceManualWarpControlGroup(
                ManualHemisphereWarp2D.AtlasSide.RIGHT,
                "regular-interior-grid", right, precondition,
                ManualHemisphereWarp2D.fit(both,
                        session.state().content().orientation(), outline)));

        precondition = ManualWarpPrecondition.capture(session.state());
        final ManualHemisphereWarp2D rightOnly = ManualHemisphereWarp2D.fit(
                right, session.state().content().orientation(), outline);
        session.apply(new ReviewEdit.ResetManualWarpSide(
                ManualHemisphereWarp2D.AtlasSide.LEFT, precondition,
                Optional.of(rightOnly)));

        assertFalse(session.state().content().hemisphereWarp().orElseThrow()
                .hasControls(ManualHemisphereWarp2D.AtlasSide.LEFT));
        assertTrue(session.state().content().hemisphereWarp().orElseThrow()
                .hasControls(ManualHemisphereWarp2D.AtlasSide.RIGHT));
        session.apply(new ReviewEdit.ClearHemisphereWarp());
        assertTrue(session.state().content().hemisphereWarp().isEmpty());
    }

    @Test
    void planeAndTiltChangesPreservePreviewControlsAndManualField() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final List<ManualWarpControl> controls = controls(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                "structure-guide");
        final ManualWarpPrecondition empty = ManualWarpPrecondition.capture(
                session.state());
        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                controls, session.state().content().orientation(),
                basis.previewDimensions().width(),
                basis.previewDimensions().height());
        session.apply(new ReviewEdit.ReplaceManualWarpControlGroup(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                "structure-guide", controls, empty, warp));
        final String fieldHash = warp.diagnostics().contentSha256();
        final ManualWarpPrecondition beforePlane =
                ManualWarpPrecondition.capture(session.state());

        session.apply(new ReviewEdit.SetCoronalLevel(
                new AllenCoronalLevel(241)));
        session.apply(new ReviewEdit.SetAtlasPlaneTilt(
                new AtlasPlaneTilt(3, -2)));

        final ManualHemisphereWarp2D after = session.state().content()
                .hemisphereWarp().orElseThrow();
        assertEquals(fieldHash, after.diagnostics().contentSha256());
        assertEquals(controls, after.controls());
        assertEquals(new AllenCoronalLevel(241),
                session.state().content().coronalLevel());
        assertEquals(new AtlasPlaneTilt(3, -2),
                session.state().content().atlasPlaneTilt());
        assertNotEquals(beforePlane.planeSha256(),
                ManualWarpPrecondition.capture(session.state())
                        .planeSha256());
        assertTrue(session.state().content().landmarks().isEmpty());
        assertEquals(ConfidenceEvidenceCategory.MANUAL_ONLY,
                session.confidence().category());

        assertTrue(session.undo());
        assertEquals(AtlasPlaneTilt.CORONAL,
                session.state().content().atlasPlaneTilt());
        assertEquals(fieldHash, session.state().content().hemisphereWarp()
                .orElseThrow().diagnostics().contentSha256());
        assertTrue(session.redo());
        assertEquals(fieldHash, session.state().content().hemisphereWarp()
                .orElseThrow().diagnostics().contentSha256());
    }

    @Test
    void acronymSpecificReplacementAdoptsLegacyStructureGuideOnly() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final List<ManualWarpControl> legacyDg = structureControls(
                "legacy-dg", "structure-guide", "DG",
                List.of(new Point2D(20, 22), new Point2D(34, 22),
                        new Point2D(20, 38), new Point2D(34, 38)));
        final List<ManualWarpControl> dgSg = structureControls(
                "dgsg", "structure-guide:DG-sg", "DG-sg",
                List.of(new Point2D(38, 24), new Point2D(46, 24),
                        new Point2D(38, 36), new Point2D(46, 36)));
        final List<ManualWarpControl> initial = new ArrayList<>(legacyDg);
        initial.addAll(dgSg);
        ManualWarpPrecondition precondition = ManualWarpPrecondition.capture(
                session.state());
        session.apply(new ReviewEdit.ReplaceAllManualWarpControlsAndInstall(
                initial, precondition, ManualHemisphereWarp2D.fit(initial,
                        session.state().content().orientation(), 100, 80)));

        final List<ManualWarpControl> replacement = structureControls(
                "new-dg", "structure-guide:DG", "DG",
                List.of(new Point2D(21, 22), new Point2D(35, 22),
                        new Point2D(21, 38), new Point2D(35, 38)));
        final List<ManualWarpControl> expected = new ArrayList<>(dgSg);
        expected.addAll(replacement);
        precondition = ManualWarpPrecondition.capture(session.state());
        session.apply(new ReviewEdit.ReplaceStructureWarpControlGroup(
                ManualHemisphereWarp2D.AtlasSide.LEFT, "DG", replacement,
                precondition, ManualHemisphereWarp2D.fit(expected,
                        session.state().content().orientation(), 100, 80)));

        final List<ManualWarpControl> installed = session.state().content()
                .hemisphereWarp().orElseThrow().controls();
        assertEquals(expected, installed);
        assertTrue(installed.stream().noneMatch(control ->
                control.groupId().equals("structure-guide")));
        assertEquals(dgSg, installed.stream().filter(control ->
                control.structureAcronym().equals("DG-sg")).toList());
        assertEquals(ReviewOperation.REPLACE_STRUCTURE_WARP_CONTROL_GROUP,
                lastOperation(session));
    }

    @Test
    void structureTransformReducerAllowsOnlyTargetsAndLegacyGroupAdoption() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final List<Point2D> sources = List.of(
                new Point2D(20, 22), new Point2D(34, 22),
                new Point2D(20, 38), new Point2D(34, 38));
        final List<ManualWarpControl> legacy = structureControls(
                "dg", "structure-guide", "DG", sources);
        ManualWarpPrecondition precondition = ManualWarpPrecondition.capture(
                session.state());
        session.apply(new ReviewEdit.ReplaceAllManualWarpControlsAndInstall(
                legacy, precondition, ManualHemisphereWarp2D.fit(legacy,
                        session.state().content().orientation(), 100, 80)));
        final List<ManualWarpControl> targetOnly = legacy.stream()
                .map(control -> new ManualWarpControl(
                        control.id(), control.atlasSide(), control.origin(),
                        "structure-guide:DG", control.structureAcronym(),
                        control.sourcePoint(), new Point2D(
                                control.targetPoint().x() + 1,
                                control.targetPoint().y())))
                .toList();
        precondition = ManualWarpPrecondition.capture(session.state());
        session.apply(new ReviewEdit.TransformStructureWarpControlGroup(
                ManualHemisphereWarp2D.AtlasSide.LEFT, "DG", targetOnly,
                precondition, ManualHemisphereWarp2D.fit(targetOnly,
                        session.state().content().orientation(), 100, 80)));

        assertEquals(targetOnly, session.state().content().hemisphereWarp()
                .orElseThrow().controls());
        assertTrue(targetOnly.stream().allMatch(control ->
                control.groupId().equals("structure-guide:DG")));

        assertStructureTransformMetadataRejected(MetadataDrift.ID);
        assertStructureTransformMetadataRejected(MetadataDrift.SOURCE);
        assertStructureTransformMetadataRejected(MetadataDrift.GROUP);
    }

    @Test
    void structureTransformAdoptsVerifiedBoundaryControlsByAcronymAndSide() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final List<ManualWarpControl> verified = controls(
                ManualHemisphereWarp2D.AtlasSide.LEFT, "structure:DG");
        ManualWarpPrecondition precondition = ManualWarpPrecondition.capture(
                session.state());
        session.apply(new ReviewEdit.ReplaceAllManualWarpControlsAndInstall(
                verified, precondition, ManualHemisphereWarp2D.fit(verified,
                        session.state().content().orientation(), 100, 80)));
        final List<ManualWarpControl> adopted = verified.stream().map(
                control -> new ManualWarpControl(control.id(),
                        control.atlasSide(),
                        ManualWarpControlOrigin.STRUCTURE_GUIDE,
                        "structure-guide:DG", control.structureAcronym(),
                        control.sourcePoint(), new Point2D(
                                control.targetPoint().x() + 0.5,
                                control.targetPoint().y()))).toList();
        precondition = ManualWarpPrecondition.capture(session.state());

        session.apply(new ReviewEdit.TransformStructureWarpControlGroup(
                ManualHemisphereWarp2D.AtlasSide.LEFT, "DG", adopted,
                precondition, ManualHemisphereWarp2D.fit(adopted,
                        session.state().content().orientation(), 100, 80)));

        assertEquals(adopted, session.state().content().hemisphereWarp()
                .orElseThrow().controls());
        assertTrue(session.state().content().hemisphereWarp().orElseThrow()
                .controls().stream().allMatch(control -> control.origin()
                        == ManualWarpControlOrigin.STRUCTURE_GUIDE
                        && control.groupId().equals("structure-guide:DG")));
        assertEquals(ReviewOperation.TRANSFORM_STRUCTURE_WARP_CONTROL_GROUP,
                lastOperation(session));
    }

    private static void assertStructureTransformMetadataRejected(
            final MetadataDrift drift) {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final List<Point2D> points = List.of(
                new Point2D(20, 22), new Point2D(34, 22),
                new Point2D(20, 38), new Point2D(34, 38));
        final List<ManualWarpControl> current = structureControls(
                "dg", "structure-guide:DG", "DG", points);
        ManualWarpPrecondition precondition = ManualWarpPrecondition.capture(
                session.state());
        session.apply(new ReviewEdit.ReplaceAllManualWarpControlsAndInstall(
                current, precondition, ManualHemisphereWarp2D.fit(current,
                        session.state().content().orientation(), 100, 80)));
        final List<ManualWarpControl> malformed = new ArrayList<>();
        for (int index = 0; index < current.size(); index++) {
            final ManualWarpControl control = current.get(index);
            malformed.add(new ManualWarpControl(
                    index == 0 && drift == MetadataDrift.ID
                            ? control.id() + "-changed" : control.id(),
                    control.atlasSide(), control.origin(),
                    index == 0 && drift == MetadataDrift.GROUP
                            ? "structure-guide:OTHER" : control.groupId(),
                    control.structureAcronym(),
                    index == 0 && drift == MetadataDrift.SOURCE
                            ? new Point2D(control.sourcePoint().x() + 0.25,
                                    control.sourcePoint().y())
                            : control.sourcePoint(),
                    new Point2D(control.targetPoint().x() + 1,
                            control.targetPoint().y())));
        }
        precondition = ManualWarpPrecondition.capture(session.state());
        final ManualWarpPrecondition captured = precondition;
        final AlignmentReviewContent before = session.state().content();
        assertThrows(IllegalArgumentException.class, () -> session.apply(
                new ReviewEdit.TransformStructureWarpControlGroup(
                        ManualHemisphereWarp2D.AtlasSide.LEFT, "DG",
                        malformed, captured,
                        ManualHemisphereWarp2D.fit(malformed,
                                session.state().content().orientation(),
                                100, 80))));
        assertEquals(before, session.state().content(),
                "rejected structure metadata drift must not alter content");
    }

    private enum MetadataDrift {
        ID,
        SOURCE,
        GROUP
    }

    private static ReviewOperation lastOperation(
            final AlignmentReviewSession session) {
        return session.auditTrail().get(session.auditTrail().size() - 1)
                .operation();
    }

    private static List<ManualWarpControl> controls(
            final ManualHemisphereWarp2D.AtlasSide side,
            final String group) {
        final boolean left = side == ManualHemisphereWarp2D.AtlasSide.LEFT;
        final List<Point2D> points = left
                ? List.of(new Point2D(25, 30), new Point2D(36, 30),
                        new Point2D(25, 50), new Point2D(38, 50))
                : List.of(new Point2D(62, 30), new Point2D(72, 30),
                        new Point2D(65, 50), new Point2D(75, 50));
        final ManualWarpControlOrigin origin = group.startsWith("structure:")
                ? ManualWarpControlOrigin.VERIFIED_STRUCTURE_BOUNDARY
                : ManualWarpControlOrigin.REGULAR_INTERIOR_GRID;
        final String structure = origin
                == ManualWarpControlOrigin.VERIFIED_STRUCTURE_BOUNDARY
                ? "DG" : "";
        final List<ManualWarpControl> controls = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            final Point2D point = points.get(index);
            controls.add(new ManualWarpControl(
                    (left ? "left-" : "right-") + (index + 1), side,
                    origin, group, structure, point, point));
        }
        return List.copyOf(controls);
    }

    private static List<ManualWarpControl> replaceTarget(
            final List<ManualWarpControl> controls,
            final String id,
            final Point2D target) {
        return controls.stream().map(control -> control.id().equals(id)
                ? new ManualWarpControl(control.id(), control.atlasSide(),
                        control.origin(), control.groupId(),
                        control.structureAcronym(), control.sourcePoint(),
                        target)
                : control).toList();
    }

    private static List<ManualWarpControl> structureControls(
            final String idPrefix,
            final String groupId,
            final String acronym,
            final List<Point2D> points) {
        final List<ManualWarpControl> result = new ArrayList<>();
        for (int index = 0; index < points.size(); index++) {
            result.add(new ManualWarpControl(idPrefix + "-" + index,
                    ManualHemisphereWarp2D.AtlasSide.LEFT,
                    ManualWarpControlOrigin.STRUCTURE_GUIDE,
                    groupId, acronym, points.get(index), points.get(index)));
        }
        return List.copyOf(result);
    }

    private static AlignmentReviewSession outlinedSession(
            final AlignmentReviewBasis basis) {
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        session.apply(new ReviewEdit.ApplyGuidedManualStartingPlane(
                basis.proposal().coronalLevel(), AtlasPlaneTilt.CORONAL,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.BOTH,
                candidateTransform(), Optional.of(exactOutline()), true,
                "test-exact-outline", List.of("outline"),
                basis.sourceSnapshot().pixelSha256(),
                basis.atlas().identitySha256()));
        return session;
    }

    private static BoundaryAuthoritativeTransform2D exactOutline() {
        final List<Point2D> atlas = List.of(
                new Point2D(45, 10), new Point2D(90, 40),
                new Point2D(55, 70), new Point2D(10, 40));
        final List<Point2D> tissue = List.of(
                new Point2D(45, 8), new Point2D(92, 40),
                new Point2D(55, 72), new Point2D(8, 40));
        return BoundaryAuthoritativeTransform2D.fitFull(
                MonotoneBoundary2D.arcLengthIndexed("atlas-", atlas),
                MonotoneBoundary2D.arcLengthIndexed("tissue-", tissue),
                100, 80);
    }

    private static AffineTransform2D candidateTransform() {
        return new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                0.2, 0, 4.5,
                0, 0.2, 8);
    }
}
