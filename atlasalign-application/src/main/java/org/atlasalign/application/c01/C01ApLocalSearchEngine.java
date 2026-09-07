package org.atlasalign.application.c01;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Deterministic, AP-only C01 candidate orchestration. */
public final class C01ApLocalSearchEngine {

    public static final double MINIMUM_FEATURE_SUPPORT_FRACTION = 0.05;

    private static final Comparator<C01ApPlaneScore> RANKING =
            Comparator.<C01ApPlaneScore>comparingDouble(
                    score -> score.gradientOrientation()
                            .orientationAgreement().orElseThrow())
                    .reversed()
                    .thenComparing(
                            Comparator.comparingDouble(
                                    (C01ApPlaneScore score) -> score
                                            .gradientOrientation()
                                            .featureSupportFraction())
                                    .reversed())
                    .thenComparingInt(score -> Math.abs(
                            score.candidate().apOffsetIndices()))
                    .thenComparingInt(score -> score.candidate()
                            .coronalLevel()
                            .zeroBasedAnteriorPosteriorIndex());

    public C01ApLocalSearchResult search(
            final C01ApSearchPlan plan,
            final Function<C01ApPlaneCandidate,
                    C01GradientOrientationScore> scorer) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(scorer, "scorer");
        final ArrayList<C01ApPlaneScore> raw = new ArrayList<>(
                C01ApSearchPlan.CANDIDATE_COUNT);
        final ArrayList<String> failures = new ArrayList<>();
        for (final C01ApPlaneCandidate candidate : plan.candidates()) {
            final C01GradientOrientationScore score =
                    Objects.requireNonNull(
                            scorer.apply(candidate),
                            "C01 scorer returned null");
            raw.add(new C01ApPlaneScore(candidate, score));
            if (!score.assessable(MINIMUM_FEATURE_SUPPORT_FRACTION)) {
                failures.add("AP offset "
                        + candidate.apOffsetIndices()
                        + " had unavailable or less than 5% internal "
                        + "feature support");
            }
        }
        if (!failures.isEmpty()) {
            return new C01ApLocalSearchResult(
                    plan,
                    raw,
                    Optional.empty(),
                    List.of(),
                    false,
                    failures);
        }
        final List<C01ApPlaneScore> ranked = raw.stream()
                .sorted(RANKING)
                .toList();
        final C01ApPlaneScore selected = ranked.get(0);
        final int offset = selected.candidate().apOffsetIndices();
        return new C01ApLocalSearchResult(
                plan,
                raw,
                Optional.of(selected),
                ranked.subList(0, 3),
                offset == C01ApSearchPlan.MINIMUM_AP_OFFSET
                        || offset == C01ApSearchPlan.MAXIMUM_AP_OFFSET,
                List.of());
    }

    /**
     * Scores immutable feature grids while preserving the frozen AP-only
     * candidate order. The provider must not choose variants per candidate.
     */
    public C01ApLocalSearchResult searchFeatureGrids(
            final C01ApSearchPlan plan,
            final C01SearchContext context,
            final C01BoundFeatureGrid tissueFeature,
            final C01ObservedSupportMask support,
            final Function<C01ApPlaneCandidate, C01BoundFeatureGrid>
                    atlasFeatureProvider) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(tissueFeature, "tissueFeature");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(
                atlasFeatureProvider, "atlasFeatureProvider");
        if (!context.r3Level().equals(plan.center())) {
            throw new IllegalArgumentException(
                    "C01 context r3 level must match the search centre");
        }
        requireBinding(
                tissueFeature,
                context,
                C01BoundFeatureGrid.Role.TISSUE,
                null);
        final C01GradientOrientationScorer gradientScorer =
                new C01GradientOrientationScorer();
        return search(plan, candidate -> {
            final C01BoundFeatureGrid atlasFeature = Objects.requireNonNull(
                    atlasFeatureProvider.apply(candidate),
                    "C01 atlas feature provider returned null");
            requireBinding(
                    atlasFeature,
                    context,
                    C01BoundFeatureGrid.Role.ATLAS,
                    candidate.coronalLevel());
            if (atlasFeature.grid().width()
                            != tissueFeature.grid().width()
                    || atlasFeature.grid().height()
                            != tissueFeature.grid().height()) {
                throw new IllegalArgumentException(
                        "C01 atlas and tissue feature grids must match");
            }
            return gradientScorer.score(
                    tissueFeature.grid().width(),
                    tissueFeature.grid().height(),
                    tissueFeature.grid().intensity(),
                    atlasFeature.grid().intensity(),
                    support);
        });
    }

    private static void requireBinding(
            final C01BoundFeatureGrid feature,
            final C01SearchContext context,
            final C01BoundFeatureGrid.Role role,
            final org.atlasalign.application.AllenCoronalLevel level) {
        if (!feature.context().equals(context)
                || !feature.contextSha256().equals(
                        context.identitySha256())
                || feature.role() != role) {
            throw new IllegalArgumentException(
                    "C01 feature grid is not bound to the frozen search context");
        }
        if (role == C01BoundFeatureGrid.Role.ATLAS) {
            if (feature.atlasLevel().isEmpty()
                    || !feature.atlasLevel().get().equals(level)) {
                throw new IllegalArgumentException(
                        "C01 atlas feature grid is bound to the wrong AP plane");
            }
        } else if (feature.atlasLevel().isPresent()) {
            throw new IllegalArgumentException(
                    "C01 tissue feature grid cannot carry an atlas AP plane");
        }
    }
}
