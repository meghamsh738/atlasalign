package org.atlasalign.plugin.batch;

import static org.junit.jupiter.api.Assertions.*;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.ByteProcessor;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scijava.command.CommandService;

class WholeSlideBatchPanelTest {
    @TempDir Path directory;

    @Test
    void nineSectionQueueHasCompactFieldsAndAccessibleControlsAtLaptopSizes() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final var panel = panel();
            for (var size : new Dimension[] {new Dimension(960, 640), new Dimension(720, 480)}) {
                panel.setSize(size);
                for (int pass = 0; pass < 3; pass++) layout(panel);
                final var name = (JTextField) find(panel, "batchSectionName");
                final var level = (JSpinner) find(panel, "batchSectionCoronalLevel");
                assertTrue(name.getHeight() > 0 && name.getHeight() <= 32,
                        "Name must remain one line: " + name.getBounds());
                assertTrue(level.getHeight() > 0 && level.getHeight() <= 32);
                final var summary = (JLabel) find(panel, "batchQueueSummary");
                assertTrue(summary.getText().startsWith("9 sections queued"));
                final var queue = (JList<?>) find(panel, "batchSectionQueue");
                assertEquals(9, queue.getModel().getSize());
                final var scroll = (JScrollPane) find(panel, "batchInspectorScroll");
                final var view = scroll.getViewport().getView();
                assertTrue(view.getWidth() <= scroll.getViewport().getExtentSize().width,
                        "Inspector must not clip horizontally");
                for (String buttonName : new String[] {"batchOpenSelected", "batchOpenNext",
                        "batchMarkComplete", "batchExportAllManualRois"}) {
                    final var button = (JButton) find(panel, buttonName);
                    assertTrue(button.getWidth() >= button.getPreferredSize().width,
                            buttonName + " must retain its full label");
                }
                for (String primary : new String[] {"batchOpenSelected", "batchOpenNext"}) {
                    final Component button = find(panel, primary);
                    final var visibleBounds = SwingUtilities.convertRectangle(
                            button.getParent(), button.getBounds(), view);
                    assertTrue(scroll.getViewport().getViewRect().contains(visibleBounds),
                            primary + " must be visible without scrolling at " + size);
                }
                final var overview = find(panel, "batchWholeSlideOverview");
                final var canvas = find(panel, "batchWholeSlideCanvas");
                assertTrue(overview.getWidth() > 0 && overview.getHeight() >= 140);
                assertTrue(canvas.getWidth() > 0 && canvas.getHeight() > 0);
                final var credit = find(panel, "atlasalignAuthorCredit");
                assertNotNull(credit);
                final var creditBounds = SwingUtilities.convertRectangle(credit.getParent(), credit.getBounds(), panel);
                assertTrue(new java.awt.Rectangle(0, 0, panel.getWidth(), panel.getHeight()).contains(creditBounds));
                final var status = find(panel, "batchQueueStatus");
                assertTrue(SwingUtilities.isDescendingFrom(status, panel));
                assertTrue(status.getY() + status.getHeight() <= panel.getHeight());
                assertTrue(status.getHeight() > 0);
            }
        });
    }

    @Test
    void selectingAnotherSectionReturnsInspectorToPreviewWithoutResettingSameSectionEdits() throws Exception {
        final var holder = new java.util.concurrent.atomic.AtomicReference<WholeSlideBatchPanel>();
        SwingUtilities.invokeAndWait(() -> {
            final var panel = panel();
            holder.set(panel);
            panel.setSize(new Dimension(720, 480));
            for (int pass = 0; pass < 3; pass++) layout(panel);
        });
        // Flush the initial deferred scroll reset before simulating user scrolling.
        SwingUtilities.invokeAndWait(() -> {
            final var panel = holder.get();
            final var scroll = (JScrollPane) find(panel, "batchInspectorScroll");
            scroll.getViewport().setViewPosition(new java.awt.Point(0, 200));
            ((JList<?>) find(panel, "batchSectionQueue")).setSelectedIndex(2);
        });
        SwingUtilities.invokeAndWait(() -> {
            final var panel = holder.get();
            final var scroll = (JScrollPane) find(panel, "batchInspectorScroll");
            assertEquals(0, scroll.getViewport().getViewPosition().y);
            for (String name : new String[] {"batchOpenSelected", "batchOpenNext", "batchSectionPreview"}) {
                final var component = find(panel, name);
                final var bounds = SwingUtilities.convertRectangle(component.getParent(),
                        component.getBounds(), scroll.getViewport().getView());
                assertTrue(scroll.getViewport().getViewRect().contains(bounds), name);
            }
            scroll.getViewport().setViewPosition(new java.awt.Point(0, 100));
            ((JTextField) find(panel, "batchSectionName")).setText("Renamed preview");
            ((JButton) find(panel, "batchSaveSectionSettings")).doClick();
        });
        SwingUtilities.invokeAndWait(() -> {
            final var panel = holder.get();
            assertNotEquals(0, ((JScrollPane) find(panel, "batchInspectorScroll"))
                    .getViewport().getViewPosition().y);
            final var preview = (JPanel) find(panel, "batchSectionPreview");
            assertTrue(preview.getToolTipText().startsWith("Renamed preview"));
            assertTrue(preview.getAccessibleContext().getAccessibleName().contains("Renamed preview"));
        });
    }

    @Test
    void savesTypedLevelAlongsideNameWithoutRefreshOverwritingIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final var panel = panel();
            ((JTextField) find(panel, "batchSectionName")).setText("Renamed section");
            final var level = (JSpinner) find(panel, "batchSectionCoronalLevel");
            ((JSpinner.DefaultEditor) level.getEditor()).getTextField().setText("321");
            ((JButton) find(panel, "batchSaveSectionSettings")).doClick();
            assertEquals("Renamed section", ((JTextField) find(panel, "batchSectionName")).getText());
            assertEquals(321, level.getValue());
        });
    }

    private WholeSlideBatchPanel panel() {
        final ImagePlus image = new ImagePlus("slide-with-a-long-readable-source-name.tif",
                new ByteProcessor(100, 100));
        final var markers = new ArrayList<Roi>();
        for (int i = 0; i < 9; i++) {
            final var roi = new Roi((i % 3) * 30, (i / 3) * 30, 20, 20);
            roi.setName("Section " + (i + 1));
            markers.add(roi);
        }
        final var project = new BatchProjectSession("test", directory,
                new BatchInputFactory().markedSections(image, markers, 264, 0));
        final var settings = new BatchLaunchSettings(1, 2048, false,
                directory.toFile(), directory.toFile(), directory.toFile());
        final var commands = (CommandService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {CommandService.class},
                (proxy, method, arguments) -> null);
        return new WholeSlideBatchPanel(project, settings, commands);
    }

    private static Component find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) {
                final Component result = find(container, name);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void layout(Container root) {
        root.doLayout();
        for (Component child : root.getComponents()) {
            if (child instanceof Container container) layout(container);
        }
    }
}
