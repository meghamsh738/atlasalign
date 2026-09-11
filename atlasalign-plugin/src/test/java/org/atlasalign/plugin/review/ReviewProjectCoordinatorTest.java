package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.*;
import ij.ImagePlus;
import ij.process.ShortProcessor;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.*;
import java.util.Optional;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import org.atlasalign.application.*;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.plugin.project.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewProjectCoordinatorTest {
    @TempDir Path temporary;

    @Test
    void editDuringSaveStaysDirtyThenDebouncesToExactNewCheckpoint() throws Exception {
        final AtomicReference<ReviewProjectCoordinator> coordinator = new AtomicReference<>();
        final AtomicReference<ReviewController> controller = new AtomicReference<>();
        final AtomicReference<JComponent> footer = new AtomicReference<>();
        final Path destination = temporary.resolve("review.atlasalign.json");
        SwingUtilities.invokeAndWait(() -> {
            final var basis = ReviewPluginFixtures.basis();
            final var session = new AlignmentReviewSession(basis);
            final var review = new ReviewController(session, ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                    () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()), Runnable::run, Runnable::run);
            final var panel = new SwingReviewPanel(review); review.attach(panel); controller.set(review);
            final var context = new ReviewProjectContext(new ImagePlus("saved-test.tif", new ShortProcessor(100, 80)),
                    temporary.resolve("atlas"), Optional.empty(), Optional.empty(), Optional.of(destination));
            final var project = new ReviewProjectCoordinator(context, review, panel.manualRoiSessionForTests(), panel, null);
            coordinator.set(project); footer.set(project.footer());
            assertTrue(project.hasUnsavedChanges());
            project.save(false, null); assertTrue(project.isSaving());
            assertEquals("Saving…", ((JLabel) named(footer.get(), "reviewProjectSaveStatus")).getText());
            review.translate(4, -2);
            assertTrue(project.hasUnsavedChanges());
        });
        try {
            final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            final AtomicBoolean saved = new AtomicBoolean();
            while (System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> saved.set(!coordinator.get().isSaving() && !coordinator.get().hasUnsavedChanges()));
                if (saved.get()) break;
                Thread.sleep(25);
            }
            assertTrue(saved.get(), "Latest edit must be checkpointed after the first save completes");
            final var restored = new ReviewProjectCodec().decode(Files.readAllBytes(destination));
            assertEquals(controller.get().state().content(), restored.alignment().content());
            assertEquals(controller.get().state().contentRevision(), restored.alignment().contentRevision());
            final AtomicBoolean closed = new AtomicBoolean();
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(((JLabel) named(footer.get(), "reviewProjectSaveStatus")).getText().startsWith("Saved ·"));
                coordinator.get().requestClose(() -> closed.set(true));
            });
            assertTrue(closed.get(), "A fully checkpointed session may close without a dirty-work prompt");
        } finally {
            SwingUtilities.invokeAndWait(() -> { coordinator.get().close(); controller.get().close(); });
        }
    }

    @Test
    void inspectionKeepsViewportControlsButBlocksAtlasPlaneAndGeometryChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final var basis = ReviewPluginFixtures.basis();
            final var review = new ReviewController(new AlignmentReviewSession(basis), ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                    () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()), Runnable::run, Runnable::run);
            final var panel = new SwingReviewPanel(review); review.attach(panel);
            final var before = review.state();
            panel.canvas().requestChannelDisplay("Inspection Z2", true); panel.setDisplayGeometryBlocked(true);
            for (final String name : new String[]{"viewportZoomIn", "viewportZoomOut", "viewportFit", "temporaryPanTool", "workflowGuide"}) {
                assertTrue(named(panel, name).isEnabled(), name + " must remain usable during inspection");
            }
            assertFalse(named(panel, "persistentStagePrimary").isEnabled());
            assertFalse(named(panel, "displayTissueClipping").isEnabled());
            assertFalse(named(panel, "placementMove").isEnabled());
            panel.nudgeCoronalLevel(1); panel.canvas().translateSourcePixels(1, 1);
            ((JButton) named(panel, "viewportZoomIn")).doClick();
            assertEquals(before, review.state());
            review.close();
        });
    }

    @Test
    void savedWorkflowAndLandmarkDisplayRestoreExactlyWithoutGeometryEdit() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final var basis = ReviewPluginFixtures.basis();
            final var review = new ReviewController(new AlignmentReviewSession(basis), ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                    () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()), Runnable::run, Runnable::run);
            final var panel = new SwingReviewPanel(review);
            final var state = new ReviewUiState(ReviewWorkflowStage.INTERIOR, ReviewCanvas.InteractionTool.LANDMARKS,
                    ReviewCanvas.ComparisonMode.COMPARE, new ReviewUiState.Viewport(1.5, 2, -3, 2, -5, 6), true, true,
                    false, false, 0xff1144bb, 0xff996611, 2.5, .75, false, false,
                    ManualHemisphereWarp2D.AtlasSide.RIGHT, Optional.empty(), false, ReviewCanvas.PlacementTool.ROTATE);
            final var geometry = review.state();
            panel.restoreProjectUi(state); review.attach(panel);
            assertEquals(state, panel.captureProjectUi());
            assertEquals(geometry, review.state());
            review.close();
        });
    }

    private static Component named(final Container container, final String name) {
        if (name.equals(container.getName())) return container;
        for (final Component child : container.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) { final var found = named(nested, name); if (found != null) return found; }
        }
        return null;
    }
}
