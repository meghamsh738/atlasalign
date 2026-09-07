package org.atlasalign.atlas;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Strict streaming reader for an axis-0 plane in an inline NRRD volume.
 */
final class NrrdCoronalPlaneReader {

    private static final int MAXIMUM_HEADER_LINE_BYTES = 16_384;
    private static final Set<String> UNSIGNED_SHORT_TYPES =
            Set.of("unsigned short", "ushort", "uint16", "uint16_t");
    private static final Set<String> UNSIGNED_INT_TYPES =
            Set.of("unsigned int", "uint", "uint32", "uint32_t");

    private final int axis0;
    private final int axis1;
    private final int axis2;

    NrrdCoronalPlaneReader(
            final int axis0,
            final int axis1,
            final int axis2) {
        if (axis0 <= 0 || axis1 <= 0 || axis2 <= 0) {
            throw new IllegalArgumentException(
                    "Expected NRRD dimensions must be positive");
        }
        this.axis0 = axis0;
        this.axis1 = axis1;
        this.axis2 = axis2;
    }

    int[] readUnsignedShortPlane(
            final Path path,
            final int axis0Index) {
        return read(path, axis0Index, 2, UNSIGNED_SHORT_TYPES);
    }

    int[] readUnsignedIntPlane(
            final Path path,
            final int axis0Index) {
        return read(path, axis0Index, 4, UNSIGNED_INT_TYPES);
    }

    /** Reads the complete verified unsigned-short volume in axis order. */
    short[] readUnsignedShortVolume(final Path path) {
        try (InputStream file = new BufferedInputStream(
                Files.newInputStream(path))) {
            final Map<String, String> fields = readHeader(file);
            validateHeader(fields, UNSIGNED_SHORT_TYPES);
            return readShortVolume(payload(fields, file));
        } catch (final IOException | ArithmeticException error) {
            throw new AtlasCacheException(
                    "Could not read atlas NRRD volume from " + path,
                    error);
        }
    }

    /** Reads the complete verified unsigned-int volume in axis order. */
    int[] readUnsignedIntVolume(final Path path) {
        try (InputStream file = new BufferedInputStream(
                Files.newInputStream(path))) {
            final Map<String, String> fields = readHeader(file);
            validateHeader(fields, UNSIGNED_INT_TYPES);
            return readIntVolume(payload(fields, file));
        } catch (final IOException | ArithmeticException error) {
            throw new AtlasCacheException(
                    "Could not read atlas NRRD volume from " + path,
                    error);
        }
    }

    private int[] read(
            final Path path,
            final int axis0Index,
            final int bytesPerSample,
            final Set<String> acceptedTypes) {
        if (axis0Index < 0 || axis0Index >= axis0) {
            throw new IllegalArgumentException(
                    "Coronal level is outside the atlas volume");
        }
        try (InputStream file = new BufferedInputStream(
                Files.newInputStream(path))) {
            final Map<String, String> fields = readHeader(file);
            validateHeader(fields, acceptedTypes);
            final String encoding = fields.get("encoding")
                    .toLowerCase(Locale.ROOT);
            final InputStream payload = payload(fields, file);
            return readPayload(
                    payload, axis0Index, bytesPerSample);
        } catch (final IOException | ArithmeticException error) {
            throw new AtlasCacheException(
                    "Could not read atlas NRRD plane from " + path,
                    error);
        }
    }

    private InputStream payload(
            final Map<String, String> fields,
            final InputStream file) throws IOException {
        final String encoding = fields.get("encoding")
                .toLowerCase(Locale.ROOT);
        return encoding.equals("gzip") || encoding.equals("gz")
                ? new GZIPInputStream(file, 64 * 1024)
                : file;
    }

    private short[] readShortVolume(
            final InputStream input) throws IOException {
        final short[] volume = new short[Math.multiplyExact(
                Math.multiplyExact(axis0, axis1), axis2)];
        final byte[] row = new byte[Math.multiplyExact(axis0, 2)];
        int offset = 0;
        for (int z = 0; z < axis2; z++) {
            for (int y = 0; y < axis1; y++) {
                readFully(input, row);
                for (int x = 0; x < axis0; x++) {
                    final int sample = x * 2;
                    volume[offset++] = (short) ((row[sample] & 0xff)
                            | ((row[sample + 1] & 0xff) << 8));
                }
            }
        }
        requireNoTrailingData(input);
        return volume;
    }

    private int[] readIntVolume(
            final InputStream input) throws IOException {
        final int[] volume = new int[Math.multiplyExact(
                Math.multiplyExact(axis0, axis1), axis2)];
        final byte[] row = new byte[Math.multiplyExact(axis0, 4)];
        int offset = 0;
        for (int z = 0; z < axis2; z++) {
            for (int y = 0; y < axis1; y++) {
                readFully(input, row);
                for (int x = 0; x < axis0; x++) {
                    final int sample = x * 4;
                    final long unsigned = (long) (row[sample] & 0xff)
                            | ((long) (row[sample + 1] & 0xff) << 8)
                            | ((long) (row[sample + 2] & 0xff) << 16)
                            | ((long) (row[sample + 3] & 0xff) << 24);
                    if (unsigned > Integer.MAX_VALUE) {
                        throw new AtlasCacheException(
                                "Atlas annotation ID exceeds signed integer range");
                    }
                    volume[offset++] = (int) unsigned;
                }
            }
        }
        requireNoTrailingData(input);
        return volume;
    }

    private Map<String, String> readHeader(
            final InputStream input) throws IOException {
        final String magic = readLine(input);
        if (magic == null || !magic.matches("NRRD000[1-5]")) {
            throw new AtlasCacheException(
                    "Atlas volume has invalid NRRD magic");
        }
        final Map<String, String> fields = new HashMap<>();
        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            if (line.startsWith("#")) {
                continue;
            }
            final int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new AtlasCacheException(
                        "Atlas NRRD header contains an invalid field");
            }
            final String name = line.substring(0, separator)
                    .trim().toLowerCase(Locale.ROOT);
            final String value = line.substring(separator + 1).trim();
            if (value.isEmpty() || fields.putIfAbsent(name, value) != null) {
                throw new AtlasCacheException(
                        "Atlas NRRD header has a blank or duplicate field: "
                                + name);
            }
        }
        if (line == null) {
            throw new AtlasCacheException(
                    "Atlas NRRD header has no inline payload");
        }
        return fields;
    }

    private void validateHeader(
            final Map<String, String> fields,
            final Set<String> acceptedTypes) {
        final String type = required(fields, "type")
                .toLowerCase(Locale.ROOT);
        final String encoding = required(fields, "encoding")
                .toLowerCase(Locale.ROOT);
        if (!acceptedTypes.contains(type)) {
            throw new AtlasCacheException(
                    "Atlas NRRD sample type is not the pinned unsigned type");
        }
        if (!required(fields, "dimension").equals("3")) {
            throw new AtlasCacheException(
                    "Atlas NRRD must have exactly three dimensions");
        }
        final String expectedSizes =
                axis0 + " " + axis1 + " " + axis2;
        final String actualSizes = required(fields, "sizes")
                .trim().replaceAll("\\s+", " ");
        if (!actualSizes.equals(expectedSizes)) {
            throw new AtlasCacheException(
                    "Atlas NRRD dimensions do not match the pinned volume");
        }
        if (!encoding.equals("gzip")
                && !encoding.equals("gz")
                && !encoding.equals("raw")) {
            throw new AtlasCacheException(
                    "Atlas NRRD encoding must be gzip or raw");
        }
        if (!required(fields, "endian").equalsIgnoreCase("little")) {
            throw new AtlasCacheException(
                    "Atlas NRRD must use little-endian samples");
        }
        if (fields.containsKey("data file")
                || fields.containsKey("datafile")
                || fields.containsKey("byte skip")
                || fields.containsKey("line skip")) {
            throw new AtlasCacheException(
                    "Detached or skipped NRRD payloads are not supported");
        }
    }

    private int[] readPayload(
            final InputStream input,
            final int axis0Index,
            final int bytesPerSample) throws IOException {
        final int[] plane = new int[Math.multiplyExact(axis1, axis2)];
        final byte[] row = new byte[Math.multiplyExact(
                axis0, bytesPerSample)];
        final int selectedOffset =
                Math.multiplyExact(axis0Index, bytesPerSample);
        for (int z = 0; z < axis2; z++) {
            for (int y = 0; y < axis1; y++) {
                readFully(input, row);
                long unsigned = 0;
                for (int index = 0;
                        index < bytesPerSample;
                        index++) {
                    unsigned |= (long) (row[
                            selectedOffset + index] & 0xff)
                            << (8 * index);
                }
                if (unsigned > Integer.MAX_VALUE) {
                    throw new AtlasCacheException(
                            "Atlas annotation ID exceeds signed integer range");
                }
                plane[y * axis2 + z] = (int) unsigned;
            }
        }
        requireNoTrailingData(input);
        return plane;
    }

    private static void requireNoTrailingData(
            final InputStream input) throws IOException {
        if (input.read() != -1) {
            throw new AtlasCacheException(
                    "Atlas NRRD payload contains trailing sample data");
        }
    }

    private static void readFully(
            final InputStream input,
            final byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            final int count = input.read(
                    bytes, offset, bytes.length - offset);
            if (count < 0) {
                throw new AtlasCacheException(
                        "Atlas NRRD payload is truncated");
            }
            offset += count;
        }
    }

    private static String required(
            final Map<String, String> fields,
            final String name) {
        final String value = fields.get(name);
        if (value == null) {
            throw new AtlasCacheException(
                    "Atlas NRRD header is missing: " + name);
        }
        return value;
    }

    private static String readLine(
            final InputStream input) throws IOException {
        final byte[] bytes = new byte[MAXIMUM_HEADER_LINE_BYTES];
        int length = 0;
        while (true) {
            final int next = input.read();
            if (next < 0) {
                return length == 0
                        ? null
                        : new String(
                                bytes, 0, length,
                                StandardCharsets.US_ASCII);
            }
            if (next == '\n') {
                if (length > 0 && bytes[length - 1] == '\r') {
                    length--;
                }
                return new String(
                        bytes, 0, length,
                        StandardCharsets.US_ASCII);
            }
            if (length == bytes.length) {
                throw new AtlasCacheException(
                        "Atlas NRRD header line is too long");
            }
            bytes[length++] = (byte) next;
        }
    }
}
