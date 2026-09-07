package org.atlasalign.plugin.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.io.IOException;
import java.util.Properties;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/** Plugin chrome only: never used by scientific exporters. */
public final class PluginBranding {
    public static final String CREDIT = "Made by Meghamsh Teja Konda";
    public static final String PROJECT_URL = "https://github.com/meghamsh738/atlasalign";
    private PluginBranding() { }

    public static JLabel creditLabel() {
        final JLabel label = new JLabel(CREDIT, SwingConstants.CENTER);
        label.setName("atlasalignAuthorCredit");
        label.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
        label.setFont(label.getFont().deriveFont(11f));
        return label;
    }

    public static JPanel withCredit(final Component content) {
        final JPanel panel = new JPanel(new BorderLayout());
        panel.add(content, BorderLayout.CENTER);
        panel.add(creditLabel(), BorderLayout.SOUTH);
        return panel;
    }

    public static String version() {
        final Properties properties = new Properties();
        try (var stream = PluginBranding.class.getResourceAsStream(
                "/META-INF/maven/org.atlasalign/atlasalign-plugin/pom.properties")) {
            if (stream != null) properties.load(stream);
        } catch (IOException ignored) { /* Development classpath has no artifact metadata. */ }
        return properties.getProperty("version", "development");
    }

    public static String aboutText() {
        return "AtlasAlign Lite " + version() + "\n" + CREDIT + "\n\n"
                + PROJECT_URL + "\n\n"
                + "Project code: MIT License. Existing contributor notices are retained.\n"
                + "Built with Fiji, ImageJ2, SciJava, and Bio-Formats.\n"
                + "Allen Mouse Brain CCFv3: Wang et al., Cell (2020).\n"
                + "DeepSlice is optional and has separate software/model terms.\n\n"
                + "Atlas and model assets have their own terms and citations.\n"
                + "Beta research software: alignment and anatomical ROIs require reviewer verification.";
    }
}
