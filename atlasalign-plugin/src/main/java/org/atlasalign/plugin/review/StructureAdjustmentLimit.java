package org.atlasalign.plugin.review;

import java.util.Objects;
import org.atlasalign.application.manual.ManualWarpSafetyReport;

/** One locally limited Structure pair/dot and the complete-field audit gate. */
public record StructureAdjustmentLimit(
        String unitId,
        ManualWarpSafetyReport report) {

    public StructureAdjustmentLimit {
        unitId = Objects.requireNonNull(unitId, "unitId").trim();
        report = Objects.requireNonNull(report, "report");
        if (unitId.isEmpty()) {
            throw new IllegalArgumentException("unitId must not be blank");
        }
    }
}
