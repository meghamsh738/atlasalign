package org.atlasalign.plugin.review;

import java.util.ArrayList;
import java.util.List;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.core.SourceImageMetadata;

/** Explicit source channels, independent of single-channel/composite visibility. */
final class ExportSelectionPanel extends JPanel {
    private final List<JCheckBox> channels = new ArrayList<>();
    private final int slice;
    private final int frame;

    ExportSelectionPanel(final SourceImageMetadata metadata, final ExportSelection selection) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setName("exportSelectionPanel");
        slice = selection.slice();
        frame = selection.frame();
        add(new JLabel("Export channels · pinned Z " + slice + " · T " + frame));
        for (int c = 1; c <= metadata.channels(); c++) {
            final JCheckBox choice = new JCheckBox(c + " · " + metadata.channelLabels().get(c - 1),
                    selection.channels().contains(c));
            choice.setName("exportChannel" + c);
            channels.add(choice);
            add(choice);
        }
        add(new JLabel("Output: selected C, Z1, T1 · original pixel values"));
    }

    ExportSelection selection() {
        final List<Integer> selected = new ArrayList<>();
        for (int index = 0; index < channels.size(); index++) {
            if (channels.get(index).isSelected()) selected.add(index + 1);
        }
        return new ExportSelection(selected, slice, frame);
    }
}
