package org.atlasalign.application.c02;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.atlasalign.core.BinaryMask;

/** Immutable row-major C02 descriptors and their raw-binary64 identity. */
public final class C02MindDescriptorGrid {

    public static final int COMPONENT_COUNT = 4;

    private final int width;
    private final int height;
    private final BinaryMask eligibleCenters;
    private final int completeObservedPixelCount;
    private final double[] components;
    private final String descriptorSha256;

    C02MindDescriptorGrid(
            final int width,
            final int height,
            final BinaryMask eligibleCenters,
            final int completeObservedPixelCount,
            final double[] components) {
        Objects.requireNonNull(eligibleCenters, "eligibleCenters");
        Objects.requireNonNull(components, "components");
        if (width <= 0 || height <= 0
                || eligibleCenters.width() != width
                || eligibleCenters.height() != height) {
            throw new IllegalArgumentException(
                    "C02 descriptor dimensions must be positive and match its mask");
        }
        if (completeObservedPixelCount <= 0
                || eligibleCenters.foregroundCount()
                        > completeObservedPixelCount
                || components.length != Math.multiplyExact(
                        eligibleCenters.foregroundCount(), COMPONENT_COUNT)) {
            throw new IllegalArgumentException(
                    "C02 descriptor counts and components are inconsistent");
        }
        for (final double value : components) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException(
                        "C02 descriptor components must be finite values in 0..1");
            }
        }
        this.width = width;
        this.height = height;
        this.eligibleCenters = BinaryMask.fromBitSet(
                width, height, eligibleCenters.copyBits());
        this.completeObservedPixelCount = completeObservedPixelCount;
        this.components = components.clone();
        this.descriptorSha256 = hash(this.components);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public BinaryMask eligibleCenters() {
        return eligibleCenters;
    }

    public int eligibleCenterCount() {
        return eligibleCenters.foregroundCount();
    }

    public int completeObservedPixelCount() {
        return completeObservedPixelCount;
    }

    /** Four components per eligible center, in row-major center order. */
    public double[] components() {
        return components.clone();
    }

    public String descriptorSha256() {
        return descriptorSha256;
    }

    private static String hash(final double[] values) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final ByteBuffer buffer = ByteBuffer.allocate(
                    Double.BYTES * 512).order(ByteOrder.BIG_ENDIAN);
            for (final double value : values) {
                if (buffer.remaining() < Double.BYTES) {
                    digest.update(buffer.array(), 0, buffer.position());
                    buffer.clear();
                }
                buffer.putLong(Double.doubleToRawLongBits(value));
            }
            digest.update(buffer.array(), 0, buffer.position());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
