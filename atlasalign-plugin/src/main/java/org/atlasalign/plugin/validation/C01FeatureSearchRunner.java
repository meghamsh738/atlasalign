package org.atlasalign.plugin.validation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.c01.C01ApLocalSearchEngine;
import org.atlasalign.application.c01.C01ApLocalSearchResult;
import org.atlasalign.application.c01.C01ApPlaneCandidate;
import org.atlasalign.application.c01.C01ApSearchPlan;
import org.atlasalign.application.c01.C01BoundFeatureGrid;
import org.atlasalign.application.c01.C01SearchContext;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.atlas.AtlasPlaneGeometry;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.plugin.review.AtlasPlaneRequest;
import org.atlasalign.plugin.review.AtlasPlaneSource;

/**
 * Validation-only bridge from copied source/atlas data to the frozen C01
 * 33-plane search engine.
 *
 * <p>The runner loads genuine Allen template planes at the immutable r3 tilt,
 * prepares one copied tissue feature grid, and delegates all scoring and
 * ranking to {@link C01ApLocalSearchEngine}. It does not alter the C01 metric,
 * source image, atlas assets, automatic proposal, or production review
 * state.</p>
 */
public final class C01FeatureSearchRunner {

    private final C01FeatureGridAdapter adapter;
    private final C01ApLocalSearchEngine engine;

    public C01FeatureSearchRunner() {
        this(new C01FeatureGridAdapter(), new C01ApLocalSearchEngine());
    }

    C01FeatureSearchRunner(
            final C01FeatureGridAdapter adapter,
            final C01ApLocalSearchEngine engine) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public C01ApLocalSearchResult run(
            final RegistrationPreview preview,
            final BinaryMask previewTissueMask,
            final SectionGeometry geometry,
            final C01SearchContext context,
            final String verifiedSourceSha256,
            final String verifiedAtlasIdentitySha256,
            final AtlasPlaneSource atlasPlaneSource) {
        return runEvidence(
                preview,
                previewTissueMask,
                geometry,
                context,
                verifiedSourceSha256,
                verifiedAtlasIdentitySha256,
                atlasPlaneSource).erodedSupport();
    }

    /**
     * Runs the frozen eroded-support search and its preregistered complete-mask
     * diagnostic over one identical set of copied feature grids.
     */
    public C01FeatureSearchEvidence runEvidence(
            final RegistrationPreview preview,
            final BinaryMask previewTissueMask,
            final SectionGeometry geometry,
            final C01SearchContext context,
            final String verifiedSourceSha256,
            final String verifiedAtlasIdentitySha256,
            final AtlasPlaneSource atlasPlaneSource) {
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(previewTissueMask, "previewTissueMask");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(atlasPlaneSource, "atlasPlaneSource");

        final C01ApSearchPlan plan = C01ApSearchPlan.around(
                context.r3Level());
        final Map<Integer, AtlasCoronalPlane> loaded = new HashMap<>();
        final Map<Integer, C01BoundFeatureGrid> features = new HashMap<>();
        final AtlasCoronalPlane center = loadExactPlane(
                context.r3Level().zeroBasedAnteriorPosteriorIndex(),
                context,
                atlasPlaneSource);
        loaded.put(center.zeroBasedAnteriorPosteriorIndex(), center);

        final C01PreparedTissueFeatures tissue = adapter.prepareTissue(
                preview,
                previewTissueMask,
                geometry,
                center.width(),
                center.height(),
                context,
                verifiedSourceSha256);

        final java.util.function.Function<C01ApPlaneCandidate,
                C01BoundFeatureGrid> featureProvider = candidate ->
                        features.computeIfAbsent(
                                candidate.coronalLevel()
                                        .zeroBasedAnteriorPosteriorIndex(),
                                ignored -> prepareCandidate(
                                        candidate,
                                        center.width(),
                                        center.height(),
                                        context,
                                        verifiedAtlasIdentitySha256,
                                        atlasPlaneSource,
                                        loaded));
        final C01ApLocalSearchResult eroded = engine.searchFeatureGrids(
                plan,
                context,
                tissue.tissueFeature(),
                tissue.support(),
                featureProvider);
        final C01ApLocalSearchResult complete = engine.searchFeatureGrids(
                plan,
                context,
                tissue.tissueFeature(),
                tissue.support().completeMaskDiagnostic(),
                featureProvider);
        final Map<Integer, String> atlasHashes = new HashMap<>();
        features.forEach((level, feature) -> atlasHashes.put(
                level, feature.grid().pixelSha256()));
        return new C01FeatureSearchEvidence(
                eroded,
                complete,
                tissue.tissueFeature().grid().pixelSha256(),
                atlasHashes);
    }

    private C01BoundFeatureGrid prepareCandidate(
            final C01ApPlaneCandidate candidate,
            final int expectedWidth,
            final int expectedHeight,
            final C01SearchContext context,
            final String verifiedAtlasIdentitySha256,
            final AtlasPlaneSource source,
            final Map<Integer, AtlasCoronalPlane> loaded) {
        final int level = candidate.coronalLevel()
                .zeroBasedAnteriorPosteriorIndex();
        final AtlasCoronalPlane plane = loaded.computeIfAbsent(
                level,
                ignored -> loadExactPlane(level, context, source));
        if (plane.width() != expectedWidth
                || plane.height() != expectedHeight) {
            throw new IllegalArgumentException(
                    "C01 candidate atlas planes changed dimensions");
        }
        return adapter.prepareAtlas(
                plane, context, verifiedAtlasIdentitySha256);
    }

    private static AtlasCoronalPlane loadExactPlane(
            final int level,
            final C01SearchContext context,
            final AtlasPlaneSource source) {
        final AtlasCoronalPlane plane = Objects.requireNonNull(
                source.load(new AtlasPlaneRequest(
                        new org.atlasalign.application.AllenCoronalLevel(level),
                        context.fixedTilt(),
                        true)),
                "C01 atlas plane source returned null");
        final AtlasPlaneGeometry geometry = plane.geometry();
        if (plane.zeroBasedAnteriorPosteriorIndex() != level
                || geometry.zeroBasedAnteriorPosteriorIndex() != level
                || bits(geometry.sagittalDegrees())
                        != bits(context.fixedTilt().sagittalDegrees())
                || bits(geometry.horizontalDegrees())
                        != bits(context.fixedTilt().horizontalDegrees())) {
            throw new IllegalArgumentException(
                    "C01 atlas source returned the wrong level or tilt");
        }
        if (!plane.hasTemplateIntensity()) {
            throw new IllegalArgumentException(
                    "C01 requires genuine Allen template intensity");
        }
        return plane;
    }

    private static long bits(final double value) {
        return Double.doubleToRawLongBits(value);
    }
}
