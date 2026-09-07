package org.atlasalign.plugin;

import org.atlasalign.plugin.ui.PluginBranding;
import org.atlasalign.plugin.setup.AtlasSetupDialog;
import org.atlasalign.plugin.setup.RuntimeSettings;
import ij.ImagePlus;
import ij.WindowManager;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.atlasalign.plugin.batch.BatchLaunchSettings;
import org.atlasalign.plugin.batch.BatchProjectLoader;
import org.atlasalign.plugin.batch.BatchProjectSession;
import org.atlasalign.plugin.batch.WholeSlideBatchWindow;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/** Resumes an autosaved queue after its unchanged sources are reopened. */
@Plugin(
        type = Command.class,
        menuPath = "Plugins>AtlasAlign Lite>Resume Batch Project",
        description = "Resume an autosaved AtlasAlign batch review queue")
public final class OpenBatchProjectCommand implements Command {

    @Parameter(visibility = org.scijava.ItemVisibility.MESSAGE, persist = false)
    private String authorCredit = PluginBranding.CREDIT;

    @Parameter(label = "AtlasAlign batch project JSON", style = "file")
    private File projectFile;

    @Parameter(label = "Registration channel (1-based)", min = "1")
    private int registrationChannel = 1;

    @Parameter(label = "Maximum preview dimension", min = "64")
    private int maximumPreviewDimension = 2_048;

    @Parameter(label = "Run DeepSlice separately for every section")
    private boolean useLocalDeepSlice;

    @Parameter(label = "Verified local DeepSlice runtime",
            style = "directory", persist = false)
    private File deepSliceRuntimeDirectory =
            new RuntimeSettings().paths().deepSliceRuntime().toFile();

    @Parameter(label = "DeepSlice temporary work directory",
            style = "directory", persist = false)
    private File deepSliceWorkDirectory =
            new RuntimeSettings().paths().deepSliceWork().toFile();

    @Parameter(label = "Verified Allen atlas cache",
            style = "directory", persist = false)
    private File atlasCacheDirectory =
            new RuntimeSettings().paths().atlasCache().toFile();

    @Parameter
    private CommandService commandService;

    @Parameter
    private LogService log;

    @Override
    public void run() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException(
                    "The batch review queue requires a graphical Fiji session");
        }
        ReviewAlignmentCommand.runAwayFromSwingEventThread(this::prepareBatch,
                error -> { throw error; });
    }

    private void prepareBatch() {
        try {
            final java.util.Optional<Path> availableAtlas = AtlasSetupDialog
                    .ensureAvailable(atlasCacheDirectory.toPath());
            if (availableAtlas.isEmpty()) return;
            atlasCacheDirectory = availableAtlas.orElseThrow().toFile();
            new RuntimeSettings().saveDeepSlice(
                    deepSliceRuntimeDirectory.toPath(), deepSliceWorkDirectory.toPath());
            final int[] ids = WindowManager.getIDList();
            final List<ImagePlus> open = ids == null ? List.of()
                    : Arrays.stream(ids).mapToObj(
                            WindowManager::getImage).toList();
            final BatchProjectSession project = new BatchProjectLoader()
                    .load(projectFile.toPath(), open);
            final BatchLaunchSettings settings = new BatchLaunchSettings(
                    registrationChannel, maximumPreviewDimension,
                    useLocalDeepSlice, deepSliceRuntimeDirectory,
                    deepSliceWorkDirectory, atlasCacheDirectory);
            project.save();
            log.info("AtlasAlign resumed batch project: "
                    + project.projectFile());
            SwingUtilities.invokeLater(() -> WholeSlideBatchWindow.open(
                    project, settings, commandService));
        } catch (final RuntimeException error) {
            final String message = rootMessage(error);
            log.error("AtlasAlign batch project was not resumed: " + message,
                    error);
            JOptionPane.showMessageDialog(null, message,
                    "Resume AtlasAlign batch",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String rootMessage(final Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null
                ? root.getClass().getSimpleName() : root.getMessage();
    }
}
