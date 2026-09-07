package org.atlasalign.plugin.validation;

import java.util.Objects;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.c01.C01BoundFeatureGrid;
import org.atlasalign.application.c01.C01FeatureGrid;
import org.atlasalign.application.c01.C01ObservedSupportMask;
import org.atlasalign.application.c01.C01SearchContext;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.Point2D;

/**
 * Validation-only adapter that creates the genuine C01 Allen-grid features.
 *
 * <p>Tissue pixels are copied from the immutable registration preview onto
 * the Allen 25 µm plane grid with deterministic bilinear interpolation at
 * pixel centres. Observed support is sampled independently with nearest
 * neighbour. Atlas template pixels are copied directly. No input array,
 * source image, atlas plane, transform, or mask is mutated.</p>
 */
public final class C01FeatureGridAdapter {

    public static final String SAMPLING_REVISION =
            "C01_ALLEN_GRID_BILINEAR_TISSUE_NEAREST_SUPPORT_V1";

    public C01PreparedTissueFeatures prepareTissue(
            final RegistrationPreview preview,
            final BinaryMask previewTissueMask,
            final SectionGeometry geometry,
            final int atlasWidth,
            final int atlasHeight,
            final C01SearchContext context,
            final String verifiedSourceSha256) {
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(previewTissueMask, "previewTissueMask");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(context, "context");
        if (!context.sourceSha256().equals(verifiedSourceSha256)) {
            throw new IllegalArgumentException(
                    "C01 context does not match the verified source identity");
        }
        final int previewWidth = preview.mapping().previewWidth();
        final int previewHeight = preview.mapping().previewHeight();
        if (previewTissueMask.width() != previewWidth
                || previewTissueMask.height() != previewHeight) {
            throw new IllegalArgumentException(
                    "C01 preview and tissue mask dimensions differ");
        }
        if (atlasWidth <= 0 || atlasHeight <= 0) {
            throw new IllegalArgumentException(
                    "C01 Allen-grid dimensions must be positive");
        }
        final float[] source = preview.pixels();
        final float[] resampled = new float[
                Math.multiplyExact(atlasWidth, atlasHeight)];
        final boolean[] observed = new boolean[resampled.length];
        for (int y = 0; y < atlasHeight; y++) {
            for (int x = 0; x < atlasWidth; x++) {
                final int output = y * atlasWidth + x;
                final Point2D previewPoint = context.fixedAtlasToFeature()
                        .apply(new Point2D(x, y));
                if (!inside(previewPoint, previewWidth, previewHeight)) {
                    continue;
                }
                resampled[output] = bilinear(
                        source,
                        previewWidth,
                        previewHeight,
                        previewPoint.x(),
                        previewPoint.y());
                observed[output] = previewTissueMask.contains(
                        nearest(previewPoint.x()),
                        nearest(previewPoint.y()));
            }
        }
        final BinaryMask observedMask = BinaryMask.fromBooleans(
                atlasWidth, atlasHeight, observed);
        final C01ObservedSupportMask support =
                C01ObservedSupportMask.create(
                        observedMask,
                        BinaryMask.empty(atlasWidth, atlasHeight),
                        geometry);
        return new C01PreparedTissueFeatures(
                C01BoundFeatureGrid.tissue(
                        context,
                        new C01FeatureGrid(
                                atlasWidth, atlasHeight, resampled)),
                support);
    }

    public C01BoundFeatureGrid prepareAtlas(
            final AtlasCoronalPlane plane,
            final C01SearchContext context,
            final String verifiedAtlasIdentitySha256) {
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(context, "context");
        if (!context.atlasIdentitySha256().equals(
                verifiedAtlasIdentitySha256)) {
            throw new IllegalArgumentException(
                    "C01 context does not match the verified atlas identity");
        }
        if (!plane.hasTemplateIntensity()) {
            throw new IllegalArgumentException(
                    "C01 requires a verified Allen template plane");
        }
        final int[] template = plane.templateIntensity();
        final float[] copied = new float[template.length];
        for (int index = 0; index < template.length; index++) {
            copied[index] = template[index];
        }
        return C01BoundFeatureGrid.atlas(
                context,
                new org.atlasalign.application.AllenCoronalLevel(
                        plane.zeroBasedAnteriorPosteriorIndex()),
                new C01FeatureGrid(
                        plane.width(), plane.height(), copied));
    }

    private static boolean inside(
            final Point2D point,
            final int width,
            final int height) {
        return point.x() >= 0 && point.x() <= width - 1
                && point.y() >= 0 && point.y() <= height - 1;
    }

    private static int nearest(final double value) {
        return (int) Math.floor(value + 0.5);
    }

    private static float bilinear(
            final float[] pixels,
            final int width,
            final int height,
            final double x,
            final double y) {
        final int x0 = (int) Math.floor(x);
        final int y0 = (int) Math.floor(y);
        final int x1 = Math.min(width - 1, x0 + 1);
        final int y1 = Math.min(height - 1, y0 + 1);
        final double fractionX = x - x0;
        final double fractionY = y - y0;
        final double upper = pixels[y0 * width + x0] * (1 - fractionX)
                + pixels[y0 * width + x1] * fractionX;
        final double lower = pixels[y1 * width + x0] * (1 - fractionX)
                + pixels[y1 * width + x1] * fractionX;
        final double value = upper * (1 - fractionY) + lower * fractionY;
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "C01 observed preview contains a non-finite sample");
        }
        return (float) value;
    }
}
