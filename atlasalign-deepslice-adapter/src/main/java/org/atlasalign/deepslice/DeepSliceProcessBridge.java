package org.atlasalign.deepslice;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.atlasalign.application.DeepSliceInput;
import org.atlasalign.application.DeepSliceOuv;
import org.atlasalign.application.DeepSlicePlanePrediction;
import org.atlasalign.application.DeepSlicePlaneProvider;
import org.atlasalign.application.DeepSliceRuntimeProvenance;
import org.atlasalign.application.DeepSliceUnavailableException;
import org.atlasalign.application.ManualFallbackReason;

/**
 * Strict file protocol and bounded subprocess boundary for local DeepSlice.
 */
public final class DeepSliceProcessBridge
        implements DeepSlicePlaneProvider, AutoCloseable {

    static final int PROTOCOL_VERSION = 2;
    static final long MAX_RESPONSE_BYTES = 65_536;
    static final long MAX_WORKER_OUTPUT_BYTES = 1_048_576;
    static final int MAX_PIXELS = 5_000_000;
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    private final DeepSliceInstallationRepository repository;
    private final Path installationDirectory;
    private final DeepSliceReleaseDescriptor descriptor;
    private final NetworkIsolation networkIsolation;
    private final Duration timeout;
    private final Path temporaryBase;
    private VerifiedRuntimeSnapshot verifiedSnapshot;

    DeepSliceProcessBridge(
            final Path installationDirectory,
            final DeepSliceReleaseDescriptor descriptor,
            final Duration timeout,
            final Path temporaryBase) {
        this(new DeepSliceInstallationRepository(),
                installationDirectory, descriptor,
                new MacOsSandboxExecNetworkIsolation(),
                timeout, temporaryBase);
    }

    DeepSliceProcessBridge(
            final Path installationDirectory,
            final DeepSliceReleaseDescriptor descriptor,
            final NetworkIsolation networkIsolation,
            final Duration timeout,
            final Path temporaryBase) {
        this(new DeepSliceInstallationRepository(),
                installationDirectory, descriptor, networkIsolation,
                timeout, temporaryBase);
    }

    DeepSliceProcessBridge(
            final DeepSliceInstallationRepository repository,
            final Path installationDirectory,
            final DeepSliceReleaseDescriptor descriptor,
            final NetworkIsolation networkIsolation,
            final Duration timeout,
            final Path temporaryBase) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.installationDirectory = Objects.requireNonNull(
                installationDirectory, "installationDirectory");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.networkIsolation = Objects.requireNonNull(
                networkIsolation, "networkIsolation");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.temporaryBase = Objects.requireNonNull(
                temporaryBase, "temporaryBase");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
    }

    @Override
    public DeepSlicePlanePrediction estimate(final DeepSliceInput input)
            throws DeepSliceUnavailableException {
        Objects.requireNonNull(input, "input");
        if (Math.multiplyExact(input.width(), input.height()) > MAX_PIXELS) {
            throw invalidOutput("DeepSlice input is too large", null);
        }
        final VerifiedRuntimeSnapshot verified =
                verifiedSnapshot();
        Path requestDirectory = null;
        try {
            requestDirectory = createPrivateRequestDirectory();
            final String requestId = UUID.randomUUID().toString();
            final Path pixelsPath = requestDirectory.resolve("pixels.f32le");
            final Path maskPath = requestDirectory.resolve("synthetic-mask.u8");
            final Path requestPath = requestDirectory.resolve("request.json");
            final Path responsePath = requestDirectory.resolve("response.json");
            writePixels(pixelsPath, input.pixels());
            writeMask(maskPath, input);
            final String pixelsHash = DeepSliceIntegrity.sha256(pixelsPath);
            final String maskHash = DeepSliceIntegrity.sha256(maskPath);
            final WorkerRequest request = new WorkerRequest(
                    PROTOCOL_VERSION,
                    requestId,
                    input.width(),
                    input.height(),
                    pixelsPath.toString(),
                    pixelsHash,
                    maskPath.toString(),
                    maskHash,
                    verified.installation().manifest().pythonVersion(),
                    verified.installation().manifest().deepSliceVersion(),
                    verified.installation().manifest().tensorflowVersion(),
                    verified.installation().manifest().modelRelease(),
                    verified.installation().manifest().asset(
                            DeepSliceAssetRole.MODEL_PRIMARY).sha256(),
                    verified.installation().manifest().asset(
                            DeepSliceAssetRole.MODEL_SECONDARY).sha256(),
                    verified.installation().manifest().asset(
                            DeepSliceAssetRole.MODEL_BACKBONE).sha256());
            MAPPER.writeValue(requestPath.toFile(), request);
            runWorker(verified.installation(), requestPath, responsePath);
            final WorkerResponse response = readResponse(responsePath);
            validateResponse(response, request);
            verifySnapshot();
            return DeepSlicePlanePrediction.fromWorkerVectors(
                    DeepSliceOuv.fromList(response.primaryOuv()),
                    DeepSliceOuv.fromList(response.secondaryOuv()),
                    DeepSliceOuv.fromList(response.ensembleOuv()),
                    verified.provenance());
        } catch (final DeepSliceUnavailableException error) {
            throw error;
        } catch (final DeepSliceAdapterException error) {
            throw unavailable(
                    ManualFallbackReason.INSTALLATION_CORRUPT,
                    "DeepSlice installation verification failed", error);
        } catch (final IOException | IllegalArgumentException error) {
            throw invalidOutput(
                    "DeepSlice request or response was invalid", error);
        } finally {
            deleteRequestDirectory(requestDirectory);
        }
    }

    private synchronized VerifiedRuntimeSnapshot verifiedSnapshot()
            throws DeepSliceUnavailableException {
        if (verifiedSnapshot != null) {
            return verifySnapshot();
        }
        final VerifiedDeepSliceInstallation source =
                verifyInstallation(installationDirectory);
        Path snapshot = null;
        try {
            Files.createDirectories(temporaryBase);
            snapshot = Files.createTempDirectory(
                    temporaryBase.toRealPath(),
                    "atlasalign-deepslice-runtime-");
            copyInstallation(source.root(), snapshot);
            verifyInstallation(installationDirectory);
            final VerifiedDeepSliceInstallation verifiedInstallation =
                    verifyInstallation(snapshot);
            makeReadOnly(snapshot);
            verifiedSnapshot = new VerifiedRuntimeSnapshot(
                    verifiedInstallation,
                    runtimeProvenance(source));
            return verifiedSnapshot;
        } catch (final IOException | DeepSliceAdapterException error) {
            deleteRequestDirectory(snapshot);
            throw unavailable(
                    ManualFallbackReason.INSTALLATION_CORRUPT,
                    "Could not create a private verified runtime snapshot",
                    error);
        }
    }

    private VerifiedRuntimeSnapshot verifySnapshot()
            throws DeepSliceUnavailableException {
        if (verifiedSnapshot == null) {
            throw unavailable(
                    ManualFallbackReason.INSTALLATION_CORRUPT,
                    "DeepSlice runtime snapshot is unavailable", null);
        }
        final VerifiedDeepSliceInstallation installation = verifyInstallation(
                verifiedSnapshot.installation().root());
        return new VerifiedRuntimeSnapshot(
                installation, verifiedSnapshot.provenance());
    }

    private DeepSliceRuntimeProvenance runtimeProvenance(
            final VerifiedDeepSliceInstallation installation)
            throws IOException {
        final DeepSliceInstallationManifest manifest = installation.manifest();
        return new DeepSliceRuntimeProvenance(
                descriptor.releaseId(),
                installation.root().toRealPath(),
                manifest.protocolVersion(),
                descriptor.manifestSizeBytes(),
                descriptor.manifestSha256(),
                manifest.pythonVersion(),
                manifest.deepSliceVersion(),
                manifest.tensorflowVersion(),
                manifest.modelRelease(),
                manifest.asset(DeepSliceAssetRole.MODEL_PRIMARY).sha256(),
                manifest.asset(DeepSliceAssetRole.MODEL_SECONDARY).sha256(),
                manifest.asset(DeepSliceAssetRole.MODEL_BACKBONE).sha256());
    }

    private VerifiedDeepSliceInstallation verifyInstallation(
            final Path directory)
            throws DeepSliceUnavailableException {
        try {
            return repository.open(directory, descriptor);
        } catch (final DeepSliceAdapterException error) {
            throw unavailable(
                    ManualFallbackReason.INSTALLATION_CORRUPT,
                    "DeepSlice installation is missing or corrupt: "
                            + error.getMessage(),
                    error);
        }
    }

    private static void copyInstallation(
            final Path source,
            final Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (final Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException(
                            "Runtime snapshot source contains a symlink");
                }
                final Path target = destination.resolve(
                        source.relativize(path).toString());
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(
                        path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.copy(path, target,
                            StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    throw new IOException(
                            "Runtime snapshot source contains unsafe data");
                }
            }
        }
    }

    private static void makeReadOnly(final Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (final Path path : paths.sorted(
                    Comparator.reverseOrder()).toList()) {
                try {
                    Files.setPosixFilePermissions(
                            path,
                            Files.isDirectory(path)
                                    ? EnumSet.of(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_EXECUTE)
                                    : EnumSet.of(
                                    PosixFilePermission.OWNER_READ,
                                    Files.isExecutable(path)
                                            ? PosixFilePermission.OWNER_EXECUTE
                                            : PosixFilePermission.OWNER_READ));
                } catch (final UnsupportedOperationException error) {
                    if (!path.toFile().setWritable(false, false)) {
                        throw new IOException(
                                "Could not protect runtime snapshot", error);
                    }
                }
            }
        }
    }

    private Path createPrivateRequestDirectory() throws IOException {
        Files.createDirectories(temporaryBase);
        final Path directory = Files.createTempDirectory(
                temporaryBase.toRealPath(), "atlasalign-deepslice-");
        try {
            Files.setPosixFilePermissions(
                    directory, PosixFilePermissions.fromString("rwx------"));
        } catch (final UnsupportedOperationException ignored) {
            // The platform may not expose POSIX permissions.
        }
        return directory;
    }

    private static void writePixels(
            final Path path,
            final float[] pixels) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            final ByteBuffer buffer = ByteBuffer.allocate(
                    Math.min(65_536, Math.max(4, pixels.length * 4)))
                    .order(ByteOrder.LITTLE_ENDIAN);
            for (final float pixel : pixels) {
                if (buffer.remaining() < Float.BYTES) {
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    buffer.clear();
                }
                buffer.putFloat(pixel);
            }
            buffer.flip();
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }
    }

    private static void writeMask(
            final Path path,
            final DeepSliceInput input) throws IOException {
        final byte[] mask = new byte[
                Math.multiplyExact(input.width(), input.height())];
        for (int y = 0; y < input.height(); y++) {
            for (int x = 0; x < input.width(); x++) {
                if (input.syntheticTissueMask().contains(x, y)) {
                    mask[y * input.width() + x] = 1;
                }
            }
        }
        Files.write(path, mask, StandardOpenOption.CREATE_NEW);
    }

    private void runWorker(
            final VerifiedDeepSliceInstallation installation,
            final Path request,
            final Path response) throws DeepSliceUnavailableException {
        final List<String> workerCommand = List.of(
                installation.pythonExecutable().toString(),
                "-I",
                installation.workerScript().toString(),
                "--request",
                request.toString(),
                "--response",
                response.toString());
        final List<String> command =
                networkIsolation.isolate(workerCommand);
        if (command.isEmpty() || command.stream().anyMatch(
                value -> value == null || value.isBlank())) {
            throw unavailable(
                    ManualFallbackReason.NETWORK_ISOLATION_UNAVAILABLE,
                    "Network isolation returned an invalid command", null);
        }
        final ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true);
        final Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH",
                installation.pythonExecutable().getParent().toString()
                        + ":/usr/bin:/bin");
        environment.put("HOME", request.getParent().toString());
        environment.put("TMPDIR", request.getParent().toString());
        environment.put("PYTHONNOUSERSITE", "1");
        environment.put("PYTHONDONTWRITEBYTECODE", "1");
        environment.put("PIP_NO_INDEX", "1");
        Process process = null;
        final AtomicBoolean overflow = new AtomicBoolean();
        Thread outputReader = null;
        try {
            process = builder.start();
            final Process startedProcess = process;
            process.getOutputStream().close();
            outputReader = new Thread(
                    () -> drainBounded(startedProcess, overflow),
                    "atlasalign-deepslice-output");
            outputReader.setDaemon(true);
            outputReader.start();
            if (!process.waitFor(
                    timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                destroyProcessTreeAndAwait(process);
                throw unavailable(
                        ManualFallbackReason.INFERENCE_TIMEOUT,
                        "DeepSlice worker timed out", null);
            }
            outputReader.join(Math.min(timeout.toMillis(), 2_000));
            if (outputReader.isAlive()) {
                throw unavailable(
                        ManualFallbackReason.WORKER_FAILED,
                        "DeepSlice worker output did not close", null);
            }
            if (overflow.get()) {
                throw unavailable(
                        ManualFallbackReason.WORKER_FAILED,
                        "DeepSlice worker exceeded its output limit", null);
            }
            if (process.exitValue() != 0) {
                throw unavailable(
                        ManualFallbackReason.WORKER_FAILED,
                        "DeepSlice worker exited with code "
                                + process.exitValue(), null);
            }
        } catch (final IOException error) {
            throw unavailable(
                    ManualFallbackReason.WORKER_FAILED,
                    "Could not start DeepSlice worker", error);
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable(
                    ManualFallbackReason.WORKER_FAILED,
                    "DeepSlice worker was interrupted", error);
        } finally {
            if (process != null && process.isAlive()) {
                destroyProcessTreeAndAwait(process);
            }
            if (process != null) {
                try {
                    process.getInputStream().close();
                } catch (final IOException ignored) {
                    // Closing the bounded output pipe releases its reader.
                }
            }
            if (outputReader != null && outputReader.isAlive()) {
                outputReader.interrupt();
            }
        }
    }

    private static void drainBounded(
            final Process process,
            final AtomicBoolean overflow) {
        try (InputStream input = process.getInputStream();
                ByteArrayOutputStream kept = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8_192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total <= MAX_WORKER_OUTPUT_BYTES) {
                    kept.write(buffer, 0, count);
                } else {
                    overflow.set(true);
                    destroyProcessTreeAndAwait(process);
                }
            }
        } catch (final IOException ignored) {
            // Closing a killed process pipe is expected.
        }
    }

    private static void destroyProcessTreeAndAwait(final Process process) {
        List<ProcessHandle> descendants = List.of();
        try {
            descendants = process.toHandle().descendants().toList();
        } catch (final RuntimeException ignored) {
            // A host sandbox may deny process-table inspection. The direct
            // worker is still forcibly terminated below, and its own sandbox
            // denies child-process creation before inference is accepted.
        }
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        final long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(2);
        try {
            process.waitFor(
                    remainingMillis(deadline), TimeUnit.MILLISECONDS);
            for (final ProcessHandle descendant : descendants) {
                final long remaining = remainingMillis(deadline);
                if (remaining == 0) {
                    break;
                }
                descendant.onExit().get(
                        remaining, TimeUnit.MILLISECONDS);
            }
        } catch (final Exception ignored) {
            // The caller still fails closed; no result is accepted.
        }
    }

    private static long remainingMillis(final long deadline) {
        final long remaining = deadline - System.nanoTime();
        return remaining <= 0 ? 0
                : Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private static WorkerResponse readResponse(final Path path)
            throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) {
            throw new IOException(
                    "DeepSlice response is missing or unsafe");
        }
        final Set<OpenOption> options = Set.of(
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        final ByteBuffer bytes = ByteBuffer.allocate(
                Math.toIntExact(MAX_RESPONSE_BYTES + 1));
        try (SeekableByteChannel channel =
                Files.newByteChannel(path, options)) {
            while (bytes.hasRemaining()
                    && channel.read(bytes) >= 0) {
                // Continue until EOF or the strict byte ceiling is crossed.
            }
            if (!bytes.hasRemaining()
                    || channel.read(ByteBuffer.allocate(1)) >= 0) {
                throw new IOException(
                        "DeepSlice response is too large");
            }
        }
        bytes.flip();
        final byte[] exactBytes = new byte[bytes.remaining()];
        bytes.get(exactBytes);
        return MAPPER.readValue(exactBytes, WorkerResponse.class);
    }

    private static void validateResponse(
            final WorkerResponse response,
            final WorkerRequest request) {
        if (response.protocolVersion() != PROTOCOL_VERSION
                || !request.requestId().equals(response.requestId())
                || !request.pixelsSha256().equals(
                response.pixelsSha256())
                || !request.deepSliceVersion().equals(
                response.deepSliceVersion())
                || !request.pythonVersion().equals(
                response.pythonVersion())
                || !request.tensorflowVersion().equals(
                response.tensorflowVersion())
                || !request.modelRelease().equals(
                response.modelRelease())
                || !request.primaryWeightSha256().equals(
                response.primaryWeightSha256())
                || !request.secondaryWeightSha256().equals(
                response.secondaryWeightSha256())
                || !request.backboneWeightSha256().equals(
                response.backboneWeightSha256())) {
            throw new IllegalArgumentException(
                    "DeepSlice response identity does not match request");
        }
    }

    private static void deleteRequestDirectory(final Path directory) {
        if (directory == null || !Files.exists(
                directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.forEach(path -> path.toFile().setWritable(true, true));
        } catch (final IOException ignored) {
            // Best effort before deletion.
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException ignored) {
                    // Best-effort cleanup after a contained request.
                }
            });
        } catch (final IOException ignored) {
            // Best-effort cleanup after a contained request.
        }
    }

    @Override
    public synchronized void close() {
        deleteRequestDirectory(verifiedSnapshot == null ? null
                : verifiedSnapshot.installation().root());
        verifiedSnapshot = null;
    }

    private static DeepSliceUnavailableException invalidOutput(
            final String message,
            final Throwable cause) {
        return unavailable(
                ManualFallbackReason.INVALID_WORKER_OUTPUT,
                message,
                cause);
    }

    private static DeepSliceUnavailableException unavailable(
            final ManualFallbackReason reason,
            final String message,
            final Throwable cause) {
        return new DeepSliceUnavailableException(reason, message, cause);
    }

    record WorkerRequest(
            int protocolVersion,
            String requestId,
            int width,
            int height,
            String pixelsFile,
            String pixelsSha256,
            String syntheticMaskFile,
            String syntheticMaskSha256,
            String pythonVersion,
            String deepSliceVersion,
            String tensorflowVersion,
            String modelRelease,
            String primaryWeightSha256,
            String secondaryWeightSha256,
            String backboneWeightSha256) {
    }

    record WorkerResponse(
            int protocolVersion,
            String requestId,
            String pixelsSha256,
            String pythonVersion,
            String deepSliceVersion,
            String tensorflowVersion,
            String modelRelease,
            String primaryWeightSha256,
            String secondaryWeightSha256,
            String backboneWeightSha256,
            List<Double> primaryOuv,
            List<Double> secondaryOuv,
            List<Double> ensembleOuv) {
    }

    private record VerifiedRuntimeSnapshot(
            VerifiedDeepSliceInstallation installation,
            DeepSliceRuntimeProvenance provenance) {
    }
}
