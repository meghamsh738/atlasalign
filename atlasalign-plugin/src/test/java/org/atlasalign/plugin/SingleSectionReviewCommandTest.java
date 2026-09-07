package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.service.Service;

class SingleSectionReviewCommandTest {
    @Test
    void menuEntryExposesOnlyInjectedServicesToTheLegacyHarvester() {
        assertEquals("", ReviewAlignmentCommand.class.getAnnotation(Plugin.class).menuPath());
        for (var field : SingleSectionReviewCommand.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(Parameter.class)) {
                assertTrue(Service.class.isAssignableFrom(field.getType()), field.getName());
            }
        }
    }

    @Test
    void intakeHasNoEditableBatchMetadataAndSuppliesResolvedDefaults() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var panel = new SingleSectionReviewCommand.IntakePanel(new int[] {-999});
            var inputs = panel.inputs();
            assertEquals("Section 1", inputs.get("reviewSectionId"));
            assertEquals(false, inputs.get("useLocalDeepSlice"));
            assertEquals("", inputs.get("manualRoiDraftPath"));
            assertEquals(0, inputs.get("sectionSourceOffsetX"));
            assertEquals(0, inputs.get("sectionSourceOffsetY"));
            // All programmatic inputs are supplied, so preprocessing cannot show a second form.
            for (var field : ReviewAlignmentCommand.class.getDeclaredFields()) {
                if (field.isAnnotationPresent(Parameter.class)
                        && !Service.class.isAssignableFrom(field.getType())) {
                    assertTrue(inputs.containsKey(field.getName()), field.getName());
                }
            }
            var labels = new ArrayList<String>();
            collectLabels(panel, labels);
            assertTrue(labels.stream().anyMatch(text -> text.contains("Batch / Whole-Slide Review")));
            assertFalse(labels.stream().anyMatch(text -> text.contains("parentSource") || text.contains("reviewSectionId")));
        });
    }

    private static void collectLabels(Container parent, List<String> labels) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JLabel label) labels.add(label.getText());
            if (child instanceof Container container) collectLabels(container, labels);
        }
    }
}
