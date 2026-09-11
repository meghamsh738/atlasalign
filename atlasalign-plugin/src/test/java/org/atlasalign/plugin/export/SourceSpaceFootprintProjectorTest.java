package org.atlasalign.plugin.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.atlasalign.application.AcceptedAlignmentSnapshot;
import org.atlasalign.application.HalfAtlasCoverage;
import org.atlasalign.application.ReviewedTissueSupport;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.junit.jupiter.api.Test;

class SourceSpaceFootprintProjectorTest {

    private final SourceSpaceFootprintProjector projector =
            new SourceSpaceFootprintProjector();

    @Test
    void sampledMembershipMatchesNativeFootprintsForFullHalfAndOverlappingDisjoinedSelections() {
        final var source = ExportTestFixtures.sourceSnapshot(8, 1, 1, 1);
        final var left = ExportTestFixtures.region(1, "LEFT");
        final var right = ExportTestFixtures.region(2, "RIGHT");
        assertMembershipMatchesFootprints(ExportTestFixtures.accepted(source, Optional.empty(), false),
                ExportTestFixtures.annotationPlane(), List.of(left, right));
        assertMembershipMatchesFootprints(ExportTestFixtures.acceptedHalfWithJoinedPlacement(source),
                ExportTestFixtures.annotationPlane(), List.of(left));
        assertMembershipMatchesFootprints(ExportTestFixtures.acceptedDisjoinedOverlap(source),
                ExportTestFixtures.overlappingDisjoinedAnnotationPlane(), List.of(left, right));
        final var clippedDisjoined = withMappingAndSupport(ExportTestFixtures.acceptedDisjoinedOverlap(source),
                new PreviewMapping(8, 6, 8, 6), ExportTestFixtures.leftSupport());
        assertMembershipMatchesFootprints(clippedDisjoined,
                ExportTestFixtures.overlappingDisjoinedAnnotationPlane(), List.of(left, right));
    }

    @Test
    void sampledMembershipMatchesNativeClippingAtFractionalCentersHolesAndPreviewEdges() {
        final var source = ExportTestFixtures.sourceSnapshot(137, 103, 8, 1, 1, 1);
        final var mapping = new PreviewMapping(137, 103, 64, 48);
        final boolean[] withHole = new boolean[64 * 48];
        java.util.Arrays.fill(withHole, true);
        for (int y = 16; y <= 26; y++) for (int x = 20; x <= 37; x++) withHole[y * 64 + x] = false;
        for (final var support : List.of(fractionalSupport(64, 48), multiComponentSupport(64, 48),
                ReviewedTissueSupport.fromMask(BinaryMask.fromBooleans(64, 48, withHole)))) {
            final var accepted = withMappingAndSupport(ExportTestFixtures.accepted(source, Optional.empty(), false),
                    mapping, support);
            assertMembershipMatchesFootprints(accepted, ExportTestFixtures.filledAnnotationPlane(1),
                    List.of(ExportTestFixtures.region(1, "ALL")));
        }
    }

    @Test
    void sampledMembershipFreezesRequestedRegionsAndDoesNotChangeAtlasLabels() {
        final var source = ExportTestFixtures.sourceSnapshot(8, 1, 1, 1);
        final var accepted = ExportTestFixtures.accepted(source, Optional.empty(), false);
        final var plane = ExportTestFixtures.annotationPlane();
        final int[] before = plane.annotationId();
        final var requested = new ArrayList<>(List.of(ExportTestFixtures.region(1, "LEFT")));
        final var membership = projector.membership(accepted, plane, requested);
        requested.clear();
        assertTrue(membership.contains(3, 5));
        assertFalse(membership.contains(4, 5));
        assertFalse(membership.contains(-1, 0));
        assertFalse(membership.contains(0, -1));
        assertFalse(membership.contains(8, 0));
        assertFalse(membership.contains(0, 6));
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, plane.annotationId());
    }

    @Test
    void mapsNearestNeighbourLabelsIntoTightSourceBounds() {
        final var source = ExportTestFixtures.sourceSnapshot(8, 1, 1, 1);
        final var accepted = ExportTestFixtures.accepted(
                source, Optional.empty(), false);

        final List<SourceRegionFootprint> footprints = projector.project(
                accepted, ExportTestFixtures.annotationPlane(),
                List.of(ExportTestFixtures.region(1, "LEFT"),
                        ExportTestFixtures.region(2, "RIGHT")),
                () -> false, ignored -> {
                });

        assertEquals(2, footprints.size());
        assertEquals(24, footprints.get(0).pixelCount());
        assertEquals(0, footprints.get(0).bounds().minimumX());
        assertEquals(4, footprints.get(0).bounds().width());
        assertTrue(footprints.get(0).containsSourcePixel(3, 5));
        assertFalse(footprints.get(0).containsSourcePixel(4, 5));
        assertEquals(4, footprints.get(1).bounds().minimumX());
        assertEquals(4, footprints.get(1).bounds().width());
    }

    @Test
    void acceptedTissueClippingIntersectsTheSourceFootprint() {
        final var source = ExportTestFixtures.sourceSnapshot(8, 1, 1, 1);
        final var accepted = ExportTestFixtures.accepted(source,
                Optional.of(ExportTestFixtures.leftSupport()), true);

        final SourceRegionFootprint left = projector.project(
                accepted, ExportTestFixtures.annotationPlane(),
                List.of(ExportTestFixtures.region(1, "LEFT")),
                () -> false, ignored -> {
                }).get(0);

        assertEquals(24, left.pixelCount());
        assertEquals(4, left.bounds().width());
    }

    @Test
    void combinedUnionDeduplicatesOverlappingPixels() {
        final var source = ExportTestFixtures.sourceSnapshot(8, 1, 1, 1);
        final var accepted = ExportTestFixtures.accepted(
                source, Optional.empty(), false);
        final List<SourceRegionFootprint> individual = projector.project(
                accepted, ExportTestFixtures.annotationPlane(),
                List.of(ExportTestFixtures.region(1, "LEFT"),
                        ExportTestFixtures.region(2, "RIGHT")),
                () -> false, ignored -> {
                });

        final SourceRegionFootprint union = projector.union(individual);

        assertEquals(48, union.pixelCount());
        assertEquals(8, union.bounds().width());
        assertEquals(2, union.selections().size());
    }

    @Test
    void overlappingDisjoinedHalvesContributeBothAtlasLabels() {
        final var source = ExportTestFixtures.sourceSnapshot(8, 1, 1, 1);
        final var accepted = ExportTestFixtures.acceptedDisjoinedOverlap(
                source);

        final List<SourceRegionFootprint> footprints = projector.project(
                accepted,
                ExportTestFixtures.overlappingDisjoinedAnnotationPlane(),
                List.of(ExportTestFixtures.region(1, "LEFT"),
                        ExportTestFixtures.region(2, "RIGHT")),
                () -> false, ignored -> {
                });

        assertEquals(48, footprints.get(0).pixelCount());
        assertEquals(48, footprints.get(1).pixelCount());
        assertTrue(footprints.get(0).containsSourcePixel(0, 0));
        assertTrue(footprints.get(1).containsSourcePixel(0, 0));
        assertEquals(48, projector.union(footprints).pixelCount());
    }

    @Test
    void halfExportUsesTheAcceptedJoinedCoarsePlacement() {
        final var source = ExportTestFixtures.sourceSnapshot(8, 1, 1, 1);
        final var accepted = ExportTestFixtures
                .acceptedHalfWithJoinedPlacement(source);

        final SourceRegionFootprint left = projector.project(
                accepted, ExportTestFixtures.annotationPlane(),
                List.of(ExportTestFixtures.region(1, "LEFT")),
                () -> false, ignored -> {
                }).get(0);

        assertEquals(24, left.pixelCount());
        assertEquals(2, left.bounds().minimumX(),
                "the source ROI must follow the accepted +2 px joined placement");
        assertEquals(4, left.bounds().width());
        assertFalse(left.containsSourcePixel(1, 0));
        assertTrue(left.containsSourcePixel(2, 0));
        assertTrue(left.containsSourcePixel(5, 5));
    }

    @Test
    void strictHalfExportRejectsTheHiddenAtlasSide() {
        final var source = ExportTestFixtures.sourceSnapshot(8, 1, 1, 1);
        final double rightStart = Math.ceil(
                (org.atlasalign.application.AtlasReviewProvenance
                        .ALLEN_CORONAL_WIDTH - 1.0) * 0.5);
        final var accepted = ExportTestFixtures
                .acceptedHalfWithJoinedPlacement(
                        source, HalfAtlasCoverage.VISIBLE_SIDE_ONLY,
                        -rightStart);

        final IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> projector.project(
                        accepted,
                        ExportTestFixtures
                                .overlappingDisjoinedAnnotationPlane(),
                        List.of(ExportTestFixtures.region(2, "RIGHT")),
                        () -> false, ignored -> {
                        }));

        assertTrue(rejected.getMessage().contains("outside the source"));
    }

    @Test
    void explicitHalfRemnantCoverageExportsTheOppositeAtlasSide() {
        final var source = ExportTestFixtures.sourceSnapshot(8, 1, 1, 1);
        final double rightStart = Math.ceil(
                (org.atlasalign.application.AtlasReviewProvenance
                        .ALLEN_CORONAL_WIDTH - 1.0) * 0.5);
        final var accepted = ExportTestFixtures
                .acceptedHalfWithJoinedPlacement(
                        source,
                        HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT,
                        -rightStart);

        final SourceRegionFootprint opposite = projector.project(
                accepted,
                ExportTestFixtures.overlappingDisjoinedAnnotationPlane(),
                List.of(ExportTestFixtures.region(2, "RIGHT")),
                () -> false, ignored -> {
                }).get(0);

        assertEquals(48, opposite.pixelCount());
        assertEquals(0, opposite.bounds().minimumX());
        assertEquals(8, opposite.bounds().width());
    }

    @Test
    void nonIdentityWarpProjectionUsesIndexedTrianglesAtSourceScale() {
        final int width = 512;
        final int height = 384;
        final var source = ExportTestFixtures.sourceSnapshot(
                width, height, 8, 1, 1, 1);
        final var accepted = ExportTestFixtures
                .acceptedWithNonIdentityWarp(source);

        final long start = System.nanoTime();
        final SourceRegionFootprint footprint = projector.project(
                accepted, ExportTestFixtures.filledAnnotationPlane(1),
                List.of(ExportTestFixtures.region(1, "ALL")),
                () -> false, ignored -> {
                }).get(0);
        final long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS
                .toMillis(System.nanoTime() - start);

        assertEquals(width * height, footprint.pixelCount());
        assertTrue(elapsedMillis < 8_000,
                "Indexed nonidentity projection took "
                        + elapsedMillis + " ms");
    }

    @Test
    void scanlineProjectionExactlyMatchesFractionalPathMembership() {
        final int sourceWidth = 320;
        final int sourceHeight = 240;
        final PreviewMapping mapping = new PreviewMapping(
                sourceWidth, sourceHeight, 80, 60);
        final var source = ExportTestFixtures.sourceSnapshot(
                sourceWidth, sourceHeight, 8, 1, 1, 1);
        final ReviewedTissueSupport support = fractionalSupport(80, 60);
        final AcceptedAlignmentSnapshot accepted = withMappingAndSupport(
                ExportTestFixtures.accepted(
                        source, Optional.empty(), false),
                mapping, support);

        final SourceRegionFootprint footprint = projector.project(
                accepted, ExportTestFixtures.filledAnnotationPlane(1),
                List.of(ExportTestFixtures.region(1, "ALL")),
                () -> false, ignored -> {
                }).get(0);

        int expectedCount = 0;
        for (int y = 0; y < sourceHeight; y++) {
            for (int x = 0; x < sourceWidth; x++) {
                final boolean expected = support.contains(
                        mapping.sourceToPreview(new Point2D(x, y)));
                assertEquals(expected, footprint.containsSourcePixel(x, y),
                        "source pixel " + x + "," + y);
                if (expected) {
                    expectedCount++;
                }
            }
        }
        assertEquals(expectedCount, footprint.pixelCount());
    }

    @Test
    void scanlineProjectionExactlyMatchesIntegerMultipolygonRaster() {
        final int width = 64;
        final int height = 48;
        final PreviewMapping mapping = new PreviewMapping(
                width, height, width, height);
        final var source = ExportTestFixtures.sourceSnapshot(
                width, height, 8, 1, 1, 1);
        final ReviewedTissueSupport support = multiComponentSupport(
                width, height);
        final AcceptedAlignmentSnapshot accepted = withMappingAndSupport(
                ExportTestFixtures.accepted(
                        source, Optional.empty(), false),
                mapping, support);

        final SourceRegionFootprint footprint = projector.project(
                accepted, ExportTestFixtures.filledAnnotationPlane(1),
                List.of(ExportTestFixtures.region(1, "ALL")),
                () -> false, ignored -> {
                }).get(0);

        int expectedCount = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final boolean expected = support.contains(x, y);
                assertEquals(expected, footprint.containsSourcePixel(x, y),
                        "source pixel " + x + "," + y);
                if (expected) {
                    expectedCount++;
                }
            }
        }
        assertEquals(expectedCount, footprint.pixelCount());
    }

    @Test
    void clippedMockScaleProjectionCompletesWithinRuntimeGuard() {
        final int sourceWidth = 4_864;
        final int sourceHeight = 5_184;
        final PreviewMapping mapping = new PreviewMapping(
                sourceWidth, sourceHeight, 192, 205);
        final var source = ExportTestFixtures.sourceSnapshot(
                sourceWidth, sourceHeight, 8, 1, 1, 1);
        final ReviewedTissueSupport support = rectangularSupport(
                192, 205, 54, 66, 54, 38);
        final AcceptedAlignmentSnapshot accepted = withMappingAndSupport(
                ExportTestFixtures.accepted(
                        source, Optional.empty(), false),
                mapping, support);

        final long start = System.nanoTime();
        final SourceRegionFootprint footprint = projector.project(
                accepted, ExportTestFixtures.filledAnnotationPlane(1),
                List.of(ExportTestFixtures.region(1, "ALL")),
                () -> false, ignored -> {
                }).get(0);
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - start);
        System.out.println("clipped 4864x5184 projection: "
                + elapsedMillis + " ms");

        assertTrue(footprint.pixelCount() > 1_000_000,
                "guard must exercise a material source-space footprint");
        assertTrue(elapsedMillis < 10_000,
                "Clipped 4864x5184 projection took "
                        + elapsedMillis + " ms");
    }

    @Test
    void optimizedScanlineMatchesFullRasterReferenceAndReportsTiming() {
        final int sourceWidth = 640;
        final int sourceHeight = 680;
        final PreviewMapping mapping = new PreviewMapping(
                sourceWidth, sourceHeight, 128, 136);
        final var source = ExportTestFixtures.sourceSnapshot(
                sourceWidth, sourceHeight, 8, 1, 1, 1);
        final ReviewedTissueSupport support = fractionalSupport(128, 136);
        final AcceptedAlignmentSnapshot accepted = withMappingAndSupport(
                ExportTestFixtures.accepted(
                        source, Optional.empty(), false),
                mapping, support);
        final var plane = ExportTestFixtures.filledAnnotationPlane(1);

        final long referenceStart = System.nanoTime();
        int referenceCount = 0;
        for (int y = 0; y < sourceHeight; y++) {
            for (int x = 0; x < sourceWidth; x++) {
                final Point2D preview = mapping.sourceToPreview(
                        new Point2D(x, y));
                if (!support.contains(preview)) {
                    continue;
                }
                final Point2D atlas = accepted.mapPreviewToAtlas(preview);
                final int atlasX = (int) Math.round(atlas.x());
                final int atlasY = (int) Math.round(atlas.y());
                if (atlasX >= 0 && atlasX < plane.width()
                        && atlasY >= 0 && atlasY < plane.height()) {
                    referenceCount++;
                }
            }
        }
        final long referenceMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - referenceStart);

        final long optimizedStart = System.nanoTime();
        final SourceRegionFootprint optimized = projector.project(
                accepted, plane,
                List.of(ExportTestFixtures.region(1, "ALL")),
                () -> false, ignored -> {
                }).get(0);
        final long optimizedMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - optimizedStart);
        System.out.println("source-space reference/optimized: "
                + referenceMillis + " ms / " + optimizedMillis + " ms");

        assertEquals(referenceCount, optimized.pixelCount());
        assertTrue(optimizedMillis < 5_000,
                "scanline projection exceeded the focused runtime guard: "
                        + optimizedMillis + " ms (reference "
                        + referenceMillis + " ms)");
    }

    @Test
    void clippingOptimizationPreservesCancellationAndProgressCallbacks() {
        final int sourceWidth = 320;
        final int sourceHeight = 240;
        final PreviewMapping mapping = new PreviewMapping(
                sourceWidth, sourceHeight, 80, 60);
        final var source = ExportTestFixtures.sourceSnapshot(
                sourceWidth, sourceHeight, 8, 1, 1, 1);
        final AcceptedAlignmentSnapshot accepted = withMappingAndSupport(
                ExportTestFixtures.accepted(
                        source, Optional.empty(), false),
                mapping, fractionalSupport(80, 60));
        final AtomicInteger cancellationChecks = new AtomicInteger();
        final List<Double> progress = new ArrayList<>();

        assertThrows(ExportCancelledException.class, () -> projector.project(
                accepted, ExportTestFixtures.filledAnnotationPlane(1),
                List.of(ExportTestFixtures.region(1, "ALL")),
                () -> cancellationChecks.incrementAndGet() == 5,
                progress::add));

        assertEquals(5, cancellationChecks.get());
        assertEquals(List.of(0.0, 1.0 / sourceHeight,
                2.0 / sourceHeight, 3.0 / sourceHeight), progress);
    }

    private static ReviewedTissueSupport fractionalSupport(
            final int width,
            final int height) {
        final ReviewedTissueSupport support = rectangularSupport(
                width, height, 11, 8, 43, 37);
        final ReviewedTissueSupport.Control control = support.controls().get(4);
        return support.moveControl(control.id(), new Point2D(
                control.point().x() + 2.375,
                control.point().y() + 1.625));
    }

    private void assertMembershipMatchesFootprints(final AcceptedAlignmentSnapshot accepted,
            final AtlasCoronalPlane plane, final List<ExportRegionSelection> selections) {
        final var footprints = projector.project(accepted, plane, selections, () -> false, ignored -> { });
        final var union = projector.union(footprints);
        final var sampledUnion = projector.membership(accepted, plane, selections);
        final var members = selections.stream().map(selection -> projector.membership(accepted, plane, List.of(selection))).toList();
        int count = 0;
        for (int y = -1; y <= accepted.previewMapping().sourceHeight(); y++) {
            for (int x = -1; x <= accepted.previewMapping().sourceWidth(); x++) {
                for (int region = 0; region < footprints.size(); region++) {
                    assertEquals(footprints.get(region).containsSourcePixel(x, y), members.get(region).contains(x, y),
                            "Region " + region + " at source pixel " + x + "," + y);
                }
                final boolean included = sampledUnion.contains(x, y);
                assertEquals(union.containsSourcePixel(x, y), included, "Union at source pixel " + x + "," + y);
                if (included) count++;
            }
        }
        assertTrue(count > 0, "The comparison must exercise an actual export footprint");
        assertEquals(union.pixelCount(), count);
    }

    private static ReviewedTissueSupport rectangularSupport(
            final int width,
            final int height,
            final int minimumX,
            final int minimumY,
            final int rectangleWidth,
            final int rectangleHeight) {
        final boolean[] values = new boolean[width * height];
        for (int y = minimumY; y < minimumY + rectangleHeight; y++) {
            for (int x = minimumX; x < minimumX + rectangleWidth; x++) {
                values[y * width + x] = true;
            }
        }
        return ReviewedTissueSupport.fromMask(
                BinaryMask.fromBooleans(width, height, values));
    }

    private static ReviewedTissueSupport multiComponentSupport(
            final int width,
            final int height) {
        final boolean[] values = new boolean[width * height];
        for (int y = 5; y < 29; y++) {
            for (int x = 4; x < 25; x++) {
                values[y * width + x] = true;
            }
        }
        for (int y = 17; y < 42; y++) {
            for (int x = 39; x < 58; x++) {
                values[y * width + x] = true;
            }
        }
        final ReviewedTissueSupport support = ReviewedTissueSupport.fromMask(
                BinaryMask.fromBooleans(width, height, values));
        final ReviewedTissueSupport.Control moved = support.controls().stream()
                .filter(control -> control.componentIndex() == 1)
                .skip(2).findFirst().orElseThrow();
        return support.moveControl(moved.id(), new Point2D(
                moved.point().x() - 1.375,
                moved.point().y() + 0.625));
    }

    private static AcceptedAlignmentSnapshot withMappingAndSupport(
            final AcceptedAlignmentSnapshot original,
            final PreviewMapping mapping,
            final ReviewedTissueSupport support) {
        return new AcceptedAlignmentSnapshot(
                original.coronalLevel(), original.atlasPlaneTilt(),
                original.preOutlineAtlasToPreview(),
                original.joinedManualPlacementApplied(),
                original.outlineWarp(), original.outlineAnchorsConfirmed(),
                original.postOutlinePreviewAdjustment(),
                original.hemisphereWarp(), original.localWarp(),
                original.orientation(), original.observedHemisphere(),
                original.reviewSectionMode(), original.manualSidePlacement(),
                original.activeLandmarks(), original.confidence(),
                original.verifiedSource(), original.verifiedAtlas(),
                original.workflowMode(),
                original.immutableAutomaticProposal(),
                original.warningsAcknowledged(), original.contentRevision(),
                original.acceptanceAuditSequence(), mapping,
                Optional.of(support), true,
                original.halfAtlasCoverage());
    }
}
