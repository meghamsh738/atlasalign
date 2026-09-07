package org.atlasalign.application.c02;

import java.util.Objects;
import org.atlasalign.application.AllenCoronalLevel;

/** One immutable AP-only atlas candidate relative to the r3 proposal. */
public record C02ApPlaneCandidate(
        int apOffsetIndices,
        AllenCoronalLevel coronalLevel) {

    public C02ApPlaneCandidate {
        coronalLevel = Objects.requireNonNull(coronalLevel, "coronalLevel");
        if (apOffsetIndices < C02ApSearchPlan.MINIMUM_AP_OFFSET
                || apOffsetIndices > C02ApSearchPlan.MAXIMUM_AP_OFFSET) {
            throw new IllegalArgumentException(
                    "C02 AP offset must be within -16..16");
        }
    }
}
