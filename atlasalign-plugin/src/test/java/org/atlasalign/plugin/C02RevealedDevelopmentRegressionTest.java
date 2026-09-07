package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ij.IJ;
import ij.ImagePlus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.BaselineRegistrationProposal;
import org.atlasalign.application.MaskRegistrationEngine;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SafeImageIntakeService;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.TissueGeometryClassifier;
import org.atlasalign.application.TissueGeometryResult;
import org.atlasalign.application.TissueSegmentationResult;
import org.atlasalign.application.TissueSegmenter;
import org.atlasalign.application.c02.C02ApLocalSearchEngine;
import org.atlasalign.application.c02.C02ApLocalSearchResult;
import org.atlasalign.application.c02.C02ApPlaneScore;
import org.atlasalign.application.c02.C02SearchContext;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.atlas.AtlasRepository;
import org.atlasalign.atlas.VerifiedAtlas;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.plugin.review.AtlasPlaneRequest;
import org.atlasalign.plugin.review.VerifiedAtlasPlaneSource;
import org.atlasalign.plugin.validation.C02FeatureSearchEvidence;
import org.atlasalign.plugin.validation.C02FeatureSearchRunner;
import org.atlasalign.plugin.validation.GuidedAtlasIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Rejection-only real-image screen for the already-frozen C02 candidate.
 *
 * <p>The six cases and their reference levels were revealed before C02. This
 * test may retire C02, but cannot promote or integrate it. DeepSlice is not
 * invoked and no blind or seven-mouse truth-bearing member is opened.</p>
 */
@EnabledIfEnvironmentVariable(
        named = "ATLASALIGN_C02_REVEALED", matches = "1")
class C02RevealedDevelopmentRegressionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONFIG_SCHEMA =
            "atlasalign-phase5-c02-revealed-regression-config-v1";
    private static final String OUTPUT_SCHEMA =
            "atlasalign-phase5-c02-revealed-regression-scores-v1";
    private static final String FROZEN_CONFIG_SHA256 =
            "9bd88d7488726d041a1c6bf4a6684daf11224ab03f9394c9086f1f6fc409a488";
    private static final int EXPECTED_CASES = 6;

    @Test
    void recordsFrozenRevealedRegressionWithoutChangingProduction()
            throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory()
                        >= 3L * 1024 * 1024 * 1024,
                "Real C02 atlas scoring requires a test heap of at least 3 GB");
        final Path configPath = requiredRegularFile(
                "atlasalign.c02.revealed.config");
        final Path inputRoot = requiredDirectory(
                "atlasalign.c02.revealed.inputRoot");
        final Path atlasCache = requiredDirectory(
                "atlasalign.c02.revealed.atlasCache");
        final Path outputPath = requiredOutput(
                "atlasalign.c02.revealed.output");
        final byte[] configBytes = Files.readAllBytes(configPath);
        assertEquals(FROZEN_CONFIG_SHA256, sha256(configBytes),
                "C02 revealed config differs from its preregistered bytes");
        final JsonNode config = MAPPER.readTree(configBytes);
        assertEquals(CONFIG_SCHEMA, requiredText(config, "schema"));
        assertTrue(config.path("developmentOnly").asBoolean(false));
        assertTrue(config.path("truthAlreadyRevealed").asBoolean(false));
        assertFalse(config.path("productionIntegration").asBoolean(true));
        assertFalse(config.path("blindPanelPermitted").asBoolean(true));
        final JsonNode cases = config.path("cases");
        assertTrue(cases.isArray());
        assertEquals(EXPECTED_CASES, cases.size());

        final VerifiedAtlas atlas = new AtlasRepository()
                .openAllenMouse25um(atlasCache);
        final String atlasIdentityBefore = GuidedAtlasIdentity.sha256(atlas);
        final VerifiedAtlasPlaneSource atlasPlanes =
                new VerifiedAtlasPlaneSource(atlas);
        final ObjectNode output = MAPPER.createObjectNode();
        output.put("schema", OUTPUT_SCHEMA);
        output.put("candidateId", requiredText(config, "candidateId"));
        output.put("developmentOnly", true);
        output.put("truthAlreadyRevealed", true);
        output.put("productionIntegration", false);
        output.put("blindPanelAccessed", false);
        output.put("sevenMouseTruthAccessed", false);
        output.put("deepSliceInvoked", false);
        output.put("configPath", configPath.toString());
        output.put("configSha256", sha256(configBytes));
        output.put("inputRoot", inputRoot.toString());
        output.put("atlasCache", atlasCache.toString());
        output.put("atlasIdentitySha256", atlasIdentityBefore);
        output.put("runtimeReleaseId",
                requiredText(config, "runtimeReleaseId"));
        output.put("runtimeManifestSha256",
                requiredText(config, "runtimeManifestSha256"));
        output.put("startedAt", Instant.now().toString());
        final long panelStarted = System.nanoTime();
        final ArrayNode outputCases = output.putArray("cases");
        for (final JsonNode configured : cases) {
            outputCases.add(runCase(
                    configured,
                    config,
                    inputRoot,
                    atlasIdentityBefore,
                    atlasPlanes));
        }
        final String atlasIdentityAfter = GuidedAtlasIdentity.sha256(atlas);
        assertEquals(atlasIdentityBefore, atlasIdentityAfter);
        output.put("caseCount", outputCases.size());
        output.put("panelDurationMillis", Duration.ofNanos(
                System.nanoTime() - panelStarted).toMillis());
        output.put("finishedAt", Instant.now().toString());
        output.put("sourceIntegrityPassed", outputCases.findValues(
                "sourceIntegrityPassed").stream()
                .allMatch(JsonNode::asBoolean));
        output.put("atlasIdentityAfterSha256", atlasIdentityAfter);
        output.put("atlasIntegrityPassed", true);
        output.put("resultInterpretation",
                "REVEALED_REJECTION_SCREEN_ONLY_NOT_PRODUCTION_EVIDENCE");
        writeCreateOnlyReadOnly(outputPath, output);
    }

    private static ObjectNode runCase(
            final JsonNode configured,
            final JsonNode config,
            final Path inputRoot,
            final String atlasIdentity,
            final VerifiedAtlasPlaneSource atlasPlanes) throws Exception {
        final String filename = requiredText(configured, "filename");
        final Path input = resolveContained(
                inputRoot,
                requiredText(configured, "inputRelativePath"));
        final Path parentSource = resolveContained(
                inputRoot, "sources/" + filename);
        final String inputSha = requiredText(configured, "inputSha256");
        final String parentSha = requiredText(
                configured, "parentSourceSha256");
        assertEquals(inputSha, sha256(input));
        assertEquals(parentSha, sha256(parentSource));
        final String beforeInputFileSha = sha256(input);
        final String beforeParentFileSha = sha256(parentSource);
        final ImagePlus source = IJ.openImage(input.toString());
        assertNotNull(source, "ImageJ could not open " + input);
        final long caseStarted = System.nanoTime();
        try {
            final ImagePlusSourceImage readOnly =
                    new ImagePlusSourceImage(source);
            final SourceImageSnapshot before = readOnly.snapshot();
            final var safe = new SafeImageIntakeService()
                    .preparePreview(readOnly, 1, 2_048);
            final RegistrationPreview preview = safe.preview();
            final TissueSegmentationResult segmentation =
                    new TissueSegmenter().segment(
                            preview.mapping().previewWidth(),
                            preview.mapping().previewHeight(),
                            preview.pixels());
            final TissueGeometryResult geometry =
                    new TissueGeometryClassifier().classify(
                            segmentation.mask());
            assertEquals(SectionGeometry.FULL, geometry.geometry(),
                    "C02 revealed regression is restricted to FULL cases");
            final AllenCoronalLevel r3Level = new AllenCoronalLevel(
                    requiredInteger(configured, "r3Level"));
            final AtlasPlaneTilt tilt = new AtlasPlaneTilt(
                    requiredDouble(configured, "sagittalTiltDegrees"),
                    requiredDouble(configured, "horizontalTiltDegrees"));
            final AtlasCoronalPlane center = atlasPlanes.load(
                    new AtlasPlaneRequest(r3Level, tilt, true));
            final BaselineRegistrationProposal baseline =
                    new MaskRegistrationEngine().register(
                            ReviewAlignmentCommand.atlasTissueMask(
                                    center, SectionGeometry.FULL),
                            segmentation.mask(),
                            r3Level,
                            geometry);
            final C02SearchContext context = new C02SearchContext(
                    inputSha,
                    atlasIdentity,
                    requiredText(config, "runtimeReleaseId"),
                    requiredText(config, "runtimeManifestSha256"),
                    r3Level,
                    tilt,
                    baseline.affine(),
                    AtlasOrientation.valueOf(requiredText(
                            configured, "orientation")),
                    C02SearchContext.FEATURE_GENERATION_ID);
            final long searchStarted = System.nanoTime();
            final C02FeatureSearchEvidence evidence =
                    new C02FeatureSearchRunner().runEvidence(
                            preview,
                            segmentation.mask(),
                            geometry.geometry(),
                            context,
                            inputSha,
                            atlasIdentity,
                            atlasPlanes);
            final long searchMillis = Duration.ofNanos(
                    System.nanoTime() - searchStarted).toMillis();
            final SourceImageSnapshot after = readOnly.snapshot();
            assertEquals(before, after,
                    "C02 changed the source ImagePlus for " + filename);
            assertEquals(beforeInputFileSha, sha256(input));
            assertEquals(beforeParentFileSha, sha256(parentSource));

            final ObjectNode record = MAPPER.createObjectNode();
            record.put("caseId", requiredText(configured, "caseId"));
            record.put("specimenId", requiredText(configured, "specimenId"));
            record.put("filename", filename);
            record.put("parentSourceSha256", parentSha);
            record.put("inputRelativePath",
                    requiredText(configured, "inputRelativePath"));
            record.put("inputSha256", inputSha);
            record.put("sourcePixelSha256", before.pixelSha256());
            record.put("previewWidth", preview.mapping().previewWidth());
            record.put("previewHeight", preview.mapping().previewHeight());
            record.put("geometry", geometry.geometry().name());
            record.put("syntheticPixelCount", 0);
            record.put("segmentationMethod", segmentation.method().name());
            record.put("segmentationForegroundFraction",
                    segmentation.foregroundFraction());
            record.put("r3Level", r3Level.zeroBasedAnteriorPosteriorIndex());
            record.put("truthLevel",
                    requiredInteger(configured, "truthLevel"));
            record.put("sagittalTiltDegrees", tilt.sagittalDegrees());
            record.put("horizontalTiltDegrees", tilt.horizontalDegrees());
            record.put("orientation", context.fixedOrientation().name());
            record.put("baselineSimilarityDice", baseline.similarityDice());
            record.put("baselineAffineDice", baseline.affineDice());
            record.set("searchContext", context(context));
            record.put("observedSupportSha256",
                    evidence.supportMaskSha256());
            record.put("eligibleCentersSha256",
                    evidence.eligibleCentersSha256());
            record.put("tissueFeatureSha256",
                    evidence.tissueFeatureSha256());
            record.put("tissueDescriptorSha256",
                    evidence.tissueDescriptorSha256());
            appendCandidates(
                    record.putArray("candidates"),
                    evidence.result(),
                    evidence.atlasFeatureSha256ByLevel(),
                    evidence.atlasDescriptorSha256ByLevel(),
                    evidence);
            record.put("assessable", evidence.result().assessable());
            final ArrayNode reasons = record.putArray("failureReasons");
            evidence.result().failureReasons().forEach(reasons::add);
            record.put("selectedAtSearchBoundary",
                    evidence.result().selectedAtSearchBoundary());
            record.put("searchDurationMillis", searchMillis);
            record.put("caseDurationMillis", Duration.ofNanos(
                    System.nanoTime() - caseStarted).toMillis());
            record.put("sourceIntegrityPassed", true);
            return record;
        } finally {
            source.close();
        }
    }

    private static ObjectNode context(final C02SearchContext context) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put("schema", C02SearchContext.SCHEMA);
        node.put("identitySha256", context.identitySha256());
        node.put("sourceSha256", context.sourceSha256());
        node.put("atlasIdentitySha256", context.atlasIdentitySha256());
        node.put("runtimeReleaseId", context.runtimeReleaseId());
        node.put("runtimeManifestSha256",
                context.runtimeManifestSha256());
        node.put("r3Level",
                context.r3Level().zeroBasedAnteriorPosteriorIndex());
        node.put("sagittalTiltDegrees",
                context.fixedTilt().sagittalDegrees());
        node.put("horizontalTiltDegrees",
                context.fixedTilt().horizontalDegrees());
        node.put("orientation", context.fixedOrientation().name());
        node.put("featureGenerationId", context.featureGenerationId());
        node.set("fixedTransform", affine(context.fixedAtlasToFeature()));
        return node;
    }

    private static ObjectNode affine(final AffineTransform2D affine) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put("sourceSpace", affine.sourceSpace().name());
        node.put("destinationSpace", affine.destinationSpace().name());
        node.put("m00", affine.m00());
        node.put("m01", affine.m01());
        node.put("m02", affine.m02());
        node.put("m10", affine.m10());
        node.put("m11", affine.m11());
        node.put("m12", affine.m12());
        return node;
    }

    private static void appendCandidates(
            final ArrayNode target,
            final C02ApLocalSearchResult result,
            final Map<Integer, String> atlasFeatureHashes,
            final Map<Integer, String> atlasDescriptorHashes,
            final C02FeatureSearchEvidence evidence) {
        for (final C02ApPlaneScore planeScore : result.rawScores()) {
            final int level = planeScore.candidate().coronalLevel()
                    .zeroBasedAnteriorPosteriorIndex();
            final var score = planeScore.mind();
            final ObjectNode node = target.addObject();
            node.put("apOffset", planeScore.candidate().apOffsetIndices());
            node.put("level", level);
            if (score.meanSquaredDescriptorDifference().isPresent()) {
                node.put("mindDistance", score
                        .meanSquaredDescriptorDifference().getAsDouble());
            } else {
                node.putNull("mindDistance");
            }
            node.put("completeObservedPixelCount",
                    score.completeObservedPixelCount());
            node.put("eligibleCenterCount", score.eligibleCenterCount());
            node.put("featureSupportFraction",
                    score.featureSupportFraction());
            final boolean assessable = score.assessable(
                    C02ApLocalSearchEngine.MINIMUM_FEATURE_SUPPORT_FRACTION);
            node.put("assessable", assessable);
            final ArrayNode candidateReasons =
                    node.putArray("failureReasons");
            if (!assessable) {
                candidateReasons.add(
                        "unavailable or less than 5% complete-observed "
                                + "MIND support");
            }
            node.put("descriptorRevision",
                    C02SearchContext.FEATURE_GENERATION_ID);
            node.put("searchContextSha256", evidence.contextSha256());
            node.put("observedSupportSha256",
                    evidence.supportMaskSha256());
            node.put("eligibleCentersSha256",
                    evidence.eligibleCentersSha256());
            node.put("tissueFeatureSha256",
                    evidence.tissueFeatureSha256());
            node.put("atlasFeatureSha256", atlasFeatureHashes.get(level));
            node.put("tissueDescriptorSha256",
                    evidence.tissueDescriptorSha256());
            node.put("atlasDescriptorSha256",
                    atlasDescriptorHashes.get(level));
        }
    }

    private static Path resolveContained(
            final Path root,
            final String relativeText) throws IOException {
        final Path relative = Path.of(relativeText);
        assertFalse(relative.isAbsolute());
        assertEquals(relative, relative.normalize());
        final Path resolved = root.resolve(relative).normalize().toRealPath();
        assertTrue(resolved.startsWith(root));
        assertFalse(Files.isSymbolicLink(resolved));
        assertTrue(Files.isRegularFile(
                resolved, LinkOption.NOFOLLOW_LINKS));
        return resolved;
    }

    private static Path requiredRegularFile(final String property)
            throws IOException {
        final String value = System.getProperty(property, "");
        assertFalse(value.isBlank(), "Missing -D" + property);
        final Path real = Path.of(value).toRealPath();
        assertFalse(Files.isSymbolicLink(real));
        assertTrue(Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS));
        return real;
    }

    private static Path requiredDirectory(final String property)
            throws IOException {
        final String value = System.getProperty(property, "");
        assertFalse(value.isBlank(), "Missing -D" + property);
        final Path real = Path.of(value).toRealPath();
        assertFalse(Files.isSymbolicLink(real));
        assertTrue(Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS));
        return real;
    }

    private static Path requiredOutput(final String property) {
        final String value = System.getProperty(property, "");
        assertFalse(value.isBlank(), "Missing -D" + property);
        final Path path = Path.of(value).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(path.getParent()));
        assertFalse(Files.exists(path),
                "Refusing to overwrite C02 output " + path);
        return path;
    }

    private static String requiredText(
            final JsonNode node,
            final String field) {
        final JsonNode value = node.path(field);
        assertTrue(value.isTextual() && !value.asText().isBlank(),
                field + " must be non-empty text");
        return value.asText();
    }

    private static int requiredInteger(
            final JsonNode node,
            final String field) {
        final JsonNode value = node.path(field);
        assertTrue(value.isIntegralNumber(), field + " must be an integer");
        return value.intValue();
    }

    private static double requiredDouble(
            final JsonNode node,
            final String field) {
        final JsonNode value = node.path(field);
        assertTrue(value.isNumber()
                        && Double.isFinite(value.doubleValue()),
                field + " must be finite");
        return value.doubleValue();
    }

    private static String sha256(final Path path) throws Exception {
        try (InputStream input = Files.newInputStream(path)) {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void writeCreateOnlyReadOnly(
            final Path path,
            final ObjectNode result) throws Exception {
        Files.writeString(
                path,
                MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result)
                        + System.lineSeparator(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException ignored) {
            assertTrue(path.toFile().setReadOnly());
        }
    }
}
