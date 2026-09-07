package org.atlasalign.atlas;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads pinned or installed atlas manifests.
 */
public final class AtlasManifests {

    public static final String ALLEN_MOUSE_25UM_RESOURCE =
            "/org/atlasalign/atlas/allen_mouse_25um.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private AtlasManifests() {
    }

    public static AtlasManifest allenMouse25um() {
        try (InputStream input = AtlasManifests.class.getResourceAsStream(
                ALLEN_MOUSE_25UM_RESOURCE)) {
            if (input == null) {
                throw new AtlasCacheException(
                        "Pinned Allen atlas manifest resource is missing");
            }
            return read(input);
        } catch (final IOException error) {
            throw new AtlasCacheException(
                    "Could not close the pinned Allen atlas manifest", error);
        }
    }

    public static AtlasManifest read(final Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            return read(input);
        } catch (final IOException | IllegalArgumentException error) {
            throw new AtlasCacheException(
                    "Could not read atlas manifest: " + path, error);
        }
    }

    static AtlasManifest read(final InputStream input) throws IOException {
        return MAPPER.readValue(input, AtlasManifest.class);
    }
}
