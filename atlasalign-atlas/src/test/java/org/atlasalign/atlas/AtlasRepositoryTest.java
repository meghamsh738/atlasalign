package org.atlasalign.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtlasRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesAndReloadsOntologyWithoutNetwork() throws Exception {
        final AtlasManifest manifest = createCache(validOntology());

        final VerifiedAtlas atlas =
                new AtlasRepository().open(temporaryDirectory, manifest);

        assertEquals(3, atlas.ontology().size());
        assertEquals("CTX", atlas.ontology().region(8).acronym());
        assertEquals(
                List.of(8, 38),
                atlas.ontology().descendants(997).stream()
                        .map(AtlasRegion::id)
                        .toList());
        assertEquals(
                8,
                atlas.ontology().search("cerebral cortex", 5).get(0).id());
        assertEquals(
                38,
                atlas.ontology().search("HY", 5).get(0).id());
        assertTrue(Files.isRegularFile(atlas.assetPath("annotation")));
    }

    @Test
    void failsClosedWhenAnAssetChanges() throws Exception {
        final AtlasManifest manifest = createCache(validOntology());
        Files.writeString(
                temporaryDirectory.resolve("annotation.nrrd"),
                "tampered");

        final AtlasCacheException error = assertThrows(
                AtlasCacheException.class,
                () -> new AtlasRepository().open(
                        temporaryDirectory, manifest));

        assertTrue(error.getMessage().contains("length mismatch"));
    }

    @Test
    void rejectsARewrittenInstalledManifest() throws Exception {
        final AtlasManifest manifest = createCache(validOntology());
        final AtlasManifest otherVersion = new AtlasManifest(
                manifest.schemaVersion(),
                manifest.atlasId(),
                "unexpected version",
                manifest.resolutionMicrometers(),
                manifest.coordinateSpace(),
                manifest.dimensions(),
                manifest.termsUrl(),
                manifest.citation(),
                manifest.assets());
        new ObjectMapper().writeValue(
                temporaryDirectory.resolve("manifest.json").toFile(),
                otherVersion);

        assertThrows(
                AtlasCacheException.class,
                () -> new AtlasRepository().open(
                        temporaryDirectory, manifest));
    }

    @Test
    void planeLoadReverifiesAssetsChangedAfterOpen()
            throws Exception {
        final AtlasManifest fixture = createCache(validOntology());
        final AtlasManifest officialIdentity =
                new AtlasManifest(
                        1,
                        "allen_mouse_25um",
                        "Allen Mouse CCFv3 2017",
                        25,
                        "left-posterior-superior",
                        List.of(528, 320, 456),
                        fixture.termsUrl(),
                        fixture.citation(),
                        fixture.assets());
        new ObjectMapper().writeValue(
                temporaryDirectory.resolve("manifest.json").toFile(),
                officialIdentity);
        final VerifiedAtlas verified =
                new AtlasRepository().open(
                        temporaryDirectory, officialIdentity);
        Files.writeString(
                temporaryDirectory.resolve("template.nrrd"),
                "changed after verification");

        final AtlasCacheException error = assertThrows(
                AtlasCacheException.class,
                () -> new AtlasCoronalPlaneLoader()
                        .load(verified, 240));

        assertTrue(error.getMessage().contains("length mismatch"));
    }

    @Test
    void rejectsInconsistentOntologyHierarchyAfterIntegrityPasses()
            throws Exception {
        final String inconsistent = validOntology()
                .replace("\"parent_structure_id\": 997",
                        "\"parent_structure_id\": 38");
        final AtlasManifest manifest = createCache(inconsistent);

        assertThrows(
                AtlasCacheException.class,
                () -> new AtlasRepository().open(
                        temporaryDirectory, manifest));
    }

    @Test
    void rejectsSymlinkedAssetEvenWhenTargetBytesWouldVerify()
            throws Exception {
        final AtlasManifest manifest = createCache(validOntology());
        final Path annotation =
                temporaryDirectory.resolve("annotation.nrrd");
        final Path moved =
                temporaryDirectory.resolve("annotation-target.nrrd");
        Files.move(annotation, moved);
        Files.createSymbolicLink(annotation, moved);

        assertThrows(
                AtlasCacheException.class,
                () -> new AtlasRepository().open(
                        temporaryDirectory, manifest));
    }

    @Test
    void rejectsRegionsDisconnectedFromTheSingleRoot() {
        final List<AtlasRegion> disconnected = List.of(
                new AtlasRegion(
                        1, "root", "root", null, 3, List.of()),
                new AtlasRegion(
                        2, "A", "A", 3, 3, List.of(3)),
                new AtlasRegion(
                        3, "B", "B", 2, 3, List.of(2)));

        assertThrows(
                AtlasCacheException.class,
                () -> new AtlasOntology(disconnected));
    }

    private AtlasManifest createCache(final String ontology)
            throws IOException {
        final Path template = temporaryDirectory.resolve("template.nrrd");
        final Path annotation = temporaryDirectory.resolve("annotation.nrrd");
        final Path ontologyPath =
                temporaryDirectory.resolve("ontology.json");
        Files.writeString(template, "template fixture");
        Files.writeString(annotation, "annotation fixture");
        Files.writeString(ontologyPath, ontology);
        final AtlasManifest manifest = new AtlasManifest(
                1,
                "test_mouse_25um",
                "test version",
                25,
                "left-posterior-superior",
                List.of(3, 2, 1),
                "https://example.invalid/terms",
                "Test citation",
                List.of(
                        asset("template", template),
                        asset("annotation", annotation),
                        asset("ontology", ontologyPath)));
        new ObjectMapper().writeValue(
                temporaryDirectory.resolve("manifest.json").toFile(),
                manifest);
        return manifest;
    }

    private static AtlasAsset asset(
            final String role,
            final Path path) throws IOException {
        return new AtlasAsset(
                role,
                path.getFileName().toString(),
                URI.create("https://example.invalid/" + path.getFileName()),
                Files.size(path),
                AtlasIntegrity.sha256(path));
    }

    private static String validOntology() {
        return """
                {
                  "success": true,
                  "msg": [{
                    "id": 997,
                    "acronym": "root",
                    "name": "root",
                    "hemisphere_id": 3,
                    "parent_structure_id": null,
                    "children": [
                      {
                        "id": 8,
                        "acronym": "CTX",
                        "name": "Cerebral cortex",
                        "hemisphere_id": 3,
                        "parent_structure_id": 997,
                        "children": []
                      },
                      {
                        "id": 38,
                        "acronym": "HY",
                        "name": "Hypothalamus",
                        "hemisphere_id": 3,
                        "parent_structure_id": 997,
                        "children": []
                      }
                    ]
                  }]
                }
                """;
    }
}
