package org.atlasalign.application.c02;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Immutable finite intensity grid used only by the frozen C02 descriptor. */
public final class C02FeatureGrid {

    private final int width;
    private final int height;
    private final float[] intensity;
    private final String pixelSha256;

    public C02FeatureGrid(
            final int width,
            final int height,
            final float[] intensity) {
        Objects.requireNonNull(intensity, "intensity");
        if (width <= 0 || height <= 0
                || intensity.length != Math.multiplyExact(width, height)) {
            throw new IllegalArgumentException(
                    "C02 feature pixels must match positive dimensions");
        }
        for (final float value : intensity) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "C02 feature pixels must be finite");
            }
        }
        this.width = width;
        this.height = height;
        this.intensity = intensity.clone();
        this.pixelSha256 = hash(this.intensity);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public float[] intensity() {
        return intensity.clone();
    }

    public String pixelSha256() {
        return pixelSha256;
    }

    private static String hash(final float[] pixels) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final ByteBuffer buffer = ByteBuffer.allocate(
                    Float.BYTES * 1024).order(ByteOrder.BIG_ENDIAN);
            for (final float pixel : pixels) {
                if (buffer.remaining() < Float.BYTES) {
                    digest.update(buffer.array(), 0, buffer.position());
                    buffer.clear();
                }
                buffer.putInt(Float.floatToRawIntBits(pixel));
            }
            digest.update(buffer.array(), 0, buffer.position());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
