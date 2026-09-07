package org.atlasalign.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.atlasalign.core.BinaryMask;

/**
 * Deterministic byte identities for inference-only virtual-half payloads.
 */
public final class VirtualHalfPayloadHashes {

    private VirtualHalfPayloadHashes() {
    }

    /**
     * SHA-256 of row-major raw IEEE-754 binary32 values, encoded
     * little-endian without any normalization or canonicalization.
     */
    public static String pixelsSha256(final float[] pixels) {
        Objects.requireNonNull(pixels, "pixels");
        final MessageDigest digest = sha256();
        for (final float pixel : pixels) {
            final int bits = Float.floatToRawIntBits(pixel);
            digest.update((byte) bits);
            digest.update((byte) (bits >>> 8));
            digest.update((byte) (bits >>> 16));
            digest.update((byte) (bits >>> 24));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * SHA-256 of row-major mask bytes, with false encoded as {@code 0x00}
     * and true encoded as {@code 0x01}.
     */
    public static String syntheticMaskSha256(final BinaryMask mask) {
        return maskSha256(mask);
    }

    /**
     * SHA-256 of row-major mask bytes, with false encoded as {@code 0x00}
     * and true encoded as {@code 0x01}.
     *
     * <p>The encoding is generic: callers must retain the semantic meaning of
     * a mask separately from its byte identity. In particular, a controlled
     * validation derivative mask is not a production synthetic-pixel mask.</p>
     */
    public static String maskSha256(final BinaryMask mask) {
        final BinaryMask source = Objects.requireNonNull(mask, "mask");
        final MessageDigest digest = sha256();
        for (int y = 0; y < source.height(); y++) {
            for (int x = 0; x < source.width(); x++) {
                digest.update((byte) (source.contains(x, y) ? 1 : 0));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by the JVM", error);
        }
    }
}
