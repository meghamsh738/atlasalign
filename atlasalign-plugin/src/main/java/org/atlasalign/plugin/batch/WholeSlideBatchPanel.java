package org.atlasalign.plugin.batch;

import ij.ImagePlus;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.Frame;
import java.awt.Window;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.JSplitPane;
import javax.swing.Scrollable;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.atlasalign.plugin.ReviewAlignmentCommand;
import org.scijava.command.CommandService;

/** Queue UI for independent reviews of marked sections or open images. */
final class WholeSlideBatchPanel extends JPanel {

    private final BatchProjectSession project;
    private final BatchLaunchSettings settings;
    private final CommandService commands;
    private final DefaultListModel<BatchReviewItem> listModel =
            new DefaultListModel<>();
    private final JList<BatchReviewItem> queue = new JList<>(listModel);
    private final JTextField sectionName = new JTextField(20);
    private final JSpinner coronalLevel = new JSpinner(
            new SpinnerNumberModel(264, 0, 527, 1));
    private final JTextArea source = text("");
    private final JTextArea bounds = text("");
    private final JTextArea sectionProgress = text("");
    private final JTextArea status = text("Ready");
    private final JLabel queueHeader = new JLabel();
    private final JButton open = new JButton("Open selected review");
    private final JButton openNext = new JButton("Open next pending");
    private final JButton complete = new JButton("Mark complete");
    private final JButton batchExport = new JButton(
            "Export all saved ROIs…");
    private final Map<String, ImagePlus> openSectionImages = new HashMap<>();
    private final SectionPreviewPanel preview = new SectionPreviewPanel();
    private final WholeSlideOverviewPanel overview;
    private JScrollPane inspectorScroll;
    private String inspectedSectionId;
    private boolean updating;

    WholeSlideBatchPanel(
            final BatchProjectSession project,
            final BatchLaunchSettings settings,
            final CommandService commands) {
        super(new BorderLayout(8, 8));
        this.project = Objects.requireNonNull(project, "project");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.commands = Objects.requireNonNull(commands, "commands");
        overview = new WholeSlideOverviewPanel(project::select);
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        buildUi();
        project.addListener(this::projectChanged);
        refresh();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        refresh();
    }

    private void buildUi() {
        final JPanel header = new JPanel(new BorderLayout(0, 4));
        queueHeader.setName("batchQueueSummary");
        queueHeader.setFont(queueHeader.getFont().deriveFont(Font.BOLD));
        header.add(queueHeader, BorderLayout.NORTH);
        final JTextArea intro = text(
                "Select a section below, then open its review. Draw final named ROIs in step 3. "
                        + "Exports retain section and whole-slide coordinates; source pixels stay unchanged.");
        intro.setRows(2);
        header.add(intro, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);

        queue.setName("batchSectionQueue");
        queue.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        queue.setVisibleRowCount(12);
        queue.setCellRenderer(new QueueRenderer());
        queue.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updating
                    && queue.getSelectedIndex() >= 0) {
                project.select(queue.getSelectedIndex());
            }
        });
        final JScrollPane scroll = new JScrollPane(queue);
        scroll.setPreferredSize(new Dimension(460, 420));
        scroll.setMinimumSize(new Dimension(220, 180));

        final JPanel inspector = new InspectorPanel();
        inspector.setName("batchSectionInspector");
        inspector.setBorder(BorderFactory.createTitledBorder(
                "Selected section"));
        addInspectorRow(inspector, open);
        addInspectorRow(inspector, openNext);
        addInspectorRow(inspector, preview);
        addInspectorRow(inspector, new JLabel("Name"));
        sectionName.setName("batchSectionName");
        addInspectorRow(inspector, sectionName);
        addInspectorRow(inspector, new JLabel("Starting Allen AP level (0–527)"));
        coronalLevel.setName("batchSectionCoronalLevel");
        addInspectorRow(inspector, coronalLevel);
        source.setRows(2);
        bounds.setRows(2);
        status.setRows(2);
        status.setName("batchQueueStatus");
        addInspectorRow(inspector, source);
        addInspectorRow(inspector, bounds);
        sectionProgress.setName("batchSectionProgress");
        sectionProgress.setRows(5);
        addInspectorRow(inspector, sectionProgress);
        addInspectorRow(inspector, text(
                "The starting level is an initial suggestion. Use Left/Right "
                        + "in the review to change the atlas plane."));

        final JButton rename = new JButton("Save name / AP level");
        rename.setName("batchSaveSectionSettings");
        rename.addActionListener(event -> saveSelectedSettings());
        addInspectorRow(inspector, rename);

        final JButton up = new JButton("Move up");
        final JButton down = new JButton("Move down");
        up.addActionListener(event -> project.moveSelected(-1));
        down.addActionListener(event -> project.moveSelected(1));
        addInspectorRow(inspector, row(up, down));

        open.setName("batchOpenSelected");
        openNext.setName("batchOpenNext");
        complete.setName("batchMarkComplete");
        batchExport.setName("batchExportAllManualRois");
        open.addActionListener(event -> openSelected());
        openNext.addActionListener(event -> project.nextPendingIndex()
                .ifPresentOrElse(index -> {
                    project.select(index);
                    openSelected();
                }, () -> status.setText(
                        "No Pending or Needs-attention items remain")));
        complete.addActionListener(event -> {
            final BatchSection section = project.selected().section();
            project.setStatus(section.id(), BatchReviewStatus.COMPLETE,
                    "Marked complete by reviewer");
        });
        batchExport.addActionListener(event -> exportAllManualRois());
        addInspectorRow(inspector, complete);
        addInspectorRow(inspector, batchExport);
        addInspectorRow(inspector, text("Project autosave: " + project.projectFile()));
        add(org.atlasalign.plugin.ui.PluginBranding.withCredit(status), BorderLayout.SOUTH);
        final GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 0;
        filler.gridy = GridBagConstraints.RELATIVE;
        filler.weighty = 1;
        filler.weightx = 1;
        filler.fill = GridBagConstraints.BOTH;
        inspector.add(new JPanel(), filler);
        inspectorScroll = new JScrollPane(inspector,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        inspectorScroll.setName("batchInspectorScroll");
        inspectorScroll.setPreferredSize(new Dimension(350, 420));
        inspectorScroll.setMinimumSize(new Dimension(310, 180));
        inspectorScroll.getVerticalScrollBar().setUnitIncrement(16);
        final JSplitPane sourceSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                scroll, overview);
        sourceSplit.setName("batchSourceSplit");
        sourceSplit.setResizeWeight(0.4);
        sourceSplit.setContinuousLayout(true);
        sourceSplit.setBorder(null);
        scroll.setPreferredSize(new Dimension(460, 170));
        scroll.setMinimumSize(new Dimension(180, 80));
        final JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                sourceSplit, inspectorScroll);
        split.setName("batchQueueSplit");
        split.setResizeWeight(0.60);
        split.setContinuousLayout(true);
        split.setBorder(null);
        add(split, BorderLayout.CENTER);
        setPreferredSize(new Dimension(940, 620));
    }

    private boolean saveSelectedSettings() {
        try {
            coronalLevel.commitEdit();
            final String name = sectionName.getText();
            final int level = ((Number) coronalLevel.getValue()).intValue();
            project.renameSelected(name);
            project.setSelectedCoronalLevel(level);
            status.setText("Section settings saved");
            return true;
        } catch (final RuntimeException | java.text.ParseException error) {
            showError("Section settings not saved", rootMessage(error));
            return false;
        }
    }

    private void openSelected() {
        if (!saveSelectedSettings()) {
            return;
        }
        final BatchReviewItem item = project.selected();
        final BatchSection section = item.section();
        if (section.status() == BatchReviewStatus.OPENING) {
            return;
        }
        final ImagePlus existingImage = openSectionImages.get(section.id());
        final javax.swing.JFrame existing = existingImage == null ? null
                : BatchReviewNavigation.existingWindow(existingImage);
        if (existing != null) {
            raiseWindow(existing);
            status.setText(section.name() + " review is already open; returned to that window");
            return;
        }
        final org.atlasalign.application.RegistrationInput input;
        try {
            if (project.checkpoint(item).isPresent()) {
                // The verified checkpoint owns C/Z/T and all geometry. It must never enter new-review intake.
                input = null;
            } else {
                final Path draftPath = BatchManualRoiExporter.draftFile(project, section);
                final var recorded = org.atlasalign.plugin.review.ManualRoiSessionStore.recordedInput(draftPath);
                if (recorded.isPresent()) {
                    input = recorded.orElseThrow();
                } else {
                    final var metadata = item.verifiedSource().metadata();
                    final JSpinner registrationChannel = new JSpinner(new SpinnerNumberModel(
                            Math.min(settings.registrationChannel(), metadata.channels()), 1, metadata.channels(), 1));
                    final JSpinner z = new JSpinner(new SpinnerNumberModel(1, 1, metadata.slices(), 1));
                    final JSpinner t = new JSpinner(new SpinnerNumberModel(1, 1, metadata.frames(), 1));
                    final JPanel scope = new JPanel(new java.awt.GridLayout(0, 2, 6, 6));
                    scope.add(new JLabel("Registration channel")); scope.add(registrationChannel);
                    scope.add(new JLabel("Optical Z")); scope.add(z);
                    scope.add(new JLabel("Time point")); scope.add(t);
                    scope.add(new JLabel("Export defaults to all channels at this Z/T."));
                    if (JOptionPane.showConfirmDialog(this, scope, "Pin image scope for " + section.name(),
                            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
                    registrationChannel.commitEdit(); z.commitEdit(); t.commitEdit();
                    input = new org.atlasalign.application.RegistrationInput((Integer) registrationChannel.getValue(),
                            (Integer) z.getValue(), (Integer) t.getValue());
                }
                input.validateAgainst(item.verifiedSource().metadata());
            }
        } catch (RuntimeException | java.text.ParseException invalid) {
            JOptionPane.showMessageDialog(this, invalid.getMessage(), "Check image scope", JOptionPane.ERROR_MESSAGE);
            return;
        }
        project.setStatus(section.id(), BatchReviewStatus.OPENING,
                "Preparing an independent source-preserving section crop");
        setOpening(true);
        status.setText("Preparing " + section.name() + "…");
        new SwingWorker<ImagePlus, Void>() {
            @Override
            protected ImagePlus doInBackground() throws Exception {
                final ImagePlus sectionImage = new SectionImageExtractor()
                        .extract(item);
                final var opening = BatchReviewNavigation.register(sectionImage,
                        () -> returnToSection(section.id()), project, section.id());
                try {
                    new BatchSectionReviewLauncher().open(project, item, sectionImage,
                            settings.atlasCacheDirectory().toPath(), image -> commands.run(ReviewAlignmentCommand.class, true,
                        "sourceImage", sectionImage,
                        "registrationChannel", input.channel(),
                        "registrationSlice", input.slice(),
                        "registrationFrame", input.frame(),
                        "maximumPreviewDimension",
                        settings.maximumPreviewDimension(),
                        "initialCoronalLevel",
                        section.initialCoronalLevel(),
                        "useLocalDeepSlice",
                        settings.useLocalDeepSlice(),
                        "deepSliceRuntimeDirectory",
                        settings.deepSliceRuntimeDirectory(),
                        "deepSliceWorkDirectory",
                        settings.deepSliceWorkDirectory(),
                        "atlasCacheDirectory",
                        settings.atlasCacheDirectory(),
                        "reviewSectionId", section.id(),
                        "parentSourceName", section.sourceName(),
                        "parentSourcePixelSha256",
                        section.sourcePixelSha256(),
                        "parentSourceWidth", section.sourceWidth(),
                        "parentSourceHeight", section.sourceHeight(),
                        "sectionSourceOffsetX", section.minimumX(),
                        "sectionSourceOffsetY", section.minimumY(),
                        "manualRoiDraftPath", project.projectDirectory()
                                .resolve("sections").resolve(section.id())
                                .resolve("manual-rois.json").toString()).get());
                    opening.get();
                } catch (final Exception error) {
                    BatchReviewNavigation.unregister(sectionImage);
                    throw error;
                }
                return sectionImage;
            }

            @Override
            protected void done() {
                setOpening(false);
                try {
                    final ImagePlus sectionImage = get();
                    openSectionImages.put(section.id(), sectionImage);
                    project.setStatus(section.id(),
                            BatchReviewStatus.REVIEW_OPEN,
                            "Independent review window opened");
                    status.setText(section.name()
                            + " review is open; continue in that window");
                } catch (final InterruptedException error) {
                    Thread.currentThread().interrupt();
                    failed(section, "Opening was interrupted");
                } catch (final ExecutionException error) {
                    failed(section, rootMessage(error));
                }
            }
        }.execute();
    }

    private void returnToSection(final String sectionId) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> returnToSection(sectionId));
            return;
        }
        final var items = project.items();
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).section().id().equals(sectionId)) {
                project.select(index);
                break;
            }
        }
        final Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            raiseWindow(window);
        }
    }

    private static void raiseWindow(final Window window) {
        if (window instanceof Frame frame) {
            frame.setExtendedState(frame.getExtendedState() & ~Frame.ICONIFIED);
        }
        window.setVisible(true);
        window.toFront();
        window.requestFocus();
    }

    private void failed(
            final BatchSection section,
            final String message) {
        project.setStatus(section.id(), BatchReviewStatus.ERROR, message);
        status.setText("Could not open " + section.name());
        showError("Section review did not open", message);
    }

    private void exportAllManualRois() {
        final JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(
                "Choose folder for all batch manual ROI exports");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        final Path folder = chooser.getSelectedFile().toPath();
        setOpening(true);
        batchExport.setEnabled(false);
        status.setText("Publishing all autosaved exact manual ROIs…");
        new SwingWorker<BatchManualRoiExporter.Result, String>() {
            @Override
            protected BatchManualRoiExporter.Result doInBackground() {
                return new BatchManualRoiExporter().export(folder, project,
                        settings.registrationChannel(), this::isCancelled,
                        (message, fraction) -> publish(message + " ("
                                + Math.round(fraction * 100) + "%)"));
            }

            @Override
            protected void process(final java.util.List<String> chunks) {
                status.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                setOpening(false);
                batchExport.setEnabled(true);
                try {
                    final var result = get();
                    status.setText("Exported " + result.exportedRois()
                            + " ROI(s) from " + result.exportedSections()
                            + " section(s)");
                    JOptionPane.showMessageDialog(WholeSlideBatchPanel.this,
                            "Batch manual ROI export complete:\n"
                                    + result.publishedDirectory()
                                    + (result.skippedSections().isEmpty()
                                    ? "" : "\n\nSkipped:\n"
                                    + String.join("\n",
                                            result.skippedSections())),
                            "AtlasAlign batch export complete",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (final InterruptedException error) {
                    Thread.currentThread().interrupt();
                    showError("Batch export interrupted",
                            "No incomplete batch folder was published");
                } catch (final ExecutionException error) {
                    showError("Batch export failed", rootMessage(error));
                }
            }
        }.execute();
    }

    private void setOpening(final boolean value) {
        open.setEnabled(!value);
        openNext.setEnabled(!value);
        complete.setEnabled(!value);
        batchExport.setEnabled(!value);
    }

    private void projectChanged() {
        if (SwingUtilities.isEventDispatchThread()) {
            refresh();
        } else {
            SwingUtilities.invokeLater(this::refresh);
        }
    }

    private void refresh() {
        updating = true;
        try {
            listModel.clear();
            project.items().forEach(listModel::addElement);
            queue.setSelectedIndex(project.selectedIndex());
            final BatchSection selected = project.selected().section();
            final boolean changedSection = !selected.id().equals(inspectedSectionId);
            inspectedSectionId = selected.id();
            preview.showSection(project.selected(), settings.registrationChannel());
            overview.showSections(project.items(), project.selectedIndex(), settings.registrationChannel());
            final int count = project.items().size();
            queueHeader.setText(count + (count == 1 ? " section queued" : " sections queued")
                    + " • selected " + (project.selectedIndex() + 1) + " of " + count);
            sectionName.setText(selected.name());
            coronalLevel.setValue(selected.initialCoronalLevel());
            source.setText("Source: " + selected.sourceName());
            bounds.setText("Source bounds: x=" + selected.minimumX()
                    + ", y=" + selected.minimumY() + ", "
                    + selected.width() + "×" + selected.height());
            final var progress = project.selected().progress();
            sectionProgress.setText(progress.summary().replace(" · ", "\n")
                    + project.checkpoint(project.selected()).map(path -> "\nCheckpoint: " + path).orElse("")
                    + (selected.status() == BatchReviewStatus.ERROR ? "\n" + selected.statusDetail() : ""));
            open.setText(progress.checkpointPath() == null ? "Open selected review" : "Resume saved review");
            coronalLevel.setEnabled(progress.checkpointPath() == null);
            if (changedSection) {
                SwingUtilities.invokeLater(() -> {
                    if (selected.id().equals(inspectedSectionId)) {
                        inspectorScroll.getViewport().setViewPosition(new java.awt.Point(0, 0));
                    }
                });
            }
        } finally {
            updating = false;
        }
    }

    private void showError(final String title, final String message) {
        JOptionPane.showMessageDialog(this, message, title,
                JOptionPane.ERROR_MESSAGE);
    }

    private static String rootMessage(final Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    private static void addInspectorRow(final JPanel panel, final Component component) {
        final GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = GridBagConstraints.RELATIVE;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(2, 4, 2, 4);
        panel.add(component, constraints);
    }

    private static JPanel row(final Component... components) {
        final JPanel panel = new JPanel(new FlowLayout(
                FlowLayout.LEFT, 4, 2));
        for (final Component component : components) {
            panel.add(component);
        }
        return panel;
    }

    private static JTextArea text(final String value) {
        final JTextArea area = new JTextArea(value);
        area.setRows(2);
        area.setColumns(24);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setBorder(BorderFactory.createEmptyBorder(4, 2, 6, 2));
        return area;
    }

    private static final class InspectorPanel extends JPanel implements Scrollable {
        private InspectorPanel() {
            super(new GridBagLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(16, visible.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class QueueRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                final JList<?> list,
                final Object value,
                final int index,
                final boolean selected,
                final boolean focus) {
            final BatchReviewItem item = (BatchReviewItem) value;
            final BatchSection section = item.section();
            final Component rendered = super.getListCellRendererComponent(list,
                    (index + 1) + ". " + section.name() + "  • AP "
                            + section.initialCoronalLevel() + "  • "
                            + section.status().displayName() + "  • "
                            + (item.progress().saveState() == BatchSectionProgress.SaveState.SAVED ? "Saved"
                                    : item.progress().saveState() == BatchSectionProgress.SaveState.FAILED ? "Save failed"
                                    : "Unsaved")
                            + (item.progress().exportState() == BatchSectionProgress.ExportState.CURRENT ? " • Export current"
                                    : item.progress().exportState() == BatchSectionProgress.ExportState.STALE ? " • Export stale" : ""),
                    index, selected, focus);
            setToolTipText(item.progress().summary());
            return rendered;
        }
    }
}
