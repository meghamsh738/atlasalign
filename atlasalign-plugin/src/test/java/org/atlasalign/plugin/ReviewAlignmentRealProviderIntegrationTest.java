package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ij.IJ;
import ij.ImagePlus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.atlasalign.application.InitialPlaneProposal;
import org.atlasalign.application.InitialPlaneSource;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.TissueGeometryClassifier;
import org.atlasalign.application.TissueSegmentationResult;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.deepslice.AtlasAlignDeepSliceRuntime;
import org.atlasalign.deepslice.DeepSliceProcessBridge;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in blinded validation through the exact production command preparation.
 */
@EnabledIfEnvironmentVariable(
        named = "ATLASALIGN_REAL_COMMAND_INTEGRATION", matches = "1")
class ReviewAlignmentRealProviderIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofMinutes(30);
    private static final int FALLBACK_LEVEL = 264;

    @Test
    void recordsRealProviderPredictionBeforeTruthScoring() throws Exception {
        final Path requestedInput =
                requiredPath("atlasalign.validation.input");
        final Path requestedOutput =
                requiredPath("atlasalign.validation.output");
        rejectGroundTruthPath(requestedInput);
        rejectGroundTruthPath(requestedOutput);
        final Path input = exactExistingPath(
                "input", requestedInput, false);
        final Path output = resolvedOutput(requestedOutput);
        rejectGroundTruthPath(input);
        rejectGroundTruthPath(output);
        assertTrue(isTiff(input), "Blinded command input must be a TIFF");

        final Path requestedRuntime = configuredPath(
                "atlasalign.validation.runtime",
                ReviewAlignmentCommand.DEFAULT_DEEPSLICE_RUNTIME);
        final Path requestedWork = configuredPath(
                "atlasalign.validation.work",
                ReviewAlignmentCommand.DEFAULT_DEEPSLICE_WORK);
        final Path requestedAtlas = configuredPath(
                "atlasalign.validation.atlas",
                ReviewAlignmentCommand.DEFAULT_ATLAS_CACHE);
        rejectGroundTruthPath(requestedRuntime);
        rejectGroundTruthPath(requestedWork);
        rejectGroundTruthPath(requestedAtlas);
        final Path runtime = exactExistingPath(
                "runtime",
                requestedRuntime,
                true);
        final Path work = exactExistingPath(
                "work",
                requestedWork,
                true);
        final Path atlas = exactExistingPath(
                "atlas",
                requestedAtlas,
                true);
        final int channel = configuredInt(
                "atlasalign.validation.channel", 1);
        final int maximumDimension = configuredInt(
                "atlasalign.validation.maximumDimension", 2_048);
        final String imageName = requiredText(
                "atlasalign.validation.imageName");
        System.out.println("AtlasAlign validation effective paths:"
                + " input=" + input
                + "; output=" + output
                + "; runtime=" + runtime
                + "; work=" + work
                + "; atlas=" + atlas);

        final ImagePlus source = IJ.openImage(input.toString());
        assertNotNull(source, "ImageJ could not open blinded TIFF: " + input);
        try {
            final SourceImageSnapshot before =
                    new ImagePlusSourceImage(source).snapshot();
            final ReviewAlignmentCommand.ReviewLaunch launch;
            final InterfaceResponsivenessHeartbeat.Snapshot responsiveness;
            try (InterfaceResponsivenessHeartbeat heartbeat =
                    InterfaceResponsivenessHeartbeat.start()) {
                try (DeepSliceProcessBridge provider =
                        AtlasAlignDeepSliceRuntime.open(
                                runtime, TIMEOUT, work)) {
                    launch = ReviewAlignmentCommand.prepareReview(
                            source,
                            channel,
                            maximumDimension,
                            FALLBACK_LEVEL,
                            atlas,
                            Optional.of(provider));
                }
                responsiveness = heartbeat.stop();
            }
            final SourceImageSnapshot after =
                    new ImagePlusSourceImage(source).snapshot();
            final boolean sourceUnchanged = before.equals(after)
                    && after.equals(launch.safePreview().verifiedSource());
            assertTrue(sourceUnchanged, "Production preparation changed source");

            final InitialPlaneProposal proposal = launch.session()
                    .state().basis().initialPlaneProposal().orElseThrow();
            assertEquals(
                    InitialPlaneSource.LOCAL_DEEPSLICE,
                    proposal.source(),
                    "Real provider fell back; do not record fallback as prediction");
            final var prediction = proposal.prediction().orElseThrow();
            final TissueSegmentationResult segmentation = launch.session()
                    .state().basis().segmentation().orElseThrow();

            final ObjectNode record = MAPPER.createObjectNode();
            record.put("image", imageName);
            record.put("blindedInputPath", input.toString());
            final String inputSha256 = sha256(input);
            record.put("blindedInputSha256", inputSha256);
            record.put("inputTiff", input.getFileName().toString());
            record.put("inputSha256", inputSha256);
            record.put("effectiveRuntimePath", runtime.toString());
            record.put("effectiveWorkPath", work.toString());
            record.put("effectiveAtlasPath", atlas.toString());
            record.put("effectivePathsVerified", true);
            record.put("proposalSource", proposal.source().name());
            record.put(
                    "zeroBasedAnteriorPosteriorIndex",
                    proposal.coronalLevel()
                            .zeroBasedAnteriorPosteriorIndex());
            record.put(
                    "sagittalTiltDegrees",
                    prediction.sagittalTiltDegrees());
            record.put(
                    "horizontalTiltDegrees",
                    prediction.horizontalTiltDegrees());
            record.put(
                    "tissueForegroundFraction",
                    segmentation.foregroundFraction());
            record.put(
                    "tissueSegmentationMethod",
                    segmentation.method().name());
            record.put(
                    "tissueSegmentationPolarity",
                    segmentation.polarity().name());
            record.put(
                    "tissueSegmentationThreshold",
                    segmentation.threshold());
            record.put(
                    "tissueHistogramLowerBound",
                    segmentation.histogramLowerBound());
            record.put(
                    "tissueHistogramUpperBound",
                    segmentation.histogramUpperBound());
            record.put(
                    "tissuePercentileWindowFallback",
                    segmentation.percentileWindowFallback());
            record.put(
                    "tissueLargestComponentFraction",
                    segmentation.largestComponentFraction());
            record.put(
                    "tissueBorderForegroundFraction",
                    segmentation.borderForegroundFraction());
            record.put(
                    "tissueCandidateScore",
                    segmentation.candidateScore());
            final var tissueCandidates =
                    record.putArray("tissueCandidates");
            segmentation.candidates().forEach(candidate -> {
                final ObjectNode candidateRecord =
                        tissueCandidates.addObject();
                candidateRecord.put(
                        "method", candidate.method().name());
                candidateRecord.put(
                        "polarity", candidate.polarity().name());
                candidateRecord.put(
                        "threshold", candidate.threshold());
                candidate.companionThreshold().ifPresentOrElse(
                        value -> candidateRecord.put(
                                "companionThreshold", value),
                        () -> candidateRecord.putNull(
                                "companionThreshold"));
                candidateRecord.put(
                        "histogramLowerBound",
                        candidate.histogramLowerBound());
                candidateRecord.put(
                        "histogramUpperBound",
                        candidate.histogramUpperBound());
                candidateRecord.put(
                        "percentileWindowFallback",
                        candidate.percentileWindowFallback());
                candidateRecord.put(
                        "foregroundFraction",
                        candidate.foregroundFraction());
                candidateRecord.put(
                        "largestComponentFraction",
                        candidate.largestComponentFraction());
                candidateRecord.put(
                        "borderForegroundFraction",
                        candidate.borderForegroundFraction());
                candidate.exteriorBackgroundFraction().ifPresentOrElse(
                        value -> candidateRecord.put(
                                "exteriorBackgroundFraction", value),
                        () -> candidateRecord.putNull(
                                "exteriorBackgroundFraction"));
                candidate.exteriorBackgroundBorderFraction()
                        .ifPresentOrElse(
                                value -> candidateRecord.put(
                                        "exteriorBackgroundBorderFraction",
                                        value),
                                () -> candidateRecord.putNull(
                                        "exteriorBackgroundBorderFraction"));
                candidateRecord.put(
                        "score", candidate.score());
                candidateRecord.put(
                        "rejectionReason",
                        candidate.rejectionReason()
                                .orElse(""));
            });
            final var tissueAuditNotes =
                    record.putArray("tissueSegmentationAuditNotes");
            segmentation.auditNotes().forEach(tissueAuditNotes::add);
            record.put(
                    "sectionClassification",
                    classificationName(
                            launch.session().state().basis().proposal()
                                    .geometry().geometry()));
            final var geometry = launch.session().state().basis()
                    .proposal().geometry();
            final var envelope = new TissueGeometryClassifier()
                    .envelopeMeasurements(segmentation.mask());
            record.put(
                    "geometryForegroundToBoundsFraction",
                    geometry.foregroundToBoundsFraction());
            record.put(
                    "geometryWidthToHeightRatio",
                    geometry.widthToHeightRatio());
            record.put(
                    "geometryImageLeftEdgeDispersion",
                    geometry.imageLeftEdgeDispersion());
            record.put(
                    "geometryImageRightEdgeDispersion",
                    geometry.imageRightEdgeDispersion());
            record.put(
                    "geometryBilateralMirroredOverlap",
                    geometry.bilateralMirroredOverlap());
            record.put(
                    "geometryHemisphereBalance",
                    geometry.hemisphereBalance());
            record.put(
                    "geometryConnectedComponentCount",
                    geometry.connectedComponentCount());
            record.put(
                    "geometrySubstantialComponentCount",
                    geometry.substantialComponentCount());
            record.put(
                    "geometryEnvelopeForegroundToBoundsFraction",
                    envelope.foregroundToBoundsFraction());
            record.put(
                    "geometryEnvelopeBilateralMirroredOverlap",
                    envelope.bilateralMirroredOverlap());
            record.put(
                    "geometryEnvelopeHemisphereBalance",
                    envelope.hemisphereBalance());
            record.put(
                    "geometryEnvelopeAddedPreviewFraction",
                    envelope.addedPreviewFraction());
            record.put(
                    "inferenceSeconds",
                    seconds(launch.timings().inference()));
            record.put(
                    "atlasLoadSeconds",
                    seconds(launch.timings()
                            .atlasLoadAndRegistration()));
            record.put(
                    "atlasLoadTimingScope",
                    "VERIFIED_ATLAS_OPEN_PLANE_LOAD_AND_BASELINE_REGISTRATION");
            record.put(
                    "interfaceResponsive",
                    responsiveness.responsive());
            record.put(
                    "interfaceHeartbeatTicks",
                    responsiveness.ticks());
            record.put(
                    "interfaceMaximumEventLoopGapSeconds",
                    responsiveness.maximumGapSeconds());
            record.put("maximumPreviewDimension", maximumDimension);
            record.put(
                    "previewWidth",
                    launch.safePreview().preview().mapping().previewWidth());
            record.put(
                    "previewHeight",
                    launch.safePreview().preview().mapping().previewHeight());
            record.put("sourceIntegrityVerified", sourceUnchanged);
            record.put("sourcePixelSha256Before", before.pixelSha256());
            record.put("sourcePixelSha256After", after.pixelSha256());
            record.put("fallbackAllenAxis0Index", FALLBACK_LEVEL);
            record.put("runtimeTimeoutSeconds", TIMEOUT.toSeconds());

            KnownCoordinatePredictionStore.appendUnique(output, record);
        } finally {
            source.close();
        }
    }

    private static double seconds(final Duration duration) {
        return duration.toNanos() / 1_000_000_000.0;
    }

    private static String sha256(final Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
        try (InputStream input = Files.newInputStream(path);
                DigestInputStream hashing =
                        new DigestInputStream(input, digest)) {
            hashing.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String classificationName(
            final SectionGeometry geometry) {
        return geometry == SectionGeometry.FULL
                ? "COMPLETE" : geometry.name();
    }

    private static boolean isTiff(final Path input) {
        final String name = input.getFileName().toString()
                .toLowerCase(Locale.ROOT);
        return name.endsWith(".tif") || name.endsWith(".tiff");
    }

    private static void rejectGroundTruthPath(final Path path) {
        for (final Path part : path.toAbsolutePath().normalize()) {
            if ("ground_truth".equalsIgnoreCase(part.toString())) {
                throw new IllegalArgumentException(
                        "Blinded runner must never access ground_truth: "
                                + path);
            }
        }
    }

    private static Path resolvedOutput(final Path output) throws IOException {
        if (Files.isSymbolicLink(output)) {
            throw new IllegalArgumentException(
                    "Validation output must not be a symbolic link: "
                            + output);
        }
        if (Files.exists(output)) {
            return exactExistingPath("output", output, false);
        }
        final Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalArgumentException(
                    "Prediction output needs a parent directory");
        }
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException(
                    "Prediction output parent must already exist: "
                            + parent);
        }
        final Path effectiveParent = exactExistingPath(
                "output parent", parent, true);
        return effectiveParent.resolve(output.getFileName());
    }

    private static Path exactExistingPath(
            final String label,
            final Path requested,
            final boolean directory) throws IOException {
        if (directory && !Files.isDirectory(requested)
                || !directory && !Files.isRegularFile(requested)) {
            throw new IllegalArgumentException(
                    "Validation " + label + " path is missing or has "
                            + "the wrong type: " + requested);
        }
        final Path effective = requested.toRealPath();
        if (!requested.equals(effective)) {
            throw new IllegalArgumentException(
                    "Validation " + label
                            + " requested path differs from effective path: "
                            + requested + " -> " + effective);
        }
        return effective;
    }

    private static Path requiredPath(final String name) {
        return Path.of(requiredText(name)).toAbsolutePath().normalize();
    }

    private static Path configuredPath(
            final String name,
            final Path fallback) {
        final String value = System.getProperty(name);
        return value == null || value.isBlank()
                ? fallback : Path.of(value).toAbsolutePath().normalize();
    }

    private static int configuredInt(
            final String name,
            final int fallback) {
        final String value = System.getProperty(name);
        return value == null || value.isBlank()
                ? fallback : Integer.parseInt(value);
    }

    private static String requiredText(final String name) {
        final String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Required system property is missing: " + name);
        }
        return value;
    }
}
