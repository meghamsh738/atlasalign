package org.atlasalign.plugin.review;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import org.atlasalign.application.DisplaySettings;
import org.atlasalign.core.SourceImageMetadata;

/** Explicit view C/Z/T and display-only per-channel color/contrast controls. */
public final class ChannelDisplayPanel extends JPanel implements AutoCloseable {
    private final ReviewController controller;
    private final ReviewCanvas canvas;
    private final ChannelPreviewRenderer renderer;
    private final Consumer<Boolean> blockGeometry;
    private final SourceImageMetadata metadata;
    private final JComboBox<String> channel = new JComboBox<>();
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"Single channel", "Composite"});
    private final JSpinner slice;
    private final JSpinner time;
    private final JLabel scope = new JLabel();
    private final JLabel status = new JLabel(" ");
    private DisplaySettings requested;
    private java.util.Map<Integer, PreviewDisplayWindow> automaticWindows = java.util.Map.of();
    private boolean updating;

    public ChannelDisplayPanel(final ReviewController controller, final ReviewCanvas canvas,
            final ChannelPreviewRenderer renderer, final Consumer<Boolean> blockGeometry) {
        super(new BorderLayout(6, 1));
        this.controller = controller; this.canvas = canvas; this.renderer = renderer; this.blockGeometry = blockGeometry;
        metadata = controller.state().basis().sourceSnapshot().metadata();
        for (int i = 0; i < metadata.channels(); i++) channel.addItem("C" + (i + 1) + " · " + metadata.channelLabels().get(i).replace('\n', ' '));
        channel.setPreferredSize(new Dimension(180, 26)); channel.setName("displayChannel");
        mode.setName("displayMode");
        slice = new JSpinner(new SpinnerNumberModel(1, 1, metadata.slices(), 1)); slice.setName("displayOpticalZ");
        time = new JSpinner(new SpinnerNumberModel(1, 1, metadata.frames(), 1)); time.setName("displayTime");
        final var controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2)) {
            @Override public Dimension getPreferredSize() {
                final int available = ChannelDisplayPanel.this.getWidth() > 0 ? ChannelDisplayPanel.this.getWidth() - 14 : 1100;
                int rowWidth = 0, rowHeight = 0, height = 4;
                for (var child : getComponents()) {
                    if (!child.isVisible()) continue;
                    final var size = child.getPreferredSize();
                    if (rowWidth > 0 && rowWidth + 5 + size.width > available) { height += rowHeight + 4; rowWidth = 0; rowHeight = 0; }
                    rowWidth += (rowWidth == 0 ? 0 : 5) + size.width; rowHeight = Math.max(rowHeight, size.height);
                }
                return new Dimension(Math.max(1, available), height + rowHeight);
            }
        };
        controls.add(new JLabel("View")); controls.add(channel); controls.add(mode);
        controls.add(new JLabel("Optical Z")); controls.add(slice); controls.add(new JLabel("T")); controls.add(time);
        final JButton contrast = new JButton("Colors / contrast…"); contrast.setName("displayColorsContrast");
        contrast.addActionListener(event -> showChannelSettings()); controls.add(contrast);
        final JButton pinned = new JButton("Return to review plane"); pinned.setName("returnToReviewPlane");
        pinned.addActionListener(event -> {
            final var current = controller.displaySettings(); final var input = controller.registrationInput();
            controller.setDisplaySettings(new DisplaySettings(current.mode(), current.selectedChannel(), input.slice(), input.frame(), current.channels()));
        });
        controls.add(pinned);
        final JPanel labels = new JPanel(new BorderLayout(10, 0)); labels.add(scope, BorderLayout.WEST); labels.add(status, BorderLayout.CENTER);
        add(controls, BorderLayout.NORTH); add(labels, BorderLayout.SOUTH);
        setBorder(BorderFactory.createEmptyBorder(3, 7, 4, 7));
        channel.addActionListener(event -> changed()); mode.addActionListener(event -> changed());
        slice.addChangeListener(event -> changed()); time.addChangeListener(event -> changed());
        controller.addProjectChangeListener(this::refresh);
        refresh();
    }

    private void changed() {
        if (updating) return;
        try {
            slice.commitEdit(); time.commitEdit();
            final var previous = controller.displaySettings();
            controller.setDisplaySettings(new DisplaySettings(mode.getSelectedIndex() == 0 ? DisplaySettings.Mode.SINGLE : DisplaySettings.Mode.COMPOSITE,
                    channel.getSelectedIndex() + 1, (Integer) slice.getValue(), (Integer) time.getValue(), previous.channels()));
        } catch (java.text.ParseException | IllegalArgumentException error) { status.setText(error.getMessage()); }
    }

    public void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::refresh); return; }
        final DisplaySettings settings = controller.displaySettings();
        if (settings.equals(requested)) return;
        requested = settings; automaticWindows = java.util.Map.of(); updating = true;
        try {
            channel.setSelectedIndex(settings.selectedChannel() - 1); mode.setSelectedIndex(settings.mode() == DisplaySettings.Mode.SINGLE ? 0 : 1);
            slice.setValue(settings.slice()); time.setValue(settings.frame());
        } finally { updating = false; }
        final var input = controller.registrationInput();
        scope.setText("Registration fixed: C" + input.channel() + " / Z" + input.slice() + " / T" + input.frame());
        final boolean inspection = settings.inspectionMode(input);
        status.setText(inspection ? "Inspection only · geometry hidden · return to the review plane to edit or export" : "Loading view…");
        blockGeometry.accept(true);
        canvas.requestChannelDisplay("Loading view: " + viewLabel(settings), inspection);
        renderer.request(settings, frame -> {
            automaticWindows = frame.automaticWindows();
            canvas.setChannelDisplay(frame.image(), viewLabel(frame.settings()) + (inspection ? " · inspection only; overlays hidden" : " · shared source coordinates"));
            status.setText(inspection ? "Inspection only · geometry hidden · return to the review plane to edit or export" : "Display colors and contrast do not change source pixels");
            blockGeometry.accept(inspection);
        }, error -> {
            canvas.setChannelDisplay(null, "View unavailable: " + error);
            status.setText("View unavailable · " + error); blockGeometry.accept(true);
        });
    }

    private static String viewLabel(final DisplaySettings settings) {
        return (settings.mode() == DisplaySettings.Mode.SINGLE ? "C" + settings.selectedChannel() : "Composite")
                + " / Optical Z " + settings.slice() + " / T " + settings.frame();
    }

    private void showChannelSettings() {
        final var current = controller.displaySettings();
        final JPanel fields = new JPanel(new GridLayout(0, 6, 5, 4));
        for (String label : new String[]{"Channel", "Visible", "Color", "Auto", "Minimum", "Maximum"}) fields.add(new JLabel(label));
        final List<JCheckBox> visible = new ArrayList<>(); final List<JCheckBox> automatic = new ArrayList<>();
        final List<JComboBox<DisplaySettings.Lut>> luts = new ArrayList<>();
        final List<JSpinner> minimum = new ArrayList<>(); final List<JSpinner> maximum = new ArrayList<>();
        for (final var channelSettings : current.channels()) {
            fields.add(new JLabel("C" + channelSettings.index()));
            final var shown = new JCheckBox("", channelSettings.visible()); visible.add(shown); fields.add(shown);
            final var color = new JComboBox<>(DisplaySettings.Lut.values()); color.setSelectedItem(channelSettings.lut()); luts.add(color); fields.add(color);
            final var auto = new JCheckBox("", channelSettings.automaticContrast()); automatic.add(auto); fields.add(auto);
            final var window = automaticWindows.get(channelSettings.index());
            final double lowValue = channelSettings.automaticContrast() && window != null ? window.lower() : channelSettings.minimum();
            final double highValue = channelSettings.automaticContrast() && window != null ? Math.max((double) window.lower() + window.range(), Math.nextUp((double) window.lower())) : channelSettings.maximum();
            final var low = new JSpinner(new SpinnerNumberModel(lowValue, -Double.MAX_VALUE, Double.MAX_VALUE, 1.0));
            final var high = new JSpinner(new SpinnerNumberModel(highValue, -Double.MAX_VALUE, Double.MAX_VALUE, 1.0));
            low.setEnabled(!auto.isSelected()); high.setEnabled(!auto.isSelected());
            auto.addActionListener(event -> { low.setEnabled(!auto.isSelected()); high.setEnabled(!auto.isSelected()); });
            minimum.add(low); maximum.add(high); fields.add(low); fields.add(high);
        }
        final JPanel body = new JPanel(new BorderLayout(0, 8));
        body.add(new JLabel("Display only. Auto uses each copied plane's 0.5–99.5% intensity window. Visible applies to Composite."), BorderLayout.NORTH);
        final JScrollPane scroll = new JScrollPane(fields); scroll.setPreferredSize(new Dimension(670, Math.min(340, 55 + 34 * current.channels().size())));
        body.add(scroll, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, body, "Channel colors and contrast", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        try {
            final List<DisplaySettings.Channel> channels = new ArrayList<>();
            for (int index = 0; index < current.channels().size(); index++) {
                minimum.get(index).commitEdit(); maximum.get(index).commitEdit();
                channels.add(new DisplaySettings.Channel(index + 1, visible.get(index).isSelected(),
                        (DisplaySettings.Lut) luts.get(index).getSelectedItem(), ((Number) minimum.get(index).getValue()).doubleValue(),
                        ((Number) maximum.get(index).getValue()).doubleValue(), automatic.get(index).isSelected()));
            }
            controller.setDisplaySettings(new DisplaySettings(current.mode(), current.selectedChannel(), current.slice(), current.frame(), channels));
        } catch (java.text.ParseException | IllegalArgumentException error) {
            JOptionPane.showMessageDialog(this, error.getMessage(), "Check contrast limits", JOptionPane.WARNING_MESSAGE);
        }
    }
    @Override public void close() { renderer.close(); }
}
