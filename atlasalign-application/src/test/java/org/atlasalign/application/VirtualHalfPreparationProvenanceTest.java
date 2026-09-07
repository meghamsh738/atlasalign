package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class VirtualHalfPreparationProvenanceTest {

    @Test
    void computesTheExactFrozenLfTerminatedPreparationIdentity() {
        final String sourcePixels = sha256(new byte[]{0, 1, 0, 1});
        final String observedMask = sha256(new byte[]{1, 0, 1, 0});
        final String inferencePixels = sha256(new byte[]{0, 0, 1, 1});
        final String syntheticMask = sha256(new byte[]{0, 1, 1, 0});
        final VirtualHalfPreparationProvenance provenance =
                new VirtualHalfPreparationProvenance(
                        "virtual-half-r1",
                        VirtualHalfMode.FULL_CANVAS,
                        sourcePixels,
                        observedMask,
                        inferencePixels,
                        syntheticMask,
                        2,
                        2,
                        2,
                        2,
                        0,
                        0,
                        0.5,
                        0.5,
                        VirtualHalfBackgroundStrategy.NOT_USED,
                        Optional.empty(),
                        0,
                        SectionGeometry.IMAGE_LEFT_HALF);

        final String expected = "algorithmRevision=virtual-half-r1\n"
                + "mode=FULL_CANVAS\n"
                + "sourcePixelsSha256=" + sourcePixels + "\n"
                + "observedMaskSha256=" + observedMask + "\n"
                + "inferencePixelsSha256=" + inferencePixels + "\n"
                + "syntheticMaskSha256=" + syntheticMask + "\n"
                + "originalWidth=2\n"
                + "originalHeight=2\n"
                + "inferenceWidth=2\n"
                + "inferenceHeight=2\n"
                + "offsetX=0\n"
                + "offsetY=0\n"
                + "medialEdgeOriginalHex=0x1.0p-1\n"
                + "medialEdgeInferenceHex=0x1.0p-1\n"
                + "backgroundStrategy=NOT_USED\n"
                + "backgroundValueHex-or-NONE=NONE\n"
                + "backgroundSampleCount=0\n"
                + "observedGeometry=IMAGE_LEFT_HALF\n";

        assertEquals(expected, provenance.preparationIdentitySerialization());
        assertEquals(sha256(expected.getBytes(StandardCharsets.UTF_8)),
                provenance.preparationIdentitySha256());
        assertEquals(syntheticMask, provenance.syntheticMaskSha256());
    }

    @Test
    void validatesBackgroundAndModeInvariants() {
        final VirtualHalfPreparationProvenance validTight = tight(
                VirtualHalfBackgroundStrategy.BORDER_NON_TISSUE,
                Optional.of(1.25f), 2);

        assertEquals(Optional.of(1.25f), validTight.backgroundValue());
        assertEquals(2, validTight.backgroundSampleCount());
        assertThrows(IllegalArgumentException.class, () -> full(
                VirtualHalfBackgroundStrategy.NOT_USED,
                Optional.of(1f), 0));
        assertThrows(IllegalArgumentException.class, () -> full(
                VirtualHalfBackgroundStrategy.NOT_USED,
                Optional.empty(), 1));
        assertThrows(IllegalArgumentException.class, () -> tight(
                VirtualHalfBackgroundStrategy.BORDER_NON_TISSUE,
                Optional.empty(), 1));
        assertThrows(IllegalArgumentException.class, () -> tight(
                VirtualHalfBackgroundStrategy.ALL_NON_TISSUE,
                Optional.of(Float.NaN), 1));
        assertThrows(IllegalArgumentException.class, () -> tight(
                VirtualHalfBackgroundStrategy.BORDER_NON_TISSUE,
                Optional.of(1f), 0));
        assertThrows(IllegalArgumentException.class, () -> tight(
                VirtualHalfBackgroundStrategy.NOT_USED,
                Optional.empty(), 0));
    }

    @Test
    void rejectsInvalidHashesDimensionsOffsetsCoordinatesAndGeometry() {
        assertThrows(IllegalArgumentException.class, () ->
                new VirtualHalfPreparationProvenance(
                        "virtual-half-r1",
                        VirtualHalfMode.FULL_CANVAS,
                        "A".repeat(64),
                        "1".repeat(64),
                        "2".repeat(64),
                        "3".repeat(64),
                        2, 2, 2, 2, 0, 0, 0, 0,
                        VirtualHalfBackgroundStrategy.NOT_USED,
                        Optional.empty(), 0,
                        SectionGeometry.IMAGE_LEFT_HALF));
        assertThrows(IllegalArgumentException.class, () ->
                new VirtualHalfPreparationProvenance(
                        "virtual-half-r1",
                        VirtualHalfMode.FULL_CANVAS,
                        "0".repeat(64),
                        "1".repeat(64),
                        "2".repeat(64),
                        "3".repeat(64),
                        DeepSliceInput.MAX_PIXELS + 1, 1,
                        DeepSliceInput.MAX_PIXELS + 1, 1,
                        0, 0, 0, 0,
                        VirtualHalfBackgroundStrategy.NOT_USED,
                        Optional.empty(), 0,
                        SectionGeometry.IMAGE_LEFT_HALF));
        assertThrows(IllegalArgumentException.class, () ->
                new VirtualHalfPreparationProvenance(
                        "virtual-half-r1",
                        VirtualHalfMode.FULL_CANVAS,
                        "0".repeat(64),
                        "1".repeat(64),
                        "2".repeat(64),
                        "3".repeat(64),
                        2, 2, 2, 2, 1, 0, 0, 1,
                        VirtualHalfBackgroundStrategy.NOT_USED,
                        Optional.empty(), 0,
                        SectionGeometry.IMAGE_LEFT_HALF));
        assertThrows(IllegalArgumentException.class, () ->
                new VirtualHalfPreparationProvenance(
                        "virtual-half-r1",
                        VirtualHalfMode.FULL_CANVAS,
                        "0".repeat(64),
                        "1".repeat(64),
                        "2".repeat(64),
                        "3".repeat(64),
                        2, 2, 2, 2, 0, 0, 0.5, 0.25,
                        VirtualHalfBackgroundStrategy.NOT_USED,
                        Optional.empty(), 0,
                        SectionGeometry.IMAGE_LEFT_HALF));
        assertThrows(IllegalArgumentException.class, () ->
                new VirtualHalfPreparationProvenance(
                        "virtual-half-r1",
                        VirtualHalfMode.FULL_CANVAS,
                        "0".repeat(64),
                        "1".repeat(64),
                        "2".repeat(64),
                        "3".repeat(64),
                        2, 2, 2, 2, 0, 0, 0, 0,
                        VirtualHalfBackgroundStrategy.NOT_USED,
                        Optional.empty(), 0, SectionGeometry.FULL));
    }

    @Test
    void rejectsAProvidedIdentityThatDoesNotMatchTheCanonicalFields() {
        final VirtualHalfPreparationProvenance valid = full(
                VirtualHalfBackgroundStrategy.NOT_USED,
                Optional.empty(), 0);

        assertThrows(IllegalArgumentException.class, () ->
                new VirtualHalfPreparationProvenance(
                        valid.algorithmRevision(),
                        valid.mode(),
                        valid.sourcePixelsSha256(),
                        valid.observedMaskSha256(),
                        valid.inferencePixelsSha256(),
                        valid.syntheticMaskSha256(),
                        valid.originalWidth(),
                        valid.originalHeight(),
                        valid.inferenceWidth(),
                        valid.inferenceHeight(),
                        valid.originalToInferenceOffsetX(),
                        valid.originalToInferenceOffsetY(),
                        valid.medialEdgeOriginal(),
                        valid.medialEdgeInference(),
                        valid.backgroundStrategy(),
                        valid.backgroundValue(),
                        valid.backgroundSampleCount(),
                        valid.observedGeometry(),
                        "f".repeat(64)));
    }

    private static VirtualHalfPreparationProvenance full(
            final VirtualHalfBackgroundStrategy strategy,
            final Optional<Float> background,
            final int sampleCount) {
        return new VirtualHalfPreparationProvenance(
                "virtual-half-r1",
                VirtualHalfMode.FULL_CANVAS,
                "0".repeat(64),
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                2, 2, 2, 2, 0, 0, 0.5, 0.5,
                strategy, background, sampleCount,
                SectionGeometry.IMAGE_LEFT_HALF);
    }

    private static VirtualHalfPreparationProvenance tight(
            final VirtualHalfBackgroundStrategy strategy,
            final Optional<Float> background,
            final int sampleCount) {
        return new VirtualHalfPreparationProvenance(
                "virtual-half-r1",
                VirtualHalfMode.TIGHT_CROP,
                "0".repeat(64),
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                2, 2, 3, 2, 1, 0, 0.5, 1.5,
                strategy, background, sampleCount,
                SectionGeometry.IMAGE_LEFT_HALF);
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes));
        } catch (final NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }
}
