package org.atlasalign.plugin;

import org.atlasalign.plugin.setup.AtlasSetupDialog;
import org.atlasalign.plugin.setup.RuntimeSettings;
import ij.ImagePlus;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.ArrayList;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.atlasalign.application.AlignmentReviewBasis;
import org.atlasalign.application.AutomaticAlignmentEligibility;
import org.atlasalign.application.AutomaticAlignmentOutcome;
import org.atlasalign.application.AutomaticPlaneInitialization;
import org.atlasalign.application.AutomaticEligibilityStatus;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.AlignmentReviewSession;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasAssetVerification;
import org.atlasalign.application.AtlasReviewProvenance;
import org.atlasalign.application.BaselineRegistrationProposal;
import org.atlasalign.application.DeepSliceInput;
import org.atlasalign.application.DeepSliceInputCondition;
import org.atlasalign.application.DeepSliceInputProvenance;
import org.atlasalign.application.DeepSlicePlanePrediction;
import org.atlasalign.application.DeepSlicePlaneProvider;
import org.atlasalign.application.DeepSliceRuntimeProvenance;
import org.atlasalign.application.InitialPlaneEstimator;
import org.atlasalign.application.InitialPlaneProposal;
import org.atlasalign.application.InitialPlaneSource;
import org.atlasalign.application.ManualFallbackReason;
import org.atlasalign.application.MaskRegistrationEngine;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.ReviewAcceptanceVerification;
import org.atlasalign.application.ReviewAcceptanceVerifier;
import org.atlasalign.application.ReviewPreviewDimensions;
import org.atlasalign.application.SafeImageIntakeService;
import org.atlasalign.application.SafePreviewResult;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.SourceVerificationException;
import org.atlasalign.application.SyntheticPixelReviewPolicy;
import org.atlasalign.application.TissueGeometryClassifier;
import org.atlasalign.application.TissueGeometryResult;
import org.atlasalign.application.TissueSegmentationResult;
import org.atlasalign.application.TissueSegmenter;
import org.atlasalign.application.VirtualHalfPreparationOutcome;
import org.atlasalign.application.VirtualHalfPreparationProvenance;
import org.atlasalign.application.VirtualHalfPreviewBuilder;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.atlas.AtlasCoronalPlaneLoader;
import org.atlasalign.atlas.AtlasRepository;
import org.atlasalign.atlas.VerifiedAtlas;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.deepslice.AtlasAlignDeepSliceRuntime;
import org.atlasalign.deepslice.DeepSliceProcessBridge;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.io.imagej.ImagePlusSourcePixelReader;
import org.atlasalign.plugin.export.ManualRoiExportService;
import org.atlasalign.plugin.export.SourceSpaceExportService;
import org.atlasalign.plugin.review.AtlasPlaneRequest;
import org.atlasalign.plugin.review.AtlasPlaneSource;
import org.atlasalign.plugin.review.ManualRoiSessionStore;
import org.atlasalign.plugin.review.ReviewController;
import org.atlasalign.plugin.review.ReviewPreview;
import org.atlasalign.plugin.review.SwingReviewWindow;
import org.atlasalign.plugin.review.VerifiedAtlasPlaneSource;
import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/**
 * Complete source-preserving Phase 5 intake and review launch.
 */
@Plugin(
        type = Command.class,
        description = "Open an explicit, reversible Allen atlas alignment review")
public final class ReviewAlignmentCommand implements Command {

    public static final Path DEFAULT_ATLAS_CACHE =
            RuntimeSettings.defaultRoot().resolve("atlas/allen_mouse_25um");
    public static final Path DEFAULT_DEEPSLICE_RUNTIME =
            RuntimeSettings.defaultRoot().resolve("deepslice/runtime");
    public static final Path DEFAULT_DEEPSLICE_WORK =
            RuntimeSettings.defaultRoot().resolve("deepslice/work");
    static final Duration DEEPSLICE_TIMEOUT = Duration.ofMinutes(30);
    static final double MINIMUM_OBLIQUE_ATLAS_TISSUE_RETENTION = 0.5;

    @Parameter(
            label = "Source image",
            callback = "sourceImageChanged")
    private ImagePlus sourceImage;

    @Parameter(
            label = "Registration channel (1-based)",
            initializer = "initializeRegistrationChannel",
            min = "1",
            persist = false)
    private int registrationChannel = 1;

    @Parameter(
            label = "Maximum preview dimension (pixels)",
            min = "64")
    private int maximumPreviewDimension = 2_048;

    @Parameter(
            label = "Fallback Allen coronal level (0-based)",
            description = "Used only when local DeepSlice is disabled or unavailable; "
                    + "axis-0 index, 0..527; not a bregma coordinate",
            min = "0",
            max = "527")
    private int initialCoronalLevel = 264;

    @Parameter(label = "Use verified local DeepSlice", persist = false)
    private boolean useLocalDeepSlice;

    @Parameter(
            label = "Verified local DeepSlice runtime",
            style = "directory",
            persist = false)
    private File deepSliceRuntimeDirectory =
            new RuntimeSettings().paths().deepSliceRuntime().toFile();

    @Parameter(
            label = "DeepSlice temporary work directory",
            style = "directory",
            persist = false)
    private File deepSliceWorkDirectory =
            new RuntimeSettings().paths().deepSliceWork().toFile();

    @Parameter(
            label = "Verified Allen atlas cache",
            style = "directory",
            persist = false)
    private File atlasCacheDirectory =
            new RuntimeSettings().paths().atlasCache().toFile();

    @Parameter
    private LogService log;

    @Parameter(required = false, persist = false,
            visibility = ItemVisibility.INVISIBLE)
    private String reviewSectionId = "Section 1";

    @Parameter(required = false, persist = false,
            visibility = ItemVisibility.INVISIBLE)
    private String parentSourceName = "";

    @Parameter(required = false, persist = false,
            visibility = ItemVisibility.INVISIBLE)
    private String parentSourcePixelSha256 = "";

    @Parameter(required = false, persist = false,
            visibility = ItemVisibility.INVISIBLE)
    private int parentSourceWidth;

    @Parameter(required = false, persist = false,
            visibility = ItemVisibility.INVISIBLE)
    private int parentSourceHeight;

    @Parameter(required = false, persist = false,
            visibility = ItemVisibility.INVISIBLE)
    private int sectionSourceOffsetX;

    @Parameter(required = false, persist = false,
            visibility = ItemVisibility.INVISIBLE)
    private int sectionSourceOffsetY;

    @Parameter(required = false, persist = false,
            visibility = ItemVisibility.INVISIBLE)
    private String manualRoiDraftPath = "";

    @Override
    public void run() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException(
                    "The alignment review window requires a graphical Fiji session");
        }
        runAwayFromSwingEventThread(
                this::launchReview,
                this::reportPreparationFailure);
    }

    private void launchReview() {
        final Path runtimePath = deepSliceRuntimeDirectory.toPath()
                .toAbsolutePath().normalize();
        final Path workPath = deepSliceWorkDirectory.toPath()
                .toAbsolutePath().normalize();
        final Optional<Path> availableAtlas = AtlasSetupDialog
                .ensureAvailable(atlasCacheDirectory.toPath().toAbsolutePath().normalize());
        if (availableAtlas.isEmpty()) return;
        final Path atlasPath = availableAtlas.orElseThrow();
        new RuntimeSettings().saveDeepSlice(runtimePath, workPath);
        log.info("AtlasAlign requested paths: runtime=" + runtimePath
                + "; work=" + workPath + "; atlas=" + atlasPath);
        final ReviewLaunch launch;
        if (useLocalDeepSlice) {
            try (DeepSliceProcessBridge provider =
                    AtlasAlignDeepSliceRuntime.open(
                            runtimePath,
                            DEEPSLICE_TIMEOUT,
                            workPath)) {
                launch = prepareReview(
                        sourceImage,
                        registrationChannel,
                        maximumPreviewDimension,
                        initialCoronalLevel,
                        atlasPath,
                        Optional.of(provider),
                        true);
            }
        } else {
            launch = prepareReview(
                    sourceImage,
                    registrationChannel,
                    maximumPreviewDimension,
                    initialCoronalLevel,
                    atlasPath,
                    Optional.empty(),
                    false);
        }
        log.info("AtlasAlign verified unchanged source SHA-256: "
                + launch.safePreview().verifiedSource().pixelSha256());
        log.info("AtlasAlign opened verified atlas: "
                + launch.verifiedAtlas().cacheDirectory());
        final TissueSegmentationResult segmentation =
                launch.session().state().basis()
                        .segmentation().orElseThrow();
        log.info("AtlasAlign selected tissue segmentation: method="
                + segmentation.method()
                + "; polarity=" + segmentation.polarity()
                + "; threshold=" + segmentation.threshold()
                + "; histogramBounds="
                + segmentation.histogramLowerBound()
                + ".." + segmentation.histogramUpperBound()
                + "; percentileWindowFallback="
                + segmentation.percentileWindowFallback()
                + "; foregroundFraction="
                + segmentation.foregroundFraction()
                + "; largestComponentFraction="
                + segmentation.largestComponentFraction()
                + "; borderForegroundFraction="
                + segmentation.borderForegroundFraction()
                + "; supportDetectionMethod="
                + segmentation.supportDetectionMethod()
                + "; supportForegroundFraction="
                + (double) segmentation.supportMask().foregroundCount()
                        / Math.multiplyExact(
                                segmentation.supportMask().width(),
                                segmentation.supportMask().height())
                + "; score=" + segmentation.candidateScore());
        segmentation.candidates().forEach(candidate -> log.info(
                "AtlasAlign tissue candidate: method="
                        + candidate.method()
                        + "; polarity=" + candidate.polarity()
                        + "; threshold=" + candidate.threshold()
                        + "; companionThreshold="
                        + candidate.companionThreshold()
                                .map(Object::toString)
                                .orElse("none")
                        + "; histogramBounds="
                        + candidate.histogramLowerBound()
                        + ".." + candidate.histogramUpperBound()
                        + "; percentileWindowFallback="
                        + candidate.percentileWindowFallback()
                        + "; foregroundFraction="
                        + candidate.foregroundFraction()
                        + "; largestComponentFraction="
                        + candidate.largestComponentFraction()
                        + "; borderForegroundFraction="
                        + candidate.borderForegroundFraction()
                        + "; exteriorBackgroundFraction="
                        + candidate.exteriorBackgroundFraction()
                                .map(Object::toString)
                                .orElse("not-applicable")
                        + "; exteriorBackgroundBorderFraction="
                        + candidate.exteriorBackgroundBorderFraction()
                                .map(Object::toString)
                                .orElse("not-applicable")
                        + "; score=" + candidate.score()
                        + "; rejection="
                        + candidate.rejectionReason()
                                .orElse("none")));
        segmentation.auditNotes().forEach(note -> log.info(
                "AtlasAlign tissue segmentation audit note: " + note));
        final TissueGeometryResult geometry = launch.session()
                .state().basis().proposal().geometry();
        final var envelope = new TissueGeometryClassifier()
                .envelopeMeasurements(segmentation.mask());
        log.info("AtlasAlign tissue geometry: "
                + geometry.geometry()
                + "; boundsOccupancy="
                + geometry.foregroundToBoundsFraction()
                + "; leftEdgeDispersion="
                + geometry.imageLeftEdgeDispersion()
                + "; rightEdgeDispersion="
                + geometry.imageRightEdgeDispersion()
                + "; bilateralMirroredOverlap="
                + geometry.bilateralMirroredOverlap()
                + "; hemisphereBalance="
                + geometry.hemisphereBalance()
                + "; connectedComponentCount="
                + geometry.connectedComponentCount()
                + "; substantialComponentCount="
                + geometry.substantialComponentCount()
                + "; envelopeBoundsOccupancy="
                + envelope.foregroundToBoundsFraction()
                + "; envelopeBilateralMirroredOverlap="
                + envelope.bilateralMirroredOverlap()
                + "; envelopeHemisphereBalance="
                + envelope.hemisphereBalance()
                + "; envelopeAddedPreviewFraction="
                + envelope.addedPreviewFraction());
        log.info("AtlasAlign registration objective: "
                + launch.session().state().basis().proposal()
                        .objectiveMode());
        logProposal(launch, log);
        final ReviewPreview copiedPreview =
                ReviewPreview.copyOf(
                        launch.safePreview().preview());
        log.info("AtlasAlign display-only preview contrast: lower="
                + copiedPreview.displayWindow().lower()
                + "; upper="
                + copiedPreview.displayWindow().upper()
                + "; strategy="
                + copiedPreview.displayWindow().strategy()
                + "; source unchanged");
        final ReviewAcceptanceVerifier liveVerifier = () -> {
            final ImagePlusSourceImage liveSource =
                    new ImagePlusSourceImage(sourceImage);
            final VerifiedAtlas reopened = new AtlasRepository()
                    .openAllenMouse25um(atlasPath);
            return new ReviewAcceptanceVerification(
                    liveSource.snapshot(), provenance(reopened));
        };
        final ReviewController controller =
                ReviewController.forSwing(
                        launch.session(),
                        copiedPreview,
                        launch.atlasPlaneSource(),
                        liveVerifier);
        final ImagePlusSourcePixelReader sourceReader =
                new ImagePlusSourcePixelReader(sourceImage);
        final SourceSpaceExportService exportService =
                new SourceSpaceExportService(
                        sourceReader,
                        launch.atlasPlaneSource(), liveVerifier,
                        controller::acceptedAlignment);
        final var previewMapping = launch.safePreview().preview().mapping();
        final ReviewerRoiSession manualRoiSession;
        final ManualRoiSessionStore manualRoiStore;
        if (manualRoiDraftPath == null || manualRoiDraftPath.isBlank()) {
            manualRoiSession = new ReviewerRoiSession(
                    checkedSectionId(), previewMapping.sourceWidth(),
                    previewMapping.sourceHeight());
            manualRoiStore = null;
        } else {
            manualRoiStore = new ManualRoiSessionStore(
                    Path.of(manualRoiDraftPath), checkedSectionId(),
                    previewMapping.sourceWidth(),
                    previewMapping.sourceHeight(),
                    launch.safePreview().verifiedSource().pixelSha256());
            manualRoiSession = manualRoiStore.loadOrCreate();
            manualRoiStore.bind(manualRoiSession);
        }
        final Optional<ManualRoiExportService.ParentSourceContext>
                parentContext = parentSourceContext();
        final ManualRoiExportService manualRoiExportService =
                new ManualRoiExportService(sourceReader,
                        launch.safePreview().verifiedSource(),
                        registrationChannel,
                        Math.max(1, sourceImage.getZ()),
                        Math.max(1, sourceImage.getT()), parentContext);
        SwingUtilities.invokeLater(() -> {
            try {
                final var frame = SwingReviewWindow.open(
                        sourceImage.getTitle(), controller, exportService,
                        manualRoiExportService, manualRoiSession);
                org.atlasalign.plugin.batch.BatchReviewNavigation.attach(
                        sourceImage, frame);
            } catch (final RuntimeException error) {
                reportPreparationFailure(error);
            }
        });
    }

    private String checkedSectionId() {
        return reviewSectionId == null || reviewSectionId.isBlank()
                ? "Section 1" : reviewSectionId.trim();
    }

    private Optional<ManualRoiExportService.ParentSourceContext>
            parentSourceContext() {
        if (parentSourceName == null || parentSourceName.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ManualRoiExportService.ParentSourceContext(
                parentSourceName, parentSourcePixelSha256,
                parentSourceWidth, parentSourceHeight,
                sectionSourceOffsetX, sectionSourceOffsetY));
    }

    private void initializeRegistrationChannel() {
        registrationChannel =
                registrationChannelDefault(sourceImage);
    }

    private void sourceImageChanged() {
        initializeRegistrationChannel();
    }

    static int registrationChannelDefault(
            final ImagePlus image) {
        if (image == null) {
            return 1;
        }
        return Math.max(1, Math.min(
                image.getNChannels(), image.getC()));
    }

    private void reportPreparationFailure(final RuntimeException error) {
        final boolean batchFailure = org.atlasalign.plugin.batch.BatchReviewNavigation
                .fail(sourceImage, error);
        final String detail = error.getMessage() == null
                || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
        log.error(
                "AtlasAlign could not prepare the alignment review: "
                        + detail,
                error);
        if (batchFailure) return; // The batch queue reports the same failure.
        JOptionPane.showMessageDialog(
                null,
                "AtlasAlign could not prepare the alignment review.\n\n"
                        + detail
                        + "\n\nThe source image was not modified.",
                "AtlasAlign review failed",
                JOptionPane.ERROR_MESSAGE);
    }

    static void runAwayFromSwingEventThread(
            final Runnable task,
            final Consumer<RuntimeException> failureHandler) {
        final Runnable guardedTask = () -> {
            try {
                task.run();
            } catch (final RuntimeException error) {
                SwingUtilities.invokeLater(
                        () -> failureHandler.accept(error));
            }
        };
        if (!SwingUtilities.isEventDispatchThread()) {
            guardedTask.run();
            return;
        }
        final Thread worker = new Thread(
                guardedTask, "atlasalign-review-preparation");
        worker.setDaemon(true);
        worker.start();
    }

    static void logProposal(
            final ReviewLaunch launch,
            final LogService targetLog) {
        logVirtualHalfPreparation(
                launch.virtualHalfPreparationOutcome(), targetLog);
        final InitialPlaneProposal proposal = launch.session()
                .state().basis().initialPlaneProposal().orElseThrow();
        final AutomaticAlignmentOutcome outcome = AutomaticAlignmentOutcome
                .from(proposal, launch.virtualHalfPreparationOutcome());
        targetLog.info("AtlasAlign automatic outcome: " + outcome);
        targetLog.info("AtlasAlign initial plane: Allen axis-0 level "
                + proposal.coronalLevel().zeroBasedAnteriorPosteriorIndex()
                + "; source=" + proposal.source());
        final AlignmentReviewBasis basis = launch.session().state().basis();
        basis.deepSliceInputProvenance().ifPresent(input -> targetLog.info(
                "AtlasAlign exact DeepSlice input: revision="
                        + input.algorithmRevision()
                        + "; condition=" + input.condition()
                        + "; dimensions=" + input.width() + "x"
                        + input.height()
                        + "; pixelsSha256=" + input.pixelsSha256()
                        + "; syntheticMaskSha256="
                        + input.syntheticMaskSha256()
                        + "; syntheticPixelFraction="
                        + input.syntheticPixelFraction()
                        + "; observedTissueFraction="
                        + (input.observedTissueFraction().isPresent()
                        ? input.observedTissueFraction().getAsDouble()
                        : "unavailable")));
        final AutomaticPlaneInitialization initialization =
                basis.automaticPlaneInitialization();
        targetLog.info("AtlasAlign automatic plane eligibility: status="
                + initialization.eligibility().status()
                + "; reasons="
                + String.join(" | ",
                initialization.eligibility().reasons())
                + "; appliedTilt=" + initialization.appliedTilt()
                + "; predictionApplied="
                + initialization.predictionApplied());
        proposal.prediction().ifPresent(prediction -> {
            targetLog.info("AtlasAlign DeepSlice proposal tilts: sagittal="
                    + prediction.sagittalTiltDegrees()
                    + " degrees; horizontal="
                    + prediction.horizontalTiltDegrees() + " degrees. "
                    + (initialization.predictionApplied()
                    ? "The verified ensemble tilt initialized the atlas reslice and baseline registration for this eligible complete section. "
                    : "The prediction was retained as review-only diagnostic guidance and the initial atlas plane remained coronal. ")
                    + "Reflection and laterality were not inferred and "
                    + "require explicit review.");
            prediction.diagnostics().ifPresent(diagnostics -> {
                final var primary = diagnostics.primary().geometry();
                final var secondary = diagnostics.secondary().geometry();
                final var ensemble = diagnostics.ensemble().geometry();
                targetLog.info("AtlasAlign normalized DeepSlice diagnostics: "
                        + "primary level="
                        + primary.zeroBasedAnteriorPosteriorIndex()
                        + "; sagittal="
                        + primary.sagittalTiltDegrees()
                        + "; horizontal="
                        + primary.horizontalTiltDegrees()
                        + "; secondary level="
                        + secondary.zeroBasedAnteriorPosteriorIndex()
                        + "; sagittal="
                        + secondary.sagittalTiltDegrees()
                        + "; horizontal="
                        + secondary.horizontalTiltDegrees()
                        + "; ensemble level="
                        + ensemble.zeroBasedAnteriorPosteriorIndex()
                        + "; sagittal="
                        + ensemble.sagittalTiltDegrees()
                        + "; horizontal="
                        + ensemble.horizontalTiltDegrees());
                if (prediction.hasPrimarySecondaryCaution()) {
                    targetLog.warn("AtlasAlign DeepSlice primary-secondary "
                            + "diagnostic disagreement exceeds a strict "
                            + "review threshold; this result is not eligible "
                            + "for automatic predicted-plane initialization.");
                }
            });
            logVerifiedRuntimeProvenance(prediction, targetLog);
        });
        proposal.fallbackReason().ifPresent(reason -> targetLog.info(
                "AtlasAlign manual fallback reason: " + reason
                        + "; " + proposal.fallbackMessage().orElse("")));
    }

    /**
     * Logs only application-owned facts carried by a real verified worker
     * result. In-memory and mock proposals intentionally have no runtime
     * provenance and must not be made to look like production inference.
     */
    static void logVerifiedRuntimeProvenance(
            final DeepSlicePlanePrediction prediction,
            final LogService targetLog) {
        Objects.requireNonNull(prediction, "prediction");
        Objects.requireNonNull(targetLog, "targetLog");
        prediction.verifiedRuntimeProvenance().ifPresent(provenance ->
                logVerifiedRuntimeProvenance(provenance, targetLog));
    }

    private static void logVerifiedRuntimeProvenance(
            final DeepSliceRuntimeProvenance provenance,
            final LogService targetLog) {
        targetLog.info("AtlasAlign verified DeepSlice runtime: releaseId="
                + provenance.verifiedReleaseId()
                + "; canonicalRuntimePath="
                + provenance.canonicalRuntimePath()
                + "; protocolVersion=" + provenance.protocolVersion()
                + "; manifestSizeBytes=" + provenance.manifestSizeBytes()
                + "; manifestSha256=" + provenance.manifestSha256()
                + "; pythonVersion=" + provenance.pythonVersion()
                + "; deepSliceVersion=" + provenance.deepSliceVersion()
                + "; tensorflowVersion=" + provenance.tensorflowVersion()
                + "; modelRelease=" + provenance.modelRelease()
                + "; primaryWeightSha256="
                + provenance.primaryWeightSha256()
                + "; secondaryWeightSha256="
                + provenance.secondaryWeightSha256()
                + "; backboneWeightSha256="
                + provenance.backboneWeightSha256());
    }

    private static void logVirtualHalfPreparation(
            final VirtualHalfPreparationOutcome outcome,
            final LogService targetLog) {
        if (outcome instanceof VirtualHalfPreparationOutcome.NotApplicable) {
            targetLog.info("AtlasAlign virtual-half preparation: "
                    + "outcome=NotApplicable; policy="
                    + SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS
                    + "; original preview is the inference input.");
            return;
        }
        if (outcome instanceof VirtualHalfPreparationOutcome.Ready ready) {
            final VirtualHalfPreparationProvenance provenance =
                    ready.provenance();
            targetLog.info("AtlasAlign virtual-half preparation: "
                    + "outcome=Ready; mode=" + provenance.mode()
                    + "; identity=" + provenance.preparationIdentitySha256()
                    + "; originalDimensions=" + provenance.originalWidth()
                    + "x" + provenance.originalHeight()
                    + "; inferenceDimensions="
                    + provenance.inferenceWidth() + "x"
                    + provenance.inferenceHeight()
                    + "; originalToInferenceOffset="
                    + provenance.originalToInferenceOffsetX() + ","
                    + provenance.originalToInferenceOffsetY()
                    + "; background=" + provenance.backgroundStrategy()
                    + "; backgroundValue="
                    + provenance.backgroundValue()
                            .map(Object::toString).orElse("none")
                    + "; backgroundSamples="
                    + provenance.backgroundSampleCount()
                    + "; syntheticMaskSha256="
                    + provenance.syntheticMaskSha256()
                    + "; policy="
                    + SyntheticPixelReviewPolicy.SYNTHETIC_PIXELS_EXCLUDED);
            return;
        }
        if (outcome instanceof VirtualHalfPreparationOutcome.ManualRequired
                manualRequired) {
            targetLog.info("AtlasAlign virtual-half preparation: "
                    + "outcome=ManualRequired; reason="
                    + manualRequired.reason()
                    + "; provider=not-called; audit="
                    + manualRequired.auditMessage()
                    + "; policy="
                    + SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS);
            return;
        }
        throw new IllegalArgumentException(
                "Unknown virtual-half preparation outcome: " + outcome);
    }

    static ReviewLaunch prepareReview(
            final ImagePlus source,
            final int channel,
            final int maximumDimension,
            final int initialLevel,
            final Path atlasCache) {
        return prepareReview(
                source,
                channel,
                maximumDimension,
                initialLevel,
                atlasCache,
                Optional.empty());
    }

    static ReviewLaunch prepareReview(
            final ImagePlus source,
            final int channel,
            final int maximumDimension,
            final int initialLevel,
            final Path atlasCache,
            final Optional<DeepSlicePlaneProvider> planeProvider) {
        return prepareReview(
                source, channel, maximumDimension, initialLevel, atlasCache,
                planeProvider, true);
    }

    static ReviewLaunch prepareReview(
            final ImagePlus source,
            final int channel,
            final int maximumDimension,
            final int initialLevel,
            final Path atlasCache,
            final Optional<DeepSlicePlaneProvider> planeProvider,
            final boolean automaticInferenceRequested) {
        final ImagePlusSourceImage readOnly =
                new ImagePlusSourceImage(source);
        final SafePreviewResult safe =
                new SafeImageIntakeService().preparePreview(
                        readOnly, channel, maximumDimension);
        final RegistrationPreview preview = safe.preview();
        final TissueSegmentationResult segmentation =
                new TissueSegmenter().segment(
                        preview.mapping().previewWidth(),
                        preview.mapping().previewHeight(),
                        preview.pixels());
        final TissueGeometryResult geometry =
                new TissueGeometryClassifier().classify(
                        segmentation.mask());
        final long inferenceStarted = System.nanoTime();
        final VirtualHalfPreparationOutcome virtualHalfOutcome =
                new VirtualHalfPreviewBuilder().build(
                        preview, segmentation.mask(), geometry);
        final InferencePreparation inferencePreparation =
                routeVirtualHalfPreparation(
                        preview,
                        new AllenCoronalLevel(initialLevel),
                        planeProvider,
                        virtualHalfOutcome,
                        automaticInferenceRequested,
                        OptionalDouble.of(
                                segmentation.foregroundFraction()));
        final InitialPlaneProposal planeProposal =
                inferencePreparation.planeProposal();
        final Duration inferenceDuration = Duration.ofNanos(
                System.nanoTime() - inferenceStarted);
        final long atlasLoadStarted = System.nanoTime();
        final VerifiedAtlas verifiedAtlas =
                new AtlasRepository().openAllenMouse25um(
                        atlasCache);
        final ReviewPreviewDimensions previewIdentity =
                ReviewPreviewDimensions.capture(
                        preview.mapping().previewWidth(),
                        preview.mapping().previewHeight(),
                        preview.pixels());
        AutomaticAlignmentEligibility eligibility =
                AutomaticAlignmentEligibility.evaluate(
                        planeProposal,
                        geometry.geometry(),
                        inferencePreparation.syntheticPixelPolicy(),
                        inferencePreparation.deepSliceInputProvenance(),
                        previewIdentity);
        final VerifiedAtlasPlaneSource atlasPlaneSource =
                new VerifiedAtlasPlaneSource(verifiedAtlas);
        final AutomaticAtlasPlaneSelection planeSelection =
                selectAutomaticAtlasPlane(
                        atlasPlaneSource, planeProposal, eligibility);
        eligibility = planeSelection.eligibility();
        final AutomaticPlaneInitialization planeInitialization =
                planeSelection.initialization();
        final AtlasCoronalPlane plane = planeSelection.plane();
        final BaselineRegistrationProposal baseline =
                new MaskRegistrationEngine().register(
                        atlasTissueMask(
                                plane, geometry.geometry()),
                        segmentation.mask(),
                        planeProposal.coronalLevel(),
                        geometry);
        final Duration atlasLoadAndRegistrationDuration =
                Duration.ofNanos(System.nanoTime() - atlasLoadStarted);
        final AlignmentReviewBasis basis =
                new AlignmentReviewBasis(
                        baseline,
                        Optional.of(planeProposal),
                        Optional.of(segmentation),
                        safe.verifiedSource(),
                        provenance(verifiedAtlas),
                        inferencePreparation.syntheticPixelPolicy(),
                        inferencePreparation
                                .inferencePreparationProvenance(),
                        previewIdentity,
                        inferencePreparation.deepSliceInputProvenance(),
                        planeInitialization);
        verifySourceUnchanged(source, safe.verifiedSource());
        final AlignmentReviewSession reviewSession =
                AlignmentReviewSession.forNewReview(basis);
        ReviewController.requireSafeInitialManualPlacement(reviewSession.state());
        return new ReviewLaunch(
                safe,
                verifiedAtlas,
                plane,
                atlasPlaneSource,
                reviewSession,
                new ReviewPreparationTimings(
                        inferenceDuration,
                        atlasLoadAndRegistrationDuration),
                inferencePreparation.virtualHalfPreparationOutcome());
    }

    static void verifySourceUnchanged(
            final ImagePlus source,
            final SourceImageSnapshot expected) {
        final SourceImageSnapshot current =
                new ImagePlusSourceImage(source).snapshot();
        if (!expected.equals(current)) {
            throw new SourceVerificationException(
                    "Source image changed while preparing the alignment "
                            + "review; the review was not opened");
        }
    }

    static InitialPlaneProposal estimateInitialPlane(
            final AllenCoronalLevel fallbackLevel,
            final DeepSliceInput input,
            final Optional<DeepSlicePlaneProvider> provider) {
        return new InitialPlaneEstimator().estimate(
                fallbackLevel, input, provider);
    }

    /**
     * Routes the immutable virtual-half preparation outcome without opening
     * Fiji UI, keeping synthetic pixels confined to provider inference.
     */
    static InferencePreparation routeVirtualHalfPreparation(
            final RegistrationPreview preview,
            final AllenCoronalLevel fallbackLevel,
            final Optional<DeepSlicePlaneProvider> provider,
            final VirtualHalfPreparationOutcome outcome) {
        return routeVirtualHalfPreparation(
                preview, fallbackLevel, provider, outcome, true,
                OptionalDouble.empty());
    }

    static InferencePreparation routeVirtualHalfPreparation(
            final RegistrationPreview preview,
            final AllenCoronalLevel fallbackLevel,
            final Optional<DeepSlicePlaneProvider> provider,
            final VirtualHalfPreparationOutcome outcome,
            final boolean automaticInferenceRequested) {
        return routeVirtualHalfPreparation(
                preview, fallbackLevel, provider, outcome,
                automaticInferenceRequested, OptionalDouble.empty());
    }

    static InferencePreparation routeVirtualHalfPreparation(
            final RegistrationPreview preview,
            final AllenCoronalLevel fallbackLevel,
            final Optional<DeepSlicePlaneProvider> provider,
            final VirtualHalfPreparationOutcome outcome,
            final boolean automaticInferenceRequested,
            final OptionalDouble observedTissueFraction) {
        Objects.requireNonNull(preview, "preview");
        Objects.requireNonNull(fallbackLevel, "fallbackLevel");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(
                observedTissueFraction, "observedTissueFraction");
        if (!automaticInferenceRequested) {
            return new InferencePreparation(
                    InitialPlaneProposal.manualOnly(fallbackLevel),
                    SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                    Optional.empty(),
                    Optional.empty(),
                    outcome);
        }
        if (outcome instanceof VirtualHalfPreparationOutcome.NotApplicable) {
            final DeepSliceInput originalPreviewInput = new DeepSliceInput(
                    preview.mapping().previewWidth(),
                    preview.mapping().previewHeight(),
                    preview.pixels(),
                    BinaryMask.empty(
                            preview.mapping().previewWidth(),
                            preview.mapping().previewHeight()));
            return new InferencePreparation(
                    estimateInitialPlane(
                            fallbackLevel, originalPreviewInput, provider),
                    SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                    Optional.empty(),
                    provider.isPresent()
                            ? Optional.of(DeepSliceInputProvenance.capture(
                            originalPreviewInput,
                            DeepSliceInputCondition.ORIGINAL_PREVIEW,
                            observedTissueFraction,
                            Optional.empty()))
                            : Optional.empty(),
                    outcome);
        }
        if (outcome instanceof VirtualHalfPreparationOutcome.Ready ready) {
            return new InferencePreparation(
                    estimateInitialPlane(
                            fallbackLevel, ready.inferenceInput(), provider),
                    SyntheticPixelReviewPolicy.SYNTHETIC_PIXELS_EXCLUDED,
                    Optional.of(ready.provenance()),
                    provider.isPresent()
                            ? Optional.of(DeepSliceInputProvenance.capture(
                            ready.inferenceInput(),
                            ready.provenance().mode()
                                    == org.atlasalign.application.VirtualHalfMode.FULL_CANVAS
                                    ? DeepSliceInputCondition.VIRTUAL_HALF_FULL_CANVAS
                                    : DeepSliceInputCondition.VIRTUAL_HALF_TIGHT_CROP,
                            observedTissueFraction,
                            Optional.of(ready.provenance()
                                    .preparationIdentitySha256())))
                            : Optional.empty(),
                    outcome);
        }
        if (outcome instanceof VirtualHalfPreparationOutcome.ManualRequired
                manualRequired) {
            final InitialPlaneProposal manualProposal =
                    new InitialPlaneProposal(
                            fallbackLevel,
                            InitialPlaneSource.MANUAL_FALLBACK,
                            Optional.empty(),
                            Optional.of(ManualFallbackReason
                                    .VIRTUAL_HALF_PREPARATION_MANUAL_REQUIRED),
                            Optional.of("Virtual-half preparation reason="
                                    + manualRequired.reason()
                                    + "; audit="
                                    + manualRequired.auditMessage()));
            return new InferencePreparation(
                    manualProposal,
                    SyntheticPixelReviewPolicy.NO_SYNTHETIC_PIXELS,
                    Optional.empty(),
                    Optional.empty(),
                    outcome);
        }
        throw new IllegalArgumentException(
                "Unknown virtual-half preparation outcome: " + outcome);
    }

    static BinaryMask atlasTissueMask(
            final AtlasCoronalPlane plane) {
        return atlasTissueMask(plane, SectionGeometry.FULL);
    }

    static boolean automaticObliquePlaneUsable(
            final AtlasCoronalPlane predicted,
            final AtlasCoronalPlane coronalComparison) {
        Objects.requireNonNull(predicted, "predicted");
        Objects.requireNonNull(coronalComparison, "coronalComparison");
        final long predictedTissue = annotationTissueCount(predicted);
        final long coronalTissue = annotationTissueCount(coronalComparison);
        return predictedTissue > 0 && coronalTissue > 0
                && predictedTissue / (double) coronalTissue
                >= MINIMUM_OBLIQUE_ATLAS_TISSUE_RETENTION;
    }

    static AutomaticAtlasPlaneSelection selectAutomaticAtlasPlane(
            final AtlasPlaneSource atlasPlaneSource,
            final InitialPlaneProposal proposal,
            final AutomaticAlignmentEligibility eligibility) {
        Objects.requireNonNull(atlasPlaneSource, "atlasPlaneSource");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(eligibility, "eligibility");
        AutomaticAlignmentEligibility selectedEligibility = eligibility;
        AutomaticPlaneInitialization initialization =
                AutomaticPlaneInitialization.from(
                        proposal, selectedEligibility);
        AtlasCoronalPlane plane = atlasPlaneSource.load(
                new AtlasPlaneRequest(
                        proposal.coronalLevel(),
                        initialization.appliedTilt(),
                        false));
        if (initialization.predictionApplied()) {
            final AtlasCoronalPlane coronal = atlasPlaneSource.load(
                    new AtlasPlaneRequest(
                            proposal.coronalLevel(),
                            AtlasPlaneTilt.CORONAL,
                            false));
            if (!automaticObliquePlaneUsable(plane, coronal)) {
                final ArrayList<String> reasons = new ArrayList<>(
                        selectedEligibility.reasons());
                reasons.add(
                        "The predicted oblique atlas plane retained less than half of the same-level coronal annotation tissue; initialization remained coronal.");
                selectedEligibility = new AutomaticAlignmentEligibility(
                        AutomaticEligibilityStatus.REVIEW_ONLY,
                        reasons);
                initialization = AutomaticPlaneInitialization.from(
                        proposal, selectedEligibility);
                plane = coronal;
            }
        }
        return new AutomaticAtlasPlaneSelection(
                plane, selectedEligibility, initialization);
    }

    private static long annotationTissueCount(
            final AtlasCoronalPlane plane) {
        long count = 0;
        for (final int annotation : plane.annotationId()) {
            if (annotation != 0) {
                count++;
            }
        }
        return count;
    }

    static BinaryMask atlasTissueMask(
            final AtlasCoronalPlane plane,
            final SectionGeometry geometry) {
        final int[] annotations = plane.annotationId();
        final boolean[] tissue =
                new boolean[annotations.length];
        for (int index = 0; index < annotations.length; index++) {
            final int x = index % plane.width();
            final boolean matchingImageSide = switch (geometry) {
                case IMAGE_LEFT_HALF -> x < plane.width() / 2;
                case IMAGE_RIGHT_HALF -> x >= plane.width() / 2;
                case FULL, BILATERAL_REVIEW_REQUIRED,
                        PARTIAL_OR_DAMAGED -> true;
            };
            tissue[index] = annotations[index] != 0
                    && matchingImageSide;
        }
        return BinaryMask.fromBooleans(
                plane.width(), plane.height(), tissue);
    }

    static AtlasReviewProvenance provenance(
            final VerifiedAtlas atlas) {
        return new AtlasReviewProvenance(
                atlas.manifest().atlasId(),
                atlas.manifest().atlasVersion(),
                AtlasCoronalPlaneLoader.CORONAL_WIDTH,
                AtlasCoronalPlaneLoader.CORONAL_HEIGHT,
                atlas.manifest().assets().stream()
                        .map(asset -> new AtlasAssetVerification(
                                asset.role(),
                                asset.sizeBytes(),
                                asset.sha256()))
                        .toList());
    }

    record ReviewLaunch(
            SafePreviewResult safePreview,
            VerifiedAtlas verifiedAtlas,
            AtlasCoronalPlane initialAtlasPlane,
            VerifiedAtlasPlaneSource atlasPlaneSource,
            AlignmentReviewSession session,
            ReviewPreparationTimings timings,
            VirtualHalfPreparationOutcome virtualHalfPreparationOutcome) {

        ReviewLaunch {
            atlasPlaneSource = Objects.requireNonNull(
                    atlasPlaneSource, "atlasPlaneSource");
            virtualHalfPreparationOutcome = Objects.requireNonNull(
                    virtualHalfPreparationOutcome,
                    "virtualHalfPreparationOutcome");
        }

        ReviewLaunch(
                final SafePreviewResult safePreview,
                final VerifiedAtlas verifiedAtlas,
                final AtlasCoronalPlane initialAtlasPlane,
                final VerifiedAtlasPlaneSource atlasPlaneSource,
                final AlignmentReviewSession session,
                final ReviewPreparationTimings timings) {
            this(
                    safePreview,
                    verifiedAtlas,
                    initialAtlasPlane,
                    atlasPlaneSource,
                    session,
                    timings,
                    new VirtualHalfPreparationOutcome.NotApplicable());
        }
    }

    record ReviewPreparationTimings(
            Duration inference,
            Duration atlasLoadAndRegistration) {
    }

    record AutomaticAtlasPlaneSelection(
            AtlasCoronalPlane plane,
            AutomaticAlignmentEligibility eligibility,
            AutomaticPlaneInitialization initialization) {

        AutomaticAtlasPlaneSelection {
            plane = Objects.requireNonNull(plane, "plane");
            eligibility = Objects.requireNonNull(
                    eligibility, "eligibility");
            initialization = Objects.requireNonNull(
                    initialization, "initialization");
        }
    }

    /**
     * Auditable routing result for virtual-half inference preparation.
     */
    record InferencePreparation(
            InitialPlaneProposal planeProposal,
            SyntheticPixelReviewPolicy syntheticPixelPolicy,
            Optional<VirtualHalfPreparationProvenance>
                    inferencePreparationProvenance,
            Optional<DeepSliceInputProvenance> deepSliceInputProvenance,
            VirtualHalfPreparationOutcome virtualHalfPreparationOutcome) {

        InferencePreparation {
            planeProposal = Objects.requireNonNull(
                    planeProposal, "planeProposal");
            syntheticPixelPolicy = Objects.requireNonNull(
                    syntheticPixelPolicy, "syntheticPixelPolicy");
            inferencePreparationProvenance = Objects.requireNonNull(
                    inferencePreparationProvenance,
                    "inferencePreparationProvenance");
            deepSliceInputProvenance = Objects.requireNonNull(
                    deepSliceInputProvenance,
                    "deepSliceInputProvenance");
            virtualHalfPreparationOutcome = Objects.requireNonNull(
                    virtualHalfPreparationOutcome,
                    "virtualHalfPreparationOutcome");
        }

        AutomaticAlignmentOutcome automaticOutcome() {
            return AutomaticAlignmentOutcome.from(
                    planeProposal, virtualHalfPreparationOutcome);
        }
    }
}
