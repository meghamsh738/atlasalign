package org.atlasalign.application;

/**
 * Typed failure that activates the manual-level fallback.
 */
public final class DeepSliceUnavailableException extends Exception {

    private final ManualFallbackReason reason;

    public DeepSliceUnavailableException(final String message) {
        this(ManualFallbackReason.WORKER_FAILED, message);
    }

    public DeepSliceUnavailableException(
            final ManualFallbackReason reason,
            final String message) {
        super(message);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public DeepSliceUnavailableException(
            final ManualFallbackReason reason,
            final String message,
            final Throwable cause) {
        super(message, cause);
        this.reason = java.util.Objects.requireNonNull(reason, "reason");
    }

    public ManualFallbackReason reason() {
        return reason;
    }
}
