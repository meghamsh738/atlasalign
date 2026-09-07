package org.atlasalign.application.manual;

import java.util.Objects;
import org.atlasalign.application.AllenCoronalLevel;

/** Raw plane descriptor only: it intentionally exposes no score/confidence. */
public record PreviewCandidateDescriptor(
        String id,
        int allenAxis0Index,
        double sagittalTiltDegrees,
        double horizontalTiltDegrees) {

    public PreviewCandidateDescriptor {
        id = Objects.requireNonNull(id, "id").trim();
        if (id.isEmpty() || allenAxis0Index < 0
                || allenAxis0Index >= AllenCoronalLevel.PLANE_COUNT
                || !Double.isFinite(sagittalTiltDegrees)
                || !Double.isFinite(horizontalTiltDegrees)) {
            throw new IllegalArgumentException("Candidate descriptor is invalid");
        }
    }
}
