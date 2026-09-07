package org.atlasalign.atlas;

import java.util.Objects;

/** Samples one oblique coronal plane from a verified in-memory volume. */
public final class AtlasObliquePlaneLoader {

    public AtlasCoronalPlane load(
            final AtlasVolume volume,
            final int zeroBasedAnteriorPosteriorIndex,
            final double sagittalDegrees,
            final double horizontalDegrees) {
        Objects.requireNonNull(volume, "volume");
        final int width = volume.axis2();
        final int height = volume.axis1();
        final AtlasPlaneGeometry geometry = AtlasPlaneGeometry.create(
                zeroBasedAnteriorPosteriorIndex,
                width,
                height,
                sagittalDegrees,
                horizontalDegrees,
                volume.axis1(),
                volume.axis2());
        final int pixels = Math.multiplyExact(width, height);
        final int[] template = volume.hasTemplate()
                ? new int[pixels] : null;
        final int[] annotation = new int[pixels];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int index = y * width + x;
                final double[] voxel = geometry.voxelForPlanePixel(x, y);
                if (!AtlasPlaneGeometry.inside(
                        voxel, volume.axis0(), volume.axis1(), volume.axis2())) {
                    continue;
                }
                final int axis0 = nearest(voxel[0]);
                final int axis1 = nearest(voxel[1]);
                final int axis2 = nearest(voxel[2]);
                if (template != null) {
                    template[index] = volume.templateIntensity(
                            axis0, axis1, axis2);
                }
                annotation[index] = volume.annotationId(
                        axis0, axis1, axis2);
            }
        }
        return template == null
                ? AtlasCoronalPlane.annotationOnly(
                        zeroBasedAnteriorPosteriorIndex,
                        width,
                        height,
                        annotation,
                        geometry)
                : new AtlasCoronalPlane(
                        zeroBasedAnteriorPosteriorIndex,
                        width,
                        height,
                        template,
                        annotation,
                        geometry);
    }

    private static int nearest(final double value) {
        return (int) Math.floor(value + 0.5);
    }
}
