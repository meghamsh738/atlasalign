package org.atlasalign.application.c01;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.atlasalign.application.AllenCoronalLevel;

/**
 * Frozen C01 enumeration: exactly 33 Allen levels at offsets -16..+16.
 *
 * <p>The plan fails closed near the atlas ends instead of silently clipping
 * the preregistered search window.</p>
 */
public final class C01ApSearchPlan {

    public static final int MINIMUM_AP_OFFSET = -16;
    public static final int MAXIMUM_AP_OFFSET = 16;
    public static final int AP_STEP = 1;
    public static final int CANDIDATE_COUNT = 33;

    private final AllenCoronalLevel center;
    private final List<C01ApPlaneCandidate> candidates;

    private C01ApSearchPlan(
            final AllenCoronalLevel center,
            final List<C01ApPlaneCandidate> candidates) {
        this.center = center;
        this.candidates = List.copyOf(candidates);
    }

    public static C01ApSearchPlan around(
            final AllenCoronalLevel center) {
        Objects.requireNonNull(center, "center");
        final int centerIndex = center.zeroBasedAnteriorPosteriorIndex();
        if (centerIndex + MINIMUM_AP_OFFSET < 0
                || centerIndex + MAXIMUM_AP_OFFSET
                >= AllenCoronalLevel.PLANE_COUNT) {
            throw new IllegalArgumentException(
                    "C01 requires the complete 33-plane search window; "
                            + "the r3 level is too close to an atlas boundary");
        }
        final ArrayList<C01ApPlaneCandidate> result =
                new ArrayList<>(CANDIDATE_COUNT);
        for (int offset = MINIMUM_AP_OFFSET;
                offset <= MAXIMUM_AP_OFFSET;
                offset += AP_STEP) {
            result.add(new C01ApPlaneCandidate(
                    offset,
                    new AllenCoronalLevel(centerIndex + offset)));
        }
        if (result.size() != CANDIDATE_COUNT) {
            throw new IllegalStateException(
                    "C01 candidate enumeration was not exactly 33 planes");
        }
        return new C01ApSearchPlan(center, result);
    }

    public AllenCoronalLevel center() {
        return center;
    }

    public List<C01ApPlaneCandidate> candidates() {
        return candidates;
    }
}
