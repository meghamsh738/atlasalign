package org.atlasalign.deepslice;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Opens the supported production DeepSlice runtime using a repository-pinned
 * release identity.
 *
 * <p>The external installation is data, not a trust anchor: its complete
 * inventory manifest must match the length and SHA-256 digest compiled into
 * this class before any installed executable or model is used.</p>
 */
public final class AtlasAlignDeepSliceRuntime {

    public static final String RELEASE_ID =
            "deepslice-1.2.8-py3.11.15-tf2.21.0-macos-arm64-r3";

    private static final DeepSliceReleaseDescriptor TRUSTED_RELEASE =
            new DeepSliceReleaseDescriptor(
                    RELEASE_ID,
                    7_582_923,
                    "fda6fe1b8d3a3ecc32f4a44f5864d8e2"
                            + "eff99014f9915a7b9be44126686f03d9",
                    2,
                    "3.11.15",
                    "1.2.8",
                    "2.21.0",
                    "ebrains-mouse-ensemble-2025-01-31",
                    "mac-os-x-aarch64");

    private AtlasAlignDeepSliceRuntime() {
    }

    /**
     * Creates an offline proposal provider only for the pinned protocol-v2
     * r3 release. The retained protocol-v1 r2 facts are historical and cannot
     * activate this boundary.
     *
     * @param installationDirectory finalized external runtime directory
     * @param timeout maximum duration of one worker inference
     * @param temporaryBase private snapshot and request storage, preferably on
     *                      the same external drive as the runtime
     * @return verified, sandboxed provider; close it to remove its snapshot
     */
    public static DeepSliceProcessBridge open(
            final Path installationDirectory,
            final Duration timeout,
            final Path temporaryBase) {
        if (TRUSTED_RELEASE.protocolVersion()
                != DeepSliceProcessBridge.PROTOCOL_VERSION) {
            throw new IllegalStateException(
                    "The pinned production DeepSlice runtime must use "
                            + "protocol v"
                            + DeepSliceProcessBridge.PROTOCOL_VERSION);
        }
        return new DeepSliceProcessBridge(
                Objects.requireNonNull(
                        installationDirectory, "installationDirectory"),
                TRUSTED_RELEASE,
                Objects.requireNonNull(timeout, "timeout"),
                Objects.requireNonNull(temporaryBase, "temporaryBase"));
    }

    static DeepSliceReleaseDescriptor trustedRelease() {
        return TRUSTED_RELEASE;
    }
}
