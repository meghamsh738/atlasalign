package org.atlasalign.deepslice;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Complete inventory of one external, self-contained DeepSlice runtime.
 */
public record DeepSliceInstallationManifest(
        int protocolVersion,
        String pythonVersion,
        String deepSliceVersion,
        String tensorflowVersion,
        String modelRelease,
        String platformArchitecture,
        List<DeepSliceFileAsset> assets) {

    public DeepSliceInstallationManifest {
        pythonVersion = Objects.requireNonNull(
                pythonVersion, "pythonVersion");
        deepSliceVersion = Objects.requireNonNull(
                deepSliceVersion, "deepSliceVersion");
        tensorflowVersion = Objects.requireNonNull(
                tensorflowVersion, "tensorflowVersion");
        modelRelease = Objects.requireNonNull(
                modelRelease, "modelRelease");
        platformArchitecture = Objects.requireNonNull(
                platformArchitecture, "platformArchitecture");
        assets = List.copyOf(Objects.requireNonNull(assets, "assets"));
        if (protocolVersion <= 0
                || pythonVersion.isBlank()
                || deepSliceVersion.isBlank()
                || tensorflowVersion.isBlank()
                || modelRelease.isBlank()
                || platformArchitecture.isBlank()
                || assets.isEmpty()) {
            throw new IllegalArgumentException(
                    "Invalid DeepSlice installation manifest");
        }
        final Set<String> paths = new HashSet<>();
        for (final DeepSliceFileAsset asset : assets) {
            if (!paths.add(asset.relativePath())) {
                throw new IllegalArgumentException(
                        "Duplicate DeepSlice asset path");
            }
        }
        requireExactlyOne(assets, DeepSliceAssetRole.PYTHON_EXECUTABLE);
        requireExactlyOne(assets, DeepSliceAssetRole.WORKER_SCRIPT);
        requireExactlyOne(assets, DeepSliceAssetRole.MODEL_PRIMARY);
        requireExactlyOne(assets, DeepSliceAssetRole.MODEL_SECONDARY);
        requireExactlyOne(assets, DeepSliceAssetRole.MODEL_BACKBONE);
        requireAtLeastOne(assets, DeepSliceAssetRole.PYTHON_RUNTIME);
        requireAtLeastOne(assets, DeepSliceAssetRole.DEEPSLICE_PACKAGE);
        requireAtLeastOne(assets, DeepSliceAssetRole.NATIVE_LIBRARY);
        requireAtLeastOne(assets, DeepSliceAssetRole.METADATA);
    }

    public DeepSliceFileAsset asset(
            final DeepSliceAssetRole role) {
        return assets.stream()
                .filter(asset -> asset.role() == role)
                .findFirst()
                .orElseThrow(() -> new DeepSliceAdapterException(
                        "DeepSlice manifest has no " + role));
    }

    private static void requireExactlyOne(
            final List<DeepSliceFileAsset> assets,
            final DeepSliceAssetRole role) {
        if (assets.stream().filter(
                asset -> asset.role() == role).count() != 1) {
            throw new IllegalArgumentException(
                    "DeepSlice manifest requires exactly one " + role);
        }
    }

    private static void requireAtLeastOne(
            final List<DeepSliceFileAsset> assets,
            final DeepSliceAssetRole role) {
        if (assets.stream().noneMatch(asset -> asset.role() == role)) {
            throw new IllegalArgumentException(
                    "DeepSlice manifest requires at least one " + role);
        }
    }
}
