package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.SimilarityTransform2D;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Opt-in, durable development evidence for the frozen registration fixture.
 */
class MaskRegistrationPerformanceDevelopmentTest {

    private static final long LIMIT_NANOSECONDS =
            10_000_000_000L;

    @Test
    void recordsWarmupAndThreeTimedRegistrations()
            throws Exception {
        final String outputProperty = System.getProperty(
                "atlasalign.registration.performanceOutput");
        Assumptions.assumeTrue(
                outputProperty != null && !outputProperty.isBlank(),
                "Set atlasalign.registration.performanceOutput "
                        + "to create durable performance evidence");
        final Path output = Path.of(outputProperty)
                .toAbsolutePath()
                .normalize();
        final Path outputParent = output.getParent().toRealPath();
        assertEquals(outputParent, output.getParent(),
                "Output path must not traverse a symlink");
        assertTrue(Files.isDirectory(
                outputParent, LinkOption.NOFOLLOW_LINKS));
        assertTrue(!Files.exists(
                output, LinkOption.NOFOLLOW_LINKS));

        final BinaryMask atlas = frozenFragmentedAtlasMask();
        final BinaryMask preview = frozenFullPreviewFrom(atlas);
        final BitSet atlasBefore = atlas.copyBits();
        final BitSet previewBefore = preview.copyBits();
        final TissueGeometryResult geometry =
                new TissueGeometryClassifier().classify(preview);
        final MaskRegistrationEngine engine =
                new MaskRegistrationEngine();

        engine.register(
                atlas,
                preview,
                new AllenCoronalLevel(264),
                geometry);

        final List<Long> elapsed = new ArrayList<>(3);
        final List<Double> affineDice = new ArrayList<>(3);
        final List<String> objectiveModes =
                new ArrayList<>(3);
        final List<SimilarityTransform2D> similarities =
                new ArrayList<>(3);
        final List<AffineTransform2D> affines =
                new ArrayList<>(3);
        for (int run = 0; run < 3; run++) {
            final long start = System.nanoTime();
            final BaselineRegistrationProposal proposal =
                    engine.register(
                            atlas,
                            preview,
                            new AllenCoronalLevel(264),
                            geometry);
            elapsed.add(System.nanoTime() - start);
            affineDice.add(proposal.affineDice());
            objectiveModes.add(
                    proposal.objectiveMode().name());
            similarities.add(proposal.similarity());
            affines.add(proposal.affine());
        }

        assertEquals(atlasBefore, atlas.copyBits());
        assertEquals(previewBefore, preview.copyBits());
        final List<Long> sorted = elapsed.stream()
                .sorted()
                .toList();
        for (final long duration : elapsed) {
            assertTrue(
                    duration < LIMIT_NANOSECONDS,
                    () -> "Registration took "
                            + duration / 1_000_000_000.0
                            + " seconds");
        }
        assertTrue(sorted.get(1) < LIMIT_NANOSECONDS);
        assertTrue(objectiveModes.stream().allMatch(
                RegistrationObjectiveMode
                        .EXACT_FULL_RESOLUTION_EXTERIOR_BOUNDARY
                        .name()::equals));
        assertTrue(similarities.stream().allMatch(
                similarities.get(0)::equals));
        assertTrue(affines.stream().allMatch(
                affines.get(0)::equals));
        assertTrue(affineDice.stream().allMatch(
                value -> Double.doubleToLongBits(value)
                        == Double.doubleToLongBits(
                                affineDice.get(0))));
        writeDurableReadOnly(
                output,
                json(
                        elapsed,
                        sorted.get(1),
                        affineDice,
                        objectiveModes,
                        similarities,
                        affines,
                        atlas,
                        preview));
    }

    private static void writeDurableReadOnly(
            final Path output,
            final String value) throws IOException {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        final FileAttribute<Set<PosixFilePermission>> permissions =
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("r--------"));
        try (FileChannel channel = FileChannel.open(
                output, options, permissions)) {
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        Files.setPosixFilePermissions(
                output, PosixFilePermissions.fromString("r--r--r--"));
        try (FileChannel channel = FileChannel.open(
                output, StandardOpenOption.READ)) {
            channel.force(true);
        }
        try (FileChannel directory = FileChannel.open(
                output.getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        }
    }

    private static String json(
            final List<Long> elapsed,
            final long median,
            final List<Double> affineDice,
            final List<String> objectiveModes,
            final List<SimilarityTransform2D> similarities,
            final List<AffineTransform2D> affines,
            final BinaryMask atlas,
            final BinaryMask preview)
            throws IOException, NoSuchAlgorithmException {
        return """
                {
                  "fixture": "frozen-fragmented-456x320-to-2048x1462",
                  "warmupRuns": 1,
                  "timedRuns": 3,
                  "elapsedNanoseconds": %s,
                  "medianNanoseconds": %d,
                  "limitNanosecondsExclusive": %d,
                  "affineDice": %s,
                  "objectiveModes": %s,
                  "similarityTransforms": %s,
                  "affineTransforms": %s,
                  "atlasDimensions": [%d, %d],
                  "atlasForeground": %d,
                  "previewDimensions": [%d, %d],
                  "previewForeground": %d,
                  "sourceIntegrityPassed": true,
                  "transformDeterminismPassed": true,
                  "productionClassSha256": {
                    "MaskRegistrationEngine": "%s",
                    "NearestPointIndex": "%s",
                    "MaskWarp": "%s"
                  },
                  "captureHarnessClassSha256": "%s",
                  "passed": true,
                  "javaVersion": "%s",
                  "osName": "%s",
                  "osVersion": "%s",
                  "osArch": "%s"
                }
                """.formatted(
                elapsed,
                median,
                LIMIT_NANOSECONDS,
                affineDice,
                objectiveModes.stream()
                        .map(value -> "\"" + value + "\"")
                        .toList(),
                similarities.stream()
                        .map(MaskRegistrationPerformanceDevelopmentTest
                                ::similarityJson)
                        .collect(Collectors.joining(
                                ", ", "[", "]")),
                affines.stream()
                        .map(MaskRegistrationPerformanceDevelopmentTest
                                ::affineJson)
                        .collect(Collectors.joining(
                                ", ", "[", "]")),
                atlas.width(),
                atlas.height(),
                atlas.foregroundCount(),
                preview.width(),
                preview.height(),
                preview.foregroundCount(),
                classSha256(MaskRegistrationEngine.class),
                classSha256(NearestPointIndex.class),
                classSha256(MaskWarp.class),
                classSha256(
                        MaskRegistrationPerformanceDevelopmentTest
                                .class),
                jsonString(System.getProperty("java.version")),
                jsonString(System.getProperty("os.name")),
                jsonString(System.getProperty("os.version")),
                jsonString(System.getProperty("os.arch")));
    }

    private static String classSha256(
            final Class<?> type)
            throws IOException, NoSuchAlgorithmException {
        final String resource = "/"
                + type.getName().replace('.', '/') + ".class";
        try (InputStream input =
                type.getResourceAsStream(resource)) {
            assertTrue(input != null,
                    "Missing class bytes for " + type.getName());
            final MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[64 * 1_024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(
                    digest.digest());
        }
    }

    private static String similarityJson(
            final SimilarityTransform2D transform) {
        return ("{\"scale\":%s,\"rotationRadians\":%s,"
                + "\"translationX\":%s,\"translationY\":%s}")
                .formatted(
                transform.scale(),
                transform.rotationRadians(),
                transform.translationX(),
                transform.translationY());
    }

    private static String affineJson(
            final AffineTransform2D transform) {
        return ("{\"m00\":%s,\"m01\":%s,\"m02\":%s,"
                + "\"m10\":%s,\"m11\":%s,\"m12\":%s}")
                .formatted(
                transform.m00(),
                transform.m01(),
                transform.m02(),
                transform.m10(),
                transform.m11(),
                transform.m12());
    }

    private static String jsonString(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static BinaryMask frozenFragmentedAtlasMask() {
        final int width = 456;
        final int height = 320;
        final boolean[] values = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final double normalizedX =
                        (x - 227.5) / 190;
                final double normalizedY =
                        (y - 159.5) / 130;
                values[y * width + x] =
                        normalizedX * normalizedX
                                + normalizedY * normalizedY <= 1
                                && (y % 4 < 2
                                || Math.abs(x - 227.5) < 8);
            }
        }
        return BinaryMask.fromBooleans(width, height, values);
    }

    private static BinaryMask frozenFullPreviewFrom(
            final BinaryMask atlas) {
        final int width = 2_048;
        final int height = 1_462;
        final boolean[] values = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int atlasX = (int) Math.round(
                        (x + 0.5) * atlas.width() / width - 0.5);
                final int atlasY = (int) Math.round(
                        (y + 0.5) * atlas.height() / height - 0.5);
                values[y * width + x] =
                        atlas.contains(atlasX, atlasY);
            }
        }
        return BinaryMask.fromBooleans(width, height, values);
    }
}
