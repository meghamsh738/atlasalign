package org.atlasalign.plugin.setup;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import org.atlasalign.atlas.AtlasDownloadService;
import org.atlasalign.atlas.AtlasManifest;
import org.atlasalign.atlas.AtlasManifests;
import org.atlasalign.atlas.AtlasRepository;
import org.atlasalign.plugin.ui.PluginBranding;

/** Modal choice with all disk verification/download work performed off the Swing event thread. */
public final class AtlasSetupDialog {
    private AtlasSetupDialog() { }

    /** Called on a worker thread before review preparation; cancellation leaves the review unopened. */
    public static Optional<Path> ensureAvailable(final Path requested) {
        try {
            new AtlasRepository().openAllenMouse25um(requested);
            new RuntimeSettings().saveAtlas(requested);
            return Optional.of(requested);
        } catch (RuntimeException missingOrInvalid) {
            if (GraphicsEnvironment.isHeadless()) throw missingOrInvalid;
            final java.util.concurrent.atomic.AtomicReference<Optional<Path>> result =
                    new java.util.concurrent.atomic.AtomicReference<>(Optional.empty());
            final Runnable show = () -> result.set(choose(null, requested));
            try {
                if (SwingUtilities.isEventDispatchThread()) show.run();
                else SwingUtilities.invokeAndWait(show);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (java.lang.reflect.InvocationTargetException error) {
                throw new IllegalStateException("Could not open atlas setup", error.getCause());
            }
            return result.get();
        }
    }

    /** Runs on the event thread and returns only a fully verified cache, or empty on cancel. */
    public static Optional<Path> choose(final Component parent, final Path initial) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Atlas setup must open on the Swing event thread");
        final Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog dialog = new JDialog(owner, "AtlasAlign Lite — Atlas setup", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        final AtlasManifest manifest = AtlasManifests.allenMouse25um();
        final JPanel content = new JPanel(new BorderLayout(8, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        final JPanel fields = new JPanel(new GridLayout(0, 1, 5, 7));
        fields.add(new JLabel("Choose an existing Allen 25 µm cache, or download a new verified cache."));
        final JTextField directory = new JTextField(initial.toString(), 46);
        final JPanel pathRow = new JPanel(new BorderLayout(6, 0));
        final JButton browse = new JButton("Browse…");
        pathRow.add(directory, BorderLayout.CENTER);
        pathRow.add(browse, BorderLayout.EAST);
        fields.add(pathRow);
        final JTextArea details = new JTextArea("Atlas: " + manifest.atlasVersion()
                + "\nDownload: " + String.format(java.util.Locale.ROOT, "%.1f MiB", manifest.totalSizeBytes() / 1048576.0)
                + "; allow at least " + (manifest.totalSizeBytes() + 1048576) + " free bytes."
                + "\nSource: Allen Institute (download.alleninstitute.org and api.brain-map.org)"
                + "\nTerms: " + manifest.termsUrl() + "\nCitation: " + manifest.citation()
                + "\nAn existing cache is verified offline. New downloads use a resumable staging folder."
                + "\nFor an invalid existing cache, choose a new folder; existing files are never overwritten."
                + "\nPython and DeepSlice are not required for manual alignment.", 9, 52);
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setBackground(content.getBackground());
        final JCheckBox terms = new JCheckBox("I acknowledge the Allen Institute terms and citation requirements for this download.");
        final JPanel center = new JPanel(new BorderLayout(5, 8));
        center.add(new JScrollPane(details), BorderLayout.CENTER);
        center.add(terms, BorderLayout.SOUTH);
        final JProgressBar progress = new JProgressBar(0, 1000);
        progress.setStringPainted(true);
        progress.setString("Ready");
        final JTextArea status = new JTextArea("", 2, 52);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setEditable(false);
        status.setBackground(content.getBackground());
        final JButton existing = new JButton("Use existing cache");
        final JButton download = new JButton("Download atlas");
        download.setEnabled(false);
        final JButton cancel = new JButton("Cancel");
        final JPanel actions = new JPanel();
        actions.add(existing); actions.add(download); actions.add(cancel);
        final JPanel bottom = new JPanel(new BorderLayout(5, 5));
        final JPanel feedback = new JPanel(new GridLayout(0, 1));
        feedback.add(progress); feedback.add(status); feedback.add(actions);
        bottom.add(feedback, BorderLayout.CENTER);
        bottom.add(PluginBranding.creditLabel(), BorderLayout.SOUTH);
        content.add(fields, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        final AtomicBoolean busy = new AtomicBoolean();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicReference<Path> selected = new java.util.concurrent.atomic.AtomicReference<>();
        final Runnable cancelAction = () -> {
            if (busy.get()) {
                cancelled.set(true);
                status.setText("Cancelling… partial downloads are kept for resume (network reads may take up to 15 seconds).");
                cancel.setEnabled(false);
            } else dialog.dispose();
        };
        cancel.addActionListener(event -> cancelAction.run());
        dialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(final WindowEvent event) { cancelAction.run(); }
        });
        browse.addActionListener(event -> {
            final JFileChooser chooser = new JFileChooser(directory.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(dialog) == JFileChooser.APPROVE_OPTION) directory.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        terms.addActionListener(event -> download.setEnabled(!busy.get() && terms.isSelected()));
        final java.util.function.Consumer<Boolean> start = fetch -> {
            final Path target;
            try {
                if (directory.getText().isBlank()) throw new IllegalArgumentException("Choose a cache folder.");
                target = Path.of(directory.getText().trim()).toAbsolutePath().normalize();
            } catch (RuntimeException invalid) { status.setText(invalid.getMessage()); return; }
            if (fetch && !terms.isSelected()) return;
            busy.set(true); cancelled.set(false);
            existing.setEnabled(false); download.setEnabled(false); browse.setEnabled(false);
            directory.setEnabled(false); terms.setEnabled(false);
            progress.setIndeterminate(true);
            status.setText(fetch ? "Downloading and verifying pinned atlas assets…" : "Verifying local atlas checksums…");
            new SwingWorker<Path, AtlasDownloadService.Progress>() {
                @Override protected Path doInBackground() throws Exception {
                    if (fetch) new AtlasDownloadService().install(target, cancelled::get, this::publish);
                    else new AtlasRepository().openAllenMouse25um(target);
                    if (cancelled.get()) throw new java.util.concurrent.CancellationException("Setup cancelled.");
                    new RuntimeSettings().saveAtlas(target);
                    return target;
                }
                @Override protected void process(final List<AtlasDownloadService.Progress> updates) {
                    if (updates.isEmpty()) return;
                    final AtlasDownloadService.Progress latest = updates.get(updates.size() - 1);
                    progress.setIndeterminate(false);
                    progress.setValue((int) (1000 * latest.completedBytes() / latest.totalBytes()));
                    progress.setString(latest.asset() + " — " + (100 * latest.completedBytes() / latest.totalBytes()) + "%");
                }
                @Override protected void done() {
                    busy.set(false); progress.setIndeterminate(false); cancel.setEnabled(true);
                    try { selected.set(get()); dialog.dispose(); }
                    catch (Exception error) {
                        if (cancelled.get()) { dialog.dispose(); return; }
                        Throwable cause = error;
                        while (cause.getCause() != null) cause = cause.getCause();
                        status.setText(cause.getMessage() == null ? cause.toString() : cause.getMessage());
                        existing.setEnabled(true); download.setEnabled(terms.isSelected()); browse.setEnabled(true);
                        directory.setEnabled(true); terms.setEnabled(true);
                        progress.setString(cancelled.get() ? "Cancelled — retry to resume" : "Setup needs attention");
                    }
                }
            }.execute();
        };
        existing.addActionListener(event -> start.accept(false));
        download.addActionListener(event -> start.accept(true));
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return Optional.ofNullable(selected.get());
    }
}
