package org.atlasalign.plugin.validation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.c01.C01BoundFeatureGrid;
import org.atlasalign.application.c01.C01SearchContext;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.PreviewMapping;
import org.junit.jupiter.api.Test;

class C01FeatureGridAdapterTest {

    @Test
    void createsIndependentGenuineTissueAndAtlasFeatureCopies() {
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
        final int[] templateBefore = template.clone();
        final C01SearchContext context = context(identity());
        final C01FeatureGridAdapter adapter = new C01FeatureGridAdapter();

        final C01PreparedTissueFeatures tissue = adapter.prepareTissue(
                new RegistrationPreview(
                        1, 1, 1,
                        new PreviewMapping(size, size, size, size),
                        previewPixels),
                BinaryMask.fromBooleans(size, size, maskPixels),
                SectionGeometry.FULL,
                size,
                size,
                context,
                identity());
        final C01BoundFeatureGrid atlas = adapter.prepareAtlas(
                new AtlasCoronalPlane(264, size, size, template, annotation),
                context,
                atlasIdentity());

        assertArrayEquals(previewBefore, previewPixels);
        assertArrayEquals(templateBefore, template);
        assertArrayEquals(previewBefore,
                tissue.tissueFeature().grid().intensity());
        assertEquals(template[7], atlas.grid().intensity()[7]);
        assertEquals(size * size,
                tissue.support().completeObserved().foregroundCount());
        assertTrue(tissue.support().internalObserved().foregroundCount()
                < size * size);
    }

    @Test
    void bilinearSamplingUsesPixelCentresAndNearestSupport() {
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
        final C01SearchContext context = context(identity(),
                new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1, 0, 0.5,
                        0, 1, 0.5));

        final C01PreparedTissueFeatures prepared =
                new C01FeatureGridAdapter().prepareTissue(
                        new RegistrationPreview(
                                1, 1, 1,
                                new PreviewMapping(
                                        previewSize, previewSize,
                                        previewSize, previewSize),
                                pixels),
                        BinaryMask.fromBooleans(
                                previewSize, previewSize, mask),
                        SectionGeometry.FULL,
                        atlasSize,
                        atlasSize,
                        context,
                        identity());

        assertEquals(5.5f,
                prepared.tissueFeature().grid().intensity()[0]);
    }

    @Test
    void failsClosedOnIdentityMismatchOrAnnotationOnlyAtlas() {
        final int size = 41;
        final float[] pixels = new float[size * size];
        final boolean[] mask = new boolean[size * size];
        final int[] annotation = new int[size * size];
        Arrays.fill(mask, true);
        Arrays.fill(annotation, 1);
        final C01SearchContext context = context(identity());
        final C01FeatureGridAdapter adapter = new C01FeatureGridAdapter();

        assertThrows(IllegalArgumentException.class,
                () -> adapter.prepareTissue(
                        new RegistrationPreview(1, 1, 1,
                                new PreviewMapping(size, size, size, size),
                                pixels),
                        BinaryMask.fromBooleans(size, size, mask),
                        SectionGeometry.FULL,
                        size, size, context, "f".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.prepareAtlas(
                        AtlasCoronalPlane.annotationOnly(
                                264, size, size, annotation,
                                org.atlasalign.atlas.AtlasPlaneGeometry
                                        .axisAligned(264, size, size,
                                                size, size)),
                        context,
                        atlasIdentity()));
    }

    private static C01SearchContext context(final String source) {
        return context(source, new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0,
                0, 1, 0));
    }

    private static C01SearchContext context(
            final String source,
            final AffineTransform2D transform) {
        return new C01SearchContext(
                source,
                atlasIdentity(),
                C01SearchContext.R3_RELEASE_ID,
                "3".repeat(64),
                new AllenCoronalLevel(264),
                AtlasPlaneTilt.CORONAL,
                transform,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                C01SearchContext.FEATURE_GENERATION_ID);
    }

    private static String identity() {
        return "1".repeat(64);
    }

    private static String atlasIdentity() {
        return "2".repeat(64);
    }
}
