package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.ItemEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import org.atlasalign.application.AlignmentReviewBasis;
import org.atlasalign.application.AlignmentReviewContent;
import org.atlasalign.application.AlignmentReviewSession;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasAnatomicalSide;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.HalfAtlasCoverage;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewOperation;
import org.atlasalign.application.ReviewedTissueSupport;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.application.ReviewEdit;
import org.atlasalign.application.ReviewAcceptanceVerification;
import org.atlasalign.application.ReviewAcceptanceBlockReason;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.roi.ReviewerRoiSide;
import org.atlasalign.application.roi.RoiPartOperation;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualWarpControl;
import org.atlasalign.application.manual.ManualWarpControlOrigin;
import org.atlasalign.application.manual.ManualWarpException;
import org.atlasalign.application.manual.ManualWarpFailureKind;
import org.atlasalign.application.manual.ManualWarpSafetyGate;
import org.atlasalign.application.manual.ManualWarpSafetyReport;
import org.atlasalign.application.manual.BoundaryFitDraft;
import org.atlasalign.application.manual.BoundaryFitModel;
import org.atlasalign.application.manual.BoundaryFitPreviewKind;
import org.atlasalign.application.manual.BoundaryWarpSolver;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D;
import org.atlasalign.application.manual.MonotoneBoundary2D;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.atlas.AtlasPlaneGeometry;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.junit.jupiter.api.Test;

class ReviewControllerTest {

    @Test
    void boundaryWarpSafetyFailureNamesGateThresholdSideAndLocation() {
        final ManualWarpSafetyReport report = ManualWarpSafetyReport.measured(
                ManualWarpSafetyGate.MAXIMUM_SINGULAR_VALUE,
                3.125, 3.0, ManualHemisphereWarp2D.AtlasSide.LEFT,
                new Point2D(120.25, 44.75), "test gate");

        final String message = ReviewController
                .boundaryWarpSafetyFailureMessage(report);

        assertTrue(message.contains("maximum singular value"));
        assertTrue(message.contains("value 3.125"));
        assertTrue(message.contains("threshold 3.000"));
        assertTrue(message.contains("atlas-left"));
        assertTrue(message.contains("preview (120.3, 44.8)"));
        assertTrue(message.contains("Requested dots kept"));
    }

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void staleAtlasLoadCompletionIsDiscarded() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.basis();
        final QueuedExecutor loader = new QueuedExecutor();
        final CapturingView view = new CapturingView();
        final ReviewController controller = controller(
                basis, loader, view);

        controller.setCoronalLevel(241);
        assertEquals(2, loader.tasks.size());

        loader.run(1);
        assertEquals(241, view.last().atlasPlane()
                .orElseThrow()
                .zeroBasedAnteriorPosteriorIndex());
        loader.run(0);

        assertEquals(241, view.last().atlasPlane()
                .orElseThrow()
                .zeroBasedAnteriorPosteriorIndex());
        assertFalse(view.last().atlasPlaneLoading());
    }

    @Test
    void neutralAtlasReferenceLoadsWithoutEditingReviewState() {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        final CapturingView view = new CapturingView();
        controller.attach(view);
        final int levelBefore = session.state().content().coronalLevel()
                .zeroBasedAnteriorPosteriorIndex();
        final long revisionBefore = session.state().contentRevision();
        final AtomicReference<AtlasCoronalPlane> reference =
                new AtomicReference<>();

        controller.loadNeutralAtlasReference(
                300, reference::set,
                message -> { throw new AssertionError(message); });

        assertEquals(300, reference.get()
                .zeroBasedAnteriorPosteriorIndex());
        assertEquals(levelBefore, session.state().content().coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
        assertEquals(revisionBefore, session.state().contentRevision());
        assertTrue(session.auditTrail().isEmpty());
    }

    @Test
    void samePlaneGuidedCandidateStillAppliesItsOutlineTransform() {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        final AffineTransform2D adjustment = new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1.2, 0, 4,
                0, 0.8, 3);
        final AffineTransform2D target = basis.proposal().affine()
                .andThen(adjustment);

        controller.applyGuidedManualCandidate(
                new ReviewEdit.ApplyGuidedManualCandidate(
                        basis.proposal().coronalLevel(),
                        session.state().content().atlasPlaneTilt(),
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.BOTH,
                        target, "outline-bounds-affine-v1",
                        "same-plane-outline-fit",
                        new AllenCoronalLevel(basis.proposal()
                                .coronalLevel()
                                .zeroBasedAnteriorPosteriorIndex()),
                        AtlasPlaneTilt.CORONAL,
                        0.02, 0.03, 0.01,
                        List.of("outline", "dg-left"),
                        basis.sourceSnapshot().pixelSha256(),
                        basis.atlas().identitySha256()));

        assertEquals(1, session.state().contentRevision());
        assertEquals(adjustment,
                session.state().content().manualPreviewAdjustment());
        assertEquals(ReviewOperation.APPLY_GUIDED_MANUAL_CANDIDATE,
                session.auditTrail().get(0).operation());
        controller.undo();
        assertEquals(basis.initialContent().manualPreviewAdjustment(),
                session.state().content().manualPreviewAdjustment());
    }

    @Test
    void exactCatalogTargetConstrainsAddedAndMovedAtlasPoints() {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final CatalogPlaneSource source = new CatalogPlaneSource();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(), source,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        final CapturingView view = new CapturingView();
        controller.attach(view);

        assertTrue(controller.resolveExactAtlasRegion("DG").isPresent());
        assertTrue(controller.resolveExactAtlasRegion("dg").isEmpty(),
                "catalog resolution is exact, not a fuzzy acronym match");
        controller.selectAtlasRegionExactAcronym("DG");
        assertEquals("DG", view.last().selectedAtlasRegion()
                .orElseThrow().acronym());
        assertTrue(view.last().selectedAtlasContour()
                .orElseThrow().isPresent());

        controller.addLandmark("targeted", new Point2D(2.4, 4.1),
                new Point2D(20, 20));
        assertEquals(new Point2D(2, 4), session.state().content()
                .activeLandmarks().get(0).atlasPoint());
        controller.moveLandmarkAtlasPoint(
                "targeted", new Point2D(7.7, 5.1));
        assertEquals(new Point2D(8, 5), session.state().content()
                .activeLandmarks().get(0).atlasPoint());

        assertThrows(IllegalArgumentException.class,
                () -> controller.addLandmark("interior",
                        new Point2D(5, 5), new Point2D(30, 30)));
        assertEquals(1, session.state().content().activeLandmarks().size());
        assertThrows(IllegalArgumentException.class,
                () -> controller.selectAtlasRegionExactAcronym("dg"));
    }

    @Test
    void targetSelectionAllowsAnyExactVerifiedOntologyAcronym() {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.preview(), new CatalogPlaneSource(),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        final CapturingView view = new CapturingView();
        controller.attach(view);

        assertTrue(controller.resolveExactAtlasRegion("CA1").isPresent());
        controller.selectAtlasRegionExactAcronym("CA1");
        assertEquals("CA1", view.last().selectedAtlasRegion()
                .orElseThrow().acronym());

        controller.selectAtlasRegionExactAcronym("DG");
        assertEquals("DG", view.last().selectedAtlasRegion()
                .orElseThrow().acronym());
        controller.selectAtlasRegionExactAcronym("DG-sg");
        assertEquals("DG-sg", view.last().selectedAtlasRegion()
                .orElseThrow().acronym());
        assertTrue(controller.resolveExactAtlasRegion("HPF").isPresent());
        assertTrue(controller.resolveExactAtlasRegion("cc").isPresent());
        assertTrue(controller.resolveExactAtlasRegion("VS").isPresent());
        assertTrue(controller.resolveExactAtlasRegion("root").isPresent());
    }

    @Test
    void selectedTargetAbsentOnCurrentPlaneFailsClosedForAtlasPoints() {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final CatalogPlaneSource source = new CatalogPlaneSource(true);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(), source,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.selectAtlasRegionExactAcronym("DG");

        assertThrows(IllegalArgumentException.class,
                () -> controller.addLandmark("absent",
                        new Point2D(2, 4), new Point2D(20, 20)));
        assertTrue(session.state().content().activeLandmarks().isEmpty());
    }

    @Test
    void targetHandleBatchDoesNotRequireAnExactOutlineMap() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.selectAtlasRegionExactAcronym("DG-sg");

        final List<String> identifiers = controller.addSelectedTargetHandles(
                4, AtlasAnatomicalSide.ATLAS_LEFT);

        assertEquals(4, identifiers.size());
        assertTrue(controller.structureAdjustmentState().candidate()
                .isEmpty(), "Set N must not calculate implicitly");
        controller.calculateStructureAdjustmentPreview();
        assertTrue(controller.structureAdjustmentState().candidate()
                .isPresent());
        assertTrue(session.state().content().hemisphereWarp().isEmpty(),
                "Set N remains a transient audited preview until Apply");
        controller.applyStructureChanges();
        assertTrue(session.state().content().activeLandmarks().isEmpty());
        assertTrue(session.state().content().outlineWarp().isEmpty());
        assertEquals(4, session.state().content().hemisphereWarp()
                .orElseThrow().manualControls(
                        ManualHemisphereWarp2D.AtlasSide.LEFT).size());
    }

    @Test
    void rapidStructureEditsRemainSolverFreeUntilExplicitCalculate() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final QueuedExecutor warpWorker = new QueuedExecutor();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, warpWorker, Runnable::run, () -> { });
        controller.attach(new CapturingView());
        controller.selectAtlasRegionExactAcronym("DG-sg");
        final long revision = session.state().contentRevision();

        controller.replaceSelectedStructureControls(
                8, ManualHemisphereWarp2D.AtlasSide.LEFT);
        controller.setStructureAdjustmentSliders(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 101, 0);
        controller.setStructureAdjustmentSliders(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 102, 0);
        controller.setStructureAdjustmentSliders(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 103, 0);

        assertEquals(0, warpWorker.tasks.size(),
                "setting points and moving either slider must perform no solve");
        final StructureAdjustmentDraft latest = controller
                .structureAdjustmentState().draft().orElseThrow();
        assertEquals(103, latest.thicknessPercent());
        assertFalse(controller.structureAdjustmentState().loading());
        assertTrue(controller.structureAdjustmentState().candidate()
                .isEmpty());

        controller.calculateStructureAdjustmentPreview();
        assertEquals(1, warpWorker.tasks.size(),
                "one explicit Calculate must queue exactly one solve");
        assertTrue(controller.structureAdjustmentState().loading());

        warpWorker.run(0);

        final StructureAdjustmentViewState audited =
                controller.structureAdjustmentState();
        assertFalse(audited.loading());
        assertEquals(latest.inputHash(), audited.candidate().orElseThrow()
                .draftHash(), "the only solve must audit the latest edit");
        assertEquals(1, warpWorker.tasks.size());
        assertEquals(revision, session.state().contentRevision(),
                "coalesced previews remain transient");

        controller.applyStructureChanges();

        assertEquals(revision + 1, session.state().contentRevision());
        assertEquals(8, session.state().content().hemisphereWarp()
                .orElseThrow().manualControls(
                        ManualHemisphereWarp2D.AtlasSide.LEFT).size());
    }

    @Test
    void connectedDgSgContourGetsTwoDeterministicBladeGroups() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.preview(),
                new ConnectedDgSgPlaneSource(),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        final CapturingView view = new CapturingView();
        controller.attach(view);
        controller.selectAtlasRegionExactAcronym("DG-sg");
        assertEquals(1, view.last().selectedAtlasContour().orElseThrow()
                .principalExteriorComponents(
                        AtlasAnatomicalSide.ATLAS_LEFT).size(),
                "the fixture must remain one connected horseshoe contour");

        controller.replaceSelectedStructureControls(
                8, ManualHemisphereWarp2D.AtlasSide.LEFT);
        final StructureAdjustmentDraft before = controller
                .structureAdjustmentState().draft().orElseThrow();
        assertTrue(before.hasTwoPrincipalComponents());
        assertEquals(4, before.componentByControlId().values().stream()
                .filter(component -> component == 0).count());
        assertEquals(4, before.componentByControlId().values().stream()
                .filter(component -> component == 1).count());
        final java.util.Map<String, Point2D> beforeById = before
                .requestedControls().stream().collect(
                        java.util.stream.Collectors.toMap(
                                ManualWarpControl::id,
                                ManualWarpControl::targetPoint));
        final java.util.Set<String> pairedIds = before.units().stream()
                .filter(StructureAdjustmentUnit::paired)
                .flatMap(unit -> unit.controlIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(before.units().stream().filter(
                StructureAdjustmentUnit::paired).map(
                        StructureAdjustmentUnit::componentIndex).distinct()
                .count() == 2,
                "both connected DG-sg blades must receive a stable pair");

        controller.setStructureAdjustmentSliders(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 100, 25);

        final StructureAdjustmentDraft after = controller
                .structureAdjustmentState().draft().orElseThrow();
        for (int component = 0; component < 2; component++) {
            final int selected = component;
            final java.util.List<Point2D> deltas = after.requestedControls()
                    .stream().filter(control -> after
                            .componentByControlId().get(control.id())
                            == selected && pairedIds.contains(control.id()))
                    .map(control -> new Point2D(
                            control.targetPoint().x()
                                    - beforeById.get(control.id()).x(),
                            control.targetPoint().y()
                                    - beforeById.get(control.id()).y()))
                    .toList();
            assertFalse(deltas.isEmpty());
            deltas.forEach(delta -> {
                assertEquals(deltas.get(0).x(), delta.x(), 1e-9);
                assertEquals(deltas.get(0).y(), delta.y(), 1e-9);
            });
        }
        final Point2D firstDelta = componentRequestedDelta(
                before, after, pairedIds, 0);
        final Point2D secondDelta = componentRequestedDelta(
                before, after, pairedIds, 1);
        assertEquals(0, firstDelta.x() + secondDelta.x(), 1e-9);
        assertEquals(0, firstDelta.y() + secondDelta.y(), 1e-9);
        assertEquals(before.initialBladeSeparation() * 0.25,
                Math.hypot(secondDelta.x() - firstDelta.x(),
                        secondDelta.y() - firstDelta.y()), 1e-9,
                "paired controls must separate by the requested gap fraction");
        after.requestedControls().stream()
                .filter(control -> !pairedIds.contains(control.id()))
                .forEach(control -> {
                    final Point2D expectedDelta = after
                            .componentByControlId().get(control.id()) == 0
                                    ? firstDelta : secondDelta;
                    assertPoint(expectedDelta, new Point2D(
                            control.targetPoint().x()
                                    - beforeById.get(control.id()).x(),
                            control.targetPoint().y()
                                    - beforeById.get(control.id()).y()));
                });
    }

    @Test
    void denseStructureDraftKeepsSixtyFourVerticesInVerifiedOutlineOrder() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final QueuedExecutor warpWorker = new QueuedExecutor();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new ConnectedDgSgPlaneSource(),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, warpWorker, Runnable::run, () -> { });
        controller.attach(new CapturingView());
        controller.selectAtlasRegionExactAcronym("DG-sg");

        controller.replaceSelectedStructureControls(
                64, ManualHemisphereWarp2D.AtlasSide.LEFT);

        final StructureAdjustmentDraft seeded = controller
                .structureAdjustmentState().draft().orElseThrow();
        assertEquals(64, seeded.requestedControls().size());
        assertEquals(1, seeded.outlinePaths().size(),
                "one connected verified exterior must remain one closed ROI path");
        final StructureOutlinePath outline = seeded.outlinePaths().get(0);
        assertTrue(outline.closed());
        assertEquals(64, outline.controlIds().size());
        assertEquals(seeded.requestedControls().stream()
                        .map(ManualWarpControl::id)
                        .collect(java.util.stream.Collectors.toSet()),
                new java.util.HashSet<>(outline.controlIds()));
        assertTrue(seeded.hasTwoPrincipalComponents(),
                "display-loop order must not remove connected DG-sg blade identities");
        assertEquals(0, warpWorker.tasks.size(),
                "creating a dense editable outline must remain solver-free");
        assertEquals(0, session.state().contentRevision());

        final ManualWarpControl first = seeded.requestedControls().get(0);
        controller.transformSelectedStructureControls(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                List.of(first.id()), List.of(new Point2D(
                        first.targetPoint().x() + 2,
                        first.targetPoint().y() + 1)));
        final StructureAdjustmentDraft edited = controller
                .structureAdjustmentState().draft().orElseThrow();
        assertEquals(seeded.outlinePaths(), edited.outlinePaths(),
                "dragging a vertex must not rebuild or reorder the ROI path");
        assertEquals(0, warpWorker.tasks.size());
        assertEquals(0, session.state().contentRevision());
    }

    @Test
    void denseConnectedDgSgPairsLocalOpposingBoundariesWithoutTipOutliers() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final QueuedExecutor warpWorker = new QueuedExecutor();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new ConnectedDgSgPlaneSource(),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, warpWorker, Runnable::run, () -> { });
        controller.attach(new CapturingView());
        controller.selectAtlasRegionExactAcronym("DG-sg");

        controller.replaceSelectedStructureControls(
                48, ManualHemisphereWarp2D.AtlasSide.LEFT);

        final StructureAdjustmentDraft seeded = controller
                .structureAdjustmentState().draft().orElseThrow();
        final java.util.List<StructureAdjustmentUnit> pairs = seeded.units()
                .stream().filter(StructureAdjustmentUnit::paired).toList();
        assertTrue(pairs.size() * 2 >= seeded.requestedControls().size() / 2,
                "dense blades should pair most non-terminal outline dots");
        assertTrue(seeded.units().stream().filter(unit -> !unit.paired())
                        .map(StructureAdjustmentUnit::componentIndex)
                        .distinct().count() == 2,
                "each blade should keep terminal dots freely editable");
        for (int component = 0; component < 2; component++) {
            final int selectedComponent = component;
            final java.util.List<Double> distances = pairs.stream()
                    .filter(unit -> unit.componentIndex()
                            == selectedComponent)
                    .map(StructureAdjustmentUnit::initialDistance)
                    .sorted().toList();
            assertTrue(distances.size() >= 2);
            final double localThickness = distances.get(
                    (distances.size() - 1) / 2);
            assertTrue(distances.get(distances.size() - 1)
                            <= localThickness * 4 + 1e-9,
                    "terminal spans must not become thickness chords");
        }

        controller.setStructureAdjustmentSliders(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 300, 0);

        final StructureAdjustmentDraft thickened = controller
                .structureAdjustmentState().draft().orElseThrow();
        final java.util.Map<String, Point2D> targets = thickened
                .requestedControls().stream().collect(
                        java.util.stream.Collectors.toMap(
                                ManualWarpControl::id,
                                ManualWarpControl::targetPoint));
        for (final StructureAdjustmentUnit unit : seeded.units()) {
            if (unit.paired()) {
                final Point2D first = targets.get(unit.controlIds().get(0));
                final Point2D second = targets.get(unit.controlIds().get(1));
                assertEquals(unit.initialDistance() * 3,
                        Math.hypot(second.x() - first.x(),
                                second.y() - first.y()),
                        1e-9);
            } else {
                final String controlId = unit.controlIds().get(0);
                assertPoint(seeded.baselineControls().stream()
                        .filter(control -> control.id().equals(controlId))
                        .findFirst().orElseThrow().targetPoint(),
                        targets.get(controlId));
            }
        }
        assertEquals(0, warpWorker.tasks.size());
        assertEquals(0, session.state().contentRevision());
    }

    @Test
    void structurePairsAndManualOffsetsStayImmutableAcrossSliderEdits() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final QueuedExecutor warpWorker = new QueuedExecutor();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new ConnectedDgSgPlaneSource(),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, warpWorker, Runnable::run, () -> { });
        controller.attach(new CapturingView());
        controller.selectAtlasRegionExactAcronym("DG-sg");
        controller.replaceSelectedStructureControls(
                8, ManualHemisphereWarp2D.AtlasSide.LEFT);
        final StructureAdjustmentDraft seeded = controller
                .structureAdjustmentState().draft().orElseThrow();
        assertEquals(8, seeded.requestedControls().size());
        final StructureAdjustmentUnit pair = seeded.units().stream()
                .filter(StructureAdjustmentUnit::paired).findFirst()
                .orElseThrow();
        final String movedId = pair.controlIds().get(0);
        final Point2D beforeMove = seeded.requestedControls().stream()
                .filter(control -> control.id().equals(movedId))
                .findFirst().orElseThrow().targetPoint();

        controller.transformSelectedStructureControls(
                ManualHemisphereWarp2D.AtlasSide.LEFT, List.of(movedId),
                List.of(new Point2D(beforeMove.x() + 3,
                        beforeMove.y() - 2)));
        controller.setStructureAdjustmentSliders(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 125, 20);

        final StructureAdjustmentDraft edited = controller
                .structureAdjustmentState().draft().orElseThrow();
        assertEquals(seeded.baselineControls(), edited.baselineControls());
        assertEquals(seeded.units(), edited.units(),
                "pair IDs, partners, axes, labels, and components are fixed");
        assertEquals(seeded.componentByControlId(),
                edited.componentByControlId());
        assertPoint(new Point2D(3, -2),
                edited.manualOffsetsByControlId().get(movedId));
        final int endpoint = pair.controlIds().indexOf(movedId);
        final double endpointSign = endpoint == 0 ? -1 : 1;
        final double componentSign = pair.componentIndex() == 0 ? -1 : 1;
        final double halfThickness = pair.initialDistance() * 0.5 * 1.25;
        final double gapShift = componentSign * 0.5
                * seeded.initialBladeSeparation() * 0.20;
        final Point2D expected = new Point2D(
                pair.midpoint().x() + endpointSign
                        * pair.separationAxis().x() * halfThickness
                        + seeded.bladeGapAxis().x() * gapShift + 3,
                pair.midpoint().y() + endpointSign
                        * pair.separationAxis().y() * halfThickness
                        + seeded.bladeGapAxis().y() * gapShift - 2);
        final Point2D actual = edited.requestedControls().stream()
                .filter(control -> control.id().equals(movedId))
                .findFirst().orElseThrow().targetPoint();
        assertPoint(expected, actual);
        assertEquals(0, warpWorker.tasks.size(),
                "dot and slider edits remain solver-free");
        assertEquals(0, session.state().contentRevision());
    }

    @Test
    void structureCalculationLimitsOnlyTheImplicatedStableUnit() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new ConnectedDgSgPlaneSource(),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.selectAtlasRegionExactAcronym("DG-sg");
        controller.replaceSelectedStructureControls(
                8, ManualHemisphereWarp2D.AtlasSide.LEFT);
        final StructureAdjustmentDraft seeded = controller
                .structureAdjustmentState().draft().orElseThrow();
        final StructureAdjustmentUnit moved = seeded.units().stream()
                .filter(StructureAdjustmentUnit::paired).findFirst()
                .orElseThrow();
        final java.util.Map<String, Point2D> starts = seeded
                .requestedControls().stream().collect(
                        java.util.stream.Collectors.toMap(
                                ManualWarpControl::id,
                                ManualWarpControl::targetPoint));
        final List<Point2D> farTargets = moved.controlIds().stream()
                .map(id -> new Point2D(starts.get(id).x() - 400,
                        starts.get(id).y() - 300)).toList();
        controller.transformSelectedStructureControls(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                moved.controlIds(), farTargets);

        controller.calculateStructureAdjustmentPreview();

        final StructureAdjustmentViewState calculated = controller
                .structureAdjustmentState();
        final StructureAdjustmentCandidate candidate = calculated
                .candidate().orElseThrow();
        assertFalse(candidate.completesRequest());
        assertEquals(moved.id(), candidate.highlightedUnitId()
                .orElseThrow());
        assertTrue(candidate.retainedFractionByUnitId().get(moved.id()) < 1);
        seeded.units().stream().filter(unit -> !unit.id().equals(moved.id()))
                .forEach(unit -> assertEquals(1.0,
                        candidate.retainedFractionByUnitId().get(unit.id()),
                        0, "unrelated units must stay fully requested"));
        assertTrue(candidate.limitingReports().get(0).report()
                .meshLocation().isPresent(),
                "pair-local recovery needs a concrete limiting location");
        assertEquals(0, session.state().contentRevision());

        controller.resetHighlightedStructureUnit();
        final StructureAdjustmentDraft reset = controller
                .structureAdjustmentState().draft().orElseThrow();
        moved.controlIds().forEach(id -> assertPoint(starts.get(id), reset
                .requestedControls().stream().filter(control -> control.id()
                        .equals(id)).findFirst().orElseThrow().targetPoint()));
        assertTrue(controller.structureAdjustmentState().candidate()
                .isEmpty());
        assertEquals(0, session.state().contentRevision());

        controller.transformSelectedStructureControls(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                moved.controlIds(), farTargets);
        controller.calculateStructureAdjustmentPreview();
        final StructureAdjustmentCandidate recalculated = controller
                .structureAdjustmentState().candidate().orElseThrow();
        assertFalse(recalculated.completesRequest());

        final long revision = session.state().contentRevision();
        controller.useValidStructurePreviewAsDraft();
        final StructureAdjustmentViewState adopted = controller
                .structureAdjustmentState();
        assertEquals(revision, session.state().contentRevision());
        assertTrue(adopted.candidate().orElseThrow().completesRequest());
        assertEquals(recalculated.validControls(), adopted.draft()
                .orElseThrow().requestedControls());
        controller.applyStructureChanges();
        assertEquals(revision + 1, session.state().contentRevision());
        assertTrue(controller.structureAdjustmentState().draft().isEmpty());
    }

    @Test
    void structureControlsAreOneUndoableBatchAndNeverLandmarks() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        final CapturingView view = new CapturingView();
        controller.attach(view);
        applyConfirmedOutline(controller, basis);
        controller.selectAtlasRegionExactAcronym("DG");
        final long revisionBefore = session.state().contentRevision();
        final int auditBefore = session.auditTrail().size();

        final List<String> identifiers = controller
                .replaceSelectedStructureControls(
                        6, ManualHemisphereWarp2D.AtlasSide.LEFT);

        assertEquals(List.of(
                "manual-control-1", "manual-control-2",
                "manual-control-3", "manual-control-4",
                "manual-control-5", "manual-control-6"), identifiers);
        assertTrue(session.state().content().activeLandmarks().isEmpty());
        assertEquals(revisionBefore, session.state().contentRevision(),
                "Set N must not create history before explicit Apply");
        assertTrue(view.last().structureAdjustment().candidate().isEmpty());
        controller.calculateStructureAdjustmentPreview();
        assertTrue(view.last().structureAdjustment().candidate().isPresent(),
                view.lastError);
        controller.applyStructureChanges();
        assertTrue(session.state().content().hemisphereWarp().isPresent());
        final var controls = session.state().content().hemisphereWarp()
                .orElseThrow().manualControls(
                        ManualHemisphereWarp2D.AtlasSide.LEFT);
        assertEquals(6, controls.size());
        controls.forEach(control -> {
            assertEquals("DG", control.structureAcronym());
            assertEquals("structure-guide:DG", control.groupId());
            assertEquals(control.sourcePoint(), control.targetPoint());
        });
        assertEquals(revisionBefore + 1,
                session.state().contentRevision());
        assertEquals(auditBefore + 1, session.auditTrail().size());
        assertEquals(ReviewOperation.REPLACE_STRUCTURE_WARP_CONTROL_GROUP,
                session.auditTrail().get(session.auditTrail().size() - 1)
                        .operation());

        assertTrue(session.undo());
        assertTrue(session.state().content().hemisphereWarp().isEmpty());
        assertEquals(revisionBefore + 2,
                session.state().contentRevision());
    }

    @Test
    void structureGroupsCoexistResizeIndependentlyAndClearByAcronym() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        applyConfirmedOutline(controller, basis);
        controller.selectAtlasRegionExactAcronym("DG");
        setAndApplyStructure(controller,
                4, ManualHemisphereWarp2D.AtlasSide.LEFT);
        final List<ManualWarpControl> dgBefore = controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.STRUCTURE_GUIDE);

        controller.selectAtlasRegionExactAcronym("DG-sg");
        setAndApplyStructure(controller,
                4, ManualHemisphereWarp2D.AtlasSide.LEFT);
        final List<ManualWarpControl> both = controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.STRUCTURE_GUIDE);
        assertEquals(8, both.size());
        assertEquals(Set.of("DG", "DG-sg"), both.stream()
                .map(ManualWarpControl::structureAcronym)
                .collect(java.util.stream.Collectors.toSet()));

        final List<ManualWarpControl> dgSg = both.stream()
                .filter(control -> control.structureAcronym().equals("DG-sg"))
                .toList();
        final AlignmentReviewContent beforeResize = session.state().content();
        final double centerX = dgSg.stream().mapToDouble(control ->
                control.targetPoint().x()).average().orElseThrow();
        final List<Point2D> widened = dgSg.stream().map(control ->
                new Point2D(centerX + 1.08
                        * (control.targetPoint().x() - centerX),
                        control.targetPoint().y())).toList();
        final long revision = session.state().contentRevision();
        controller.transformSelectedStructureControls(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                dgSg.stream().map(ManualWarpControl::id).toList(), widened);
        assertEquals(revision, session.state().contentRevision(),
                "point edits remain transient until Structure Apply");
        controller.calculateStructureAdjustmentPreview();
        controller.applyStructureChanges();

        assertEquals(revision + 1, session.state().contentRevision());
        assertEquals(ReviewOperation.TRANSFORM_STRUCTURE_WARP_CONTROL_GROUP,
                session.auditTrail().get(session.auditTrail().size() - 1)
                        .operation());
        final List<ManualWarpControl> afterResize = controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.STRUCTURE_GUIDE);
        assertEquals(dgBefore, afterResize.stream().filter(control ->
                control.structureAcronym().equals("DG")).toList(),
                "resizing DG-sg must retain DG controls exactly");
        assertNotEquals(dgSg, afterResize.stream().filter(control ->
                control.structureAcronym().equals("DG-sg")).toList());
        final AlignmentReviewContent resized = session.state().content();

        controller.clearSelectedStructureControls(
                ManualHemisphereWarp2D.AtlasSide.LEFT);
        assertEquals(ReviewOperation.CLEAR_STRUCTURE_WARP_CONTROL_GROUP,
                session.auditTrail().get(session.auditTrail().size() - 1)
                        .operation());
        assertEquals(dgBefore, controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.STRUCTURE_GUIDE));
        assertTrue(session.state().content().activeLandmarks().isEmpty());
        final AlignmentReviewContent cleared = session.state().content();

        controller.undo();
        assertEquals(resized, session.state().content(),
                "Undo must restore the exact resized shared field");
        controller.undo();
        assertEquals(beforeResize, session.state().content(),
                "a second Undo must restore the exact pre-resize groups");
        controller.redo();
        assertEquals(resized, session.state().content());
        controller.redo();
        assertEquals(cleared, session.state().content(),
                "Redo must restore the exact acronym-specific clear");
    }

    @Test
    void unsafeStructureGroupResizeInstallsOneAuditedSafeFraction() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        applyConfirmedOutline(controller, basis);
        controller.selectAtlasRegionExactAcronym("DG");
        setAndApplyStructure(controller,
                6, ManualHemisphereWarp2D.AtlasSide.LEFT);
        final List<ManualWarpControl> before = controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.STRUCTURE_GUIDE);
        final double centerX = before.stream().mapToDouble(control ->
                control.targetPoint().x()).average().orElseThrow();
        final double centerY = before.stream().mapToDouble(control ->
                control.targetPoint().y()).average().orElseThrow();
        final List<Point2D> requested = before.stream().map(control ->
                new Point2D(centerX + 10
                        * (control.targetPoint().x() - centerX),
                        centerY + 10
                        * (control.targetPoint().y() - centerY))).toList();
        final long revision = session.state().contentRevision();

        controller.transformSelectedStructureControls(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                before.stream().map(ManualWarpControl::id).toList(),
                requested);
        assertEquals(revision, session.state().contentRevision());
        controller.calculateStructureAdjustmentPreview();
        final StructureAdjustmentDraft originalDraft = controller
                .structureAdjustmentState().draft().orElseThrow();
        final StructureAdjustmentCandidate calculated = controller
                .structureAdjustmentState().candidate().orElseThrow();
        assertFalse(calculated.completesRequest());
        controller.applyStructureChanges();

        assertEquals(revision + 1, session.state().contentRevision());
        final ManualHemisphereWarp2D installed = session.state().content()
                .hemisphereWarp().orElseThrow();
        final List<Point2D> installedTargets = installed.manualControls(
                ManualHemisphereWarp2D.AtlasSide.LEFT).stream()
                .filter(control -> control.structureAcronym().equals("DG"))
                .map(ManualWarpControl::targetPoint).toList();
        assertNotEquals(requested, installedTargets);
        assertNotEquals(before.stream().map(
                ManualWarpControl::targetPoint).toList(), installedTargets);
        assertTrue(installed.diagnostics()
                .minimumJacobianDeterminant() >= 0.20);
        assertTrue(installed.diagnostics().minimumSingularValue() >= 0.30);
        assertTrue(installed.diagnostics().maximumSingularValue() <= 3.0);
        assertTrue(installed.diagnostics().maximumAnisotropy() <= 3.0);
        final StructureAdjustmentDraft remaining = controller
                .structureAdjustmentState().draft().orElseThrow();
        assertEquals(100, remaining.thicknessPercent());
        assertEquals(0, remaining.bladeGapPercent());
        assertEquals(installedTargets, remaining.baselineControls().stream()
                .map(ManualWarpControl::targetPoint).toList(),
                "partial Apply rebases amber starts to applied positions");
        assertEquals(requested, remaining.requestedControls().stream()
                .map(ManualWarpControl::targetPoint).toList(),
                "the original absolute request remains editable");
        assertEquals(originalDraft.units().stream().map(
                        StructureAdjustmentUnit::id).toList(),
                remaining.units().stream().map(
                        StructureAdjustmentUnit::id).toList());
        assertEquals(originalDraft.units().stream().map(
                        StructureAdjustmentUnit::controlIds).toList(),
                remaining.units().stream().map(
                        StructureAdjustmentUnit::controlIds).toList());
        assertTrue(controller.structureAdjustmentState().candidate()
                .isEmpty(), "the remainder requires another Calculate");
        assertEquals(basis.sourceSnapshot().pixelSha256(),
                session.state().basis().sourceSnapshot().pixelSha256());
        assertTrue(session.state().content().activeLandmarks().isEmpty());
    }

    @Test
    void staleStructureGroupTransformCannotRestoreAnEarlierPlane() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final QueuedExecutor warpWorker = new QueuedExecutor();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, warpWorker, Runnable::run, () -> { });
        controller.attach(new CapturingView());
        applyConfirmedOutline(controller, basis);
        controller.selectAtlasRegionExactAcronym("DG");
        controller.replaceSelectedStructureControls(
                4, ManualHemisphereWarp2D.AtlasSide.LEFT);
        controller.calculateStructureAdjustmentPreview();
        warpWorker.run(0);
        controller.applyStructureChanges();
        final List<ManualWarpControl> before = controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.STRUCTURE_GUIDE);
        final List<Point2D> requested = before.stream().map(control ->
                new Point2D(control.targetPoint().x() + 2,
                        control.targetPoint().y())).toList();

        controller.transformSelectedStructureControls(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                before.stream().map(ManualWarpControl::id).toList(),
                requested);
        controller.calculateStructureAdjustmentPreview();
        controller.setCoronalLevel(241);
        final long revisedPlaneRevision = session.state().contentRevision();
        warpWorker.run(1);

        assertEquals(revisedPlaneRevision, session.state().contentRevision(),
                "a stale off-thread structure solve cannot create history");
        assertEquals(241, session.state().content().coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
        assertEquals(before, controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.STRUCTURE_GUIDE));
    }

    @Test
    void defaultBilateralGridAndStructureGroupsReplaceWithoutDuplicates() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        final CapturingView view = new CapturingView();
        controller.attach(view);
        assertEquals(0, session.state().contentRevision(),
                "opening the review must retain the coarse-placement stage");
        assertTrue(session.state().content().hemisphereWarp().isEmpty());
        assertTrue(controller.startLocalWarpWithDefaultGridIfNeeded());
        assertEquals(1, session.state().contentRevision(),
                "choosing Points must install the bilateral grid as one revision: "
                        + view.lastError);
        assertEquals(1, session.auditTrail().size());
        assertEquals(24, controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
        assertEquals(24, controls(session,
                ManualHemisphereWarp2D.AtlasSide.RIGHT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
        assertTrue(controls(session, ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.TISSUE_BOUNDARY).isEmpty(),
                "new reviews must not create boundary warp handles");

        controller.selectAtlasRegionExactAcronym("DG");
        setAndApplyStructure(controller,
                4, ManualHemisphereWarp2D.AtlasSide.LEFT);
        setAndApplyStructure(controller,
                6, ManualHemisphereWarp2D.AtlasSide.LEFT);
        assertEquals(6, controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.STRUCTURE_GUIDE).size());
        assertEquals(30, session.state().content().hemisphereWarp()
                .orElseThrow().manualControls(
                        ManualHemisphereWarp2D.AtlasSide.LEFT).size());
        assertTrue(session.state().content().activeLandmarks().isEmpty());

        controller.replaceInteriorGridControlsForSupportedSides(12);
        assertEquals(12, controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
        assertEquals(12, controls(session,
                ManualHemisphereWarp2D.AtlasSide.RIGHT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
        assertEquals(6, controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.STRUCTURE_GUIDE).size(),
                "Set-N grid replaces only the regular-grid group");

        final AlignmentReviewContent beforeReset = session.state().content();
        final long revisionBeforeReset = session.state().contentRevision();
        final int auditBeforeReset = session.auditTrail().size();
        controller.reset();
        assertEquals(revisionBeforeReset + 1,
                session.state().contentRevision(),
                "Reset to coarse placement must be one revision");
        assertEquals(auditBeforeReset + 1, session.auditTrail().size(),
                "Reset to coarse placement must be one audit entry");
        assertEquals(ReviewOperation.RESET_TO_PROPOSAL,
                session.auditTrail().get(session.auditTrail().size() - 1)
                        .operation());
        assertTrue(session.state().content().hemisphereWarp().isEmpty(),
                "Reset must return to coarse placement without local dots");

        controller.undo();
        assertEquals(beforeReset, session.state().content(),
                "one Undo after Reset must restore the complete pre-reset review");
        controller.redo();
        assertTrue(session.state().content().hemisphereWarp().isEmpty(),
                "Redo must replay the exact coarse-placement reset");
    }

    @Test
    void interiorGridAcceptsSixtyFourDistributedPointsOnOneSide() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());

        final List<String> identifiers = controller
                .replaceInteriorGridControls(
                        64, ManualHemisphereWarp2D.AtlasSide.LEFT);

        assertEquals(64, identifiers.size());
        assertEquals(64, controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
        assertEquals(1, session.state().contentRevision(),
                "one dense Interior replacement remains one Undo revision");
    }

    @Test
    void existingSideControlsDoNotBiasStructureSamplesToLoopStart() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        final CapturingView view = new CapturingView();
        controller.attach(view);
        assertTrue(controller.startLocalWarpWithDefaultGridIfNeeded());
        controller.selectAtlasRegionExactAcronym("DG-sg");

        final var before = session.state();
        final SelectedAtlasContour contour = view.last()
                .selectedAtlasContour().orElseThrow();
        final List<Point2D> expected = contour.samplePrincipalExteriorPoints(
                16, AtlasAnatomicalSide.ATLAS_LEFT).stream()
                .map(before::mapAtlasBeforeHemisphereWarp).toList();

        controller.replaceSelectedStructureControls(
                16, ManualHemisphereWarp2D.AtlasSide.LEFT);

        final List<Point2D> actual = controller.structureAdjustmentState()
                .draft().orElseThrow().requestedControls().stream()
                .map(ManualWarpControl::sourcePoint).toList();
        assertEquals(expected, actual,
                "unrelated grid controls must not truncate structure "
                        + "sampling to the first perimeter arc");
    }

    @Test
    void pointsTriggeredBilateralGridConstructionAndSolveRunAwayFromEdt()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Boolean> ranOnEdt = new AtomicReference<>();
        final Executor manualExecutor = task -> worker.execute(() -> {
            ranOnEdt.set(SwingUtilities.isEventDispatchThread());
            try {
                task.run();
            } finally {
                completed.countDown();
            }
        });
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, manualExecutor, Runnable::run,
                worker::shutdownNow);

        SwingUtilities.invokeAndWait(() -> {
            controller.attach(new CapturingView());
            assertTrue(session.state().content().hemisphereWarp().isEmpty());
            assertTrue(controller.startLocalWarpWithDefaultGridIfNeeded());
        });

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(Boolean.FALSE, ranOnEdt.get());
        assertEquals(48, session.state().content().hemisphereWarp()
                .orElseThrow().controls().size());
        assertEquals(1, session.state().contentRevision());
        controller.close();
    }

    @Test
    void clickingAnInactiveSideDotActivatesThatSideWithoutHidingEitherSide()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            panel.canvas().setSize(820, 600);
            controller.attach(panel);
            assertTrue(controller.startLocalWarpWithDefaultGridIfNeeded());
            final ReviewCanvas canvas = panel.canvas();
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            canvas.setActiveHemisphereSide(
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            final ManualWarpControl right = session.state().content()
                    .hemisphereWarp().orElseThrow().manualControls(
                            ManualHemisphereWarp2D.AtlasSide.RIGHT).get(0);
            final Point2D screen = canvas.sourceScreenMapping()
                    .previewToScreen(right.targetPoint());
            final long revision = session.state().contentRevision();

            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED,
                    (int) Math.round(screen.x()),
                    (int) Math.round(screen.y()), MouseEvent.BUTTON1));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED,
                    (int) Math.round(screen.x()),
                    (int) Math.round(screen.y()), MouseEvent.BUTTON1));

            assertTrue(findRadio(panel, "Atlas right").isSelected());
            assertEquals(48, canvas.visibleManualWarpControls().size());
            assertEquals(revision, session.state().contentRevision(),
                    "selecting an inactive-side identity dot is display-only");
            assertEquals(7, ReviewCanvas.MANUAL_WARP_HIT_RADIUS);
            assertEquals(9, ReviewCanvas.MANUAL_WARP_TARGET_DIAMETER);
            assertEquals(7, ReviewCanvas.MANUAL_WARP_ORIGIN_DIAMETER);
        });
    }

    @Test
    void cropMoveIsAtomicUndoableAndCannotChangeWarpOrConfidence() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        final var support = session.state().content()
                .reviewedTissueSupport().orElseThrow();
        final var control = support.controls().get(0);
        final var warp = session.state().content().hemisphereWarp();
        final var confidence = session.confidence();
        final long revision = session.state().contentRevision();

        controller.moveTissueSupportControl(control.id(), new Point2D(
                control.point().x() + 0.5, control.point().y() + 0.5));

        assertEquals(revision + 1, session.state().contentRevision());
        assertNotEquals(support.contentSha256(), session.state().content()
                .reviewedTissueSupport().orElseThrow().contentSha256());
        assertEquals(warp, session.state().content().hemisphereWarp());
        assertEquals(confidence, session.confidence());
        assertTrue(session.undo());
        assertEquals(support, session.state().content()
                .reviewedTissueSupport().orElseThrow());
        assertTrue(session.redo());
        assertEquals(warp, session.state().content().hemisphereWarp());
    }

    @Test
    void unchangedCropSuggestionPreservesHistoryAndRedo() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final java.util.ArrayDeque<Runnable> queued = new java.util.ArrayDeque<>();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, queued::addLast, Runnable::run, () -> { });
        final CapturingView view = new CapturingView();
        controller.attach(view);
        controller.resuggestTissueSupport();
        assertDoesNotThrow(() -> queued.removeFirst().run());
        final var support = session.state().content()
                .reviewedTissueSupport().orElseThrow();
        final var control = support.controls().get(0);
        controller.moveTissueSupportControl(control.id(), new Point2D(
                control.point().x() + 0.5, control.point().y() + 0.5));
        queued.removeFirst().run();
        assertTrue(session.undo());
        final var before = session.state();
        final var audit = session.auditTrail();
        controller.resuggestTissueSupport();
        assertDoesNotThrow(() -> queued.removeFirst().run());
        assertEquals(before.content(), session.state().content());
        assertEquals(before.contentRevision(), session.state().contentRevision());
        assertEquals(audit, session.auditTrail());
        assertTrue(session.canRedo());
        assertEquals("", view.lastError);
    }

    @Test
    void cropConstructionRunsOffEdtAndStaleResultIsDiscarded()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Boolean> ranOnEdt = new AtomicReference<>();
        final Executor worker = command -> {
            final Thread thread = new Thread(() -> {
                try {
                    ranOnEdt.set(SwingUtilities.isEventDispatchThread());
                    command.run();
                } finally {
                    completed.countDown();
                }
            }, "tissue-support-test-worker");
            thread.setDaemon(true);
            thread.start();
        };
        final ReviewController backgroundController = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, worker, Runnable::run, () -> { });
        final var support = session.state().content()
                .reviewedTissueSupport().orElseThrow();
        final var control = support.controls().get(0);

        SwingUtilities.invokeAndWait(() ->
                backgroundController.moveTissueSupportControl(
                        control.id(), new Point2D(
                                control.point().x() + 0.5,
                                control.point().y() + 0.5)));
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(Boolean.FALSE, ranOnEdt.get());
        assertNotEquals(support, session.state().content()
                .reviewedTissueSupport().orElseThrow());

        final AlignmentReviewSession staleSession =
                new AlignmentReviewSession(basis);
        final java.util.ArrayDeque<Runnable> queued =
                new java.util.ArrayDeque<>();
        final ReviewController staleController = new ReviewController(
                staleSession, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, queued::addLast, Runnable::run, () -> { });
        final var staleSupport = staleSession.state().content()
                .reviewedTissueSupport().orElseThrow();
        final var staleControl = staleSupport.controls().get(0);
        staleController.moveTissueSupportControl(staleControl.id(),
                new Point2D(staleControl.point().x() + 0.5,
                        staleControl.point().y() + 0.5));
        staleController.setSagittalTiltDegrees(1);
        final long changedRevision = staleSession.state().contentRevision();

        queued.removeFirst().run();

        assertEquals(changedRevision, staleSession.state().contentRevision());
        assertEquals(staleSupport, staleSession.state().content()
                .reviewedTissueSupport().orElseThrow());
    }

    @Test
    void directControlMoveRefitsOneSideInOneRevision() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        final CapturingView view = new CapturingView();
        controller.attach(view);
        applyConfirmedOutline(controller, basis);
        controller.selectAtlasRegionExactAcronym("DG");
        setAndApplyStructure(controller,
                4, ManualHemisphereWarp2D.AtlasSide.LEFT);
        setAndApplyStructure(controller,
                4, ManualHemisphereWarp2D.AtlasSide.RIGHT);
        assertEquals(4, session.state().content().hemisphereWarp()
                .orElseThrow().manualControls(
                        ManualHemisphereWarp2D.AtlasSide.RIGHT).size(),
                view.lastError);
        final var before = session.state().content().hemisphereWarp()
                .orElseThrow().controls();
        final var moved = before.stream().filter(control ->
                control.atlasSide()
                        == ManualHemisphereWarp2D.AtlasSide.LEFT)
                .findFirst().orElseThrow();
        final long beforeRevision = session.state().contentRevision();
        final Point2D oppositeBefore = session.state().content()
                .hemisphereWarp().orElseThrow().manualControls(
                        ManualHemisphereWarp2D.AtlasSide.RIGHT).get(0)
                .sourcePoint();
        final Point2D midlineBefore = session.state().content().outlineWarp()
                .orElseThrow().hemisphereMidline().dorsal();

        controller.moveManualWarpControlAndRefit(
                moved.id(), new Point2D(
                        moved.targetPoint().x() + 1,
                        moved.targetPoint().y() + 1));

        assertEquals(beforeRevision + 1,
                session.state().contentRevision());
        final var warp = session.state().content().hemisphereWarp()
                .orElseThrow();
        final String warpHash = warp.diagnostics().contentSha256();
        assertEquals(4, warp.manualControls(
                ManualHemisphereWarp2D.AtlasSide.LEFT).size());
        assertEquals(4, warp.manualControls(
                ManualHemisphereWarp2D.AtlasSide.RIGHT).size());
        assertEquals(oppositeBefore, warp.apply(
                        ManualHemisphereWarp2D.AtlasSide.RIGHT,
                        oppositeBefore),
                "moving atlas-left controls must not deform atlas-right anatomy");
        assertEquals(midlineBefore, warp.apply(midlineBefore),
                "the confirmed anatomical midline must remain fixed");
        assertEquals(ReviewOperation.MOVE_MANUAL_WARP_CONTROL_AND_INSTALL,
                session.auditTrail().get(session.auditTrail().size() - 1)
                        .operation());
        assertTrue(session.state().content().landmarks().isEmpty());
        final var second = session.state().content().hemisphereWarp()
                .orElseThrow().manualControls(
                        ManualHemisphereWarp2D.AtlasSide.LEFT).get(1);
        controller.moveManualWarpControlAndRefit(
                second.id(), new Point2D(
                        second.targetPoint().x() + 0.5,
                        second.targetPoint().y() - 0.5));
        final String secondWarpHash = session.state().content()
                .hemisphereWarp().orElseThrow().diagnostics()
                .contentSha256();
        assertFalse(warpHash.equals(secondWarpHash));
        assertEquals(beforeRevision + 2,
                session.state().contentRevision(),
                "each completed endpoint gesture creates one revision");
        controller.undo();
        assertEquals(warpHash, session.state().content().hemisphereWarp()
                .orElseThrow().diagnostics().contentSha256(),
                "undo must restore the exact preceding deformation");
        controller.redo();
        assertEquals(secondWarpHash, session.state().content().hemisphereWarp()
                .orElseThrow().diagnostics().contentSha256(),
                "redo must restore the exact audited deformation");
    }

    @Test
    void sideControlReplacementChangesGranularityWithoutDuplicates() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        applyConfirmedOutline(controller, basis);
        controller.selectAtlasRegionExactAcronym("DG-sg");

        final List<String> oldLeft = controller
                .replaceSelectedStructureControls(
                        4, ManualHemisphereWarp2D.AtlasSide.LEFT);
        controller.calculateStructureAdjustmentPreview();
        controller.applyStructureChanges();
        final List<String> right = controller
                .replaceSelectedStructureControls(
                        5, ManualHemisphereWarp2D.AtlasSide.RIGHT);
        controller.calculateStructureAdjustmentPreview();
        controller.applyStructureChanges();
        final long beforeReplacement = session.state().contentRevision();
        final List<String> newLeft = controller
                .replaceSelectedStructureControls(
                        8, ManualHemisphereWarp2D.AtlasSide.LEFT);
        assertEquals(beforeReplacement, session.state().contentRevision());
        controller.calculateStructureAdjustmentPreview();
        controller.applyStructureChanges();

        final var active = session.state().content().hemisphereWarp()
                .orElseThrow().controls();
        assertEquals(13, active.size());
        assertEquals(8, active.stream().filter(control -> control.atlasSide()
                == ManualHemisphereWarp2D.AtlasSide.LEFT).count());
        assertEquals(5, active.stream().filter(control -> control.atlasSide()
                == ManualHemisphereWarp2D.AtlasSide.RIGHT).count());
        assertEquals(8, active.stream().filter(control -> control.atlasSide()
                        == ManualHemisphereWarp2D.AtlasSide.LEFT)
                .map(control -> control.sourcePoint()).distinct().count(),
                "each side batch must contain unique atlas controls");
        assertTrue(active.stream().noneMatch(control ->
                oldLeft.contains(control.id())));
        assertEquals(right.size(), active.stream()
                        .filter(control -> right.contains(control.id())).count(),
                "opposite-side handles must be preserved");
        assertEquals(8, newLeft.size());
        assertEquals(beforeReplacement + 1,
                session.state().contentRevision());
        assertEquals(ReviewOperation.REPLACE_STRUCTURE_WARP_CONTROL_GROUP,
                session.auditTrail().get(session.auditTrail().size() - 1)
                        .operation());

        controller.undo();
        assertEquals(9, session.state().content().hemisphereWarp()
                .orElseThrow().controls().size());
    }

    @Test
    void staleBackgroundMeshResultIsDiscardedAfterUpstreamPlaneEdit() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final java.util.ArrayDeque<Runnable> warpTasks =
                new java.util.ArrayDeque<>();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, warpTasks::addLast, Runnable::run, () -> { });
        controller.attach(new CapturingView());
        applyConfirmedOutline(controller, basis);
        controller.selectAtlasRegionExactAcronym("DG");
        final long outlinedRevision = session.state().contentRevision();

        controller.replaceSelectedStructureControls(
                6, ManualHemisphereWarp2D.AtlasSide.LEFT);
        assertEquals(0, warpTasks.size(),
                "Set N must remain solver-free");
        controller.calculateStructureAdjustmentPreview();
        assertEquals(1, warpTasks.size());
        assertEquals(outlinedRevision, session.state().contentRevision(),
                "submitting a solve must not create a revision");
        controller.setHorizontalTiltDegrees(1);
        final long changedRevision = session.state().contentRevision();

        warpTasks.removeFirst().run();

        assertEquals(changedRevision, session.state().contentRevision());
        assertTrue(session.state().content().hemisphereWarp().isEmpty());
        assertTrue(session.state().content().outlineWarp().isEmpty());
    }

    @Test
    void manualMeshSolveRunsAwayFromSwingEventDispatchThread()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final CountDownLatch solved = new CountDownLatch(1);
        final AtomicReference<Boolean> ranOnEdt = new AtomicReference<>();
        final Executor backgroundSolver = command -> {
            final Thread thread = new Thread(() -> {
                try {
                    ranOnEdt.set(SwingUtilities.isEventDispatchThread());
                    command.run();
                } finally {
                    solved.countDown();
                }
            }, "manual-mesh-test-worker");
            thread.setDaemon(true);
            thread.start();
        };
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, backgroundSolver, Runnable::run, () -> { });
        controller.attach(new CapturingView());
        applyConfirmedOutline(controller, basis);
        controller.selectAtlasRegionExactAcronym("DG");

        SwingUtilities.invokeAndWait(() -> {
            controller.replaceSelectedStructureControls(
                    4, ManualHemisphereWarp2D.AtlasSide.LEFT);
            assertTrue(controller.structureAdjustmentState().candidate()
                    .isEmpty());
            controller.calculateStructureAdjustmentPreview();
        });

        assertTrue(solved.await(5, TimeUnit.SECONDS));
        assertEquals(Boolean.FALSE, ranOnEdt.get());
        assertTrue(controller.structureAdjustmentState().candidate()
                .isPresent());
        assertTrue(session.state().content().hemisphereWarp().isEmpty(),
                "off-thread audit is still a transient preview");
        controller.applyStructureChanges();
        assertTrue(session.state().content().hemisphereWarp().isPresent());
    }

    @Test
    void unsafeReleaseClampsToLastAuditedStateInOneRevision() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        applyConfirmedOutline(controller, basis);
        controller.selectAtlasRegionExactAcronym("DG");
        setAndApplyStructure(controller,
                6, ManualHemisphereWarp2D.AtlasSide.LEFT);
        final var prior = session.state().content().hemisphereWarp()
                .orElseThrow().manualControls(
                        ManualHemisphereWarp2D.AtlasSide.LEFT).get(0);
        final long revision = session.state().contentRevision();
        final Point2D unsafe = new Point2D(99, 79);

        controller.moveManualWarpControlAndRefit(prior.id(), unsafe);

        assertEquals(revision + 1, session.state().contentRevision());
        final var installedWarp = session.state().content().hemisphereWarp()
                .orElseThrow();
        final Point2D installed = installedWarp.controls().stream()
                .filter(control -> control.id().equals(prior.id()))
                .findFirst().orElseThrow().targetPoint();
        assertNotEquals(unsafe, installed);
        assertNotEquals(prior.targetPoint(), installed);
        assertTrue(installedWarp.diagnostics()
                .minimumJacobianDeterminant() >= 0.20);
        assertTrue(installedWarp.diagnostics().minimumSingularValue() >= 0.30);
        assertTrue(installedWarp.diagnostics().maximumSingularValue() <= 3.0);
        assertTrue(installedWarp.diagnostics().maximumAnisotropy() <= 3.0);
        assertTrue(installedWarp.diagnostics().solveMillis() < 1500,
                "unsafe clamp exceeded the bounded release target: "
                        + installedWarp.diagnostics().solveMillis() + " ms");
    }

    @Test
    void corpusCallosumRequiresAnExplicitEditableSide() {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        applyConfirmedOutline(controller, basis);
        controller.selectAtlasRegionExactAcronym("cc");
        final long revisionBefore = session.state().contentRevision();

        assertThrows(IllegalArgumentException.class,
                () -> controller.addSelectedTargetHandles(3));
        assertThrows(IllegalArgumentException.class,
                () -> controller.addSelectedTargetHandles(
                        3, AtlasAnatomicalSide.MIDLINE));
        assertTrue(session.state().content().activeLandmarks().isEmpty());
        assertEquals(revisionBefore, session.state().contentRevision());
    }

    @Test
    void targetHandleBatchSupportsReviewerSelectedSectionGeometry() {
        for (final ReviewSectionMode mode : ReviewSectionMode.values()) {
            final AlignmentReviewBasis basis =
                    ReviewPluginFixtures.scaledAtlasBasis();
            final AlignmentReviewSession session =
                    new AlignmentReviewSession(basis);
            final ReviewController controller = new ReviewController(
                    session, ReviewPluginFixtures.preview(),
                    new CatalogPlaneSource(),
                    () -> new ReviewAcceptanceVerification(
                            basis.sourceSnapshot(), basis.atlas()),
                    Runnable::run, Runnable::run);
            final CapturingView view = new CapturingView();
            controller.attach(view);
            final long revisionBeforeModeChange =
                    session.state().contentRevision();
            final ReviewSectionMode modeBeforeChange = session.state()
                    .content().reviewSectionMode();
            controller.setReviewSectionMode(mode);
            if (mode != modeBeforeChange) {
                assertEquals(revisionBeforeModeChange + 1,
                        session.state().contentRevision(),
                        "Changing section mode must be one undoable revision");
            }
            if (mode == ReviewSectionMode.HALF) {
                controller.setObservedHemisphere(
                        ObservedAnatomicalHemisphere.LEFT);
            }
            controller.selectAtlasRegionExactAcronym("DG");

            controller.replaceSelectedStructureControls(
                    6, ManualHemisphereWarp2D.AtlasSide.LEFT);
            assertTrue(session.state().content().landmarks().isEmpty(),
                    mode.name());
            controller.calculateStructureAdjustmentPreview();
            assertTrue(controller.structureAdjustmentState().candidate()
                    .isPresent(), mode.name() + ": " + view.lastError);
            controller.applyStructureChanges();
            assertTrue(session.state().content().hemisphereWarp().isPresent(),
                    mode.name() + ": " + view.lastError);
        }
    }

    @Test
    void disjoinedPlacementIsAtomicIndependentAndPreservedByPlaneSliders() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.setReviewSectionMode(ReviewSectionMode.DISJOINED);
        final Point2D leftAtlas = new Point2D(40, 30);
        final Point2D rightAtlas = new Point2D(400, 30);
        final Point2D leftBefore = controller.state()
                .mapAtlasToPreview(leftAtlas);
        final Point2D rightBefore = controller.state()
                .mapAtlasToPreview(rightAtlas);
        final long revision = controller.state().contentRevision();

        controller.translateManualSide(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 5, -2);

        assertEquals(revision + 1, controller.state().contentRevision());
        assertPoint(new Point2D(leftBefore.x() + 5, leftBefore.y() - 2),
                controller.state().mapAtlasToPreview(leftAtlas));
        assertEquals(rightBefore,
                controller.state().mapAtlasToPreview(rightAtlas));
        final Point2D leftProbeAtlas = new Point2D(25, 20);
        final Point2D leftProbeBeforeResize = controller.state()
                .mapAtlasToPreview(leftProbeAtlas);
        final Point2D leftResizePivot = controller.state()
                .mapAtlasToPreview(leftAtlas);
        final double resizeAxis = Math.toRadians(11);
        controller.rotateManualSide(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                resizeAxis, leftResizePivot);
        final long beforeResize = controller.state().contentRevision();

        controller.scaleManualSideAxes(
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                1.15, 0.85, resizeAxis, leftResizePivot);

        assertEquals(beforeResize + 1,
                controller.state().contentRevision());
        assertNotEquals(leftProbeBeforeResize,
                controller.state().mapAtlasToPreview(leftProbeAtlas));
        assertEquals(rightBefore,
                controller.state().mapAtlasToPreview(rightAtlas),
                "resizing one Disjoined half must leave the other exact");
        final long safeRevision = controller.state().contentRevision();
        final ManualWarpException tooFar = assertThrows(
                ManualWarpException.class,
                () -> controller.translateManualSide(
                        ManualHemisphereWarp2D.AtlasSide.LEFT, 1_000, 0));
        assertEquals(ManualWarpFailureKind.EXCESSIVE_DISPLACEMENT,
                tooFar.kind());
        assertEquals(safeRevision, controller.state().contentRevision());
        final ManualWarpException tooSmall = assertThrows(
                ManualWarpException.class,
                () -> controller.scaleManualSide(
                        ManualHemisphereWarp2D.AtlasSide.LEFT, 0.10,
                        leftBefore));
        assertEquals(ManualWarpFailureKind.EXCESSIVE_STRETCH,
                tooSmall.kind());
        assertEquals(safeRevision, controller.state().contentRevision());
        final var placement = controller.state().content()
                .manualSidePlacement();
        controller.setCoronalLevel(241);
        controller.setSagittalTiltDegrees(2);
        controller.setHorizontalTiltDegrees(-1);
        assertEquals(placement,
                controller.state().content().manualSidePlacement());
        controller.resetManualWarpSide(
                ManualHemisphereWarp2D.AtlasSide.LEFT);
        assertTrue(controller.state().content().manualSidePlacement()
                .isIdentity());
    }

    @Test
    void fullAndHalfAcceptSafeTwoAxisResizeAndRejectUnsafeAspectRatio() {
        for (final ReviewSectionMode mode : List.of(
                ReviewSectionMode.FULL, ReviewSectionMode.HALF)) {
            final AlignmentReviewBasis basis =
                    ReviewPluginFixtures.segmentedScaledAtlasBasis();
            final AlignmentReviewSession session =
                    new AlignmentReviewSession(basis);
            final ReviewController controller = new ReviewController(
                    session, ReviewPluginFixtures.segmentedPreview(),
                    new CatalogPlaneSource(false, false),
                    () -> new ReviewAcceptanceVerification(
                            basis.sourceSnapshot(), basis.atlas()),
                    Runnable::run, Runnable::run);
            controller.attach(new CapturingView());
            if (mode == ReviewSectionMode.HALF) {
                controller.setReviewSectionMode(mode);
                controller.setObservedHemisphere(
                        ObservedAnatomicalHemisphere.LEFT);
            }
            final Point2D pivot = new Point2D(50, 40);
            final long before = controller.state().contentRevision();

            assertTrue(controller.canPreviewScaleAxes(
                    1.20, 0.80, 0, pivot), mode.name());
            controller.scaleAxes(1.20, 0.80, 0, pivot);

            assertEquals(before + 1,
                    controller.state().contentRevision(), mode.name());
            final AffineTransform2D resized = controller.state().content()
                    .manualPreviewAdjustment();
            assertEquals(1.20, resized.m00(), 1e-12, mode.name());
            assertEquals(0.80, resized.m11(), 1e-12, mode.name());
            assertEquals(0, resized.m01(), 1e-12, mode.name());
            assertEquals(0, resized.m10(), 1e-12, mode.name());

            assertFalse(controller.canPreviewScaleAxes(
                    1, 0.20, 0, pivot), mode.name());
            final long safeRevision = controller.state().contentRevision();
            final ManualWarpException rejected = assertThrows(
                    ManualWarpException.class,
                    () -> controller.scaleAxes(1, 0.20, 0, pivot));
            assertEquals(ManualWarpFailureKind.EXCESSIVE_STRETCH,
                    rejected.kind(), mode.name());
            assertEquals(safeRevision,
                    controller.state().contentRevision(), mode.name());
            assertEquals(resized, controller.state().content()
                    .manualPreviewAdjustment(), mode.name());

            final double angle = Math.toRadians(17);
            controller.rotateRadians(angle, pivot);
            assertTrue(controller.canPreviewScaleAxes(
                    1.05, 0.95, angle, pivot),
                    mode + " rotated-axis preview");
            controller.scaleAxes(1.05, 0.95, angle, pivot);
            final AffineTransform2D rotatedResize = controller.state()
                    .preOutlineAtlasToPreview();
            final double axisDot = rotatedResize.m00()
                    * rotatedResize.m01()
                    + rotatedResize.m10() * rotatedResize.m11();
            assertEquals(0, axisDot, 1e-8,
                    mode + " resulting atlas axes must remain perpendicular");
        }
    }

    @Test
    void fullAndHalfUseJoinedPlacementBeforeMappedSeamLocalWarp() {
        for (final ReviewSectionMode mode : List.of(
                ReviewSectionMode.FULL, ReviewSectionMode.HALF)) {
            final AlignmentReviewBasis basis =
                    ReviewPluginFixtures.segmentedScaledAtlasBasis();
            final AlignmentReviewSession session =
                    new AlignmentReviewSession(basis);
            final ReviewController controller = new ReviewController(
                    session, ReviewPluginFixtures.segmentedPreview(),
                    new CatalogPlaneSource(false, false),
                    () -> new ReviewAcceptanceVerification(
                            basis.sourceSnapshot(), basis.atlas()),
                    Runnable::run, Runnable::run);
            controller.attach(new CapturingView());
            if (mode == ReviewSectionMode.HALF) {
                controller.setReviewSectionMode(mode);
                controller.setObservedHemisphere(
                        ObservedAnatomicalHemisphere.LEFT);
            }
            final Point2D leftAtlas = new Point2D(40, 30);
            final Point2D rightAtlas = new Point2D(400, 30);
            final Point2D leftBefore = controller.state()
                    .mapAtlasToPreview(leftAtlas);
            final Point2D rightBefore = controller.state()
                    .mapAtlasToPreview(rightAtlas);

            controller.translate(4, -2);
            controller.rotateRadians(Math.toRadians(1),
                    new Point2D(50, 40));
            controller.scaleUniform(1.01, new Point2D(50, 40));

            assertNotEquals(leftBefore,
                    controller.state().mapAtlasToPreview(leftAtlas));
            assertNotEquals(rightBefore,
                    controller.state().mapAtlasToPreview(rightAtlas));
            final double middle =
                    (basis.atlas().atlasPlaneWidth() - 1.0) * 0.5;
            final ManualHemisphereWarp2D.MidlineSegment expectedMidline =
                    new ManualHemisphereWarp2D.MidlineSegment(
                            controller.state().mapAtlasBeforeHemisphereWarp(
                                    new Point2D(middle, 0)),
                            controller.state().mapAtlasBeforeHemisphereWarp(
                                    new Point2D(middle,
                                            basis.atlas().atlasPlaneHeight()
                                                    - 1.0)));
            final long beforePoints = session.state().contentRevision();

            assertTrue(controller.startLocalWarpWithDefaultGridIfNeeded());

            assertEquals(beforePoints + 1,
                    session.state().contentRevision());
            assertEquals(expectedMidline, session.state().content()
                    .hemisphereWarp().orElseThrow().imageMidline());
            final long lockedRevision = session.state().contentRevision();
            final IllegalStateException locked = assertThrows(
                    IllegalStateException.class,
                    () -> controller.translate(1, 0));
            assertTrue(locked.getMessage().contains("Clear warp or Undo"));
            assertEquals(lockedRevision, session.state().contentRevision());
        }
    }

    @Test
    void halfPointSeedingRequiresOverlapWithPlacedAtlasSide() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.setReviewSectionMode(ReviewSectionMode.HALF);
        controller.setObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT);

        controller.translate(75, 0);
        assertTrue(controller.startLocalWarpWithDefaultGridIfNeeded());

        assertEquals(24, controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
        assertTrue(controls(session,
                ManualHemisphereWarp2D.AtlasSide.RIGHT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).isEmpty(),
                "an atlas side wholly beyond the placed tissue footprint must not receive dots");
    }

    @Test
    void disjoinedPointSeedingFollowsEachIndependentPlacedFootprint() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.setReviewSectionMode(ReviewSectionMode.DISJOINED);
        controller.translateManualSide(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 20, 0);
        controller.translateManualSide(
                ManualHemisphereWarp2D.AtlasSide.RIGHT, -20, 0);

        assertTrue(controller.startLocalWarpWithDefaultGridIfNeeded());

        final List<ManualWarpControl> left = controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID);
        final List<ManualWarpControl> right = controls(session,
                ManualHemisphereWarp2D.AtlasSide.RIGHT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID);
        assertEquals(24, left.size());
        assertEquals(24, right.size());
        assertTrue(left.stream().allMatch(control ->
                control.sourcePoint().x() >= 20
                        && control.sourcePoint().x() < 66));
        assertTrue(right.stream().allMatch(control ->
                control.sourcePoint().x() > 25
                        && control.sourcePoint().x() <= 72));
    }

    @Test
    void clearWarpPreservesDisjoinedCoarsePlacementAndReopensTransform() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.setReviewSectionMode(ReviewSectionMode.DISJOINED);
        controller.translateManualSide(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 12, -2);
        controller.translateManualSide(
                ManualHemisphereWarp2D.AtlasSide.RIGHT, -9, 3);
        final var placed = controller.state().content()
                .manualSidePlacement();

        assertTrue(controller.startLocalWarpWithDefaultGridIfNeeded());
        assertTrue(controller.state().content().hemisphereWarp().isPresent());
        controller.clearLocalWarp();

        assertTrue(controller.state().content().hemisphereWarp().isEmpty());
        assertEquals(placed,
                controller.state().content().manualSidePlacement(),
                "Clear warp must retain primary Disjoined placement");
        controller.translateManualSide(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 1, 0);

        controller.undo();
        assertTrue(controller.state().content().hemisphereWarp().isEmpty());
        assertEquals(placed,
                controller.state().content().manualSidePlacement());
        controller.undo();
        assertTrue(controller.state().content().hemisphereWarp().isPresent());
        assertEquals(placed,
                controller.state().content().manualSidePlacement());
        controller.redo();
        assertTrue(controller.state().content().hemisphereWarp().isEmpty());
        assertEquals(placed,
                controller.state().content().manualSidePlacement());
    }

    @Test
    void uprightPlacementIsUndoableAndStartupPlacementPassesSafetyGates() {
        final AlignmentReviewBasis basis = basisWithShearedProposal();
        ReviewController.requireSafeInitialManualPlacement(
                AlignmentReviewSession.forNewReview(basis).state());
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = uprightController(session);
        final var before = session.state().content();
        assertTrue(controller.canMakeAtlasUpright());
        controller.makeAtlasUpright();
        assertEquals(0, controller.state().preOutlineAtlasToPreview().m01(), 1e-12);
        assertEquals(0, controller.state().preOutlineAtlasToPreview().m10(), 1e-12);
        assertFalse(controller.canMakeAtlasUpright());
        final long revision = controller.state().contentRevision();
        controller.makeAtlasUpright();
        assertEquals(revision, controller.state().contentRevision());
        controller.undo();
        assertEquals(before, controller.state().content());
    }

    @Test
    void uprightPlacementCannotEscapeReachableWorkspace() {
        final AlignmentReviewSession session = new AlignmentReviewSession(basisWithShearedProposal());
        final ReviewController controller = uprightController(session);
        controller.translate(100, 0);
        final var before = session.state();
        assertFalse(controller.canMakeAtlasUpright());
        final ManualWarpException rejected = assertThrows(ManualWarpException.class,
                controller::makeAtlasUpright);
        assertEquals(ManualWarpFailureKind.OUTSIDE_WORKSPACE, rejected.kind());
        assertEquals(before, session.state());
    }

    @Test
    void uprightPlacementRetainsManualAdjustmentStretchLimits() {
        final AlignmentReviewSession session = new AlignmentReviewSession(basisWithShearedProposal());
        session.apply(new ReviewEdit.Scale(2.9, new Point2D(50, 40)));
        final ReviewController controller = uprightController(session);
        final var before = session.state();
        assertFalse(controller.canMakeAtlasUpright());
        final ManualWarpException rejected = assertThrows(ManualWarpException.class,
                controller::makeAtlasUpright);
        assertEquals(ManualWarpFailureKind.EXCESSIVE_STRETCH, rejected.kind());
        assertEquals(before, session.state());
    }

    private static ReviewController uprightController(final AlignmentReviewSession session) {
        final var basis = session.state().basis();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        return controller;
    }

    @Test
    void joinedPlacementRejectsUnsafeLiveAndCommittedMovesWithoutRevision() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());

        assertTrue(controller.canPreviewTranslate(35, 0),
                "a partly offscreen atlas must remain draggable back into view");
        assertFalse(controller.canPreviewTranslate(10_000, 0));
        final long revision = session.state().contentRevision();
        final ManualWarpException rejected = assertThrows(
                ManualWarpException.class,
                () -> controller.translate(10_000, 0));

        assertEquals(ManualWarpFailureKind.OUTSIDE_WORKSPACE,
                rejected.kind());
        assertEquals(revision, session.state().contentRevision());
        assertTrue(session.state().content().manualPreviewAdjustment()
                .equals(AlignmentReviewContent.identityPreviewAdjustment()));
    }

    @Test
    void coarsePlacementPreservesInheritedAffineShearInBothOrientations() {
        for (final AtlasOrientation orientation : List.of(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT)) {
            final AlignmentReviewBasis basis = basisWithShearedProposal();
            final AlignmentReviewSession session =
                    new AlignmentReviewSession(basis);
            final ReviewController controller = new ReviewController(
                    session, ReviewPluginFixtures.segmentedPreview(),
                    new CatalogPlaneSource(false, false),
                    () -> new ReviewAcceptanceVerification(
                            basis.sourceSnapshot(), basis.atlas()),
                    Runnable::run, Runnable::run);
            controller.attach(new CapturingView());
            controller.setOrientation(orientation);
            final double inheritedShear = atlasAxisShear(
                    controller.state().preOutlineAtlasToPreview());
            final long revision = controller.state().contentRevision();

            assertTrue(controller.canPreviewTranslate(1, 1),
                    orientation + " inherited proposal shear");
            controller.translate(1, 1);
            assertTrue(controller.canPreviewRotate(0.04,
                    new Point2D(50, 40)));
            controller.rotateRadians(0.04, new Point2D(50, 40));
            final AffineTransform2D rotated = controller.state()
                    .preOutlineAtlasToPreview();
            final double displayedAxis = Math.atan2(
                    rotated.m10(), rotated.m00());
            assertTrue(controller.canPreviewScaleAxes(
                    1.05, 0.95, displayedAxis, new Point2D(50, 40)));
            controller.scaleAxes(
                    1.05, 0.95, displayedAxis, new Point2D(50, 40));

            assertEquals(revision + 3,
                    controller.state().contentRevision());
            assertEquals(inheritedShear, atlasAxisShear(
                    controller.state().preOutlineAtlasToPreview()), 1e-9);
            assertFalse(controller.canPreviewScaleAxes(
                    1.05, 0.95, displayedAxis + 0.25,
                    new Point2D(50, 40)),
                    "off-axis scaling must not change inherited shear");
        }
    }

    @Test
    void disjoinedPlacementPreservesInheritedAffineShearPerSide() {
        final AlignmentReviewBasis basis = basisWithShearedProposal();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.setOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT);
        controller.setReviewSectionMode(ReviewSectionMode.DISJOINED);
        final ManualHemisphereWarp2D.AtlasSide side =
                ManualHemisphereWarp2D.AtlasSide.LEFT;
        final double inheritedShear = atlasAxisShear(
                placedSideTransform(controller, side));

        assertTrue(controller.canPreviewTranslateManualSide(side, 1, 1));
        controller.translateManualSide(side, 1, 1);
        final AffineTransform2D translated = placedSideTransform(
                controller, side);
        final double displayedAxis = Math.atan2(
                translated.m10(), translated.m00());
        assertTrue(controller.canPreviewScaleManualSideAxes(
                side, 1.04, 0.96, displayedAxis, new Point2D(45, 40)));
        controller.scaleManualSideAxes(
                side, 1.04, 0.96, displayedAxis, new Point2D(45, 40));

        assertEquals(inheritedShear,
                atlasAxisShear(placedSideTransform(controller, side)), 1e-9);
        assertFalse(controller.canPreviewScaleManualSideAxes(
                side, 1.04, 0.96, displayedAxis + 0.25,
                new Point2D(45, 40)),
                "off-axis side scaling must not change inherited shear");
    }

    @Test
    void disjoinedPlacementRejectsCompleteLossOfDetectedTissueIntersection() {
        final int width = 100;
        final int height = 80;
        final boolean[] pixels = new boolean[width * height];
        for (int y = 20; y <= 60; y++) {
            for (int x = 44; x <= 48; x++) {
                pixels[y * width + x] = true;
            }
        }
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasisWithMask(
                        BinaryMask.fromBooleans(width, height, pixels));
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.setReviewSectionMode(ReviewSectionMode.DISJOINED);
        final long safeRevision = controller.state().contentRevision();

        final ManualWarpException rejected = assertThrows(
                ManualWarpException.class,
                () -> controller.translateManualSide(
                        ManualHemisphereWarp2D.AtlasSide.LEFT, -7, 0));

        assertEquals(ManualWarpFailureKind.OUTSIDE_WORKSPACE,
                rejected.kind());
        assertTrue(rejected.getMessage().contains(
                "no longer overlaps the reviewed tissue crop"));
        assertEquals(safeRevision, controller.state().contentRevision(),
                "a rejected half placement must create no audit revision");
        assertTrue(controller.state().content().manualSidePlacement()
                .isIdentity());
    }

    @Test
    void disjoinedPlacementUsesEditedSupportInsteadOfOriginalSegmentation() {
        final int width = 100;
        final int height = 80;
        final BinaryMask original = rectangularMask(
                width, height, 44, 48, 20, 60);

        final AlignmentReviewBasis expandedBasis =
                ReviewPluginFixtures.scaledAtlasBasisWithMask(original);
        final AlignmentReviewSession expandedSession =
                new AlignmentReviewSession(expandedBasis);
        expandedSession.apply(new ReviewEdit.ReplaceReviewedTissueSupport(
                ReviewedTissueSupport.fromMask(rectangularMask(
                        width, height, 35, 40, 20, 60)), true));
        final ReviewController expandedController = new ReviewController(
                expandedSession, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        expandedBasis.sourceSnapshot(), expandedBasis.atlas()),
                Runnable::run, Runnable::run);
        expandedController.attach(new CapturingView());
        expandedController.setReviewSectionMode(ReviewSectionMode.DISJOINED);
        final long expandedRevision = expandedSession.state()
                .contentRevision();

        expandedController.translateManualSide(
                ManualHemisphereWarp2D.AtlasSide.LEFT, -7, 0);

        assertEquals(expandedRevision + 1,
                expandedSession.state().contentRevision(),
                "an expanded reviewed crop must permit its intersecting half placement");

        final AlignmentReviewBasis contractedBasis =
                ReviewPluginFixtures.scaledAtlasBasisWithMask(original);
        final AlignmentReviewSession contractedSession =
                new AlignmentReviewSession(contractedBasis);
        contractedSession.apply(new ReviewEdit.ReplaceReviewedTissueSupport(
                ReviewedTissueSupport.fromMask(rectangularMask(
                        width, height, 47, 48, 20, 60)), true));
        final ReviewController contractedController = new ReviewController(
                contractedSession, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        contractedBasis.sourceSnapshot(),
                        contractedBasis.atlas()),
                Runnable::run, Runnable::run);
        contractedController.attach(new CapturingView());
        contractedController.setReviewSectionMode(ReviewSectionMode.DISJOINED);
        final long contractedRevision = contractedSession.state()
                .contentRevision();

        final ManualWarpException rejected = assertThrows(
                ManualWarpException.class,
                () -> contractedController.translateManualSide(
                        ManualHemisphereWarp2D.AtlasSide.LEFT, -1, 0));

        assertEquals(ManualWarpFailureKind.OUTSIDE_WORKSPACE,
                rejected.kind());
        assertEquals(contractedRevision,
                contractedSession.state().contentRevision(),
                "a placement outside the contracted crop must create no revision");
    }

    @Test
    void singlePaneZoomAndFitToolbarActionsRemainReachable()
            throws Exception {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            final ReviewCanvas canvas = panel.canvas();
            canvas.setSize(820, 400);
            controller.attach(panel);
            final ScreenMapping tissueFit = canvas.sourceScreenMapping();
            final JButton zoomIn = findButton(panel, "Zoom +");
            final JButton fit = findButton(panel, "Fit");
            final JButton zoomOut = findButton(panel, "Zoom −");
            assertTrue(zoomIn.isEnabled());
            assertTrue(fit.isEnabled());
            assertTrue(zoomOut.isEnabled());

            zoomIn.doClick();
            assertNotEquals(tissueFit, canvas.sourceScreenMapping());
            fit.doClick();
            assertEquals(tissueFit, canvas.sourceScreenMapping());
        });
    }

    @Test
    void assistedBoundaryFitPreviewsWithoutRevisionAndAppliesAtomically() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final CapturingView view = new CapturingView();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(view);
        final long initialRevision = session.state().contentRevision();

        controller.startBoundaryFitMatching(BoundaryFitModel.SIMILARITY,
                ManualHemisphereWarp2D.AtlasSide.LEFT);

        assertEquals(initialRevision, session.state().contentRevision(),
                "Starting guided matching must not edit review history");
        assertTrue(controller.boundaryFitState().draft().isPresent());
        assertEquals(8, controller.boundaryFitState().draft()
                .orElseThrow().anchors().size());
        assertTrue(controller.boundaryFitState().candidate().isEmpty());

        completeBoundaryMatch(controller, 0);
        assertEquals(BoundaryFitPreviewKind.TRANSLATION,
                controller.boundaryFitState().preview().orElseThrow().kind());
        assertTrue(controller.boundaryFitState().candidate().isEmpty());
        completeBoundaryMatch(controller, 2);
        assertEquals(BoundaryFitPreviewKind.SIMILARITY,
                controller.boundaryFitState().preview().orElseThrow().kind());
        assertTrue(controller.boundaryFitState().candidate().isEmpty());
        completeBoundaryMatch(controller, 4);
        assertEquals(BoundaryFitPreviewKind.SIMILARITY,
                controller.boundaryFitState().preview().orElseThrow().kind());
        assertTrue(controller.boundaryFitState().candidate().isEmpty());
        completeBoundaryMatch(controller, 6);

        assertTrue(controller.boundaryFitState().candidate().isPresent());
        assertEquals(4, controller.boundaryFitState().candidate()
                .orElseThrow().includedMatchCount());

        controller.applyBoundaryFit();

        assertEquals(initialRevision + 1,
                session.state().contentRevision());
        assertEquals(ReviewOperation.APPLY_ASSISTED_BOUNDARY_FIT,
                session.auditTrail().get(0).operation());
        assertTrue(controller.boundaryFitState().candidate().isEmpty());
        final AffineTransform2D applied = session.state().content()
                .manualPreviewAdjustment();
        controller.undo();
        controller.redo();
        assertEquals(applied, session.state().content()
                .manualPreviewAdjustment());

        controller.startBoundaryFitMatching(BoundaryFitModel.SIMILARITY,
                ManualHemisphereWarp2D.AtlasSide.LEFT);
        assertTrue(controller.boundaryFitState().draft().isPresent());
        controller.setCoronalLevel(241);
        assertTrue(controller.boundaryFitState().draft().isEmpty());
        assertTrue(controller.boundaryFitState().candidate().isEmpty());
        assertTrue(controller.boundaryFitState().refitSuggested());
        assertEquals(applied, session.state().content()
                .manualPreviewAdjustment(),
                "Changing the plane must retain an applied coarse placement");
    }

    @Test
    void matchSkippingIsReversibleAdvancesAndEnforcesFourIncludedPairs() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.segmentedPreview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.startBoundaryFitMatching(BoundaryFitModel.SIMILARITY,
                ManualHemisphereWarp2D.AtlasSide.LEFT);
        final List<String> identifiers = controller.boundaryFitState().draft()
                .orElseThrow().anchors().stream()
                .map(anchor -> anchor.id()).toList();

        for (final String identifier : identifiers) {
            controller.setBoundaryFitAnchorIncluded(identifier, false);
        }
        assertTrue(controller.boundaryFitState().draft().orElseThrow()
                .activeAnchorId().isEmpty(),
                "skipping the final usable anchor must clear the prompt");
        assertEquals(0, controller.boundaryFitState().draft().orElseThrow()
                .includedCompletedCount());

        final int[] restored = {0, 2, 4, 6};
        for (int index = 0; index < restored.length; index++) {
            final int anchorIndex = restored[index];
            final String identifier = identifiers.get(anchorIndex);
            controller.setBoundaryFitAnchorIncluded(identifier, true);
            assertEquals(identifier, controller.boundaryFitState().draft()
                    .orElseThrow().activeAnchorId().orElseThrow());
            completeBoundaryMatch(controller, anchorIndex);
            assertEquals(index + 1, controller.boundaryFitState().draft()
                    .orElseThrow().includedCompletedCount());
            if (index < 3) {
                assertTrue(controller.boundaryFitState().candidate().isEmpty(),
                        "Apply needs four included distributed pairs");
            }
        }
        assertTrue(controller.boundaryFitState().candidate().isPresent());
        assertEquals(8, controller.boundaryFitState().draft().orElseThrow()
                .anchors().size(), "skipping must never delete anchors");
    }

    @Test
    void matchSkipButtonExcludesTheActiveAnchorAndAdvances() throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.segmentedPreview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            findNamed(panel, "workflowStep1", JButton.class).doClick();
            findNamed(panel, "suggestBoundaryFit", JButton.class).doClick();
            final var before = controller.boundaryFitState().draft()
                    .orElseThrow();
            final String first = before.activeAnchorId().orElseThrow();

            final JButton skip = findNamed(panel,
                    "toggleSelectedBoundaryFitMatch", JButton.class);
            assertEquals("Skip selected", skip.getText());
            assertTrue(skip.isEnabled());
            skip.doClick();

            final var after = controller.boundaryFitState().draft()
                    .orElseThrow();
            assertFalse(after.anchors().stream()
                    .filter(anchor -> anchor.id().equals(first))
                    .findFirst().orElseThrow().included(),
                    () -> "excluded=" + after.anchors().stream()
                            .filter(anchor -> !anchor.included())
                            .map(anchor -> anchor.id()).toList()
                            + ", active=" + after.activeAnchorId());
            assertNotEquals(first, after.activeAnchorId().orElseThrow());
        });
    }

    @Test
    void assistedBoundaryDraftDisablesPointsAndAcceptUntilResolved()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.segmentedPreview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JButton suggest = findNamed(panel,
                    "suggestBoundaryFit", JButton.class);
            final JButton place = findNamed(panel,
                    "workflowStep1", JButton.class);
            final JButton setup = findNamed(panel,
                    "workflowStep0", JButton.class);
            final JButton accept = findNamed(panel,
                    "persistentAccept", JButton.class);
            final JButton apply = findNamed(panel,
                    "applyBoundaryFit", JButton.class);
            final JButton cancel = findNamed(panel,
                    "cancelBoundaryFit", JButton.class);

            place.doClick();
            assertTrue(suggest.isEnabled());
            assertEquals("Start matching", suggest.getText());
            final long initialRevision = controller.state()
                    .contentRevision();
            assertFalse(controller.boundaryFitState().active(),
                    "entering optional Match must not create a draft");
            suggest.doClick();
            assertTrue(controller.boundaryFitState().active());
            assertEquals("Resume matching", suggest.getText());
            assertFalse(apply.isEnabled());
            assertTrue(cancel.isEnabled());
            assertFalse(accept.isEnabled());
            setup.doClick();
            assertTrue(place.isEnabled(),
                    "leaving Match pauses rather than discards its draft");
            assertTrue(controller.boundaryFitState().active());
            assertTrue(accept.isEnabled(),
                    "a paused preview does not alter accepted geometry");
            assertEquals(ReviewCanvas.InteractionTool.TRANSFORM,
                    panel.canvas().interactionTool(),
                    "Setup keeps manual placement available");

            place.doClick();
            assertFalse(accept.isEnabled());

            cancel.doClick();
            assertEquals(initialRevision,
                    controller.state().contentRevision(),
                    "explicitly exiting an unapplied matcher is not an edit");
            assertFalse(controller.boundaryFitState().active());
            assertTrue(accept.isEnabled());

            suggest.doClick();
            assertTrue(cancel.isEnabled());
            cancel.doClick();
            assertFalse(controller.boundaryFitState().active(),
                    "the dedicated Cancel action must remain available");
        });
    }

    @Test
    void leavingSetupDiscardsOnlyTheUnfinishedCropTrace() throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final var installed = session.state().content()
                    .reviewedTissueSupport();
            final long revision = session.state().contentRevision();
            panel.canvas().startTissueSupportTrace();
            assertTrue(panel.canvas().tissueSupportTracing());

            findNamed(panel, "workflowStep1", JButton.class).doClick();

            assertFalse(panel.canvas().tissueSupportTracing());
            assertTrue(panel.canvas().tissueSupportTracePoints().isEmpty());
            assertEquals(installed, session.state().content()
                    .reviewedTissueSupport());
            assertEquals(revision, session.state().contentRevision(),
                    "discarding an unfinished crop polygon is view state only");
        });
    }

    @Test
    void defaultNextBypassesAdvancedMatchAndSkipKeepsFinishedManualRois()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JButton setup = findNamed(panel,
                    "workflowStep0", JButton.class);
            final JButton match = findNamed(panel,
                    "workflowStep1", JButton.class);
            final JButton border = findNamed(panel,
                    "workflowStep2", JButton.class);
            final JButton structure = findNamed(panel,
                    "workflowStep4", JButton.class);
            final JButton acceptExport = findNamed(panel,
                    "workflowStep5", JButton.class);
            final JButton next = findNamed(panel,
                    "persistentNext", JButton.class);
            final JButton back = findNamed(panel,
                    "persistentBack", JButton.class);
            final JButton skip = findNamed(panel,
                    "persistentStageSkip", JButton.class);
            assertEquals("Match (Advanced)", match.getText());

            next.doClick();
            assertFalse(border.isEnabled(),
                    "Setup Next must bypass Advanced Match");
            assertTrue(match.isEnabled());
            assertTrue(controller.boundaryFitState().draft().isEmpty());
            back.doClick();
            assertFalse(setup.isEnabled(),
                    "Border Back must return directly to Setup");

            structure.doClick();
            final var roiSession = panel.manualRoiSessionForTests();
            roiSession.newPolygon("DG left", ReviewerRoiSide.LEFT,
                    RoiPartOperation.ADD);
            roiSession.addVertex(new Point2D(10, 10));
            roiSession.addVertex(new Point2D(40, 10));
            roiSession.addVertex(new Point2D(40, 40));
            roiSession.addVertex(new Point2D(10, 40));
            roiSession.finishActivePart();
            roiSession.newPolygon("unfinished", ReviewerRoiSide.LEFT,
                    RoiPartOperation.ADD);
            roiSession.addVertex(new Point2D(50, 50));
            roiSession.addVertex(new Point2D(60, 50));
            final AlignmentReviewContent installed = session.state()
                    .content();
            final long revision = session.state().contentRevision();
            assertEquals(2, roiSession.snapshot().rois().size());
            assertEquals("Skip Draw ROIs", skip.getText());

            skip.doClick();

            assertFalse(acceptExport.isEnabled());
            assertEquals(1, roiSession.snapshot().rois().size());
            assertEquals("DG left", roiSession.snapshot().rois()
                    .get(0).name());
            assertTrue(roiSession.snapshot().rois().get(0).finished());
            assertTrue(panel.canvas().manualRoiVisible(),
                    "finished ROIs must stay visible after changing stage");
            assertEquals(installed, session.state().content());
            assertEquals(revision, session.state().contentRevision(),
                    "manual ROI Skip must not create a scientific revision");
        });
    }

    @Test
    void acceptWhileCropTraceIsUnfinishedDiscardsTraceBeforeAcceptance()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final AlignmentReviewContent installed = session.state()
                    .content();
            final long revision = session.state().contentRevision();
            panel.canvas().startTissueSupportTrace();

            findNamed(panel, "persistentAccept", JButton.class).doClick();

            assertFalse(panel.canvas().tissueSupportTracing());
            assertEquals(installed, session.state().content());
            assertEquals(revision, session.state().contentRevision());
            assertTrue(controller.acceptedAlignment().isEmpty());
        });
    }

    @Test
    void disjoinedRoughPlacementStillAllowsGuidedFitOnOnlyActiveSide()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            controller.setReviewSectionMode(ReviewSectionMode.DISJOINED);
            controller.translateManualSide(
                    ManualHemisphereWarp2D.AtlasSide.LEFT, 2, -1);
            final var leftBefore = session.state().content()
                    .manualSidePlacement().atlasLeft();
            final var rightBefore = session.state().content()
                    .manualSidePlacement().atlasRight();
            final JButton match = findNamed(panel,
                    "suggestBoundaryFit", JButton.class);

            assertTrue(match.isEnabled(),
                    "rough side placement is coarse geometry, not a local warp");
            match.doClick();
            completeFourBoundaryMatches(controller,
                    new int[]{0, 1, 3, 5});
            final var candidate = controller.boundaryFitState()
                    .candidate().orElseThrow();
            final long revisionBeforeApply = session.state()
                    .contentRevision();

            controller.applyBoundaryFit();

            assertEquals(revisionBeforeApply + 1,
                    session.state().contentRevision());
            assertEquals(leftBefore.andThen(
                            candidate.previewCorrection()),
                    session.state().content().manualSidePlacement()
                            .atlasLeft());
            assertEquals(rightBefore,
                    session.state().content().manualSidePlacement()
                            .atlasRight(),
                    "guided fitting one Disjoined half must leave the other exact");
        });
    }

    @Test
    void failedGuidedFitKeepsEveryAnchorEditableAndCancellable() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.segmentedPreview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.startBoundaryFitMatching(BoundaryFitModel.ORTHOGONAL_XY,
                ManualHemisphereWarp2D.AtlasSide.LEFT);
        final BoundaryFitDraft initial = controller.boundaryFitState()
                .draft().orElseThrow();
        final var tissueBoundary = initial.request().tissueBoundary();
        final List<Point2D> extremeTissuePoints = List.of(
                tissueBoundary.stream().min(java.util.Comparator
                        .comparingDouble(sample -> sample.point().x()))
                        .orElseThrow().point(),
                tissueBoundary.stream().max(java.util.Comparator
                        .comparingDouble(sample -> sample.point().x()))
                        .orElseThrow().point(),
                tissueBoundary.stream().min(java.util.Comparator
                        .comparingDouble(sample -> sample.point().y()))
                        .orElseThrow().point(),
                tissueBoundary.stream().max(java.util.Comparator
                        .comparingDouble(sample -> sample.point().y()))
                        .orElseThrow().point());
        final var contentBefore = controller.state().content();
        final String sourceHash = controller.state().basis()
                .sourceSnapshot().pixelSha256();
        final int[] anchorIndices = {0, 1, 4, 5};
        for (int index = 0; index < anchorIndices.length; index++) {
            controller.setBoundaryFitTissuePoint(initial.anchors()
                    .get(anchorIndices[index]).id(),
                    extremeTissuePoints.get(index));
        }

        assertTrue(controller.boundaryFitState().draft().isPresent(),
                "an unsafe or insufficient solve must retain the draft");
        assertEquals(8, controller.boundaryFitState().draft()
                .orElseThrow().anchors().size());
        assertTrue(controller.boundaryFitState().candidate().isEmpty());
        assertTrue(controller.boundaryFitState().message().isPresent());
        assertTrue(controller.boundaryFitState().message().orElseThrow()
                .contains("Points kept"));
        assertEquals(BoundaryFitModel.ORTHOGONAL_XY,
                controller.boundaryFitState().draft().orElseThrow()
                        .request().model(),
                "unsafe point placement must not force separate X/Y fitting off");
        assertEquals(4, controller.boundaryFitState().draft().orElseThrow()
                .includedCompletedCount());
        assertEquals(contentBefore, controller.state().content(),
                "transient border points must not alter evidence, confidence, or review geometry");
        assertEquals(sourceHash, controller.state().basis()
                .sourceSnapshot().pixelSha256(),
                "guided matching must not alter source pixels");

        controller.cancelBoundaryFit();

        assertFalse(controller.boundaryFitState().active());
        assertEquals(0, controller.state().contentRevision());
    }

    @Test
    void finiteOffCyanEndpointsRemainExactTransientReviewerGeometry() {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        final AlignmentReviewContent installed = session.state().content();
        final String sourceHash = basis.sourceSnapshot().pixelSha256();

        controller.startBoundaryFitMatching(BoundaryFitModel.SIMILARITY,
                ManualHemisphereWarp2D.AtlasSide.LEFT);
        final String anchorId = controller.boundaryFitState().draft()
                .orElseThrow().activeAnchor().orElseThrow().id();
        final Point2D freeMatch = new Point2D(98.25, 77.75);
        controller.setBoundaryFitTissuePoint(anchorId, freeMatch);

        assertEquals(freeMatch, controller.boundaryFitState().draft()
                .orElseThrow().anchors().stream()
                .filter(anchor -> anchor.id().equals(anchorId))
                .findFirst().orElseThrow().tissuePreviewPoint()
                .orElseThrow(),
                "controller endpoints are exact; cyan snapping is only a canvas affordance");
        controller.cancelBoundaryFit();

        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 4);
        final String pairId = controller.boundaryWarpState().draft()
                .orElseThrow().matches().get(0).id();
        final Point2D freeBorder = new Point2D(97.125, 76.375);
        controller.completeBoundaryWarpMatch(pairId, freeBorder);

        assertEquals(freeBorder, controller.boundaryWarpState().draft()
                .orElseThrow().matches().stream()
                .filter(match -> match.id().equals(pairId))
                .findFirst().orElseThrow().tissuePreviewPoint(),
                "an unsafe or torn-edge request must remain editable at its exact finite endpoint");
        assertEquals(installed, session.state().content(),
                "off-cyan geometry must not edit the installed cyan crop or export clipping");
        assertEquals(0, session.state().contentRevision());
        assertEquals(sourceHash, basis.sourceSnapshot().pixelSha256());
    }

    @Test
    void explicitMatchAndBorderSkipsDiscardEmptyActiveAndPausedDraftsWithoutRevision()
            throws Exception {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final AlignmentReviewContent installed = session.state().content();
            final JButton setup = findNamed(panel,
                    "workflowStep0", JButton.class);
            final JButton match = findNamed(panel,
                    "workflowStep1", JButton.class);
            final JButton border = findNamed(panel,
                    "workflowStep2", JButton.class);
            final JButton interior = findNamed(panel,
                    "workflowStep3", JButton.class);
            final JButton structure = findNamed(panel,
                    "workflowStep4", JButton.class);
            final JButton startMatch = findNamed(panel,
                    "suggestBoundaryFit", JButton.class);
            final JButton skipMatch = findNamed(panel,
                    "skipBoundaryFitStage", JButton.class);
            final JButton prepareBorder = findNamed(panel,
                    "suggestBoundaryWarpPairs", JButton.class);
            final JButton skipBorder = findNamed(panel,
                    "skipBoundaryWarpStage", JButton.class);
            final JButton skipInterior = findNamed(panel,
                    "skipInteriorStage", JButton.class);

            match.doClick();
            skipMatch.doClick();
            assertFalse(border.isEnabled());
            skipBorder.doClick();
            assertFalse(interior.isEnabled());
            skipInterior.doClick();
            assertFalse(structure.isEnabled());
            assertEquals(0, session.state().contentRevision(),
                    "skipping empty optional stages is navigation only");

            match.doClick();
            startMatch.doClick();
            assertTrue(controller.boundaryFitState().active());
            setup.doClick();
            assertTrue(controller.boundaryFitState().active(),
                    "ordinary navigation pauses the Match draft");
            match.doClick();
            skipMatch.doClick();
            assertFalse(controller.boundaryFitState().active());
            assertFalse(border.isEnabled());

            prepareBorder.doClick();
            assertTrue(controller.boundaryWarpState().active());
            setup.doClick();
            assertTrue(controller.boundaryWarpState().active(),
                    "ordinary navigation pauses the Border draft");
            border.doClick();
            skipBorder.doClick();

            assertFalse(controller.boundaryWarpState().active());
            assertFalse(interior.isEnabled());
            assertEquals(installed, session.state().content());
            assertEquals(0, session.state().contentRevision(),
                    "explicit Skip preserves installed placement and warp exactly");
        });
    }

    @Test
    void borderInteriorBorderPausesExactUnappliedGhostAndKeepsAppliedWarp()
            throws Exception {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JButton border = findNamed(panel,
                    "workflowStep2", JButton.class);
            final JButton interior = findNamed(panel,
                    "workflowStep3", JButton.class);
            final JButton structure = findNamed(panel,
                    "workflowStep4", JButton.class);
            final JButton prepare = findNamed(panel,
                    "suggestBoundaryWarpPairs", JButton.class);

            border.doClick();
            prepare.doClick();
            controller.calculateBoundaryWarpPreview();
            final BoundaryWarpViewState paused =
                    controller.boundaryWarpState();
            assertTrue(paused.draft().isPresent());
            assertTrue(paused.preview().isPresent());
            assertTrue(paused.candidate().isPresent(),
                    paused.message().orElse(""));
            final AlignmentReviewContent beforeApply =
                    session.state().content();
            final long beforeRevision =
                    session.state().contentRevision();
            assertTrue(panel.canvas()
                    .boundaryWarpPreviewVisibleForTests());

            interior.doClick();

            assertSame(paused, controller.boundaryWarpState(),
                    "ordinary Next must pause the exact draft, requested "
                            + "preview, audited endpoints, and candidate");
            assertEquals(beforeApply, session.state().content());
            assertEquals(beforeRevision,
                    session.state().contentRevision());
            assertFalse(panel.canvas()
                    .boundaryWarpPreviewVisibleForTests(),
                    "Interior shows only installed geometry");
            assertTrue(panel.compactStatusTextForTests()
                    .contains("not installed"));

            border.doClick();
            assertSame(paused, controller.boundaryWarpState());
            assertTrue(panel.canvas()
                    .boundaryWarpPreviewVisibleForTests());
            assertEquals(paused.draft().orElseThrow().matches(),
                    controller.boundaryWarpState().draft().orElseThrow()
                            .matches(),
                    "all requested endpoints must restore exactly");
            assertEquals(paused.candidate().orElseThrow().auditedMatches(),
                    controller.boundaryWarpState().candidate().orElseThrow()
                            .auditedMatches(),
                    "all safe endpoints must restore exactly");

            controller.applyBoundaryWarp();
            final AlignmentReviewContent installed =
                    session.state().content();
            final long installedRevision =
                    session.state().contentRevision();
            final String installedHash = installed.hemisphereWarp()
                    .orElseThrow().diagnostics().contentSha256();
            assertEquals(beforeRevision + 1, installedRevision,
                    "Apply installs exactly one scientific revision");

            interior.doClick();
            assertEquals(installed, session.state().content());
            assertEquals(installedRevision,
                    session.state().contentRevision());
            assertEquals(installedHash, session.state().content()
                    .hemisphereWarp().orElseThrow().diagnostics()
                    .contentSha256());
            structure.doClick();
            assertEquals(installed, session.state().content());
            assertEquals(installedRevision,
                    session.state().contentRevision(),
                    "Interior and Structure render the same installed field "
                            + "without adding a revision");
        });
    }

    @Test
    void manualBoundaryDraftIsTransientAppliesOnceAndPointsRetainItsGroup() {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        final long initialRevision = session.state().contentRevision();

        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 4);
        assertTrue(controller.boundaryWarpState().draft().isPresent());
        assertEquals(4, controller.boundaryWarpState().draft().orElseThrow()
                .matches().size(),
                "the selected Border density publishes an editable draft");
        assertEquals(initialRevision, session.state().contentRevision());
        assertTrue(controller.boundaryWarpState().candidate().isEmpty(),
                "preparing points performs no nonlinear solve");
        controller.calculateBoundaryWarpPreview();
        assertTrue(controller.boundaryWarpState().candidate().isPresent(),
                controller.boundaryWarpState().message().orElse(""));
        assertEquals(initialRevision, session.state().contentRevision(),
                "a nonlinear ghost must not enter review history");
        controller.applyBoundaryWarp();

        assertEquals(initialRevision + 1,
                session.state().contentRevision());
        assertEquals(ReviewOperation.APPLY_MANUAL_BOUNDARY_WARP,
                session.auditTrail().get(0).operation());
        final ManualHemisphereWarp2D borderWarp = session.state().content()
                .hemisphereWarp().orElseThrow();
        final String borderFieldHash = borderWarp.diagnostics()
                .contentSha256();
        final List<Point2D> borderPreservationSamples =
                borderWarp.manualControls(
                        ManualHemisphereWarp2D.AtlasSide.LEFT).stream()
                        .map(ManualWarpControl::sourcePoint).toList();
        final int borderCount = controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR).size();
        assertTrue(borderCount >= 4 && borderCount <= 12);

        controller.setCoronalLevel(241);
        assertEquals(borderFieldHash, session.state().content()
                .hemisphereWarp().orElseThrow().diagnostics()
                .contentSha256(),
                "plane browsing must retain the tissue-space border field");
        assertTrue(controller.boundaryWarpState().recheckSuggested());

        assertTrue(controller.startLocalWarpWithDefaultGridIfNeeded());
        assertEquals(borderCount, controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR).size(),
                "choosing Points must preserve the applied border group");
        assertEquals(24, controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
        assertEquals(24, controls(session,
                ManualHemisphereWarp2D.AtlasSide.RIGHT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
        final ManualHemisphereWarp2D withPointsWarp = session.state()
                .content().hemisphereWarp().orElseThrow();
        for (final Point2D sample : borderPreservationSamples) {
            assertPoint(borderWarp.apply(
                            ManualHemisphereWarp2D.AtlasSide.LEFT, sample),
                    withPointsWarp.apply(
                            ManualHemisphereWarp2D.AtlasSide.LEFT, sample));
        }
        final AlignmentReviewContent withPoints = session.state().content();
        controller.undo();
        assertTrue(controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).isEmpty());
        assertEquals(borderCount, controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR).size());
        controller.redo();
        assertEquals(withPoints, session.state().content());
    }

    @Test
    void boundaryPairExclusionIsVisibleReversibleAndEnforcesFourIncludedPairs() {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 4);
        final var original = controller.boundaryWarpState().draft()
                .orElseThrow();
        final var selected = original.matches().get(1);
        assertTrue(controller.boundaryWarpState().candidate().isEmpty());

        controller.setBoundaryWarpMatchIncluded(selected.id(), false);

        final var excluded = controller.boundaryWarpState().draft()
                .orElseThrow().matches().stream()
                .filter(match -> match.id().equals(selected.id()))
                .findFirst().orElseThrow();
        assertFalse(excluded.included());
        assertEquals(selected.atlasPreviewPoint(),
                excluded.atlasPreviewPoint());
        assertEquals(selected.tissuePreviewPoint(),
                excluded.tissuePreviewPoint());
        assertTrue(controller.boundaryWarpState().candidate().isEmpty(),
                "three included border pairs cannot be applied");

        final Point2D editedTissue = original.tissueBoundary().stream()
                .map(sample -> sample.point())
                .filter(point -> !point.equals(selected.tissuePreviewPoint()))
                .findFirst().orElseThrow();
        controller.completeBoundaryWarpMatch(selected.id(), editedTissue);
        final var editedExcluded = controller.boundaryWarpState().draft()
                .orElseThrow().matches().stream()
                .filter(match -> match.id().equals(selected.id()))
                .findFirst().orElseThrow();
        assertFalse(editedExcluded.included(),
                "editing an excluded pair must not silently re-include it");
        assertEquals(editedTissue, editedExcluded.tissuePreviewPoint());

        controller.setBoundaryWarpMatchIncluded(selected.id(), true);

        final var restored = controller.boundaryWarpState().draft()
                .orElseThrow().matches().stream()
                .filter(match -> match.id().equals(selected.id()))
                .findFirst().orElseThrow();
        assertTrue(restored.included());
        assertEquals(selected.atlasPreviewPoint(),
                restored.atlasPreviewPoint());
        assertEquals(editedTissue,
                restored.tissuePreviewPoint());
        assertTrue(controller.boundaryWarpState().dirty());
        controller.calculateBoundaryWarpPreview();
        assertTrue(controller.boundaryWarpState().candidate().isPresent(),
                controller.boundaryWarpState().message().orElse(""));
        assertEquals(0, controller.state().contentRevision(),
                "exclude/include remains transient draft state");
    }

    @Test
    void halfBoundaryAndInteriorUseOnlyVisibleSideUntilRemnantOptIn() {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.setReviewSectionMode(ReviewSectionMode.HALF);
        controller.setObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT);

        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 4);

        assertEquals(ManualHemisphereWarp2D.AtlasSide.LEFT,
                controller.boundaryWarpState().draft().orElseThrow()
                        .targetSide());
        assertTrue(controller.boundaryWarpState().secondaryDraft().isEmpty(),
                "strict Half must not publish hidden-side border points");
        assertTrue(controller.boundaryWarpOppositeRemnantAvailable(),
                "the fixture intentionally contains a distributed remnant");
        final IllegalArgumentException hiddenSide = assertThrows(
                IllegalArgumentException.class,
                () -> controller.replaceInteriorGridControls(4,
                        ManualHemisphereWarp2D.AtlasSide.RIGHT));
        assertTrue(hiddenSide.getMessage().contains("confirmed visible"));

        controller.cancelBoundaryWarp();
        assertTrue(controller.startLocalWarpWithDefaultGridIfNeeded());
        assertEquals(24, controls(session,
                ManualHemisphereWarp2D.AtlasSide.LEFT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
        assertEquals(0, controls(session,
                ManualHemisphereWarp2D.AtlasSide.RIGHT,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
        controller.undo();

        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 4);
        controller.setHalfAtlasCoverage(
                org.atlasalign.application.HalfAtlasCoverage
                        .INCLUDE_OPPOSITE_REMNANT);
        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 4);

        assertTrue(controller.boundaryWarpState().secondaryDraft().isPresent(),
                "eligible opposite-side controls require explicit persisted opt-in");
    }

    @Test
    void halfRemnantEligibilityExpiresAfterPlaneOrCropRevision() {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.setReviewSectionMode(ReviewSectionMode.HALF);
        controller.setObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT);

        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 4);
        assertTrue(controller.boundaryWarpOppositeRemnantAvailable());

        controller.setCoronalLevel(241);

        assertFalse(controller.boundaryWarpOppositeRemnantAvailable(),
                "a plane revision must expire remnant eligibility");
        assertThrows(IllegalStateException.class, () ->
                controller.setHalfAtlasCoverage(
                        HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT));

        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 4);
        assertTrue(controller.boundaryWarpOppositeRemnantAvailable());
        final var support = session.state().content()
                .reviewedTissueSupport().orElseThrow();
        final var control = support.controls().get(0);
        controller.moveTissueSupportControl(control.id(), new Point2D(
                control.point().x() + 0.25, control.point().y()));

        assertFalse(controller.boundaryWarpOppositeRemnantAvailable(),
                "a tissue-support revision must expire remnant eligibility");
        assertThrows(IllegalStateException.class, () ->
                controller.setHalfAtlasCoverage(
                        HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT));
    }

    @Test
    void halfBulkSeedersRespectStrictAndOptInSidesUnderBothOrientations() {
        for (final AtlasOrientation orientation : List.of(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT)) {
            assertHalfBulkSeederSides(orientation, false);
            assertHalfBulkSeederSides(orientation, true);
        }
    }

    @Test
    void failedInteriorSeedRepublishesTheUnchangedReviewModel() {
        final AlignmentReviewBasis basis = ReviewPluginFixtures
                .segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        final CapturingView view = new CapturingView();
        controller.attach(view);
        final AlignmentReviewContent before = session.state().content();
        final int modelsBefore = view.models.size();

        controller.reportManualWarpFailure(
                new IllegalStateException("synthetic audited seed failure"));

        assertTrue(view.lastError.contains("Manual warp not applied"),
                "the audited seed failure must remain actionable");
        assertEquals(before, session.state().content());
        assertTrue(view.models.size() > modelsBefore,
                "an off-thread failure must republish the unchanged model so the UI leaves its Preparing state");
    }

    @Test
    void inactiveBoundarySideAcceptsReleaseWhileItsFirstSolveIsPending() {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final QueuedExecutor warpWorker = new QueuedExecutor();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, warpWorker, Runnable::run, () -> { });
        controller.attach(new CapturingView());

        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 6);
        warpWorker.run(0);
        controller.activateBoundaryWarpSide(
                ManualHemisphereWarp2D.AtlasSide.RIGHT);
        assertFalse(controller.boundaryWarpState().loading(),
                "activating the other side is a draft-only operation");
        final var pending = controller.boundaryWarpState().draft()
                .orElseThrow();
        final var match = pending.matches().get(0);
        final Point2D replacement = pending.tissueBoundary()
                .get(pending.tissueBoundary().size() / 2).point();

        controller.moveBoundaryWarpMatch(match.id(),
                ReviewController.BoundaryFitEndpoint.TISSUE,
                replacement);
        final String ignoredId = pending.matches().get(1).id();
        final String deletedId = pending.matches().get(2).id();
        controller.setBoundaryWarpMatchIncluded(ignoredId, false);
        controller.removeBoundaryWarpMatch(deletedId);

        assertFalse(controller.boundaryWarpState().loading(),
                "draft edits must not invoke the nonlinear solver");
        assertEquals(replacement, controller.boundaryWarpState().draft()
                .orElseThrow().matches().get(0).tissuePreviewPoint(),
                "the release edit must replace the pending activation solve");
        assertFalse(controller.boundaryWarpState().draft().orElseThrow()
                .matches().stream().filter(value -> value.id().equals(
                        ignoredId)).findFirst().orElseThrow().included());
        assertTrue(controller.boundaryWarpState().draft().orElseThrow()
                .matches().stream().noneMatch(value -> value.id().equals(
                        deletedId)));
        assertEquals(3, controller.boundaryWarpState().primary()
                .pendingEditCount());
        assertEquals(1, warpWorker.tasks.size(),
                "only initial point preparation has run so far");
        controller.calculateBoundaryWarpPreview();
        assertTrue(controller.boundaryWarpState().loading());
        assertEquals(2, warpWorker.tasks.size());
        warpWorker.run(1);
        assertFalse(controller.boundaryWarpState().loading());
        assertEquals(replacement, controller.boundaryWarpState().draft()
                .orElseThrow().matches().get(0).tissuePreviewPoint());
        assertEquals(0, controller.state().contentRevision(),
                "calculating a Border preview must create no revision");

        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.RIGHT, 6);
        assertEquals(3, warpWorker.tasks.size());
        warpWorker.run(2);
        assertEquals(6, controller.boundaryWarpState().draft()
                .orElseThrow().matches().size(),
                "Re-suggest must restore the generated point set");
    }

    @Test
    void unchangedBoundarySnapIsAQuietNoOp() {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 4);
        final BoundaryWarpViewState before = controller.boundaryWarpState();
        final var match = before.draft().orElseThrow().matches().get(0);

        assertDoesNotThrow(() -> controller.moveBoundaryWarpMatch(
                match.id(), ReviewController.BoundaryFitEndpoint.TISSUE,
                match.tissuePreviewPoint()));
        assertDoesNotThrow(() -> controller.completeBoundaryWarpMatch(
                match.id(), match.tissuePreviewPoint()));

        assertSame(before, controller.boundaryWarpState(),
                "an unchanged snapped release must not start a new solve or publish an error");
    }

    @Test
    void rejectedBorderCalculationKeepsPairsButDoesNotDisplayAnUnauditedGhost() {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final var session = new AlignmentReviewSession(basis);
        final var controller = new ReviewController(session,
                ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.startBoundaryWarp(ManualHemisphereWarp2D.AtlasSide.LEFT, 4);
        final var prepared = controller.boundaryWarpState().draft().orElseThrow();
        controller.moveBoundaryWarpMatch(prepared.matches().get(1).id(),
                ReviewController.BoundaryFitEndpoint.ATLAS,
                prepared.matches().get(0).atlasPreviewPoint());
        final var draft = controller.boundaryWarpState().draft().orElseThrow();
        final var before = session.state();

        controller.calculateBoundaryWarpPreview();

        final var rejected = controller.boundaryWarpState();
        assertEquals(draft, rejected.draft().orElseThrow());
        assertTrue(rejected.candidate().isEmpty());
        assertTrue(rejected.preview().isEmpty(),
                "a failed calculation must not display a plausible but unaudited requested warp");
        assertTrue(rejected.safetyReport().isPresent());
        assertThrows(IllegalStateException.class, controller::applyBoundaryWarp);
        assertEquals(before, session.state());
    }

    @Test
    void partialBoundaryApplyRebasesOnTheExactInstalledControls() {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 4);
        final var initial = controller.boundaryWarpState().draft()
                .orElseThrow();
        controller.moveBoundaryWarpMatch(initial.matches().get(0).id(),
                ReviewController.BoundaryFitEndpoint.TISSUE,
                new Point2D(98.0, 78.0));
        controller.calculateBoundaryWarpPreview();
        final var candidate = controller.boundaryWarpState().candidate()
                .orElseThrow(() -> new AssertionError(controller
                        .boundaryWarpState().message().orElse("")));
        assertFalse(candidate.completesRequestedWarp(),
                "the deliberately large finite request must exercise the safe-step rebase");
        final var installedById = candidate.replacementControls().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ManualWarpControl::id,
                        java.util.function.Function.identity()));

        controller.applyBoundaryWarp();

        final var remainder = controller.boundaryWarpState().draft()
                .orElseThrow();
        assertEquals(candidate.matches().stream()
                        .map(match -> match.tissuePreviewPoint()).toList(),
                remainder.matches().stream()
                        .map(match -> match.tissuePreviewPoint()).toList(),
                "the remaining reviewer-requested tissue endpoints stay exact while atlas anchors rebase to the installed exterior");
        for (final var baseline : remainder.baselinePoints()) {
            final ManualWarpControl installed = installedById.get(
                    baseline.controlId());
            if (installed == null) {
                continue;
            }
            assertEquals(installed.sourcePoint(), baseline.sourcePoint());
            assertEquals(installed.targetPoint(), baseline.targetPoint(),
                    "the next pass must start at the exact installed safe endpoint");
        }
        assertEquals(1, session.state().contentRevision(),
                "one safe step installs exactly one review revision");
    }

    @Test
    void applyingOneBoundarySideRebasesAndPreservesOtherSideEdits() {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT, 4);
        controller.calculateBoundaryWarpPreview();
        assertTrue(controller.boundaryWarpState().candidate().isPresent());

        controller.activateBoundaryWarpSide(
                ManualHemisphereWarp2D.AtlasSide.RIGHT);
        final var right = controller.boundaryWarpState().draft()
                .orElseThrow();
        final Point2D replacement = right.tissueBoundary()
                .get(right.tissueBoundary().size() / 3).point();
        controller.moveBoundaryWarpMatch(right.matches().get(0).id(),
                ReviewController.BoundaryFitEndpoint.TISSUE,
                replacement);
        final List<org.atlasalign.application.manual.BoundaryFitMatch>
                editedRight = controller.boundaryWarpState().draft()
                        .orElseThrow().matches();
        controller.activateBoundaryWarpSide(
                ManualHemisphereWarp2D.AtlasSide.LEFT);
        assertTrue(controller.boundaryWarpState().candidate().isPresent());

        controller.applyBoundaryWarp();

        assertEquals(1, session.state().contentRevision());
        assertEquals(ManualHemisphereWarp2D.AtlasSide.RIGHT,
                controller.boundaryWarpState().draft().orElseThrow()
                        .targetSide());
        assertEquals(editedRight, controller.boundaryWarpState().draft()
                .orElseThrow().matches(),
                "applying the left side must preserve every edited right-side draft point");
    }

    @Test
    void staleManualBoundaryStartInstallsNothing() {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final QueuedExecutor warpWorker = new QueuedExecutor();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, warpWorker, Runnable::run, () -> { });
        controller.attach(new CapturingView());

        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT);
        assertEquals(1, warpWorker.tasks.size());
        controller.setCoronalLevel(241);
        warpWorker.run(0);

        assertTrue(controller.boundaryWarpState().draft().isEmpty());
        assertTrue(controller.boundaryWarpState().recheckSuggested());
        assertTrue(session.state().content().hemisphereWarp().isEmpty());
        assertEquals(1, session.auditTrail().size(),
                "only the newer plane edit may be audited");
    }

    @Test
    void staleManualBoundarySolveInstallsNoCandidateOrRevision() {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final QueuedExecutor warpWorker = new QueuedExecutor();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, warpWorker, Runnable::run, () -> { });
        controller.attach(new CapturingView());

        controller.startBoundaryWarp(
                ManualHemisphereWarp2D.AtlasSide.LEFT);
        warpWorker.run(0);
        assertTrue(controller.boundaryWarpState().draft().isPresent());
        controller.calculateBoundaryWarpPreview();
        assertEquals(2, warpWorker.tasks.size());
        assertTrue(controller.boundaryWarpState().loading());

        controller.setCoronalLevel(241);
        final AlignmentReviewContent changed = session.state().content();
        final int auditSize = session.auditTrail().size();
        warpWorker.run(1);

        assertEquals(changed, session.state().content());
        assertEquals(auditSize, session.auditTrail().size());
        assertTrue(controller.boundaryWarpState().candidate().isEmpty());
        assertTrue(controller.boundaryWarpState().draft().isEmpty());
        assertTrue(controller.boundaryWarpState().recheckSuggested());
    }

    @Test
    void borderToolUsesCompactSingleWindowControlsAndBlocksAcceptWhileDrafted()
            throws Exception {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JButton border = findNamed(panel,
                    "workflowStep2", JButton.class);
            final JButton setup = findNamed(panel,
                    "workflowStep0", JButton.class);
            final JButton accept = findNamed(panel,
                    "persistentAccept", JButton.class);
            final JButton suggest = findNamed(panel,
                    "suggestBoundaryWarpPairs", JButton.class);
            final JButton cancel = findNamed(panel,
                    "cancelBoundaryWarp", JButton.class);

            assertEquals("1 Border", border.getText());
            assertTrue(border.isEnabled());
            border.doClick();
            assertFalse(border.isEnabled(),
                    "the current wizard stage is visibly selected");
            assertTrue(controller.boundaryWarpState().draft().isEmpty(),
                    "entering optional Border must not create a draft");
            suggest.doClick();
            assertTrue(controller.boundaryWarpState().draft().isPresent());
            assertTrue(suggest.isEnabled());
            assertTrue(cancel.isEnabled());
            assertFalse(accept.isEnabled());
            setup.doClick();
            assertTrue(border.isEnabled(),
                    "leaving Border pauses rather than discards its draft");
            assertTrue(controller.boundaryWarpState().draft().isPresent());
            assertTrue(accept.isEnabled());

            border.doClick();
            assertFalse(border.isEnabled());

            cancel.doClick();
            assertFalse(controller.boundaryWarpState().active());
            assertTrue(setup.isEnabled());
            assertTrue(accept.isEnabled());
        });
    }

    @Test
    void assistedBoundarySolveRunsOffEdtAndStaleCompletionIsDiscarded()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<Boolean> ranOnEdt = new AtomicReference<>();
        final Executor worker = command -> {
            final Thread thread = new Thread(() -> {
                try {
                    ranOnEdt.set(SwingUtilities.isEventDispatchThread());
                    command.run();
                } finally {
                    completed.countDown();
                }
            }, "boundary-fit-test-worker");
            thread.setDaemon(true);
            thread.start();
        };
        final ReviewController background = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, worker, Runnable::run, () -> { });
        background.attach(new CapturingView());

        SwingUtilities.invokeAndWait(() -> background
                .startBoundaryFitMatching(
                BoundaryFitModel.SIMILARITY,
                ManualHemisphereWarp2D.AtlasSide.LEFT));

        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(Boolean.FALSE, ranOnEdt.get());
        assertTrue(background.boundaryFitState().draft().isPresent());
        assertTrue(background.boundaryFitState().candidate().isEmpty());

        final AlignmentReviewSession staleSession =
                new AlignmentReviewSession(basis);
        final java.util.ArrayDeque<Runnable> queued =
                new java.util.ArrayDeque<>();
        final ReviewController stale = new ReviewController(
                staleSession, ReviewPluginFixtures.segmentedPreview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, queued::addLast, Runnable::run, () -> { });
        stale.attach(new CapturingView());
        stale.startBoundaryFitMatching(BoundaryFitModel.SIMILARITY,
                ManualHemisphereWarp2D.AtlasSide.LEFT);
        stale.translate(1, 0);
        final long changedRevision = staleSession.state().contentRevision();

        queued.removeFirst().run();

        assertEquals(changedRevision,
                staleSession.state().contentRevision());
        assertTrue(stale.boundaryFitState().draft().isEmpty());
        assertTrue(stale.boundaryFitState().refitSuggested());
    }

    @Test
    void persistentDisplayControlsDoNotCreateReviewRevisions()
            throws Exception {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final long revision = session.state().contentRevision();
            final JButton view = findButton(panel, "View ▾");
            final javax.swing.JPopupMenu popup = view.getComponentPopupMenu();
            final JCheckBox labels = findNamed(popup,
                    "displayPointLabels", JCheckBox.class);
            final JCheckBox grid = findNamed(popup,
                    "displayWarpGrid", JCheckBox.class);
            final JSlider opacity = findNamed(panel,
                    "displayOverlayOpacity", JSlider.class);
            final JComboBox<?> gridCount = findNamed(panel,
                    "gridControlDensity", JComboBox.class);
            final JComboBox<?> structureCount = findNamed(panel,
                    "structureControlDensity", JComboBox.class);

            assertFalse(labels.isSelected());
            assertFalse(grid.isSelected());
            assertEquals(48, gridCount.getSelectedItem());
            assertEquals(48, structureCount.getSelectedItem());
            assertEquals(List.of(4, 6, 8, 12, 16, 24, 32, 48, 64),
                    java.util.stream.IntStream.range(0,
                                    gridCount.getItemCount())
                            .mapToObj(gridCount::getItemAt).toList());
            assertTrue(findNamed(panel, "persistentUndo", JButton.class)
                    .isVisible());
            assertTrue(findNamed(panel, "persistentRedo", JButton.class)
                    .isVisible());
            assertTrue(findNamed(panel, "persistentReset", JButton.class)
                    .isVisible());
            assertTrue(findNamed(panel, "persistentClearWarp", JButton.class)
                    .isVisible());
            assertTrue(findNamed(panel, "persistentBack", JButton.class)
                    .isVisible());
            assertTrue(findNamed(panel, "persistentAccept", JButton.class)
                    .isVisible());
            labels.doClick();
            grid.doClick();
            opacity.setValue(40);
            assertTrue(panel.canvas().landmarkLabelsVisible());
            assertTrue(panel.canvas().deformationGridVisible());
            assertEquals(0.4, panel.canvas().overlayOpacity(), 1e-6);
            assertEquals(revision, session.state().contentRevision());
        });
    }

    @Test
    void reviewStartsInSetupAndInteriorWaitsForExplicitGridAction()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JButton setup = findNamed(panel,
                    "workflowStep0", JButton.class);
            final JButton place = findNamed(panel,
                    "workflowStep1", JButton.class);
            final JButton interior = findNamed(panel,
                    "workflowStep3", JButton.class);
            final JButton setGrid = findNamed(panel,
                    "setInteriorGridControls", JButton.class);

            assertFalse(setup.isEnabled());
            assertEquals(ReviewCanvas.InteractionTool.TRANSFORM,
                    panel.canvas().interactionTool());
            assertTrue(session.state().content().hemisphereWarp().isEmpty());
            assertEquals(0, session.state().contentRevision());

            interior.doClick();

            assertFalse(interior.isEnabled());
            assertEquals(ReviewCanvas.InteractionTool.POINTS,
                    panel.canvas().interactionTool());
            assertEquals(0, session.state().contentRevision(),
                    "entering optional Interior is navigation only");
            assertTrue(session.state().content().hemisphereWarp().isEmpty());

            setGrid.doClick();

            assertEquals(1, session.state().contentRevision());
            assertEquals(48, controls(session,
                    ManualHemisphereWarp2D.AtlasSide.LEFT,
                    ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
            assertEquals(0, controls(session,
                    ManualHemisphereWarp2D.AtlasSide.RIGHT,
                    ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
            place.doClick();
            assertFalse(place.isEnabled(),
                    "coarse placement must remain locked after local refinement starts");
            assertEquals(ReviewCanvas.InteractionTool.POINTS,
                    panel.canvas().interactionTool());

            controller.undo();
            assertTrue(session.state().content().hemisphereWarp().isEmpty());
            assertTrue(place.isEnabled(),
                    "Undo must reopen coarse placement without another edit");
            place.doClick();
            assertFalse(panel.canvas().singleTissuePane(),
                    "Match uses independently fitted tissue and atlas panes");
        });
    }

    @Test
    void skipInteriorPreservesEmptyActiveAndRevisitedInstalledState()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JButton interior = findNamed(panel,
                    "workflowStep3", JButton.class);
            final JButton skip = findNamed(panel,
                    "skipInteriorStage", JButton.class);
            final JButton setGrid = findNamed(panel,
                    "setInteriorGridControls", JButton.class);
            final JButton addOne = findNamed(panel,
                    "addInteriorPoint", JButton.class);

            interior.doClick();
            skip.doClick();
            assertEquals(0, session.state().contentRevision(),
                    "skipping empty Interior creates no revision");
            assertTrue(session.state().content().hemisphereWarp().isEmpty());

            interior.doClick();
            setGrid.doClick();
            final AlignmentReviewContent installed =
                    session.state().content();
            final long installedRevision =
                    session.state().contentRevision();
            addOne.doClick();
            assertTrue(panel.canvas().manualWarpPointPlacementArmed());

            skip.doClick();

            assertFalse(panel.canvas().manualWarpPointPlacementArmed(),
                    "Skip discards only the unfinished add-one gesture");
            assertEquals(installed, session.state().content());
            assertEquals(installedRevision,
                    session.state().contentRevision(),
                    "Skip preserves every installed control exactly");

            controller.undo();
            assertTrue(session.state().content().hemisphereWarp().isEmpty());
            controller.redo();
            assertEquals(installed, session.state().content(),
                    "Skip must not interfere with exact Undo/Redo history");

            final long revisitedRevision =
                    session.state().contentRevision();
            interior.doClick();
            assertEquals(revisitedRevision,
                    session.state().contentRevision(),
                    "revisiting Interior must not auto-seed controls");
            skip.doClick();
            assertEquals(installed, session.state().content());
            assertEquals(revisitedRevision,
                    session.state().contentRevision());
        });
    }

    @Test
    void setupAnatomyGuideAssistsPlaneSelectionWithoutChangingStructureFlow()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JComboBox<?> setupGuide = findNamed(panel,
                    "setupAnatomyGuideSearch", JComboBox.class);
            final JComboBox<?> structureGuide = findNamed(panel,
                    "ontologyGuideSearch", JComboBox.class);
            final JTextField structureQuery = findNamed(panel,
                    "atlasGuideLiveSearch", JTextField.class);
            final javax.swing.JTree hierarchy = findNamed(panel,
                    "atlasGuideHierarchy", javax.swing.JTree.class);
            final JButton setup = findNamed(panel,
                    "workflowStep0", JButton.class);
            final JButton structure = findNamed(panel,
                    "workflowStep4", JButton.class);

            assertFalse(setupGuide.isEditable());
            assertFalse(structureGuide.isEditable(),
                    "native Fiji must not expose a stale editable guide label");
            assertTrue(setupGuide.getItemAt(1).toString().contains(" — "));
            assertTrue(structureGuide.getItemAt(2).toString()
                    .contains("Dentate gyrus, granule cell layer"),
                    "preset choices must show their full verified labels");
            assertFalse(setup.isEnabled());
            assertEquals(ReviewCanvas.InteractionTool.TRANSFORM,
                    panel.canvas().interactionTool());

            final var directGuideItemListeners =
                    setupGuide.getItemListeners();
            final var directGuideActionListeners =
                    setupGuide.getActionListeners();
            for (final var listener : directGuideItemListeners) {
                setupGuide.removeItemListener(listener);
            }
            for (final var listener : directGuideActionListeners) {
                setupGuide.removeActionListener(listener);
            }
            setupGuide.setSelectedIndex(2);
            for (final var listener : directGuideItemListeners) {
                setupGuide.addItemListener(listener);
            }
            for (final var listener : directGuideActionListeners) {
                setupGuide.addActionListener(listener);
            }

            assertEquals(ReviewCanvas.InteractionTool.TRANSFORM,
                    panel.canvas().interactionTool(),
                    "the setup guide must not start local refinement");
            assertTrue(structureGuide.getSelectedItem().toString()
                    .startsWith("DG-sg"),
                    "a native popup choice must preserve and mirror the chosen guide");
            assertEquals(Optional.of("DG-sg"),
                    panel.selectedGuideAcronymForTests());
            assertTrue(panel.selectedGuideContourPresentForTests(),
                    "the directly selected preset must publish its magenta contour");

            final PopupMenuListener guidePopupListener =
                    panelPopupListener(setupGuide);
            guidePopupListener.popupMenuWillBecomeVisible(
                    new PopupMenuEvent(setupGuide));
            setupGuide.setSelectedIndex(1);
            for (final var listener : setupGuide.getKeyListeners()) {
                if (listener.getClass().getName().startsWith(
                        SwingReviewPanel.class.getName() + "$")) {
                    listener.keyPressed(new KeyEvent(setupGuide,
                            KeyEvent.KEY_PRESSED, 0L, 0,
                            KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
                }
            }
            guidePopupListener.popupMenuCanceled(
                    new PopupMenuEvent(setupGuide));
            guidePopupListener.popupMenuWillBecomeInvisible(
                    new PopupMenuEvent(setupGuide));
            assertEquals(Optional.of("DG-sg"),
                    panel.selectedGuideAcronymForTests(),
                    "Escape must restore the guide selected when the popup opened");

            guidePopupListener.popupMenuWillBecomeVisible(
                    new PopupMenuEvent(setupGuide));
            final var guideItemListeners = setupGuide.getItemListeners();
            final var guideActionListeners = setupGuide.getActionListeners();
            for (final var listener : guideItemListeners) {
                setupGuide.removeItemListener(listener);
            }
            for (final var listener : guideActionListeners) {
                setupGuide.removeActionListener(listener);
            }
            setupGuide.setSelectedIndex(1);
            guidePopupListener.popupMenuCanceled(
                    new PopupMenuEvent(setupGuide));
            guidePopupListener.popupMenuWillBecomeInvisible(
                    new PopupMenuEvent(setupGuide));
            for (final var listener : guideItemListeners) {
                setupGuide.addItemListener(listener);
            }
            for (final var listener : guideActionListeners) {
                setupGuide.addActionListener(listener);
            }
            assertEquals(Optional.of("DG"),
                    panel.selectedGuideAcronymForTests(),
                    "the selection model must commit an Aqua mouse-selected row even when item and action callbacks are absent");

            structure.doClick();
            structureGuide.setSelectedIndex(5);

            assertEquals(ReviewCanvas.InteractionTool.POINTS,
                    panel.canvas().interactionTool(),
                    "the existing Structure selector keeps its Points behavior");
            assertTrue(setupGuide.getSelectedItem().toString()
                    .startsWith("cc"),
                    "the Setup & Plane selector must mirror Structure changes");
            assertEquals(Optional.of("cc"),
                    panel.selectedGuideAcronymForTests());

            structureQuery.setText("CA1");
            assertEquals(Optional.of("cc"), panel.selectedGuideAcronymForTests(),
                    "typing offers matches without silently choosing anatomy");
            hierarchy.setSelectionRow(1);
            assertTrue(structureGuide.getSelectedItem().toString()
                    .startsWith("CA1"),
                    "less-common verified acronyms remain selectable");
            assertTrue(setupGuide.getSelectedItem().toString()
                    .startsWith("CA1"),
                    "typed ontology choices must mirror back to Setup & Plane");
            assertEquals(Optional.of("CA1"),
                    panel.selectedGuideAcronymForTests());
        });
    }

    private static PopupMenuListener panelPopupListener(
            final JComboBox<?> combo) {
        for (final PopupMenuListener listener
                : combo.getPopupMenuListeners()) {
            if (listener.getClass().getName().startsWith(
                    SwingReviewPanel.class.getName() + "$")) {
                return listener;
            }
        }
        throw new AssertionError("SwingReviewPanel popup listener not found");
    }

    @Test
    void temporaryPanReturnsToCurrentWorkflowToolWithoutDiscardingDrafts()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JToggleButton pan = findNamed(panel,
                    "temporaryPanTool", JToggleButton.class);
            final JButton match = findNamed(panel,
                    "workflowStep1", JButton.class);
            final long initialRevision = session.state().contentRevision();

            pan.doClick();
            assertTrue(pan.isSelected());
            assertEquals(ReviewCanvas.InteractionTool.PAN,
                    panel.canvas().interactionTool());

            pan.doClick();
            assertFalse(pan.isSelected());
            assertEquals(ReviewCanvas.InteractionTool.TRANSFORM,
                    panel.canvas().interactionTool(),
                    "leaving temporary Pan in Setup must restore Transform");
            assertEquals(initialRevision,
                    session.state().contentRevision());

            match.doClick();
            findNamed(panel, "suggestBoundaryFit", JButton.class).doClick();
            assertTrue(controller.boundaryFitState().active());
            pan.doClick();
            assertEquals(ReviewCanvas.InteractionTool.PAN,
                    panel.canvas().interactionTool());
            pan.doClick();
            assertEquals(ReviewCanvas.InteractionTool.TRANSFORM,
                    panel.canvas().interactionTool(),
                    "leaving temporary Pan in Match restores matcher hit testing");
            assertTrue(controller.boundaryFitState().active(),
                    "panning must not discard the unresolved match draft");
            assertEquals(initialRevision,
                    session.state().contentRevision(),
                    "view-only panning must not create a review revision");
        });
    }

    @Test
    void persistentWorkflowActionsAreNotHorizontallyClippedAtSupportedSizes()
            throws Exception {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            for (final Dimension size : List.of(
                    new Dimension(1280, 800),
                    new Dimension(1024, 768))) {
                panel.setSize(size);
                layoutTree(panel);
                final JPanel toolbarStack = findNamed(panel,
                        "compactToolbarStack", JPanel.class);
                assertTrue(toolbarStack.getHeight() <= 200,
                        "wrapped toolbar consumes more than 200 px at "
                                + size.width + "x" + size.height);
                for (final String rowName : List.of(
                        "alignmentStepRail", "interactionToolbarRow",
                        "persistentToolbarRow")) {
                    final JPanel row = findNamed(panel, rowName, JPanel.class);
                    assertTrue(row.getWidth() > 0);
                    assertTrue(row.getHeight() <= 64);
                    for (final Component child : row.getComponents()) {
                        if (child.isVisible()) {
                            assertTrue(child.getY() + child.getHeight() <= row.getHeight(),
                                    "Toolbar child is vertically clipped: " + child.getName());
                        }
                    }
                    for (final Component component : row.getComponents()) {
                        assertTrue(component.getX() >= 0);
                        assertTrue(component.getX() + component.getWidth()
                                        <= row.getWidth(),
                                component + " in " + rowName
                                        + " is clipped at " + size.width
                                        + "x" + size.height);
                    }
                }
                for (final String name : List.of(
                        "persistentBack", "persistentUndo", "persistentRedo",
                        "persistentReset", "persistentAccept",
                        "persistentClearWarp")) {
                    assertTrue(findNamed(panel, name, JButton.class)
                            .isVisible());
                }
                final JScrollPane inspector = findNamed(panel,
                        "manualInspectorScroll", JScrollPane.class);
                assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                        inspector.getHorizontalScrollBarPolicy());
                assertTrue(inspector.getViewport().getExtentSize().width > 0);
                assertTrue(inspector.getViewport().getView().getWidth()
                                <= inspector.getViewport().getExtentSize().width,
                        "the compact inspector overflows horizontally at "
                                + size.width + "x" + size.height
                                + ": view="
                                + inspector.getViewport().getView().getWidth()
                                + ", extent="
                                + inspector.getViewport().getExtentSize().width
                                + ", widest=" + widestPreferredComponent(
                                inspector.getViewport().getView()));
            }
        });
    }

    @Test
    void drawRoiInspectorUsesClearPolygonActionsWithoutHorizontalClipping()
            throws Exception {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            findNamed(panel, "workflowStep4", JButton.class).doClick();

            assertEquals("Create from guide",
                    findNamed(panel, "manualRoiConvertGuide", JButton.class)
                            .getText());
            assertEquals("Import Fiji ROI",
                    findNamed(panel, "manualRoiImportFromManager",
                            JButton.class).getText());
            assertTrue(SwingReviewPanel.workflowGuideText()
                    .contains("Create from guide"));

            findNamed(panel, "manualRoiMoreDrawing", javax.swing.JToggleButton.class).doClick();
            for (final Dimension size : List.of(
                    new Dimension(1280, 800),
                    new Dimension(1024, 768))) {
                panel.setSize(size);
                layoutTree(panel);
                final JScrollPane inspector = findNamed(panel,
                        "workflowStage4Scroll", JScrollPane.class);
                assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                        inspector.getHorizontalScrollBarPolicy());
                assertTrue(inspector.getViewport().getView().getWidth()
                                <= inspector.getViewport()
                                        .getExtentSize().width,
                        "Draw ROIs inspector overflows horizontally at "
                                + size.width + "x" + size.height);
                for (final String name : List.of(
                        "manualRoiConvertGuide", "manualRoiNewPolygon",
                        "manualRoiNewFreehand", "manualRoiAddPiece",
                        "manualRoiAddHole", "manualRoiFinish",
                        "manualRoiCancelUnfinished",
                        "manualRoiSendToManager",
                        "manualRoiImportFromManager", "manualRoiPreview",
                        "manualRoiExport", "workflowGuide")) {
                    final Component component = findNamed(
                            panel, name, Component.class);
                    assertTrue(component.getWidth() > 0,
                            name + " has no laid-out width");
                    assertTrue(component.getX() >= 0
                                    && component.getX()
                                    + component.getWidth()
                                    <= component.getParent().getWidth(),
                            name + " is clipped at " + size.width + "x"
                                    + size.height);
                }
                final JButton convertGuide = findNamed(panel,
                        "manualRoiConvertGuide", JButton.class);
                assertTrue(convertGuide.getWidth()
                                >= convertGuide.getPreferredSize().width,
                        "guide-to-polygon action text is clipped at "
                                + size.width + "x" + size.height);
            }
        });
    }

    private static String widestPreferredComponent(
            final Component root) {
        Component widest = root;
        if (root instanceof Container container) {
            for (final Component child : container.getComponents()) {
                final String candidate = widestPreferredComponent(child);
                final int separator = candidate.indexOf(':');
                final int candidateWidth = Integer.parseInt(
                        candidate.substring(0, separator));
                if (candidateWidth >= widest.getPreferredSize().width) {
                    return candidate;
                }
            }
        }
        return widest.getPreferredSize().width + ":"
                + widest.getClass().getSimpleName() + "["
                + Objects.toString(widest.getName(), "") + "]";
    }

    @Test
    void pointsPanelSeedsLeftAndRightWhileKeepingMidlineFixed()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.segmentedPreview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            applyConfirmedOutline(controller, basis);
            final JComboBox<?> guide = findNamed(panel,
                    "ontologyGuideSearch", JComboBox.class);
            guide.setSelectedIndex(2);
            final JComboBox<?> structureDensity = findNamed(panel,
                    "structureControlDensity", JComboBox.class);
            structureDensity.setSelectedItem(4);
            final JComboBox<?> gridDensity = findNamed(panel,
                    "gridControlDensity", JComboBox.class);
            gridDensity.setSelectedItem(24);
            final JButton structure = findButton(panel,
                    "Create 4-point ROI outline");
            final JButton applyStructure = findButton(panel,
                    "Apply structure changes");
            final JButton calculateStructure = findButton(panel,
                    "Calculate structure preview");
            final JButton acrossSide = findButton(panel,
                    "Set 24 across side");
            final JButton interior = findNamed(panel,
                    "workflowStep3", JButton.class);
            final JButton structureStep = findNamed(panel,
                    "workflowStep4", JButton.class);
            assertFalse(structure.isEnabled(),
                    "structure dots stay secondary until Points is chosen");
            structureStep.doClick();
            assertTrue(structure.isEnabled(),
                    "entering Structure must refresh point-tool actions even when no model render follows the stage change");
            interior.doClick();
            assertTrue(structure.isEnabled());
            assertTrue(acrossSide.isEnabled());
            acrossSide.doClick();
            structure.doClick();
            assertFalse(applyStructure.isEnabled());
            assertTrue(calculateStructure.isEnabled());
            calculateStructure.doClick();
            assertTrue(applyStructure.isEnabled());
            applyStructure.doClick();
            findRadio(panel, "Atlas right").doClick();
            acrossSide.doClick();
            structure.doClick();
            assertFalse(applyStructure.isEnabled());
            calculateStructure.doClick();
            assertTrue(applyStructure.isEnabled());
            applyStructure.doClick();

            final var warp = session.state().content().hemisphereWarp()
                    .orElseThrow();
            assertEquals(4, controls(session,
                    ManualHemisphereWarp2D.AtlasSide.LEFT,
                    ManualWarpControlOrigin.STRUCTURE_GUIDE).size());
            assertEquals(4, controls(session,
                    ManualHemisphereWarp2D.AtlasSide.RIGHT,
                    ManualWarpControlOrigin.STRUCTURE_GUIDE).size());
            assertEquals(28, warp.manualControls(
                    ManualHemisphereWarp2D.AtlasSide.LEFT).size());
            assertEquals(28, warp.manualControls(
                    ManualHemisphereWarp2D.AtlasSide.RIGHT).size());
            assertEquals(56, panel.canvas().visibleManualWarpControls().size());
            assertTrue(panel.canvas().visibleManualWarpControls().stream()
                    .anyMatch(control -> control.atlasSide()
                            == ManualHemisphereWarp2D.AtlasSide.RIGHT));
            assertTrue(panel.canvas().visibleManualWarpControls().stream()
                    .anyMatch(control -> control.atlasSide()
                            == ManualHemisphereWarp2D.AtlasSide.LEFT));
            findRadio(panel, "Atlas left").doClick();
            assertEquals(56, panel.canvas().visibleManualWarpControls().size(),
                    "command-side selection must not hide the other side");
            assertTrue(session.state().content().activeFitLandmarks()
                    .isEmpty(),
                    "manual warp controls are geometry, not landmark evidence");
            final Point2D dorsalMidline = warp.imageMidline().dorsal();
            assertEquals(dorsalMidline, warp.apply(dorsalMidline),
                    "the anatomical seam must remain fixed");

            guide.setSelectedIndex(5);
            assertTrue(structure.isEnabled());
            assertTrue(acrossSide.isEnabled(),
                    "regular interior controls do not depend on a structure target");
            assertEquals(56, warp.controls().size());
        });
    }

    @Test
    void tiltEditIsAnUndoablePlaneRequest() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.basis();
        final AtomicReference<AtlasPlaneRequest> request =
                new AtomicReference<>();
        final AtlasPlaneSource source = new AtlasPlaneSource() {
            @Override
            public AtlasCoronalPlane load(final int level) {
                return load(new AtlasPlaneRequest(
                        new org.atlasalign.application.AllenCoronalLevel(level),
                        AtlasPlaneTilt.CORONAL));
            }

            @Override
            public AtlasCoronalPlane load(
                    final AtlasPlaneRequest next) {
                request.set(next);
                final AtlasCoronalPlane axisAligned =
                        ReviewPluginFixtures.plane(
                                next.zeroBasedAnteriorPosteriorIndex());
                final AtlasPlaneGeometry geometry =
                        AtlasPlaneGeometry.create(
                                next.zeroBasedAnteriorPosteriorIndex(),
                                axisAligned.width(),
                                axisAligned.height(),
                                next.tilt().sagittalDegrees(),
                                next.tilt().horizontalDegrees(),
                                axisAligned.height(),
                                axisAligned.width());
                return new AtlasCoronalPlane(
                        axisAligned.zeroBasedAnteriorPosteriorIndex(),
                        axisAligned.width(),
                        axisAligned.height(),
                        axisAligned.templateIntensity(),
                        axisAligned.annotationId(),
                        geometry);
            }
        };
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.preview(),
                source,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run,
                Runnable::run);
        controller.attach(new CapturingView());

        controller.setSagittalTiltDegrees(12);
        controller.setHorizontalTiltDegrees(-7);
        assertEquals(12, request.get().tilt().sagittalDegrees());
        assertEquals(-7, request.get().tilt().horizontalDegrees());
        assertEquals(12, controller.state().content().atlasPlaneTilt()
                .sagittalDegrees());
        assertEquals(-7, controller.state().content().atlasPlaneTilt()
                .horizontalDegrees());
        assertEquals(2, controller.state().contentRevision());

        controller.undo();
        assertEquals(12, controller.state().content().atlasPlaneTilt()
                .sagittalDegrees());
        assertEquals(0, controller.state().content().atlasPlaneTilt()
                .horizontalDegrees());
        controller.undo();
        assertEquals(AtlasPlaneTilt.CORONAL,
                controller.state().content().atlasPlaneTilt());
    }

    @Test
    void planeSlidersKeepManualDotsAndApplyFieldToTheNewAtlasPlane() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final CapturingView view = new CapturingView();
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(view);
        controller.selectAtlasRegionExactAcronym("DG");
        setAndApplyStructure(controller,
                6, ManualHemisphereWarp2D.AtlasSide.LEFT);
        final var before = session.state().content().hemisphereWarp()
                .orElseThrow();
        final String fieldHash = before.diagnostics().contentSha256();
        final List<ManualWarpControl> dots = before.controls();

        controller.setCoronalLevel(241);
        controller.setSagittalTiltDegrees(4);
        controller.setHorizontalTiltDegrees(-3);

        final var after = session.state().content().hemisphereWarp()
                .orElseThrow();
        assertEquals(fieldHash, after.diagnostics().contentSha256());
        assertEquals(dots, after.controls());
        assertEquals(241, view.last().atlasPlane().orElseThrow()
                .zeroBasedAnteriorPosteriorIndex());
        assertEquals(241, controller.state().content().coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
        assertEquals(new AtlasPlaneTilt(4, -3),
                controller.state().content().atlasPlaneTilt());
        assertTrue(controller.state().content().outlineWarp().isEmpty(),
                "normal manual warping must not construct an exact outline map");
    }

    @Test
    void draggingWithoutAVisibleManualHandleCreatesNoReviewRevision()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.basis();
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final ReviewController controller =
                new ReviewController(
                        session,
                        ReviewPluginFixtures.preview(),
                        ReviewPluginFixtures::plane,
                        () -> new ReviewAcceptanceVerification(
                                basis.sourceSnapshot(), basis.atlas()),
                        Runnable::run,
                        Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel =
                    new SwingReviewPanel(controller);
            panel.canvas().setSize(400, 320);
            controller.attach(panel);
            final ReviewCanvas canvas = panel.canvas();
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            canvas.dispatchEvent(mouse(
                    canvas, MouseEvent.MOUSE_PRESSED,
                    100, 100, MouseEvent.BUTTON1));
            canvas.dispatchEvent(mouse(
                    canvas, MouseEvent.MOUSE_DRAGGED,
                    112, 106, MouseEvent.NOBUTTON));
            canvas.dispatchEvent(mouse(
                    canvas, MouseEvent.MOUSE_DRAGGED,
                    124, 112, MouseEvent.NOBUTTON));
            canvas.dispatchEvent(mouse(
                    canvas, MouseEvent.MOUSE_RELEASED,
                    124, 112, MouseEvent.BUTTON1));
        });

        assertEquals(0, session.state().contentRevision());
        assertTrue(session.auditTrail().isEmpty());
        assertFalse(session.canUndo());
    }

    @Test
    void manualControlReleaseCreatesOneRevisionAndEscapeCreatesNone()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.scaledAtlasBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                new CatalogPlaneSource(false, false),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            final ReviewCanvas canvas = panel.canvas();
            canvas.setSize(820, 400);
            controller.attach(panel);
            applyConfirmedOutline(controller, basis);
            controller.selectAtlasRegionExactAcronym("DG");
            controller.replaceSelectedStructureControls(
                    4, ManualHemisphereWarp2D.AtlasSide.LEFT);
            final var draftControl = controller.structureAdjustmentState()
                    .draft().orElseThrow().requestedControls().get(0);
            canvas.setActiveHemisphereSide(
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            canvas.setStructureEditingEnabled(true);
            final var control = draftControl;
            final Point2D screen = canvas.sourceScreenMapping()
                    .previewToScreen(control.targetPoint());
            final int x = (int) Math.round(screen.x());
            final int y = (int) Math.round(screen.y());
            final long beforeRelease = session.state().contentRevision();
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED,
                    x, y, MouseEvent.BUTTON1));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_DRAGGED,
                    x + 2, y + 2, MouseEvent.NOBUTTON));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED,
                    x + 2, y + 2, MouseEvent.BUTTON1));
            assertEquals(beforeRelease,
                    session.state().contentRevision());
            assertTrue(controller.structureAdjustmentState().candidate()
                    .isEmpty(), "point release must not solve implicitly");
            controller.calculateStructureAdjustmentPreview();
            assertTrue(controller.structureAdjustmentState().candidate()
                    .isPresent());
            controller.applyStructureChanges();
            assertEquals(beforeRelease + 1,
                    session.state().contentRevision());

            final long beforeEscape = session.state().contentRevision();
            final var moved = session.state().content().hemisphereWarp()
                    .orElseThrow().controls().stream()
                    .filter(candidate -> candidate.id().equals(control.id()))
                    .findFirst().orElseThrow();
            final Point2D movedScreen = canvas.sourceScreenMapping()
                    .previewToScreen(moved.targetPoint());
            final int movedX = (int) Math.round(movedScreen.x());
            final int movedY = (int) Math.round(movedScreen.y());
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED,
                    movedX, movedY, MouseEvent.BUTTON1));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_DRAGGED,
                    movedX + 4, movedY + 4, MouseEvent.NOBUTTON));
            canvas.getActionMap().get("cancel-canvas-gesture")
                    .actionPerformed(new java.awt.event.ActionEvent(
                            canvas, java.awt.event.ActionEvent.ACTION_PERFORMED,
                            "escape"));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED,
                    movedX + 4, movedY + 4, MouseEvent.BUTTON1));
            assertEquals(beforeEscape, session.state().contentRevision());
        });
    }

    @Test
    void legacyGenericSimilarityControlsAreNotExposed()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.basis();
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session,
                ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run,
                Runnable::run);
        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel =
                    new SwingReviewPanel(controller);
            controller.attach(panel);
            assertThrows(IllegalArgumentException.class,
                    () -> findButton(panel, "Fit similarity (0 points)"));
            assertThrows(IllegalArgumentException.class,
                    () -> findButton(panel, "Fit global affine (0 points)"));
        });
        assertTrue(session.state().content().activeLandmarks().isEmpty());
    }

    @Test
    void legacyGenericLocalWarpControlsAreNotExposed()
            throws Exception {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            assertThrows(IllegalArgumentException.class,
                    () -> findButton(panel,
                            "Fit generic local warp (0 FIT)"));
            final JButton clear = findButton(panel, "Clear internal warp");
            assertFalse(clear.isEnabled());
            assertTrue(session.state().content().localWarp().isEmpty());
            assertTrue(session.state().content().hemisphereWarp().isEmpty());
        });
    }

    @Test
    void accessibleOrientationSelectionCommitsExactlyOneReviewEdit()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.basis();
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final ReviewController controller =
                new ReviewController(
                        session,
                        ReviewPluginFixtures.preview(),
                        ReviewPluginFixtures::plane,
                        () -> new ReviewAcceptanceVerification(
                                basis.sourceSnapshot(), basis.atlas()),
                        Runnable::run,
                        Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel =
                    new SwingReviewPanel(controller);
            controller.attach(panel);
            final JRadioButton direct = findRadio(panel, "Direct");
            final JRadioButton reflected = findRadio(panel, "Reflected");
            assertTrue(direct.isSelected());
            assertEquals(0,
                    session.state().contentRevision());
            assertTrue(session.state().content()
                    .orientation().confirmed());
            reflected.doClick();
            assertEquals(1,
                    session.state().contentRevision());
            direct.doClick();
            assertEquals(2, session.state().contentRevision());
        });

        assertEquals(2, session.state().contentRevision());
        assertTrue(session.state().content()
                .orientation().confirmed());
        assertTrue(session.canUndo());
    }

    @Test
    void halfSectionRequiresSeparateAccessibleLateralitySelection()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.basis(
                        SectionGeometry.IMAGE_LEFT_HALF);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final ReviewController controller =
                new ReviewController(
                        session,
                        ReviewPluginFixtures.preview(),
                        ReviewPluginFixtures::plane,
                        () -> new ReviewAcceptanceVerification(
                                basis.sourceSnapshot(), basis.atlas()),
                        Runnable::run,
                        Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel =
                    new SwingReviewPanel(controller);
            controller.attach(panel);
            final JRadioButton direct = findRadio(panel, "Direct");
            final JRadioButton reflected = findRadio(panel, "Reflected");
            final JRadioButton left = findRadio(panel, "L");
            final JRadioButton right = findRadio(panel, "R");
            final JRadioButton unsure = findRadio(panel, "?");

            assertTrue(direct.isSelected());
            assertTrue(left.isEnabled());
            assertFalse(right.isEnabled());
            assertTrue(unsure.isEnabled());

            assertEquals(
                    ObservedAnatomicalHemisphere.UNSURE,
                    session.state().observedAnatomicalHemisphere());

            left.doClick();
            assertEquals(
                    ObservedAnatomicalHemisphere.LEFT,
                    session.state().observedAnatomicalHemisphere());

            reflected.doClick();
            assertEquals(
                    ObservedAnatomicalHemisphere.UNSURE,
                    session.state().observedAnatomicalHemisphere());
            assertFalse(left.isEnabled());
            assertTrue(right.isEnabled());
            assertTrue(unsure.isEnabled());

            right.doClick();
            assertEquals(
                    ObservedAnatomicalHemisphere.RIGHT,
                    session.state().observedAnatomicalHemisphere());
        });
    }

    @Test
    void imageRightHalfEnablesRightAfterDirectOrientation()
            throws Exception {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.basis(
                        SectionGeometry.IMAGE_RIGHT_HALF);
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final ReviewController controller =
                new ReviewController(
                        session,
                        ReviewPluginFixtures.preview(),
                        ReviewPluginFixtures::plane,
                        () -> new ReviewAcceptanceVerification(
                                basis.sourceSnapshot(), basis.atlas()),
                        Runnable::run,
                        Runnable::run);

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel =
                    new SwingReviewPanel(controller);
            controller.attach(panel);
            final JRadioButton direct = findRadio(panel, "Direct");
            final JRadioButton left = findRadio(panel, "L");
            final JRadioButton right = findRadio(panel, "R");
            final JRadioButton unsure = findRadio(panel, "?");

            assertTrue(direct.isSelected());
            assertFalse(left.isEnabled());
            assertTrue(right.isEnabled());
            assertTrue(unsure.isEnabled());

            right.doClick();
            assertEquals(
                    ObservedAnatomicalHemisphere.RIGHT,
                    session.state().observedAnatomicalHemisphere());
        });
    }

    @Test
    void closingPendingReviewNeverAccepts() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.basis();
        final CapturingView view = new CapturingView();
        final ReviewController controller = controller(
                basis, Runnable::run, view);

        controller.close();

        assertFalse(controller.isAccepted());
        assertTrue(view.closed);
    }

    @Test
    void acceptanceBeforeCurrentPlaneLoadsIsAudited() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.basis();
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final QueuedExecutor loader = new QueuedExecutor();
        final CapturingView view = new CapturingView();
        final ReviewController controller =
                new ReviewController(
                        session,
                        ReviewPluginFixtures.preview(),
                        ReviewPluginFixtures::plane,
                        () -> new ReviewAcceptanceVerification(
                                basis.sourceSnapshot(), basis.atlas()),
                        loader,
                        Runnable::run);
        controller.attach(view);

        assertTrue(controller.accept().isEmpty());

        final var audit = session.auditTrail().get(0)
                .acceptanceAudit().orElseThrow();
        assertFalse(audit.succeeded());
        assertEquals(
                ReviewAcceptanceBlockReason
                        .CURRENT_ATLAS_PLANE_NOT_VERIFIED,
                audit.failureReason().orElseThrow());
        assertTrue(view.lastError.contains("verified atlas overlay"));
    }

    @Test
    void editResetAndAcceptanceLeaveLiveImageUnchanged() {
        final byte[] pixels = new byte[100 * 80];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = (byte) index;
        }
        final ImagePlus source = new ImagePlus(
                "source",
                new ByteProcessor(100, 80, pixels));
        source.getStack().setSliceLabel("DAPI", 1);
        source.setDisplayRange(17, 201);
        final Object originalPixels =
                source.getProcessor().getPixels();
        final double originalMinimum = source.getDisplayRangeMin();
        final double originalMaximum = source.getDisplayRangeMax();
        final int originalSlice = source.getCurrentSlice();
        final ImagePlusSourceImage readOnly =
                new ImagePlusSourceImage(source);
        final SourceImageSnapshot before = readOnly.snapshot();
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.basis(before);
        final CapturingView view = new CapturingView();
        final ReviewController controller =
                new ReviewController(
                        new AlignmentReviewSession(basis),
                        ReviewPluginFixtures.preview(),
                        ReviewPluginFixtures::plane,
                        () -> new ReviewAcceptanceVerification(
                                readOnly.snapshot(), basis.atlas()),
                        Runnable::run,
                        Runnable::run);
        controller.attach(view);

        controller.translate(4, -3);
        controller.rotateDegrees(1);
        controller.scaleUniform(1.02);
        controller.addLandmark("local-1", new Point2D(10, 10),
                new Point2D(15, 9));
        controller.addLandmark("local-2", new Point2D(80, 10),
                new Point2D(82, 13));
        controller.addLandmark("local-3", new Point2D(80, 60),
                new Point2D(78, 62));
        controller.addLandmark("local-4", new Point2D(10, 60),
                new Point2D(13, 58));
        controller.fitActiveLandmarksLocalWarp();
        assertTrue(controller.state().content().localWarp().isPresent());
        controller.reset();
        controller.setWarningsAcknowledged(true);

        assertTrue(controller.accept().isPresent());
        assertEquals(before, readOnly.snapshot());
        assertEquals(pixels.length,
                ((byte[]) source.getProcessor().getPixels()).length);
        assertSame(
                originalPixels, source.getProcessor().getPixels());
        assertEquals(
                originalMinimum, source.getDisplayRangeMin());
        assertEquals(
                originalMaximum, source.getDisplayRangeMax());
        assertEquals(originalSlice, source.getCurrentSlice());
    }

    private static ReviewController controller(
            final AlignmentReviewBasis basis,
            final Executor loader,
            final CapturingView view) {
        final ReviewController controller =
                new ReviewController(
                        new AlignmentReviewSession(basis),
                        ReviewPluginFixtures.preview(),
                        ReviewPluginFixtures::plane,
                        () -> new ReviewAcceptanceVerification(
                                basis.sourceSnapshot(), basis.atlas()),
                        loader,
                        Runnable::run);
        controller.attach(view);
        return controller;
    }

    private static void completeFourBoundaryMatches(
            final ReviewController controller) {
        completeFourBoundaryMatches(controller, new int[]{0, 2, 4, 6});
    }

    private static void completeFourBoundaryMatches(
            final ReviewController controller,
            final int[] anchorIndices) {
        final BoundaryFitDraft draft = controller.boundaryFitState().draft()
                .orElseThrow();
        for (final int index : anchorIndices) {
            completeBoundaryMatch(controller, index);
        }
    }

    @Test
    void atlasGuideConversionCreatesTheExactRequestedPolygonVertexCount() {
        final List<Point2D> longLoop = List.of(
                new Point2D(10, 10), new Point2D(90, 10),
                new Point2D(90, 50), new Point2D(10, 50));
        final List<Point2D> shortLoop = List.of(
                new Point2D(120, 20), new Point2D(150, 20),
                new Point2D(150, 40), new Point2D(120, 40));

        final List<List<Point2D>> sampled =
                ManualRoiEditorPanel.resampleLoops(
                        List.of(shortLoop, longLoop), 64);

        assertEquals(2, sampled.size());
        assertEquals(64, sampled.stream().mapToInt(List::size).sum());
        assertTrue(sampled.stream().allMatch(loop -> loop.size() >= 3));
        assertTrue(sampled.get(0).size() > sampled.get(1).size(),
                "the longer contour component should receive more handles");
    }

    private static void completeBoundaryMatch(
            final ReviewController controller,
            final int anchorIndex) {
        final BoundaryFitDraft draft = controller.boundaryFitState().draft()
                .orElseThrow();
        final var anchor = draft.anchors().get(anchorIndex);
        final Point2D tissue = draft.request().tissueBoundary().stream()
                .min(java.util.Comparator.comparingDouble(sample -> {
                    final double dx = sample.point().x()
                            - anchor.atlasPreviewPoint().x();
                    final double dy = sample.point().y()
                            - anchor.atlasPreviewPoint().y();
                    return dx * dx + dy * dy;
                })).orElseThrow().point();
        controller.setBoundaryFitTissuePoint(anchor.id(), tissue);
    }

    private static void assertHalfBulkSeederSides(
            final AtlasOrientation orientation,
            final boolean includeOppositeRemnant) {
        final AlignmentReviewBasis basis = matchingAtlasSupportBasis();
        final AlignmentReviewSession session = new AlignmentReviewSession(
                basis);
        final ReviewController controller = new ReviewController(
                session, ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        controller.attach(new CapturingView());
        if (controller.state().content().orientation() != orientation) {
            controller.setOrientation(orientation);
        }
        controller.setReviewSectionMode(ReviewSectionMode.HALF);
        final ManualHemisphereWarp2D.AtlasSide visibleSide =
                orientation == AtlasOrientation
                        .CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT
                        ? ManualHemisphereWarp2D.AtlasSide.LEFT
                        : ManualHemisphereWarp2D.AtlasSide.RIGHT;
        final ManualHemisphereWarp2D.AtlasSide hiddenSide =
                visibleSide == ManualHemisphereWarp2D.AtlasSide.LEFT
                        ? ManualHemisphereWarp2D.AtlasSide.RIGHT
                        : ManualHemisphereWarp2D.AtlasSide.LEFT;
        final ObservedAnatomicalHemisphere visibleHemisphere =
                visibleSide == ManualHemisphereWarp2D.AtlasSide.LEFT
                        ? ObservedAnatomicalHemisphere.LEFT
                        : ObservedAnatomicalHemisphere.RIGHT;
        if (controller.state().content().observedHemisphere()
                != visibleHemisphere) {
            controller.setObservedHemisphere(visibleHemisphere);
        }
        if (includeOppositeRemnant) {
            controller.startBoundaryWarp(
                    visibleSide, 4);
            assertTrue(controller.boundaryWarpOppositeRemnantAvailable());
            controller.setHalfAtlasCoverage(
                    HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT);
        }

        controller.replaceInteriorGridControlsForSupportedSides(4);

        assertEquals(4, controls(session,
                visibleSide,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size());
        assertEquals(includeOppositeRemnant ? 4 : 0, controls(session,
                hiddenSide,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID).size(),
                "bulk grid seeding must follow persisted anatomical-side scope under "
                        + orientation);

        controller.clearLocalWarp();
        controller.resuggestBoundaryControls(4);

        assertEquals(4, controls(session,
                visibleSide,
                ManualWarpControlOrigin.TISSUE_BOUNDARY).size());
        assertEquals(includeOppositeRemnant ? 4 : 0, controls(session,
                hiddenSide,
                ManualWarpControlOrigin.TISSUE_BOUNDARY).size(),
                "bulk boundary seeding must follow persisted anatomical-side scope under "
                        + orientation);
    }

    private static AlignmentReviewBasis matchingAtlasSupportBasis() {
        final boolean[] pixels = new boolean[100 * 80];
        for (int y = 8; y <= 56; y++) {
            for (int x = 12; x <= 80; x++) {
                pixels[y * 100 + x] = true;
            }
        }
        return ReviewPluginFixtures.scaledAtlasBasisWithMask(
                BinaryMask.fromBooleans(100, 80, pixels));
    }

    private static void applyConfirmedOutline(
            final ReviewController controller,
            final AlignmentReviewBasis basis) {
        final double atlasMaximumX =
                basis.atlas().atlasPlaneWidth() - 1.0;
        final double atlasMaximumY =
                basis.atlas().atlasPlaneHeight() - 1.0;
        final double atlasMidlineX = atlasMaximumX / 2.0;
        final List<Point2D> atlasLoop = List.of(
                new Point2D(0, 0),
                new Point2D(atlasMidlineX, 0),
                new Point2D(atlasMaximumX, 0),
                new Point2D(atlasMaximumX, atlasMaximumY),
                new Point2D(atlasMidlineX, atlasMaximumY),
                new Point2D(0, atlasMaximumY));
        final List<Point2D> globallyMappedLoop = atlasLoop.stream()
                .map(basis.proposal().affine()::apply)
                .toList();
        final BoundaryAuthoritativeTransform2D outline =
                BoundaryAuthoritativeTransform2D.fitFull(
                        MonotoneBoundary2D.arcLengthIndexed(
                                "atlas-", globallyMappedLoop),
                        MonotoneBoundary2D.arcLengthIndexed(
                                "tissue-", globallyMappedLoop),
                        100, 80);
        controller.applyGuidedManualCandidate(
                new ReviewEdit.ApplyGuidedManualCandidate(
                        basis.proposal().coronalLevel(),
                        AtlasPlaneTilt.CORONAL,
                        AtlasOrientation
                                .CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.BOTH,
                        basis.proposal().affine(), Optional.of(outline), true,
                        "test-outline", "test-candidate",
                        basis.proposal().coronalLevel(),
                        AtlasPlaneTilt.CORONAL,
                        0.1, 0.1, 0.1, List.of("outline"),
                        basis.sourceSnapshot().pixelSha256(),
                        basis.atlas().identitySha256()));
    }

    private static MouseEvent mouse(
            final ReviewCanvas canvas,
            final int id,
            final int x,
            final int y,
            final int button) {
        return new MouseEvent(
                canvas,
                id,
                System.currentTimeMillis(),
                0,
                x,
                y,
                1,
                false,
                button);
    }

    private static void click(
            final ReviewCanvas canvas,
            final ScreenMapping mapping,
            final Point2D point) {
        final Point2D screen = mapping.previewToScreen(point);
        final int x = (int) Math.round(screen.x());
        final int y = (int) Math.round(screen.y());
        canvas.dispatchEvent(mouse(
                canvas, MouseEvent.MOUSE_PRESSED,
                x, y, MouseEvent.BUTTON1));
        canvas.dispatchEvent(mouse(
                canvas, MouseEvent.MOUSE_RELEASED,
                x, y, MouseEvent.BUTTON1));
    }

    private static void assertPoint(
            final Point2D expected,
            final Point2D actual) {
        assertEquals(expected.x(), actual.x(), 1e-6);
        assertEquals(expected.y(), actual.y(), 1e-6);
    }

    private static List<ManualWarpControl> controls(
            final AlignmentReviewSession session,
            final ManualHemisphereWarp2D.AtlasSide side,
            final ManualWarpControlOrigin origin) {
        return session.state().content().hemisphereWarp().orElseThrow()
                .manualControls(side).stream()
                .filter(control -> control.origin() == origin)
                .toList();
    }

    private static List<String> setAndApplyStructure(
            final ReviewController controller,
            final int count,
            final ManualHemisphereWarp2D.AtlasSide side) {
        final List<String> identifiers = controller
                .replaceSelectedStructureControls(count, side);
        controller.calculateStructureAdjustmentPreview();
        assertTrue(controller.structureAdjustmentState().candidate()
                .isPresent(), controller.structureAdjustmentState()
                        .message().orElse(""));
        controller.applyStructureChanges();
        return identifiers;
    }

    private static JButton findButton(
            final Container root,
            final String text) {
        for (final Component component : root.getComponents()) {
            if (component instanceof JButton button
                    && button.getText().equals(text)) {
                return button;
            }
            if (component instanceof Container container) {
                try {
                    return findButton(container, text);
                } catch (final IllegalArgumentException ignored) {
                    // Continue searching sibling containers.
                }
            }
        }
        throw new IllegalArgumentException(
                "Button was not found: " + text);
    }

    private static <T extends Component> T findNamed(
            final Container root,
            final String name,
            final Class<T> type) {
        for (final Component component : root.getComponents()) {
            if (type.isInstance(component)
                    && name.equals(component.getName())) {
                return type.cast(component);
            }
            if (component instanceof Container container) {
                try {
                    return findNamed(container, name, type);
                } catch (final IllegalArgumentException ignored) {
                    // Continue searching sibling containers.
                }
            }
        }
        throw new IllegalArgumentException(
                "Named component was not found: " + name);
    }

    private static JRadioButton findRadio(
            final Container root,
            final String text) {
        for (final Component component : root.getComponents()) {
            if (component instanceof JRadioButton radio
                    && radio.getText().equals(text)) {
                return radio;
            }
            if (component instanceof Container container) {
                try {
                    return findRadio(container, text);
                } catch (final IllegalArgumentException ignored) {
                    // Continue searching sibling containers.
                }
            }
        }
        throw new IllegalArgumentException(
                "Radio button was not found: " + text);
    }

    private static JToggleButton findToggle(
            final Container root,
            final String text) {
        for (final Component component : root.getComponents()) {
            if (component instanceof JToggleButton toggle
                    && toggle.getText().equals(text)) {
                return toggle;
            }
            if (component instanceof Container container) {
                try {
                    return findToggle(container, text);
                } catch (final IllegalArgumentException ignored) {
                    // Continue searching sibling containers.
                }
            }
        }
        throw new IllegalArgumentException(
                "Toggle was not found: " + text);
    }

    private static void layoutTree(final Container container) {
        container.doLayout();
        for (final Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }

    private static BinaryMask rectangularMask(
            final int width,
            final int height,
            final int minimumX,
            final int maximumX,
            final int minimumY,
            final int maximumY) {
        final boolean[] pixels = new boolean[width * height];
        for (int y = minimumY; y <= maximumY; y++) {
            for (int x = minimumX; x <= maximumX; x++) {
                pixels[y * width + x] = true;
            }
        }
        return BinaryMask.fromBooleans(width, height, pixels);
    }

    private static Point2D componentRequestedDelta(
            final StructureAdjustmentDraft before,
            final StructureAdjustmentDraft after,
            final Set<String> includedIds,
            final int component) {
        final java.util.Map<String, Point2D> starts = before
                .requestedControls().stream().collect(
                        java.util.stream.Collectors.toMap(
                                ManualWarpControl::id,
                                ManualWarpControl::targetPoint));
        return after.requestedControls().stream()
                .filter(control -> includedIds.contains(control.id())
                        && after.componentByControlId().get(control.id())
                        == component)
                .map(control -> new Point2D(
                        control.targetPoint().x()
                                - starts.get(control.id()).x(),
                        control.targetPoint().y()
                                - starts.get(control.id()).y()))
                .findFirst().orElseThrow();
    }

    private static AlignmentReviewBasis basisWithShearedProposal() {
        return ReviewPluginFixtures
                .segmentedScaledAtlasBasisWithShearedProposal();
    }

    private static AffineTransform2D placedSideTransform(
            final ReviewController controller,
            final ManualHemisphereWarp2D.AtlasSide side) {
        return controller.state().preOutlineAtlasToPreview().andThen(
                controller.state().content().manualSidePlacement()
                        .transform(side));
    }

    private static double atlasAxisShear(final AffineTransform2D transform) {
        return (transform.m00() * transform.m01()
                + transform.m10() * transform.m11())
                / (transform.m00() * transform.m00()
                        + transform.m10() * transform.m10());
    }

    private static final class QueuedExecutor
            implements Executor {

        private final List<Runnable> tasks =
                new ArrayList<>();

        @Override
        public void execute(final Runnable command) {
            tasks.add(command);
        }

        void run(final int index) {
            tasks.get(index).run();
        }
    }

    private static final class ConnectedDgSgPlaneSource
            implements AtlasPlaneSource, AtlasRegionCatalog {

        private static final SelectedAtlasRegion REGION =
                new SelectedAtlasRegion(11, "DG-sg",
                        "Dentate gyrus, granule cell layer", Set.of(11));

        @Override
        public AtlasCoronalPlane load(final int level) {
            final int width = 456;
            final int height = 320;
            final int[] labels = new int[width * height];
            fillLabels(labels, width, 55, 75, 165, 98, 11);
            fillLabels(labels, width, 65, 128, 175, 153, 11);
            fillLabels(labels, width, 155, 90, 175, 138, 11);
            return AtlasCoronalPlane.annotationOnly(level, width, height,
                    labels, AtlasPlaneGeometry.axisAligned(level, width,
                            height, height, width));
        }

        @Override
        public Optional<SelectedAtlasRegion> resolveExactAcronym(
                final String acronym) {
            return REGION.acronym().equalsIgnoreCase(acronym)
                    ? Optional.of(REGION) : Optional.empty();
        }

        private static void fillLabels(
                final int[] labels,
                final int width,
                final int minimumX,
                final int minimumY,
                final int maximumX,
                final int maximumY,
                final int value) {
            for (int y = minimumY; y <= maximumY; y++) {
                for (int x = minimumX; x <= maximumX; x++) {
                    labels[y * width + x] = value;
                }
            }
        }
    }

    private static final class CatalogPlaneSource
            implements AtlasPlaneSource, AtlasRegionCatalog {

        private static final SelectedAtlasRegion DG =
                new SelectedAtlasRegion(10, "DG", "Dentate gyrus",
                        Set.of(10, 11));
        private static final SelectedAtlasRegion DG_SG =
                new SelectedAtlasRegion(11, "DG-sg",
                        "Dentate gyrus, granule cell layer", Set.of(11));
        private static final SelectedAtlasRegion CA1 =
                new SelectedAtlasRegion(99, "CA1", "Field CA1", Set.of(99));
        private final boolean absent;
        private final boolean includeTinyBoundaryComponent;

        CatalogPlaneSource() {
            this(false, true);
        }

        CatalogPlaneSource(final boolean absent) {
            this(absent, true);
        }

        CatalogPlaneSource(
                final boolean absent,
                final boolean includeTinyBoundaryComponent) {
            this.absent = absent;
            this.includeTinyBoundaryComponent = includeTinyBoundaryComponent;
        }

        @Override
        public List<org.atlasalign.atlas.AtlasRegion> hierarchy() {
            return List.of(new org.atlasalign.atlas.AtlasRegion(1,"root","Brain",null,3,List.of(99)),
                    new org.atlasalign.atlas.AtlasRegion(99,"CA1","Field CA1",1,3,List.of()));
        }

        @Override
        public AtlasCoronalPlane load(final int level) {
            final int width = 456;
            final int height = 320;
            final int[] labels = new int[width * height];
            if (!absent) {
                if (includeTinyBoundaryComponent) {
                    for (int y = 2; y <= 8; y++) {
                        for (int x = 2; x <= 8; x++) {
                            labels[y * width + x] = x < 5 ? 10 : 11;
                        }
                        for (int x = 447; x <= 453; x++) {
                            labels[y * width + x] = x < 450 ? 11 : 10;
                        }
                    }
                }
                // Larger mirrored components keep side-specific handle
                // geometry realistic while the tiny component above retains
                // the exact-boundary snapping fixture used by other tests.
                for (int y = 80; y <= 150; y++) {
                    for (int x = 60; x <= 160; x++) {
                        labels[y * width + x] = x < 110 ? 10 : 11;
                    }
                    for (int x = 295; x <= 395; x++) {
                        labels[y * width + x] = x < 345 ? 11 : 10;
                    }
                }
                for (int y = 12; y <= 20; y++) {
                    for (int x = 227; x <= 228; x++) {
                        labels[y * width + x] = 52;
                    }
                }
            }
            return AtlasCoronalPlane.annotationOnly(
                    level, width, height, labels,
                    AtlasPlaneGeometry.axisAligned(
                            level, width, height, height, width));
        }

        @Override
        public Optional<SelectedAtlasRegion> resolveExactAcronym(
                final String acronym) {
            return switch (acronym) {
                case "DG" -> Optional.of(DG);
                case "DG-sg" -> Optional.of(DG_SG);
                case "HPF", "cc", "VS", "root" -> Optional.of(
                        new SelectedAtlasRegion(50 + acronym.length(),
                                acronym, acronym, Set.of(50 + acronym.length())));
                case "CA1" -> Optional.of(CA1);
                default -> Optional.empty();
            };
        }
    }

    private static final class CapturingView
            implements ReviewView {

        private final List<ReviewViewModel> models =
                new ArrayList<>();
        private boolean closed;
        private String lastError = "";

        @Override
        public void render(final ReviewViewModel model) {
            models.add(model);
        }

        @Override
        public void showError(
                final String title,
                final String message) {
            lastError = title + ": " + message;
        }

        @Override
        public void reviewClosed() {
            closed = true;
        }

        ReviewViewModel last() {
            return models.get(models.size() - 1);
        }
    }
}
