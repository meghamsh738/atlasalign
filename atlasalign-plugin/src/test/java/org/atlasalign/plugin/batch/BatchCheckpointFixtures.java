package org.atlasalign.plugin.batch;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.atlasalign.application.*;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.roi.*;
import org.atlasalign.core.*;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.plugin.project.*;

final class BatchCheckpointFixtures {
    private BatchCheckpointFixtures() { }

    static ImagePlus image(final boolean multidimensional) {
        final var stack = new ImageStack(20, 18);
        for (int plane = 0; plane < (multidimensional ? 4 : 1); plane++) {
            final var pixels = new ByteProcessor(20, 18);
            for (int index = 0; index < pixels.getPixelCount(); index++) pixels.set(index, (plane * 30 + index) % 250);
            stack.addSlice("channel-plane-" + plane, pixels);
        }
        final var image = new ImagePlus("source.tif", stack);
        image.setDimensions(multidimensional ? 2 : 1, multidimensional ? 2 : 1, 1);
        return image;
    }

    static ReviewerRoiSession rois(final BatchSection section, final String name) {
        final var rois = new ReviewerRoiSession(section.id(), section.width(), section.height());
        rois.newPolygon(name, ReviewerRoiSide.LEFT, RoiPartOperation.ADD);
        rois.addVertices(List.of(new Point2D(1, 1), new Point2D(8, 1), new Point2D(8, 8), new Point2D(1, 8)));
        rois.finishActivePart();
        return rois;
    }

    static ReviewProject review(final BatchReviewItem item, final ReviewerRoiSession.Snapshot rois) {
        final ImagePlus crop = new SectionImageExtractor().extract(item);
        final var source = new ImagePlusSourceImage(crop).snapshot();
        final var geometry = new TissueGeometryResult(SectionGeometry.FULL,
                new MaskBounds(0, 0, crop.getWidth() - 1, crop.getHeight() - 1), 1.4, .8, .05, .05, .9, .95,
                1, 1, OptionalDouble.empty());
        final var similarity = new SimilarityTransform2D(CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL, .2, 0, 0, 0);
        final var atlas = new AtlasReviewProvenance(AllenCoronalLevel.ATLAS_ID, AllenCoronalLevel.ATLAS_VERSION,
                456, 320, List.of(new AtlasAssetVerification("template", 10, "1".repeat(64)),
                        new AtlasAssetVerification("annotation", 20, "2".repeat(64)),
                        new AtlasAssetVerification("ontology", 30, "3".repeat(64))));
        final var basis = new AlignmentReviewBasis(new BaselineRegistrationProposal(new AllenCoronalLevel(240), geometry,
                similarity, similarity.asAffine(), RegistrationObjectiveMode.EXACT_FULL_RESOLUTION_COMPLETE_BOUNDARY,
                .8, .8), Optional.empty(), Optional.empty(), source, atlas,
                SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS, Optional.empty(),
                new ReviewPreviewDimensions(crop.getWidth(), crop.getHeight()));
        final var session = new AlignmentReviewSession(basis);
        session.apply(new ReviewEdit.Translate(1.25, .75));
        final var input = new RegistrationInput(crop.getNChannels(), crop.getNSlices(), 1);
        return new ReviewProject(new ReviewProject.SourceReference(item.section().sourceName(), Optional.empty()),
                "/verified/test-atlas", session.checkpoint(), input, DisplaySettings.defaults(source.metadata(), input),
                new ExportSelection(List.of(crop.getNChannels()), crop.getNSlices(), 1), rois, ReviewUiState.defaults(),
                Optional.of(BatchReviewCheckpoint.parentContext(item.section())));
    }
}
