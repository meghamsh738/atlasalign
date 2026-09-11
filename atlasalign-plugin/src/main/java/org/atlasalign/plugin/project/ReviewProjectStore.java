package org.atlasalign.plugin.project;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import org.atlasalign.application.ReviewAcceptanceVerification;

/** Same-directory, forced temporary write followed by an atomic replacement. */
public final class ReviewProjectStore {
    private final ReviewProjectCodec codec;
    private final AtomicReplacement replacement;

    @FunctionalInterface
    interface AtomicReplacement { void replace(Path temporary, Path destination) throws IOException; }

    public ReviewProjectStore() {
        this(new ReviewProjectCodec(), (temporary, destination) -> Files.move(temporary, destination,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    ReviewProjectStore(final ReviewProjectCodec codec, final AtomicReplacement replacement) {
        this.codec = Objects.requireNonNull(codec); this.replacement = Objects.requireNonNull(replacement);
    }

    public void save(final Path file, final ReviewProject project) throws IOException {
        final Path destination = file.toAbsolutePath().normalize();
        final byte[] bytes = codec.encode(project);
        final Path directory = destination.getParent();
        Files.createDirectories(directory);
        final Path temporary = directory.resolve("." + destination.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                final ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            // Deliberately no non-atomic fallback: the preceding valid checkpoint wins on failure.
            replacement.replace(temporary, destination);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public byte[] read(final Path file) throws IOException {
        final long size = Files.size(file);
        if (size <= 0 || size > ReviewProjectCodec.MAXIMUM_FILE_BYTES) {
            throw new IOException("Review-project file size is invalid");
        }
        try (var stream = Files.newInputStream(file)) {
            final byte[] result = stream.readNBytes((int) ReviewProjectCodec.MAXIMUM_FILE_BYTES + 1);
            if (result.length > ReviewProjectCodec.MAXIMUM_FILE_BYTES) throw new IOException("Review project is too large");
            return result;
        }
    }

    public ReviewProjectCodec.Header header(final byte[] bytes) { return codec.header(bytes); }

    /** Geometry may be reconstructed only after the caller verifies both live assets. */
    public ReviewProject restore(final byte[] bytes, final ReviewAcceptanceVerification verification) {
        final var header = codec.header(bytes);
        if (!header.sourceSnapshot().equals(verification.currentSourceSnapshot())) {
            throw new IllegalArgumentException("Source image pixels, dimensions or calibration differ from the saved review");
        }
        if (!header.atlas().equals(verification.currentAtlas())) {
            throw new IllegalArgumentException("Atlas cache identity differs from the saved review");
        }
        return codec.decode(bytes);
    }
}
