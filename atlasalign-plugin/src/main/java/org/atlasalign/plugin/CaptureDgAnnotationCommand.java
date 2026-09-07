package org.atlasalign.plugin;

import org.atlasalign.plugin.ui.PluginBranding;
import org.atlasalign.plugin.setup.RuntimeSettings;
import ij.ImagePlus;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SafeImageIntakeService;
import org.atlasalign.application.SafePreviewResult;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.plugin.validation.DgAnnotationCapturePanel;
import org.atlasalign.plugin.validation.DgAnnotationEvidenceWriter;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/** Validation-only, source-only DG annotation capture command. */
@Plugin(
        type = Command.class,
        menuPath = "Plugins>AtlasAlign Lite>Validation>Capture DG Evidence",
        description = "Capture locked DG evidence without atlas, model, score, or truth display")
public final class CaptureDgAnnotationCommand implements Command {

    @Parameter(visibility = org.scijava.ItemVisibility.MESSAGE, persist = false)
    private String authorCredit = PluginBranding.CREDIT;

    private static final Path DEFAULT_OUTPUT =
            RuntimeSettings.defaultRoot().resolve("validation/phase5-guided-dg-v1/provider-inputs/dg");

    @Parameter(label = "Source image")
    private ImagePlus sourceImage;

    @Parameter(label = "Pseudonymous DG provider ID", persist = false)
    private String providerId = "provider-a";

    @Parameter(label = "Structural channel (1-based)", min = "1")
    private int registrationChannel = 1;

    @Parameter(label = "Maximum annotation-preview dimension", min = "64")
    private int maximumPreviewDimension = 2_048;

    @Parameter(
            label = "Create-only DG provider-input directory",
            style = "directory",
            persist = false)
    private File outputDirectory = DEFAULT_OUTPUT.toFile();

    @Parameter
    private LogService log;

    @Override
    public void run() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException(
                    "DG annotation capture requires a graphical Fiji session");
        }
        final ImagePlusSourceImage readOnly = new ImagePlusSourceImage(sourceImage);
        final SourceImageSnapshot before = readOnly.snapshot();
        final SafePreviewResult safe = new SafeImageIntakeService().preparePreview(
                readOnly, registrationChannel, maximumPreviewDimension);
        if (!before.equals(safe.verifiedSource())) {
            throw new IllegalStateException(
                    "Source image changed while preparing DG annotation preview");
        }
        final RegistrationPreview preview = safe.preview();
        final String mappingId = "pixel-center-ch" + preview.channel()
                + "-z" + preview.sourceSlice()
                + "-t" + preview.sourceFrame()
                + "-src" + preview.mapping().sourceWidth() + "x"
                + preview.mapping().sourceHeight()
                + "-preview" + preview.mapping().previewWidth() + "x"
                + preview.mapping().previewHeight();
        SwingUtilities.invokeLater(() -> openCaptureWindow(
                readOnly, before, preview, mappingId));
    }

    private void openCaptureWindow(
            final ImagePlusSourceImage readOnly,
            final SourceImageSnapshot before,
            final RegistrationPreview preview,
            final String mappingId) {
        final JFrame frame = new JFrame(
                "AtlasAlign validation — source-only DG evidence");
        final DgAnnotationCapturePanel panel = new DgAnnotationCapturePanel(
                preview,
                providerId,
                before.pixelSha256(),
                mappingId,
                captured -> {
                    final SourceImageSnapshot after = readOnly.snapshot();
                    if (!before.equals(after)) {
                        throw new IllegalStateException(
                                "Source image changed during DG annotation capture");
                    }
                    final String timestamp = Instant.now().toString()
                            .replace(':', '-');
                    final Path output = outputDirectory.toPath()
                            .toAbsolutePath().normalize()
                            .resolve(providerId + "-"
                                    + before.pixelSha256().substring(0, 12)
                                    + "-"
                                    + captured.annotation().anatomicalSide().name()
                                    + "-" + timestamp + ".json");
                    DgAnnotationEvidenceWriter.writeCreateOnly(
                            output,
                            captured.annotation(),
                            captured.entrySeconds(),
                            captured.preResultRevisionCount());
                    log.info("AtlasAlign created locked pre-output DG evidence: "
                            + output);
                });
        frame.setLayout(new BorderLayout());
        frame.add(panel, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.pack();
        frame.setSize(Math.min(1_240, frame.getWidth()),
                Math.min(780, frame.getHeight()));
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }
}
