package org.atlasalign.application.manual;

import java.util.Objects;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ObservedAnatomicalHemisphere;

/** Completed explicit reviewer observation; image side never implies anatomy. */
public record SectionObservation(
        SectionGeometry geometry,
        AtlasOrientation orientation,
        ObservedAnatomicalHemisphere observedHemisphere,
        boolean lateralityConfirmed,
        boolean reflectionConfirmed) {

    public SectionObservation {
        geometry = Objects.requireNonNull(geometry, "geometry");
        orientation = Objects.requireNonNull(orientation, "orientation");
        observedHemisphere = Objects.requireNonNull(
                observedHemisphere, "observedHemisphere");
        if (!orientation.confirmed()) {
            throw new IllegalArgumentException("Atlas orientation must be explicit");
        }
        if (!lateralityConfirmed
                || observedHemisphere == ObservedAnatomicalHemisphere.UNSURE) {
            throw new IllegalArgumentException(
                    "Anatomical laterality must be explicitly confirmed");
        }
        if (!reflectionConfirmed) {
            throw new IllegalArgumentException(
                    "Reflection decision must be explicitly confirmed");
        }
        if (geometry == SectionGeometry.FULL
                && observedHemisphere != ObservedAnatomicalHemisphere.BOTH) {
            throw new IllegalArgumentException(
                    "A full section must explicitly observe both hemispheres");
        }
        if ((geometry == SectionGeometry.IMAGE_LEFT_HALF
                || geometry == SectionGeometry.IMAGE_RIGHT_HALF)
                && observedHemisphere == ObservedAnatomicalHemisphere.BOTH) {
            throw new IllegalArgumentException(
                    "A half section must identify one anatomical hemisphere");
        }
    }
}
