package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import javax.swing.SwingUtilities;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AutomaticAlignmentEligibility;
import org.atlasalign.application.AutomaticEligibilityStatus;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.DeepSliceInput;
import org.atlasalign.application.DeepSliceOuv;
import org.atlasalign.application.DeepSlicePlanePrediction;
import org.atlasalign.application.DeepSliceRuntimeProvenance;
import org.atlasalign.application.InitialPlaneSource;
import org.atlasalign.application.InitialPlaneProposal;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.plugin.review.AtlasPlaneRequest;
import org.atlasalign.plugin.review.AtlasPlaneSource;
import org.junit.jupiter.api.Test;
import org.scijava.command.Command;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandModule;
import org.scijava.log.LogService;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.Parameter;
import org.scijava.module.process.InitPreprocessor;

class ReviewAlignmentCommandTest {

    @Test
    void isDiscoverableInTheReviewMenu() {
        final Plugin annotation =
                SingleSectionReviewCommand.class.getAnnotation(Plugin.class);

        assertNotNull(annotation);
        assertEquals(Command.class, annotation.type());
        assertEquals(
                "Plugins>AtlasAlign Lite>Review Atlas Alignment",
                annotation.menuPath());
        assertEquals(
                org.atlasalign.plugin.setup.RuntimeSettings.defaultRoot().resolve("atlas/allen_mouse_25um"),
                ReviewAlignmentCommand.DEFAULT_ATLAS_CACHE);
        assertEquals(
                org.atlasalign.plugin.setup.RuntimeSettings.defaultRoot().resolve("deepslice/runtime"),
                ReviewAlignmentCommand.DEFAULT_DEEPSLICE_RUNTIME);
        assertEquals(
                org.atlasalign.plugin.setup.RuntimeSettings.defaultRoot().resolve("deepslice/work"),
                ReviewAlignmentCommand.DEFAULT_DEEPSLICE_WORK);
        assertEquals(
                30,
                ReviewAlignmentCommand.DEEPSLICE_TIMEOUT.toMinutes());
    }

    @Test
    void safeLaunchInputsDoNotRestoreStalePreferences()
            throws NoSuchFieldException {
        for (final String field : new String[] {
                "registrationChannel",
                "registrationSlice",
                "registrationFrame",
                "useLocalDeepSlice",
                "deepSliceRuntimeDirectory",
                "deepSliceWorkDirectory",
                "atlasCacheDirectory"}) {
            final Parameter parameter = ReviewAlignmentCommand.class
                    .getDeclaredField(field)
                    .getAnnotation(Parameter.class);
            assertNotNull(parameter);
            assertEquals(false, parameter.persist());
        }
    }

    @Test
    void intakeRegistrationChannelDefaultReadsCurrentSourceChannel() {
        final ImageStack stack = new ImageStack(2, 2);
        stack.addSlice(new ByteProcessor(2, 2));
        stack.addSlice(new ByteProcessor(2, 2));
        final ImagePlus source = new ImagePlus("two-channel", stack);
        source.setDimensions(2, 1, 1);
        source.setC(2);

        assertEquals(
                2,
                ReviewAlignmentCommand
                        .registrationChannelDefault(source));
        assertEquals(
                1,
                ReviewAlignmentCommand
                        .registrationChannelDefault(null));

    }

    @Test
    void resolvedRegistrationScopeSurvivesRealSciJavaInitialization() throws Exception {
        // Reproduces commands.run(..., true, inputs): explicit inputs are set and
        // resolved before InitPreprocessor invokes the command's initializers.
        for (final int requestedChannel : new int[] {2, 1}) {
            final ImagePlus source = scopedSource(3 - requestedChannel);
            final var before = new ImagePlusSourceImage(source).snapshot();
            final int activeChannel = source.getC(), activeSlice = source.getZ(), activeFrame = source.getT();
            final double displayMinimum = source.getDisplayRangeMin(), displayMaximum = source.getDisplayRangeMax();
            final CommandModule module = initializedCommand(Map.of(
                    "sourceImage", source,
                    "registrationChannel", requestedChannel,
                    "registrationSlice", 2,
                    "registrationFrame", 1));

            assertEquals(requestedChannel, module.getInput("registrationChannel"),
                    "A resolved registration channel must not be replaced by the source's active display channel");
            assertEquals(2, module.getInput("registrationSlice"));
            assertEquals(1, module.getInput("registrationFrame"));
            for (final String name : List.of("sourceImage", "registrationChannel", "registrationSlice", "registrationFrame")) {
                assertTrue(module.isInputResolved(name), name);
            }
            // A source widget callback must not silently reset an explicit scope either.
            module.getInfo().getInput("sourceImage").callback(module);
            assertEquals(requestedChannel, module.getInput("registrationChannel"));
            assertEquals(before, new ImagePlusSourceImage(source).snapshot());
            assertEquals(activeChannel, source.getC());
            assertEquals(activeSlice, source.getZ());
            assertEquals(activeFrame, source.getT());
            assertEquals(displayMinimum, source.getDisplayRangeMin());
            assertEquals(displayMaximum, source.getDisplayRangeMax());
        }
    }

    @Test
    void workerCompatibilityDefaultsRemainOneWhileIntakeUsesCurrentChannel() throws Exception {
        final ImagePlus source = scopedSource(2);
        final var before = new ImagePlusSourceImage(source).snapshot();
        assertEquals(2, ReviewAlignmentCommand.registrationChannelDefault(source));
        final CommandModule module = initializedCommand(Map.of("sourceImage", source));
        assertEquals(1, module.getInput("registrationChannel"));
        assertEquals(1, module.getInput("registrationSlice"));
        assertEquals(1, module.getInput("registrationFrame"));
        assertEquals(before, new ImagePlusSourceImage(source).snapshot());
        assertEquals(2, source.getC());
        assertEquals(1, source.getZ());
        assertEquals(2, source.getT());
    }

    private static CommandModule initializedCommand(final Map<String, Object> inputs) throws Exception {
        final CommandModule module = new CommandModule(new CommandInfo(ReviewAlignmentCommand.class));
        inputs.forEach((name, value) -> { module.setInput(name, value); module.resolveInput(name); });
        final InitPreprocessor initializer = new InitPreprocessor();
        initializer.process(module);
        assertFalse(initializer.isCanceled(), initializer.getCancelReason());
        return module;
    }

    private static ImagePlus scopedSource(final int activeChannel) {
        final ImageStack stack = new ImageStack(2, 2);
        for (int index = 0; index < 8; index++) {
            stack.addSlice("source-plane-" + index,
                    new ByteProcessor(2, 2, new byte[] {(byte) index, (byte) (index + 20), (byte) (index + 40), (byte) (index + 60)}, null));
        }
        final ImagePlus source = new ImagePlus("scoped-source", stack);
        source.setDimensions(2, 2, 2);
        source.setPosition(activeChannel, 1, 2);
        source.getCalibration().pixelWidth = .625;
        source.getCalibration().pixelHeight = .875;
        source.setDisplayRange(12, 192);
        return source;
    }

    @Test
    void injectedProviderOverridesFallbackWithoutInferringOrientation() {
        final DeepSliceInput input = new DeepSliceInput(
                2,
                2,
                new float[] {0, 1, 2, 3},
                BinaryMask.empty(2, 2));

        final var proposal = ReviewAlignmentCommand.estimateInitialPlane(
                new AllenCoronalLevel(264),
                input,
                Optional.of(ignored ->
                        new DeepSlicePlanePrediction(321, -1.25, 2.5)));

        assertEquals(InitialPlaneSource.LOCAL_DEEPSLICE, proposal.source());
        assertEquals(321, proposal.coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
        assertEquals(-1.25, proposal.prediction().orElseThrow()
                .sagittalTiltDegrees());
        assertEquals(2.5, proposal.prediction().orElseThrow()
                .horizontalTiltDegrees());
        assertTrue(proposal.fallbackReason().isEmpty());
    }

    @Test
    void logsExactVerifiedRuntimeFactsOnlyForRealWorkerPredictions() {
        final List<String> information = new ArrayList<>();
        final LogService log = informationLog(information);
        final DeepSliceOuv primary = new DeepSliceOuv(
                0, 100, 0, 3, 0, 0, 0, 0, -3);
        final DeepSliceOuv secondary = new DeepSliceOuv(
                0, 100, 0, 3, -1.5, 0, 0, -1.5, -3);
        final DeepSliceOuv ensemble = new DeepSliceOuv(
                0, 100, 0, 3, -0.75, 0, 0, -0.75, -3);
        final DeepSliceRuntimeProvenance provenance =
                new DeepSliceRuntimeProvenance(
                        "deepslice-1.2.8-py3.11.15-tf2.21.0-macos-arm64-r3",
                        Path.of("test fixtures", "verified-runtime").toAbsolutePath().normalize(),
                        2,
                        7_582_923,
                        "fda6fe1b8d3a3ecc32f4a44f5864d8e2"
                                + "eff99014f9915a7b9be44126686f03d9",
                        "3.11.15",
                        "1.2.8",
                        "2.21.0",
                        "ebrains-mouse-ensemble-2025-01-31",
                        "da34a5bca0245314a68daff30c144675"
                                + "8c0851b21370ef9797d5288018b08717",
                        "b85b7325158d117b2ac7559495c0b50d"
                                + "fcf3545aa29281a345f9ef1e33542e42",
                        "c5bf1c05b020c4177164039b854c3ef9"
                                + "2b16b73384734647c1f0ad5cf79f6975");
        final DeepSlicePlanePrediction realPrediction =
                DeepSlicePlanePrediction.fromWorkerVectors(
                        primary, secondary, ensemble, provenance);

        ReviewAlignmentCommand.logVerifiedRuntimeProvenance(
                new DeepSlicePlanePrediction(264, 0, 0), log);
        assertTrue(information.isEmpty());

        ReviewAlignmentCommand.logVerifiedRuntimeProvenance(
                realPrediction, log);

        assertEquals(List.of(
                "AtlasAlign verified DeepSlice runtime: releaseId="
                        + "deepslice-1.2.8-py3.11.15-tf2.21.0-macos-arm64-r3"
                        + "; canonicalRuntimePath=" + Path.of("test fixtures", "verified-runtime").toAbsolutePath().normalize()
                        + "; protocolVersion=2; manifestSizeBytes=7582923"
                        + "; manifestSha256="
                        + "fda6fe1b8d3a3ecc32f4a44f5864d8e2"
                        + "eff99014f9915a7b9be44126686f03d9"
                        + "; pythonVersion=3.11.15; deepSliceVersion=1.2.8"
                        + "; tensorflowVersion=2.21.0; modelRelease="
                        + "ebrains-mouse-ensemble-2025-01-31"
                        + "; primaryWeightSha256="
                        + "da34a5bca0245314a68daff30c144675"
                        + "8c0851b21370ef9797d5288018b08717"
                        + "; secondaryWeightSha256="
                        + "b85b7325158d117b2ac7559495c0b50d"
                        + "fcf3545aa29281a345f9ef1e33542e42"
                        + "; backboneWeightSha256="
                        + "c5bf1c05b020c4177164039b854c3ef9"
                        + "2b16b73384734647c1f0ad5cf79f6975"),
                information);
    }

    @Test
    void disabledProviderUsesExplicitFallback() {
        final DeepSliceInput input = new DeepSliceInput(
                1,
                1,
                new float[] {1},
                BinaryMask.empty(1, 1));

        final var proposal = ReviewAlignmentCommand.estimateInitialPlane(
                new AllenCoronalLevel(264),
                input,
                Optional.empty());

        assertEquals(InitialPlaneSource.MANUAL_FALLBACK, proposal.source());
        assertEquals(264, proposal.coronalLevel()
                .zeroBasedAnteriorPosteriorIndex());
        assertTrue(proposal.fallbackReason().isPresent());
        assertTrue(proposal.fallbackMessage().isPresent());
    }

    @Test
    void reviewPreparationLeavesSwingEventThread() throws Exception {
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<String> workerName = new AtomicReference<>();
        final AtomicReference<Boolean> ranOnEdt = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() ->
                ReviewAlignmentCommand.runAwayFromSwingEventThread(
                        () -> {
                            workerName.set(Thread.currentThread().getName());
                            ranOnEdt.set(
                                    SwingUtilities.isEventDispatchThread());
                            finished.countDown();
                        },
                        ignored -> finished.countDown()));

        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertEquals(Boolean.FALSE, ranOnEdt.get());
        assertEquals("atlasalign-review-preparation", workerName.get());
        assertNotEquals(
                Thread.currentThread().getName(), workerName.get());
    }

    @Test
    void asynchronousPreparationFailureIsReportedOnSwingThread()
            throws Exception {
        final CountDownLatch reported = new CountDownLatch(1);
        final AtomicReference<RuntimeException> observed =
                new AtomicReference<>();
        final AtomicReference<Boolean> reportedOnEdt =
                new AtomicReference<>();

        SwingUtilities.invokeAndWait(() ->
                ReviewAlignmentCommand.runAwayFromSwingEventThread(
                        () -> {
                            throw new IllegalStateException(
                                    "atlas cache could not be opened");
                        },
                        error -> {
                            observed.set(error);
                            reportedOnEdt.set(
                                    SwingUtilities.isEventDispatchThread());
                            reported.countDown();
                        }));

        assertTrue(reported.await(5, TimeUnit.SECONDS));
        assertEquals(
                "atlas cache could not be opened",
                observed.get().getMessage());
        assertEquals(Boolean.TRUE, reportedOnEdt.get());
    }

    @Test
    void atlasMaskUsesCopiedAnnotationWithoutChangingPlane() {
        final int[] template = {0, 10, 20, 30};
        final int[] annotation = {0, 1, 2, 0};
        final AtlasCoronalPlane plane =
                new AtlasCoronalPlane(
                        4, 2, 2, template, annotation);

        final var mask =
                ReviewAlignmentCommand.atlasTissueMask(plane);

        assertEquals(2, mask.foregroundCount());
        assertTrue(mask.contains(1, 0));
        assertTrue(mask.contains(0, 1));
        assertEquals(1, plane.annotationId(1, 0));
        assertEquals(20, plane.templateIntensity(0, 1));
    }

    @Test
    void halfGeometrySelectsMatchingImageSideWithoutSynthesis() {
        final int[] template = new int[8];
        final int[] annotation = {
            1, 1, 1, 1,
            1, 1, 1, 1
        };
        final AtlasCoronalPlane plane =
                new AtlasCoronalPlane(
                        4, 4, 2, template, annotation);

        final var left = ReviewAlignmentCommand.atlasTissueMask(
                plane, SectionGeometry.IMAGE_LEFT_HALF);
        final var right = ReviewAlignmentCommand.atlasTissueMask(
                plane, SectionGeometry.IMAGE_RIGHT_HALF);

        assertEquals(4, left.foregroundCount());
        assertEquals(4, right.foregroundCount());
        assertTrue(left.contains(0, 0));
        assertTrue(!left.contains(3, 0));
        assertTrue(!right.contains(0, 0));
        assertTrue(right.contains(3, 0));
    }

    @Test
    void rejectsEmptyOrSeverelyClippedAutomaticObliquePlanes() {
        final AtlasCoronalPlane coronal = plane(
                new int[]{1, 1, 1, 1});
        final AtlasCoronalPlane retainedHalf = plane(
                new int[]{1, 1, 0, 0});
        final AtlasCoronalPlane clipped = plane(
                new int[]{1, 0, 0, 0});
        final AtlasCoronalPlane empty = plane(
                new int[]{0, 0, 0, 0});

        assertTrue(ReviewAlignmentCommand.automaticObliquePlaneUsable(
                retainedHalf, coronal));
        assertTrue(!ReviewAlignmentCommand.automaticObliquePlaneUsable(
                clipped, coronal));
        assertTrue(!ReviewAlignmentCommand.automaticObliquePlaneUsable(
                empty, coronal));
    }

    @Test
    void clippedPredictedPlaneLoadsAndReturnsTheCoronalFallbackState() {
        final AtlasCoronalPlane coronal = plane(
                new int[]{1, 1, 1, 1});
        final AtlasCoronalPlane clipped = plane(
                new int[]{1, 0, 0, 0});
        final DeepSlicePlanePrediction prediction =
                new DeepSlicePlanePrediction(240, 2.5, -1.5);
        final InitialPlaneProposal proposal = new InitialPlaneProposal(
                new AllenCoronalLevel(240),
                InitialPlaneSource.LOCAL_DEEPSLICE,
                Optional.of(prediction), Optional.empty(), Optional.empty());
        final AutomaticAlignmentEligibility eligible =
                new AutomaticAlignmentEligibility(
                        AutomaticEligibilityStatus.ELIGIBLE_FULL,
                        List.of("verified test input"));
        final List<AtlasPlaneRequest> requests = new ArrayList<>();
        final AtlasPlaneSource source = new AtlasPlaneSource() {
            @Override
            public AtlasCoronalPlane load(final int level) {
                return coronal;
            }

            @Override
            public AtlasCoronalPlane load(final AtlasPlaneRequest request) {
                requests.add(request);
                return AtlasPlaneTilt.CORONAL.equals(request.tilt())
                        ? coronal : clipped;
            }
        };

        final var selection =
                ReviewAlignmentCommand.selectAutomaticAtlasPlane(
                        source, proposal, eligible);

        assertEquals(2, requests.size());
        assertEquals(new AtlasPlaneTilt(2.5, -1.5),
                requests.get(0).tilt());
        assertEquals(AtlasPlaneTilt.CORONAL,
                requests.get(1).tilt());
        assertTrue(selection.plane() == coronal);
        assertEquals(AutomaticEligibilityStatus.REVIEW_ONLY,
                selection.eligibility().status());
        assertEquals(AtlasPlaneTilt.CORONAL,
                selection.initialization().appliedTilt());
        assertTrue(!selection.initialization().predictionApplied());
        assertTrue(selection.eligibility().reasons().stream().anyMatch(
                reason -> reason.contains("retained less than half")));
    }

    private static AtlasCoronalPlane plane(final int[] annotation) {
        return new AtlasCoronalPlane(
                240, 2, 2, new int[4], annotation);
    }

    private static LogService informationLog(final List<String> information) {
        return (LogService) Proxy.newProxyInstance(
                ReviewAlignmentCommandTest.class.getClassLoader(),
                new Class<?>[]{LogService.class},
                (ignored, method, arguments) -> {
                    if (method.getName().equals("info")
                            && arguments != null
                            && arguments.length > 0) {
                        information.add(String.valueOf(arguments[0]));
                    }
                    return null;
                });
    }
}
