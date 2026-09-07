package org.atlasalign.atlas;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** Resumable, bounded downloads; the destination becomes visible only after verification. */
public final class AtlasDownloadService {
    public record Progress(String asset, long completedBytes, long totalBytes) { }

    private final AtlasManifest manifest;
    private final UnaryOperator<URI> endpoint;

    public AtlasDownloadService() {
        this(AtlasManifests.allenMouse25um(), UnaryOperator.identity());
    }

    // Test seam redirects pinned HTTPS URLs to a loopback fixture server only.
    AtlasDownloadService(final AtlasManifest manifest, final UnaryOperator<URI> endpoint) {
        this.manifest = Objects.requireNonNull(manifest);
        this.endpoint = Objects.requireNonNull(endpoint);
    }

    public VerifiedAtlas install(final Path destination, final BooleanSupplier cancelled,
            final Consumer<Progress> progress) throws IOException {
        Objects.requireNonNull(cancelled);
        Objects.requireNonNull(progress);
        final Path target = destination.toAbsolutePath().normalize();
        checkCancelled(cancelled);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            // Offline reuse; never overwrite an existing, possibly user-owned cache.
            return new AtlasRepository().open(target, manifest);
        }
        if (target.getParent() == null) throw new IOException("Choose a cache folder below a writable parent.");
        Files.createDirectories(target.getParent());
        final Path staging = target.resolveSibling(target.getFileName() + ".download");
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Download staging path is not a safe directory: " + staging);
        }
        Files.createDirectories(staging);
        final Path lockPath = target.resolveSibling(target.getFileName() + ".download.lock");
        if (Files.isSymbolicLink(lockPath)) throw new IOException("Unsafe download lock path.");
        try (FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another atlas download is already running for this folder.");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return new AtlasRepository().open(target, manifest);
            }
            long completed = 0;
            for (final AtlasAsset asset : manifest.assets()) {
                checkCancelled(cancelled);
                final Path file = staging.resolve(asset.relativePath());
                ensureSafeFile(file);
                if (!verified(file, asset)) {
                    final long base = completed;
                    download(asset, file, cancelled, count -> progress.accept(
                            new Progress(asset.relativePath(), base + count, manifest.totalSizeBytes())));
                }
                if (!verified(file, asset)) {
                    Files.deleteIfExists(file);
                    throw new IOException("Atlas checksum verification failed: " + asset.relativePath()
                            + ". Retry to download this asset again.");
                }
                completed += asset.sizeBytes();
                progress.accept(new Progress(asset.relativePath(), completed, manifest.totalSizeBytes()));
            }
            checkCancelled(cancelled);
            final Path manifestFile = staging.resolve("manifest.json");
            ensureSafeFile(manifestFile);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
            new AtlasRepository().open(staging, manifest);
            checkCancelled(cancelled);
            // Keep a sibling lock held across publication, including on Windows.
            try {
                Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, target);
            }
            return new AtlasRepository().open(target, manifest);
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            throw new IOException("Another atlas download is already running for this folder.", busy);
        }
    }

    private void download(final AtlasAsset asset, final Path file,
            final BooleanSupplier cancelled, final Consumer<Long> progress) throws IOException {
        long offset = Files.exists(file) ? Files.size(file) : 0;
        if (offset >= asset.sizeBytes()) {
            Files.delete(file); // An unverified complete staging file must be fetched anew.
            offset = 0;
        }
        if (Files.getFileStore(file.getParent()).getUsableSpace() < asset.sizeBytes() - offset) {
            throw new IOException("Not enough free disk space to download " + asset.relativePath());
        }
        final HttpURLConnection connection = (HttpURLConnection) endpoint.apply(asset.url()).toURL().openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "AtlasAlign-Lite/atlas-setup");
        if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
        try {
            checkCancelled(cancelled);
            final int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_PARTIAL) {
                final String expected = "bytes " + offset + "-" + (asset.sizeBytes() - 1)
                        + "/" + asset.sizeBytes();
                if (!expected.equals(connection.getHeaderField("Content-Range"))) {
                    throw new IOException("Unexpected download range for " + asset.relativePath());
                }
            } else if (status == HttpURLConnection.HTTP_OK) {
                offset = 0; // Server ignored Range: restart safely instead of appending.
            } else {
                throw new IOException("Atlas download returned HTTP " + status + " for " + asset.relativePath());
            }
            final long length = connection.getContentLengthLong();
            if (length >= 0 && length != asset.sizeBytes() - offset) {
                throw new IOException("Unexpected download length for " + asset.relativePath());
            }
            try (InputStream input = connection.getInputStream();
                    OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE, offset == 0
                                    ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND)) {
                final byte[] buffer = new byte[64 * 1024];
                long count = offset;
                progress.accept(count);
                while (true) {
                    checkCancelled(cancelled);
                    final int read = input.read(buffer);
                    if (read < 0) break;
                    if (read > asset.sizeBytes() - count) throw new IOException("Atlas download exceeded pinned length.");
                    output.write(buffer, 0, read);
                    count += read;
                    progress.accept(count);
                }
                if (count != asset.sizeBytes()) throw new IOException("Interrupted atlas download; retry to resume " + asset.relativePath());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void ensureSafeFile(final Path file) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe atlas staging file: " + file);
        }
    }

    private static boolean verified(final Path file, final AtlasAsset asset) throws IOException {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                && Files.size(file) == asset.sizeBytes()
                && AtlasIntegrity.sha256(file).equals(asset.sha256());
    }

    private static void checkCancelled(final BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Atlas download cancelled; retry the same folder to resume.");
        }
    }
}
