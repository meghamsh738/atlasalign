package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
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
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.BaselineRegistrationProposal;
import org.atlasalign.application.MaskRegistrationEngine;
import org.atlasalign.application.TissueGeometryClassifier;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.atlas.AtlasCoronalPlaneLoader;
import org.atlasalign.atlas.AtlasRepository;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.SimilarityTransform2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in capture of one engine's frozen registration-equivalence outputs.
 */
@EnabledIfEnvironmentVariable(
        named = "ATLASALIGN_REGISTRATION_EQUIVALENCE",
        matches = "1")
class RegistrationEquivalenceDevelopmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIXTURE_MANIFEST_SHA256 =
            "0731aaa797b301e6adf5cfa272eb8d8c09d1127f804fdf2c49ed16f079334e7f";
    private static final Pattern MANIFEST_LINE = Pattern.compile(
            "^([0-9a-f]{64})  ([A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*)$");
    private static final int MAXIMUM_COMPLETE_BOUNDARY_POINTS = 4_096;
    private static final List<String> REGISTRATION_CLASSES = List.of(
            "org.atlasalign.application.MaskRegistrationEngine",
            "org.atlasalign.application.MaskRegistrationEngine$Moments",
            "org.atlasalign.application.MaskRegistrationEngine$RegistrationObjective",
            "org.atlasalign.application.MaskRegistrationEngine$RegistrationObjective$BoundarySet",
            "org.atlasalign.application.NearestPointIndex",
            "org.atlasalign.application.MaskWarp",
            "org.atlasalign.application.RegistrationObjectiveMode",
            "org.atlasalign.application.BaselineRegistrationProposal");

    @Test
    void recordsFrozenDevelopmentTransformsWithoutTruthAccess()
            throws Exception {
        final Path fixtureRoot = requiredDirectory(
                "atlasalign.registration.fixtureRoot");
        final Path atlasCache = requiredDirectory(
                "atlasalign.registration.atlasCache");
        final Path output = requiredOutput(
                "atlasalign.registration.output");
        final Path fixtureManifest = fixtureRoot.resolve(
                "REGISTRATION_EQUIVALENCE_FIXTURES.sha256");
        assertTrue(!Files.isSymbolicLink(fixtureManifest));
        assertTrue(Files.isRegularFile(
                fixtureManifest, LinkOption.NOFOLLOW_LINKS));
        final byte[] fixtureManifestBytes =
                readFrozenBytes(fixtureManifest);
        assertEquals(
                FIXTURE_MANIFEST_SHA256,
                sha256(fixtureManifestBytes));
        final List<FrozenFixture> overlays = verifiedFixtures(
                fixtureRoot, fixtureManifestBytes);

        final AtlasCoronalPlane plane =
                new AtlasCoronalPlaneLoader().load(
                        new AtlasRepository()
                                .openAllenMouse25um(atlasCache),
                        264);
        final BinaryMask atlasMask =
                ReviewAlignmentCommand.atlasTissueMask(plane);
        final ObjectNode result = MAPPER.createObjectNode();
        result.put("protocol",
                "phase5-registration-equivalence-capture-v1");
        result.put("engineLabel", requiredText(
                "atlasalign.registration.engineLabel"));
        result.put("engineCommit", requiredText(
                "atlasalign.registration.engineCommit"));
        result.put("truthAccessed", false);
        result.put("fixtureRoot", fixtureRoot.toString());
        result.put("fixtureManifestSha256",
                FIXTURE_MANIFEST_SHA256);
        result.put("atlasLevel", 264);
        result.put("atlasWidth", atlasMask.width());
        result.put("atlasHeight", atlasMask.height());
        result.put("atlasForeground", atlasMask.foregroundCount());
        final ObjectNode classHashes =
                result.putObject("engineClassSha256");
        for (final String className : REGISTRATION_CLASSES) {
            classHashes.put(
                    className,
                    classSha256(className));
        }
        result.put(
                "captureHarnessClassSha256",
                classSha256(getClass().getName()));
        final ArrayNode cases = result.putArray("cases");

        for (final FrozenFixture fixture : overlays) {
            final Path overlay = fixture.path();
            final byte[] sourceBytes = readFrozenBytes(overlay);
            final String beforeSha = sha256(sourceBytes);
            assertEquals(fixture.sha256(), beforeSha);
            final BufferedImage image = ImageIO.read(
                    new ByteArrayInputStream(sourceBytes));
            assertNotNull(image,
                    "ImageIO could not decode " + overlay);
            final BinaryMask previewMask = decodeOverlay(image);
            final MaskRegistrationEngine engine =
                    new MaskRegistrationEngine();
            final TissueGeometryClassifier classifier =
                    new TissueGeometryClassifier();
            final BaselineRegistrationProposal proposal = engine.register(
                    atlasMask,
                    previewMask,
                    new AllenCoronalLevel(264),
                    classifier.classify(previewMask));
            final BaselineRegistrationProposal repeated = engine.register(
                    atlasMask,
                    previewMask,
                    new AllenCoronalLevel(264),
                    classifier.classify(previewMask));
            assertEquals(proposal, repeated,
                    "Registration was not deterministic for " + overlay);
            final int atlasCompleteBoundaryCount =
                    completeBoundaryCount(atlasMask);
            final int previewCompleteBoundaryCount =
                    completeBoundaryCount(previewMask);
            final String expectedMode =
                    atlasCompleteBoundaryCount
                                    > MAXIMUM_COMPLETE_BOUNDARY_POINTS
                            || previewCompleteBoundaryCount
                                    > MAXIMUM_COMPLETE_BOUNDARY_POINTS
                            ? "EXACT_FULL_RESOLUTION_EXTERIOR_BOUNDARY"
                            : "EXACT_FULL_RESOLUTION_COMPLETE_BOUNDARY";
            assertEquals(expectedMode, proposal.objectiveMode().name(),
                    "Objective mode did not match paired boundary counts");
            final ObjectNode record = cases.addObject();
            record.put("fixture",
                    overlay.getFileName().toString());
            record.put("fixtureSha256", beforeSha);
            record.put("previewWidth", previewMask.width());
            record.put("previewHeight", previewMask.height());
            record.put("previewForeground",
                    previewMask.foregroundCount());
            record.set("similarity", similarity(
                    proposal.similarity()));
            record.set("affine", affine(proposal.affine()));
            record.put("similarityDice",
                    proposal.similarityDice());
            record.put("affineDice", proposal.affineDice());
            record.put("objectiveMode",
                    proposal.objectiveMode().name());
            record.put("atlasCompleteBoundaryCount",
                    atlasCompleteBoundaryCount);
            record.put("previewCompleteBoundaryCount",
                    previewCompleteBoundaryCount);
            record.put("objectiveModeVerified", true);
            record.put("determinismRepeatCount", 2);
            record.put("transformDeterminismPassed", true);
            assertEquals(beforeSha, sha256(overlay),
                    "Registration modified " + overlay);
            record.put("sourceIntegrityPassed", true);
        }

        result.put("caseCount", overlays.size());
        writeDurableReadOnly(
                output,
                MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result)
                        + System.lineSeparator());
    }

    private static List<FrozenFixture> verifiedFixtures(
            final Path fixtureRoot,
            final byte[] manifestBytes) throws Exception {
        final List<FrozenFixture> overlays = new ArrayList<>();
        final Set<Path> declared = new HashSet<>();
        final List<String> lines = new String(
                manifestBytes, StandardCharsets.UTF_8)
                .lines()
                .toList();
        assertEquals(16, lines.size());
        for (final String line : lines) {
            final Matcher matcher = MANIFEST_LINE.matcher(line);
            assertTrue(matcher.matches(),
                    "Malformed fixture manifest line: " + line);
            final Path relative = Path.of(matcher.group(2));
            assertTrue(!relative.isAbsolute());
            assertEquals(relative, relative.normalize());
            final Path path = fixtureRoot.resolve(relative).normalize();
            assertTrue(path.startsWith(fixtureRoot));
            assertTrue(declared.add(path),
                    "Duplicate fixture manifest entry: " + relative);
            assertTrue(!Files.isSymbolicLink(path),
                    "Fixture must not be a symlink: " + relative);
            assertTrue(Files.isRegularFile(
                    path, LinkOption.NOFOLLOW_LINKS),
                    "Fixture must be a regular file: " + relative);
            assertEquals(matcher.group(1), sha256(path),
                    "Fixture checksum mismatch: " + relative);
            if (relative.getNameCount() == 2
                    && relative.getName(0).toString()
                            .equals("mask-overlays")
                    && relative.getFileName().toString()
                            .endsWith(".mask.png")) {
                overlays.add(new FrozenFixture(
                        path, matcher.group(1)));
            }
        }
        assertEquals(15, overlays.size());
        final Path overlayRoot = fixtureRoot.resolve("mask-overlays");
        final Set<Path> actual = new HashSet<>();
        try (var stream = Files.list(overlayRoot)) {
            for (final Path path : stream.toList()) {
                assertTrue(!Files.isSymbolicLink(path),
                        "Overlay directory contains a symlink: " + path);
                assertTrue(Files.isRegularFile(
                        path, LinkOption.NOFOLLOW_LINKS),
                        "Overlay directory contains a non-file: " + path);
                assertTrue(actual.add(path.normalize()));
            }
        }
        assertEquals(
                overlays.stream().map(FrozenFixture::path)
                        .collect(java.util.stream.Collectors.toSet()),
                actual);
        overlays.sort(java.util.Comparator.comparing(
                fixture -> fixture.path().getFileName().toString()));
        return List.copyOf(overlays);
    }

    private static int completeBoundaryCount(final BinaryMask mask) {
        int count = 0;
        for (int y = 0; y < mask.height(); y++) {
            for (int x = 0; x < mask.width(); x++) {
                if (mask.contains(x, y)
                        && (!mask.contains(x - 1, y)
                        || !mask.contains(x + 1, y)
                        || !mask.contains(x, y - 1)
                        || !mask.contains(x, y + 1))) {
                    count++;
                }
            }
        }
        return count;
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

    private static ObjectNode similarity(
            final SimilarityTransform2D transform) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put("scale", transform.scale());
        node.put("rotationRadians",
                transform.rotationRadians());
        node.put("translationX", transform.translationX());
        node.put("translationY", transform.translationY());
        node.set("affine", affine(transform.asAffine()));
        return node;
    }

    private static ObjectNode affine(
            final AffineTransform2D transform) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put("m00", transform.m00());
        node.put("m01", transform.m01());
        node.put("m02", transform.m02());
        node.put("m10", transform.m10());
        node.put("m11", transform.m11());
        node.put("m12", transform.m12());
        return node;
    }

    private static BinaryMask decodeOverlay(
            final BufferedImage image) {
        final boolean[] values = new boolean[
                Math.multiplyExact(
                        image.getWidth(), image.getHeight())];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final int rgb = image.getRGB(x, y);
                final int red = rgb >>> 16 & 0xff;
                final int green = rgb >>> 8 & 0xff;
                final int blue = rgb & 0xff;
                values[y * image.getWidth() + x] =
                        red == 255 && green == 255 && blue == 0
                                || red > green && green == blue;
            }
        }
        final BinaryMask mask = BinaryMask.fromBooleans(
                image.getWidth(), image.getHeight(), values);
        assertTrue(!mask.isEmpty());
        return mask;
    }

    private static Path requiredDirectory(
            final String property) throws IOException {
        final Path path = Path.of(requiredText(property))
                .toAbsolutePath()
                .normalize()
                .toRealPath();
        assertTrue(Files.isDirectory(path));
        return path;
    }

    private static Path requiredOutput(
            final String property) throws IOException {
        final Path path = Path.of(requiredText(property))
                .toAbsolutePath()
                .normalize();
        final Path parent = path.getParent().toRealPath();
        assertEquals(parent, path.getParent(),
                "Output path must not traverse a symlink");
        assertTrue(Files.isDirectory(
                parent, LinkOption.NOFOLLOW_LINKS));
        assertTrue(!Files.exists(path, LinkOption.NOFOLLOW_LINKS));
        return parent.resolve(path.getFileName());
    }

    private static String requiredText(
            final String property) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing system property: " + property);
        }
        return value;
    }

    private static String sha256(final Path path)
            throws IOException, NoSuchAlgorithmException {
        return sha256(readFrozenBytes(path));
    }

    private static String sha256(final byte[] bytes)
            throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(bytes));
    }

    private static byte[] readFrozenBytes(final Path path)
            throws IOException {
        assertTrue(!Files.isSymbolicLink(path),
                "Frozen input must not be a symlink: " + path);
        assertTrue(Files.isRegularFile(
                path, LinkOption.NOFOLLOW_LINKS),
                "Frozen input must be a regular file: " + path);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            final long size = channel.size();
            assertTrue(size <= Integer.MAX_VALUE,
                    "Frozen input is too large: " + path);
            final ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) {
                final int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
            }
            assertEquals(size, channel.size(),
                    "Frozen input changed while reading: " + path);
            assertEquals(size, buffer.position(),
                    "Frozen input was truncated while reading: " + path);
            return buffer.array();
        }
    }

    private static String classSha256(
            final String className)
            throws IOException, NoSuchAlgorithmException,
            ClassNotFoundException {
        final String resource = "/"
                + className.replace('.', '/') + ".class";
        final Class<?> type = Class.forName(className);
        try (InputStream input =
                type.getResourceAsStream(resource)) {
            assertNotNull(input,
                    "Missing loaded class bytes for "
                            + className);
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

    private record FrozenFixture(Path path, String sha256) {
    }
}
