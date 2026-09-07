package org.atlasalign.application.c02;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Deterministic, AP-only C02 candidate orchestration. */
public final class C02ApLocalSearchEngine {

    public static final double MINIMUM_FEATURE_SUPPORT_FRACTION = 0.05;

    private static final Comparator<C02ApPlaneScore> RANKING =
            Comparator.<C02ApPlaneScore>comparingDouble(score -> score.mind()
                    .meanSquaredDescriptorDifference().orElseThrow())
                    .thenComparing(
                            Comparator.comparingDouble(
                                    (C02ApPlaneScore score) -> score.mind()
                                            .featureSupportFraction())
                                    .reversed())
                    .thenComparingInt(score -> Math.abs(
                            score.candidate().apOffsetIndices()))
                    .thenComparingInt(score -> score.candidate()
                            .coronalLevel()
                            .zeroBasedAnteriorPosteriorIndex());

    public C02ApLocalSearchResult search(
            final C02ApSearchPlan plan,
            final Function<C02ApPlaneCandidate, C02MindScore> scorer) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(scorer, "scorer");
        final ArrayList<C02ApPlaneScore> raw = new ArrayList<>(
                C02ApSearchPlan.CANDIDATE_COUNT);
        final ArrayList<String> failures = new ArrayList<>();
        for (final C02ApPlaneCandidate candidate : plan.candidates()) {
            final C02MindScore score = Objects.requireNonNull(
                    scorer.apply(candidate),
                    "C02 scorer returned null");
            raw.add(new C02ApPlaneScore(candidate, score));
            if (!score.assessable(MINIMUM_FEATURE_SUPPORT_FRACTION)) {
                failures.add("AP offset "
                        + candidate.apOffsetIndices()
                        + " had unavailable or less than 5% complete-observed "
                        + "MIND support");
            }
        }
        if (!failures.isEmpty()) {
            return new C02ApLocalSearchResult(
                    plan,
                    raw,
                    Optional.empty(),
                    List.of(),
                    false,
                    failures);
        }
        final List<C02ApPlaneScore> ranked = raw.stream()
                .sorted(RANKING)
                .toList();
        final C02ApPlaneScore selected = ranked.get(0);
        final int offset = selected.candidate().apOffsetIndices();
        return new C02ApLocalSearchResult(
                plan,
                raw,
                Optional.of(selected),
                ranked.subList(0, 3),
                offset == C02ApSearchPlan.MINIMUM_AP_OFFSET
                        || offset == C02ApSearchPlan.MAXIMUM_AP_OFFSET,
                List.of());
    }

    public C02ApLocalSearchResult searchFeatureGrids(
            final C02ApSearchPlan plan,
            final C02SearchContext context,
            final C02BoundFeatureGrid tissueFeature,
            final C02ObservedSupportMask support,
            final Function<C02ApPlaneCandidate, C02BoundFeatureGrid>
                    atlasFeatureProvider) {
        return searchFeatureGrids(
                plan,
                context,
                tissueFeature,
                support,
                atlasFeatureProvider,
                ignored -> { },
                (candidate, descriptor) -> { });
    }

    /**
     * Runs the frozen search and exposes each immutable descriptor once for
     * validation evidence. Observers cannot change ranking or input buffers.
     */
    public C02ApLocalSearchResult searchFeatureGrids(
            final C02ApSearchPlan plan,
            final C02SearchContext context,
            final C02BoundFeatureGrid tissueFeature,
            final C02ObservedSupportMask support,
            final Function<C02ApPlaneCandidate, C02BoundFeatureGrid>
                    atlasFeatureProvider,
            final Consumer<C02MindDescriptorGrid> tissueDescriptorObserver,
            final BiConsumer<C02ApPlaneCandidate, C02MindDescriptorGrid>
                    atlasDescriptorObserver) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(tissueFeature, "tissueFeature");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(
                atlasFeatureProvider, "atlasFeatureProvider");
        Objects.requireNonNull(
                tissueDescriptorObserver, "tissueDescriptorObserver");
        Objects.requireNonNull(
                atlasDescriptorObserver, "atlasDescriptorObserver");
        if (!context.r3Level().equals(plan.center())) {
            throw new IllegalArgumentException(
                    "C02 context r3 level must match the search centre");
        }
        requireBinding(
                tissueFeature,
                context,
                C02BoundFeatureGrid.Role.TISSUE,
                null);
        final C02Mind4Scorer mindScorer = new C02Mind4Scorer();
        final C02MindDescriptorGrid tissueDescriptor =
                mindScorer.buildDescriptor(tissueFeature.grid(), support);
        tissueDescriptorObserver.accept(tissueDescriptor);
        return search(plan, candidate -> {
            final C02BoundFeatureGrid atlasFeature = Objects.requireNonNull(
                    atlasFeatureProvider.apply(candidate),
                    "C02 atlas feature provider returned null");
            requireBinding(
                    atlasFeature,
                    context,
                    C02BoundFeatureGrid.Role.ATLAS,
                    candidate.coronalLevel());
            final C02MindDescriptorGrid atlasDescriptor =
                    mindScorer.buildDescriptor(atlasFeature.grid(), support);
            atlasDescriptorObserver.accept(candidate, atlasDescriptor);
            return mindScorer.score(tissueDescriptor, atlasDescriptor);
        });
    }

    private static void requireBinding(
            final C02BoundFeatureGrid feature,
            final C02SearchContext context,
            final C02BoundFeatureGrid.Role role,
            final org.atlasalign.application.AllenCoronalLevel level) {
        if (!feature.context().equals(context)
                || !feature.contextSha256().equals(context.identitySha256())
                || feature.role() != role) {
            throw new IllegalArgumentException(
                    "C02 feature grid is not bound to the frozen search context");
        }
        if (role == C02BoundFeatureGrid.Role.ATLAS) {
            if (feature.atlasLevel().isEmpty()
                    || !feature.atlasLevel().get().equals(level)) {
                throw new IllegalArgumentException(
                        "C02 atlas feature grid is bound to the wrong AP plane");
            }
        } else if (feature.atlasLevel().isPresent()) {
            throw new IllegalArgumentException(
                    "C02 tissue feature grid cannot carry an atlas AP plane");
        }
    }
}
