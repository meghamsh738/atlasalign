package org.atlasalign.plugin;

import ij.ImagePlus;
import ij.io.Opener;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import javax.imageio.ImageIO;
import org.atlasalign.application.DeepSliceInput;
import org.atlasalign.application.DeepSliceModelPrediction;
import org.atlasalign.application.DeepSliceOuv;
import org.atlasalign.application.DeepSlicePlaneGeometry;
import org.atlasalign.application.DeepSlicePlanePrediction;
import org.atlasalign.application.DeepSlicePlaneProvider;
import org.atlasalign.application.DeepSlicePredictionDiagnostics;
import org.atlasalign.application.DeepSliceRuntimeProvenance;
import org.atlasalign.application.ReadOnlySourceImage;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SafeImageIntakeService;
import org.atlasalign.application.SafePreviewResult;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.TissueGeometryClassifier;
import org.atlasalign.application.TissueGeometryResult;
import org.atlasalign.application.TissueSegmentationResult;
import org.atlasalign.application.TissueSegmenter;
import org.atlasalign.application.ValidationHalfDerivative;
import org.atlasalign.application.ValidationHalfDerivativeBuilder;
import org.atlasalign.application.ValidationHalfDerivativeCondition;
import org.atlasalign.application.ValidationHalfDerivativePreparationOutcome;
import org.atlasalign.application.VirtualHalfPayloadHashes;
import org.atlasalign.application.VirtualHalfPreparationOutcome;
import org.atlasalign.application.VirtualHalfPreviewBuilder;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.io.imagej.ImagePlusSourceImage;

/**
 * Test-support harness for the frozen Phase 5 controlled half-section panel.
 *
 * <p>This is deliberately not a command, runtime activation path, or a source
 * of accepted coordinates. It uses the existing production preparation and
 * provider boundary directly, and writes one durable test evidence event per
 * requested diagnostic case.</p>
 */
public final class HalfSectionRobustnessRunner {

    /** Explicit condition name for a separately requested full diagnostic. */
    public static final String FULL_DIAGNOSTIC_CONDITION = "FULL_DIAGNOSTIC";

    /** User-authorized source-only development subset in its frozen order. */
    public static final List<String> AUTHORIZED_SOURCE_IDS = List.of(
            "GLT1a s094", "Pitx3 s083", "CamKII s218");

    /** Frozen order for each source's four controlled virtual-half inputs. */
    public static final List<ValidationHalfDerivativeCondition>
            ORDERED_HALF_CONDITIONS = List.of(
                    ValidationHalfDerivativeCondition
                            .IMAGE_LEFT_FULL_CANVAS,
                    ValidationHalfDerivativeCondition
                            .IMAGE_RIGHT_FULL_CANVAS,
                    ValidationHalfDerivativeCondition
                            .IMAGE_LEFT_TIGHT_CROP,
                    ValidationHalfDerivativeCondition
                            .IMAGE_RIGHT_TIGHT_CROP);

    static final String R3_RELEASE_ID =
            "deepslice-1.2.8-py3.11.15-tf2.21.0-macos-arm64-r3";
    static final long R3_MANIFEST_SIZE_BYTES = 7_582_923L;
    static final String R3_MANIFEST_SHA256 =
            "fda6fe1b8d3a3ecc32f4a44f5864d8e2"
                    + "eff99014f9915a7b9be44126686f03d9";
    static final String R3_PRIMARY_WEIGHT_SHA256 =
            "da34a5bca0245314a68daff30c144675"
                    + "8c0851b21370ef9797d5288018b08717";
    static final String R3_SECONDARY_WEIGHT_SHA256 =
            "b85b7325158d117b2ac7559495c0b50d"
                    + "fcf3545aa29281a345f9ef1e33542e42";
    static final String R3_BACKBONE_WEIGHT_SHA256 =
            "c5bf1c05b020c4177164039b854c3ef9"
                    + "2b16b73384734647c1f0ad5cf79f6975";

    private static final int MAX_CAPTURED_REAL_SOURCE_BYTES =
            512 * 1024 * 1024;
    private static final String INFERENCE_INPUT_IDENTITY_SCHEMA =
            "atlasalign-phase5-inference-input-v1";

    private final SafeImageIntakeService safeImageIntake;
    private final TissueSegmenter tissueSegmenter;
    private final TissueGeometryClassifier tissueGeometryClassifier;
    private final ValidationHalfDerivativeBuilder derivativeBuilder;
    private final VirtualHalfPreviewBuilder virtualHalfPreviewBuilder;
    private final ProviderCallClock providerCallClock;

    /** Creates a harness wired only to the unchanged production services. */
    public HalfSectionRobustnessRunner() {
        this(
                new SafeImageIntakeService(),
                new TissueSegmenter(),
                new TissueGeometryClassifier(),
                new ValidationHalfDerivativeBuilder(),
                new VirtualHalfPreviewBuilder(),
                new SystemProviderCallClock());
    }

    HalfSectionRobustnessRunner(
            final SafeImageIntakeService safeImageIntake,
            final TissueSegmenter tissueSegmenter,
            final TissueGeometryClassifier tissueGeometryClassifier,
            final ValidationHalfDerivativeBuilder derivativeBuilder,
            final VirtualHalfPreviewBuilder virtualHalfPreviewBuilder) {
        this(
                safeImageIntake,
                tissueSegmenter,
                tissueGeometryClassifier,
                derivativeBuilder,
                virtualHalfPreviewBuilder,
                new SystemProviderCallClock());
    }

    HalfSectionRobustnessRunner(
            final SafeImageIntakeService safeImageIntake,
            final TissueSegmenter tissueSegmenter,
            final TissueGeometryClassifier tissueGeometryClassifier,
            final ValidationHalfDerivativeBuilder derivativeBuilder,
            final VirtualHalfPreviewBuilder virtualHalfPreviewBuilder,
            final ProviderCallClock providerCallClock) {
        this.safeImageIntake = Objects.requireNonNull(
                safeImageIntake, "safeImageIntake");
        this.tissueSegmenter = Objects.requireNonNull(
                tissueSegmenter, "tissueSegmenter");
        this.tissueGeometryClassifier = Objects.requireNonNull(
                tissueGeometryClassifier, "tissueGeometryClassifier");
        this.derivativeBuilder = Objects.requireNonNull(
                derivativeBuilder, "derivativeBuilder");
        this.virtualHalfPreviewBuilder = Objects.requireNonNull(
                virtualHalfPreviewBuilder, "virtualHalfPreviewBuilder");
        this.providerCallClock = Objects.requireNonNull(
                providerCallClock, "providerCallClock");
    }

    /**
     * Executes the fixed 12-case development half panel. The input source
     * order is part of the test protocol and is rejected before any provider
     * call or event write when it differs from {@link #AUTHORIZED_SOURCE_IDS}.
     */
    public List<CaseResult> runAuthorizedDevelopmentHalfPanel(
            final List<SourceInput> sources,
            final int oneBasedChannel,
            final int maximumPreviewDimension,
            final DeepSlicePlaneProvider provider,
            final ProviderRuntime providerRuntime,
            final HalfSectionRobustnessRecordWriter writer)
            throws IOException {
        final List<SourceInput> requestedSources = List.copyOf(
                Objects.requireNonNull(sources, "sources"));
        verifyAuthorizedSourceOrder(requestedSources);
        final DeepSlicePlaneProvider requiredProvider = Objects.requireNonNull(
                provider, "provider");
        final ProviderRuntime requiredRuntime = Objects.requireNonNull(
                providerRuntime, "providerRuntime");
        final HalfSectionRobustnessRecordWriter requiredWriter =
                Objects.requireNonNull(writer, "writer");

        final List<CaseResult> results = new ArrayList<>(
                AUTHORIZED_SOURCE_IDS.size() * ORDERED_HALF_CONDITIONS.size());
        int ordinal = 1;
        for (final SourceInput source : requestedSources) {
            final SourcePreparation preparation;
            final String sourceFileSha256;
            try {
                sourceFileSha256 = verifiedLiveSourceFileSha256(
                        source, requiredRuntime);
                preparation = prepareSource(
                        source,
                        sourceFileSha256,
                        oneBasedChannel,
                        maximumPreviewDimension,
                        true);
            } catch (final Exception error) {
                for (final ValidationHalfDerivativeCondition condition
                        : ORDERED_HALF_CONDITIONS) {
                    results.add(writePreparationFailure(
                            ordinal++, source, condition.name(),
                            sourceFileHashIfAvailable(source),
                            requiredRuntime, requiredWriter, error));
                }
                continue;
            }

            for (final ValidationHalfDerivativeCondition condition
                    : ORDERED_HALF_CONDITIONS) {
                final ValidationHalfDerivative derivative = preparation
                        .derivatives().derivative(condition);
                results.add(runPreparedHalfCase(
                        ordinal++, preparation, derivative, requiredProvider,
                        requiredRuntime, requiredWriter));
            }
        }
        return List.copyOf(results);
    }

    /**
     * Executes one fixed half condition for an authorized source. This narrow
     * entry point is intended for the opt-in real-provider smoke test; it
     * never selects a source or condition implicitly.
     */
    public CaseResult runSingleAuthorizedHalfCase(
            final int ordinal,
            final SourceInput source,
            final ValidationHalfDerivativeCondition condition,
            final int oneBasedChannel,
            final int maximumPreviewDimension,
            final DeepSlicePlaneProvider provider,
            final ProviderRuntime providerRuntime,
            final HalfSectionRobustnessRecordWriter writer)
            throws IOException {
        if (!AUTHORIZED_SOURCE_IDS.contains(Objects.requireNonNull(
                source, "source").sourceId())) {
            throw new IllegalArgumentException(
                    "Single half diagnostic source is not authorized: "
                            + source.sourceId());
        }
        final ValidationHalfDerivativeCondition requiredCondition =
                Objects.requireNonNull(condition, "condition");
        final ProviderRuntime requiredRuntime = Objects.requireNonNull(
                providerRuntime, "providerRuntime");
        final HalfSectionRobustnessRecordWriter requiredWriter =
                Objects.requireNonNull(writer, "writer");
        final SourcePreparation preparation;
        try {
            final String sourceFileSha256 = verifiedLiveSourceFileSha256(
                    source, requiredRuntime);
            preparation = prepareSource(
                    source,
                    sourceFileSha256,
                    oneBasedChannel,
                    maximumPreviewDimension,
                    true);
        } catch (final Exception error) {
            return writePreparationFailure(
                    ordinal,
                    source,
                    requiredCondition.name(),
                    sourceFileHashIfAvailable(source),
                    requiredRuntime,
                    requiredWriter,
                    error);
        }
        return runPreparedHalfCase(
                ordinal,
                preparation,
                preparation.derivatives().derivative(requiredCondition),
                Objects.requireNonNull(provider, "provider"),
                requiredRuntime,
                requiredWriter);
    }

    /**
     * Runs a full-source diagnostic without virtual synthesis. The provider
     * receives the original preview plus an empty production synthetic mask.
     */
    public CaseResult runFullDiagnostic(
            final int ordinal,
            final SourceInput source,
            final int oneBasedChannel,
            final int maximumPreviewDimension,
            final DeepSlicePlaneProvider provider,
            final ProviderRuntime providerRuntime,
            final HalfSectionRobustnessRecordWriter writer)
            throws IOException {
        final SourceInput requiredSource = Objects.requireNonNull(
                source, "source");
        final ProviderRuntime requiredRuntime = Objects.requireNonNull(
                providerRuntime, "providerRuntime");
        final HalfSectionRobustnessRecordWriter requiredWriter =
                Objects.requireNonNull(writer, "writer");
        final SourcePreparation preparation;
        try {
            final String sourceFileSha256 = verifiedLiveSourceFileSha256(
                    requiredSource, requiredRuntime);
            preparation = prepareSource(
                    requiredSource,
                    sourceFileSha256,
                    oneBasedChannel,
                    maximumPreviewDimension,
                    true);
        } catch (final Exception error) {
            return writePreparationFailure(
                    ordinal,
                    requiredSource,
                    FULL_DIAGNOSTIC_CONDITION,
                    sourceFileHashIfAvailable(requiredSource),
                    requiredRuntime,
                    requiredWriter,
                    error);
        }
        final DeepSliceInput inferenceInput = new DeepSliceInput(
                preparation.preview().mapping().previewWidth(),
                preparation.preview().mapping().previewHeight(),
                preparation.preview().pixels(),
                BinaryMask.empty(
                        preparation.preview().mapping().previewWidth(),
                        preparation.preview().mapping().previewHeight()));
        return invokeAndRecord(
                ordinal,
                preparation,
                FULL_DIAGNOSTIC_CONDITION,
                Optional.empty(),
                Optional.empty(),
                1.0d,
                0.0d,
                inferenceInput,
                Objects.requireNonNull(provider, "provider"),
                requiredRuntime,
                requiredWriter);
    }

    /**
     * Reads one bounded regular source file through a no-follow channel. The
     * returned bytes and hash are inseparable, so a real ImageJ decode can be
     * tied to the exact byte payload that supplied its expected file identity.
     */
    public static CapturedSourceBytes captureRealSourceBytes(
            final Path sourceFile) throws IOException {
        final Path regular = requireRegularNoFollowSourceFile(sourceFile);
        try (FileChannel channel = FileChannel.open(regular,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            final long expectedSize = channel.size();
            if (expectedSize < 0L || expectedSize > MAX_CAPTURED_REAL_SOURCE_BYTES) {
                throw new IOException("Real source exceeds the bounded capture size: "
                        + regular);
            }
            final byte[] bytes = new byte[(int) expectedSize];
            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) {
                    throw new IOException("Source ended during byte capture: "
                            + regular);
                }
            }
            if (channel.size() != expectedSize) {
                throw new IOException("Source changed during byte capture: "
                        + regular);
            }
            final MessageDigest digest = sha256();
            digest.update(bytes);
            return new CapturedSourceBytes(
                    regular, bytes, HexFormat.of().formatHex(digest.digest()));
        }
    }

    /**
     * Bounded source bytes captured before a real run. The only decode method
     * deliberately consumes these captured bytes, never the live path.
     */
    public static final class CapturedSourceBytes {

        private final Path sourceFile;
        private final byte[] bytes;
        private final String sha256;

        private CapturedSourceBytes(
                final Path sourceFile,
                final byte[] bytes,
                final String sha256) {
            this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile");
            this.bytes = Arrays.copyOf(
                    Objects.requireNonNull(bytes, "bytes"), bytes.length);
            this.sha256 = requireSha256(sha256, "sha256");
        }

        public Path sourceFile() {
            return sourceFile;
        }

        public String sha256() {
            return sha256;
        }

        /** Returns a defensive copy for diagnostics that need the raw bytes. */
        public byte[] copyOfBytes() {
            return Arrays.copyOf(bytes, bytes.length);
        }

        /**
         * Decodes a TIFF only from the captured byte payload. A null ImageJ
         * result is rejected rather than falling back to path-based decoding.
         */
        private ImagePlus decodeTiff() throws IOException {
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                final ImagePlus decoded = new Opener().openTiff(input,
                        sourceFile.getFileName().toString());
                if (decoded == null) {
                    throw new IOException("ImageJ could not decode captured TIFF bytes: "
                            + sourceFile);
                }
                return decoded;
            } catch (final RuntimeException error) {
                throw new IOException("ImageJ failed while decoding captured TIFF bytes: "
                        + sourceFile, error);
            }
        }

        /**
         * Decodes a PNG only from the captured byte payload. The live source
         * path is used solely as an immutable display name and is never
         * reopened by ImageIO.
         */
        private ImagePlus decodePng() throws IOException {
            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                final BufferedImage decoded = ImageIO.read(input);
                if (decoded == null) {
                    throw new IOException(
                            "ImageIO could not decode captured PNG bytes: "
                                    + sourceFile);
                }
                return new ImagePlus(sourceFile.getFileName().toString(), decoded);
            } catch (final RuntimeException error) {
                throw new IOException(
                        "ImageIO failed while decoding captured PNG bytes: "
                                + sourceFile,
                        error);
            }
        }

        /**
         * Creates a real-source factory from captured TIFF or PNG bytes.
         * Unsupported filename types fail closed instead of trying a
         * path-based or format-guessing decoder.
         */
        public CapturedImageJSource decodeImageSource() throws IOException {
            final String filename = sourceFile.getFileName().toString()
                    .toLowerCase(java.util.Locale.ROOT);
            if (filename.endsWith(".tif") || filename.endsWith(".tiff")) {
                return new CapturedImageJSource(this, decodeTiff());
            }
            if (filename.endsWith(".png")) {
                return new CapturedImageJSource(this, decodePng());
            }
            throw new IOException(
                    "Captured real source must be TIFF or PNG: " + sourceFile);
        }

        /**
         * Creates the only real-source factory from the captured TIFF bytes.
         * Its private ImagePlus is never decoded from, or reopened through,
         * the mutable live filesystem path.
         */
        public CapturedImageJSource decodeTiffSource() throws IOException {
            return new CapturedImageJSource(this, decodeTiff());
        }
    }

    /** A closable ImageJ source whose decoder and expected hash share capture bytes. */
    public static final class CapturedImageJSource implements AutoCloseable {

        private final CapturedSourceBytes captured;
        private final ImagePlus image;
        private boolean closed;

        private CapturedImageJSource(
                final CapturedSourceBytes captured,
                final ImagePlus image) {
            this.captured = Objects.requireNonNull(captured, "captured");
            this.image = Objects.requireNonNull(image, "image");
        }

        /**
         * Produces a real-bound source input from this exact decoded payload.
         * Test-only sources must instead use {@link SourceInput#testOnly}.
         */
        public SourceInput sourceInput(final String sourceId) {
            if (closed) {
                throw new IllegalStateException(
                        "Captured ImageJ source has already been closed");
            }
            return SourceInput.capturedReal(sourceId, captured,
                    new ImagePlusSourceImage(image));
        }

        @Override
        public void close() {
            if (!closed) {
                image.close();
                closed = true;
            }
        }
    }

    /**
     * Source input plus a local file identity. Test-only inputs are explicit;
     * a requested real record can only use a captured source-byte binding.
     */
    public static final class SourceInput {

        private final String sourceId;
        private final Path sourceFile;
        private final ReadOnlySourceImage source;
        private final SourceFileBinding sourceFileBinding;

        private SourceInput(
                final String sourceId,
                final Path sourceFile,
                final ReadOnlySourceImage source,
                final SourceFileBinding sourceFileBinding) {
            this.sourceId = requireText(sourceId, "sourceId");
            this.sourceFile = Objects.requireNonNull(sourceFile, "sourceFile")
                    .toAbsolutePath().normalize();
            this.source = Objects.requireNonNull(source, "source");
            this.sourceFileBinding = Objects.requireNonNull(
                    sourceFileBinding, "sourceFileBinding");
            if (this.sourceFile.getFileName() == null
                    || this.sourceFile.getFileName().toString().isBlank()) {
                throw new IllegalArgumentException(
                        "sourceFile must name a file");
            }
        }

        /** Explicit factory for deterministic fake/unit sources only. */
        public static SourceInput testOnly(
                final String sourceId,
                final Path sourceFile,
                final ReadOnlySourceImage source) {
            return new SourceInput(sourceId, sourceFile, source,
                    new TestOnlySourceFileBinding());
        }

        /**
         * Factory for a real source decoded from {@code captured}. It stores
         * the capture hash, which an authorized real run must match before
         * preparation and after its provider call.
         */
        private static SourceInput capturedReal(
                final String sourceId,
                final CapturedSourceBytes captured,
                final ReadOnlySourceImage source) {
            final CapturedSourceBytes required = Objects.requireNonNull(
                    captured, "captured");
            return new SourceInput(sourceId, required.sourceFile(), source,
                    new CapturedSourceFileBinding(required.sha256()));
        }

        public String sourceId() {
            return sourceId;
        }

        public Path sourceFile() {
            return sourceFile;
        }

        public ReadOnlySourceImage source() {
            return source;
        }

        /** Filename only; the event intentionally does not require a path. */
        public String sourceFilename() {
            return sourceFile.getFileName().toString();
        }

        /** Present only for source bytes captured through the real factory. */
        public Optional<String> expectedCapturedSourceFileSha256() {
            return sourceFileBinding.expectedSha256();
        }

        private boolean hasCapturedRealSourceBinding() {
            return sourceFileBinding instanceof CapturedSourceFileBinding;
        }

        private void verifyExpectedSourceFileSha256(final String liveSha256)
                throws SourceIntegrityFailure {
            final Optional<String> expected = sourceFileBinding.expectedSha256();
            if (expected.isPresent() && !expected.orElseThrow().equals(liveSha256)) {
                throw new SourceIntegrityFailure(
                        "Live source file does not match captured source bytes");
            }
        }
    }

    private sealed interface SourceFileBinding permits TestOnlySourceFileBinding,
            CapturedSourceFileBinding {

        Optional<String> expectedSha256();
    }

    private record TestOnlySourceFileBinding() implements SourceFileBinding {

        @Override
        public Optional<String> expectedSha256() {
            return Optional.empty();
        }
    }

    private record CapturedSourceFileBinding(String capturedSha256)
            implements SourceFileBinding {

        private CapturedSourceFileBinding {
            capturedSha256 = requireSha256(
                    capturedSha256, "capturedSha256");
        }

        @Override
        public Optional<String> expectedSha256() {
            return Optional.of(capturedSha256);
        }
    }

    /**
     * Complete, recomputable identity of the precise production provider
     * input. It is independent of derivative provenance so full diagnostics
     * remain uniquely auditable.
     */
    public record InferenceInputIdentity(
            int width,
            int height,
            String pixelsSha256,
            String productionSyntheticMaskSha256,
            String inferenceInputIdentitySha256) {

        public InferenceInputIdentity {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                        "Inference input dimensions must be positive");
            }
            pixelsSha256 = requireSha256(pixelsSha256, "pixelsSha256");
            productionSyntheticMaskSha256 = requireSha256(
                    productionSyntheticMaskSha256,
                    "productionSyntheticMaskSha256");
            inferenceInputIdentitySha256 = requireSha256(
                    inferenceInputIdentitySha256,
                    "inferenceInputIdentitySha256");
            final String expected = HalfSectionRobustnessRunner
                    .inferenceInputIdentitySha256(
                    width,
                    height,
                    pixelsSha256,
                    productionSyntheticMaskSha256);
            if (!expected.equals(inferenceInputIdentitySha256)) {
                throw new IllegalArgumentException(
                        "Inference input identity does not match its payload hashes");
            }
        }

        /** Computes an identity directly from the immutable provider input. */
        public static InferenceInputIdentity from(final DeepSliceInput input) {
            final DeepSliceInput required = Objects.requireNonNull(input, "input");
            final String pixels = VirtualHalfPayloadHashes.pixelsSha256(
                    required.pixels());
            final String synthetic = VirtualHalfPayloadHashes.syntheticMaskSha256(
                    required.syntheticPixelMask());
            return new InferenceInputIdentity(
                    required.width(),
                    required.height(),
                    pixels,
                    synthetic,
                    HalfSectionRobustnessRunner.inferenceInputIdentitySha256(
                            required.width(), required.height(), pixels, synthetic));
        }
    }

    /** Distinguishes intentionally fake tests from verified real evidence. */
    public sealed interface ProviderRuntime permits NonRealTestOnly,
            AuthorizedProtocolV2R3 {
    }

    /**
     * Explicit fake/test marker. It has no runtime provenance fields and is
     * structurally distinct from a real event.
     */
    public record NonRealTestOnly(String testProviderLabel)
            implements ProviderRuntime {

        public NonRealTestOnly {
            testProviderLabel = requireText(
                    testProviderLabel, "testProviderLabel");
        }
    }

    /** Requests a real record only when the returned prediction proves r3. */
    public record AuthorizedProtocolV2R3() implements ProviderRuntime {
    }

    /** One emitted outcome; an operational failure is itself an event. */
    public sealed interface CaseResult permits SuccessfulCase,
            FailedCase {

        Path eventPath();
    }

    /** Successfully persisted case event and its provider input identity. */
    public record SuccessfulCase(
            Path eventPath,
            SuccessEvent event,
            DeepSliceInput providerInput) implements CaseResult {

        public SuccessfulCase {
            eventPath = Objects.requireNonNull(eventPath, "eventPath");
            event = Objects.requireNonNull(event, "event");
            providerInput = Objects.requireNonNull(
                    providerInput, "providerInput");
        }
    }

    /** Persisted failure event. No prediction is silently omitted. */
    public record FailedCase(Path eventPath, FailureEvent event)
            implements CaseResult {

        public FailedCase {
            eventPath = Objects.requireNonNull(eventPath, "eventPath");
            event = Objects.requireNonNull(event, "event");
        }
    }

    /** Fully populated success schema before JSON serialization. */
    public record SuccessEvent(
            int ordinal,
            String sourceId,
            String sourceFilename,
            String condition,
            String sourceFileSha256,
            String sourcePixelsSha256,
            String fullSourceMaskSha256,
            Optional<String> derivativeIdentitySha256,
            Optional<String> preparationIdentitySha256,
            InferenceInputIdentity inferenceInputIdentity,
            double visibleTissueFractionOfFullTissue,
            double controlledSyntheticFractionOfDerivativePixels,
            double productionSyntheticPixelFractionOfInferencePixels,
            PredictionRecord prediction,
            ProviderRuntimeRecord providerRuntime,
            Instant startedAtUtc,
            Instant finishedAtUtc,
            long runtimeNanoseconds,
            boolean sourceIntegrityVerified) {

        public SuccessEvent {
            validateCaseIdentity(ordinal, sourceId, sourceFilename, condition);
            sourceFileSha256 = requireSha256(
                    sourceFileSha256, "sourceFileSha256");
            sourcePixelsSha256 = requireSha256(
                    sourcePixelsSha256, "sourcePixelsSha256");
            fullSourceMaskSha256 = requireSha256(
                    fullSourceMaskSha256, "fullSourceMaskSha256");
            derivativeIdentitySha256 = optionalSha256(
                    derivativeIdentitySha256, "derivativeIdentitySha256");
            preparationIdentitySha256 = optionalSha256(
                    preparationIdentitySha256, "preparationIdentitySha256");
            inferenceInputIdentity = Objects.requireNonNull(
                    inferenceInputIdentity, "inferenceInputIdentity");
            validateFraction(
                    visibleTissueFractionOfFullTissue,
                    "visibleTissueFractionOfFullTissue");
            validateFraction(
                    controlledSyntheticFractionOfDerivativePixels,
                    "controlledSyntheticFractionOfDerivativePixels");
            validateFraction(
                    productionSyntheticPixelFractionOfInferencePixels,
                    "productionSyntheticPixelFractionOfInferencePixels");
            if (FULL_DIAGNOSTIC_CONDITION.equals(condition)
                    && (Double.doubleToLongBits(
                    visibleTissueFractionOfFullTissue)
                    != Double.doubleToLongBits(1.0d)
                    || Double.doubleToLongBits(
                    controlledSyntheticFractionOfDerivativePixels)
                    != Double.doubleToLongBits(0.0d)
                    || Double.doubleToLongBits(
                    productionSyntheticPixelFractionOfInferencePixels)
                    != Double.doubleToLongBits(0.0d))) {
                throw new IllegalArgumentException(
                        "Full diagnostic fractions must be exactly 1.0/0.0/0.0");
            }
            prediction = Objects.requireNonNull(prediction, "prediction");
            providerRuntime = Objects.requireNonNull(
                    providerRuntime, "providerRuntime");
            startedAtUtc = Objects.requireNonNull(startedAtUtc, "startedAtUtc");
            finishedAtUtc = Objects.requireNonNull(
                    finishedAtUtc, "finishedAtUtc");
            if (finishedAtUtc.isBefore(startedAtUtc) || runtimeNanoseconds < 0
                    || !sourceIntegrityVerified) {
                throw new IllegalArgumentException(
                        "Success event requires monotonic timing and verified source integrity");
            }
        }
    }

    /** Failure schema with every common key retained and unavailable values null. */
    public record FailureEvent(
            int ordinal,
            String sourceId,
            String sourceFilename,
            String condition,
            Optional<String> sourceFileSha256,
            Optional<String> sourcePixelsSha256,
            Optional<String> fullSourceMaskSha256,
            Optional<String> derivativeIdentitySha256,
            Optional<String> preparationIdentitySha256,
            Optional<InferenceInputIdentity> inferenceInputIdentity,
            ProviderRuntimeRecord providerRuntime,
            Optional<Instant> startedAtUtc,
            Optional<Instant> finishedAtUtc,
            long runtimeNanoseconds,
            boolean sourceIntegrityVerified,
            String failureCode,
            String failureDetail) {

        public FailureEvent {
            validateCaseIdentity(ordinal, sourceId, sourceFilename, condition);
            sourceFileSha256 = optionalSha256(
                    sourceFileSha256, "sourceFileSha256");
            sourcePixelsSha256 = optionalSha256(
                    sourcePixelsSha256, "sourcePixelsSha256");
            fullSourceMaskSha256 = optionalSha256(
                    fullSourceMaskSha256, "fullSourceMaskSha256");
            derivativeIdentitySha256 = optionalSha256(
                    derivativeIdentitySha256, "derivativeIdentitySha256");
            preparationIdentitySha256 = optionalSha256(
                    preparationIdentitySha256, "preparationIdentitySha256");
            inferenceInputIdentity = Objects.requireNonNull(
                    inferenceInputIdentity, "inferenceInputIdentity");
            providerRuntime = Objects.requireNonNull(
                    providerRuntime, "providerRuntime");
            startedAtUtc = Objects.requireNonNull(startedAtUtc, "startedAtUtc");
            finishedAtUtc = Objects.requireNonNull(
                    finishedAtUtc, "finishedAtUtc");
            if (startedAtUtc.isPresent() != finishedAtUtc.isPresent()
                    || runtimeNanoseconds < 0
                    || (inferenceInputIdentity.isPresent()
                    && startedAtUtc.isEmpty())) {
                throw new IllegalArgumentException(
                        "Failure event input identity requires paired timing");
            }
            failureCode = requireText(failureCode, "failureCode");
            failureDetail = requireText(failureDetail, "failureDetail");
        }
    }

    /** Stored provider evidence, with fake and real forms made disjoint. */
    public record ProviderRuntimeRecord(
            String evidenceClass,
            boolean realInference,
            Optional<String> testProviderLabel,
            Optional<DeepSliceRuntimeProvenance> verifiedRuntimeProvenance) {

        public ProviderRuntimeRecord {
            evidenceClass = requireText(evidenceClass, "evidenceClass");
            testProviderLabel = Objects.requireNonNull(
                    testProviderLabel, "testProviderLabel");
            verifiedRuntimeProvenance = Objects.requireNonNull(
                    verifiedRuntimeProvenance, "verifiedRuntimeProvenance");
            final boolean fake = "NON_REAL_TEST_ONLY".equals(evidenceClass);
            final boolean real = "VERIFIED_PROTOCOL_V2_R3".equals(
                    evidenceClass);
            final boolean requestedReal =
                    "AUTHORIZED_PROTOCOL_V2_R3_REQUESTED".equals(
                            evidenceClass);
            if ((!fake && !real && !requestedReal)
                    || (fake && real) || (fake && requestedReal)
                    || (real && requestedReal)) {
                throw new IllegalArgumentException(
                        "Provider evidence class is not recognized");
            }
            if (fake && (realInference || testProviderLabel.isEmpty()
                    || verifiedRuntimeProvenance.isPresent())) {
                throw new IllegalArgumentException(
                        "Fake provider evidence must be explicitly non-real only");
            }
            if (real && (!realInference || testProviderLabel.isPresent()
                    || verifiedRuntimeProvenance.isEmpty())) {
                throw new IllegalArgumentException(
                        "Real provider evidence requires verified r3 provenance only");
            }
            if (real) {
                verifyAuthorizedR3(verifiedRuntimeProvenance.orElseThrow());
            }
            if (requestedReal && (realInference || testProviderLabel.isPresent()
                    || verifiedRuntimeProvenance.isPresent())) {
                throw new IllegalArgumentException(
                        "Unexecuted real-provider request must not resemble evidence");
            }
        }
    }

    /** One vector and its pinned, application-derived geometry. */
    public record VectorRecord(
            List<Double> ouv,
            double centerDepth,
            int allenAxis0,
            double sagittalTiltDegrees,
            double horizontalTiltDegrees) {

        public VectorRecord {
            ouv = List.copyOf(Objects.requireNonNull(ouv, "ouv"));
            final DeepSliceOuv vector = DeepSliceOuv.fromList(ouv);
            final DeepSlicePlaneGeometry expected =
                    DeepSlicePlaneGeometry.from(vector);
            final double expectedDepth = HalfSectionRobustnessRunner
                    .centerDepth(vector);
            if (!Double.isFinite(centerDepth)
                    || allenAxis0 != expected.zeroBasedAnteriorPosteriorIndex()
                    || Double.doubleToLongBits(sagittalTiltDegrees)
                    != Double.doubleToLongBits(expected.sagittalTiltDegrees())
                    || Double.doubleToLongBits(horizontalTiltDegrees)
                    != Double.doubleToLongBits(expected.horizontalTiltDegrees())
                    || Double.doubleToLongBits(centerDepth)
                    != Double.doubleToLongBits(expectedDepth)) {
                throw new IllegalArgumentException(
                        "Vector geometry is not the pinned O/U/V conversion");
            }
        }
    }

    /** Strict primary-secondary disagreement diagnostics. */
    public record DisagreementRecord(
            int absoluteAllenAxis0Difference,
            double absoluteSagittalTiltDifferenceDegrees,
            double absoluteHorizontalTiltDifferenceDegrees,
            boolean allenAxis0Caution,
            boolean sagittalTiltCaution,
            boolean horizontalTiltCaution,
            boolean any) {

        public DisagreementRecord {
            if (absoluteAllenAxis0Difference < 0
                    || !Double.isFinite(absoluteSagittalTiltDifferenceDegrees)
                    || !Double.isFinite(absoluteHorizontalTiltDifferenceDegrees)
                    || absoluteSagittalTiltDifferenceDegrees < 0
                    || absoluteHorizontalTiltDifferenceDegrees < 0
                    || allenAxis0Caution != absoluteAllenAxis0Difference > 20
                    || sagittalTiltCaution
                    != absoluteSagittalTiltDifferenceDegrees > 5.0d
                    || horizontalTiltCaution
                    != absoluteHorizontalTiltDifferenceDegrees > 5.0d
                    || any != (allenAxis0Caution || sagittalTiltCaution
                    || horizontalTiltCaution)) {
                throw new IllegalArgumentException(
                        "Primary-secondary disagreement is not strict or finite");
            }
        }
    }

    /** Exact normalized primary, secondary, and ensemble diagnostic payload. */
    public record PredictionRecord(
            VectorRecord primary,
            VectorRecord secondary,
            VectorRecord ensemble,
            DisagreementRecord primarySecondaryDisagreement) {

        public PredictionRecord {
            primary = Objects.requireNonNull(primary, "primary");
            secondary = Objects.requireNonNull(secondary, "secondary");
            ensemble = Objects.requireNonNull(ensemble, "ensemble");
            primarySecondaryDisagreement = Objects.requireNonNull(
                    primarySecondaryDisagreement,
                    "primarySecondaryDisagreement");
            final double[] expected = new double[DeepSliceOuv.COMPONENT_COUNT];
            for (int index = 0; index < expected.length; index++) {
                expected[index] = (primary.ouv().get(index)
                        + secondary.ouv().get(index)) / 2.0d;
                if (!Double.isFinite(expected[index])
                        || Double.doubleToLongBits(expected[index])
                        != Double.doubleToLongBits(
                        ensemble.ouv().get(index))) {
                    throw new IllegalArgumentException(
                            "Ensemble is not the exact binary64 mean");
                }
            }
            final DisagreementRecord expectedDisagreement = disagreement(
                    primary, secondary);
            if (!expectedDisagreement.equals(primarySecondaryDisagreement)) {
                throw new IllegalArgumentException(
                        "Primary-secondary disagreement does not match vectors");
            }
        }
    }

    private CaseResult runPreparedHalfCase(
            final int ordinal,
            final SourcePreparation preparation,
            final ValidationHalfDerivative derivative,
            final DeepSlicePlaneProvider provider,
            final ProviderRuntime providerRuntime,
            final HalfSectionRobustnessRecordWriter writer)
            throws IOException {
        final Optional<String> derivativeIdentity = Optional.of(derivative
                .provenance().derivativeIdentitySha256());
        try {
            verifySourceIntegrity(preparation);
        } catch (final Exception error) {
            return writeFailure(
                    ordinal,
                    preparation,
                    derivative.condition().name(),
                    derivativeIdentity,
                    Optional.empty(),
                    Optional.empty(),
                    providerRuntimeRecordForFailure(providerRuntime),
                    Optional.empty(),
                    Optional.empty(),
                    0L,
                    false,
                    classifyFailure(error),
                    detail(error),
                    writer);
        }
        final VirtualHalfPreparationOutcome.Ready ready;
        try {
            final RegistrationPreview derivativePreview = identityPreview(
                    preparation.preview(), derivative);
            final TissueGeometryResult controlledGeometry = controlledGeometry(
                    derivative);
            final VirtualHalfPreparationOutcome outcome =
                    virtualHalfPreviewBuilder.build(
                            derivativePreview,
                            derivative.observedHalfMask(),
                            controlledGeometry);
            if (!(outcome instanceof VirtualHalfPreparationOutcome.Ready built)) {
                throw new IllegalStateException(
                        "Controlled half preparation was not Ready: " + outcome);
            }
            ready = built;
        } catch (final Exception error) {
            boolean integrityVerified = false;
            try {
                verifySourceIntegrity(preparation);
                integrityVerified = true;
            } catch (final Exception integrityFailure) {
                error.addSuppressed(integrityFailure);
            }
            return writeFailure(
                    ordinal,
                    preparation,
                    derivative.condition().name(),
                    derivativeIdentity,
                    Optional.empty(),
                    Optional.empty(),
                    providerRuntimeRecordForFailure(providerRuntime),
                    Optional.empty(),
                    Optional.empty(),
                    0L,
                    integrityVerified,
                    classifyFailure(error),
                    detail(error),
                    writer);
        }
        final long derivativePixelCount = (long) derivative.width()
                * derivative.height();
        final double visibleFraction = (double) derivative
                .observedHalfMask().foregroundCount()
                / preparation.fullSourceMask().foregroundCount();
        final double controlFraction = (double) derivative.controlMask()
                .foregroundCount() / derivativePixelCount;
        return invokeAndRecord(
                ordinal,
                preparation,
                derivative.condition().name(),
                derivativeIdentity,
                Optional.of(ready.provenance().preparationIdentitySha256()),
                visibleFraction,
                controlFraction,
                ready.inferenceInput(),
                provider,
                providerRuntime,
                writer);
    }

    private CaseResult invokeAndRecord(
            final int ordinal,
            final SourcePreparation preparation,
            final String condition,
            final Optional<String> derivativeIdentity,
            final Optional<String> preparationIdentity,
            final double visibleFraction,
            final double controlFraction,
            final DeepSliceInput inferenceInput,
            final DeepSlicePlaneProvider provider,
            final ProviderRuntime providerRuntime,
            final HalfSectionRobustnessRecordWriter writer)
            throws IOException {
        final InferenceInputIdentity inputIdentity = InferenceInputIdentity.from(
                Objects.requireNonNull(inferenceInput, "inferenceInput"));
        final Instant startedAtUtc = providerCallClock.instant();
        final long startedNanos = providerCallClock.nanoTime();
        final ProviderCallTiming timing;
        final DeepSlicePlanePrediction prediction;
        try {
            prediction = provider.estimate(inferenceInput);
            timing = providerCallTiming(startedAtUtc, startedNanos);
        } catch (final Exception error) {
            final ProviderCallTiming failedTiming = providerCallTiming(
                    startedAtUtc, startedNanos);
            boolean integrityVerified = false;
            try {
                verifySourceIntegrity(preparation);
                integrityVerified = true;
            } catch (final Exception integrityFailure) {
                error.addSuppressed(integrityFailure);
            }
            return writeFailure(
                    ordinal,
                    preparation,
                    condition,
                    derivativeIdentity,
                    preparationIdentity,
                    Optional.of(inputIdentity),
                    providerRuntimeRecordForFailure(providerRuntime),
                    Optional.of(startedAtUtc),
                    Optional.of(failedTiming.finishedAtUtc()),
                    failedTiming.runtimeNanoseconds(),
                    integrityVerified,
                    classifyFailure(error),
                    detail(error),
                    writer);
        }
        final ProviderRuntimeRecord runtimeRecord;
        final PredictionRecord predictionRecord;
        try {
            final DeepSlicePlanePrediction nonNullPrediction =
                    Objects.requireNonNull(prediction,
                            "provider returned null prediction");
            verifySourceIntegrity(preparation);
            runtimeRecord = runtimeRecord(providerRuntime, nonNullPrediction);
            predictionRecord = predictionRecord(nonNullPrediction);
        } catch (final Exception error) {
            boolean integrityVerified = false;
            try {
                verifySourceIntegrity(preparation);
                integrityVerified = true;
            } catch (final Exception integrityFailure) {
                error.addSuppressed(integrityFailure);
            }
            return writeFailure(
                    ordinal,
                    preparation,
                    condition,
                    derivativeIdentity,
                    preparationIdentity,
                    Optional.of(inputIdentity),
                    providerRuntimeRecordForFailure(providerRuntime),
                    Optional.of(startedAtUtc),
                    Optional.of(timing.finishedAtUtc()),
                    timing.runtimeNanoseconds(),
                    integrityVerified,
                    classifyFailure(error),
                    detail(error),
                    writer);
        }
        final SuccessEvent event = new SuccessEvent(
                ordinal,
                preparation.source().sourceId(),
                preparation.source().sourceFilename(),
                condition,
                preparation.sourceFileSha256(),
                preparation.sourcePixelsSha256(),
                preparation.fullSourceMaskSha256(),
                derivativeIdentity,
                preparationIdentity,
                inputIdentity,
                visibleFraction,
                controlFraction,
                fraction(inferenceInput.syntheticPixelMask()),
                predictionRecord,
                runtimeRecord,
                startedAtUtc,
                timing.finishedAtUtc(),
                timing.runtimeNanoseconds(),
                true);
        final Path eventPath = writer.write(event);
        return new SuccessfulCase(eventPath, event, inferenceInput);
    }

    private CaseResult writePreparationFailure(
            final int ordinal,
            final SourceInput source,
            final String condition,
            final Optional<String> sourceFileSha256,
            final ProviderRuntime providerRuntime,
            final HalfSectionRobustnessRecordWriter writer,
            final Exception error) throws IOException {
        final FailureEvent event = new FailureEvent(
                ordinal,
                source.sourceId(),
                source.sourceFilename(),
                condition,
                sourceFileSha256,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                providerRuntimeRecordForFailure(providerRuntime),
                Optional.empty(),
                Optional.empty(),
                0L,
                false,
                classifyFailure(error),
                detail(error));
        return new FailedCase(writer.write(event), event);
    }

    private CaseResult writeFailure(
            final int ordinal,
            final SourcePreparation preparation,
            final String condition,
            final Optional<String> derivativeIdentity,
            final Optional<String> preparationIdentity,
            final Optional<InferenceInputIdentity> inferenceInputIdentity,
            final ProviderRuntimeRecord runtimeRecord,
            final Optional<Instant> startedAtUtc,
            final Optional<Instant> finishedAtUtc,
            final long runtimeNanoseconds,
            final boolean sourceIntegrityVerified,
            final String failureCode,
            final String failureDetail,
            final HalfSectionRobustnessRecordWriter writer) throws IOException {
        final FailureEvent event = new FailureEvent(
                ordinal,
                preparation.source().sourceId(),
                preparation.source().sourceFilename(),
                condition,
                Optional.of(preparation.sourceFileSha256()),
                Optional.of(preparation.sourcePixelsSha256()),
                Optional.of(preparation.fullSourceMaskSha256()),
                derivativeIdentity,
                preparationIdentity,
                inferenceInputIdentity,
                runtimeRecord,
                startedAtUtc,
                finishedAtUtc,
                runtimeNanoseconds,
                sourceIntegrityVerified,
                failureCode,
                failureDetail);
        return new FailedCase(writer.write(event), event);
    }

    private SourcePreparation prepareSource(
            final SourceInput source,
            final String sourceFileSha256,
            final int oneBasedChannel,
            final int maximumPreviewDimension,
            final boolean requireFullGeometry) throws IOException {
        final SourceImageSnapshot snapshotBefore = source.source().snapshot();
        final SafePreviewResult safe = safeImageIntake.preparePreview(
                source.source(), oneBasedChannel, maximumPreviewDimension);
        if (!snapshotBefore.equals(safe.verifiedSource())) {
            throw new SourceIntegrityFailure(
                    "Safe intake reported a source snapshot different from its baseline");
        }
        final RegistrationPreview preview = safe.preview();
        final TissueSegmentationResult segmentation = tissueSegmenter.segment(
                preview.mapping().previewWidth(),
                preview.mapping().previewHeight(), preview.pixels());
        final TissueGeometryResult geometry = tissueGeometryClassifier.classify(
                segmentation.mask());
        if (requireFullGeometry && geometry.geometry() != SectionGeometry.FULL) {
            throw new SourceEligibilityFailure(
                    "Controlled half diagnostics require a FULL source, found "
                            + geometry.geometry());
        }
        final ValidationHalfDerivativePreparationOutcome outcome =
                derivativeBuilder.build(preview, segmentation.mask(), geometry);
        if (!(outcome instanceof ValidationHalfDerivativePreparationOutcome.Ready
                derivatives)) {
            final ValidationHalfDerivativePreparationOutcome.Failed failed =
                    (ValidationHalfDerivativePreparationOutcome.Failed) outcome;
            throw new IllegalStateException(failed.auditMessage());
        }
        final SourcePreparation preparation = new SourcePreparation(
                source,
                sourceFileSha256,
                snapshotBefore,
                preview,
                segmentation.mask(),
                geometry,
                derivatives,
                VirtualHalfPayloadHashes.pixelsSha256(preview.pixels()),
                VirtualHalfPayloadHashes.maskSha256(segmentation.mask()));
        verifySourceIntegrity(preparation);
        return preparation;
    }

    private void verifySourceIntegrity(final SourcePreparation preparation)
            throws IOException {
        if (!preparation.snapshotBefore().equals(
                preparation.source().source().snapshot())) {
            throw new SourceIntegrityFailure(
                    "Source snapshot changed during a robustness case");
        }
        final String after = sha256NoFollow(preparation.source().sourceFile());
        preparation.source().verifyExpectedSourceFileSha256(after);
        if (!preparation.sourceFileSha256().equals(after)) {
            throw new SourceIntegrityFailure(
                    "Source file bytes changed during a robustness case");
        }
    }

    private TissueGeometryResult controlledGeometry(
            final ValidationHalfDerivative derivative) {
        final TissueGeometryResult measured = tissueGeometryClassifier.classify(
                derivative.observedHalfMask());
        return new TissueGeometryResult(
                derivative.condition().observedGeometry(),
                measured.bounds(),
                measured.widthToHeightRatio(),
                measured.foregroundToBoundsFraction(),
                measured.imageLeftEdgeDispersion(),
                measured.imageRightEdgeDispersion(),
                measured.bilateralMirroredOverlap(),
                measured.hemisphereBalance(),
                measured.connectedComponentCount(),
                measured.substantialComponentCount(),
                OptionalDouble.of(derivative.provenance()
                        .medialEdgeDerivative()));
    }

    private static RegistrationPreview identityPreview(
            final RegistrationPreview fullSource,
            final ValidationHalfDerivative derivative) {
        final PreviewMapping identityMapping = new PreviewMapping(
                derivative.width(), derivative.height(), derivative.width(),
                derivative.height());
        return new RegistrationPreview(
                fullSource.channel(), fullSource.sourceSlice(),
                fullSource.sourceFrame(), identityMapping, derivative.pixels());
    }

    private static PredictionRecord predictionRecord(
            final DeepSlicePlanePrediction prediction) {
        final DeepSlicePredictionDiagnostics diagnostics = prediction.diagnostics()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Prediction lacks normalized O/U/V diagnostics"));
        final VectorRecord primary = vectorRecord(diagnostics.primary());
        final VectorRecord secondary = vectorRecord(diagnostics.secondary());
        final VectorRecord ensemble = vectorRecord(diagnostics.ensemble());
        return new PredictionRecord(
                primary, secondary, ensemble, disagreement(primary, secondary));
    }

    private static VectorRecord vectorRecord(
            final DeepSliceModelPrediction prediction) {
        return new VectorRecord(
                prediction.ouv().toList(),
                centerDepth(prediction.ouv()),
                prediction.geometry().zeroBasedAnteriorPosteriorIndex(),
                prediction.geometry().sagittalTiltDegrees(),
                prediction.geometry().horizontalTiltDegrees());
    }

    private static DisagreementRecord disagreement(
            final VectorRecord primary, final VectorRecord secondary) {
        final int axisDifference = Math.abs(primary.allenAxis0()
                - secondary.allenAxis0());
        final double sagittalDifference = Math.abs(
                primary.sagittalTiltDegrees()
                - secondary.sagittalTiltDegrees());
        final double horizontalDifference = Math.abs(
                primary.horizontalTiltDegrees()
                - secondary.horizontalTiltDegrees());
        return new DisagreementRecord(
                axisDifference,
                sagittalDifference,
                horizontalDifference,
                axisDifference > 20,
                sagittalDifference > 5.0d,
                horizontalDifference > 5.0d,
                axisDifference > 20 || sagittalDifference > 5.0d
                || horizontalDifference > 5.0d);
    }

    private static ProviderRuntimeRecord runtimeRecord(
            final ProviderRuntime requestedRuntime,
            final DeepSlicePlanePrediction prediction) {
        if (requestedRuntime instanceof NonRealTestOnly fake) {
            if (prediction.verifiedRuntimeProvenance().isPresent()) {
                throw new IllegalArgumentException(
                        "Non-real test provider unexpectedly returned runtime provenance");
            }
            return new ProviderRuntimeRecord(
                    "NON_REAL_TEST_ONLY", false,
                    Optional.of(fake.testProviderLabel()), Optional.empty());
        }
        if (requestedRuntime instanceof AuthorizedProtocolV2R3) {
            final DeepSliceRuntimeProvenance provenance = prediction
                    .verifiedRuntimeProvenance().orElseThrow(() ->
                            new IllegalArgumentException(
                                    "Real record lacks verified protocol-v2 r3 provenance"));
            verifyAuthorizedR3(provenance);
            return new ProviderRuntimeRecord(
                    "VERIFIED_PROTOCOL_V2_R3", true,
                    Optional.empty(), Optional.of(provenance));
        }
        throw new IllegalArgumentException("Unknown provider runtime marker");
    }

    private static ProviderRuntimeRecord providerRuntimeRecordForFailure(
            final ProviderRuntime requestedRuntime) {
        if (requestedRuntime instanceof NonRealTestOnly fake) {
            return new ProviderRuntimeRecord(
                    "NON_REAL_TEST_ONLY", false,
                    Optional.of(fake.testProviderLabel()), Optional.empty());
        }
        if (requestedRuntime instanceof AuthorizedProtocolV2R3) {
            return new ProviderRuntimeRecord(
                    "AUTHORIZED_PROTOCOL_V2_R3_REQUESTED", false,
                    Optional.empty(), Optional.empty());
        }
        throw new IllegalArgumentException("Unknown provider runtime marker");
    }

    private static void verifyAuthorizedR3(
            final DeepSliceRuntimeProvenance provenance) {
        if (!R3_RELEASE_ID.equals(provenance.verifiedReleaseId())
                || provenance.protocolVersion() != 2
                || provenance.manifestSizeBytes() != R3_MANIFEST_SIZE_BYTES
                || !R3_MANIFEST_SHA256.equals(provenance.manifestSha256())
                || !"3.11.15".equals(provenance.pythonVersion())
                || !"1.2.8".equals(provenance.deepSliceVersion())
                || !"2.21.0".equals(provenance.tensorflowVersion())
                || !"ebrains-mouse-ensemble-2025-01-31".equals(
                        provenance.modelRelease())
                || !R3_PRIMARY_WEIGHT_SHA256.equals(
                        provenance.primaryWeightSha256())
                || !R3_SECONDARY_WEIGHT_SHA256.equals(
                        provenance.secondaryWeightSha256())
                || !R3_BACKBONE_WEIGHT_SHA256.equals(
                        provenance.backboneWeightSha256())) {
            throw new IllegalArgumentException(
                    "Runtime provenance is not the authorized protocol-v2 r3 release");
        }
    }

    private static void verifyAuthorizedSourceOrder(
            final List<SourceInput> sources) {
        if (sources.size() != AUTHORIZED_SOURCE_IDS.size()) {
            throw new IllegalArgumentException(
                    "Development half panel requires exactly "
                            + AUTHORIZED_SOURCE_IDS.size() + " sources");
        }
        for (int index = 0; index < AUTHORIZED_SOURCE_IDS.size(); index++) {
            if (!AUTHORIZED_SOURCE_IDS.get(index).equals(
                    sources.get(index).sourceId())) {
                throw new IllegalArgumentException(
                        "Development source order differs at position "
                                + (index + 1));
            }
        }
    }

    private static Optional<String> sourceFileHashIfAvailable(
            final SourceInput source) {
        try {
            return Optional.of(sha256NoFollow(source.sourceFile()));
        } catch (final IOException ignored) {
            return Optional.empty();
        }
    }

    private static String verifiedLiveSourceFileSha256(
            final SourceInput source,
            final ProviderRuntime providerRuntime) throws IOException {
        final SourceInput requiredSource = Objects.requireNonNull(
                source, "source");
        if (providerRuntime instanceof AuthorizedProtocolV2R3
                && !requiredSource.hasCapturedRealSourceBinding()) {
            throw new SourceIntegrityFailure(
                    "Requested real inference requires captured source-byte binding");
        }
        final String liveSha256 = sha256NoFollow(requiredSource.sourceFile());
        requiredSource.verifyExpectedSourceFileSha256(liveSha256);
        return liveSha256;
    }

    private static double fraction(final BinaryMask mask) {
        return (double) mask.foregroundCount()
                / ((long) mask.width() * mask.height());
    }

    private ProviderCallTiming providerCallTiming(
            final Instant startedAtUtc,
            final long startedNanos) {
        final long finishedNanos = providerCallClock.nanoTime();
        final Instant finishedAtUtc = providerCallClock.instant();
        return new ProviderCallTiming(
                startedAtUtc,
                finishedAtUtc,
                Math.max(0L, finishedNanos - startedNanos));
    }

    private static String classifyFailure(final Exception error) {
        if (error instanceof SourceIntegrityFailure) {
            return "SOURCE_INTEGRITY_FAILURE";
        }
        if (error instanceof SourceEligibilityFailure) {
            return "INELIGIBLE_SOURCE_GEOMETRY";
        }
        return "CASE_FAILURE";
    }

    private static String detail(final Exception error) {
        final String message = error.getMessage();
        return error.getClass().getSimpleName() + ": "
                + (message == null || message.isBlank()
                ? "no detail" : message);
    }

    private static String sha256NoFollow(final Path source) throws IOException {
        final Path absolute = requireRegularNoFollowSourceFile(source);
        final MessageDigest digest = sha256();
        final OpenOption[] options = new OpenOption[]{
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS};
        try (FileChannel channel = FileChannel.open(absolute, options)) {
            final long expectedSize = channel.size();
            final ByteBuffer buffer = ByteBuffer.allocate(16_384);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
            if (channel.size() != expectedSize) {
                throw new IOException("Source file changed while hashing: "
                        + absolute);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Path requireRegularNoFollowSourceFile(final Path source)
            throws IOException {
        final Path absolute = Objects.requireNonNull(source, "source")
                .toAbsolutePath().normalize();
        final Path parent = Objects.requireNonNull(absolute.getParent(),
                "Source file must have a parent");
        if (Files.isSymbolicLink(parent)
                || Files.isSymbolicLink(absolute)
                || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Source file must be a regular non-symlink: "
                    + absolute);
        }
        return absolute;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String inferenceInputIdentitySha256(
            final int width,
            final int height,
            final String pixelsSha256,
            final String productionSyntheticMaskSha256) {
        final String canonical = INFERENCE_INPUT_IDENTITY_SCHEMA + '\n'
                + "width=" + width + '\n'
                + "height=" + height + '\n'
                + "pixelsSha256=" + pixelsSha256 + '\n'
                + "productionSyntheticMaskSha256="
                + productionSyntheticMaskSha256 + '\n';
        final MessageDigest digest = sha256();
        digest.update(canonical.getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(digest.digest());
    }

    interface ProviderCallClock {

        Instant instant();

        long nanoTime();
    }

    private static final class SystemProviderCallClock
            implements ProviderCallClock {

        @Override
        public Instant instant() {
            return Instant.now();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    }

    private record ProviderCallTiming(
            Instant startedAtUtc,
            Instant finishedAtUtc,
            long runtimeNanoseconds) {

        private ProviderCallTiming {
            startedAtUtc = Objects.requireNonNull(startedAtUtc, "startedAtUtc");
            finishedAtUtc = Objects.requireNonNull(
                    finishedAtUtc, "finishedAtUtc");
            if (finishedAtUtc.isBefore(startedAtUtc) || runtimeNanoseconds < 0L) {
                throw new IllegalArgumentException(
                        "Provider timing must be monotonic and non-negative");
            }
        }
    }

    private static double centerDepth(final DeepSliceOuv ouv) {
        final double nx = finite((ouv.uy() * ouv.vz()
                - ouv.uz() * ouv.vy()) / 9.0d, "normal x");
        final double ny = finite((ouv.uz() * ouv.vx()
                - ouv.ux() * ouv.vz()) / 9.0d, "normal y");
        final double nz = finite((ouv.ux() * ouv.vy()
                - ouv.uy() * ouv.vx()) / 9.0d, "normal z");
        if (ny == 0.0d) {
            throw new IllegalArgumentException(
                    "DeepSlice O/U/V has a singular normal denominator");
        }
        final double k = finite(-fsum(
                finite(ouv.ox() * nx, "origin-normal x"),
                finite(ouv.oy() * ny, "origin-normal y"),
                finite(ouv.oz() * nz, "origin-normal z")), "plane intercept");
        final double depth = finite(-finite(
                228.0d * nx + 160.0d * nz + k,
                "center-depth numerator") / ny, "center depth");
        final double roundedDepth = finite(Math.floor(depth + 0.5d),
                "rounded center depth");
        final double axis = finite(527.0d - roundedDepth, "axis 0");
        if (axis < 0.0d || axis >= 528.0d) {
            throw new IllegalArgumentException(
                    "DeepSlice center depth maps outside Allen axis 0");
        }
        return depth;
    }

    private static double fsum(
            final double first, final double second, final double third) {
        final double[] partials = new double[3];
        int count = 0;
        for (final double input : new double[]{first, second, third}) {
            double value = input;
            int retained = 0;
            for (int index = 0; index < count; index++) {
                double other = partials[index];
                if (Math.abs(value) < Math.abs(other)) {
                    final double temporary = value;
                    value = other;
                    other = temporary;
                }
                final double high = finite(value + other, "compensated sum");
                final double roundoff = high - value;
                final double low = other - roundoff;
                if (low != 0.0d) {
                    partials[retained++] = low;
                }
                value = high;
            }
            if (value != 0.0d) {
                partials[retained++] = value;
            }
            count = retained;
        }
        if (count == 0) {
            return 0.0d;
        }
        double high = partials[--count];
        while (count > 0) {
            final double value = high;
            final double other = partials[--count];
            high = finite(value + other, "compensated sum");
            final double roundoff = high - value;
            final double low = other - roundoff;
            if (low != 0.0d) {
                if (count > 0 && ((low < 0.0d && partials[count - 1] < 0.0d)
                        || (low > 0.0d && partials[count - 1] > 0.0d))) {
                    final double twiceLow = low * 2.0d;
                    final double corrected = high + twiceLow;
                    if (twiceLow == corrected - high) {
                        high = corrected;
                    }
                }
                break;
            }
        }
        return finite(high, "compensated sum");
    }

    private static double finite(final double value, final String label) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "DeepSlice " + label + " must be finite");
        }
        return value;
    }

    private static void validateCaseIdentity(
            final int ordinal,
            final String sourceId,
            final String sourceFilename,
            final String condition) {
        if (ordinal <= 0) {
            throw new IllegalArgumentException("ordinal must be positive");
        }
        requireText(sourceId, "sourceId");
        requireText(sourceFilename, "sourceFilename");
        requireText(condition, "condition");
    }

    private static Optional<String> optionalSha256(
            final Optional<String> value, final String name) {
        final Optional<String> required = Objects.requireNonNull(value, name);
        return required.map(item -> requireSha256(item, name));
    }

    private static String requireSha256(final String value, final String name) {
        final String required = Objects.requireNonNull(value, name);
        if (!required.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be lower-case SHA-256");
        }
        return required;
    }

    private static String requireText(final String value, final String name) {
        final String required = Objects.requireNonNull(value, name);
        if (required.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return required;
    }

    private static void validateFraction(final double value, final String name) {
        if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
            throw new IllegalArgumentException(
                    name + " must be a finite unit interval value");
        }
    }

    private record SourcePreparation(
            SourceInput source,
            String sourceFileSha256,
            SourceImageSnapshot snapshotBefore,
            RegistrationPreview preview,
            BinaryMask fullSourceMask,
            TissueGeometryResult fullSourceGeometry,
            ValidationHalfDerivativePreparationOutcome.Ready derivatives,
            String sourcePixelsSha256,
            String fullSourceMaskSha256) {

        private SourcePreparation {
            source = Objects.requireNonNull(source, "source");
            sourceFileSha256 = requireSha256(
                    sourceFileSha256, "sourceFileSha256");
            snapshotBefore = Objects.requireNonNull(
                    snapshotBefore, "snapshotBefore");
            preview = Objects.requireNonNull(preview, "preview");
            fullSourceMask = Objects.requireNonNull(
                    fullSourceMask, "fullSourceMask");
            fullSourceGeometry = Objects.requireNonNull(
                    fullSourceGeometry, "fullSourceGeometry");
            derivatives = Objects.requireNonNull(derivatives, "derivatives");
            sourcePixelsSha256 = requireSha256(
                    sourcePixelsSha256, "sourcePixelsSha256");
            fullSourceMaskSha256 = requireSha256(
                    fullSourceMaskSha256, "fullSourceMaskSha256");
        }
    }

    private static final class SourceIntegrityFailure extends IOException {

        private SourceIntegrityFailure(final String message) {
            super(message);
        }
    }

    private static final class SourceEligibilityFailure
            extends IllegalArgumentException {

        private SourceEligibilityFailure(final String message) {
            super(message);
        }
    }
}
