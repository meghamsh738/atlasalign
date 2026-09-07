package org.atlasalign.deepslice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.atlasalign.application.DeepSliceInput;
import org.atlasalign.application.DeepSlicePlanePrediction;
import org.atlasalign.application.DeepSliceUnavailableException;
import org.atlasalign.application.ManualFallbackReason;
import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(value = OS.WINDOWS, disabledReason = "The fake runtime uses a POSIX shell and /usr/bin/python3; Windows DeepSlice process runtime is unverified")
class DeepSliceProcessBridgeTest {

    private static final String SUCCESS_WORKER = """
            import argparse, hashlib, json, os
            p = argparse.ArgumentParser()
            p.add_argument('--request', required=True)
            p.add_argument('--response', required=True)
            a = p.parse_args()
            with open(a.request, encoding='utf-8') as f:
                r = json.load(f)
            with open(r['pixelsFile'], 'rb') as f:
                assert hashlib.sha256(f.read()).hexdigest() == r['pixelsSha256']
            with open(r['syntheticMaskFile'], 'rb') as f:
                assert hashlib.sha256(f.read()).hexdigest() == r['syntheticMaskSha256']
            assert 'HTTP_PROXY' not in os.environ
            assert 'PYTHONPATH' not in os.environ
            assert r['protocolVersion'] == 2
            assert set(r) == {
                'protocolVersion', 'requestId', 'width', 'height',
                'pixelsFile', 'pixelsSha256', 'syntheticMaskFile',
                'syntheticMaskSha256', 'pythonVersion', 'deepSliceVersion',
                'tensorflowVersion', 'modelRelease', 'primaryWeightSha256',
                'secondaryWeightSha256', 'backboneWeightSha256'
            }
            primary = [0.0, 100.0, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0, -3.0]
            secondary = list(primary)
            ensemble = list(primary)
            response = {
                'protocolVersion': 2,
                'requestId': r['requestId'],
                'pixelsSha256': r['pixelsSha256'],
                'pythonVersion': r['pythonVersion'],
                'deepSliceVersion': r['deepSliceVersion'],
                'tensorflowVersion': r['tensorflowVersion'],
                'modelRelease': r['modelRelease'],
                'primaryWeightSha256': r['primaryWeightSha256'],
                'secondaryWeightSha256': r['secondaryWeightSha256'],
                'backboneWeightSha256': r['backboneWeightSha256'],
                'primaryOuv': primary,
                'secondaryOuv': secondary,
                'ensembleOuv': ensemble
            }
            with open(a.response, 'w', encoding='utf-8') as f:
                json.dump(response, f)
            """;

    @TempDir
    Path temporaryDirectory;

    @Test
    void exchangesExactProtocolV2WithNormalizedDiagnosticsAndProvenance()
            throws IOException, DeepSliceUnavailableException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime with spaces"),
                SUCCESS_WORKER);
        final boolean[] synthetic = {
                false, true,
                false, false
        };

        final DeepSlicePlanePrediction prediction;
        try (DeepSliceProcessBridge bridge = bridge(
                installed, Duration.ofSeconds(5))) {
            prediction = bridge.estimate(
                    new DeepSliceInput(
                            2, 2,
                            new float[] {0.25f, 1.5f, -2.0f, 4.0f},
                            BinaryMask.fromBooleans(2, 2, synthetic)));
        }

        assertEquals(427,
                prediction.zeroBasedAnteriorPosteriorIndex());
        assertEquals(0, prediction.sagittalTiltDegrees());
        assertEquals(0, prediction.horizontalTiltDegrees());
        assertEquals(List.of(0d, 100d, 0d, 3d, 0d, 0d, 0d, 0d, -3d),
                prediction.primaryPrediction().orElseThrow().ouv().toList());
        assertTrue(prediction.diagnostics().isPresent());
        assertTrue(prediction.secondaryPrediction().isPresent());
        assertTrue(prediction.ensemblePrediction().isPresent());
        final var provenance = prediction.runtimeProvenance().orElseThrow();
        assertEquals("test-release", provenance.verifiedReleaseId());
        assertEquals(installed.root().toRealPath(),
                provenance.canonicalRuntimePath());
        assertEquals(2, provenance.protocolVersion());
        assertEquals(installed.descriptor().manifestSizeBytes(),
                provenance.manifestSizeBytes());
        assertEquals(installed.descriptor().manifestSha256(),
                provenance.manifestSha256());
        assertEquals(installed.manifest().pythonVersion(),
                provenance.pythonVersion());
        assertEquals(installed.manifest().deepSliceVersion(),
                provenance.deepSliceVersion());
        assertEquals(installed.manifest().tensorflowVersion(),
                provenance.tensorflowVersion());
        assertEquals(installed.manifest().modelRelease(),
                provenance.modelRelease());
        assertEquals(installed.manifest().asset(
                DeepSliceAssetRole.MODEL_PRIMARY).sha256(),
                provenance.primaryWeightSha256());
        assertEquals(installed.manifest().asset(
                DeepSliceAssetRole.MODEL_SECONDARY).sha256(),
                provenance.secondaryWeightSha256());
        assertEquals(installed.manifest().asset(
                DeepSliceAssetRole.MODEL_BACKBONE).sha256(),
                provenance.backboneWeightSha256());
        try (var requests = Files.list(
                temporaryDirectory.resolve("requests"))) {
            assertFalse(requests.findAny().isPresent());
        }
    }

    @Test
    void rejectsProtocolV1Response() throws IOException {
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "'protocolVersion': 2,",
                "'protocolVersion': 1,"));
    }

    @Test
    void rejectsMissingAndNullPredictionFields() throws IOException {
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "with open(a.response, 'w', encoding='utf-8') as f:",
                "response.pop('primaryOuv')\n"
                        + "with open(a.response, 'w', encoding='utf-8') as f:"));
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "'primaryOuv': primary,",
                "'primaryOuv': None,"));
    }

    @Test
    void rejectsDuplicateJsonKeys() throws IOException {
        final String worker = SUCCESS_WORKER.replace(
                "json.dump(response, f)",
                "f.write(json.dumps(response)[:-1]"
                        + " + ', \"requestId\": \"duplicate\"}')");
        assertInvalidResponse(worker);
    }

    @Test
    void rejectsUnknownAndTrailingResponseData() throws IOException {
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "'ensembleOuv': ensemble",
                "'ensembleOuv': ensemble, 'unexpected': True"));
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "json.dump(response, f)",
                "json.dump(response, f); f.write(' trailing')"));
    }

    @Test
    void rejectsWrongLengthAndNonFiniteOuvArrays() throws IOException {
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "'primaryOuv': primary,",
                "'primaryOuv': [0.0] * 8,"));
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "'primaryOuv': primary,",
                "'primaryOuv': [float('nan')] + primary[1:],"));
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "'primaryOuv': primary,",
                "'primaryOuv': [None] + primary[1:],"));
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "'primaryOuv': primary,",
                "'primaryOuv': ['0.0'] + primary[1:],"));
    }

    @Test
    void rejectsMismatchedWorkerEnsemble() throws IOException {
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "'ensembleOuv': ensemble",
                "'ensembleOuv': [1.0] + ensemble[1:]"));
    }

    @Test
    void rejectsSingularOutOfRangeAndOverTiltDerivedGeometry()
            throws IOException {
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "primary = [0.0, 100.0, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0, -3.0]",
                "primary = [0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0]"));
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "primary = [0.0, 100.0, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0, -3.0]",
                "primary = [0.0, -1.0, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0, -3.0]"));
        assertInvalidResponse(SUCCESS_WORKER.replace(
                "primary = [0.0, 100.0, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0, -3.0]",
                "primary = [228.0, 100.0, 0.0, 3.0, 3.2, 0.0, 0.0, 0.0, -3.0]"));
    }

    @Test
    void rejectsProtocolV1InstallationBeforeWorkerUse() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), SUCCESS_WORKER, 1);

        final DeepSliceUnavailableException error = assertThrows(
                DeepSliceUnavailableException.class,
                () -> bridge(installed, Duration.ofSeconds(5)).estimate(input()));

        assertEquals(ManualFallbackReason.INSTALLATION_CORRUPT, error.reason());
        assertFalse(Files.exists(temporaryDirectory.resolve("requests")));
    }

    @Test
    void rejectsOversizedWorkerOutput() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"),
                "print('x' * 1100000)\n");

        final DeepSliceUnavailableException error = assertThrows(
                DeepSliceUnavailableException.class,
                () -> bridge(installed, Duration.ofSeconds(5))
                        .estimate(input()));

        assertEquals(ManualFallbackReason.WORKER_FAILED, error.reason());
    }

    @Test
    void exposesExactInstallationInventoryFailure() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), SUCCESS_WORKER);
        Files.writeString(
                installed.root().resolve("unexpected-cache.pyc"),
                "not trusted");

        final DeepSliceUnavailableException error = assertThrows(
                DeepSliceUnavailableException.class,
                () -> bridge(installed, Duration.ofSeconds(5))
                        .estimate(input()));

        assertEquals(
                ManualFallbackReason.INSTALLATION_CORRUPT,
                error.reason());
        assertTrue(error.getMessage().contains(
                "extra=[unexpected-cache.pyc]"));
    }

    @Test
    void rejectsOversizedResponse() throws IOException {
        final String worker = SUCCESS_WORKER.replace(
                "json.dump(response, f)",
                "json.dump(response, f); f.write(' ' * 70000)");
        assertInvalidResponse(worker);
    }

    @Test
    void rejectsMismatchedResponseIdentity() throws IOException {
        final String worker = SUCCESS_WORKER.replace(
                "'requestId': r['requestId']",
                "'requestId': 'wrong-request'");
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), worker);

        final DeepSliceUnavailableException error = assertThrows(
                DeepSliceUnavailableException.class,
                () -> bridge(installed, Duration.ofSeconds(5))
                        .estimate(input()));

        assertEquals(ManualFallbackReason.INVALID_WORKER_OUTPUT,
                error.reason());
    }

    @Test
    void rejectsLegacyDerivedResponseFields() throws IOException {
        final String worker = SUCCESS_WORKER.replace(
                "'ensembleOuv': ensemble",
                "'ensembleOuv': ensemble, "
                        + "'zeroBasedAnteriorPosteriorIndex': 427");
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), worker);

        final DeepSliceUnavailableException error = assertThrows(
                DeepSliceUnavailableException.class,
                () -> bridge(installed, Duration.ofSeconds(5))
                        .estimate(input()));

        assertEquals(ManualFallbackReason.INVALID_WORKER_OUTPUT,
                error.reason());
    }

    @Test
    void timesOutAndKillsWorker() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"),
                "import time\ntime.sleep(10)\n");

        final DeepSliceUnavailableException error = assertThrows(
                DeepSliceUnavailableException.class,
                () -> bridge(installed, Duration.ofMillis(150))
                        .estimate(input()));

        assertEquals(ManualFallbackReason.INFERENCE_TIMEOUT,
                error.reason());
    }

    @Test
    void rejectsNonzeroWorkerExit() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"),
                "raise SystemExit(7)\n");

        final DeepSliceUnavailableException error = assertThrows(
                DeepSliceUnavailableException.class,
                () -> bridge(installed, Duration.ofSeconds(5))
                        .estimate(input()));

        assertEquals(ManualFallbackReason.WORKER_FAILED, error.reason());
    }

    @Test
    void rejectsOversizedInputsBeforeStartingWorker() throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve("runtime"), SUCCESS_WORKER);
        final int width = DeepSliceProcessBridge.MAX_PIXELS + 1;

        final DeepSliceUnavailableException error = assertThrows(
                DeepSliceUnavailableException.class,
                () -> bridge(installed, Duration.ofSeconds(5)).estimate(
                        new DeepSliceInput(
                                width, 1, new float[width],
                                BinaryMask.empty(width, 1))));

        assertEquals(ManualFallbackReason.INVALID_WORKER_OUTPUT,
                error.reason());
    }

    private DeepSliceProcessBridge bridge(
            final DeepSliceTestInstallation.Installed installed,
            final Duration timeout) {
        final NetworkIsolation testIsolation = List::copyOf;
        return new DeepSliceProcessBridge(
                installed.root(),
                installed.descriptor(),
                testIsolation,
                timeout,
                temporaryDirectory.resolve("requests"));
    }

    private static DeepSliceInput input() {
        return new DeepSliceInput(
                1, 1, new float[] {1.0f}, BinaryMask.empty(1, 1));
    }

    private void assertInvalidResponse(final String worker)
            throws IOException {
        final var installed = DeepSliceTestInstallation.create(
                temporaryDirectory.resolve(
                        "runtime-" + Math.abs(worker.hashCode())),
                worker);
        final DeepSliceUnavailableException error = assertThrows(
                DeepSliceUnavailableException.class,
                () -> bridge(installed, Duration.ofSeconds(5))
                        .estimate(input()));
        assertEquals(ManualFallbackReason.INVALID_WORKER_OUTPUT,
                error.reason());
    }
}
