package org.atlasalign.plugin.validation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.c02.C02ApSearchPlan;
import org.atlasalign.application.c02.C02SearchContext;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.atlas.AtlasPlaneGeometry;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.plugin.review.AtlasPlaneRequest;
import org.atlasalign.plugin.review.AtlasPlaneSource;
import org.junit.jupiter.api.Test;

class C02FeatureSearchRunnerTest {

    private static final int SIZE = 51;

    @Test
    void loadsExactlyThirtyThreeGenuineFixedTiltPlanesAndHashesEvidence() {
        final AtlasPlaneTilt tilt = new AtlasPlaneTilt(1.25, -2.5);
        final C02SearchContext context = context(tilt);
        final float[] pixels = pattern();
        final float[] pixelsBefore = pixels.clone();
        final boolean[] observed = new boolean[SIZE * SIZE];
        Arrays.fill(observed, true);
        final List<AtlasPlaneRequest> requests = new ArrayList<>();

        final C02FeatureSearchEvidence evidence =
                new C02FeatureSearchRunner().runEvidence(
                        preview(pixels),
                        BinaryMask.fromBooleans(SIZE, SIZE, observed),
                        SectionGeometry.FULL,
                        context,
                        sourceIdentity(),
                        atlasIdentity(),
                        source(requests, false));

        assertTrue(evidence.result().assessable());
        assertEquals(C02ApSearchPlan.CANDIDATE_COUNT,
                evidence.result().rawScores().size());
        assertEquals(C02ApSearchPlan.CANDIDATE_COUNT, requests.size());
        assertEquals(C02ApSearchPlan.CANDIDATE_COUNT,
                new HashSet<>(requests.stream()
                        .map(AtlasPlaneRequest::zeroBasedAnteriorPosteriorIndex)
                        .toList()).size());
        assertTrue(requests.stream().allMatch(AtlasPlaneRequest::showAnatomy));
        assertTrue(requests.stream().allMatch(request ->
                request.tilt().equals(tilt)));
        assertArrayEquals(pixelsBefore, pixels);
        assertEquals(context.identitySha256(), evidence.contextSha256());
        assertEquals(33, evidence.atlasFeatureSha256ByLevel().size());
        assertEquals(33, evidence.atlasDescriptorSha256ByLevel().size());
        assertTrue(evidence.atlasFeatureSha256ByLevel().values().stream()
                .allMatch(hash -> hash.length() == 64));
        assertTrue(evidence.atlasDescriptorSha256ByLevel().values().stream()
                .allMatch(hash -> hash.length() == 64));
    }

    @Test
    void repeatExecutionProducesIdenticalHashesAndRawScores() {
        final boolean[] observed = new boolean[SIZE * SIZE];
        Arrays.fill(observed, true);
        final BinaryMask mask = BinaryMask.fromBooleans(
                SIZE, SIZE, observed);
        final C02SearchContext context = context(
                new AtlasPlaneTilt(1.25, -2.5));

        final C02FeatureSearchEvidence first =
                new C02FeatureSearchRunner().runEvidence(
                        preview(pattern()),
                        mask,
                        SectionGeometry.FULL,
                        context,
                        sourceIdentity(),
                        atlasIdentity(),
                        source(new ArrayList<>(), false));
        final C02FeatureSearchEvidence repeated =
                new C02FeatureSearchRunner().runEvidence(
                        preview(pattern()),
                        mask,
                        SectionGeometry.FULL,
                        context,
                        sourceIdentity(),
                        atlasIdentity(),
                        source(new ArrayList<>(), false));

        assertEquals(first.contextSha256(), repeated.contextSha256());
        assertEquals(first.supportMaskSha256(),
                repeated.supportMaskSha256());
        assertEquals(first.eligibleCentersSha256(),
                repeated.eligibleCentersSha256());
        assertEquals(first.tissueFeatureSha256(),
                repeated.tissueFeatureSha256());
        assertEquals(first.tissueDescriptorSha256(),
                repeated.tissueDescriptorSha256());
        assertEquals(first.atlasFeatureSha256ByLevel(),
                repeated.atlasFeatureSha256ByLevel());
        assertEquals(first.atlasDescriptorSha256ByLevel(),
                repeated.atlasDescriptorSha256ByLevel());
        assertEquals(first.result().rawScores(),
                repeated.result().rawScores());
    }

    @Test
    void failsClosedWhenAtlasSourceChangesTheRequestedTilt() {
        final boolean[] observed = new boolean[SIZE * SIZE];
        Arrays.fill(observed, true);
        final AtlasPlaneTilt tilt = new AtlasPlaneTilt(1.25, -2.5);

        final IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> new C02FeatureSearchRunner().run(
                        preview(pattern()),
                        BinaryMask.fromBooleans(SIZE, SIZE, observed),
                        SectionGeometry.FULL,
                        context(tilt),
                        sourceIdentity(),
                        atlasIdentity(),
                        source(new ArrayList<>(), true)));

        assertTrue(error.getMessage().contains("wrong level or tilt"));
    }

    private static AtlasPlaneSource source(
            final List<AtlasPlaneRequest> requests,
            final boolean ignoreTilt) {
        return new AtlasPlaneSource() {
            @Override
            public AtlasCoronalPlane load(final int level) {
                throw new AssertionError("C02 must make an explicit request");
            }

            @Override
            public AtlasCoronalPlane load(final AtlasPlaneRequest request) {
                requests.add(request);
                final int level = request.zeroBasedAnteriorPosteriorIndex();
                final AtlasPlaneTilt actualTilt = ignoreTilt
                        ? AtlasPlaneTilt.CORONAL : request.tilt();
                final int[] template = new int[SIZE * SIZE];
                final int[] annotation = new int[SIZE * SIZE];
                final float[] values = pattern();
                for (int index = 0; index < template.length; index++) {
                    template[index] = Math.round(values[index]);
                    annotation[index] = 1;
                }
                return new AtlasCoronalPlane(
                        level,
                        SIZE,
                        SIZE,
                        template,
                        annotation,
                        AtlasPlaneGeometry.create(
                                level,
                                SIZE,
                                SIZE,
                                actualTilt.sagittalDegrees(),
                                actualTilt.horizontalDegrees(),
                                SIZE,
                                SIZE));
            }
        };
    }

    private static RegistrationPreview preview(final float[] pixels) {
        return new RegistrationPreview(
                1,
                1,
                1,
                new PreviewMapping(SIZE, SIZE, SIZE, SIZE),
                pixels);
    }

    private static float[] pattern() {
        final float[] pixels = new float[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                pixels[y * SIZE + x] = (float) (
                        x * 9 + y * 3 + ((x / 5 + y / 7) % 2) * 40);
            }
        }
        return pixels;
    }

    private static C02SearchContext context(final AtlasPlaneTilt tilt) {
        return new C02SearchContext(
                sourceIdentity(),
                atlasIdentity(),
                C02SearchContext.R3_RELEASE_ID,
                "3".repeat(64),
                new AllenCoronalLevel(264),
                tilt,
                new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1, 0, 0,
                        0, 1, 0),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                C02SearchContext.FEATURE_GENERATION_ID);
    }

    private static String sourceIdentity() {
        return "1".repeat(64);
    }

    private static String atlasIdentity() {
        return "2".repeat(64);
    }
}
