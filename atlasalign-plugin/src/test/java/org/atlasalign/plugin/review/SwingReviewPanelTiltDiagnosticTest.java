package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Color;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Optional;
import java.util.prefs.Preferences;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JRadioButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.ComboPopup;
import org.atlasalign.application.AlignmentReviewBasis;
import org.atlasalign.application.AlignmentReviewSession;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.BaselineRegistrationProposal;
import org.atlasalign.application.DeepSliceOuv;
import org.atlasalign.application.DeepSlicePlanePrediction;
import org.atlasalign.application.InitialPlaneProposal;
import org.atlasalign.application.InitialPlaneSource;
import org.atlasalign.application.ReviewWorkflowMode;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.application.SectionGeometry;
import org.junit.jupiter.api.Test;

/** Headless Swing coverage for the one-surface manual-warp workflow. */
class SwingReviewPanelTiltDiagnosticTest {

    static {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void controlsUseOneDirectWorkspaceWithoutTabsOrHorizontalScrolling()
            throws Exception {
        final ReviewController controller = automaticController();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            assertFalse(containsTabbedPane(panel));
            final JComboBox<?> density = findNamed(panel,
                    "gridControlDensity", JComboBox.class);
            final JScrollPane scroll = (JScrollPane) SwingUtilities
                    .getAncestorOfClass(JScrollPane.class, density);
            assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER,
                    scroll.getHorizontalScrollBarPolicy());
            final JPanel content = (JPanel) scroll.getViewport().getView();
            assertTrue(allLeafComponentsFit(content, 300),
                    widestLeafComponent(content));
        });
    }

    @Test
    void densityChoicesAndComparisonViewsAreInTheTopViewPopup()
            throws Exception {
        final ReviewController controller = controller();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JComboBox<?> gridDensity = findNamed(panel,
                    "gridControlDensity", JComboBox.class);
            final JComboBox<?> structureDensity = findNamed(panel,
                    "structureControlDensity", JComboBox.class);
            final javax.swing.JSlider thickness = findNamed(panel,
                    "structureRoiSize", javax.swing.JSlider.class);
            assertEquals(List.of(4, 6, 8, 12, 16, 24, 32, 48, 64),
                    java.util.stream.IntStream.range(0,
                                    gridDensity.getItemCount())
                            .mapToObj(gridDensity::getItemAt).toList());
            assertEquals(List.of(4, 6, 8, 12, 16, 24, 32, 48, 64),
                    java.util.stream.IntStream.range(0,
                                    structureDensity.getItemCount())
                            .mapToObj(structureDensity::getItemAt).toList());
            assertEquals(48, gridDensity.getSelectedItem());
            assertEquals(48, structureDensity.getSelectedItem());
            assertEquals(75, thickness.getMinimum());
            assertEquals(300, thickness.getMaximum());
            final JButton view = (JButton) findButton(panel, "View ▾");
            final JPopupMenu popup = view.getComponentPopupMenu();
            final AbstractButton before = findButton(popup, "Before");
            final AbstractButton after = findButton(popup, "After");
            final AbstractButton compare = findButton(popup, "Compare");
            assertTrue(before.isVisible());
            assertTrue(after.isVisible());
            assertTrue(compare.isVisible());
            before.doClick();
            assertEquals(ReviewCanvas.ComparisonMode.BEFORE,
                    panel.canvas().comparisonMode());
            compare.doClick();
            assertEquals(ReviewCanvas.ComparisonMode.COMPARE,
                    panel.canvas().comparisonMode());
            after.doClick();
            assertEquals(ReviewCanvas.ComparisonMode.AFTER,
                    panel.canvas().comparisonMode());
            assertFalse(allButtonLabels(panel).stream()
                    .anyMatch(label -> label.contains("on boundary")));
            assertTrue(findButton(panel, "Re-suggest crop").isVisible());
        });
    }

    @Test
    void directAndDisplayDefaultsFollowTheSelectedSectionMode()
            throws Exception {
        final ReviewController controller = controller(
                ReviewPluginFixtures.segmentedScaledAtlasBasis(
                        SectionGeometry.IMAGE_LEFT_HALF));

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JComboBox<?> mode = findNamed(panel,
                    "reviewSectionMode", JComboBox.class);
            assertEquals(ReviewSectionMode.HALF, mode.getSelectedItem());
            final JRadioButton direct = (JRadioButton) findButton(
                    panel, "Direct");
            assertTrue(direct.isSelected());
            final JPopupMenu popup = ((JButton) findButton(panel, "View ▾"))
                    .getComponentPopupMenu();
            assertTrue(findNamed(popup, "displayTissueClipping",
                    JCheckBox.class).isSelected());
            assertTrue(findNamed(popup, "displayDisplacementLines",
                    JCheckBox.class).isSelected());
            assertFalse(findNamed(popup, "displayPointLabels",
                    JCheckBox.class).isSelected());
            assertFalse(findNamed(popup, "displayWarpGrid",
                    JCheckBox.class).isSelected());
        });
    }

    @Test
    void closingAnAccessibilitySelectedSectionPopupCommitsTheReviewMode()
            throws Exception {
        final ReviewController controller = controller();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JComboBox<?> mode = findNamed(panel,
                    "reviewSectionMode", JComboBox.class);
            assertEquals(ReviewSectionMode.FULL,
                    controller.state().content().reviewSectionMode());

            final Object accessiblePopup = mode.getUI()
                    .getAccessibleChild(mode, 0);
            assertTrue(accessiblePopup instanceof ComboPopup);
            final ComboPopup popup = (ComboPopup) accessiblePopup;
            popup.getList().setSelectedValue(
                    ReviewSectionMode.DISJOINED, true);
            assertEquals(ReviewSectionMode.FULL,
                    controller.state().content().reviewSectionMode(),
                    "highlighting the accessibility popup row alone must not mutate review content");

            final PopupMenuEvent close = new PopupMenuEvent(mode);
            for (final PopupMenuListener listener
                    : mode.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeInvisible(close);
            }

            assertEquals(ReviewSectionMode.DISJOINED,
                    controller.state().content().reviewSectionMode());
            assertEquals(ReviewSectionMode.DISJOINED,
                    mode.getSelectedItem());
        });
    }

    @Test
    void disclosureCancelCommitsTheAccessibilityHighlightedSectionMode()
            throws Exception {
        final ReviewController controller = controller();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JComboBox<?> mode = findNamed(panel,
                    "reviewSectionMode", JComboBox.class);
            final ComboPopup popup = (ComboPopup) mode.getUI()
                    .getAccessibleChild(mode, 0);
            final PopupMenuEvent event = new PopupMenuEvent(mode);
            final long revision = controller.state().contentRevision();

            for (final PopupMenuListener listener
                    : mode.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeVisible(event);
            }
            popup.getList().setSelectedValue(
                    ReviewSectionMode.DISJOINED, true);
            for (final PopupMenuListener listener
                    : mode.getPopupMenuListeners()) {
                listener.popupMenuCanceled(event);
            }
            // Native Swing can restore the combo model's original value after
            // reporting cancellation but before reporting invisibility.  The
            // accessibility choice captured above must remain authoritative.
            popup.getList().setSelectedValue(ReviewSectionMode.FULL, true);
            for (final PopupMenuListener listener
                    : mode.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeInvisible(event);
            }

            assertEquals(revision + 1,
                    controller.state().contentRevision());
            assertEquals(ReviewSectionMode.DISJOINED,
                    controller.state().content().reviewSectionMode());
            assertEquals(ReviewSectionMode.DISJOINED,
                    mode.getSelectedItem());
        });
    }

    @Test
    void escapeCancelsAnAccessibilityHighlightedSectionMode()
            throws Exception {
        final ReviewController controller = controller();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JComboBox<?> mode = findNamed(panel,
                    "reviewSectionMode", JComboBox.class);
            final ComboPopup popup = (ComboPopup) mode.getUI()
                    .getAccessibleChild(mode, 0);
            final PopupMenuEvent event = new PopupMenuEvent(mode);

            for (final PopupMenuListener listener
                    : mode.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeVisible(event);
            }
            popup.getList().setSelectedValue(
                    ReviewSectionMode.DISJOINED, true);
            final KeyEvent escape = new KeyEvent(popup.getList(),
                    KeyEvent.KEY_PRESSED, 1L, 0,
                    KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);
            for (final var listener : popup.getList().getKeyListeners()) {
                listener.keyPressed(escape);
            }
            for (final PopupMenuListener listener
                    : mode.getPopupMenuListeners()) {
                listener.popupMenuCanceled(event);
                listener.popupMenuWillBecomeInvisible(event);
            }

            assertEquals(ReviewSectionMode.FULL,
                    controller.state().content().reviewSectionMode());
            assertEquals(ReviewSectionMode.FULL, mode.getSelectedItem());
        });
    }

    @Test
    void retainedAccessibilitySelectionCommitsBeforeLeavingSetupAndAcceptance()
            throws Exception {
        final ReviewController controller = controller(
                ReviewPluginFixtures.segmentedScaledAtlasBasis(
                        SectionGeometry.IMAGE_LEFT_HALF));

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JComboBox<?> mode = findNamed(panel,
                    "reviewSectionMode", JComboBox.class);

            // Reproduce the native accessibility path: the combo visibly
            // retains Disjoined, but its ordinary ActionEvent never reaches
            // the panel before the popup is closed through the disclosure.
            final ActionListener[] actions = mode.getActionListeners();
            for (final ActionListener action : actions) {
                mode.removeActionListener(action);
            }
            mode.setSelectedItem(ReviewSectionMode.DISJOINED);
            for (final ActionListener action : actions) {
                mode.addActionListener(action);
            }
            assertEquals(ReviewSectionMode.HALF,
                    controller.state().content().reviewSectionMode());
            assertEquals(ReviewSectionMode.DISJOINED,
                    mode.getSelectedItem());

            final PopupMenuEvent event = new PopupMenuEvent(mode);
            for (final PopupMenuListener listener
                    : mode.getPopupMenuListeners()) {
                listener.popupMenuCanceled(event);
                listener.popupMenuWillBecomeInvisible(event);
            }

            assertEquals(ReviewSectionMode.DISJOINED,
                    controller.state().content().reviewSectionMode());
            findButton(panel, "4 Review & Export").doClick();
            controller.setWarningsAcknowledged(true);
            assertEquals(ReviewSectionMode.DISJOINED,
                    controller.accept().orElseThrow().reviewSectionMode());
        });
    }

    @Test
    void stageNavigationRepairsAVisibleSectionModeMismatch()
            throws Exception {
        final ReviewController controller = controller();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JComboBox<?> mode = findNamed(panel,
                    "reviewSectionMode", JComboBox.class);
            final ActionListener[] actions = mode.getActionListeners();
            for (final ActionListener action : actions) {
                mode.removeActionListener(action);
            }
            mode.setSelectedItem(ReviewSectionMode.DISJOINED);
            for (final ActionListener action : actions) {
                mode.addActionListener(action);
            }

            findButton(panel, "4 Review & Export").doClick();

            assertEquals(ReviewSectionMode.DISJOINED,
                    controller.state().content().reviewSectionMode());
            assertEquals(ReviewSectionMode.DISJOINED,
                    mode.getSelectedItem());
        });
    }

    @Test
    void firstAcceptRepairsVisibleModeMismatchWithoutAcceptingStaleContent()
            throws Exception {
        final ReviewController controller = controller();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JComboBox<?> mode = findNamed(panel,
                    "reviewSectionMode", JComboBox.class);
            final ActionListener[] actions = mode.getActionListeners();
            for (final ActionListener action : actions) {
                mode.removeActionListener(action);
            }
            mode.setSelectedItem(ReviewSectionMode.DISJOINED);
            for (final ActionListener action : actions) {
                mode.addActionListener(action);
            }
            final JButton acceptButton = findNamed(panel,
                    "persistentAccept", JButton.class);

            acceptButton.doClick();

            assertEquals(ReviewSectionMode.DISJOINED,
                    controller.state().content().reviewSectionMode());
            assertTrue(controller.acceptedAlignment().isEmpty(),
                    "repairing a visible/model mismatch must not accept in the same click");

            controller.setWarningsAcknowledged(true);
            acceptButton.doClick();

            assertEquals(ReviewSectionMode.DISJOINED,
                    controller.acceptedAlignment().orElseThrow()
                            .reviewSectionMode());
        });
    }

    @Test
    void normalSelectionAndPopupCloseCreateExactlyOneModeRevision()
            throws Exception {
        final ReviewController controller = controller();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JComboBox<?> mode = findNamed(panel,
                    "reviewSectionMode", JComboBox.class);
            final long revision = controller.state().contentRevision();

            mode.setSelectedItem(ReviewSectionMode.DISJOINED);
            final PopupMenuEvent close = new PopupMenuEvent(mode);
            for (final PopupMenuListener listener
                    : mode.getPopupMenuListeners()) {
                listener.popupMenuWillBecomeInvisible(close);
            }

            assertEquals(revision + 1,
                    controller.state().contentRevision());
            assertEquals(ReviewSectionMode.DISJOINED,
                    controller.state().content().reviewSectionMode());
        });
    }

    @Test
    void rejectedSectionSelectionRestoresTheRenderedReviewMode()
            throws Exception {
        final ReviewController controller = controller();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JComboBox<?> mode = findNamed(panel,
                    "reviewSectionMode", JComboBox.class);
            assertEquals(ReviewSectionMode.FULL, mode.getSelectedItem());
            controller.close();

            mode.setSelectedItem(ReviewSectionMode.DISJOINED);

            assertEquals(ReviewSectionMode.FULL, mode.getSelectedItem());
            assertEquals(ReviewSectionMode.FULL,
                    controller.state().content().reviewSectionMode());
        });
    }

    @Test
    void roiStylePreferencesSurviveAReviewWindowRestart() throws Exception {
        final Preferences preferences = Preferences.userNodeForPackage(
                SwingReviewPanel.class).node("review-display");
        final String selectedKey = "selected-guide-color";
        final String dimKey = "dim-guide-color";
        final String thicknessKey = "guide-thickness";
        final String priorSelected = preferences.get(selectedKey, null);
        final String priorDim = preferences.get(dimKey, null);
        final String priorThickness = preferences.get(thicknessKey, null);
        final Color selected = new Color(31, 147, 221, 255);
        final Color dim = new Color(122, 91, 44, 210);
        try {
            preferences.putInt(selectedKey, selected.getRGB());
            preferences.putInt(dimKey, dim.getRGB());
            preferences.putDouble(thicknessKey, 3.75);
            SwingUtilities.invokeAndWait(() -> {
                final ReviewController firstController = controller();
                final SwingReviewPanel first = new SwingReviewPanel(
                        firstController);
                firstController.attach(first);
                assertEquals(selected, first.canvas().selectedRegionColor());
                assertEquals(dim, first.canvas().dimRegionColor());
                assertEquals(3.75, first.canvas().regionStrokeWidth());
                firstController.close();

                final ReviewController restartedController = controller();
                final SwingReviewPanel restarted = new SwingReviewPanel(
                        restartedController);
                restartedController.attach(restarted);
                assertEquals(selected,
                        restarted.canvas().selectedRegionColor());
                assertEquals(dim, restarted.canvas().dimRegionColor());
                assertEquals(3.75,
                        restarted.canvas().regionStrokeWidth());
                restartedController.close();
            });
        } finally {
            restorePreference(preferences, selectedKey, priorSelected);
            restorePreference(preferences, dimKey, priorDim);
            restorePreference(preferences, thicknessKey, priorThickness);
        }
    }

    @Test
    void automaticReviewCanEnterManualWarpWithoutOpeningAnotherSurface()
            throws Exception {
        final ReviewController controller = automaticController();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final AbstractButton start = findButton(panel, "Refine manually");
            assertTrue(start.isEnabled());
            assertTrue(findNamed(panel, "workflowStage5Scroll",
                    JScrollPane.class).isVisible());
            assertFalse(findNamed(panel, "manualInspectorScroll",
                    JScrollPane.class).isVisible());

            start.doClick();

            assertEquals(ReviewWorkflowMode.MANUAL_REFINEMENT,
                    controller.state().content().workflowMode());
            assertTrue(findNamed(panel, "manualInspectorScroll",
                    JScrollPane.class).isVisible());
            assertFalse(findNamed(panel, "workflowStage5Scroll",
                    JScrollPane.class).isVisible());
            assertFalse(containsTabbedPane(panel));
            assertFalse(allButtonLabels(panel).stream().anyMatch(text ->
                    text.contains("guided manual workflow")
                            || text.contains("fit similarity")
                            || text.contains("fit global affine")
                            || text.contains("generic local warp")
                            || text.equals("t fit")
                            || text.equals("a fit")
                            || text.equals("fit both")));
        });
    }

    @Test
    void persistentReviewActionsRemainVisibleAtSupportedWidths()
            throws Exception {
        final ReviewController controller = controller();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            assertTrue(findNamed(panel, "persistentBack", JButton.class)
                    .isVisible());
            assertTrue(findNamed(panel, "persistentUndo", JButton.class)
                    .isVisible());
            assertTrue(findNamed(panel, "persistentRedo", JButton.class)
                    .isVisible());
            assertTrue(findNamed(panel, "persistentReset", JButton.class)
                    .isVisible());
            assertTrue(findNamed(panel, "persistentAccept", JButton.class)
                    .isVisible());
            assertTrue(findNamed(panel, "persistentClearWarp", JButton.class)
                    .isVisible());
        });
    }

    @Test
    void exportPreviewCannotLeakOutOfStructure()
            throws Exception {
        final ReviewController controller = controller();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            final JButton setup = findNamed(panel,
                    "workflowStep0", JButton.class);
            final JButton structure = findNamed(panel,
                    "workflowStep4", JButton.class);
            final JCheckBox preview = findNamed(panel,
                    "previewExportedRoi", JCheckBox.class);

            structure.doClick();
            preview.setEnabled(true);
            preview.setSelected(true);
            panel.canvas().setExportedRoiPreviewVisible(true);
            assertTrue(panel.canvas().exportedRoiPreviewVisibleForTests());

            setup.doClick();
            assertFalse(preview.isSelected());
            assertFalse(preview.isEnabled());
            assertFalse(panel.canvas().exportedRoiPreviewVisibleForTests(),
                    "changing tabs must restore the ordinary tissue and atlas overlay");
        });
    }

    @Test
    void guidedCandidateReturnsToTheDirectManualWarpWorkspace()
            throws Exception {
        final ReviewController controller = controller();

        SwingUtilities.invokeAndWait(() -> {
            final SwingReviewPanel panel = new SwingReviewPanel(controller);
            controller.attach(panel);
            panel.openFineAnatomyWarpAfterGuidedCandidate();

            assertFalse(containsTabbedPane(panel));
            assertEquals(ReviewCanvas.InteractionTool.POINTS,
                    panel.canvas().interactionTool());
        });
    }

    private static ReviewController controller() {
        final AlignmentReviewBasis basis = ReviewPluginFixtures.basis();
        return controller(basis);
    }

    private static ReviewController automaticController() {
        final AlignmentReviewBasis fixture = ReviewPluginFixtures.basis();
        final DeepSliceOuv plane = new DeepSliceOuv(
                0, 100, 0, 3, 0, 0, 0, 0, -3);
        final DeepSlicePlanePrediction prediction =
                new DeepSlicePlanePrediction(plane, plane, plane);
        final AllenCoronalLevel level = new AllenCoronalLevel(
                prediction.zeroBasedAnteriorPosteriorIndex());
        final InitialPlaneProposal initial = new InitialPlaneProposal(
                level,
                InitialPlaneSource.LOCAL_DEEPSLICE,
                Optional.of(prediction),
                Optional.empty(), Optional.empty());
        final BaselineRegistrationProposal baseline =
                new BaselineRegistrationProposal(
                        level, fixture.proposal().geometry(),
                        fixture.proposal().similarity(),
                        fixture.proposal().affine(),
                        fixture.proposal().objectiveMode(),
                        fixture.proposal().similarityDice(),
                        fixture.proposal().affineDice());
        final AlignmentReviewBasis basis = new AlignmentReviewBasis(
                baseline, Optional.of(initial),
                fixture.segmentation(), fixture.sourceSnapshot(),
                fixture.atlas(), fixture.syntheticPixelPolicy(),
                fixture.inferencePreparationProvenance(),
                fixture.previewDimensions());
        return controller(basis);
    }

    private static ReviewController controller(
            final AlignmentReviewBasis basis) {
        return new ReviewController(
                new AlignmentReviewSession(basis),
                ReviewPluginFixtures.preview(),
                ReviewPluginFixtures::plane,
                () -> new org.atlasalign.application.ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run,
                Runnable::run);
    }

    private static boolean allLeafComponentsFit(
            final Container root,
            final int viewportWidth) {
        for (final Component component : root.getComponents()) {
            if ((!(component instanceof Container)
                    || ((Container) component).getComponentCount() == 0)
                    && component.getPreferredSize().width > viewportWidth) {
                return false;
            }
            if (component instanceof Container container
                    && !allLeafComponentsFit(container, viewportWidth)) {
                return false;
            }
        }
        return true;
    }

    private static void restorePreference(
            final Preferences preferences,
            final String key,
            final String priorValue) {
        if (priorValue == null) {
            preferences.remove(key);
        } else {
            preferences.put(key, priorValue);
        }
    }

    private static String widestLeafComponent(final Container root) {
        Component widest = null;
        String childResult = "";
        for (final Component component : root.getComponents()) {
            if ((!(component instanceof Container)
                    || ((Container) component).getComponentCount() == 0)
                    && (widest == null || component.getPreferredSize().width
                    > widest.getPreferredSize().width)) {
                widest = component;
            }
            if (component instanceof Container container) {
                final String child = widestLeafComponent(container);
                if (!child.isEmpty()) {
                    final int childWidth = Integer.parseInt(
                            child.substring(0, child.indexOf(':')));
                    final int ownWidth = widest == null ? -1
                            : widest.getPreferredSize().width;
                    if (childWidth > ownWidth) {
                        childResult = child;
                    }
                }
            }
        }
        if (!childResult.isEmpty()) {
            return childResult;
        }
        return widest == null ? "" : widest.getPreferredSize().width + ":"
                + widest.getClass().getSimpleName();
    }

    private static boolean containsTabbedPane(final Container root) {
        for (final Component component : root.getComponents()) {
            if (component instanceof javax.swing.JTabbedPane) {
                return true;
            }
            if (component instanceof Container container
                    && containsTabbedPane(container)) {
                return true;
            }
        }
        return false;
    }

    private static AbstractButton findButton(
            final Container root,
            final String text) {
        for (final Component component : root.getComponents()) {
            if (component instanceof AbstractButton button
                    && text.equals(button.getText())) {
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
        throw new IllegalArgumentException("Button was not found: " + text);
    }

    private static <T extends Component> T findNamed(
            final Container root,
            final String name,
            final Class<T> type) {
        for (final Component component : root.getComponents()) {
            if (name.equals(component.getName()) && type.isInstance(component)) {
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
        throw new IllegalArgumentException("Named component was not found: "
                + name);
    }

    private static List<String> allButtonLabels(final Container root) {
        final List<String> labels = new java.util.ArrayList<>();
        for (final Component component : root.getComponents()) {
            if (component instanceof JButton button
                    && button.getText() != null) {
                labels.add(button.getText().toLowerCase(
                        java.util.Locale.ROOT));
            }
            if (component instanceof Container container) {
                labels.addAll(allButtonLabels(container));
            }
        }
        return labels;
    }
}
