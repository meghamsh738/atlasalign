package org.atlasalign.application.dg;

import java.util.List;
import java.util.Objects;
import org.atlasalign.application.guided.GuidedRunMode;

/** D01 candidate ranking; always manual anatomical provenance. */
public record DgRankingResult(
        DgRankingMethod method,
        List<DgCandidateEvidence> rawCandidates,
        List<DgRankedCandidate> rankedCandidates,
        List<String> unavailableComponents,
        List<String> warnings) {

    public DgRankingResult {
        method = Objects.requireNonNull(method, "method");
        rawCandidates = List.copyOf(rawCandidates);
        rankedCandidates = List.copyOf(rankedCandidates);
        unavailableComponents = List.copyOf(unavailableComponents);
        warnings = List.copyOf(warnings);
        if (!rankedCandidates.isEmpty()
                && rankedCandidates.size() != rawCandidates.size()) {
            throw new IllegalArgumentException(
                    "Assessable D01 rankings must retain every candidate");
        }
    }

    public boolean assessable() {
        return !rankedCandidates.isEmpty();
    }

    public GuidedRunMode provenance() {
        return GuidedRunMode.MANUAL_ANATOMICAL_RANKING;
    }

    public boolean mayProduceAutomaticConsistent() {
        return false;
    }

    public boolean mayProduceGuidedConsistent() {
        return false;
    }
}
