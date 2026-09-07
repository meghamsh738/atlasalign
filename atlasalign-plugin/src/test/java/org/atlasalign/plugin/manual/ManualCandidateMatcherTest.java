package org.atlasalign.plugin.manual;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.manual.AnatomicalSide;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D;
import org.atlasalign.application.manual.ContourCaptureStatus;
import org.atlasalign.application.manual.ContourCompleteness;
import org.atlasalign.application.manual.ContourSegment;
import org.atlasalign.application.manual.ContourTopology;
import org.atlasalign.application.manual.ContourVertex;
import org.atlasalign.application.manual.ManualContour;
import org.atlasalign.application.manual.ManualContourKind;
import org.atlasalign.application.manual.ManualOutlineWarp2D;
import org.atlasalign.application.manual.MonotoneBoundary2D;
import org.atlasalign.application.manual.SectionGeometry;
import org.atlasalign.application.manual.SectionObservation;
import org.atlasalign.application.manual.SourcePixelPoint;
import org.atlasalign.application.manual.VerifiedAtlasGuideIdentity;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.plugin.review.SelectedAtlasRegion;
import org.junit.jupiter.api.Test;

class ManualCandidateMatcherTest {

    private static final SelectedAtlasRegion ROOT = new SelectedAtlasRegion(
            15, "root", "Brain", Set.of(11, 15));
    private static final SelectedAtlasRegion GUIDE = new SelectedAtlasRegion(
            11, "DG-sg", "Dentate gyrus granule layer", Set.of(11));
    private static final SectionObservation DIRECT = new SectionObservation(
            SectionGeometry.FULL,
            AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
            ObservedAnatomicalHemisphere.BOTH, true, true);

    @Test
    void matchingGuideShapeRanksAheadOfShiftedGuide() {
        final List<ManualContour> contours = List.of(
                tissueOutline(), tissueGuide());

        final ManualCandidateMatch matching = ManualCandidateMatcher.match(
                plane(264, 6), AtlasPlaneTilt.CORONAL,
                ROOT, GUIDE, contours, DIRECT, 100, 80);
        final ManualCandidateMatch shifted = ManualCandidateMatcher.match(
                plane(270, 3), AtlasPlaneTilt.CORONAL,
                ROOT, GUIDE, contours, DIRECT, 100, 80);

        assertTrue(matching.combinedMismatch()
                < shifted.combinedMismatch());
        assertTrue(matching.guideMismatch() < shifted.guideMismatch());
        assertEquals(264, matching.allenAxis0Index());
    }

    @Test
    void completeFullOutlineCarriesOneProvisionalWarpAcrossAllRegions() {
        final AtlasCoronalPlane selectedPlane = bilateralPlane(264);
        final AtlasPlaneTilt selectedTilt = new AtlasPlaneTilt(-1.0, 2.0);
        final ManualCandidateMatch match = ManualCandidateMatcher.match(
                selectedPlane, selectedTilt,
                ROOT, GUIDE, List.of(warpedTissueOutline(), tissueGuide()),
                DIRECT, new PreviewMapping(100, 80, 80, 64));

        assertEquals(264, match.allenAxis0Index());
        assertEquals(selectedTilt, match.tilt(),
                "Outline refinement must not change either cutting-plane tilt");
        assertTrue(match.plane() == selectedPlane,
                "Outline refinement must retain the exact selected atlas plane object");
        assertEquals("outline-bounds-affine-v1",
                match.outlineFitMethod());
        assertTrue(match.hasProvisionalOutlineWarp());
        assertEquals(64, match.outlineWarpContentSha256().length());
        assertEquals(OutlineWarpAnchorState.PROVISIONAL_UNCONFIRMED,
                match.outlineWarpAnchorState());
        assertTrue(match.tissueOutlineMismatch() < 0.02,
                "outline mismatch must be evaluated after the fitted warp");
        assertEquals(List.of("dorsal-midline", "image-right",
                "ventral-midline", "image-left"),
                match.outlineWarp().orElseThrow().anchors().stream()
                        .map(anchor -> anchor.name()).toList());

        final Point2D rootPoint = match.rootOverlayPoints().get(0);
        final Point2D guidePoint = match.guideOverlayPoints().get(0);
        assertEquals(match.outlineWarp().orElseThrow().apply(
                match.atlasToPreviewAffine().apply(rootPoint)),
                match.mapAtlasToPreview(rootPoint));
        assertEquals(match.outlineWarp().orElseThrow().apply(
                match.atlasToPreviewAffine().apply(guidePoint)),
                match.mapAtlasToPreview(guidePoint));
        assertEquals(match.mapAtlasToPreview(rootPoint),
                match.rootOverlayPreviewPoints().get(0));
        assertEquals(match.mapAtlasToPreview(guidePoint),
                match.guideOverlayPreviewPoints().get(0));

        final Point2D atlas = new Point2D(7.25, 6.5);
        final Point2D recovered = match.mapPreviewToAtlas(
                match.mapAtlasToPreview(atlas));
        assertPoint(atlas.x(), atlas.y(), recovered);

        final SectionObservation reflected = new SectionObservation(
                SectionGeometry.FULL,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT,
                ObservedAnatomicalHemisphere.BOTH, true, true);
        final ManualCandidateMatch reversed = ManualCandidateMatcher.match(
                bilateralPlane(264), AtlasPlaneTilt.CORONAL,
                ROOT, GUIDE, List.of(warpedTissueOutline(), tissueGuide()),
                reflected, new PreviewMapping(100, 80, 80, 64));
        assertTrue(reversed.hasProvisionalOutlineWarp());
        assertTrue(match.outlineWarp().orElseThrow().diagnostics()
                .atlasTraversalDirection()
                != reversed.outlineWarp().orElseThrow().diagnostics()
                        .atlasTraversalDirection());
    }

    @Test
    void outlineOnlyPreviewIsExactBeforeAnyGuideTrace() {
        final PreviewMapping previewMapping = new PreviewMapping(
                100, 80, 80, 64);
        final AtlasCoronalPlane plane = bilateralPlane(264);
        final List<ManualContour> contours = List.of(warpedTissueOutline());
        final ManualOutlineWarpPreview preview =
                ManualCandidateMatcher.outlinePreview(
                        plane, ROOT, GUIDE, contours, DIRECT, previewMapping);
        assertTrue(preview.outlineTransform().diagnostics()
                .maximumBoundaryErrorPixels() <= 0.25);
        final Point2D guidePoint = preview.guideAtlasPaths().get(0).get(0);
        assertEquals(preview.outlineTransform().apply(
                        preview.atlasToPreviewAffine().apply(guidePoint)),
                preview.mapAtlasToPreview(guidePoint));
        assertFalse(preview.mappedRootPreviewPaths().isEmpty());
        assertFalse(preview.mappedGuidePreviewPaths().isEmpty());
        assertEquals(2, preview.guideAtlasPaths().size(),
                "bilateral guide components must remain separate paths");
        assertEquals(2, preview.mappedGuidePreviewPaths().size(),
                "exact rendering must not connect disconnected hemispheres");
        assertTrue(preview.mappedRootPreviewPaths().get(0).size()
                >= preview.rootAtlasPaths().get(0).size(),
                "rendered root must retain mesh-edge splits from mapPath");
    }

    @Test
    void exactPreviewReplaysDeterministicallyAndIgnoresLegacyDensityHint() {
        final PreviewMapping mapping = new PreviewMapping(100, 80, 80, 64);
        final ManualOutlineWarpPreview preview = ManualCandidateMatcher
                .outlinePreview(bilateralPlane(264), ROOT, GUIDE,
                        List.of(warpedTissueOutline()), DIRECT, mapping, 5);
        final ManualOutlineWarpPreview replay = ManualCandidateMatcher
                .outlinePreview(bilateralPlane(264), ROOT, GUIDE,
                        List.of(warpedTissueOutline()), DIRECT, mapping);
        assertEquals(preview.contentSha256(), replay.contentSha256());
    }

    @Test
    void excludedFissureGapFailsClosedRatherThanBeingGuessedAcross() {
        final PreviewMapping mapping = new PreviewMapping(100, 80, 80, 64);
        final ManualContour withGap = fissuredTissueOutline(true);
        final var error = assertThrows(IllegalArgumentException.class,
                () -> ManualCandidateMatcher.outlinePreview(
                        bilateralPlane(264), ROOT, GUIDE,
                        List.of(withGap), DIRECT, mapping));
        assertTrue(error.getMessage().contains("does not guess"));
        assertEquals(Set.of(
                        new ContourSegment("fissure-left", "fissure-tip"),
                        new ContourSegment("fissure-tip", "fissure-right")),
                withGap.excludedGapSegments(),
                "failed exact mapping must not edit reviewer input");
    }

    @Test
    void disconnectedAtlasRootFailsClosedBeforeExactOutlineFit() {
        final var error = assertThrows(IllegalArgumentException.class,
                () -> ManualCandidateMatcher.outlinePreview(
                        disconnectedRootPlane(264), ROOT, GUIDE,
                        List.of(warpedTissueOutline()), DIRECT,
                        new PreviewMapping(100, 80, 80, 64)));
        assertTrue(error.getMessage().contains(
                "requires one connected atlas root exterior"));
    }

    @Test
    void cyclicStartAndPermittedReversalNormalizeIdentically() {
        final List<Point2D> loop = List.of(
                new Point2D(10, 5), new Point2D(30, 4),
                new Point2D(45, 20), new Point2D(34, 40),
                new Point2D(10, 38), new Point2D(2, 18));
        final List<Point2D> shifted = new ArrayList<>();
        shifted.addAll(loop.subList(3, loop.size()));
        shifted.addAll(loop.subList(0, 3));
        final List<Point2D> reversed = new ArrayList<>(shifted);
        Collections.reverse(reversed);
        final List<Point2D> normalized = ManualCandidateMatcher
                .normalizeClosedLoop(loop);
        final List<Point2D> normalizedShifted = ManualCandidateMatcher
                .normalizeClosedLoop(shifted);
        final List<Point2D> normalizedReversed = ManualCandidateMatcher
                .normalizeClosedLoop(reversed);
        assertEquals(normalized, normalizedShifted);
        assertEquals(normalized, normalizedReversed);

        final BoundaryAuthoritativeTransform2D baseline =
                exactIdentity(normalized);
        assertEquals(baseline.contentSha256(),
                exactIdentity(normalizedShifted).contentSha256());
        assertEquals(baseline.contentSha256(),
                exactIdentity(normalizedReversed).contentSha256());
    }

    private static BoundaryAuthoritativeTransform2D exactIdentity(
            final List<Point2D> loop) {
        return BoundaryAuthoritativeTransform2D.fitFull(
                MonotoneBoundary2D.arcLengthIndexed("atlas-", loop),
                MonotoneBoundary2D.arcLengthIndexed("tissue-", loop),
                100, 80);
    }

    @Test
    void semanticAnchorProposalIsDeterministicAcrossOppositeWinding() {
        final List<Point2D> clockwise = List.of(
                new Point2D(40, 10), new Point2D(70, 12),
                new Point2D(72, 32), new Point2D(68, 54),
                new Point2D(40, 50), new Point2D(12, 54),
                new Point2D(8, 32), new Point2D(10, 14));
        final List<Point2D> reversed = new ArrayList<>(clockwise);
        Collections.reverse(reversed);

        final var first = ManualCandidateMatcher
                .provisionalSemanticAnchors(clockwise, reversed);
        final var replay = ManualCandidateMatcher
                .provisionalSemanticAnchors(clockwise, reversed);

        assertEquals(first, replay);
        assertEquals(List.of("dorsal-midline", "image-right",
                "ventral-midline", "image-left"), first.stream()
                        .map(anchor -> anchor.name()).toList());
        assertTrue(cyclicDirection(first.stream()
                .mapToInt(anchor -> anchor.atlasVertexIndex()).toArray())
                != cyclicDirection(first.stream()
                        .mapToInt(anchor -> anchor.tissueVertexIndex())
                        .toArray()));
    }

    @Test
    void missingSelectedAnatomyFailsClosed() {
        final int[] empty = new int[20 * 16];
        assertThrows(IllegalArgumentException.class,
                () -> ManualCandidateMatcher.match(
                        new AtlasCoronalPlane(
                                264, 20, 16, new int[empty.length], empty),
                        AtlasPlaneTilt.CORONAL,
                        ROOT, GUIDE,
                        List.of(tissueOutline(), tissueGuide()),
                        DIRECT, 100, 80));
    }

    @Test
    void halfSectionUsesOnlyTheConfirmedAnatomicalHemisphere() {
        final SectionObservation leftHalf = new SectionObservation(
                SectionGeometry.IMAGE_LEFT_HALF,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.LEFT, true, true);
        final ManualCandidateMatch match = ManualCandidateMatcher.match(
                plane(264, 6), AtlasPlaneTilt.CORONAL,
                ROOT, GUIDE, List.of(tissueOutline(), tissueGuide()),
                leftHalf, 100, 80);

        assertTrue(match.rootOverlayPoints().stream()
                .allMatch(point -> point.x() <= 9.5));
        assertTrue(match.guideOverlayPoints().stream()
                .allMatch(point -> point.x() <= 9.5));
    }

    @Test
    void anatomicalSideIsPreservedAcrossDirectAndReflectedDisplay() {
        final SectionObservation reflected = new SectionObservation(
                SectionGeometry.FULL,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT,
                ObservedAnatomicalHemisphere.BOTH, true, true);
        final List<ManualContour> leftEvidence = List.of(
                tissueOutline(), tissueGuide(
                        AnatomicalSide.LEFT, ContourCompleteness.COMPLETE));

        final ManualCandidateMatch direct = ManualCandidateMatcher.match(
                bilateralPlane(264), AtlasPlaneTilt.CORONAL,
                ROOT, GUIDE, leftEvidence, DIRECT, 100, 80);
        final ManualCandidateMatch reversed = ManualCandidateMatcher.match(
                bilateralPlane(264), AtlasPlaneTilt.CORONAL,
                ROOT, GUIDE, leftEvidence, reflected, 100, 80);

        assertTrue(direct.guideOverlayPoints().stream()
                .allMatch(point -> point.x() <= 9.5));
        assertTrue(reversed.guideOverlayPoints().stream()
                .allMatch(point -> point.x() <= 9.5));
        assertTrue(meanMappedGuideX(direct) < 40);
        assertTrue(meanMappedGuideX(reversed) > 40);

        final ManualCandidateMatch right = ManualCandidateMatcher.match(
                bilateralPlane(264), AtlasPlaneTilt.CORONAL,
                ROOT, GUIDE, List.of(tissueOutline(), tissueGuide(
                        AnatomicalSide.RIGHT, ContourCompleteness.COMPLETE)),
                DIRECT, 100, 80);
        assertTrue(right.guideOverlayPoints().stream()
                .allMatch(point -> point.x() >= 9.5));
    }

    @Test
    void damagedUncertainAndSideUnknownGuideEvidenceFailClosed() {
        for (final ContourCompleteness completeness : List.of(
                ContourCompleteness.DAMAGED,
                ContourCompleteness.UNCERTAIN)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ManualCandidateMatcher.match(
                            bilateralPlane(264), AtlasPlaneTilt.CORONAL,
                            ROOT, GUIDE, List.of(tissueOutline(), tissueGuide(
                                    AnatomicalSide.LEFT, completeness)),
                            DIRECT, 100, 80));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ManualCandidateMatcher.match(
                        bilateralPlane(264), AtlasPlaneTilt.CORONAL,
                        ROOT, GUIDE, List.of(tissueOutline(), tissueGuide(
                                AnatomicalSide.UNSURE,
                                ContourCompleteness.COMPLETE)),
                        DIRECT, 100, 80));

        final ManualCandidateMatch partial = ManualCandidateMatcher.match(
                bilateralPlane(264), AtlasPlaneTilt.CORONAL,
                ROOT, GUIDE, List.of(tissueOutline(), tissueGuide(
                        AnatomicalSide.LEFT, ContourCompleteness.PARTIAL)),
                DIRECT, 100, 80);
        assertTrue(Double.isFinite(partial.guideMismatch()));

        for (final ContourCompleteness completeness : List.of(
                ContourCompleteness.DAMAGED,
                ContourCompleteness.UNCERTAIN)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ManualCandidateMatcher.match(
                            bilateralPlane(264), AtlasPlaneTilt.CORONAL,
                            ROOT, GUIDE, List.of(tissueOutline(completeness),
                                    tissueGuide()), DIRECT, 100, 80));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ManualCandidateMatcher.match(
                        bilateralPlane(264), AtlasPlaneTilt.CORONAL,
                        ROOT, GUIDE, List.of(
                                tissueOutline(ContourCompleteness.PARTIAL),
                                tissueGuide()), DIRECT, 100, 80));
    }

    @Test
    void fullOutlineAmbiguityAndUnsafeGeometryFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> ManualCandidateMatcher.match(
                        bilateralPlane(264), AtlasPlaneTilt.CORONAL,
                        ROOT, GUIDE,
                        List.of(tissueOutline(), warpedTissueOutline(),
                                tissueGuide()), DIRECT, 100, 80));
        assertThrows(IllegalArgumentException.class,
                () -> ManualCandidateMatcher.match(
                        bilateralPlane(264), AtlasPlaneTilt.CORONAL,
                        ROOT, GUIDE, List.of(bowTieTissueOutline(),
                                tissueGuide()), DIRECT, 100, 80));
    }

    @Test
    void partialSectionIsExplicitlyAffineOnly() {
        final SectionObservation leftHalf = new SectionObservation(
                SectionGeometry.IMAGE_LEFT_HALF,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.LEFT, true, true);
        final ManualCandidateMatch match = ManualCandidateMatcher.match(
                plane(264, 6), AtlasPlaneTilt.CORONAL,
                ROOT, GUIDE, List.of(tissueOutline(), tissueGuide()),
                leftHalf, 100, 80);

        assertTrue(match.outlineWarp().isEmpty());
        assertEquals(OutlineWarpAnchorState.NOT_APPLICABLE_PARTIAL_AFFINE,
                match.outlineWarpAnchorState());
        assertEquals("outline-bounds-similarity-v0-partial",
                match.outlineFitMethod());
    }

    private static double meanMappedGuideX(
            final ManualCandidateMatch match) {
        return match.guideOverlayPoints().stream()
                .map(match::mapAtlasToSource)
                .mapToDouble(point -> point.x()).average().orElseThrow();
    }

    private static AtlasCoronalPlane plane(
            final int level,
            final int guideMinimumX) {
        final int width = 20;
        final int height = 16;
        final int[] labels = new int[width * height];
        for (int y = 2; y <= 13; y++) {
            for (int x = 2; x <= 17; x++) {
                labels[y * width + x] = 15;
            }
        }
        for (int y = 5; y <= 7; y++) {
            for (int x = guideMinimumX; x <= guideMinimumX + 3; x++) {
                labels[y * width + x] = 11;
            }
        }
        return new AtlasCoronalPlane(
                level, width, height, new int[labels.length], labels);
    }

    private static AtlasCoronalPlane bilateralPlane(final int level) {
        final int width = 20;
        final int height = 16;
        final int[] labels = new int[width * height];
        for (int y = 2; y <= 13; y++) {
            for (int x = 2; x <= 17; x++) {
                labels[y * width + x] = 15;
            }
        }
        for (int y = 5; y <= 7; y++) {
            for (int x = 4; x <= 7; x++) {
                labels[y * width + x] = 11;
            }
            for (int x = 12; x <= 15; x++) {
                labels[y * width + x] = 11;
            }
        }
        return new AtlasCoronalPlane(
                level, width, height, new int[labels.length], labels);
    }

    private static AtlasCoronalPlane disconnectedRootPlane(final int level) {
        final AtlasCoronalPlane connected = bilateralPlane(level);
        final int[] labels = connected.annotationId();
        labels[0] = 15;
        return new AtlasCoronalPlane(
                level, connected.width(), connected.height(),
                new int[labels.length], labels);
    }

    private static ManualContour tissueOutline() {
        return tissueOutline(ContourCompleteness.COMPLETE);
    }

    private static ManualContour warpedTissueOutline() {
        return new ManualContour(
                "warped-outline", ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.COMPLETE,
                AnatomicalSide.BILATERAL, ContourCompleteness.COMPLETE,
                Optional.empty(), ManualWorkflowIdentities.source(
                        GuidedManualPluginFixtures.basis()), List.of(
                        point("s1", 10, 14), point("s2", 40, 10),
                        point("s3", 70, 12), point("s4", 72, 32),
                        point("s5", 68, 54), point("s6", 40, 50),
                        point("s7", 12, 54), point("s8", 8, 32)),
                Set.of());
    }

    private static ManualContour bowTieTissueOutline() {
        return new ManualContour(
                "bow-tie", ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.COMPLETE,
                AnatomicalSide.BILATERAL, ContourCompleteness.COMPLETE,
                Optional.empty(), ManualWorkflowIdentities.source(
                        GuidedManualPluginFixtures.basis()), List.of(
                        point("b1", 10, 10), point("b2", 70, 54),
                        point("b3", 70, 10), point("b4", 10, 54)),
                Set.of());
    }

    private static ManualContour fissuredTissueOutline(
            final boolean excludeFissure) {
        final Set<ContourSegment> gaps = excludeFissure
                ? Set.of(
                        new ContourSegment("fissure-left", "fissure-tip"),
                        new ContourSegment("fissure-tip", "fissure-right"))
                : Set.of();
        return new ManualContour(
                "fissured-outline", ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.COMPLETE,
                AnatomicalSide.BILATERAL, ContourCompleteness.COMPLETE,
                Optional.empty(), ManualWorkflowIdentities.source(
                        GuidedManualPluginFixtures.basis()), List.of(
                        point("s1", 10, 14),
                        point("fissure-left", 27, 10),
                        point("fissure-tip", 32, 28),
                        point("fissure-right", 37, 10),
                        point("s3", 70, 12), point("s4", 72, 32),
                        point("s5", 68, 54), point("s6", 40, 50),
                        point("s7", 12, 54), point("s8", 8, 32)),
                gaps);
    }

    private static ManualContour tissueOutline(
            final ContourCompleteness completeness) {
        return new ManualContour(
                "outline", ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.COMPLETE,
                AnatomicalSide.BILATERAL, completeness, Optional.empty(),
                ManualWorkflowIdentities.source(
                        GuidedManualPluginFixtures.basis()), List.of(
                        point("o1", 10, 10), point("o2", 70, 10),
                        point("o3", 70, 54), point("o4", 10, 54)),
                Set.of());
    }

    private static ManualContour tissueGuide() {
        return tissueGuide(
                AnatomicalSide.LEFT, ContourCompleteness.COMPLETE);
    }

    private static ManualContour tissueGuide(
            final AnatomicalSide side,
            final ContourCompleteness completeness) {
        final var identity = new VerifiedAtlasGuideIdentity(
                "1".repeat(64), "Allen CCF structure graph",
                "2017", 11, "DG-sg");
        return new ManualContour(
                "guide", ManualContourKind.ANATOMICAL_STRUCTURE,
                ContourTopology.OPEN, ContourCaptureStatus.COMPLETE,
                side, completeness, Optional.of(identity),
                ManualWorkflowIdentities.source(
                        GuidedManualPluginFixtures.basis()), List.of(
                        point("g1", 26, 22), point("g2", 38, 22),
                        point("g3", 38, 30), point("g4", 26, 30)),
                Set.of());
    }

    private static ManualContour contour(
            final String id,
            final ManualContourKind kind,
            final ContourTopology topology,
            final Optional<VerifiedAtlasGuideIdentity> guide,
            final List<ContourVertex> points) {
        return new ManualContour(
                id, kind, topology, ContourCaptureStatus.COMPLETE,
                kind == ManualContourKind.TISSUE_OUTLINE
                        ? AnatomicalSide.BILATERAL : AnatomicalSide.LEFT,
                ContourCompleteness.COMPLETE, guide,
                ManualWorkflowIdentities.source(
                        GuidedManualPluginFixtures.basis()),
                points, Set.of());
    }

    private static ContourVertex point(
            final String id,
            final double x,
            final double y) {
        return new ContourVertex(id, new SourcePixelPoint(x, y));
    }

    private static void assertPoint(
            final double expectedX,
            final double expectedY,
            final org.atlasalign.core.Point2D actual) {
        assertEquals(expectedX, actual.x(), 1e-6);
        assertEquals(expectedY, actual.y(), 1e-6);
    }

    private static int cyclicDirection(final int[] indices) {
        int positive = 0;
        int negative = 0;
        for (int index = 0; index < indices.length; index++) {
            final int delta = indices[(index + 1) % indices.length]
                    - indices[index];
            if (delta > 0) {
                positive++;
            } else if (delta < 0) {
                negative++;
            }
        }
        return positive > negative ? 1 : -1;
    }
}
