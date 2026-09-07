package org.atlasalign.application;

import java.util.Objects;

/**
 * Reviewer-controlled tilt of the Allen oblique-coronal cutting plane.
 *
 * <p>The angles are expressed in degrees and are deliberately kept separate
 * from atlas left/right reflection and anatomical laterality.  The atlas
 * sampler documents the rotation order used to turn these values into a
 * three-dimensional plane.</p>
 */
public record AtlasPlaneTilt(
        double sagittalDegrees,
        double horizontalDegrees) {

    public static final double MAXIMUM_ABSOLUTE_DEGREES = 45.0;
    public static final AtlasPlaneTilt CORONAL = new AtlasPlaneTilt(0, 0);

    public AtlasPlaneTilt {
        requireAngle(sagittalDegrees, "sagittalDegrees");
        requireAngle(horizontalDegrees, "horizontalDegrees");
    }

    public AtlasPlaneTilt withSagittalDelta(final double deltaDegrees) {
        requireFinite(deltaDegrees, "deltaDegrees");
        return new AtlasPlaneTilt(
                sagittalDegrees + deltaDegrees,
                horizontalDegrees);
    }

    public AtlasPlaneTilt withHorizontalDelta(final double deltaDegrees) {
        requireFinite(deltaDegrees, "deltaDegrees");
        return new AtlasPlaneTilt(
                sagittalDegrees,
                horizontalDegrees + deltaDegrees);
    }

    private static void requireAngle(
            final double value,
            final String name) {
        requireFinite(value, name);
        if (Math.abs(value) > MAXIMUM_ABSOLUTE_DEGREES) {
            throw new IllegalArgumentException(
                    name + " must be within +/-"
                            + MAXIMUM_ABSOLUTE_DEGREES + " degrees");
        }
    }

    private static void requireFinite(
            final double value,
            final String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
