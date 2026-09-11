package org.atlasalign.plugin;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.process.Blitter;
import java.awt.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.*;
import org.atlasalign.application.*;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.atlas.AtlasRepository;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.io.imagej.ImagePlusSourcePixelReader;
import org.atlasalign.plugin.export.*;
import org.atlasalign.plugin.project.*;
import org.atlasalign.plugin.review.*;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

/** Reopens complete geometry only after verifying the referenced source and atlas. */
@Plugin(type = Command.class, menuPath = "Plugins>AtlasAlign Lite>Open Review Project…",
        description = "Resume a saved AtlasAlign review with verified source and atlas assets")
public final class OpenReviewProjectCommand implements Command {
    @Override public void run() { SwingUtilities.invokeLater(() -> chooseAndOpen(null)); }

    public static void chooseAndOpen(final Component owner) {
        final JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open AtlasAlign review project");
        if (chooser.showOpenDialog(owner) != JFileChooser.APPROVE_OPTION) return;
        final Path file = chooser.getSelectedFile().toPath();
        final Thread worker = new Thread(() -> {
            try { openChosen(file, owner); }
            catch (Exception error) { SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(owner,
                    "The saved review could not be opened.\n\n" + message(error), "Open project failed", JOptionPane.ERROR_MESSAGE)); }
        }, "atlasalign-project-reopen");
        worker.setDaemon(true); worker.start();
    }

    private static void openChosen(final Path file, final Component owner) throws Exception {
        final ReviewProjectStore store = new ReviewProjectStore();
        final byte[] bytes = store.read(file);
        final var header = store.header(bytes);
        final ImagePlus source = resolveSource(header, owner);
        if (source == null) return;
        Path atlas = Path.of(header.atlasCacheDirectory());
        if (!Files.isDirectory(atlas)) {
            atlas = onEdt(() -> {
                final JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Locate this project's verified Allen atlas cache");
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                return chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
            });
            if (atlas == null) return;
        }
        final var launch = prepare(bytes, source, atlas);
        show(launch, file);
    }

    /** Also used by the batch queue with its independently extracted section image. */
    public static void reopen(final Path file, final ImagePlus section, final Path atlasDirectory) throws IOException {
        final var store = new ReviewProjectStore();
        show(prepare(store.read(file), section, atlasDirectory), file);
    }

    public record RestoredLaunch(ImagePlus source, Path atlasDirectory, ReviewProject project,
            ReviewController controller, ReviewerRoiSession rois, SourceSpaceExportService atlasExport,
            ManualRoiExportService roiExport) { }

    public static RestoredLaunch prepare(final byte[] bytes, final ImagePlus source, final Path atlasDirectory) {
        final ReviewProjectStore store = new ReviewProjectStore();
        final var header = store.header(bytes);
        final var liveSource = new ImagePlusSourceImage(source);
        final var sourceSnapshot = liveSource.snapshot();
        if (!sourceSnapshot.equals(header.sourceSnapshot())) {
            throw new IllegalArgumentException("The source does not match this project's pixels, dimensions, channels or calibration. Reopen the original image in Fiji.");
        }
        final var atlas = new AtlasRepository().openAllenMouse25um(atlasDirectory);
        final var verification = new ReviewAcceptanceVerification(sourceSnapshot, ReviewAlignmentCommand.provenance(atlas));
        final ReviewProject project = store.restore(bytes, verification);
        final var dimensions = project.alignment().basis().previewDimensions();
        final var preview = liveSource.createPreview(project.registrationInput(), Math.max(dimensions.width(), dimensions.height()));
        final var restoredDimensions = ReviewPreviewDimensions.capture(preview.mapping().previewWidth(), preview.mapping().previewHeight(), preview.pixels());
        if (dimensions.width() != restoredDimensions.width() || dimensions.height() != restoredDimensions.height()
                || dimensions.pixelsSha256().isPresent() && !dimensions.pixelsSha256().equals(restoredDimensions.pixelsSha256())) {
            throw new IllegalArgumentException("The pinned registration preview no longer matches the saved review");
        }
        if (!sourceSnapshot.equals(liveSource.snapshot())) throw new IllegalArgumentException("Source changed while reopening the project");
        final var session = AlignmentReviewSession.restore(project.alignment(), verification);
        final var planeSource = new VerifiedAtlasPlaneSource(atlas);
        final ReviewAcceptanceVerifier verifier = () -> new ReviewAcceptanceVerification(new ImagePlusSourceImage(source).snapshot(),
                ReviewAlignmentCommand.provenance(new AtlasRepository().openAllenMouse25um(atlasDirectory)));
        final var controller = ReviewController.forSwing(session, ReviewPreview.copyOf(preview), planeSource, verifier);
        controller.initializeImageScope(project.registrationInput());
        controller.setExportSelection(project.exportSelection()); controller.setDisplaySettings(project.displaySettings());
        final var savedRois = project.rois();
        final var rois = ReviewerRoiSession.restore(savedRois.sectionId(), savedRois.sourceWidth(), savedRois.sourceHeight(),
                savedRois.rois(), savedRois.activeRoiId(), savedRois.activePartId(), savedRois.revision());
        final var reader = new ImagePlusSourcePixelReader(source);
        return new RestoredLaunch(source, atlasDirectory, project, controller, rois,
                new SourceSpaceExportService(reader, planeSource, verifier, controller::acceptedAlignment),
                new ManualRoiExportService(reader, sourceSnapshot, project.registrationInput().channel(),
                        project.registrationInput().slice(), project.registrationInput().frame(), project.parentSource()));
    }

    private static void show(final RestoredLaunch launch, final Path file) {
        SwingUtilities.invokeLater(() -> {
            try {
                final var context = new ReviewProjectContext(launch.source(), launch.atlasDirectory(), launch.project().parentSource(),
                        Optional.of(launch.project()), Optional.of(file));
                final var frame = SwingReviewWindow.open(launch.source().getTitle(), launch.controller(), launch.atlasExport(),
                        launch.roiExport(), launch.rois(), context);
                org.atlasalign.plugin.batch.BatchReviewNavigation.attach(launch.source(), frame);
            } catch (RuntimeException error) {
                launch.controller().close();
                if (!org.atlasalign.plugin.batch.BatchReviewNavigation.fail(launch.source(), error)) {
                    JOptionPane.showMessageDialog(null, message(error), "Open project failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }

    private static ImagePlus resolveSource(final ReviewProjectCodec.Header header, final Component owner) throws Exception {
        final var candidates = new ArrayList<ImagePlus>();
        final int[] ids = WindowManager.getIDList();
        if (ids != null) for (int id : ids) {
            final ImagePlus image = WindowManager.getImage(id);
            if (image != null && (image.getTitle().equals(header.source().title())
                    || ReviewProjectContext.sourcePath(image).filter(path -> header.source().path().filter(path::equals).isPresent()).isPresent())) candidates.add(image);
        }
        for (final var candidate : candidates) {
            final ImagePlus section = matchingSection(candidate, header);
            if (section != null) return section;
        }
        if (header.source().path().isPresent() && Files.isRegularFile(Path.of(header.source().path().orElseThrow()))) {
            final ImagePlus image = readTiff(Path.of(header.source().path().orElseThrow()));
            if (image != null) {
                final var section = matchingSection(image, header);
                if (section != null) return section;
            }
        }
        final Path chosen = onEdt(() -> {
            final JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Locate original TIFF / OME-TIFF: " + header.source().title());
            return chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath() : null;
        });
        if (chosen == null) return null;
        final var image = readTiff(chosen);
        final var section = image == null ? null : matchingSection(image, header);
        if (section == null) throw new IllegalArgumentException("The selected image differs from the saved source. Open the original image in Fiji with its original C/Z/T and calibration, then reopen this project.");
        return section;
    }

    private static ImagePlus readTiff(final Path file) throws Exception {
        final String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".tif") && !name.endsWith(".tiff")) throw new IllegalArgumentException("This version reopens TIFF / OME-TIFF sources");
        if (name.endsWith(".ome.tif") || name.endsWith(".ome.tiff")) {
            try {
                // Fiji already supplies the Bio-Formats importer; no new runtime dependency.
                final Class<?> importer = Class.forName("loci.plugins.BF");
                final ImagePlus[] images = (ImagePlus[]) importer.getMethod("openImagePlus", String.class).invoke(null, file.toString());
                if (images.length == 1) return images[0];
                throw new IllegalArgumentException("Open the original OME series in Fiji before reopening this project");
            } catch (ClassNotFoundException absent) {
                throw new IllegalArgumentException("Open the OME-TIFF using Fiji's Bio-Formats importer before reopening the project", absent);
            }
        }
        return IJ.openImage(file.toString());
    }

    private static ImagePlus matchingSection(final ImagePlus source, final ReviewProjectCodec.Header header) {
        final var snapshot = new ImagePlusSourceImage(source).snapshot();
        if (header.parentSource().isEmpty()) return snapshot.equals(header.sourceSnapshot()) ? source : null;
        final var parent = header.parentSource().orElseThrow();
        if (!snapshot.pixelSha256().equals(parent.pixelSha256()) || source.getWidth() != parent.sourceWidth()
                || source.getHeight() != parent.sourceHeight()) return null;
        final var metadata = header.sourceSnapshot().metadata();
        if ((long) parent.sectionOffsetX() + metadata.width() > source.getWidth()
                || (long) parent.sectionOffsetY() + metadata.height() > source.getHeight()) throw new IllegalArgumentException("Saved section crop is outside the parent image");
        final ImageStack stack = new ImageStack(metadata.width(), metadata.height());
        for (int plane = 1; plane <= source.getStackSize(); plane++) {
            final var pixels = source.getStack().getProcessor(plane);
            final var crop = pixels.createProcessor(metadata.width(), metadata.height());
            crop.copyBits(pixels, -parent.sectionOffsetX(), -parent.sectionOffsetY(), Blitter.COPY);
            stack.addSlice(source.getStack().getSliceLabel(plane), crop);
        }
        final ImagePlus crop = new ImagePlus("Saved section — " + source.getTitle(), stack);
        crop.setDimensions(source.getNChannels(), source.getNSlices(), source.getNFrames());
        crop.setOpenAsHyperStack(source.getNChannels() > 1 || source.getNSlices() > 1 || source.getNFrames() > 1);
        final var calibration = source.getCalibration().copy();
        calibration.xOrigin -= parent.sectionOffsetX(); calibration.yOrigin -= parent.sectionOffsetY(); crop.setCalibration(calibration);
        if (!snapshot.equals(new ImagePlusSourceImage(source).snapshot())) throw new IllegalArgumentException("Parent source changed while reopening its section");
        return new ImagePlusSourceImage(crop).snapshot().equals(header.sourceSnapshot()) ? crop : null;
    }

    private static <T> T onEdt(final Callable<T> task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return task.call();
        final FutureTask<T> future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get();
    }
    private static String message(final Throwable error) {
        Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
