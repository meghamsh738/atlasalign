package org.atlasalign.application.dg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.guided.GuidedRunMode;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class DgCurveAndRankingTest {

    @Test
    void resamplesAtExactArcLengthAndComputesSymmetricDistance() {
        final List<Point2D> resampled = new DgCurveResampler().resample(
                List.of(
                        new Point2D(0, 0),
                        new Point2D(4, 0),
                        new Point2D(4, 4)),
                5);

        assertEquals(List.of(
                new Point2D(0, 0),
                new Point2D(2, 0),
                new Point2D(4, 0),
                new Point2D(4, 2),
                new Point2D(4, 4)), resampled);
        assertEquals(2.0, new DgCurveDistance().symmetricCentrelineDistance(
                List.of(new Point2D(0, 0), new Point2D(2, 0)),
                List.of(new Point2D(0, 2), new Point2D(2, 2))),
                1e-12);
        assertEquals(128, new DgCurveResampler().resample(
                List.of(new Point2D(0, 0), new Point2D(10, 0))).size());
    }

    @Test
    void bilateralDistancePreservesSidesAndAbstainsWhenOneIsMissing() {
        final DgCurveDistance distance = new DgCurveDistance();
        final Map<AnatomicalSide, List<Point2D>> tissue = Map.of(
                AnatomicalSide.LEFT,
                List.of(new Point2D(0, 0), new Point2D(10, 0)),
                AnatomicalSide.RIGHT,
                List.of(new Point2D(100, 0), new Point2D(110, 0)));
        final Map<AnatomicalSide, List<Point2D>> atlas = Map.of(
                AnatomicalSide.LEFT,
                List.of(new Point2D(0, 2), new Point2D(10, 2)),
                AnatomicalSide.RIGHT,
                List.of(new Point2D(100, 4), new Point2D(110, 4)));

        assertEquals(3.0,
                distance.bilateralSidePreservingDistance(tissue, atlas)
                        .orElseThrow(),
                1e-12);
        assertTrue(distance.bilateralSidePreservingDistance(
                Map.of(AnatomicalSide.LEFT, tissue.get(AnatomicalSide.LEFT)),
                atlas).isEmpty());
    }

    @Test
    void ranksFrozenAblationsWithoutChangingManualProvenance() {
        final List<DgCandidateEvidence> candidates = List.of(
                evidence("a", 200, 0.60, 8, 7),
                evidence("b", 201, 0.80, 4, 3),
                evidence("c", 202, 0.70, 6, 5));
        final DgCandidateRanker ranker = new DgCandidateRanker();

        final DgRankingResult whole = ranker.rank(
                candidates, DgRankingMethod.WHOLE_SECTION_BASELINE);
        final DgRankingResult dg = ranker.rank(
                candidates, DgRankingMethod.DG_ONLY);
        final DgRankingResult augmented = ranker.rank(
                candidates, DgRankingMethod.DG_AUGMENTED);
        final DgRankingResult multi = ranker.rank(
                candidates, DgRankingMethod.MULTI_FEATURE);

        assertEquals("b", whole.rankedCandidates().get(0)
                .evidence().candidateId());
        assertEquals("b", dg.rankedCandidates().get(0)
                .evidence().candidateId());
        assertEquals("b", augmented.rankedCandidates().get(0)
                .evidence().candidateId());
        assertEquals("b", multi.rankedCandidates().get(0)
                .evidence().candidateId());
        assertEquals(GuidedRunMode.MANUAL_ANATOMICAL_RANKING,
                multi.provenance());
        assertFalse(multi.mayProduceAutomaticConsistent());
        assertFalse(multi.mayProduceGuidedConsistent());
        assertEquals(candidates, multi.rawCandidates());
    }

    @Test
    void zeroMadOrInsufficientDgEvidenceIsUnavailableNotInvented() {
        final List<DgCandidateEvidence> flat = List.of(
                evidence("a", 200, 0.5, 4, 3),
                evidence("b", 201, 0.5, 4, 3),
                evidence("c", 202, 0.5, 4, 3));

        final DgRankingResult result = new DgCandidateRanker().rank(
                flat, DgRankingMethod.DG_AUGMENTED);

        assertFalse(result.assessable());
        assertTrue(result.unavailableComponents().contains("WHOLE_SECTION"));
        assertTrue(result.unavailableComponents().contains("DG"));
        assertEquals(List.of("INSUFFICIENT_DG_EVIDENCE"), result.warnings());
    }

    @Test
    void multiFeatureAllowsUnavailableAnchorsButReportsIt() {
        final List<DgCandidateEvidence> candidates = List.of(
                evidenceWithoutAnchor("a", 200, 0.60, 8),
                evidenceWithoutAnchor("b", 201, 0.80, 4),
                evidenceWithoutAnchor("c", 202, 0.70, 6));

        final DgRankingResult result = new DgCandidateRanker().rank(
                candidates, DgRankingMethod.MULTI_FEATURE);

        assertTrue(result.assessable());
        assertTrue(result.unavailableComponents()
                .contains("NON_DG_INPUT_ANCHORS"));
        assertEquals(1, result.warnings().size());
    }

    private static DgCandidateEvidence evidence(
            final String id,
            final int level,
            final double whole,
            final double dg,
            final double anchor) {
        return new DgCandidateEvidence(
                id,
                new AllenCoronalLevel(level),
                AtlasPlaneTilt.CORONAL,
                OptionalDouble.of(whole),
                Map.of(
                        AnatomicalSide.LEFT, OptionalDouble.of(dg),
                        AnatomicalSide.RIGHT, OptionalDouble.of(dg)),
                OptionalDouble.of(anchor),
                Integer.toHexString(level).repeat(64).substring(0, 64));
    }

    private static DgCandidateEvidence evidenceWithoutAnchor(
            final String id,
            final int level,
            final double whole,
            final double dg) {
        return new DgCandidateEvidence(
                id,
                new AllenCoronalLevel(level),
                AtlasPlaneTilt.CORONAL,
                OptionalDouble.of(whole),
                Map.of(
                        AnatomicalSide.LEFT, OptionalDouble.of(dg),
                        AnatomicalSide.RIGHT, OptionalDouble.of(dg)),
                OptionalDouble.empty(),
                Integer.toHexString(level).repeat(64).substring(0, 64));
    }
}
