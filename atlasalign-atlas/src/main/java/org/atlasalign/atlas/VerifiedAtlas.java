package org.atlasalign.atlas;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Atlas handle created only by {@link AtlasRepository} after every pinned
 * asset and the ontology hierarchy have passed verification.
 */
public final class VerifiedAtlas {

    private final Path cacheDirectory;
    private final AtlasManifest manifest;
    private final AtlasOntology ontology;

    VerifiedAtlas(
            final Path cacheDirectory,
            final AtlasManifest manifest,
            final AtlasOntology ontology) {
        this.cacheDirectory = Objects.requireNonNull(
                cacheDirectory, "cacheDirectory")
                .toAbsolutePath().normalize();
        this.manifest = Objects.requireNonNull(
                manifest, "manifest");
        this.ontology = Objects.requireNonNull(
                ontology, "ontology");
    }

    public Path cacheDirectory() {
        return cacheDirectory;
    }

    public AtlasManifest manifest() {
        return manifest;
    }

    public AtlasOntology ontology() {
        return ontology;
    }

    public Path assetPath(final String role) {
        return cacheDirectory.resolve(
                manifest.asset(role).relativePath());
    }
}
