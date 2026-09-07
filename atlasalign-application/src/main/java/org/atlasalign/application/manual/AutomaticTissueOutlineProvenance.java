package org.atlasalign.application.manual;

import java.util.Objects;
import org.atlasalign.application.TissuePolarity;
import org.atlasalign.application.TissueSegmentationMethod;

/** Immutable identity and derivation record for a proposed tissue outline. */
public record AutomaticTissueOutlineProvenance(
        SourceImageIdentity sourceIdentity,
        String copiedPreviewPixelSha256,
        TissueSegmentationMethod segmentationMethod,
        TissuePolarity segmentationPolarity,
        float segmentationThreshold,
        String inputMaskSha256,
        String filledEnvelopeSha256,
        String outlinedEnvelopeSha256,
        String discardedDiagonalOnlyPixelsSha256,
        String includedCornerBackgroundPixelsSha256,
        String algorithmRevision,
        String generatedLoopSha256,
        int previewWidth,
        int previewHeight,
        int selectedComponentPixelCount,
        int filledEnvelopePixelCount,
        int outlinedEnvelopePixelCount,
        int discardedDiagonalOnlyComponentCount,
        int discardedDiagonalOnlyPixelCount,
        int largestDiscardedDiagonalOnlyComponentPixelCount,
        int largestDiscardedDiagonalOnlyEightConnectedSpanPixels,
        int includedCornerBackgroundComponentCount,
        int includedCornerBackgroundPixelCount,
        int largestIncludedCornerBackgroundComponentPixelCount,
        int largestIncludedCornerBackgroundEightConnectedSpanPixels,
        int rawLoopVertexCount,
        int generatedVertexCount,
        boolean reviewerModified) {

    public AutomaticTissueOutlineProvenance {
        sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        copiedPreviewPixelSha256 = requireSha256(
                copiedPreviewPixelSha256, "copiedPreviewPixelSha256");
        segmentationMethod = Objects.requireNonNull(
                segmentationMethod, "segmentationMethod");
        segmentationPolarity = Objects.requireNonNull(
                segmentationPolarity, "segmentationPolarity");
        if (!Float.isFinite(segmentationThreshold)) {
            throw new IllegalArgumentException(
                    "segmentationThreshold must be finite");
        }
        inputMaskSha256 = requireSha256(inputMaskSha256, "inputMaskSha256");
        filledEnvelopeSha256 = requireSha256(
                filledEnvelopeSha256, "filledEnvelopeSha256");
        outlinedEnvelopeSha256 = requireSha256(
                outlinedEnvelopeSha256, "outlinedEnvelopeSha256");
        discardedDiagonalOnlyPixelsSha256 = requireSha256(
                discardedDiagonalOnlyPixelsSha256,
                "discardedDiagonalOnlyPixelsSha256");
        includedCornerBackgroundPixelsSha256 = requireSha256(
                includedCornerBackgroundPixelsSha256,
                "includedCornerBackgroundPixelsSha256");
        generatedLoopSha256 = requireSha256(
                generatedLoopSha256, "generatedLoopSha256");
        algorithmRevision = requireText(algorithmRevision, "algorithmRevision");
        requirePositive(previewWidth, "previewWidth");
        requirePositive(previewHeight, "previewHeight");
        requirePositive(selectedComponentPixelCount,
                "selectedComponentPixelCount");
        requirePositive(filledEnvelopePixelCount,
                "filledEnvelopePixelCount");
        if (filledEnvelopePixelCount < selectedComponentPixelCount) {
            throw new IllegalArgumentException(
                    "A filled envelope cannot contain fewer pixels than its component");
        }
        requirePositive(outlinedEnvelopePixelCount,
                "outlinedEnvelopePixelCount");
        requireNonNegative(discardedDiagonalOnlyComponentCount,
                "discardedDiagonalOnlyComponentCount");
        requireNonNegative(discardedDiagonalOnlyPixelCount,
                "discardedDiagonalOnlyPixelCount");
        requireNonNegative(largestDiscardedDiagonalOnlyComponentPixelCount,
                "largestDiscardedDiagonalOnlyComponentPixelCount");
        requireNonNegative(
                largestDiscardedDiagonalOnlyEightConnectedSpanPixels,
                "largestDiscardedDiagonalOnlyEightConnectedSpanPixels");
        requireNonNegative(includedCornerBackgroundComponentCount,
                "includedCornerBackgroundComponentCount");
        requireNonNegative(includedCornerBackgroundPixelCount,
                "includedCornerBackgroundPixelCount");
        requireNonNegative(largestIncludedCornerBackgroundComponentPixelCount,
                "largestIncludedCornerBackgroundComponentPixelCount");
        requireNonNegative(
                largestIncludedCornerBackgroundEightConnectedSpanPixels,
                "largestIncludedCornerBackgroundEightConnectedSpanPixels");
        if (outlinedEnvelopePixelCount + discardedDiagonalOnlyPixelCount
                != filledEnvelopePixelCount
                        + includedCornerBackgroundPixelCount) {
            throw new IllegalArgumentException(
                    "Outlined pixels must equal retained envelope plus included corner background");
        }
        if ((includedCornerBackgroundComponentCount == 0)
                != (includedCornerBackgroundPixelCount == 0)
                || (includedCornerBackgroundPixelCount == 0)
                        != (largestIncludedCornerBackgroundComponentPixelCount
                                == 0)
                || largestIncludedCornerBackgroundComponentPixelCount
                        > includedCornerBackgroundPixelCount
                || (includedCornerBackgroundPixelCount == 0)
                        != (largestIncludedCornerBackgroundEightConnectedSpanPixels
                                == 0)
                || largestIncludedCornerBackgroundEightConnectedSpanPixels
                        > includedCornerBackgroundPixelCount) {
            throw new IllegalArgumentException(
                    "Included corner-background component counts are inconsistent");
        }
        if ((discardedDiagonalOnlyComponentCount == 0)
                != (discardedDiagonalOnlyPixelCount == 0)
                || (discardedDiagonalOnlyPixelCount == 0)
                        != (largestDiscardedDiagonalOnlyComponentPixelCount
                                == 0)
                || largestDiscardedDiagonalOnlyComponentPixelCount
                        > discardedDiagonalOnlyPixelCount
                || (discardedDiagonalOnlyPixelCount == 0)
                        != (largestDiscardedDiagonalOnlyEightConnectedSpanPixels
                                == 0)
                || largestDiscardedDiagonalOnlyEightConnectedSpanPixels
                        > discardedDiagonalOnlyPixelCount) {
            throw new IllegalArgumentException(
                    "Discarded diagonal-only component counts are inconsistent");
        }
        if (rawLoopVertexCount < 3 || generatedVertexCount < 3
                || generatedVertexCount > rawLoopVertexCount) {
            throw new IllegalArgumentException("Outline vertex counts are invalid");
        }
    }

    public AutomaticTissueOutlineProvenance markReviewerModified() {
        if (reviewerModified) {
            return this;
        }
        return new AutomaticTissueOutlineProvenance(
                sourceIdentity, copiedPreviewPixelSha256,
                segmentationMethod, segmentationPolarity,
                segmentationThreshold, inputMaskSha256,
                filledEnvelopeSha256, outlinedEnvelopeSha256,
                discardedDiagonalOnlyPixelsSha256,
                includedCornerBackgroundPixelsSha256, algorithmRevision,
                generatedLoopSha256, previewWidth, previewHeight,
                selectedComponentPixelCount, filledEnvelopePixelCount,
                outlinedEnvelopePixelCount,
                discardedDiagonalOnlyComponentCount,
                discardedDiagonalOnlyPixelCount,
                largestDiscardedDiagonalOnlyComponentPixelCount,
                largestDiscardedDiagonalOnlyEightConnectedSpanPixels,
                includedCornerBackgroundComponentCount,
                includedCornerBackgroundPixelCount,
                largestIncludedCornerBackgroundComponentPixelCount,
                largestIncludedCornerBackgroundEightConnectedSpanPixels,
                rawLoopVertexCount, generatedVertexCount, true);
    }

    private static String requireSha256(final String value, final String name) {
        final String hash = Objects.requireNonNull(value, name);
        if (!hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be a lowercase SHA-256 value");
        }
        return hash;
    }

    private static String requireText(final String value, final String name) {
        final String text = Objects.requireNonNull(value, name).trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    private static void requirePositive(final int value, final String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(final int value, final String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
