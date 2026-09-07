package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.JButton;
import javax.swing.SwingUtilities;
import org.atlasalign.application.AlignmentReviewSession;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewAcceptanceVerification;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

/** Headless coverage for stage-to-canvas interaction ownership. */
class SwingReviewPanelStageInteractionTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void batchNavigationIsAvailableInEveryStageWithoutChangingReview() throws Exception {
        final var basis = ReviewPluginFixtures.basis();
        final var session = new AlignmentReviewSession(basis);
        final var controller = new ReviewController(session,
                ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        SwingUtilities.invokeAndWait(() -> {
            final var panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final var back = named(panel, "batchAllSections", JButton.class);
            assertFalse(back.isVisible(), "single section reviews have no batch navigation");
            final var calls = new java.util.concurrent.atomic.AtomicInteger();
            panel.setReturnToSectionsAction(calls::incrementAndGet);
            final var before = session.state();
            for (int index : new int[]{0, 2, 3, 4, 5}) {
                named(panel, "workflowStep" + index, JButton.class).doClick();
                assertTrue(back.isVisible());
                assertTrue(back.isEnabled());
                back.doClick();
            }
            assertEquals(5, calls.get());
            assertEquals(before, session.state());
            assertTrue(panel.compactStatusTextForTests().startsWith("Review & Export"));
            assertFalse(panel.canvas().manualWarpEditingEnabled());
            assertFalse(named(panel, "manualRoiReviewExport", JButton.class).isEnabled(),
                    "empty export must stay disabled after review renders");
            assertTrue(named(panel, "setupAtlasGuideBrowser", AtlasGuideBrowser.class) != null);
        });
    }

    @Test
    void completingInteriorHidesHandlesAndPreservesInstalledState() throws Exception {
        final var basis = ReviewPluginFixtures.segmentedScaledAtlasBasis();
        final var session = new AlignmentReviewSession(basis);
        final var controller = new ReviewController(session,
                ReviewPluginFixtures.segmentedPreview(), ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        SwingUtilities.invokeAndWait(() -> {
            final var panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            named(panel, "workflowStep3", JButton.class).doClick();
            assertTrue(panel.canvas().manualWarpEditingEnabled());
            final var before = session.state();
            named(panel, "applyInteriorStage", JButton.class).doClick();
            assertFalse(panel.canvas().manualWarpEditingEnabled());
            assertTrue(panel.canvas().manualRoiEditingEnabled());
            assertEquals(before, session.state());
            named(panel, "workflowStep5", JButton.class).doClick();
            assertFalse(panel.canvas().manualWarpEditingEnabled());
            assertFalse(panel.canvas().manualRoiEditingEnabled());
            named(panel, "workflowStep3", JButton.class).doClick();
            assertTrue(panel.canvas().manualWarpEditingEnabled());
        });
    }

    @Test
    void continuingWithAppliedBorderDiscardsOnlyTheRejectedDraft() throws Exception {
        final boolean[] pixels = new boolean[100 * 80];
        for (int y = 8; y <= 56; y++) {
            for (int x = 12; x <= 80; x++) {
                pixels[y * 100 + x] = true;
            }
        }
        final var basis = ReviewPluginFixtures.scaledAtlasBasisWithMask(
                org.atlasalign.core.BinaryMask.fromBooleans(100, 80, pixels));
        final var session = new AlignmentReviewSession(basis);
        final var controller = new ReviewController(session,
                ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        SwingUtilities.invokeAndWait(() -> {
            final var panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            controller.startBoundaryWarp(
                    org.atlasalign.application.manual.ManualHemisphereWarp2D.AtlasSide.LEFT, 4);
            controller.calculateBoundaryWarpPreview();
            controller.applyBoundaryWarp();
            final var before = session.state();
            controller.startBoundaryWarp(
                    org.atlasalign.application.manual.ManualHemisphereWarp2D.AtlasSide.LEFT, 4);
            final var draft = controller.boundaryWarpState().draft().orElseThrow();
            controller.moveBoundaryWarpMatch(draft.matches().get(1).id(),
                    ReviewController.BoundaryFitEndpoint.ATLAS,
                    draft.matches().get(0).atlasPreviewPoint());
            controller.calculateBoundaryWarpPreview();
            named(panel, "workflowStep2", JButton.class).doClick();
            final var keep = named(panel, "skipBoundaryWarpStage", JButton.class);
            assertEquals("Keep applied border & continue", keep.getText());
            assertTrue(keep.isEnabled());
            assertFalse(named(panel, "applyBoundaryWarp", JButton.class).isEnabled());
            assertTrue(named(panel, "boundaryWarpStatus", javax.swing.JLabel.class)
                    .getText().contains("New border changes were not applied"));

            final var status = named(panel, "boundaryWarpStatus", javax.swing.JLabel.class);
            final var measured = new javax.swing.JLabel(status.getText());
            measured.setFont(status.getFont());
            assertEquals(measured.getPreferredSize().height, status.getPreferredSize().height,
                    "dynamic failure text must be remeasured instead of retaining its old short height");
            assertTrue(panel.compactStatusTextForTests().contains("New border changes not applied"));
            keep.doClick();

            assertEquals(before, session.state());
            assertFalse(controller.boundaryWarpState().active());
            assertTrue(panel.canvas().manualWarpEditingEnabled());
            assertFalse(panel.canvas().boundaryWarpPreviewVisibleForTests());
            assertTrue(panel.compactStatusTextForTests().contains("Applied border kept"));
        });
    }

    @Test
    void returningFromBorderRestoresSetupToolAndStatus() throws Exception {
        final var basis = ReviewPluginFixtures.segmentedScaledAtlasBasis();
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
            final JButton setup = named(
                    panel, "workflowStep0", JButton.class);
            final JButton border = named(
                    panel, "workflowStep2", JButton.class);

            border.doClick();
            assertEquals(ReviewCanvas.InteractionTool.BORDER,
                    panel.canvas().interactionTool());
            assertTrue(panel.compactStatusTextForTests()
                    .startsWith("Border is optional"));

            setup.doClick();

            assertEquals(ReviewCanvas.InteractionTool.TRANSFORM,
                    panel.canvas().interactionTool());
            assertFalse(panel.canvas().manualRoiEditingEnabled());
            assertTrue(panel.compactStatusTextForTests()
                    .startsWith("Place atlas"));
        });
    }

    @Test
    void halfSetupDispatchesBodyResizeAndRotationThroughPanel()
            throws Exception {
        final var basis = ReviewPluginFixtures.segmentedScaledAtlasBasis(
                SectionGeometry.IMAGE_LEFT_HALF);
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
            panel.setSize(1280, 800);
            layoutTree(panel);
            layoutTree(panel);
            controller.setCoronalLevel(289);
            controller.setOrientation(
                    AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT);
            controller.setObservedHemisphere(
                    ObservedAnatomicalHemisphere.LEFT);
            final ReviewCanvas canvas = panel.canvas();
            assertTrue(canvas.getWidth() > 0 && canvas.getHeight() > 0);
            assertEquals(ReviewCanvas.InteractionTool.TRANSFORM,
                    canvas.interactionTool());
            assertFalse(canvas.manualRoiEditingEnabled());

            List<Point2D> handles =
                    canvas.transformResizeHandleScreenPoints();
            assertEquals(8, handles.size());
            Point2D body = mean(handles);
            long revision = controller.state().contentRevision();
            primaryDrag(canvas, body, 12, 6);
            assertEquals(revision + 1,
                    controller.state().contentRevision());

            handles = canvas.transformResizeHandleScreenPoints();
            revision = controller.state().contentRevision();
            primaryDrag(canvas, handles.get(0), -8, -5);
            assertEquals(revision + 1,
                    controller.state().contentRevision());

            revision = controller.state().contentRevision();
            primaryDrag(canvas,
                    canvas.transformRotationHandleScreenPoint()
                            .orElseThrow(),
                    12, 4);
            assertEquals(revision + 1,
                    controller.state().contentRevision());
        });
    }

    @Test
    void stepZeroCanvasCommitsBodyResizeAndRotationWithShearedBaseline()
            throws Exception {
        final var basis = ReviewPluginFixtures
                .segmentedScaledAtlasBasisWithShearedProposal();
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
            panel.setSize(1280, 800);
            layoutTree(panel);
            layoutTree(panel);
            final ReviewCanvas canvas = panel.canvas();
            assertEquals(ReviewCanvas.InteractionTool.TRANSFORM,
                    canvas.interactionTool());
            assertFalse(canvas.manualRoiEditingEnabled());
            final Point2D body = mean(
                    canvas.transformResizeHandleScreenPoints());
            final long revision = controller.state().contentRevision();

            primaryDrag(canvas, body, 12, 6);

            assertEquals(revision + 1,
                    controller.state().contentRevision(),
                    "a Step 0 canvas drag must commit over a sheared registration proposal");

            primaryDrag(canvas,
                    canvas.transformResizeHandleScreenPoints().get(0),
                    -8, -5);
            assertEquals(revision + 2,
                    controller.state().contentRevision(),
                    "the visible resize handle must work over inherited affine shear");

            primaryDrag(canvas,
                    canvas.transformRotationHandleScreenPoint().orElseThrow(),
                    12, 4);
            assertEquals(revision + 3,
                    controller.state().contentRevision(),
                    "the visible rotation handle must work over inherited affine shear");

        });
    }

    private static Point2D mean(final List<Point2D> points) {
        return new Point2D(
                points.stream().mapToDouble(Point2D::x).average()
                        .orElseThrow(),
                points.stream().mapToDouble(Point2D::y).average()
                        .orElseThrow());
    }

    private static void primaryDrag(
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

    private static MouseEvent mouse(
            final ReviewCanvas canvas,
            final int id,
            final Point2D start,
            final int dx,
            final int dy,
            final int button,
            final int modifiers) {
        return new MouseEvent(canvas, id, System.currentTimeMillis(),
                modifiers, (int) Math.round(start.x()) + dx,
                (int) Math.round(start.y()) + dy, 1, false, button);
    }

    private static <T extends Component> T named(
            final Container root,
            final String name,
            final Class<T> type) {
        for (final Component component : root.getComponents()) {
            if (name.equals(component.getName())
                    && type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container container) {
                try {
                    return named(container, name, type);
                } catch (final IllegalArgumentException ignored) {
                    // Continue searching sibling containers.
                }
            }
        }
        throw new IllegalArgumentException(
                "Named component was not found: " + name);
    }

    private static void layoutTree(final Container container) {
        container.doLayout();
        for (final Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }
}
