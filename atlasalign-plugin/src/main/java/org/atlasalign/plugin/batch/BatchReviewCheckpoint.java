package org.atlasalign.plugin.batch;

import ij.ImagePlus;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import org.atlasalign.application.AtlasReviewProvenance;
import org.atlasalign.application.ReviewAcceptanceVerification;
import org.atlasalign.atlas.AtlasRepository;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.plugin.ReviewAlignmentCommand;
import org.atlasalign.plugin.export.ManualRoiExportService.ParentSourceContext;
import org.atlasalign.plugin.project.ReviewProject;
import org.atlasalign.plugin.project.ReviewProjectCodec;
import org.atlasalign.plugin.project.ReviewProjectStore;

/** Shared source/section identity gate for full-project batch resume and export. */
final class BatchReviewCheckpoint {
    private final Function<Path, AtlasReviewProvenance> verifyAtlas;

    BatchReviewCheckpoint() {
        this(path -> ReviewAlignmentCommand.provenance(new AtlasRepository().openAllenMouse25um(path)));
    }

    BatchReviewCheckpoint(final Function<Path, AtlasReviewProvenance> verifyAtlas) {
        this.verifyAtlas = Objects.requireNonNull(verifyAtlas);
    }

    ReviewProject read(final Path checkpoint, final BatchReviewItem item, final ImagePlus sectionImage) throws IOException {
        final var store = new ReviewProjectStore();
        final byte[] bytes = store.read(checkpoint);
        final var header = store.header(bytes);
        requireSection(header, item, sectionImage);
        final var atlas = verifyAtlas.apply(Path.of(header.atlasCacheDirectory()));
        final var project = store.restore(bytes, new ReviewAcceptanceVerification(
                new ImagePlusSourceImage(sectionImage).snapshot(), atlas));
        if (!project.rois().sectionId().equals(item.section().id())) {
            throw new IllegalArgumentException("Saved review belongs to another batch section");
        }
        return project;
    }

    static void requireSection(final ReviewProjectCodec.Header header, final BatchReviewItem item,
            final ImagePlus sectionImage) {
        if (!header.sourceSnapshot().equals(new ImagePlusSourceImage(sectionImage).snapshot())) {
            throw new IllegalArgumentException("Saved review source does not match this unchanged section crop");
        }
        final var section = item.section();
        final var expectedParent = parentContext(section);
        final boolean wholeImage = section.minimumX() == 0 && section.minimumY() == 0
                && section.width() == section.sourceWidth() && section.height() == section.sourceHeight();
        if (header.parentSource().isPresent() && !header.parentSource().orElseThrow().equals(expectedParent)
                || header.parentSource().isEmpty() && !wholeImage) {
            throw new IllegalArgumentException("Saved review parent image or section offsets differ from this queue item");
        }
    }

    static ParentSourceContext parentContext(final BatchSection section) {
        return new ParentSourceContext(section.sourceName(), section.sourcePixelSha256(), section.sourceWidth(),
                section.sourceHeight(), section.minimumX(), section.minimumY());
    }
}
