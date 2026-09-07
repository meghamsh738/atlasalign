package org.atlasalign.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CalibrationMetadataTest {

    @Test
    void preservesUndefinedAndInvalidCalibrationWithoutGuessing() {
        final CalibrationMetadata calibration = new CalibrationMetadata(
                Double.NaN, -2.0, Double.POSITIVE_INFINITY,
                0.0, "", null);

        assertEquals(CalibrationFieldStatus.UNDEFINED,
                calibration.pixelWidthStatus());
        assertEquals(CalibrationFieldStatus.INVALID,
                calibration.pixelHeightStatus());
        assertEquals(CalibrationFieldStatus.INVALID,
                calibration.pixelDepthStatus());
        assertEquals(CalibrationFieldStatus.UNDEFINED,
                calibration.frameIntervalStatus());
        assertEquals(CalibrationFieldStatus.UNDEFINED,
                calibration.spatialUnitStatus());
        assertEquals(CalibrationFieldStatus.UNDEFINED,
                calibration.timeUnitStatus());
        assertEquals(-2.0, calibration.pixelHeight());
        assertEquals(Double.POSITIVE_INFINITY, calibration.pixelDepth());
    }

    @Test
    void classifiesValidCalibrationFields() {
        final CalibrationMetadata calibration = new CalibrationMetadata(
                0.65, 0.65, 2.0, 3.5, "µm", "s");

        assertEquals(CalibrationFieldStatus.VALID,
                calibration.pixelWidthStatus());
        assertEquals(CalibrationFieldStatus.VALID,
                calibration.pixelHeightStatus());
        assertEquals(CalibrationFieldStatus.VALID,
                calibration.pixelDepthStatus());
        assertEquals(CalibrationFieldStatus.VALID,
                calibration.frameIntervalStatus());
        assertEquals(CalibrationFieldStatus.VALID,
                calibration.spatialUnitStatus());
        assertEquals(CalibrationFieldStatus.VALID,
                calibration.timeUnitStatus());
        assertEquals("\u00b5m", calibration.canonicalSpatialUnit().orElseThrow());
        assertEquals("s", calibration.canonicalTimeUnit().orElseThrow());
    }

    @Test
    void rejectsUnsupportedNonblankUnitsInsteadOfGuessing() {
        final CalibrationMetadata calibration = new CalibrationMetadata(
                1.0, 1.0, 1.0, 1.0, "furlong", "fortnight");

        assertEquals(CalibrationFieldStatus.INVALID,
                calibration.spatialUnitStatus());
        assertEquals(CalibrationFieldStatus.INVALID,
                calibration.timeUnitStatus());
        assertEquals(true, calibration.canonicalSpatialUnit().isEmpty());
        assertEquals(true, calibration.canonicalTimeUnit().isEmpty());
    }
}
