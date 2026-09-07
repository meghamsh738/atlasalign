package org.atlasalign.plugin;

import org.atlasalign.plugin.ui.PluginBranding;
import org.atlasalign.plugin.setup.AtlasSetupDialog;
import org.atlasalign.plugin.setup.RuntimeSettings;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import ij.plugin.frame.RoiManager;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.atlasalign.plugin.batch.BatchInputFactory;
import org.atlasalign.plugin.batch.BatchLaunchSettings;
import org.atlasalign.plugin.batch.BatchProjectSession;
import org.atlasalign.plugin.batch.BatchReviewItem;
import org.atlasalign.plugin.batch.WholeSlideBatchWindow;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/** Creates a persistent queue from marked whole-slide sections or open images. */
@Plugin(
        type = Command.class,
        menuPath = "Plugins>AtlasAlign Lite>Batch / Whole-Slide Review",
        description = "Review marked tissue sections or open images as an autosaved AtlasAlign queue")
public final class BatchReviewCommand implements Command {

    @Parameter(visibility = org.scijava.ItemVisibility.MESSAGE, persist = false)
    private String authorCredit = PluginBranding.CREDIT;

    static final String MARKED_SECTIONS =
            "ROI Manager markers in current whole slide";
    static final String OPEN_IMAGES = "Each open Fiji image";

    @Parameter(label = "Current whole-slide image", required = false)
    private ImagePlus sourceImage;

    @Parameter(label = "Batch source", choices = {
        "ROI Manager markers in current whole slide",
        "Each open Fiji image"
    })
    private String sourceMode = MARKED_SECTIONS;

    @Parameter(label = "Import selected ROI Manager markers only",
            description = "Off: import every marker, even if one is highlighted. "
                    + "On: import only explicitly selected markers.", persist = false)
    private boolean selectedMarkersOnly;

    @Parameter(label = "Project folder", style = "directory")
    private File projectFolder;

    @Parameter(label = "Registration channel (1-based)", min = "1")
    private int registrationChannel = 1;

    @Parameter(label = "Maximum preview dimension", min = "64")
    private int maximumPreviewDimension = 2_048;

    @Parameter(label = "First section Allen AP level (0-based)",
            min = "0", max = "527")
    private int firstCoronalLevel = 264;

    @Parameter(label = "AP-level step between queued sections",
            min = "-527", max = "527")
    private int coronalLevelStep;

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
            final List<BatchReviewItem> items = createItems();
            final Path directory = projectDirectory(items);
            final BatchProjectSession project = new BatchProjectSession(
                    directory.getFileName().toString(), directory, items);
            final BatchLaunchSettings settings = new BatchLaunchSettings(
                    registrationChannel, maximumPreviewDimension,
                    useLocalDeepSlice, deepSliceRuntimeDirectory,
                    deepSliceWorkDirectory, atlasCacheDirectory);
            log.info("AtlasAlign batch project: " + project.projectFile()
                    + "; queued items=" + items.size()
                    + "; source mode=" + sourceMode);
            SwingUtilities.invokeLater(() -> WholeSlideBatchWindow.open(
                    project, settings, commandService));
        } catch (final RuntimeException error) {
            final String message = rootMessage(error);
            log.error("AtlasAlign batch queue was not created: " + message,
                    error);
            JOptionPane.showMessageDialog(null, message,
                    "AtlasAlign batch review",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private List<BatchReviewItem> createItems() {
        final BatchInputFactory factory = new BatchInputFactory();
        if (OPEN_IMAGES.equals(sourceMode)) {
            final int[] imageIds = WindowManager.getIDList();
            final List<ImagePlus> open = imageIds == null ? List.of()
                    : Arrays.stream(imageIds).mapToObj(
                            WindowManager::getImage).toList();
            return factory.openImages(open, firstCoronalLevel,
                    coronalLevelStep);
        }
        if (sourceImage == null) {
            throw new IllegalArgumentException(
                    "Choose the open whole-slide image containing the section markers");
        }
        final RoiManager manager = RoiManager.getInstance2();
        if (manager == null) {
            throw new IllegalArgumentException(
                    "Open Fiji ROI Manager and add one area ROI around each tissue section first");
        }
        final List<Roi> markers = selectMarkers(manager.getRoisAsArray(),
                manager.getSelectedIndexes(), selectedMarkersOnly);
        return factory.markedSections(sourceImage, markers,
                firstCoronalLevel, coronalLevelStep);
    }

    static List<Roi> selectMarkers(
            final Roi[] allMarkers,
            final int[] selectedIndexes,
            final boolean selectedOnly) {
        if (!selectedOnly) {
            return List.copyOf(Arrays.asList(allMarkers));
        }
        if (selectedIndexes.length == 0) {
            throw new IllegalArgumentException(
                    "No ROI Manager markers are selected. Select the markers to import, "
                            + "or turn off 'Import selected ROI Manager markers only' to import all markers.");
        }
        return Arrays.stream(selectedIndexes).mapToObj(index -> {
            if (index < 0 || index >= allMarkers.length) {
                throw new IllegalArgumentException(
                        "The ROI Manager selection changed. Please select the markers again.");
            }
            return allMarkers[index];
        }).toList();
    }

    private Path projectDirectory(final List<BatchReviewItem> items) {
        if (projectFolder == null) {
            throw new IllegalArgumentException(
                    "Choose a folder for the autosaved batch project");
        }
        final Path parent = projectFolder.toPath()
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(parent) || !Files.isWritable(parent)) {
            throw new IllegalArgumentException(
                    "The batch project folder must exist and be writable");
        }
        final String base = safeToken(items.get(0).section().sourceName())
                + "__atlasalign_batch";
        Path candidate = parent.resolve(base);
        for (int suffix = 2; Files.exists(candidate); suffix++) {
            candidate = parent.resolve(String.format(Locale.ROOT,
                    "%s_%03d", base, suffix));
        }
        return candidate;
    }

    private static String safeToken(final String value) {
        final String token = value.trim()
                .replaceAll("[^A-Za-z0-9._+-]+", "_")
                .replaceAll("^_+|_+$", "");
        return token.isEmpty() ? "images" : token;
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
