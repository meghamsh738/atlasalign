package org.atlasalign.application.manual;

/** Stable scientific gate that limited or rejected a manual-warp request. */
public enum ManualWarpSafetyGate {
    DETERMINANT,
    MINIMUM_SINGULAR_VALUE,
    MAXIMUM_SINGULAR_VALUE,
    ANISOTROPY,
    DISPLACEMENT,
    ROUND_TRIP,
    WORKSPACE,
    SEAM,
    CONTROL_TOPOLOGY,
    CONTROL_COVERAGE,
    CONTROL_CAPACITY,
    FINITE_GEOMETRY,
    UNKNOWN
}
