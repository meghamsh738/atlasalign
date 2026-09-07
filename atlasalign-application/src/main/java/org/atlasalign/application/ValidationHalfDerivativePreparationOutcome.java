package org.atlasalign.application;

import java.util.List;
import java.util.Objects;

/**
 * Typed all-or-nothing outcome of preparing the four controlled validation
 * half derivatives from one verified full source.
 */
public sealed interface ValidationHalfDerivativePreparationOutcome permits
        ValidationHalfDerivativePreparationOutcome.Ready,
        ValidationHalfDerivativePreparationOutcome.Failed {

    /**
     * All four ordered derivatives sharing one full-source and finite
     * background identity.
     */
    record Ready(List<ValidationHalfDerivative> derivatives)
            implements ValidationHalfDerivativePreparationOutcome {

        public Ready {
            derivatives = List.copyOf(Objects.requireNonNull(
                    derivatives, "derivatives"));
            final ValidationHalfDerivativeCondition[] conditions =
                    ValidationHalfDerivativeCondition.values();
            if (derivatives.size() != conditions.length) {
                throw new IllegalArgumentException(
                        "Validation derivative preparation must contain all four conditions");
            }
            final ValidationHalfDerivative first = derivatives.get(0);
            first.verifyIntegrity();
            final ValidationHalfDerivativeProvenance firstProvenance =
                    first.provenance();
            for (int index = 0; index < conditions.length; index++) {
                final ValidationHalfDerivative derivative = derivatives.get(index);
                derivative.verifyIntegrity();
                if (derivative.condition() != conditions[index]) {
                    throw new IllegalArgumentException(
                            "Validation derivatives are not in frozen condition order");
                }
                if (!sharesSourceIdentity(
                        firstProvenance, derivative.provenance())
                        || !sharesBackgroundIdentity(
                                firstProvenance, derivative.provenance())) {
                    throw new IllegalArgumentException(
                            "Validation derivatives must share source and background identity");
                }
            }
        }

        /** Returns the derivative for a frozen condition without reordering. */
        public ValidationHalfDerivative derivative(
                final ValidationHalfDerivativeCondition condition) {
            final ValidationHalfDerivativeCondition required =
                    Objects.requireNonNull(condition, "condition");
            return derivatives.get(required.ordinal());
        }

        private static boolean sharesSourceIdentity(
                final ValidationHalfDerivativeProvenance first,
                final ValidationHalfDerivativeProvenance next) {
            return first.algorithmRevision().equals(next.algorithmRevision())
                    && first.sourceGeometry() == next.sourceGeometry()
                    && first.sourcePixelsSha256().equals(
                            next.sourcePixelsSha256())
                    && first.fullSourceMaskSha256().equals(
                            next.fullSourceMaskSha256())
                    && first.sourceWidth() == next.sourceWidth()
                    && first.sourceHeight() == next.sourceHeight()
                    && first.fullSourceTissuePixelCount()
                    == next.fullSourceTissuePixelCount();
        }

        private static boolean sharesBackgroundIdentity(
                final ValidationHalfDerivativeProvenance first,
                final ValidationHalfDerivativeProvenance next) {
            return first.backgroundStrategy() == next.backgroundStrategy()
                    && Float.floatToRawIntBits(first.backgroundValue())
                    == Float.floatToRawIntBits(next.backgroundValue())
                    && first.backgroundSampleCount()
                    == next.backgroundSampleCount();
        }
    }

    /** No derivative was emitted; the audit message states the closed reason. */
    record Failed(
            ValidationHalfDerivativeFailureReason reason,
            String auditMessage) implements ValidationHalfDerivativePreparationOutcome {

        public Failed {
            reason = Objects.requireNonNull(reason, "reason");
            auditMessage = Objects.requireNonNull(auditMessage, "auditMessage");
            if (auditMessage.isBlank()) {
                throw new IllegalArgumentException(
                        "Validation derivative failure requires an audit message");
            }
        }
    }
}
