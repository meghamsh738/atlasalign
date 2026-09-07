package org.atlasalign.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class InstalledAtlasSmokeTest {

    @Test
    void verifiesPinnedRealCacheAndLoadsOntologyOffline() {
        final String cache = System.getProperty("atlasalign.atlas.cache");
        Assumptions.assumeTrue(
                cache != null && !cache.isBlank(),
                "Set -Datlasalign.atlas.cache to run the installed-atlas smoke test");

        final VerifiedAtlas atlas = new AtlasRepository()
                .openAllenMouse25um(Path.of(cache));

        assertEquals("allen_mouse_25um", atlas.manifest().atlasId());
        assertEquals(1_327, atlas.ontology().size());
        assertEquals(
                "HY",
                atlas.ontology().search("hypothalamus", 5).get(0).acronym());
        assertFalse(atlas.ontology().descendants(38).isEmpty());

        final AtlasCoronalPlane plane =
                new AtlasCoronalPlaneLoader().load(atlas, 240);
        assertEquals(240, plane.zeroBasedAnteriorPosteriorIndex());
        assertEquals(456, plane.width());
        assertEquals(320, plane.height());
        assertEquals(456 * 320, plane.templateIntensity().length);
        assertEquals(456 * 320, plane.annotationId().length);

        final AtlasCoronalPlane first =
                new AtlasCoronalPlaneLoader().load(atlas, 0);
        final AtlasCoronalPlane last =
                new AtlasCoronalPlaneLoader().load(atlas, 527);
        assertEquals(0, first.zeroBasedAnteriorPosteriorIndex());
        assertEquals(527, last.zeroBasedAnteriorPosteriorIndex());
        assertEquals(456 * 320, first.annotationId().length);
        assertEquals(456 * 320, last.annotationId().length);
    }
}
