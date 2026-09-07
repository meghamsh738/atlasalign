package org.atlasalign.plugin.review;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import org.atlasalign.atlas.AtlasRegion;

/** Local Allen ontology browser: typing filters nodes but never changes the guide. */
final class AtlasGuideBrowser extends JPanel {
    private final JTextField query = new JTextField() {
        @Override public void scrollRectToVisible(final java.awt.Rectangle rectangle) {
            if (isFocusOwner()) super.scrollRectToVisible(rectangle);
        }
    };
    private final JTree tree = new JTree();
    private final JScrollPane results = new JScrollPane(tree);
    private final JLabel hint = new JLabel("Type a name or acronym, or browse the hierarchy");
    private final JToggleButton browse = new JToggleButton("Browse");
    private final List<AtlasRegion> regions;
    private final Map<Integer, AtlasRegion> byId = new HashMap<>();
    private boolean rebuilding;

    AtlasGuideBrowser(final List<AtlasRegion> regions, final Consumer<String> select) {
        super(new BorderLayout(3, 3));
        this.regions = List.copyOf(regions);
        regions.forEach(region -> byId.put(region.id(), region));
        browse.setToolTipText("Browse the Allen structure hierarchy");
        query.setName("atlasGuideLiveSearch");
        query.setToolTipText("Search all Allen structures by name or acronym; choose a result to display its guide");
        tree.setName("atlasGuideHierarchy");
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public java.awt.Component getTreeCellRendererComponent(
                    JTree value, Object node, boolean selected, boolean expanded,
                    boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(value, node, selected, expanded, leaf, row, focus);
                Object item = ((DefaultMutableTreeNode) node).getUserObject();
                if (item instanceof AtlasRegion region) {
                    setText(region.acronym() + " — " + region.name());
                    setToolTipText(region.name());
                }
                return this;
            }
        });
        tree.addTreeSelectionListener(event -> {
            if (!isEnabled() || rebuilding || tree.getLastSelectedPathComponent() == null) return;
            Object item = ((DefaultMutableTreeNode) tree.getLastSelectedPathComponent()).getUserObject();
            if (item instanceof AtlasRegion region) select.accept(region.acronym());
        });
        final JPanel searchRow = new JPanel(new BorderLayout(3, 0));
        searchRow.add(query, BorderLayout.CENTER);
        searchRow.add(browse, BorderLayout.EAST);
        add(searchRow, BorderLayout.NORTH);
        results.setPreferredSize(new Dimension(280, 170));
        add(results, BorderLayout.CENTER);
        add(hint, BorderLayout.SOUTH);
        query.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { refresh(); }
            public void removeUpdate(DocumentEvent e) { refresh(); }
            public void changedUpdate(DocumentEvent e) { refresh(); }
        });
        browse.addActionListener(event -> refresh());
        refresh();
    }

    @Override public void setEnabled(final boolean enabled) {
        super.setEnabled(enabled);
        if (query != null) query.setEnabled(enabled);
        if (tree != null) tree.setEnabled(enabled);
        if (browse != null) browse.setEnabled(enabled);
    }

    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    private void refresh() {
        final String needle = query.getText().trim().toLowerCase(Locale.ROOT);
        final Set<Integer> visible = new HashSet<>();
        int matches = 0;
        for (AtlasRegion region : regions) {
            if (needle.isEmpty() || region.acronym().toLowerCase(Locale.ROOT).contains(needle)
                    || region.name().toLowerCase(Locale.ROOT).contains(needle)) {
                matches++;
                AtlasRegion current = region;
                while (current != null && visible.add(current.id())) {
                    current = current.parentId() == null ? null : byId.get(current.parentId());
                }
            }
        }
        final var root = new DefaultMutableTreeNode("Allen structures");
        final Map<Integer, DefaultMutableTreeNode> nodes = new HashMap<>();
        for (AtlasRegion region : regions) {
            if (visible.contains(region.id())) nodes.put(region.id(), new DefaultMutableTreeNode(region));
        }
        for (AtlasRegion region : regions) {
            final var node = nodes.get(region.id());
            if (node == null) continue;
            final var parent = nodes.get(region.parentId());
            if (parent == null) root.add(node); else parent.add(node);
        }
        rebuilding = true;
        try {
            tree.setModel(new DefaultTreeModel(root));
            if (!needle.isEmpty()) {
                for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
            }
        } finally { rebuilding = false; }
        results.setVisible(!needle.isEmpty() || browse.isSelected());
        hint.setText(needle.isEmpty() ? "Search by name or acronym" : matches + " matches — choose a structure");
        revalidate();
    }
}
