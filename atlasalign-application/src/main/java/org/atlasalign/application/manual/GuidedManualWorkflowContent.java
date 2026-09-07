package org.atlasalign.application.manual;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable in-memory scientific content for the guided-manual wizard. */
public record GuidedManualWorkflowContent(
        Optional<SectionObservation> sectionObservation,
        Optional<VerifiedAtlasGuideIdentity> selectedGuide,
        Map<String, ManualContour> contours,
        Optional<PreviewCandidateSpecification> previewSpecification,
        Optional<PreviewCandidateResult> previewResult,
        Set<ManualAlignmentStage> completedStages,
        Map<ManualAlignmentStage, ArtifactStatus> artifactStatuses) {

    public GuidedManualWorkflowContent {
        sectionObservation = Objects.requireNonNull(
                sectionObservation, "sectionObservation");
        selectedGuide = Objects.requireNonNull(selectedGuide, "selectedGuide");
        contours = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(
                contours, "contours")));
        previewSpecification = Objects.requireNonNull(
                previewSpecification, "previewSpecification");
        previewResult = Objects.requireNonNull(previewResult, "previewResult");
        completedStages = Set.copyOf(Objects.requireNonNull(
                completedStages, "completedStages"));
        final Map<ManualAlignmentStage, ArtifactStatus> copiedStatuses =
                new EnumMap<>(ManualAlignmentStage.class);
        copiedStatuses.putAll(Objects.requireNonNull(
                artifactStatuses, "artifactStatuses"));
        artifactStatuses = Map.copyOf(copiedStatuses);
        contours.forEach((id, contour) -> {
            if (id == null || contour == null || !id.equals(contour.id())) {
                throw new IllegalArgumentException(
                        "Contour map keys must equal non-null contour IDs");
            }
        });
        contours.values().stream().map(ManualContour::sourceIdentity)
                .distinct().skip(1).findAny().ifPresent(ignored -> {
                    throw new IllegalArgumentException(
                            "All contours must belong to one exact source identity");
                });
        if (previewResult.isPresent()) {
            if (previewSpecification.isEmpty()) {
                throw new IllegalArgumentException(
                        "A preview result requires its specification");
            }
            validatePreviewPair(previewSpecification.orElseThrow(),
                    previewResult.orElseThrow());
        }
    }

    public static GuidedManualWorkflowContent empty() {
        return new GuidedManualWorkflowContent(
                Optional.empty(), Optional.empty(), Map.of(),
                Optional.empty(), Optional.empty(), Set.of(), Map.of());
    }

    GuidedManualWorkflowContent withSectionObservation(
            final SectionObservation observation) {
        return changed(ManualAlignmentStage.PREPARE_SECTION,
                Optional.of(observation), selectedGuide, contours,
                previewSpecification, previewResult, completedStages);
    }

    GuidedManualWorkflowContent withSelectedGuide(
            final VerifiedAtlasGuideIdentity guide) {
        return changed(ManualAlignmentStage.CHOOSE_ANATOMICAL_GUIDE,
                sectionObservation, Optional.of(guide), contours,
                previewSpecification, previewResult, completedStages);
    }

    GuidedManualWorkflowContent withContour(final ManualContour contour) {
        if (contour.kind() == ManualContourKind.ANATOMICAL_STRUCTURE) {
            final ManualContour existing = contours.get(contour.id());
            if (existing == null && (selectedGuide.isEmpty()
                    || !selectedGuide.orElseThrow().equals(
                            contour.atlasGuide().orElseThrow()))) {
                throw new IllegalArgumentException(
                        "A new anatomical contour must match the selected exact guide");
            }
            if (existing != null && !existing.atlasGuide().equals(
                    contour.atlasGuide())) {
                throw new IllegalArgumentException(
                        "A contour's exact atlas guide identity cannot change");
            }
        }
        final Map<String, ManualContour> changed = new LinkedHashMap<>(contours);
        changed.put(contour.id(), contour);
        return changed(stageFor(contour), sectionObservation, selectedGuide,
                changed, previewSpecification, previewResult, completedStages);
    }

    GuidedManualWorkflowContent withoutContour(final String contourId) {
        final ManualContour existing = contours.get(contourId);
        if (existing == null) {
            throw new IllegalArgumentException("Unknown contour: " + contourId);
        }
        final Map<String, ManualContour> changed = new LinkedHashMap<>(contours);
        changed.remove(contourId);
        return changed(stageFor(existing), sectionObservation, selectedGuide,
                changed, previewSpecification, previewResult, completedStages);
    }

    GuidedManualWorkflowContent withCompletedStage(
            final ManualAlignmentStage stage) {
        final Set<ManualAlignmentStage> completed = EnumSet.noneOf(
                ManualAlignmentStage.class);
        completed.addAll(completedStages);
        completed.add(stage);
        return changed(stage, sectionObservation, selectedGuide, contours,
                previewSpecification, previewResult, completed);
    }

    GuidedManualWorkflowContent withPreview(
            final PreviewCandidateSpecification specification,
            final PreviewCandidateResult result) {
        validatePreviewPair(specification, result);
        if (sectionObservation.isEmpty()) {
            throw new IllegalStateException(
                    "An explicit section observation is required for preview");
        }
        if (selectedGuide.isEmpty()) {
            throw new IllegalStateException(
                    "A verified anatomical guide is required for preview");
        }
        requireGeometrySpecificTissueContours();
        if (contours.values().stream().noneMatch(contour ->
                contour.kind() == ManualContourKind.ANATOMICAL_STRUCTURE)) {
            throw new IllegalStateException(
                    "A completed anatomical contour is required for preview");
        }
        if (contours.values().stream().anyMatch(contour ->
                !contour.eligibleForPreview())) {
            throw new IllegalStateException(
                    "Draft contours cannot feed a candidate preview");
        }
        if (contours.values().stream().anyMatch(contour ->
                !contour.sourceIdentity().equals(specification.sourceIdentity()))) {
            throw new IllegalArgumentException(
                    "Preview specification must match the exact contour source");
        }
        if (contours.values().stream()
                .flatMap(contour -> contour.atlasGuide().stream())
                .anyMatch(guide -> !guide.atlasIdentitySha256().equals(
                        specification.atlasIdentity().identitySha256()))) {
            throw new IllegalArgumentException(
                    "Preview specification must match every verified contour guide atlas");
        }
        if (selectedGuide.isPresent()
                && !selectedGuide.orElseThrow().atlasIdentitySha256().equals(
                        specification.atlasIdentity().identitySha256())) {
            throw new IllegalArgumentException(
                    "Preview specification must match the selected guide atlas");
        }
        return changed(ManualAlignmentStage.PREVIEW_CANDIDATES,
                sectionObservation, selectedGuide, contours,
                Optional.of(specification), Optional.of(result), completedStages);
    }

    /**
     * Fails closed unless the completed tissue evidence matches the explicitly
     * recorded image-side section geometry.
     */
    public void requireGeometrySpecificTissueContours() {
        final SectionObservation observation = sectionObservation.orElseThrow(
                () -> new IllegalStateException(
                        "An explicit section observation is required"));
        final boolean outline = hasCompleted(ManualContourKind.TISSUE_OUTLINE);
        final boolean visibleBoundary = hasCompleted(
                ManualContourKind.VISIBLE_TISSUE_BOUNDARY);
        final boolean midline = hasCompleted(ManualContourKind.MIDLINE);
        switch (observation.geometry()) {
            case FULL -> {
                if (!outline) {
                    throw new IllegalStateException(
                            "A full section requires a completed TISSUE_OUTLINE");
                }
            }
            case IMAGE_LEFT_HALF, IMAGE_RIGHT_HALF -> {
                if (!visibleBoundary || !midline) {
                    throw new IllegalStateException(
                            "A half section requires completed VISIBLE_TISSUE_BOUNDARY and MIDLINE contours");
                }
            }
            case PARTIAL_OR_DAMAGED -> {
                if (!visibleBoundary && !outline) {
                    throw new IllegalStateException(
                            "A partial or damaged section requires a completed VISIBLE_TISSUE_BOUNDARY or TISSUE_OUTLINE; MIDLINE alone is insufficient");
                }
            }
        }
    }

    private boolean hasCompleted(final ManualContourKind kind) {
        return contours.values().stream().anyMatch(contour ->
                contour.kind() == kind && contour.eligibleForPreview());
    }

    GuidedManualWorkflowContent resetStage(
            final ManualAlignmentStage stage,
            final GuidedManualWorkflowContent baseline) {
        Optional<SectionObservation> observation = sectionObservation;
        Optional<VerifiedAtlasGuideIdentity> guide = selectedGuide;
        Map<String, ManualContour> keptContours = contours;
        Optional<PreviewCandidateSpecification> specification = previewSpecification;
        Optional<PreviewCandidateResult> result = previewResult;
        final Set<ManualAlignmentStage> completed = EnumSet.noneOf(
                ManualAlignmentStage.class);
        completed.addAll(completedStages);
        completed.remove(stage);
        switch (stage) {
            case PREPARE_SECTION -> observation = baseline.sectionObservation;
            case DRAW_TISSUE_OUTLINE -> keptContours = replaceContoursForStage(
                    contours, baseline.contours, stage);
            case CHOOSE_ANATOMICAL_GUIDE -> guide = baseline.selectedGuide;
            case DRAW_STRUCTURE_ON_TISSUE -> keptContours = replaceContoursForStage(
                    contours, baseline.contours, stage);
            case PREVIEW_CANDIDATES -> {
                specification = baseline.previewSpecification;
                result = baseline.previewResult;
            }
            default -> { /* These stages currently own only completion state. */ }
        }
        return changed(stage, observation, guide, keptContours,
                specification, result, completed);
    }

    private GuidedManualWorkflowContent changed(
            final ManualAlignmentStage changedStage,
            final Optional<SectionObservation> observation,
            final Optional<VerifiedAtlasGuideIdentity> guide,
            final Map<String, ManualContour> updatedContours,
            final Optional<PreviewCandidateSpecification> specification,
            final Optional<PreviewCandidateResult> result,
            final Set<ManualAlignmentStage> completed) {
        final Map<ManualAlignmentStage, ArtifactStatus> statuses =
                new EnumMap<>(ManualAlignmentStage.class);
        statuses.putAll(artifactStatuses);
        statuses.put(changedStage, ArtifactStatus.CURRENT);
        for (final ManualAlignmentStage stage : ManualAlignmentStage.values()) {
            if (stage.ordinal() > changedStage.ordinal()
                    && (statuses.containsKey(stage)
                    || completedStages.contains(stage)
                    || hasArtifact(stage))) {
                statuses.put(stage, ArtifactStatus.STALE);
            }
        }
        return new GuidedManualWorkflowContent(
                observation, guide, updatedContours, specification, result,
                completed, statuses);
    }

    private boolean hasArtifact(final ManualAlignmentStage stage) {
        return switch (stage) {
            case PREPARE_SECTION -> sectionObservation.isPresent();
            case DRAW_TISSUE_OUTLINE -> contours.values().stream()
                    .anyMatch(c -> stageFor(c) == stage);
            case CHOOSE_ANATOMICAL_GUIDE -> selectedGuide.isPresent();
            case DRAW_STRUCTURE_ON_TISSUE -> contours.values().stream()
                    .anyMatch(c -> stageFor(c) == stage);
            case PREVIEW_CANDIDATES -> previewSpecification.isPresent()
                    || previewResult.isPresent();
            default -> completedStages.contains(stage);
        };
    }

    private static ManualAlignmentStage stageFor(final ManualContour contour) {
        return contour.kind() == ManualContourKind.ANATOMICAL_STRUCTURE
                ? ManualAlignmentStage.DRAW_STRUCTURE_ON_TISSUE
                : ManualAlignmentStage.DRAW_TISSUE_OUTLINE;
    }

    private static Map<String, ManualContour> replaceContoursForStage(
            final Map<String, ManualContour> current,
            final Map<String, ManualContour> baseline,
            final ManualAlignmentStage stage) {
        final Map<String, ManualContour> result = new LinkedHashMap<>();
        current.values().stream().filter(c -> stageFor(c) != stage)
                .forEach(c -> result.put(c.id(), c));
        baseline.values().stream().filter(c -> stageFor(c) == stage)
                .forEach(c -> result.put(c.id(), c));
        return result;
    }

    private static void validatePreviewPair(
            final PreviewCandidateSpecification specification,
            final PreviewCandidateResult result) {
        if (!specification.id().equals(result.specificationId())
                || !specification.sourceIdentity().equals(result.sourceIdentity())
                || !specification.atlasIdentity().equals(result.atlasIdentity())) {
            throw new IllegalArgumentException(
                    "Preview result identities must exactly match its specification");
        }
        for (final PreviewCandidateDescriptor candidate : result.candidates()) {
            if (candidate.allenAxis0Index() < specification.inclusiveApStart()
                    || candidate.allenAxis0Index() > specification.inclusiveApEnd()
                    || candidate.sagittalTiltDegrees()
                    < specification.sagittalTiltMinimumDegrees()
                    || candidate.sagittalTiltDegrees()
                    > specification.sagittalTiltMaximumDegrees()
                    || candidate.horizontalTiltDegrees()
                    < specification.horizontalTiltMinimumDegrees()
                    || candidate.horizontalTiltDegrees()
                    > specification.horizontalTiltMaximumDegrees()) {
                throw new IllegalArgumentException(
                        "Preview candidate lies outside requested AP/tilt bounds");
            }
        }
    }
}
