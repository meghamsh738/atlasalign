package org.atlasalign.application.manual;

import java.util.Objects;
import org.atlasalign.core.SourceImageMetadata;

/**
 * Exact source identity needed to validate source-pixel contour input.
 *
 * <p>The complete immutable metadata snapshot is retained deliberately: equal
 * pixels and dimensions are not sufficient to identify a channel, stack,
 * calibration, bit depth, or exact ImageJ plane-label state.</p>
 */
public record SourceImageIdentity(
        String pixelSha256,
        SourceImageMetadata metadata,
        String previewMappingIdentity) {

    public SourceImageIdentity {
        pixelSha256 = Objects.requireNonNull(pixelSha256, "pixelSha256");
        metadata = Objects.requireNonNull(metadata, "metadata");
        previewMappingIdentity = requireText(
                previewMappingIdentity, "previewMappingIdentity");
        if (!pixelSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "pixelSha256 must be a lowercase SHA-256 value");
        }
    }

    public int width() {
        return metadata.width();
    }

    public int height() {
        return metadata.height();
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }
}
