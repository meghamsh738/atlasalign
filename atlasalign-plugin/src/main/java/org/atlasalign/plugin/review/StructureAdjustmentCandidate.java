package org.atlasalign.plugin.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualWarpControl;

/** Calculated, fully audited shared-field preview for one exact draft hash. */
public record StructureAdjustmentCandidate(
        String draftHash,
        List<ManualWarpControl> validControls,
        ManualHemisphereWarp2D auditedWarp,
        Map<String, Double> retainedFractionByUnitId,
        List<StructureAdjustmentLimit> limitingReports,
        Optional<String> highlightedUnitId) {

    public StructureAdjustmentCandidate {
        draftHash = Objects.requireNonNull(draftHash, "draftHash");
        validControls = List.copyOf(Objects.requireNonNull(
                validControls, "validControls"));
        auditedWarp = Objects.requireNonNull(auditedWarp, "auditedWarp");
        retainedFractionByUnitId = Map.copyOf(new LinkedHashMap<>(
                Objects.requireNonNull(retainedFractionByUnitId,
                        "retainedFractionByUnitId")));
        limitingReports = List.copyOf(Objects.requireNonNull(
                limitingReports, "limitingReports"));
        highlightedUnitId = Objects.requireNonNull(
                highlightedUnitId, "highlightedUnitId");
        if (draftHash.isBlank() || validControls.size() < 4
                || retainedFractionByUnitId.values().stream().anyMatch(
                        value -> value == null || !Double.isFinite(value)
                                || value < 0 || value > 1)) {
            throw new IllegalArgumentException(
                    "Calculated Structure candidate is invalid");
        }
    }

    public boolean completesRequest() {
        return retainedFractionByUnitId.values().stream()
                .allMatch(value -> value >= 1.0 - 1e-12);
    }
}
