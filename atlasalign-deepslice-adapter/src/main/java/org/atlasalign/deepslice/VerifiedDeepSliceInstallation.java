package org.atlasalign.deepslice;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Paths exposed only after complete manifest and file verification.
 */
public record VerifiedDeepSliceInstallation(
        Path root,
        DeepSliceInstallationManifest manifest,
        Path pythonExecutable,
        Path workerScript) {

    public VerifiedDeepSliceInstallation {
        root = Objects.requireNonNull(root, "root");
        manifest = Objects.requireNonNull(manifest, "manifest");
        pythonExecutable = Objects.requireNonNull(
                pythonExecutable, "pythonExecutable");
        workerScript = Objects.requireNonNull(workerScript, "workerScript");
    }
}
