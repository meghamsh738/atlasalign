package org.atlasalign.application;

import java.util.List;
import java.util.Objects;

/**
 * A finite DeepSlice QuickNII O/U/V vector in its frozen component order.
 */
public record DeepSliceOuv(
        double ox,
        double oy,
        double oz,
        double ux,
        double uy,
        double uz,
        double vx,
        double vy,
        double vz) {

    /** Number of components in the frozen O/U/V representation. */
    public static final int COMPONENT_COUNT = 9;

    public DeepSliceOuv {
        if (!Double.isFinite(ox) || !Double.isFinite(oy)
                || !Double.isFinite(oz) || !Double.isFinite(ux)
                || !Double.isFinite(uy) || !Double.isFinite(uz)
                || !Double.isFinite(vx) || !Double.isFinite(vy)
                || !Double.isFinite(vz)) {
            throw new IllegalArgumentException(
                    "DeepSlice O/U/V components must be finite binary64 values");
        }
    }

    /** Defensively reads the frozen O/U/V ordering from an array. */
    public DeepSliceOuv(final double[] components) {
        this(requireComponent(components, 0), requireComponent(components, 1),
                requireComponent(components, 2), requireComponent(components, 3),
                requireComponent(components, 4), requireComponent(components, 5),
                requireComponent(components, 6), requireComponent(components, 7),
                requireComponent(components, 8));
        if (components.length != COMPONENT_COUNT) {
            throw new IllegalArgumentException(
                    "DeepSlice O/U/V requires exactly nine components");
        }
    }

    /** Defensively reads the frozen O/U/V ordering from an array. */
    public static DeepSliceOuv fromArray(final double[] components) {
        return new DeepSliceOuv(components);
    }

    /** Defensively reads the frozen O/U/V ordering from an immutable view. */
    public static DeepSliceOuv fromList(final List<Double> components) {
        Objects.requireNonNull(components, "components");
        if (components.size() != COMPONENT_COUNT) {
            throw new IllegalArgumentException(
                    "DeepSlice O/U/V requires exactly nine components");
        }
        return new DeepSliceOuv(
                requireBoxedComponent(components, 0),
                requireBoxedComponent(components, 1),
                requireBoxedComponent(components, 2),
                requireBoxedComponent(components, 3),
                requireBoxedComponent(components, 4),
                requireBoxedComponent(components, 5),
                requireBoxedComponent(components, 6),
                requireBoxedComponent(components, 7),
                requireBoxedComponent(components, 8));
    }

    /** Returns a new array in exact O/U/V order. */
    public double[] toArray() {
        return new double[]{ox, oy, oz, ux, uy, uz, vx, vy, vz};
    }

    /** Returns an immutable list in exact O/U/V order. */
    public List<Double> toList() {
        return List.of(ox, oy, oz, ux, uy, uz, vx, vy, vz);
    }

    /** Returns one frozen-order component without exposing mutable storage. */
    public double component(final int index) {
        return switch (index) {
            case 0 -> ox;
            case 1 -> oy;
            case 2 -> oz;
            case 3 -> ux;
            case 4 -> uy;
            case 5 -> uz;
            case 6 -> vx;
            case 7 -> vy;
            case 8 -> vz;
            default -> throw new IndexOutOfBoundsException(
                    "DeepSlice O/U/V component index must be 0..8");
        };
    }

    private static double requireComponent(
            final double[] components, final int index) {
        Objects.requireNonNull(components, "components");
        if (components.length != COMPONENT_COUNT) {
            throw new IllegalArgumentException(
                    "DeepSlice O/U/V requires exactly nine components");
        }
        return components[index];
    }

    private static double requireBoxedComponent(
            final List<Double> components, final int index) {
        final Double component = components.get(index);
        if (component == null) {
            throw new IllegalArgumentException(
                    "DeepSlice O/U/V components must not be null");
        }
        return component;
    }
}
