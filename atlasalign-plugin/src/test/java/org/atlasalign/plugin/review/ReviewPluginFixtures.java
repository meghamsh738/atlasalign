package org.atlasalign.plugin.review;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.atlasalign.application.AlignmentReviewBasis;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasAssetVerification;
import org.atlasalign.application.AtlasReviewProvenance;
import org.atlasalign.application.BaselineRegistrationProposal;
import org.atlasalign.application.RegistrationObjectiveMode;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.SyntheticPixelReviewPolicy;
import org.atlasalign.application.TissueSegmenter;
import org.atlasalign.application.TissueGeometryResult;
import org.atlasalign.application.TissueSegmentationResult;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.CalibrationMetadata;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.MaskBounds;
import org.atlasalign.core.SimilarityTransform2D;
import org.atlasalign.core.SourceImageMetadata;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.core.StackPlaneLabel;

final class ReviewPluginFixtures {

    private ReviewPluginFixtures() {
    }

    static AlignmentReviewBasis basis() {
        return basis(sourceSnapshot(), SectionGeometry.FULL, 1.0);
    }

    /** Realistic atlas-to-preview scale for tests that use a 456 px atlas. */
    static AlignmentReviewBasis scaledAtlasBasis() {
        return basis(sourceSnapshot(), SectionGeometry.FULL, 0.2);
    }

    static AlignmentReviewBasis segmentedScaledAtlasBasis() {
        return segmentedScaledAtlasBasis(SectionGeometry.FULL);
    }

    static AlignmentReviewBasis segmentedScaledAtlasBasis(
            final SectionGeometry geometry) {
        final AlignmentReviewBasis base = basis(
                sourceSnapshot(), geometry, 0.2);
        final ReviewPreview preview = segmentedPreview();
        return new AlignmentReviewBasis(
                base.proposal(), base.initialPlaneProposal(),
                Optional.of(new TissueSegmenter().segment(
                        preview.width(), preview.height(), preview.pixels())),
                base.sourceSnapshot(), base.atlas(),
                base.syntheticPixelPolicy(),
                base.inferencePreparationProvenance(),
                base.previewDimensions());
    }

    static AlignmentReviewBasis scaledAtlasBasisWithMask(
            final BinaryMask mask) {
        final AlignmentReviewBasis base = scaledAtlasBasis();
        final var template = segmentedScaledAtlasBasis().segmentation()
                .orElseThrow();
        final var segmentation = new TissueSegmentationResult(
                        mask, template.polarity(), template.threshold(),
                        template.foregroundFraction(),
                        template.largestComponentFraction(),
                        template.borderForegroundFraction(),
                        template.method(), template.histogramLowerBound(),
                        template.histogramUpperBound(),
                        template.percentileWindowFallback(),
                        template.candidateScore(), template.candidates(),
                        template.auditNotes());
        return new AlignmentReviewBasis(
                base.proposal(), base.initialPlaneProposal(),
                Optional.of(segmentation), base.sourceSnapshot(),
                base.atlas(), base.syntheticPixelPolicy(),
                base.inferencePreparationProvenance(),
                base.previewDimensions());
    }

    static AlignmentReviewBasis basis(
            final SectionGeometry geometry) {
        return basis(sourceSnapshot(), geometry, 1.0);
    }

    static AlignmentReviewBasis basis(
            final SourceImageSnapshot source) {
        return basis(source, SectionGeometry.FULL, 1.0);
    }

    private static AlignmentReviewBasis basis(
            final SourceImageSnapshot source,
            final SectionGeometry sectionGeometry,
            final double atlasToPreviewScale) {
        final TissueGeometryResult geometry =
                new TissueGeometryResult(
                        sectionGeometry,
                        new MaskBounds(2, 3, 90, 70),
                        1.4,
                        0.8,
                        0.05,
                        0.05,
                        0.9,
                        0.95,
                        1,
                        1,
                        sectionGeometry == SectionGeometry.IMAGE_LEFT_HALF
                                || sectionGeometry
                                == SectionGeometry.IMAGE_RIGHT_HALF
                                ? OptionalDouble.of(50)
                                : OptionalDouble.empty());
        final SimilarityTransform2D similarity =
                new SimilarityTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        atlasToPreviewScale, 0, 0, 0);
        return new AlignmentReviewBasis(
                new BaselineRegistrationProposal(
                        new AllenCoronalLevel(240),
                        geometry,
                        similarity,
                        similarity.asAffine(),
                        RegistrationObjectiveMode
                                .EXACT_FULL_RESOLUTION_COMPLETE_BOUNDARY,
                        0.82,
                        0.88),
                Optional.empty(),
                Optional.empty(),
                source,
                atlasProvenance(),
                SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                Optional.empty(),
                new org.atlasalign.application.ReviewPreviewDimensions(
                        100, 80));
    }

    static AlignmentReviewBasis segmentedScaledAtlasBasisWithShearedProposal() {
        final AlignmentReviewBasis base = segmentedScaledAtlasBasis();
        final BaselineRegistrationProposal proposal = base.proposal();
        // Scaled from a real Fiji registration result. Its atlas axes are
        // well-conditioned but deliberately retain the proposal's affine shear.
        final AffineTransform2D sheared = new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                0.12287656122980052, 0.0799786128097269, 5,
                -0.0679598628097269, 0.19127656122980053, 35);
        final BaselineRegistrationProposal updated =
                new BaselineRegistrationProposal(
                        proposal.coronalLevel(), proposal.geometry(),
                        proposal.similarity(), sheared,
                        proposal.objectiveMode(), proposal.similarityDice(),
                        proposal.affineDice());
        return new AlignmentReviewBasis(
                updated, base.initialPlaneProposal(), base.segmentation(),
                base.sourceSnapshot(), base.atlas(),
                base.syntheticPixelPolicy(),
                base.inferencePreparationProvenance(),
                base.previewDimensions());
    }

    static ReviewPreview preview() {
        final float[] pixels = new float[100 * 80];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = index % 255;
        }
        return new ReviewPreview(100, 80, pixels);
    }

    static ReviewPreview segmentedPreview() {
        final int width = 100;
        final int height = 80;
        final float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final double nx = (x - 49.5) / 43.0;
                final double ny = (y - 39.5) / 31.0;
                pixels[y * width + x] = nx * nx + ny * ny <= 1
                        ? 180 : 10;
            }
        }
        return new ReviewPreview(width, height, pixels);
    }

    static AtlasCoronalPlane plane(final int level) {
        final int width = 456;
        final int height = 320;
        final int[] template = new int[width * height];
        final int[] annotations = new int[width * height];
        for (int y = 40; y < 280; y++) {
            for (int x = 60; x < 396; x++) {
                final int index = y * width + x;
                template[index] = 500 + x + y;
                annotations[index] = x < width / 2 ? 1 : 2;
            }
        }
        return new AtlasCoronalPlane(
                level, width, height, template, annotations);
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

    private static SourceImageSnapshot sourceSnapshot() {
        return new SourceImageSnapshot(
                new SourceImageMetadata(
                        100,
                        80,
                        1,
                        1,
                        1,
                        8,
                        List.of("DAPI"),
                        List.of(StackPlaneLabel.fromNullable("DAPI")),
                        new CalibrationMetadata(
                                1, 1, 1, 0, "pixel", "s")),
                "a".repeat(64));
    }
}
