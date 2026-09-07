package org.atlasalign.application.manual;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure topology-aware vertex edits that preserve explicit missing evidence. */
public final class ManualContourVertexEdits {

    private ManualContourVertexEdits() {
    }

    public static ManualContour insertAfter(
            final ManualContour contour,
            final String afterVertexId,
            final ContourVertex inserted) {
        Objects.requireNonNull(contour, "contour");
        Objects.requireNonNull(inserted, "inserted");
        final List<ContourVertex> before = contour.vertices();
        final int afterIndex = indexOf(before, afterVertexId);
        final int nextIndex = afterIndex + 1 < before.size()
                ? afterIndex + 1
                : contour.topology() == ContourTopology.CLOSED
                        && before.size() > 1 ? 0 : -1;
        final Set<ContourSegment> gaps = new LinkedHashSet<>(
                contour.excludedGapSegments());
        if (nextIndex >= 0) {
            final ContourSegment replaced = new ContourSegment(
                    before.get(afterIndex).id(), before.get(nextIndex).id());
            if (gaps.remove(replaced)) {
                gaps.add(new ContourSegment(
                        before.get(afterIndex).id(), inserted.id()));
                gaps.add(new ContourSegment(
                        inserted.id(), before.get(nextIndex).id()));
            }
        }
        final List<ContourVertex> vertices = new ArrayList<>(before);
        vertices.add(afterIndex + 1, inserted);
        return copy(contour, vertices, gaps);
    }

    public static ManualContour delete(
            final ManualContour contour,
            final String vertexId) {
        Objects.requireNonNull(contour, "contour");
        final List<ContourVertex> before = contour.vertices();
        final int removedIndex = indexOf(before, vertexId);
        final boolean closed = contour.topology() == ContourTopology.CLOSED;
        final int previousIndex = removedIndex > 0
                ? removedIndex - 1 : closed ? before.size() - 1 : -1;
        final int nextIndex = removedIndex + 1 < before.size()
                ? removedIndex + 1 : closed ? 0 : -1;
        final Set<ContourSegment> gaps = new LinkedHashSet<>(
                contour.excludedGapSegments());
        boolean mergedGap = false;
        if (previousIndex >= 0) {
            mergedGap |= gaps.remove(new ContourSegment(
                    before.get(previousIndex).id(), vertexId));
        }
        if (nextIndex >= 0) {
            mergedGap |= gaps.remove(new ContourSegment(
                    vertexId, before.get(nextIndex).id()));
        }
        final List<ContourVertex> vertices = new ArrayList<>(before);
        vertices.remove(removedIndex);
        if (mergedGap && previousIndex >= 0 && nextIndex >= 0
                && vertices.size() > 1) {
            gaps.add(new ContourSegment(
                    before.get(previousIndex).id(), before.get(nextIndex).id()));
        }
        return copy(contour, vertices, gaps);
    }

    private static ManualContour copy(
            final ManualContour original,
            final List<ContourVertex> vertices,
            final Set<ContourSegment> gaps) {
        final int completeMinimum = original.topology() == ContourTopology.CLOSED
                ? 3 : 2;
        final ContourCaptureStatus capture = vertices.size() >= completeMinimum
                ? original.captureStatus() : ContourCaptureStatus.DRAFT;
        return new ManualContour(
                original.id(), original.kind(), original.topology(), capture,
                original.anatomicalSide(), original.completeness(),
                original.atlasGuide(), original.sourceIdentity(), vertices, gaps,
                original.automaticProposalProvenance().map(
                        AutomaticTissueOutlineProvenance::markReviewerModified));
    }

    private static int indexOf(
            final List<ContourVertex> vertices,
            final String id) {
        for (int index = 0; index < vertices.size(); index++) {
            if (vertices.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unknown vertex " + id);
    }
}
