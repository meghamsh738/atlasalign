package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.Action;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class SwingReviewWindowTest {

    @Test
    void escapeIsInstalledOnRootPaneAndRunsCancellation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JRootPane rootPane = new JRootPane();
            final AtomicInteger cancellations = new AtomicInteger();
            SwingReviewWindow.installEscapeCancellation(
                    rootPane, cancellations::incrementAndGet);

            final Object actionKey = rootPane.getInputMap(
                    JComponent.WHEN_IN_FOCUSED_WINDOW).get(
                            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
            assertNotNull(actionKey);
            final Action action = rootPane.getActionMap().get(actionKey);
            assertNotNull(action);
            action.actionPerformed(new ActionEvent(
                    rootPane, ActionEvent.ACTION_PERFORMED, "escape"));
            assertEquals(1, cancellations.get());
        });
    }

    @Test
    void activeWindowDispatcherConsumesOnlyEscapePress() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JButton source = new JButton();
            final AtomicInteger cancellations = new AtomicInteger();
            final KeyEvent escapePressed = new KeyEvent(source,
                    KeyEvent.KEY_PRESSED, 1L, 0, KeyEvent.VK_ESCAPE,
                    KeyEvent.CHAR_UNDEFINED);
            final KeyEvent escapeReleased = new KeyEvent(source,
                    KeyEvent.KEY_RELEASED, 2L, 0, KeyEvent.VK_ESCAPE,
                    KeyEvent.CHAR_UNDEFINED);
            final KeyEvent enterPressed = new KeyEvent(source,
                    KeyEvent.KEY_PRESSED, 3L, 0, KeyEvent.VK_ENTER, '\n');
            final KeyEvent modifiedEscape = new KeyEvent(source,
                    KeyEvent.KEY_PRESSED, 4L, KeyEvent.SHIFT_DOWN_MASK,
                    KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED);

            assertEquals(false, SwingReviewWindow.dispatchEscape(
                    escapePressed, false, () -> {
                        cancellations.incrementAndGet();
                        return true;
                    }));
            assertEquals(false, SwingReviewWindow.dispatchEscape(
                    escapeReleased, true, () -> {
                        cancellations.incrementAndGet();
                        return true;
                    }));
            assertEquals(false, SwingReviewWindow.dispatchEscape(
                    enterPressed, true, () -> {
                        cancellations.incrementAndGet();
                        return true;
                    }));
            assertEquals(false, SwingReviewWindow.dispatchEscape(
                    modifiedEscape, true, () -> {
                        cancellations.incrementAndGet();
                        return true;
                    }));
            assertEquals(false, SwingReviewWindow.dispatchEscape(
                    escapePressed, true, () -> false));
            assertEquals(true, SwingReviewWindow.dispatchEscape(
                    escapePressed, true, () -> {
                        cancellations.incrementAndGet();
                        return true;
                    }));
            assertEquals(1, cancellations.get());
        });
    }

    @Test
    void planeDispatcherWorksAfterToolbarFocusButPreservesEditors()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JButton pan = new JButton("Pan");
            final AtomicInteger movement = new AtomicInteger();
            final KeyEvent left = new KeyEvent(pan,
                    KeyEvent.KEY_PRESSED, 1L, 0, KeyEvent.VK_LEFT,
                    KeyEvent.CHAR_UNDEFINED);
            final KeyEvent right = new KeyEvent(pan,
                    KeyEvent.KEY_PRESSED, 2L, 0, KeyEvent.VK_RIGHT,
                    KeyEvent.CHAR_UNDEFINED);
            final KeyEvent modified = new KeyEvent(pan,
                    KeyEvent.KEY_PRESSED, 3L, KeyEvent.SHIFT_DOWN_MASK,
                    KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED);

            assertEquals(true, SwingReviewWindow.dispatchPlaneNavigation(
                    left, true, pan, movement::addAndGet));
            assertEquals(-1, movement.get());
            assertEquals(true, SwingReviewWindow.dispatchPlaneNavigation(
                    right, true, pan, movement::addAndGet));
            assertEquals(0, movement.get());
            assertEquals(false, SwingReviewWindow.dispatchPlaneNavigation(
                    modified, true, pan, movement::addAndGet));
            assertEquals(false, SwingReviewWindow.dispatchPlaneNavigation(
                    right, false, pan, movement::addAndGet));

            for (final JComponent editor : new JComponent[]{
                    new JTextField(), new JComboBox<>(), new JSlider()}) {
                assertEquals(false,
                        SwingReviewWindow.dispatchPlaneNavigation(
                                right, true, editor,
                                movement::addAndGet));
            }
            assertEquals(0, movement.get());
        });
    }

    @Test
    void rootPanePlaneBindingsWorkAfterPanAndPreserveEditors()
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final JRootPane root = new JRootPane();
            final JButton pan = new JButton("Pan");
            final JTextField editor = new JTextField();
            final AtomicReference<Component> focus =
                    new AtomicReference<>(pan);
            final AtomicInteger movement = new AtomicInteger();
            SwingReviewWindow.installPlaneNavigation(
                    root, focus::get, movement::addAndGet);

            final Object previous = root.getInputMap(
                    JComponent.WHEN_IN_FOCUSED_WINDOW).get(
                            KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0));
            final Object next = root.getInputMap(
                    JComponent.WHEN_IN_FOCUSED_WINDOW).get(
                            KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0));
            root.getActionMap().get(previous).actionPerformed(null);
            root.getActionMap().get(next).actionPerformed(null);
            assertEquals(0, movement.get());

            focus.set(editor);
            root.getActionMap().get(next).actionPerformed(null);
            assertEquals(0, movement.get());
            assertEquals(null, root.getInputMap(
                    JComponent.WHEN_IN_FOCUSED_WINDOW).get(
                            KeyStroke.getKeyStroke(
                                    KeyEvent.VK_RIGHT,
                                    KeyEvent.SHIFT_DOWN_MASK)));
        });
    }
}
