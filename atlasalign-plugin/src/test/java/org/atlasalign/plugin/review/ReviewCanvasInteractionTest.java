package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.atlasalign.application.AlignmentReviewBasis;
import org.atlasalign.application.AlignmentReviewSession;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewAcceptanceVerification;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualWarpControl;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ReviewCanvasInteractionTest {

    @Test
    void numericMoveRotateAndScaleEachCommitOneChangeAndUndoExactly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final var basis = ReviewPluginFixtures.scaledAtlasBasis();
            final var review = new ReviewController(new AlignmentReviewSession(basis), ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                    () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()), Runnable::run, Runnable::run);
            final var panel = new SwingReviewPanel(review); review.attach(panel);
            final CanvasContext context = new CanvasContext(panel.canvas(), review);
            final ReviewCanvas canvas = context.canvas();
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.TRANSFORM);
            for (final Runnable edit : List.<Runnable>of(() -> canvas.translateSourcePixels(3, -2),
                    () -> canvas.rotateDegrees(4), () -> canvas.scalePercent(105, 117))) {
                final var before = context.controller().state().content();
                final long revision = context.controller().state().contentRevision();
                edit.run();
                assertEquals(revision + 1, context.controller().state().contentRevision());
                assertNotEquals(before, context.controller().state().content());
                context.controller().undo();
                assertEquals(before, context.controller().state().content());
            }
            context.controller().close();
        });
    }

    @Test
    void canvasArrowNudgesUseSourcePixelsAndInspectionCannotEditGeometry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = context();
            final ReviewCanvas canvas = context.canvas();
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.TRANSFORM);
            final Point2D before = context.controller().state().content().manualPreviewAdjustment().apply(new Point2D(0, 0));
            final long revision = context.controller().state().contentRevision();
            final var metadata = context.controller().state().basis().sourceSnapshot().metadata();
            final double expected = (double) context.controller().state().basis().previewDimensions().width() / metadata.width();
            final Object arrow = canvas.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
            canvas.getActionMap().get(arrow).actionPerformed(new ActionEvent(canvas, 0, "nudge"));
            assertEquals(revision + 1, context.controller().state().contentRevision());
            assertEquals(before.x() + expected, context.controller().state().content().manualPreviewAdjustment().apply(new Point2D(0, 0)).x(), 1e-12);
            context.controller().undo();
            assertEquals(before, context.controller().state().content().manualPreviewAdjustment().apply(new Point2D(0, 0)));
            final Object shifted = canvas.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK));
            canvas.getActionMap().get(shifted).actionPerformed(new ActionEvent(canvas, 0, "nudge"));
            assertEquals(before.x() + 10 * expected, context.controller().state().content().manualPreviewAdjustment().apply(new Point2D(0, 0)).x(), 1e-12);
            final var pinned = context.controller().state();
            canvas.requestChannelDisplay("Inspecting Z2", true);
            canvas.getActionMap().get(arrow).actionPerformed(new ActionEvent(canvas, 0, "nudge"));
            canvas.rotateDegrees(2); canvas.scalePercent(120, 120);
            assertEquals(pinned, context.controller().state());
        });
    }

    @Test
    void reviewerPolygonTraceIsCanvasModalAndInstallsOneSupportRevision()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = context();
            final ReviewCanvas canvas = context.canvas();
            final AtomicInteger translations = new AtomicInteger();
            final AtomicInteger traceCount = new AtomicInteger(-1);
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void translate(
                                final double dx, final double dy) {
                            translations.incrementAndGet();
                        }

                        @Override
                        public void tissueSupportTraceChanged(
                                final int pointCount) {
                            traceCount.set(pointCount);
                        }
                    });
            canvas.setInteractionTool(
                    ReviewCanvas.InteractionTool.TRANSFORM);
            canvas.startTissueSupportTrace();
            final List<Point2D> preview = List.of(
                    new Point2D(20, 18),
                    new Point2D(72, 18),
                    new Point2D(72, 58),
                    new Point2D(20, 58));
            for (final Point2D point : preview) {
                final Point2D screen = canvas.sourceScreenMapping()
                        .previewToScreen(point);
                canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED,
                        screen, 0, 0, MouseEvent.BUTTON1));
            }

            assertEquals(preview.size(),
                    canvas.tissueSupportTracePoints().size());
            for (int index = 0; index < preview.size(); index++) {
                assertEquals(preview.get(index).x(),
                        canvas.tissueSupportTracePoints().get(index).x(),
                        0.2);
                assertEquals(preview.get(index).y(),
                        canvas.tissueSupportTracePoints().get(index).y(),
                        0.2);
            }
            assertEquals(4, traceCount.get());
            assertEquals(0, translations.get(),
                    "polygon tracing must not start atlas placement");

            final long revision = context.controller().state()
                    .contentRevision();
            context.controller().replaceTissueSupportWithPolygon(
                    canvas.tissueSupportTracePoints());
            assertEquals(revision + 1, context.controller().state()
                    .contentRevision());
            assertEquals(4, context.controller().state().content()
                    .reviewedTissueSupport().orElseThrow()
                    .controls().size());

            canvas.cancelTissueSupportTrace();
            assertFalse(canvas.tissueSupportTracing());
            assertTrue(canvas.tissueSupportTracePoints().isEmpty());
        });
    }

    @Test
    void displayControlsStartUnobtrusiveAndRemainDisplayOnly()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = context();
            final ReviewCanvas canvas = context.canvas();
            final AtomicInteger editCalls = new AtomicInteger();
            canvas.setInteractionListener(new ReviewCanvas.InteractionListener() {
                @Override
                public void translate(final double dx, final double dy) {
                    editCalls.incrementAndGet();
                }
            });

            assertNull(canvas.getToolTipText(),
                    "a canvas tooltip must not cover anatomy during placement");
            assertFalse(canvas.landmarkLabelsVisible());
            assertFalse(canvas.deformationGridVisible());
            assertEquals(0.65, canvas.overlayOpacity(), 1e-6);

            canvas.setLandmarkLabelsVisible(true);
            canvas.setDeformationGridVisible(true);
            canvas.setOverlayOpacity(0.4);

            assertTrue(canvas.landmarkLabelsVisible());
            assertTrue(canvas.deformationGridVisible());
            assertEquals(0.4, canvas.overlayOpacity(), 1e-6);
            assertEquals(0, editCalls.get(),
                    "view switches must not request a scientific edit");
            assertThrows(IllegalArgumentException.class,
                    () -> canvas.setOverlayOpacity(1.01));
        });
    }

    @Test
    void activeSideAndAddOneArmAreDisplayOnlyAndEscapeCancels()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final ReviewCanvas canvas = canvas();
            final AtomicInteger addCalls = new AtomicInteger();
            canvas.setActiveHemisphereSide(
                    ManualHemisphereWarp2D.AtlasSide.RIGHT);
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void manualWarpPointSelected(
                                final Point2D point) {
                            addCalls.incrementAndGet();
                        }
                    });

            assertEquals(ManualHemisphereWarp2D.AtlasSide.RIGHT,
                    canvas.activeHemisphereSide());
            canvas.armManualWarpPointPlacement();
            assertTrue(canvas.manualWarpPointPlacementArmed());
            assertEquals("cancel-canvas-gesture",
                    canvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                            .get(KeyStroke.getKeyStroke(
                                    KeyEvent.VK_ESCAPE, 0)),
                    "Escape must cancel even while the Add-one button retains focus");
            canvas.getActionMap().get("cancel-canvas-gesture")
                    .actionPerformed(new ActionEvent(
                            canvas, ActionEvent.ACTION_PERFORMED, "escape"));
            assertFalse(canvas.manualWarpPointPlacementArmed());
            assertEquals(0, addCalls.get(),
                    "Escape must not commit an interior point");
        });
    }

    @Test
    @org.junit.jupiter.api.Disabled("Superseded by dot-first Structure refinement")
    void structureSideHandleRequestsWidthOnlyGroupTransform()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = structureContext();
            context.controller().selectAtlasRegionExactAcronym("DG");
            context.controller().replaceSelectedStructureControls(
                    4, ManualHemisphereWarp2D.AtlasSide.LEFT);
            final ReviewCanvas canvas = context.canvas();
            canvas.setActiveHemisphereSide(
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            canvas.setStructureEditingEnabled(true);
            assertEquals(4, context.controller().state().content()
                    .hemisphereWarp().orElseThrow().manualControls(
                            ManualHemisphereWarp2D.AtlasSide.LEFT).stream()
                    .filter(control -> control.origin()
                            == org.atlasalign.application.manual
                                    .ManualWarpControlOrigin.STRUCTURE_GUIDE)
                    .count(), context.controller().state().content()
                            .hemisphereWarp().orElseThrow().manualControls(
                                    ManualHemisphereWarp2D.AtlasSide.LEFT)
                            .toString());
            final List<Point2D> handles =
                    canvas.structureResizeHandleScreenPoints();
            assertEquals(8, handles.size());
            final AtomicReference<List<Point2D>> requested =
                    new AtomicReference<>();
            final AtomicReference<List<String>> identifiers =
                    new AtomicReference<>();
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void transformStructureControls(
                                final List<String> controlIds,
                                final List<Point2D> requestedTargets) {
                            identifiers.set(controlIds);
                            requested.set(requestedTargets);
                        }
                    });
            final List<org.atlasalign.application.manual.ManualWarpControl>
                    before = context.controller().state().content()
                    .hemisphereWarp().orElseThrow().manualControls(
                            ManualHemisphereWarp2D.AtlasSide.LEFT);

            drag(canvas, handles.get(5), 18, 0, true, false);

            assertEquals(before.stream().map(control -> control.id()).toList(),
                    identifiers.get());
            assertTrue(java.util.stream.IntStream.range(0, before.size())
                    .anyMatch(index -> Math.abs(requested.get().get(index).x()
                            - before.get(index).targetPoint().x()) > 1e-6));
            for (int index = 0; index < before.size(); index++) {
                assertEquals(before.get(index).targetPoint().y(),
                        requested.get().get(index).y(), 1e-9,
                        "a side handle must leave height unchanged");
            }
        });
    }

    @Test
    @org.junit.jupiter.api.Disabled("Superseded by ROI size and Blade gap sliders")
    void structureBoxSupportsHeightCornerProportionalAndTranslationGestures()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final StructureGesture height = structureGesture(
                    4, 0, -14, false, false);
            assertTrue(java.util.stream.IntStream.range(
                            0, height.before().size())
                    .anyMatch(index -> Math.abs(height.requested().get(index)
                            .y() - height.before().get(index).y()) > 1e-6));
            for (int index = 0; index < height.before().size(); index++) {
                assertEquals(height.before().get(index).x(),
                        height.requested().get(index).x(), 1e-9,
                        "a top edge handle must leave width unchanged");
            }

            final StructureGesture corner = structureGesture(
                    2, 20, 7, false, false);
            final double freeWidthScale = extentX(corner.requested())
                    / extentX(corner.before());
            final double freeHeightScale = extentY(corner.requested())
                    / extentY(corner.before());
            assertNotEquals(freeWidthScale, freeHeightScale, 1e-4,
                    "an unmodified corner drag permits independent width and height");

            final StructureGesture proportional = structureGesture(
                    2, 20, 7, true, false);
            assertEquals(extentX(proportional.requested())
                            / extentX(proportional.before()),
                    extentY(proportional.requested())
                            / extentY(proportional.before()),
                    1e-9, "Shift on a corner must preserve proportions");

            final StructureGesture translated = structureGesture(
                    -1, 11, -8, false, true);
            final double dx = translated.requested().get(0).x()
                    - translated.before().get(0).x();
            final double dy = translated.requested().get(0).y()
                    - translated.before().get(0).y();
            assertTrue(Math.abs(dx) > 1e-6 && Math.abs(dy) > 1e-6);
            for (int index = 1; index < translated.before().size(); index++) {
                assertEquals(dx, translated.requested().get(index).x()
                        - translated.before().get(index).x(), 1e-9);
                assertEquals(dy, translated.requested().get(index).y()
                        - translated.before().get(index).y(), 1e-9);
            }
        });
    }

    @Test
    @org.junit.jupiter.api.Disabled("Superseded by dot-first Structure refinement")
    void structureBoxOrthogonalNoOpDoesNotRequestASolve()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = structureContext();
            context.controller().selectAtlasRegionExactAcronym("DG");
            context.controller().replaceSelectedStructureControls(
                    4, ManualHemisphereWarp2D.AtlasSide.LEFT);
            final ReviewCanvas canvas = context.canvas();
            canvas.setActiveHemisphereSide(
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            canvas.setStructureEditingEnabled(true);
            final List<Point2D> handles =
                    canvas.structureResizeHandleScreenPoints();
            final AtomicInteger solveRequests = new AtomicInteger();
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void transformStructureControls(
                                final List<String> controlIds,
                                final List<Point2D> requestedTargets) {
                            solveRequests.incrementAndGet();
                        }
                    });

            drag(canvas, handles.get(5), 0, 14, true, false);

            assertEquals(0, solveRequests.get(),
                    "moving a width handle only vertically is a no-op, not a pending audit");
        });
    }

    @Test
    @org.junit.jupiter.api.Disabled("Superseded by the audited Blade gap slider")
    void structureGapMovesTwoPrincipalBladesEquallyApartWithoutResizingThem()
            throws Exception {
        final AtomicReference<CanvasContext> contextRef =
                new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = twoBladeStructureContext();
            context.controller().selectAtlasRegionExactAcronym("DG");
            context.controller().replaceSelectedStructureControls(
                    6, ManualHemisphereWarp2D.AtlasSide.LEFT);
            context.canvas().setActiveHemisphereSide(
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            context.canvas().setInteractionTool(
                    ReviewCanvas.InteractionTool.POINTS);
            context.canvas().setStructureEditingEnabled(true);
            contextRef.set(context);
        });
        final CanvasContext context = contextRef.get();
        final Point2D gapHandle = awaitStructureGapHandle(context.canvas());

        SwingUtilities.invokeAndWait(() -> {
            final var controls = context.controller().state().content()
                    .hemisphereWarp().orElseThrow().manualControls(
                            ManualHemisphereWarp2D.AtlasSide.LEFT);
            assertEquals(6, controls.size());
            final List<Point2D> before = controls.stream()
                    .map(control -> control.targetPoint()).toList();
            final double split = before.stream().mapToDouble(Point2D::y)
                    .average().orElseThrow();
            final List<Integer> upper = java.util.stream.IntStream.range(
                            0, before.size())
                    .filter(index -> before.get(index).y() < split)
                    .boxed().toList();
            final List<Integer> lower = java.util.stream.IntStream.range(
                            0, before.size())
                    .filter(index -> before.get(index).y() >= split)
                    .boxed().toList();
            assertTrue(upper.size() >= 2 && lower.size() >= 2,
                    "both principal blades must receive at least two controls");
            final List<Point2D> handles = context.canvas()
                    .structureResizeHandleScreenPoints();
            final Point2D tinyFragment = context.canvas()
                    .sourceScreenMapping().previewToScreen(
                            context.controller().state().mapAtlasToPreview(
                                    new Point2D(114, 239)));
            assertTrue(handles.stream().mapToDouble(Point2D::y).max()
                            .orElseThrow() < tinyFragment.y(),
                    "the resize box must exclude the remote tiny fragment");

            final AtomicReference<List<String>> requestedIds =
                    new AtomicReference<>();
            final AtomicReference<List<Point2D>> requestedTargets =
                    new AtomicReference<>();
            context.canvas().setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void transformStructureControls(
                                final List<String> controlIds,
                                final List<Point2D> targets) {
                            requestedIds.set(List.copyOf(controlIds));
                            requestedTargets.set(List.copyOf(targets));
                        }
                    });

            final Point2D gapAxis = context.canvas()
                    .structureGapAxisPreview().orElseThrow();
            drag(context.canvas(), gapHandle,
                    (int) Math.round(gapAxis.x() * 24),
                    (int) Math.round(gapAxis.y() * 24), true);

            assertEquals(controls.stream().map(control -> control.id()).toList(),
                    requestedIds.get());
            final List<Point2D> requested = requestedTargets.get();
            assertEquals(extentX(subset(before, upper)),
                    extentX(subset(requested, upper)), 1e-9);
            assertEquals(extentY(subset(before, upper)),
                    extentY(subset(requested, upper)), 1e-9);
            assertEquals(extentX(subset(before, lower)),
                    extentX(subset(requested, lower)), 1e-9);
            assertEquals(extentY(subset(before, lower)),
                    extentY(subset(requested, lower)), 1e-9);
            final double upperShift = meanY(subset(requested, upper))
                    - meanY(subset(before, upper));
            final double lowerShift = meanY(subset(requested, lower))
                    - meanY(subset(before, lower));
            assertTrue(upperShift < 0 && lowerShift > 0,
                    "dragging the double-arrow must widen the inter-blade gap");
            assertEquals(0, upperShift + lowerShift, 1e-9,
                    "principal components move equally in opposite directions");
        });
    }

    @Test
    void structureRefinementUsesLargeTransientDotsAndNoImmediateRevision()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = structureContext();
            context.controller().selectAtlasRegionExactAcronym("DG");
            final long revision = context.controller().state()
                    .contentRevision();
            context.controller().replaceSelectedStructureControls(
                    4, ManualHemisphereWarp2D.AtlasSide.LEFT);
            final StructureAdjustmentDraft draft = context.controller()
                    .structureAdjustmentState().draft().orElseThrow();
            assertEquals(4, draft.requestedControls().size());
            assertEquals(revision, context.controller().state()
                    .contentRevision());
            assertTrue(context.controller().state().content()
                    .hemisphereWarp().isEmpty());

            final ReviewCanvas canvas = context.canvas();
            canvas.setActiveHemisphereSide(
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            canvas.setStructureEditingEnabled(true);
            final AtomicReference<List<String>> identifiers =
                    new AtomicReference<>();
            final AtomicReference<List<Point2D>> requested =
                    new AtomicReference<>();
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void transformStructureControls(
                                final List<String> controlIds,
                                final List<Point2D> targets) {
                            identifiers.set(controlIds);
                            requested.set(targets);
                        }
                    });
            final var point = draft.requestedControls().get(0);
            final Point2D screen = canvas.sourceScreenMapping()
                    .previewToScreen(point.targetPoint());
            drag(canvas, screen, 12, -5, true);

            assertEquals(List.of(point.id()), identifiers.get());
            assertEquals(1, requested.get().size());
            assertNotEquals(point.targetPoint(), requested.get().get(0));
            assertEquals(revision, context.controller().state()
                    .contentRevision());
        });
    }

    @Test
    void twoPrincipalComponentsReceiveDotsAndGapSliderMovesThemSymmetrically()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = twoBladeStructureContext();
            context.controller().selectAtlasRegionExactAcronym("DG");
            context.controller().replaceSelectedStructureControls(
                    6, ManualHemisphereWarp2D.AtlasSide.LEFT);
            final StructureAdjustmentDraft before = context.controller()
                    .structureAdjustmentState().draft().orElseThrow();
            final long first = before.componentByControlId().values().stream()
                    .filter(component -> component == 0).count();
            final long second = before.componentByControlId().values().stream()
                    .filter(component -> component == 1).count();
            assertTrue(first >= 2 && second >= 2,
                    "each principal blade must receive at least two dots");
            final long revision = context.controller().state()
                    .contentRevision();

            context.controller().setStructureAdjustmentSliders(
                    ManualHemisphereWarp2D.AtlasSide.LEFT, 100, 50);
            final StructureAdjustmentDraft after = context.controller()
                    .structureAdjustmentState().draft().orElseThrow();
            final java.util.Map<String, Point2D> beforeById = before
                    .requestedControls().stream().collect(
                            java.util.stream.Collectors.toMap(
                                    control -> control.id(),
                                    control -> control.targetPoint()));
            final java.util.Map<Integer, List<Point2D>> deltas = after
                    .requestedControls().stream()
                    .collect(
                            java.util.stream.Collectors.groupingBy(
                                    control -> after.componentByControlId()
                                            .get(control.id()),
                                    java.util.stream.Collectors.mapping(
                                            control -> new Point2D(
                                                    control.targetPoint().x()
                                                            - beforeById.get(
                                                            control.id()).x(),
                                                    control.targetPoint().y()
                                                            - beforeById.get(
                                                            control.id()).y()),
                                            java.util.stream.Collectors
                                                    .toList())));
            final Point2D firstDelta = deltas.get(0).get(0);
            final Point2D secondDelta = deltas.get(1).get(0);
            deltas.get(0).forEach(delta -> {
                assertEquals(firstDelta.x(), delta.x(), 1e-9);
                assertEquals(firstDelta.y(), delta.y(), 1e-9);
            });
            deltas.get(1).forEach(delta -> {
                assertEquals(secondDelta.x(), delta.x(), 1e-9);
                assertEquals(secondDelta.y(), delta.y(), 1e-9);
            });
            assertEquals(0, firstDelta.x() + secondDelta.x(), 1e-9);
            assertEquals(0, firstDelta.y() + secondDelta.y(), 1e-9);
            assertTrue(Math.hypot(firstDelta.x(), firstDelta.y()) > 0);
            assertEquals(revision, context.controller().state()
                    .contentRevision(),
                    "the coherent whole-blade gap slider remains transient until Apply");
        });
    }

    @Test
    void roiThicknessSliderChangesStablePairDistancesOnly()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = twoBladeStructureContext();
            context.controller().selectAtlasRegionExactAcronym("DG");
            final long revision = context.controller().state()
                    .contentRevision();
            context.controller().replaceSelectedStructureControls(
                    8, ManualHemisphereWarp2D.AtlasSide.LEFT);
            final StructureAdjustmentDraft before = context.controller()
                    .structureAdjustmentState().draft().orElseThrow();

            context.controller().setStructureAdjustmentSliders(
                    ManualHemisphereWarp2D.AtlasSide.LEFT, 300, 0);
            final StructureAdjustmentDraft after = context.controller()
                    .structureAdjustmentState().draft().orElseThrow();
            final java.util.Map<String, Point2D> beforeById = before
                    .requestedControls().stream().collect(
                            java.util.stream.Collectors.toMap(
                                    ManualWarpControl::id,
                                    ManualWarpControl::targetPoint));
            final java.util.Map<String, Point2D> afterById = after
                    .requestedControls().stream().collect(
                            java.util.stream.Collectors.toMap(
                                    ManualWarpControl::id,
                                    ManualWarpControl::targetPoint));
            for (final StructureAdjustmentUnit unit : before.units()) {
                if (!unit.paired()) {
                    final String id = unit.controlIds().get(0);
                    assertEquals(beforeById.get(id), afterById.get(id));
                    continue;
                }
                final Point2D beforeFirst = beforeById.get(
                        unit.controlIds().get(0));
                final Point2D beforeSecond = beforeById.get(
                        unit.controlIds().get(1));
                final Point2D afterFirst = afterById.get(
                        unit.controlIds().get(0));
                final Point2D afterSecond = afterById.get(
                        unit.controlIds().get(1));
                assertEquals(Math.hypot(beforeSecond.x() - beforeFirst.x(),
                                beforeSecond.y() - beforeFirst.y()) * 3.0,
                        Math.hypot(afterSecond.x() - afterFirst.x(),
                                afterSecond.y() - afterFirst.y()), 1e-9);
                assertPoint(midpoint(beforeFirst, beforeSecond),
                        midpoint(afterFirst, afterSecond));
            }
            assertEquals(revision, context.controller().state()
                    .contentRevision());

            context.controller().cancelStructureChanges();

            assertTrue(context.controller().structureAdjustmentState()
                    .draft().isEmpty());
            assertTrue(context.controller().state().content()
                    .hemisphereWarp().isEmpty());
            assertEquals(revision, context.controller().state()
                    .contentRevision(),
                    "Cancel must install nothing and create no revision");
        });
    }

    @Test
    void exportedRoiPreviewDimsContextWithoutCreatingARevision()
            throws Exception {
        final AtomicReference<CanvasContext> contextRef =
                new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = structureContext();
            context.controller().selectAtlasRegionExactAcronym("DG");
            context.canvas().setExportedRoiPreviewVisible(true);
            contextRef.set(context);
        });
        final CanvasContext context = contextRef.get();
        for (int attempt = 0; attempt < 100
                && context.canvas().exportedRoiPreviewImageForTests() == null;
                attempt++) {
            Thread.sleep(20);
        }
        final var preview = context.canvas()
                .exportedRoiPreviewImageForTests();
        final var original = context.canvas().basePreviewImageForTests();
        assertTrue(preview != null);
        int bright = 0;
        int dim = 0;
        for (int y = 0; y < preview.getHeight(); y++) {
            for (int x = 0; x < preview.getWidth(); x++) {
                final int source = original.getRaster().getSample(x, y, 0);
                if (source < 20) {
                    continue;
                }
                final int shown = preview.getRaster().getSample(x, y, 0);
                if (shown == source) {
                    bright++;
                } else if (Math.abs(shown - Math.round(source * 0.2f)) <= 1) {
                    dim++;
                }
            }
        }
        assertTrue(bright > 0, "selected ROI must retain full brightness");
        assertTrue(dim > 0, "remaining tissue must be shown at 20% brightness");
        assertEquals(0, context.controller().state().contentRevision());
    }

    @Test
    void dirtyStructureRequestKeepsPreviewingInstalledExportMembership()
            throws Exception {
        final AtomicReference<CanvasContext> contextRef =
                new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = structureContext();
            context.controller().selectAtlasRegionExactAcronym("DG");
            context.canvas().setExportedRoiPreviewVisible(true);
            contextRef.set(context);
        });
        final CanvasContext context = contextRef.get();
        final int installedHash = awaitExportPreviewHash(context.canvas());
        final long revision = context.controller().state().contentRevision();

        SwingUtilities.invokeAndWait(() -> context.controller()
                .replaceSelectedStructureControls(
                        8, ManualHemisphereWarp2D.AtlasSide.LEFT));

        assertEquals(installedHash,
                awaitExportPreviewHash(context.canvas()),
                "an uncalculated cyan request must not replace the installed export membership");
        assertEquals(revision, context.controller().state()
                .contentRevision());
    }

    @Test
    void tissueZoomIsIndependentCursorStableAndClamped() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final ReviewCanvas canvas = canvas();
            final ScreenMapping tissueBefore = canvas.sourceScreenMapping();
            final ScreenMapping atlasBefore = canvas.atlasScreenMapping();
            final Point2D anchor = new Point2D(202, 212);
            final Point2D anchoredPreview =
                    tissueBefore.screenToPreview(anchor);

            canvas.zoomTissueIn();

            final ScreenMapping tissueAfter = canvas.sourceScreenMapping();
            assertEquals(tissueBefore.scale() * 1.25,
                    tissueAfter.scale(), 1e-12);
            assertEquals(atlasBefore, canvas.atlasScreenMapping());
            assertEquals(anchoredPreview.x(),
                    tissueAfter.screenToPreview(anchor).x(), 1e-12);
            assertEquals(anchoredPreview.y(),
                    tissueAfter.screenToPreview(anchor).y(), 1e-12);
            for (int index = 0; index < 30; index++) {
                canvas.zoomTissueIn();
            }
            assertEquals(tissueBefore.scale() * 16,
                    canvas.sourceScreenMapping().scale(), 1e-12);
            canvas.fitTissueView();
            assertEquals(tissueBefore, canvas.sourceScreenMapping());
            for (int index = 0; index < 30; index++) {
                canvas.zoomTissueOut();
            }
            final ScreenMapping zoomedOut = canvas.sourceScreenMapping();
            assertEquals(tissueBefore.scale() * 0.25,
                    zoomedOut.scale(), 1e-12);
            assertEquals(anchoredPreview.x(),
                    zoomedOut.screenToPreview(anchor).x(), 1e-12);
            assertEquals(anchoredPreview.y(),
                    zoomedOut.screenToPreview(anchor).y(), 1e-12);
            canvas.fitTissueView();
            assertEquals(tissueBefore, canvas.sourceScreenMapping());
        });
    }

    @Test
    void transformPreviewCommitsOnceAndEscapeCancels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final ReviewCanvas canvas = canvas();
            final AtomicInteger calls = new AtomicInteger();
            final AtomicReference<Point2D> delta = new AtomicReference<>();
            canvas.setInteractionListener(new ReviewCanvas.InteractionListener() {
                @Override
                public void translate(final double dx, final double dy) {
                    calls.incrementAndGet();
                    delta.set(new Point2D(dx, dy));
                }
            });
            final Point2D start = canvas.sourceScreenMapping()
                    .previewToScreen(new Point2D(70, 60));
            drag(canvas, start, 20, 10, false);
            assertEquals(0, calls.get());
            canvas.getActionMap().get("cancel-canvas-gesture")
                    .actionPerformed(new ActionEvent(
                            canvas, ActionEvent.ACTION_PERFORMED, "escape"));
            release(canvas, start, 20, 10);
            assertEquals(0, calls.get());
            assertNull(delta.get());

            drag(canvas, start, 20, 10, true);
            assertEquals(1, calls.get());
            assertEquals(canvas.sourceScreenMapping()
                    .screenDeltaToPreview(20), delta.get().x(), 1e-9);
            assertEquals(canvas.sourceScreenMapping()
                    .screenDeltaToPreview(10), delta.get().y(), 1e-9);
        });
    }

    @Test
    void guidedBoundaryClickMatchesActiveAtlasDotWithoutShift()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = guidedContext();
            final ReviewCanvas canvas = context.canvas();
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void setBoundaryFitTissuePoint(
                                final String id,
                                final Point2D point) {
                            context.controller().setBoundaryFitTissuePoint(
                                    id, point);
                        }
                    });
            context.controller().startBoundaryFitMatching(
                    org.atlasalign.application.manual.BoundaryFitModel
                            .SIMILARITY,
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            final var draft = context.controller().boundaryFitState().draft()
                    .orElseThrow();
            final var anchor = draft.activeAnchor().orElseThrow();
            final var tissue = draft.request().tissueBoundary().stream()
                    .max(java.util.Comparator.comparingDouble(sample ->
                            draft.anchors().stream()
                                    .mapToDouble(existing -> Math.hypot(
                                            sample.point().x() - existing
                                                    .atlasPreviewPoint().x(),
                                            sample.point().y() - existing
                                                    .atlasPreviewPoint().y()))
                                    .min().orElse(0)))
                    .orElseThrow().point();
            final Point2D screen = canvas.sourceScreenMapping()
                    .previewToScreen(tissue);

            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED,
                    screen, 0, 0, MouseEvent.BUTTON1));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED,
                    screen, 0, 0, MouseEvent.BUTTON1));

            final var updated = context.controller().boundaryFitState()
                    .draft().orElseThrow();
            assertTrue(updated.anchors().stream()
                    .filter(value -> value.id().equals(anchor.id()))
                    .findFirst().orElseThrow().tissuePreviewPoint()
                    .isPresent());
            assertEquals(0, context.controller().state().contentRevision(),
                    "matching a transient endpoint must not create history");
        });
    }

    @Test
    void zoomedAndPannedMatchClickKeepsAnExactFreeOffCyanEndpoint()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = guidedContext();
            final ReviewCanvas canvas = context.canvas();
            canvas.setSingleTissuePane(false);
            canvas.setBoundaryFitEditingEnabled(true);
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void setBoundaryFitTissuePoint(
                                final String id,
                                final Point2D point) {
                            context.controller().setBoundaryFitTissuePoint(
                                    id, point);
                        }
                    });
            context.controller().startBoundaryFitMatching(
                    org.atlasalign.application.manual.BoundaryFitModel
                            .SIMILARITY,
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            final var draft = context.controller().boundaryFitState().draft()
                    .orElseThrow();
            final String anchorId = draft.activeAnchor().orElseThrow().id();

            canvas.zoomTissueIn();
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.PAN);
            drag(canvas, new Point2D(180, 180), 13, -9, true);
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.TRANSFORM);
            final ScreenMapping mapping = canvas.sourceScreenMapping();
            final Point2D requested = new Point2D(50.125, 40.375);
            final Point2D screen = mapping.previewToScreen(requested);
            final Point2D integralScreen = new Point2D(
                    Math.round(screen.x()), Math.round(screen.y()));
            final Point2D expected = mapping.screenToPreview(integralScreen);
            final double nearestCyanScreenDistance = draft.request()
                    .tissueBoundary().stream().mapToDouble(sample ->
                            Math.hypot(mapping.previewToScreen(sample.point()).x()
                                            - integralScreen.x(),
                                    mapping.previewToScreen(sample.point()).y()
                                            - integralScreen.y()))
                    .min().orElseThrow();
            assertTrue(nearestCyanScreenDistance > 16,
                    "the fixture click must exercise the free endpoint path");

            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED,
                    integralScreen, 0, 0, MouseEvent.BUTTON1));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED,
                    integralScreen, 0, 0, MouseEvent.BUTTON1));

            final Point2D actual = context.controller().boundaryFitState()
                    .draft().orElseThrow().anchors().stream()
                    .filter(anchor -> anchor.id().equals(anchorId))
                    .findFirst().orElseThrow().tissuePreviewPoint()
                    .orElseThrow();
            assertEquals(expected.x(), actual.x(), 1e-12);
            assertEquals(expected.y(), actual.y(), 1e-12);
            assertEquals(0, context.controller().state().contentRevision());
        });
    }

    @Test
    void guidedAtlasDotCanBeDraggedDirectlyOntoTheTissueBorder()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = guidedContext();
            final ReviewCanvas canvas = context.canvas();
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void setBoundaryFitTissuePoint(
                                final String id,
                                final Point2D point) {
                            context.controller().setBoundaryFitTissuePoint(
                                    id, point);
                        }

                        @Override
                        public void moveBoundaryFitMatch(
                                final String id,
                                final ReviewController.BoundaryFitEndpoint endpoint,
                                final Point2D point) {
                            context.controller().moveBoundaryFitMatch(
                                    id, endpoint, point);
                        }
                    });
            context.controller().startBoundaryFitMatching(
                    org.atlasalign.application.manual.BoundaryFitModel
                            .SIMILARITY,
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            final var draft = context.controller().boundaryFitState().draft()
                    .orElseThrow();
            final var anchor = draft.activeAnchor().orElseThrow();
            final Point2D originalAtlas = anchor.atlasPreviewPoint();
            final Point2D tissue = draft.request().tissueBoundary().stream()
                    .max(java.util.Comparator.comparingDouble(sample ->
                            Math.hypot(sample.point().x()
                                    - originalAtlas.x(),
                                    sample.point().y()
                                    - originalAtlas.y())))
                    .orElseThrow().point();
            final Point2D start = canvas.atlasScreenMapping()
                    .previewToScreen(anchor.atlasPlanePoint());
            final Point2D end = canvas.sourceScreenMapping()
                    .previewToScreen(tissue);
            final int dx = (int) Math.round(end.x() - start.x());
            final int dy = (int) Math.round(end.y() - start.y());

            drag(canvas, start, dx, dy, true);

            final var updated = context.controller().boundaryFitState()
                    .draft().orElseThrow().anchors().stream()
                    .filter(value -> value.id().equals(anchor.id()))
                    .findFirst().orElseThrow();
            assertTrue(updated.tissuePreviewPoint().isPresent(),
                    "dropping a numbered atlas dot on cyan must complete the pair");
            assertEquals(originalAtlas, updated.atlasPreviewPoint(),
                    "the drag creates the tissue endpoint without moving the atlas anchor");
            assertEquals(0, context.controller().state().contentRevision(),
                    "matching a transient endpoint must not create history");
        });
    }

    @Test
    void completedGuidedAtlasEndpointStillSlidesAlongAtlasExterior()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = guidedContext();
            final ReviewCanvas canvas = context.canvas();
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void moveBoundaryFitMatch(
                                final String id,
                                final ReviewController.BoundaryFitEndpoint endpoint,
                                final Point2D point) {
                            context.controller().moveBoundaryFitMatch(
                                    id, endpoint, point);
                        }
                    });
            context.controller().startBoundaryFitMatching(
                    org.atlasalign.application.manual.BoundaryFitModel
                            .SIMILARITY,
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            final var initial = context.controller().boundaryFitState()
                    .draft().orElseThrow();
            final var anchor = initial.activeAnchor().orElseThrow();
            final Point2D tissue = initial.request().tissueBoundary().get(0)
                    .point();
            context.controller().setBoundaryFitTissuePoint(
                    anchor.id(), tissue);
            final var completed = context.controller().boundaryFitState()
                    .draft().orElseThrow().anchors().stream()
                    .filter(value -> value.id().equals(anchor.id()))
                    .findFirst().orElseThrow();
            final Point2D target = initial.request().atlasBoundary().stream()
                    .max(java.util.Comparator.comparingDouble(sample ->
                            Math.hypot(sample.point().x()
                                    - completed.atlasPreviewPoint().x(),
                                    sample.point().y()
                                    - completed.atlasPreviewPoint().y())))
                    .orElseThrow().point();
            final Point2D canonicalTarget = initial.request()
                    .currentOrientedAtlasToPreview().inverse().apply(target);
            final Point2D start = canvas.atlasScreenMapping()
                    .previewToScreen(completed.atlasPlanePoint());
            final Point2D end = canvas.atlasScreenMapping()
                    .previewToScreen(canonicalTarget);

            drag(canvas, start,
                    (int) Math.round(end.x() - start.x()),
                    (int) Math.round(end.y() - start.y()), true);

            final var moved = context.controller().boundaryFitState()
                    .draft().orElseThrow().anchors().stream()
                    .filter(value -> value.id().equals(anchor.id()))
                    .findFirst().orElseThrow();
            assertFalse(moved.atlasPreviewPoint().equals(
                    completed.atlasPreviewPoint()));
            assertEquals(completed.tissuePreviewPoint(),
                    moved.tissuePreviewPoint(),
                    "editing a completed amber endpoint must retain its tissue match");
            assertEquals(0, context.controller().state().contentRevision());
        });
    }

    @Test
    void reflectedHalfFullAndDisjoinedAtlasPaneDragsUseRawCoordinates()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            verifyReflectedAtlasPaneDrag(SectionGeometry.FULL,
                    ReviewSectionMode.FULL,
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            verifyReflectedAtlasPaneDrag(SectionGeometry.IMAGE_LEFT_HALF,
                    ReviewSectionMode.HALF,
                    ManualHemisphereWarp2D.AtlasSide.RIGHT);
            verifyReflectedAtlasPaneDrag(SectionGeometry.FULL,
                    ReviewSectionMode.DISJOINED,
                    ManualHemisphereWarp2D.AtlasSide.RIGHT);
        });
    }

    @Test
    void guidedDraftMakesTransformBodyAndHandlesModalUntilExplicitExit()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = guidedContext();
            final ReviewCanvas canvas = context.canvas();
            context.controller().startBoundaryFitMatching(
                    org.atlasalign.application.manual.BoundaryFitModel
                            .SIMILARITY,
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            final AtomicInteger cancellations = new AtomicInteger();
            final AtomicInteger translations = new AtomicInteger();
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void cancelBoundaryFitForManualTransform() {
                            cancellations.incrementAndGet();
                            context.controller().cancelBoundaryFit();
                        }

                        @Override
                        public boolean canPreviewTranslate(
                                final double dx,
                                final double dy) {
                            return context.controller().canPreviewTranslate(
                                    dx, dy);
                        }

                        @Override
                        public void translate(
                                final double dx,
                                final double dy) {
                            translations.incrementAndGet();
                            context.controller().translate(dx, dy);
                        }
                    });
            final long revision = context.controller().state()
                    .contentRevision();
            final List<Point2D> handles =
                    canvas.transformResizeHandleScreenPoints();
            final Point2D body = new Point2D(
                    handles.stream().mapToDouble(Point2D::x).average()
                            .orElseThrow(),
                    handles.stream().mapToDouble(Point2D::y).average()
                            .orElseThrow());

            drag(canvas, body, 20, 10, true);
            drag(canvas, handles.get(0), 20, 10, true);

            assertEquals(0, cancellations.get(),
                    "canvas placement geometry must not cancel matching");
            assertEquals(0, translations.get(),
                    "body and resize handles are inert while matching is active");
            assertTrue(context.controller().boundaryFitState().draft()
                    .isPresent());
            assertEquals(revision, context.controller().state()
                    .contentRevision());

            // Represents the explicit toolbar Exit match/Cancel action. Once
            // the reviewer deliberately exits, the same body drag is a
            // normal one-revision Transform gesture.
            context.controller().cancelBoundaryFit();
            drag(canvas, body, 20, 10, true);

            assertEquals(1, translations.get());
            assertEquals(revision + 1, context.controller().state()
                    .contentRevision());
        });
    }

    @Test
    void guidedLoadingStateIsModalBeforeNumberedDraftArrives()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final AlignmentReviewBasis basis =
                    ReviewPluginFixtures.segmentedScaledAtlasBasis();
            final ArrayDeque<Runnable> queuedWarpWork = new ArrayDeque<>();
            final ReviewController controller = new ReviewController(
                    new AlignmentReviewSession(basis),
                    ReviewPluginFixtures.segmentedPreview(),
                    ReviewPluginFixtures::plane,
                    () -> new ReviewAcceptanceVerification(
                            basis.sourceSnapshot(), basis.atlas()),
                    Runnable::run,
                    queuedWarpWork::addLast,
                    Runnable::run,
                    () -> { });
            final ReviewCanvas canvas = new ReviewCanvas(controller);
            canvas.setSize(820, 400);
            controller.attach(new ReviewView() {
                @Override
                public void render(final ReviewViewModel model) {
                    canvas.setModel(model);
                }

                @Override
                public void showError(
                        final String title,
                        final String message) { }

                @Override
                public void reviewClosed() { }
            });
            final AtomicInteger cancellations = new AtomicInteger();
            final AtomicInteger translations = new AtomicInteger();
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void cancelBoundaryFitForManualTransform() {
                            cancellations.incrementAndGet();
                        }

                        @Override
                        public void translate(
                                final double dx,
                                final double dy) {
                            translations.incrementAndGet();
                        }
                    });

            controller.startBoundaryFitMatching(
                    org.atlasalign.application.manual.BoundaryFitModel
                            .SIMILARITY,
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            assertTrue(controller.boundaryFitState().active());
            assertTrue(controller.boundaryFitState().loading());
            assertTrue(controller.boundaryFitState().draft().isEmpty());
            assertEquals(1, queuedWarpWork.size(),
                    "draft construction must still be pending");
            final long revision = controller.state().contentRevision();
            final List<Point2D> handles =
                    canvas.transformResizeHandleScreenPoints();
            final Point2D body = new Point2D(
                    handles.stream().mapToDouble(Point2D::x).average()
                            .orElseThrow(),
                    handles.stream().mapToDouble(Point2D::y).average()
                            .orElseThrow());

            drag(canvas, body, 20, 10, true);
            drag(canvas, handles.get(0), 20, 10, true);

            assertEquals(0, cancellations.get());
            assertEquals(0, translations.get());
            assertEquals(revision, controller.state().contentRevision());
            assertTrue(controller.boundaryFitState().loading(),
                    "canvas gestures must not escape the modal loading state");
            assertTrue(controller.boundaryFitState().draft().isEmpty());
        });
    }

    @Test
    void refreshingCurrentToolPreservesHeldSpaceUntilRelease() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = halfDirectContext();
            final ReviewCanvas canvas = context.canvas();
            final AtomicInteger translations = new AtomicInteger();
            canvas.setInteractionListener(new ReviewCanvas.InteractionListener() {
                @Override
                public void translate(final double dx, final double dy) {
                    translations.incrementAndGet();
                    context.controller().translate(dx, dy);
                }
            });
            final long revision = context.controller().state().contentRevision();
            canvas.getActionMap().get("canvas-space-down").actionPerformed(
                    new ActionEvent(canvas, ActionEvent.ACTION_PERFORMED, "space-down"));
            // Normal Setup rendering re-applies the active Transform tool.
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.TRANSFORM);
            final Point2D body = canvas.sourceScreenMapping()
                    .previewToScreen(new Point2D(80, 70));
            nativePrimaryDrag(canvas, body, 12, 6);
            assertEquals(0, translations.get(), "A held Space must keep the drag in Pan");
            assertEquals(revision, context.controller().state().contentRevision());
            canvas.getActionMap().get("canvas-space-up").actionPerformed(
                    new ActionEvent(canvas, ActionEvent.ACTION_PERFORMED, "space-up"));
            nativePrimaryDrag(canvas, body, 12, 6);
            assertEquals(1, translations.get(), "Releasing Space restores atlas dragging");
            assertEquals(revision + 1, context.controller().state().contentRevision());
        });
    }

    @Test
    void focusLossClearsTemporarySpacePanBeforeTransformDrag()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext control = halfDirectContext();
            final AtomicInteger controlTranslations = new AtomicInteger();
            control.canvas().setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void translate(
                                final double dx, final double dy) {
                            controlTranslations.incrementAndGet();
                        }
                    });
            final Point2D controlBody = control.canvas()
                    .sourceScreenMapping().previewToScreen(
                            new Point2D(80, 70));
            nativePrimaryDrag(control.canvas(), controlBody, 12, 6);
            assertEquals(1, controlTranslations.get(),
                    "the test point must be a live Transform body target");

            final CanvasContext context = halfDirectContext();
            final ReviewCanvas canvas = context.canvas();
            final AtomicInteger translations = new AtomicInteger();
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void translate(
                                final double dx, final double dy) {
                            translations.incrementAndGet();
                            context.controller().translate(dx, dy);
                        }
                    });
            final var before = context.controller().state().content()
                    .manualPreviewAdjustment();
            canvas.getActionMap().get("canvas-space-down")
                    .actionPerformed(new ActionEvent(
                            canvas, ActionEvent.ACTION_PERFORMED,
                            "space-down"));
            final FocusEvent focusLost = new FocusEvent(
                    canvas, FocusEvent.FOCUS_LOST);
            for (final var listener : canvas.getFocusListeners()) {
                listener.focusLost(focusLost);
            }
            final Point2D body = canvas.sourceScreenMapping()
                    .previewToScreen(new Point2D(80, 70));

            nativePrimaryDrag(canvas, body, 12, 6);

            assertEquals(1, translations.get(),
                    "a missed Space release must not leave later primary drags in PAN");
            final var after = context.controller().state().content()
                    .manualPreviewAdjustment();
            assertNotEquals(before, after);
            context.controller().undo();
            assertEquals(before, context.controller().state().content()
                    .manualPreviewAdjustment());
            context.controller().redo();
            assertEquals(after, context.controller().state().content()
                    .manualPreviewAdjustment());
        });
    }

    @Test
    void resizeHandlesStartLockedAndExplicitUnlockAllowsFreeAxes()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final ReviewCanvas edgeCanvas = canvas();
            final List<Point2D> edgeHandles =
                    edgeCanvas.transformResizeHandleScreenPoints();
            assertEquals(8, edgeHandles.size(),
                    "four corners and four edge-midpoint handles are required");
            final AtomicReference<double[]> edgeScale =
                    new AtomicReference<>();
            edgeCanvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void scaleAxes(
                                final double scaleX,
                                final double scaleY,
                                final double axisRadians,
                                final Point2D pivot) {
                            edgeScale.set(new double[] {scaleX, scaleY});
                        }
                    });
            drag(edgeCanvas, edgeHandles.get(5), 16, 0,
                    true, false);
            assertTrue(edgeScale.get()[0] > 1);
            assertEquals(edgeScale.get()[0], edgeScale.get()[1], 1e-12,
                    "proportions are locked by default");

            final ReviewCanvas shiftedEdgeCanvas = canvas();
            shiftedEdgeCanvas.setAspectRatioLocked(false);
            final AtomicReference<double[]> shiftedEdgeScale =
                    new AtomicReference<>();
            shiftedEdgeCanvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void scaleAxes(
                                final double scaleX,
                                final double scaleY,
                                final double axisRadians,
                                final Point2D pivot) {
                            shiftedEdgeScale.set(
                                    new double[] {scaleX, scaleY});
                        }
                    });
            drag(shiftedEdgeCanvas,
                    shiftedEdgeCanvas.transformResizeHandleScreenPoints()
                            .get(5),
                    16, 0, true, true);
            assertEquals(shiftedEdgeScale.get()[0],
                    shiftedEdgeScale.get()[1], 1e-12,
                    "Shift must keep proportions from an edge handle");

            final ReviewCanvas cornerCanvas = canvas();
            cornerCanvas.setAspectRatioLocked(false);
            final AtomicReference<double[]> cornerScale =
                    new AtomicReference<>();
            cornerCanvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void scaleAxes(
                                final double scaleX,
                                final double scaleY,
                                final double axisRadians,
                                final Point2D pivot) {
                            cornerScale.set(new double[] {scaleX, scaleY});
                        }
                    });
            drag(cornerCanvas,
                    cornerCanvas.transformResizeHandleScreenPoints().get(2),
                    18, 4, true, false);
            assertTrue(Math.abs(cornerScale.get()[0]
                    - cornerScale.get()[1]) > 1e-3,
                    "a corner drag without Shift must permit free aspect ratio");

            final ReviewCanvas shiftedCornerCanvas = canvas();
            shiftedCornerCanvas.setAspectRatioLocked(false);
            final AtomicReference<double[]> shiftedCornerScale =
                    new AtomicReference<>();
            shiftedCornerCanvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void scaleAxes(
                                final double scaleX,
                                final double scaleY,
                                final double axisRadians,
                                final Point2D pivot) {
                            shiftedCornerScale.set(
                                    new double[] {scaleX, scaleY});
                        }
                    });
            drag(shiftedCornerCanvas,
                    shiftedCornerCanvas.transformResizeHandleScreenPoints()
                            .get(2),
                    18, 4, true, true);
            assertEquals(shiftedCornerScale.get()[0],
                    shiftedCornerScale.get()[1], 1e-12,
                    "Shift must keep proportions from a corner handle");
        });
    }

    @Test
    void unsafeResizeReleaseCommitsOnlyTheLastSafePreview() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final ReviewCanvas canvas = canvas();
            final Point2D rightEdge = canvas
                    .transformResizeHandleScreenPoints().get(5);
            final AtomicReference<double[]> committed =
                    new AtomicReference<>();
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public boolean canPreviewScaleAxes(
                                final double scaleX,
                                final double scaleY,
                                final double axisRadians,
                                final Point2D pivot) {
                            return scaleX <= 1.15 && scaleY <= 1.15;
                        }

                        @Override
                        public void scaleAxes(
                                final double scaleX,
                                final double scaleY,
                                final double axisRadians,
                                final Point2D pivot) {
                            committed.set(new double[] {scaleX, scaleY});
                        }
                    });
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED,
                    rightEdge, 0, 0, MouseEvent.BUTTON1));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_DRAGGED,
                    rightEdge, 8, 0, MouseEvent.NOBUTTON));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_DRAGGED,
                    rightEdge, 200, 0, MouseEvent.NOBUTTON));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED,
                    rightEdge, 200, 0, MouseEvent.BUTTON1));

            assertTrue(committed.get()[0] > 1,
                    "the safe sample before the rejected move must survive");
            assertTrue(committed.get()[0] <= 1.15,
                    "release at an unsafe location must retain last-safe scale");
            assertEquals(committed.get()[0], committed.get()[1], 1e-12);
        });
    }

    @Test
    void disjoinedSeamHandlesRetainExplicitSidePlacementAndOppositePivot()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = context();
            final ReviewCanvas canvas = context.canvas();
            context.controller().setReviewSectionMode(
                    ReviewSectionMode.DISJOINED);
            canvas.setActiveHemisphereSide(
                    ManualHemisphereWarp2D.AtlasSide.LEFT);
            final List<Point2D> before =
                    canvas.transformResizeHandleScreenPoints();
            assertEquals(8, before.size());

            context.controller().translateManualSide(
                    ManualHemisphereWarp2D.AtlasSide.LEFT, 5, 0);
            final List<Point2D> after =
                    canvas.transformResizeHandleScreenPoints();
            final ScreenMapping mapping = canvas.sourceScreenMapping();
            for (int index = 0; index < after.size(); index++) {
                assertEquals(5, mapping.screenDeltaToPreview(
                        after.get(index).x() - before.get(index).x()),
                        1e-9,
                        "every active-half corner and edge handle, including the raw seam, must follow its side");
                assertEquals(0, mapping.screenDeltaToPreview(
                        after.get(index).y() - before.get(index).y()),
                        1e-9);
            }

            final AtomicReference<Point2D> pivot = new AtomicReference<>();
            canvas.setInteractionListener(
                    new ReviewCanvas.InteractionListener() {
                        @Override
                        public void scaleAxes(
                                final double scaleX,
                                final double scaleY,
                                final double axisRadians,
                                final Point2D previewPivot) {
                            pivot.set(previewPivot);
                        }
                    });
            drag(canvas, after.get(5), 8, 0, true, false);

            final Point2D expectedOpposite = mapping.screenToPreview(
                    after.get(7));
            assertEquals(expectedOpposite.x(), pivot.get().x(), 1e-9);
            assertEquals(expectedOpposite.y(), pivot.get().y(), 1e-9,
                    "a seam-edge resize must anchor the opposite edge midpoint");
        });
    }

    @Test
    void panChangesOnlyTheChosenViewWithoutEditCallback() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final ReviewCanvas canvas = canvas();
            canvas.zoomTissueIn();
            final ScreenMapping tissueBefore = canvas.sourceScreenMapping();
            final ScreenMapping atlasBefore = canvas.atlasScreenMapping();
            final AtomicInteger editCalls = new AtomicInteger();
            canvas.setInteractionListener(new ReviewCanvas.InteractionListener() {
                @Override
                public void translate(final double dx, final double dy) {
                    editCalls.incrementAndGet();
                }
            });
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.PAN);
            final Point2D start = new Point2D(150, 150);
            drag(canvas, start, 18, -7, true);

            assertEquals(tissueBefore.offsetX() + 18,
                    canvas.sourceScreenMapping().offsetX(), 0);
            assertEquals(tissueBefore.offsetY() - 7,
                    canvas.sourceScreenMapping().offsetY(), 0);
            assertEquals(atlasBefore, canvas.atlasScreenMapping());
            assertEquals(0, editCalls.get());
        });
    }

    @Test
    void atlasPaneZoomAndPanRemainIndependentFromTissuePane()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final ReviewCanvas canvas = canvas();
            final ScreenMapping tissueBefore = canvas.sourceScreenMapping();
            final ScreenMapping atlasBefore = canvas.atlasScreenMapping();

            canvas.zoomAtlasIn();

            assertEquals(tissueBefore, canvas.sourceScreenMapping());
            assertEquals(atlasBefore.scale() * 1.25,
                    canvas.atlasScreenMapping().scale(), 1e-12);
            final ScreenMapping atlasZoomed = canvas.atlasScreenMapping();
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.PAN);
            final Point2D start = atlasZoomed.previewToScreen(
                    new Point2D(50, 40));
            drag(canvas, start, -12, 9, true);

            assertEquals(tissueBefore, canvas.sourceScreenMapping());
            assertNotEquals(atlasZoomed, canvas.atlasScreenMapping(),
                    "atlas panning may clamp at an edge but must remain independent");
            assertEquals(atlasZoomed.scale(),
                    canvas.atlasScreenMapping().scale(), 1e-12);
        });
    }

    @Test
    void pointsToolSelectsAndCommitsOneAtlasEndpointMove()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = context();
            final ReviewCanvas canvas = context.canvas();
            context.controller().addLandmark(
                    "L1", new Point2D(70, 60), new Point2D(30, 30));
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            final AtomicReference<String> selected = new AtomicReference<>();
            final AtomicReference<Point2D> moved = new AtomicReference<>();
            final AtomicInteger moveCalls = new AtomicInteger();
            canvas.setInteractionListener(new ReviewCanvas.InteractionListener() {
                @Override
                public void landmarkSelected(final String id) {
                    selected.set(id);
                }

                @Override
                public void moveLandmarkAtlasPoint(
                        final String id,
                        final Point2D point) {
                    assertEquals("L1", id);
                    moveCalls.incrementAndGet();
                    moved.set(point);
                }
            });
            final ScreenMapping mapping = canvas.atlasScreenMapping();
            final Point2D start = mapping.previewToScreen(
                    new Point2D(70, 60));

            drag(canvas, start, 12, 8, true);

            assertEquals("L1", selected.get());
            assertEquals(1, moveCalls.get());
            final Point2D expected = mapping.screenToPreview(new Point2D(
                    Math.round(start.x()) + 12,
                    Math.round(start.y()) + 8));
            assertEquals(expected.x(), moved.get().x(), 1e-9);
            assertEquals(expected.y(), moved.get().y(), 1e-9);
        });
    }

    @Test
    void pointsToolClickSelectsEndpointWithoutMovingOrAuditingIt()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final CanvasContext context = context();
            final ReviewCanvas canvas = context.canvas();
            context.controller().addLandmark(
                    "L1", new Point2D(70.3, 60.7), new Point2D(30, 30));
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            final AtomicReference<String> selected = new AtomicReference<>();
            final AtomicInteger moves = new AtomicInteger();
            canvas.setInteractionListener(new ReviewCanvas.InteractionListener() {
                @Override
                public void landmarkSelected(final String id) {
                    selected.set(id);
                }

                @Override
                public void moveLandmarkAtlasPoint(
                        final String id,
                        final Point2D point) {
                    moves.incrementAndGet();
                }
            });
            final Point2D marker = canvas.atlasScreenMapping().previewToScreen(
                    new Point2D(70.3, 60.7));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED,
                    marker, 0, 0, MouseEvent.BUTTON1));
            canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED,
                    marker, 0, 0, MouseEvent.BUTTON1));

            assertEquals("L1", selected.get());
            assertEquals(0, moves.get());
        });
    }

    private static ReviewCanvas canvas() {
        return context().canvas();
    }

    private static CanvasContext context() {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        return context(basis, ReviewPluginFixtures.preview());
    }

    private static CanvasContext guidedContext() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        return context(basis, ReviewPluginFixtures.segmentedPreview());
    }

    private static CanvasContext halfDirectContext() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis(
                        SectionGeometry.IMAGE_LEFT_HALF);
        final CanvasContext context = context(
                basis, ReviewPluginFixtures.segmentedPreview());
        context.controller().setOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT);
        context.controller().setObservedHemisphere(
                ObservedAnatomicalHemisphere.LEFT);
        return context;
    }

    private static CanvasContext structureContext() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.segmentedPreview(),
                new StructurePlaneSource(),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        final ReviewCanvas canvas = new ReviewCanvas(controller);
        canvas.setSize(820, 400);
        controller.attach(new ReviewView() {
            @Override public void render(final ReviewViewModel model) {
                canvas.setModel(model);
            }
            @Override public void showError(
                    final String title, final String message) {
                throw new AssertionError(title + ": " + message);
            }
            @Override public void reviewClosed() { }
        });
        return new CanvasContext(canvas, controller);
    }

    private static int awaitExportPreviewHash(
            final ReviewCanvas canvas) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            final var preview = canvas.exportedRoiPreviewImageForTests();
            if (preview != null) {
                return java.util.Arrays.hashCode(preview.getRGB(
                        0, 0, preview.getWidth(), preview.getHeight(),
                        null, 0, preview.getWidth()));
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for export preview");
    }

    private static CanvasContext twoBladeStructureContext() {
        final AlignmentReviewBasis basis =
                ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.segmentedPreview(),
                new TwoBladeStructurePlaneSource(),
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        final ReviewCanvas canvas = new ReviewCanvas(controller);
        canvas.setSize(820, 400);
        controller.attach(new ReviewView() {
            @Override public void render(final ReviewViewModel model) {
                canvas.setModel(model);
            }
            @Override public void showError(
                    final String title, final String message) {
                throw new AssertionError(title + ": " + message);
            }
            @Override public void reviewClosed() { }
        });
        return new CanvasContext(canvas, controller);
    }

    private static Point2D awaitStructureGapHandle(
            final ReviewCanvas canvas) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            final AtomicReference<Optional<Point2D>> result =
                    new AtomicReference<>(Optional.empty());
            SwingUtilities.invokeAndWait(() -> result.set(
                    canvas.structureGapHandleScreenPoint()));
            if (result.get().isPresent()) {
                return result.get().orElseThrow();
            }
            Thread.sleep(20);
        }
        throw new AssertionError(
                "timed out waiting for the two-principal-component render cache");
    }

    private static StructureGesture structureGesture(
            final int handleIndex,
            final int dx,
            final int dy,
            final boolean shift,
            final boolean translateInside) {
        final CanvasContext context = structureContext();
        context.controller().selectAtlasRegionExactAcronym("DG");
        context.controller().replaceSelectedStructureControls(
                4, ManualHemisphereWarp2D.AtlasSide.LEFT);
        final ReviewCanvas canvas = context.canvas();
        canvas.setActiveHemisphereSide(
                ManualHemisphereWarp2D.AtlasSide.LEFT);
        canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
        canvas.setStructureEditingEnabled(true);
        final List<Point2D> handles =
                canvas.structureResizeHandleScreenPoints();
        assertEquals(8, handles.size());
        final List<Point2D> before = context.controller().state().content()
                .hemisphereWarp().orElseThrow().manualControls(
                        ManualHemisphereWarp2D.AtlasSide.LEFT).stream()
                .map(control -> control.targetPoint()).toList();
        final AtomicReference<List<Point2D>> requested =
                new AtomicReference<>();
        canvas.setInteractionListener(
                new ReviewCanvas.InteractionListener() {
                    @Override
                    public void transformStructureControls(
                            final List<String> controlIds,
                            final List<Point2D> requestedTargets) {
                        requested.set(requestedTargets);
                    }
                });
        final Point2D start = translateInside
                ? new Point2D((handles.get(0).x() + handles.get(2).x()) / 2,
                        (handles.get(0).y() + handles.get(2).y()) / 2)
                : handles.get(handleIndex);
        drag(canvas, start, dx, dy, true, shift);
        assertTrue(requested.get() != null,
                "the resize box gesture must request a group transform");
        return new StructureGesture(before, requested.get());
    }

    private static double extentX(final List<Point2D> points) {
        return points.stream().mapToDouble(Point2D::x).max().orElseThrow()
                - points.stream().mapToDouble(Point2D::x).min().orElseThrow();
    }

    private static double extentY(final List<Point2D> points) {
        return points.stream().mapToDouble(Point2D::y).max().orElseThrow()
                - points.stream().mapToDouble(Point2D::y).min().orElseThrow();
    }

    private static List<Point2D> subset(
            final List<Point2D> points,
            final List<Integer> indices) {
        return indices.stream().map(points::get).toList();
    }

    private static double meanY(final List<Point2D> points) {
        return points.stream().mapToDouble(Point2D::y)
                .average().orElseThrow();
    }

    private static void assertPoint(
            final Point2D expected,
            final Point2D actual) {
        assertEquals(expected.x(), actual.x(), 1e-9);
        assertEquals(expected.y(), actual.y(), 1e-9);
    }

    private static Point2D midpoint(
            final Point2D first,
            final Point2D second) {
        return new Point2D((first.x() + second.x()) * 0.5,
                (first.y() + second.y()) * 0.5);
    }

    private static void verifyReflectedAtlasPaneDrag(
            final SectionGeometry geometry,
            final ReviewSectionMode sectionMode,
            final ManualHemisphereWarp2D.AtlasSide activeSide) {
        final CanvasContext context = context(
                ReviewPluginFixtures.segmentedScaledAtlasBasis(geometry),
                ReviewPluginFixtures.segmentedPreview());
        final ReviewController controller = context.controller();
        final ReviewCanvas canvas = context.canvas();
        controller.setOrientation(
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT);
        if (sectionMode == ReviewSectionMode.HALF) {
            controller.setObservedHemisphere(
                    ObservedAnatomicalHemisphere.RIGHT);
        } else if (sectionMode == ReviewSectionMode.DISJOINED) {
            controller.setReviewSectionMode(ReviewSectionMode.DISJOINED);
            controller.translateManualSide(
                    ManualHemisphereWarp2D.AtlasSide.LEFT, -18, 3);
            controller.translateManualSide(
                    ManualHemisphereWarp2D.AtlasSide.RIGHT, 22, -4);
        }
        canvas.setInteractionListener(
                new ReviewCanvas.InteractionListener() {
                    @Override
                    public void moveBoundaryFitMatch(
                            final String id,
                            final ReviewController.BoundaryFitEndpoint endpoint,
                            final Point2D point) {
                        controller.moveBoundaryFitMatch(id, endpoint, point);
                    }
                });
        controller.startBoundaryFitMatching(
                org.atlasalign.application.manual.BoundaryFitModel
                        .SIMILARITY,
                activeSide);
        final var draft = controller.boundaryFitState().draft()
                .orElseThrow();
        final var anchor = draft.activeAnchor().orElseThrow();
        final boolean rawRight = anchor.atlasPlanePoint().x() >= 228;
        final Point2D rawTarget;
        final Point2D expectedTarget;
        if (sectionMode == ReviewSectionMode.DISJOINED) {
            rawTarget = new Point2D(rawRight ? 210 : 246,
                    anchor.atlasPlanePoint().y());
            final Point2D targetSideMapped = BoundaryFitRequestFactory
                    .mappedAtlasPreviewPoint(controller.state(),
                            draft.request().targetSide(), rawTarget);
            final Point2D rawClassifiedMapped = controller.state()
                    .mapAtlasBeforeHemisphereWarp(rawTarget);
            assertNotEquals(rawClassifiedMapped, targetSideMapped,
                    "different side placements must distinguish an explicit target-side mapping after crossing the raw midline");
            expectedTarget = draft.request().atlasBoundary().stream()
                    .min(java.util.Comparator.comparingDouble(sample ->
                            Math.hypot(sample.point().x()
                                            - targetSideMapped.x(),
                                    sample.point().y()
                                            - targetSideMapped.y())))
                    .orElseThrow().point();
        } else {
            expectedTarget = draft.request().atlasBoundary().stream()
                    .filter(sample -> {
                        final Point2D raw = BoundaryFitRequestFactory
                                .rawAtlasPlanePoint(controller.state(),
                                        draft.request().targetSide(),
                                        sample.point());
                        return (raw.x() >= 228) == rawRight;
                    })
                    .max(java.util.Comparator.comparingDouble(sample ->
                            Math.hypot(sample.point().x()
                                            - anchor.atlasPreviewPoint().x(),
                                    sample.point().y()
                                            - anchor.atlasPreviewPoint().y())))
                    .orElseThrow().point();
            rawTarget = BoundaryFitRequestFactory.rawAtlasPlanePoint(
                    controller.state(), draft.request().targetSide(),
                    expectedTarget);
        }
        final Point2D start = canvas.atlasScreenMapping()
                .previewToScreen(anchor.atlasPlanePoint());
        final Point2D end = canvas.atlasScreenMapping()
                .previewToScreen(rawTarget);
        if (sectionMode == ReviewSectionMode.DISJOINED) {
            assertNotEquals(rawRight, rawTarget.x() > 227.5,
                    "the regression release point must cross the raw midline");
        }

        drag(canvas, start,
                (int) Math.round(end.x() - start.x()),
                (int) Math.round(end.y() - start.y()), true);

        final var moved = controller.boundaryFitState().draft()
                .orElseThrow().anchors().stream()
                .filter(candidate -> candidate.id().equals(anchor.id()))
                .findFirst().orElseThrow();
        assertNotEquals(anchor.atlasPreviewPoint(),
                moved.atlasPreviewPoint(),
                sectionMode + " reflected atlas-pane drag must move the endpoint");
        assertEquals(expectedTarget.x(), moved.atlasPreviewPoint().x(), 1e-9);
        assertEquals(expectedTarget.y(), moved.atlasPreviewPoint().y(), 1e-9);
        final Point2D remapped = BoundaryFitRequestFactory
                .mappedAtlasPreviewPoint(controller.state(),
                        draft.request().targetSide(),
                        moved.atlasPlanePoint());
        assertEquals(remapped.x(), moved.atlasPreviewPoint().x(), 1e-9);
        assertEquals(remapped.y(), moved.atlasPreviewPoint().y(), 1e-9);
        assertEquals(rawRight, moved.atlasPlanePoint().x() > 227.5,
                sectionMode + " reflected drag must snap to the selected raw atlas half; moved="
                        + moved.atlasPlanePoint() + ", expectedPreview="
                        + expectedTarget + ", release=" + rawTarget);
    }

    private static CanvasContext context(
            final AlignmentReviewBasis basis,
            final ReviewPreview preview) {
        final ReviewController controller = new ReviewController(
                new AlignmentReviewSession(basis),
                preview,
                ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run,
                Runnable::run);
        final ReviewCanvas canvas = new ReviewCanvas(controller);
        canvas.setSize(820, 400);
        controller.attach(new ReviewView() {
            @Override
            public void render(final ReviewViewModel model) {
                canvas.setModel(model);
            }

            @Override
            public void showError(
                    final String title,
                    final String message) { }

            @Override
            public void reviewClosed() { }
        });
        return new CanvasContext(canvas, controller);
    }

    private static void drag(
            final ReviewCanvas canvas,
            final Point2D start,
            final int dx,
            final int dy,
            final boolean release) {
        drag(canvas, start, dx, dy, release, false);
    }

    private static void drag(
            final ReviewCanvas canvas,
            final Point2D start,
            final int dx,
            final int dy,
            final boolean release,
            final boolean shift) {
        final int modifiers = shift ? InputEvent.SHIFT_DOWN_MASK : 0;
        canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED,
                start, 0, 0, MouseEvent.BUTTON1, modifiers));
        canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_DRAGGED,
                start, dx, dy, MouseEvent.NOBUTTON, modifiers));
        if (release) {
            release(canvas, start, dx, dy, modifiers);
        }
    }

    private static void release(
            final ReviewCanvas canvas,
            final Point2D start,
            final int dx,
            final int dy) {
        release(canvas, start, dx, dy, 0);
    }

    /** Dispatches the modifier pattern produced by a native primary drag. */
    private static void nativePrimaryDrag(
            final ReviewCanvas canvas,
            final Point2D start,
            final int dx,
            final int dy) {
        canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_PRESSED,
                start, 0, 0, MouseEvent.BUTTON1,
                InputEvent.BUTTON1_DOWN_MASK));
        canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_DRAGGED,
                start, dx, dy, MouseEvent.NOBUTTON,
                InputEvent.BUTTON1_DOWN_MASK));
        canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED,
                start, dx, dy, MouseEvent.BUTTON1, 0));
    }

    private static void release(
            final ReviewCanvas canvas,
            final Point2D start,
            final int dx,
            final int dy,
            final int modifiers) {
        canvas.dispatchEvent(mouse(canvas, MouseEvent.MOUSE_RELEASED,
                start, dx, dy, MouseEvent.BUTTON1, modifiers));
    }

    private static MouseEvent mouse(
            final ReviewCanvas canvas,
            final int id,
            final Point2D start,
            final int dx,
            final int dy,
            final int button) {
        return mouse(canvas, id, start, dx, dy, button, 0);
    }

    private static MouseEvent mouse(
            final ReviewCanvas canvas,
            final int id,
            final Point2D start,
            final int dx,
            final int dy,
            final int button,
            final int modifiers) {
        return new MouseEvent(canvas, id, System.currentTimeMillis(), modifiers,
                (int) Math.round(start.x()) + dx,
                (int) Math.round(start.y()) + dy,
                1, false, button);
    }

    private record CanvasContext(
            ReviewCanvas canvas,
            ReviewController controller) { }

    private record StructureGesture(
            List<Point2D> before,
            List<Point2D> requested) { }

    private static final class StructurePlaneSource
            implements AtlasPlaneSource, AtlasRegionCatalog {
        @Override public org.atlasalign.atlas.AtlasCoronalPlane load(
                final int level) {
            return ReviewPluginFixtures.plane(level);
        }

        @Override public java.util.Optional<SelectedAtlasRegion>
                resolveExactAcronym(final String acronym) {
            return "DG".equalsIgnoreCase(acronym)
                    ? java.util.Optional.of(new SelectedAtlasRegion(
                            1, "DG", "Dentate gyrus", java.util.Set.of(1)))
                    : java.util.Optional.empty();
        }
    }

    private static final class TwoBladeStructurePlaneSource
            implements AtlasPlaneSource, AtlasRegionCatalog {
        @Override public org.atlasalign.atlas.AtlasCoronalPlane load(
                final int level) {
            final int width = 456;
            final int height = 320;
            final int[] template = new int[width * height];
            final int[] annotations = new int[width * height];
            fill(annotations, width, 60, 70, 170, 100, 1);
            fill(annotations, width, 70, 140, 180, 175, 1);
            fill(annotations, width, 110, 235, 118, 243, 1);
            for (int index = 0; index < annotations.length; index++) {
                template[index] = annotations[index] == 0 ? 0 : 700;
            }
            return new org.atlasalign.atlas.AtlasCoronalPlane(
                    level, width, height, template, annotations);
        }

        private static void fill(
                final int[] values,
                final int width,
                final int minimumX,
                final int minimumY,
                final int maximumX,
                final int maximumY,
                final int value) {
            for (int y = minimumY; y <= maximumY; y++) {
                for (int x = minimumX; x <= maximumX; x++) {
                    values[y * width + x] = value;
                }
            }
        }

        @Override public java.util.Optional<SelectedAtlasRegion>
                resolveExactAcronym(final String acronym) {
            return "DG".equalsIgnoreCase(acronym)
                    ? java.util.Optional.of(new SelectedAtlasRegion(
                            1, "DG", "Dentate gyrus", java.util.Set.of(1)))
                    : java.util.Optional.empty();
        }
    }
}
