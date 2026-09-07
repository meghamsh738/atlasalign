package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.junit.jupiter.api.Test;

class GuidedManualTissueGeometryTest {

    @Test
    void fullRequiresCompletedTissueOutline() {
        assertThrows(IllegalStateException.class, () -> attach(
                SectionGeometry.FULL, tissue(ManualContourKind.VISIBLE_TISSUE_BOUNDARY,
                        "visible", ContourCaptureStatus.COMPLETE)));
        assertDoesNotThrow(() -> attach(SectionGeometry.FULL,
                tissue(ManualContourKind.TISSUE_OUTLINE, "outline",
                        ContourCaptureStatus.COMPLETE)));
    }

    @Test
    void eitherHalfRequiresCompletedVisibleBoundaryAndMidline() {
        for (final SectionGeometry geometry : List.of(
                SectionGeometry.IMAGE_LEFT_HALF,
                SectionGeometry.IMAGE_RIGHT_HALF)) {
            assertThrows(IllegalStateException.class, () -> attach(geometry,
                    tissue(ManualContourKind.VISIBLE_TISSUE_BOUNDARY,
                            "visible", ContourCaptureStatus.COMPLETE)));
            assertThrows(IllegalStateException.class, () -> attach(geometry,
                    tissue(ManualContourKind.MIDLINE, "midline",
                            ContourCaptureStatus.COMPLETE)));
            assertDoesNotThrow(() -> attach(geometry,
                    tissue(ManualContourKind.VISIBLE_TISSUE_BOUNDARY,
                            "visible", ContourCaptureStatus.COMPLETE),
                    tissue(ManualContourKind.MIDLINE, "midline",
                            ContourCaptureStatus.COMPLETE)));
        }
    }

    @Test
    void partialRejectsLoneMidlineAndAcceptsBoundaryOrOutline() {
        assertThrows(IllegalStateException.class, () -> attach(
                SectionGeometry.PARTIAL_OR_DAMAGED,
                tissue(ManualContourKind.MIDLINE, "midline",
                        ContourCaptureStatus.COMPLETE)));
        assertDoesNotThrow(() -> attach(SectionGeometry.PARTIAL_OR_DAMAGED,
                tissue(ManualContourKind.VISIBLE_TISSUE_BOUNDARY, "visible",
                        ContourCaptureStatus.COMPLETE)));
        assertDoesNotThrow(() -> attach(SectionGeometry.PARTIAL_OR_DAMAGED,
                tissue(ManualContourKind.TISSUE_OUTLINE, "outline",
                        ContourCaptureStatus.COMPLETE)));
    }

    @Test
    void requiredKindsMustBeComplete() {
        assertThrows(IllegalStateException.class, () -> attach(
                SectionGeometry.FULL,
                tissue(ManualContourKind.TISSUE_OUTLINE, "draft",
                        ContourCaptureStatus.DRAFT)));
    }

    private static void attach(
            final SectionGeometry geometry,
            final ManualContour... tissueContours) {
        final GuidedManualWorkflowSession session =
                new GuidedManualWorkflowSession();
        session.apply(new ManualWorkflowEdit.SetSectionObservation(
                observation(geometry)));
        session.apply(new ManualWorkflowEdit.ChooseAnatomicalGuide(
                ManualContourContractTest.guide()));
        for (final ManualContour contour : tissueContours) {
            session.apply(new ManualWorkflowEdit.UpsertContour(contour));
        }
        session.apply(new ManualWorkflowEdit.UpsertContour(structure()));
        final PreviewCandidateSpecification specification =
                new PreviewCandidateSpecification(
                        "preview", ManualContourContractTest.source(),
                        new VerifiedAtlasIdentity(
                                "Allen Mouse CCF", "2020 25um", "2".repeat(64)),
                        250, 278, -5, 5, -5, 5,
                        PreviewOnlyStatus.PREVIEW_ONLY_NOT_VALIDATED);
        session.apply(new ManualWorkflowEdit.AttachPreviewOnlyResult(
                specification, new PreviewCandidateResult(
                        specification.id(), specification.sourceIdentity(),
                        specification.atlasIdentity(), List.of(),
                        PreviewOnlyStatus.PREVIEW_ONLY_NOT_VALIDATED)));
    }

    private static SectionObservation observation(
            final SectionGeometry geometry) {
        return new SectionObservation(
                geometry, AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                geometry == SectionGeometry.FULL
                        ? ObservedAnatomicalHemisphere.BOTH
                        : ObservedAnatomicalHemisphere.LEFT,
                true, true);
    }

    private static ManualContour tissue(
            final ManualContourKind kind,
            final String id,
            final ContourCaptureStatus capture) {
        final ContourTopology topology = kind == ManualContourKind.TISSUE_OUTLINE
                ? ContourTopology.CLOSED : ContourTopology.OPEN;
        final List<ContourVertex> vertices = capture == ContourCaptureStatus.DRAFT
                ? List.of(ManualContourContractTest.vertex("a", 1, 1))
                : topology == ContourTopology.CLOSED
                        ? List.of(ManualContourContractTest.vertex("a", 1, 1),
                                ManualContourContractTest.vertex("b", 80, 1),
                                ManualContourContractTest.vertex("c", 40, 70))
                        : List.of(ManualContourContractTest.vertex("a", 1, 1),
                                ManualContourContractTest.vertex("b", 1, 70));
        return new ManualContour(
                id, kind, topology, capture, AnatomicalSide.BILATERAL,
                ContourCompleteness.COMPLETE, Optional.empty(),
                ManualContourContractTest.source(), vertices, Set.of());
    }

    private static ManualContour structure() {
        return ManualContourContractTest.contour(
                "structure", ManualContourKind.ANATOMICAL_STRUCTURE,
                ContourTopology.OPEN, ContourCaptureStatus.COMPLETE,
                List.of(ManualContourContractTest.vertex("s1", 20, 20),
                        ManualContourContractTest.vertex("s2", 30, 30)),
                Set.of());
    }
}
