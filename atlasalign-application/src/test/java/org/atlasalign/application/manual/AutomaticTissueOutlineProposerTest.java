package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewPreviewDimensions;
import org.atlasalign.application.TissuePolarity;
import org.atlasalign.application.TissueSegmentationCandidateDiagnostic;
import org.atlasalign.application.TissueSegmentationMethod;
import org.atlasalign.application.TissueSegmentationResult;
import org.atlasalign.application.VirtualHalfPayloadHashes;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.PreviewMapping;
import org.junit.jupiter.api.Test;

class AutomaticTissueOutlineProposerTest {

    private static final String PREVIEW_SHA = VirtualHalfPayloadHashes
            .pixelsSha256(previewPixels(20, 16));
    private final AutomaticTissueOutlineProposer proposer =
            new AutomaticTissueOutlineProposer();

    @Test
    void proposesDeterministicDraftFromLargestComponentAndFillsHoles() {
        final boolean[] pixels = new boolean[20 * 16];
        fill(pixels, 20, 3, 3, 16, 12, true);
        fill(pixels, 20, 8, 6, 10, 8, false);
        pixels[8 * 20 + 1] = true;
        final BinaryMask mask = BinaryMask.fromBooleans(20, 16, pixels);
        final BitSet before = mask.copyBits();
        final PreviewMapping mapping = new PreviewMapping(40, 32, 20, 16);
        final SourceImageIdentity source = source(40, 32);

        final ManualContour first = propose(
                "auto-outline", segmentation(mask), mapping, source);
        final ManualContour second = propose(
                "auto-outline", segmentation(mask), mapping, source);

        assertEquals(first, second);
        assertEquals(ManualContourKind.TISSUE_OUTLINE, first.kind());
        assertEquals(ContourTopology.CLOSED, first.topology());
        assertEquals(ContourCaptureStatus.DRAFT, first.captureStatus());
        assertEquals(AnatomicalSide.BILATERAL, first.anatomicalSide());
        assertEquals(ContourCompleteness.COMPLETE, first.completeness());
        assertTrue(first.excludedGapSegments().isEmpty());
        assertEquals(4, first.vertices().size());
        assertEquals(before, mask.copyBits());
        final AutomaticTissueOutlineProvenance provenance = first
                .automaticProposalProvenance().orElseThrow();
        assertEquals(source, provenance.sourceIdentity());
        assertEquals(PREVIEW_SHA, provenance.copiedPreviewPixelSha256());
        assertEquals(140 - 9, provenance.selectedComponentPixelCount());
        assertEquals(140, provenance.filledEnvelopePixelCount());
        assertEquals(140, provenance.outlinedEnvelopePixelCount());
        assertEquals(0, provenance.discardedDiagonalOnlyComponentCount());
        assertEquals(0, provenance.discardedDiagonalOnlyPixelCount());
        assertEquals(0, provenance
                .largestDiscardedDiagonalOnlyEightConnectedSpanPixels());
        assertEquals(0,
                provenance.includedCornerBackgroundComponentCount());
        assertEquals(0, provenance.includedCornerBackgroundPixelCount());
        assertEquals(0, provenance
                .largestIncludedCornerBackgroundEightConnectedSpanPixels());
        assertEquals(4, provenance.rawLoopVertexCount());
        assertEquals(4, provenance.generatedVertexCount());
        assertFalse(provenance.reviewerModified());
        assertEquals(
                "largest-8c-fill-holes-resolve-microscopic-corner-contacts-v4",
                provenance.algorithmRevision());
        first.vertices().forEach(vertex -> {
            assertTrue(vertex.point().x() >= 0 && vertex.point().x() <= 39);
            assertTrue(vertex.point().y() >= 0 && vertex.point().y() <= 31);
        });
    }

    @Test
    void tieSelectionIsStableAndUsesFirstRowMajorComponent() {
        final boolean[] pixels = new boolean[20 * 16];
        fill(pixels, 20, 2, 2, 5, 5, true);
        fill(pixels, 20, 12, 9, 15, 12, true);
        final ManualContour contour = propose(
                "tie", segmentation(BinaryMask.fromBooleans(20, 16, pixels)),
                new PreviewMapping(20, 16, 20, 16), source(20, 16));

        assertTrue(contour.vertices().stream()
                .allMatch(vertex -> vertex.point().x() < 7
                        && vertex.point().y() < 7));
        assertEquals(16, contour.automaticProposalProvenance().orElseThrow()
                .selectedComponentPixelCount());
    }

    @Test
    void rejectsEmptyBorderTouchingImplausibleAndMismatchedInputs() {
        final PreviewMapping mapping = new PreviewMapping(20, 16, 20, 16);
        final SourceImageIdentity source = source(20, 16);
        assertThrows(IllegalArgumentException.class, () -> propose(
                "empty", segmentation(BinaryMask.empty(20, 16)), mapping,
                source));

        final boolean[] border = new boolean[20 * 16];
        fill(border, 20, 0, 3, 5, 8, true);
        final IllegalArgumentException borderError = assertThrows(
                IllegalArgumentException.class, () -> propose(
                        "border", segmentation(BinaryMask.fromBooleans(
                                20, 16, border)), mapping, source));
        assertTrue(borderError.getMessage().contains("touches"));

        final boolean[] implausiblySmall = new boolean[20 * 16];
        fill(implausiblySmall, 20, 3, 3, 4, 4, true);
        assertThrows(IllegalArgumentException.class, () -> propose(
                "small", segmentation(BinaryMask.fromBooleans(
                        20, 16, implausiblySmall)), mapping, source));
        assertThrows(IllegalArgumentException.class, () -> propose(
                "dimensions", segmentation(BinaryMask.empty(10, 8)), mapping,
                source));
        assertThrows(IllegalArgumentException.class, () -> propose(
                "source", segmentation(BinaryMask.empty(20, 16)), mapping,
                source(21, 16)));
    }

    @Test
    void rejectsSameSizedCopiedPreviewThatDoesNotMatchReviewBasisIdentity() {
        final BinaryMask mask = BinaryMask.fromBooleans(
                20, 16, rectanglePixels());
        final PreviewMapping mapping = new PreviewMapping(20, 16, 20, 16);
        final float[] actualCopiedPreview = previewPixels(20, 16);
        final float[] differentBasisPreview = actualCopiedPreview.clone();
        differentBasisPreview[37] += 1.0f;

        final IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class, () -> proposer.propose(
                        "mismatch", segmentation(mask), mapping, source(20, 16),
                        actualCopiedPreview, ReviewPreviewDimensions.capture(
                                20, 16, differentBasisPreview)));

        assertTrue(mismatch.getMessage().contains("do not match"));
        assertThrows(IllegalArgumentException.class, () -> proposer.propose(
                "missing-identity", segmentation(mask), mapping, source(20, 16),
                actualCopiedPreview, new ReviewPreviewDimensions(20, 16)));
    }

    @Test
    void rejectsMaterialDiagonalOnlyLobesRatherThanBridgingThem() {
        final boolean[] pixels = new boolean[20 * 16];
        fill(pixels, 20, 3, 3, 5, 5, true);
        fill(pixels, 20, 6, 6, 8, 8, true);

        final IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> propose(
                        "diagonal-lobes",
                        segmentation(BinaryMask.fromBooleans(20, 16, pixels)),
                        new PreviewMapping(20, 16, 20, 16), source(20, 16)));

        assertTrue(error.getMessage().contains("material diagonal-only lobe"));
        assertTrue(error.getMessage().contains("without inventing tissue"));
    }

    @Test
    void prunesOnlyMicroscopicDiagonalSpecksFromS102TopologyRegression() {
        final int width = 1_024;
        final int height = 670;
        final boolean[] pixels = new boolean[width * height];
        fill(pixels, width, 100, 100, 899, 549, true);
        final int[] diagonalOnlySizes = {
            6, 3, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1
        };
        int x = 130;
        for (final int componentSize : diagonalOnlySizes) {
            for (int offset = 0; offset < componentSize; offset++) {
                pixels[99 * width + x + offset] = true;
                pixels[100 * width + x + offset] = false;
            }
            x += componentSize + 24;
        }
        final BinaryMask mask = BinaryMask.fromBooleans(
                width, height, pixels);
        final BitSet before = mask.copyBits();

        final ManualContour first = propose(
                "s102-topology", segmentation(mask),
                new PreviewMapping(width, height, width, height),
                source(width, height));
        final ManualContour second = propose(
                "s102-topology", segmentation(mask),
                new PreviewMapping(width, height, width, height),
                source(width, height));

        assertEquals(first, second);
        assertEquals(before, mask.copyBits());
        final AutomaticTissueOutlineProvenance provenance = first
                .automaticProposalProvenance().orElseThrow();
        assertEquals(360_000, provenance.filledEnvelopePixelCount());
        assertEquals(359_978, provenance.outlinedEnvelopePixelCount());
        assertEquals(14,
                provenance.discardedDiagonalOnlyComponentCount());
        assertEquals(22, provenance.discardedDiagonalOnlyPixelCount());
        assertEquals(6,
                provenance.largestDiscardedDiagonalOnlyComponentPixelCount());
        assertTrue(provenance
                .largestDiscardedDiagonalOnlyEightConnectedSpanPixels() <= 8);
        assertFalse(provenance.filledEnvelopeSha256().equals(
                provenance.outlinedEnvelopeSha256()));
    }

    @Test
    void resolvesOnlyMicroscopicCornerConnectedBackgroundWithoutClosingTears() {
        final int width = 500;
        final int height = 500;
        final boolean[] pixels = new boolean[width * height];
        fill(pixels, width, 50, 50, 449, 449, true);
        pixels[50 * width + 200] = false;
        pixels[51 * width + 201] = false;
        pixels[52 * width + 202] = false;

        final ManualContour contour = propose(
                "corner-background", segmentation(BinaryMask.fromBooleans(
                        width, height, pixels)),
                new PreviewMapping(width, height, width, height),
                source(width, height));

        final AutomaticTissueOutlineProvenance provenance = contour
                .automaticProposalProvenance().orElseThrow();
        assertEquals(2,
                provenance.includedCornerBackgroundComponentCount());
        assertEquals(2, provenance.includedCornerBackgroundPixelCount());
        assertEquals(1, provenance
                .largestIncludedCornerBackgroundComponentPixelCount());
        assertEquals(2, provenance
                .largestIncludedCornerBackgroundEightConnectedSpanPixels());
        assertEquals(provenance.filledEnvelopePixelCount() + 2,
                provenance.outlinedEnvelopePixelCount());
    }

    @Test
    void rejectsMaterialCornerConnectedBackgroundGap() {
        final int width = 500;
        final int height = 500;
        final boolean[] pixels = new boolean[width * height];
        fill(pixels, width, 50, 50, 449, 449, true);
        pixels[50 * width + 200] = false;
        for (int x = 201; x <= 205; x++) {
            pixels[51 * width + x] = false;
        }

        final IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> propose(
                        "material-background-gap",
                        segmentation(BinaryMask.fromBooleans(
                                width, height, pixels)),
                        new PreviewMapping(width, height, width, height),
                        source(width, height)));

        assertTrue(error.getMessage().contains("material background gap"));
        assertTrue(error.getMessage().contains("components="));
    }

    @Test
    void rejectsElongatedDiagonalForegroundChainDespiteTinyFourComponents() {
        final int width = 500;
        final int height = 500;
        final boolean[] pixels = new boolean[width * height];
        fill(pixels, width, 50, 50, 449, 449, true);
        for (int offset = 1; offset <= 9; offset++) {
            pixels[(50 - offset) * width + 50 - offset] = true;
        }

        final IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> propose(
                        "elongated-diagonal-lobe",
                        segmentation(BinaryMask.fromBooleans(
                                width, height, pixels)),
                        new PreviewMapping(width, height, width, height),
                        source(width, height)));

        assertTrue(error.getMessage().contains("material diagonal-only lobe"));
        assertTrue(error.getMessage().contains("largest8cSpan=9"));
    }

    @Test
    void rejectsElongatedDiagonalBackgroundTearDespiteTinyFourComponents() {
        final int width = 500;
        final int height = 500;
        final boolean[] pixels = new boolean[width * height];
        fill(pixels, width, 50, 50, 449, 449, true);
        for (int offset = 0; offset <= 5; offset++) {
            pixels[(50 + offset) * width + 200 + offset] = false;
        }

        final IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> propose(
                        "elongated-diagonal-tear",
                        segmentation(BinaryMask.fromBooleans(
                                width, height, pixels)),
                        new PreviewMapping(width, height, width, height),
                        source(width, height)));

        assertTrue(error.getMessage().contains("material background gap"));
        assertTrue(error.getMessage().contains("largest8cSpan=5"));
    }

    @Test
    void vertexEditsPreserveProvenanceAndMarkOnlyActualReviewChanges() {
        final ManualContour proposed = rectangleProposal();
        final AutomaticTissueOutlineProvenance original = proposed
                .automaticProposalProvenance().orElseThrow();

        final ManualContour finished = proposed.withCaptureStatus(
                ContourCaptureStatus.COMPLETE);
        assertFalse(finished.automaticProposalProvenance().orElseThrow()
                .reviewerModified());
        assertTrue(finished.eligibleForPreview());

        final ManualContour changed = ManualContourVertexEdits.insertAfter(
                proposed, proposed.vertices().get(0).id(),
                new ContourVertex("reviewer-inserted",
                        new SourcePixelPoint(9, 4)));

        assertTrue(changed.automaticProposalProvenance().orElseThrow()
                .reviewerModified());
        assertEquals(original.generatedLoopSha256(), changed
                .automaticProposalProvenance().orElseThrow()
                .generatedLoopSha256());
        assertThrows(IllegalArgumentException.class, () -> new ManualContour(
                "wrong-kind", ManualContourKind.MIDLINE, ContourTopology.OPEN,
                ContourCaptureStatus.DRAFT, AnatomicalSide.BILATERAL,
                ContourCompleteness.COMPLETE, Optional.empty(),
                proposed.sourceIdentity(), proposed.vertices(), Set.of(),
                proposed.automaticProposalProvenance()));
        assertThrows(IllegalArgumentException.class, () -> new ManualContour(
                "wrong-source", ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.DRAFT,
                AnatomicalSide.BILATERAL, ContourCompleteness.COMPLETE,
                Optional.empty(), source(21, 16), proposed.vertices(), Set.of(),
                proposed.automaticProposalProvenance()));
    }

    @Test
    void deterministicallyCapsJaggedLoopWithoutChangingTopology() {
        final int width = 320;
        final int height = 60;
        final boolean[] pixels = new boolean[width * height];
        for (int x = 10; x < 310; x++) {
            final int top = 5 + x % 2;
            for (int y = top; y <= 45; y++) {
                pixels[y * width + x] = true;
            }
        }
        final BinaryMask mask = BinaryMask.fromBooleans(width, height, pixels);

        final ManualContour contour = propose(
                "jagged", segmentation(mask),
                new PreviewMapping(width, height, width, height),
                source(width, height));

        final AutomaticTissueOutlineProvenance provenance = contour
                .automaticProposalProvenance().orElseThrow();
        assertTrue(provenance.rawLoopVertexCount() > 192);
        assertEquals(192, contour.vertices().size());
        assertEquals(192, provenance.generatedVertexCount());
        assertEquals(contour.vertices().size(), contour.vertices().stream()
                .map(ContourVertex::point).distinct().count());
    }

    @Test
    void reviewerChosenEditPointCapIsDeterministicAndAudited() {
        final int width = 320;
        final int height = 60;
        final boolean[] pixels = new boolean[width * height];
        for (int x = 10; x < 310; x++) {
            final int top = 5 + x % 2;
            for (int y = top; y <= 45; y++) {
                pixels[y * width + x] = true;
            }
        }
        final PreviewMapping mapping = new PreviewMapping(
                width, height, width, height);
        final float[] copiedPreview = previewPixels(width, height);
        final ManualContour first = proposer.propose(
                "compact", segmentation(BinaryMask.fromBooleans(
                        width, height, pixels)), mapping,
                source(width, height), copiedPreview,
                ReviewPreviewDimensions.capture(
                        width, height, copiedPreview), 48);
        final ManualContour replay = proposer.propose(
                "compact", segmentation(BinaryMask.fromBooleans(
                        width, height, pixels)), mapping,
                source(width, height), copiedPreview,
                ReviewPreviewDimensions.capture(
                        width, height, copiedPreview), 48);

        assertEquals(first, replay);
        assertEquals(48, first.vertices().size());
        assertEquals(48, first.automaticProposalProvenance().orElseThrow()
                .generatedVertexCount());
        assertTrue(first.automaticProposalProvenance().orElseThrow()
                .algorithmRevision().endsWith(";maxVertices=48"));
        assertThrows(IllegalArgumentException.class, () -> proposer.propose(
                "too-small", segmentation(BinaryMask.fromBooleans(
                        width, height, pixels)), mapping,
                source(width, height), copiedPreview,
                ReviewPreviewDimensions.capture(
                        width, height, copiedPreview), 23));
    }

    @Test
    void insertionIsOneAuditedRevisionAndUndoRestoresPriorContent() {
        final GuidedManualWorkflowSession session =
                new GuidedManualWorkflowSession();
        final ManualContour proposal = rectangleProposal();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                fullObservation()));

        final ManualWorkflowRevision revision = session.apply(
                new ManualWorkflowEdit.InsertAutomaticTissueOutline(proposal));

        assertEquals(ManualWorkflowOperation.INSERT_AUTOMATIC_TISSUE_OUTLINE,
                revision.operation());
        assertEquals(3, session.visibleHistory().size());
        assertEquals(proposal, session.content().contours().get(proposal.id()));
        assertTrue(revision.description().contains("automatic tissue-outline"));
        final AutomaticTissueOutlineProvenance provenance = proposal
                .automaticProposalProvenance().orElseThrow();
        assertTrue(revision.description().contains(
                "sourceSha256=" + proposal.sourceIdentity().pixelSha256()));
        assertTrue(revision.description().contains(
                "copiedPreviewSha256=" + PREVIEW_SHA));
        assertTrue(revision.description().contains(
                "inputMaskSha256=" + provenance.inputMaskSha256()));
        assertTrue(revision.description().contains(
                "filledEnvelopeSha256=" + provenance.filledEnvelopeSha256()));
        assertTrue(revision.description().contains(
                "outlinedEnvelopeSha256="
                        + provenance.outlinedEnvelopeSha256()));
        assertTrue(revision.description().contains(
                "discardedDiagonalOnlyPixels=0"));
        assertTrue(revision.description().contains(
                "largestDiscardedDiagonalOnly8cSpanPixels=0"));
        assertTrue(revision.description().contains(
                "includedCornerBackgroundPixelsSha256="
                        + provenance.includedCornerBackgroundPixelsSha256()));
        assertTrue(revision.description().contains(
                "includedCornerBackgroundPixels=0"));
        assertTrue(revision.description().contains(
                "largestIncludedCornerBackground8cSpanPixels=0"));
        assertTrue(revision.description().contains(
                "generatedLoopSha256=" + provenance.generatedLoopSha256()));
        assertTrue(revision.description().contains("reviewerModified=false"));
        assertTrue(session.undo());
        assertTrue(session.content().contours().isEmpty());
        assertEquals(List.of(revision.id()), session.redoChoices().stream()
                .map(ManualWorkflowRevision::id).toList());
    }

    @Test
    void insertionRequiresFullObservationAndNeverDuplicatesAnOutline() {
        final ManualContour proposal = rectangleProposal();
        final GuidedManualWorkflowSession missingObservation =
                new GuidedManualWorkflowSession();
        assertThrows(IllegalStateException.class, () -> missingObservation
                .apply(new ManualWorkflowEdit.InsertAutomaticTissueOutline(
                        proposal)));

        final GuidedManualWorkflowSession half =
                new GuidedManualWorkflowSession();
        half.apply(new ManualWorkflowEdit.SetSectionObservation(
                new SectionObservation(
                        SectionGeometry.IMAGE_LEFT_HALF,
                        AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                        ObservedAnatomicalHemisphere.LEFT, true, true)));
        assertThrows(IllegalStateException.class, () -> half.apply(
                new ManualWorkflowEdit.InsertAutomaticTissueOutline(
                        proposal)));

        final GuidedManualWorkflowSession full =
                new GuidedManualWorkflowSession();
        full.apply(new ManualWorkflowEdit.SetSectionObservation(
                fullObservation()));
        full.apply(new ManualWorkflowEdit.InsertAutomaticTissueOutline(
                proposal));
        assertThrows(IllegalStateException.class, () -> full.apply(
                new ManualWorkflowEdit.InsertAutomaticTissueOutline(
                        propose("second-outline", segmentation(
                                        BinaryMask.fromBooleans(20, 16,
                                                rectanglePixels())),
                                new PreviewMapping(20, 16, 20, 16),
                                source(20, 16)))));
        assertEquals(1, full.content().contours().size());
    }

    private ManualContour rectangleProposal() {
        return propose(
                "auto-outline",
                segmentation(BinaryMask.fromBooleans(
                        20, 16, rectanglePixels())),
                new PreviewMapping(20, 16, 20, 16), source(20, 16));
    }

    private ManualContour propose(
            final String id,
            final TissueSegmentationResult segmentation,
            final PreviewMapping mapping,
            final SourceImageIdentity source) {
        final float[] copiedPreview = previewPixels(
                mapping.previewWidth(), mapping.previewHeight());
        return proposer.propose(
                id, segmentation, mapping, source, copiedPreview,
                ReviewPreviewDimensions.capture(
                        mapping.previewWidth(), mapping.previewHeight(),
                        copiedPreview));
    }

    private static float[] previewPixels(
            final int width, final int height) {
        final float[] pixels = new float[Math.multiplyExact(width, height)];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = index % 17;
        }
        return pixels;
    }

    private static boolean[] rectanglePixels() {
        final boolean[] pixels = new boolean[20 * 16];
        fill(pixels, 20, 3, 3, 16, 12, true);
        return pixels;
    }

    private static SectionObservation fullObservation() {
        return new SectionObservation(
                SectionGeometry.FULL,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.BOTH, true, true);
    }

    private static TissueSegmentationResult segmentation(final BinaryMask mask) {
        final int total = mask.width() * mask.height();
        final double foreground = (double) mask.foregroundCount() / total;
        final List<TissueSegmentationCandidateDiagnostic> diagnostics =
                new ArrayList<>();
        for (final TissueSegmentationMethod method
                : TissueSegmentationMethod.values()) {
            diagnostics.add(new TissueSegmentationCandidateDiagnostic(
                    method, TissuePolarity.BRIGHT_ON_DARK, 0.5f,
                    Optional.empty(), 0, 1, false, foreground, foreground, 0,
                    Optional.empty(), Optional.empty(), 1, Optional.empty()));
        }
        return new TissueSegmentationResult(
                mask, TissuePolarity.BRIGHT_ON_DARK, 0.5f,
                foreground, foreground, 0,
                TissueSegmentationMethod.ORDINARY_OTSU, 0, 1, false, 1,
                diagnostics, List.of("test segmentation"));
    }

    private static SourceImageIdentity source(
            final int width, final int height) {
        final var base = ManualContourContractTest.sourceMetadata();
        return new SourceImageIdentity(
                "1".repeat(64),
                new org.atlasalign.core.SourceImageMetadata(
                        width, height, base.channels(), base.slices(),
                        base.frames(), base.bitDepth(), base.channelLabels(),
                        base.stackPlaneLabels(), base.calibration()),
                "SOURCE_TO_PREVIEW_PIXEL_CENTRE_V1");
    }

    private static void fill(
            final boolean[] pixels,
            final int width,
            final int minimumX,
            final int minimumY,
            final int maximumX,
            final int maximumY,
            final boolean value) {
        for (int y = minimumY; y <= maximumY; y++) {
            for (int x = minimumX; x <= maximumX; x++) {
                pixels[y * width + x] = value;
            }
        }
    }
}
