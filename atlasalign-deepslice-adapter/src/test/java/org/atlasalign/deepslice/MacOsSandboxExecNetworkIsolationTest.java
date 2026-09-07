package org.atlasalign.deepslice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.atlasalign.application.DeepSliceInput;
import org.atlasalign.application.DeepSliceUnavailableException;
import org.atlasalign.application.ManualFallbackReason;
import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.MAC)
@DisabledIfSystemProperty(named = "atlasalign.nativeSandboxChecks", matches = "false",
        disabledReason = "Hosted CI does not certify native optional-DeepSlice sandbox integration")
class MacOsSandboxExecNetworkIsolationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void provesNetworkDenialBeforeWrappingWorker()
            throws DeepSliceUnavailableException {
        final List<String> worker = List.of("/usr/bin/true");

        final List<String> command =
                new MacOsSandboxExecNetworkIsolation().isolate(worker);

        assertEquals("/usr/bin/sandbox-exec", command.get(0));
        assertEquals("-p", command.get(1));
        assertEquals(
                MacOsSandboxExecNetworkIsolation.PROFILE,
                command.get(2));
        assertEquals(worker.get(0), command.get(3));
    }

    @Test
    void productionBridgeDeniesWorkerChildProcesses()
            throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"),
                """
                import subprocess
                subprocess.Popen(['/usr/bin/sleep', '10'])
                """);
        try (DeepSliceProcessBridge bridge =
                new DeepSliceProcessBridge(
                        installed.root(),
                        installed.descriptor(),
                        Duration.ofSeconds(5),
                        temporaryDirectory.resolve("requests"))) {
            final DeepSliceUnavailableException error = assertThrows(
                    DeepSliceUnavailableException.class,
                    () -> bridge.estimate(new DeepSliceInput(
                            1, 1, new float[] {1.0f},
                            BinaryMask.empty(1, 1))));

            assertEquals(
                    ManualFallbackReason.WORKER_FAILED, error.reason());
        }
    }
}
