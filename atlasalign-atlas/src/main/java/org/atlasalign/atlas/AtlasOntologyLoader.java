package org.atlasalign.atlas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class AtlasOntologyLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AtlasOntologyLoader() {
    }

    static AtlasOntology load(final Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            final JsonNode document = MAPPER.readTree(input);
            if (!document.path("success").asBoolean(false)) {
                throw new AtlasCacheException(
                        "Allen ontology response does not report success");
            }
            final JsonNode roots = document.path("msg");
            if (!roots.isArray() || roots.size() != 1) {
                throw new AtlasCacheException(
                        "Allen ontology must contain exactly one root node");
            }
            final List<AtlasRegion> regions = new ArrayList<>();
            flatten(roots.get(0), null, regions);
            return new AtlasOntology(regions);
        } catch (final IOException | IllegalArgumentException error) {
            throw new AtlasCacheException(
                    "Could not load verified atlas ontology: " + path, error);
        }
    }

    private static void flatten(
            final JsonNode node,
            final Integer expectedParent,
            final List<AtlasRegion> regions) {
        requireObject(node);
        final int id = requirePositiveInt(node, "id");
        final JsonNode parentNode = node.get("parent_structure_id");
        final Integer declaredParent = parentNode == null || parentNode.isNull()
                ? null
                : requirePositiveInt(node, "parent_structure_id");
        if (!Objects.equals(expectedParent, declaredParent)) {
            throw new AtlasCacheException(
                    "Ontology nesting disagrees with parent_structure_id for "
                            + id);
        }
        final JsonNode childrenNode = node.get("children");
        if (childrenNode == null || !childrenNode.isArray()) {
            throw new AtlasCacheException(
                    "Ontology region " + id + " has no children array");
        }
        final List<Integer> childIds = new ArrayList<>();
        for (final JsonNode child : childrenNode) {
            childIds.add(requirePositiveInt(child, "id"));
        }
        regions.add(new AtlasRegion(
                id,
                requireText(node, "acronym"),
                requireText(node, "name"),
                declaredParent,
                requirePositiveInt(node, "hemisphere_id"),
                childIds));
        for (final JsonNode child : childrenNode) {
            flatten(child, id, regions);
        }
    }

    private static void requireObject(final JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new AtlasCacheException(
                    "Ontology contains a non-object region");
        }
    }

    private static int requirePositiveInt(
            final JsonNode node,
            final String field) {
        requireObject(node);
        final JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt() || value.asInt() <= 0) {
            throw new AtlasCacheException(
                    "Ontology field " + field + " must be a positive integer");
        }
        return value.asInt();
    }

    private static String requireText(
            final JsonNode node,
            final String field) {
        final JsonNode value = node.get(field);
        if (value == null || !value.isTextual()
                || value.asText().isBlank()) {
            throw new AtlasCacheException(
                    "Ontology field " + field + " must be non-blank text");
        }
        return value.asText();
    }
}
