package org.atlasalign.core;

/** Typed validity retained for one source-calibration field. */
public enum CalibrationFieldStatus {
    VALID,
    UNDEFINED,
    INVALID;

    static CalibrationFieldStatus positiveValue(final double value) {
        if (Double.isNaN(value)) {
            return UNDEFINED;
        }
        return Double.isFinite(value) && value > 0 ? VALID : INVALID;
    }
}
