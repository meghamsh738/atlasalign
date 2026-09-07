package org.atlasalign.application;

import java.util.Objects;

/**
 * Explicit outcome of preparing an inference-only virtual half-section.
 *
 * <p>{@link NotApplicable} does not imply either acceptance or that local
 * inference will be skipped: it only states that no virtual synthesis is
 * required.</p>
 */
public sealed interface VirtualHalfPreparationOutcome permits
        VirtualHalfPreparationOutcome.NotApplicable,
        VirtualHalfPreparationOutcome.Ready,
        VirtualHalfPreparationOutcome.ManualRequired {

    /** No virtual synthesis is required for this observed geometry. */
    record NotApplicable() implements VirtualHalfPreparationOutcome {
    }

    /** A verified inference-only input and its immutable provenance. */
    record Ready(
            DeepSliceInput inferenceInput,
            VirtualHalfPreparationProvenance provenance)
            implements VirtualHalfPreparationOutcome {

        public Ready {
            inferenceInput = Objects.requireNonNull(
                    inferenceInput, "inferenceInput");
            provenance = Objects.requireNonNull(provenance, "provenance");
            if (inferenceInput.width() != provenance.inferenceWidth()
                    || inferenceInput.height()
                    != provenance.inferenceHeight()) {
                throw new IllegalArgumentException(
                        "Inference input and preparation provenance dimensions must agree");
            }
            if (!VirtualHalfPayloadHashes.pixelsSha256(
                    inferenceInput.pixels()).equals(
                    provenance.inferencePixelsSha256())) {
                throw new IllegalArgumentException(
                        "Inference pixels do not match their preparation provenance hash");
            }
            if (!VirtualHalfPayloadHashes.syntheticMaskSha256(
                    inferenceInput.syntheticPixelMask()).equals(
                    provenance.syntheticMaskSha256())) {
                throw new IllegalArgumentException(
                        "Synthetic-pixel mask does not match its preparation provenance hash");
            }
        }
    }

    /** Automatic preparation stopped and requires a visible manual path. */
    record ManualRequired(
            VirtualHalfPreparationManualReason reason,
            String auditMessage) implements VirtualHalfPreparationOutcome {

        public ManualRequired {
            reason = Objects.requireNonNull(reason, "reason");
            auditMessage = Objects.requireNonNull(
                    auditMessage, "auditMessage");
            if (auditMessage.isBlank()) {
                throw new IllegalArgumentException(
                        "Manual preparation reason requires an audit message");
            }
        }
    }
}
