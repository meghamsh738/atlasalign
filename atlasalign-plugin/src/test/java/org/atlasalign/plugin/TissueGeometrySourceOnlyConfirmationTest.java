package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ij.ImagePlus;
import ij.io.Opener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.atlasalign.application.SafeImageIntakeService;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.TissueGeometryClassifier;
import org.atlasalign.application.TissueSegmenter;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in source-only geometry confirmation. It never reads coordinate truth.
 */
@EnabledIfEnvironmentVariable(
        named = "ATLASALIGN_GEOMETRY_CONFIRMATION", matches = "1")
class TissueGeometrySourceOnlyConfirmationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, ExpectedSource> EXPECTED = expected();

    @Test
    void confirmsFrozenPcp2SourceGeometryWithoutCoordinateTruth()
            throws Exception {
        final Path sourceDirectory = requiredDirectory(
                "atlasalign.validation.geometrySourceDirectory");
        final Path inputDirectory = requiredDirectory(
                "atlasalign.validation.geometryInputDirectory");
        final Path output = requiredOutput(
                "atlasalign.validation.geometryOutput");
        assertTrue(Files.isDirectory(sourceDirectory),
                "Geometry source path must be a directory");
        assertTrue(Files.isDirectory(inputDirectory),
                "Geometry input path must be a directory");
        final Set<String> actualFiles;
        try (var paths = Files.list(sourceDirectory)) {
            actualFiles = paths
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
        }
        assertEquals(EXPECTED.keySet(), actualFiles,
                "Geometry source directory must contain only the frozen panel");
        final Set<String> expectedInputs = EXPECTED.values().stream()
                .map(ExpectedSource::inputFilename)
                .collect(Collectors.toUnmodifiableSet());
        final Set<String> actualInputs;
        try (var paths = Files.list(inputDirectory)) {
            actualInputs = paths
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
        }
        assertEquals(expectedInputs, actualInputs,
                "Geometry input directory must contain only the frozen panel");

        final ObjectNode result = MAPPER.createObjectNode();
        result.put("protocol", "phase5-pcp2-source-geometry-confirmation-v1");
        result.put("truthAccessed", false);
        result.put("coordinateScoringEligible", false);
        result.put("sourceDirectory", sourceDirectory.toString());
        result.put("inputDirectory", inputDirectory.toString());
        final ArrayNode cases = result.putArray("cases");

        int passed = 0;
        for (final var entry : EXPECTED.entrySet()) {
            final Path sourcePath = sourceDirectory.resolve(entry.getKey());
            final ExpectedSource expected = entry.getValue();
            final Path inputPath = inputDirectory.resolve(
                    expected.inputFilename());
            assertFalse(Files.isSymbolicLink(sourcePath),
                    "Source must not be a symbolic link: " + sourcePath);
            assertTrue(Files.isRegularFile(sourcePath),
                    "Source must be a regular file: " + sourcePath);
            assertFalse(Files.isWritable(sourcePath),
                    "Frozen source must be read-only: " + sourcePath);
            final byte[] sourceBytes = readFrozenBytes(sourcePath);
            final String fileHashBefore = sha256(sourceBytes);
            assertEquals(expected.sha256(), fileHashBefore,
                    "Frozen source hash differs: " + sourcePath);
            assertFalse(Files.isSymbolicLink(inputPath),
                    "Input must not be a symbolic link: " + inputPath);
            assertTrue(Files.isRegularFile(inputPath),
                    "Input must be a regular file: " + inputPath);
            assertFalse(Files.isWritable(inputPath),
                    "Frozen input must be read-only: " + inputPath);
            final byte[] inputBytes = readFrozenBytes(inputPath);
            final String inputHashBefore = sha256(inputBytes);
            assertEquals(expected.inputSha256(), inputHashBefore,
                    "Frozen input hash differs: " + inputPath);

            final ImagePlus source = new Opener().openTiff(
                    new ByteArrayInputStream(inputBytes),
                    expected.inputFilename());
            assertNotNull(source, "ImageJ could not open " + inputPath);
            final ObjectNode record = cases.addObject();
            record.put("image", entry.getKey());
            record.put("sourcePath", sourcePath.toString());
            record.put("sourceFileSha256Before", fileHashBefore);
            record.put("inputPath", inputPath.toString());
            record.put("inputFileSha256Before", inputHashBefore);
            record.put("adjudicatedClassification",
                    expected.geometry().name());
            try {
                final ImagePlusSourceImage readOnly =
                        new ImagePlusSourceImage(source);
                final SourceImageSnapshot pixelsBefore = readOnly.snapshot();
                final var safe = new SafeImageIntakeService()
                        .preparePreview(readOnly, 1, 2_048);
                final var segmentation = new TissueSegmenter().segment(
                        safe.preview().mapping().previewWidth(),
                        safe.preview().mapping().previewHeight(),
                        safe.preview().pixels());
                final var geometry = new TissueGeometryClassifier()
                        .classify(segmentation.mask());
                final SourceImageSnapshot pixelsAfter = readOnly.snapshot();
                assertEquals(pixelsBefore, pixelsAfter,
                        "Geometry confirmation modified " + entry.getKey());
                assertEquals(expected.geometry(), geometry.geometry(),
                        "Geometry differs from frozen source label: "
                                + entry.getKey());

                record.put("status", "PASS");
                record.put("previewWidth",
                        safe.preview().mapping().previewWidth());
                record.put("previewHeight",
                        safe.preview().mapping().previewHeight());
                record.put("segmentationMethod",
                        segmentation.method().name());
                record.put("segmentationPolarity",
                        segmentation.polarity().name());
                record.put("foregroundFraction",
                        segmentation.foregroundFraction());
                record.put("sectionClassification",
                        geometry.geometry().name());
                record.put("tissueAspectRatio",
                        geometry.widthToHeightRatio());
                record.put("foregroundToBoundsFraction",
                        geometry.foregroundToBoundsFraction());
                record.put("imageLeftEdgeDispersion",
                        geometry.imageLeftEdgeDispersion());
                record.put("imageRightEdgeDispersion",
                        geometry.imageRightEdgeDispersion());
                record.put("bilateralMirroredOverlap",
                        geometry.bilateralMirroredOverlap());
                record.put("hemisphereBalance",
                        geometry.hemisphereBalance());
                record.put("connectedComponentCount",
                        geometry.connectedComponentCount());
                record.put("substantialComponentCount",
                        geometry.substantialComponentCount());
                record.put("sourcePixelSha256Before",
                        pixelsBefore.pixelSha256());
                record.put("sourcePixelSha256After",
                        pixelsAfter.pixelSha256());
                record.put("sourceIntegrityVerified", true);
                passed++;
            } finally {
                source.close();
            }
            final String fileHashAfter = sha256(
                    readFrozenBytes(sourcePath));
            assertEquals(fileHashBefore, fileHashAfter,
                    "Source file changed during confirmation: " + sourcePath);
            record.put("sourceFileSha256After", fileHashAfter);
            final String inputHashAfter = sha256(
                    readFrozenBytes(inputPath));
            assertEquals(inputHashBefore, inputHashAfter,
                    "Input file changed during confirmation: " + inputPath);
            record.put("inputFileSha256After", inputHashAfter);
        }

        result.put("caseCount", EXPECTED.size());
        result.put("passCount", passed);
        result.put("failureCount", EXPECTED.size() - passed);
        writeDurableReadOnly(
                output,
                MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result) + System.lineSeparator());
        assertEquals(EXPECTED.size(), passed);
    }

    private static Map<String, ExpectedSource> expected() {
        final Map<String, ExpectedSource> result = new LinkedHashMap<>();
        result.put(
                "1261_PcP2_tTA_lacZ_Xgal_NR_s025.png",
                new ExpectedSource(
                        "220f467842ba414556a5531a7d27b87946ea3f4fcb4c4b29632a5246199ab8c9",
                        "1261_PcP2_tTA_lacZ_Xgal_NR_s025.tif",
                        "fca7c26c81ce4d392b8af2745442d8ffb83cc59d922513b60b025a598b4f68b1",
                        SectionGeometry.FULL));
        result.put(
                "1261_PcP2_tTA_lacZ_Xgal_NR_s101.png",
                new ExpectedSource(
                        "e1f090c66fc43f3ddc14a0a75a1a881eac4d6f6df910804b3a50d1e8a7124ef9",
                        "1261_PcP2_tTA_lacZ_Xgal_NR_s101.tif",
                        "994a089d41385096d9cd4b1291b236130e9cf4351067dc9b61257ed19f87b25d",
                        SectionGeometry.FULL));
        result.put(
                "1261_PcP2_tTA_lacZ_Xgal_NR_s164.png",
                new ExpectedSource(
                        "235fba049dd628c37b4c79c840218a5c622d693a955534587f35c3855966f006",
                        "1261_PcP2_tTA_lacZ_Xgal_NR_s164.tif",
                        "ed4035072a8abafccf96e2856c2fa7a146729f43dd6dc0c5e8b1895cd202ec55",
                        SectionGeometry.PARTIAL_OR_DAMAGED));
        return Collections.unmodifiableMap(result);
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] readFrozenBytes(final Path path)
            throws IOException {
        assertFalse(Files.isSymbolicLink(path),
                "Frozen input must not be a symlink: " + path);
        assertTrue(Files.isRegularFile(
                path, LinkOption.NOFOLLOW_LINKS),
                "Frozen input must be a regular file: " + path);
        final Set<OpenOption> options = Set.of(
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (FileChannel channel = FileChannel.open(path, options)) {
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

    private static void writeDurableReadOnly(
            final Path output,
            final String value) throws IOException {
        final Path temporary = output.getParent().resolve(
                "." + output.getFileName() + ".tmp");
        assertFalse(Files.exists(temporary, LinkOption.NOFOLLOW_LINKS),
                "Temporary evidence path already exists: " + temporary);
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final Set<OpenOption> options = Set.of(
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        final FileAttribute<Set<PosixFilePermission>> permissions =
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------"));
        try (FileChannel channel = FileChannel.open(
                temporary, options, permissions)) {
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        Files.setPosixFilePermissions(
                temporary,
                PosixFilePermissions.fromString("r--r--r--"));
        Files.move(
                temporary,
                output,
                StandardCopyOption.ATOMIC_MOVE);
        assertFalse(Files.isSymbolicLink(output),
                "Published evidence must not be a symlink: " + output);
        assertTrue(Files.isRegularFile(
                output, LinkOption.NOFOLLOW_LINKS),
                "Published evidence must be a regular file: " + output);
        assertArrayEquals(bytes, readFrozenBytes(output),
                "Published evidence differs from forced bytes");
        try (FileChannel channel = FileChannel.open(
                output, StandardOpenOption.READ)) {
            channel.force(true);
        }
        try (FileChannel directory = FileChannel.open(
                output.getParent(), StandardOpenOption.READ)) {
            directory.force(true);
        }
        assertFalse(Files.isWritable(output),
                "Evidence output must be read-only: " + output);
    }

    private static Path requiredDirectory(final String property)
            throws IOException {
        final Path configured = Path.of(requiredProperty(property))
                .toAbsolutePath().normalize();
        final Path real = configured.toRealPath();
        assertEquals(real, configured,
                "Input path must not traverse a symlink: " + configured);
        assertTrue(Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS),
                "Input path must be a directory: " + real);
        return real;
    }

    private static Path requiredOutput(final String property)
            throws IOException {
        final Path configured = Path.of(requiredProperty(property))
                .toAbsolutePath().normalize();
        final Path parent = configured.getParent().toRealPath();
        assertEquals(parent, configured.getParent(),
                "Output path must not traverse a symlink");
        assertTrue(Files.isDirectory(
                parent, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(configured, LinkOption.NOFOLLOW_LINKS),
                "Refusing to overwrite geometry confirmation evidence");
        return parent.resolve(configured.getFileName());
    }

    private static String requiredProperty(final String property) {
        final String value = System.getProperty(property);
        assertNotNull(value, "Missing system property: " + property);
        assertFalse(value.isBlank(), "Blank system property: " + property);
        return value;
    }

    private record ExpectedSource(
            String sha256,
            String inputFilename,
            String inputSha256,
            SectionGeometry geometry) {
    }
}
