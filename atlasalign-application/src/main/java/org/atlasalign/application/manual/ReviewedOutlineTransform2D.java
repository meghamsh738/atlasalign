package org.atlasalign.application.manual;

import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;

/**
 * Immutable reviewer-controlled preview-domain outline transform.
 *
 * <p>The legacy smooth warp and the boundary-authoritative piecewise-affine
 * map implement this contract so an accepted review retains the exact same
 * transform object and content identity that was previewed.</p>
 */
public sealed interface ReviewedOutlineTransform2D
        permits ManualOutlineWarp2D, BoundaryAuthoritativeTransform2D {

    CoordinateSpace2D sourceSpace();

    CoordinateSpace2D destinationSpace();

    Point2D apply(Point2D point);

    Point2D inverse(Point2D point);

    int previewWidth();

    int previewHeight();

    String algorithmRevision();

    String contentSha256();

    /** Geometric dorsal/ventral midline endpoints used by side refinement. */
    MidlineEndpoints hemisphereMidline();

    record MidlineEndpoints(Point2D dorsal, Point2D ventral) {
        public MidlineEndpoints {
            if (dorsal == null || ventral == null || dorsal.equals(ventral)) {
                throw new IllegalArgumentException(
                        "Outline midline endpoints must be distinct");
            }
        }
    }
}
