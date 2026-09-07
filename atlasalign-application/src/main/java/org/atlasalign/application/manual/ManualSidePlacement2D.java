package org.atlasalign.application.manual;

import java.util.Objects;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;

/**
 * Immutable preview-space rotation/translation/positive-axis-scale placement
 * for two raw atlas halves. Reflection is forbidden here; the review
 * controller validates that composition with the upstream atlas placement
 * also leaves the displayed atlas axes perpendicular (no effective shear).
 * This is reviewer geometry, not evidence and not an additional local field.
 */
public record ManualSidePlacement2D(
        AffineTransform2D atlasLeft,
        AffineTransform2D atlasRight) {

    private static final double MINIMUM_DETERMINANT = 0.20;
    private static final double MINIMUM_SCALE = 0.30;
    private static final double MAXIMUM_SCALE = 3.0;
    private static final double MAXIMUM_ANISOTROPY = 3.0;

    public ManualSidePlacement2D {
        atlasLeft = requirePreviewTransform(atlasLeft, "atlasLeft");
        atlasRight = requirePreviewTransform(atlasRight, "atlasRight");
    }

    public static ManualSidePlacement2D identity() {
        return new ManualSidePlacement2D(identityTransform(),
                identityTransform());
    }

    public AffineTransform2D transform(
            final ManualHemisphereWarp2D.AtlasSide side) {
        return Objects.requireNonNull(side, "side")
                == ManualHemisphereWarp2D.AtlasSide.LEFT
                ? atlasLeft : atlasRight;
    }

    public Point2D apply(
            final ManualHemisphereWarp2D.AtlasSide side,
            final Point2D point) {
        return transform(side).apply(Objects.requireNonNull(point, "point"));
    }

    public Point2D inverse(
            final ManualHemisphereWarp2D.AtlasSide side,
            final Point2D point) {
        return transform(side).inverse().apply(
                Objects.requireNonNull(point, "point"));
    }

    public ManualSidePlacement2D withTransform(
            final ManualHemisphereWarp2D.AtlasSide side,
            final AffineTransform2D transform) {
        final AffineTransform2D checked = requirePreviewTransform(
                transform, "transform");
        return side == ManualHemisphereWarp2D.AtlasSide.LEFT
                ? new ManualSidePlacement2D(checked, atlasRight)
                : new ManualSidePlacement2D(atlasLeft, checked);
    }

    public boolean isIdentity() {
        final AffineTransform2D identity = identityTransform();
        return atlasLeft.equals(identity) && atlasRight.equals(identity);
    }

    private static AffineTransform2D identityTransform() {
        return new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0, 0, 1, 0);
    }

    private static AffineTransform2D requirePreviewTransform(
            final AffineTransform2D transform,
            final String name) {
        final AffineTransform2D checked = Objects.requireNonNull(
                transform, name);
        if (checked.sourceSpace() != CoordinateSpace2D.PREVIEW_PIXEL
                || checked.destinationSpace()
                != CoordinateSpace2D.PREVIEW_PIXEL) {
            throw new IllegalArgumentException(
                    "Manual side placement must be an orientation-preserving preview-space transform");
        }
        final double firstSquared = checked.m00() * checked.m00()
                + checked.m10() * checked.m10();
        final double secondSquared = checked.m01() * checked.m01()
                + checked.m11() * checked.m11();
        final double determinant = checked.determinant();
        final double trace = firstSquared + secondSquared;
        final double discriminant = Math.sqrt(Math.max(0,
                trace * trace
                        - 4 * determinant * determinant));
        final double maximum = Math.sqrt(
                (trace + discriminant) * 0.5);
        final double minimum = Math.sqrt(Math.max(0,
                (trace - discriminant) * 0.5));
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum)
                || checked.determinant() < MINIMUM_DETERMINANT
                || minimum < MINIMUM_SCALE || maximum > MAXIMUM_SCALE
                || maximum / minimum > MAXIMUM_ANISOTROPY) {
            throw new ManualWarpException(
                    ManualWarpFailureKind.EXCESSIVE_STRETCH,
                    "That resize would squash or stretch the atlas too far, so the half stayed at its last safe shape.",
                    "Manual side placement must be orientation-preserving, have determinant at least 0.20, singular values from 0.30 to 3.0, and anisotropy at most 3.0; the controller separately verifies shear-free placed atlas axes");
        }
        return checked;
    }
}
