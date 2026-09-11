package org.atlasalign.plugin.project;

import java.awt.Component;
import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.*;
import org.atlasalign.application.DisplaySettings;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.plugin.OpenReviewProjectCommand;
import org.atlasalign.plugin.review.ReviewController;
import org.atlasalign.plugin.review.SwingReviewPanel;

/** Owns truthful save status, debounced checkpoints, and the dirty-close guard. */
public final class ReviewProjectCoordinator implements AutoCloseable {
    private final ReviewProjectContext context;
    private final ReviewController controller;
    private final ReviewerRoiSession rois;
    private final SwingReviewPanel panel;
    private final JFrame frame;
    private final ReviewProjectStore store = new ReviewProjectStore();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        final Thread thread = new Thread(task, "atlasalign-project-writer"); thread.setDaemon(true); return thread;
    });
    private final Timer debounce = new Timer(1000, event -> save(false, null));
    private final JLabel status = new JLabel("Not saved");
    private final JButton saveButton = new JButton("Save project");
    private final JButton saveAsButton = new JButton("Save as…");
    private Path projectFile;
    private Stamp savedStamp;
    private Stamp observedStamp;
    private boolean writing;
    private boolean closed;

    private record Stamp(long alignmentRevision, boolean accepted, long roiRevision,
            ExportSelection export, DisplaySettings display, ReviewUiState ui) { }

    public ReviewProjectCoordinator(final ReviewProjectContext context, final ReviewController controller,
            final ReviewerRoiSession rois, final SwingReviewPanel panel, final JFrame frame) {
        this.context = Objects.requireNonNull(context); this.controller = controller;
        this.rois = rois; this.panel = panel; this.frame = frame;
        projectFile = context.projectFile().orElse(null);
        context.restored().ifPresent(project -> panel.restoreProjectUi(project.ui()));
        observedStamp = stamp();
        savedStamp = context.restored().isPresent() ? observedStamp : null;
        if (projectFile != null && savedStamp != null) status.setText("Reopened · acceptance requires review");
        debounce.setRepeats(false);
        controller.addProjectChangeListener(this::changed);
        controller.addExportListener(event -> event.context().rois().ifPresent(snapshot ->
                org.atlasalign.plugin.batch.BatchReviewNavigation.exported(context.sourceImage(), event.directory(),
                        event.context().alignmentRevision(), snapshot, event.context().selection())));
        rois.addListener(this::changed);
        observeControls(panel);
        observeControls(panel.projectViewControls());
        panel.canvas().addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(final MouseEvent event) { changed(); }
        });
        panel.canvas().addMouseWheelListener((MouseWheelEvent event) -> changed());
        saveButton.addActionListener(event -> save(false, null));
        saveAsButton.addActionListener(event -> save(true, null));
        reportBatch(savedStamp == null ? "UNSAVED" : "SAVED");
    }

    public JComponent footer() {
        final JPanel footer = new JPanel(new java.awt.BorderLayout(0, 2));
        final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        final JButton open = new JButton("Open project…");
        open.addActionListener(event -> OpenReviewProjectCommand.chooseAndOpen(frame));
        actions.add(saveButton); actions.add(saveAsButton); actions.add(open);
        final JButton newInput = new JButton("New registration…");
        newInput.setToolTipText("Save this project, then start a separate manual review for a different C/Z/T input");
        newInput.setEnabled(context.parentSource().isEmpty());
        if (!newInput.isEnabled()) newInput.setToolTipText("For batch crops, create a new batch review to choose a different registration plane");
        newInput.addActionListener(event -> chooseNewRegistration()); actions.add(newInput);
        footer.add(actions, java.awt.BorderLayout.CENTER); footer.add(status, java.awt.BorderLayout.SOUTH);
        status.setBorder(BorderFactory.createEmptyBorder(0, 8, 3, 8));
        status.setName("reviewProjectSaveStatus");
        status.setToolTipText("After the first save, committed edits are checkpointed after one second.");
        return footer;
    }

    private void chooseNewRegistration() {
        final var metadata = controller.state().basis().sourceSnapshot().metadata();
        final var current = controller.registrationInput();
        final JSpinner channel = new JSpinner(new SpinnerNumberModel(current.channel(), 1, metadata.channels(), 1));
        final JSpinner slice = new JSpinner(new SpinnerNumberModel(current.slice(), 1, metadata.slices(), 1));
        final JSpinner time = new JSpinner(new SpinnerNumberModel(current.frame(), 1, metadata.frames(), 1));
        final JPanel fields = new JPanel(new java.awt.GridLayout(0, 2, 6, 6));
        fields.add(new JLabel("Registration channel")); fields.add(channel); fields.add(new JLabel("Optical Z")); fields.add(slice);
        fields.add(new JLabel("Time point")); fields.add(time);
        final JPanel body = new JPanel(new java.awt.BorderLayout(0, 10));
        body.add(new JLabel("<html>This project is saved first. A new manual review starts with fresh alignment and ROIs.<br>The current review stays open and keeps its original image scope.</html>"), java.awt.BorderLayout.NORTH);
        body.add(fields);
        if (JOptionPane.showConfirmDialog(frame, body, "Start a separate registration", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            channel.commitEdit(); slice.commitEdit(); time.commitEdit();
            final var input = new org.atlasalign.application.RegistrationInput((Integer) channel.getValue(), (Integer) slice.getValue(), (Integer) time.getValue());
            if (input.equals(current)) return;
            final int level = controller.state().content().coronalLevel().zeroBasedAnteriorPosteriorIndex();
            final var dimensions = controller.state().basis().previewDimensions();
            save(false, () -> org.atlasalign.plugin.ReviewAlignmentCommand.startNewManualReview(context.sourceImage(),
                    context.atlasDirectory(), input, level, Math.max(dimensions.width(), dimensions.height())));
        } catch (java.text.ParseException | IllegalArgumentException error) {
            JOptionPane.showMessageDialog(frame, error.getMessage(), "Check registration input", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void observeControls(final Component component) {
        if (component instanceof AbstractButton button) button.addActionListener(event -> changed());
        if (component instanceof JComboBox<?> combo) combo.addActionListener(event -> changed());
        if (component instanceof JSpinner spinner) spinner.addChangeListener(event -> changed());
        if (component instanceof JSlider slider) slider.addChangeListener(event -> { if (!slider.getValueIsAdjusting()) changed(); });
        if (component instanceof Container container) for (final Component child : container.getComponents()) observeControls(child);
    }

    private Stamp stamp() {
        return new Stamp(controller.state().contentRevision(), controller.acceptedAlignment().isPresent(),
                rois.snapshot().revision(), controller.exportSelection(), controller.displaySettings(), panel.captureProjectUi());
    }

    public void changed() {
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            final Stamp current = stamp();
            if (current.equals(observedStamp)) return;
            observedStamp = current;
            if (!current.equals(savedStamp)) {
                status.setText(projectFile == null ? "Not saved" : writing ? "Saving · newer edits pending" : "Unsaved changes");
                if (projectFile != null) debounce.restart();
                reportBatch(projectFile == null ? "UNSAVED" : "DIRTY");
            }
        });
    }

    private ReviewProject capture() {
        return new ReviewProject(context.sourceReference(), context.atlasDirectory().toAbsolutePath().normalize().toString(),
                controller.checkpoint(), controller.registrationInput(), controller.displaySettings(), controller.exportSelection(),
                rois.snapshot(), panel.captureProjectUi(), context.parentSource());
    }

    private Path chooseDestination() {
        final Object batchDefault = context.sourceImage().getProperty("AtlasAlign.review.defaultProjectPath");
        final Path suggestion = projectFile != null ? projectFile : batchDefault instanceof String path ? Path.of(path) : null;
        final JFileChooser chooser = new JFileChooser(suggestion == null ? null : suggestion.getParent().toFile());
        chooser.setDialogTitle("Save complete AtlasAlign review project");
        chooser.setSelectedFile(suggestion == null ? new java.io.File("review.atlasalign.json") : suggestion.toFile());
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return null;
        Path destination = chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        if (!destination.getFileName().toString().endsWith(".json")) destination = destination.resolveSibling(destination.getFileName() + ".atlasalign.json");
        if (Files.exists(destination) && !destination.equals(projectFile)
                && JOptionPane.showConfirmDialog(frame, "Replace this existing project?\n" + destination,
                        "Replace project", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return null;
        return destination;
    }

    public void save(final boolean saveAs, final Runnable afterSaved) {
        if (closed || writing) return;
        final Path destination = saveAs || projectFile == null ? chooseDestination() : projectFile;
        if (destination == null) return;
        final ReviewProject project;
        try { project = capture(); }
        catch (RuntimeException error) { showFailure(error); return; }
        final Stamp capturedStamp = stamp();
        debounce.stop(); writing = true; status.setText("Saving…");
        saveButton.setEnabled(false); saveAsButton.setEnabled(false);
        writer.execute(() -> {
            Exception failure = null;
            try { store.save(destination, project); } catch (Exception error) { failure = error; }
            final Exception result = failure;
            SwingUtilities.invokeLater(() -> {
                writing = false;
                if (closed) return;
                saveButton.setEnabled(true); saveAsButton.setEnabled(true);
                if (result != null) { showFailure(result); return; }
                projectFile = destination; savedStamp = capturedStamp;
                context.sourceImage().setProperty("AtlasAlign.review.savedPath", destination.toString());
                context.sourceImage().setProperty("AtlasAlign.review.savedRevision", project.alignment().contentRevision());
                final Stamp current = stamp(); observedStamp = current;
                status.setText(current.equals(savedStamp) ? "Saved · " + destination.getFileName() : "Unsaved changes");
                reportBatch(current.equals(savedStamp) ? "SAVED" : "DIRTY");
                if (!current.equals(savedStamp)) debounce.restart();
                else if (afterSaved != null) afterSaved.run();
            });
        });
    }

    private void showFailure(final Exception error) {
        status.setText("Save failed · previous checkpoint retained");
        status.setToolTipText(error.getMessage());
        reportBatch("FAILED");
        JOptionPane.showMessageDialog(frame, "The project could not be saved.\nThe previous checkpoint remains intact.\n\n"
                + error.getMessage(), "Save failed", JOptionPane.ERROR_MESSAGE);
    }

    private void reportBatch(final String saveState) {
        org.atlasalign.plugin.batch.BatchReviewNavigation.reviewProgress(context.sourceImage(), controller.state().contentRevision(),
                controller.acceptedAlignment().isPresent(), rois.snapshot(), controller.exportSelection(), projectFile, saveState);
    }

    public boolean hasUnsavedChanges() { return !stamp().equals(savedStamp); }
    public boolean isSaving() { return writing; }

    /** Called on the EDT before the controller closes or batch navigation disposes the window. */
    public void requestClose(final Runnable close) {
        if (writing) {
            JOptionPane.showMessageDialog(frame, "Wait for the project save to finish before closing.", "Saving project", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (!hasUnsavedChanges()) { close.run(); return; }
        final int decision = JOptionPane.showOptionDialog(frame, "This review has unsaved changes.", "Close review",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new String[]{"Save and close", "Discard unsaved changes", "Keep reviewing"}, "Save and close");
        if (decision == 0) save(false, close);
        else if (decision == 1) close.run();
    }

    @Override public void close() { closed = true; debounce.stop(); writer.shutdown(); }
}
