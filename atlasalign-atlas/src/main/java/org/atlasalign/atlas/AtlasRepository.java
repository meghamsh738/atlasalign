package org.atlasalign.atlas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Opens an already-installed atlas without network access and fails closed on
 * any manifest, length, checksum, or ontology mismatch.
 */
public final class AtlasRepository {

    public VerifiedAtlas openAllenMouse25um(final Path cacheDirectory) {
        return open(cacheDirectory, AtlasManifests.allenMouse25um());
    }

    VerifiedAtlas open(
            final Path cacheDirectory,
            final AtlasManifest expectedManifest) {
        Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        Objects.requireNonNull(expectedManifest, "expectedManifest");
        final Path root = cacheDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new AtlasCacheException(
                    "Atlas cache directory is missing: " + root);
        }
        final Path installedManifestPath = root.resolve("manifest.json");
        if (!Files.isRegularFile(
                installedManifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new AtlasCacheException(
                    "Atlas cache has no installed manifest");
        }
        final AtlasManifest installed =
                AtlasManifests.read(installedManifestPath);
        if (!expectedManifest.equals(installed)) {
            throw new AtlasCacheException(
                    "Installed atlas manifest does not match the pinned release");
        }
        for (final AtlasAsset asset : expectedManifest.assets()) {
            verifyAsset(root, asset);
        }
        final Path ontologyPath = root.resolve(
                expectedManifest.asset("ontology").relativePath());
        return new VerifiedAtlas(
                root,
                expectedManifest,
                AtlasOntologyLoader.load(ontologyPath));
    }

    private static void verifyAsset(
            final Path root,
            final AtlasAsset asset) {
        final Path path = root.resolve(asset.relativePath()).normalize();
        if (!path.getParent().equals(root)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new AtlasCacheException(
                    "Atlas asset is missing or unsafe: "
                            + asset.relativePath());
        }
        try {
            final long actualSize = Files.size(path);
            if (actualSize != asset.sizeBytes()) {
                throw new AtlasCacheException(
                        "Atlas asset length mismatch for "
                                + asset.relativePath());
            }
            final String actualSha256 = AtlasIntegrity.sha256(path);
            if (!actualSha256.equals(asset.sha256())) {
                throw new AtlasCacheException(
                        "Atlas asset SHA-256 mismatch for "
                                + asset.relativePath());
            }
        } catch (final IOException error) {
            throw new AtlasCacheException(
                    "Could not verify atlas asset "
                            + asset.relativePath(), error);
        }
    }
}
