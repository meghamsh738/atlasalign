package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class AlignmentReviewBasisProvenanceTest {

    @Test
    void oldConstructorRemainsCompatibleWhenNoInferenceProvenanceExists() {
        final AlignmentReviewBasis basis = ReviewTestFixtures.basis(
                SectionGeometry.FULL);

        assertTrue(basis.inferencePreparationProvenance().isEmpty());
    }

    @Test
    void syntheticPixelPolicyAndPreparationProvenanceMustAgree() {
        final AlignmentReviewBasis base = ReviewTestFixtures.basis(
                SectionGeometry.IMAGE_LEFT_HALF);
        final VirtualHalfPreparationProvenance provenance = provenance(
                SectionGeometry.IMAGE_LEFT_HALF);

        assertThrows(IllegalArgumentException.class, () -> basis(
                base,
                SyntheticPixelReviewPolicy.SYNTHETIC_PIXELS_EXCLUDED,
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> basis(
                base,
                SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                Optional.of(provenance)));

        final AlignmentReviewBasis valid = basis(
                base,
                SyntheticPixelReviewPolicy.SYNTHETIC_PIXELS_EXCLUDED,
                Optional.of(provenance));
        assertEquals(provenance,
                valid.inferencePreparationProvenance().orElseThrow());
    }

    @Test
    void preparationGeometryMustMatchTheReviewGeometry() {
        final AlignmentReviewBasis base = ReviewTestFixtures.basis(
                SectionGeometry.IMAGE_LEFT_HALF);

        assertThrows(IllegalArgumentException.class, () -> basis(
                base,
                SyntheticPixelReviewPolicy.SYNTHETIC_PIXELS_EXCLUDED,
                Optional.of(provenance(SectionGeometry.IMAGE_RIGHT_HALF))));
    }

    private static AlignmentReviewBasis basis(
            final AlignmentReviewBasis base,
            final SyntheticPixelReviewPolicy policy,
            final Optional<VirtualHalfPreparationProvenance> provenance) {
        return new AlignmentReviewBasis(
                base.proposal(),
                base.initialPlaneProposal(),
                base.segmentation(),
                base.sourceSnapshot(),
                base.atlas(),
                policy,
                provenance,
                base.previewDimensions());
    }

    private static VirtualHalfPreparationProvenance provenance(
            final SectionGeometry geometry) {
        return new VirtualHalfPreparationProvenance(
                "virtual-half-r1",
                VirtualHalfMode.FULL_CANVAS,
                "0".repeat(64),
                "1".repeat(64),
                "2".repeat(64),
                "3".repeat(64),
                2, 2, 2, 2, 0, 0, 0, 0,
                VirtualHalfBackgroundStrategy.NOT_USED,
                Optional.empty(), 0, geometry);
    }
}
