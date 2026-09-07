package org.atlasalign.application.manual;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Preview descriptors that cannot be accepted or promoted by this API. */
public record PreviewCandidateResult(
        String specificationId,
        SourceImageIdentity sourceIdentity,
        VerifiedAtlasIdentity atlasIdentity,
        List<PreviewCandidateDescriptor> candidates,
        PreviewOnlyStatus status) {

    public PreviewCandidateResult {
        specificationId = Objects.requireNonNull(
                specificationId, "specificationId").trim();
        sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        atlasIdentity = Objects.requireNonNull(atlasIdentity, "atlasIdentity");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        status = Objects.requireNonNull(status, "status");
        if (specificationId.isEmpty()
                || status != PreviewOnlyStatus.PREVIEW_ONLY_NOT_VALIDATED) {
            throw new IllegalArgumentException("Result must remain preview-only");
        }
        final Set<String> ids = new HashSet<>();
        for (final PreviewCandidateDescriptor candidate : candidates) {
            Objects.requireNonNull(candidate, "candidates must not contain null");
            if (!ids.add(candidate.id())) {
                throw new IllegalArgumentException("Candidate IDs must be unique");
            }
        }
    }

    public boolean promotable() {
        return false;
    }
}
