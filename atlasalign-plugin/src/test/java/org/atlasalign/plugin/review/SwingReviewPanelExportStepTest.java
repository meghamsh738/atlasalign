package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.atlasalign.application.AlignmentReviewSession;
import org.atlasalign.application.ReviewAcceptanceVerification;
import org.atlasalign.application.export.SourcePixelReader;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.plugin.export.SourceSpaceExportService;
import org.junit.jupiter.api.Test;

class SwingReviewPanelExportStepTest {

    @Test
    void acceptedReviewUsesSameWindowExportCardAndBackReturnsToAlignment()
            throws Exception {
        final AtomicReference<SwingReviewPanel> panelRef =
                new AtomicReference<>();
        final AtomicReference<ReviewController> controllerRef =
                new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final var basis = ReviewPluginFixtures.basis();
            final AlignmentReviewSession session =
                    new AlignmentReviewSession(basis);
            final CatalogPlaneSource atlas = new CatalogPlaneSource();
            final var verifier = (org.atlasalign.application
                    .ReviewAcceptanceVerifier) () ->
                    new ReviewAcceptanceVerification(
                            basis.sourceSnapshot(), basis.atlas());
            final ReviewController controller = new ReviewController(
                    session, ReviewPluginFixtures.preview(), atlas, verifier,
                    Runnable::run, Runnable::run);
            final SourceSpaceExportService exporter =
                    new SourceSpaceExportService(
                            new EmptyPixelReader(basis.sourceSnapshot()),
                            atlas, verifier, controller::acceptedAlignment);
            final SwingReviewPanel panel = new SwingReviewPanel(
                    controller, exporter, "source.tif");
            controller.attach(panel);
            controller.setWarningsAcknowledged(true);
            panelRef.set(panel);
            controllerRef.set(controller);
        });
        final SwingReviewPanel panel = panelRef.get();
        final ReviewController controller = controllerRef.get();
        final JPanel cards = (JPanel) named(panel,
                "reviewInspectorCards");
        final JButton accept = (JButton) named(panel, "persistentAccept");
        assertNotNull(named(panel, "exportOntologySearch"));
        assertEquals("Atlas Accept", accept.getText());

        SwingUtilities.invokeAndWait(accept::doClick);

        assertTrue(controller.isAccepted());
        assertEquals("Atlas export", accept.getText());
        assertTrue(cards.getComponent(1).isVisible());
        assertFalse(cards.getComponent(0).isVisible());
        final JCheckBox fullSourceMask = (JCheckBox) named(
                panel, "exportFullSourceMask");
        final JCheckBox maskedSourceCrop = (JCheckBox) named(
                panel, "exportMaskedSourceCrop");
        assertNotNull(fullSourceMask);
        assertNotNull(maskedSourceCrop);
        assertEquals("Full-size mask", fullSourceMask.getText());
        assertEquals("Masked image", maskedSourceCrop.getText());
        assertFalse(fullSourceMask.isSelected());
        assertFalse(maskedSourceCrop.isSelected());
        SwingUtilities.invokeAndWait(() -> {
            for (final Dimension size : List.of(
                    new Dimension(1024, 768),
                    new Dimension(1280, 800))) {
                panel.setSize(size);
                layoutTree(panel);
                layoutTree(panel);
                final Container toolbar = (Container) named(panel, "persistentToolbarRow");
                for (final Component button : toolbar.getComponents()) {
                    if (button.isVisible()) {
                        assertTrue(button.getX() >= 0 && button.getY() >= 0
                                && button.getX() + button.getWidth() <= toolbar.getWidth()
                                && button.getY() + button.getHeight() <= toolbar.getHeight(),
                                "Wrapped toolbar button must remain fully visible: " + button.getName());
                    }
                }
                final JScrollPane content = (JScrollPane) named(
                        panel, "exportContentScroll");
                assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                        content.getHorizontalScrollBarPolicy());
                assertTrue(content.getViewport().getView().getWidth()
                                <= content.getViewport()
                                        .getExtentSize().width,
                        "the export inspector overflows horizontally at "
                                + size.width + "x" + size.height);
                final JPanel buttons = (JPanel) named(
                        panel, "exportActionButtons");
                for (final Component component : buttons.getComponents()) {
                    assertTrue(component.getX() >= 0);
                    assertTrue(component.getX() + component.getWidth()
                                    <= buttons.getWidth(),
                            component + " is clipped in the export actions at "
                                    + size.width + "x" + size.height);
                }
                for (final JCheckBox option : List.of(
                        fullSourceMask, maskedSourceCrop)) {
                    assertTrue(option.getX() >= 0);
                    assertTrue(option.getX() + option.getWidth()
                                    <= option.getParent().getWidth(),
                            option.getText()
                                    + " is clipped in the export inspector at "
                                    + size.width + "x" + size.height);
                }
            }
        });

        final JButton back = (JButton) named(panel,
                "exportBackToAlignment");
        SwingUtilities.invokeAndWait(back::doClick);

        assertTrue(cards.getComponent(0).isVisible());
        assertFalse(cards.getComponent(1).isVisible());
        assertTrue(controller.isAccepted());

        SwingUtilities.invokeAndWait(() -> controller.translate(1, 0));

        assertFalse(controller.isAccepted());
        assertEquals("Atlas Accept", accept.getText());
        assertTrue(cards.getComponent(0).isVisible());
        SwingUtilities.invokeAndWait(controller::close);
    }

    private static Component named(
            final Container root,
            final String name) {
        for (final Component child : root.getComponents()) {
            if (child instanceof JComponent component
                    && name.equals(component.getName())) {
                return child;
            }
            if (child instanceof Container container) {
                final Component found = named(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void layoutTree(final Container container) {
        container.doLayout();
        for (final Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutTree(nested);
            }
        }
    }

    private static final class CatalogPlaneSource
            implements AtlasPlaneSource, AtlasRegionCatalog {
        @Override
        public org.atlasalign.atlas.AtlasCoronalPlane load(
                final int level) {
            return ReviewPluginFixtures.plane(level);
        }

        @Override
        public Optional<SelectedAtlasRegion> resolveExactAcronym(
                final String acronym) {
            return Optional.of(new SelectedAtlasRegion(
                    1, acronym, acronym + " name", Set.of(1)));
        }

        @Override
        public List<SelectedAtlasRegion> search(
                final String query,
                final int maximumResults) {
            return List.of(new SelectedAtlasRegion(
                    1, "DG", "Dentate gyrus", Set.of(1)));
        }
    }

    private record EmptyPixelReader(SourceImageSnapshot snapshot)
            implements SourcePixelReader {
        @Override
        public PixelBlock readPlane(
                final int oneBasedChannel,
                final int oneBasedSlice,
                final int oneBasedFrame,
                final Bounds bounds) {
            return new ByteBlock(bounds.width(), bounds.height(),
                    new byte[bounds.pixelCount()]);
        }
    }
}
