package org.atlasalign.application.manual;

import java.util.Objects;
import org.atlasalign.application.AllenCoronalLevel;

/** Reviewer-requested bounds only; this contract contains no scoring policy. */
public record PreviewCandidateSpecification(
        String id,
        SourceImageIdentity sourceIdentity,
        VerifiedAtlasIdentity atlasIdentity,
        int inclusiveApStart,
        int inclusiveApEnd,
        double sagittalTiltMinimumDegrees,
        double sagittalTiltMaximumDegrees,
        double horizontalTiltMinimumDegrees,
        double horizontalTiltMaximumDegrees,
        PreviewOnlyStatus status) {

    public PreviewCandidateSpecification {
        id = Objects.requireNonNull(id, "id").trim();
        sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        atlasIdentity = Objects.requireNonNull(atlasIdentity, "atlasIdentity");
        status = Objects.requireNonNull(status, "status");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Specification id must not be blank");
        }
        if (inclusiveApStart < 0 || inclusiveApEnd < inclusiveApStart
                || inclusiveApEnd >= AllenCoronalLevel.PLANE_COUNT) {
            throw new IllegalArgumentException("AP bounds are invalid");
        }
        validateBounds(sagittalTiltMinimumDegrees,
                sagittalTiltMaximumDegrees, "sagittal tilt");
        validateBounds(horizontalTiltMinimumDegrees,
                horizontalTiltMaximumDegrees, "horizontal tilt");
        if (status != PreviewOnlyStatus.PREVIEW_ONLY_NOT_VALIDATED) {
            throw new IllegalArgumentException("Candidate request must remain preview-only");
        }
    }

    private static void validateBounds(
            final double minimum, final double maximum, final String name) {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum)
                || maximum < minimum) {
            throw new IllegalArgumentException(name + " bounds are invalid");
        }
    }
}
