package org.atlasalign.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import org.junit.jupiter.api.Test;

class AtlasManifestsTest {

    @Test
    void pinnedAllenManifestHasExpectedIdentityAndBoundedAssets() {
        final AtlasManifest manifest = AtlasManifests.allenMouse25um();

        assertEquals("allen_mouse_25um", manifest.atlasId());
        assertEquals("Allen Mouse CCFv3 2017", manifest.atlasVersion());
        assertEquals(25, manifest.resolutionMicrometers());
        assertEquals("left-posterior-superior", manifest.coordinateSpace());
        assertEquals(37_672_058L, manifest.totalSizeBytes());
        assertEquals(3, manifest.assets().size());
        assertEquals(
                "e4a2b483e842b4c8c1b5452d940ea59e14bc1ebaa38fe6a9c3bacac6db2a8f4b",
                manifest.asset("template").sha256());
    }

    @Test
    void assetRejectsPathTraversal() {
        assertThrows(IllegalArgumentException.class, () -> new AtlasAsset(
                "ontology",
                "../ontology.json",
                URI.create("https://example.invalid/ontology.json"),
                10,
                "0".repeat(64)));
    }
}
