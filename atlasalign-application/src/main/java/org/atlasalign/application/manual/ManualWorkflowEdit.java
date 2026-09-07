package org.atlasalign.application.manual;

import java.util.Objects;

/** One immutable scientific workflow edit; navigation is intentionally absent. */
public sealed interface ManualWorkflowEdit {

    ManualWorkflowOperation operation();

    ManualAlignmentStage stage();

    default ManualAlignmentStage affectedStage(
            final GuidedManualWorkflowContent current) {
        return stage();
    }

    String description();

    GuidedManualWorkflowContent apply(
            GuidedManualWorkflowContent current,
            GuidedManualWorkflowContent baseline);

    record SetSectionObservation(SectionObservation observation)
            implements ManualWorkflowEdit {
        public SetSectionObservation {
            observation = Objects.requireNonNull(observation, "observation");
        }
        @Override public ManualWorkflowOperation operation() {
            return ManualWorkflowOperation.SET_SECTION_OBSERVATION;
        }
        @Override public ManualAlignmentStage stage() {
            return ManualAlignmentStage.PREPARE_SECTION;
        }
        @Override public String description() {
            return "Set explicit section observation";
        }
        @Override public GuidedManualWorkflowContent apply(
                final GuidedManualWorkflowContent current,
                final GuidedManualWorkflowContent baseline) {
            return current.withSectionObservation(observation);
        }
    }

    record ChooseAnatomicalGuide(VerifiedAtlasGuideIdentity guide)
            implements ManualWorkflowEdit {
        public ChooseAnatomicalGuide {
            guide = Objects.requireNonNull(guide, "guide");
        }
        @Override public ManualWorkflowOperation operation() {
            return ManualWorkflowOperation.CHOOSE_ANATOMICAL_GUIDE;
        }
        @Override public ManualAlignmentStage stage() {
            return ManualAlignmentStage.CHOOSE_ANATOMICAL_GUIDE;
        }
        @Override public String description() {
            return "Choose verified anatomical guide " + guide.acronym();
        }
        @Override public GuidedManualWorkflowContent apply(
                final GuidedManualWorkflowContent current,
                final GuidedManualWorkflowContent baseline) {
            return current.withSelectedGuide(guide);
        }
    }

    record UpsertContour(ManualContour contour) implements ManualWorkflowEdit {
        public UpsertContour {
            contour = Objects.requireNonNull(contour, "contour");
        }
        @Override public ManualWorkflowOperation operation() {
            return ManualWorkflowOperation.UPSERT_CONTOUR;
        }
        @Override public ManualAlignmentStage stage() {
            return contour.kind() == ManualContourKind.ANATOMICAL_STRUCTURE
                    ? ManualAlignmentStage.DRAW_STRUCTURE_ON_TISSUE
                    : ManualAlignmentStage.DRAW_TISSUE_OUTLINE;
        }
        @Override public String description() {
            return "Upsert contour " + contour.id();
        }
        @Override public GuidedManualWorkflowContent apply(
                final GuidedManualWorkflowContent current,
                final GuidedManualWorkflowContent baseline) {
            return current.withContour(contour);
        }
    }

    /** Inserts one unreviewed automatic tissue-outline proposal as one revision. */
    record InsertAutomaticTissueOutline(ManualContour proposal)
            implements ManualWorkflowEdit {
        public InsertAutomaticTissueOutline {
            proposal = Objects.requireNonNull(proposal, "proposal");
            if (proposal.kind() != ManualContourKind.TISSUE_OUTLINE
                    || proposal.topology() != ContourTopology.CLOSED
                    || proposal.captureStatus() != ContourCaptureStatus.DRAFT
                    || proposal.automaticProposalProvenance().isEmpty()
                    || proposal.automaticProposalProvenance().orElseThrow()
                            .reviewerModified()) {
                throw new IllegalArgumentException(
                        "An automatic tissue-outline edit requires an unmodified draft proposal");
            }
        }
        @Override public ManualWorkflowOperation operation() {
            return ManualWorkflowOperation.INSERT_AUTOMATIC_TISSUE_OUTLINE;
        }
        @Override public ManualAlignmentStage stage() {
            return ManualAlignmentStage.DRAW_TISSUE_OUTLINE;
        }
        @Override public String description() {
            final AutomaticTissueOutlineProvenance provenance = proposal
                    .automaticProposalProvenance().orElseThrow();
            return "Insert automatic tissue-outline proposal " + proposal.id()
                    + " [algorithm=" + provenance.algorithmRevision()
                    + ", sourceSha256="
                    + provenance.sourceIdentity().pixelSha256()
                    + ", copiedPreviewSha256="
                    + provenance.copiedPreviewPixelSha256()
                    + ", inputMaskSha256=" + provenance.inputMaskSha256()
                    + ", filledEnvelopeSha256="
                    + provenance.filledEnvelopeSha256()
                    + ", outlinedEnvelopeSha256="
                    + provenance.outlinedEnvelopeSha256()
                    + ", discardedDiagonalOnlyPixelsSha256="
                    + provenance.discardedDiagonalOnlyPixelsSha256()
                    + ", includedCornerBackgroundPixelsSha256="
                    + provenance.includedCornerBackgroundPixelsSha256()
                    + ", discardedDiagonalOnlyComponents="
                    + provenance.discardedDiagonalOnlyComponentCount()
                    + ", discardedDiagonalOnlyPixels="
                    + provenance.discardedDiagonalOnlyPixelCount()
                    + ", largestDiscardedDiagonalOnlyComponentPixels="
                    + provenance
                            .largestDiscardedDiagonalOnlyComponentPixelCount()
                    + ", largestDiscardedDiagonalOnly8cSpanPixels="
                    + provenance
                            .largestDiscardedDiagonalOnlyEightConnectedSpanPixels()
                    + ", includedCornerBackgroundComponents="
                    + provenance.includedCornerBackgroundComponentCount()
                    + ", includedCornerBackgroundPixels="
                    + provenance.includedCornerBackgroundPixelCount()
                    + ", largestIncludedCornerBackgroundComponentPixels="
                    + provenance
                            .largestIncludedCornerBackgroundComponentPixelCount()
                    + ", largestIncludedCornerBackground8cSpanPixels="
                    + provenance
                            .largestIncludedCornerBackgroundEightConnectedSpanPixels()
                    + ", generatedLoopSha256="
                    + provenance.generatedLoopSha256()
                    + ", segmentationMethod="
                    + provenance.segmentationMethod()
                    + ", segmentationPolarity="
                    + provenance.segmentationPolarity()
                    + ", segmentationThreshold="
                    + Float.toHexString(provenance.segmentationThreshold())
                    + ", reviewerModified=false]";
        }
        @Override public GuidedManualWorkflowContent apply(
                final GuidedManualWorkflowContent current,
                final GuidedManualWorkflowContent baseline) {
            final SectionObservation observation = current.sectionObservation()
                    .orElseThrow(() -> new IllegalStateException(
                            "Save an explicit section observation before inserting an automatic outline proposal"));
            if (observation.geometry() != SectionGeometry.FULL) {
                throw new IllegalStateException(
                        "Automatic tissue-outline proposals require a complete FULL section");
            }
            if (current.contours().values().stream().anyMatch(contour ->
                    contour.kind() == ManualContourKind.TISSUE_OUTLINE)) {
                throw new IllegalStateException(
                        "An automatic proposal cannot replace or duplicate an existing tissue outline");
            }
            if (current.contours().containsKey(proposal.id())) {
                throw new IllegalArgumentException(
                        "A contour with this proposal ID already exists");
            }
            return current.withContour(proposal);
        }
    }

    record RemoveContour(String contourId) implements ManualWorkflowEdit {
        public RemoveContour {
            contourId = Objects.requireNonNull(contourId, "contourId").trim();
            if (contourId.isEmpty()) {
                throw new IllegalArgumentException("contourId must not be blank");
            }
        }
        @Override public ManualWorkflowOperation operation() {
            return ManualWorkflowOperation.REMOVE_CONTOUR;
        }
        @Override public ManualAlignmentStage stage() {
            return ManualAlignmentStage.DRAW_TISSUE_OUTLINE;
        }
        @Override public ManualAlignmentStage affectedStage(
                final GuidedManualWorkflowContent current) {
            final ManualContour contour = current.contours().get(contourId);
            if (contour == null) {
                throw new IllegalArgumentException("Unknown contour: " + contourId);
            }
            return contour.kind() == ManualContourKind.ANATOMICAL_STRUCTURE
                    ? ManualAlignmentStage.DRAW_STRUCTURE_ON_TISSUE
                    : ManualAlignmentStage.DRAW_TISSUE_OUTLINE;
        }
        @Override public String description() {
            return "Remove contour " + contourId;
        }
        @Override public GuidedManualWorkflowContent apply(
                final GuidedManualWorkflowContent current,
                final GuidedManualWorkflowContent baseline) {
            return current.withoutContour(contourId);
        }
    }

    record CompleteStage(ManualAlignmentStage completedStage)
            implements ManualWorkflowEdit {
        public CompleteStage {
            completedStage = Objects.requireNonNull(completedStage, "completedStage");
        }
        @Override public ManualWorkflowOperation operation() {
            return ManualWorkflowOperation.COMPLETE_STAGE;
        }
        @Override public ManualAlignmentStage stage() {
            return completedStage;
        }
        @Override public String description() {
            return "Complete stage " + completedStage;
        }
        @Override public GuidedManualWorkflowContent apply(
                final GuidedManualWorkflowContent current,
                final GuidedManualWorkflowContent baseline) {
            return current.withCompletedStage(completedStage);
        }
    }

    record AttachPreviewOnlyResult(
            PreviewCandidateSpecification specification,
            PreviewCandidateResult result) implements ManualWorkflowEdit {
        public AttachPreviewOnlyResult {
            specification = Objects.requireNonNull(specification, "specification");
            result = Objects.requireNonNull(result, "result");
        }
        @Override public ManualWorkflowOperation operation() {
            return ManualWorkflowOperation.ATTACH_PREVIEW_ONLY_RESULT;
        }
        @Override public ManualAlignmentStage stage() {
            return ManualAlignmentStage.PREVIEW_CANDIDATES;
        }
        @Override public String description() {
            return "Attach non-validated candidate preview";
        }
        @Override public GuidedManualWorkflowContent apply(
                final GuidedManualWorkflowContent current,
                final GuidedManualWorkflowContent baseline) {
            return current.withPreview(specification, result);
        }
    }

    record ResetStage(ManualAlignmentStage resetStage)
            implements ManualWorkflowEdit {
        public ResetStage {
            resetStage = Objects.requireNonNull(resetStage, "resetStage");
        }
        @Override public ManualWorkflowOperation operation() {
            return ManualWorkflowOperation.RESET_STAGE;
        }
        @Override public ManualAlignmentStage stage() {
            return resetStage;
        }
        @Override public String description() {
            return "Reset stage " + resetStage;
        }
        @Override public GuidedManualWorkflowContent apply(
                final GuidedManualWorkflowContent current,
                final GuidedManualWorkflowContent baseline) {
            return current.resetStage(resetStage, baseline);
        }
    }

    record ResetAll() implements ManualWorkflowEdit {
        @Override public ManualWorkflowOperation operation() {
            return ManualWorkflowOperation.RESET_ALL;
        }
        @Override public ManualAlignmentStage stage() {
            return ManualAlignmentStage.PREPARE_SECTION;
        }
        @Override public String description() {
            return "Reset all guided-manual workflow content";
        }
        @Override public GuidedManualWorkflowContent apply(
                final GuidedManualWorkflowContent current,
                final GuidedManualWorkflowContent baseline) {
            return baseline;
        }
    }
}
