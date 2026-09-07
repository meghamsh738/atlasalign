package org.atlasalign.application;

import java.util.Objects;

/**
 * Explicit automatic-alignment result. Callers must choose a manual path; an
 * unavailable result never silently becomes an automatic proposal.
 */
public sealed interface AutomaticAlignmentOutcome permits
        AutomaticAlignmentOutcome.Success,
        AutomaticAlignmentOutcome.Unavailable,
        AutomaticAlignmentOutcome.ManualRequired,
        AutomaticAlignmentOutcome.NotAttempted {

    record Success(InitialPlaneProposal proposal)
            implements AutomaticAlignmentOutcome {
        public Success {
            proposal = Objects.requireNonNull(proposal, "proposal");
            if (proposal.source() != InitialPlaneSource.LOCAL_DEEPSLICE
                    || proposal.prediction().isEmpty()
                    || proposal.prediction().orElseThrow().diagnostics().isEmpty()
                    || proposal.prediction().orElseThrow()
                    .verifiedRuntimeProvenance().isEmpty()) {
                throw new IllegalArgumentException(
                        "Automatic success requires verified local DeepSlice diagnostics and runtime provenance");
            }
        }
    }

    record Unavailable(
            ManualFallbackReason reason,
            String message,
            boolean retryable) implements AutomaticAlignmentOutcome {
        public Unavailable {
            reason = Objects.requireNonNull(reason, "reason");
            message = Objects.requireNonNull(message, "message");
            if (message.isBlank()) {
                throw new IllegalArgumentException("Unavailable outcome needs a message");
            }
        }
    }

    record ManualRequired(
            VirtualHalfPreparationManualReason reason,
            String auditMessage) implements AutomaticAlignmentOutcome {
        public ManualRequired {
            reason = Objects.requireNonNull(reason, "reason");
            auditMessage = Objects.requireNonNull(auditMessage, "auditMessage");
            if (auditMessage.isBlank()) {
                throw new IllegalArgumentException("Manual-required outcome needs an audit message");
            }
        }
    }

    /** Automatic inference was explicitly disabled for this review attempt. */
    record NotAttempted(String message) implements AutomaticAlignmentOutcome {
        public NotAttempted {
            message = Objects.requireNonNull(message, "message");
            if (message.isBlank()) {
                throw new IllegalArgumentException(
                        "Not-attempted outcome needs a message");
            }
        }
    }

    public static AutomaticAlignmentOutcome from(
            final InitialPlaneProposal proposal,
            final VirtualHalfPreparationOutcome preparation) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(preparation, "preparation");
        if (proposal.source() == InitialPlaneSource.MANUAL_ONLY) {
            return new NotAttempted(
                    "Automatic alignment was disabled for this session");
        }
        if (preparation instanceof VirtualHalfPreparationOutcome.ManualRequired required) {
            return new ManualRequired(required.reason(), required.auditMessage());
        }
        if (proposal.source() == InitialPlaneSource.LOCAL_DEEPSLICE) {
            return new Success(proposal);
        }
        return new Unavailable(proposal.fallbackReason().orElseThrow(),
                proposal.fallbackMessage().orElseThrow(),
                retryable(proposal.fallbackReason().orElseThrow()));
    }

    private static boolean retryable(final ManualFallbackReason reason) {
        return switch (reason) {
            case VIRTUAL_HALF_PREPARATION_MANUAL_REQUIRED -> false;
            default -> true;
        };
    }
}
