package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;

class TissueSegmenterTest {

    private final TissueSegmenter segmenter = new TissueSegmenter();

    @Test
    void segmentsBrightTissueDeterministically() {
        final BinaryMask expected = SyntheticMasks.fullSection();
        final float[] pixels =
                SyntheticMasks.image(expected, 110, 8);

        final TissueSegmentationResult first = segmenter.segment(
                expected.width(), expected.height(), pixels);
        final TissueSegmentationResult second = segmenter.segment(
                expected.width(), expected.height(), pixels);

        assertEquals(TissuePolarity.BRIGHT_ON_DARK, first.polarity());
        assertEquals(
                TissueSegmentationMethod.ORDINARY_OTSU,
                first.method());
        assertTrue(iou(expected, first.mask()) >= 0.97);
        assertEquals(8, first.candidates().size());
        assertTrue(first.candidates().stream()
                .anyMatch(TissueSegmentationCandidateDiagnostic::valid));
        assertEquals(first, second);
    }

    @Test
    void segmentsDarkTissueWithoutChangingTheContract() {
        final BinaryMask expected = SyntheticMasks.fullSection();
        final TissueSegmentationResult result = segmenter.segment(
                expected.width(),
                expected.height(),
                SyntheticMasks.image(expected, 12, 180));

        assertEquals(TissuePolarity.DARK_ON_BRIGHT, result.polarity());
        assertTrue(iou(expected, result.mask()) >= 0.97);
    }

    @Test
    void percentileClippedOtsuIgnoresIsolatedHotPixels() {
        final BinaryMask expected = SyntheticMasks.fullSection();
        final float[] pixels =
                SyntheticMasks.image(expected, 110, 8);
        pixels[0] = 65_535;

        final TissueSegmentationResult result = segmenter.segment(
                expected.width(), expected.height(), pixels);

        assertEquals(
                TissueSegmentationMethod.PERCENTILE_CLIPPED_OTSU,
                result.method());
        assertEquals(TissuePolarity.BRIGHT_ON_DARK, result.polarity());
        assertTrue(iou(expected, result.mask()) >= 0.97);
        assertTrue(result.candidates().stream()
                .filter(candidate -> candidate.method()
                        == TissueSegmentationMethod.ORDINARY_OTSU)
                .allMatch(candidate ->
                        candidate.rejectionReason().isPresent()));
    }

    @Test
    void segmentsUnevenLowContrastTissue() {
        final BinaryMask expected = SyntheticMasks.fullSection();
        final float[] pixels =
                new float[expected.width() * expected.height()];
        for (int y = 0; y < expected.height(); y++) {
            for (int x = 0; x < expected.width(); x++) {
                final int index = y * expected.width() + x;
                final float background = 20
                        + (float) x / expected.width() * 18;
                pixels[index] = expected.contains(x, y)
                        ? background + 23 : background;
            }
        }

        final TissueSegmentationResult result = segmenter.segment(
                expected.width(), expected.height(), pixels);

        assertTrue(iou(expected, result.mask()) >= 0.90);
        assertTrue(result.foregroundFraction() >= 0.01);
        assertTrue(result.foregroundFraction() <= 0.90);
    }

    @Test
    void retainsBilateralSignalVoidsAndHalfSections() {
        for (final BinaryMask expected : new BinaryMask[] {
                SyntheticMasks.fullSectionWithBilateralSignalVoids(),
                SyntheticMasks.damagedSection(),
                SyntheticMasks.sectionWithUnilateralSignalLoss(),
                SyntheticMasks.imageLeftHalf(),
                SyntheticMasks.imageRightHalf()}) {
            final TissueSegmentationResult result = segmenter.segment(
                    expected.width(),
                    expected.height(),
                    SyntheticMasks.image(expected, 95, 11));

            assertTrue(iou(expected, result.mask()) >= 0.97);
            assertFalse(result.mask().isEmpty());
        }
    }

    @Test
    void rejectsBorderSaturatedCandidateWithoutRejectingCentralTissue() {
        final float[] pixels = new float[10_000];
        for (int y = 35; y < 65; y++) {
            for (int x = 30; x < 70; x++) {
                pixels[y * 100 + x] = 100;
            }
        }

        final TissueSegmentationResult result =
                segmenter.segment(100, 100, pixels);

        assertEquals(TissuePolarity.BRIGHT_ON_DARK, result.polarity());
        assertTrue(result.candidates().stream()
                .filter(candidate -> candidate.polarity()
                        == TissuePolarity.DARK_ON_BRIGHT)
                .allMatch(candidate -> candidate.rejectionReason()
                        .orElse("").contains("border")));
    }

    @Test
    void retainsSeveralPlausibleTissuePieces() {
        final float[] pixels = new float[10_000];
        for (int originY : new int[] {20, 60}) {
            for (int originX : new int[] {20, 60}) {
                for (int y = originY; y < originY + 20; y++) {
                    for (int x = originX; x < originX + 20; x++) {
                        pixels[y * 100 + x] = 100;
                    }
                }
            }
        }

        final TissueSegmentationResult result =
                segmenter.segment(100, 100, pixels);

        assertEquals(TissuePolarity.BRIGHT_ON_DARK, result.polarity());
        assertEquals(1_600, result.mask().foregroundCount());
        assertEquals(0.25, result.largestComponentFraction(), 1e-12);
        assertEquals(0, result.borderForegroundFraction(), 1e-12);
    }

    @Test
    void acceptsEightEqualPiecesAtComponentBoundary() {
        final float[] pixels = equalPieces(4, 2, 8);

        final TissueSegmentationResult result =
                segmenter.segment(100, 100, pixels);

        assertEquals(TissuePolarity.BRIGHT_ON_DARK, result.polarity());
        assertEquals(512, result.mask().foregroundCount());
        assertEquals(0.125, result.largestComponentFraction(), 1e-12);
    }

    @Test
    void rejectsNineEqualFragmentsJustBelowComponentBoundary() {
        final float[] pixels = equalPieces(3, 3, 8);

        final TissueSegmentationException implausible =
                assertThrows(
                        TissueSegmentationException.class,
                        () -> segmenter.segment(100, 100, pixels));

        assertTrue(implausible.candidates().stream()
                .filter(candidate -> candidate.polarity()
                        == TissuePolarity.BRIGHT_ON_DARK)
                .allMatch(candidate -> candidate.rejectionReason()
                        .orElse("").contains(
                                "12.5% of raw foreground")));
    }

    @Test
    void rejectsFragmentedSignalAndItsBorderConnectedComplement() {
        final float[] pixels = new float[10_000];
        for (int y = 8; y < 92; y += 8) {
            for (int x = 8; x < 92; x += 8) {
                for (int offsetY = 0; offsetY < 3; offsetY++) {
                    for (int offsetX = 0; offsetX < 3; offsetX++) {
                        pixels[(y + offsetY) * 100
                                + x + offsetX] = 100;
                    }
                }
            }
        }

        final TissueSegmentationException implausible =
                assertThrows(
                        TissueSegmentationException.class,
                        () -> segmenter.segment(
                                100, 100, pixels));

        assertTrue(implausible.candidates().size() >= 4);
        assertTrue(implausible.candidates().stream()
                .filter(candidate -> candidate.polarity()
                        == TissuePolarity.BRIGHT_ON_DARK)
                .allMatch(candidate -> candidate.rejectionReason()
                        .orElse("").contains(
                                "12.5% of raw foreground")));
        assertTrue(implausible.candidates().stream()
                .filter(candidate -> candidate.polarity()
                        == TissuePolarity.DARK_ON_BRIGHT)
                .allMatch(candidate -> candidate.rejectionReason()
                        .orElse("").contains(
                                "image border")));
    }

    @Test
    void rejectsConstantAndApproximatelyWholeFrameCandidates() {
        final float[] constant =
                new float[SyntheticMasks.WIDTH * SyntheticMasks.HEIGHT];
        final TissueSegmentationException noContrast =
                assertThrows(
                        TissueSegmentationException.class,
                        () -> segmenter.segment(
                                SyntheticMasks.WIDTH,
                                SyntheticMasks.HEIGHT,
                                constant));
        assertTrue(noContrast.candidates().isEmpty());
        assertTrue(noContrast.getMessage().contains(
                "no finite intensity contrast"));

        final float[] almostWhole = new float[10_000];
        for (int index = 0; index < 50; index++) {
            almostWhole[index] = 100;
        }
        final TissueSegmentationException implausible =
                assertThrows(
                        TissueSegmentationException.class,
                        () -> segmenter.segment(
                                100, 100, almostWhole));
        assertTrue(implausible.candidates().size() >= 4);
        assertTrue(implausible.candidates().stream()
                .allMatch(candidate ->
                        candidate.rejectionReason().isPresent()));
        assertTrue(implausible.getMessage().contains(
                "source image was not modified"));
    }

    @Test
    void multiOtsuUsesFrozenThresholdEdgesAndLexicalTieBreak() {
        final float[] pixels = new float[10_000];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = index < 3_000 ? 0
                    : index < 6_000 ? 1 : 2;
        }

        final TissueSegmentationResult result =
                segmenter.segment(100, 100, pixels);
        final TissueSegmentationCandidateDiagnostic bright =
                result.candidates().stream()
                        .filter(candidate -> candidate.method()
                                == TissueSegmentationMethod
                                        .ORDINARY_MULTI_OTSU)
                        .filter(candidate -> candidate.polarity()
                                == TissuePolarity.BRIGHT_ON_DARK)
                        .findFirst()
                        .orElseThrow();
        assertEquals(2.0f / 256, bright.threshold());
        assertEquals(258.0f / 256,
                bright.companionThreshold().orElseThrow());
        final TissueSegmentationCandidateDiagnostic dark =
                result.candidates().stream()
                        .filter(candidate -> candidate.method()
                                == TissueSegmentationMethod
                                        .ORDINARY_MULTI_OTSU)
                        .filter(candidate -> candidate.polarity()
                                == TissuePolarity.DARK_ON_BRIGHT)
                        .findFirst()
                        .orElseThrow();
        assertEquals(258.0f / 256, dark.threshold());
        assertEquals(2.0f / 256,
                dark.companionThreshold().orElseThrow());
    }

    @Test
    void recordsExactAuditNotesWhenThreeClassesAreUnavailable() {
        final float[] pixels = new float[10_000];
        for (int y = 30; y < 70; y++) {
            for (int x = 25; x < 75; x++) {
                pixels[y * 100 + x] = 100;
            }
        }

        final TissueSegmentationResult result =
                segmenter.segment(100, 100, pixels);

        assertEquals(4, result.candidates().size());
        assertEquals(java.util.List.of(
                "ORDINARY_MULTI_OTSU: three-class histogram has "
                        + "fewer than three non-empty classes",
                "PERCENTILE_CLIPPED_MULTI_OTSU: three-class histogram "
                        + "has fewer than three non-empty classes"),
                result.auditNotes());
    }

    @Test
    void multiOtsuDiagnosticsAuditCompanionAndExteriorBackground() {
        final BinaryMask expected = SyntheticMasks.fullSection();
        final float[] pixels = new float[expected.width() * expected.height()];
        for (int y = 0; y < expected.height(); y++) {
            for (int x = 0; x < expected.width(); x++) {
                final int index = y * expected.width() + x;
                pixels[index] = expected.contains(x, y)
                        ? 20 + (x % 5) : 220 + (x % 5);
            }
        }

        final TissueSegmentationResult result = segmenter.segment(
                expected.width(), expected.height(), pixels);
        final var multi = result.candidates().stream()
                .filter(candidate -> candidate.method()
                        == TissueSegmentationMethod.ORDINARY_MULTI_OTSU)
                .toList();
        assertEquals(2, multi.size());
        assertTrue(multi.stream()
                .allMatch(candidate ->
                        candidate.companionThreshold().isPresent()
                                && candidate.exteriorBackgroundFraction()
                                        .isPresent()
                                && candidate
                                        .exteriorBackgroundBorderFraction()
                                        .isPresent()));
        assertTrue(multi.stream().allMatch(candidate ->
                candidate.exteriorBackgroundFraction().orElseThrow() >= 0
                        && candidate.exteriorBackgroundBorderFraction()
                                .orElseThrow() >= 0));
        assertTrue(result.candidates().stream()
                .filter(candidate -> candidate.method()
                        == TissueSegmentationMethod.ORDINARY_OTSU)
                .allMatch(candidate ->
                        candidate.companionThreshold().isEmpty()
                                && candidate.exteriorBackgroundFraction()
                                        .isEmpty()
                                && candidate
                                        .exteriorBackgroundBorderFraction()
                                        .isEmpty()));
    }

    @Test
    void frozenSyntheticArtifactsNeverLookLikeCompleteTissue() {
        final TissueGeometryClassifier classifier =
                new TissueGeometryClassifier();
        for (final String fixture : new String[] {
                "constant", "near-constant", "linear", "vignette",
                "scanner-shadow", "dust", "whole-frame"}) {
            SectionGeometry geometry = null;
            try {
                final TissueSegmentationResult result = segmenter.segment(
                        SyntheticMasks.WIDTH,
                        SyntheticMasks.HEIGHT,
                        SyntheticMasks.frozenFixture(fixture));
                geometry = classifier.classify(result.mask()).geometry();
                assertNotEquals(SectionGeometry.FULL, geometry, fixture);
                assertNotEquals(
                        SectionGeometry.BILATERAL_REVIEW_REQUIRED,
                        geometry,
                        fixture);
            } catch (final TissueSegmentationException expected) {
                // Frozen protocol permits rejection for every artifact.
            }
            if (fixture.equals("constant")
                    || fixture.equals("dust")
                    || fixture.equals("whole-frame")) {
                assertEquals(null, geometry, fixture + " must be rejected");
            } else if (fixture.equals("near-constant")
                    || fixture.equals("scanner-shadow")) {
                assertTrue(geometry == null
                                || geometry
                                        == SectionGeometry
                                                .PARTIAL_OR_DAMAGED,
                        fixture);
            } else {
                assertTrue(geometry == null
                                || geometry
                                        == SectionGeometry
                                                .PARTIAL_OR_DAMAGED
                                || geometry
                                        == SectionGeometry.IMAGE_LEFT_HALF
                                || geometry
                                        == SectionGeometry.IMAGE_RIGHT_HALF,
                        fixture);
            }
        }
    }

    @Test
    void frozenSyntheticTissueDamageRetainsExactGeometryLabels() {
        final TissueGeometryClassifier classifier =
                new TissueGeometryClassifier();
        final java.util.Map<String, SectionGeometry> expected =
                java.util.Map.of(
                        "left-half", SectionGeometry.IMAGE_LEFT_HALF,
                        "right-half", SectionGeometry.IMAGE_RIGHT_HALF,
                        "unilateral-loss",
                                SectionGeometry.PARTIAL_OR_DAMAGED,
                        "damaged-multi-piece",
                                SectionGeometry.PARTIAL_OR_DAMAGED);
        expected.forEach((fixture, geometry) -> {
            final TissueSegmentationResult result = segmenter.segment(
                    SyntheticMasks.WIDTH,
                    SyntheticMasks.HEIGHT,
                    SyntheticMasks.frozenFixture(fixture));
            assertEquals(
                    geometry,
                    classifier.classify(result.mask()).geometry(),
                    fixture);
        });
    }

    private static double iou(
            final BinaryMask first,
            final BinaryMask second) {
        final java.util.BitSet intersection = first.copyBits();
        intersection.and(second.copyBits());
        final java.util.BitSet union = first.copyBits();
        union.or(second.copyBits());
        return (double) intersection.cardinality() / union.cardinality();
    }

    private static float[] equalPieces(
            final int columns,
            final int rows,
            final int side) {
        final float[] pixels = new float[10_000];
        final int spacingX = 100 / (columns + 1);
        final int spacingY = 100 / (rows + 1);
        for (int row = 1; row <= rows; row++) {
            for (int column = 1; column <= columns; column++) {
                final int originX = column * spacingX - side / 2;
                final int originY = row * spacingY - side / 2;
                for (int y = originY; y < originY + side; y++) {
                    for (int x = originX; x < originX + side; x++) {
                        pixels[y * 100 + x] = 100;
                    }
                }
            }
        }
        return pixels;
    }
}
