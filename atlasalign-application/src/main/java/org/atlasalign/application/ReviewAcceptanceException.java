package org.atlasalign.application;

import java.util.Objects;

public final class ReviewAcceptanceException
        extends IllegalStateException {

    private final ReviewAcceptanceBlockReason reason;

    public ReviewAcceptanceException(
            final ReviewAcceptanceBlockReason reason,
            final String message) {
        this(reason, message, null);
    }

    public ReviewAcceptanceException(
            final ReviewAcceptanceBlockReason reason,
            final String message,
            final Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public ReviewAcceptanceBlockReason reason() {
        return reason;
    }
}
