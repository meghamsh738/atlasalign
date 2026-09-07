package org.atlasalign.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Test-only durable append-only store for sequential blinded predictions.
 *
 * <p>The validation protocol runs one image at a time. This store deliberately
 * provides no concurrent-writer coordination or cache.
 */
final class KnownCoordinatePredictionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KnownCoordinatePredictionStore() {
    }

    static void appendUnique(
            final Path destination,
            final ObjectNode prediction) throws IOException {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(prediction, "prediction");
        final JsonNode imageNode = prediction.get("image");
        if (imageNode == null || !imageNode.isTextual()
                || imageNode.textValue().isBlank()) {
            throw new IllegalArgumentException(
                    "Prediction must have a non-blank image");
        }

        final Path absolute = destination.toAbsolutePath().normalize();
        final Path parent = Objects.requireNonNull(
                absolute.getParent(), "Prediction output needs a parent");
        Files.createDirectories(parent);
        final ArrayNode accumulated = MAPPER.createArrayNode();
        if (Files.exists(absolute)) {
            final JsonNode existing = MAPPER.readTree(absolute.toFile());
            if (existing == null) {
                throw new IOException(
                        "Existing prediction JSON is empty");
            }
            final JsonNode records = existing.isObject()
                    && existing.has("predictions")
                    ? existing.get("predictions")
                    : existing;
            if (records == null || !records.isArray()) {
                throw new IOException(
                        "Existing prediction JSON must contain a predictions array");
            }
            for (final JsonNode record : records) {
                if (!record.isObject()) {
                    throw new IOException(
                            "Existing prediction array contains a non-object entry");
                }
                final JsonNode existingImage = record.get("image");
                final JsonNode alternateImage = record.get("imageName");
                final JsonNode storedImage;
                if (validImage(existingImage) ^ validImage(alternateImage)) {
                    storedImage = validImage(existingImage)
                            ? existingImage : alternateImage;
                } else {
                    throw new IOException(
                            "Existing prediction must contain exactly one "
                                    + "non-blank image or imageName field");
                }
                if (imageNode.equals(storedImage)) {
                    throw new IOException(
                            "Prediction already exists for image: "
                                    + imageNode.textValue());
                }
                accumulated.add(record.deepCopy());
            }
        }
        accumulated.add(prediction.deepCopy());
        final ObjectNode document = MAPPER.createObjectNode();
        document.set("predictions", accumulated);

        final Path temporary = Files.createTempFile(
                parent, "." + absolute.getFileName() + "-", ".tmp");
        boolean moved = false;
        try {
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(temporary.toFile(), document);
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            forceDirectory(parent);
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static boolean validImage(final JsonNode image) {
        return image != null
                && image.isTextual()
                && !image.textValue().isBlank();
    }

    private static void forceDirectory(final Path directory) {
        try (FileChannel channel = FileChannel.open(
                directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (final IOException | UnsupportedOperationException ignored) {
            // The prediction file itself was fsynced before its atomic rename.
        }
    }
}
