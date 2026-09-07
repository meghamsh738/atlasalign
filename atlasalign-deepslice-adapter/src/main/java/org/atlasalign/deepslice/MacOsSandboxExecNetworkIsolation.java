package org.atlasalign.deepslice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.atlasalign.application.DeepSliceUnavailableException;
import org.atlasalign.application.ManualFallbackReason;

/**
 * Denies all worker networking with the macOS sandbox before inference starts.
 */
public final class MacOsSandboxExecNetworkIsolation
        implements NetworkIsolation {

    static final Path SANDBOX_EXEC = Path.of("/usr/bin/sandbox-exec");
    static final Path SYSTEM_PYTHON = Path.of("/usr/bin/python3");
    static final String PROFILE =
            "(version 1) (allow default)"
                    + " (deny network*) (deny process-fork)";
    private static final String NETWORK_CANARY =
            "import socket; socket.create_connection("
                    + "('127.0.0.1', 9), timeout=0.2)";
    private static final String PROCESS_CANARY =
            "import subprocess; subprocess.Popen(['/usr/bin/true'])";
    private final Duration probeTimeout;

    public MacOsSandboxExecNetworkIsolation() {
        this(Duration.ofSeconds(3));
    }

    MacOsSandboxExecNetworkIsolation(final Duration probeTimeout) {
        this.probeTimeout = probeTimeout;
    }

    @Override
    public List<String> isolate(final List<String> command)
            throws DeepSliceUnavailableException {
        if (!System.getProperty("os.name", "").toLowerCase(
                java.util.Locale.ROOT).contains("mac")
                || !Files.isExecutable(SANDBOX_EXEC)
                || !Files.isExecutable(SYSTEM_PYTHON)) {
            throw unavailable("macOS sandbox-exec is unavailable", null);
        }
        probe();
        final List<String> isolated =
                new ArrayList<>(command.size() + 3);
        isolated.add(SANDBOX_EXEC.toString());
        isolated.add("-p");
        isolated.add(PROFILE);
        isolated.addAll(command);
        return List.copyOf(isolated);
    }

    private void probe() throws DeepSliceUnavailableException {
        final ProbeResult allowed = runProbe(List.of("/usr/bin/true"));
        if (allowed.exitCode() != 0) {
            throw unavailable(
                    "macOS network-isolation process probe failed", null);
        }
        final ProbeResult denied = runProbe(List.of(
                SYSTEM_PYTHON.toString(), "-I", "-c", NETWORK_CANARY));
        if (denied.exitCode() == 0
                || !denied.output().contains("Operation not permitted")) {
            throw unavailable(
                    "macOS sandbox did not prove network denial", null);
        }
        final ProbeResult forkDenied = runProbe(List.of(
                SYSTEM_PYTHON.toString(), "-I", "-c", PROCESS_CANARY));
        if (forkDenied.exitCode() == 0
                || !forkDenied.output().contains(
                "Operation not permitted")) {
            throw unavailable(
                    "macOS sandbox did not prove process-fork denial", null);
        }
    }

    private ProbeResult runProbe(final List<String> childCommand)
            throws DeepSliceUnavailableException {
        final Process process;
        try {
            final List<String> command = new ArrayList<>();
            command.add(SANDBOX_EXEC.toString());
            command.add("-p");
            command.add(PROFILE);
            command.addAll(childCommand);
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(
                    probeTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw unavailable(
                        "macOS network-isolation probe timed out", null);
            }
            final String output = new String(
                    process.getInputStream().readNBytes(4_096),
                    java.nio.charset.StandardCharsets.UTF_8);
            return new ProbeResult(process.exitValue(), output);
        } catch (final IOException error) {
            throw unavailable(
                    "Could not start macOS network-isolation probe", error);
        } catch (final InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable(
                    "Network-isolation probe was interrupted", error);
        }
    }

    private static DeepSliceUnavailableException unavailable(
            final String message,
            final Throwable cause) {
        return new DeepSliceUnavailableException(
                ManualFallbackReason.NETWORK_ISOLATION_UNAVAILABLE,
                message,
                cause);
    }

    private record ProbeResult(int exitCode, String output) {
    }
}
