package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.core.CalibrationMetadata;
import org.atlasalign.core.SourceImageMetadata;
import org.atlasalign.core.StackPlaneLabel;
import org.junit.jupiter.api.Test;

class ManualContourContractTest {

    @Test
    void acceptsDraftFromFirstClickButOnlyCompleteContourCanFeedPreview() {
        final ManualContour draft = contour(
                "draft", ManualContourKind.ANATOMICAL_STRUCTURE,
                ContourTopology.OPEN, ContourCaptureStatus.DRAFT,
                List.of(vertex("a", 5, 6)), Set.of());
        final ManualContour complete = contour(
                "complete", ManualContourKind.ANATOMICAL_STRUCTURE,
                ContourTopology.OPEN, ContourCaptureStatus.COMPLETE,
                List.of(vertex("a", 5, 6), vertex("b", 8, 9)), Set.of());

        assertFalse(draft.eligibleForPreview());
        assertTrue(complete.eligibleForPreview());
        assertThrows(IllegalArgumentException.class, () -> contour(
                "short", ManualContourKind.ANATOMICAL_STRUCTURE,
                ContourTopology.OPEN, ContourCaptureStatus.COMPLETE,
                List.of(vertex("a", 5, 6)), Set.of()));
        final ManualContour closedDraft = contour(
                "closed-draft", ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.DRAFT,
                List.of(vertex("a", 5, 6)), Set.of());
        assertFalse(closedDraft.eligibleForPreview());
    }

    @Test
    void validatesPixelCentreBoundsAndStableUniqueVertexIds() {
        assertThrows(IllegalArgumentException.class, () -> contour(
                "outside", ManualContourKind.VISIBLE_TISSUE_BOUNDARY,
                ContourTopology.OPEN, ContourCaptureStatus.COMPLETE,
                List.of(vertex("a", 0, 0), vertex("b", 100, 2)), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> contour(
                "duplicate", ManualContourKind.VISIBLE_TISSUE_BOUNDARY,
                ContourTopology.OPEN, ContourCaptureStatus.COMPLETE,
                List.of(vertex("a", 0, 0), vertex("a", 4, 2)), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new SourcePixelPoint(Double.NaN, 1));
    }

    @Test
    void preservesOnlyExplicitAdjacentGapSegments() {
        final ManualContour contour = contour(
                "outline", ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.COMPLETE,
                List.of(vertex("a", 1, 1), vertex("b", 20, 1),
                        vertex("c", 10, 20)),
                Set.of(new ContourSegment("c", "a")));

        assertEquals(Set.of(new ContourSegment("c", "a")),
                contour.excludedGapSegments());
        assertThrows(IllegalArgumentException.class, () -> contour(
                "bad-gap", ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.COMPLETE,
                List.of(vertex("a", 1, 1), vertex("b", 20, 1),
                        vertex("c", 10, 20)),
                Set.of(new ContourSegment("a", "c"))));
    }

    @Test
    void requiresExactGuideOnlyForAnatomicalStructures() {
        assertThrows(IllegalArgumentException.class, () -> new ManualContour(
                "wrong", ManualContourKind.MIDLINE, ContourTopology.OPEN,
                ContourCaptureStatus.COMPLETE, AnatomicalSide.BILATERAL,
                ContourCompleteness.COMPLETE, Optional.of(guide()), source(),
                List.of(vertex("a", 1, 1), vertex("b", 1, 20)), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ManualContour(
                "missing", ManualContourKind.ANATOMICAL_STRUCTURE,
                ContourTopology.OPEN, ContourCaptureStatus.COMPLETE,
                AnatomicalSide.LEFT, ContourCompleteness.PARTIAL,
                Optional.empty(), source(),
                List.of(vertex("a", 1, 1), vertex("b", 1, 20)), Set.of()));
    }

    @Test
    void sectionObservationRequiresExplicitOrientationLateralityAndReflection() {
        assertThrows(IllegalArgumentException.class, () -> new SectionObservation(
                SectionGeometry.IMAGE_LEFT_HALF,
                AtlasOrientation.UNCONFIRMED_PROVISIONAL_DIRECT,
                ObservedAnatomicalHemisphere.LEFT, true, true));
        assertThrows(IllegalArgumentException.class, () -> new SectionObservation(
                SectionGeometry.IMAGE_LEFT_HALF,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.UNSURE, true, true));
        final SectionObservation observation = new SectionObservation(
                SectionGeometry.IMAGE_LEFT_HALF,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT,
                ObservedAnatomicalHemisphere.RIGHT, true, true);
        assertTrue(observation.orientation().reflected());
    }

    @Test
    void sourceIdentityIncludesExactStackChannelBitDepthLabelsAndCalibration() {
        final SourceImageIdentity baseline = source();
        final SourceImageIdentity changedLabel = new SourceImageIdentity(
                baseline.pixelSha256(), new SourceImageMetadata(
                        100, 80, 1, 1, 1, 8, List.of("myelin"),
                        List.of(StackPlaneLabel.fromNullable("myelin")),
                        new CalibrationMetadata(1, 1, 1, 0, "pixel", "s")),
                baseline.previewMappingIdentity());
        final SourceImageIdentity changedCalibration = new SourceImageIdentity(
                baseline.pixelSha256(), new SourceImageMetadata(
                        100, 80, 1, 1, 1, 8, List.of("DAPI"),
                        List.of(StackPlaneLabel.fromNullable("DAPI")),
                        new CalibrationMetadata(2, 1, 1, 0, "µm", "s")),
                baseline.previewMappingIdentity());
        final SourceImageIdentity changedBitDepth = new SourceImageIdentity(
                baseline.pixelSha256(), new SourceImageMetadata(
                        100, 80, 1, 1, 1, 16, List.of("DAPI"),
                        List.of(StackPlaneLabel.fromNullable("DAPI")),
                new CalibrationMetadata(1, 1, 1, 0, "pixel", "s")),
                baseline.previewMappingIdentity());
        final SourceImageIdentity changedStackAndChannels =
                new SourceImageIdentity(
                        baseline.pixelSha256(), new SourceImageMetadata(
                                100, 80, 2, 2, 1, 8,
                                List.of("DAPI", "myelin"),
                                List.of(
                                        StackPlaneLabel.fromNullable("c1-z1"),
                                        StackPlaneLabel.fromNullable("c2-z1"),
                                        StackPlaneLabel.fromNullable("c1-z2"),
                                        StackPlaneLabel.fromNullable("c2-z2")),
                                new CalibrationMetadata(
                                        1, 1, 1, 0, "pixel", "s")),
                        baseline.previewMappingIdentity());

        assertFalse(baseline.equals(changedLabel));
        assertFalse(baseline.equals(changedCalibration));
        assertFalse(baseline.equals(changedBitDepth));
        assertFalse(baseline.equals(changedStackAndChannels));
        assertEquals(100, baseline.width());
        assertEquals(80, baseline.height());
    }

    static ManualContour contour(
            final String id,
            final ManualContourKind kind,
            final ContourTopology topology,
            final ContourCaptureStatus status,
            final List<ContourVertex> vertices,
            final Set<ContourSegment> gaps) {
        return new ManualContour(
                id, kind, topology, status,
                kind == ManualContourKind.ANATOMICAL_STRUCTURE
                        ? AnatomicalSide.LEFT : AnatomicalSide.BILATERAL,
                ContourCompleteness.COMPLETE,
                kind == ManualContourKind.ANATOMICAL_STRUCTURE
                        ? Optional.of(guide()) : Optional.empty(),
                source(), vertices, gaps);
    }

    static ContourVertex vertex(
            final String id, final double x, final double y) {
        return new ContourVertex(id, new SourcePixelPoint(x, y));
    }

    static SourceImageIdentity source() {
        return new SourceImageIdentity("1".repeat(64), sourceMetadata(),
                "SOURCE_TO_PREVIEW_PIXEL_CENTRE_V1");
    }

    static SourceImageMetadata sourceMetadata() {
        return new SourceImageMetadata(
                100, 80, 1, 1, 1, 8,
                List.of("DAPI"),
                List.of(StackPlaneLabel.fromNullable("DAPI")),
                new CalibrationMetadata(1, 1, 1, 0, "pixel", "s"));
    }

    static VerifiedAtlasGuideIdentity guide() {
        return new VerifiedAtlasGuideIdentity(
                "2".repeat(64), "Allen CCF", "2020", 632, "DG-sg");
    }
}
