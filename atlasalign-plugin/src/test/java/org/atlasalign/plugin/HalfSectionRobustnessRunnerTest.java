package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.process.FloatProcessor;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.atlasalign.application.DeepSliceInput;
import org.atlasalign.application.DeepSliceOuv;
import org.atlasalign.application.DeepSlicePlanePrediction;
import org.atlasalign.application.DeepSlicePredictionDiagnostics;
import org.atlasalign.application.DeepSliceRuntimeProvenance;
import org.atlasalign.application.ReadOnlySourceImage;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SafeImageIntakeService;
import org.atlasalign.application.TissueGeometryClassifier;
import org.atlasalign.application.TissueSegmenter;
import org.atlasalign.application.ValidationHalfDerivative;
import org.atlasalign.application.ValidationHalfDerivativeBuilder;
import org.atlasalign.application.ValidationHalfDerivativeCondition;
import org.atlasalign.application.ValidationHalfDerivativePreparationOutcome;
import org.atlasalign.application.VirtualHalfPayloadHashes;
import org.atlasalign.application.VirtualHalfPreviewBuilder;
import org.atlasalign.core.CalibrationMetadata;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.core.SourceImageMetadata;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.core.StackPlaneLabel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Focused unit coverage for the Phase 5 controlled half diagnostic harness. */
@DisabledOnOs(value = OS.WINDOWS, disabledReason =
        "Diagnostic evidence writer requires directory fsync unavailable on Windows NIO")
class HalfSectionRobustnessRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HalfSectionRobustnessRunner.ProviderRuntime TEST_RUNTIME =
            new HalfSectionRobustnessRunner.NonRealTestOnly("deterministic-fake");

    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesTheExactTwelveCaseSourceAndConditionOrder()
            throws Exception {
        final List<DeepSliceInput> providerInputs = new ArrayList<>();
        final List<HalfSectionRobustnessRunner.CaseResult> results =
                new HalfSectionRobustnessRunner().runAuthorizedDevelopmentHalfPanel(
                        authorizedSources(),
                        1,
                        128,
                        input -> {
                            providerInputs.add(input);
                            return fakePrediction();
                        },
                        TEST_RUNTIME,
                        writer("ordered"));

        assertEquals(12, results.size());
        assertEquals(12, providerInputs.size());
        final List<String> expected = new ArrayList<>();
        for (final String source : HalfSectionRobustnessRunner.AUTHORIZED_SOURCE_IDS) {
            for (final ValidationHalfDerivativeCondition condition
                    : HalfSectionRobustnessRunner.ORDERED_HALF_CONDITIONS) {
                expected.add(source + ":" + condition.name());
            }
        }
        assertEquals(expected, results.stream()
                .map(HalfSectionRobustnessRunner.SuccessfulCase.class::cast)
                .map(result -> result.event().sourceId() + ":"
                        + result.event().condition())
                .toList());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
                results.stream().map(HalfSectionRobustnessRunner.SuccessfulCase.class::cast)
                        .map(result -> result.event().ordinal()).toList());
    }

    @Test
    void rejectsAnyDeviationFromTheFrozenSourceOrderBeforeProviderCalls()
            throws Exception {
        final List<HalfSectionRobustnessRunner.SourceInput> reversed =
                authorizedSources();
        final HalfSectionRobustnessRunner.SourceInput first = reversed.remove(0);
        reversed.add(first);
        final AtomicInteger calls = new AtomicInteger();

        assertThrows(IllegalArgumentException.class,
                () -> new HalfSectionRobustnessRunner()
                        .runAuthorizedDevelopmentHalfPanel(
                                reversed,
                                1,
                                128,
                                input -> {
                                    calls.incrementAndGet();
                                    return fakePrediction();
                                },
                                TEST_RUNTIME,
                                writer("wrong-order")));
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsAnIneligibleGeometryWithoutCallingTheProvider()
            throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final HalfSectionRobustnessRunner.CaseResult result =
                new HalfSectionRobustnessRunner().runSingleAuthorizedHalfCase(
                        1,
                        source("GLT1a s094", "damaged.tif", damagedPixels()),
                        ValidationHalfDerivativeCondition.IMAGE_LEFT_FULL_CANVAS,
                        1,
                        128,
                        input -> {
                            calls.incrementAndGet();
                            return fakePrediction();
                        },
                        TEST_RUNTIME,
                        writer("ineligible"));

        final HalfSectionRobustnessRunner.FailedCase failed = assertInstanceOf(
                HalfSectionRobustnessRunner.FailedCase.class, result);
        assertEquals(0, calls.get());
        assertEquals("INELIGIBLE_SOURCE_GEOMETRY", failed.event().failureCode());
        assertEquals("FAILURE", read(failed.eventPath()).path("status").asText());
    }

    @Test
    void refusesRequestedRealEvidenceForAnExplicitTestOnlySource()
            throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final HalfSectionRobustnessRunner.FailedCase failed = assertInstanceOf(
                HalfSectionRobustnessRunner.FailedCase.class,
                new HalfSectionRobustnessRunner().runSingleAuthorizedHalfCase(
                        1,
                        source("GLT1a s094", "unbound-real.tif", fullPixels()),
                        ValidationHalfDerivativeCondition.IMAGE_LEFT_FULL_CANVAS,
                        1,
                        128,
                        providerInput -> {
                            calls.incrementAndGet();
                            return fakePrediction();
                        },
                        new HalfSectionRobustnessRunner
                                .AuthorizedProtocolV2R3(),
                        writer("unbound-real")));
        assertEquals(0, calls.get());
        assertEquals("SOURCE_INTEGRITY_FAILURE", failed.event().failureCode());
        assertEquals("AUTHORIZED_PROTOCOL_V2_R3_REQUESTED",
                failed.event().providerRuntime().evidenceClass());
    }

    @Test
    void passesOnlyProductionReadyInputToProviderAndKeepsControlMaskSeparate()
            throws Exception {
        final List<DeepSliceInput> providerInputs = new ArrayList<>();
        final List<HalfSectionRobustnessRunner.CaseResult> results =
                new HalfSectionRobustnessRunner().runAuthorizedDevelopmentHalfPanel(
                        authorizedSources(),
                        1,
                        128,
                        input -> {
                            providerInputs.add(input);
                            return fakePrediction();
                        },
                        TEST_RUNTIME,
                        writer("boundary"));

        final List<ValidationHalfDerivative> expectedDerivatives = derivatives(
                fullPixels());
        for (int index = 0; index < 4; index++) {
            final String controlHash = VirtualHalfPayloadHashes.maskSha256(
                    expectedDerivatives.get(index).controlMask());
            final String providerSyntheticHash = VirtualHalfPayloadHashes
                    .syntheticMaskSha256(providerInputs.get(index)
                            .syntheticPixelMask());
            assertNotEquals(controlHash, providerSyntheticHash,
                    "Validation control mask leaked to provider at condition " + index);
        }
        assertTrue(results.stream().allMatch(
                HalfSectionRobustnessRunner.SuccessfulCase.class::isInstance));
    }

    @Test
    void recordsFullAndHalfFractionsAndARecomputableEnsemble()
            throws Exception {
        final HalfSectionRobustnessRunner runner = new HalfSectionRobustnessRunner();
        final HalfSectionRobustnessRunner.SourceInput full = source(
                "GLT1a s094", "full.tif", fullPixels());
        final List<DeepSliceInput> captured = new ArrayList<>();
        final HalfSectionRobustnessRunner.SuccessfulCase fullResult =
                assertInstanceOf(HalfSectionRobustnessRunner.SuccessfulCase.class,
                        runner.runFullDiagnostic(
                                1,
                                full,
                                1,
                                128,
                                input -> {
                                    captured.add(input);
                                    return fakePrediction();
                                },
                                TEST_RUNTIME,
                                writer("fractions-full")));
        assertEquals(1.0d,
                fullResult.event().visibleTissueFractionOfFullTissue());
        assertEquals(0.0d,
                fullResult.event()
                        .controlledSyntheticFractionOfDerivativePixels());
        assertEquals(0.0d,
                fullResult.event()
                        .productionSyntheticPixelFractionOfInferencePixels());
        assertTrue(captured.get(0).syntheticPixelMask().isEmpty());
        assertTrue(fullResult.event().derivativeIdentitySha256().isEmpty());
        assertTrue(fullResult.event().preparationIdentitySha256().isEmpty());
        final JsonNode fullEvent = read(fullResult.eventPath());
        assertSerializedInferenceInputIdentity(fullEvent,
                fullResult.providerInput());

        final HalfSectionRobustnessRunner.SuccessfulCase halfResult =
                assertInstanceOf(HalfSectionRobustnessRunner.SuccessfulCase.class,
                        runner.runSingleAuthorizedHalfCase(
                                1,
                                source("GLT1a s094", "half.tif", fullPixels()),
                                ValidationHalfDerivativeCondition
                                        .IMAGE_LEFT_FULL_CANVAS,
                                1,
                                128,
                                input -> fakePrediction(),
                                TEST_RUNTIME,
                                writer("fractions-half")));
        assertTrue(halfResult.event().visibleTissueFractionOfFullTissue() > 0.0d);
        assertTrue(halfResult.event().visibleTissueFractionOfFullTissue() < 1.0d);
        assertTrue(halfResult.event()
                .controlledSyntheticFractionOfDerivativePixels() > 0.0d);
        assertTrue(halfResult.event()
                .productionSyntheticPixelFractionOfInferencePixels() > 0.0d);
        assertTrue(halfResult.event().derivativeIdentitySha256().isPresent());
        assertTrue(halfResult.event().preparationIdentitySha256().isPresent());

        final JsonNode event = read(halfResult.eventPath());
        assertSerializedInferenceInputIdentity(event, halfResult.providerInput());
        assertNotEquals(fullEvent.path("inferenceInputIdentitySha256").asText(),
                event.path("inferenceInputIdentitySha256").asText());
        final JsonNode primary = event.path("primary").path("componentsOuv");
        final JsonNode secondary = event.path("secondary").path("componentsOuv");
        final JsonNode ensemble = event.path("ensemble").path("componentsOuv");
        assertEquals(9, primary.size());
        assertEquals("ox,oy,oz,ux,uy,uz,vx,vy,vz",
                event.path("primary").path("componentOrder").asText());
        for (int index = 0; index < 9; index++) {
            final double expected = (primary.get(index).asDouble()
                    + secondary.get(index).asDouble()) / 2.0d;
            assertEquals(Double.doubleToLongBits(expected),
                    Double.doubleToLongBits(ensemble.get(index).asDouble()));
        }
        assertTrue(event.path("primary").has("centerDepth"));
        assertTrue(event.path("primary").has("allenAxis0"));
        assertTrue(event.path("primarySecondaryDisagreement")
                .path("allenAxis0Caution").asBoolean());
    }

    @Test
    void rejectsForgedInferenceInputIdentityMetadata() {
        final DeepSliceInput input = new DeepSliceInput(
                2, 2, new float[]{1.0f, 2.0f, 3.0f, 4.0f},
                org.atlasalign.core.BinaryMask.empty(2, 2));
        final HalfSectionRobustnessRunner.InferenceInputIdentity identity =
                HalfSectionRobustnessRunner.InferenceInputIdentity.from(input);

        assertThrows(IllegalArgumentException.class,
                () -> new HalfSectionRobustnessRunner.InferenceInputIdentity(
                        identity.width(),
                        identity.height(),
                        identity.pixelsSha256(),
                        identity.productionSyntheticMaskSha256(),
                        "0".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new HalfSectionRobustnessRunner.InferenceInputIdentity(
                        identity.width() + 1,
                        identity.height(),
                        identity.pixelsSha256(),
                        identity.productionSyntheticMaskSha256(),
                        identity.inferenceInputIdentitySha256()));
    }

    @Test
    void capturedRealSourceUsesExactBytesAndFailsClosedOnReplacement()
            throws Exception {
        final Path input = writeTiff("captured.tif", 2, 2,
                new float[]{1.0f, 2.0f, 3.0f, 4.0f});
        final HalfSectionRobustnessRunner.CapturedSourceBytes captured =
                HalfSectionRobustnessRunner.captureRealSourceBytes(input);
        assertArrayEquals(Files.readAllBytes(input), captured.copyOfBytes());

        try (HalfSectionRobustnessRunner.CapturedImageJSource decoded = captured
                .decodeTiffSource()) {
            final HalfSectionRobustnessRunner.SourceInput source =
                    decoded.sourceInput("GLT1a s094");
            assertEquals(2, source.source().snapshot().metadata().width());
            assertEquals(2, source.source().snapshot().metadata().height());
            assertEquals(Optional.of(captured.sha256()),
                    source.expectedCapturedSourceFileSha256());
            Files.writeString(input, "replacement-after-capture");
            final AtomicInteger calls = new AtomicInteger();

            final HalfSectionRobustnessRunner.FailedCase failed = assertInstanceOf(
                    HalfSectionRobustnessRunner.FailedCase.class,
                    new HalfSectionRobustnessRunner().runSingleAuthorizedHalfCase(
                            1,
                            source,
                            ValidationHalfDerivativeCondition
                                    .IMAGE_LEFT_FULL_CANVAS,
                            1,
                            128,
                            providerInput -> {
                                calls.incrementAndGet();
                                return fakePrediction();
                            },
                            new HalfSectionRobustnessRunner
                                    .AuthorizedProtocolV2R3(),
                            writer("captured-replacement")));
            assertEquals(0, calls.get());
            assertEquals("SOURCE_INTEGRITY_FAILURE", failed.event().failureCode());
            assertFalse(failed.event().sourceIntegrityVerified());
        }
    }

    @Test
    void capturedRealSourceDecodesPngWithoutReopeningTheLivePath()
            throws Exception {
        final Path input = writePng("captured.png", 3, 2);
        final HalfSectionRobustnessRunner.CapturedSourceBytes captured =
                HalfSectionRobustnessRunner.captureRealSourceBytes(input);
        Files.writeString(input, "replacement-after-capture");

        try (HalfSectionRobustnessRunner.CapturedImageJSource decoded = captured
                .decodeImageSource()) {
            final HalfSectionRobustnessRunner.SourceInput source = decoded
                    .sourceInput("GLT1a s094");
            assertEquals(3, source.source().snapshot().metadata().width());
            assertEquals(2, source.source().snapshot().metadata().height());
            assertEquals(Optional.of(captured.sha256()),
                    source.expectedCapturedSourceFileSha256());
        }
    }

    @Test
    void capturedRealSourceRejectsUnsupportedImageTypes() throws Exception {
        final Path input = writeInput("captured.jpg");
        final HalfSectionRobustnessRunner.CapturedSourceBytes captured =
                HalfSectionRobustnessRunner.captureRealSourceBytes(input);

        assertThrows(IOException.class, captured::decodeImageSource);
    }

    @Test
    void rejectsSymlinkBeforeCapturingRealSourceBytes() throws Exception {
        final Path target = writeInput("captured-target.tif");
        final Path symlink = temporaryDirectory.resolve("captured-link.tif");
        Files.createSymbolicLink(symlink, target);

        assertThrows(IOException.class,
                () -> HalfSectionRobustnessRunner.captureRealSourceBytes(symlink));
    }

    @Test
    void capturedRealSourceChecksTheExpectedHashAgainAfterProvider()
            throws Exception {
        final Path input = writeTiff("captured-post-provider.tif", 121, 81,
                fullPixels());
        final HalfSectionRobustnessRunner.CapturedSourceBytes captured =
                HalfSectionRobustnessRunner.captureRealSourceBytes(input);
        try (HalfSectionRobustnessRunner.CapturedImageJSource decoded = captured
                .decodeTiffSource()) {
            final HalfSectionRobustnessRunner.SourceInput source =
                    decoded.sourceInput("GLT1a s094");
            final AtomicInteger calls = new AtomicInteger();
            final HalfSectionRobustnessRunner.FailedCase failed = assertInstanceOf(
                    HalfSectionRobustnessRunner.FailedCase.class,
                    new HalfSectionRobustnessRunner().runSingleAuthorizedHalfCase(
                            1,
                            source,
                            ValidationHalfDerivativeCondition
                                    .IMAGE_LEFT_FULL_CANVAS,
                            1,
                            128,
                            providerInput -> {
                                calls.incrementAndGet();
                                try {
                                    Files.writeString(input,
                                            "replacement-after-provider");
                                } catch (final IOException error) {
                                    throw new IllegalStateException(error);
                                }
                                return fakePrediction();
                            },
                            new HalfSectionRobustnessRunner
                                    .AuthorizedProtocolV2R3(),
                            writer("captured-post-provider")));
            assertEquals(1, calls.get());
            assertEquals("SOURCE_INTEGRITY_FAILURE", failed.event().failureCode());
            assertFalse(failed.event().sourceIntegrityVerified());
            assertTrue(failed.event().inferenceInputIdentity().isPresent());
            assertTrue(failed.event().finishedAtUtc().isPresent());
        }
    }

    @Test
    void retainsOnlyProviderDurationWhenPostCallValidationFails()
            throws Exception {
        final Instant started = Instant.parse("2026-08-02T18:00:00Z");
        final Instant finished = Instant.parse("2026-08-02T18:00:00.000000041Z");
        final HalfSectionRobustnessRunner runner = runnerWithClock(
                new SequenceProviderCallClock(
                        List.of(started, finished), List.of(100L, 141L)));
        final List<DeepSliceInput> providerInputs = new ArrayList<>();

        final HalfSectionRobustnessRunner.FailedCase failed = assertInstanceOf(
                HalfSectionRobustnessRunner.FailedCase.class,
                runner.runFullDiagnostic(
                        1,
                        source("GLT1a s094", "timing.tif", fullPixels()),
                        1,
                        128,
                        providerInput -> {
                            providerInputs.add(providerInput);
                            return new DeepSlicePlanePrediction(427, 0.0d, 0.0d);
                        },
                        TEST_RUNTIME,
                        writer("timing")));

        assertEquals(started, failed.event().startedAtUtc().orElseThrow());
        assertEquals(finished, failed.event().finishedAtUtc().orElseThrow());
        assertEquals(41L, failed.event().runtimeNanoseconds());
        assertTrue(failed.event().inferenceInputIdentity().isPresent());
        final JsonNode event = read(failed.eventPath());
        assertEquals(41L, event.path("runtimeNanoseconds").asLong());
        assertSerializedInferenceInputIdentity(event, providerInputs.get(0));
    }

    @Test
    void rejectsPredictionsWithoutFiniteNormalizedVectorsAndMarksFakeEvidence()
            throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new DeepSliceOuv(Double.NaN, 1, 1, 1, 1, 1, 1, 1, 1));
        final HalfSectionRobustnessRunner.CaseResult result =
                new HalfSectionRobustnessRunner().runFullDiagnostic(
                        1,
                        source("GLT1a s094", "legacy.tif", fullPixels()),
                        1,
                        128,
                        input -> new DeepSlicePlanePrediction(427, 0.0d, 0.0d),
                        TEST_RUNTIME,
                        writer("legacy"));
        final HalfSectionRobustnessRunner.FailedCase failed = assertInstanceOf(
                HalfSectionRobustnessRunner.FailedCase.class, result);
        final JsonNode event = read(failed.eventPath());
        assertEquals("FAILURE", event.path("status").asText());
        assertTrue(event.path("primary").isNull());
        assertEquals("NON_REAL_TEST_ONLY", event.path("providerRuntime")
                .path("evidenceClass").asText());
        assertFalse(event.path("providerRuntime").path("realInference").asBoolean());
        assertTrue(event.path("providerRuntime")
                .path("verifiedRuntimeProvenance").isNull());
    }

    @Test
    void rejectsNonR3ProvenanceBeforeItCanBecomeRealEvidence()
            throws Exception {
        final HalfSectionRobustnessRunner.CaseResult result;
        try (HalfSectionRobustnessRunner.CapturedImageJSource captured =
                capturedFullImageJSource("wrong-runtime.tif")) {
            result = new HalfSectionRobustnessRunner().runFullDiagnostic(
                    1,
                    captured.sourceInput("GLT1a s094"),
                    1,
                    128,
                    input -> predictionWithWrongRuntime(),
                    new HalfSectionRobustnessRunner.AuthorizedProtocolV2R3(),
                    writer("wrong-runtime"));
        }
        final HalfSectionRobustnessRunner.FailedCase failed = assertInstanceOf(
                HalfSectionRobustnessRunner.FailedCase.class, result);
        final JsonNode event = read(failed.eventPath());
        assertEquals("FAILURE", event.path("status").asText());
        assertEquals("AUTHORIZED_PROTOCOL_V2_R3_REQUESTED",
                event.path("providerRuntime").path("evidenceClass").asText());
        assertFalse(event.path("providerRuntime").path("realInference")
                .asBoolean());
        assertTrue(event.path("providerRuntime")
                .path("verifiedRuntimeProvenance").isNull());
    }

    @Test
    void usesCreateOnlyEventsAndRejectsExistingOrSymlinkTargets()
            throws Exception {
        final HalfSectionRobustnessRunner runner = new HalfSectionRobustnessRunner();
        final HalfSectionRobustnessRecordWriter writer = writer("exclusive");
        final HalfSectionRobustnessRunner.SourceInput source = source(
                "GLT1a s094", "exclusive.tif", fullPixels());
        final HalfSectionRobustnessRunner.SuccessfulCase first =
                assertInstanceOf(HalfSectionRobustnessRunner.SuccessfulCase.class,
                        runner.runFullDiagnostic(
                                1, source, 1, 128,
                                input -> fakePrediction(), TEST_RUNTIME, writer));
        final byte[] original = Files.readAllBytes(first.eventPath());
        assertThrows(IOException.class,
                () -> runner.runFullDiagnostic(
                        1, source, 1, 128,
                        input -> fakePrediction(), TEST_RUNTIME, writer));
        assertEquals(new String(original), Files.readString(first.eventPath()));

        final Path symlinkDirectory = temporaryDirectory.resolve("symlink-events");
        Files.createDirectory(symlinkDirectory);
        final Path target = temporaryDirectory.resolve("unrelated-target.json");
        Files.writeString(target, "unrelated");
        final Path symlink = symlinkDirectory.resolve(
                "0001-GLT1a_s094-FULL_DIAGNOSTIC.json");
        Files.createSymbolicLink(symlink, target);
        final HalfSectionRobustnessRecordWriter symlinkWriter =
                new HalfSectionRobustnessRecordWriter(symlinkDirectory);
        assertThrows(IOException.class,
                () -> runner.runFullDiagnostic(
                        1,
                        source("GLT1a s094", "symlink.tif", fullPixels()),
                        1,
                        128,
                        input -> fakePrediction(),
                        TEST_RUNTIME,
                        symlinkWriter));
        assertEquals("unrelated", Files.readString(target));
    }

    @Test
    void writesFailureEventsAndFailsClosedWhenSourceIntegrityChanges()
            throws Exception {
        final MutableFakeSource mutable = new MutableFakeSource(fullPixels());
        final Path input = writeInput("mutable.tif");
        final HalfSectionRobustnessRunner.SourceInput source =
                HalfSectionRobustnessRunner.SourceInput.testOnly(
                        "GLT1a s094", input, mutable);
        final HalfSectionRobustnessRunner.CaseResult result =
                new HalfSectionRobustnessRunner().runFullDiagnostic(
                        1,
                        source,
                        1,
                        128,
                        providerInput -> {
                            mutable.markChanged();
                            return fakePrediction();
                        },
                        TEST_RUNTIME,
                        writer("integrity"));
        final HalfSectionRobustnessRunner.FailedCase failed = assertInstanceOf(
                HalfSectionRobustnessRunner.FailedCase.class, result);
        final JsonNode event = read(failed.eventPath());
        assertEquals("SOURCE_INTEGRITY_FAILURE", event.path("failureCode").asText());
        assertFalse(event.path("sourceIntegrityVerified").asBoolean());
        assertTrue(event.path("primary").isNull());
    }

    private List<HalfSectionRobustnessRunner.SourceInput> authorizedSources()
            throws IOException {
        final List<HalfSectionRobustnessRunner.SourceInput> sources =
                new ArrayList<>();
        for (int index = 0;
                index < HalfSectionRobustnessRunner.AUTHORIZED_SOURCE_IDS.size();
                index++) {
            sources.add(source(HalfSectionRobustnessRunner.AUTHORIZED_SOURCE_IDS
                    .get(index), "source-" + index + ".tif", fullPixels()));
        }
        return sources;
    }

    private HalfSectionRobustnessRunner.SourceInput source(
            final String sourceId,
            final String filename,
            final float[] pixels) throws IOException {
        return HalfSectionRobustnessRunner.SourceInput.testOnly(
                sourceId, writeInput(filename), new MutableFakeSource(pixels));
    }

    private Path writeInput(final String filename) throws IOException {
        final Path input = temporaryDirectory.resolve(filename);
        Files.writeString(input, "test-only-source-" + filename);
        return input;
    }

    private Path writeTiff(
            final String filename,
            final int width,
            final int height,
            final float[] pixels) throws IOException {
        final Path input = temporaryDirectory.resolve(filename);
        final ImagePlus image = new ImagePlus(filename,
                new FloatProcessor(width, height, pixels));
        try {
            if (!new FileSaver(image).saveAsTiff(input.toString())) {
                throw new IOException("Could not create TIFF fixture: " + input);
            }
        } finally {
            image.close();
        }
        return input;
    }

    private Path writePng(
            final String filename,
            final int width,
            final int height) throws IOException {
        final Path input = temporaryDirectory.resolve(filename);
        final BufferedImage image = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int gray = 32 + (x + y * width) * 24;
                image.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
            }
        }
        if (!ImageIO.write(image, "PNG", input.toFile())) {
            throw new IOException("Could not create PNG fixture: " + input);
        }
        return input;
    }

    private HalfSectionRobustnessRunner.CapturedImageJSource
            capturedFullImageJSource(final String filename) throws IOException {
        return HalfSectionRobustnessRunner.captureRealSourceBytes(
                writeTiff(filename, 121, 81, fullPixels())).decodeTiffSource();
    }

    private HalfSectionRobustnessRecordWriter writer(final String name)
            throws IOException {
        final Path directory = temporaryDirectory.resolve(name);
        Files.createDirectory(directory);
        return new HalfSectionRobustnessRecordWriter(directory);
    }

    private JsonNode read(final Path event) throws IOException {
        return MAPPER.readTree(event.toFile());
    }

    private static void assertSerializedInferenceInputIdentity(
            final JsonNode event,
            final DeepSliceInput input) {
        final HalfSectionRobustnessRunner.InferenceInputIdentity expected =
                HalfSectionRobustnessRunner.InferenceInputIdentity.from(input);
        assertEquals(expected.width(), event.path("inferenceInputWidth").asInt());
        assertEquals(expected.height(), event.path("inferenceInputHeight").asInt());
        assertEquals(expected.pixelsSha256(), event.path(
                "inferenceInputPixelsSha256").asText());
        assertEquals(expected.productionSyntheticMaskSha256(), event.path(
                "productionSyntheticMaskSha256").asText());
        assertEquals(expected.inferenceInputIdentitySha256(), event.path(
                "inferenceInputIdentitySha256").asText());
    }

    private static HalfSectionRobustnessRunner runnerWithClock(
            final HalfSectionRobustnessRunner.ProviderCallClock clock) {
        return new HalfSectionRobustnessRunner(
                new SafeImageIntakeService(),
                new TissueSegmenter(),
                new TissueGeometryClassifier(),
                new ValidationHalfDerivativeBuilder(),
                new VirtualHalfPreviewBuilder(),
                clock);
    }

    private static List<ValidationHalfDerivative> derivatives(
            final float[] pixels) {
        final MutableFakeSource source = new MutableFakeSource(pixels);
        final RegistrationPreview preview = source.createPreview(1, 128);
        final var segmentation = new TissueSegmenter().segment(
                preview.mapping().previewWidth(), preview.mapping().previewHeight(),
                preview.pixels());
        final var geometry = new TissueGeometryClassifier().classify(
                segmentation.mask());
        final ValidationHalfDerivativePreparationOutcome.Ready ready =
                assertInstanceOf(ValidationHalfDerivativePreparationOutcome.Ready.class,
                        new ValidationHalfDerivativeBuilder().build(
                                preview, segmentation.mask(), geometry));
        return ready.derivatives();
    }

    private static DeepSlicePlanePrediction fakePrediction() {
        final DeepSliceOuv primary = new DeepSliceOuv(
                0, 100, 0, 3, 0, 0, 0, 0, -3);
        final DeepSliceOuv secondary = new DeepSliceOuv(
                0, 70, 0, 3, 0, 0, 0, 0, -3);
        final DeepSliceOuv ensemble = mean(primary, secondary);
        return new DeepSlicePlanePrediction(new DeepSlicePredictionDiagnostics(
                primary, secondary, ensemble));
    }

    private static DeepSlicePlanePrediction predictionWithWrongRuntime() {
        final DeepSlicePlanePrediction ordinary = fakePrediction();
        return new DeepSlicePlanePrediction(
                ordinary.diagnostics().orElseThrow(),
                new DeepSliceRuntimeProvenance(
                        "untrusted-r3",
                        Path.of("/verified/runtime").toAbsolutePath().normalize(),
                        2,
                        1L,
                        "a".repeat(64),
                        "3.11.15",
                        "1.2.8",
                        "2.21.0",
                        "wrong-model",
                        "b".repeat(64),
                        "c".repeat(64),
                        "d".repeat(64)));
    }

    private static DeepSliceOuv mean(
            final DeepSliceOuv first, final DeepSliceOuv second) {
        final double[] values = new double[DeepSliceOuv.COMPONENT_COUNT];
        for (int index = 0; index < values.length; index++) {
            values[index] = (first.component(index)
                    + second.component(index)) / 2.0d;
        }
        return new DeepSliceOuv(values);
    }

    private static float[] fullPixels() {
        final int width = 121;
        final int height = 81;
        final float[] pixels = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final double horizontal = (x - 60.0d) / 52.0d;
                final double vertical = (y - 40.0d) / 31.0d;
                pixels[y * width + x] = horizontal * horizontal
                        + vertical * vertical <= 1.0d ? 100.0f : 0.0f;
            }
        }
        return pixels;
    }

    private static float[] damagedPixels() {
        final int width = 121;
        final int height = 81;
        final float[] pixels = new float[width * height];
        for (int y = 20; y < 60; y++) {
            for (int x = 25; x < 65; x++) {
                pixels[y * width + x] = 100.0f;
            }
        }
        return pixels;
    }

    private static final class MutableFakeSource implements ReadOnlySourceImage {

        private final float[] pixels;
        private final SourceImageMetadata metadata;
        private boolean changed;

        private MutableFakeSource(final float[] pixels) {
            this.pixels = pixels.clone();
            this.metadata = new SourceImageMetadata(
                    121,
                    81,
                    1,
                    1,
                    1,
                    32,
                    List.of("test"),
                    List.of(StackPlaneLabel.fromNullable("test")),
                    new CalibrationMetadata(
                            1.0d, 1.0d, 1.0d, 1.0d, "px", "frame"));
        }

        @Override
        public SourceImageSnapshot snapshot() {
            return new SourceImageSnapshot(metadata,
                    (changed ? "b" : "a").repeat(64));
        }

        @Override
        public RegistrationPreview createPreview(
                final int channel, final int maximumDimension) {
            return new RegistrationPreview(
                    channel,
                    1,
                    1,
                    PreviewMapping.bounded(121, 81, maximumDimension),
                    pixels);
        }

        private void markChanged() {
            changed = true;
        }
    }

    private static final class SequenceProviderCallClock
            implements HalfSectionRobustnessRunner.ProviderCallClock {

        private final List<Instant> instants;
        private final List<Long> nanoTimes;
        private int instantIndex;
        private int nanoIndex;

        private SequenceProviderCallClock(
                final List<Instant> instants,
                final List<Long> nanoTimes) {
            this.instants = List.copyOf(instants);
            this.nanoTimes = List.copyOf(nanoTimes);
        }

        @Override
        public Instant instant() {
            if (instantIndex >= instants.size()) {
                throw new AssertionError("Unexpected instant request");
            }
            return instants.get(instantIndex++);
        }

        @Override
        public long nanoTime() {
            if (nanoIndex >= nanoTimes.size()) {
                throw new AssertionError("Unexpected nanoTime request");
            }
            return nanoTimes.get(nanoIndex++);
        }
    }
}
