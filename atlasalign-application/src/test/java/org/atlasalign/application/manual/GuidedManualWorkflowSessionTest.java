package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.junit.jupiter.api.Test;

class GuidedManualWorkflowSessionTest {

    @Test
    void navigationNeverCreatesScientificRevision() {
        final GuidedManualWorkflowSession session = new GuidedManualWorkflowSession();

        assertTrue(session.next());
        assertTrue(session.next());
        assertTrue(session.back());
        session.showStage(ManualAlignmentStage.REVIEW_AND_ACCEPT);

        assertEquals(1, session.visibleHistory().size());
        assertEquals(0, session.currentRevision().id());
    }

    @Test
    void editingAfterUndoPreservesBothFutureBranches() {
        final GuidedManualWorkflowSession session = new GuidedManualWorkflowSession();
        final long observation = session.apply(
                new ManualWorkflowEdit.SetSectionObservation(observation())).id();
        final long oldFuture = session.apply(
                new ManualWorkflowEdit.ChooseAnatomicalGuide(
                        ManualContourContractTest.guide())).id();
        assertTrue(session.undo());
        final long newFuture = session.apply(new ManualWorkflowEdit.UpsertContour(
                tissueOutline("outline-a"))).id();

        session.checkout(observation);
        assertEquals(List.of(oldFuture, newFuture), session.redoChoices().stream()
                .map(ManualWorkflowRevision::id).toList());
        session.redo(oldFuture);
        assertTrue(session.content().selectedGuide().isPresent());
        session.checkout(observation);
        session.redo(newFuture);
        assertTrue(session.content().contours().containsKey("outline-a"));
    }

    @Test
    void earlierEditPreservesButMarksDownstreamArtifactsStale() {
        final GuidedManualWorkflowSession session = preparedThroughPreview();
        final PreviewCandidateResult result = session.content().previewResult().orElseThrow();

        session.apply(new ManualWorkflowEdit.UpsertContour(tissueOutline("revised")));

        assertEquals(result, session.content().previewResult().orElseThrow());
        assertEquals(ArtifactStatus.STALE,
                session.content().artifactStatuses().get(
                        ManualAlignmentStage.PREVIEW_CANDIDATES));
    }

    @Test
    void resetCurrentStepAndResetAllAreSingleRevisions() {
        final GuidedManualWorkflowSession session = preparedThroughPreview();
        final int beforeStepReset = session.visibleHistory().size();
        session.showStage(ManualAlignmentStage.DRAW_TISSUE_OUTLINE);

        session.resetCurrentStep();

        assertEquals(beforeStepReset + 1, session.visibleHistory().size());
        assertTrue(session.content().contours().values().stream()
                .noneMatch(contour -> contour.kind()
                        != ManualContourKind.ANATOMICAL_STRUCTURE));
        assertTrue(session.content().contours().values().stream()
                .anyMatch(contour -> contour.kind()
                        == ManualContourKind.ANATOMICAL_STRUCTURE));
        assertTrue(session.content().previewResult().isPresent());
        assertEquals(ArtifactStatus.STALE,
                session.content().artifactStatuses().get(
                        ManualAlignmentStage.PREVIEW_CANDIDATES));
        final int beforeAllReset = session.visibleHistory().size();

        session.resetAll();

        assertEquals(beforeAllReset + 1, session.visibleHistory().size());
        assertEquals(GuidedManualWorkflowContent.empty(), session.content());
        assertTrue(session.undo());
        assertTrue(session.content().previewResult().isPresent());
    }

    @Test
    void historyAndContentCollectionsAreImmutableAndDeterministic() {
        final GuidedManualWorkflowSession session = new GuidedManualWorkflowSession();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(observation()));
        session.apply(new ManualWorkflowEdit.UpsertContour(tissueOutline("outline-a")));

        assertEquals(List.of(0L, 1L, 2L), session.visibleHistory().stream()
                .map(ManualWorkflowRevision::id).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> session.visibleHistory().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> session.content().contours().clear());
    }

    @Test
    void previewIsNonPromotableAndRejectsDraftOrOutOfBoundsCandidate() {
        final PreviewCandidateSpecification specification = specification();
        final PreviewCandidateResult result = result(specification,
                List.of(new PreviewCandidateDescriptor("c1", 264, 0, 0)));

        assertEquals(PreviewOnlyStatus.PREVIEW_ONLY_NOT_VALIDATED, result.status());
        assertFalse(result.promotable());
        assertThrows(IllegalArgumentException.class,
                () -> new PreviewCandidateSpecification(
                        "bad", ManualContourContractTest.source(), atlas(),
                        0, 528, -1, 1, -1, 1,
                        PreviewOnlyStatus.PREVIEW_ONLY_NOT_VALIDATED));
        assertThrows(IllegalArgumentException.class,
                () -> new PreviewCandidateDescriptor("bad", 528, 0, 0));
        assertTrue(Arrays.stream(PreviewCandidateResult.class.getRecordComponents())
                .noneMatch(component -> component.getName().matches(
                        ".*(confidence|probability|accepted|promoted).*")));

        final GuidedManualWorkflowSession draftSession =
                new GuidedManualWorkflowSession();
        draftSession.apply(new ManualWorkflowEdit.UpsertContour(
                ManualContourContractTest.contour(
                        "draft", ManualContourKind.VISIBLE_TISSUE_BOUNDARY,
                        ContourTopology.OPEN, ContourCaptureStatus.DRAFT,
                        List.of(ManualContourContractTest.vertex("a", 1, 1)),
                        Set.of())));
        assertThrows(IllegalStateException.class,
                () -> draftSession.apply(
                        new ManualWorkflowEdit.AttachPreviewOnlyResult(
                                specification, result)));

        final PreviewCandidateResult outside = result(specification,
                List.of(new PreviewCandidateDescriptor("outside", 300, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new GuidedManualWorkflowSession().apply(
                        new ManualWorkflowEdit.AttachPreviewOnlyResult(
                                specification, outside)));

        final PreviewCandidateSpecification wrongAtlas =
                new PreviewCandidateSpecification(
                        "wrong-atlas", ManualContourContractTest.source(),
                        new VerifiedAtlasIdentity(
                                "Allen Mouse CCF", "other", "9".repeat(64)),
                        250, 278, -5, 5, -5, 5,
                        PreviewOnlyStatus.PREVIEW_ONLY_NOT_VALIDATED);
        final PreviewCandidateResult wrongAtlasResult = result(
                wrongAtlas, List.of());
        final GuidedManualWorkflowSession complete = preparedInputs();
        assertThrows(IllegalArgumentException.class,
                () -> complete.apply(
                        new ManualWorkflowEdit.AttachPreviewOnlyResult(
                                wrongAtlas, wrongAtlasResult)));
    }

    @Test
    void everyEditCreatesExactlyOneRevision() {
        final GuidedManualWorkflowSession session = new GuidedManualWorkflowSession();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(observation()));
        session.apply(new ManualWorkflowEdit.UpsertContour(tissueOutline("outline-a")));
        session.apply(new ManualWorkflowEdit.CompleteStage(
                ManualAlignmentStage.DRAW_TISSUE_OUTLINE));
        session.apply(new ManualWorkflowEdit.RemoveContour("outline-a"));
        assertEquals(5, session.visibleHistory().size());
    }

    @Test
    void structureContoursKeepExactGuideIdentityAcrossMultipleGuides() {
        final GuidedManualWorkflowSession session =
                new GuidedManualWorkflowSession();
        assertThrows(IllegalArgumentException.class,
                () -> session.apply(new ManualWorkflowEdit.UpsertContour(
                        structureContour())));
        session.apply(new ManualWorkflowEdit.ChooseAnatomicalGuide(
                ManualContourContractTest.guide()));
        session.apply(new ManualWorkflowEdit.UpsertContour(structureContour()));
        final VerifiedAtlasGuideIdentity other =
                new VerifiedAtlasGuideIdentity(
                        "2".repeat(64), "Allen CCF", "2020", 726, "DG");
        session.apply(new ManualWorkflowEdit.ChooseAnatomicalGuide(other));
        session.apply(new ManualWorkflowEdit.UpsertContour(
                new ManualContour(
                        "dg-a", ManualContourKind.ANATOMICAL_STRUCTURE,
                        ContourTopology.OPEN, ContourCaptureStatus.COMPLETE,
                        AnatomicalSide.LEFT, ContourCompleteness.COMPLETE,
                        Optional.of(other), ManualContourContractTest.source(),
                        List.of(
                                ManualContourContractTest.vertex("x", 20, 20),
                                ManualContourContractTest.vertex("y", 40, 30)),
                        Set.of())));
        assertEquals(Set.of("DG-sg", "DG"), session.content().contours()
                .values().stream().flatMap(contour -> contour.atlasGuide().stream())
                .map(VerifiedAtlasGuideIdentity::acronym)
                .collect(java.util.stream.Collectors.toSet()));
    }

    private static GuidedManualWorkflowSession preparedThroughPreview() {
        final GuidedManualWorkflowSession session = preparedInputs();
        final PreviewCandidateSpecification specification = specification();
        session.apply(new ManualWorkflowEdit.AttachPreviewOnlyResult(
                specification, result(specification,
                        List.of(new PreviewCandidateDescriptor("c1", 264, 0, 0)))));
        return session;
    }

    private static GuidedManualWorkflowSession preparedInputs() {
        final GuidedManualWorkflowSession session = new GuidedManualWorkflowSession();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(observation()));
        session.apply(new ManualWorkflowEdit.ChooseAnatomicalGuide(
                ManualContourContractTest.guide()));
        session.apply(new ManualWorkflowEdit.UpsertContour(tissueOutline("outline-a")));
        session.apply(new ManualWorkflowEdit.UpsertContour(structureContour()));
        return session;
    }

    private static SectionObservation observation() {
        return new SectionObservation(
                SectionGeometry.FULL,
                AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                ObservedAnatomicalHemisphere.BOTH, true, true);
    }

    private static ManualContour tissueOutline(final String id) {
        return ManualContourContractTest.contour(
                id, ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.COMPLETE,
                List.of(
                        ManualContourContractTest.vertex("a", 1, 1),
                        ManualContourContractTest.vertex("b", 80, 1),
                        ManualContourContractTest.vertex("c", 40, 70)),
                Set.of());
    }

    private static ManualContour structureContour() {
        return ManualContourContractTest.contour(
                "dg-sg-a", ManualContourKind.ANATOMICAL_STRUCTURE,
                ContourTopology.OPEN, ContourCaptureStatus.COMPLETE,
                List.of(
                        ManualContourContractTest.vertex("dg-a", 20, 25),
                        ManualContourContractTest.vertex("dg-b", 50, 35)),
                Set.of());
    }

    private static PreviewCandidateSpecification specification() {
        return new PreviewCandidateSpecification(
                "preview-1", ManualContourContractTest.source(), atlas(),
                250, 278, -5, 5, -5, 5,
                PreviewOnlyStatus.PREVIEW_ONLY_NOT_VALIDATED);
    }

    private static PreviewCandidateResult result(
            final PreviewCandidateSpecification specification,
            final List<PreviewCandidateDescriptor> candidates) {
        return new PreviewCandidateResult(
                specification.id(), specification.sourceIdentity(),
                specification.atlasIdentity(), candidates,
                PreviewOnlyStatus.PREVIEW_ONLY_NOT_VALIDATED);
    }

    private static VerifiedAtlasIdentity atlas() {
        return new VerifiedAtlasIdentity(
                "Allen Mouse CCF", "2020 25um", "2".repeat(64));
    }
}
