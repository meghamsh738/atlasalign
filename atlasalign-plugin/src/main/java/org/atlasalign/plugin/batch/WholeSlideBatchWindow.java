package org.atlasalign.plugin.batch;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import javax.swing.JFrame;
import javax.swing.WindowConstants;
import org.scijava.command.CommandService;

/** Top-level non-modal review queue window. */
public final class WholeSlideBatchWindow {

    private WholeSlideBatchWindow() {
    }

    public static JFrame open(
            final BatchProjectSession project,
            final BatchLaunchSettings settings,
            final CommandService commands) {
        final JFrame frame = new JFrame(
                "AtlasAlign Lite — batch / whole-slide review");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setContentPane(new WholeSlideBatchPanel(
                project, settings, commands));
        frame.pack();
        final var usable = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        frame.setMinimumSize(new Dimension(Math.min(720, usable.width),
                Math.min(480, usable.height)));
        frame.setSize(Math.min(960, usable.width), Math.min(680, usable.height));
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        return frame;
    }
}
