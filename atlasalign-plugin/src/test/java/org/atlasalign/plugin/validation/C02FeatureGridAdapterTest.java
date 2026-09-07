package org.atlasalign.plugin.validation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.c02.C02BoundFeatureGrid;
import org.atlasalign.application.c02.C02SearchContext;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.PreviewMapping;
import org.junit.jupiter.api.Test;

class C02FeatureGridAdapterTest {

    @Test
    void copiesGenuineInputsWithoutMutatingPreviewAtlasOrMask() {
        final int size = 41;
        final float[] previewPixels = new float[size * size];
        final boolean[] maskPixels = new boolean[size * size];
        final int[] template = new int[size * size];
        final int[] annotation = new int[size * size];
        Arrays.fill(maskPixels, true);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                final int index = y * size + x;
                previewPixels[index] = x + y * 0.25f;
                template[index] = x + y;
                annotation[index] = 1;
            }
        }
        final float[] previewBefore = previewPixels.clone();
        final boolean[] maskBefore = maskPixels.clone();
        final int[] templateBefore = template.clone();
        final C02SearchContext context = context(identityTransform());
        final C02FeatureGridAdapter adapter = new C02FeatureGridAdapter();

        final C02PreparedTissueFeatures tissue = adapter.prepareTissue(
                preview(size, previewPixels),
                BinaryMask.fromBooleans(size, size, maskPixels),
                SectionGeometry.FULL,
                size,
                size,
                context,
                sourceIdentity());
        final C02BoundFeatureGrid atlas = adapter.prepareAtlas(
                new AtlasCoronalPlane(
                        264, size, size, template, annotation),
                context,
                atlasIdentity());

        assertArrayEquals(previewBefore, previewPixels);
        assertArrayEquals(maskBefore, maskPixels);
        assertArrayEquals(templateBefore, template);
        assertArrayEquals(previewBefore,
                tissue.tissueFeature().grid().intensity());
        assertEquals(template[7], atlas.grid().intensity()[7]);
        assertEquals(size * size,
                tissue.support().completeObserved().foregroundCount());
    }

    @Test
    void usesPixelCentreBilinearIntensityAndNearestObservedSupport() {
        final int previewSize = 61;
        final int atlasSize = 41;
        final float[] pixels = new float[previewSize * previewSize];
        final boolean[] mask = new boolean[previewSize * previewSize];
        Arrays.fill(mask, true);
        for (int y = 0; y < previewSize; y++) {
            for (int x = 0; x < previewSize; x++) {
                pixels[y * previewSize + x] = x + 10 * y;
            }
        }
        final C02SearchContext context = context(new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0.5,
                0, 1, 0.5));

        final C02PreparedTissueFeatures prepared =
                new C02FeatureGridAdapter().prepareTissue(
                        preview(previewSize, pixels),
                        BinaryMask.fromBooleans(
                                previewSize, previewSize, mask),
                        SectionGeometry.FULL,
                        atlasSize,
                        atlasSize,
                        context,
                        sourceIdentity());

        assertEquals(5.5f,
                prepared.tissueFeature().grid().intensity()[0]);
    }

    @Test
    void failsClosedOnIdentityMismatchOrAnnotationOnlyPlane() {
        final int size = 41;
        final boolean[] mask = new boolean[size * size];
        final int[] annotation = new int[size * size];
        Arrays.fill(mask, true);
        Arrays.fill(annotation, 1);
        final C02SearchContext context = context(identityTransform());
        final C02FeatureGridAdapter adapter = new C02FeatureGridAdapter();

        assertThrows(IllegalArgumentException.class,
                () -> adapter.prepareTissue(
                        preview(size, new float[size * size]),
                        BinaryMask.fromBooleans(size, size, mask),
                        SectionGeometry.FULL,
                        size,
                        size,
                        context,
                        "f".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.prepareAtlas(
                        AtlasCoronalPlane.annotationOnly(
                                264,
                                size,
                                size,
                                annotation,
                                org.atlasalign.atlas.AtlasPlaneGeometry
                                        .axisAligned(
                                                264,
                                                size,
                                                size,
                                                size,
                                                size)),
                        context,
                        atlasIdentity()));
    }

    private static RegistrationPreview preview(
            final int size,
            final float[] pixels) {
        return new RegistrationPreview(
                1,
                1,
                1,
                new PreviewMapping(size, size, size, size),
                pixels);
    }

    private static C02SearchContext context(
            final AffineTransform2D transform) {
        return new C02SearchContext(
                sourceIdentity(),
                atlasIdentity(),
                C02SearchContext.R3_RELEASE_ID,
                "3".repeat(64),
                new AllenCoronalLevel(264),
                AtlasPlaneTilt.CORONAL,
                transform,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                C02SearchContext.FEATURE_GENERATION_ID);
    }

    private static AffineTransform2D identityTransform() {
        return new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0,
                0, 1, 0);
    }

    private static String sourceIdentity() {
        return "1".repeat(64);
    }

    private static String atlasIdentity() {
        return "2".repeat(64);
    }
}
