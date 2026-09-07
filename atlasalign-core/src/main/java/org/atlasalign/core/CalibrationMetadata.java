package org.atlasalign.core;

import java.util.Objects;
import java.util.Optional;

public record CalibrationMetadata(
        double pixelWidth,
        double pixelHeight,
        double pixelDepth,
        double frameInterval,
        String spatialUnit,
        String timeUnit) {

    public CalibrationMetadata {
        spatialUnit = Objects.requireNonNullElse(spatialUnit, "");
        timeUnit = Objects.requireNonNullElse(timeUnit, "");
    }

    /**
     * Fiji files occasionally carry absent or malformed calibration.  Intake
     * preserves those exact values instead of replacing them with guessed
     * defaults; export records this typed status and omits invalid OME fields.
     */
    public CalibrationFieldStatus pixelWidthStatus() {
        return CalibrationFieldStatus.positiveValue(pixelWidth);
    }

    public CalibrationFieldStatus pixelHeightStatus() {
        return CalibrationFieldStatus.positiveValue(pixelHeight);
    }

    public CalibrationFieldStatus pixelDepthStatus() {
        return CalibrationFieldStatus.positiveValue(pixelDepth);
    }

    public CalibrationFieldStatus frameIntervalStatus() {
        if (Double.isNaN(frameInterval) || frameInterval == 0.0) {
            return CalibrationFieldStatus.UNDEFINED;
        }
        return Double.isFinite(frameInterval) && frameInterval > 0
                ? CalibrationFieldStatus.VALID
                : CalibrationFieldStatus.INVALID;
    }

    public CalibrationFieldStatus spatialUnitStatus() {
        final String normalized = spatialUnit.trim()
                .toLowerCase(java.util.Locale.ROOT);
        if (normalized.isBlank()
                || normalized.equals("pixel")
                || normalized.equals("pixels")
                || normalized.equals("px")) {
            return CalibrationFieldStatus.UNDEFINED;
        }
        return canonicalSpatialUnit().isPresent()
                ? CalibrationFieldStatus.VALID
                : CalibrationFieldStatus.INVALID;
    }

    public CalibrationFieldStatus timeUnitStatus() {
        if (timeUnit.isBlank()) {
            return CalibrationFieldStatus.UNDEFINED;
        }
        return canonicalTimeUnit().isPresent()
                ? CalibrationFieldStatus.VALID
                : CalibrationFieldStatus.INVALID;
    }

    /** Canonical OME unit for a verified physical length, without guessing. */
    public Optional<String> canonicalSpatialUnit() {
        final String normalized = spatialUnit.trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replace('\u03bc', '\u00b5');
        return Optional.ofNullable(switch (normalized) {
            case "m", "meter", "meters", "metre", "metres" -> "m";
            case "cm", "centimeter", "centimeters", "centimetre",
                    "centimetres" -> "cm";
            case "mm", "millimeter", "millimeters", "millimetre",
                    "millimetres" -> "mm";
            case "\u00b5m", "um", "micron", "microns", "micrometer",
                    "micrometers", "micrometre", "micrometres" -> "\u00b5m";
            case "nm", "nanometer", "nanometers", "nanometre",
                    "nanometres" -> "nm";
            case "pm", "picometer", "picometers", "picometre",
                    "picometres" -> "pm";
            case "fm", "femtometer", "femtometers", "femtometre",
                    "femtometres" -> "fm";
            case "\u00e5", "angstrom", "angstroms" -> "\u00c5";
            case "in", "inch", "inches" -> "in";
            case "ft", "foot", "feet" -> "ft";
            default -> null;
        });
    }

    /** Canonical OME unit for a verified time interval, without guessing. */
    public Optional<String> canonicalTimeUnit() {
        final String normalized = timeUnit.trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replace('\u03bc', '\u00b5');
        return Optional.ofNullable(switch (normalized) {
            case "s", "sec", "secs", "second", "seconds" -> "s";
            case "ms", "msec", "millisecond", "milliseconds" -> "ms";
            case "\u00b5s", "us", "usec", "microsecond", "microseconds"
                    -> "\u00b5s";
            case "ns", "nsec", "nanosecond", "nanoseconds" -> "ns";
            case "ps", "psec", "picosecond", "picoseconds" -> "ps";
            case "min", "mins", "minute", "minutes" -> "min";
            case "h", "hr", "hrs", "hour", "hours" -> "h";
            case "d", "day", "days" -> "d";
            default -> null;
        });
    }
}
