package org.atlasalign.plugin.review;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.atlasalign.application.AcceptedAlignmentSnapshot;
import org.atlasalign.core.CalibrationFieldStatus;
import org.atlasalign.plugin.export.ExportCancelledException;
import org.atlasalign.plugin.export.ExportRegionSelection;
import org.atlasalign.plugin.export.SourceSpaceExportOptions;
import org.atlasalign.plugin.export.SourceSpaceExportService;

/** Compact Phase 6 inspector shown after explicit alignment acceptance. */
final class SourceSpaceExportPanel extends JPanel {

    private record RegionChoice(
            SelectedAtlasRegion region,
            JCheckBox includeDescendants) {
    }

    private final ReviewController controller;
    private final SourceSpaceExportService exporter;
    private final String sourceName;
    private final Runnable backHandler;
    private final JTextField search = new JTextField(12);
    private final JButton searchButton = new JButton("Search");
    private final DefaultListModel<SelectedAtlasRegion> searchModel =
            new DefaultListModel<>();
    private final JList<SelectedAtlasRegion> searchResults =
            new JList<>(searchModel);
    private final JButton addSelection = new JButton("Add region");
    private final JPanel chosenRegions = new JPanel();
    private final List<RegionChoice> choices = new ArrayList<>();
    private final JCheckBox combined = new JCheckBox("Combined union");
    private final JCheckBox fullSourceMask = new JCheckBox("Full-size mask");
    private final JCheckBox maskedSourceCrop = new JCheckBox("Masked image");
    private final JLabel folder = new JLabel("No output folder selected");
    private final JButton chooseFolder = new JButton("Folder…");
    private final JButton export = new JButton("Export");
    private final JButton cancel = new JButton("Cancel");
    private final JButton back = new JButton("Back");
    private final JLabel calibrationWarning = new JLabel(" ");
    private final JLabel status = new JLabel("Select one or more regions");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            runnable -> {
                final Thread thread = new Thread(
                        runnable, "atlasalign-source-space-export");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private AcceptedAlignmentSnapshot accepted;
    private Path selectedFolder;
    private boolean running;
    private boolean displayGeometryBlocked;
    private ExportSelectionPanel exportSelectionPanel;

    SourceSpaceExportPanel(
            final ReviewController controller,
            final SourceSpaceExportService exporter,
            final String sourceName,
            final Runnable backHandler) {
        super(new BorderLayout(0, 6));
        this.controller = Objects.requireNonNull(controller, "controller");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.backHandler = Objects.requireNonNull(backHandler, "backHandler");
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        add(header(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        add(actions(), BorderLayout.SOUTH);
        wireActions();
        setRunningState(false);
    }

    void setDisplayGeometryBlocked(final boolean blocked) {
        displayGeometryBlocked = blocked;
        setRunningState(running);
    }

    private boolean exportReady() {
        return !displayGeometryBlocked && !running && !choices.isEmpty()
                && selectedFolder != null && accepted != null;
    }

    void setAcceptedAlignment(
            final AcceptedAlignmentSnapshot snapshot) {
        accepted = Objects.requireNonNull(snapshot, "snapshot");
        final var calibration = snapshot.verifiedSource()
                .metadata().calibration();
        final boolean caution = calibration.pixelWidthStatus()
                != CalibrationFieldStatus.VALID
                || calibration.pixelHeightStatus()
                != CalibrationFieldStatus.VALID
                || calibration.spatialUnitStatus()
                != CalibrationFieldStatus.VALID;
        final String calibrationMessage = caution
                ? "Calibration undefined; no values guessed."
                : "Source calibration will be copied to OME metadata.";
        calibrationWarning.setText(calibrationMessage);
        calibrationWarning.setToolTipText(caution
                ? "Undefined or invalid calibration fields will be omitted from OME metadata and recorded exactly in the export manifest."
                : calibrationMessage);
        status("Accepted revision " + snapshot.contentRevision()
                + " — choose Allen regions to export", false);
        if (choices.isEmpty()) {
            search.setText("DG");
            runSearch();
        }
        setRunningState(running);
    }

    void acceptanceInvalidated() {
        cancelExport();
        accepted = null;
        setRunningState(running);
        backHandler.run();
    }

    void close() {
        cancelled.set(true);
        worker.shutdownNow();
    }

    boolean exportRunning() {
        return running;
    }

    private JPanel header() {
        final JPanel panel = verticalPanel();
        final JLabel title = new JLabel("Export atlas regions");
        title.setFont(title.getFont().deriveFont(
                java.awt.Font.BOLD, title.getFont().getSize2D() + 1));
        panel.add(title);
        final JLabel note = new JLabel(
                "Raw crop unchanged; masks define the irregular ROI");
        note.setToolTipText(
                "The canonical crop keeps every source value in its tight rectangle. The binary mask and Fiji ROI identify the irregular region; optional masked images zero only pixels outside that region.");
        panel.add(note);
        panel.add(calibrationWarning);
        exportSelectionPanel = new ExportSelectionPanel(controller.state().basis().sourceSnapshot().metadata(),
                controller.exportSelection());
        panel.add(exportSelectionPanel);
        return panel;
    }

    private Component content() {
        final JPanel panel = new ViewportWidthPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        final JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        search.setName("exportOntologySearch");
        searchButton.setName("exportOntologySearchButton");
        searchRow.add(search, BorderLayout.CENTER);
        searchRow.add(searchButton, BorderLayout.EAST);
        panel.add(searchRow);
        searchResults.setVisibleRowCount(5);
        searchResults.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(
                    final JList<?> list,
                    final Object value,
                    final int index,
                    final boolean selected,
                    final boolean focused) {
                super.getListCellRendererComponent(
                        list, value, index, selected, focused);
                if (value instanceof SelectedAtlasRegion region) {
                    setText(region.displayName());
                }
                return this;
            }
        });
        final JScrollPane searchScroll = new JScrollPane(searchResults);
        searchScroll.setPreferredSize(new Dimension(250, 92));
        panel.add(searchScroll);
        panel.add(addSelection);
        chosenRegions.setLayout(new BoxLayout(
                chosenRegions, BoxLayout.Y_AXIS));
        final JScrollPane selectedScroll = new JScrollPane(chosenRegions);
        selectedScroll.setBorder(BorderFactory.createTitledBorder(
                "Selected regions"));
        selectedScroll.setPreferredSize(new Dimension(250, 150));
        selectedScroll.setName("exportSelectedRegions");
        panel.add(selectedScroll);
        combined.setName("exportCombinedUnion");
        panel.add(combined);
        fullSourceMask.setName("exportFullSourceMask");
        fullSourceMask.setToolTipText(
                "Also write a two-dimensional binary mask at the complete source width and height for direct overlay in Fiji or Imaris");
        panel.add(fullSourceMask);
        maskedSourceCrop.setName("exportMaskedSourceCrop");
        maskedSourceCrop.setToolTipText(
                "Also write a derived C/Z/T-preserving crop with exact source values inside the region and numeric zero outside");
        panel.add(maskedSourceCrop);
        final JPanel folderRow = new JPanel(new BorderLayout(4, 0));
        folder.setToolTipText("No output folder selected");
        folderRow.add(folder, BorderLayout.CENTER);
        folderRow.add(chooseFolder, BorderLayout.EAST);
        panel.add(folderRow);
        final JScrollPane contentScroll = new JScrollPane(panel);
        contentScroll.setName("exportContentScroll");
        contentScroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        return contentScroll;
    }

    private JPanel actions() {
        final JPanel panel = verticalPanel();
        progress.setStringPainted(true);
        progress.setValue(0);
        panel.add(progress);
        status.setToolTipText(status.getText());
        panel.add(status);
        final JPanel buttons = new JPanel(new FlowLayout(
                FlowLayout.LEFT, 4, 2));
        buttons.setName("exportActionButtons");
        back.setName("exportBackToAlignment");
        export.setName("exportSourceCrops");
        cancel.setName("cancelSourceExport");
        chooseFolder.setName("exportChooseFolder");
        buttons.add(back);
        buttons.add(export);
        buttons.add(cancel);
        panel.add(buttons);
        return panel;
    }

    private void wireActions() {
        search.addActionListener(this::searchRequested);
        searchButton.addActionListener(this::searchRequested);
        addSelection.addActionListener(event -> addSelectedRegion());
        chooseFolder.addActionListener(event -> chooseFolder());
        back.addActionListener(event -> {
            if (!running) {
                backHandler.run();
            }
        });
        export.addActionListener(event -> startExport());
        cancel.addActionListener(event -> cancelExport());
    }

    private void searchRequested(final ActionEvent ignored) {
        runSearch();
    }

    private void runSearch() {
        final String query = search.getText().trim();
        if (query.isEmpty()) {
            status("Enter an Allen acronym or region name", true);
            return;
        }
        try {
            final List<SelectedAtlasRegion> results =
                    controller.searchAtlasRegions(query, 40);
            searchModel.clear();
            results.forEach(searchModel::addElement);
            if (!results.isEmpty()) {
                searchResults.setSelectedIndex(0);
            }
            status(results.isEmpty()
                    ? "No verified Allen regions matched that search"
                    : results.size() + " matching region"
                            + (results.size() == 1 ? "" : "s"),
                    results.isEmpty());
        } catch (final RuntimeException error) {
            status(messageOf(error), true);
        }
    }

    private void addSelectedRegion() {
        final SelectedAtlasRegion region = searchResults.getSelectedValue();
        if (region == null) {
            status("Choose one search result first", true);
            return;
        }
        if (choices.stream().anyMatch(choice -> choice.region()
                .rootRegionId() == region.rootRegionId())) {
            status(region.acronym() + " is already selected", true);
            return;
        }
        final JCheckBox descendants = new JCheckBox(
                "Include descendants", true);
        descendants.setToolTipText(
                "Include every verified child structure under this Allen ontology node");
        choices.add(new RegionChoice(region, descendants));
        rebuildChoices();
        status("Added " + region.displayName(), false);
    }

    private void rebuildChoices() {
        chosenRegions.removeAll();
        for (final RegionChoice choice : List.copyOf(choices)) {
            final JPanel row = new JPanel(new BorderLayout(3, 0));
            final JLabel name = new JLabel(choice.region().displayName());
            name.setToolTipText(choice.region().displayName());
            row.add(name, BorderLayout.NORTH);
            final JPanel options = new JPanel(new FlowLayout(
                    FlowLayout.LEFT, 2, 0));
            options.add(choice.includeDescendants());
            final JButton remove = new JButton("Remove");
            remove.addActionListener(event -> {
                choices.remove(choice);
                rebuildChoices();
            });
            options.add(remove);
            row.add(options, BorderLayout.SOUTH);
            chosenRegions.add(row);
        }
        chosenRegions.revalidate();
        chosenRegions.repaint();
        setRunningState(running);
    }

    private void chooseFolder() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        final JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose AtlasAlign export folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (selectedFolder != null) {
            chooser.setCurrentDirectory(selectedFolder.toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            setDestination(chooser.getSelectedFile().toPath());
        }
    }

    void setDestination(final Path destination) {
        selectedFolder = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        final String display = selectedFolder.getFileName() == null
                ? selectedFolder.toString() : selectedFolder.getFileName().toString();
        folder.setText(ellipsize(display, 20));
        folder.setToolTipText(selectedFolder.toString());
        rebuildChoices();
    }

    void startExport() {
        if (displayGeometryBlocked || running) return;
        if (!exportReady()) {
            status("Choose regions and an output folder first", true);
            return;
        }
        final AcceptedAlignmentSnapshot snapshot = accepted;
        final List<ExportRegionSelection> selections = choices.stream()
                .map(choice -> ExportRegionSelection.from(
                        choice.region(),
                        choice.includeDescendants().isSelected()))
                .toList();
        final SourceSpaceExportOptions options =
                new SourceSpaceExportOptions(
                        combined.isSelected(),
                        fullSourceMask.isSelected(),
                        maskedSourceCrop.isSelected());
        final org.atlasalign.application.export.ExportSelection selection;
        try {
            selection = exportSelectionPanel.selection();
            controller.setExportSelection(selection);
        } catch (IllegalArgumentException invalid) {
            status(invalid.getMessage(), true);
            return;
        }
        final Path destination = selectedFolder;
        if (!GraphicsEnvironment.isHeadless()) {
            final JPanel summary = new JPanel(new java.awt.GridLayout(0, 1, 0, 5));
            summary.add(new JLabel("Regions: " + selections.stream().map(ExportRegionSelection::displayName).collect(java.util.stream.Collectors.joining(", "))));
            summary.add(new JLabel("Channels: " + selection.channels() + " · pinned Z " + selection.slice() + " · T " + selection.frame()));
            summary.add(new JLabel("Destination: " + destination));
            final var plane = controller.exportPreviewPlane();
            if (!ExportPreviewDialog.confirm(this, summary, () -> ExportPreviewDialog.atlas(controller.registrationPreview(),
                    controller.registrationInput(), snapshot, plane, selections))) return;
        }
        if (!exportReady() || accepted != snapshot) return;
        final var exportContext = controller.captureExportContext();
        cancelled.set(false);
        setExportRunning(true);
        worker.execute(() -> {
            try {
                final SourceSpaceExportService.Result result = exporter.export(
                        destination, sourceName, snapshot, selections,
                        options, selection,
                        cancelled::get,
                        (stage, fraction) -> SwingUtilities.invokeLater(() -> {
                            progress.setValue((int) Math.round(
                                    100 * Math.max(0, Math.min(1, fraction))));
                            status(stage, false);
                        }));
                SwingUtilities.invokeLater(() -> exportFinished(result, exportContext));
            } catch (final RuntimeException error) {
                SwingUtilities.invokeLater(() -> exportFailed(error));
            }
        });
    }

    private void exportFinished(
            final SourceSpaceExportService.Result result, final ReviewController.ExportContext exportContext) {
        setExportRunning(false);
        progress.setValue(100);
        status("Exported to " + result.publishedDirectory(), false);
        controller.recordCompletedExport(result.publishedDirectory(), exportContext);
        status.setToolTipText(result.publishedDirectory().toString());
        if (!GraphicsEnvironment.isHeadless()) {
            final String warning = result.warnings().isEmpty() ? ""
                    : "\n\nCalibration notes:\n- "
                            + String.join("\n- ", result.warnings());
            JOptionPane.showMessageDialog(this,
                    "Source-space export completed.\n\n"
                            + result.publishedDirectory() + warning,
                    "AtlasAlign export complete",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void exportFailed(final RuntimeException error) {
        setExportRunning(false);
        final boolean wasCancelled = error instanceof ExportCancelledException
                || cancelled.get();
        status(wasCancelled
                ? "Export cancelled; no files were published"
                : messageOf(error), !wasCancelled);
        if (!wasCancelled && !GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(this,
                    messageOf(error) + "\n\nNo export directory was published.",
                    "AtlasAlign export failed",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void cancelExport() {
        if (running) {
            cancelled.set(true);
            status("Cancelling safely…", false);
        }
    }

    private void setRunningState(final boolean active) {
        final boolean editable = !active && !displayGeometryBlocked;
        search.setEnabled(editable);
        searchButton.setEnabled(editable);
        searchResults.setEnabled(editable);
        addSelection.setEnabled(editable);
        chooseFolder.setEnabled(editable);
        combined.setEnabled(editable);
        fullSourceMask.setEnabled(editable);
        maskedSourceCrop.setEnabled(editable);
        setEnabledRecursively(chosenRegions, editable);
        setEnabledRecursively(exportSelectionPanel, editable);
        export.setEnabled(exportReady());
        cancel.setEnabled(active);
        back.setEnabled(editable);
    }

    private void setExportRunning(final boolean active) {
        final boolean previous = running;
        running = active;
        setRunningState(active);
        firePropertyChange("exportRunning", previous, active);
    }

    private static void setEnabledRecursively(
            final Component component,
            final boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container container) {
            for (final Component child : container.getComponents()) {
                setEnabledRecursively(child, enabled);
            }
        }
    }

    private void status(final String message, final boolean error) {
        final String text = Objects.requireNonNullElse(message, "");
        status.setText(ellipsize(text, 58));
        status.setToolTipText(text);
        status.setForeground(error ? new Color(155, 45, 30)
                : new Color(45, 65, 75));
    }

    private static JPanel verticalPanel() {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    /** Box-layout panel whose inspector content always fits the viewport. */
    private static final class ViewportWidthPanel extends JPanel
            implements Scrollable {

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(
                final Rectangle visibleRect,
                final int orientation,
                final int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(
                final Rectangle visibleRect,
                final int orientation,
                final int direction) {
            return Math.max(16, visibleRect.height - 16);
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

    private static String messageOf(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private static String ellipsize(
            final String value,
            final int maximumCharacters) {
        if (value.length() <= maximumCharacters) {
            return value;
        }
        return value.substring(0, Math.max(1, maximumCharacters - 1))
                + "…";
    }
}
