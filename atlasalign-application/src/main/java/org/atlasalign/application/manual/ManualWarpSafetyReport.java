package org.atlasalign.application.manual;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import org.atlasalign.core.Point2D;

/**
 * Typed, reviewer-visible description of the first deterministic safety gate
 * that rejected a complete manual deformation field.
 */
public record ManualWarpSafetyReport(
        ManualWarpSafetyGate gate,
        OptionalDouble measuredValue,
        OptionalDouble threshold,
        Optional<ManualHemisphereWarp2D.AtlasSide> affectedSide,
        Optional<Point2D> meshLocation,
        String detail) {

    public ManualWarpSafetyReport {
        gate = Objects.requireNonNull(gate, "gate");
        measuredValue = Objects.requireNonNull(
                measuredValue, "measuredValue");
        threshold = Objects.requireNonNull(threshold, "threshold");
        affectedSide = Objects.requireNonNull(
                affectedSide, "affectedSide");
        meshLocation = Objects.requireNonNull(
                meshLocation, "meshLocation");
        detail = detail == null ? "" : detail.trim();
    }

    public static ManualWarpSafetyReport measured(
            final ManualWarpSafetyGate gate,
            final double measured,
            final double limit,
            final ManualHemisphereWarp2D.AtlasSide side,
            final Point2D location,
            final String detail) {
        return new ManualWarpSafetyReport(gate,
                OptionalDouble.of(measured), OptionalDouble.of(limit),
                Optional.ofNullable(side), Optional.ofNullable(location),
                detail);
    }

    public static ManualWarpSafetyReport unmeasured(
            final ManualWarpSafetyGate gate,
            final ManualHemisphereWarp2D.AtlasSide side,
            final Point2D location,
            final String detail) {
        return new ManualWarpSafetyReport(gate,
                OptionalDouble.empty(), OptionalDouble.empty(),
                Optional.ofNullable(side), Optional.ofNullable(location),
                detail);
    }
}
