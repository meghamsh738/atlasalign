package org.atlasalign.plugin.validation;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.atlasalign.application.c02.C02ApLocalSearchResult;
import org.atlasalign.application.c02.C02ApPlaneScore;
import org.atlasalign.application.c02.C02ApSearchPlan;

/** Hash-visible, validation-only evidence for one frozen C02 search. */
public record C02FeatureSearchEvidence(
        C02ApLocalSearchResult result,
        String contextSha256,
        String supportMaskSha256,
        String eligibleCentersSha256,
        String tissueFeatureSha256,
        String tissueDescriptorSha256,
        Map<Integer, String> atlasFeatureSha256ByLevel,
        Map<Integer, String> atlasDescriptorSha256ByLevel) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public C02FeatureSearchEvidence {
        result = Objects.requireNonNull(result, "result");
        requireSha256(contextSha256, "contextSha256");
        requireSha256(supportMaskSha256, "supportMaskSha256");
        requireSha256(eligibleCentersSha256, "eligibleCentersSha256");
        requireSha256(tissueFeatureSha256, "tissueFeatureSha256");
        requireSha256(tissueDescriptorSha256, "tissueDescriptorSha256");
        atlasFeatureSha256ByLevel = Map.copyOf(Objects.requireNonNull(
                atlasFeatureSha256ByLevel,
                "atlasFeatureSha256ByLevel"));
        atlasDescriptorSha256ByLevel = Map.copyOf(Objects.requireNonNull(
                atlasDescriptorSha256ByLevel,
                "atlasDescriptorSha256ByLevel"));
        final Set<Integer> expectedLevels = new HashSet<>();
        for (int position = 0;
                position < C02ApSearchPlan.CANDIDATE_COUNT;
                position++) {
            final C02ApPlaneScore score = result.rawScores().get(position);
            expectedLevels.add(score.candidate().coronalLevel()
                    .zeroBasedAnteriorPosteriorIndex());
        }
        if (!atlasFeatureSha256ByLevel.keySet().equals(expectedLevels)
                || !atlasDescriptorSha256ByLevel.keySet().equals(
                        expectedLevels)) {
            throw new IllegalArgumentException(
                    "C02 evidence must hash all and only the 33 atlas grids");
        }
        atlasFeatureSha256ByLevel.values().forEach(value ->
                requireSha256(value, "atlasFeatureSha256"));
        atlasDescriptorSha256ByLevel.values().forEach(value ->
                requireSha256(value, "atlasDescriptorSha256"));
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
