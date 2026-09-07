package org.atlasalign.deepslice;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class DeepSliceTestInstallation {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DeepSliceTestInstallation() {
    }

    static Installed create(
            final Path requestedRoot,
            final String workerSource) throws IOException {
        return create(requestedRoot, workerSource, 2);
    }

    static Installed create(
            final Path requestedRoot,
            final String workerSource,
            final int protocolVersion) throws IOException {
        Files.createDirectories(requestedRoot);
        final Path root = requestedRoot.toRealPath();
        final Path python = root.resolve("bin/python3");
        final Path worker = root.resolve("worker/worker.py");
        Files.createDirectories(python.getParent());
        Files.createDirectories(worker.getParent());
        Files.writeString(
                python,
                "#!/bin/sh\nexec /usr/bin/python3 \"$@\"\n",
                StandardCharsets.UTF_8);
        if (!python.toFile().setExecutable(true, false) && !Files.isExecutable(python)) {
            throw new IOException("Could not make test Python executable");
        }
        Files.writeString(
                worker, workerSource, StandardCharsets.UTF_8);
        final Path pythonRuntime = write(
                root, "lib/python-runtime.dat", "runtime");
        final Path deepSlicePackage = write(
                root, "lib/deepslice-package.dat", "deepslice");
        final Path nativeLibrary = write(
                root, "lib/native-library.dat", "native");
        final Path modelPrimary = write(
                root, "models/primary.h5", "primary");
        final Path modelSecondary = write(
                root, "models/secondary.h5", "secondary");
        final Path modelBackbone = write(
                root, "models/backbone.h5", "backbone");
        final Path metadata = write(
                root, "metadata/release.json", "{}");
        final DeepSliceInstallationManifest manifest =
                new DeepSliceInstallationManifest(
                        protocolVersion,
                        "3.11-test",
                        "test-1.2.8",
                        "2.21-test",
                        "test-models",
                        DeepSliceInstallationRepository
                                .hostPlatformArchitecture(),
                        List.of(
                                asset(root, python,
                                        DeepSliceAssetRole
                                                .PYTHON_EXECUTABLE),
                                asset(root, worker,
                                        DeepSliceAssetRole.WORKER_SCRIPT),
                                asset(root, pythonRuntime,
                                        DeepSliceAssetRole.PYTHON_RUNTIME),
                                asset(root, deepSlicePackage,
                                        DeepSliceAssetRole.DEEPSLICE_PACKAGE),
                                asset(root, nativeLibrary,
                                        DeepSliceAssetRole.NATIVE_LIBRARY),
                                asset(root, modelPrimary,
                                        DeepSliceAssetRole.MODEL_PRIMARY),
                                asset(root, modelSecondary,
                                        DeepSliceAssetRole.MODEL_SECONDARY),
                                asset(root, modelBackbone,
                                        DeepSliceAssetRole.MODEL_BACKBONE),
                                asset(root, metadata,
                                        DeepSliceAssetRole.METADATA)));
        final Path manifestPath = root.resolve(
                DeepSliceInstallationRepository.MANIFEST_FILENAME);
        MAPPER.writeValue(manifestPath.toFile(), manifest);
        final DeepSliceReleaseDescriptor descriptor =
                new DeepSliceReleaseDescriptor(
                        "test-release",
                        Files.size(manifestPath),
                        DeepSliceIntegrity.sha256(manifestPath),
                        protocolVersion,
                        manifest.pythonVersion(),
                        manifest.deepSliceVersion(),
                        manifest.tensorflowVersion(),
                        manifest.modelRelease(),
                        manifest.platformArchitecture());
        return new Installed(root, descriptor, manifest, worker);
    }

    private static Path write(
            final Path root,
            final String relativePath,
            final String contents) throws IOException {
        final Path path = root.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, contents, StandardCharsets.UTF_8);
        return path;
    }

    private static DeepSliceFileAsset asset(
            final Path root,
            final Path path,
            final DeepSliceAssetRole role) throws IOException {
        return new DeepSliceFileAsset(
                root.relativize(path).toString().replace(java.io.File.separatorChar, '/'),
                Files.size(path),
                DeepSliceIntegrity.sha256(path),
                role,
                Files.isExecutable(path));
    }

    record Installed(
            Path root,
            DeepSliceReleaseDescriptor descriptor,
            DeepSliceInstallationManifest manifest,
            Path worker) {
    }
}
