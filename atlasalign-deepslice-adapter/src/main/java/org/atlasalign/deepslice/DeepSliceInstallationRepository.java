package org.atlasalign.deepslice;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Opens an external DeepSlice runtime only after verifying its trusted
 * inventory and every installed file.
 */
public final class DeepSliceInstallationRepository {

    public static final String MANIFEST_FILENAME =
            "installation-manifest.json";
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final int MAX_INVENTORY_DIAGNOSTICS = 8;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();

    public VerifiedDeepSliceInstallation open(
            final Path installationDirectory,
            final DeepSliceReleaseDescriptor descriptor) {
        Objects.requireNonNull(
                installationDirectory, "installationDirectory");
        Objects.requireNonNull(descriptor, "descriptor");
        requireProtocolV2(descriptor.protocolVersion(), "release descriptor");
        final Path root = installationDirectory
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(root)
                || !sameRealPath(root)) {
            throw new DeepSliceAdapterException(
                    "DeepSlice installation root is missing or unsafe");
        }
        final Path manifestPath = root.resolve(MANIFEST_FILENAME);
        final byte[] trustedManifest =
                readTrustedManifest(manifestPath, descriptor);
        final DeepSliceInstallationManifest manifest =
                readManifest(trustedManifest);
        requireProtocolV2(manifest.protocolVersion(), "installation manifest");
        verifyDescriptor(manifest, descriptor);
        verifyCompleteInventory(root, manifest);
        final Path python = resolveAsset(
                root,
                manifest.asset(DeepSliceAssetRole.PYTHON_EXECUTABLE));
        final Path worker = resolveAsset(
                root,
                manifest.asset(DeepSliceAssetRole.WORKER_SCRIPT));
        if (!Files.isExecutable(python)) {
            throw new DeepSliceAdapterException(
                    "Verified DeepSlice Python is not executable");
        }
        return new VerifiedDeepSliceInstallation(
                root, manifest, python, worker);
    }

    public static String hostPlatformArchitecture() {
        return normalizePlatform(System.getProperty("os.name"))
                + "-" + normalizePlatform(System.getProperty("os.arch"));
    }

    private static void requireProtocolV2(
            final int protocolVersion, final String subject) {
        if (protocolVersion != DeepSliceProcessBridge.PROTOCOL_VERSION) {
            throw new DeepSliceAdapterException(
                    "DeepSlice " + subject + " must use protocol v"
                            + DeepSliceProcessBridge.PROTOCOL_VERSION);
        }
    }

    private static String normalizePlatform(final String value) {
        return value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private static boolean sameRealPath(final Path path) {
        try {
            return path.toRealPath().equals(path);
        } catch (final IOException error) {
            return false;
        }
    }

    private static byte[] readTrustedManifest(
            final Path manifestPath,
            final DeepSliceReleaseDescriptor descriptor) {
        if (!Files.isRegularFile(
                manifestPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(manifestPath)) {
            throw new DeepSliceAdapterException(
                    "DeepSlice installation manifest is missing or unsafe");
        }
        try {
            if (descriptor.manifestSizeBytes() > MAX_MANIFEST_BYTES
                    || Files.size(manifestPath)
                    != descriptor.manifestSizeBytes()) {
                throw new DeepSliceAdapterException(
                        "DeepSlice installation manifest is not trusted");
            }
            final byte[] bytes = Files.readAllBytes(manifestPath);
            if (bytes.length != descriptor.manifestSizeBytes()
                    || !DeepSliceIntegrity.sha256(bytes).equals(
                    descriptor.manifestSha256())) {
                throw new DeepSliceAdapterException(
                        "DeepSlice installation manifest is not trusted");
            }
            return bytes;
        } catch (final IOException error) {
            throw new DeepSliceAdapterException(
                    "Could not verify DeepSlice installation manifest",
                    error);
        }
    }

    private static DeepSliceInstallationManifest readManifest(
            final byte[] bytes) {
        try {
            return MAPPER.readValue(
                    bytes, DeepSliceInstallationManifest.class);
        } catch (final IOException | IllegalArgumentException error) {
            throw new DeepSliceAdapterException(
                    "Could not read DeepSlice installation manifest",
                    error);
        }
    }

    private static void verifyDescriptor(
            final DeepSliceInstallationManifest manifest,
            final DeepSliceReleaseDescriptor descriptor) {
        if (manifest.protocolVersion() != descriptor.protocolVersion()
                || !manifest.pythonVersion().equals(
                descriptor.pythonVersion())
                || !manifest.deepSliceVersion().equals(
                descriptor.deepSliceVersion())
                || !manifest.tensorflowVersion().equals(
                descriptor.tensorflowVersion())
                || !manifest.modelRelease().equals(
                descriptor.modelRelease())
                || !manifest.platformArchitecture().equals(
                descriptor.platformArchitecture())
                || !manifest.platformArchitecture().equals(
                hostPlatformArchitecture())) {
            throw new DeepSliceAdapterException(
                    "DeepSlice installation release or platform mismatch");
        }
    }

    private static void verifyCompleteInventory(
            final Path root,
            final DeepSliceInstallationManifest manifest) {
        final Set<Path> expected = new HashSet<>();
        for (final DeepSliceFileAsset asset : manifest.assets()) {
            expected.add(expectedPath(root, asset));
        }
        final Set<Path> actual = new HashSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> !path.equals(root))
                    .forEach(path -> inspectPath(
                            root, path, actual));
        } catch (final IOException error) {
            throw new DeepSliceAdapterException(
                    "Could not inventory DeepSlice installation", error);
        }
        actual.remove(root.resolve(MANIFEST_FILENAME));
        if (!actual.equals(expected)) {
            throw new DeepSliceAdapterException(
                    inventoryMismatch(root, expected, actual));
        }
        final Set<Object> fileKeys = new HashSet<>();
        for (final DeepSliceFileAsset asset : manifest.assets()) {
            rejectAliasedFile(resolveAsset(root, asset), fileKeys);
        }
    }

    private static Path expectedPath(
            final Path root,
            final DeepSliceFileAsset asset) {
        final Path path = root.resolve(asset.relativePath()).normalize();
        if (!path.startsWith(root)) {
            throw new DeepSliceAdapterException(
                    "DeepSlice asset escaped its root: "
                            + asset.relativePath());
        }
        return path.toAbsolutePath().normalize();
    }

    private static String inventoryMismatch(
            final Path root,
            final Set<Path> expected,
            final Set<Path> actual) {
        final Set<Path> missing = new HashSet<>(expected);
        missing.removeAll(actual);
        final Set<Path> extra = new HashSet<>(actual);
        extra.removeAll(expected);
        return "DeepSlice installation inventory mismatch; missing="
                + summarizedRelativePaths(root, missing)
                + "; extra=" + summarizedRelativePaths(root, extra);
    }

    private static String summarizedRelativePaths(
            final Path root,
            final Set<Path> paths) {
        final List<String> sorted = paths.stream()
                .map(root::relativize)
                .map(Path::toString)
                .map(DeepSliceInstallationRepository::escapeControls)
                .sorted()
                .toList();
        final List<String> shown = sorted.stream()
                .limit(MAX_INVENTORY_DIAGNOSTICS)
                .toList();
        return shown + (sorted.size() > shown.size()
                ? " (and " + (sorted.size() - shown.size()) + " more)"
                : "");
    }

    private static String escapeControls(final String value) {
        final StringBuilder escaped = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)) {
                escaped.append(String.format(
                        java.util.Locale.ROOT,
                        "\\u%04x",
                        codePoint));
            } else {
                escaped.appendCodePoint(codePoint);
            }
        });
        return escaped.toString();
    }

    private static void rejectAliasedFile(
            final Path path,
            final Set<Object> fileKeys) {
        try {
            final BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.fileKey() != null
                    && !fileKeys.add(attributes.fileKey())) {
                throw new DeepSliceAdapterException(
                        "DeepSlice inventory aliases one physical file");
            }
            try {
                final Object links = Files.getAttribute(
                        path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
                if (links instanceof Number number
                        && number.longValue() != 1) {
                    throw new DeepSliceAdapterException(
                            "DeepSlice asset has unsafe hard links");
                }
            } catch (final UnsupportedOperationException ignored) {
                // File identity still detects aliases inside the inventory.
            }
        } catch (final IOException error) {
            throw new DeepSliceAdapterException(
                    "Could not verify DeepSlice file identity", error);
        }
    }

    private static void inspectPath(
            final Path root,
            final Path path,
            final Set<Path> regularFiles) {
        if (Files.isSymbolicLink(path)) {
            throw new DeepSliceAdapterException(
                    "DeepSlice installation contains a symbolic link");
        }
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            regularFiles.add(path.toAbsolutePath().normalize());
        } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new DeepSliceAdapterException(
                    "DeepSlice installation contains an unsafe entry");
        }
        if (!path.toAbsolutePath().normalize().startsWith(root)) {
            throw new DeepSliceAdapterException(
                    "DeepSlice installation entry escaped its root");
        }
    }

    private static Path resolveAsset(
            final Path root,
            final DeepSliceFileAsset asset) {
        final Path path = root.resolve(
                asset.relativePath()).normalize();
        if (!path.startsWith(root)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)
                || !sameRealPath(path)) {
            throw new DeepSliceAdapterException(
                    "DeepSlice asset is missing or unsafe: "
                            + asset.relativePath());
        }
        try {
            if (Files.size(path) != asset.sizeBytes()
                    || !DeepSliceIntegrity.sha256(path).equals(
                    asset.sha256())
                    || asset.executable() != Files.isExecutable(path)) {
                throw new DeepSliceAdapterException(
                        "DeepSlice asset identity mismatch: "
                                + asset.relativePath());
            }
            return path.toAbsolutePath().normalize();
        } catch (final IOException error) {
            throw new DeepSliceAdapterException(
                    "Could not verify DeepSlice asset: "
                            + asset.relativePath(), error);
        }
    }
}
