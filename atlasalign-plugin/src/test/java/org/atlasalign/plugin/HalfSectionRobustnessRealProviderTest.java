package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.atlasalign.application.DeepSliceInput;
import org.atlasalign.application.DeepSlicePlaneProvider;
import org.atlasalign.deepslice.AtlasAlignDeepSliceRuntime;
import org.atlasalign.deepslice.DeepSliceProcessBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Narrow opt-in check of one real production virtual-half provider call.
 * Default Maven runs exercise only local truth-path guard tests; they skip the
 * real invocation before resolving any source, runtime, or truth-adjacent path.
 */
class HalfSectionRobustnessRealProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DEFAULT_RUNTIME = Path.of(System.getProperty("user.home"), ".atlasalign", ""
                    + "deepslice/runtime-1.2.8-py3.11.15-tf2.21.0-"
                    + "macos-arm64-r3");
    private static final Path DEFAULT_WORK = Path.of(System.getProperty("user.home"), ".atlasalign", ""
                    + "deepslice/half-section-robustness-work");
    @TempDir
    Path temporaryDirectory;

    @Test
    @EnabledIfEnvironmentVariable(
            named = "ATLASALIGN_HALF_SECTION_REAL_PROVIDER", matches = "1")
    void executesOneAuthorizedR3VirtualHalfCaseThroughProductionInput()
            throws Exception {
        final Path input = guardedExistingFile(requiredPath(
                "atlasalign.halfSection.input"));
        final Path outputDirectory = guardedExistingDirectory(requiredPath(
                "atlasalign.halfSection.outputDirectory"));
        final Path runtime = guardedExistingDirectory(configuredPath(
                "atlasalign.halfSection.runtime", DEFAULT_RUNTIME));
        final Path work = guardedExistingDirectory(configuredPath(
                "atlasalign.halfSection.work", DEFAULT_WORK));
        final String sourceId = configuredText(
                "atlasalign.halfSection.sourceId", "GLT1a s094");
        final int ordinal = configuredInt(
                "atlasalign.halfSection.ordinal", 1);
        final org.atlasalign.application.ValidationHalfDerivativeCondition
                condition = configuredCondition(
                        "atlasalign.halfSection.condition",
                        org.atlasalign.application
                                .ValidationHalfDerivativeCondition
                                .IMAGE_LEFT_FULL_CANVAS);

        final HalfSectionRobustnessRunner.CapturedSourceBytes captured =
                HalfSectionRobustnessRunner.captureRealSourceBytes(input);
        try (HalfSectionRobustnessRunner.CapturedImageJSource decoded = captured
                .decodeImageSource()) {
            final HalfSectionRobustnessRunner.SourceInput source = decoded
                    .sourceInput(sourceId);
            final AtomicReference<DeepSliceInput> received =
                    new AtomicReference<>();
            final HalfSectionRobustnessRunner.CaseResult result;
            try (DeepSliceProcessBridge bridge = AtlasAlignDeepSliceRuntime.open(
                    runtime, Duration.ofMinutes(30), work)) {
                final DeepSlicePlaneProvider observingProvider = inputPayload -> {
                    received.set(inputPayload);
                    return bridge.estimate(inputPayload);
                };
                result = new HalfSectionRobustnessRunner()
                        .runSingleAuthorizedHalfCase(
                                ordinal,
                                source,
                                condition,
                                configuredInt(
                                        "atlasalign.halfSection.channel", 1),
                                configuredInt(
                                        "atlasalign.halfSection.maximumDimension",
                                        2_048),
                                observingProvider,
                                new HalfSectionRobustnessRunner
                                        .AuthorizedProtocolV2R3(),
                                new HalfSectionRobustnessRecordWriter(
                                        outputDirectory));
            }

            final HalfSectionRobustnessRunner.SuccessfulCase successful =
                    assertInstanceOf(
                            HalfSectionRobustnessRunner.SuccessfulCase.class,
                            result,
                            "A real run may not be recorded as a fallback or partial success");
            assertSame(successful.providerInput(), received.get(),
                    "Provider must receive exactly the production Ready inference input");
            assertTrue(successful.event().preparationIdentitySha256().isPresent());
            assertTrue(successful.providerInput().syntheticPixelMask()
                    .foregroundCount() > 0);

            final JsonNode event = MAPPER.readTree(successful.eventPath().toFile());
            assertEquals("VERIFIED_PROTOCOL_V2_R3", event.path("providerRuntime")
                    .path("evidenceClass").asText());
            assertTrue(event.path("providerRuntime").path("realInference")
                    .asBoolean());
            assertEquals(HalfSectionRobustnessRunner.R3_RELEASE_ID,
                    event.path("providerRuntime")
                            .path("verifiedRuntimeProvenance")
                            .path("verifiedReleaseId").asText());
            assertEquals(2, event.path("providerRuntime")
                    .path("verifiedRuntimeProvenance")
                    .path("protocolVersion").asInt());
            assertEquals(HalfSectionRobustnessRunner.R3_MANIFEST_SHA256,
                    event.path("providerRuntime")
                            .path("verifiedRuntimeProvenance")
                            .path("manifestSha256").asText());
        }
    }

    @Test
    void rejectsNonexistentCaseInsensitiveTruthPathBeforeResolution() {
        final Path forbidden = temporaryDirectory.resolve("GrOuNd_TrUtH")
                .resolve("missing-input.tif");

        final IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> guardedExistingFile(forbidden));

        assertEquals("Real half-section test must not resolve truth paths",
                error.getMessage());
    }

    @Test
    void rejectsCanonicalRedirectionIntoTruthDirectory() throws Exception {
        final Path truthDirectory = temporaryDirectory.resolve("ground_truth");
        final Path runtimeInTruthDirectory = truthDirectory.resolve("runtime");
        Files.createDirectories(runtimeInTruthDirectory);
        final Path permittedLexicalLink = temporaryDirectory.resolve(
                "permitted-runtime-link");
        Files.createSymbolicLink(permittedLexicalLink, truthDirectory);

        final IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> guardedExistingDirectory(
                        permittedLexicalLink.resolve("runtime")));

        assertEquals("Real half-section test must not resolve truth paths",
                error.getMessage());
    }

    private static Path requiredPath(final String property) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: "
                    + property);
        }
        return Path.of(value);
    }

    private static Path configuredPath(
            final String property, final Path defaultValue) {
        final String value = System.getProperty(property);
        return value == null || value.isBlank() ? defaultValue : Path.of(value);
    }

    private static int configuredInt(
            final String property, final int defaultValue) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException error) {
            throw new IllegalArgumentException(
                    "Invalid integer property " + property, error);
        }
    }

    private static String configuredText(
            final String property, final String defaultValue) {
        final String value = System.getProperty(property);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static org.atlasalign.application.ValidationHalfDerivativeCondition
            configuredCondition(
                    final String property,
                    final org.atlasalign.application
                            .ValidationHalfDerivativeCondition defaultValue) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return org.atlasalign.application
                    .ValidationHalfDerivativeCondition.valueOf(value);
        } catch (final IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "Invalid half-section condition " + property, error);
        }
    }

    /**
     * Resolves a requested real input only after its lexical path is cleared,
     * then clears the canonical path before any caller can use it.
     */
    static Path guardedExistingFile(final Path requested)
            throws Exception {
        final Path absolute = guardedRawPath(requested);
        rejectSymlink(absolute);
        if (!Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Expected input file: " + absolute);
        }
        return guardedCanonicalPath(absolute.toRealPath());
    }

    /**
     * Resolves a requested real directory only after its lexical path is
     * cleared, then clears the canonical path before any caller can use it.
     */
    static Path guardedExistingDirectory(final Path requested)
            throws Exception {
        final Path absolute = guardedRawPath(requested);
        rejectSymlink(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Expected directory: " + absolute);
        }
        return guardedCanonicalPath(absolute.toRealPath());
    }

    private static Path guardedRawPath(final Path requested) {
        if (requested == null) {
            throw new NullPointerException("requested");
        }
        rejectGroundTruthPathLexically(requested);
        final Path absolute = requested.toAbsolutePath().normalize();
        rejectGroundTruthPathLexically(absolute);
        return absolute;
    }

    private static Path guardedCanonicalPath(final Path canonical) {
        rejectGroundTruthPathLexically(canonical);
        return canonical;
    }

    private static void rejectSymlink(final Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("Refusing symbolic link: " + path);
        }
    }

    private static void rejectGroundTruthPathLexically(final Path path) {
        for (final Path component : path) {
            if ("ground_truth".equalsIgnoreCase(component.toString())) {
                throw new IllegalArgumentException(
                        "Real half-section test must not resolve truth paths");
            }
        }
    }
}
