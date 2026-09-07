package org.atlasalign.atlas;

import java.util.Objects;

/**
 * One verified Allen annotation volume with optional grayscale template held
 * for the lifetime of an oblique-plane review. The backing arrays are never
 * exposed or mutated.
 */
public final class AtlasVolume {

    private final int axis0;
    private final int axis1;
    private final int axis2;
    private final short[] template;
    private final int[] annotation;

    AtlasVolume(
            final int axis0,
            final int axis1,
            final int axis2,
            final short[] template,
            final int[] annotation) {
        if (axis0 <= 0 || axis1 <= 0 || axis2 <= 0) {
            throw new IllegalArgumentException(
                    "Atlas volume dimensions must be positive");
        }
        final int voxels = Math.multiplyExact(
                Math.multiplyExact(axis0, axis1), axis2);
        if (annotation == null || annotation.length != voxels
                || template != null && template.length != voxels) {
            throw new IllegalArgumentException(
                    "Atlas volume buffers must match dimensions");
        }
        this.axis0 = axis0;
        this.axis1 = axis1;
        this.axis2 = axis2;
        this.template = template;
        this.annotation = annotation;
    }

    public int axis0() {
        return axis0;
    }

    public int axis1() {
        return axis1;
    }

    public int axis2() {
        return axis2;
    }

    public int templateIntensity(
            final int axis0Index,
            final int axis1Index,
            final int axis2Index) {
        if (template == null) {
            throw new IllegalStateException(
                    "Atlas template volume has not been requested");
        }
        return template[index(axis0Index, axis1Index, axis2Index)] & 0xffff;
    }

    public boolean hasTemplate() {
        return template != null;
    }

    AtlasVolume withTemplate(final short[] nextTemplate) {
        return new AtlasVolume(
                axis0, axis1, axis2, nextTemplate, annotation);
    }

    public int annotationId(
            final int axis0Index,
            final int axis1Index,
            final int axis2Index) {
        return annotation[index(axis0Index, axis1Index, axis2Index)];
    }

    private int index(
            final int axis0Index,
            final int axis1Index,
            final int axis2Index) {
        if (axis0Index < 0 || axis0Index >= axis0
                || axis1Index < 0 || axis1Index >= axis1
                || axis2Index < 0 || axis2Index >= axis2) {
            throw new IndexOutOfBoundsException(
                    "Atlas voxel coordinate is outside the volume");
        }
        return (axis2Index * axis1 + axis1Index) * axis0 + axis0Index;
    }
}
