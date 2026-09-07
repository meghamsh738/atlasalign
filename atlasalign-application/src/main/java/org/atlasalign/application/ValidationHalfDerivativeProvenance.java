package org.atlasalign.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Immutable, self-identifying provenance for a controlled validation
 * half-section derivative. The control mask recorded here is deliberately
 * distinct from any production inference synthetic-pixel mask.
 */
public record ValidationHalfDerivativeProvenance(
        String algorithmRevision,
        ValidationHalfDerivativeCondition condition,
        SectionGeometry sourceGeometry,
        String sourcePixelsSha256,
        String fullSourceMaskSha256,
        String derivativePixelsSha256,
        String observedHalfMaskSha256,
        String controlMaskSha256,
        int sourceWidth,
        int sourceHeight,
        int derivativeWidth,
        int derivativeHeight,
        int sourceToDerivativeOffsetX,
        int sourceToDerivativeOffsetY,
        double medialEdgeSource,
        double medialEdgeDerivative,
        VirtualHalfBackgroundStrategy backgroundStrategy,
        float backgroundValue,
        int backgroundSampleCount,
        int fullSourceTissuePixelCount,
        int observedTissuePixelCount,
        int controlPixelCount,
        SectionGeometry observedGeometry,
        OptionalInt cropMinimumX,
        OptionalInt cropMinimumY,
        OptionalInt cropMaximumX,
        OptionalInt cropMaximumY,
        String derivativeIdentitySha256) {

    private static final Pattern LOWERCASE_SHA256 =
            Pattern.compile("[0-9a-f]{64}");

    /**
     * Creates provenance and computes the identity from the frozen canonical
     * LF-terminated serialization.
     */
    public ValidationHalfDerivativeProvenance(
            final String algorithmRevision,
            final ValidationHalfDerivativeCondition condition,
            final SectionGeometry sourceGeometry,
            final String sourcePixelsSha256,
            final String fullSourceMaskSha256,
            final String derivativePixelsSha256,
            final String observedHalfMaskSha256,
            final String controlMaskSha256,
            final int sourceWidth,
            final int sourceHeight,
            final int derivativeWidth,
            final int derivativeHeight,
            final int sourceToDerivativeOffsetX,
            final int sourceToDerivativeOffsetY,
            final double medialEdgeSource,
            final double medialEdgeDerivative,
            final VirtualHalfBackgroundStrategy backgroundStrategy,
            final float backgroundValue,
            final int backgroundSampleCount,
            final int fullSourceTissuePixelCount,
            final int observedTissuePixelCount,
            final int controlPixelCount,
            final SectionGeometry observedGeometry,
            final OptionalInt cropMinimumX,
            final OptionalInt cropMinimumY,
            final OptionalInt cropMaximumX,
            final OptionalInt cropMaximumY) {
        this(
                algorithmRevision,
                condition,
                sourceGeometry,
                sourcePixelsSha256,
                fullSourceMaskSha256,
                derivativePixelsSha256,
                observedHalfMaskSha256,
                controlMaskSha256,
                sourceWidth,
                sourceHeight,
                derivativeWidth,
                derivativeHeight,
                sourceToDerivativeOffsetX,
                sourceToDerivativeOffsetY,
                medialEdgeSource,
                medialEdgeDerivative,
                backgroundStrategy,
                backgroundValue,
                backgroundSampleCount,
                fullSourceTissuePixelCount,
                observedTissuePixelCount,
                controlPixelCount,
                observedGeometry,
                cropMinimumX,
                cropMinimumY,
                cropMaximumX,
                cropMaximumY,
                sha256(identitySerialization(
                        algorithmRevision,
                        condition,
                        sourceGeometry,
                        sourcePixelsSha256,
                        fullSourceMaskSha256,
                        derivativePixelsSha256,
                        observedHalfMaskSha256,
                        controlMaskSha256,
                        sourceWidth,
                        sourceHeight,
                        derivativeWidth,
                        derivativeHeight,
                        sourceToDerivativeOffsetX,
                        sourceToDerivativeOffsetY,
                        medialEdgeSource,
                        medialEdgeDerivative,
                        backgroundStrategy,
                        backgroundValue,
                        backgroundSampleCount,
                        fullSourceTissuePixelCount,
                        observedTissuePixelCount,
                        controlPixelCount,
                        observedGeometry,
                        cropMinimumX,
                        cropMinimumY,
                        cropMaximumX,
                        cropMaximumY)));
    }

    public ValidationHalfDerivativeProvenance {
        algorithmRevision = Objects.requireNonNull(
                algorithmRevision, "algorithmRevision");
        condition = Objects.requireNonNull(condition, "condition");
        sourceGeometry = Objects.requireNonNull(sourceGeometry, "sourceGeometry");
        sourcePixelsSha256 = requireSha256(
                sourcePixelsSha256, "sourcePixelsSha256");
        fullSourceMaskSha256 = requireSha256(
                fullSourceMaskSha256, "fullSourceMaskSha256");
        derivativePixelsSha256 = requireSha256(
                derivativePixelsSha256, "derivativePixelsSha256");
        observedHalfMaskSha256 = requireSha256(
                observedHalfMaskSha256, "observedHalfMaskSha256");
        controlMaskSha256 = requireSha256(
                controlMaskSha256, "controlMaskSha256");
        backgroundStrategy = Objects.requireNonNull(
                backgroundStrategy, "backgroundStrategy");
        observedGeometry = Objects.requireNonNull(
                observedGeometry, "observedGeometry");
        cropMinimumX = Objects.requireNonNull(cropMinimumX, "cropMinimumX");
        cropMinimumY = Objects.requireNonNull(cropMinimumY, "cropMinimumY");
        cropMaximumX = Objects.requireNonNull(cropMaximumX, "cropMaximumX");
        cropMaximumY = Objects.requireNonNull(cropMaximumY, "cropMaximumY");
        derivativeIdentitySha256 = requireSha256(
                derivativeIdentitySha256, "derivativeIdentitySha256");

        if (!ValidationHalfDerivativeBuilder.ALGORITHM_REVISION.equals(
                algorithmRevision)) {
            throw new IllegalArgumentException(
                    "Validation derivative provenance has an unexpected algorithm revision");
        }
        validateSourceGeometry(sourceGeometry);
        validateDimensions(sourceWidth, sourceHeight, "source");
        validateDimensions(derivativeWidth, derivativeHeight, "derivative");
        validateMedialEdges(
                medialEdgeSource,
                medialEdgeDerivative,
                sourceWidth,
                sourceToDerivativeOffsetX);
        validateBackground(
                backgroundStrategy, backgroundValue, backgroundSampleCount);
        validateCounts(
                sourceWidth,
                sourceHeight,
                derivativeWidth,
                derivativeHeight,
                fullSourceTissuePixelCount,
                observedTissuePixelCount,
                controlPixelCount);
        if (observedGeometry != condition.observedGeometry()) {
            throw new IllegalArgumentException(
                    "Observed derivative geometry does not match its condition");
        }
        validateConditionExtent(
                condition,
                sourceWidth,
                sourceHeight,
                derivativeWidth,
                derivativeHeight,
                sourceToDerivativeOffsetX,
                sourceToDerivativeOffsetY,
                cropMinimumX,
                cropMinimumY,
                cropMaximumX,
                cropMaximumY);

        final String expectedIdentity = sha256(identitySerialization(
                algorithmRevision,
                condition,
                sourceGeometry,
                sourcePixelsSha256,
                fullSourceMaskSha256,
                derivativePixelsSha256,
                observedHalfMaskSha256,
                controlMaskSha256,
                sourceWidth,
                sourceHeight,
                derivativeWidth,
                derivativeHeight,
                sourceToDerivativeOffsetX,
                sourceToDerivativeOffsetY,
                medialEdgeSource,
                medialEdgeDerivative,
                backgroundStrategy,
                backgroundValue,
                backgroundSampleCount,
                fullSourceTissuePixelCount,
                observedTissuePixelCount,
                controlPixelCount,
                observedGeometry,
                cropMinimumX,
                cropMinimumY,
                cropMaximumX,
                cropMaximumY));
        if (!expectedIdentity.equals(derivativeIdentitySha256)) {
            throw new IllegalArgumentException(
                    "Validation derivative identity does not match frozen provenance");
        }
    }

    /** Exact UTF-8 input whose SHA-256 is {@link #derivativeIdentitySha256()}. */
    public String derivativeIdentitySerialization() {
        return identitySerialization(
                algorithmRevision,
                condition,
                sourceGeometry,
                sourcePixelsSha256,
                fullSourceMaskSha256,
                derivativePixelsSha256,
                observedHalfMaskSha256,
                controlMaskSha256,
                sourceWidth,
                sourceHeight,
                derivativeWidth,
                derivativeHeight,
                sourceToDerivativeOffsetX,
                sourceToDerivativeOffsetY,
                medialEdgeSource,
                medialEdgeDerivative,
                backgroundStrategy,
                backgroundValue,
                backgroundSampleCount,
                fullSourceTissuePixelCount,
                observedTissuePixelCount,
                controlPixelCount,
                observedGeometry,
                cropMinimumX,
                cropMinimumY,
                cropMaximumX,
                cropMaximumY);
    }

    /** Alias for callers that refer to the provenance identity generically. */
    public String identitySha256() {
        return derivativeIdentitySha256;
    }

    /** Alias for callers that need the exact frozen serialization. */
    public String identitySerialization() {
        return derivativeIdentitySerialization();
    }

    private static void validateSourceGeometry(final SectionGeometry value) {
        if (value != SectionGeometry.FULL
                && value != SectionGeometry.BILATERAL_REVIEW_REQUIRED) {
            throw new IllegalArgumentException(
                    "Validation derivatives require a verified full source geometry");
        }
    }

    private static String requireSha256(
            final String value, final String fieldName) {
        final String required = Objects.requireNonNull(value, fieldName);
        if (!LOWERCASE_SHA256.matcher(required).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must be lower-case SHA-256 hex");
        }
        return required;
    }

    private static void validateDimensions(
            final int width, final int height, final String label) {
        if (width <= 0 || height <= 0
                || ValidationHalfDerivativeBuilder.exceedsPixelCap(
                        width, height)) {
            throw new IllegalArgumentException(
                    label + " dimensions exceed the shared pixel cap");
        }
    }

    private static void validateMedialEdges(
            final double source,
            final double derivative,
            final int sourceWidth,
            final int offsetX) {
        final double expectedSource = (sourceWidth - 1) / 2.0d;
        if (!Double.isFinite(source) || !Double.isFinite(derivative)
                || Double.doubleToLongBits(source)
                != Double.doubleToLongBits(expectedSource)
                || Double.doubleToLongBits(derivative)
                != Double.doubleToLongBits(source + offsetX)) {
            throw new IllegalArgumentException(
                    "Derivative medial-edge coordinates do not use the frozen source midline");
        }
    }

    private static void validateBackground(
            final VirtualHalfBackgroundStrategy strategy,
            final float value,
            final int sampleCount) {
        if ((strategy != VirtualHalfBackgroundStrategy.BORDER_NON_TISSUE
                && strategy != VirtualHalfBackgroundStrategy.ALL_NON_TISSUE)
                || !Float.isFinite(value)
                || sampleCount <= 0) {
            throw new IllegalArgumentException(
                    "Validation derivative background provenance is inconsistent");
        }
    }

    private static void validateCounts(
            final int sourceWidth,
            final int sourceHeight,
            final int derivativeWidth,
            final int derivativeHeight,
            final int fullSourceTissuePixelCount,
            final int observedTissuePixelCount,
            final int controlPixelCount) {
        final long sourcePixels = (long) sourceWidth * sourceHeight;
        final long derivativePixels = (long) derivativeWidth * derivativeHeight;
        if (fullSourceTissuePixelCount <= 0
                || fullSourceTissuePixelCount > sourcePixels
                || observedTissuePixelCount <= 0
                || observedTissuePixelCount > fullSourceTissuePixelCount
                || controlPixelCount < 0
                || controlPixelCount > derivativePixels) {
            throw new IllegalArgumentException(
                    "Validation derivative tissue or control counts are inconsistent");
        }
    }

    private static void validateConditionExtent(
            final ValidationHalfDerivativeCondition condition,
            final int sourceWidth,
            final int sourceHeight,
            final int derivativeWidth,
            final int derivativeHeight,
            final int offsetX,
            final int offsetY,
            final OptionalInt cropMinimumX,
            final OptionalInt cropMinimumY,
            final OptionalInt cropMaximumX,
            final OptionalInt cropMaximumY) {
        final boolean anyCrop = cropMinimumX.isPresent()
                || cropMinimumY.isPresent()
                || cropMaximumX.isPresent()
                || cropMaximumY.isPresent();
        final boolean allCrop = cropMinimumX.isPresent()
                && cropMinimumY.isPresent()
                && cropMaximumX.isPresent()
                && cropMaximumY.isPresent();
        if (!condition.isTightCrop()) {
            if (anyCrop || derivativeWidth != sourceWidth
                    || derivativeHeight != sourceHeight
                    || offsetX != 0 || offsetY != 0) {
                throw new IllegalArgumentException(
                        "Full-canvas derivative provenance must preserve full source geometry");
            }
            return;
        }
        if (!allCrop) {
            throw new IllegalArgumentException(
                    "Tight-crop derivative provenance requires all crop bounds");
        }
        final int minimumX = cropMinimumX.getAsInt();
        final int minimumY = cropMinimumY.getAsInt();
        final int maximumX = cropMaximumX.getAsInt();
        final int maximumY = cropMaximumY.getAsInt();
        if (minimumX < 0 || minimumY < 0
                || maximumX < minimumX || maximumY < minimumY
                || maximumX >= sourceWidth || maximumY >= sourceHeight
                || derivativeWidth != maximumX - minimumX + 1
                || derivativeHeight != maximumY - minimumY + 1
                || offsetX != -minimumX || offsetY != -minimumY) {
            throw new IllegalArgumentException(
                    "Tight-crop derivative provenance does not match its source crop");
        }
    }

    private static String identitySerialization(
            final String algorithmRevision,
            final ValidationHalfDerivativeCondition condition,
            final SectionGeometry sourceGeometry,
            final String sourcePixelsSha256,
            final String fullSourceMaskSha256,
            final String derivativePixelsSha256,
            final String observedHalfMaskSha256,
            final String controlMaskSha256,
            final int sourceWidth,
            final int sourceHeight,
            final int derivativeWidth,
            final int derivativeHeight,
            final int sourceToDerivativeOffsetX,
            final int sourceToDerivativeOffsetY,
            final double medialEdgeSource,
            final double medialEdgeDerivative,
            final VirtualHalfBackgroundStrategy backgroundStrategy,
            final float backgroundValue,
            final int backgroundSampleCount,
            final int fullSourceTissuePixelCount,
            final int observedTissuePixelCount,
            final int controlPixelCount,
            final SectionGeometry observedGeometry,
            final OptionalInt cropMinimumX,
            final OptionalInt cropMinimumY,
            final OptionalInt cropMaximumX,
            final OptionalInt cropMaximumY) {
        return "algorithmRevision=" + algorithmRevision + '\n'
                + "condition=" + condition.canonicalLabel() + '\n'
                + "sourceGeometry=" + sourceGeometry.name() + '\n'
                + "sourcePixelsSha256=" + sourcePixelsSha256 + '\n'
                + "fullSourceMaskSha256=" + fullSourceMaskSha256 + '\n'
                + "derivativePixelsSha256=" + derivativePixelsSha256 + '\n'
                + "observedHalfMaskSha256=" + observedHalfMaskSha256 + '\n'
                + "controlMaskSha256=" + controlMaskSha256 + '\n'
                + "sourceWidth=" + sourceWidth + '\n'
                + "sourceHeight=" + sourceHeight + '\n'
                + "derivativeWidth=" + derivativeWidth + '\n'
                + "derivativeHeight=" + derivativeHeight + '\n'
                + "sourceToDerivativeOffsetX="
                + sourceToDerivativeOffsetX + '\n'
                + "sourceToDerivativeOffsetY="
                + sourceToDerivativeOffsetY + '\n'
                + "medialEdgeSourceHex="
                + Double.toHexString(medialEdgeSource) + '\n'
                + "medialEdgeDerivativeHex="
                + Double.toHexString(medialEdgeDerivative) + '\n'
                + "backgroundStrategy=" + backgroundStrategy.name() + '\n'
                + "backgroundValueHex="
                + Float.toHexString(backgroundValue) + '\n'
                + "backgroundSampleCount=" + backgroundSampleCount + '\n'
                + "fullSourceTissuePixelCount="
                + fullSourceTissuePixelCount + '\n'
                + "observedTissuePixelCount="
                + observedTissuePixelCount + '\n'
                + "controlPixelCount=" + controlPixelCount + '\n'
                + "observedGeometry=" + observedGeometry.name() + '\n'
                + "cropMinimumX-or-NONE=" + cropValue(cropMinimumX) + '\n'
                + "cropMinimumY-or-NONE=" + cropValue(cropMinimumY) + '\n'
                + "cropMaximumX-or-NONE=" + cropValue(cropMaximumX) + '\n'
                + "cropMaximumY-or-NONE=" + cropValue(cropMaximumY) + '\n';
    }

    private static String cropValue(final OptionalInt value) {
        return value.isPresent() ? Integer.toString(value.getAsInt()) : "NONE";
    }

    private static String sha256(final String text) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    text.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by the JVM", error);
        }
    }
}
