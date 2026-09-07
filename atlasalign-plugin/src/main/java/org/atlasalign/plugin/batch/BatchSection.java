package org.atlasalign.plugin.batch;

import java.util.List;
import java.util.Objects;
import org.atlasalign.core.Point2D;

/** Immutable, source-coordinate description of one review queue item. */
public record BatchSection(
        String id,
        String name,
        String sourceName,
        String sourcePixelSha256,
        int sourceWidth,
        int sourceHeight,
        int minimumX,
        int minimumY,
        int width,
        int height,
        List<Point2D> markerVertices,
        int initialCoronalLevel,
        BatchReviewStatus status,
        String statusDetail) {

    public BatchSection {
        id = requireText(id, "id");
        name = requireText(name, "name");
        sourceName = requireText(sourceName, "sourceName");
        sourcePixelSha256 = requireText(
                sourcePixelSha256, "sourcePixelSha256");
        if (!sourcePixelSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Source pixel SHA-256 must be lowercase hexadecimal");
        }
        if (sourceWidth <= 0 || sourceHeight <= 0
                || minimumX < 0 || minimumY < 0
                || width <= 0 || height <= 0
                || (long) minimumX + width > sourceWidth
                || (long) minimumY + height > sourceHeight) {
            throw new IllegalArgumentException(
                    "Batch section bounds are outside the source image");
        }
        markerVertices = List.copyOf(Objects.requireNonNull(
                markerVertices, "markerVertices"));
        if (markerVertices.size() < 3) {
            throw new IllegalArgumentException(
                    "A section marker requires at least three vertices");
        }
        if (initialCoronalLevel < 0 || initialCoronalLevel > 527) {
            throw new IllegalArgumentException(
                    "Allen coronal level must be in 0..527");
        }
        status = Objects.requireNonNull(status, "status");
        statusDetail = statusDetail == null ? "" : statusDetail.trim();
    }

    public BatchSection withName(final String value) {
        return copy(requireText(value, "name"), initialCoronalLevel,
                status, statusDetail);
    }

    public BatchSection withInitialCoronalLevel(final int value) {
        return copy(name, value, status, statusDetail);
    }

    public BatchSection withStatus(
            final BatchReviewStatus value,
            final String detail) {
        return copy(name, initialCoronalLevel,
                Objects.requireNonNull(value, "status"), detail);
    }

    private BatchSection copy(
            final String copiedName,
            final int copiedLevel,
            final BatchReviewStatus copiedStatus,
            final String copiedDetail) {
        return new BatchSection(id, copiedName, sourceName,
                sourcePixelSha256, sourceWidth, sourceHeight,
                minimumX, minimumY, width, height, markerVertices,
                copiedLevel, copiedStatus, copiedDetail);
    }

    private static String requireText(
            final String value,
            final String name) {
        final String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
