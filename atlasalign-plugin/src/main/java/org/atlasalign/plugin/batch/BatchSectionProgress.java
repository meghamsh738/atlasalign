package org.atlasalign.plugin.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.roi.ReviewerRoi;
import org.atlasalign.application.roi.ReviewerRoiSession;

/** Independent review, ROI, checkpoint and export facts; none implies scientific acceptance. */
public record BatchSectionProgress(long alignmentRevision, boolean accepted, long roiRevision,
        String roiContentSha256, int finishedRois, int unfinishedRois, ExportSelection exportSelection,
        String checkpointPath, SaveState saveState, ExportStamp lastExport) {

    public enum SaveState { UNSAVED, SAVED, DIRTY, FAILED }
    public enum ExportState { NOT_EXPORTED, CURRENT, STALE }
    public record ExportStamp(long alignmentRevision, String roiContentSha256,
            ExportSelection selection, String directory) {
        public ExportStamp {
            if (alignmentRevision < -1 || !Objects.requireNonNull(roiContentSha256).matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid batch export evidence");
            }
            Objects.requireNonNull(selection);
            if (Objects.requireNonNull(directory).isBlank()) throw new IllegalArgumentException("Export needs a directory");
        }
    }

    public BatchSectionProgress {
        if (alignmentRevision < -1 || roiRevision < -1 || finishedRois < 0 || unfinishedRois < 0
                || accepted && alignmentRevision < 0) throw new IllegalArgumentException("Invalid section progress");
        roiContentSha256 = Objects.requireNonNull(roiContentSha256);
        if (!roiContentSha256.isEmpty() && !roiContentSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid saved ROI geometry identity");
        }
        Objects.requireNonNull(saveState);
        if (checkpointPath != null && checkpointPath.isBlank()
                || saveState == SaveState.SAVED && checkpointPath == null) {
            throw new IllegalArgumentException("Saved review progress needs a checkpoint reference");
        }
    }

    public static BatchSectionProgress initial() {
        return new BatchSectionProgress(-1, false, -1, "", 0, 0, null, null, SaveState.UNSAVED, null);
    }

    BatchSectionProgress review(final long revision, final boolean isAccepted, final ReviewerRoiSession.Snapshot rois,
            final ExportSelection selection, final String checkpoint, final SaveState state) {
        if (revision < 0) throw new IllegalArgumentException("A review revision must be non-negative");
        return withSnapshot(revision, isAccepted, rois, selection,
                checkpoint == null ? checkpointPath : checkpoint, state);
    }

    BatchSectionProgress withSnapshot(final long revision, final boolean isAccepted, final ReviewerRoiSession.Snapshot rois,
            final ExportSelection selection, final String checkpoint, final SaveState state) {
        Objects.requireNonNull(rois); Objects.requireNonNull(selection);
        final int finished = (int) rois.rois().stream().filter(ReviewerRoi::finished).count();
        return new BatchSectionProgress(revision, isAccepted, rois.revision(), roiHash(rois), finished,
                rois.rois().size() - finished, selection, checkpoint, state, lastExport);
    }

    BatchSectionProgress withExport(final ExportStamp stamp) {
        return new BatchSectionProgress(alignmentRevision, accepted, roiRevision, roiContentSha256,
                finishedRois, unfinishedRois, exportSelection, checkpointPath, saveState, Objects.requireNonNull(stamp));
    }

    BatchSectionProgress reopened(final boolean checkpointFailed) {
        return new BatchSectionProgress(alignmentRevision, false, roiRevision, roiContentSha256,
                finishedRois, unfinishedRois, exportSelection, checkpointPath,
                checkpointFailed ? SaveState.FAILED : saveState, lastExport);
    }

    public ExportState exportState() {
        if (lastExport == null) return ExportState.NOT_EXPORTED;
        return lastExport.alignmentRevision() == alignmentRevision
                && lastExport.roiContentSha256().equals(roiContentSha256)
                && lastExport.selection().equals(exportSelection) ? ExportState.CURRENT : ExportState.STALE;
    }

    public String summary() {
        return (alignmentRevision < 0 ? "Alignment not started" : accepted ? "Atlas accepted" : "Atlas needs review")
                + " · " + finishedRois + " ROI(s) ready"
                + (unfinishedRois == 0 ? "" : ", " + unfinishedRois + " unfinished")
                + " · " + switch (saveState) {
                    case UNSAVED -> "Review not saved"; case SAVED -> "Review saved";
                    case DIRTY -> "Unsaved edits"; case FAILED -> "Save needs attention";
                }
                + " · " + switch (exportState()) {
                    case NOT_EXPORTED -> "Not exported"; case CURRENT -> "Export current"; case STALE -> "Export stale";
                };
    }

    static String roiHash(final ReviewerRoiSession.Snapshot snapshot) {
        // Exclude editor selection, revision and visibility: display-only changes do not stale an export.
        final List<Map<String, Object>> rois = snapshot.rois().stream().map(roi -> {
            final Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", roi.id()); value.put("name", roi.name()); value.put("side", roi.side());
            value.put("guide", roi.guideLink().orElse(null)); value.put("parts", roi.parts());
            value.put("selectedForExport", roi.selectedForExport());
            return value;
        }).toList();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(new ObjectMapper().writeValueAsBytes(rois)));
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Could not identify batch ROI geometry", error);
        }
    }

    static String reference(final Path projectDirectory, final Path checkpoint) {
        final Path absolute = checkpoint.toAbsolutePath().normalize();
        return absolute.startsWith(projectDirectory) ? projectDirectory.relativize(absolute).toString() : absolute.toString();
    }
}
