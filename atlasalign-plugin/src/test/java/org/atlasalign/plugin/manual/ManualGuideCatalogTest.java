package org.atlasalign.plugin.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ManualGuideCatalogTest {

    @Test
    void fixedGuideOptionsResolveToExactVerifiedIdentities() {
        final String atlasHash = "b".repeat(64);
        final ManualGuideCatalog catalog = new ManualGuideCatalog(
                new GuidedManualPluginFixtures.FakeAtlas(),
                atlasHash, "CCFv3-test");

        assertEquals(List.of("DG", "DG-sg", "HPF", "cc", "VS", "root"),
                catalog.options().stream()
                        .map(ManualGuideOption::acronym).toList());
        for (final ManualGuideOption option : catalog.options()) {
            final ManualGuideCatalog.ResolvedGuide resolved = catalog
                    .resolve(option).orElseThrow();
            assertEquals(option.acronym(), resolved.region().acronym());
            assertEquals(option.acronym(), resolved.identity().acronym());
            assertEquals(atlasHash,
                    resolved.identity().atlasIdentitySha256());
            assertEquals("CCFv3-test", resolved.identity().ontologyVersion());
        }
    }

    @Test
    void resolutionDoesNotFallBackToFuzzyOrUnknownRegion() {
        final ManualGuideCatalog catalog = new ManualGuideCatalog(
                acronym -> java.util.Optional.empty(),
                "c".repeat(64), "CCFv3-test");

        assertTrue(catalog.resolve(catalog.options().get(0)).isEmpty());
    }
}
