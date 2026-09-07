package org.atlasalign.plugin.review;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.ArrayList;
import javax.swing.*;
import org.atlasalign.atlas.AtlasRegion;
import org.junit.jupiter.api.Test;

class AtlasGuideBrowserTest {
    @Test void typingFiltersWithAncestorsWithoutSelectingAnatomy() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var selected = new ArrayList<String>();
            var panel = new AtlasGuideBrowser(List.of(
                    new AtlasRegion(1,"root","Brain",null,3,List.of(2)),
                    new AtlasRegion(2,"HPF","Hippocampal formation",1,3,List.of(3)),
                    new AtlasRegion(3,"DG","Dentate gyrus",2,3,List.of())),selected::add);
            JTextField search = find(panel,JTextField.class);
            JTree tree = find(panel,JTree.class);
            search.setText("dentate");
            assertEquals(3,tree.getRowCount());
            assertTrue(selected.isEmpty());
            panel.setEnabled(false);
            assertFalse(search.isEnabled());
            assertFalse(tree.isEnabled());
            tree.setSelectionRow(2);
            assertTrue(selected.isEmpty());
            panel.setEnabled(true);
            tree.clearSelection();
            tree.setSelectionRow(2);
            assertEquals(List.of("DG"),selected);
            search.setText("no match");
            assertEquals(0,tree.getRowCount());
            assertEquals(List.of("DG"),selected);
            search.setText("");
            assertFalse(find(panel,JScrollPane.class).isVisible());
        });
    }
    private static <T> T find(Container root,Class<T> type) {
        for(Component c:root.getComponents()) {
            if(type.isInstance(c)) return type.cast(c);
            if(c instanceof Container child) {
                T found=find(child,type); if(found!=null)return found;
            }
        }
        return null;
    }
}
