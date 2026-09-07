package org.atlasalign.plugin.manual;

import java.util.Objects;

/** Fixed, Phase-5-safe anatomical guides available to contour capture. */
public record ManualGuideOption(
        String acronym,
        String displayName,
        String drawingHint) {

    public ManualGuideOption {
        acronym = requireText(acronym, "acronym");
        displayName = requireText(displayName, "displayName");
        drawingHint = requireText(drawingHint, "drawingHint");
    }

    @Override
    public String toString() {
        return displayName;
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
