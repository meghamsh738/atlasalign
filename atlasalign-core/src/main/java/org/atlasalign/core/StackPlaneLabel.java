package org.atlasalign.core;

import java.util.Objects;

/**
 * Exact ImageJ stack-plane label state. The presence flag distinguishes a null
 * label from an empty string or any generated display label.
 */
public record StackPlaneLabel(boolean present, String value) {

    public StackPlaneLabel {
        value = Objects.requireNonNull(value, "value");
        if (!present && !value.isEmpty()) {
            throw new IllegalArgumentException("An absent label cannot have text");
        }
    }

    public static StackPlaneLabel fromNullable(final String label) {
        return label == null
                ? new StackPlaneLabel(false, "")
                : new StackPlaneLabel(true, label);
    }
}
