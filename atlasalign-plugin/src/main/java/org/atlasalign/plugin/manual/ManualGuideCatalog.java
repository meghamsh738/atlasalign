package org.atlasalign.plugin.manual;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.manual.VerifiedAtlasGuideIdentity;
import org.atlasalign.plugin.review.AtlasRegionCatalog;
import org.atlasalign.plugin.review.SelectedAtlasRegion;

/** Resolves the small approved guide list against the verified Allen ontology. */
public final class ManualGuideCatalog {

    private static final List<ManualGuideOption> OPTIONS = List.of(
            new ManualGuideOption("DG", "Dentate gyrus (DG)",
                    "Trace each visible DG boundary or blade separately."),
            new ManualGuideOption("DG-sg", "DG granule-cell layer (DG-sg)",
                    "Trace the visible granule-cell layer as an open polyline."),
            new ManualGuideOption("HPF", "Hippocampal formation (HPF)",
                    "Trace only the clearly visible hippocampal formation boundary."),
            new ManualGuideOption("cc", "Corpus callosum (cc)",
                    "Trace the visible callosal arc; leave torn portions as gaps."),
            new ManualGuideOption("VS", "Ventricular system (VS)",
                    "Trace visible ventricular boundaries without closing missing arcs."),
            new ManualGuideOption("root", "Whole-brain boundary reference",
                    "Use this for the outer brain edge; draw the midline separately in Step 2."));

    private final AtlasRegionCatalog catalog;
    private final String atlasIdentitySha256;
    private final String atlasVersion;

    public ManualGuideCatalog(
            final AtlasRegionCatalog catalog,
            final String atlasIdentitySha256,
            final String atlasVersion) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.atlasIdentitySha256 = requireSha256(atlasIdentitySha256);
        this.atlasVersion = Objects.requireNonNull(
                atlasVersion, "atlasVersion").trim();
        if (this.atlasVersion.isEmpty()) {
            throw new IllegalArgumentException("atlasVersion must not be blank");
        }
    }

    public List<ManualGuideOption> options() {
        return OPTIONS;
    }

    public Optional<ResolvedGuide> resolve(final ManualGuideOption option) {
        Objects.requireNonNull(option, "option");
        return catalog.resolveExactAcronym(option.acronym())
                .map(region -> new ResolvedGuide(
                        option,
                        region,
                        new VerifiedAtlasGuideIdentity(
                                atlasIdentitySha256,
                                "Allen CCF structure graph",
                                atlasVersion,
                                region.rootRegionId(),
                                region.acronym())));
    }

    private static String requireSha256(final String value) {
        final String checked = Objects.requireNonNull(value, "value");
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "atlasIdentitySha256 must be a lowercase SHA-256");
        }
        return checked;
    }

    public record ResolvedGuide(
            ManualGuideOption option,
            SelectedAtlasRegion region,
            VerifiedAtlasGuideIdentity identity) {

        public ResolvedGuide {
            option = Objects.requireNonNull(option, "option");
            region = Objects.requireNonNull(region, "region");
            identity = Objects.requireNonNull(identity, "identity");
            if (!option.acronym().equals(region.acronym())
                    || !region.acronym().equals(identity.acronym())) {
                throw new IllegalArgumentException(
                        "Guide option, ontology region, and identity must agree");
            }
        }
    }
}
