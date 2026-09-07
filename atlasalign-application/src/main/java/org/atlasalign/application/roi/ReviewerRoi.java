package org.atlasalign.application.roi;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One exact reviewer segmentation. Parts are evaluated as the union of ADD
 * polygons minus the union of SUBTRACT polygons.
 */
public record ReviewerRoi(
        String id,
        String name,
        ReviewerRoiSide side,
        Optional<ReviewerRoiGuideLink> guideLink,
        List<ReviewerRoiPart> parts,
        boolean visible,
        boolean selectedForExport) {

    public ReviewerRoi {
        id = requireText(id, "id");
        name = requireText(name, "name");
        side = Objects.requireNonNull(side, "side");
        guideLink = Objects.requireNonNull(guideLink, "guideLink");
        parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
        final Set<String> partIds = new HashSet<>();
        for (final ReviewerRoiPart part : parts) {
            Objects.requireNonNull(part,
                    "ROI must not contain null polygon parts");
            if (!partIds.add(part.id())) {
                throw new IllegalArgumentException(
                        "ROI part IDs must be unique");
            }
        }
    }

    public boolean finished() {
        return parts.stream().anyMatch(part -> part.finished()
                && part.operation() == RoiPartOperation.ADD)
                && parts.stream().allMatch(ReviewerRoiPart::finished);
    }

    public ReviewerRoi withParts(final List<ReviewerRoiPart> updated) {
        return new ReviewerRoi(id, name, side, guideLink, updated,
                visible, selectedForExport);
    }

    public ReviewerRoi withName(final String updated) {
        return new ReviewerRoi(id, updated, side, guideLink, parts,
                visible, selectedForExport);
    }

    public ReviewerRoi withSide(final ReviewerRoiSide updated) {
        return new ReviewerRoi(id, name, updated, guideLink, parts,
                visible, selectedForExport);
    }

    public ReviewerRoi withVisibility(final boolean updated) {
        return new ReviewerRoi(id, name, side, guideLink, parts,
                updated, selectedForExport);
    }

    public ReviewerRoi withExportSelection(final boolean updated) {
        return new ReviewerRoi(id, name, side, guideLink, parts,
                visible, updated);
    }

    private static String requireText(
            final String value,
            final String field) {
        final String checked = Objects.requireNonNull(value, field).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return checked;
    }
}
