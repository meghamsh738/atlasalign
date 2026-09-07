package org.atlasalign.application.dg;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.regex.Pattern;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasPlaneTilt;

/** Candidate table row containing only precomputed, provenance-bound evidence. */
public record DgCandidateEvidence(
        String candidateId,
        AllenCoronalLevel level,
        AtlasPlaneTilt tilt,
        OptionalDouble wholeSectionScore,
        Map<AnatomicalSide, OptionalDouble> dgSymmetricDistanceBySide,
        OptionalDouble nonDgInputAnchorDistance,
        String candidateEvidenceSha256) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public DgCandidateEvidence {
        candidateId = requireText(candidateId, "candidateId");
        level = Objects.requireNonNull(level, "level");
        tilt = Objects.requireNonNull(tilt, "tilt");
        wholeSectionScore = requireMetric(
                wholeSectionScore, "wholeSectionScore", false);
        final EnumMap<AnatomicalSide, OptionalDouble> sideDistances =
                new EnumMap<>(AnatomicalSide.class);
        Objects.requireNonNull(
                dgSymmetricDistanceBySide, "dgSymmetricDistanceBySide")
                .forEach((side, distance) -> sideDistances.put(
                        Objects.requireNonNull(side, "DG anatomical side"),
                        requireMetric(
                                distance,
                                "dgSymmetricDistanceBySide." + side,
                                true)));
        if (!sideDistances.keySet().equals(
                java.util.EnumSet.allOf(AnatomicalSide.class))) {
            throw new IllegalArgumentException(
                    "D01 candidate evidence must preserve both anatomical sides");
        }
        dgSymmetricDistanceBySide = Map.copyOf(sideDistances);
        nonDgInputAnchorDistance = requireMetric(
                nonDgInputAnchorDistance,
                "nonDgInputAnchorDistance", true);
        Objects.requireNonNull(
                candidateEvidenceSha256, "candidateEvidenceSha256");
        if (!SHA256.matcher(candidateEvidenceSha256).matches()) {
            throw new IllegalArgumentException(
                    "candidateEvidenceSha256 must be a lowercase SHA-256");
        }
    }

    /** Equal-weight bilateral distance, unavailable unless both sides exist. */
    public OptionalDouble dgSymmetricDistance() {
        final OptionalDouble left = dgSymmetricDistanceBySide.get(
                AnatomicalSide.LEFT);
        final OptionalDouble right = dgSymmetricDistanceBySide.get(
                AnatomicalSide.RIGHT);
        if (left.isEmpty() || right.isEmpty()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(
                (left.getAsDouble() + right.getAsDouble()) * 0.5);
    }

    private static OptionalDouble requireMetric(
            final OptionalDouble metric,
            final String label,
            final boolean nonNegative) {
        Objects.requireNonNull(metric, label);
        if (metric.isPresent()
                && (!Double.isFinite(metric.getAsDouble())
                || nonNegative && metric.getAsDouble() < 0)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return metric;
    }

    private static String requireText(
            final String value,
            final String label) {
        final String checked = Objects.requireNonNull(value, label).trim();
        if (checked.isEmpty() || checked.contains("\n") || checked.contains("\r")) {
            throw new IllegalArgumentException(label + " must be single-line text");
        }
        return checked;
    }
}
