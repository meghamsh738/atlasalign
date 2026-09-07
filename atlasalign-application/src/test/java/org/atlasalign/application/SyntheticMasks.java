package org.atlasalign.application;

import java.util.function.BiPredicate;
import org.atlasalign.core.BinaryMask;

final class SyntheticMasks {

    static final int WIDTH = 128;
    static final int HEIGHT = 96;

    private SyntheticMasks() {
    }

    static BinaryMask fullSection() {
        return create(WIDTH, HEIGHT, (x, y) -> {
            final boolean main = ellipse(x, y, 64, 48, 48, 34);
            final boolean upperLobe = ellipse(x, y, 23, 33, 14, 11);
            final boolean notch = ellipse(x, y, 86, 31, 8, 6);
            return (main || upperLobe) && !notch;
        });
    }

    static BinaryMask imageLeftHalf() {
        final BinaryMask full = fullSection();
        return create(
                WIDTH,
                HEIGHT,
                (x, y) -> x <= 64 && full.contains(x, y));
    }

    static BinaryMask imageRightHalf() {
        final BinaryMask full = fullSection();
        return create(
                WIDTH,
                HEIGHT,
                (x, y) -> x >= 64 && full.contains(x, y));
    }

    static BinaryMask damagedSection() {
        final BinaryMask full = fullSection();
        return create(
                WIDTH,
                HEIGHT,
                (x, y) -> full.contains(x, y)
                        && !(x > 71 && y > 44)
                        && !(x < 38 && y < 35));
    }

    static BinaryMask fullSectionWithBilateralSignalVoids() {
        final BinaryMask full = fullSection();
        return create(
                WIDTH,
                HEIGHT,
                (x, y) -> full.contains(x, y)
                        && !ellipse(x, y, 40, 50, 11, 17)
                        && !ellipse(x, y, 81, 50, 11, 17));
    }

    static BinaryMask sectionWithUnilateralSignalLoss() {
        final BinaryMask full = fullSection();
        return create(
                WIDTH,
                HEIGHT,
                (x, y) -> full.contains(x, y)
                        && !ellipse(x, y, 43, 50, 16, 22));
    }

    static BinaryMask paddedFullSection() {
        final BinaryMask full = fullSection();
        return create(
                192,
                144,
                (x, y) -> full.contains(x - 32, y - 24));
    }

    static BinaryMask denseWavySection(
            final int boundsWidth,
            final int edgeAmplitude) {
        final int height = 100;
        return create(boundsWidth, height, (x, y) -> {
            if (y == 0 || y == height - 1) {
                return true;
            }
            final int wave = y % 2 == 0
                    ? edgeAmplitude : -edgeAmplitude;
            final int left = 10 + wave;
            final int right = boundsWidth - 11 + wave;
            return x >= left && x <= right;
        });
    }

    static BinaryMask denseAsymmetricEdgeSection() {
        final int width = 160;
        final int height = 100;
        return create(width, height, (x, y) -> {
            if (y == 0 || y == height - 1) {
                return true;
            }
            final int leftWave = y % 2 == 0 ? 3 : -3;
            final int rightWave = y % 2 == 0 ? 6 : -6;
            final int left = 10 + leftWave;
            final int right = width - 11 + rightWave;
            return x >= left && x <= right;
        });
    }

    static BinaryMask denseDisconnectedLowDispersionSection() {
        final int width = 160;
        final int height = 100;
        return create(width, height, (x, y) -> {
            final int wave = y % 2 == 0 ? 4 : -4;
            final int left = 10 + wave;
            final int right = width - 11 - wave;
            return x >= left && x <= 77
                    || x >= 82 && x <= right;
        });
    }

    static float[] image(
            final BinaryMask tissue,
            final float tissueValue,
            final float backgroundValue) {
        final float[] result =
                new float[tissue.width() * tissue.height()];
        for (int y = 0; y < tissue.height(); y++) {
            for (int x = 0; x < tissue.width(); x++) {
                final int index = y * tissue.width() + x;
                final float deterministicNoise =
                        (float) (((x * 17 + y * 31) % 7) - 3) * 0.1f;
                result[index] = (tissue.contains(x, y)
                        ? tissueValue : backgroundValue)
                        + deterministicNoise;
            }
        }
        return result;
    }

    static float[] frozenFixture(final String name) {
        final float[] result = new float[WIDTH * HEIGHT];
        final BinaryMask full = fullSection();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                final int index = y * WIDTH + x;
                final float noise = (float) (0.1
                        * (((17 * x + 31 * y) % 7) - 3));
                result[index] = switch (name) {
                    case "constant" -> 50;
                    case "near-constant" -> (float) (50 + 0.001
                            * (((17 * x + 31 * y) % 7) - 3));
                    case "linear" -> (float) (20 + 80.0 * x / 127);
                    case "vignette" -> (float) (30 + 140
                            * Math.min(1, Math.sqrt(
                                    Math.pow((x - 20.0) / 70, 2)
                                            + Math.pow(
                                                    (y - 47.5) / 50,
                                                    2))));
                    case "scanner-shadow" -> x < 8 ? 20 : 220;
                    case "dust" -> inBlock(x, y, 20, 20)
                            || inBlock(x, y, 60, 30)
                            || inBlock(x, y, 100, 70)
                                    ? 20 : 220;
                    case "whole-frame" -> x < 8 && y < 8 ? 220 : 20;
                    case "left-half" -> full.contains(x, y) && x <= 64
                            ? 20 + noise : 230 + noise;
                    case "right-half" -> full.contains(x, y) && x >= 64
                            ? 20 + noise : 230 + noise;
                    case "unilateral-loss" -> full.contains(x, y)
                            && !ellipse(x, y, 43, 50, 16, 22)
                                    ? 20 + noise : 230 + noise;
                    case "damaged-multi-piece" ->
                            ellipse(x, y, 35, 35, 16, 12)
                                    || ellipse(x, y, 92, 35, 14, 10)
                                    || ellipse(x, y, 38, 68, 13, 9)
                                    || ellipse(x, y, 90, 66, 12, 8)
                                            ? 20 + noise : 230 + noise;
                    default -> throw new IllegalArgumentException(
                            "Unknown frozen fixture: " + name);
                };
            }
        }
        return result;
    }

    private static boolean inBlock(
            final int x,
            final int y,
            final int originX,
            final int originY) {
        return x >= originX && x <= originX + 2
                && y >= originY && y <= originY + 2;
    }

    static BinaryMask create(
            final int width,
            final int height,
            final BiPredicate<Integer, Integer> predicate) {
        final boolean[] pixels = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y * width + x] = predicate.test(x, y);
            }
        }
        return BinaryMask.fromBooleans(width, height, pixels);
    }

    private static boolean ellipse(
            final int x,
            final int y,
            final double centerX,
            final double centerY,
            final double radiusX,
            final double radiusY) {
        final double normalizedX = (x - centerX) / radiusX;
        final double normalizedY = (y - centerY) / radiusY;
        return normalizedX * normalizedX
                + normalizedY * normalizedY <= 1;
    }
}
