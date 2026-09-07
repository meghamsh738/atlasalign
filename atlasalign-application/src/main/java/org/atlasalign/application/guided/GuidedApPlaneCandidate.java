package org.atlasalign.application.guided;

import java.util.Objects;
import org.atlasalign.application.AllenCoronalLevel;

/** One candidate in a computation-matched 33-plane guided AP search. */
public record GuidedApPlaneCandidate(
        int ordinal,
        AllenCoronalLevel coronalLevel,
        int offsetFromR3Indices) {

    public GuidedApPlaneCandidate {
        if (ordinal < 0 || ordinal >= GuidedApSearchPlan.CANDIDATE_COUNT) {
            throw new IllegalArgumentException(
                    "Guided candidate ordinal must be in 0..32");
        }
        coronalLevel = Objects.requireNonNull(
                coronalLevel, "coronalLevel");
    }
}
