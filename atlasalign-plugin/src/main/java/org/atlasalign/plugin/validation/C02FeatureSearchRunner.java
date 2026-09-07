package org.atlasalign.plugin.validation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.VirtualHalfPayloadHashes;
import org.atlasalign.application.c02.C02ApLocalSearchEngine;
import org.atlasalign.application.c02.C02ApLocalSearchResult;
import org.atlasalign.application.c02.C02ApPlaneCandidate;
import org.atlasalign.application.c02.C02ApSearchPlan;
import org.atlasalign.application.c02.C02BoundFeatureGrid;
import org.atlasalign.application.c02.C02MindDescriptorGrid;
import org.atlasalign.application.c02.C02SearchContext;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.atlas.AtlasPlaneGeometry;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.plugin.review.AtlasPlaneRequest;
import org.atlasalign.plugin.review.AtlasPlaneSource;

/**
 * Validation-only bridge from copied source/atlas data to frozen C02.
 *
 * <p>The runner loads exactly 33 genuine template planes at the immutable r3
 * tilt. It does not change the source, atlas, r3 proposal, review state, or
 * production Fiji behavior.</p>
 */
public final class C02FeatureSearchRunner {

    private final C02FeatureGridAdapter adapter;
    private final C02ApLocalSearchEngine engine;

    public C02FeatureSearchRunner() {
        this(new C02FeatureGridAdapter(), new C02ApLocalSearchEngine());
    }

    C02FeatureSearchRunner(
            final C02FeatureGridAdapter adapter,
            final C02ApLocalSearchEngine engine) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    public C02ApLocalSearchResult run(
            final RegistrationPreview preview,
            final BinaryMask previewTissueMask,
            final SectionGeometry geometry,
            final C02SearchContext context,
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
                atlasPlaneSource).result();
    }

    public C02FeatureSearchEvidence runEvidence(
            final RegistrationPreview preview,
            final BinaryMask previewTissueMask,
            final SectionGeometry geometry,
            final C02SearchContext context,
            final String verifiedSourceSha256,
            final String verifiedAtlasIdentitySha256,
            final AtlasPlaneSource atlasPlaneSource) {
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(previewTissueMask, "previewTissueMask");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(atlasPlaneSource, "atlasPlaneSource");

        final C02ApSearchPlan plan = C02ApSearchPlan.around(
                context.r3Level());
        final Map<Integer, AtlasCoronalPlane> loaded = new HashMap<>();
        final Map<Integer, C02BoundFeatureGrid> features = new HashMap<>();
        final AtlasCoronalPlane center = loadExactPlane(
                context.r3Level().zeroBasedAnteriorPosteriorIndex(),
                context,
                atlasPlaneSource);
        loaded.put(center.zeroBasedAnteriorPosteriorIndex(), center);

        final C02PreparedTissueFeatures tissue = adapter.prepareTissue(
                preview,
                previewTissueMask,
                geometry,
                center.width(),
                center.height(),
                context,
                verifiedSourceSha256);
        final java.util.function.Function<C02ApPlaneCandidate,
                C02BoundFeatureGrid> featureProvider = candidate ->
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
        final C02MindDescriptorGrid[] tissueDescriptor =
                new C02MindDescriptorGrid[1];
        final Map<Integer, String> descriptorHashes = new HashMap<>();
        final C02ApLocalSearchResult result = engine.searchFeatureGrids(
                plan,
                context,
                tissue.tissueFeature(),
                tissue.support(),
                featureProvider,
                descriptor -> tissueDescriptor[0] = descriptor,
                (candidate, descriptor) -> descriptorHashes.put(
                        candidate.coronalLevel()
                                .zeroBasedAnteriorPosteriorIndex(),
                        descriptor.descriptorSha256()));
        if (tissueDescriptor[0] == null) {
            throw new IllegalStateException(
                    "C02 search did not expose its tissue descriptor");
        }
        final Map<Integer, String> featureHashes = new HashMap<>();
        for (final C02ApPlaneCandidate candidate : plan.candidates()) {
            final int level = candidate.coronalLevel()
                    .zeroBasedAnteriorPosteriorIndex();
            final C02BoundFeatureGrid feature = featureProvider.apply(candidate);
            featureHashes.put(level, feature.grid().pixelSha256());
        }
        return new C02FeatureSearchEvidence(
                result,
                context.identitySha256(),
                VirtualHalfPayloadHashes.maskSha256(
                        tissue.support().completeObserved()),
                VirtualHalfPayloadHashes.maskSha256(
                        tissueDescriptor[0].eligibleCenters()),
                tissue.tissueFeature().grid().pixelSha256(),
                tissueDescriptor[0].descriptorSha256(),
                featureHashes,
                descriptorHashes);
    }

    private C02BoundFeatureGrid prepareCandidate(
            final C02ApPlaneCandidate candidate,
            final int expectedWidth,
            final int expectedHeight,
            final C02SearchContext context,
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
                    "C02 candidate atlas planes changed dimensions");
        }
        return adapter.prepareAtlas(
                plane, context, verifiedAtlasIdentitySha256);
    }

    private static AtlasCoronalPlane loadExactPlane(
            final int level,
            final C02SearchContext context,
            final AtlasPlaneSource source) {
        final AtlasCoronalPlane plane = Objects.requireNonNull(
                source.load(new AtlasPlaneRequest(
                        new org.atlasalign.application.AllenCoronalLevel(level),
                        context.fixedTilt(),
                        true)),
                "C02 atlas plane source returned null");
        final AtlasPlaneGeometry geometry = plane.geometry();
        if (plane.zeroBasedAnteriorPosteriorIndex() != level
                || geometry.zeroBasedAnteriorPosteriorIndex() != level
                || bits(geometry.sagittalDegrees())
                        != bits(context.fixedTilt().sagittalDegrees())
                || bits(geometry.horizontalDegrees())
                        != bits(context.fixedTilt().horizontalDegrees())) {
            throw new IllegalArgumentException(
                    "C02 atlas source returned the wrong level or tilt");
        }
        if (!plane.hasTemplateIntensity()) {
            throw new IllegalArgumentException(
                    "C02 requires genuine Allen template intensity");
        }
        return plane;
    }

    private static long bits(final double value) {
        return Double.doubleToRawLongBits(value);
    }
}
