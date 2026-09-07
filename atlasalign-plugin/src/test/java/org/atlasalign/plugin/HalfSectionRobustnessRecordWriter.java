package org.atlasalign.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.DeepSliceRuntimeProvenance;

/**
 * Create-only writer for one Phase 5 half-section diagnostic event per case.
 *
 * <p>It intentionally does not append or replace event files. A collision,
 * symbolic link, serialization failure, or durable-write failure is reported
 * to the caller so a case can never be silently skipped or overwritten.</p>
 */
public final class HalfSectionRobustnessRecordWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path outputDirectory;

    /**
     * Uses an already-existing, non-symlink directory. Creating evidence
     * directories is deliberately left to the caller's explicit setup.
     */
    public HalfSectionRobustnessRecordWriter(final Path outputDirectory)
            throws IOException {
        final Path requested = Objects.requireNonNull(
                outputDirectory, "outputDirectory").toAbsolutePath()
                .normalize();
        if (Files.isSymbolicLink(requested)
                || !Files.isDirectory(requested, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Event output directory must be an existing non-symlink: "
                            + requested);
        }
        this.outputDirectory = requested.toRealPath();
    }

    /** Writes exactly one exclusive success or failure event. */
    public Path write(final HalfSectionRobustnessRunner.SuccessEvent event)
            throws IOException {
        return writeEvent(successNode(Objects.requireNonNull(event, "event")),
                event.ordinal(), event.sourceId(), event.condition());
    }

    /** Writes exactly one exclusive failure event; it is never a success. */
    public Path write(final HalfSectionRobustnessRunner.FailureEvent event)
            throws IOException {
        return writeEvent(failureNode(Objects.requireNonNull(event, "event")),
                event.ordinal(), event.sourceId(), event.condition());
    }

    private Path writeEvent(
            final ObjectNode event,
            final int ordinal,
            final String sourceId,
            final String condition) throws IOException {
        final Path destination = outputDirectory.resolve(eventFilename(
                ordinal, sourceId, condition)).normalize();
        if (!outputDirectory.equals(destination.getParent())) {
            throw new IOException("Event filename escaped the output directory");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(destination)) {
            throw new IOException(
                    "Refusing to append or replace existing event: "
                            + destination);
        }
        final byte[] serialized = (MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(event) + "\n").getBytes(StandardCharsets.UTF_8);
        final OpenOption[] options = new OpenOption[]{
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS};
        try (FileChannel channel = FileChannel.open(destination, options)) {
            final ByteBuffer bytes = ByteBuffer.wrap(serialized);
            while (bytes.hasRemaining()) {
                channel.write(bytes);
            }
            channel.force(true);
        }
        if (Files.isSymbolicLink(destination)
                || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Created event is not a regular non-symlink file: "
                            + destination);
        }
        forceDirectory(outputDirectory);
        return destination;
    }

    private static ObjectNode successNode(
            final HalfSectionRobustnessRunner.SuccessEvent event) {
        final ObjectNode node = commonNode(
                "SUCCESS",
                event.ordinal(),
                event.sourceId(),
                event.sourceFilename(),
                event.condition(),
                Optional.of(event.sourceFileSha256()),
                Optional.of(event.sourcePixelsSha256()),
                Optional.of(event.fullSourceMaskSha256()),
                event.derivativeIdentitySha256(),
                event.preparationIdentitySha256(),
                Optional.of(event.inferenceInputIdentity()),
                event.providerRuntime(),
                Optional.of(event.startedAtUtc()),
                Optional.of(event.finishedAtUtc()),
                event.runtimeNanoseconds(),
                event.sourceIntegrityVerified());
        node.put("visibleTissueFractionOfFullTissue",
                event.visibleTissueFractionOfFullTissue());
        node.put("controlledSyntheticFractionOfDerivativePixels",
                event.controlledSyntheticFractionOfDerivativePixels());
        node.put("productionSyntheticPixelFractionOfInferencePixels",
                event.productionSyntheticPixelFractionOfInferencePixels());
        node.set("primary", vectorNode(event.prediction().primary()));
        node.set("secondary", vectorNode(event.prediction().secondary()));
        node.set("ensemble", vectorNode(event.prediction().ensemble()));
        node.set("primarySecondaryDisagreement", disagreementNode(
                event.prediction().primarySecondaryDisagreement()));
        return node;
    }

    private static ObjectNode failureNode(
            final HalfSectionRobustnessRunner.FailureEvent event) {
        final ObjectNode node = commonNode(
                "FAILURE",
                event.ordinal(),
                event.sourceId(),
                event.sourceFilename(),
                event.condition(),
                event.sourceFileSha256(),
                event.sourcePixelsSha256(),
                event.fullSourceMaskSha256(),
                event.derivativeIdentitySha256(),
                event.preparationIdentitySha256(),
                event.inferenceInputIdentity(),
                event.providerRuntime(),
                event.startedAtUtc(),
                event.finishedAtUtc(),
                event.runtimeNanoseconds(),
                event.sourceIntegrityVerified());
        node.putNull("visibleTissueFractionOfFullTissue");
        node.putNull("controlledSyntheticFractionOfDerivativePixels");
        node.putNull("productionSyntheticPixelFractionOfInferencePixels");
        node.putNull("primary");
        node.putNull("secondary");
        node.putNull("ensemble");
        node.putNull("primarySecondaryDisagreement");
        node.put("failureCode", event.failureCode());
        node.put("failureDetail", event.failureDetail());
        return node;
    }

    private static ObjectNode commonNode(
            final String status,
            final int ordinal,
            final String sourceId,
            final String sourceFilename,
            final String condition,
            final Optional<String> sourceFileSha256,
            final Optional<String> sourcePixelsSha256,
            final Optional<String> fullSourceMaskSha256,
            final Optional<String> derivativeIdentitySha256,
            final Optional<String> preparationIdentitySha256,
            final Optional<HalfSectionRobustnessRunner.InferenceInputIdentity>
                    inferenceInputIdentity,
            final HalfSectionRobustnessRunner.ProviderRuntimeRecord providerRuntime,
            final Optional<Instant> startedAtUtc,
            final Optional<Instant> finishedAtUtc,
            final long runtimeNanoseconds,
            final boolean sourceIntegrityVerified) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put("eventSchema", "phase5-half-section-robustness-v1");
        node.put("status", status);
        node.put("ordinal", ordinal);
        node.put("sourceId", sourceId);
        node.put("sourceFilename", sourceFilename);
        node.put("condition", condition);
        optionalText(node, "sourceFileSha256", sourceFileSha256);
        optionalText(node, "sourcePixelsSha256", sourcePixelsSha256);
        optionalText(node, "fullSourceMaskSha256", fullSourceMaskSha256);
        optionalText(node, "derivativeIdentitySha256", derivativeIdentitySha256);
        optionalText(node, "preparationIdentitySha256", preparationIdentitySha256);
        optionalInferenceInputIdentity(node, inferenceInputIdentity);
        node.set("providerRuntime", providerRuntimeNode(providerRuntime));
        optionalInstant(node, "startedAtUtc", startedAtUtc);
        optionalInstant(node, "finishedAtUtc", finishedAtUtc);
        node.put("runtimeNanoseconds", runtimeNanoseconds);
        node.put("sourceIntegrityVerified", sourceIntegrityVerified);
        return node;
    }

    private static ObjectNode vectorNode(
            final HalfSectionRobustnessRunner.VectorRecord vector) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put("componentOrder", "ox,oy,oz,ux,uy,uz,vx,vy,vz");
        final ArrayNode components = node.putArray("componentsOuv");
        vector.ouv().forEach(components::add);
        node.put("centerDepth", vector.centerDepth());
        node.put("allenAxis0", vector.allenAxis0());
        node.put("sagittalTiltDegrees", vector.sagittalTiltDegrees());
        node.put("horizontalTiltDegrees", vector.horizontalTiltDegrees());
        return node;
    }

    private static ObjectNode disagreementNode(
            final HalfSectionRobustnessRunner.DisagreementRecord disagreement) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put("absoluteAllenAxis0Difference",
                disagreement.absoluteAllenAxis0Difference());
        node.put("absoluteSagittalTiltDifferenceDegrees",
                disagreement.absoluteSagittalTiltDifferenceDegrees());
        node.put("absoluteHorizontalTiltDifferenceDegrees",
                disagreement.absoluteHorizontalTiltDifferenceDegrees());
        node.put("allenAxis0Caution", disagreement.allenAxis0Caution());
        node.put("sagittalTiltCaution", disagreement.sagittalTiltCaution());
        node.put("horizontalTiltCaution",
                disagreement.horizontalTiltCaution());
        node.put("any", disagreement.any());
        return node;
    }

    private static ObjectNode providerRuntimeNode(
            final HalfSectionRobustnessRunner.ProviderRuntimeRecord runtime) {
        final ObjectNode node = MAPPER.createObjectNode();
        node.put("evidenceClass", runtime.evidenceClass());
        node.put("realInference", runtime.realInference());
        optionalText(node, "testProviderLabel", runtime.testProviderLabel());
        if (runtime.verifiedRuntimeProvenance().isPresent()) {
            final DeepSliceRuntimeProvenance provenance = runtime
                    .verifiedRuntimeProvenance().orElseThrow();
            final ObjectNode verified = node.putObject("verifiedRuntimeProvenance");
            verified.put("verifiedReleaseId", provenance.verifiedReleaseId());
            verified.put("canonicalRuntimePath",
                    provenance.canonicalRuntimePath().toString());
            verified.put("protocolVersion", provenance.protocolVersion());
            verified.put("manifestSizeBytes", provenance.manifestSizeBytes());
            verified.put("manifestSha256", provenance.manifestSha256());
            verified.put("pythonVersion", provenance.pythonVersion());
            verified.put("deepSliceVersion", provenance.deepSliceVersion());
            verified.put("tensorflowVersion", provenance.tensorflowVersion());
            verified.put("modelRelease", provenance.modelRelease());
            verified.put("primaryWeightSha256", provenance.primaryWeightSha256());
            verified.put("secondaryWeightSha256", provenance.secondaryWeightSha256());
            verified.put("backboneWeightSha256", provenance.backboneWeightSha256());
        } else {
            node.putNull("verifiedRuntimeProvenance");
        }
        return node;
    }

    private static void optionalText(
            final ObjectNode node,
            final String name,
            final Optional<String> value) {
        value.ifPresentOrElse(item -> node.put(name, item),
                () -> node.putNull(name));
    }

    private static void optionalInstant(
            final ObjectNode node,
            final String name,
            final Optional<Instant> value) {
        value.ifPresentOrElse(item -> node.put(name, item.toString()),
                () -> node.putNull(name));
    }

    private static void optionalInferenceInputIdentity(
            final ObjectNode node,
            final Optional<HalfSectionRobustnessRunner.InferenceInputIdentity>
                    identity) {
        final Optional<HalfSectionRobustnessRunner.InferenceInputIdentity>
                required = Objects.requireNonNull(identity,
                        "inferenceInputIdentity");
        if (required.isPresent()) {
            final HalfSectionRobustnessRunner.InferenceInputIdentity value =
                    required.orElseThrow();
            node.put("inferenceInputWidth", value.width());
            node.put("inferenceInputHeight", value.height());
            node.put("inferenceInputPixelsSha256", value.pixelsSha256());
            node.put("productionSyntheticMaskSha256",
                    value.productionSyntheticMaskSha256());
            node.put("inferenceInputIdentitySha256",
                    value.inferenceInputIdentitySha256());
        } else {
            node.putNull("inferenceInputWidth");
            node.putNull("inferenceInputHeight");
            node.putNull("inferenceInputPixelsSha256");
            node.putNull("productionSyntheticMaskSha256");
            node.putNull("inferenceInputIdentitySha256");
        }
    }

    private static String eventFilename(
            final int ordinal,
            final String sourceId,
            final String condition) {
        return String.format("%04d-%s-%s.json", ordinal,
                safeFilenameToken(sourceId), safeFilenameToken(condition));
    }

    private static String safeFilenameToken(final String value) {
        final StringBuilder token = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if ((character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')) {
                token.append(character);
            } else {
                token.append('_');
            }
        }
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Event filename token is empty");
        }
        return token.toString();
    }

    private static void forceDirectory(final Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(
                directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (final UnsupportedOperationException ignored) {
            // The event file itself has already been forced. Some file systems
            // do not allow directory channels through the Java API.
        }
    }
}
