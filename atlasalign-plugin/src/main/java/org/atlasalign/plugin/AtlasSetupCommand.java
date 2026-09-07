package org.atlasalign.plugin;

import javax.swing.SwingUtilities;
import org.atlasalign.plugin.setup.AtlasSetupDialog;
import org.atlasalign.plugin.setup.RuntimeSettings;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class,
        menuPath = "Plugins>AtlasAlign Lite>Atlas Setup",
        description = "Choose a verified local Allen atlas or download the pinned atlas")
public final class AtlasSetupCommand implements Command {
    @Override
    public void run() {
        SwingUtilities.invokeLater(() -> AtlasSetupDialog.choose(null,
                new RuntimeSettings().paths().atlasCache()));
    }
}
