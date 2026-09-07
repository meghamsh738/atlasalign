package org.atlasalign.application.guided;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.atlasalign.application.c01.C01ApLocalSearchEngine;
import org.atlasalign.application.c01.C01ApPlaneScore;
import org.atlasalign.application.c01.C01BoundFeatureGrid;
import org.atlasalign.application.c01.C01GradientOrientationScore;
import org.atlasalign.application.c01.C01GradientOrientationScorer;
import org.atlasalign.application.c01.C01ObservedSupportMask;
import org.atlasalign.application.c01.C01SearchContext;

/** Computation-matched G01 search using the unchanged C01 image score. */
public final class GuidedApSearchEngine {

    private static final Comparator<GuidedApPlaneScore> RANKING =
            Comparator.<GuidedApPlaneScore>comparingDouble(
                    score -> score.gradientOrientation()
                            .orientationAgreement().orElseThrow())
                    .reversed()
                    .thenComparing(Comparator.comparingDouble(
                            (GuidedApPlaneScore score) -> score
                                    .gradientOrientation()
                                    .featureSupportFraction()).reversed())
                    .thenComparingInt(score -> Math.abs(
                            score.candidate().offsetFromR3Indices()))
                    .thenComparingInt(score -> score.candidate()
                            .coronalLevel()
                            .zeroBasedAnteriorPosteriorIndex());

    public GuidedApSearchResult search(
            final GuidedApSearchPlan plan,
            final Function<GuidedApPlaneCandidate,
                    C01GradientOrientationScore> scorer,
            final C01ApPlaneScore immutableR3Sentinel) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(scorer, "scorer");
        requireSentinel(plan, immutableR3Sentinel);
        final ArrayList<GuidedApPlaneScore> raw = new ArrayList<>(
                GuidedApSearchPlan.CANDIDATE_COUNT);
        final ArrayList<String> failures = new ArrayList<>();
        for (final GuidedApPlaneCandidate candidate : plan.candidates()) {
            final C01GradientOrientationScore score = Objects.requireNonNull(
                    scorer.apply(candidate), "G01 scorer returned null");
            raw.add(new GuidedApPlaneScore(candidate, score));
            if (!score.assessable(
                    C01ApLocalSearchEngine.MINIMUM_FEATURE_SUPPORT_FRACTION)) {
                failures.add("Guided ordinal " + candidate.ordinal()
                        + " at Allen level "
                        + candidate.coronalLevel()
                                .zeroBasedAnteriorPosteriorIndex()
                        + " was unavailable or below 5% feature support");
            }
        }
        final int r3 = plan.immutableR3Proposal()
                .zeroBasedAnteriorPosteriorIndex();
        final int lower = plan.prior().inclusiveStart()
                .zeroBasedAnteriorPosteriorIndex();
        final int upper = plan.prior().inclusiveEnd()
                .zeroBasedAnteriorPosteriorIndex();
        final boolean r3Outside = r3 < lower || r3 > upper;
        if (!failures.isEmpty()) {
            return new GuidedApSearchResult(
                    plan, raw, Optional.empty(), List.of(),
                    immutableR3Sentinel, r3Outside, false, false, failures);
        }
        final List<GuidedApPlaneScore> ranked = raw.stream()
                .sorted(RANKING)
                .toList();
        final GuidedApPlaneScore selected = ranked.get(0);
        final int selectedLevel = selected.candidate().coronalLevel()
                .zeroBasedAnteriorPosteriorIndex();
        final boolean sentinelOutscored = r3Outside
                && compareSentinel(immutableR3Sentinel, selected) < 0;
        return new GuidedApSearchResult(
                plan,
                raw,
                Optional.of(selected),
                ranked.subList(0, 3),
                immutableR3Sentinel,
                r3Outside,
                selectedLevel == lower || selectedLevel == upper,
                sentinelOutscored,
                List.of());
    }

    public GuidedApSearchResult searchFeatureGrids(
            final GuidedApSearchPlan plan,
            final C01SearchContext context,
            final C01BoundFeatureGrid tissueFeature,
            final C01ObservedSupportMask support,
            final Function<GuidedApPlaneCandidate, C01BoundFeatureGrid>
                    atlasFeatureProvider,
            final C01ApPlaneScore immutableR3Sentinel) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(tissueFeature, "tissueFeature");
        Objects.requireNonNull(support, "support");
        Objects.requireNonNull(atlasFeatureProvider, "atlasFeatureProvider");
        if (!context.r3Level().equals(plan.immutableR3Proposal())) {
            throw new IllegalArgumentException(
                    "The G01 context must retain the immutable r3 proposal");
        }
        requireGridBinding(tissueFeature, context, null);
        final C01GradientOrientationScorer gradient =
                new C01GradientOrientationScorer();
        return search(plan, candidate -> {
            final C01BoundFeatureGrid atlas = Objects.requireNonNull(
                    atlasFeatureProvider.apply(candidate),
                    "G01 atlas feature provider returned null");
            requireGridBinding(atlas, context, candidate);
            if (atlas.grid().width() != tissueFeature.grid().width()
                    || atlas.grid().height() != tissueFeature.grid().height()) {
                throw new IllegalArgumentException(
                        "G01 tissue and atlas feature grids must match");
            }
            return gradient.score(
                    tissueFeature.grid().width(),
                    tissueFeature.grid().height(),
                    tissueFeature.grid().intensity(),
                    atlas.grid().intensity(),
                    support);
        }, immutableR3Sentinel);
    }

    private static void requireSentinel(
            final GuidedApSearchPlan plan,
            final C01ApPlaneScore sentinel) {
        Objects.requireNonNull(sentinel, "immutableR3Sentinel");
        if (sentinel.candidate().apOffsetIndices() != 0
                || !sentinel.candidate().coronalLevel().equals(
                        plan.immutableR3Proposal())
                || !sentinel.gradientOrientation().assessable(
                        C01ApLocalSearchEngine.MINIMUM_FEATURE_SUPPORT_FRACTION)) {
            throw new IllegalArgumentException(
                    "G01 requires the assessable exact r3 AUTO33 sentinel");
        }
    }

    private static void requireGridBinding(
            final C01BoundFeatureGrid feature,
            final C01SearchContext context,
            final GuidedApPlaneCandidate candidate) {
        if (!feature.context().equals(context)
                || !feature.contextSha256().equals(context.identitySha256())) {
            throw new IllegalArgumentException(
                    "G01 feature grid changed the frozen C01 context");
        }
        if (candidate == null) {
            if (feature.role() != C01BoundFeatureGrid.Role.TISSUE
                    || feature.atlasLevel().isPresent()) {
                throw new IllegalArgumentException(
                        "G01 tissue feature binding is invalid");
            }
        } else if (feature.role() != C01BoundFeatureGrid.Role.ATLAS
                || feature.atlasLevel().isEmpty()
                || !feature.atlasLevel().orElseThrow().equals(
                        candidate.coronalLevel())) {
            throw new IllegalArgumentException(
                    "G01 atlas feature is bound to the wrong candidate");
        }
    }

    /** Negative means the external r3 sentinel ranks ahead of the guided hit. */
    private static int compareSentinel(
            final C01ApPlaneScore sentinel,
            final GuidedApPlaneScore selected) {
        final double sentinelScore = sentinel.gradientOrientation()
                .orientationAgreement().orElseThrow();
        final double selectedScore = selected.gradientOrientation()
                .orientationAgreement().orElseThrow();
        int comparison = -Double.compare(sentinelScore, selectedScore);
        if (comparison != 0) {
            return comparison;
        }
        comparison = -Double.compare(
                sentinel.gradientOrientation().featureSupportFraction(),
                selected.gradientOrientation().featureSupportFraction());
        if (comparison != 0) {
            return comparison;
        }
        comparison = Integer.compare(
                Math.abs(sentinel.candidate().apOffsetIndices()),
                Math.abs(selected.candidate().offsetFromR3Indices()));
        if (comparison != 0) {
            return comparison;
        }
        return Integer.compare(
                sentinel.candidate().coronalLevel()
                        .zeroBasedAnteriorPosteriorIndex(),
                selected.candidate().coronalLevel()
                        .zeroBasedAnteriorPosteriorIndex());
    }
}
