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
import org.atlasalign.atlas.AtlasRepository;
import org.atlasalign.atlas.VerifiedAtlas;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.plugin.review.VerifiedAtlasPlaneSource;
import org.atlasalign.plugin.validation.GuidedAtlasIdentity;
import org.atlasalign.plugin.validation.GuidedPriorCapturePanel;
import org.atlasalign.plugin.validation.GuidedPriorEvidenceWriter;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/** Validation-only, output-blinded AP-prior capture command. */
@Plugin(
        type = Command.class,
        menuPath = "Plugins>AtlasAlign Lite>Validation>Capture Guided AP Prior",
        description = "Capture a locked broad AP range before automatic output is visible")
public final class CaptureGuidedApPriorCommand implements Command {

    @Parameter(visibility = org.scijava.ItemVisibility.MESSAGE, persist = false)
    private String authorCredit = PluginBranding.CREDIT;

    private static final Path DEFAULT_OUTPUT =
            RuntimeSettings.defaultRoot().resolve("validation/phase5-guided-dg-v1/provider-inputs");

    @Parameter(label = "Source image")
    private ImagePlus sourceImage;

    @Parameter(label = "Pseudonymous prior provider ID", persist = false)
    private String providerId = "provider-a";

    @Parameter(label = "Verified Allen atlas cache", style = "directory", persist = false)
    private File atlasCacheDirectory = ReviewAlignmentCommand.DEFAULT_ATLAS_CACHE.toFile();

    @Parameter(label = "Create-only provider-input directory", style = "directory", persist = false)
    private File outputDirectory = DEFAULT_OUTPUT.toFile();

    @Parameter
    private LogService log;

    @Override
    public void run() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException(
                    "Guided prior capture requires a graphical Fiji session");
        }
        final ImagePlusSourceImage readOnly = new ImagePlusSourceImage(sourceImage);
        final SourceImageSnapshot before = readOnly.snapshot();
        final VerifiedAtlas atlas = new AtlasRepository().openAllenMouse25um(
                atlasCacheDirectory.toPath().toAbsolutePath().normalize());
        final String atlasIdentity = GuidedAtlasIdentity.sha256(atlas);
        SwingUtilities.invokeLater(() -> {
            final JFrame frame = new JFrame(
                    "AtlasAlign validation — lock AP prior before auto-align");
            final GuidedPriorCapturePanel panel = new GuidedPriorCapturePanel(
                    new VerifiedAtlasPlaneSource(atlas),
                    providerId,
                    before.pixelSha256(),
                    atlasIdentity,
                    captured -> {
                        final SourceImageSnapshot after = readOnly.snapshot();
                        if (!before.equals(after)) {
                            throw new IllegalStateException(
                                    "Source image changed during guided-prior capture");
                        }
                        final String timestamp = Instant.now().toString()
                                .replace(':', '-');
                        final Path output = outputDirectory.toPath()
                                .toAbsolutePath().normalize()
                                .resolve(providerId + "-"
                                        + before.pixelSha256().substring(0, 12)
                                        + "-" + timestamp + ".json");
                        GuidedPriorEvidenceWriter.writeCreateOnly(
                                output,
                                captured.prior(),
                                captured.entrySeconds(),
                                captured.preResultRevisionCount());
                        log.info("AtlasAlign created locked pre-output G01 prior: "
                                + output);
                    });
            frame.setLayout(new BorderLayout());
            frame.add(panel, BorderLayout.CENTER);
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.pack();
            frame.setSize(Math.min(1180, frame.getWidth()),
                    Math.min(760, frame.getHeight()));
            frame.setLocationByPlatform(true);
            frame.setVisible(true);
        });
    }
}
