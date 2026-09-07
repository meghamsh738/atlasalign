package org.atlasalign.deepslice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AtlasAlignDeepSliceRuntimeTest {

    @Test
    void pinsTheProductionManifestAndRuntimeIdentity() {
        final DeepSliceReleaseDescriptor release =
                AtlasAlignDeepSliceRuntime.trustedRelease();

        assertEquals(
                "deepslice-1.2.8-py3.11.15-tf2.21.0-macos-arm64-r3",
                release.releaseId());
        assertEquals(7_582_923, release.manifestSizeBytes());
        assertEquals(
                "fda6fe1b8d3a3ecc32f4a44f5864d8e2"
                        + "eff99014f9915a7b9be44126686f03d9",
                release.manifestSha256());
        assertEquals("3.11.15", release.pythonVersion());
        assertEquals("1.2.8", release.deepSliceVersion());
        assertEquals("2.21.0", release.tensorflowVersion());
        assertEquals(
                "ebrains-mouse-ensemble-2025-01-31",
                release.modelRelease());
        assertEquals(
                "mac-os-x-aarch64",
                release.platformArchitecture());
        assertEquals(2, release.protocolVersion());
    }

    @Test
    void createsAProviderForThePinnedProtocolV2R3Release() {
        final DeepSliceProcessBridge bridge = assertDoesNotThrow(
                () -> AtlasAlignDeepSliceRuntime.open(
                        Path.of("/runtime"),
                        Duration.ofSeconds(1),
                        Path.of("/work")));

        bridge.close();
    }
}
