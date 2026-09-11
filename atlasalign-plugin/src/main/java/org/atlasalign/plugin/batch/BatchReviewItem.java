package org.atlasalign.plugin.batch;

import ij.ImagePlus;
import java.util.Objects;
import org.atlasalign.core.SourceImageSnapshot;

/** Runtime link between a persisted section descriptor and its open source. */
public record BatchReviewItem(
        ImagePlus source,
        SourceImageSnapshot verifiedSource,
        BatchSection section,
        BatchSectionProgress progress) {

    public BatchReviewItem(final ImagePlus source, final SourceImageSnapshot verifiedSource, final BatchSection section) {
        this(source, verifiedSource, section, BatchSectionProgress.initial());
    }

    public BatchReviewItem {
        source = Objects.requireNonNull(source, "source");
        verifiedSource = Objects.requireNonNull(
                verifiedSource, "verifiedSource");
        section = Objects.requireNonNull(section, "section");
        progress = Objects.requireNonNull(progress, "progress");
        if (!verifiedSource.pixelSha256().equals(
                section.sourcePixelSha256())
                || verifiedSource.metadata().width()
                != section.sourceWidth()
                || verifiedSource.metadata().height()
                != section.sourceHeight()) {
            throw new IllegalArgumentException(
                    "Batch item source does not match its section descriptor");
        }
    }

    BatchReviewItem withSection(final BatchSection value) {
        return new BatchReviewItem(source, verifiedSource, value, progress);
    }

    BatchReviewItem withProgress(final BatchSectionProgress value) {
        return new BatchReviewItem(source, verifiedSource, section, value);
    }
}
