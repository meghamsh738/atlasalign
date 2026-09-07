package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class TissueGeometryClassifierTest {

    private final TissueGeometryClassifier classifier =
            new TissueGeometryClassifier();

    @Test
    void distinguishesFullAndImageSideHalfSections() {
        final TissueGeometryResult full =
                classifier.classify(SyntheticMasks.fullSection());
        final TissueGeometryResult left =
                classifier.classify(SyntheticMasks.imageLeftHalf());
        final TissueGeometryResult right =
                classifier.classify(SyntheticMasks.imageRightHalf());

        assertEquals(SectionGeometry.FULL, full.geometry());
        assertFalse(full.medialEdgeX().isPresent());
        assertEquals(SectionGeometry.IMAGE_LEFT_HALF, left.geometry());
        assertTrue(left.medialEdgeX().isPresent());
        assertEquals(SectionGeometry.IMAGE_RIGHT_HALF, right.geometry());
        assertTrue(right.medialEdgeX().isPresent());
        assertTrue(left.anatomicalLateralityRequiresConfirmation());
        assertTrue(right.anatomicalLateralityRequiresConfirmation());
    }

    @Test
    void leavesDamagedTissueForHumanReview() {
        final TissueGeometryResult result =
                classifier.classify(SyntheticMasks.damagedSection());

        assertEquals(
                SectionGeometry.PARTIAL_OR_DAMAGED,
                result.geometry());
    }

    @Test
    void keepsSparseBilateralSectionUnderExplicitReview() {
        final TissueGeometryResult result = classifier.classify(
                SyntheticMasks.fullSectionWithBilateralSignalVoids());

        assertEquals(
                SectionGeometry.BILATERAL_REVIEW_REQUIRED,
                result.geometry());
        assertTrue(result.foregroundToBoundsFraction() < 0.62);
        assertTrue(result.anatomicalLateralityRequiresConfirmation());
        assertFalse(result.medialEdgeX().isPresent());
    }

    @Test
    void doesNotTreatUnilateralSignalLossAsComplete() {
        final TissueGeometryResult result = classifier.classify(
                SyntheticMasks.sectionWithUnilateralSignalLoss());

        assertEquals(
                SectionGeometry.PARTIAL_OR_DAMAGED,
                result.geometry());
    }

    @Test
    void requiresAspectAtLeastOnePointTwoEightForFullSection() {
        assertEquals(
                SectionGeometry.PARTIAL_OR_DAMAGED,
                classifier.classify(
                        SyntheticMasks.denseWavySection(127, 5))
                        .geometry());
        assertEquals(
                SectionGeometry.FULL,
                classifier.classify(
                        SyntheticMasks.denseWavySection(128, 5))
                        .geometry());
        assertEquals(
                SectionGeometry.FULL,
                classifier.classify(
                        SyntheticMasks.denseWavySection(129, 5))
                        .geometry());
    }

    @Test
    void acceptsHighConfidenceBilateralGeometryBelowEdgeThreshold() {
        final TissueGeometryResult below = classifier.classify(
                SyntheticMasks.denseWavySection(128, 4));
        final TissueGeometryResult above = classifier.classify(
                SyntheticMasks.denseWavySection(128, 5));

        assertTrue(below.imageLeftEdgeDispersion() < 0.035);
        assertTrue(below.imageRightEdgeDispersion() < 0.035);
        assertTrue(below.foregroundToBoundsFraction()
                >= 0.6538691251067112);
        assertTrue(below.bilateralMirroredOverlap()
                >= 0.7901669791805024);
        assertEquals(SectionGeometry.FULL, below.geometry());
        assertTrue(above.imageLeftEdgeDispersion() > 0.035);
        assertTrue(above.imageRightEdgeDispersion() > 0.035);
        assertEquals(SectionGeometry.FULL, above.geometry());
    }

    @Test
    void acceptsHighConfidenceFullSectionWithOneFlatterOuterEdge() {
        final TissueGeometryResult result = classifier.classify(
                SyntheticMasks.denseAsymmetricEdgeSection());

        assertTrue(result.imageLeftEdgeDispersion() < 0.035);
        assertTrue(result.imageRightEdgeDispersion() > 0.035);
        assertTrue(result.foregroundToBoundsFraction()
                >= 0.6538691251067112);
        assertTrue(result.bilateralMirroredOverlap()
                >= 0.7901669791805024);
        assertEquals(1, result.connectedComponentCount());
        assertEquals(SectionGeometry.FULL, result.geometry());
    }

    @Test
    void pinsEveryHighConfidenceBilateralBoundary() {
        final double occupancy = 0.6538691251067112;
        final double overlap = 0.7901669791805024;

        assertTrue(TissueGeometryClassifier
                .isHighConfidenceBilateralFullSection(
                        1.28, occupancy, overlap, 0.85, 1));
        assertFalse(TissueGeometryClassifier
                .isHighConfidenceBilateralFullSection(
                        Math.nextDown(1.28), occupancy,
                        overlap, 0.85, 1));
        assertFalse(TissueGeometryClassifier
                .isHighConfidenceBilateralFullSection(
                        1.28, Math.nextDown(occupancy),
                        overlap, 0.85, 1));
        assertFalse(TissueGeometryClassifier
                .isHighConfidenceBilateralFullSection(
                        1.28, occupancy,
                        Math.nextDown(overlap), 0.85, 1));
        assertFalse(TissueGeometryClassifier
                .isHighConfidenceBilateralFullSection(
                        1.28, occupancy, overlap,
                        Math.nextDown(0.85), 1));
        assertFalse(TissueGeometryClassifier
                .isHighConfidenceBilateralFullSection(
                        1.28, occupancy, overlap, 0.85, 2));
    }

    @Test
    void pinsEveryCompactPosteriorBoundary() {
        final double aspect = 1.20;
        final double occupancy = 0.65;
        final double overlap = 0.80;
        final double balance = 0.95;
        final double edge = 0.05;

        assertTrue(TissueGeometryClassifier.isCompactPosteriorFullSection(
                aspect, occupancy, overlap, balance, edge, edge, 1));
        assertFalse(TissueGeometryClassifier.isCompactPosteriorFullSection(
                Math.nextDown(aspect), occupancy,
                overlap, balance, edge, edge, 1));
        assertFalse(TissueGeometryClassifier.isCompactPosteriorFullSection(
                aspect, Math.nextDown(occupancy),
                overlap, balance, edge, edge, 1));
        assertFalse(TissueGeometryClassifier.isCompactPosteriorFullSection(
                aspect, occupancy, Math.nextDown(overlap),
                balance, edge, edge, 1));
        assertFalse(TissueGeometryClassifier.isCompactPosteriorFullSection(
                aspect, occupancy, overlap,
                Math.nextDown(balance), edge, edge, 1));
        assertFalse(TissueGeometryClassifier.isCompactPosteriorFullSection(
                aspect, occupancy, overlap, balance,
                Math.nextDown(edge), edge, 1));
        assertFalse(TissueGeometryClassifier.isCompactPosteriorFullSection(
                aspect, occupancy, overlap, balance,
                edge, Math.nextDown(edge), 1));
        assertFalse(TissueGeometryClassifier.isCompactPosteriorFullSection(
                aspect, occupancy, overlap, balance, edge, edge, 2));
    }

    @Test
    void pinsEveryVeryCompactPosteriorBoundary() {
        final double minimumAspect = 1.15;
        final double maximumAspect = 1.20;
        final double edge = 0.05;
        final double occupancy = 0.60;
        final double overlap = 0.735;
        final double balance = 0.88;
        final double maximumAdded = 0.06;

        assertTrue(TissueGeometryClassifier
                .isVeryCompactPosteriorFullSection(
                        minimumAspect, edge, edge, 1,
                        occupancy, overlap, balance, maximumAdded));
        assertFalse(TissueGeometryClassifier
                .isVeryCompactPosteriorFullSection(
                        Math.nextDown(minimumAspect), edge, edge, 1,
                        occupancy, overlap, balance, maximumAdded));
        assertTrue(TissueGeometryClassifier
                .isVeryCompactPosteriorFullSection(
                        Math.nextDown(maximumAspect), edge, edge, 1,
                        occupancy, overlap, balance, maximumAdded));
        assertFalse(TissueGeometryClassifier
                .isVeryCompactPosteriorFullSection(
                        maximumAspect, edge, edge, 1,
                        occupancy, overlap, balance, maximumAdded));
        assertFalse(TissueGeometryClassifier
                .isVeryCompactPosteriorFullSection(
                        minimumAspect, Math.nextDown(edge), edge, 1,
                        occupancy, overlap, balance, maximumAdded));
        assertFalse(TissueGeometryClassifier
                .isVeryCompactPosteriorFullSection(
                        minimumAspect, edge, Math.nextDown(edge), 1,
                        occupancy, overlap, balance, maximumAdded));
        assertFalse(TissueGeometryClassifier
                .isVeryCompactPosteriorFullSection(
                        minimumAspect, edge, edge, 2,
                        occupancy, overlap, balance, maximumAdded));
        assertFalse(TissueGeometryClassifier
                .isVeryCompactPosteriorFullSection(
                        minimumAspect, edge, edge, 1,
                        Math.nextDown(occupancy),
                        overlap, balance, maximumAdded));
        assertFalse(TissueGeometryClassifier
                .isVeryCompactPosteriorFullSection(
                        minimumAspect, edge, edge, 1,
                        occupancy, Math.nextDown(overlap),
                        balance, maximumAdded));
        assertFalse(TissueGeometryClassifier
                .isVeryCompactPosteriorFullSection(
                        minimumAspect, edge, edge, 1,
                        occupancy, overlap,
                        Math.nextDown(balance), maximumAdded));
        assertFalse(TissueGeometryClassifier
                .isVeryCompactPosteriorFullSection(
                        minimumAspect, edge, edge, 1,
                        occupancy, overlap, balance,
                        Math.nextUp(maximumAdded)));
    }

    @Test
    void reportsEnvelopeMeasurementsWithoutChangingInput() {
        final BinaryMask input = SyntheticMasks
                .fullSectionWithBilateralSignalVoids();
        final var before = input.copyBits();

        final TissueEnvelopeMeasurements result =
                classifier.envelopeMeasurements(input);

        assertEquals(before, input.copyBits());
        assertTrue(result.foregroundToBoundsFraction()
                >= classifier.classify(input)
                        .foregroundToBoundsFraction());
        assertTrue(result.addedPreviewFraction() > 0);
    }

    @Test
    void doesNotPromoteDenseSymmetricDisconnectedLowDispersionMask() {
        final TissueGeometryResult result = classifier.classify(
                SyntheticMasks.denseDisconnectedLowDispersionSection());

        assertTrue(result.imageLeftEdgeDispersion() < 0.035);
        assertTrue(result.imageRightEdgeDispersion() < 0.035);
        assertTrue(result.foregroundToBoundsFraction()
                >= 0.6538691251067112);
        assertTrue(result.bilateralMirroredOverlap()
                >= 0.7901669791805024);
        assertEquals(2, result.connectedComponentCount());
        assertEquals(
                SectionGeometry.PARTIAL_OR_DAMAGED,
                result.geometry());
    }

    @Test
    void reviewsDenseFullGeometryAtExactFivePercentFragmentBoundary() {
        final TissueGeometryResult below = classifier.classify(
                fragmentedDenseSection(20));
        final TissueGeometryResult exact = classifier.classify(
                fragmentedDenseSection(19));

        assertEquals(2, below.connectedComponentCount());
        assertEquals(1, below.substantialComponentCount());
        assertEquals(SectionGeometry.FULL, below.geometry());
        assertEquals(2, exact.connectedComponentCount());
        assertEquals(2, exact.substantialComponentCount());
        assertEquals(
                SectionGeometry.BILATERAL_REVIEW_REQUIRED,
                exact.geometry());
    }

    @Test
    void acceptsMasksWhoseComponentsAreAllBelowFivePercent() {
        final BinaryMask fragments = SyntheticMasks.create(
                100, 100, (x, y) -> {
                    if ((x - 10) % 15 != 0
                            || (y - 10) % 15 != 0) {
                        return false;
                    }
                    return x >= 10 && x <= 70
                            && y >= 10 && y <= 70;
                });

        final TissueGeometryResult result =
                classifier.classify(fragments);

        assertEquals(25, result.connectedComponentCount());
        assertEquals(0, result.substantialComponentCount());
        assertEquals(
                SectionGeometry.PARTIAL_OR_DAMAGED,
                result.geometry());
    }

    private static BinaryMask fragmentedDenseSection(
            final int gapY) {
        final int width = 128;
        final int height = gapY + 2;
        return SyntheticMasks.create(width, height, (x, y) -> {
            if (y == gapY) {
                return false;
            }
            final int left = y % 2 == 0 ? 5 : 15;
            return x >= left && x < left + 100;
        });
    }
}
