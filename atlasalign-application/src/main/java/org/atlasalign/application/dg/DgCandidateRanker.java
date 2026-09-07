package org.atlasalign.application.dg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;

/** Frozen robust-standardized D01 ablation ranker. */
public final class DgCandidateRanker {

    private static final double WINSOR_LIMIT = 3;

    public DgRankingResult rank(
            final List<DgCandidateEvidence> candidates,
            final DgRankingMethod method) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(method, "method");
        final List<DgCandidateEvidence> raw = List.copyOf(candidates);
        if (raw.size() < 2) {
            throw new IllegalArgumentException(
                    "D01 requires at least two candidate planes");
        }
        if (raw.stream().map(DgCandidateEvidence::candidateId)
                .distinct().count() != raw.size()) {
            throw new IllegalArgumentException(
                    "D01 candidate IDs must be unique");
        }

        final Optional<double[]> whole = standardizedLoss(
                raw,
                DgCandidateEvidence::wholeSectionScore,
                true);
        final Optional<double[]> dg = standardizedLoss(
                raw,
                DgCandidateEvidence::dgSymmetricDistance,
                false);
        final Optional<double[]> anchors = standardizedLoss(
                raw,
                DgCandidateEvidence::nonDgInputAnchorDistance,
                false);
        final ArrayList<String> unavailable = new ArrayList<>();
        if (whole.isEmpty()) {
            unavailable.add("WHOLE_SECTION");
        }
        if (dg.isEmpty()) {
            unavailable.add("DG");
        }
        if (anchors.isEmpty()) {
            unavailable.add("NON_DG_INPUT_ANCHORS");
        }

        final List<double[]> components = switch (method) {
            case WHOLE_SECTION_BASELINE -> whole.map(List::of).orElse(List.of());
            case DG_ONLY -> dg.map(List::of).orElse(List.of());
            case DG_AUGMENTED -> whole.isPresent() && dg.isPresent()
                    ? List.of(whole.orElseThrow(), dg.orElseThrow())
                    : List.of();
            case MULTI_FEATURE -> whole.isPresent() && dg.isPresent()
                    ? anchors.<List<double[]>>map(value -> List.of(
                            whole.orElseThrow(), dg.orElseThrow(), value))
                            .orElseGet(() -> List.of(
                                    whole.orElseThrow(), dg.orElseThrow()))
                    : List.of();
        };
        if (components.isEmpty()) {
            return new DgRankingResult(
                    method,
                    raw,
                    List.of(),
                    unavailable,
                    List.of("INSUFFICIENT_DG_EVIDENCE"));
        }
        final ArrayList<DgRankedCandidate> ranked =
                new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            double sum = 0;
            for (final double[] component : components) {
                sum += component[index];
            }
            ranked.add(new DgRankedCandidate(
                    raw.get(index), sum / components.size()));
        }
        ranked.sort(Comparator
                .comparingDouble(DgRankedCandidate::aggregateLoss)
                .thenComparingInt(item -> item.evidence().level()
                        .zeroBasedAnteriorPosteriorIndex())
                .thenComparing(item -> item.evidence().candidateId()));
        final ArrayList<String> warnings = new ArrayList<>();
        if (method == DgRankingMethod.MULTI_FEATURE && anchors.isEmpty()) {
            warnings.add("Non-DG input-anchor evidence was unavailable; "
                    + "the multi-feature ablation used whole-section and DG evidence only");
        }
        return new DgRankingResult(
                method, raw, ranked, unavailable, warnings);
    }

    private static Optional<double[]> standardizedLoss(
            final List<DgCandidateEvidence> candidates,
            final Function<DgCandidateEvidence, OptionalDouble> extractor,
            final boolean higherIsBetter) {
        final double[] values = new double[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) {
            final OptionalDouble value = extractor.apply(candidates.get(index));
            if (value.isEmpty()) {
                return Optional.empty();
            }
            values[index] = higherIsBetter
                    ? -value.getAsDouble() : value.getAsDouble();
        }
        final double median = median(values);
        final double[] deviations = Arrays.stream(values)
                .map(value -> Math.abs(value - median))
                .toArray();
        final double mad = median(deviations);
        if (!(mad > 0) || !Double.isFinite(mad)) {
            return Optional.empty();
        }
        final double[] standardized = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            standardized[index] = Math.max(-WINSOR_LIMIT, Math.min(
                    WINSOR_LIMIT, (values[index] - median) / mad));
        }
        return Optional.of(standardized);
    }

    private static double median(final double[] values) {
        final double[] copy = values.clone();
        Arrays.sort(copy);
        final int middle = copy.length / 2;
        return copy.length % 2 == 1
                ? copy[middle]
                : (copy[middle - 1] + copy[middle]) / 2;
    }
}
