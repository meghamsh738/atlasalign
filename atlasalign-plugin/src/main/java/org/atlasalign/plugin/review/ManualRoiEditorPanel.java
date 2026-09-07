package org.atlasalign.plugin.review;

import ij.gui.Roi;
import ij.gui.ShapeRoi;
import ij.plugin.frame.RoiManager;
import ij.process.FloatPolygon;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import org.atlasalign.application.roi.ReviewerRoi;
import org.atlasalign.application.roi.ReviewerRoiGuideLink;
import org.atlasalign.application.roi.ReviewerRoiPart;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.application.roi.ReviewerRoiSide;
import org.atlasalign.application.roi.ReviewerRoiVertex;
import org.atlasalign.application.roi.ReviewerRoiVertexReducer;
import org.atlasalign.application.roi.RoiPartOperation;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.plugin.export.ManualRoiExportService;

/**
 * Fiji-style exact manual ROI editor. All geometry is stored in immutable
 * level-0 source coordinates and is deliberately independent of atlas-plane
 * or warp revisions.
 */
final class ManualRoiEditorPanel extends JPanel {

    private static final PointCountChoice[] POINT_COUNT_CHOICES = {
        new PointCountChoice("Auto", 0),
        new PointCountChoice("4", 4),
        new PointCountChoice("6", 6),
        new PointCountChoice("8", 8),
        new PointCountChoice("12", 12),
        new PointCountChoice("16", 16),
        new PointCountChoice("24", 24),
        new PointCountChoice("32", 32),
        new PointCountChoice("48", 48),
        new PointCountChoice("64", 64)
    };

    private final ReviewerRoiSession session;
    private final PreviewMapping mapping;
    private final ReviewCanvas canvas;
    private final Optional<ManualRoiExportService> exportService;
    private final String sourceName;
    private final Supplier<ReviewerRoiSide> defaultSide;
    private final DefaultListModel<ReviewerRoi> roiModel =
            new DefaultListModel<>();
    private final JList<ReviewerRoi> roiList = new JList<>(roiModel);
    private final JTextField roiName = new JTextField("ROI 1", 14) {
        @Override public void scrollRectToVisible(final java.awt.Rectangle rectangle) {
            if (isFocusOwner()) super.scrollRectToVisible(rectangle);
        }
    };
    private final JComboBox<ReviewerRoiSide> roiSide =
            new JComboBox<>(ReviewerRoiSide.values());
    private final JComboBox<PointCountChoice> guideVertices =
            new JComboBox<>(POINT_COUNT_CHOICES);
    private final JComboBox<PointCountChoice> selectedVertices =
            new JComboBox<>(POINT_COUNT_CHOICES);
    private final JLabel selectedVertexCount = new JLabel(
            "Select an ROI to edit its points");
    private final JButton applyVertexCount = new JButton("Apply count");
    private final JCheckBox visible = new JCheckBox("Visible", true);
    private final JCheckBox selectedForExport = new JCheckBox(
            "Include in export", true);
    private final JCheckBox preview = new JCheckBox("Preview export mask");
    private final JCheckBox includeUnion = new JCheckBox(
            "Export combined mask", true);
    private final JTextArea selectedGuide = text(
            "Choose one atlas guide above, or draw without one");
    private final JTextArea roiReviewStatus = text("");
    private final JTextArea status = text("Draw one or more named ROIs");
    private final JButton convertGuide = new JButton("Create from guide");
    private final JButton finish = new JButton("Finish outline");
    private final JButton cancel = new JButton("Cancel drawing");
    private final JButton undo = new JButton("ROI Undo");
    private final JButton redo = new JButton("ROI Redo");
    private final JButton export = new JButton("Export selected ROIs…");
    private ReviewViewModel model;
    private boolean updating;
    private boolean selectingList;
    private boolean active;

    ManualRoiEditorPanel(
            final ReviewerRoiSession session,
            final PreviewMapping mapping,
            final ReviewCanvas canvas,
            final Optional<ManualRoiExportService> exportService,
            final String sourceName,
            final Supplier<ReviewerRoiSide> defaultSide) {
        super(new BorderLayout(0, 6));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        this.session = Objects.requireNonNull(session, "session");
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.canvas = Objects.requireNonNull(canvas, "canvas");
        this.exportService = Objects.requireNonNull(
                exportService, "exportService");
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.defaultSide = Objects.requireNonNull(defaultSide, "defaultSide");
        if (mapping.sourceWidth() != session.snapshot().sourceWidth()
                || mapping.sourceHeight()
                != session.snapshot().sourceHeight()) {
            throw new IllegalArgumentException(
                    "Manual ROI session and preview mapping must match");
        }
        buildUi();
        wireCanvas();
        session.addListener(this::sessionChanged);
        refresh();
    }

    ReviewerRoiSession session() {
        return session;
    }

    private Runnable refreshCompactReview = () -> {};

    void refreshExportReadiness() {
        export.setEnabled(exportService.isPresent()
                && !session.snapshot().exportableRois().isEmpty());
        refreshCompactReview.run();
    }

    JPanel compactReviewPanel(final Runnable returnToEditor,
            final Runnable openAdvancedAtlasExport) {
        final JTextArea explanation = text(
                "Check each named ROI against the tissue before export. "
                + "Finished means a closed outline, not reviewed anatomy. "
                + "Excluded holes stay outside the exported mask.");
        explanation.setRows(4);
        final JTextArea summary = text("");
        summary.setName("manualRoiExportSummary");
        summary.setRows(3);
        final JButton edit = new JButton("Draw ROIs");
        edit.addActionListener(event -> returnToEditor.run());
        final JButton exportButton = new JButton("Export selected ROIs…");
        exportButton.setName("manualRoiReviewExport");
        final JButton advanced = new JButton("Advanced atlas-region export…");
        advanced.addActionListener(event -> openAdvancedAtlasExport.run());
        advanced.setEnabled(exportService.isPresent());
        final JPanel result = section("Review & exact ROI export",
                explanation, summary, edit, exportButton,
                new JSeparator(), advanced);
        exportButton.addActionListener(event -> exportSelected(result));
        final Runnable refreshSummary = () -> {
            final var snapshot = session.snapshot();
            final int count = snapshot.exportableRois().size();
            summary.setText(count == 0
                    ? "No finished ROIs selected. Use Draw ROIs to create or import anatomical outlines. Section markers only define the crop."
                    : count + " finished ROI(s) selected for exact export");
            edit.setText(snapshot.rois().isEmpty() ? "Draw ROIs" : "Edit drawn ROIs");
            exportButton.setEnabled(count > 0 && exportService.isPresent());
            advanced.setEnabled(exportService.isPresent());
        };
        refreshCompactReview = refreshSummary;
        session.addListener(refreshSummary);
        refreshSummary.run();
        return result;
    }

    void updateModel(final ReviewViewModel value) {
        model = Objects.requireNonNull(value, "value");
        selectedGuide.setText(value.selectedAtlasRegion()
                .map(region -> "Selected guide: " + region.displayName())
                .orElse("Select one atlas guide above to copy it, or draw a new polygon yourself."));
        convertGuide.setEnabled(value.selectedAtlasRegion().isPresent()
                && value.selectedAtlasContour().isPresent());
        refreshReviewStatus();
        canvas.setManualRoiState(session.snapshot(), mapping);
    }

    void setActive(final boolean value) {
        active = value;
        canvas.setManualRoiEditingEnabled(value);
        canvas.setManualRoiVisible(true);
        if (value) {
            canvas.requestFocusInWindow();
        }
    }

    void discardTransientDrawing() {
        session.cancelDraft();
        canvas.setManualRoiCaptureMode(false);
    }

    void exportSelected(final Component owner) {
        if (exportService.isEmpty()) {
            showError(owner, "Export unavailable",
                    "This test panel was opened without the original-source export service.");
            return;
        }
        final var snapshot = session.snapshot();
        if (snapshot.exportableRois().isEmpty()) {
            showError(owner, "Nothing selected",
                    "Finish and select at least one named ROI first.");
            return;
        }
        final JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Choose folder for exact manual ROI export");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        export.setEnabled(false);
        status.setText("Exporting untouched source pixels…");
        final Path folder = chooser.getSelectedFile().toPath();
        new SwingWorker<ManualRoiExportService.Result, String>() {
            @Override
            protected ManualRoiExportService.Result doInBackground() {
                return exportService.orElseThrow().export(folder, sourceName,
                        snapshot.sectionId(), snapshot.exportableRois(),
                        includeUnion.isSelected(), this::isCancelled,
                        (message, fraction) -> publish(message + " ("
                                + Math.round(fraction * 100) + "%)"));
            }

            @Override
            protected void process(final List<String> chunks) {
                status.setText(chunks.get(chunks.size() - 1));
            }

            @Override
            protected void done() {
                export.setEnabled(true);
                try {
                    final var result = get();
                    status.setText("Exported "
                            + snapshot.exportableRois().size()
                            + " ROI(s) to " + result.publishedDirectory());
                    JOptionPane.showMessageDialog(owner,
                            "Exact manual ROI export complete:\n"
                            + result.publishedDirectory(),
                            "AtlasAlign export complete",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (final Exception error) {
                    status.setText("Export failed — source and ROI draft unchanged");
                    showError(owner, "Manual ROI export failed",
                            rootMessage(error));
                }
            }
        }.execute();
    }

    private void buildUi() {
        final JPanel content = verticalPanel();
        content.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        final JTextArea intro = text(
                "Create an ROI from the guide, then drag its cyan points to fit the tissue.");
        intro.setRows(2);
        intro.setName("manualRoiIntro");
        intro.setFont(intro.getFont().deriveFont(Font.BOLD));
        content.add(intro);

        selectedGuide.setRows(2);
        selectedGuide.setName("manualRoiSelectedGuideStatus");
        guideVertices.setSelectedIndex(0);
        guideVertices.setName("manualRoiGuideVertices");
        convertGuide.setName("manualRoiConvertGuide");
        convertGuide.setEnabled(false);
        convertGuide.setToolTipText(
                "Copy the selected atlas contour once as freely editable source-coordinate polygon vertices");
        convertGuide.addActionListener(event -> convertGuide());
        final JPanel guideRow = new JPanel(new BorderLayout(4, 0));
        guideRow.add(new JLabel("Editable points"), BorderLayout.WEST);
        guideRow.add(guideVertices, BorderLayout.CENTER);
        final JTextArea guideExplanation = text(
                "Auto chooses a manageable number of editable points.");
        guideExplanation.setRows(2);
        content.add(section("Start from atlas guide",
                guideRow, convertGuide, guideExplanation));


        final JButton polygon = new JButton("Draw polygon");
        final JButton freehand = new JButton("Draw freehand");
        polygon.setName("manualRoiNewPolygon");
        freehand.setName("manualRoiNewFreehand");
        polygon.setToolTipText(
                "Click around the tissue to draw a new polygon export mask");
        freehand.setToolTipText(
                "Drag one continuous outline to draw a new export mask");
        polygon.addActionListener(event -> startNew(false));
        freehand.addActionListener(event -> startNew(true));

        final JButton addPiece = new JButton("Add separate piece");
        final JButton addHole = new JButton("Exclude area inside ROI");
        addPiece.setName("manualRoiAddPiece");
        addHole.setName("manualRoiAddHole");
        addPiece.setToolTipText(
                "Add another disconnected polygon to the selected named ROI");
        addHole.setToolTipText(
                "Draw around an area to leave out of the ROI, such as a tear or empty space. Source pixels are unchanged.");
        addPiece.addActionListener(event -> addPart(RoiPartOperation.ADD));
        addHole.addActionListener(event -> addPart(
                RoiPartOperation.SUBTRACT));
        finish.addActionListener(event -> runEdit("Polygon not finished",
                session::finishActivePart));
        finish.setName("manualRoiFinish");
        cancel.setName("manualRoiCancelUnfinished");
        cancel.addActionListener(event -> {
            session.cancelDraft();
            status.setText("Unfinished polygon discarded; finished ROIs retained");
        });
        final JPanel drawingTools = section("Draw or adjust pieces",
                polygon,
                freehand,
                addPiece,
                addHole,
                finish,
                cancel,
                text("Exclude area leaves a hole in the exported ROI, for example around a tear. It does not erase image pixels."));
        drawingTools.setVisible(false);
        final javax.swing.JToggleButton moreDrawing = new javax.swing.JToggleButton("More drawing tools…");
        moreDrawing.setName("manualRoiMoreDrawing");
        moreDrawing.addActionListener(event -> {
            drawingTools.setVisible(moreDrawing.isSelected());
            revalidate();
        });
        content.add(moreDrawing);
        drawingTools.add(selectedGuide);
        content.add(drawingTools);

        roiList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        roiList.setName("manualRoiList");
        roiList.setVisibleRowCount(4);
        roiList.setCellRenderer(new RoiRenderer());
        roiList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updating
                    && roiList.getSelectedValue() != null) {
                selectingList = true;
                try { session.select(roiList.getSelectedValue().id()); }
                finally { selectingList = false; }
            }
        });
        final JScrollPane listScroll = new JScrollPane(roiList);
        listScroll.setPreferredSize(new Dimension(280, 88));
        content.add(new JLabel("Your ROIs (Shift / ⌘ for multiple)"));
        roiList.setToolTipText("Shift-click or Command-click to select multiple ROIs for deletion");
        content.add(listScroll);
        roiReviewStatus.setName("manualRoiReviewStatus");
        roiReviewStatus.setRows(3);
        content.add(roiReviewStatus);

        selectedVertices.setSelectedIndex(0);
        selectedVertices.setName("manualRoiSelectedVertexCount");
        selectedVertices.addActionListener(event -> {
            if (!updating) {
                refreshPointControls();
            }
        });
        selectedVertexCount.setName("manualRoiVertexCountSummary");
        applyVertexCount.setName("manualRoiApplyVertexCount");
        applyVertexCount.setToolTipText(
                "Reduce the selected finished ROI as one undoable ROI edit");
        applyVertexCount.addActionListener(event -> applySelectedPointCount());
        final JPanel selectedPointRow = new JPanel(new BorderLayout(4, 0));
        selectedPointRow.add(selectedVertices, BorderLayout.CENTER);
        selectedPointRow.add(applyVertexCount, BorderLayout.EAST);
        final JTextArea pointHelp = text(
                "Apply reduces points. Shift-click edge: add. Right-click point: remove.");
        pointHelp.setRows(2);
        content.add(section("Selected ROI points",
                selectedVertexCount, selectedPointRow, pointHelp));

        roiSide.addActionListener(event -> {
            if (!updating && activeRoi().isPresent()) {
                runEdit("ROI side not changed", () -> session.setSide(
                        activeRoi().orElseThrow().id(),
                        (ReviewerRoiSide) roiSide.getSelectedItem()));
            }
        });
        visible.addActionListener(event -> {
            if (!updating && activeRoi().isPresent()) {
                session.setVisible(activeRoi().orElseThrow().id(),
                        visible.isSelected());
            }
        });
        selectedForExport.addActionListener(event -> {
            if (!updating && activeRoi().isPresent()) {
                session.setSelectedForExport(
                        activeRoi().orElseThrow().id(),
                        selectedForExport.isSelected());
            }
        });
        content.add(row(visible, selectedForExport));

        final JButton rename = new JButton("Rename");
        final JButton duplicate = new JButton("Duplicate");
        final JButton delete = new JButton("Delete selected");
        delete.setName("manualRoiDeleteSelected");
        rename.addActionListener(event -> activeRoi().ifPresent(roi ->
                runEdit("ROI not renamed", () -> session.rename(
                        roi.id(), roiName.getText()))));
        duplicate.addActionListener(event -> activeRoi().ifPresent(roi ->
                runEdit("ROI not duplicated", () ->
                        session.duplicate(roi.id()))));
        delete.addActionListener(event -> {
            final var ids = roiList.getSelectedValuesList().stream()
                    .map(ReviewerRoi::id).toList();
            runEdit("ROIs not deleted", () -> session.deleteAll(ids));
            status.setText("Deleted " + ids.size() + " ROI(s). ROI Undo restores them.");
        });
        final JPanel nameRow = new JPanel(new BorderLayout(4, 0));
        nameRow.add(roiName, BorderLayout.CENTER);
        nameRow.add(roiSide, BorderLayout.EAST);
        content.add(nameRow);
        content.add(row(rename, duplicate));
        content.add(delete);

        undo.addActionListener(event -> session.undo());
        redo.addActionListener(event -> session.redo());
        undo.setName("manualRoiUndo");
        redo.setName("manualRoiRedo");
        content.add(row(undo, redo));

        final JButton sendManager = new JButton("Send to ROI Manager");
        final JButton importManager = new JButton("Import Fiji ROI");
        sendManager.setName("manualRoiSendToManager");
        importManager.setName("manualRoiImportFromManager");
        sendManager.setToolTipText(
                "Send the selected outline to Fiji's ROI Manager");
        importManager.setToolTipText(
                "Copy the selected Fiji ROI Manager outline into this editor as a source-coordinate polygon");
        sendManager.addActionListener(event -> sendToRoiManager());
        importManager.addActionListener(event -> importFromRoiManager());
        drawingTools.add(sendManager);
        drawingTools.add(importManager);

        preview.addActionListener(event -> {
            canvas.setManualRoiPreviewVisible(preview.isSelected());
            status.setText(preview.isSelected()
                    ? "Preview: selected ROI bright; remaining tissue 20%"
                    : "Normal tissue display");
        });
        preview.setName("manualRoiPreview");
        content.add(preview);

        drawingTools.add(text("Editing: drag cyan vertices freely; Shift-click a "
                + "segment to insert a vertex; right-click a vertex to delete; "
                + "drag inside a finished ROI to move it. Multiple ADD pieces "
                + "and SUBTRACT holes form one named export mask."));
        export.addActionListener(event -> exportSelected(this));
        selectedForExport.setName("manualRoiIncludeInExport");
        includeUnion.setName("manualRoiIncludeUnion");
        export.setName("manualRoiExport");
        content.add(includeUnion);
        content.add(export);
        status.setRows(2);
        content.add(status);
        for (final Component child : content.getComponents()) {
            if (child instanceof JComponent component) component.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        add(content, BorderLayout.NORTH);
    }

    private void wireCanvas() {
        canvas.setManualRoiState(session.snapshot(), mapping);
        canvas.setManualRoiListener(new ManualRoiCanvasLayer.Listener() {
            @Override
            public void select(final String roiId) {
                runEdit("ROI not selected", () -> session.select(roiId));
            }

            @Override
            public void addVertex(final Point2D sourcePoint) {
                runEdit("ROI vertex not added", () ->
                        session.addVertex(sourcePoint));
            }

            @Override
            public void addFreehandVertices(
                    final List<Point2D> sourcePoints) {
                runEdit("Freehand ROI not added", () ->
                        session.addVertices(sourcePoints));
            }

            @Override
            public void moveVertex(final String roiId, final String partId,
                    final String vertexId, final Point2D sourcePoint) {
                runEdit("ROI vertex not moved", () -> session.moveVertex(
                        roiId, partId, vertexId, sourcePoint));
            }

            @Override
            public void insertAfter(final String roiId, final String partId,
                    final String vertexId, final Point2D sourcePoint) {
                runEdit("ROI vertex not inserted", () ->
                        session.insertAfter(roiId, partId, vertexId,
                                sourcePoint));
            }

            @Override
            public void deleteVertex(final String roiId, final String partId,
                    final String vertexId) {
                runEdit("ROI vertex not deleted", () ->
                        session.deleteVertex(roiId, partId, vertexId));
            }

            @Override
            public void translate(final String roiId, final double sourceDx,
                    final double sourceDy) {
                runEdit("ROI not moved", () -> session.translate(
                        roiId, sourceDx, sourceDy));
            }
        });
    }

    private void sessionChanged() {
        if (SwingUtilities.isEventDispatchThread()) {
            refresh();
        } else {
            SwingUtilities.invokeLater(this::refresh);
        }
    }

    private void refresh() {
        final var snapshot = session.snapshot();
        updating = true;
        try {
            final var selectedIds = roiList.getSelectedValuesList().stream()
                    .map(ReviewerRoi::id).toList();
            roiModel.clear();
            snapshot.rois().forEach(roiModel::addElement);
            snapshot.activeRoi().ifPresentOrElse(roi -> {
                if (selectingList || selectedIds.size() > 1 && selectedIds.contains(roi.id())) {
                    for (int i = 0; i < roiModel.size(); i++) {
                        if (selectedIds.contains(roiModel.get(i).id())) roiList.addSelectionInterval(i, i);
                    }
                } else {
                    roiList.setSelectedValue(roi, false);
                }
                setNameWithoutScrolling(roi.name());
                roiSide.setSelectedItem(roi.side());
                visible.setSelected(roi.visible());
                selectedForExport.setSelected(roi.selectedForExport());
            }, () -> roiList.clearSelection());
            finish.setEnabled(snapshot.activePart().isPresent());
            cancel.setEnabled(snapshot.activePart().isPresent());
            undo.setEnabled(session.canUndo());
            redo.setEnabled(session.canRedo());
            export.setEnabled(exportService.isPresent()
                    && !snapshot.exportableRois().isEmpty());
        } finally {
            updating = false;
        }
        refreshPointControls();
        refreshReviewStatus();
        canvas.setManualRoiState(snapshot, mapping);
        canvas.setManualRoiVisible(true);
        canvas.repaint();
    }

    private void setNameWithoutScrolling(final String name) {
        if (roiName.getText().equals(name)) return;
        final var caret = (javax.swing.text.DefaultCaret) roiName.getCaret();
        final int policy = caret.getUpdatePolicy();
        caret.setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        try { roiName.setText(name); }
        finally { caret.setUpdatePolicy(policy); }
    }

    private void refreshReviewStatus() {
        final var active = session.snapshot().activeRoi();
        roiReviewStatus.setText(active.map(roi ->
                (roi.finished() ? "Closed outline — check anatomy before export." : "Unfinished — finish the outline before export.")
                + (roi.guideLink().filter(guide -> model != null
                    && (guide.coronalLevel() != model.coronalLevel()
                    || guide.alignmentRevision() != model.reviewState().contentRevision())).isPresent()
                    ? " Guide changed; your ROI stayed fixed." : ""))
                .orElse("Select an ROI to edit. Shift / ⌘-click to select several."));
        roiReviewStatus.setToolTipText(active.map(roi -> reviewStatus(roi, model)).orElse(""));
    }

    private void refreshPointControls() {
        final ReviewerRoi roi = session.snapshot().activeRoi().orElse(null);
        if (roi == null) {
            selectedVertexCount.setText("Select an ROI to edit its points");
            applyVertexCount.setEnabled(false);
            return;
        }
        final int current = ReviewerRoiVertexReducer.vertexCount(roi);
        selectedVertexCount.setText(current + " points · "
                + roi.parts().size() + " part" + (roi.parts().size() == 1 ? "" : "s"));
        final PointCountChoice choice = (PointCountChoice)
                selectedVertices.getSelectedItem();
        // Auto is calculated only after the explicit Apply click so routine
        // selection and drag refreshes never run dense contour analysis.
        final int target = choice.automatic()
                ? 3 * roi.parts().size() : choice.points();
        applyVertexCount.setEnabled(roi.finished()
                && target >= 3 * roi.parts().size()
                && target < current);
    }

    private void applySelectedPointCount() {
        final ReviewerRoi roi = activeRoi().orElse(null);
        if (roi == null) {
            return;
        }
        runEdit("ROI points not reduced", () -> {
            final PointCountChoice choice = (PointCountChoice)
                    selectedVertices.getSelectedItem();
            final int before = ReviewerRoiVertexReducer.vertexCount(roi);
            final int target = choice.resolve(roi,
                    mapping.sourceWidth(), mapping.sourceHeight());
            session.reduceVertices(roi.id(), target);
            final int after = ReviewerRoiVertexReducer.vertexCount(
                    session.snapshot().activeRoi().orElseThrow());
            status.setText(after < before
                    ? "Reduced '" + roi.name() + "' from " + before
                            + " to " + after
                            + " editable points. ROI Undo restores the exact prior polygon."
                    : "'" + roi.name() + "' already has " + before
                            + " points; add more only where needed with Shift-click.");
        });
    }

    static String reviewStatus(final ReviewerRoi roi, final ReviewViewModel model) {
        final String geometry = roi.finished() ? "Finished outline" : "Unfinished outline";
        final String exportState = roi.finished() && roi.selectedForExport()
                ? "Selected for exact polygon export" : "Not exportable or not selected";
        final String provenance = roi.guideLink().map(guide -> {
            final boolean changed = model != null
                    && (guide.coronalLevel() != model.coronalLevel()
                    || guide.alignmentRevision() != model.reviewState().contentRevision()
                    || !guide.atlasIdentityHash().equals(model.reviewState().basis()
                            .atlas().identitySha256()));
            return "Copied guide: " + guide.acronym() + " at AP index " + guide.coronalLevel()
                    + (changed ? ". Alignment changed since copy; polygon preserved."
                            : ". Independent source-coordinate polygon.");
        }).orElse("Drawn/imported polygon; no atlas guide link.");
        return roi.name() + " — " + geometry + ". " + exportState + ".\n"
                + "Anatomical review is not recorded automatically; check against tissue.\n"
                + provenance;
    }

    private void startNew(final boolean freehand) {
        runEdit("ROI not started", () -> {
            session.newPolygon(roiName.getText(), defaultSide.get(),
                    RoiPartOperation.ADD);
            canvas.setManualRoiCaptureMode(freehand);
            status.setText(freehand
                    ? "Drag one continuous freehand boundary, then Finish outline"
                    : "Click polygon vertices, then Finish outline");
            if (active) {
                canvas.requestFocusInWindow();
            }
        });
    }

    private void addPart(final RoiPartOperation operation) {
        runEdit("Polygon part not started", () -> {
            session.addPart(operation);
            canvas.setManualRoiCaptureMode(false);
            status.setText(operation == RoiPartOperation.ADD
                    ? "Draw another piece belonging to this named ROI"
                    : "Draw the hole to exclude from this named ROI");
        });
    }

    private void convertGuide() {
        if (model == null || model.selectedAtlasContour().isEmpty()
                || model.selectedAtlasRegion().isEmpty()) {
            showError(this, "Guide unavailable",
                    "Choose an anatomy guide that is present on this AP plane first.");
            return;
        }
        runEdit("Guide not converted", () -> {
            final SelectedAtlasContour contour = model
                    .selectedAtlasContour().orElseThrow();
            final SelectedAtlasRegion region = model
                    .selectedAtlasRegion().orElseThrow();
            final ReviewerRoiSide side = defaultSide.get();
            final List<List<Point2D>> atlasLoops = loopsForSide(
                    contour, side);
            if (atlasLoops.isEmpty()) {
                throw new IllegalArgumentException(
                        "The selected guide has no contour on the active side at this plane");
            }
            final List<List<Point2D>> sourceLoops = atlasLoops.stream()
                    .map(loop -> loop.stream()
                            .map(model.reviewState()::mapAtlasToPreview)
                            .map(mapping::previewToSource)
                            .map(this::clampSource)
                            .toList()).toList();
            final PointCountChoice choice = (PointCountChoice)
                    guideVertices.getSelectedItem();
            final int target = choice.resolve(sourceLoops,
                    mapping.sourceWidth(), mapping.sourceHeight());
            final List<List<Point2D>> sampled = resampleLoops(
                    sourceLoops, target);
            final int count = sampled.stream().mapToInt(List::size).sum();
            final ReviewerRoiGuideLink guide = new ReviewerRoiGuideLink(
                    region.rootRegionId(), region.acronym(), region.name(),
                    true, model.reviewState().basis().atlas()
                            .identitySha256(),
                    model.coronalLevel(),
                    model.reviewState().contentRevision());
            session.createFromGuide(region.acronym() + " "
                            + side.displayName(), side, guide, sampled);
            status.setText("Created " + region.acronym() + " with " + count
                    + " points" + (choice.automatic() ? " (Auto)" : "")
                    + ". Drag cyan points to refine.");
        });
    }

    private static List<List<Point2D>> loopsForSide(
            final SelectedAtlasContour contour,
            final ReviewerRoiSide side) {
        if (side == ReviewerRoiSide.BILATERAL
                || side == ReviewerRoiSide.UNKNOWN) {
            return contour.orderedExteriorLoops();
        }
        final double middle = (contour.width() - 1.0) * 0.5;
        return contour.orderedExteriorLoops().stream().filter(loop -> {
            final double centre = loop.stream().mapToDouble(Point2D::x)
                    .average().orElse(middle);
            return side == ReviewerRoiSide.LEFT
                    ? centre < middle : centre > middle;
        }).toList();
    }

    static List<List<Point2D>> resampleLoops(
            final List<List<Point2D>> loops,
            final int totalPoints) {
        final List<List<Point2D>> eligible = loops.stream()
                .filter(loop -> loop.size() >= 3)
                .sorted(Comparator.comparingDouble(
                        ManualRoiEditorPanel::perimeter).reversed())
                .toList();
        if (eligible.isEmpty() || totalPoints < 3 * eligible.size()) {
            throw new IllegalArgumentException(
                    "Not enough guide vertices for all contour components");
        }
        final int current = eligible.stream().mapToInt(List::size).sum();
        if (totalPoints <= current) {
            return ReviewerRoiVertexReducer.reduceLoops(
                    eligible, totalPoints);
        }
        final int[] counts = new int[eligible.size()];
        java.util.Arrays.fill(counts, 3);
        final double totalLength = eligible.stream()
                .mapToDouble(ManualRoiEditorPanel::perimeter).sum();
        for (int assigned = 3 * eligible.size(); assigned < totalPoints;
                assigned++) {
            int best = 0;
            double bestDeficit = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < eligible.size(); index++) {
                final double ideal = totalPoints
                        * perimeter(eligible.get(index)) / totalLength;
                final double deficit = ideal - counts[index];
                if (deficit > bestDeficit) {
                    best = index;
                    bestDeficit = deficit;
                }
            }
            counts[best]++;
        }
        final List<List<Point2D>> result = new ArrayList<>();
        for (int index = 0; index < eligible.size(); index++) {
            result.add(resample(eligible.get(index), counts[index]));
        }
        return List.copyOf(result);
    }

    private static List<Point2D> resample(
            final List<Point2D> loop,
            final int count) {
        final double length = perimeter(loop);
        final List<Point2D> result = new ArrayList<>();
        int edge = 0;
        double traversed = 0;
        double edgeLength = distance(loop.get(0), loop.get(1 % loop.size()));
        for (int sample = 0; sample < count; sample++) {
            final double target = sample * length / count;
            while (traversed + edgeLength < target
                    && edge + 1 < loop.size()) {
                traversed += edgeLength;
                edge++;
                edgeLength = distance(loop.get(edge),
                        loop.get((edge + 1) % loop.size()));
            }
            final Point2D start = loop.get(edge);
            final Point2D end = loop.get((edge + 1) % loop.size());
            final double fraction = edgeLength <= 1e-12 ? 0
                    : (target - traversed) / edgeLength;
            result.add(new Point2D(start.x()
                    + fraction * (end.x() - start.x()), start.y()
                    + fraction * (end.y() - start.y())));
        }
        return List.copyOf(result);
    }

    private static double perimeter(final List<Point2D> loop) {
        double length = 0;
        for (int index = 0; index < loop.size(); index++) {
            length += distance(loop.get(index),
                    loop.get((index + 1) % loop.size()));
        }
        return length;
    }

    private static double distance(final Point2D first,
            final Point2D second) {
        return Math.hypot(second.x() - first.x(),
                second.y() - first.y());
    }

    private Point2D clampSource(final Point2D point) {
        return new Point2D(Math.max(0,
                Math.min(mapping.sourceWidth() - 1.0, point.x())),
                Math.max(0,
                        Math.min(mapping.sourceHeight() - 1.0, point.y())));
    }

    private void sendToRoiManager() {
        final ReviewerRoi roi = activeRoi().orElse(null);
        if (roi == null || !roi.finished()) {
            showError(this, "ROI Manager",
                    "Select a finished reviewer ROI first.");
            return;
        }
        final Area area = new Area();
        for (final ReviewerRoiPart part : roi.parts()) {
            final Area polygon = new Area(path(part));
            if (part.operation() == RoiPartOperation.ADD) {
                area.add(polygon);
            } else {
                area.subtract(polygon);
            }
        }
        final ShapeRoi shape = new ShapeRoi(area);
        shape.setName(roi.name());
        RoiManager manager = RoiManager.getInstance2();
        if (manager == null) {
            manager = new RoiManager();
        }
        manager.addRoi(shape);
        status.setText("Sent '" + roi.name()
                + "' to Fiji ROI Manager in source coordinates");
    }

    private void importFromRoiManager() {
        final RoiManager manager = RoiManager.getInstance2();
        if (manager == null) {
            showError(this, "ROI Manager",
                    "Open Fiji ROI Manager and select one or more polygon ROIs first.");
            return;
        }
        Roi[] selected = manager.getSelectedRoisAsArray();
        if (selected.length == 0) {
            selected = manager.getRoisAsArray();
        }
        if (selected.length == 0) {
            showError(this, "ROI Manager", "ROI Manager is empty.");
            return;
        }
        int imported = 0;
        for (final Roi roi : selected) {
            final FloatPolygon polygon = roi.getFloatPolygon();
            if (polygon == null || polygon.npoints < 3) {
                continue;
            }
            final List<ReviewerRoiVertex> vertices = new ArrayList<>();
            for (int index = 0; index < polygon.npoints; index++) {
                vertices.add(new ReviewerRoiVertex(
                        "import-vertex-" + index,
                        clampSource(new Point2D(polygon.xpoints[index],
                                polygon.ypoints[index]))));
            }
            final ReviewerRoiPart part = new ReviewerRoiPart(
                    "import-part", RoiPartOperation.ADD, vertices, true);
            final String name = roi.getName() == null
                    || roi.getName().isBlank()
                    ? "Imported ROI" : roi.getName();
            session.importCompleted(name, defaultSide.get(), List.of(part));
            imported++;
        }
        status.setText("Imported " + imported
                + " polygon ROI(s) from Fiji ROI Manager");
    }

    private static Path2D path(final ReviewerRoiPart part) {
        final Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (int index = 0; index < part.vertices().size(); index++) {
            final Point2D point = part.vertices().get(index).sourcePoint();
            if (index == 0) {
                path.moveTo(point.x(), point.y());
            } else {
                path.lineTo(point.x(), point.y());
            }
        }
        path.closePath();
        return path;
    }

    private Optional<ReviewerRoi> activeRoi() {
        return session.snapshot().activeRoi();
    }

    private void runEdit(final String title, final Runnable operation) {
        try {
            operation.run();
        } catch (final RuntimeException error) {
            showError(this, title, rootMessage(error));
        }
    }

    private static JPanel verticalPanel() {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JPanel section(
            final String title,
            final Component... components) {
        final JPanel panel = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                final Dimension preferred = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, preferred.height);
            }
        };
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(3, 3, 3, 3)));
        for (final Component component : components) {
            component.setMaximumSize(new Dimension(Integer.MAX_VALUE,
                    Math.max(24, component.getPreferredSize().height)));
            if (component instanceof JComponent swingComponent) {
                swingComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            panel.add(component);
        }
        return panel;
    }

    private static JPanel row(final Component... components) {
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (final Component component : components) {
            row.add(component);
        }
        return row;
    }

    private static JTextArea text(final String value) {
        final JTextArea area = new JTextArea(value) {
            @Override public void scrollRectToVisible(final java.awt.Rectangle rectangle) {
                // Display-only status changes must not scroll the inspector.
            }
        };
        ((javax.swing.text.DefaultCaret) area.getCaret()).setUpdatePolicy(
                javax.swing.text.DefaultCaret.NEVER_UPDATE);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setOpaque(false);
        area.setFocusable(false);
        area.setColumns(24);
        area.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
        return area;
    }

    private static void showError(final Component owner,
            final String title, final String message) {
        JOptionPane.showMessageDialog(owner, message, title,
                JOptionPane.ERROR_MESSAGE);
    }

    private static String rootMessage(final Throwable error) {
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null
                ? root.getClass().getSimpleName() : root.getMessage();
    }

    private record PointCountChoice(String label, int points) {
        private boolean automatic() {
            return points == 0;
        }

        private int resolve(
                final ReviewerRoi roi,
                final int sourceWidth,
                final int sourceHeight) {
            return automatic()
                    ? ReviewerRoiVertexReducer.automaticTarget(
                            roi, sourceWidth, sourceHeight)
                    : points;
        }

        private int resolve(
                final List<List<Point2D>> loops,
                final int sourceWidth,
                final int sourceHeight) {
            return automatic()
                    ? ReviewerRoiVertexReducer.automaticTarget(
                            loops, sourceWidth, sourceHeight)
                    : points;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class RoiRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                final JList<?> list, final Object value, final int index,
                final boolean selected, final boolean focus) {
            final ReviewerRoi roi = (ReviewerRoi) value;
            final String state = roi.finished() ? "finished" : "drawing";
            final String export = roi.selectedForExport() ? "☑" : "☐";
            final JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, export + " " + roi.name() + "  ["
                            + roi.side().displayName() + "; " + state + "]",
                    index, selected, focus);
            if (!roi.visible() && !selected) {
                label.setForeground(Color.GRAY);
            }
            return label;
        }
    }
}
