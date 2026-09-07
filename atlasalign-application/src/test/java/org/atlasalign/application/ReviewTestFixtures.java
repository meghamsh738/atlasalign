package org.atlasalign.application;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.nio.file.Path;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CalibrationMetadata;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.MaskBounds;
import org.atlasalign.core.SimilarityTransform2D;
import org.atlasalign.core.SourceImageMetadata;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.core.StackPlaneLabel;

final class ReviewTestFixtures {

    private ReviewTestFixtures() {
    }

    static AlignmentReviewBasis basis(
            final SectionGeometry geometry) {
        return basis(
                geometry,
                0.88,
                SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS);
    }

    static AlignmentReviewBasis automaticBasis(
            final SectionGeometry geometry,
            final DeepSliceOuv primary,
            final DeepSliceOuv secondary) {
        final AlignmentReviewBasis original = basis(geometry);
        final DeepSliceOuv ensemble = average(primary, secondary);
        final DeepSlicePredictionDiagnostics diagnostics =
                new DeepSlicePredictionDiagnostics(primary, secondary, ensemble);
        final DeepSlicePlanePrediction prediction =
                DeepSlicePlanePrediction.fromWorkerVectors(
                        primary, secondary, ensemble, runtimeProvenance());
        final InitialPlaneProposal planeProposal = new InitialPlaneProposal(
                original.proposal().coronalLevel(),
                InitialPlaneSource.LOCAL_DEEPSLICE,
                Optional.of(prediction), Optional.empty(), Optional.empty());
        final float[] previewPixels = new float[8_000];
        final DeepSliceInputProvenance inputProvenance =
                DeepSliceInputProvenance.capture(
                        new DeepSliceInput(
                                100, 80, previewPixels,
                                org.atlasalign.core.BinaryMask.empty(100, 80)),
                        DeepSliceInputCondition.ORIGINAL_PREVIEW,
                        OptionalDouble.empty(), Optional.empty());
        final AutomaticAlignmentEligibility eligibility =
                AutomaticAlignmentEligibility.evaluate(
                        planeProposal,
                        geometry,
                        original.syntheticPixelPolicy(),
                        Optional.of(inputProvenance),
                        ReviewPreviewDimensions.capture(
                                100, 80, previewPixels));
        return new AlignmentReviewBasis(
                original.proposal(),
                Optional.of(planeProposal),
                original.segmentation(), original.sourceSnapshot(), original.atlas(),
                original.syntheticPixelPolicy(),
                original.inferencePreparationProvenance(),
                ReviewPreviewDimensions.capture(
                        100, 80, previewPixels),
                Optional.of(inputProvenance),
                AutomaticPlaneInitialization.from(
                        planeProposal, eligibility));
    }

    static DeepSliceOuv coronalOuv(final double centreDepth) {
        return new DeepSliceOuv(0, centreDepth, 0, 3, 0, 0, 0, 0, -3);
    }

    private static DeepSliceOuv average(
            final DeepSliceOuv first, final DeepSliceOuv second) {
        final double[] values = new double[DeepSliceOuv.COMPONENT_COUNT];
        for (int index = 0; index < values.length; index++) {
            values[index] = (first.component(index) + second.component(index)) / 2;
        }
        return new DeepSliceOuv(values);
    }

    private static DeepSliceRuntimeProvenance runtimeProvenance() {
        return new DeepSliceRuntimeProvenance(
                "deepslice-test-r3", Path.of("/verified/runtime").toAbsolutePath().normalize(), 2, 1,
                "a".repeat(64), "3.11.15", "1.2.8", "2.21.0",
                "test-model-release", "b".repeat(64), "c".repeat(64),
                "d".repeat(64));
    }

    static AlignmentReviewBasis basis(
            final SectionGeometry geometry,
            final double affineDice,
            final SyntheticPixelReviewPolicy syntheticPolicy) {
        final TissueGeometryResult geometryResult =
                new TissueGeometryResult(
                        geometry,
                        new MaskBounds(2, 3, 90, 70),
                        1.3,
                        0.8,
                        0.05,
                        0.05,
                        0.9,
                        0.95,
                        1,
                        1,
                        geometry == SectionGeometry.FULL
                                || geometry
                                == SectionGeometry
                                        .BILATERAL_REVIEW_REQUIRED
                                ? OptionalDouble.empty()
                                : OptionalDouble.of(45));
        final SimilarityTransform2D similarity =
                new SimilarityTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.PREVIEW_PIXEL,
                        1, 0, 0, 0);
        final BaselineRegistrationProposal proposal =
                new BaselineRegistrationProposal(
                        new AllenCoronalLevel(240),
                        geometryResult,
                        similarity,
                        similarity.asAffine(),
                        RegistrationObjectiveMode
                                .EXACT_FULL_RESOLUTION_COMPLETE_BOUNDARY,
                        0.82,
                        affineDice);
        return new AlignmentReviewBasis(
                proposal,
                Optional.empty(),
                Optional.empty(),
                source("a"),
                atlas(),
                syntheticPolicy,
                Optional.empty(),
                new ReviewPreviewDimensions(100, 80));
    }

    static SourceImageSnapshot source(final String hashCharacter) {
        return new SourceImageSnapshot(
                new SourceImageMetadata(
                        100,
                        80,
                        1,
                        1,
                        1,
                        16,
                        List.of("DAPI"),
                        List.of(StackPlaneLabel.fromNullable("DAPI")),
                        new CalibrationMetadata(
                                0.5, 0.5, 1, 0, "um", "s")),
                hashCharacter.repeat(64));
    }

    static AtlasReviewProvenance atlas() {
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

    static ReviewAcceptanceVerifier verifier(
            final AlignmentReviewBasis basis) {
        return () -> new ReviewAcceptanceVerification(
                basis.sourceSnapshot(),
                basis.atlas());
    }

    static AffineTransform2D identityAtlasToPreview() {
        return new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0,
                0, 1, 0);
    }
}
