package org.atlasalign.plugin.setup;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class RuntimeSettingsTest {
    @Test void defaultsAndSavedPathsArePortableAndRetainOfflineLocations() throws Exception {
        final Preferences prefs = Preferences.userRoot().node("/atlasalign-test/" + UUID.randomUUID());
        try {
            final Path home = Path.of(System.getProperty("java.io.tmpdir"), "user with spaces");
            final RuntimeSettings settings = new RuntimeSettings(prefs, home);
            assertEquals(home.resolve(".atlasalign/atlas/allen_mouse_25um").toAbsolutePath(), settings.paths().atlasCache());
            final Path saved = home.resolve("offline drive/verified atlas");
            settings.saveAtlas(saved);
            settings.saveDeepSlice(home.resolve("optional runtime"), home.resolve("work"));
            assertEquals(saved.toAbsolutePath(), new RuntimeSettings(prefs, home).paths().atlasCache());
            assertEquals(home.resolve("optional runtime").toAbsolutePath(), settings.paths().deepSliceRuntime());
        } finally { prefs.removeNode(); }
    }

    @Test void explicitPropertyOverridesSavedSettingWithoutChangingIt() throws Exception {
        final Preferences prefs = Preferences.userRoot().node("/atlasalign-test/" + UUID.randomUUID());
        final String key = "atlasalign.atlasCache";
        final String before = System.getProperty(key);
        try {
            final Path home = Path.of(System.getProperty("java.io.tmpdir"), "user");
            final RuntimeSettings settings = new RuntimeSettings(prefs, home);
            settings.saveAtlas(home.resolve("saved"));
            System.setProperty(key, home.resolve("override").toString());
            assertEquals(home.resolve("override").toAbsolutePath(), settings.paths().atlasCache());
            System.clearProperty(key);
            assertEquals(home.resolve("saved").toAbsolutePath(), settings.paths().atlasCache());
        } finally {
            if (before == null) System.clearProperty(key); else System.setProperty(key, before);
            prefs.removeNode();
        }
    }
}
