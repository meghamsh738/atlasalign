package org.atlasalign.application;

import java.util.Objects;
import java.util.Optional;
import org.atlasalign.core.SourceImageSnapshot;

/**
 * Verification and acknowledgement evidence for an acceptance attempt.
 */
public record ReviewAcceptanceAudit(
        boolean succeeded,
        boolean warningsAcknowledged,
        Optional<SourceImageSnapshot> currentSourceSnapshot,
        Optional<AtlasReviewProvenance> currentAtlas,
        Optional<ReviewAcceptanceBlockReason> failureReason) {

    public ReviewAcceptanceAudit {
        currentSourceSnapshot = Objects.requireNonNull(
                currentSourceSnapshot, "currentSourceSnapshot");
        currentAtlas = Objects.requireNonNull(
                currentAtlas, "currentAtlas");
        failureReason = Objects.requireNonNull(
                failureReason, "failureReason");
        if (succeeded
                && (currentSourceSnapshot.isEmpty()
                || currentAtlas.isEmpty()
                || failureReason.isPresent())
                || !succeeded && failureReason.isEmpty()) {
            throw new IllegalArgumentException(
                    "Acceptance audit evidence is inconsistent");
        }
    }
}
