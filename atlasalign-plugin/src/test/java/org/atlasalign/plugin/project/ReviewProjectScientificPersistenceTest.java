package org.atlasalign.plugin.project;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ShortProcessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.atlasalign.application.*;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.manual.*;
import org.atlasalign.application.manual.ManualHemisphereWarp2D.AtlasSide;
import org.atlasalign.application.roi.*;
import org.atlasalign.core.*;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.plugin.OpenReviewProjectCommand;
import org.atlasalign.plugin.export.ManualRoiExportService.ParentSourceContext;
import org.atlasalign.plugin.review.ReviewCanvas;
import org.atlasalign.plugin.review.ReviewWorkflowStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the complete project envelope, beyond individual transform DTOs. */
class ReviewProjectScientificPersistenceTest {
    private static final int WIDTH = 400;
    private static final int HEIGHT = 300;
    private static final AtlasOrientation ORIENTATION = AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT;
    private static final AtlasPlaneTilt TILT = new AtlasPlaneTilt(2.5, -1.25);
    private final ReviewProjectCodec codec = new ReviewProjectCodec();

    @TempDir Path temporaryDirectory;

    @Test
    void fullHalfAndDisjoinedProjectsRetainExactMeshesAndComposedMappings() {
        for (final ReviewSectionMode mode : ReviewSectionMode.values()) {
            final AlignmentReviewBasis basis = basis(mode);
            final var controls = new ArrayList<>(controls(AtlasSide.LEFT, 0, 2));
            if (mode != ReviewSectionMode.HALF) {
                controls.addAll(controls(AtlasSide.RIGHT, mode == ReviewSectionMode.DISJOINED ? 0 : 190, -2));
            }
            final var warp = ManualHemisphereWarp2D.fit(controls, ORIENTATION, mode, WIDTH, HEIGHT);
            final var placement = mode == ReviewSectionMode.DISJOINED
                    ? new ManualSidePlacement2D(translation(3.125, -2.75), translation(-8.375, 4.5))
                    : ManualSidePlacement2D.identity();
            final var content = content(basis, mode, placement, Optional.empty(), Optional.of(warp),
                    List.of(landmark("fit", basis, new Point2D(50, 60), new Point2D(52, 60.5), LandmarkRole.FIT),
                            landmark("check", basis, new Point2D(100, 80), new Point2D(100.25, 81), LandmarkRole.CHECK)),
                    Optional.empty());
            final ReviewProject original = project(basis, content);
            final ReviewProject restored = roundTrip(original);
            final var reopenedWarp = restored.alignment().content().hemisphereWarp().orElseThrow();
            assertEquals(warp.snapshot(), reopenedWarp.snapshot(), mode.name());
            assertEquals(warp.snapshot().snapshotSha256(), reopenedWarp.snapshot().snapshotSha256());
            assertEquals(warp.diagnostics().contentSha256(), reopenedWarp.diagnostics().contentSha256());
            for (final Point2D point : samples()) {
                assertEquals(warp.apply(point), reopenedWarp.apply(point));
                assertEquals(warp.inverse(point), reopenedWarp.inverse(point));
                for (final AtlasSide side : AtlasSide.values()) {
                    assertEquals(warp.apply(side, point), reopenedWarp.apply(side, point));
                    assertEquals(warp.inverse(side, point), reopenedWarp.inverse(side, point));
                }
            }
            assertEquals(mode, restored.alignment().content().reviewSectionMode());
            assertEquals(placement, restored.alignment().content().manualSidePlacement());
            assertEquals(TILT, restored.alignment().content().atlasPlaneTilt());
            assertEquals(content.landmarks(), restored.alignment().content().landmarks());
            assertEquals(content.halfAtlasCoverage(), restored.alignment().content().halfAtlasCoverage());
        }
    }

    @Test
    void manualOutlineAndConstrainedWarpRetainSolvedCoefficientsAndIndependentCheckEvidence() {
        final AlignmentReviewBasis basis = basis(ReviewSectionMode.FULL);
        final List<Point2D> loop = List.of(new Point2D(20, 20), new Point2D(375, 20),
                new Point2D(375, 265), new Point2D(20, 265));
        final List<Point2D> tissue = loop.stream().map(point -> new Point2D(point.x() + 3, point.y() + 1)).toList();
        final var anchors = List.of(new ManualOutlineWarp2D.AnchorPair("D", 0, 0),
                new ManualOutlineWarp2D.AnchorPair("R", 1, 1), new ManualOutlineWarp2D.AnchorPair("V", 2, 2),
                new ManualOutlineWarp2D.AnchorPair("L", 3, 3));
        final var outline = ManualOutlineWarp2D.fit(loop, tissue, anchors, WIDTH, HEIGHT);
        final var beforeLocal = content(basis, ReviewSectionMode.FULL, ManualSidePlacement2D.identity(),
                Optional.of(outline), Optional.empty(), List.of(), Optional.empty());
        final var state = new AlignmentReviewState(basis, beforeLocal, 5);
        final List<Point2D> atlasPoints = List.of(new Point2D(50, 50), new Point2D(130, 50),
                new Point2D(50, 130), new Point2D(130, 130));
        final List<Point2D> sources = atlasPoints.stream().map(state::mapAtlasToPreview).toList();
        final var targets = new ArrayList<>(sources);
        targets.set(0, new Point2D(sources.get(0).x() + 2, sources.get(0).y() + .75));
        final Point2D checkAtlas = new Point2D(220, 210);
        final Point2D checkSource = state.mapAtlasToPreview(checkAtlas);
        final Point2D checkTarget = new Point2D(checkSource.x() + .25, checkSource.y() + .5);
        final var local = ConstrainedLocalWarp2D.fit(sources, targets, List.of(checkSource),
                List.of(checkTarget), WIDTH, HEIGHT);
        final var landmarks = new ArrayList<LandmarkPair>();
        for (int index = 0; index < atlasPoints.size(); index++) {
            landmarks.add(landmark("fit-" + index, basis, atlasPoints.get(index), targets.get(index), LandmarkRole.FIT));
        }
        landmarks.add(landmark("independent-check", basis, checkAtlas, checkTarget, LandmarkRole.CHECK));
        final var content = content(basis, ReviewSectionMode.FULL, ManualSidePlacement2D.identity(),
                Optional.of(outline), Optional.empty(), landmarks, Optional.of(local));
        final var restored = roundTrip(project(basis, content)).alignment().content();
        final var restoredOutline = (ManualOutlineWarp2D) restored.outlineWarp().orElseThrow();
        final var restoredLocal = restored.localWarp().orElseThrow();
        assertEquals(outline.diagnostics().contentSha256(), restoredOutline.diagnostics().contentSha256());
        assertEquals(outline.xWeights(), restoredOutline.xWeights());
        assertEquals(outline.yWeights(), restoredOutline.yWeights());
        assertEquals(local.diagnostics().contentHash(), restoredLocal.diagnostics().contentHash());
        assertEquals(local.xWeights(), restoredLocal.xWeights());
        assertEquals(local.yWeights(), restoredLocal.yWeights());
        assertEquals(local.diagnostics().checkRms(), restoredLocal.diagnostics().checkRms());
        assertTrue(restoredLocal.diagnostics().checkRms().isPresent());
        assertEquals(1, restored.activeCheckLandmarks().size());
        assertEquals(4, restoredLocal.fitSourcePoints().size());
        for (final Point2D point : samples()) {
            assertEquals(outline.apply(point), restoredOutline.apply(point));
            assertEquals(outline.inverse(point), restoredOutline.inverse(point));
            assertEquals(local.apply(point), restoredLocal.apply(point));
            assertEquals(local.inverse(point), restoredLocal.inverse(point));
        }
    }

    @Test
    void boundaryAuthoritativeOutlineRetainsExactTrianglesAndPinnedHemisphereField() {
        final AlignmentReviewBasis basis = basis(ReviewSectionMode.FULL);
        final List<Point2D> atlas = List.of(new Point2D(200, 50), new Point2D(300, 100),
                new Point2D(300, 200), new Point2D(200, 250), new Point2D(100, 200), new Point2D(100, 100));
        final List<Point2D> tissue = atlas.stream().map(point -> new Point2D(
                .98 * point.x() + .04 * point.y() + 5, .01 * point.x() + .96 * point.y() + 3)).toList();
        final var outline = BoundaryAuthoritativeTransform2D.fitFull(MonotoneBoundary2D.indexed("atlas-", atlas),
                MonotoneBoundary2D.indexed("tissue-", tissue), WIDTH, HEIGHT);
        final var controls = new ArrayList<ManualWarpControl>();
        for (final Point2D atlasPoint : List.of(new Point2D(230, 120), new Point2D(270, 120),
                new Point2D(230, 180), new Point2D(270, 180))) {
            final Point2D source = outline.apply(atlasPoint);
            controls.add(new ManualWarpControl("exact-" + controls.size(), AtlasSide.RIGHT,
                    ManualWarpControlOrigin.USER_PLACED_INTERIOR, "exact", source,
                    new Point2D(source.x() + 1.5, source.y() - .7)));
        }
        final var hemisphere = ManualHemisphereWarp2D.fit(controls, ORIENTATION, outline);
        final var content = content(basis, ReviewSectionMode.FULL, ManualSidePlacement2D.identity(),
                Optional.of(outline), Optional.of(hemisphere), List.of(), Optional.empty());
        final var restored = roundTrip(project(basis, content)).alignment().content();
        final var restoredOutline = (BoundaryAuthoritativeTransform2D) restored.outlineWarp().orElseThrow();
        final var restoredHemisphere = restored.hemisphereWarp().orElseThrow();
        assertEquals(outline.snapshot(), restoredOutline.snapshot());
        assertEquals(outline.snapshot().triangles(), restoredOutline.snapshot().triangles());
        assertEquals(outline.diagnostics().contentSha256(), restoredOutline.diagnostics().contentSha256());
        assertEquals(hemisphere.snapshot(), restoredHemisphere.snapshot());
        assertEquals(outline.hemisphereMidlinePath(), restoredHemisphere.imageMidlinePath());
        assertEquals(outline.snapshot(), restoredHemisphere.snapshot().outline());
        for (final Point2D point : samples()) {
            assertEquals(outline.containsAtlasPoint(point), restoredOutline.containsAtlasPoint(point));
            assertEquals(outline.containsTissuePoint(point), restoredOutline.containsTissuePoint(point));
            assertSameBoundaryOutcome(() -> outline.apply(point), () -> restoredOutline.apply(point));
            assertSameBoundaryOutcome(() -> outline.inverse(point), () -> restoredOutline.inverse(point));
            for (final AtlasSide side : AtlasSide.values()) {
                assertEquals(hemisphere.apply(side, point), restoredHemisphere.apply(side, point));
                assertEquals(hemisphere.inverse(side, point), restoredHemisphere.inverse(side, point));
            }
        }
    }

    @Test
    void automaticPredictionRuntimeAndInputProvenanceSurviveWithoutInference() {
        final AlignmentReviewBasis manual = basis(ReviewSectionMode.FULL);
        final var plane = new DeepSliceOuv(0, 240, 0, 3, 0, 0, 0, 0, -3);
        final Path runtimePath = temporaryDirectory.resolve("verified-runtime").toAbsolutePath().normalize();
        final var prediction = DeepSlicePlanePrediction.fromWorkerVectors(plane, plane, plane,
                new DeepSliceRuntimeProvenance("verified-test-release", runtimePath,
                        2, 100, "a".repeat(64), "3.11.15", "1.2.8", "2.21.0", "test-model",
                        "b".repeat(64), "c".repeat(64), "d".repeat(64)));
        final var level = new AllenCoronalLevel(prediction.zeroBasedAnteriorPosteriorIndex());
        final var initial = new InitialPlaneProposal(level, InitialPlaneSource.LOCAL_DEEPSLICE,
                Optional.of(prediction), Optional.empty(), Optional.empty());
        final var proposal = new BaselineRegistrationProposal(level, manual.proposal().geometry(),
                manual.proposal().similarity(), manual.proposal().affine(), manual.proposal().objectiveMode(), .82, .88);
        final var input = new DeepSliceInputProvenance(DeepSliceInputProvenance.ALGORITHM_REVISION,
                DeepSliceInputCondition.ORIGINAL_PREVIEW, WIDTH, HEIGHT, "e".repeat(64), "f".repeat(64),
                0, 0, OptionalDouble.of(.7), Optional.empty());
        final var basis = new AlignmentReviewBasis(proposal, Optional.of(initial), manual.segmentation(),
                manual.sourceSnapshot(), manual.atlas(), manual.syntheticPixelPolicy(), Optional.empty(),
                manual.previewDimensions(), Optional.of(input), manual.automaticPlaneInitialization());
        final var content = content(basis, ReviewSectionMode.FULL, ManualSidePlacement2D.identity(),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty());
        final var saved = roundTrip(project(basis, content));
        assertEquals(basis.initialPlaneProposal(), saved.alignment().basis().initialPlaneProposal());
        assertEquals(basis.deepSliceInputProvenance(), saved.alignment().basis().deepSliceInputProvenance());
        assertEquals(prediction.runtimeProvenance(), saved.alignment().basis().initialPlaneProposal()
                .orElseThrow().prediction().orElseThrow().runtimeProvenance());
        assertEquals(runtimePath, saved.alignment().basis().initialPlaneProposal().orElseThrow()
                .prediction().orElseThrow().runtimeProvenance().orElseThrow().canonicalRuntimePath());
        assertEquals(ReviewWorkflowMode.AUTOMATIC_REVIEW, saved.alignment().content().workflowMode());
    }

    @Test
    void multipartHolesDraftsAndWholeSlideScopeRemainEditableAfterReopen() {
        final AlignmentReviewBasis basis = basis(ReviewSectionMode.FULL);
        final ReviewProject original = project(basis, basis.initialContent());
        final var restored = roundTrip(original);
        final var before = original.rois();
        final var after = restored.rois();
        assertEquals(before, after);
        assertEquals(3, after.rois().get(0).parts().size());
        assertEquals(RoiPartOperation.SUBTRACT, after.rois().get(0).parts().get(1).operation());
        assertEquals(before.activeRoiId(), after.activeRoiId());
        assertEquals(before.activePartId(), after.activePartId());
        assertEquals(2, after.activePart().orElseThrow().vertices().size());
        assertFalse(after.activePart().orElseThrow().finished());
        assertEquals(original.parentSource(), restored.parentSource());
        assertEquals(new ExportSelection(List.of(2), 2, 1), restored.exportSelection());
        assertEquals(new RegistrationInput(2, 2, 1), restored.registrationInput());
        final var editor = ReviewerRoiSession.restore(after.sectionId(), WIDTH, HEIGHT, after.rois(),
                after.activeRoiId(), after.activePartId(), after.revision());
        assertFalse(editor.canUndo());
        editor.addVertex(new Point2D(280, 80));
        editor.finishActivePart();
        assertTrue(editor.snapshot().rois().stream().allMatch(ReviewerRoi::finished));
        assertEquals(2, before.activePart().orElseThrow().vertices().size(), "Restored edits cannot mutate the saved draft");
    }

    @Test
    void failedAtomicReplacementLeavesPreviousCheckpointByteForByteAndRemovesTemporaryFile() throws IOException {
        final AlignmentReviewBasis basis = basis(ReviewSectionMode.FULL);
        final ReviewProject previous = project(basis, basis.initialContent());
        final Path file = temporaryDirectory.resolve("section.atlasalign-project.json");
        final var store = new ReviewProjectStore();
        store.save(file, previous);
        final byte[] previousBytes = Files.readAllBytes(file);
        final var session = new AlignmentReviewSession(basis);
        session.apply(new ReviewEdit.Translate(3, 4));
        final ReviewProject next = project(basis, session.state().content());
        final var replacementReached = new AtomicBoolean();
        final var failing = new ReviewProjectStore(codec, (temporary, destination) -> {
            replacementReached.set(true);
            assertEquals(file, destination);
            assertEquals(next, codec.decode(Files.readAllBytes(temporary)));
            throw new IOException("Injected atomic replacement failure");
        });
        assertThrows(IOException.class, () -> failing.save(file, next));
        assertTrue(replacementReached.get());
        assertArrayEquals(previousBytes, Files.readAllBytes(file));
        assertEquals(previous, store.restore(store.read(file), verification(basis)));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of(file), files.toList());
        }
        store.save(file, next);
        assertEquals(next, store.restore(store.read(file), verification(basis)));
    }

    @Test
    void changedSourceOrAtlasRejectBeforeGeometryDecodeAndTruncatedFilesFailClosed() throws Exception {
        final AlignmentReviewBasis basis = basis(ReviewSectionMode.FULL);
        final byte[] bytes = codec.encode(project(basis, basis.initialContent()));
        final var store = new ReviewProjectStore();
        final var changedSource = new SourceImageSnapshot(basis.sourceSnapshot().metadata(), "9".repeat(64));
        final var changedAssets = new ArrayList<>(basis.atlas().assets());
        changedAssets.set(0, new AtlasAssetVerification("template", 10, "8".repeat(64)));
        final var changedAtlas = new AtlasReviewProvenance(basis.atlas().atlasId(), basis.atlas().atlasVersion(),
                basis.atlas().atlasPlaneWidth(), basis.atlas().atlasPlaneHeight(), changedAssets);
        // The envelope remains well formed, but geometry is deliberately unusable. Identity gates must win first.
        final var mapper = new ObjectMapper();
        final var root = (ObjectNode) mapper.readTree(bytes);
        ((ObjectNode) root.path("payload").path("alignment").path("content")).put("coronalLevel", "invalid-geometry");
        root.put("payloadSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(root.path("payload")))));
        final byte[] invalidGeometry = mapper.writeValueAsBytes(root);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> store.restore(invalidGeometry,
                new ReviewAcceptanceVerification(changedSource, basis.atlas()))).getMessage().startsWith("Source image"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> store.restore(invalidGeometry,
                new ReviewAcceptanceVerification(basis.sourceSnapshot(), changedAtlas))).getMessage().startsWith("Atlas cache"));
        assertThrows(IllegalArgumentException.class, () -> store.restore(bytes,
                new ReviewAcceptanceVerification(changedSource, basis.atlas())));
        assertThrows(IllegalArgumentException.class, () -> store.restore(bytes,
                new ReviewAcceptanceVerification(basis.sourceSnapshot(), changedAtlas)));
        assertThrows(IllegalArgumentException.class, () -> store.restore(invalidGeometry, verification(basis)));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(java.util.Arrays.copyOf(bytes, bytes.length / 2)));
        assertThrows(IOException.class, () -> store.read(temporaryDirectory.resolve("missing-project.json")));
        final Path empty = Files.createFile(temporaryDirectory.resolve("empty-project.json"));
        assertThrows(IOException.class, () -> store.read(empty));
    }

    @Test
    void reopenRejectsChangedLiveSourceAndMissingAtlasWithoutChangingSource() {
        final var stack = new ImageStack(WIDTH, HEIGHT);
        for (int plane = 0; plane < 8; plane++) {
            final short[] pixels = new short[WIDTH * HEIGHT];
            pixels[0] = (short) (100 + plane);
            stack.addSlice("plane-" + plane, new ShortProcessor(WIDTH, HEIGHT, pixels, null));
        }
        final var source = new ImagePlus("Source", stack);
        source.setDimensions(2, 2, 2);
        source.setPosition(2, 2, 1);
        final var sourceSnapshot = new ImagePlusSourceImage(source).snapshot();
        final var base = basis(ReviewSectionMode.FULL);
        final var liveBasis = new AlignmentReviewBasis(base.proposal(), base.initialPlaneProposal(), base.segmentation(),
                sourceSnapshot, base.atlas(), base.syntheticPixelPolicy(), base.inferencePreparationProvenance(),
                base.previewDimensions(), base.deepSliceInputProvenance(), base.automaticPlaneInitialization());
        final byte[] saved = codec.encode(project(liveBasis, liveBasis.initialContent()));
        final Path missingAtlas = temporaryDirectory.resolve("missing-atlas-cache");
        assertThrows(RuntimeException.class, () -> OpenReviewProjectCommand.prepare(saved, source, missingAtlas));
        assertFalse(Files.exists(missingAtlas));
        assertEquals(sourceSnapshot, new ImagePlusSourceImage(source).snapshot());
        assertEquals(2, source.getC()); assertEquals(2, source.getZ()); assertEquals(1, source.getT());
        ((short[]) stack.getPixels(1))[0]++;
        final var changed = new ImagePlusSourceImage(source).snapshot();
        final var failure = assertThrows(IllegalArgumentException.class,
                () -> OpenReviewProjectCommand.prepare(saved, source, missingAtlas));
        assertTrue(failure.getMessage().startsWith("The source does not match"));
        assertEquals(changed, new ImagePlusSourceImage(source).snapshot());
    }

    private ReviewProject roundTrip(final ReviewProject original) {
        final byte[] bytes = codec.encode(original);
        final var header = codec.header(bytes);
        assertEquals(original.alignment().basis().sourceSnapshot(), header.sourceSnapshot());
        assertEquals(original.alignment().basis().atlas(), header.atlas());
        final var restored = new ReviewProjectStore().restore(bytes, verification(original.alignment().basis()));
        assertArrayEquals(bytes, codec.encode(restored), "Re-encoding must retain exact numeric values and saved state");
        final var reopened = AlignmentReviewSession.restore(restored.alignment(), verification(original.alignment().basis()));
        // BoundaryAuthoritativeTransform2D has identity equality; the byte/hash/sample
        // checks compare its persisted value, while this checks that restore installs that decoded object.
        assertEquals(restored.alignment().content(), reopened.state().content());
        assertEquals(original.alignment().initialContent(), reopened.initialContent());
        assertEquals(original.alignment().contentRevision(), reopened.state().contentRevision());
        assertEquals(original.alignment().auditHistory(), reopened.priorAuditHistory());
        final var reopenedHistory = reopened.checkpoint().auditHistory();
        assertEquals(ReviewOperation.REOPEN_PROJECT, reopenedHistory.get(reopenedHistory.size() - 1).operation());
        assertTrue(reopened.acceptedAlignment().isEmpty());
        assertFalse(reopened.canUndo());
        assertFalse(reopened.canRedo());
        assertEquals(original.displaySettings(), restored.displaySettings());
        assertEquals(original.ui(), restored.ui());
        final var originalState = new AlignmentReviewState(original.alignment().basis(), original.alignment().content(),
                original.alignment().contentRevision());
        for (final Point2D point : samples()) {
            assertSameBoundaryOutcome(() -> originalState.mapAtlasToPreview(point), () -> reopened.state().mapAtlasToPreview(point));
            assertSameBoundaryOutcome(() -> originalState.mapPreviewToAtlasCandidates(point),
                    () -> reopened.state().mapPreviewToAtlasCandidates(point));
        }
        original.alignment().content().reviewedTissueSupport().ifPresent(support -> {
            final var reopenedSupport = reopened.state().content().reviewedTissueSupport().orElseThrow();
            assertEquals(support.sha256(), reopenedSupport.sha256());
            assertEquals(support.sourceMaskSha256(), reopenedSupport.sourceMaskSha256());
            assertEquals(support.sourceMask(), reopenedSupport.sourceMask());
            assertEquals(support.controls(), reopenedSupport.controls());
            assertEquals(support.polygons(), reopenedSupport.polygons());
        });
        return restored;
    }

    private static AlignmentReviewBasis basis(final ReviewSectionMode mode) {
        final var source = new SourceImageSnapshot(new SourceImageMetadata(WIDTH, HEIGHT, 2, 2, 2, 16,
                List.of("DAPI", "GFAP"), List.of(StackPlaneLabel.fromNullable(null), StackPlaneLabel.fromNullable(""),
                        StackPlaneLabel.fromNullable("DAPI Z2"), StackPlaneLabel.fromNullable("GFAP Z2"),
                        StackPlaneLabel.fromNullable(null), StackPlaneLabel.fromNullable(""),
                        StackPlaneLabel.fromNullable("DAPI T2 Z2"), StackPlaneLabel.fromNullable("GFAP T2 Z2")),
                new CalibrationMetadata(.625, .75, 4, 2.5, "µm", "s")), "a".repeat(64));
        final var geometry = new TissueGeometryResult(mode == ReviewSectionMode.HALF
                ? SectionGeometry.IMAGE_LEFT_HALF : SectionGeometry.FULL,
                new MaskBounds(20, 20, 380, 280), 1.4, .8, .05, .05, .9, .95, 1, 1,
                mode == ReviewSectionMode.HALF ? OptionalDouble.of(199.5) : OptionalDouble.empty());
        final var similarity = new SimilarityTransform2D(CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL, 1, 0, 0, 0);
        final var atlas = new AtlasReviewProvenance(AllenCoronalLevel.ATLAS_ID, AllenCoronalLevel.ATLAS_VERSION,
                456, 320, List.of(new AtlasAssetVerification("template", 10, "1".repeat(64)),
                        new AtlasAssetVerification("annotation", 20, "2".repeat(64)),
                        new AtlasAssetVerification("ontology", 30, "3".repeat(64))));
        return new AlignmentReviewBasis(new BaselineRegistrationProposal(new AllenCoronalLevel(240), geometry,
                similarity, similarity.asAffine(), RegistrationObjectiveMode.EXACT_FULL_RESOLUTION_COMPLETE_BOUNDARY,
                .82, .88), Optional.empty(), Optional.empty(), source, atlas,
                SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS, Optional.empty(), new ReviewPreviewDimensions(WIDTH, HEIGHT));
    }

    private static AlignmentReviewContent content(final AlignmentReviewBasis basis, final ReviewSectionMode mode,
            final ManualSidePlacement2D placement, final Optional<ReviewedOutlineTransform2D> outline,
            final Optional<ManualHemisphereWarp2D> hemisphere, final List<LandmarkPair> landmarks,
            final Optional<ConstrainedLocalWarp2D> local) {
        final var initial = basis.initialContent();
        return new AlignmentReviewContent(initial.coronalLevel(), TILT, initial.workflowMode(), ORIENTATION,
                mode == ReviewSectionMode.HALF ? ObservedAnatomicalHemisphere.LEFT : ObservedAnatomicalHemisphere.BOTH,
                mode, placement, AlignmentReviewContent.identityPreviewAdjustment(), outline, outline.isPresent(),
                AlignmentReviewContent.identityPreviewAdjustment(), hemisphere, landmarks, local,
                Optional.of(tissueSupport()), true, mode == ReviewSectionMode.HALF
                        ? HalfAtlasCoverage.INCLUDE_OPPOSITE_REMNANT : HalfAtlasCoverage.VISIBLE_SIDE_ONLY);
    }

    private static ReviewedTissueSupport tissueSupport() {
        final var pixels = new BitSet(WIDTH * HEIGHT);
        for (int y = 20; y < 270; y++) pixels.set(y * WIDTH + 20, y * WIDTH + 380);
        for (int y = 100; y < 120; y++) pixels.clear(y * WIDTH + 180, y * WIDTH + 200);
        final var support = ReviewedTissueSupport.fromMask(BinaryMask.fromBitSet(WIDTH, HEIGHT, pixels));
        final var first = support.controls().get(0);
        return support.moveControl(first.id(), new Point2D(first.point().x() + .25, first.point().y() + .5));
    }

    private static ReviewProject project(final AlignmentReviewBasis basis, final AlignmentReviewContent content) {
        final var audit = List.of(new ReviewAuditSummary(1, ReviewOperation.TRANSLATE, "Saved reviewer placement", Optional.empty()),
                new ReviewAuditSummary(2, ReviewOperation.ACCEPT, "Prior acceptance requires renewal after reopening",
                        Optional.of(new ReviewAcceptanceAudit(true, true, Optional.of(basis.sourceSnapshot()),
                                Optional.of(basis.atlas()), Optional.empty()))));
        final var checkpoint = new AlignmentReviewCheckpoint(basis, basis.initialContent(), content, false, 7, audit);
        final var input = new RegistrationInput(2, 2, 1);
        final var ui = new ReviewUiState(ReviewWorkflowStage.STRUCTURE, ReviewCanvas.InteractionTool.POINTS,
                ReviewCanvas.ComparisonMode.COMPARE, new ReviewUiState.Viewport(2.25, 12.5, -8.25, 1.5, -3, 4),
                true, true, false, false, 0xff11cc44, 0xcc7722ee, 2.5, .35, false, false,
                AtlasSide.RIGHT, Optional.of(42), true);
        return new ReviewProject(new ReviewProject.SourceReference("whole-slide.ome.tif", Optional.of("/data/whole-slide.ome.tif")),
                "/verified/atlas-cache", checkpoint, input, DisplaySettings.defaults(basis.sourceSnapshot().metadata(), input),
                new ExportSelection(List.of(2), 2, 1), rois(), ui,
                Optional.of(new ParentSourceContext("whole-slide.ome.tif", "7".repeat(64), 2000, 1800, 200, 350)));
    }

    private static ReviewerRoiSession.Snapshot rois() {
        final var session = new ReviewerRoiSession("Section 3", WIDTH, HEIGHT);
        session.newPolygon("Cortex multipart", ReviewerRoiSide.LEFT, RoiPartOperation.ADD);
        session.addVertices(List.of(new Point2D(20, 20), new Point2D(150, 20), new Point2D(150, 150), new Point2D(20, 150)));
        session.finishActivePart();
        session.addPart(RoiPartOperation.SUBTRACT);
        session.addVertices(List.of(new Point2D(50, 50), new Point2D(80, 50), new Point2D(80, 80), new Point2D(50, 80)));
        session.finishActivePart();
        session.addPart(RoiPartOperation.ADD);
        session.addVertices(List.of(new Point2D(170, 20), new Point2D(200, 20), new Point2D(200, 60), new Point2D(170, 60)));
        session.finishActivePart();
        session.newPolygon("Unfinished right region", ReviewerRoiSide.RIGHT, RoiPartOperation.ADD);
        session.addVertices(List.of(new Point2D(250, 30), new Point2D(290, 30)));
        return session.snapshot();
    }

    private static List<ManualWarpControl> controls(final AtlasSide side, final double offsetX, final double dx) {
        final var controls = new ArrayList<ManualWarpControl>();
        for (final Point2D point : List.of(new Point2D(60 + offsetX, 70), new Point2D(130 + offsetX, 70),
                new Point2D(60 + offsetX, 220), new Point2D(130 + offsetX, 220))) {
            controls.add(new ManualWarpControl(side + "-" + controls.size(), side,
                    ManualWarpControlOrigin.REGULAR_INTERIOR_GRID, "grid", point,
                    new Point2D(point.x() + dx, point.y() + .5)));
        }
        return controls;
    }

    private static LandmarkPair landmark(final String id, final AlignmentReviewBasis basis,
            final Point2D atlas, final Point2D preview, final LandmarkRole role) {
        return new LandmarkPair(id, basis.proposal().coronalLevel(), TILT, atlas, preview, role);
    }

    private static AffineTransform2D translation(final double dx, final double dy) {
        return new AffineTransform2D(CoordinateSpace2D.PREVIEW_PIXEL, CoordinateSpace2D.PREVIEW_PIXEL, 1, 0, dx, 0, 1, dy);
    }

    private static ReviewAcceptanceVerification verification(final AlignmentReviewBasis basis) {
        return new ReviewAcceptanceVerification(basis.sourceSnapshot(), basis.atlas());
    }

    private static List<Point2D> samples() {
        final var result = new ArrayList<Point2D>();
        for (int y = 0; y < 7; y++) for (int x = 0; x < 8; x++) {
            result.add(new Point2D(1.25 + x * 53.1, .375 + y * 46.7));
        }
        return result;
    }

    private static <T> void assertSameBoundaryOutcome(final Supplier<T> original, final Supplier<T> restored) {
        final T expected;
        try {
            expected = original.get();
        } catch (final BoundaryGeometryException outside) {
            final var replay = assertThrows(BoundaryGeometryException.class, restored::get);
            assertEquals(outside.getMessage(), replay.getMessage());
            return;
        }
        assertEquals(expected, restored.get());
    }
}
