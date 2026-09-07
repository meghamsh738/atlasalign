package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.scijava.Context;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.PluginService;

class PrepareSafePreviewCommandTest {

    private Context context;

    @AfterEach
    void disposeContext() {
        if (context != null) {
            context.dispose();
        }
    }

    @Test
    void isDiscoverableAsSciJavaCommandInExpectedMenu() {
        final Plugin annotation = PrepareSafePreviewCommand.class.getAnnotation(Plugin.class);

        assertNotNull(annotation);
        assertEquals(Command.class, annotation.type());
        assertEquals(
                "Plugins>AtlasAlign Lite>Prepare Safe Preview",
                annotation.menuPath());
    }

    @Test
    void generatedSciJavaIndexDiscoversCommand() {
        context = new Context(PluginService.class);
        final PluginService plugins = context.service(PluginService.class);

        assertNotNull(plugins.getPlugin(
                PrepareSafePreviewCommand.class,
                Command.class));
    }

    @Test
    void commandLogicReturnsSeparatePreviewWithoutStartingAUiContext() {
        final ImagePlus source = new ImagePlus(
                "source",
                new ByteProcessor(128, 64, new byte[128 * 64]));

        final ImagePlus preview = PrepareSafePreviewCommand.preparePreview(
                source, 1, 64, ignored -> {
                });

        assertNotNull(preview);
        assertEquals(64, preview.getWidth());
        assertEquals(32, preview.getHeight());
        org.junit.jupiter.api.Assertions.assertTrue(
                preview.getProperty("AtlasAlign.previewToSource")
                        .toString()
                        .contains("/0.50000000000000000"));
        assertEquals(128, source.getWidth());
        assertEquals(64, source.getHeight());
        assertEquals(8, source.getBitDepth());
    }
}
