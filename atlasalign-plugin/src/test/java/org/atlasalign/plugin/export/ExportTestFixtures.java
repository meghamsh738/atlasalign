package org.atlasalign.plugin.export;

import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import org.atlasalign.application.AcceptedAlignmentSnapshot;
import org.atlasalign.application.AlignmentReviewContent;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasAssetVerification;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.AtlasReviewProvenance;
import org.atlasalign.application.ConfidenceEvidence;
import org.atlasalign.application.ConfidenceEvidenceCategory;
import org.atlasalign.application.ConfidenceMetric;
import org.atlasalign.application.HalfAtlasCoverage;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewConfidenceReport;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.application.ReviewWorkflowMode;
import org.atlasalign.application.ReviewedTissueSupport;
import org.atlasalign.application.export.SourcePixelReader;
import org.atlasalign.application.manual.ManualSidePlacement2D;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualWarpControl;
import org.atlasalign.application.manual.ManualWarpControlOrigin;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.CalibrationMetadata;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.SourceImageMetadata;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.core.StackPlaneLabel;

final class ExportTestFixtures {

    static final int SOURCE_WIDTH = 8;
    static final int SOURCE_HEIGHT = 6;
    static final int LEVEL = 240;

    private ExportTestFixtures() {
    }

    static SourceImageSnapshot sourceSnapshot(
            final int bitDepth,
            final int channels,
            final int slices,
            final int frames) {
        return sourceSnapshot(bitDepth, channels, slices, frames,
                new CalibrationMetadata(
                        0.65, 0.65, 2.0, 3.0, "µm", "s"));
    }

    static SourceImageSnapshot sourceSnapshot(
            final int width,
            final int height,
            final int bitDepth,
            final int channels,
            final int slices,
            final int frames) {
        return sourceSnapshot(width, height, bitDepth, channels, slices,
                frames, new CalibrationMetadata(
                        0.65, 0.65, 2.0, 3.0, "µm", "s"));
    }

    static SourceImageSnapshot sourceSnapshot(
            final int bitDepth,
            final int channels,
            final int slices,
            final int frames,
            final CalibrationMetadata calibration) {
        return sourceSnapshot(SOURCE_WIDTH, SOURCE_HEIGHT, bitDepth,
                channels, slices, frames, calibration);
    }

    private static SourceImageSnapshot sourceSnapshot(
            final int width,
            final int height,
            final int bitDepth,
            final int channels,
            final int slices,
            final int frames,
            final CalibrationMetadata calibration) {
        final int planes = channels * slices * frames;
        return new SourceImageSnapshot(
                new SourceImageMetadata(
                        width, height, channels, slices, frames,
                        bitDepth,
                        java.util.stream.IntStream.range(0, channels)
                                .mapToObj(index -> "Channel " + (index + 1))
                                .toList(),
                        java.util.stream.IntStream.range(0, planes)
                                .mapToObj(index -> StackPlaneLabel
                                        .fromNullable("plane-" + index))
                                .toList(),
                        calibration),
                "a".repeat(64));
    }

    static AcceptedAlignmentSnapshot accepted(
            final SourceImageSnapshot source,
            final Optional<ReviewedTissueSupport> support,
            final boolean clipping) {
        final AffineTransform2D identityAtlasToPreview =
                new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1, 0, 0, 0, 1, 0);
        return new AcceptedAlignmentSnapshot(
                new AllenCoronalLevel(LEVEL), AtlasPlaneTilt.CORONAL,
                identityAtlasToPreview, Optional.empty(), false,
                AlignmentReviewContent.identityPreviewAdjustment(),
                Optional.empty(), Optional.empty(),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.BOTH,
                ReviewSectionMode.FULL,
                ManualSidePlacement2D.identity(), List.of(),
                new ReviewConfidenceReport(
                        ConfidenceEvidenceCategory.MANUAL_ONLY,
                        List.of(ConfidenceEvidence.unavailable(
                                ConfidenceMetric.SOURCE_IDENTITY,
                                "Verified source identity")),
                        List.of("Reviewer-controlled alignment")),
                source, atlasProvenance(), ReviewWorkflowMode.MANUAL_ONLY,
                Optional.empty(), true, 7, 11,
                new PreviewMapping(
                        source.metadata().width(), source.metadata().height(),
                        source.metadata().width(), source.metadata().height()),
                support, clipping);
    }

    static AcceptedAlignmentSnapshot acceptedDisjoinedOverlap(
            final SourceImageSnapshot source) {
        final AffineTransform2D identityAtlasToPreview =
                new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1, 0, 0, 0, 1, 0);
        final double firstRightAtlasX =
                Math.ceil((AtlasReviewProvenance.ALLEN_CORONAL_WIDTH
                        - 1.0) * 0.5);
        final ManualSidePlacement2D overlap = new ManualSidePlacement2D(
                previewIdentity(),
                new AffineTransform2D(
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1, 0, -firstRightAtlasX, 0, 1, 0));
        return new AcceptedAlignmentSnapshot(
                new AllenCoronalLevel(LEVEL), AtlasPlaneTilt.CORONAL,
                identityAtlasToPreview, Optional.empty(), false,
                AlignmentReviewContent.identityPreviewAdjustment(),
                Optional.empty(), Optional.empty(),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.BOTH,
                ReviewSectionMode.DISJOINED, overlap, List.of(),
                new ReviewConfidenceReport(
                        ConfidenceEvidenceCategory.MANUAL_ONLY,
                        List.of(ConfidenceEvidence.unavailable(
                                ConfidenceMetric.SOURCE_IDENTITY,
                                "Verified source identity")),
                        List.of("Reviewer-controlled alignment")),
                source, atlasProvenance(), ReviewWorkflowMode.MANUAL_ONLY,
                Optional.empty(), true, 8, 12,
                new PreviewMapping(SOURCE_WIDTH, SOURCE_HEIGHT,
                        SOURCE_WIDTH, SOURCE_HEIGHT),
                Optional.empty(), false);
    }

    static AcceptedAlignmentSnapshot acceptedHalfWithJoinedPlacement(
            final SourceImageSnapshot source) {
        return acceptedHalfWithJoinedPlacement(
                source, HalfAtlasCoverage.VISIBLE_SIDE_ONLY, 2);
    }

    static AcceptedAlignmentSnapshot acceptedHalfWithJoinedPlacement(
            final SourceImageSnapshot source,
            final HalfAtlasCoverage coverage,
            final double translationX) {
        final AffineTransform2D placedAtlasToPreview =
                new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1, 0, translationX, 0, 1, 0);
        return new AcceptedAlignmentSnapshot(
                new AllenCoronalLevel(LEVEL), AtlasPlaneTilt.CORONAL,
                placedAtlasToPreview, true, Optional.empty(), false,
                AlignmentReviewContent.identityPreviewAdjustment(),
                Optional.empty(), Optional.empty(),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.LEFT,
                ReviewSectionMode.HALF,
                ManualSidePlacement2D.identity(), List.of(),
                new ReviewConfidenceReport(
                        ConfidenceEvidenceCategory.MANUAL_ONLY,
                        List.of(ConfidenceEvidence.unavailable(
                                ConfidenceMetric.SOURCE_IDENTITY,
                                "Verified source identity")),
                        List.of("Reviewer-controlled alignment")),
                source, atlasProvenance(), ReviewWorkflowMode.MANUAL_ONLY,
                Optional.empty(), true, 10, 14,
                new PreviewMapping(SOURCE_WIDTH, SOURCE_HEIGHT,
                        SOURCE_WIDTH, SOURCE_HEIGHT),
                Optional.empty(), false, coverage);
    }

    static AcceptedAlignmentSnapshot acceptedWithNonIdentityWarp(
            final SourceImageSnapshot source) {
        final int previewWidth = AtlasReviewProvenance.ALLEN_CORONAL_WIDTH;
        final int previewHeight = AtlasReviewProvenance.ALLEN_CORONAL_HEIGHT;
        final List<ManualWarpControl> controls = List.of(
                control("l1", ManualHemisphereWarp2D.AtlasSide.LEFT,
                        70, 70, 74, 72),
                control("l2", ManualHemisphereWarp2D.AtlasSide.LEFT,
                        165, 70, 165, 70),
                control("l3", ManualHemisphereWarp2D.AtlasSide.LEFT,
                        70, 245, 70, 245),
                control("l4", ManualHemisphereWarp2D.AtlasSide.LEFT,
                        165, 245, 165, 245),
                control("r1", ManualHemisphereWarp2D.AtlasSide.RIGHT,
                        290, 70, 286, 72),
                control("r2", ManualHemisphereWarp2D.AtlasSide.RIGHT,
                        385, 70, 385, 70),
                control("r3", ManualHemisphereWarp2D.AtlasSide.RIGHT,
                        290, 245, 290, 245),
                control("r4", ManualHemisphereWarp2D.AtlasSide.RIGHT,
                        385, 245, 385, 245));
        final ManualHemisphereWarp2D warp = ManualHemisphereWarp2D.fit(
                controls,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ReviewSectionMode.FULL, previewWidth, previewHeight);
        final AffineTransform2D atlasToPreview = new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0, 0, 1, 0);
        return new AcceptedAlignmentSnapshot(
                new AllenCoronalLevel(LEVEL), AtlasPlaneTilt.CORONAL,
                atlasToPreview, Optional.empty(), false,
                AlignmentReviewContent.identityPreviewAdjustment(),
                Optional.of(warp), Optional.empty(),
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.BOTH,
                ReviewSectionMode.FULL, ManualSidePlacement2D.identity(),
                List.of(),
                new ReviewConfidenceReport(
                        ConfidenceEvidenceCategory.MANUAL_ONLY,
                        List.of(ConfidenceEvidence.unavailable(
                                ConfidenceMetric.SOURCE_IDENTITY,
                                "Verified source identity")),
                        List.of("Reviewer-controlled alignment")),
                source, atlasProvenance(), ReviewWorkflowMode.MANUAL_ONLY,
                Optional.empty(), true, 9, 13,
                new PreviewMapping(
                        source.metadata().width(), source.metadata().height(),
                        previewWidth, previewHeight),
                Optional.empty(), false);
    }

    private static ManualWarpControl control(
            final String id,
            final ManualHemisphereWarp2D.AtlasSide side,
            final double sourceX,
            final double sourceY,
            final double targetX,
            final double targetY) {
        return new ManualWarpControl(id, side,
                ManualWarpControlOrigin.REGULAR_INTERIOR_GRID,
                "grid", new Point2D(sourceX, sourceY),
                new Point2D(targetX, targetY));
    }

    static AtlasReviewProvenance atlasProvenance() {
        return new AtlasReviewProvenance(
                AllenCoronalLevel.ATLAS_ID,
                AllenCoronalLevel.ATLAS_VERSION,
                AtlasReviewProvenance.ALLEN_CORONAL_WIDTH,
                AtlasReviewProvenance.ALLEN_CORONAL_HEIGHT,
                List.of(
                        new AtlasAssetVerification(
                                "template", 10, "1".repeat(64)),
                        new AtlasAssetVerification(
                                "annotation", 20, "2".repeat(64)),
                        new AtlasAssetVerification(
                                "ontology", 30, "3".repeat(64))));
    }

    static AtlasCoronalPlane annotationPlane() {
        final int width = AtlasReviewProvenance.ALLEN_CORONAL_WIDTH;
        final int height = AtlasReviewProvenance.ALLEN_CORONAL_HEIGHT;
        final int[] labels = new int[width * height];
        for (int y = 0; y < SOURCE_HEIGHT; y++) {
            for (int x = 0; x < SOURCE_WIDTH; x++) {
                labels[y * width + x] = x < 4 ? 1 : 2;
            }
        }
        return AtlasCoronalPlane.annotationOnly(
                LEVEL, width, height, labels,
                org.atlasalign.atlas.AtlasPlaneGeometry.axisAligned(
                        LEVEL, width, height, height, width));
    }

    static AtlasCoronalPlane overlappingDisjoinedAnnotationPlane() {
        final int width = AtlasReviewProvenance.ALLEN_CORONAL_WIDTH;
        final int height = AtlasReviewProvenance.ALLEN_CORONAL_HEIGHT;
        final int[] labels = new int[width * height];
        final int rightStart = (int) Math.ceil((width - 1.0) * 0.5);
        for (int y = 0; y < SOURCE_HEIGHT; y++) {
            for (int x = 0; x < SOURCE_WIDTH; x++) {
                labels[y * width + x] = 1;
                labels[y * width + rightStart + x] = 2;
            }
        }
        return AtlasCoronalPlane.annotationOnly(
                LEVEL, width, height, labels,
                org.atlasalign.atlas.AtlasPlaneGeometry.axisAligned(
                        LEVEL, width, height, height, width));
    }

    static AtlasCoronalPlane filledAnnotationPlane(final int label) {
        final int width = AtlasReviewProvenance.ALLEN_CORONAL_WIDTH;
        final int height = AtlasReviewProvenance.ALLEN_CORONAL_HEIGHT;
        final int[] labels = new int[width * height];
        java.util.Arrays.fill(labels, label);
        return AtlasCoronalPlane.annotationOnly(
                LEVEL, width, height, labels,
                org.atlasalign.atlas.AtlasPlaneGeometry.axisAligned(
                        LEVEL, width, height, height, width));
    }

    private static AffineTransform2D previewIdentity() {
        return new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0, 0, 1, 0);
    }

    static ReviewedTissueSupport leftSupport() {
        final boolean[] values = new boolean[SOURCE_WIDTH * SOURCE_HEIGHT];
        for (int y = 0; y < SOURCE_HEIGHT; y++) {
            for (int x = 0; x < 4; x++) {
                values[y * SOURCE_WIDTH + x] = true;
            }
        }
        return ReviewedTissueSupport.fromMask(BinaryMask.fromBooleans(
                SOURCE_WIDTH, SOURCE_HEIGHT, values));
    }

    static ExportRegionSelection region(
            final int id,
            final String acronym) {
        return new ExportRegionSelection(
                id, acronym, acronym + " name", true,
                java.util.Set.of(id));
    }

    static SourceRegionFootprint fullFootprint(
            final ExportRegionSelection selection) {
        final BitSet mask = new BitSet(SOURCE_WIDTH * SOURCE_HEIGHT);
        mask.set(0, SOURCE_WIDTH * SOURCE_HEIGHT);
        return new SourceRegionFootprint(List.of(selection),
                SOURCE_WIDTH, SOURCE_HEIGHT,
                new SourcePixelReader.Bounds(
                        0, 0, SOURCE_WIDTH, SOURCE_HEIGHT), mask);
    }

    static SourceRegionFootprint irregularFootprint(
            final ExportRegionSelection selection) {
        final int width = 4;
        final int height = 3;
        final BitSet mask = new BitSet(width * height);
        for (final int bit : new int[]{0, 3, 5, 6, 8, 11}) {
            mask.set(bit);
        }
        return new SourceRegionFootprint(List.of(selection),
                SOURCE_WIDTH, SOURCE_HEIGHT,
                new SourcePixelReader.Bounds(2, 1, width, height), mask);
    }

    static final class MemoryReader implements SourcePixelReader {
        private final SourceImageSnapshot snapshot;

        MemoryReader(final SourceImageSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public SourceImageSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public PixelBlock readPlane(
                final int channel,
                final int slice,
                final int frame,
                final Bounds bounds) {
            final int plane = (frame - 1) * snapshot.metadata().slices()
                    * snapshot.metadata().channels()
                    + (slice - 1) * snapshot.metadata().channels()
                    + channel - 1;
            if (snapshot.metadata().bitDepth() == 8) {
                final byte[] pixels = new byte[bounds.pixelCount()];
                fill(bounds, (sourceIndex, cropIndex) -> pixels[cropIndex]
                        = (byte) (plane * 17 + sourceIndex));
                return new ByteBlock(bounds.width(), bounds.height(), pixels);
            }
            if (snapshot.metadata().bitDepth() == 16) {
                final short[] pixels = new short[bounds.pixelCount()];
                fill(bounds, (sourceIndex, cropIndex) -> pixels[cropIndex]
                        = (short) (40_000 + plane * 97 + sourceIndex));
                return new UnsignedShortBlock(
                        bounds.width(), bounds.height(), pixels);
            }
            final float[] pixels = new float[bounds.pixelCount()];
            fill(bounds, (sourceIndex, cropIndex) -> pixels[cropIndex]
                    = sourceIndex == 0 && plane == 0
                    ? Float.intBitsToFloat(0x7fc01234)
                    : sourceIndex == 1 && plane == 0
                    ? Float.intBitsToFloat(0x80000000)
                    : Float.intBitsToFloat(0x80000000
                            | (plane * 131 + sourceIndex + 1)));
            return new FloatBlock(bounds.width(), bounds.height(), pixels);
        }

        private void fill(
                final Bounds bounds,
                final java.util.function.BiConsumer<Integer, Integer>
                        setter) {
            int cropIndex = 0;
            for (int y = 0; y < bounds.height(); y++) {
                for (int x = 0; x < bounds.width(); x++) {
                    setter.accept((bounds.minimumY() + y)
                            * snapshot.metadata().width()
                            + bounds.minimumX() + x, cropIndex++);
                }
            }
        }
    }
}
