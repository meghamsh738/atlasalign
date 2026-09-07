package org.atlasalign.plugin;

import org.atlasalign.plugin.ui.PluginBranding;
import ij.ImagePlus;
import ij.process.FloatProcessor;
import java.util.Locale;
import java.util.function.Consumer;
import org.atlasalign.application.RegistrationPreview;
import org.atlasalign.application.SafeImageIntakeService;
import org.atlasalign.application.SafePreviewResult;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.scijava.ItemIO;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(
        type = Command.class,
        menuPath = "Plugins>AtlasAlign Lite>Prepare Safe Preview",
        description = "Create a bounded registration preview without changing source pixels or metadata")
public final class PrepareSafePreviewCommand implements Command {

    @Parameter(visibility = org.scijava.ItemVisibility.MESSAGE, persist = false)
    private String authorCredit = PluginBranding.CREDIT;

    @Parameter(label = "Source image")
    private ImagePlus sourceImage;

    @Parameter(
            label = "Registration channel (1-based)",
            description = "Choose the DAPI, autofluorescence, or structural channel",
            min = "1")
    private int registrationChannel = 1;

    @Parameter(
            label = "Maximum preview dimension (pixels)",
            description = "The source is never upsampled or modified",
            min = "64")
    private int maximumPreviewDimension = 2_048;

    @Parameter
    private LogService log;

    @Parameter(type = ItemIO.OUTPUT)
    private ImagePlus registrationPreview;

    @Override
    public void run() {
        registrationPreview = preparePreview(
                sourceImage,
                registrationChannel,
                maximumPreviewDimension,
                message -> log.info(message));
    }

    static ImagePlus preparePreview(
            final ImagePlus sourceImage,
            final int registrationChannel,
            final int maximumPreviewDimension,
            final Consumer<String> auditLog) {
        final SafePreviewResult result = new SafeImageIntakeService().preparePreview(
                new ImagePlusSourceImage(sourceImage),
                registrationChannel,
                maximumPreviewDimension);
        final RegistrationPreview preview = result.preview();
        final FloatProcessor processor = new FloatProcessor(
                preview.mapping().previewWidth(),
                preview.mapping().previewHeight(),
                preview.pixels());
        final ImagePlus registrationPreview = new ImagePlus(
                sourceImage.getTitle() + " — AtlasAlign preview (channel "
                        + registrationChannel + ")",
                processor);
        registrationPreview.setProperty(
                "AtlasAlign.previewToSource",
                String.format(
                        Locale.ROOT,
                        "pixel-center mapping: x=(x+0.5)/%.17g-0.5; y=(y+0.5)/%.17g-0.5",
                        preview.mapping().scaleX(),
                        preview.mapping().scaleY()));
        auditLog.accept("AtlasAlign verified unchanged source SHA-256: "
                + result.verifiedSource().pixelSha256());
        return registrationPreview;
    }
}
