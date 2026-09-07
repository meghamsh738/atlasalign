package org.atlasalign.application.manual;

/** Typed failure that is safe to translate into a concise reviewer message. */
public final class BoundaryFitException extends IllegalArgumentException {

    private final BoundaryFitFailureKind kind;
    private final String reviewerMessage;

    public BoundaryFitException(
            final BoundaryFitFailureKind kind,
            final String reviewerMessage,
            final String technicalMessage) {
        super(technicalMessage);
        this.kind = java.util.Objects.requireNonNull(kind, "kind");
        if (reviewerMessage == null || reviewerMessage.isBlank()) {
            throw new IllegalArgumentException(
                    "Boundary-fit reviewer message must not be blank");
        }
        this.reviewerMessage = reviewerMessage;
    }

    public BoundaryFitFailureKind kind() {
        return kind;
    }

    public String reviewerMessage() {
        return reviewerMessage;
    }
}
