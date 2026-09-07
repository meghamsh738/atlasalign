package org.atlasalign.plugin.manual;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.atlasalign.application.AlignmentReviewBasis;
import org.atlasalign.application.AlignmentReviewSession;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasAssetVerification;
import org.atlasalign.application.AtlasReviewProvenance;
import org.atlasalign.application.BaselineRegistrationProposal;
import org.atlasalign.application.RegistrationObjectiveMode;
import org.atlasalign.application.ReviewAcceptanceVerification;
import org.atlasalign.application.ReviewPreviewDimensions;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.SyntheticPixelReviewPolicy;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.TissueSegmenter;
import org.atlasalign.application.TissueGeometryResult;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.CalibrationMetadata;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.MaskBounds;
import org.atlasalign.core.SimilarityTransform2D;
import org.atlasalign.core.SourceImageMetadata;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.core.StackPlaneLabel;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.plugin.review.AtlasPlaneSource;
import org.atlasalign.plugin.review.AtlasRegionCatalog;
import org.atlasalign.plugin.review.ReviewController;
import org.atlasalign.plugin.review.ReviewPreview;
import org.atlasalign.plugin.review.ReviewView;
import org.atlasalign.plugin.review.ReviewViewModel;
import org.atlasalign.plugin.review.SelectedAtlasRegion;

final class GuidedManualPluginFixtures {

    private GuidedManualPluginFixtures() {
    }

    static AlignmentReviewBasis basis() {
        return basis(SectionGeometry.FULL);
    }

    static AlignmentReviewBasis basis(final SectionGeometry sectionGeometry) {
        final SourceImageSnapshot source = new SourceImageSnapshot(
                new SourceImageMetadata(
                        100, 80, 1, 1, 1, 8,
                        List.of("DAPI"),
                        List.of(StackPlaneLabel.fromNullable("DAPI")),
                        new CalibrationMetadata(1, 1, 1, 0, "pixel", "s")),
                "a".repeat(64));
        final TissueGeometryResult geometry = new TissueGeometryResult(
                sectionGeometry,
                new MaskBounds(2, 3, 90, 70),
                1.4, 0.8, 0.05, 0.05, 0.9, 0.95, 1, 1,
                OptionalDouble.empty());
        final SimilarityTransform2D similarity = new SimilarityTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0, 0);
        final float[] previewPixels = new float[100 * 80];
        for (int y = 10; y < 70; y++) {
            for (int x = 10; x < 90; x++) {
                previewPixels[y * 100 + x] = 100f;
            }
        }
        final var segmentation = new TissueSegmenter().segment(
                100, 80, previewPixels);
        return new AlignmentReviewBasis(
                new BaselineRegistrationProposal(
                        new AllenCoronalLevel(264), geometry, similarity,
                        similarity.asAffine(),
                        RegistrationObjectiveMode
                                .EXACT_FULL_RESOLUTION_COMPLETE_BOUNDARY,
                        0.82, 0.88),
                Optional.empty(), Optional.of(segmentation), source,
                new AtlasReviewProvenance(
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
                                        "ontology", 30, "3".repeat(64)))),
                SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                Optional.empty(), ReviewPreviewDimensions.capture(
                        100, 80, previewPixels));
    }

    static ReviewPreview preview() {
        final float[] pixels = new float[100 * 80];
        for (int y = 10; y < 70; y++) {
            for (int x = 10; x < 90; x++) {
                pixels[y * 100 + x] = 100f;
            }
        }
        return new ReviewPreview(100, 80, pixels);
    }

    static AtlasCoronalPlane plane(final int level) {
        final int width = 100;
        final int height = 80;
        final int[] template = new int[width * height];
        final int[] annotation = new int[width * height];
        for (int y = 10; y < 70; y++) {
            for (int x = 10; x < 90; x++) {
                final int offset = y * width + x;
                template[offset] = x + y;
                annotation[offset] = x < 50 ? 10 : 11;
            }
        }
        return new AtlasCoronalPlane(
                level, width, height, template, annotation);
    }

    static Fixture fixture() {
        return fixture(preview());
    }

    static Fixture fixture(final SectionGeometry sectionGeometry) {
        return fixture(preview(), sectionGeometry);
    }

    static Fixture fixture(final ReviewPreview reviewPreview) {
        return fixture(reviewPreview, SectionGeometry.FULL);
    }

    private static Fixture fixture(
            final ReviewPreview reviewPreview,
            final SectionGeometry sectionGeometry) {
        final AlignmentReviewBasis basis = basis(sectionGeometry);
        final FakeAtlas atlas = new FakeAtlas();
        final AlignmentReviewSession session =
                new AlignmentReviewSession(basis);
        final ReviewController controller = new ReviewController(
                session, reviewPreview, atlas,
                () -> new ReviewAcceptanceVerification(
                        basis.sourceSnapshot(), basis.atlas()),
                Runnable::run, Runnable::run);
        final CapturingView view = new CapturingView();
        controller.attach(view);
        return new Fixture(controller, view.last(), atlas, session);
    }

    record Fixture(
            ReviewController controller,
            ReviewViewModel model,
            FakeAtlas atlas,
            AlignmentReviewSession session) {
    }

    static final class FakeAtlas implements AtlasPlaneSource, AtlasRegionCatalog {
        @Override
        public AtlasCoronalPlane load(final int level) {
            return plane(level);
        }

        @Override
        public Optional<SelectedAtlasRegion> resolveExactAcronym(
                final String acronym) {
            return switch (acronym) {
                case "DG" -> Optional.of(region(10, "DG", "Dentate gyrus"));
                case "DG-sg" -> Optional.of(region(
                        11, "DG-sg", "Dentate gyrus granule layer"));
                case "HPF" -> Optional.of(region(
                        12, "HPF", "Hippocampal formation"));
                case "cc" -> Optional.of(region(13, "cc", "Corpus callosum"));
                case "VS" -> Optional.of(region(
                        14, "VS", "Ventricular system"));
                case "root" -> Optional.of(new SelectedAtlasRegion(
                        15, "root", "Brain", Set.of(10, 11, 15)));
                default -> Optional.empty();
            };
        }

        private static SelectedAtlasRegion region(
                final int id,
                final String acronym,
                final String name) {
            return new SelectedAtlasRegion(id, acronym, name, Set.of(id));
        }
    }

    private static final class CapturingView implements ReviewView {
        private ReviewViewModel model;

        @Override
        public void render(final ReviewViewModel value) {
            model = value;
        }

        @Override
        public void showError(final String title, final String message) {
            throw new AssertionError(title + ": " + message);
        }

        @Override
        public void reviewClosed() {
        }

        private ReviewViewModel last() {
            if (model == null) {
                throw new AssertionError("Controller did not publish a model");
            }
            return model;
        }
    }
}
