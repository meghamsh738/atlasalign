package org.atlasalign.plugin.batch;

import ij.ImagePlus;
import java.awt.Component;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import javax.swing.JFrame;
import org.atlasalign.plugin.review.SwingReviewPanel;

/** Connects a batch-owned crop to its live review without adding intake fields. */
public final class BatchReviewNavigation {
    private static final Map<ImagePlus, Navigation> REVIEWS = new IdentityHashMap<>();

    private BatchReviewNavigation() { }

    public static synchronized java.util.concurrent.CompletableFuture<JFrame> register(
            final ImagePlus image, final Runnable returnToSections) {
        final Navigation navigation = new Navigation(Objects.requireNonNull(returnToSections));
        if (REVIEWS.containsKey(Objects.requireNonNull(image))) {
            throw new IllegalStateException("This section already has a registered review");
        }
        REVIEWS.put(image, navigation);
        return navigation.opening;
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
        private final java.util.concurrent.CompletableFuture<JFrame> opening =
                new java.util.concurrent.CompletableFuture<>();
        private JFrame window;
        private Navigation(final Runnable returnToSections) {
            this.returnToSections = returnToSections;
        }
    }
}
