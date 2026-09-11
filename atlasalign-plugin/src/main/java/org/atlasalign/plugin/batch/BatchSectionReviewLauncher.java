package org.atlasalign.plugin.batch;

import ij.ImagePlus;
import java.nio.file.Path;
import java.util.Objects;
import org.atlasalign.plugin.OpenReviewProjectCommand;
import org.atlasalign.plugin.project.ReviewProjectStore;

/** Dispatches a saved checkpoint directly to restore; a damaged reference never starts a fresh alignment. */
final class BatchSectionReviewLauncher {
    @FunctionalInterface interface NewReview { void open(ImagePlus image) throws Exception; }
    @FunctionalInterface interface Reopen { void open(Path file, ImagePlus image, Path atlasDirectory) throws Exception; }
    private final Reopen reopen;

    BatchSectionReviewLauncher() { this(OpenReviewProjectCommand::reopen); }
    BatchSectionReviewLauncher(final Reopen reopen) { this.reopen = Objects.requireNonNull(reopen); }

    void open(final BatchProjectSession project, final BatchReviewItem item, final ImagePlus sectionImage,
            final Path atlasDirectory, final NewReview newReview) throws Exception {
        final var checkpoint = project.checkpoint(item);
        sectionImage.setProperty("AtlasAlign.review.defaultProjectPath",
                checkpoint.orElseGet(() -> project.defaultCheckpoint(item.section())).toString());
        if (checkpoint.isPresent()) {
            final Path file = checkpoint.orElseThrow();
            final var store = new ReviewProjectStore();
            BatchReviewCheckpoint.requireSection(store.header(store.read(file)), item, sectionImage);
            reopen.open(file, sectionImage, atlasDirectory);
        } else newReview.open(sectionImage);
    }
}
