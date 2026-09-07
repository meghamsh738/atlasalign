package org.atlasalign.application.c01;

import java.util.Objects;
import org.atlasalign.application.AllenCoronalLevel;

/** One immutable AP-only atlas candidate relative to the r3 proposal. */
public record C01ApPlaneCandidate(
        int apOffsetIndices,
        AllenCoronalLevel coronalLevel) {

    public C01ApPlaneCandidate {
        coronalLevel = Objects.requireNonNull(
                coronalLevel, "coronalLevel");
        if (apOffsetIndices < C01ApSearchPlan.MINIMUM_AP_OFFSET
                || apOffsetIndices > C01ApSearchPlan.MAXIMUM_AP_OFFSET) {
            throw new IllegalArgumentException(
                    "C01 AP offset must be within -16..16");
        }
    }
}
