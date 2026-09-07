package org.atlasalign.application.manual;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One audited, unapplied nonlinear outer-border deformation preview. */
public record BoundaryWarpCandidate(
        BoundaryWarpRequest request,
        List<BoundaryFitMatch> matches,
        List<BoundaryFitMatch> auditedMatches,
        double safeStepFraction,
        List<ManualWarpControl> replacementControls,
        ManualHemisphereWarp2D validatedWarp,
        ManualHemisphereWarp2D previewDeltaWarp,
        String solverRevision,
        String inputHash,
        Optional<ManualWarpSafetyReport> limitingSafetyReport) {

    public BoundaryWarpCandidate {
        request = Objects.requireNonNull(request, "request");
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        auditedMatches = List.copyOf(Objects.requireNonNull(
                auditedMatches, "auditedMatches"));
        if (!Double.isFinite(safeStepFraction)
                || safeStepFraction <= 0 || safeStepFraction > 1) {
            throw new IllegalArgumentException(
                    "Boundary-warp safe step must be in (0, 1]");
        }
        replacementControls = List.copyOf(Objects.requireNonNull(
                replacementControls, "replacementControls"));
        validatedWarp = Objects.requireNonNull(
                validatedWarp, "validatedWarp");
        previewDeltaWarp = Objects.requireNonNull(
                previewDeltaWarp, "previewDeltaWarp");
        if (solverRevision == null || solverRevision.isBlank()
                || inputHash == null
                || !inputHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Boundary-warp solver and input identities are required");
        }
        limitingSafetyReport = Objects.requireNonNull(
                limitingSafetyReport, "limitingSafetyReport");
    }

    public BoundaryWarpCandidate(
            final BoundaryWarpRequest request,
            final List<BoundaryFitMatch> matches,
            final List<BoundaryFitMatch> auditedMatches,
            final double safeStepFraction,
            final List<ManualWarpControl> replacementControls,
            final ManualHemisphereWarp2D validatedWarp,
            final ManualHemisphereWarp2D previewDeltaWarp,
            final String solverRevision,
            final String inputHash) {
        this(request, matches, auditedMatches, safeStepFraction,
                replacementControls, validatedWarp, previewDeltaWarp,
                solverRevision, inputHash, Optional.empty());
    }

    public long contentRevision() {
        return request.contentRevision();
    }

    public int includedMatchCount() {
        return (int) matches.stream().filter(
                BoundaryFitMatch::included).count();
    }

    /** Whether the audited field reaches every currently requested endpoint. */
    public boolean completesRequestedWarp() {
        return safeStepFraction >= 1.0 - 1e-12;
    }

    public BoundaryWarpCandidate withLimitingSafetyReport(
            final ManualWarpSafetyReport report) {
        return new BoundaryWarpCandidate(request, matches, auditedMatches,
                safeStepFraction, replacementControls, validatedWarp,
                previewDeltaWarp, solverRevision, inputHash,
                Optional.of(Objects.requireNonNull(report, "report")));
    }
}
