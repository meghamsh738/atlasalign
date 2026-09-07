package org.atlasalign.application;

import java.util.List;
import org.atlasalign.core.Point2D;

/**
 * Read-only atlas-membership projection shared by live visualization and
 * accepted source export. Implementations preserve section-mode side
 * semantics, including overlapping Disjoined halves.
 */
public interface AtlasMembershipProjection {

    List<Point2D> mapPreviewToAtlasCandidates(Point2D previewPoint);

    boolean includesAtlasPoint(Point2D atlasPoint);
}
