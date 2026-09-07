package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnownCoordinatePredictionStoreTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void appendsUniqueImagesAndNeverReplacesFirstPrediction()
            throws Exception {
        final Path output = temporaryDirectory.resolve("predictions.json");
        KnownCoordinatePredictionStore.appendUnique(
                output, prediction("s102.png", 300));
        final byte[] immutableS102 = Files.readAllBytes(output);
        KnownCoordinatePredictionStore.appendUnique(
                output, prediction("s052.png", 250));
        KnownCoordinatePredictionStore.appendUnique(
                output, prediction("s152.png", 350));

        final JsonNode records =
                MAPPER.readTree(output.toFile()).get("predictions");
        assertEquals(3, records.size());
        assertEquals("s102.png", records.get(0).get("image").asText());
        assertEquals(
                300,
                records.get(0)
                        .get("zeroBasedAnteriorPosteriorIndex").asInt());
        assertEquals("s052.png", records.get(1).get("image").asText());
        assertEquals("s152.png", records.get(2).get("image").asText());

        final byte[] beforeDuplicate = Files.readAllBytes(output);
        assertThrows(
                IOException.class,
                () -> KnownCoordinatePredictionStore.appendUnique(
                        output, prediction("s102.png", 301)));
        assertArrayEquals(beforeDuplicate, Files.readAllBytes(output));
        assertEquals(
                MAPPER.readTree(
                        new ByteArrayInputStream(immutableS102))
                        .get("predictions").get(0),
                MAPPER.readTree(output.toFile())
                        .get("predictions").get(0));
    }

    @Test
    void malformedExistingEntryFailsWithoutChangingFile()
            throws Exception {
        final Path output = temporaryDirectory.resolve("predictions.json");
        Files.writeString(
                output,
                "{\"predictions\":[{\"proposalSource\":\"LOCAL_DEEPSLICE\"}]}");
        final byte[] before = Files.readAllBytes(output);

        assertThrows(
                IOException.class,
                () -> KnownCoordinatePredictionStore.appendUnique(
                        output, prediction("s052.png", 250)));

        assertArrayEquals(before, Files.readAllBytes(output));
    }

    private static ObjectNode prediction(
            final String image,
            final int level) {
        final ObjectNode value = MAPPER.createObjectNode();
        value.put("image", image);
        value.put("zeroBasedAnteriorPosteriorIndex", level);
        return value;
    }
}
