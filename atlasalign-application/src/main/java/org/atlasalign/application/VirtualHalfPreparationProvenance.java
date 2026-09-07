package org.atlasalign.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable, self-identifying provenance for an inference-only virtual half
 * input. The preparation identity is the SHA-256 of the frozen canonical
 * LF-terminated serialization.
 */
public record VirtualHalfPreparationProvenance(
        String algorithmRevision,
        VirtualHalfMode mode,
        String sourcePixelsSha256,
        String observedMaskSha256,
        String inferencePixelsSha256,
        String syntheticMaskSha256,
        int originalWidth,
        int originalHeight,
        int inferenceWidth,
        int inferenceHeight,
        int originalToInferenceOffsetX,
        int originalToInferenceOffsetY,
        double medialEdgeOriginal,
        double medialEdgeInference,
        VirtualHalfBackgroundStrategy backgroundStrategy,
        Optional<Float> backgroundValue,
        int backgroundSampleCount,
        SectionGeometry observedGeometry,
        String preparationIdentitySha256) {

    private static final Pattern LOWERCASE_SHA256 =
            Pattern.compile("[0-9a-f]{64}");

    /**
     * Creates provenance and computes its preparation identity from the
     * frozen serialization.
     */
    public VirtualHalfPreparationProvenance(
            final String algorithmRevision,
            final VirtualHalfMode mode,
            final String sourcePixelsSha256,
            final String observedMaskSha256,
            final String inferencePixelsSha256,
            final String syntheticMaskSha256,
            final int originalWidth,
            final int originalHeight,
            final int inferenceWidth,
            final int inferenceHeight,
            final int originalToInferenceOffsetX,
            final int originalToInferenceOffsetY,
            final double medialEdgeOriginal,
            final double medialEdgeInference,
            final VirtualHalfBackgroundStrategy backgroundStrategy,
            final Optional<Float> backgroundValue,
            final int backgroundSampleCount,
            final SectionGeometry observedGeometry) {
        this(
                algorithmRevision,
                mode,
                sourcePixelsSha256,
                observedMaskSha256,
                inferencePixelsSha256,
                syntheticMaskSha256,
                originalWidth,
                originalHeight,
                inferenceWidth,
                inferenceHeight,
                originalToInferenceOffsetX,
                originalToInferenceOffsetY,
                medialEdgeOriginal,
                medialEdgeInference,
                backgroundStrategy,
                backgroundValue,
                backgroundSampleCount,
                observedGeometry,
                sha256(identitySerialization(
                        algorithmRevision,
                        mode,
                        sourcePixelsSha256,
                        observedMaskSha256,
                        inferencePixelsSha256,
                        syntheticMaskSha256,
                        originalWidth,
                        originalHeight,
                        inferenceWidth,
                        inferenceHeight,
                        originalToInferenceOffsetX,
                        originalToInferenceOffsetY,
                        medialEdgeOriginal,
                        medialEdgeInference,
                        backgroundStrategy,
                        backgroundValue,
                        backgroundSampleCount,
                        observedGeometry)));
    }

    public VirtualHalfPreparationProvenance {
        algorithmRevision = Objects.requireNonNull(
                algorithmRevision, "algorithmRevision");
        mode = Objects.requireNonNull(mode, "mode");
        sourcePixelsSha256 = requireSha256(
                sourcePixelsSha256, "sourcePixelsSha256");
        observedMaskSha256 = requireSha256(
                observedMaskSha256, "observedMaskSha256");
        inferencePixelsSha256 = requireSha256(
                inferencePixelsSha256, "inferencePixelsSha256");
        syntheticMaskSha256 = requireSha256(
                syntheticMaskSha256, "syntheticMaskSha256");
        backgroundStrategy = Objects.requireNonNull(
                backgroundStrategy, "backgroundStrategy");
        backgroundValue = Objects.requireNonNull(
                backgroundValue, "backgroundValue");
        observedGeometry = Objects.requireNonNull(
                observedGeometry, "observedGeometry");
        preparationIdentitySha256 = requireSha256(
                preparationIdentitySha256, "preparationIdentitySha256");

        validateAlgorithmRevision(algorithmRevision);
        validateDimensions(originalWidth, originalHeight, "original");
        validateDimensions(inferenceWidth, inferenceHeight, "inference");
        validateExtentAndOffsets(
                mode,
                originalWidth,
                originalHeight,
                inferenceWidth,
                inferenceHeight,
                originalToInferenceOffsetX,
                originalToInferenceOffsetY);
        validateMedialEdges(
                medialEdgeOriginal,
                medialEdgeInference,
                originalWidth,
                inferenceWidth,
                originalToInferenceOffsetX);
        validateBackground(
                mode,
                backgroundStrategy,
                backgroundValue,
                backgroundSampleCount);
        if (observedGeometry != SectionGeometry.IMAGE_LEFT_HALF
                && observedGeometry != SectionGeometry.IMAGE_RIGHT_HALF) {
            throw new IllegalArgumentException(
                    "Virtual-half provenance requires an observed half geometry");
        }

        final String expectedIdentity = sha256(identitySerialization(
                algorithmRevision,
                mode,
                sourcePixelsSha256,
                observedMaskSha256,
                inferencePixelsSha256,
                syntheticMaskSha256,
                originalWidth,
                originalHeight,
                inferenceWidth,
                inferenceHeight,
                originalToInferenceOffsetX,
                originalToInferenceOffsetY,
                medialEdgeOriginal,
                medialEdgeInference,
                backgroundStrategy,
                backgroundValue,
                backgroundSampleCount,
                observedGeometry));
        if (!expectedIdentity.equals(preparationIdentitySha256)) {
            throw new IllegalArgumentException(
                    "Preparation identity does not match its frozen provenance");
        }
    }

    /**
     * Exact UTF-8 text input whose SHA-256 is
     * {@link #preparationIdentitySha256()}.
     */
    public String preparationIdentitySerialization() {
        return identitySerialization(
                algorithmRevision,
                mode,
                sourcePixelsSha256,
                observedMaskSha256,
                inferencePixelsSha256,
                syntheticMaskSha256,
                originalWidth,
                originalHeight,
                inferenceWidth,
                inferenceHeight,
                originalToInferenceOffsetX,
                originalToInferenceOffsetY,
                medialEdgeOriginal,
                medialEdgeInference,
                backgroundStrategy,
                backgroundValue,
                backgroundSampleCount,
                observedGeometry);
    }

    private static void validateAlgorithmRevision(final String value) {
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(
                    "Algorithm revision must be a nonblank canonical value");
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (character < 0x20 || character > 0x7e) {
                throw new IllegalArgumentException(
                        "Algorithm revision must use printable ASCII");
            }
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
                || (long) width * height > DeepSliceInput.MAX_PIXELS) {
            throw new IllegalArgumentException(
                    label + " dimensions exceed the shared DeepSlice pixel cap");
        }
    }

    private static void validateExtentAndOffsets(
            final VirtualHalfMode mode,
            final int originalWidth,
            final int originalHeight,
            final int inferenceWidth,
            final int inferenceHeight,
            final int offsetX,
            final int offsetY) {
        if (offsetX < 0 || offsetY < 0
                || (long) offsetX + originalWidth > inferenceWidth
                || (long) offsetY + originalHeight > inferenceHeight) {
            throw new IllegalArgumentException(
                    "Original raster does not fit at its inference offset");
        }
        if (mode == VirtualHalfMode.FULL_CANVAS) {
            if (originalWidth != inferenceWidth
                    || originalHeight != inferenceHeight
                    || offsetX != 0 || offsetY != 0) {
                throw new IllegalArgumentException(
                        "Full-canvas provenance must preserve dimensions and origin");
            }
        } else if (inferenceHeight != originalHeight || offsetY != 0
                || inferenceWidth < originalWidth) {
            throw new IllegalArgumentException(
                    "Tight-crop provenance must preserve its Y extent");
        }
    }

    private static void validateMedialEdges(
            final double medialEdgeOriginal,
            final double medialEdgeInference,
            final int originalWidth,
            final int inferenceWidth,
            final int offsetX) {
        if (!withinRaster(medialEdgeOriginal, originalWidth)
                || !withinRaster(medialEdgeInference, inferenceWidth)
                || Double.doubleToLongBits(medialEdgeOriginal + offsetX)
                != Double.doubleToLongBits(medialEdgeInference)) {
            throw new IllegalArgumentException(
                    "Medial-edge coordinates are not a finite offset pair");
        }
    }

    private static boolean withinRaster(
            final double coordinate, final int width) {
        return Double.isFinite(coordinate)
                && coordinate >= 0
                && coordinate <= width - 1;
    }

    private static void validateBackground(
            final VirtualHalfMode mode,
            final VirtualHalfBackgroundStrategy strategy,
            final Optional<Float> value,
            final int sampleCount) {
        final boolean notUsed = strategy
                == VirtualHalfBackgroundStrategy.NOT_USED;
        if (notUsed != value.isEmpty()
                || (notUsed && sampleCount != 0)
                || (!notUsed && (sampleCount <= 0
                || !Float.isFinite(value.orElseThrow())))) {
            throw new IllegalArgumentException(
                    "Virtual-half background provenance is inconsistent");
        }
        if ((mode == VirtualHalfMode.FULL_CANVAS) != notUsed) {
            throw new IllegalArgumentException(
                    "Background strategy does not match the virtual-half mode");
        }
    }

    private static String identitySerialization(
            final String algorithmRevision,
            final VirtualHalfMode mode,
            final String sourcePixelsSha256,
            final String observedMaskSha256,
            final String inferencePixelsSha256,
            final String syntheticMaskSha256,
            final int originalWidth,
            final int originalHeight,
            final int inferenceWidth,
            final int inferenceHeight,
            final int offsetX,
            final int offsetY,
            final double medialEdgeOriginal,
            final double medialEdgeInference,
            final VirtualHalfBackgroundStrategy backgroundStrategy,
            final Optional<Float> backgroundValue,
            final int backgroundSampleCount,
            final SectionGeometry observedGeometry) {
        final String background = backgroundValue.isPresent()
                ? Float.toHexString(backgroundValue.orElseThrow())
                : "NONE";
        return "algorithmRevision=" + algorithmRevision + '\n'
                + "mode=" + mode + '\n'
                + "sourcePixelsSha256=" + sourcePixelsSha256 + '\n'
                + "observedMaskSha256=" + observedMaskSha256 + '\n'
                + "inferencePixelsSha256=" + inferencePixelsSha256 + '\n'
                + "syntheticMaskSha256=" + syntheticMaskSha256 + '\n'
                + "originalWidth=" + originalWidth + '\n'
                + "originalHeight=" + originalHeight + '\n'
                + "inferenceWidth=" + inferenceWidth + '\n'
                + "inferenceHeight=" + inferenceHeight + '\n'
                + "offsetX=" + offsetX + '\n'
                + "offsetY=" + offsetY + '\n'
                + "medialEdgeOriginalHex="
                + Double.toHexString(medialEdgeOriginal) + '\n'
                + "medialEdgeInferenceHex="
                + Double.toHexString(medialEdgeInference) + '\n'
                + "backgroundStrategy=" + backgroundStrategy + '\n'
                + "backgroundValueHex-or-NONE=" + background + '\n'
                + "backgroundSampleCount=" + backgroundSampleCount + '\n'
                + "observedGeometry=" + observedGeometry + '\n';
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
