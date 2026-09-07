package org.atlasalign.application.manual;

import java.util.Objects;
import java.util.Optional;

/**
 * Fail-closed manual-warp error with a stable UI-safe explanation.
 * Technical diagnostics remain available for logs without being shown in the
 * ordinary reviewer workflow.
 */
public final class ManualWarpException extends IllegalArgumentException {

    private final ManualWarpFailureKind kind;
    private final String userMessage;
    private final String technicalDetail;
    private final Optional<ManualWarpSafetyReport> safetyReport;

    public ManualWarpException(
            final ManualWarpFailureKind kind,
            final String userMessage,
            final String technicalDetail) {
        this(kind, userMessage, technicalDetail, Optional.empty());
    }

    public ManualWarpException(
            final ManualWarpFailureKind kind,
            final String userMessage,
            final String technicalDetail,
            final ManualWarpSafetyReport safetyReport) {
        this(kind, userMessage, technicalDetail,
                Optional.of(Objects.requireNonNull(
                        safetyReport, "safetyReport")));
    }

    private ManualWarpException(
            final ManualWarpFailureKind kind,
            final String userMessage,
            final String technicalDetail,
            final Optional<ManualWarpSafetyReport> safetyReport) {
        super(Objects.requireNonNull(userMessage, "userMessage"));
        this.kind = Objects.requireNonNull(kind, "kind");
        this.userMessage = userMessage;
        this.technicalDetail = technicalDetail == null
                ? "" : technicalDetail.trim();
        this.safetyReport = Objects.requireNonNull(
                safetyReport, "safetyReport");
    }

    public ManualWarpFailureKind kind() {
        return kind;
    }

    public String userMessage() {
        return userMessage;
    }

    public String technicalDetail() {
        return technicalDetail;
    }

    public Optional<ManualWarpSafetyReport> safetyReport() {
        return safetyReport;
    }
}
