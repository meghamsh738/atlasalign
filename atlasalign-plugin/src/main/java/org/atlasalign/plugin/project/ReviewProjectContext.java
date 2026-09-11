package org.atlasalign.plugin.project;

import ij.ImagePlus;
import java.nio.file.Path;
import java.util.Optional;
import org.atlasalign.plugin.export.ManualRoiExportService.ParentSourceContext;

/** Live source handles stay outside the persisted project. */
public record ReviewProjectContext(ImagePlus sourceImage, Path atlasDirectory,
        Optional<ParentSourceContext> parentSource, Optional<ReviewProject> restored,
        Optional<Path> projectFile) {
    public ReviewProject.SourceReference sourceReference() {
        if (restored.isPresent()) return restored.orElseThrow().source();
        if (parentSource.isPresent()) {
            final Object parentPath = sourceImage.getProperty("AtlasAlign.batch.parentPath");
            return new ReviewProject.SourceReference(parentSource.orElseThrow().sourceName(),
                    parentPath instanceof String value && !value.isBlank() ? Optional.of(value) : Optional.empty());
        }
        return new ReviewProject.SourceReference(sourceImage.getTitle(), sourcePath(sourceImage));
    }

    public static Optional<String> sourcePath(final ImagePlus image) {
        final var info = image.getOriginalFileInfo();
        if (info == null || info.fileName == null || info.fileName.isBlank() || info.directory == null) return Optional.empty();
        return Optional.of(Path.of(info.directory).resolve(info.fileName).toAbsolutePath().normalize().toString());
    }
}
