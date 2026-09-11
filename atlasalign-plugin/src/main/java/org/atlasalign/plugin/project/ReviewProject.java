package org.atlasalign.plugin.project;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.AlignmentReviewCheckpoint;
import org.atlasalign.application.DisplaySettings;
import org.atlasalign.application.RegistrationInput;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.plugin.export.ManualRoiExportService.ParentSourceContext;

/** Versioned project payload. Sources/caches are referenced; no image/model buffers are embedded. */
public record ReviewProject(SourceReference source, String atlasCacheDirectory,
        AlignmentReviewCheckpoint alignment, RegistrationInput registrationInput,
        DisplaySettings displaySettings, ExportSelection exportSelection,
        ReviewerRoiSession.Snapshot rois, ReviewUiState ui,
        Optional<ParentSourceContext> parentSource) {
    public record SourceReference(String title, Optional<String> path) {
        public SourceReference {
            title = Objects.requireNonNull(title, "title");
            path = Objects.requireNonNull(path, "path");
            if (title.isBlank() || path.filter(String::isBlank).isPresent()) {
                throw new IllegalArgumentException("Source reference must identify an image");
            }
        }
    }
    public ReviewProject {
        Objects.requireNonNull(source, "source");
        atlasCacheDirectory = Objects.requireNonNull(atlasCacheDirectory, "atlasCacheDirectory");
        if (atlasCacheDirectory.isBlank()) throw new IllegalArgumentException("Project needs an atlas cache reference");
        Objects.requireNonNull(alignment, "alignment"); Objects.requireNonNull(registrationInput, "registrationInput");
        Objects.requireNonNull(displaySettings, "displaySettings"); Objects.requireNonNull(exportSelection, "exportSelection");
        Objects.requireNonNull(rois, "rois"); Objects.requireNonNull(ui, "ui");
        parentSource = Objects.requireNonNull(parentSource, "parentSource");
        final var metadata = alignment.basis().sourceSnapshot().metadata();
        registrationInput.validateAgainst(metadata); displaySettings.validateAgainst(metadata);
        exportSelection.validateAgainst(metadata);
        if (exportSelection.slice() != registrationInput.slice() || exportSelection.frame() != registrationInput.frame()
                || rois.sourceWidth() != metadata.width() || rois.sourceHeight() != metadata.height()) {
            throw new IllegalArgumentException("Project geometry and image scope do not match");
        }
        // The editor's existing restore gate validates IDs, bounds and active unfinished parts.
        ReviewerRoiSession.restore(rois.sectionId(), rois.sourceWidth(), rois.sourceHeight(),
                rois.rois(), rois.activeRoiId(), rois.activePartId(), rois.revision());
    }
}
