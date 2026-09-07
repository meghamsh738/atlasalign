package org.atlasalign.application.manual;

/** Stable, user-actionable category for a rejected manual-warp request. */
public enum ManualWarpFailureKind {
    CONTROL_LIMIT,
    TOO_FEW_CONTROLS,
    FOLD_RISK,
    EXCESSIVE_STRETCH,
    EXCESSIVE_DISPLACEMENT,
    OUTSIDE_WORKSPACE,
    STALE_RESULT,
    INVALID_CONTROL,
    UNKNOWN
}
