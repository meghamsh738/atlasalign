package org.atlasalign.plugin;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import org.atlasalign.plugin.ui.PluginBranding;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, menuPath = "Plugins>AtlasAlign Lite>About AtlasAlign Lite",
        description = "Version, authorship, license, and scientific acknowledgements")
public final class AboutAtlasAlignCommand implements Command {
    @Override public void run() {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                PluginBranding.aboutText(), "About AtlasAlign Lite", JOptionPane.INFORMATION_MESSAGE));
    }
}
