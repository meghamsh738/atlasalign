package org.atlasalign.plugin.review;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JRootPane;
import javax.swing.JScrollBar;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.text.JTextComponent;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.plugin.export.ManualRoiExportService;
import org.atlasalign.plugin.export.SourceSpaceExportService;

/**
 * Top-level review window, deliberately separate from the headless-testable
 * panel.
 */
public final class SwingReviewWindow {

    private SwingReviewWindow() {
    }

    public static JFrame open(
            final String sourceTitle,
            final ReviewController controller) {
        return open(sourceTitle, controller, null);
    }

    public static JFrame open(
            final String sourceTitle,
            final ReviewController controller,
            final SourceSpaceExportService exportService) {
        return open(sourceTitle, controller, exportService, null, null);
    }

    public static JFrame open(
            final String sourceTitle,
            final ReviewController controller,
            final SourceSpaceExportService exportService,
            final ManualRoiExportService manualRoiExportService,
            final ReviewerRoiSession manualRoiSession) {
        Objects.requireNonNull(sourceTitle, "sourceTitle");
        Objects.requireNonNull(controller, "controller");
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Review window must be opened on the Swing event-dispatch thread");
        }
        final JFrame frame = new JFrame(
                "AtlasAlign Lite review — " + sourceTitle);
        final SwingReviewPanel panel = exportService == null
                ? new SwingReviewPanel(controller)
                : manualRoiExportService != null && manualRoiSession != null
                ? new SwingReviewPanel(controller, exportService,
                        manualRoiExportService, manualRoiSession, sourceTitle)
                : new SwingReviewPanel(
                        controller, exportService, sourceTitle);
        panel.setCloseHandler(frame::dispose);
        installEscapeCancellation(
                frame.getRootPane(), panel::cancelCanvasInteraction);
        final KeyboardFocusManager focusManager = KeyboardFocusManager
                .getCurrentKeyboardFocusManager();
        installPlaneNavigation(
                frame.getRootPane(), focusManager::getFocusOwner,
                panel::nudgeCoronalLevel);
        final KeyEventDispatcher escapeDispatcher = event -> dispatchEscape(
                event, reviewWindowOwns(
                        frame, event, focusManager.getFocusOwner()),
                panel::cancelCanvasInteraction);
        final KeyEventDispatcher planeDispatcher = event ->
                dispatchPlaneNavigation(event, reviewWindowOwns(
                                frame, event, focusManager.getFocusOwner()),
                        focusManager.getFocusOwner(),
                        panel::nudgeCoronalLevel);
        focusManager.addKeyEventDispatcher(escapeDispatcher);
        focusManager.addKeyEventDispatcher(planeDispatcher);
        frame.setDefaultCloseOperation(
                WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent event) {
                controller.close();
            }

            @Override
            public void windowClosed(final WindowEvent event) {
                focusManager.removeKeyEventDispatcher(escapeDispatcher);
                focusManager.removeKeyEventDispatcher(planeDispatcher);
            }
        });
        frame.setLayout(new BorderLayout());
        frame.add(panel, BorderLayout.CENTER);
        frame.pack();
        final Rectangle usable = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        frame.setSize(
                Math.min(1280, usable.width),
                Math.min(800, usable.height));
        frame.setMinimumSize(new Dimension(
                Math.min(800, usable.width),
                Math.min(560, usable.height)));
        frame.setLocation(
                usable.x + Math.max(0, (usable.width - frame.getWidth()) / 2),
                usable.y + Math.max(0, (usable.height - frame.getHeight()) / 2));
        controller.attach(panel);
        frame.setVisible(true);
        return frame;
    }

    static void installEscapeCancellation(
            final JRootPane rootPane, final Runnable cancellation) {
        final JRootPane checkedRoot = Objects.requireNonNull(
                rootPane, "rootPane");
        final Runnable checkedCancellation = Objects.requireNonNull(
                cancellation, "cancellation");
        final String actionKey = "cancel-review-interaction";
        checkedRoot.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), actionKey);
        checkedRoot.getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                checkedCancellation.run();
            }
        });
    }

    /**
     * Installs AP-plane arrows on the review root pane. A root-pane binding is
     * more reliable than a global dispatcher in hosted AWT applications such
     * as Fiji on macOS, while Swing still gives focused editors, popups, lists,
     * and sliders first refusal of their native arrow bindings.
     */
    static void installPlaneNavigation(
            final JRootPane rootPane,
            final Supplier<Component> focusOwner,
            final IntConsumer navigation) {
        final JRootPane checkedRoot = Objects.requireNonNull(
                rootPane, "rootPane");
        final Supplier<Component> checkedFocusOwner = Objects.requireNonNull(
                focusOwner, "focusOwner");
        final IntConsumer checkedNavigation = Objects.requireNonNull(
                navigation, "navigation");
        installPlaneNavigationKey(
                checkedRoot, checkedFocusOwner, checkedNavigation,
                KeyEvent.VK_LEFT, -1, "previous-atlas-plane");
        installPlaneNavigationKey(
                checkedRoot, checkedFocusOwner, checkedNavigation,
                KeyEvent.VK_RIGHT, 1, "next-atlas-plane");
    }

    private static void installPlaneNavigationKey(
            final JRootPane rootPane,
            final Supplier<Component> focusOwner,
            final IntConsumer navigation,
            final int keyCode,
            final int delta,
            final String actionKey) {
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(keyCode, 0), actionKey);
        rootPane.getActionMap().put(actionKey, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                if (!preservesNativeArrowNavigation(focusOwner.get())) {
                    navigation.accept(delta);
                }
            }
        });
    }

    private static boolean reviewWindowOwns(
            final JFrame frame,
            final KeyEvent event,
            final Component focusOwner) {
        final Component source = event.getComponent();
        return frame.isShowing()
                && (belongsToFrame(source, frame)
                || belongsToFrame(focusOwner, frame));
    }

    private static boolean belongsToFrame(
            final Component component, final JFrame frame) {
        return component != null && (component == frame
                || SwingUtilities.isDescendingFrom(component, frame));
    }

    static boolean dispatchEscape(
            final KeyEvent event,
            final boolean reviewWindowActive,
            final BooleanSupplier cancellation) {
        Objects.requireNonNull(event, "event");
        final BooleanSupplier checkedCancellation = Objects.requireNonNull(
                cancellation, "cancellation");
        if (!reviewWindowActive || event.getID() != KeyEvent.KEY_PRESSED
                || event.getKeyCode() != KeyEvent.VK_ESCAPE
                || event.getModifiersEx() != 0) {
            return false;
        }
        // Consume only when the review canvas actually owned the interaction.
        // Otherwise the focused outline canvas or dialog must receive Escape.
        return checkedCancellation.getAsBoolean();
    }

    /**
     * Restores review-wide AP-plane navigation without stealing the arrow
     * keys owned by an editor, popup, list, or another slider. Toolbar
     * buttons (notably Pan) deliberately do not opt out, so the reviewer can
     * continue changing atlas planes immediately after clicking them.
     */
    static boolean dispatchPlaneNavigation(
            final KeyEvent event,
            final boolean reviewWindowActive,
            final Component focusOwner,
            final IntConsumer navigation) {
        Objects.requireNonNull(event, "event");
        final IntConsumer checkedNavigation = Objects.requireNonNull(
                navigation, "navigation");
        if (!reviewWindowActive || event.getID() != KeyEvent.KEY_PRESSED
                || event.getModifiersEx() != 0
                || event.getKeyCode() != KeyEvent.VK_LEFT
                && event.getKeyCode() != KeyEvent.VK_RIGHT
                || preservesNativeArrowNavigation(focusOwner)) {
            return false;
        }
        checkedNavigation.accept(event.getKeyCode() == KeyEvent.VK_LEFT
                ? -1 : 1);
        return true;
    }

    private static boolean preservesNativeArrowNavigation(
            final Component focusOwner) {
        Component current = focusOwner;
        while (current != null) {
            if (current instanceof JTextComponent
                    || current instanceof JComboBox<?>
                    || current instanceof JList<?>
                    || current instanceof JTable
                    || current instanceof JTree
                    || current instanceof JSlider
                    || current instanceof JSpinner
                    || current instanceof JScrollBar) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }
}
