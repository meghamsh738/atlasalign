package org.atlasalign.plugin.validation;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.atlasalign.application.c01.C01ApLocalSearchResult;
import org.atlasalign.application.c01.C01ApPlaneScore;
import org.atlasalign.application.c01.C01ApSearchPlan;

/**
 * Frozen eroded-support result plus the whole-mask C01 diagnostic.
 *
 * <p>Both searches use the same copied tissue grid and the same 33 copied
 * atlas grids. The hashes make that identity visible to the external
 * evaluator rather than trusting a producer-declared winner.</p>
 */
public record C01FeatureSearchEvidence(
        C01ApLocalSearchResult erodedSupport,
        C01ApLocalSearchResult completeMaskDiagnostic,
        String tissueFeatureSha256,
        Map<Integer, String> atlasFeatureSha256ByLevel) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public C01FeatureSearchEvidence {
        erodedSupport = Objects.requireNonNull(
                erodedSupport, "erodedSupport");
        completeMaskDiagnostic = Objects.requireNonNull(
                completeMaskDiagnostic, "completeMaskDiagnostic");
        requireSha256(tissueFeatureSha256, "tissueFeatureSha256");
        atlasFeatureSha256ByLevel = Map.copyOf(Objects.requireNonNull(
                atlasFeatureSha256ByLevel,
                "atlasFeatureSha256ByLevel"));
        if (!erodedSupport.plan().equals(completeMaskDiagnostic.plan())) {
            throw new IllegalArgumentException(
                    "C01 diagnostic searches must share one AP plan");
        }
        final Set<Integer> expectedLevels = new HashSet<>();
        for (int position = 0;
                position < C01ApSearchPlan.CANDIDATE_COUNT;
                position++) {
            final C01ApPlaneScore eroded =
                    erodedSupport.rawScores().get(position);
            final C01ApPlaneScore complete =
                    completeMaskDiagnostic.rawScores().get(position);
            if (!eroded.candidate().equals(complete.candidate())) {
                throw new IllegalArgumentException(
                        "C01 diagnostic candidate order changed");
            }
            expectedLevels.add(eroded.candidate().coronalLevel()
                    .zeroBasedAnteriorPosteriorIndex());
        }
        if (!atlasFeatureSha256ByLevel.keySet().equals(expectedLevels)) {
            throw new IllegalArgumentException(
                    "C01 evidence must hash all and only the 33 atlas grids");
        }
        atlasFeatureSha256ByLevel.values().forEach(value ->
                requireSha256(value, "atlasFeatureSha256"));
    }

    private static void requireSha256(
            final String value,
            final String label) {
        Objects.requireNonNull(value, label);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    label + " must be a lowercase SHA-256");
        }
    }
}
