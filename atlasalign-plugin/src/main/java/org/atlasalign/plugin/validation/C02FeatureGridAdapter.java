package org.atlasalign.plugin.validation;

import java.util.Objects;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.c02.C02BoundFeatureGrid;
import org.atlasalign.application.c02.C02FeatureGrid;
import org.atlasalign.application.c02.C02ObservedSupportMask;
import org.atlasalign.application.c02.C02SearchContext;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.Point2D;

/**
 * Validation-only adapter for genuine C02 tissue and Allen intensity grids.
 *
 * <p>Tissue pixels are copied from the immutable registration preview with
 * bilinear sampling at pixel centres. Observed support uses nearest-neighbour
 * sampling. Atlas template intensities are copied directly. No source,
 * preview, mask, plane, transform, or atlas asset is mutated.</p>
 */
public final class C02FeatureGridAdapter {

    public static final String SAMPLING_REVISION =
            "C02_ALLEN_GRID_BILINEAR_TISSUE_NEAREST_SUPPORT_V1";

    public C02PreparedTissueFeatures prepareTissue(
            final RegistrationPreview preview,
            final BinaryMask previewTissueMask,
            final SectionGeometry geometry,
            final int atlasWidth,
            final int atlasHeight,
            final C02SearchContext context,
            final String verifiedSourceSha256) {
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(previewTissueMask, "previewTissueMask");
        Objects.requireNonNull(geometry, "geometry");
        Objects.requireNonNull(context, "context");
        if (!context.sourceSha256().equals(verifiedSourceSha256)) {
            throw new IllegalArgumentException(
                    "C02 context does not match the verified source identity");
        }
        final int previewWidth = preview.mapping().previewWidth();
        final int previewHeight = preview.mapping().previewHeight();
        if (previewTissueMask.width() != previewWidth
                || previewTissueMask.height() != previewHeight) {
            throw new IllegalArgumentException(
                    "C02 preview and tissue mask dimensions differ");
        }
        if (atlasWidth <= 0 || atlasHeight <= 0) {
            throw new IllegalArgumentException(
                    "C02 Allen-grid dimensions must be positive");
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
        final C02ObservedSupportMask support = C02ObservedSupportMask.create(
                observedMask,
                BinaryMask.empty(atlasWidth, atlasHeight),
                geometry);
        return new C02PreparedTissueFeatures(
                C02BoundFeatureGrid.tissue(
                        context,
                        new C02FeatureGrid(
                                atlasWidth, atlasHeight, resampled)),
                support);
    }

    public C02BoundFeatureGrid prepareAtlas(
            final AtlasCoronalPlane plane,
            final C02SearchContext context,
            final String verifiedAtlasIdentitySha256) {
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(context, "context");
        if (!context.atlasIdentitySha256().equals(
                verifiedAtlasIdentitySha256)) {
            throw new IllegalArgumentException(
                    "C02 context does not match the verified atlas identity");
        }
        if (!plane.hasTemplateIntensity()) {
            throw new IllegalArgumentException(
                    "C02 requires a verified Allen template plane");
        }
        final int[] template = plane.templateIntensity();
        final float[] copied = new float[template.length];
        for (int index = 0; index < template.length; index++) {
            copied[index] = template[index];
        }
        return C02BoundFeatureGrid.atlas(
                context,
                new org.atlasalign.application.AllenCoronalLevel(
                        plane.zeroBasedAnteriorPosteriorIndex()),
                new C02FeatureGrid(plane.width(), plane.height(), copied));
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
                    "C02 observed preview contains a non-finite sample");
        }
        return (float) value;
    }
}
