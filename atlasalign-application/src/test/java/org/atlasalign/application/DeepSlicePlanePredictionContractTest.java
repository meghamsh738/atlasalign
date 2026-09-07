package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeepSlicePlanePredictionContractTest {

    @Test
    void ouvUsesTheFrozenOrderWithoutLeakingMutableArrays() {
        final double[] values = zeroTiltOuv().toArray();
        final DeepSliceOuv ouv = DeepSliceOuv.fromArray(values);

        values[0] = -100;
        final double[] exposed = ouv.toArray();
        exposed[1] = -200;

        assertEquals(0, ouv.ox());
        assertEquals(100, ouv.oy());
        assertArrayEquals(new double[]{
                0, 100, 0, 3, 0, 0, 0, 0, -3}, ouv.toArray());
        assertEquals(List.of(0d, 100d, 0d, 3d, 0d, 0d, 0d, 0d, -3d),
                ouv.toList());
        assertThrows(UnsupportedOperationException.class, () ->
                ouv.toList().add(1d));
        assertThrows(IllegalArgumentException.class, () ->
                DeepSliceOuv.fromArray(new double[8]));
        final Double[] nullComponent = new Double[9];
        Arrays.fill(nullComponent, 0d);
        nullComponent[4] = null;
        assertThrows(IllegalArgumentException.class, () ->
                DeepSliceOuv.fromList(Arrays.asList(nullComponent)));
        assertThrows(IllegalArgumentException.class, () -> new DeepSliceOuv(
                Double.NaN, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void derivesPinnedZeroAndSignedObliqueTiltFixtures() {
        final DeepSlicePlaneGeometry zero = DeepSlicePlaneGeometry.from(
                zeroTiltOuv());
        final DeepSlicePlaneGeometry oblique = DeepSlicePlaneGeometry.from(
                signedObliqueOuv());
        final double expectedOblique = -Math.toDegrees(Math.atan(0.5));

        assertEquals(427, zero.zeroBasedAnteriorPosteriorIndex());
        assertEquals(0, zero.sagittalTiltDegrees(), 0);
        assertEquals(0, zero.horizontalTiltDegrees(), 0);
        assertEquals(461, oblique.zeroBasedAnteriorPosteriorIndex());
        assertEquals(expectedOblique, oblique.sagittalTiltDegrees(), 1e-12);
        assertEquals(expectedOblique, oblique.horizontalTiltDegrees(), 1e-12);
    }

    @Test
    void preservesPinnedFsumCancellationInsteadOfNaiveLeftToRightSummation() {
        /*
         * Precomputed with the frozen finite Python math.fsum equations:
         * O dot n terms are 4503599627370496.0, 0.75,
         * -4503599627370393.0. math.fsum is 103.75, while naive
         * left-to-right summation is 104.0. The respective axis-0 values
         * are 472 and 471, so this fixture detects loss of compensation.
         */
        final DeepSliceOuv cancellation = new DeepSliceOuv(
                36_028_797_018_963_968d,
                0.75,
                -36_028_797_018_963_144d,
                3,
                -0.375,
                0,
                0,
                0.375,
                -3);

        final DeepSlicePlaneGeometry geometry = DeepSlicePlaneGeometry.from(
                cancellation);

        assertEquals(472, geometry.zeroBasedAnteriorPosteriorIndex());
        assertEquals(-7.125016348901757,
                geometry.sagittalTiltDegrees(), 1e-12);
        assertEquals(7.125016348901757,
                geometry.horizontalTiltDegrees(), 1e-12);
    }

    @Test
    void normalizesEnsembleAndUsesItForAllConvenienceValues() {
        final DeepSliceOuv primary = zeroTiltOuv();
        final DeepSliceOuv secondary = signedObliqueOuv();
        final DeepSliceOuv expectedEnsemble = average(primary, secondary);
        final DeepSlicePlanePrediction prediction =
                new DeepSlicePlanePrediction(
                        primary, secondary, expectedEnsemble);
        final DeepSlicePlaneGeometry ensembleGeometry =
                DeepSlicePlaneGeometry.from(expectedEnsemble);

        assertEquals(ensembleGeometry.zeroBasedAnteriorPosteriorIndex(),
                prediction.zeroBasedAnteriorPosteriorIndex());
        assertEquals(ensembleGeometry.sagittalTiltDegrees(),
                prediction.sagittalTiltDegrees(), 0);
        assertEquals(ensembleGeometry.horizontalTiltDegrees(),
                prediction.horizontalTiltDegrees(), 0);
        assertEquals(expectedEnsemble,
                prediction.ensemblePrediction().orElseThrow().ouv());
        assertEquals(primary, prediction.primaryPrediction().orElseThrow().ouv());
        assertEquals(secondary,
                prediction.secondaryPrediction().orElseThrow().ouv());
    }

    @Test
    void acceptsTheExactEnsembleToleranceBoundaryAndRejectsNextUp() {
        final DeepSliceOuv primary = zeroTiltOuv();
        final DeepSliceOuv secondary = signedObliqueOuv();
        final double[] atTolerance = average(primary, secondary).toArray();
        atTolerance[0] = 1e-9;
        final double[] beyondTolerance = average(primary, secondary).toArray();
        beyondTolerance[0] = Math.nextUp(1e-9);

        new DeepSlicePlanePrediction(
                primary, secondary, new DeepSliceOuv(atTolerance));
        assertThrows(IllegalArgumentException.class, () ->
                new DeepSlicePlanePrediction(
                        primary,
                        secondary,
                        new DeepSliceOuv(beyondTolerance)));
    }

    @Test
    void appliesStrictPrimarySecondaryCautionBoundaries() {
        final DeepSliceOuv axisPrimary = zeroTiltOuv();
        final DeepSliceOuv axisBoundary = new DeepSliceOuv(
                0, 80, 0, 3, 0, 0, 0, 0, -3);
        final DeepSliceOuv axisOutside = new DeepSliceOuv(
                0, 79, 0, 3, 0, 0, 0, 0, -3);
        final DeepSlicePredictionDiagnostics noAxisCaution =
                diagnostics(axisPrimary, axisBoundary);
        final DeepSlicePredictionDiagnostics axisCaution =
                diagnostics(axisPrimary, axisOutside);

        assertEquals(20, Math.abs(noAxisCaution.primary().geometry()
                .zeroBasedAnteriorPosteriorIndex()
                - noAxisCaution.secondary().geometry()
                .zeroBasedAnteriorPosteriorIndex()));
        assertFalse(noAxisCaution.hasAnteriorPosteriorCaution());
        assertTrue(axisCaution.hasAnteriorPosteriorCaution());

        final DeepSlicePredictionDiagnostics noSagittalCaution = diagnostics(
                sagittalTiltOuv(0), sagittalTiltOuv(5));
        final DeepSlicePredictionDiagnostics sagittalCaution = diagnostics(
                sagittalTiltOuv(0), sagittalTiltOuv(5.01));
        final DeepSlicePredictionDiagnostics noHorizontalCaution = diagnostics(
                horizontalTiltOuv(0), horizontalTiltOuv(5));
        final DeepSlicePredictionDiagnostics horizontalCaution = diagnostics(
                horizontalTiltOuv(0), horizontalTiltOuv(5.01));

        assertFalse(noSagittalCaution.hasSagittalTiltCaution());
        assertTrue(sagittalCaution.hasSagittalTiltCaution());
        assertFalse(noHorizontalCaution.hasHorizontalTiltCaution());
        assertTrue(horizontalCaution.hasHorizontalTiltCaution());
    }

    @Test
    void rejectsNonFiniteSingularAndOutOfRangeGeometry() {
        assertThrows(IllegalArgumentException.class, () ->
                DeepSlicePlaneGeometry.from(new DeepSliceOuv(
                        0, 0, 0, 1, 0, 0, 0, 1, 0)));
        assertThrows(IllegalArgumentException.class, () ->
                DeepSlicePlaneGeometry.from(new DeepSliceOuv(
                        0, -1, 0, 3, 0, 0, 0, 0, -3)));
        assertThrows(IllegalArgumentException.class, () ->
                DeepSlicePlaneGeometry.from(new DeepSliceOuv(
                        0, 100, 0, 3, -6, 0, 0, 0, -3)));
    }

    @Test
    void retainsLegacyPredictionsWithoutFabricatingOuvDiagnostics() {
        final DeepSlicePlanePrediction legacy =
                new DeepSlicePlanePrediction(245, 1.5, -2);

        assertTrue(legacy.diagnostics().isEmpty());
        assertTrue(legacy.primaryPrediction().isEmpty());
        assertTrue(legacy.secondaryPrediction().isEmpty());
        assertTrue(legacy.ensemblePrediction().isEmpty());
        assertTrue(legacy.runtimeProvenance().isEmpty());
        assertTrue(legacy.verifiedRuntimeProvenance().isEmpty());
        assertFalse(legacy.hasPrimarySecondaryCaution());
    }

    @Test
    void retainsVerifiedRuntimeFactsOnlyForRealWorkerVectors() {
        final DeepSliceRuntimeProvenance provenance = provenance();
        final DeepSlicePlanePrediction prediction =
                DeepSlicePlanePrediction.fromWorkerVectors(
                        zeroTiltOuv(),
                        signedObliqueOuv(),
                        average(zeroTiltOuv(), signedObliqueOuv()),
                        provenance);
        final DeepSlicePlanePrediction inMemoryDetailed =
                new DeepSlicePlanePrediction(
                        zeroTiltOuv(),
                        signedObliqueOuv(),
                        average(zeroTiltOuv(), signedObliqueOuv()));

        assertEquals(provenance, prediction.runtimeProvenance().orElseThrow());
        assertEquals(provenance,
                prediction.verifiedRuntimeProvenance().orElseThrow());
        assertTrue(prediction.diagnostics().isPresent());
        assertTrue(inMemoryDetailed.diagnostics().isPresent());
        assertTrue(inMemoryDetailed.runtimeProvenance().isEmpty());
        assertThrows(IllegalArgumentException.class, () ->
                new DeepSlicePlanePrediction(
                        245,
                        0,
                        0,
                        Optional.empty(),
                        Optional.of(provenance)));
    }

    @Test
    void rejectsRuntimeProvenanceThatIsNotVerifiedProtocolV2Identity() {
        assertThrows(IllegalArgumentException.class, () ->
                new DeepSliceRuntimeProvenance(
                        "release", Path.of("relative/runtime"), 2,
                        1, "a".repeat(64), "3.11.15", "1.2.8",
                        "2.21.0", "models", "b".repeat(64),
                        "c".repeat(64), "d".repeat(64)));
        assertThrows(IllegalArgumentException.class, () ->
                new DeepSliceRuntimeProvenance(
                        "release", Path.of("/verified/runtime").toAbsolutePath().normalize(), 1,
                        1, "a".repeat(64), "3.11.15", "1.2.8",
                        "2.21.0", "models", "b".repeat(64),
                        "c".repeat(64), "d".repeat(64)));
    }

    @Test
    void rejectsConvenienceValuesThatDoNotExactlyMatchTheEnsemble() {
        final DeepSlicePredictionDiagnostics diagnostics = diagnostics(
                zeroTiltOuv(), signedObliqueOuv());
        final DeepSlicePlaneGeometry ensemble = diagnostics.ensemble().geometry();

        assertThrows(IllegalArgumentException.class, () ->
                new DeepSlicePlanePrediction(
                        ensemble.zeroBasedAnteriorPosteriorIndex() + 1,
                        ensemble.sagittalTiltDegrees(),
                        ensemble.horizontalTiltDegrees(),
                        Optional.of(diagnostics)));
    }

    private static DeepSliceOuv zeroTiltOuv() {
        return new DeepSliceOuv(0, 100, 0, 3, 0, 0, 0, 0, -3);
    }

    private static DeepSliceOuv signedObliqueOuv() {
        return new DeepSliceOuv(0, 100, 0, 3, -1.5, 0, 0, -1.5, -3);
    }

    private static DeepSliceOuv sagittalTiltOuv(final double degrees) {
        final double tangent = Math.tan(Math.toRadians(degrees));
        return new DeepSliceOuv(
                228, 100, 0, 3, 3 * tangent, 0, 0, 0, -3);
    }

    private static DeepSliceOuv horizontalTiltOuv(final double degrees) {
        final double tangent = Math.tan(Math.toRadians(degrees));
        return new DeepSliceOuv(
                0, 100, 160, 3, 0, 0, 0, 3 * tangent, -3);
    }

    private static DeepSlicePredictionDiagnostics diagnostics(
            final DeepSliceOuv primary, final DeepSliceOuv secondary) {
        return new DeepSlicePredictionDiagnostics(
                primary, secondary, average(primary, secondary));
    }

    private static DeepSliceOuv average(
            final DeepSliceOuv first, final DeepSliceOuv second) {
        final double[] values = new double[DeepSliceOuv.COMPONENT_COUNT];
        for (int index = 0; index < values.length; index++) {
            values[index] = (first.component(index)
                    + second.component(index)) / 2;
        }
        return new DeepSliceOuv(values);
    }

    private static DeepSliceRuntimeProvenance provenance() {
        return new DeepSliceRuntimeProvenance(
                "deepslice-test-r3",
                Path.of("/verified/runtime").toAbsolutePath().normalize(),
                2,
                1_234,
                "a".repeat(64),
                "3.11.15",
                "1.2.8",
                "2.21.0",
                "test-model-release",
                "b".repeat(64),
                "c".repeat(64),
                "d".repeat(64));
    }
}
