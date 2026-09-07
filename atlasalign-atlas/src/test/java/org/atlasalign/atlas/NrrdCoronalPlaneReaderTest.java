package org.atlasalign.atlas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NrrdCoronalPlaneReaderTest {

    private static final int AXIS_0 = 3;
    private static final int AXIS_1 = 2;
    private static final int AXIS_2 = 2;

    @TempDir
    Path temporaryDirectory;

    private final NrrdCoronalPlaneReader reader =
            new NrrdCoronalPlaneReader(
                    AXIS_0, AXIS_1, AXIS_2);

    @Test
    void readsAxisZeroPlaneAndTransposesToCoronalRows()
            throws Exception {
        final Path path = writeNrrd(
                "unsigned short", "raw", 2, false);

        assertArrayEquals(
                new int[] {1, 101, 11, 111},
                reader.readUnsignedShortPlane(path, 1));
    }

    @Test
    void readsGzipUnsignedAnnotationPlane() throws Exception {
        final Path path = writeNrrd(
                "unsigned int", "gzip", 4, false);

        assertArrayEquals(
                new int[] {2, 102, 12, 112},
                reader.readUnsignedIntPlane(path, 2));
    }

    @Test
    void rejectsWrongTypeDimensionsAndTruncation() throws Exception {
        final Path wrongType = writeNrrd(
                "unsigned int", "raw", 4, false);
        assertThrows(
                AtlasCacheException.class,
                () -> reader.readUnsignedShortPlane(wrongType, 0));

        final Path wrongDimensions =
                temporaryDirectory.resolve("wrong-dimensions.nrrd");
        Files.write(
                wrongDimensions,
                ("NRRD0004\n"
                        + "type: unsigned short\n"
                        + "dimension: 3\n"
                        + "sizes: 4 2 2\n"
                        + "endian: little\n"
                        + "encoding: raw\n\n")
                        .getBytes(StandardCharsets.US_ASCII));
        assertThrows(
                AtlasCacheException.class,
                () -> reader.readUnsignedShortPlane(
                        wrongDimensions, 0));

        final Path truncated = writeNrrd(
                "unsigned short", "raw", 2, true);
        assertThrows(
                AtlasCacheException.class,
                () -> reader.readUnsignedShortPlane(truncated, 0));
    }

    @Test
    void planeOwnsDefensiveCopies() {
        final int[] template = {1, 2, 3, 4};
        final int[] annotation = {5, 6, 7, 8};
        final AtlasCoronalPlane plane =
                new AtlasCoronalPlane(
                        4, 2, 2, template, annotation);

        template[0] = 99;
        annotation[0] = 99;
        final int[] returned = plane.templateIntensity();
        returned[1] = 99;

        assertArrayEquals(
                new int[] {1, 2, 3, 4},
                plane.templateIntensity());
        assertArrayEquals(
                new int[] {5, 6, 7, 8},
                plane.annotationId());
    }

    @Test
    void annotationOnlyPlaneRejectsTemplateAccess() {
        final AtlasCoronalPlane plane = AtlasCoronalPlane.annotationOnly(
                1,
                2,
                2,
                new int[] {0, 1, 2, 3},
                AtlasPlaneGeometry.axisAligned(1, 2, 2, 2, 2));

        assertFalse(plane.hasTemplateIntensity());
        assertArrayEquals(new int[] {0, 1, 2, 3}, plane.annotationId());
        assertThrows(IllegalStateException.class, plane::templateIntensity);
    }

    private Path writeNrrd(
            final String type,
            final String encoding,
            final int bytesPerSample,
            final boolean truncate) throws IOException {
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int z = 0; z < AXIS_2; z++) {
            for (int y = 0; y < AXIS_1; y++) {
                for (int x = 0; x < AXIS_0; x++) {
                    final int value = x + 10 * y + 100 * z;
                    for (int index = 0;
                            index < bytesPerSample;
                            index++) {
                        raw.write((value >>> (8 * index)) & 0xff);
                    }
                }
            }
        }
        byte[] payload = raw.toByteArray();
        if (truncate) {
            payload = java.util.Arrays.copyOf(
                    payload, payload.length - 1);
        }
        if (encoding.equals("gzip")) {
            final ByteArrayOutputStream compressed =
                    new ByteArrayOutputStream();
            try (GZIPOutputStream gzip =
                    new GZIPOutputStream(compressed)) {
                gzip.write(payload);
            }
            payload = compressed.toByteArray();
        }
        final String header = "NRRD0004\n"
                + "# fixture\n"
                + "type: " + type + "\n"
                + "dimension: 3\n"
                + "sizes: 3 2 2\n"
                + "endian: little\n"
                + "encoding: " + encoding + "\n\n";
        final ByteArrayOutputStream file =
                new ByteArrayOutputStream();
        file.write(header.getBytes(StandardCharsets.US_ASCII));
        file.write(payload);
        final Path path = temporaryDirectory.resolve(
                type.replace(' ', '-') + "-" + encoding
                        + "-" + truncate + ".nrrd");
        Files.write(path, file.toByteArray());
        return path;
    }
}
