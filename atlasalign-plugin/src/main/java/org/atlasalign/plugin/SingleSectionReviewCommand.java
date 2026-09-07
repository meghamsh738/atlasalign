package org.atlasalign.plugin;

import org.atlasalign.plugin.ui.PluginBranding;
import org.atlasalign.plugin.setup.RuntimeSettings;
import ij.ImagePlus;
import ij.WindowManager;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;
import org.scijava.command.Command;
import org.scijava.command.CommandService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

/** Explicit intake avoids exposing internal command inputs in legacy Fiji dialogs. */
@Plugin(type = Command.class,
        menuPath = "Plugins>AtlasAlign Lite>Review Atlas Alignment",
        description = "Review one open section with the Allen atlas")
public final class SingleSectionReviewCommand implements Command {
    @Parameter
    private CommandService commands;

    @Override
    public void run() {
        SwingUtilities.invokeLater(() -> {
            final int[] ids = WindowManager.getIDList();
            if (ids == null || ids.length == 0) {
                JOptionPane.showMessageDialog(null,
                        "Open a section image in Fiji, then choose Review Atlas Alignment.");
                return;
            }
            final IntakePanel intake = new IntakePanel(ids);
            if (JOptionPane.showConfirmDialog(null, intake, "Review one section",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                    == JOptionPane.OK_OPTION) {
                final Map<String, Object> inputs;
                try {
                    inputs = intake.inputs();
                } catch (IllegalArgumentException invalid) {
                    JOptionPane.showMessageDialog(null, invalid.getMessage(),
                            "Check section settings", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (inputs.get("sourceImage") == null) {
                    JOptionPane.showMessageDialog(null, "The selected image was closed. Please reopen it.");
                    return;
                }
                // Explicit inputs are already resolved; keep the existing internal command API.
                commands.run(ReviewAlignmentCommand.class, true, inputs);
            }
        });
    }

    static final class IntakePanel extends JPanel {
        private final int[] ids;
        private final JComboBox<String> source = new JComboBox<>();
        private final JSpinner channel = new JSpinner(new SpinnerNumberModel(1, 1, 1, 1));
        private final JSpinner preview = new JSpinner(new SpinnerNumberModel(2048, 64, Integer.MAX_VALUE, 256));
        private final JSpinner level = new JSpinner(new SpinnerNumberModel(264, 0, 527, 1));
        private final JCheckBox deepSlice = new JCheckBox("Suggest an initial level with local DeepSlice", false);
        private final JTextField atlas = new JTextField(new RuntimeSettings().paths().atlasCache().toString(), 32);
        private final JTextField runtime = new JTextField(new RuntimeSettings().paths().deepSliceRuntime().toString(), 32);
        private final JTextField work = new JTextField(new RuntimeSettings().paths().deepSliceWork().toString(), 32);

        IntakePanel(final int[] imageIds) {
            super(new BorderLayout(0, 10));
            ids = imageIds.clone();
            final JPanel fields = new JPanel(new GridLayout(0, 1, 0, 5));
            fields.add(new JLabel("<html>Use one open tissue section. The source image stays unchanged.<br>"
                    + "For multiple sections: Plugins → AtlasAlign Lite → Batch / Whole-Slide Review.</html>"));
            int selected = 0;
            for (int i = 0; i < ids.length; i++) {
                final ImagePlus image = WindowManager.getImage(ids[i]);
                source.addItem(image == null ? "Closed image" : image.getTitle() + " [" + ids[i] + "]");
                if (image == WindowManager.getCurrentImage()) selected = i;
            }
            source.setSelectedIndex(selected);
            fields.add(row("Section image", source));
            fields.add(row("Tissue channel", channel));
            fields.add(new JLabel("Channel used to locate tissue (1 = first channel)."));
            fields.add(row("Preview size (pixels)", preview));
            fields.add(new JLabel("Longest preview edge; smaller previews prepare faster."));
            fields.add(row("Initial atlas level (0–527)", level));
            fields.add(new JLabel("Starting level when DeepSlice is off or unavailable; not a bregma coordinate."));
            fields.add(deepSlice);
            fields.add(new JLabel("Optional local model; its suggestion still needs your review."));
            add(fields, BorderLayout.NORTH);
            final JPanel advanced = new JPanel(new GridLayout(0, 1, 0, 5));
            advanced.add(directory("Allen atlas cache", atlas));
            advanced.add(directory("Local DeepSlice runtime", runtime));
            advanced.add(directory("DeepSlice temporary work", work));
            advanced.setVisible(false);
            final JCheckBox setup = new JCheckBox("Advanced: optional directory setup");
            setup.addActionListener(event -> {
                advanced.setVisible(setup.isSelected());
                final java.awt.Window window = SwingUtilities.getWindowAncestor(this);
                if (window != null) window.pack();
            });
            final JPanel bottom = new JPanel(new BorderLayout(0, 5));
            bottom.add(setup, BorderLayout.NORTH);
            bottom.add(advanced, BorderLayout.CENTER);
            bottom.add(PluginBranding.creditLabel(), BorderLayout.SOUTH);
            add(bottom, BorderLayout.SOUTH);
            source.addActionListener(event -> updateChannel());
            updateChannel();
        }

        private void updateChannel() {
            final ImagePlus image = selectedImage();
            channel.setModel(new SpinnerNumberModel(
                    ReviewAlignmentCommand.registrationChannelDefault(image), 1,
                    image == null ? 1 : Math.max(1, image.getNChannels()), 1));
        }

        private ImagePlus selectedImage() {
            return WindowManager.getImage(ids[source.getSelectedIndex()]);
        }

        Map<String, Object> inputs() {
            try {
                channel.commitEdit();
                preview.commitEdit();
                level.commitEdit();
            } catch (java.text.ParseException invalid) {
                throw new IllegalArgumentException("Enter valid whole numbers for channel, preview size and atlas level.", invalid);
            }
            final Map<String, Object> inputs = new LinkedHashMap<>();
            inputs.put("sourceImage", selectedImage());
            inputs.put("registrationChannel", channel.getValue());
            inputs.put("maximumPreviewDimension", preview.getValue());
            inputs.put("initialCoronalLevel", level.getValue());
            inputs.put("useLocalDeepSlice", deepSlice.isSelected());
            inputs.put("atlasCacheDirectory", new File(atlas.getText().trim()));
            inputs.put("deepSliceRuntimeDirectory", new File(runtime.getText().trim()));
            inputs.put("deepSliceWorkDirectory", new File(work.getText().trim()));
            inputs.put("reviewSectionId", "Section 1");
            inputs.put("parentSourceName", "");
            inputs.put("parentSourcePixelSha256", "");
            inputs.put("parentSourceWidth", 0);
            inputs.put("parentSourceHeight", 0);
            inputs.put("sectionSourceOffsetX", 0);
            inputs.put("sectionSourceOffsetY", 0);
            inputs.put("manualRoiDraftPath", "");
            return inputs;
        }

        private static JPanel row(final String label, final JComponent control) {
            final JPanel row = new JPanel(new BorderLayout(8, 0));
            row.add(new JLabel(label), BorderLayout.WEST);
            row.add(control, BorderLayout.CENTER);
            return row;
        }

        private JPanel directory(final String label, final JTextField text) {
            final JPanel row = row(label, text);
            final JButton browse = new JButton("Browse…");
            browse.addActionListener(event -> {
                final JFileChooser chooser = new JFileChooser(text.getText());
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    text.setText(chooser.getSelectedFile().getAbsolutePath());
                }
            });
            row.add(browse, BorderLayout.EAST);
            return row;
        }
    }
}
