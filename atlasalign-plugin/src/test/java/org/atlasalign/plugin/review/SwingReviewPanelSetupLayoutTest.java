package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.util.List;
import javax.swing.*;
import org.atlasalign.application.*;
import org.junit.jupiter.api.Test;

class SwingReviewPanelSetupLayoutTest {
    @Test
    void placementAndPlaneControlsFitWithoutScrollingAtSupportedSizes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final var basis = ReviewPluginFixtures.basis();
            final var session = new AlignmentReviewSession(basis);
            final var controller = new ReviewController(session,
                    ReviewPluginFixtures.preview(), ReviewPluginFixtures::plane,
                    () -> new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas()),
                    Runnable::run, Runnable::run);
            final var panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final var scroll = (JScrollPane) named(panel, "manualInspectorScroll");
            for (final Dimension size : List.of(new Dimension(1024, 768), new Dimension(1280, 800))) {
                panel.setSize(size);
                for (int pass = 0; pass < 5; pass++) layout(panel);
                assertTrue(scroll.getViewport().getView().getHeight()
                                <= scroll.getViewport().getExtentSize().height,
                        "Step 0 placement/plane controls require scrolling at " + size);
                assertTrue(scroll.getViewport().getView().getWidth()
                                <= scroll.getViewport().getExtentSize().width,
                        "Step 0 controls overflow horizontally at " + size);
            }
            final var numeric = (JToggleButton) named(panel, "showNumericAdjustments");
            numeric.doClick();
            for (final Dimension size : List.of(new Dimension(800, 560), new Dimension(1280, 800))) {
                panel.setSize(size);
                for (int pass = 0; pass < 8; pass++) layout(panel);
                final var numbers = (JPanel) named(panel, "numericPlacementFields");
                assertTrue(numbers.isVisible());
                assertTrue(numbers.getHeight() >= numbers.getPreferredSize().height,
                        "Expanded numeric controls must receive their full preferred height: actual=" + numbers.getSize() + " preferred=" + numbers.getPreferredSize() + " parent=" + numbers.getParent().getSize() + " parentPreferred=" + numbers.getParent().getPreferredSize());
                final var precision = numbers.getParent();
                assertTrue(numbers.getY() + numbers.getHeight() <= precision.getHeight(), "Numeric fields must fit within their visible parent");
                for (final String name : List.of("placementMove", "placementRotate", "placementScale", "moveSourceX", "moveSourceY", "rotateDegrees", "scaleWidthPercent", "scaleHeightPercent")) {
                    final var control = named(panel, name);
                    final var bounds = SwingUtilities.convertRectangle(control.getParent(), control.getBounds(), scroll.getViewport().getView());
                    assertTrue(bounds.width > 0 && bounds.height > 0, name + " must be laid out");
                    assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= scroll.getViewport().getExtentSize().width,
                            name + " must fit the inspector width when expanded at " + size);
                }
                final var primary = named(panel, "persistentStagePrimary");
                final var actionBounds = SwingUtilities.convertRectangle(primary.getParent(), primary.getBounds(), panel);
                assertTrue(actionBounds.y >= 0 && actionBounds.y + actionBounds.height <= size.height,
                        "Primary action must stay inside the fixed footer");
            }
            numeric.doClick();
            final long revision = session.state().contentRevision();
            final var crop = (JCheckBox) named(panel, "showTissueCropControls");
            crop.doClick();
            assertTrue(named(panel, "tissueCropControls").isVisible());
            crop.doClick();
            assertFalse(named(panel, "tissueCropControls").isVisible());
            assertEquals(revision, session.state().contentRevision(),
                    "Showing optional crop controls must not alter alignment or tissue support");
        });
    }
    private static Component named(final Container parent, final String name) {
        if (name.equals(parent.getName())) return parent;
        for (final Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) {
                final Component match = named(container, name);
                if (match != null) return match;
            }
        }
        return null;
    }
    private static void layout(final Container container) {
        container.doLayout();
        for (final Component child : container.getComponents()) {
            if (child instanceof Container nested) layout(nested);
        }
    }
}
