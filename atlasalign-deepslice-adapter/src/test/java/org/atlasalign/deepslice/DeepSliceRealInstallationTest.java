package org.atlasalign.deepslice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.imageio.ImageIO;
import org.atlasalign.application.DeepSliceInput;
import org.atlasalign.application.DeepSliceOuv;
import org.atlasalign.application.DeepSlicePlaneGeometry;
import org.atlasalign.application.DeepSlicePlanePrediction;
import org.atlasalign.application.DeepSliceRuntimeProvenance;
import org.atlasalign.core.BinaryMask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Slow production-runtime acceptance test. It is opt-in because verification
 * creates a private copy of the complete external runtime before inference.
 */
@EnabledIfEnvironmentVariable(
        named = "ATLASALIGN_REAL_DEEPSLICE", matches = "1")
class DeepSliceRealInstallationTest {

    private static final Path DEFAULT_RUNTIME = Path.of(System.getProperty("user.home"), ".atlasalign", ""
                    + "deepslice/runtime-1.2.8-py3.11.15-tf2.21.0-"
                    + "macos-arm64-r3");
    private static final Path DEFAULT_FIXTURE = Path.of(System.getProperty("user.home"), ".atlasalign", ""
                    + "deepslice/fixtures/biop-Section_s007.jpg");
    private static final Path DEFAULT_TEMPORARY_BASE = Path.of(System.getProperty("user.home"), ".atlasalign", ""
                    + "deepslice/integration-test-work");
    private static final long FIXTURE_SIZE_BYTES = 24_490;
    private static final String FIXTURE_SHA256 =
            "ec986a8046212ba1c38fd957baa7330d"
                    + "f84bee48c286e00c382d29bd42614c32";
    private static final String R3_RELEASE_ID =
            "deepslice-1.2.8-py3.11.15-tf2.21.0-macos-arm64-r3";
    private static final long R3_MANIFEST_SIZE_BYTES = 7_582_923;
    private static final String R3_MANIFEST_SHA256 =
            "fda6fe1b8d3a3ecc32f4a44f5864d8e2"
                    + "eff99014f9915a7b9be44126686f03d9";
    private static final String PRIMARY_WEIGHT_SHA256 =
            "da34a5bca0245314a68daff30c144675"
                    + "8c0851b21370ef9797d5288018b08717";
    private static final String SECONDARY_WEIGHT_SHA256 =
            "b85b7325158d117b2ac7559495c0b50d"
                    + "fcf3545aa29281a345f9ef1e33542e42";
    private static final String BACKBONE_WEIGHT_SHA256 =
            "c5bf1c05b020c4177164039b854c3ef9"
                    + "2b16b73384734647c1f0ad5cf79f6975";

    @Test
    void verifiedSandboxedRuntimeEstimatesPublicBiopSection()
            throws Exception {
        final Path runtime = configuredPath(
                "ATLASALIGN_DEEPSLICE_RUNTIME", DEFAULT_RUNTIME);
        final Path fixture = configuredPath(
                "ATLASALIGN_DEEPSLICE_FIXTURE", DEFAULT_FIXTURE);
        final Path temporaryBase = configuredPath(
                "ATLASALIGN_DEEPSLICE_TEMP", DEFAULT_TEMPORARY_BASE);
        assertEquals(FIXTURE_SIZE_BYTES, Files.size(fixture));
        assertEquals(FIXTURE_SHA256, DeepSliceIntegrity.sha256(fixture));
        final DeepSliceInput input = readGrayscale(fixture);

        try (DeepSliceProcessBridge bridge =
                AtlasAlignDeepSliceRuntime.open(
                        runtime, Duration.ofMinutes(10), temporaryBase)) {
            final DeepSlicePlanePrediction prediction =
                    bridge.estimate(input);

            assertEquals(
                    324,
                    prediction.zeroBasedAnteriorPosteriorIndex());
            assertEquals(
                    -1.029,
                    prediction.sagittalTiltDegrees(),
                    0.15);
            assertEquals(
                    0.217,
                    prediction.horizontalTiltDegrees(),
                    0.15);
            assertCompleteApplicationDiagnostics(prediction);
            assertExactRuntimeProvenance(prediction, runtime);
        }
    }

    private static void assertCompleteApplicationDiagnostics(
            final DeepSlicePlanePrediction prediction) {
        final DeepSliceOuv primary = prediction.primaryPrediction()
                .orElseThrow().ouv();
        final DeepSliceOuv secondary = prediction.secondaryPrediction()
                .orElseThrow().ouv();
        final DeepSliceOuv ensemble = prediction.ensemblePrediction()
                .orElseThrow().ouv();
        assertNotEquals(primary, secondary,
                "The two real DeepSlice model passes must remain distinct");
        assertEquals(componentWiseMean(primary, secondary), ensemble,
                "The stored ensemble must be application-normalized");

        final DeepSlicePlaneGeometry applicationGeometry =
                DeepSlicePlaneGeometry.from(ensemble);
        assertEquals(applicationGeometry.zeroBasedAnteriorPosteriorIndex(),
                prediction.zeroBasedAnteriorPosteriorIndex());
        assertEquals(applicationGeometry.sagittalTiltDegrees(),
                prediction.sagittalTiltDegrees(), 0);
        assertEquals(applicationGeometry.horizontalTiltDegrees(),
                prediction.horizontalTiltDegrees(), 0);
        assertTrue(prediction.diagnostics().isPresent());
    }

    private static void assertExactRuntimeProvenance(
            final DeepSlicePlanePrediction prediction,
            final Path runtime) throws IOException {
        final DeepSliceRuntimeProvenance provenance = prediction
                .verifiedRuntimeProvenance().orElseThrow();
        assertEquals(R3_RELEASE_ID, provenance.verifiedReleaseId());
        assertEquals(runtime.toRealPath(), provenance.canonicalRuntimePath());
        assertEquals(2, provenance.protocolVersion());
        assertEquals(R3_MANIFEST_SIZE_BYTES, provenance.manifestSizeBytes());
        assertEquals(R3_MANIFEST_SHA256, provenance.manifestSha256());
        assertEquals("3.11.15", provenance.pythonVersion());
        assertEquals("1.2.8", provenance.deepSliceVersion());
        assertEquals("2.21.0", provenance.tensorflowVersion());
        assertEquals("ebrains-mouse-ensemble-2025-01-31",
                provenance.modelRelease());
        assertEquals(PRIMARY_WEIGHT_SHA256, provenance.primaryWeightSha256());
        assertEquals(SECONDARY_WEIGHT_SHA256,
                provenance.secondaryWeightSha256());
        assertEquals(BACKBONE_WEIGHT_SHA256,
                provenance.backboneWeightSha256());
    }

    private static DeepSliceOuv componentWiseMean(
            final DeepSliceOuv first, final DeepSliceOuv second) {
        final double[] values = new double[DeepSliceOuv.COMPONENT_COUNT];
        for (int index = 0; index < values.length; index++) {
            values[index] = (first.component(index)
                    + second.component(index)) / 2;
        }
        return new DeepSliceOuv(values);
    }

    private static Path configuredPath(
            final String environmentName,
            final Path defaultPath) {
        final String configured = System.getenv(environmentName);
        return configured == null || configured.isBlank()
                ? defaultPath : Path.of(configured);
    }

    private static DeepSliceInput readGrayscale(final Path path)
            throws IOException {
        final BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) {
            throw new IOException("Could not decode fixture: " + path);
        }
        final int width = image.getWidth();
        final int height = image.getHeight();
        final float[] pixels = new float[
                Math.multiplyExact(width, height)];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int rgb = image.getRGB(x, y);
                final int red = (rgb >>> 16) & 0xff;
                final int green = (rgb >>> 8) & 0xff;
                final int blue = rgb & 0xff;
                pixels[y * width + x] = (float) (
                        0.299 * red + 0.587 * green + 0.114 * blue);
            }
        }
        return new DeepSliceInput(
                width,
                height,
                pixels,
                BinaryMask.empty(width, height));
    }
}
