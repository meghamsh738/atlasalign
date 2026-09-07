package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class VirtualHalfPayloadHashesTest {

    @Test
    void hashesRawLittleEndianFloatBitsWithoutCanonicalizingNaNOrSignedZero() {
        final float rawNaN = Float.intBitsToFloat(0x7fc01234);
        final float[] pixels = new float[]{rawNaN, -0.0f, 1.0f};
        final byte[] expectedBytes = new byte[]{
                0x34, 0x12, (byte) 0xc0, 0x7f,
                0, 0, 0, (byte) 0x80,
                0, 0, (byte) 0x80, 0x3f};

        assertEquals(sha256(expectedBytes),
                VirtualHalfPayloadHashes.pixelsSha256(pixels));
    }

    @Test
    void hashesRowMajorZeroOneMaskBytes() {
        final BinaryMask mask = BinaryMask.fromBooleans(
                2, 2, new boolean[]{false, true, true, false});
        final String expected = sha256(new byte[]{0, 1, 1, 0});

        assertEquals(expected,
                VirtualHalfPayloadHashes.maskSha256(mask));
        assertEquals(expected,
                VirtualHalfPayloadHashes.syntheticMaskSha256(mask));
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
