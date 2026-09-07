package org.atlasalign.plugin.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.c01.C01SearchContext;
import org.atlasalign.application.dg.AnatomicalSide;
import org.atlasalign.application.dg.DgInputAnchor;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.junit.jupiter.api.Test;

class DgCandidateGeometryScorerTest {

    @Test
    void mapsAtlasToSourceAndPreservesLeftAndRightEvidence() {
        final Map<AnatomicalSide, List<Point2D>> atlas = Map.of(
                AnatomicalSide.LEFT,
                List.of(new Point2D(0, 0), new Point2D(10, 0)),
                AnatomicalSide.RIGHT,
                List.of(new Point2D(100, 0), new Point2D(110, 0)));
        final Map<AnatomicalSide, List<Point2D>> source = Map.of(
                AnatomicalSide.LEFT,
                List.of(new Point2D(10, 20), new Point2D(30, 20)),
                AnatomicalSide.RIGHT,
                List.of(new Point2D(210, 20), new Point2D(230, 20)));
        final DgCandidateGeometryEvidence evidence =
                new DgCandidateGeometryScorer().score(
                        source,
                        atlas,
                        Map.of(
                                DgInputAnchor.DORSAL_CORPUS_CALLOSUM_MIDLINE,
                                new Point2D(20, 40)),
                        Map.of(
                                DgInputAnchor.DORSAL_CORPUS_CALLOSUM_MIDLINE,
                                new Point2D(5, 10)),
                        context(),
                        new PreviewMapping(300, 200, 300, 200));

        assertEquals(0.0, evidence.dgDistanceBySide()
                .get(AnatomicalSide.LEFT).orElseThrow(), 1e-12);
        assertEquals(0.0, evidence.dgDistanceBySide()
                .get(AnatomicalSide.RIGHT).orElseThrow(), 1e-12);
        assertEquals(0.0, evidence.bilateralDgDistance().orElseThrow(), 1e-12);
        assertEquals(0.0,
                evidence.nonDgInputAnchorDistance().orElseThrow(), 1e-12);
    }

    @Test
    void missingHemisphereAbstainsInsteadOfCrossMatchingSides() {
        final DgCandidateGeometryEvidence evidence =
                new DgCandidateGeometryScorer().score(
                        Map.of(
                                AnatomicalSide.LEFT,
                                List.of(new Point2D(10, 20),
                                        new Point2D(30, 20))),
                        Map.of(
                                AnatomicalSide.LEFT,
                                List.of(new Point2D(0, 0),
                                        new Point2D(10, 0)),
                                AnatomicalSide.RIGHT,
                                List.of(new Point2D(100, 0),
                                        new Point2D(110, 0))),
                        Map.of(),
                        Map.of(),
                        context(),
                        new PreviewMapping(300, 200, 300, 200));

        assertTrue(evidence.dgDistanceBySide()
                .get(AnatomicalSide.RIGHT).isEmpty());
        assertTrue(evidence.bilateralDgDistance().isEmpty());
        assertTrue(evidence.nonDgInputAnchorDistance().isEmpty());
    }

    private static C01SearchContext context() {
        return new C01SearchContext(
                "1".repeat(64),
                "2".repeat(64),
                C01SearchContext.R3_RELEASE_ID,
                "3".repeat(64),
                new AllenCoronalLevel(264),
                AtlasPlaneTilt.CORONAL,
                new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        2, 0, 10,
                        0, 2, 20),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                C01SearchContext.FEATURE_GENERATION_ID);
    }
}
