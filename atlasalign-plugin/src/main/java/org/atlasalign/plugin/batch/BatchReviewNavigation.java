package org.atlasalign.plugin.batch;

import ij.ImagePlus;
import java.awt.Component;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import javax.swing.JFrame;
import org.atlasalign.plugin.review.SwingReviewPanel;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.roi.ReviewerRoiSession;

/** Connects a batch-owned crop to its live review without adding intake fields. */
public final class BatchReviewNavigation {
    private static final Map<ImagePlus, Navigation> REVIEWS = new IdentityHashMap<>();

    private BatchReviewNavigation() { }

    public static synchronized java.util.concurrent.CompletableFuture<JFrame> register(
            final ImagePlus image, final Runnable returnToSections) {
        return register(image, returnToSections, null, null);
    }

    static synchronized java.util.concurrent.CompletableFuture<JFrame> register(final ImagePlus image,
            final Runnable returnToSections, final BatchProjectSession project, final String sectionId) {
        final Navigation navigation = new Navigation(Objects.requireNonNull(returnToSections), project, sectionId);
        if (REVIEWS.containsKey(Objects.requireNonNull(image))) {
            throw new IllegalStateException("This section already has a registered review");
        }
        REVIEWS.put(image, navigation);
        return navigation.opening;
    }

    public static void reviewProgress(final ImagePlus image, final long alignmentRevision, final boolean accepted,
            final ReviewerRoiSession.Snapshot rois, final ExportSelection selection, final Path checkpoint,
            final String saveState) {
        final Navigation navigation;
        synchronized (BatchReviewNavigation.class) { navigation = REVIEWS.get(image); }
        if (navigation != null && navigation.project != null) {
            navigation.project.reviewProgress(navigation.sectionId, alignmentRevision, accepted, rois, selection, checkpoint, saveState);
        }
    }

    public static void exported(final ImagePlus image, final Path directory) {
        final Navigation navigation;
        synchronized (BatchReviewNavigation.class) { navigation = REVIEWS.get(image); }
        if (navigation != null && navigation.project != null) navigation.project.exported(navigation.sectionId, directory);
    }

    /** Records exactly what the worker exported, even if the live review changed while it ran. */
    public static void exported(final ImagePlus image, final Path directory, final long alignmentRevision,
            final ReviewerRoiSession.Snapshot capturedRois, final ExportSelection selection) {
        final Navigation navigation;
        synchronized (BatchReviewNavigation.class) { navigation = REVIEWS.get(image); }
        if (navigation != null && navigation.project != null) {
            navigation.project.exportedSnapshot(navigation.sectionId, alignmentRevision, capturedRois, selection, directory);
        }
    }

    public static synchronized JFrame existingWindow(final ImagePlus image) {
        final Navigation navigation = REVIEWS.get(image);
        return navigation != null && navigation.window != null
                && navigation.window.isDisplayable() ? navigation.window : null;
    }

    public static synchronized void unregister(final ImagePlus image) {
        final Navigation navigation = REVIEWS.remove(image);
        if (navigation != null && !navigation.opening.isDone()) {
            navigation.opening.completeExceptionally(
                    new IllegalStateException("Section review closed before opening"));
        }
    }

    /** Reports preparation or native-window failure to the batch launcher. */
    public static synchronized boolean fail(final ImagePlus image, final Throwable error) {
        final Navigation navigation = REVIEWS.remove(image);
        if (navigation == null) return false;
        navigation.opening.completeExceptionally(Objects.requireNonNull(error));
        return true;
    }

    public static synchronized void attach(final ImagePlus image, final JFrame frame) {
        final Navigation navigation = REVIEWS.get(image);
        if (navigation == null) return;
        navigation.window = frame;
        for (Component component : frame.getContentPane().getComponents()) {
            if (component instanceof SwingReviewPanel panel) {
                panel.setReturnToSectionsAction(navigation.returnToSections);
            }
        }
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(final WindowEvent event) {
                unregister(image);
            }
        });
        navigation.opening.complete(frame);
    }

    private static final class Navigation {
        private final Runnable returnToSections;
        private final BatchProjectSession project;
        private final String sectionId;
        private final java.util.concurrent.CompletableFuture<JFrame> opening =
                new java.util.concurrent.CompletableFuture<>();
        private JFrame window;
        private Navigation(final Runnable returnToSections, final BatchProjectSession project, final String sectionId) {
            this.returnToSections = returnToSections;
            this.project = project;
            this.sectionId = sectionId;
        }
    }
}
