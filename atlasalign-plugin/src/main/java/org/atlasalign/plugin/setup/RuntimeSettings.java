package org.atlasalign.plugin.setup;

import java.nio.file.Path;
import java.util.Objects;
import java.util.prefs.Preferences;

/** Portable user settings shared by all review entry points. No installation-specific paths. */
public final class RuntimeSettings {
    public static final String PREFERENCES_NODE = "/org/atlasalign/runtime";
    public static final String ATLAS_CACHE = "atlasCache";
    public static final String DEEPSLICE_RUNTIME = "deepSliceRuntime";
    public static final String DEEPSLICE_WORK = "deepSliceWork";
    private final Preferences preferences;
    private final Path home;

    public record Paths(Path atlasCache, Path deepSliceRuntime, Path deepSliceWork) { }

    public RuntimeSettings() {
        this(Preferences.userRoot().node(PREFERENCES_NODE), Path.of(System.getProperty("user.home")));
    }

    RuntimeSettings(final Preferences preferences, final Path home) {
        this.preferences = Objects.requireNonNull(preferences);
        this.home = Objects.requireNonNull(home);
    }

    public static Path defaultRoot() {
        return Path.of(System.getProperty("user.home"), ".atlasalign");
    }

    public Paths paths() {
        final Path root = home.resolve(".atlasalign");
        return new Paths(resolve(ATLAS_CACHE, root.resolve("atlas/allen_mouse_25um")),
                resolve(DEEPSLICE_RUNTIME, root.resolve("deepslice/runtime")),
                resolve(DEEPSLICE_WORK, root.resolve("deepslice/work")));
    }

    private Path resolve(final String key, final Path fallback) {
        final String override = System.getProperty("atlasalign." + key);
        final String value = override == null || override.isBlank()
                ? preferences.get(key, fallback.toString()) : override;
        try {
            // Missing saved paths remain visible so removable/offline caches are not silently replaced.
            return Path.of(value).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException invalid) {
            return fallback.toAbsolutePath().normalize();
        }
    }

    public void saveAtlas(final Path atlasCache) {
        preferences.put(ATLAS_CACHE, atlasCache.toAbsolutePath().normalize().toString());
    }

    public void saveDeepSlice(final Path runtime, final Path work) {
        if (runtime != null) preferences.put(DEEPSLICE_RUNTIME, runtime.toAbsolutePath().normalize().toString());
        if (work != null) preferences.put(DEEPSLICE_WORK, work.toAbsolutePath().normalize().toString());
    }
}
