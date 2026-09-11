package org.atlasalign.plugin.review;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ItemEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.prefs.Preferences;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.ComboPopup;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.ConfidenceEvidence;
import org.atlasalign.application.InitialPlaneProposal;
import org.atlasalign.application.LandmarkPair;
import org.atlasalign.application.LandmarkRole;
import org.atlasalign.application.HalfAtlasCoverage;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.ReviewWorkflowMode;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.BoundaryFitAnchor;
import org.atlasalign.application.manual.BoundaryFitMatch;
import org.atlasalign.application.manual.BoundaryFitModel;
import org.atlasalign.application.manual.BoundaryWarpSolver;
import org.atlasalign.application.manual.ManualWarpSafetyReport;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.application.roi.ReviewerRoiSide;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.plugin.manual.GuidedManualWizardPanel;
import org.atlasalign.plugin.export.ManualRoiExportService;
import org.atlasalign.plugin.export.SourceSpaceExportService;

/**
 * Thin Swing implementation of the review boundary. Constructing this panel
 * never creates or displays a top-level window.
 */
public final class SwingReviewPanel extends JPanel
        implements ReviewView {

    /** Fits inside the minimum inspector after its border and scroll bar. */
    private static final int CONTROL_TEXT_WIDTH = 200;
    private static final int INSPECTOR_PREFERRED_WIDTH = 400;
    private static final int INSPECTOR_MINIMUM_WIDTH = 340;

    private final ReviewController controller;
    private final Optional<SourceSpaceExportService> exportService;
    private final Optional<ManualRoiExportService> manualRoiExportService;
    private final String sourceName;
    private final ReviewCanvas canvas;
    private final PreviewMapping manualRoiMapping;
    private final ReviewerRoiSession manualRoiSession;
    private final ManualRoiEditorPanel manualRoiEditor;
    private final javax.swing.Timer planeSliderTimer = new javax.swing.Timer(100,
            event -> flushPlaneSliders());
    private final JSlider levelSlider = new JSlider(
            SwingConstants.HORIZONTAL,
            0,
            AllenCoronalLevel.PLANE_COUNT - 1,
            AllenCoronalLevel.PLANE_COUNT / 2);
    private final JLabel levelLabel = new JLabel();
    private final JLabel tiltLabel = new JLabel();
    private final JLabel horizontalTiltLabel = new JLabel();
    private final JSlider sagittalTiltSlider = new JSlider(
            -45, 45, 0);
    private final JSlider horizontalTiltSlider = new JSlider(
            -45, 45, 0);
    private final JLabel initialProposal = new JLabel();
    private final JLabel automaticGuidance = new JLabel();
    private final JLabel previewContrast = new JLabel();
    private final JToggleButton automaticDetails = new JToggleButton(
            "Technical provenance…");
    private final JTextArea automaticProvenance = new JTextArea(8, 30);
    private final JCheckBox showAtlasAnatomy =
            new JCheckBox("Show atlas anatomy");
    private final JRadioButton orientationUnconfirmed =
            new JRadioButton("Provisional direct (unconfirmed)");
    private final JRadioButton orientationDirect =
            new JRadioButton("Direct: atlas left → image left");
    private final JRadioButton orientationReflected =
            new JRadioButton("Reflected: atlas left → image right");
    private final JRadioButton hemisphereLeft =
            new JRadioButton("Left");
    private final JRadioButton hemisphereRight =
            new JRadioButton("Right");
    private final JRadioButton hemisphereUnsure =
            new JRadioButton("Unsure");
    private final JButton undo = new JButton("Undo");
    private final JButton redo = new JButton("Redo");
    private final JButton reset = new JButton("Reset proposal");
    private final JButton refineManually = new JButton("Refine manually");
    private final JButton guidedManualWizard = new JButton(
            "Guided manual workflow");
    private final JButton accept = new JButton("Accept alignment");
    private final JButton revoke = new JButton("Revoke acceptance");
    private final JCheckBox warnings =
            new JCheckBox("I acknowledge the listed review cautions");
    private final JLabel acceptance = new JLabel();
    private final JTextArea confidence = new JTextArea(13, 30);
    private final JTextField landmarkId = new JTextField(7);
    private final JTextField atlasX = new JTextField(5);
    private final JTextField atlasY = new JTextField(5);
    private final JTextField previewX = new JTextField(5);
    private final JTextField previewY = new JTextField(5);
    private final JRadioButton landmarkFitRole =
            new JRadioButton("FIT — influences transform", true);
    private final JRadioButton landmarkCheckRole =
            new JRadioButton("CHECK — independent residual");
    private final JToggleButton advancedPointEditing = new JToggleButton(
            "Advanced point editing…");
    private final JButton fitLandmarks = new JButton(
            "Fit similarity (2+)");
    private final JButton fitLandmarksAffine = new JButton(
            "Fit global affine (3+)");
    private final JButton fitLandmarksLocalWarp = new JButton(
            "Fit local warp (4+)");
    private final JButton clearLocalWarp = new JButton(
            "Clear internal warp");
    private final JButton fitLandmarksToolbar = new JButton(
            "Fit similarity");
    private final JButton fitLocalWarpToolbar = new JButton(
            "Fit local");
    private final JToggleButton transformTool = new JToggleButton(
            "Transform", true);
    private final JToggleButton borderTool = new JToggleButton("Border");
    private final JToggleButton pointsTool = new JToggleButton("Points");
    private final JToggleButton panTool = new JToggleButton("Pan");
    private final JLabel landmarkCaptureStatus = new JLabel(wrappedHtml(
            "Click Atlas first, then matching Tissue point."));
    private final JLabel localWarpStatus = new JLabel(wrappedHtml(
            "Local warp inactive. Global alignment remains authoritative."));
    private final JLabel outlineWarpStatus = new JLabel(wrappedHtml(
            "Manual outline warp inactive."));
    private final JRadioButton targetNone =
            new JRadioButton("No target — all atlas structures", true);
    private final JRadioButton targetDg =
            new JRadioButton("DG — whole dentate gyrus");
    private final JRadioButton targetDgSg =
            new JRadioButton("DG-sg — granule cell layer");
    private final JRadioButton targetHpf =
            new JRadioButton("HPF — hippocampal formation");
    private final JRadioButton targetVs =
            new JRadioButton("VS — ventricular system");
    private final JRadioButton targetCc =
            new JRadioButton("cc — corpus callosum");
    private final JLabel targetRegionStatus = new JLabel(wrappedHtml(
            "No target selected; atlas points are unconstrained."));
    private final JButton seedAtlasLeftHandles = new JButton(
            "Set N on structure");
    private final JButton clearSelectedStructure = new JButton(
            "Clear selected structure");
    private final JButton seedAtlasRightHandles = new JButton(
            "Set N across side");
    private final JSpinner hemisphereHandleCount = new JSpinner(
            new SpinnerNumberModel(6, 4, 12, 1));
    private final JCheckBox advancedHemisphereDensity = new JCheckBox(
            "Advanced density (13–24)");
    private final JRadioButton activeAtlasLeft = new JRadioButton(
            "Active atlas left", true);
    private final JRadioButton activeAtlasRight = new JRadioButton(
            "Active atlas right");
    private final JButton applyInteriorStage = new JButton("Apply & continue to ROIs");
    private final JButton addInteriorPoint = new JButton(
            "Add one interior point");
    private final JButton skipInteriorStage = new JButton(
            "Skip Interior — keep current warp");
    private final JButton resetWarpSide = new JButton("Reset active side");
    private final JComboBox<Integer> structureControlDensity =
            new JComboBox<>(new Integer[]{4, 6, 8, 12, 16, 24, 32, 48, 64});
    private final JSlider structureRoiSize = new JSlider(75, 300, 100);
    private final JLabel structureRoiSizeValue = new JLabel("100%");
    private final JSlider structureBladeGap = new JSlider(-50, 100, 0);
    private final JLabel structureBladeGapValue = new JLabel("0%");
    private final JButton calculateStructureChanges = new JButton(
            "Calculate structure preview");
    private final JButton applyStructureChanges = new JButton(
            "Apply structure changes");
    private final JButton useValidStructurePreview = new JButton(
            "Use valid preview as draft");
    private final JButton resetHighlightedStructureUnit = new JButton(
            "Reset highlighted pair/dot");
    private final JButton cancelStructureChanges = new JButton(
            "Cancel changes");
    private final JButton skipStructureStage = new JButton(
            "Skip Structure — keep current warp");
    private final JCheckBox previewExportedRoi = new JCheckBox(
            "Preview exported ROI");
    private final JLabel structureAdjustmentStatus = new JLabel(
            "Set points to begin a Structure draft");
    private final JComboBox<Integer> gridControlDensity =
            new JComboBox<>(new Integer[]{4, 6, 8, 12, 16, 24, 32, 48, 64});
    private final JComboBox<Integer> boundaryControlDensity =
            new JComboBox<>(new Integer[]{4, 6, 8, 12, 16, 24, 36, 48});
    private final JComboBox<ReviewSectionMode> sectionMode =
            new JComboBox<>(ReviewSectionMode.values());
    private final JButton resuggestBoundary = new JButton(
            "Re-suggest crop");
    private final JButton drawTissueCrop = new JButton(
            "Draw crop polygon");
    private final JButton finishTissueCrop = new JButton(
            "Finish polygon");
    private final JButton cancelTissueCropDraw = new JButton(
            "Cancel drawing");
    private final JLabel tissueCropDrawStatus = new JLabel(
            "Automatic contrast or reviewer polygon");
    private final JButton suggestBoundaryFit = new JButton(
            "Start matching");
    private final JButton restartBoundaryFit = new JButton(
            "Restart points");
    private final JButton skipBoundaryFitStage = new JButton(
            "Skip Match — keep current placement");
    private final JCheckBox boundaryFitSeparateAxes = new JCheckBox(
            "Separate width/height");
    private final JButton applyBoundaryFit = new JButton("Apply");
    private final JButton cancelBoundaryFit = new JButton("Cancel");
    private final JButton includeBoundaryFitMatch = new JButton(
            "Skip selected");
    private final JButton removeBoundaryFitMatch = new JButton(
            "Clear selected endpoint");
    private final JLabel boundaryFitStatus = new JLabel(
            "Ready for guided matching");
    private final JButton suggestBoundaryWarpPairs = new JButton(
            "Prepare border points");
    private final JButton resuggestBoundaryWarpPairs = new JButton(
            "Re-suggest 24 border points");
    private final JButton skipBoundaryWarpStage = new JButton(
            "Skip Border — keep current warp");
    private final JButton calculateBoundaryWarp = new JButton(
            "Calculate border preview");
    private final JButton applyBoundaryWarp = new JButton("Apply border");
    private final JButton cancelBoundaryWarp = new JButton("Cancel");
    private final JButton includeBoundaryWarpMatch = new JButton(
            "Ignore selected");
    private final JButton removeBoundaryWarpMatch = new JButton(
            "Delete from draft");
    private final JCheckBox includeOppositeHalfRemnant = new JCheckBox(
            "Include opposite-side remnant");
    private final JLabel boundaryWarpStatus = new JLabel(
            "Shift-click atlas border, then tissue border");
    private final JTextField setupOtherGuideQuery = new JTextField(8);
    private final JButton selectSetupOtherGuide = new JButton("Find");
    private final JTextField otherGuideAcronym = new JTextField(8);
    private final JButton selectOtherGuide = new JButton("Find");
    private final JComboBox<String> setupGuideSearch = new JComboBox<>(
            new String[]{"All structures", "DG — Dentate gyrus",
                    "DG-sg — Dentate gyrus, granule cell layer",
                    "HPF — Hippocampal formation",
                    "VS — ventricular systems", "cc — corpus callosum"});
    private final JComboBox<String> guideSearch = new JComboBox<>(
            new String[]{"All structures", "DG — Dentate gyrus",
                    "DG-sg — Dentate gyrus, granule cell layer",
                    "HPF — Hippocampal formation",
                    "VS — ventricular systems", "cc — corpus callosum"});
    private final JRadioButton showBefore = new JRadioButton("Before");
    private final JRadioButton showAfter = new JRadioButton("After", true);
    private final JRadioButton showCompare = new JRadioButton("Compare");
    private final JButton toolbarUndo = new JButton("Atlas Undo");
    private final JButton toolbarRedo = new JButton("Atlas Redo");
    private final JButton toolbarReset = new JButton("Atlas Reset");
    private final JButton makeAtlasUpright = new JButton("Make upright");
    private final JButton toolbarClearWarp = new JButton("Clear warp");
    private final JButton allSections = new JButton("All sections");
    private Runnable returnToSectionsAction;
    private AtlasGuideBrowser setupAtlasBrowser;
    private final JButton toolbarBack = new JButton("Back");
    private final JButton toolbarNext = new JButton("Next");
    private final JButton toolbarSkip = new JButton("Skip step");
    private final JButton toolbarAccept = new JButton("Atlas Accept");
    private final JCheckBox showPointLabels = new JCheckBox("Point labels");
    private final JCheckBox outerBoundaries = new JCheckBox("Outer atlas boundary only", true);
    private final JCheckBox lockAspectRatio = new JCheckBox("Lock proportions", true);
    private final JPanel precisionControls = new JPanel() {
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    };
    private final JButton stagePrimary = new JButton("Continue");
    private final JLabel reviewStates = new JLabel();
    private final JLabel overlayLegend = new JLabel();
    private ReviewController.CompletedExport lastExport;
    private JPanel landmarkControls;
    private final JCheckBox showDeformationGrid = new JCheckBox("Warp grid");
    private final JCheckBox showDisplacementLines = new JCheckBox(
            "Displacement lines", true);
    private final JCheckBox tissueClipping = new JCheckBox("Clip atlas to tissue");
    private final JCheckBoxMenuItem editTissueCrop =
            new JCheckBoxMenuItem("Edit tissue crop");
    private final JButton viewOptions = new JButton("View ▾");
    private final JButton workflowGuide = new JButton("How to use");
    private final JPopupMenu viewPopup = new JPopupMenu();
    private final JMenuItem advancedMatch = new JMenuItem(
            "Advanced: numbered Match anchors…");
    private final JButton selectedGuideColor = new JButton("Selected color…");
    private final JButton dimGuideColor = new JButton("Dim color…");
    private final JSpinner guideThickness = new JSpinner(
            new SpinnerNumberModel(1.25, 0.5, 5.0, 0.25));
    private final JSlider overlayOpacity = new JSlider(0, 100, 65);
    private final JToggleButton pointListDetails = new JToggleButton(
            "Show point list…");
    private final JTextArea landmarkTable = new JTextArea(6, 30);
    private final JLabel errorStatus = new JLabel(" ");
    private final JLabel compactStatus = new JLabel("Ready");
    private JPanel manualPanel;
    private JPanel landmarkPanel;
    private JPanel canvasToolbar;
    private JPanel interactionToolbar;
    private JPanel boundaryFitPanel;
    private JPanel boundaryWarpPanel;
    private final CardLayout stageInspectorCards = new CardLayout();
    private final JPanel stageInspector = new JPanel(stageInspectorCards);
    private final JButton[] workflowStepButtons = java.util.Arrays.stream(
            ReviewWorkflowStage.values())
            .map(stage -> new JButton(stage.label()))
            .toArray(JButton[]::new);
    private ReviewWorkflowStage workflowStage =
            ReviewWorkflowStage.SETUP_AND_PLANE;
    private JPanel advancedPointForm;
    private JScrollPane landmarkTableScroll;
    private JScrollPane automaticProvenanceScroll;
    private ReviewViewModel latestModel;
    private boolean displayGeometryBlocked;
    private final java.util.Map<JComponent, Boolean> displayEnabledStates = new java.util.IdentityHashMap<>();
    private final CardLayout workflowCards = new CardLayout();
    private final JPanel workflowSurface = new JPanel(workflowCards);
    private final CardLayout inspectorCards = new CardLayout();
    private final JPanel inspectorSurface = new JPanel(inspectorCards);
    private GuidedManualWizardPanel guidedManualPanel;
    private SourceSpaceExportPanel exportPanel;
    private boolean exportInspectorVisible;
    private long pendingManualWarpMoveRevision = -1;
    private String selectedBoundaryFitMatchId;
    private String selectedBoundaryWarpMatchId;
    private ReviewSectionMode lastRenderedSectionMode;
    private ReviewSectionMode retainedSectionModePopupSelection;
    private boolean sectionModePopupEscapePressed;
    private final Preferences preferences = Preferences.userNodeForPackage(
            SwingReviewPanel.class).node("review-display");

    private boolean updating;
    private Runnable closeHandler = () -> {
    };

    public SwingReviewPanel(final ReviewController controller) {
        this(controller, Optional.empty(), Optional.empty(),
                defaultManualRoiSession(controller), "source-image");
    }

    public SwingReviewPanel(
            final ReviewController controller,
            final SourceSpaceExportService exportService,
            final String sourceName) {
        this(controller, Optional.of(Objects.requireNonNull(
                exportService, "exportService")), Optional.empty(),
                defaultManualRoiSession(controller), sourceName);
    }

    public SwingReviewPanel(
            final ReviewController controller,
            final SourceSpaceExportService exportService,
            final ManualRoiExportService manualRoiExportService,
            final ReviewerRoiSession manualRoiSession,
            final String sourceName) {
        this(controller, Optional.of(Objects.requireNonNull(
                        exportService, "exportService")),
                Optional.of(Objects.requireNonNull(
                        manualRoiExportService, "manualRoiExportService")),
                Objects.requireNonNull(manualRoiSession,
                        "manualRoiSession"), sourceName);
    }

    private SwingReviewPanel(
            final ReviewController controller,
            final Optional<SourceSpaceExportService> exportService,
            final Optional<ManualRoiExportService> manualRoiExportService,
            final ReviewerRoiSession manualRoiSession,
            final String sourceName) {
        super(new BorderLayout(8, 8));
        this.controller = Objects.requireNonNull(
                controller, "controller");
        this.exportService = Objects.requireNonNull(
                exportService, "exportService");
        this.manualRoiExportService = Objects.requireNonNull(
                manualRoiExportService, "manualRoiExportService");
        this.sourceName = Objects.requireNonNull(sourceName, "sourceName");
        this.manualRoiMapping = controller.state().basis().previewMapping();
        this.manualRoiSession = Objects.requireNonNull(
                manualRoiSession, "manualRoiSession");
        canvas = new ReviewCanvas(controller);
        canvas.setLandmarkCaptureListener(this::addClickedLandmark);
        canvas.setInteractionListener(new ReviewCanvas.InteractionListener() {
            @Override
            public void translate(final double dx, final double dy) {
                if (disjoinedMode()) {
                    runCanvasEdit("Atlas half not moved", () ->
                            controller.translateManualSide(
                                    activeWarpSide(), dx, dy));
                } else {
                    controller.translate(dx, dy);
                }
            }

            @Override
            public void scaleUniform(
                    final double factor,
                    final Point2D pivot) {
                if (disjoinedMode()) {
                    runCanvasEdit("Atlas half not scaled", () ->
                            controller.scaleManualSide(activeWarpSide(),
                                    factor, pivot));
                } else {
                    controller.scaleUniform(factor, pivot);
                }
            }

            @Override
            public void scaleAxes(
                    final double scaleX,
                    final double scaleY,
                    final double axisRadians,
                    final Point2D pivot) {
                if (disjoinedMode()) {
                    runCanvasEdit("Atlas half not resized", () ->
                            controller.scaleManualSideAxes(activeWarpSide(),
                                    scaleX, scaleY, axisRadians, pivot));
                } else {
                    runCanvasEdit("Atlas not resized", () ->
                            controller.scaleAxes(scaleX, scaleY,
                                    axisRadians, pivot));
                }
            }

            @Override
            public void rotateRadians(
                    final double radians,
                    final Point2D pivot) {
                if (disjoinedMode()) {
                    runCanvasEdit("Atlas half not rotated", () ->
                            controller.rotateManualSide(activeWarpSide(),
                                    radians, pivot));
                } else {
                    controller.rotateRadians(radians, pivot);
                }
            }

            @Override
            public boolean canPreviewTranslate(
                    final double dx,
                    final double dy) {
                return disjoinedMode()
                        ? controller.canPreviewTranslateManualSide(
                                activeWarpSide(), dx, dy)
                        : controller.canPreviewTranslate(dx, dy);
            }

            @Override
            public boolean canPreviewScale(
                    final double factor,
                    final Point2D pivot) {
                return disjoinedMode()
                        ? controller.canPreviewScaleManualSide(
                                activeWarpSide(), factor, pivot)
                        : controller.canPreviewScale(factor, pivot);
            }

            @Override
            public boolean canPreviewScaleAxes(
                    final double scaleX,
                    final double scaleY,
                    final double axisRadians,
                    final Point2D pivot) {
                return disjoinedMode()
                        ? controller.canPreviewScaleManualSideAxes(
                                activeWarpSide(), scaleX, scaleY,
                                axisRadians, pivot)
                        : controller.canPreviewScaleAxes(
                                scaleX, scaleY, axisRadians, pivot);
            }

            @Override
            public boolean canPreviewRotate(
                    final double radians,
                    final Point2D pivot) {
                return disjoinedMode()
                        ? controller.canPreviewRotateManualSide(
                                activeWarpSide(), radians, pivot)
                        : controller.canPreviewRotate(radians, pivot);
            }

            @Override
            public void moveLandmarkAtlasPoint(
                    final String id,
                    final Point2D point) {
                controller.moveLandmarkAtlasPoint(id, point);
            }

            @Override
            public void moveLandmarkPreviewPoint(
                    final String id,
                    final Point2D point) {
                moveTissueEndpoint(id, point);
            }

            @Override
            public void moveManualWarpControlAndRefit(
                    final String id,
                    final Point2D point) {
                try {
                    pendingManualWarpMoveRevision = controller.state()
                            .contentRevision();
                    controller.moveManualWarpControlAndRefit(id, point);
                    if (latestModel.reviewState().contentRevision()
                            == pendingManualWarpMoveRevision) {
                        setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                                "Moved the selected tissue endpoint. The full "
                                + "local warp safety check is running off the "
                                + "display thread; an unsafe request stays at its "
                                + "last safe geometry."));
                    }
                } catch (final RuntimeException error) {
                    pendingManualWarpMoveRevision = -1;
                    showError("Manual warp control not updated",
                            messageOf(error));
                }
            }

            @Override
            public void transformStructureControls(
                    final java.util.List<String> controlIds,
                    final java.util.List<Point2D> requestedTargets) {
                try {
                    pendingManualWarpMoveRevision = controller.state()
                            .contentRevision();
                    controller.transformSelectedStructureControls(
                            activeWarpSide(), controlIds, requestedTargets);
                    setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                            "Requested shape updated. Keep moving cyan dots "
                            + "or adjusting the sliders, then press Calculate "
                            + "structure preview. No warp solve or review "
                            + "revision has run yet."));
                } catch (final RuntimeException error) {
                    pendingManualWarpMoveRevision = -1;
                    showError("Structure draft not updated",
                            messageOf(error));
                }
            }

            @Override
            public void manualWarpSideActivated(
                    final ManualHemisphereWarp2D.AtlasSide side) {
                if (side == ManualHemisphereWarp2D.AtlasSide.LEFT) {
                    activeAtlasLeft.setSelected(true);
                } else {
                    activeAtlasRight.setSelected(true);
                }
                updateHandleButtonLabels();
            }

            @Override
            public void manualWarpPointSelected(final Point2D point) {
                addManualWarpPoint(point);
            }

            @Override
            public void moveTissueSupportControl(
                    final String id,
                    final Point2D point) {
                runCanvasEdit("Tissue crop not changed", () ->
                        controller.moveTissueSupportControl(id, point));
            }

            @Override
            public void insertTissueSupportControl(
                    final int componentIndex,
                    final int afterVertexIndex,
                    final Point2D point) {
                runCanvasEdit("Tissue crop point not added", () ->
                        controller.insertTissueSupportControl(
                                componentIndex, afterVertexIndex, point));
            }

            @Override
            public void deleteTissueSupportControl(final String id) {
                runCanvasEdit("Tissue crop point not removed", () ->
                        controller.deleteTissueSupportControl(id));
            }

            @Override
            public void tissueSupportTraceChanged(final int pointCount) {
                updateTissueCropDrawControls(pointCount);
            }

            @Override
            public void landmarkSelected(final String id) {
                selectLandmark(id);
            }

            @Override
            public void moveBoundaryFitMatch(
                    final String id,
                    final ReviewController.BoundaryFitEndpoint endpoint,
                    final Point2D point) {
                runCanvasEdit("Boundary match not moved", () ->
                        controller.moveBoundaryFitMatch(
                                id, endpoint, point));
            }

            @Override
            public void setBoundaryFitTissuePoint(
                    final String id,
                    final Point2D tissuePoint) {
                runCanvasEdit("Boundary point not matched", () ->
                        controller.setBoundaryFitTissuePoint(
                                id, tissuePoint));
            }

            @Override
            public void clearBoundaryFitAnchor(final String id) {
                runCanvasEdit("Boundary match not cleared", () ->
                        controller.clearBoundaryFitAnchor(id));
            }

            @Override
            public void boundaryFitMatchSelected(final String id) {
                selectedBoundaryFitMatchId = id;
                runCanvasEdit("Boundary point not selected", () ->
                        controller.selectBoundaryFitAnchor(id));
                updateBoundaryFitSelectionButtons();
            }

            @Override
            public void cancelBoundaryFitForManualTransform() {
                selectedBoundaryFitMatchId = null;
                controller.cancelBoundaryFit();
            }

            @Override
            public void moveBoundaryWarpMatch(
                    final String id,
                    final ReviewController.BoundaryFitEndpoint endpoint,
                    final Point2D point) {
                runCanvasEdit("Border pair not moved", () ->
                        controller.moveBoundaryWarpMatch(
                                id, endpoint, point));
            }

            @Override
            public void addBoundaryWarpMatch(
                    final Point2D atlasPoint,
                    final Point2D tissuePoint) {
                runCanvasEdit("Border pair not added", () ->
                        controller.addBoundaryWarpMatch(
                                atlasPoint, tissuePoint));
            }

            @Override
            public void completeBoundaryWarpMatch(
                    final String id,
                    final Point2D tissuePoint) {
                runCanvasEdit("Border pair not completed", () ->
                        controller.completeBoundaryWarpMatch(
                                id, tissuePoint));
            }

            @Override
            public void removeBoundaryWarpMatch(final String id) {
                runCanvasEdit("Border pair not removed", () ->
                        controller.removeBoundaryWarpMatch(id));
            }

            @Override
            public void boundaryWarpMatchSelected(final String id) {
                selectedBoundaryWarpMatchId = id;
                updateBoundaryWarpSelectionButtons();
            }

            @Override
            public void boundaryWarpSideActivated(
                    final ManualHemisphereWarp2D.AtlasSide side) {
                runCanvasEdit("Border side not activated", () ->
                        controller.activateBoundaryWarpSide(side));
                final boolean previous = updating;
                updating = true;
                try {
                    if (side == ManualHemisphereWarp2D.AtlasSide.LEFT) {
                        activeAtlasLeft.setSelected(true);
                    } else {
                        activeAtlasRight.setSelected(true);
                    }
                    canvas.setActiveHemisphereSide(side);
                } finally {
                    updating = previous;
                }
            }
        });
        group(
                orientationUnconfirmed,
                orientationDirect,
                orientationReflected);
        group(
                hemisphereLeft,
                hemisphereRight,
                hemisphereUnsure);
        group(transformTool, borderTool, pointsTool);
        group(landmarkFitRole, landmarkCheckRole);
        group(activeAtlasLeft, activeAtlasRight);
        group(targetNone, targetDg, targetDgSg, targetHpf, targetVs,
                targetCc);
        group(showBefore, showAfter, showCompare);
        showAtlasAnatomy.setToolTipText(
                "Show grayscale atlas anatomy (slower first load)");
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        canvas.setSingleTissuePane(true);
        transformTool.setSelected(true);
        canvas.setInteractionTool(ReviewCanvas.InteractionTool.TRANSFORM);
        /*
         * Keep the verified guide selectors non-editable.  Native macOS
         * Swing can leave an editable combo's text editor showing the old
         * value after its popup model has selected a new structure.  That
         * split state is particularly harmful here because the visible
         * label and the structure-refinement target then disagree.  The
         * verified choices are deliberately bounded, so a plain selector is
         * both clearer and deterministic across Fiji look-and-feel variants.
         */
        setupGuideSearch.setEditable(false);
        setupGuideSearch.setName("setupAnatomyGuideSearch");
        setupGuideSearch.setToolTipText(
                "Highlight a verified atlas structure while choosing the AP level and tilt");
        setupOtherGuideQuery.setName("setupOtherGuideQuery");
        selectSetupOtherGuide.setName("setupOtherGuideFind");
        setupOtherGuideQuery.setToolTipText(
                "Search the verified atlas ontology by acronym or structure name");
        guideSearch.setEditable(false);
        guideSearch.setName("ontologyGuideSearch");
        otherGuideAcronym.setName("structureOtherGuideQuery");
        selectOtherGuide.setName("structureOtherGuideFind");
        otherGuideAcronym.setToolTipText(
                "Search the verified atlas ontology by acronym or structure name");
        loadDisplayPreferences();
        manualRoiEditor = new ManualRoiEditorPanel(
                this.manualRoiSession, manualRoiMapping, canvas,
                this.manualRoiExportService, this.sourceName,
                this::defaultManualRoiSide);
        manualRoiEditor.setImageScopeController(controller);
        manualRoiEditor.addPropertyChangeListener("exportRunning", event -> updateStagePrimary());
        this.manualRoiSession.addListener(() -> SwingUtilities.invokeLater(
                () -> { refreshManualRoiSummary(); updateReviewStates(); updateStagePrimary(); }));
        controller.addExportListener(event -> SwingUtilities.invokeLater(() -> {
            lastExport = event; updateReviewStates();
        }));
        controller.addProjectChangeListener(() -> SwingUtilities.invokeLater(this::updateReviewStates));
        final JComponent controls = buildControls();
        controls.setPreferredSize(new Dimension(
                INSPECTOR_PREFERRED_WIDTH, 650));
        controls.setMinimumSize(new Dimension(
                INSPECTOR_MINIMUM_WIDTH, 420));
        inspectorSurface.setName("reviewInspectorCards");
        inspectorSurface.add(controls, "alignment");
        exportService.ifPresent(service -> {
            exportPanel = new SourceSpaceExportPanel(
                    controller, service, this.sourceName,
                    this::showAlignmentInspector);
            exportPanel.setPreferredSize(new Dimension(
                    INSPECTOR_PREFERRED_WIDTH, 650));
            exportPanel.setMinimumSize(new Dimension(
                    INSPECTOR_MINIMUM_WIDTH, 420));
            inspectorSurface.add(exportPanel, "export");
        });
        final JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                canvasHost(),
                inspectorSurface);
        split.setResizeWeight(1);
        split.setContinuousLayout(true);
        workflowSurface.add(split, "review");
        add(workflowSurface, BorderLayout.CENTER);
        wireActions();
        setWorkflowStage(ReviewWorkflowStage.SETUP_AND_PLANE);
    }

    private static ReviewerRoiSession defaultManualRoiSession(
            final ReviewController controller) {
        final PreviewMapping mapping = Objects.requireNonNull(
                controller, "controller").state().basis().previewMapping();
        return new ReviewerRoiSession(
                "Section 1", mapping.sourceWidth(), mapping.sourceHeight());
    }

    private ReviewerRoiSide defaultManualRoiSide() {
        if (activeAtlasRight.isSelected()) {
            return ReviewerRoiSide.RIGHT;
        }
        if (latestModel != null) {
            return switch (latestModel.observedHemisphere()) {
                case LEFT -> ReviewerRoiSide.LEFT;
                case RIGHT -> ReviewerRoiSide.RIGHT;
                case BOTH -> ReviewerRoiSide.BILATERAL;
                case UNSURE -> ReviewerRoiSide.LEFT;
            };
        }
        return ReviewerRoiSide.LEFT;
    }

    private boolean disjoinedMode() {
        return latestModel != null && latestModel.reviewState().content()
                .reviewSectionMode() == ReviewSectionMode.DISJOINED;
    }

    private void runCanvasEdit(
            final String title,
            final Runnable edit) {
        try {
            edit.run();
        } catch (final RuntimeException error) {
            showError(title, messageOf(error));
        }
    }

    public void setCloseHandler(final Runnable handler) {
        closeHandler = Objects.requireNonNull(handler, "handler");
    }

    public ReviewCanvas canvas() {
        return canvas;
    }

    public void setDisplayGeometryBlocked(final boolean blocked) {
        restoreDisplayControlStates();
        displayGeometryBlocked = blocked;
        manualRoiEditor.setDisplayGeometryBlocked(blocked);
        if (exportPanel != null) exportPanel.setDisplayGeometryBlocked(blocked);
        if (blocked) disableGeometryControls(this);
        else if (latestModel != null) render(latestModel);
    }

    public JComponent projectViewControls() { return viewPopup; }

    private void restoreDisplayControlStates() {
        displayEnabledStates.forEach(JComponent::setEnabled); displayEnabledStates.clear();
    }

    private void disableGeometryControls(final Component component) {
        if (component instanceof JComponent displayOnly && Boolean.TRUE.equals(displayOnly.getClientProperty("displayOnlyControl"))) return;
        if (component instanceof javax.swing.AbstractButton || component instanceof JComboBox<?>
                || component instanceof JSpinner || component instanceof JSlider || component instanceof javax.swing.text.JTextComponent) {
            final JComponent control = (JComponent) component;
            displayEnabledStates.put(control, control.isEnabled()); control.setEnabled(false);
        }
        if (component instanceof java.awt.Container container) for (Component child : container.getComponents()) disableGeometryControls(child);
    }

    public org.atlasalign.plugin.project.ReviewUiState captureProjectUi() {
        return new org.atlasalign.plugin.project.ReviewUiState(workflowStage, canvas.interactionTool(),
                canvas.comparisonMode(), canvas.viewport(), canvas.landmarkLabelsVisible(),
                canvas.deformationGridVisible(), canvas.displacementLinesVisible(), canvas.singleTissuePane(),
                canvas.selectedRegionColor().getRGB(), canvas.dimRegionColor().getRGB(), canvas.regionStrokeWidth(),
                canvas.overlayOpacity(), canvas.aspectRatioLocked(), canvas.outerBoundariesOnly(),
                canvas.activeHemisphereSide(), controller.selectedRegionId(), controller.showAtlasAnatomy(), canvas.placementTool());
    }

    public void restoreProjectUi(final org.atlasalign.plugin.project.ReviewUiState saved) {
        setWorkflowStage(saved.stage());
        canvas.setInteractionTool(saved.tool()); canvas.setComparisonMode(saved.comparison());
        landmarkControls.setVisible(saved.stage() == ReviewWorkflowStage.INTERIOR && saved.tool() == ReviewCanvas.InteractionTool.LANDMARKS);
        panTool.setSelected(saved.tool() == ReviewCanvas.InteractionTool.PAN);
        transformTool.setSelected(saved.tool() == ReviewCanvas.InteractionTool.TRANSFORM);
        borderTool.setSelected(saved.tool() == ReviewCanvas.InteractionTool.BORDER);
        pointsTool.setSelected(saved.tool() == ReviewCanvas.InteractionTool.POINTS || saved.tool() == ReviewCanvas.InteractionTool.LANDMARKS);
        canvas.setLandmarkLabelsVisible(saved.landmarkLabels());
        canvas.setDeformationGridVisible(saved.deformationGrid()); canvas.setDisplacementLinesVisible(saved.displacementLines());
        canvas.setSingleTissuePane(saved.singleTissuePane());
        canvas.setRegionStyle(new Color(saved.selectedGuideArgb(), true), new Color(saved.dimGuideArgb(), true), saved.regionStrokeWidth());
        canvas.setOverlayOpacity(saved.overlayOpacity()); canvas.setAspectRatioLocked(saved.aspectRatioLocked());
        canvas.setOuterBoundariesOnly(saved.outerBoundariesOnly()); canvas.setActiveHemisphereSide(saved.activeSide());
        canvas.setPlacementTool(saved.placementTool()); lockAspectRatio.setSelected(saved.aspectRatioLocked()); outerBoundaries.setSelected(saved.outerBoundariesOnly());
        showPointLabels.setSelected(saved.landmarkLabels()); showDeformationGrid.setSelected(saved.deformationGrid());
        showDisplacementLines.setSelected(saved.displacementLines()); overlayOpacity.setValue((int) Math.round(saved.overlayOpacity() * 100));
        guideThickness.setValue(saved.regionStrokeWidth());
        activeAtlasLeft.setSelected(saved.activeSide() == ManualHemisphereWarp2D.AtlasSide.LEFT);
        activeAtlasRight.setSelected(saved.activeSide() == ManualHemisphereWarp2D.AtlasSide.RIGHT);
        showBefore.setSelected(saved.comparison() == ReviewCanvas.ComparisonMode.BEFORE);
        showAfter.setSelected(saved.comparison() == ReviewCanvas.ComparisonMode.AFTER);
        showCompare.setSelected(saved.comparison() == ReviewCanvas.ComparisonMode.COMPARE);
        controller.restoreSelectedRegion(saved.selectedRegionId()); controller.setShowAtlasAnatomy(saved.showAtlasAnatomy());
        canvas.restoreViewport(saved.viewport());
    }

    String landmarkCaptureStatusTextForTests() {
        return landmarkCaptureStatus.getText();
    }

    String compactStatusTextForTests() {
        return compactStatus.getText();
    }

    ReviewerRoiSession manualRoiSessionForTests() {
        return manualRoiSession;
    }

    Optional<String> selectedGuideAcronymForTests() {
        return latestModel == null ? Optional.empty()
                : latestModel.selectedAtlasRegion()
                        .map(SelectedAtlasRegion::acronym);
    }

    boolean selectedGuideContourPresentForTests() {
        return latestModel != null && latestModel.selectedAtlasContour()
                .map(SelectedAtlasContour::isPresent).orElse(false);
    }

    @Override
    public void render(final ReviewViewModel model) {
        restoreDisplayControlStates();
        updating = true;
        try {
            latestModel = model;
            canvas.setModel(model);
            manualRoiEditor.updateModel(model);
            if (guidedManualPanel != null) {
                guidedManualPanel.updateModel(model);
            }
            if (!levelSlider.getValueIsAdjusting()) levelSlider.setValue(model.coronalLevel());
            setWrappedLabelText(levelLabel, wrappedHtml(String.format(
                    Locale.ROOT,
                    "Level %d — %d µm from anterior origin (not bregma)",
                    model.coronalLevel(),
                    model.reviewState().content().coronalLevel()
                            .anteriorOriginMicrometers())));
            final AtlasPlaneTilt tilt = model.reviewState().content()
                    .atlasPlaneTilt();
            if (!sagittalTiltSlider.getValueIsAdjusting()) sagittalTiltSlider.setValue((int) Math.round(
                    tilt.sagittalDegrees()));
            if (!horizontalTiltSlider.getValueIsAdjusting()) horizontalTiltSlider.setValue((int) Math.round(
                    tilt.horizontalDegrees()));
            showAtlasAnatomy.setSelected(model.showAtlasAnatomy());
            tiltLabel.setText(String.format(
                    Locale.ROOT,
                    "Sagittal tilt %.1f°", tilt.sagittalDegrees()));
            horizontalTiltLabel.setText(String.format(
                    Locale.ROOT, "Horizontal tilt %.1f°",
                    tilt.horizontalDegrees()));
            setWrappedLabelText(initialProposal, initialProposalText(model));
            setWrappedLabelText(
                    automaticGuidance, automaticPlaneGuidance(model));
            automaticProvenance.setText(
                    automaticProvenanceText(model));
            automaticProvenance.setCaretPosition(0);
            setWrappedLabelText(previewContrast, wrappedHtml(String.format(
                    Locale.ROOT,
                    "Display-only contrast: %.3f to %.3f (%s); "
                            + "source unchanged",
                    model.preview().displayWindow().lower(),
                    model.preview().displayWindow().upper(),
                    displayStrategyText(model.preview()
                            .displayWindow().strategy()))));
            selectOrientation(model.orientation());
            selectHemisphere(model.observedHemisphere());
            final ReviewSectionMode renderedMode = model.reviewState()
                    .content().reviewSectionMode();
            sectionMode.setSelectedItem(renderedMode);
            final boolean clip = model.reviewState().content()
                    .tissueClippingEnabled();
            final boolean manual = model.reviewState().content()
                    .workflowMode().permitsManualEdits();
            // Automatic review has only the Accept/Refine entry card.  The
            // numbered wizard starts at Setup only after the reviewer enters
            // manual refinement.  Keeping the stored manual stage untouched
            // also makes a later automatic render unable to trigger a stage
            // activation side effect (such as seeding local controls).
            stageInspectorCards.show(stageInspector,
                    manual ? workflowStage.cardKey()
                            : ReviewWorkflowStage.ACCEPT_EXPORT.cardKey());
            tissueClipping.setSelected(clip);
            canvas.setTissueClippingEnabled(clip);
            final boolean cropAvailable = model.reviewState().content()
                    .reviewedTissueSupport().isPresent();
            tissueClipping.setEnabled(manual && cropAvailable);
            editTissueCrop.setEnabled(manual && cropAvailable);
            if (!cropAvailable && editTissueCrop.isSelected()) {
                editTissueCrop.setSelected(false);
                canvas.setTissueSupportEditing(false);
            }
            lastRenderedSectionMode = renderedMode;
            setHemisphereEnabled(model);
            setEnabledRecursively(manualPanel, manual);
            setEnabledRecursively(landmarkPanel, manual);
            // The recursive workflow gate must not erase the stricter
            // geometry/orientation-specific laterality choices.
            setHemisphereEnabled(model);
            guidedManualWizard.setEnabled(manual);
            refineManually.setEnabled(model.reviewState().content()
                    .workflowMode() == ReviewWorkflowMode.AUTOMATIC_REVIEW);
            refineManually.setText(manual
                    ? "Manual refinement active"
                    : "Refine manually");
            undo.setEnabled(model.canUndo());
            redo.setEnabled(model.canRedo());
            reset.setEnabled(model.canReset());
            toolbarUndo.setEnabled(model.canUndo());
            toolbarRedo.setEnabled(model.canRedo());
            toolbarReset.setEnabled(model.canReset());
            makeAtlasUpright.setEnabled(controller.canMakeAtlasUpright());
            toolbarBack.setEnabled(manual
                    && (workflowStage.index() > 0
                            || exportInspectorVisible));
            toolbarNext.setEnabled(manual
                    && workflowStage != ReviewWorkflowStage.ACCEPT_EXPORT
                    && !exportInspectorVisible);
            toolbarSkip.setEnabled(manual
                    && workflowStage != ReviewWorkflowStage.SETUP_AND_PLANE
                    && workflowStage != ReviewWorkflowStage.ACCEPT_EXPORT
                    && !exportInspectorVisible);
            updateStageSkipLabel();
            for (int index = 0; index < workflowStepButtons.length; index++) {
                workflowStepButtons[index].setEnabled(manual
                        && index != workflowStage.index()
                        && !(index == ReviewWorkflowStage.MATCH.index()
                                && (model.reviewState().content()
                                        .hemisphereWarp().isPresent()
                                || model.reviewState().content()
                                        .localWarp().isPresent())));
            }
            toolbarAccept.setText(model.accepted()
                    && exportPanel != null ? "Atlas export" : "Atlas Accept");
            final boolean boundaryFitActive = model.boundaryFit().active();
            final boolean boundaryWarpActive = model.boundaryWarp().active();
            final boolean boundaryFitEditing = boundaryFitActive
                    && workflowStage == ReviewWorkflowStage.MATCH;
            final boolean boundaryWarpEditing = boundaryWarpActive
                    && workflowStage == ReviewWorkflowStage.BORDER;
            toolbarAccept.setEnabled((!model.accepted()
                    || exportPanel != null) && !boundaryFitEditing
                    && !boundaryWarpEditing);
            warnings.setSelected(model.warningsAcknowledged());
            accept.setEnabled(!model.accepted() && !boundaryFitEditing
                    && !boundaryWarpEditing);
            revoke.setEnabled(model.accepted());
            acceptance.setText(model.accepted()
                    ? "ACCEPTED — current verified revision"
                    : "PENDING — no accepted alignment");
            acceptance.setForeground(model.accepted()
                    ? new Color(0, 115, 45)
                    : new Color(150, 70, 0));
            if (!model.accepted() && exportInspectorVisible) {
                if (exportPanel != null) {
                    exportPanel.acceptanceInvalidated();
                } else {
                    showAlignmentInspector();
                }
            }
            confidence.setText(confidenceText(model));
            landmarkTable.setText(landmarkTableText(model));
            setEnabledRecursively(interactionToolbar, manual);
            final int fitPairs = model.reviewState().content()
                    .activeGenericFitLandmarks().size();
            final int checkPairs = model.reviewState().content()
                    .activeCheckLandmarks().stream()
                    .filter(pair -> pair.anatomicalHandleMetadata().isEmpty())
                    .toList().size();
            final boolean localWarp = model.reviewState().content()
                    .localWarp().isPresent();
            final boolean hemisphereWarp = model.reviewState().content()
                    .hemisphereWarp().isPresent();
            final boolean sidePlacement = !model.reviewState().content()
                    .manualSidePlacement().isIdentity();
            final boolean deformingWarp = localWarp || hemisphereWarp;
            final boolean anyLocalWarp = deformingWarp || sidePlacement;
            final String points = fitPairs == 1 ? " point" : " points";
            fitLandmarks.setText("Fit similarity (" + fitPairs
                    + points + ")");
            fitLandmarksAffine.setText("Fit global affine (" + fitPairs
                    + points + ")");
            fitLandmarksToolbar.setText("Fit sim (" + fitPairs + ")");
            fitLandmarksLocalWarp.setText("Fit generic local warp ("
                    + fitPairs + " FIT)");
            fitLocalWarpToolbar.setText("Fit generic local (" + fitPairs
                    + ")");
            final boolean exactBoundary = false;
            fitLandmarks.setEnabled(
                    manual && !exactBoundary && fitPairs >= 2);
            fitLandmarksToolbar.setEnabled(
                    manual && !exactBoundary && fitPairs >= 2);
            fitLandmarksAffine.setEnabled(
                    manual && !exactBoundary && fitPairs >= 3);
            fitLandmarksLocalWarp.setEnabled(
                    manual && !exactBoundary && fitPairs >= 4
                    && !anyLocalWarp);
            fitLocalWarpToolbar.setEnabled(
                    manual && !exactBoundary && fitPairs >= 4
                    && !anyLocalWarp);
            clearLocalWarp.setEnabled(manual && anyLocalWarp);
            toolbarClearWarp.setEnabled(manual && anyLocalWarp);
            final boolean sideHandlesReady = manual
                    && model.orientation().confirmed()
                    && model.selectedAtlasContour()
                    .map(SelectedAtlasContour::isPresent).orElse(false);
            final boolean sideMeshReady = manual
                    && model.orientation().confirmed();
            final boolean halfMode = renderedMode == ReviewSectionMode.HALF;
            final ManualHemisphereWarp2D.AtlasSide visibleHalfSide =
                    model.observedHemisphere()
                            == ObservedAnatomicalHemisphere.RIGHT
                            ? ManualHemisphereWarp2D.AtlasSide.RIGHT
                            : ManualHemisphereWarp2D.AtlasSide.LEFT;
            final boolean remnantIncluded = includeOppositeHalfRemnant
                    .isSelected();
            activeAtlasLeft.setEnabled(sideMeshReady && (!halfMode
                    || visibleHalfSide
                            == ManualHemisphereWarp2D.AtlasSide.LEFT
                    || remnantIncluded));
            activeAtlasRight.setEnabled(sideMeshReady && (!halfMode
                    || visibleHalfSide
                            == ManualHemisphereWarp2D.AtlasSide.RIGHT
                    || remnantIncluded));
            if (halfMode && !remnantIncluded
                    && model.observedHemisphere()
                            != ObservedAnatomicalHemisphere.UNSURE
                    && model.observedHemisphere()
                            != ObservedAnatomicalHemisphere.BOTH) {
                if (visibleHalfSide
                        == ManualHemisphereWarp2D.AtlasSide.LEFT) {
                    activeAtlasLeft.setSelected(true);
                } else {
                    activeAtlasRight.setSelected(true);
                }
                canvas.setActiveHemisphereSide(visibleHalfSide);
            }
            updateToolSensitiveActionEnablement(model);
            resuggestBoundary.setEnabled(sideMeshReady
                    && model.reviewState().basis().segmentation().isPresent());
            drawTissueCrop.setEnabled(manual);
            finishTissueCrop.setEnabled(manual
                    && canvas.tissueSupportTracing()
                    && canvas.tissueSupportTracePoints().size()
                            >= org.atlasalign.application
                                    .ReviewedTissueSupport
                                    .MINIMUM_CONTROLS_PER_COMPONENT);
            cancelTissueCropDraw.setEnabled(manual
                    && canvas.tissueSupportTracing());
            resetWarpSide.setEnabled(manual && (hemisphereWarp
                    || sidePlacement));
            skipInteriorStage.setEnabled(manual);
            updateHandleButtonLabels();
            seedAtlasLeftHandles.setToolTipText(
                    "Create or replace a dense Fiji-style editable outline for the selected structure on the active atlas side");
            clearSelectedStructure.setToolTipText(
                    "Remove only the selected acronym's controls on the active atlas side");
            seedAtlasRightHandles.setToolTipText(
                    "Replace the regular interior control group with 4–64 distributed points on the active atlas side");
            transformTool.setEnabled(manual
                    && !localWarp && !hemisphereWarp);
            final boolean halfLateralityReady = renderedMode
                    != ReviewSectionMode.HALF
                    || model.observedHemisphere()
                    == ObservedAnatomicalHemisphere.LEFT
                    || model.observedHemisphere()
                    == ObservedAnatomicalHemisphere.RIGHT;
            borderTool.setEnabled(manual && !localWarp
                    && !boundaryFitEditing && model.orientation().confirmed()
                    && halfLateralityReady && cropAvailable
                    && model.atlasPlane().isPresent()
                    && !model.atlasPlaneLoading());
            pointsTool.setEnabled(manual && !boundaryFitEditing
                    && !boundaryWarpEditing);
            sectionMode.setEnabled(manual);
            if ((localWarp || hemisphereWarp)
                    && transformTool.isSelected()) {
                pointsTool.setSelected(true);
                canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            } else if (!deformingWarp
                    && workflowStage == ReviewWorkflowStage.SETUP_AND_PLANE && !panTool.isSelected()
                    && !boundaryFitEditing && !boundaryWarpEditing) {
                transformTool.setSelected(true);
                canvas.setInteractionTool(
                        ReviewCanvas.InteractionTool.TRANSFORM);
            }
            setWrappedLabelText(localWarpStatus, wrappedHtml(hemisphereWarp
                    ? hemisphereWarpStatusText(model)
                    : localWarp
                    ? localWarpStatusText(model, fitPairs, checkPairs)
                    : "Reviewer-controlled manual warp inactive. The editable "
                    + "contrast boundary is proposed automatically for eligible full sections."));
            setWrappedLabelText(outlineWarpStatus,
                    outlineWarpStatusText(model));
            renderSelectedTarget(model);
            renderBoundaryFit(model, manual, deformingWarp);
            renderBoundaryWarp(model, manual, localWarp);
            renderStructureAdjustment(model, manual);
            if (pendingManualWarpMoveRevision >= 0
                    && model.reviewState().contentRevision()
                    > pendingManualWarpMoveRevision
                    && model.reviewState().content().hemisphereWarp()
                            .isPresent()) {
                final double solveMillis = model.reviewState().content()
                        .hemisphereWarp().orElseThrow().diagnostics()
                        .solveMillis();
                setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                        String.format(Locale.ROOT,
                                "Installed one atomic audited warp revision in "
                                + "%.1f ms of solver time. The endpoint is at "
                                + "the requested or last safe clamped position.",
                                solveMillis)));
                pendingManualWarpMoveRevision = -1;
            }
            controller.takeManualWarpStatusMessage().ifPresent(message ->
                    setWrappedLabelText(landmarkCaptureStatus,
                            wrappedHtml(message)));
            errorStatus.setText(model.atlasPlaneError()
                    .map(value -> "Atlas load: " + value)
                    .orElse(" "));
            manualRoiEditor.refreshExportReadiness();
            setEnabledRecursively(precisionControls, canvas.precisionEditingAvailable());
            precisionControls.setToolTipText(canvas.precisionEditingAvailable() ? "Each Apply is one undoable change; distances use source pixels"
                    : "Placement controls require an editable coarse placement; undo or clear local refinement to return to Setup");
            updateStagePrimary(); updateReviewStates();
            compactStatus.setText(compactStatusText(model));
            compactStatus.setToolTipText(hemisphereWarp
                    ? hemisphereWarpStatusText(model)
                    : "Reviewer controls are manual geometry and never automatic evidence.");
        } finally {
            updating = false;
            if (displayGeometryBlocked) disableGeometryControls(this);
        }
    }

    @Override
    public void showError(
            final String title,
            final String message) {
        if (title.startsWith("Manual warp")) {
            pendingManualWarpMoveRevision = -1;
        }
        errorStatus.setText(title + ": " + message);
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(
                    this,
                    message,
                    title,
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    private void renderBoundaryFit(
            final ReviewViewModel model,
            final boolean manual,
            final boolean deformingWarp) {
        final var state = model.boundaryFit();
        final boolean halfLateralityReady = model.reviewState().content()
                .reviewSectionMode() != ReviewSectionMode.HALF
                || model.observedHemisphere()
                == ObservedAnatomicalHemisphere.LEFT
                || model.observedHemisphere()
                == ObservedAnatomicalHemisphere.RIGHT;
        final boolean ready = manual && !deformingWarp
                && model.orientation().confirmed()
                && halfLateralityReady
                && model.reviewState().content().reviewedTissueSupport()
                        .isPresent()
                && model.atlasPlane().isPresent()
                && !model.atlasPlaneLoading();
        suggestBoundaryFit.setText(state.active()
                ? "Resume matching" : "Start matching");
        restartBoundaryFit.setText(state.refitSuggested()
                ? "Match again" : "Restart points");
        transformTool.setText(state.active() ? "Exit match" : "Transform");
        transformTool.setToolTipText(state.active()
                ? "Discard the uncommitted border matches and return to manual Transform"
                : "Drag corners or side handles to resize; hold Shift to keep proportions; clear local warp before returning");
        suggestBoundaryFit.setEnabled(ready && !state.loading());
        restartBoundaryFit.setEnabled(ready && state.draft().isPresent()
                && !state.loading());
        skipBoundaryFitStage.setEnabled(manual && !state.loading());
        state.draft().ifPresent(draft ->
                boundaryFitSeparateAxes.setSelected(draft.request()
                        .model() == BoundaryFitModel.ORTHOGONAL_XY));
        boundaryFitSeparateAxes.setEnabled(ready && !state.loading());
        applyBoundaryFit.setEnabled(state.candidate().isPresent()
                && !state.loading());
        cancelBoundaryFit.setEnabled(state.active());
        final String message = state.message().orElse(ready
                ? "Optional: match to cyan, or click farther away for an exact torn-edge point; clipping stays cyan"
                : deformingWarp
                ? "Clear or Undo local warp before border fitting"
                : "Confirm the plane, orientation, tissue crop, and Half laterality");
        setWrappedLabelText(boundaryFitStatus, wrappedHtml(message));
        boundaryFitStatus.setToolTipText(message);
        state.draft().flatMap(draft -> draft.activeAnchorId())
                .ifPresent(identifier ->
                        selectedBoundaryFitMatchId = identifier);
        if (selectedBoundaryFitMatchId != null && state.draft().stream()
                .flatMap(draft -> draft.anchors().stream())
                .noneMatch(anchor -> anchor.id().equals(
                        selectedBoundaryFitMatchId))) {
            selectedBoundaryFitMatchId = null;
        }
        updateBoundaryFitSelectionButtons();
        updateBoundaryFitPanelVisibility();
    }

    private void renderBoundaryWarp(
            final ReviewViewModel model,
            final boolean manual,
            final boolean genericLocalWarp) {
        final var state = model.boundaryWarp();
        final boolean ready = manual && !genericLocalWarp
                && model.orientation().confirmed()
                && model.reviewState().content().reviewedTissueSupport()
                        .isPresent()
                && model.atlasPlane().isPresent()
                && !model.atlasPlaneLoading();
        updateBoundaryWarpButtonLabel();
        suggestBoundaryWarpPairs.setText(state.active()
                ? "Resume border points" : "Prepare border points");
        suggestBoundaryWarpPairs.setEnabled(ready && !state.loading());
        resuggestBoundaryWarpPairs.setEnabled(ready
                && state.draft().isPresent() && !state.loading());
        skipBoundaryWarpStage.setEnabled(manual && !state.loading());
        final boolean appliedBorder = hasAppliedBorder(model);
        skipBoundaryWarpStage.setText(appliedBorder
                ? "Keep applied border & continue"
                : "Skip Border — keep current placement");
        skipBoundaryWarpStage.setToolTipText(appliedBorder
                ? "Continue to Interior with the applied border; discard only the pending Border edits. Atlas Undo still restores earlier applied changes."
                : "Continue to Interior without applying the pending Border edits.");
        compactMeasured(skipBoundaryWarpStage);
        calculateBoundaryWarp.setEnabled(ready && !state.loading()
                && state.draft().stream().anyMatch(request -> request
                        .matches().stream().filter(BoundaryFitMatch::included)
                        .count() >= BoundaryWarpSolver
                                .MINIMUM_INCLUDED_MATCHES));
        applyBoundaryWarp.setEnabled(state.candidate().isPresent()
                && !state.loading() && !state.dirty());
        applyBoundaryWarp.setText("Apply border");
        compactMeasured(applyBoundaryWarp);
        cancelBoundaryWarp.setEnabled(state.active());
        final boolean half = model.reviewState().content()
                .reviewSectionMode() == ReviewSectionMode.HALF;
        includeOppositeHalfRemnant.setVisible(half);
        includeOppositeHalfRemnant.setSelected(half
                && model.reviewState().content().halfAtlasCoverage()
                        .includesOppositeRemnant());
        includeOppositeHalfRemnant.setEnabled(half && ready
                && !state.loading()
                && controller.boundaryWarpOppositeRemnantAvailable());
        includeOppositeHalfRemnant.setToolTipText(
                controller.boundaryWarpOppositeRemnantAvailable()
                        ? "Also expose local controls for the geometrically supported opposite-side tissue remnant"
                        : "No distributed opposite-side tissue remnant is supported by the current placement");
        final String draftMessage = state.message().orElse(
                state.recheckSuggested()
                        ? "Plane changed — recheck border pairs"
                        : ready
                        ? "Optional: snap near cyan or place exact torn-edge points; clipping remains unchanged"
                        : "Confirm plane, orientation, crop, and Half laterality");
        final String baseMessage = state.candidate().isPresent()
                ? draftMessage + " — preview only; not installed until Apply"
                : draftMessage;
        final String message = state.safetyReport()
                .map(report -> boundarySafetyText(report)
                        + " — " + baseMessage)
                .orElse(baseMessage);
        final boolean rejected = state.safetyReport().isPresent()
                && state.candidate().isEmpty();
        final String visibleMessage = rejected
                ? appliedBorder
                        ? "New border changes were not applied. Keep the applied border to continue, or edit the pairs and calculate again."
                        : "Border changes were not applied. Edit the pairs or adjust Setup, then calculate again."
                : message;
        setWrappedLabelText(boundaryWarpStatus, wrappedHtml(visibleMessage));
        boundaryWarpStatus.setToolTipText(message);
        if (selectedBoundaryWarpMatchId != null
                && state.drafts().stream()
                .flatMap(request -> request.matches().stream())
                .noneMatch(match -> match.id().equals(
                        selectedBoundaryWarpMatchId))) {
            selectedBoundaryWarpMatchId = null;
        }
        updateBoundaryWarpSelectionButtons();
        updateBoundaryFitPanelVisibility();
    }

    private static boolean hasAppliedBorder(final ReviewViewModel model) {
        return model != null && model.reviewState().content().hemisphereWarp()
                .stream().flatMap(warp -> warp.controls().stream())
                .anyMatch(control -> control.groupId().equals(
                        BoundaryWarpSolver.GROUP_ID));
    }

    private static String boundarySafetyText(
            final ManualWarpSafetyReport report) {
        final String gate = report.gate().name().toLowerCase(Locale.ROOT)
                .replace('_', ' ');
        final StringBuilder text = new StringBuilder("Limiting ")
                .append(gate);
        if (report.measuredValue().isPresent()
                && report.threshold().isPresent()) {
            final boolean minimum = switch (report.gate()) {
                case DETERMINANT, MINIMUM_SINGULAR_VALUE -> true;
                default -> false;
            };
            text.append(' ').append(String.format(Locale.ROOT, "%.4g",
                    report.measuredValue().getAsDouble()))
                    .append(minimum ? " < " : " > ")
                    .append(String.format(Locale.ROOT, "%.4g",
                            report.threshold().getAsDouble()));
        }
        report.affectedSide().ifPresent(side -> text.append(" on atlas ")
                .append(side.name().toLowerCase(Locale.ROOT)));
        report.meshLocation().ifPresent(location -> text.append(" near (")
                .append(String.format(Locale.ROOT, "%.1f", location.x()))
                .append(", ")
                .append(String.format(Locale.ROOT, "%.1f", location.y()))
                .append(')'));
        return text.toString();
    }

    private void renderStructureAdjustment(
            final ReviewViewModel model,
            final boolean manual) {
        final StructureAdjustmentViewState state =
                model.structureAdjustment();
        final StructureAdjustmentDraft draft = state.draft().orElse(null);
        structureRoiSize.setValue(draft == null
                ? 100 : draft.thicknessPercent());
        structureBladeGap.setValue(draft == null
                ? 0 : draft.bladeGapPercent());
        structureRoiSizeValue.setText(structureRoiSize.getValue() + "%");
        structureBladeGapValue.setText(
                (structureBladeGap.getValue() > 0 ? "+" : "")
                        + structureBladeGap.getValue() + "%");
        final boolean active = manual && draft != null
                && draft.atlasSide() == activeWarpSide();
        final boolean anatomySelectionEnabled = manual && !state.active();
        guideSearch.setEnabled(anatomySelectionEnabled);
        otherGuideAcronym.setEnabled(anatomySelectionEnabled);
        selectOtherGuide.setEnabled(anatomySelectionEnabled);
        setupGuideSearch.setEnabled(anatomySelectionEnabled);
        if (setupAtlasBrowser != null) setupAtlasBrowser.setEnabled(anatomySelectionEnabled);
        setupOtherGuideQuery.setEnabled(anatomySelectionEnabled);
        selectSetupOtherGuide.setEnabled(anatomySelectionEnabled);
        structureRoiSize.setEnabled(active);
        structureBladeGap.setEnabled(active
                && draft.hasTwoPrincipalComponents());
        structureBladeGap.setToolTipText(draft != null
                && draft.hasTwoPrincipalComponents()
                ? "Move the two recognized blade groups equally apart or together without resizing them"
                : "Blade gap requires two recognized blade groups on the active atlas side");
        calculateStructureChanges.setEnabled(active && !state.loading());
        applyStructureChanges.setEnabled(active && !state.loading()
                && state.candidate().isPresent());
        useValidStructurePreview.setEnabled(active && !state.loading()
                && state.candidate().stream().anyMatch(candidate ->
                        !candidate.completesRequest()));
        resetHighlightedStructureUnit.setEnabled(active
                && state.highlightedUnitId().isPresent());
        cancelStructureChanges.setEnabled(manual && state.active());
        skipStructureStage.setEnabled(manual);
        updateExportPreviewAvailability(model, manual, draft, state);
        String message = state.message().orElse(active
                ? "Edit the requested shape, then Calculate structure preview."
                : "Create a 4–64 point editable ROI outline to begin.");
        if (active && state.candidate().isEmpty()) {
            message += " Preview exported ROI shows the currently installed "
                    + "magenta shape; the dashed cyan request is not exported.";
        }
        setWrappedLabelText(structureAdjustmentStatus,
                wrappedHtml(message));
        structureAdjustmentStatus.setToolTipText(message);
    }

    private void updateExportPreviewAvailability(
            final ReviewViewModel model,
            final boolean manual,
            final StructureAdjustmentDraft draft,
            final StructureAdjustmentViewState state) {
        final boolean calculatedPreviewMatches = draft != null
                && state.candidate().stream().anyMatch(candidate ->
                        candidate.draftHash().equals(draft.inputHash()));
        final boolean exportPreviewAvailable = manual
                && workflowStage == ReviewWorkflowStage.STRUCTURE
                && model.selectedAtlasRegion().isPresent();
        if (!exportPreviewAvailable && previewExportedRoi.isSelected()) {
            previewExportedRoi.setSelected(false);
            canvas.setExportedRoiPreviewVisible(false);
        }
        previewExportedRoi.setEnabled(exportPreviewAvailable);
        previewExportedRoi.setToolTipText(draft != null
                && !calculatedPreviewMatches
                ? "Show the installed magenta ROI bright; the dashed cyan "
                        + "request is not exported until it is calculated and applied"
                : "Show the installed or calculated ROI bright and the remaining tissue at 20% brightness");
    }

    private BoundaryFitAnchor selectedBoundaryFitAnchor() {
        if (latestModel == null || selectedBoundaryFitMatchId == null) {
            return null;
        }
        return latestModel.boundaryFit().draft().stream()
                .flatMap(draft -> draft.anchors().stream())
                .filter(anchor -> anchor.id().equals(
                        selectedBoundaryFitMatchId))
                .findFirst().orElse(null);
    }

    private void updateBoundaryFitSelectionButtons() {
        final BoundaryFitAnchor anchor = selectedBoundaryFitAnchor();
        final boolean enabled = anchor != null && latestModel != null
                && !latestModel.boundaryFit().loading();
        includeBoundaryFitMatch.setEnabled(enabled);
        removeBoundaryFitMatch.setEnabled(enabled
                && anchor.tissuePreviewPoint().isPresent());
        includeBoundaryFitMatch.setText(anchor != null && anchor.included()
                ? "Skip selected" : "Use selected");
        compactMeasured(includeBoundaryFitMatch);
    }

    private BoundaryFitMatch selectedBoundaryWarpMatch() {
        if (latestModel == null || selectedBoundaryWarpMatchId == null) {
            return null;
        }
        return latestModel.boundaryWarp().drafts().stream()
                .flatMap(request -> request.matches().stream())
                .filter(match -> match.id().equals(
                        selectedBoundaryWarpMatchId))
                .findFirst().orElse(null);
    }

    private void updateBoundaryWarpSelectionButtons() {
        final BoundaryFitMatch match = selectedBoundaryWarpMatch();
        final boolean enabled = match != null && latestModel != null
                && !latestModel.boundaryWarp().loading();
        includeBoundaryWarpMatch.setEnabled(enabled);
        removeBoundaryWarpMatch.setEnabled(enabled);
        includeBoundaryWarpMatch.setText(match != null && match.included()
                ? "Ignore selected" : "Use selected");
        compactMeasured(includeBoundaryWarpMatch);
    }

    private void updateBoundaryFitPanelVisibility() {
        if (boundaryFitPanel == null) {
            return;
        }
        boundaryFitPanel.setVisible(transformTool.isSelected());
        boundaryFitPanel.revalidate();
        if (boundaryWarpPanel != null) {
            boundaryWarpPanel.setVisible(borderTool.isSelected());
            boundaryWarpPanel.revalidate();
        }
    }

    private void cancelBoundaryFitIfActive() {
        if (!updating && latestModel != null
                && latestModel.boundaryFit().active()) {
            controller.cancelBoundaryFit();
        }
        if (!updating && latestModel != null
                && latestModel.boundaryWarp().active()) {
            controller.cancelBoundaryWarp();
        }
    }

    private static String displayStrategyText(
            final PreviewDisplayStrategy strategy) {
        return switch (strategy) {
            case PERCENTILE_0_5_TO_99_5 ->
                    "0.5th–99.5th percentile";
            case FINITE_MIN_MAX_FALLBACK ->
                    "finite min/max fallback";
        };
    }

    @Override
    public void reviewClosed() {
        planeSliderTimer.stop();
        guidedManualPanel = null;
        if (exportPanel != null) {
            exportPanel.close();
            exportPanel = null;
        }
        closeHandler.run();
    }

    private JComponent buildControls() {
        orientationDirect.setText("Direct");
        orientationReflected.setText("Reflected");
        activeAtlasLeft.setText("Atlas left");
        activeAtlasRight.setText("Atlas right");
        hemisphereLeft.setText("L");
        hemisphereRight.setText("R");
        hemisphereUnsure.setText("?");

        final JPanel setup = scrollableVerticalPanel();
        setup.add(section("Place the atlas",
                wrappedLabel("Choose a tool or drag the atlas and its handles."),
                precisionPlacementControls(), makeAtlasUpright));
        makeAtlasUpright.setName("makeAtlasUpright");
        makeAtlasUpright.setToolTipText(
                "Remove rotation and skew while keeping the atlas centre and size. Use Atlas Undo to restore.");
        sectionMode.setToolTipText(
                "Full: both sides. Half: the confirmed side, with an optional supported remnant. Disjoined: two independent atlas halves.");
        setup.add(section("Section and orientation",
                new JLabel("Section type"), sectionMode,
                compactRow(orientationDirect, orientationReflected),
                new JLabel("Active atlas side"),
                compactRow(activeAtlasLeft, activeAtlasRight),
                new JLabel("Visible side for Half"),
                compactRow(hemisphereLeft, hemisphereRight,
                        hemisphereUnsure)));
        setupAtlasBrowser = new AtlasGuideBrowser(controller.atlasHierarchy(),
                acronym -> selectTarget(acronym, false));
        setupAtlasBrowser.setName("setupAtlasGuideBrowser");
        setup.add(section("Plane",
                new JLabel("Quick anatomy guide"),
                setupGuideSearch,
                setupAtlasBrowser,
                levelLabel, levelSlider,
                tiltLabel, sagittalTiltSlider,
                horizontalTiltLabel, horizontalTiltSlider,
                button("Reset to coronal plane",
                        controller::resetAtlasPlaneTilt)));
        final JCheckBox cropDisclosure = new JCheckBox("Edit tissue crop…");
        cropDisclosure.setName("showTissueCropControls");
        cropDisclosure.setToolTipText(
                "Optional: adjust the cyan tissue boundary. This does not move the orange atlas.");
        final JPanel tissueCropControls = section("Tissue crop",
                wrappedLabel("Drag cyan boundary points to include missed tissue. Turn off Clip atlas to tissue to show the full aligned atlas without changing its placement."),
                actionGrid(resuggestBoundary, drawTissueCrop,
                        finishTissueCrop, cancelTissueCropDraw),
                tissueCropDrawStatus);
        tissueCropControls.setName("tissueCropControls");
        tissueCropControls.setVisible(false);
        cropDisclosure.addActionListener(event -> {
            if (canvas.tissueSupportTracing() && !cropDisclosure.isSelected()) {
                cropDisclosure.setSelected(true);
            }
            tissueCropControls.setVisible(cropDisclosure.isSelected());
            if (editTissueCrop.isEnabled()
                    && editTissueCrop.isSelected() != cropDisclosure.isSelected()) {
                editTissueCrop.doClick();
            }
            setup.revalidate();
            setup.repaint();
        });
        setup.add(cropDisclosure);
        setup.add(tissueCropControls);

        final JPanel placement = scrollableVerticalPanel();
        placement.add(statusSection(
                "Choose a numbered amber atlas point on the right, then its matching cyan tissue-border point on the left."));
        boundaryFitPanel = section("Coarse boundary match",
                actionGrid(suggestBoundaryFit, skipBoundaryFitStage),
                actionGrid(restartBoundaryFit, applyBoundaryFit,
                        cancelBoundaryFit),
                boundaryFitSeparateAxes,
                actionGrid(includeBoundaryFitMatch,
                        removeBoundaryFitMatch),
                boundaryFitStatus);
        placement.add(boundaryFitPanel);

        final JPanel border = scrollableVerticalPanel();
        boundaryWarpPanel = section("Dense outer-border warp",
                new JLabel("Initial editing detail per supported side"),
                boundaryControlDensity,
                includeOppositeHalfRemnant,
                actionGrid(suggestBoundaryWarpPairs,
                        skipBoundaryWarpStage),
                resuggestBoundaryWarpPairs,
                actionGrid(includeBoundaryWarpMatch,
                        removeBoundaryWarpMatch),
                new JLabel(wrappedHtml(
                        "Ignore keeps a point in the draft for later reuse; Delete removes it until Re-suggest.")),
                calculateBoundaryWarp,
                actionGrid(applyBoundaryWarp, cancelBoundaryWarp),
                boundaryWarpStatus);
        border.add(boundaryWarpPanel);

        final JPanel interior = scrollableVerticalPanel();
        interior.add(section("Interior refinement",
                new JLabel("Grid density"),
                gridControlDensity,
                actionGrid(seedAtlasRightHandles, skipInteriorStage),
                addInteriorPoint,
                wrappedLabel("Each drag updates the warp. Apply & continue keeps it and hides the points. Atlas Undo reverses edits."),
                applyInteriorStage,
                resetWarpSide,
                clearLocalWarp));
        landmarkControls = landmarkForm();
        landmarkControls.setVisible(false);
        final JButton landmarksMode = button("Landmarks", () -> {
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.LANDMARKS);
            canvas.setManualWarpEditingEnabled(true); canvas.setSingleTissuePane(false);
            landmarkControls.setVisible(true); interior.revalidate();
        });
        landmarksMode.setName("landmarksMode");
        final JButton localMode = button("Local points", () -> {
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS); canvas.setSingleTissuePane(true);
            landmarkControls.setVisible(false); interior.revalidate();
        });
        interior.add(section("Refinement mode", compactRow(localMode, landmarksMode), landmarkControls));

        final JPanel structure = scrollableVerticalPanel();
        structure.add(section("Atlas guide",
                wrappedLabel("Select one structure to display and copy"),
                guideSearch,
                new AtlasGuideBrowser(controller.atlasHierarchy(),
                        acronym -> selectTarget(acronym, false))));
        structure.add(manualRoiEditor);
        final JToggleButton legacyStructure = new JToggleButton(
                "Advanced Structure…");
        legacyStructure.setToolTipText(
                "Show legacy shared-warp Structure controls");
        final JPanel legacyStructurePanel = section(
                "Legacy shared-warp Structure",
                new JLabel("Outline vertices"),
                structureControlDensity,
                seedAtlasLeftHandles,
                clearSelectedStructure,
                compactRow(new JLabel("ROI thickness"),
                        structureRoiSizeValue),
                structureRoiSize,
                compactRow(new JLabel("Blade gap"),
                        structureBladeGapValue),
                structureBladeGap,
                calculateStructureChanges,
                actionGrid(applyStructureChanges,
                        useValidStructurePreview),
                resetHighlightedStructureUnit,
                actionGrid(cancelStructureChanges,
                        skipStructureStage),
                previewExportedRoi,
                structureAdjustmentStatus);
        legacyStructurePanel.setVisible(false);
        legacyStructure.addActionListener(event -> {
            legacyStructurePanel.setVisible(legacyStructure.isSelected());
            canvas.setManualWarpEditingEnabled(legacyStructure.isSelected());
            canvas.setStructureEditingEnabled(
                    legacyStructure.isSelected()
                            && workflowStage
                            == ReviewWorkflowStage.STRUCTURE);
            structure.revalidate();
            structure.repaint();
        });
        structure.add(legacyStructure);
        structure.add(legacyStructurePanel);

        final JPanel review = scrollableVerticalPanel();
        review.add(manualRoiEditor.compactReviewPanel(
                () -> setWorkflowStage(ReviewWorkflowStage.STRUCTURE),
                this::acceptAndOpenExport));
        review.add(section("Advanced atlas-projected export",
                refineManually,
                warnings,
                acceptance,
                errorStatus));

        suggestBoundaryFit.setToolTipText(
                "Start or resume numbered atlas matching; nearby tissue clicks snap to cyan and farther clicks stay exact");
        restartBoundaryFit.setToolTipText(
                "Discard the current numbered matches and start a fresh draft");
        skipBoundaryFitStage.setToolTipText(
                "Discard any Match draft, keep the installed Setup placement, and continue without a review revision");
        boundaryFitSeparateAxes.setToolTipText(
                "Allow independent positive width and height scaling; shear and reflection remain forbidden");
        includeBoundaryFitMatch.setToolTipText(
                "Skip or reuse the selected numbered atlas anchor");
        removeBoundaryFitMatch.setToolTipText(
                "Clear the selected tissue endpoint and match it again");
        suggestBoundaryWarpPairs.setToolTipText(
                "Prepare or resume editable outer-border points; nothing is applied automatically");
        resuggestBoundaryWarpPairs.setToolTipText(
                "Replace the current outer-border draft at the selected density; nothing is applied automatically");
        skipBoundaryWarpStage.setToolTipText(
                "Discard any Border draft, keep the installed warp, and continue without a review revision");
        calculateBoundaryWarp.setToolTipText(
                "Run one audited nonlinear solve after all draft point edits are complete");
        skipInteriorStage.setToolTipText(
                "Discard only an unfinished Interior canvas gesture, keep every installed placement and warp, and continue without a review revision");
        applyBoundaryWarp.setToolTipText(
                "Install the safe boundary-band deformation as one undoable review revision");
        includeBoundaryWarpMatch.setToolTipText(
                "Ignore or reuse the selected point without deleting it from the draft");
        removeBoundaryWarpMatch.setToolTipText(
                "Delete the selected point from this draft; Re-suggest restores the generated set");
        boundaryControlDensity.setSelectedItem(24);
        gridControlDensity.setSelectedItem(48);
        structureControlDensity.setSelectedItem(48);
        structureRoiSize.setMajorTickSpacing(50);
        structureRoiSize.setPaintTicks(true);
        structureRoiSize.setToolTipText(
                "Widen or narrow each local opposing-boundary pair from 75–300% around its fixed midpoint; this is not centre scaling");
        structureBladeGap.setMajorTickSpacing(25);
        structureBladeGap.setPaintTicks(true);
        structureBladeGap.setToolTipText(
                "Move the two recognized blades equally apart or together without changing their local thickness");
        calculateStructureChanges.setToolTipText(
                "Calculate one audited shared local-warp preview after all requested edits");
        applyStructureChanges.setToolTipText(
                "Install the calculated valid preview as one Undo revision");
        useValidStructurePreview.setToolTipText(
                "Replace the requested shape with the calculated valid positions without creating a revision");
        resetHighlightedStructureUnit.setToolTipText(
                "Reset only the locally limiting pair or unpaired dot to its amber starts");
        skipStructureStage.setToolTipText(
                "Discard the transient Structure request, keep installed geometry, and continue without a revision");
        updateBoundaryWarpButtonLabel();
        gridControlDensity.setName("gridControlDensity");
        boundaryControlDensity.setName("boundaryControlDensity");
        structureControlDensity.setName("structureControlDensity");
        structureRoiSize.setName("structureRoiSize");
        structureBladeGap.setName("structureBladeGap");
        calculateStructureChanges.setName(
                "calculateStructureAdjustmentPreview");
        applyStructureChanges.setName("applyStructureChanges");
        useValidStructurePreview.setName("useValidStructurePreviewAsDraft");
        resetHighlightedStructureUnit.setName(
                "resetHighlightedStructureUnit");
        cancelStructureChanges.setName("cancelStructureChanges");
        skipStructureStage.setName("skipStructureStage");
        previewExportedRoi.setName("previewExportedRoi");
        activeAtlasLeft.setName("activeAtlasLeft");
        activeAtlasRight.setName("activeAtlasRight");
        resuggestBoundary.setName("resuggestBoundary");
        drawTissueCrop.setName("drawTissueCropPolygon");
        finishTissueCrop.setName("finishTissueCropPolygon");
        cancelTissueCropDraw.setName("cancelTissueCropPolygon");
        drawTissueCrop.setToolTipText(
                "Click tissue-border points to draw a replacement cyan crop polygon");
        finishTissueCrop.setToolTipText(
                "Close and install the reviewer-drawn crop polygon");
        cancelTissueCropDraw.setToolTipText(
                "Discard the unfinished polygon without changing review state");
        suggestBoundaryFit.setName("suggestBoundaryFit");
        restartBoundaryFit.setName("restartBoundaryFit");
        skipBoundaryFitStage.setName("skipBoundaryFitStage");
        boundaryFitSeparateAxes.setName("boundaryFitSeparateAxes");
        applyBoundaryFit.setName("applyBoundaryFit");
        cancelBoundaryFit.setName("cancelBoundaryFit");
        includeBoundaryFitMatch.setName("toggleSelectedBoundaryFitMatch");
        suggestBoundaryWarpPairs.setName("suggestBoundaryWarpPairs");
        resuggestBoundaryWarpPairs.setName("resuggestBoundaryWarpPairs");
        skipBoundaryWarpStage.setName("skipBoundaryWarpStage");
        skipInteriorStage.setName("skipInteriorStage");
        calculateBoundaryWarp.setName("calculateBoundaryWarpPreview");
        applyBoundaryWarp.setName("applyBoundaryWarp");
        boundaryWarpStatus.setName("boundaryWarpStatus");
        cancelBoundaryWarp.setName("cancelBoundaryWarp");
        includeBoundaryWarpMatch.setName("toggleSelectedBoundaryWarpMatch");
        includeOppositeHalfRemnant.setName(
                "includeOppositeHalfRemnant");
        seedAtlasLeftHandles.setName("setStructureControls");
        clearSelectedStructure.setName("clearSelectedStructureControls");
        seedAtlasRightHandles.setName("setInteriorGridControls");
        addInteriorPoint.setName("addInteriorPoint");
        resetWarpSide.setName("resetWarpSide");
        sectionMode.setName("reviewSectionMode");
        tissueClipping.setName("displayTissueClipping");
        tissueClipping.setToolTipText(
                "Limit atlas guides and atlas-region exports to the cyan tissue boundary. "
                + "Turn off if detection misses tissue. Alignment and drawn ROIs stay fixed; Atlas Undo restores this setting.");
        editTissueCrop.setName("editTissueCrop");
        showDisplacementLines.setName("displayDisplacementLines");
        guideThickness.setName("displayGuideThickness");
        stageInspector.setName("progressiveAlignmentInspector");
        final JScrollPane setupScroll = verticalScroll(setup);
        setupScroll.setName("manualInspectorScroll");
        stageInspector.add(setupScroll, "0");
        stageInspector.add(namedVerticalScroll(placement,
                "workflowStage1Scroll"), "1");
        stageInspector.add(namedVerticalScroll(border,
                "workflowStage2Scroll"), "2");
        stageInspector.add(namedVerticalScroll(interior,
                "workflowStage3Scroll"), "3");
        stageInspector.add(namedVerticalScroll(structure,
                "workflowStage4Scroll"), "4");
        stageInspector.add(namedVerticalScroll(review,
                "workflowStage5Scroll"), "5");
        stageInspectorCards.show(stageInspector, "0");
        manualPanel = stageInspector;
        landmarkPanel = stageInspector;
        buildViewPopup();
        return stageInspector;
    }

    private JPanel canvasHost() {
        final JPanel host = new JPanel(new BorderLayout(0, 4));
        canvasToolbar = new JPanel();
        // Unlike BoxLayout, recompute width-dependent row heights on each resize.
        canvasToolbar.setLayout(new java.awt.LayoutManager() {
            public void addLayoutComponent(final String name, final Component child) { }
            public void removeLayoutComponent(final Component child) { }
            public Dimension preferredLayoutSize(final java.awt.Container parent) {
                int width = 0;
                int height = 0;
                for (final Component child : parent.getComponents()) {
                    if (child.isVisible()) {
                        final Dimension size = child.getPreferredSize();
                        width = Math.max(width, size.width);
                        height += size.height;
                    }
                }
                return new Dimension(width, height);
            }
            public Dimension minimumLayoutSize(final java.awt.Container parent) {
                return preferredLayoutSize(parent);
            }
            public void layoutContainer(final java.awt.Container parent) {
                int y = 0;
                for (final Component child : parent.getComponents()) {
                    if (child.isVisible()) {
                        final int height = child.getPreferredSize().height;
                        child.setBounds(0, y, parent.getWidth(), height);
                        y += height;
                    }
                }
            }
        });
        canvasToolbar.setName("compactToolbarStack");
        canvasToolbar.setToolTipText(
                "Mouse wheel zooms the pane under the cursor; Space-drag pans.");
        interactionToolbar = compactToolbarRow();
        interactionToolbar.setName("interactionToolbarRow");
        panTool.setName("temporaryPanTool");
        panTool.setToolTipText(
                "Pan the current view; click again to return to the active alignment tool");
        transformTool.setName("placementTransformTool");
        borderTool.setName("outerBoundaryWarpTool");
        pointsTool.setName("localWarpPointsTool");
        transformTool.setToolTipText(
                "Drag corners or side handles to resize; hold Shift to keep proportions; clear local warp before returning");
        pointsTool.setToolTipText(
                "Start local refinement and install 24 grid points per supported atlas side");
        borderTool.setToolTipText(
                "Pair the atlas exterior to the cyan tissue border by clicking or dragging endpoints");
        final JPanel stepRail = compactToolbarRow();
        stepRail.setName("alignmentStepRail");
        for (int index = 0; index < workflowStepButtons.length; index++) {
            final JButton step = workflowStepButtons[index];
            step.setName("workflowStep" + index);
            step.setMargin(new Insets(3, 7, 3, 7));
            compactMeasured(step);
            stepRail.add(step);
            if (index == ReviewWorkflowStage.MATCH.index()) {
                // Keep a programmatic/accessibility endpoint for legacy test
                // harnesses while removing Advanced Match from the normal
                // visual workflow rail.
                step.setVisible(false);
            }
        }
        canvasToolbar.add(stepRail);
        interactionToolbar.add(displayOnly(compactMeasured(panTool), "temporaryPanTool"));
        interactionToolbar.add(displayOnly(compactMeasured(button("Zoom −", canvas::zoomTissueOut)), "viewportZoomOut"));
        interactionToolbar.add(displayOnly(compactMeasured(button("Fit", this::fitCurrentWorkflowView)), "viewportFit"));
        interactionToolbar.add(displayOnly(compactMeasured(button("Zoom +", canvas::zoomTissueIn)), "viewportZoomIn"));
        final JPanel opacity = new JPanel(new BorderLayout(2, 0));
        opacity.setPreferredSize(new Dimension(132, 30));
        opacity.add(new JLabel("Overlay"), BorderLayout.WEST);
        opacity.add(overlayOpacity, BorderLayout.CENTER);
        interactionToolbar.add(displayOnly(opacity, "viewportOpacity"));
        interactionToolbar.add(compactWidth(tissueClipping,
                tissueClipping.getPreferredSize().width));
        compactMeasured(viewOptions);
        interactionToolbar.add(viewOptions);
        workflowGuide.setName("workflowGuide");
        workflowGuide.setToolTipText(
                "Open a step-by-step guide for the fast review workflow");
        interactionToolbar.add(displayOnly(compactMeasured(workflowGuide), "workflowGuide"));
        canvasToolbar.add(interactionToolbar);
        orientationDirect.setToolTipText(
                "Atlas left appears on image left (default)");
        orientationReflected.setToolTipText(
                "Atlas left appears on image right");
        hemisphereLeft.setToolTipText("Visible anatomical left hemisphere");
        hemisphereRight.setToolTipText("Visible anatomical right hemisphere");
        hemisphereUnsure.setToolTipText("Visible laterality is unsure");
        final JPanel persistent = compactToolbarRow();
        persistent.setName("persistentToolbarRow");
        toolbarUndo.setName("persistentUndo");
        toolbarRedo.setName("persistentRedo");
        toolbarReset.setName("persistentReset");
        toolbarClearWarp.setName("persistentClearWarp");
        toolbarBack.setName("persistentBack");
        toolbarNext.setName("persistentNext");
        toolbarSkip.setName("persistentStageSkip");
        toolbarAccept.setName("persistentAccept");
        showPointLabels.setName("displayPointLabels");
        showDeformationGrid.setName("displayWarpGrid");
        overlayOpacity.setName("displayOverlayOpacity");
        allSections.setName("batchAllSections");
        allSections.setToolTipText("Return to the section list. This review stays open with its current alignment and Undo history.");
        allSections.setVisible(false);
        allSections.addActionListener(event -> {
            if (returnToSectionsAction != null) returnToSectionsAction.run();
        });
        persistent.add(displayOnly(compactMeasured(allSections), "batchAllSections"));
        persistent.add(compactMeasured(toolbarBack));
        persistent.add(compactMeasured(toolbarNext));
        stagePrimary.setName("persistentStagePrimary");
        stagePrimary.addActionListener(event -> stagePrimaryAction());
        persistent.add(compactMeasured(stagePrimary));
        persistent.add(compactMeasured(toolbarSkip));
        persistent.add(compactMeasured(toolbarUndo));
        persistent.add(compactMeasured(toolbarRedo));
        persistent.add(compactMeasured(toolbarReset));
        persistent.add(compactMeasured(toolbarAccept));
        persistent.add(compactMeasured(toolbarClearWarp));
        final JPanel fixedFooter = new JPanel(new BorderLayout(0, 2));
        final JPanel states = compactToolbarRow();
        reviewStates.setName("separateReviewStates"); states.add(reviewStates);
        fixedFooter.add(states, BorderLayout.NORTH); fixedFooter.add(persistent, BorderLayout.CENTER);
        add(fixedFooter, BorderLayout.SOUTH);
        host.add(canvasToolbar, BorderLayout.NORTH);
        host.add(canvas, BorderLayout.CENTER);
        compactStatus.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        compactStatus.setPreferredSize(new Dimension(100, 24));
        final JPanel footer = new JPanel(new BorderLayout(6, 0));
        footer.add(compactStatus, BorderLayout.CENTER);
        footer.add(org.atlasalign.plugin.ui.PluginBranding.creditLabel(), BorderLayout.EAST);
        final JPanel canvasFooter = new JPanel(new BorderLayout());
        overlayLegend.setName("overlayLegend"); overlayLegend.setFont(overlayLegend.getFont().deriveFont(10f));
        overlayLegend.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        canvasFooter.add(overlayLegend, BorderLayout.NORTH); canvasFooter.add(footer, BorderLayout.SOUTH);
        host.add(canvasFooter, BorderLayout.SOUTH);
        return host;
    }

    /** Batch navigation keeps this live review and its Undo history intact. */
    public void setReturnToSectionsAction(final Runnable action) {
        returnToSectionsAction = java.util.Objects.requireNonNull(action);
        allSections.setVisible(true);
        revalidate();
        repaint();
    }

    private void buildViewPopup() {
        viewPopup.setName("reviewViewPopup");
        viewOptions.setComponentPopupMenu(viewPopup);
        viewPopup.add(advancedMatch);
        viewPopup.addSeparator();
        viewPopup.add(showBefore);
        viewPopup.add(showAfter);
        viewPopup.add(showCompare);
        viewPopup.addSeparator();
        viewPopup.add(editTissueCrop);
        viewPopup.add(outerBoundaries);
        outerBoundaries.addActionListener(event -> canvas.setOuterBoundariesOnly(outerBoundaries.isSelected()));
        viewPopup.add(showDeformationGrid);
        viewPopup.add(showPointLabels);
        viewPopup.add(showDisplacementLines);
        viewPopup.addSeparator();
        viewPopup.add(selectedGuideColor);
        viewPopup.add(dimGuideColor);
        final JPanel thickness = new JPanel(new BorderLayout(4, 0));
        thickness.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        thickness.add(new JLabel("ROI thickness"), BorderLayout.WEST);
        thickness.add(guideThickness, BorderLayout.CENTER);
        viewPopup.add(thickness);
        advancedMatch.addActionListener(event ->
                setWorkflowStage(ReviewWorkflowStage.MATCH));
    }

    private void showWorkflowGuide() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        final JTextArea steps = new JTextArea(workflowGuideText());
        steps.setEditable(false);
        steps.setLineWrap(true);
        steps.setWrapStyleWord(true);
        steps.setCaretPosition(0);
        steps.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        final JScrollPane scroll = new JScrollPane(steps);
        scroll.setPreferredSize(new Dimension(560, 500));
        scroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        JOptionPane.showMessageDialog(this,
                org.atlasalign.plugin.ui.PluginBranding.withCredit(scroll),
                "AtlasAlign Lite — step-by-step guide",
                JOptionPane.INFORMATION_MESSAGE);
    }

    static String workflowGuideText() {
        return """
                FAST REVIEW WORKFLOW

                0. Setup & Plane
                Choose Full, Half, or Disjoined; confirm orientation and side; then use the AP slider or Page Up/Page Down to find the best atlas plane. Choose Move, Rotate or Scale. Canvas arrows nudge by 1 source pixel; Shift-arrows use 10. Proportions start locked. Adjust the cyan tissue crop only when needed.

                1. Border (optional)
                Prepare border points, move all required points, and Ignore or Delete unwanted draft points. Press Calculate border preview once after editing, inspect the cyan preview, then Apply border. Skip when Setup already aligns the outer edge.

                2. Interior (optional)
                Add distributed interior points only when internal anatomy needs local adjustment. Each completed drag updates the warp. Apply & continue to ROIs keeps that result and hides the points; return to Interior to edit again. Atlas Undo reverses a drag. Skip when no adjustment is needed.

                3. Draw ROIs
                Choose an Atlas guide from the dropdown, or type a name/acronym to filter the Allen hierarchy and select a result. Use Auto and Create from guide to begin. Drag cyan points to refine the ROI. Shift-click an edge adds a point; right-click a point removes it. Shift-click or Command-click rows to select several ROIs, then Delete selected. ROI Undo restores the deletion. More drawing tools includes freehand drawing, separate pieces and Exclude area inside ROI: draw around a tear or empty space to leave it out of the export without erasing source pixels.

                4. Review & Export
                Turn on Preview export mask: selected ROIs stay bright and everything else is shown at 20%. Finished means a closed outline, not verified anatomy. Check each polygon against the tissue, then export. Every source pixel whose centre is inside an included polygon and outside its holes is exported.

                MULTIPLE SECTIONS ON A SLIDE
                Open the slide in Fiji. Draw one rectangle around each section and add each to ROI Manager (T). Use Plugins → AtlasAlign Lite → Batch / Whole-Slide Review and choose the ROI Manager markers option. All ROI Manager markers are included by default; Selected markers only is an explicit option. Each row becomes an independent review with its own atlas level and ROIs. Click All sections in any review to return to the queue; opening that row again returns to its live window with the same alignment and Undo history. The project folder keeps the queue and ROI drafts; source pixels remain unchanged. Markers select whole sections, not anatomical regions. A downloaded example may include a section_markers.zip to load in ROI Manager → More → Open.

                ADVANCED MATCH
                Open View ▾ → Advanced: numbered Match anchors only when Setup needs extra coarse-placement anchors. Normal Next navigation intentionally bypasses this stage.

                IMPORTANT
                Atlas lines are guides. Once copied, an editable polygon is the export mask and remains fixed when the plane or alignment changes. Atlas Accept/Undo/Redo apply to alignment only; ROI Undo/Redo apply to polygons. Export uses untouched original source pixels.
                """;
    }

    private void fitCurrentWorkflowView() {
        if (workflowStage == ReviewWorkflowStage.MATCH) {
            canvas.fitBothViews();
        } else {
            canvas.fitTissueView();
        }
    }

    private void loadDisplayPreferences() {
        final Color selected = new Color(preferences.getInt(
                "selected-guide-color",
                ReviewCanvas.SELECTED_REGION_COLOR.getRGB()), true);
        final Color dim = new Color(preferences.getInt(
                "dim-guide-color", ReviewCanvas.DIM_REGION_COLOR.getRGB()),
                true);
        final double requestedThickness = preferences.getDouble(
                "guide-thickness", 1.25);
        final double thickness = Math.max(0.5,
                Math.min(5.0, requestedThickness));
        guideThickness.setValue(thickness);
        selectedGuideColor.setBackground(selected);
        dimGuideColor.setBackground(dim);
        selectedGuideColor.setOpaque(true);
        dimGuideColor.setOpaque(true);
        canvas.setRegionStyle(selected, dim, thickness);
        canvas.setDisplacementLinesVisible(true);
    }

    private void chooseGuideColor(final boolean selected) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        final Color initial = selected ? canvas.selectedRegionColor()
                : canvas.dimRegionColor();
        final Color chosen = JColorChooser.showDialog(this,
                selected ? "Selected ROI color" : "Dim guide color",
                initial);
        if (chosen == null) {
            return;
        }
        final Color selectedColor = selected ? chosen
                : canvas.selectedRegionColor();
        final Color dimColor = selected ? canvas.dimRegionColor() : chosen;
        selectedGuideColor.setBackground(selectedColor);
        dimGuideColor.setBackground(dimColor);
        preferences.putInt("selected-guide-color", selectedColor.getRGB());
        preferences.putInt("dim-guide-color", dimColor.getRGB());
        canvas.setRegionStyle(selectedColor, dimColor,
                ((Number) guideThickness.getValue()).doubleValue());
    }

    private JPanel precisionPlacementControls() {
        precisionControls.setName("precisionPlacementControls");
        precisionControls.setLayout(new BoxLayout(precisionControls, BoxLayout.Y_AXIS));
        precisionControls.setAlignmentX(Component.LEFT_ALIGNMENT);
        final JPanel tools = compactRow();
        for (final ReviewCanvas.PlacementTool tool : ReviewCanvas.PlacementTool.values()) {
            if (tool == ReviewCanvas.PlacementTool.ALL) continue;
            final String label = tool.name().substring(0, 1) + tool.name().substring(1).toLowerCase(java.util.Locale.ROOT);
            final JButton choice = button(label, () -> {
                canvas.setInteractionTool(ReviewCanvas.InteractionTool.TRANSFORM); canvas.setPlacementTool(tool); panTool.setSelected(false);
                compactStatus.setText(label + " mode · arrow keys nudge 1 source pixel; Shift = 10");
            });
            choice.setName("placement" + label); tools.add(choice);
        }
        precisionControls.add(tools);
        lockAspectRatio.setName("lockAtlasProportions"); lockAspectRatio.addActionListener(event -> canvas.setAspectRatioLocked(lockAspectRatio.isSelected()));
        precisionControls.add(lockAspectRatio);
        final JPanel numbers = new JPanel(new java.awt.GridLayout(0, 1, 0, 4)) {
            @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }
        };
        numbers.setName("numericPlacementFields"); numbers.setAlignmentX(Component.LEFT_ALIGNMENT);
        numbers.setVisible(false);
        final JToggleButton numeric = new JToggleButton("Numeric adjustments…");
        numeric.setName("showNumericAdjustments");
        numeric.addActionListener(event -> {
            numbers.setVisible(numeric.isSelected());
            for (java.awt.Container parent = numbers; parent != null; parent = parent.getParent()) parent.invalidate();
            revalidate();
            repaint();
        });
        precisionControls.add(numeric);
        final JSpinner dx = precisionNumber(0, -1000000, 1000000, 1, "moveSourceX");
        final JSpinner dy = precisionNumber(0, -1000000, 1000000, 1, "moveSourceY");
        numbers.add(precisionRow("Δ X/Y px", dx, dy, button("Move", () -> numericEdit(() -> {
            dx.commitEdit(); dy.commitEdit(); canvas.translateSourcePixels(number(dx), number(dy));
            dx.setValue(0.0); dy.setValue(0.0);
        }))));
        final JSpinner degrees = precisionNumber(0, -360, 360, .1, "rotateDegrees");
        numbers.add(precisionRow("Δ angle °", degrees, null, button("Rotate", () -> numericEdit(() -> {
            degrees.commitEdit(); canvas.rotateDegrees(number(degrees)); degrees.setValue(0.0);
        }))));
        final JSpinner sx = precisionNumber(100, 1, 1000, 1, "scaleWidthPercent");
        final JSpinner sy = precisionNumber(100, 1, 1000, 1, "scaleHeightPercent");
        numbers.add(precisionRow("Scale %", sx, sy, button("Scale", () -> numericEdit(() -> {
            sx.commitEdit(); sy.commitEdit(); canvas.scalePercent(number(sx), number(sy)); sx.setValue(100.0); sy.setValue(100.0);
        }))));
        sy.setToolTipText("Height percentage is used only when Lock proportions is off");
        precisionControls.add(numbers);
        for (final Component control : precisionControls.getComponents()) {
            if (control instanceof JComponent child) child.setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        return precisionControls;
    }

    private static JPanel precisionRow(final String label, final JSpinner first,
            final JSpinner second, final JButton apply) {
        final JPanel row = new JPanel(new GridBagLayout());
        final GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0; c.insets = new Insets(0, 2, 0, 2); c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; row.add(new JLabel(label), c);
        c.weightx = 1; c.gridx = 1; row.add(first, c);
        c.gridx = 2; row.add(second == null ? new JPanel() : second, c);
        c.weightx = 0; c.gridx = 3; apply.setMargin(new Insets(3, 6, 3, 6)); row.add(apply, c);
        return row;
    }

    private static JSpinner precisionNumber(final double value, final double minimum, final double maximum,
            final double step, final String name) {
        final JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
        spinner.setName(name); spinner.setPreferredSize(new Dimension(68, 26)); return spinner;
    }
    private static double number(final JSpinner spinner) { return ((Number) spinner.getValue()).doubleValue(); }
    @FunctionalInterface private interface NumericEdit { void run() throws java.text.ParseException; }
    private void numericEdit(final NumericEdit edit) {
        try { edit.run(); } catch (java.text.ParseException | IllegalArgumentException error) { showError("Check adjustment", error.getMessage()); }
    }

    private JPanel landmarkForm() {
        final JPanel panel = verticalPanel();
        panel.add(wrappedLabel(
                "In Landmarks mode, click Atlas first, then the matching Tissue point. "
                + "Fits use every active pair on this exact atlas plane."));
        panel.add(wrappedLabel(
                "Similarity uses two or more pairs for rotation and uniform scale. "
                + "Global affine uses three or more non-collinear pairs to allow "
                + "unequal scale and shear. It never bends individual regions or "
                + "changes reflection, laterality, or cutting-plane tilt."));
        panel.add(buttonRow(fitLandmarks, fitLandmarksAffine,
                fitLandmarksLocalWarp, clearLocalWarp,
                button("Remove selected", this::removeLandmark),
                button("Cancel point", this::cancelAtlasPick)));
        panel.add(wrappedLabel(
                "FIT points calculate a transform. CHECK points are held out "
                + "and report a held-out residual. Designate CHECK points "
                + "before global adjustment; afterward, Undo/Reset or add a "
                + "new CHECK pair so a fitted point cannot impersonate held-out evidence."));
        panel.add(buttonRow(landmarkFitRole, landmarkCheckRole));
        panel.add(wrappedLabel(
                "Local warp bends only the copied atlas contour after global "
                + "alignment. It requires four well-spread FIT points, shows "
                + "a deformation grid, fails closed on folds/excessive "
                + "distortion, and never upgrades automatic confidence. "
                + "Changing the plane, global transform, or any point clears it."));
        panel.add(localWarpStatus);
        panel.add(landmarkCaptureStatus);
        panel.add(advancedPointEditing);

        final JPanel advanced = new JPanel(new GridBagLayout());
        final GridBagConstraints constraints =
                new GridBagConstraints();
        constraints.insets = new Insets(2, 2, 2, 2);
        constraints.fill = GridBagConstraints.HORIZONTAL;
        addField(advanced, constraints, 0, "ID", landmarkId);
        addField(advanced, constraints, 1, "Atlas X", atlasX);
        addField(advanced, constraints, 2, "Atlas Y", atlasY);
        addField(advanced, constraints, 3, "Preview X", previewX);
        addField(advanced, constraints, 4, "Preview Y", previewY);
        constraints.gridx = 0;
        constraints.gridy = 5;
        constraints.gridwidth = 2;
        advanced.add(button("Add pair", this::addLandmark), constraints);
        constraints.gridx = 2;
        advanced.add(button("Remove ID", this::removeLandmark),
                constraints);
        constraints.gridy = 6;
        constraints.gridx = 0;
        advanced.add(button(
                "Update atlas point",
                this::moveLandmarkAtlasPoint), constraints);
        constraints.gridx = 2;
        advanced.add(button(
                "Update preview point",
                this::moveLandmarkPreviewPoint), constraints);
        advancedPointForm = advanced;
        advancedPointForm.setVisible(false);
        panel.add(advancedPointForm);
        landmarkTable.setEditable(false);
        landmarkTable.setLineWrap(true);
        landmarkTable.setWrapStyleWord(true);
        landmarkTable.setText("No active landmark pairs at this level.");
        pointListDetails.setName("showPointList");
        panel.add(pointListDetails);
        landmarkTableScroll = textScroll(landmarkTable);
        landmarkTableScroll.setName("pointListScroll");
        landmarkTableScroll.setVisible(false);
        panel.add(landmarkTableScroll);
        return panel;
    }

    private void wireActions() {
        levelSlider.addChangeListener(this::levelChanged);
        sagittalTiltSlider.addChangeListener(this::sagittalTiltChanged);
        horizontalTiltSlider.addChangeListener(this::horizontalTiltChanged);
        showAtlasAnatomy.addActionListener(event -> {
            if (!updating) {
                controller.setShowAtlasAnatomy(
                        showAtlasAnatomy.isSelected());
            }
        });
        orientationUnconfirmed.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                setOrientation(
                        AtlasOrientation
                                .UNCONFIRMED_PROVISIONAL_DIRECT);
            }
        });
        orientationDirect.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                setOrientation(
                        AtlasOrientation
                                .CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT);
            }
        });
        orientationReflected.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                setOrientation(
                        AtlasOrientation
                                .CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT);
            }
        });
        hemisphereLeft.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                setHemisphere(ObservedAnatomicalHemisphere.LEFT);
            }
        });
        hemisphereRight.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                setHemisphere(ObservedAnatomicalHemisphere.RIGHT);
            }
        });
        hemisphereUnsure.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED) {
                setHemisphere(ObservedAnatomicalHemisphere.UNSURE);
            }
        });
        undo.addActionListener(event -> controller.undo());
        redo.addActionListener(event -> controller.redo());
        reset.addActionListener(event -> controller.reset());
        toolbarUndo.addActionListener(event -> controller.undo());
        toolbarRedo.addActionListener(event -> controller.redo());
        toolbarReset.addActionListener(event -> controller.reset());
        makeAtlasUpright.addActionListener(event -> {
            try {
                controller.makeAtlasUpright();
            } catch (final RuntimeException error) {
                showError("Atlas not straightened", messageOf(error));
            }
        });
        toolbarClearWarp.addActionListener(event -> clearLocalWarp());
        toolbarBack.addActionListener(event -> {
            if (exportInspectorVisible) {
                showAlignmentInspector();
                setWorkflowStage(ReviewWorkflowStage.ACCEPT_EXPORT);
                return;
            }
            setWorkflowStage(workflowStage.previous());
        });
        toolbarNext.addActionListener(event ->
                setWorkflowStage(workflowStage.next()));
        toolbarSkip.addActionListener(event -> skipCurrentWorkflowStage());
        for (int index = 0; index < workflowStepButtons.length; index++) {
            final ReviewWorkflowStage stage = ReviewWorkflowStage.at(index);
            workflowStepButtons[index].addActionListener(event ->
                    setWorkflowStage(stage));
        }
        toolbarAccept.addActionListener(event -> acceptAndOpenExport());
        workflowGuide.addActionListener(event -> showWorkflowGuide());
        viewOptions.addActionListener(event -> viewPopup.show(
                viewOptions, 0, viewOptions.getHeight()));
        suggestBoundaryFit.addActionListener(event -> {
            if (latestModel != null && latestModel.boundaryFit().active()) {
                resumeBoundaryFitInteraction();
            } else {
                startBoundaryFitDraft();
            }
        });
        restartBoundaryFit.addActionListener(event ->
                startBoundaryFitDraft());
        skipBoundaryFitStage.addActionListener(event ->
                skipBoundaryFitStage());
        boundaryFitSeparateAxes.addActionListener(event -> {
            if (!updating && latestModel != null
                    && latestModel.boundaryFit().draft().isPresent()) {
                runCanvasEdit("Fit model not changed", () ->
                        controller.setBoundaryFitModel(
                                boundaryFitSeparateAxes.isSelected()
                                        ? BoundaryFitModel.ORTHOGONAL_XY
                                        : BoundaryFitModel.SIMILARITY));
            }
        });
        applyBoundaryFit.addActionListener(event ->
                runCanvasEdit("Border fit not applied",
                        controller::applyBoundaryFit));
        cancelBoundaryFit.addActionListener(event ->
                controller.cancelBoundaryFit());
        includeBoundaryFitMatch.addActionListener(event -> {
            final BoundaryFitAnchor anchor = selectedBoundaryFitAnchor();
            if (anchor != null) {
                runCanvasEdit("Boundary match not changed", () ->
                        controller.setBoundaryFitAnchorIncluded(
                                anchor.id(), !anchor.included()));
            }
        });
        removeBoundaryFitMatch.addActionListener(event -> {
            final BoundaryFitAnchor anchor = selectedBoundaryFitAnchor();
            if (anchor != null) {
                runCanvasEdit("Boundary match not cleared", () ->
                        controller.clearBoundaryFitAnchor(anchor.id()));
            }
        });
        suggestBoundaryWarpPairs.addActionListener(event -> {
            if (latestModel != null && latestModel.boundaryWarp().active()) {
                resumeBoundaryWarpInteraction();
            } else {
                startBoundaryWarpDraft();
            }
        });
        resuggestBoundaryWarpPairs.addActionListener(event ->
                startBoundaryWarpDraft());
        skipBoundaryWarpStage.addActionListener(event ->
                skipBoundaryWarpStage());
        applyInteriorStage.setName("applyInteriorStage");
        applyInteriorStage.addActionListener(event -> {
            canvas.cancelTransientGesture();
            setWorkflowStage(ReviewWorkflowStage.STRUCTURE);
        });
        skipInteriorStage.addActionListener(event ->
                skipInteriorStage());
        includeOppositeHalfRemnant.addActionListener(event -> {
            if (!updating && latestModel != null
                    && latestModel.reviewState().content()
                            .reviewSectionMode() == ReviewSectionMode.HALF) {
                final boolean restartDraft = latestModel.boundaryWarp()
                        .active();
                runCanvasEdit("Half remnant not changed", () ->
                {
                    controller.setHalfAtlasCoverage(
                                includeOppositeHalfRemnant.isSelected()
                                        ? HalfAtlasCoverage
                                                .INCLUDE_OPPOSITE_REMNANT
                                        : HalfAtlasCoverage
                                                .VISIBLE_SIDE_ONLY);
                    if (restartDraft) {
                        controller.startBoundaryWarp(activeWarpSide(),
                                selectedBoundaryDensity());
                    }
                });
            }
        });
        calculateBoundaryWarp.addActionListener(event ->
                runCanvasEdit("Border preview not calculated",
                        controller::calculateBoundaryWarpPreview));
        applyBoundaryWarp.addActionListener(event ->
                runCanvasEdit("Border warp not applied",
                        controller::applyBoundaryWarp));
        cancelBoundaryWarp.addActionListener(event ->
                controller.cancelBoundaryWarp());
        includeBoundaryWarpMatch.addActionListener(event -> {
            final BoundaryFitMatch match = selectedBoundaryWarpMatch();
            if (match != null) {
                runCanvasEdit("Border pair not changed", () ->
                        controller.setBoundaryWarpMatchIncluded(
                                match.id(), !match.included()));
            }
        });
        removeBoundaryWarpMatch.addActionListener(event -> {
            final BoundaryFitMatch match = selectedBoundaryWarpMatch();
            if (match != null) {
                runCanvasEdit("Border pair not removed", () ->
                        controller.removeBoundaryWarpMatch(match.id()));
            }
        });
        showPointLabels.addActionListener(event -> canvas
                .setLandmarkLabelsVisible(showPointLabels.isSelected()));
        showDeformationGrid.addActionListener(event -> canvas
                .setDeformationGridVisible(
                        showDeformationGrid.isSelected()));
        showDisplacementLines.addActionListener(event -> canvas
                .setDisplacementLinesVisible(
                        showDisplacementLines.isSelected()));
        tissueClipping.addActionListener(event -> {
            if (!updating) {
                try {
                    controller.setTissueClippingEnabled(
                            tissueClipping.isSelected());
                } catch (final RuntimeException error) {
                    tissueClipping.setSelected(latestModel.reviewState()
                            .content().tissueClippingEnabled());
                    showError("Tissue clipping not changed", messageOf(error));
                }
            }
        });
        editTissueCrop.addActionListener(event -> {
            canvas.setTissueSupportEditing(editTissueCrop.isSelected());
            if (editTissueCrop.isSelected()) {
                activatePointsTool();
                compactStatus.setText(
                        "Edit crop — drag nodes; Shift-click an edge to insert; Option-click a node to delete");
            }
        });
        selectedGuideColor.addActionListener(event ->
                chooseGuideColor(true));
        dimGuideColor.addActionListener(event ->
                chooseGuideColor(false));
        guideThickness.addChangeListener(event -> {
            final double value = ((Number) guideThickness.getValue())
                    .doubleValue();
            preferences.putDouble("guide-thickness", value);
            canvas.setRegionStyle(canvas.selectedRegionColor(),
                    canvas.dimRegionColor(), value);
        });
        overlayOpacity.addChangeListener(event -> canvas.setOverlayOpacity(
                overlayOpacity.getValue() / 100.0));
        pointListDetails.addActionListener(event -> {
            landmarkTableScroll.setVisible(pointListDetails.isSelected());
            pointListDetails.setText(pointListDetails.isSelected()
                    ? "Hide point list" : "Show point list…");
            revalidate();
        });
        structureControlDensity.addActionListener(event ->
                updateHandleButtonLabels());
        structureRoiSize.addChangeListener(event -> {
            structureRoiSizeValue.setText(
                    structureRoiSize.getValue() + "%");
            if (!updating) {
                updateStructureSliders();
            }
        });
        structureBladeGap.addChangeListener(event -> {
            final int value = structureBladeGap.getValue();
            structureBladeGapValue.setText((value > 0 ? "+" : "")
                    + value + "%");
            if (!updating) {
                updateStructureSliders();
            }
        });
        gridControlDensity.addActionListener(event ->
                updateHandleButtonLabels());
        boundaryControlDensity.addActionListener(event ->
                updateBoundaryWarpButtonLabel());
        sectionMode.addActionListener(event -> commitSectionModeSelection(
                sectionMode.getSelectedItem()));
        final KeyAdapter sectionModeEscapeTracker = new KeyAdapter() {
            @Override
            public void keyPressed(final KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    sectionModePopupEscapePressed = true;
                }
            }
        };
        sectionMode.addKeyListener(sectionModeEscapeTracker);
        sectionModePopupList().ifPresent(list ->
                list.addKeyListener(sectionModeEscapeTracker));
        sectionMode.addPopupMenuListener(new PopupMenuListener() {
            private boolean cancelled;

            @Override
            public void popupMenuWillBecomeVisible(
                    final PopupMenuEvent event) {
                cancelled = false;
                sectionModePopupEscapePressed = false;
                retainedSectionModePopupSelection = null;
            }

            @Override
            public void popupMenuWillBecomeInvisible(
                    final PopupMenuEvent event) {
                if (cancelled && sectionModePopupEscapePressed) {
                    restoreCurrentSectionModeSelection();
                    retainedSectionModePopupSelection = null;
                    return;
                }
                if (retainedSectionModePopupSelection == null) {
                    final Object popupSelection =
                            popupSectionModeSelection();
                    if (popupSelection instanceof ReviewSectionMode mode) {
                        retainedSectionModePopupSelection = mode;
                    }
                }
                commitVisuallyRetainedSectionModeSelection();
            }

            @Override
            public void popupMenuCanceled(final PopupMenuEvent event) {
                cancelled = true;
                if (!sectionModePopupEscapePressed) {
                    final Object popupSelection = popupSectionModeSelection();
                    if (popupSelection instanceof ReviewSectionMode mode) {
                        retainedSectionModePopupSelection = mode;
                    }
                }
            }
        });
        setupGuideSearch.addActionListener(event -> {
            if (!updating) {
                selectGuideSearchValue(setupGuideSearch, false,
                        event.getActionCommand());
            }
        });
        setupGuideSearch.addItemListener(event -> {
            synchronizeGuideEditorSelection(setupGuideSearch, event);
            commitGuideItemSelection(setupGuideSearch, event, false);
        });
        installGuideModelCommit(setupGuideSearch, false);
        installGuidePopupCommit(setupGuideSearch, false);
        selectSetupOtherGuide.addActionListener(event ->
                selectGuideQuery(setupOtherGuideQuery.getText(), false));
        setupOtherGuideQuery.addActionListener(event ->
                selectGuideQuery(setupOtherGuideQuery.getText(), false));
        guideSearch.addActionListener(event -> {
            if (!updating) {
                selectGuideSearchValue(guideSearch, true,
                        event.getActionCommand());
            }
        });
        guideSearch.addItemListener(event -> {
            synchronizeGuideEditorSelection(guideSearch, event);
            commitGuideItemSelection(guideSearch, event, true);
        });
        installGuideModelCommit(guideSearch, true);
        installGuidePopupCommit(guideSearch, true);
        fitLandmarks.addActionListener(event -> fitActiveLandmarks());
        fitLandmarksToolbar.addActionListener(event -> fitActiveLandmarks());
        fitLandmarksAffine.addActionListener(event -> fitActiveLandmarksAffine());
        fitLandmarksLocalWarp.addActionListener(
                event -> fitActiveLandmarksLocalWarp());
        fitLocalWarpToolbar.addActionListener(
                event -> fitActiveLandmarksLocalWarp());
        clearLocalWarp.addActionListener(event -> clearLocalWarp());
        activeAtlasLeft.addActionListener(event -> {
            if (!updating && activeAtlasLeft.isSelected()) {
                activateAtlasSide(
                        ManualHemisphereWarp2D.AtlasSide.LEFT);
            }
        });
        activeAtlasRight.addActionListener(event -> {
            if (!updating && activeAtlasRight.isSelected()) {
                activateAtlasSide(
                        ManualHemisphereWarp2D.AtlasSide.RIGHT);
            }
        });
        seedAtlasLeftHandles.addActionListener(event ->
                replaceSelectedStructureControls());
        clearSelectedStructure.addActionListener(event ->
                clearSelectedStructureControls());
        calculateStructureChanges.addActionListener(event ->
                runCanvasEdit("Structure preview not calculated",
                        controller::calculateStructureAdjustmentPreview));
        applyStructureChanges.addActionListener(event ->
                runCanvasEdit("Structure changes not applied",
                        controller::applyStructureChanges));
        useValidStructurePreview.addActionListener(event ->
                runCanvasEdit("Valid Structure preview not adopted",
                        controller::useValidStructurePreviewAsDraft));
        resetHighlightedStructureUnit.addActionListener(event ->
                runCanvasEdit("Highlighted Structure unit not reset",
                        controller::resetHighlightedStructureUnit));
        cancelStructureChanges.addActionListener(event ->
                controller.cancelStructureChanges());
        skipStructureStage.addActionListener(event ->
                skipStructureStage());
        previewExportedRoi.addActionListener(event -> canvas
                .setExportedRoiPreviewVisible(
                        previewExportedRoi.isSelected()));
        seedAtlasRightHandles.addActionListener(event ->
                replaceInteriorGridControls());
        resuggestBoundary.addActionListener(event ->
                resuggestBoundaryControls());
        drawTissueCrop.addActionListener(event ->
                startTissueCropDrawing());
        finishTissueCrop.addActionListener(event ->
                finishTissueCropDrawing());
        cancelTissueCropDraw.addActionListener(event ->
                cancelTissueCropDrawing());
        addInteriorPoint.addActionListener(event -> armInteriorPoint());
        resetWarpSide.addActionListener(event -> resetManualWarpSide());
        targetNone.addItemListener(event -> {
            if (!updating && event.getStateChange() == ItemEvent.SELECTED) {
                controller.clearSelectedAtlasRegion();
                activatePointsTool();
            }
        });
        targetDg.addItemListener(event -> {
            if (!updating && event.getStateChange() == ItemEvent.SELECTED) {
                selectTarget("DG");
            }
        });
        targetDgSg.addItemListener(event -> {
            if (!updating && event.getStateChange() == ItemEvent.SELECTED) {
                selectTarget("DG-sg");
            }
        });
        targetHpf.addItemListener(event -> {
            if (!updating && event.getStateChange() == ItemEvent.SELECTED) {
                selectTarget("HPF");
            }
        });
        targetVs.addItemListener(event -> {
            if (!updating && event.getStateChange() == ItemEvent.SELECTED) {
                selectTarget("VS");
            }
        });
        targetCc.addItemListener(event -> {
            if (!updating && event.getStateChange() == ItemEvent.SELECTED) {
                selectTarget("cc");
            }
        });
        selectOtherGuide.addActionListener(event ->
                selectGuideQuery(otherGuideAcronym.getText(), true));
        otherGuideAcronym.addActionListener(event ->
                selectGuideQuery(otherGuideAcronym.getText(), true));
        showBefore.addActionListener(event -> canvas.setComparisonMode(
                ReviewCanvas.ComparisonMode.BEFORE));
        showAfter.addActionListener(event -> canvas.setComparisonMode(
                ReviewCanvas.ComparisonMode.AFTER));
        showCompare.addActionListener(event -> canvas.setComparisonMode(
                ReviewCanvas.ComparisonMode.COMPARE));
        landmarkFitRole.addItemListener(event -> {
            if (!updating && event.getStateChange() == ItemEvent.SELECTED) {
                setSelectedLandmarkRole(LandmarkRole.FIT);
            }
        });
        landmarkCheckRole.addItemListener(event -> {
            if (!updating && event.getStateChange() == ItemEvent.SELECTED) {
                setSelectedLandmarkRole(LandmarkRole.CHECK);
            }
        });
        advancedPointEditing.addActionListener(event -> {
            advancedPointForm.setVisible(advancedPointEditing.isSelected());
            advancedPointEditing.setText(advancedPointEditing.isSelected()
                    ? "Hide advanced point editing"
                    : "Advanced point editing…");
            revalidate();
        });
        automaticDetails.addActionListener(event -> {
            automaticProvenanceScroll.setVisible(
                    automaticDetails.isSelected());
            automaticDetails.setText(automaticDetails.isSelected()
                    ? "Hide technical provenance"
                    : "Technical provenance…");
            revalidate();
        });
        transformTool.addActionListener(event -> {
            panTool.setSelected(false);
            cancelBoundaryFitIfActive();
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.TRANSFORM);
            updateBoundaryFitPanelVisibility();
        });
        borderTool.addActionListener(event -> {
            panTool.setSelected(false);
            if (latestModel != null && latestModel.boundaryFit().active()) {
                controller.cancelBoundaryFit();
            }
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.BORDER);
            updateBoundaryFitPanelVisibility();
            if (latestModel != null
                    && !latestModel.boundaryWarp().active()) {
                runCanvasEdit("Border tool not started", () ->
                        controller.startBoundaryWarp(activeWarpSide(),
                                selectedBoundaryDensity(),
                                includeOppositeHalfRemnant.isSelected()));
            }
        });
        pointsTool.addActionListener(event -> {
            panTool.setSelected(false);
            cancelBoundaryFitIfActive();
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            updateBoundaryFitPanelVisibility();
            try {
                if (controller.startLocalWarpWithDefaultGridIfNeeded(
                        includeOppositeHalfRemnant.isSelected())) {
                    compactStatus.setText(
                            "Preparing 24 points per supported side…");
                }
            } catch (final RuntimeException error) {
                showError("Local warp not started", messageOf(error));
            }
        });
        panTool.addActionListener(event -> {
            if (panTool.isSelected()) {
                canvas.setInteractionTool(ReviewCanvas.InteractionTool.PAN);
            } else {
                restoreWorkflowInteractionToolAfterPan();
            }
            updateBoundaryFitPanelVisibility();
        });
        warnings.addActionListener(event -> {
            if (!updating) {
                controller.setWarningsAcknowledged(
                        warnings.isSelected());
            }
        });
        accept.addActionListener(event -> acceptAndOpenExport());
        revoke.addActionListener(
                event -> controller.revokeAcceptance());
        refineManually.addActionListener(event -> controller.enterManualRefinement());
        guidedManualWizard.addActionListener(
                event -> openGuidedManualWizard());
    }

    private Object popupSectionModeSelection() {
        final Optional<JList<?>> popupList = sectionModePopupList();
        if (popupList.isPresent()
                && popupList.orElseThrow().getSelectedValue() != null) {
            return popupList.orElseThrow().getSelectedValue();
        }
        return sectionMode.getSelectedItem();
    }

    private Optional<JList<?>> sectionModePopupList() {
        final Object accessiblePopup = sectionMode.getUI()
                .getAccessibleChild(sectionMode, 0);
        if (accessiblePopup instanceof ComboPopup popup) {
            return Optional.of(popup.getList());
        }
        return Optional.empty();
    }

    private void commitSectionModeSelection(final Object selection) {
        if (updating || !(selection instanceof ReviewSectionMode mode)) {
            return;
        }
        try {
            updating = true;
            try {
                sectionMode.setSelectedItem(mode);
            } finally {
                updating = false;
            }
            controller.setReviewSectionMode(mode);
            lastRenderedSectionMode = mode;
            retainedSectionModePopupSelection = null;
            transformTool.setSelected(true);
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.TRANSFORM);
            compactStatus.setText(
                    "Place the atlas first — drag its body or resize handles; Shift keeps proportions; choose Points when ready");
        } catch (final RuntimeException error) {
            updating = true;
            try {
                sectionMode.setSelectedItem(latestModel.reviewState()
                        .content().reviewSectionMode());
            } finally {
                updating = false;
            }
            showError("Section mode not changed", messageOf(error));
        }
    }

    private void restoreCurrentSectionModeSelection() {
        final ReviewSectionMode current = controller.state().content()
                .reviewSectionMode();
        updating = true;
        try {
            sectionMode.setSelectedItem(current);
        } finally {
            updating = false;
        }
        lastRenderedSectionMode = current;
    }

    /**
     * Some accessibility clients close a combo popup through its disclosure
     * button. Swing reports that close as a popup cancellation even though the
     * combo box visibly retains the chosen item. Keep the review content and
     * the visible selection atomic; a real Escape cancellation retains the
     * previously rendered item and therefore remains a no-op.
     *
     * @return {@code true} when a visible/model mismatch was found
     */
    private boolean commitVisuallyRetainedSectionModeSelection() {
        if (updating || latestModel == null) {
            return false;
        }
        final Object selection = retainedSectionModePopupSelection != null
                ? retainedSectionModePopupSelection
                : sectionMode.getSelectedItem();
        if (!(selection instanceof ReviewSectionMode selected)) {
            return false;
        }
        final ReviewSectionMode current = controller.state().content()
                .reviewSectionMode();
        if (selected == current) {
            if (sectionMode.getSelectedItem() != selected) {
                restoreCurrentSectionModeSelection();
            }
            retainedSectionModePopupSelection = null;
            return false;
        }
        commitSectionModeSelection(selected);
        return true;
    }

    private void openGuidedManualWizard() {
        if (latestModel == null) {
            showError("Guided manual alignment",
                    "The review image is not ready yet.");
            return;
        }
        if (!latestModel.reviewState().content().workflowMode()
                .permitsManualEdits()) {
            showError("Guided manual alignment",
                    "Enter manual refinement before opening the guided workflow.");
            return;
        }
        if (guidedManualPanel == null) {
            guidedManualPanel = new GuidedManualWizardPanel(
                    controller, latestModel);
            guidedManualPanel.setCloseHandler(this::showReviewWorkflow);
            guidedManualPanel.setWorkflowAppliedHandler(() -> {
                showReviewWorkflow();
                openFineAnatomyWarpAfterGuidedCandidate();
            });
            workflowSurface.add(guidedManualPanel, "guided");
        } else {
            guidedManualPanel.updateModel(latestModel);
        }
        workflowCards.show(workflowSurface, "guided");
        workflowSurface.revalidate();
        workflowSurface.repaint();
    }

    private void showReviewWorkflow() {
        workflowCards.show(workflowSurface, "review");
        workflowSurface.revalidate();
        workflowSurface.repaint();
    }

    private void acceptAndOpenExport() {
        if (discardUnfinishedCropTrace()) {
            return;
        }
        if (commitVisuallyRetainedSectionModeSelection()) {
            compactStatus.setText(
                    "Section type applied — recheck alignment before accepting");
            compactStatus.setToolTipText(
                    "The visible section type had not reached the review content. It is now applied; recheck the alignment before Accept.");
            return;
        }
        final Optional<org.atlasalign.application.AcceptedAlignmentSnapshot>
                existing = controller.acceptedAlignment();
        if (existing.isPresent()) {
            showExportInspector(existing.orElseThrow());
            return;
        }
        controller.accept().ifPresent(this::showExportInspector);
    }

    private void showExportInspector(
            final org.atlasalign.application.AcceptedAlignmentSnapshot
                    acceptedSnapshot) {
        if (exportPanel == null) {
            return;
        }
        exportPanel.setAcceptedAlignment(acceptedSnapshot);
        exportInspectorVisible = true;
        inspectorCards.show(inspectorSurface, "export");
        inspectorSurface.revalidate();
        inspectorSurface.repaint();
    }

    private void showAlignmentInspector() {
        exportInspectorVisible = false;
        inspectorCards.show(inspectorSurface, "alignment");
        inspectorSurface.revalidate();
        inspectorSurface.repaint();
    }

    boolean guidedManualWorkflowVisible() {
        return guidedManualPanel != null && guidedManualPanel.isVisible();
    }

    void openFineAnatomyWarpAfterGuidedCandidate() {
        activatePointsTool();
        setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                "Reviewer-chosen plane loaded. Choose a structure, set N, "
                + "select the active atlas side, and drag its hollow tissue "
                + "endpoints. Each side is refined independently; no candidate "
                + "score or automatic confidence was created."));
    }

    private void planeSliderChanged(final JSlider slider) {
        if (updating) return;
        if (!slider.getValueIsAdjusting()) {
            planeSliderTimer.stop();
            flushPlaneSliders();
        } else if (!planeSliderTimer.isRunning()) {
            planeSliderTimer.setRepeats(false);
            planeSliderTimer.start();
        }
    }

    private void flushPlaneSliders() {
        // Snapshot every thumb before a controller publication updates the panel.
        final int level = levelSlider.getValue();
        final int sagittal = sagittalTiltSlider.getValue();
        final int horizontal = horizontalTiltSlider.getValue();
        controller.setCoronalLevel(level);
        controller.setSagittalTiltDegrees(sagittal);
        controller.setHorizontalTiltDegrees(horizontal);
    }

    private void levelChanged(final ChangeEvent ignored) {
        planeSliderChanged(levelSlider);

    }

    /** Package-visible target for the active-window Left/Right dispatcher. */
    void nudgeCoronalLevel(final int delta) {
        if (displayGeometryBlocked) return;
        if (delta == 0 || !levelSlider.isEnabled()) {
            return;
        }
        levelSlider.setValue(Math.max(levelSlider.getMinimum(),
                Math.min(levelSlider.getMaximum(),
                        levelSlider.getValue() + delta)));
    }

    private void sagittalTiltChanged(final ChangeEvent ignored) {
        planeSliderChanged(sagittalTiltSlider);
    }

    private void horizontalTiltChanged(final ChangeEvent ignored) {
        planeSliderChanged(horizontalTiltSlider);
    }

    private void adjustSagittalTilt(final int delta) {
        controller.setSagittalTiltDegrees(Math.max(
                sagittalTiltSlider.getMinimum(), Math.min(
                        sagittalTiltSlider.getMaximum(),
                        sagittalTiltSlider.getValue() + delta)));
    }

    private void adjustHorizontalTilt(final int delta) {
        controller.setHorizontalTiltDegrees(Math.max(
                horizontalTiltSlider.getMinimum(), Math.min(
                        horizontalTiltSlider.getMaximum(),
                        horizontalTiltSlider.getValue() + delta)));
    }

    private void addLandmark() {
        try {
            controller.addLandmark(
                    landmarkId.getText(),
                    new Point2D(
                            number(atlasX), number(atlasY)),
                    new Point2D(
                            number(previewX), number(previewY)));
        } catch (final RuntimeException error) {
            showError("Landmark not added", messageOf(error));
        }
    }

    private void removeLandmark() {
        try {
            controller.removeLandmark(landmarkId.getText());
        } catch (final RuntimeException error) {
            showError("Landmark not removed", messageOf(error));
        }
    }

    private void moveLandmarkAtlasPoint() {
        try {
            controller.moveLandmarkAtlasPoint(
                    landmarkId.getText(),
                    new Point2D(number(atlasX), number(atlasY)));
        } catch (final RuntimeException error) {
            showError("Landmark not updated", messageOf(error));
        }
    }

    private void moveLandmarkPreviewPoint() {
        try {
            moveTissueEndpoint(
                    landmarkId.getText().trim(),
                    new Point2D(
                            number(previewX), number(previewY)));
        } catch (final RuntimeException error) {
            showError("Landmark not updated", messageOf(error));
        }
    }

    private void moveTissueEndpoint(
            final String identifier,
            final Point2D point) {
        try {
            modelLandmark(identifier).orElseThrow(
                    () -> new IllegalArgumentException(
                            "Active landmark not found: " + identifier));
            controller.moveLandmarkPreviewPoint(identifier, point);
        } catch (final RuntimeException error) {
            showError("Landmark not updated", messageOf(error));
        }
    }

    private boolean addClickedLandmark(
            final Point2D atlasPoint,
            final Point2D previewPoint) {
        try {
            final String identifier = controller.addClickedLandmark(
                    atlasPoint, previewPoint);
            landmarkId.setText(identifier);
            atlasX.setText(numberText(atlasPoint.x()));
            atlasY.setText(numberText(atlasPoint.y()));
            previewX.setText(numberText(previewPoint.x()));
            previewY.setText(numberText(previewPoint.y()));
            updating = true;
            try {
                landmarkFitRole.setSelected(true);
            } finally {
                updating = false;
            }
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "Added " + identifier
                    + " as FIT. Add another pair, or mark selected points "
                    + "as CHECK before fitting."));
            return true;
        } catch (final RuntimeException error) {
            showError("Landmark not added", messageOf(error));
            return false;
        }
    }

    private void fitActiveLandmarks() {
        try {
            controller.fitActiveLandmarks();
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "MANUAL_LANDMARKS similarity fit applied; use Undo to revert."));
        } catch (final RuntimeException error) {
            showError("Landmark fit not applied", messageOf(error));
        }
    }

    private void fitActiveLandmarksAffine() {
        try {
            controller.fitActiveLandmarksAffine();
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "MANUAL_LANDMARKS global affine fit applied; use Undo to revert."));
        } catch (final RuntimeException error) {
            showError("Global affine fit not applied", messageOf(error));
        }
    }

    private void fitActiveLandmarksLocalWarp() {
        try {
            controller.fitActiveLandmarksLocalWarp();
            pointsTool.setSelected(true);
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "MANUAL_LOCAL_WARP applied from FIT points. The cyan grid "
                    + "shows atlas deformation; CHECK residuals remain held out. "
                    + "Use Undo or Clear local warp to revert."));
        } catch (final RuntimeException error) {
            showError("Local warp not applied", messageOf(error));
        }
    }

    private void clearLocalWarp() {
        try {
            controller.clearLocalWarp();
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "Reviewer-controlled local/hemisphere warp cleared; "
                    + "global alignment retained."));
        } catch (final RuntimeException error) {
            showError("Local warp not cleared", messageOf(error));
        }
    }

    private void selectTarget(final String acronym) {
        selectTarget(acronym, true);
    }

    private void selectTarget(
            final String acronym,
            final boolean activatePoints) {
        try {
            controller.selectAtlasRegionExactAcronym(acronym);
            if (activatePoints) {
                activatePointsTool();
            }
        } catch (final RuntimeException error) {
            showError("Atlas target not selected", messageOf(error));
            updating = true;
            try {
                targetNone.setSelected(true);
            } finally {
                updating = false;
            }
        }
    }

    private void selectGuideSearchValue(
            final JComboBox<String> search,
            final boolean activatePoints,
            final String actionCommand) {
        /*
         * Editable combo boxes emit "comboBoxChanged" for a popup choice and
         * "comboBoxEdited" for text committed by the editor.  On native
         * macOS Swing, the popup selection can arrive before the editor has
         * copied the selected value.  Reading the editor unconditionally
         * therefore replays the old "All structures" value and silently
         * clears the structure target.  Preserve typed search while making a
         * popup choice authoritative for its own event.
         */
        final boolean popupChoice = search.isPopupVisible()
                || "comboBoxChanged".equals(actionCommand);
        final Object item = search.isEditable() && !popupChoice
                ? search.getEditor().getItem()
                : search.getSelectedItem();
        if (search.isEditable() && popupChoice && item != null) {
            final boolean previousUpdating = updating;
            updating = true;
            try {
                search.getEditor().setItem(item);
            } finally {
                updating = previousUpdating;
            }
        }
        final String requested = item == null ? ""
                : item.toString().trim();
        selectGuideQuery(requested, activatePoints);
    }

    private void selectGuideQuery(
            final String query,
            final boolean activatePoints) {
        final String requested = Objects.requireNonNull(query, "query")
                .trim();
        if (requested.isEmpty()) {
            return;
        }
        if (requested.equalsIgnoreCase("All structures")) {
            controller.clearSelectedAtlasRegion();
            if (activatePoints) {
                activatePointsTool();
            }
            return;
        }
        final int separator = requested.indexOf(" — ");
        final String acronym = separator > 0
                ? requested.substring(0, separator).trim() : requested;
        if (controller.resolveExactAtlasRegion(acronym).isPresent()) {
            selectTarget(acronym, activatePoints);
            return;
        }
        final java.util.List<SelectedAtlasRegion> matches =
                controller.searchAtlasRegions(requested, 20);
        if (matches.isEmpty()) {
            showError("Atlas guide not found",
                    "No atlas structure matched that search. Try an acronym such as DG-sg or part of a structure name.");
            return;
        }
        final boolean previousUpdating = updating;
        updating = true;
        try {
            for (final JComboBox<String> combo : java.util.List.of(
                    setupGuideSearch, guideSearch)) {
                combo.removeAllItems();
                combo.addItem("All structures");
                for (final SelectedAtlasRegion match : matches) {
                    combo.addItem(match.acronym() + " — " + match.name());
                }
                combo.setSelectedIndex(1);
            }
        } finally {
            updating = previousUpdating;
        }
        selectTarget(matches.get(0).acronym(), activatePoints);
    }

    private void synchronizeGuideEditorSelection(
            final JComboBox<String> search,
            final ItemEvent event) {
        if (updating || !search.isEditable()
                || event.getStateChange() != ItemEvent.SELECTED
                || event.getItem() == null) {
            return;
        }
        final boolean previousUpdating = updating;
        updating = true;
        try {
            search.getEditor().setItem(event.getItem());
        } finally {
            updating = previousUpdating;
        }
    }

    private void commitGuideItemSelection(
            final JComboBox<String> search,
            final ItemEvent event,
            final boolean activatePoints) {
        if (updating || event.getStateChange() != ItemEvent.SELECTED
                || event.getItem() == null) {
            return;
        }
        selectGuideSearchValue(search, activatePoints,
                "comboBoxChanged");
    }

    private void installGuidePopupCommit(
            final JComboBox<String> search,
            final boolean activatePoints) {
        final KeyAdapter escapeTracker = new KeyAdapter() {
            @Override
            public void keyPressed(final KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    search.putClientProperty("guidePopupEscapePressed",
                            Boolean.TRUE);
                }
            }
        };
        search.addKeyListener(escapeTracker);
        comboPopupList(search).ifPresent(list ->
                list.addKeyListener(escapeTracker));
        search.addPopupMenuListener(new PopupMenuListener() {
            private Object selectionAtOpen;
            private boolean cancelled;

            @Override
            public void popupMenuWillBecomeVisible(
                    final PopupMenuEvent event) {
                selectionAtOpen = search.getSelectedItem();
                cancelled = false;
                search.putClientProperty("guidePopupEscapePressed",
                        Boolean.FALSE);
            }

            @Override
            public void popupMenuWillBecomeInvisible(
                    final PopupMenuEvent event) {
                if (cancelled) {
                    return;
                }
                /*
                 * A popup-close event is an explicit reviewer choice.  Do
                 * not discard it merely because a model render overlapped
                 * the native macOS close notification; the selected model
                 * value remains authoritative and the controller will make
                 * a repeated choice a no-op.
                 */
                commitPopupGuideSelection(search, activatePoints);
            }

            @Override
            public void popupMenuCanceled(final PopupMenuEvent event) {
                cancelled = true;
                if (Boolean.TRUE.equals(search.getClientProperty(
                        "guidePopupEscapePressed"))) {
                    search.setSelectedItem(selectionAtOpen);
                    return;
                }
                /*
                 * Aqua sometimes reports a mouse-selected row as a canceled
                 * popup when focus moves back to the canvas.  Commit only if
                 * the model selection actually changed; Escape and an
                 * unchanged popup remain true no-ops.
                 */
                if (!Objects.equals(selectionAtOpen,
                        search.getSelectedItem())) {
                    commitPopupGuideSelection(search, activatePoints);
                }
            }
        });
    }

    private void installGuideModelCommit(
            final JComboBox<String> search,
            final boolean activatePoints) {
        search.getModel().addListDataListener(new ListDataListener() {
            @Override
            public void intervalAdded(final ListDataEvent event) {
                // Item-list mutations occur only during guarded rendering.
            }

            @Override
            public void intervalRemoved(final ListDataEvent event) {
                // Item-list mutations occur only during guarded rendering.
            }

            @Override
            public void contentsChanged(final ListDataEvent event) {
                if (!updating && search.getSelectedItem() != null) {
                    selectGuideSearchValue(search, activatePoints,
                            "comboBoxChanged");
                }
            }
        });
    }

    private Optional<JList<?>> comboPopupList(
            final JComboBox<?> combo) {
        final Object accessiblePopup = combo.getUI()
                .getAccessibleChild(combo, 0);
        if (accessiblePopup instanceof ComboPopup popup) {
            return Optional.of(popup.getList());
        }
        return Optional.empty();
    }

    private void commitPopupGuideSelection(
            final JComboBox<String> search,
            final boolean activatePoints) {
        if (search.getSelectedItem() == null) {
            return;
        }
        synchronizeGuideEditorSelection(search,
                new ItemEvent(search,
                        ItemEvent.ITEM_STATE_CHANGED,
                        search.getSelectedItem(),
                        ItemEvent.SELECTED));
        selectGuideSearchValue(search, activatePoints,
                "comboBoxChanged");
    }

    /**
     * Active-side selection is display focus, not a draft reset. Border keeps
     * both per-side records and swaps the requested side into the primary
     * editor. A single Structure request cannot be hidden behind another
     * side, so the reviewer must explicitly Apply, Cancel, or Skip it first.
     */
    private void activateAtlasSide(
            final ManualHemisphereWarp2D.AtlasSide requestedSide) {
        final StructureAdjustmentDraft structureDraft = latestModel == null
                ? null : latestModel.structureAdjustment().draft()
                        .orElse(null);
        if (workflowStage == ReviewWorkflowStage.STRUCTURE
                && structureDraft != null
                && structureDraft.atlasSide() != requestedSide) {
            final boolean previousUpdating = updating;
            updating = true;
            try {
                activeAtlasLeft.setSelected(structureDraft.atlasSide()
                        == ManualHemisphereWarp2D.AtlasSide.LEFT);
                activeAtlasRight.setSelected(structureDraft.atlasSide()
                        == ManualHemisphereWarp2D.AtlasSide.RIGHT);
                canvas.setActiveHemisphereSide(structureDraft.atlasSide());
            } finally {
                updating = previousUpdating;
            }
            final String notice = "Apply, Cancel, or Skip the current "
                    + "Structure request before changing atlas side.";
            compactStatus.setText(ellipsize(notice, 92));
            compactStatus.setToolTipText(notice);
            return;
        }

        canvas.setActiveHemisphereSide(requestedSide);
        if (workflowStage == ReviewWorkflowStage.BORDER
                && latestModel != null
                && latestModel.boundaryWarp().active()
                && latestModel.boundaryWarp().drafts().stream().anyMatch(
                        draft -> draft.targetSide() == requestedSide)
                && latestModel.boundaryWarp().draft().stream().noneMatch(
                        draft -> draft.targetSide() == requestedSide)) {
            runCanvasEdit("Border side not activated", () ->
                    controller.activateBoundaryWarpSide(requestedSide));
        }
        updateHandleButtonLabels();
    }

    private ManualHemisphereWarp2D.AtlasSide activeWarpSide() {
        return activeAtlasRight.isSelected()
                ? ManualHemisphereWarp2D.AtlasSide.RIGHT
                : ManualHemisphereWarp2D.AtlasSide.LEFT;
    }

    private void replaceSelectedStructureControls() {
        try {
            final int count = selectedStructureControlDensity();
            final var identifiers = controller.replaceSelectedStructureControls(
                    count, activeWarpSide());
            activatePointsTool();
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "Created an editable " + identifiers.size()
                    + "-vertex ROI outline. Drag any cyan square or use ROI thickness and "
                    + "Blade gap, then Calculate structure preview. Nothing "
                    + "is installed until Apply structure changes."));
        } catch (final RuntimeException error) {
            showError("Structure controls not replaced", messageOf(error));
        }
    }

    private void updateStructureSliders() {
        runCanvasEdit("Structure sliders not updated", () ->
                controller.setStructureAdjustmentSliders(
                        activeWarpSide(), structureRoiSize.getValue(),
                        structureBladeGap.getValue()));
    }

    private void clearSelectedStructureControls() {
        try {
            pendingManualWarpMoveRevision = controller.state()
                    .contentRevision();
            controller.clearSelectedStructureControls(activeWarpSide());
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "Clearing only the selected structure's controls. Other "
                    + "structures, border/interior controls, and the opposite "
                    + "atlas side remain unchanged."));
        } catch (final RuntimeException error) {
            pendingManualWarpMoveRevision = -1;
            showError("Selected structure not cleared", messageOf(error));
        }
    }

    private void replaceInteriorGridControls() {
        try {
            final int count = selectedGridControlDensity();
            final var identifiers = controller.replaceInteriorGridControls(
                    count, activeWarpSide());
            activatePointsTool();
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "Set " + identifiers.size()
                    + " regular interior controls on the active side. "
                    + "The previous regular-interior group was replaced."));
        } catch (final RuntimeException error) {
            final String message = messageOf(error);
            if (message.contains("too narrow")
                    || message.contains("safely interior")) {
                final String friendly = "Atlas "
                        + activeWarpSide().name().toLowerCase(Locale.ROOT)
                        + " does not overlap enough reviewed tissue. Choose the other atlas side, reposition the atlas, or edit the tissue crop.";
                compactStatus.setText(ellipsize(friendly, 92));
                compactStatus.setToolTipText(friendly);
                errorStatus.setText(friendly);
            } else {
                showError("Interior controls not replaced", message);
            }
        }
    }

    private void armInteriorPoint() {
        try {
            activatePointsTool();
            canvas.armManualWarpPointPlacement();
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "Add-one armed. Click a tissue interior point on the "
                    + "active side; Escape cancels without creating a revision."));
        } catch (final RuntimeException error) {
            showError("Interior point not armed", messageOf(error));
        }
    }

    /**
     * Window-wide Escape target; cancellation is display-only and auditable.
     *
     * @return true only when the review canvas owned an interaction to cancel
     */
    boolean cancelCanvasInteraction() {
        final boolean addOneWasArmed =
                canvas.manualWarpPointPlacementArmed();
        final boolean canceled = canvas.cancelCurrentInteraction();
        if (!canceled) {
            return false;
        }
        if (addOneWasArmed) {
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "Add-one canceled. No control or review revision was created."));
        }
        return true;
    }

    private void addManualWarpPoint(final Point2D point) {
        try {
            final String identifier = controller.addManualWarpControl(
                    activeWarpSide(), point);
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "Added " + identifier + " as a user-placed interior "
                    + "control on the active side; the validated local warp installs "
                    + "atomically when the solve completes."));
        } catch (final RuntimeException error) {
            showError("Interior point not added", messageOf(error));
        }
    }

    private void resetManualWarpSide() {
        try {
            controller.resetManualWarpSide(activeWarpSide());
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "Reset the active side's controls and local warp; the "
                    + "opposite side remains unchanged."));
        } catch (final RuntimeException error) {
            showError("Manual warp side not reset", messageOf(error));
        }
    }

    private void updateHandleButtonLabels() {
        final int structureCount = selectedStructureControlDensity();
        final int gridCount = selectedGridControlDensity();
        seedAtlasLeftHandles.setText(
                "Create " + structureCount + "-point ROI outline");
        seedAtlasRightHandles.setText(
                "Set " + gridCount + " across side");
    }

    private int selectedStructureControlDensity() {
        return Objects.requireNonNull(
                (Integer) structureControlDensity.getSelectedItem());
    }

    private int selectedGridControlDensity() {
        return Objects.requireNonNull(
                (Integer) gridControlDensity.getSelectedItem());
    }

    private int selectedBoundaryDensity() {
        return Objects.requireNonNull(
                (Integer) boundaryControlDensity.getSelectedItem());
    }

    private void updateBoundaryWarpButtonLabel() {
        resuggestBoundaryWarpPairs.setText(
                "Re-suggest " + selectedBoundaryDensity()
                        + " border points");
        compactMeasured(resuggestBoundaryWarpPairs);
    }

    private void setWorkflowStage(final ReviewWorkflowStage requestedStage) {
        // Wizard navigation is display-only. Interior controls are installed
        // only after an explicit Set/Add action.
        final ReviewWorkflowStage checked = Objects.requireNonNull(
                requestedStage, "requestedStage");
        if (workflowStage == ReviewWorkflowStage.SETUP_AND_PLANE
                && checked != ReviewWorkflowStage.SETUP_AND_PLANE) {
            discardUnfinishedCropTrace();
        }
        // A visible combo selection must become review content before any
        // mode-dependent matching, placement, or warp stage is activated.
        commitVisuallyRetainedSectionModeSelection();
        if (exportInspectorVisible
                && checked != ReviewWorkflowStage.ACCEPT_EXPORT) {
            showAlignmentInspector();
        }
        workflowStage = checked;
        if (workflowStage != ReviewWorkflowStage.STRUCTURE) {
            previewExportedRoi.setSelected(false);
            previewExportedRoi.setEnabled(false);
            canvas.setExportedRoiPreviewVisible(false);
        }
        panTool.setSelected(false);
        stageInspectorCards.show(stageInspector,
                workflowStage.cardKey());
        for (int index = 0; index < workflowStepButtons.length; index++) {
            workflowStepButtons[index].setEnabled(
                    index != workflowStage.index());
        }
        toolbarBack.setEnabled(workflowStage.index() > 0
                || exportInspectorVisible);
        toolbarNext.setEnabled(
                workflowStage != ReviewWorkflowStage.ACCEPT_EXPORT
                        && !exportInspectorVisible);
        toolbarSkip.setEnabled(
                workflowStage != ReviewWorkflowStage.SETUP_AND_PLANE
                        && workflowStage
                                != ReviewWorkflowStage.ACCEPT_EXPORT
                        && !exportInspectorVisible);
        updateStageSkipLabel();
        canvas.setSingleTissuePane(
                workflowStage != ReviewWorkflowStage.MATCH);
        canvas.setBoundaryFitEditingEnabled(
                workflowStage == ReviewWorkflowStage.MATCH);
        canvas.setBoundaryWarpPreviewVisible(
                workflowStage == ReviewWorkflowStage.BORDER);
        canvas.setStructureEditingEnabled(false);
        canvas.setManualWarpEditingEnabled(
                workflowStage != ReviewWorkflowStage.STRUCTURE
                        && workflowStage != ReviewWorkflowStage.ACCEPT_EXPORT);
        manualRoiEditor.setActive(
                workflowStage == ReviewWorkflowStage.STRUCTURE);
        canvas.setManualRoiVisible(true);

        switch (workflowStage) {
            case SETUP_AND_PLANE -> activatePlacementForStage();
            case MATCH -> activateMatchingForStage();
            case BORDER -> activateBorderForStage();
            case INTERIOR -> activateInteriorForStage();
            case STRUCTURE -> activateStructureForStage();
            case ACCEPT_EXPORT -> {
                activateInteriorForStage();
                if (latestModel != null) {
                    compactStatus.setText(compactStatusText(latestModel));
                    compactStatus.setToolTipText(compactStatusText(latestModel));
                }
            }
        }
        if (workflowStage == ReviewWorkflowStage.BORDER && latestModel != null) {
            final String notice = compactStatusText(latestModel);
            compactStatus.setText(notice);
            compactStatus.setToolTipText(notice);
        }
        if (workflowStage != ReviewWorkflowStage.BORDER
                && latestModel != null
                && latestModel.boundaryWarp().active()) {
            showPausedBoundaryWarpNotice(latestModel);
        }
        if (latestModel != null) {
            updateToolSensitiveActionEnablement(latestModel);
        }
        if (latestModel != null) {
            final boolean transientEditing =
                    workflowStage == ReviewWorkflowStage.MATCH
                            && latestModel.boundaryFit().active()
                    || workflowStage == ReviewWorkflowStage.BORDER
                            && latestModel.boundaryWarp().active();
            toolbarAccept.setEnabled((!latestModel.accepted()
                    || exportPanel != null) && !transientEditing);
            accept.setEnabled(!latestModel.accepted()
                    && !transientEditing);
        }
        updateStagePrimary(); updateReviewStates();
        updateBoundaryFitPanelVisibility();
        stageInspector.revalidate();
        stageInspector.repaint();
    }

    /** Drops only the unfinished trace; installed tissue support is state. */
    private boolean discardUnfinishedCropTrace() {
        if (workflowStage != ReviewWorkflowStage.SETUP_AND_PLANE
                || !canvas.tissueSupportTracing()) {
            return false;
        }
        canvas.cancelTissueSupportTrace();
        final String notice = "Unfinished crop polygon discarded — reviewed cyan crop unchanged";
        compactStatus.setText(ellipsize(notice, 92));
        compactStatus.setToolTipText(notice);
        tissueCropDrawStatus.setText(
                "Automatic contrast or reviewer polygon");
        tissueCropDrawStatus.setToolTipText(notice);
        return true;
    }

    private void updateToolSensitiveActionEnablement(
            final ReviewViewModel model) {
        final boolean manual = model.reviewState().content()
                .workflowMode().permitsManualEdits();
        final boolean sideHandlesReady = manual
                && model.orientation().confirmed()
                && model.selectedAtlasContour()
                .map(SelectedAtlasContour::isPresent).orElse(false);
        final boolean sideMeshReady = manual
                && model.orientation().confirmed();
        final boolean hemisphereWarp = model.reviewState().content()
                .hemisphereWarp().isPresent();
        final boolean pointsSelected = pointsTool.isSelected();
        seedAtlasLeftHandles.setEnabled(sideHandlesReady && pointsSelected);
        clearSelectedStructure.setEnabled(sideHandlesReady && pointsSelected
                && hemisphereWarp && !model.structureAdjustment().active());
        seedAtlasRightHandles.setEnabled(sideMeshReady && pointsSelected);
        addInteriorPoint.setEnabled(manual && hemisphereWarp
                && pointsSelected);
    }

    private void activateMatchingForStage() {
        if (latestModel == null) {
            return;
        }
        if (latestModel.boundaryFit().active()) {
            resumeBoundaryFitInteraction();
            return;
        }
        final boolean hasWarp = latestModel.reviewState().content()
                .hemisphereWarp().isPresent()
                || latestModel.reviewState().content().localWarp().isPresent();
        if (hasWarp) {
            pointsTool.setSelected(true);
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            compactStatus.setText(
                    "Clear warp or Undo local changes before coarse matching");
            return;
        }
        transformTool.setSelected(true);
        canvas.setInteractionTool(ReviewCanvas.InteractionTool.TRANSFORM);
        compactStatus.setText(
                "Match is optional — start matching or keep the Setup placement");
    }

    private void activatePlacementForStage() {
        final boolean hasWarp = latestModel != null
                && (latestModel.reviewState().content().hemisphereWarp()
                        .isPresent()
                || latestModel.reviewState().content().localWarp()
                        .isPresent());
        if (hasWarp) {
            pointsTool.setSelected(true);
            canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
            compactStatus.setText(
                    "Clear warp or Undo local changes before coarse placement");
            return;
        }
        transformTool.setSelected(true);
        canvas.setInteractionTool(ReviewCanvas.InteractionTool.TRANSFORM);
        final String notice = latestModel == null
                ? "Place atlas — drag body • resize handles • Shift keeps "
                        + "proportions • rotate • Points starts local warp"
                : compactStatusText(latestModel);
        compactStatus.setText(notice);
        compactStatus.setToolTipText(notice);
    }

    private void activateBorderForStage() {
        borderTool.setSelected(true);
        canvas.setInteractionTool(ReviewCanvas.InteractionTool.BORDER);
        if (latestModel != null && latestModel.boundaryWarp().active()) {
            resumeBoundaryWarpInteraction();
        } else {
            compactStatus.setText(
                    "Border is optional — prepare points or keep the current warp");
        }
    }

    private void startBoundaryFitDraft() {
        runCanvasEdit("Border matching not started", () ->
                controller.startBoundaryFitMatching(
                        boundaryFitSeparateAxes.isSelected()
                                ? BoundaryFitModel.ORTHOGONAL_XY
                                : BoundaryFitModel.SIMILARITY,
                        activeWarpSide()));
    }

    private void resumeBoundaryFitInteraction() {
        transformTool.setSelected(true);
        canvas.setInteractionTool(ReviewCanvas.InteractionTool.TRANSFORM);
        canvas.requestFocusInWindow();
    }

    private void skipBoundaryFitStage() {
        if (latestModel != null && latestModel.boundaryFit().active()) {
            controller.cancelBoundaryFit();
        }
        setWorkflowStage(ReviewWorkflowStage.BORDER);
        final String notice =
                "Match skipped — current Setup placement retained";
        compactStatus.setText(notice);
        compactStatus.setToolTipText(notice);
    }

    private void startBoundaryWarpDraft() {
        runCanvasEdit("Border points not prepared",
                () -> controller.startBoundaryWarp(
                        activeWarpSide(), selectedBoundaryDensity(),
                        includeOppositeHalfRemnant.isSelected()));
    }

    private void resumeBoundaryWarpInteraction() {
        borderTool.setSelected(true);
        canvas.setInteractionTool(ReviewCanvas.InteractionTool.BORDER);
        canvas.requestFocusInWindow();
    }

    private void skipBoundaryWarpStage() {
        if (latestModel != null && latestModel.boundaryWarp().active()) {
            controller.cancelBoundaryWarp();
        }
        setWorkflowStage(ReviewWorkflowStage.INTERIOR);
        final String notice = hasAppliedBorder(latestModel)
                ? "Applied border kept — ready for Interior points"
                : "Border skipped — installed placement and warp retained";
        compactStatus.setText(notice);
        compactStatus.setToolTipText(notice);
    }

    private void skipInteriorStage() {
        canvas.cancelTransientGesture();
        setWorkflowStage(ReviewWorkflowStage.STRUCTURE);
        final String notice =
                "Interior skipped — installed placement and warp retained";
        compactStatus.setText(notice);
        compactStatus.setToolTipText(notice);
    }

    private void skipStructureStage() {
        manualRoiEditor.discardTransientDrawing();
        setWorkflowStage(ReviewWorkflowStage.ACCEPT_EXPORT);
        final String notice =
                "Draw ROIs skipped — finished named ROIs retained; unfinished polygon discarded";
        compactStatus.setText(notice);
        compactStatus.setToolTipText(notice);
    }

    private void skipCurrentWorkflowStage() {
        switch (workflowStage) {
            case MATCH -> skipBoundaryFitStage();
            case BORDER -> skipBoundaryWarpStage();
            case INTERIOR -> skipInteriorStage();
            case STRUCTURE -> skipStructureStage();
            case SETUP_AND_PLANE, ACCEPT_EXPORT -> {
                // No optional stage to discard here.
            }
        }
    }

    private AbstractButton primaryDelegate() {
        return switch (workflowStage) {
            case SETUP_AND_PLANE, STRUCTURE -> toolbarNext;
            case MATCH -> latestModel != null && latestModel.boundaryFit().active() ? applyBoundaryFit : suggestBoundaryFit;
            case BORDER -> latestModel != null && latestModel.boundaryWarp().candidate().isPresent() ? applyBoundaryWarp
                    : latestModel != null && latestModel.boundaryWarp().active() ? calculateBoundaryWarp : suggestBoundaryWarpPairs;
            case INTERIOR -> applyInteriorStage;
            case ACCEPT_EXPORT -> null;
        };
    }

    private void updateStagePrimary() {
        if (controller.isClosed()) return;
        final AbstractButton delegate = primaryDelegate();
        final boolean exportReady = manualRoiEditor.exportReady();
        stagePrimary.setText(workflowStage == ReviewWorkflowStage.SETUP_AND_PLANE ? "Continue to Border"
                : workflowStage == ReviewWorkflowStage.STRUCTURE ? "Review ROIs"
                : workflowStage == ReviewWorkflowStage.ACCEPT_EXPORT ? "Export ROIs…" : delegate.getText());
        stagePrimary.setEnabled(!displayGeometryBlocked && (delegate == null ? exportReady : delegate.isEnabled()));
        stagePrimary.setToolTipText(stagePrimary.isEnabled() ? "Primary action for this stage"
                : displayGeometryBlocked ? "Return to the registration plane and wait for its preview"
                : delegate == null ? "Finish and select at least one ROI before exporting"
                : delegate.getToolTipText() == null ? "Complete the required edits or preview for this stage first" : delegate.getToolTipText());
        compactMeasured(stagePrimary);
    }

    private void stagePrimaryAction() {
        final AbstractButton delegate = primaryDelegate();
        if (delegate == null) manualRoiEditor.exportSelected(this);
        else delegate.doClick();
    }

    private void updateReviewStates() {
        if (controller.isClosed()) return;
        overlayLegend.setText("<html><font color='" + colorHex(canvas.dimRegionColor()) + "'>■</font> Atlas · "
                + "<font color='#00aabb'>■</font> Crop · <font color='" + colorHex(canvas.selectedRegionColor())
                + "'>■</font> Guide · ROIs: numbered colors · Holes: red</html>");
        final var snapshot = manualRoiSession.snapshot();
        final String exported = lastExport == null ? "not exported" : lastExport.context().equals(controller.captureExportContext())
                ? "current" : "completed · review edits since export";
        reviewStates.setText("Alignment: " + (controller.acceptedAlignment().isPresent() ? "accepted" : "needs acceptance")
                + "   |   ROIs: " + snapshot.exportableRois().size() + " ready / " + snapshot.rois().size() + " total"
                + "   |   Export: " + exported);
        reviewStates.setToolTipText("Alignment acceptance is separate from independent manual ROI completion. "
                + (lastExport == null ? "No export has completed in this open review." : "Last export: " + lastExport.directory()));
    }

    private void updateStageSkipLabel() {
        toolbarSkip.setText(switch (workflowStage) {
            case MATCH -> "Skip Match";
            case BORDER -> hasAppliedBorder(latestModel)
                    ? "Keep applied border" : "Skip Border";
            case INTERIOR -> "Skip Interior";
            case STRUCTURE -> "Skip Draw ROIs";
            case SETUP_AND_PLANE, ACCEPT_EXPORT -> "Skip step";
        });
        compactMeasured(toolbarSkip);
    }

    private void showPausedBoundaryWarpNotice(
            final ReviewViewModel model) {
        final String notice = model.boundaryWarp().candidate().isPresent()
                ? "Border preview paused — not installed; return to Border and "
                        + "Apply border"
                : "Border draft paused — return to Border to continue";
        compactStatus.setText(ellipsize(notice, 92));
        compactStatus.setToolTipText(notice);
    }

    private void activateInteriorForStage() {
        pointsTool.setSelected(true);
        canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
    }

    private void activateStructureForStage() {
        pointsTool.setSelected(true);
        canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
        manualRoiEditor.setActive(true);
        compactStatus.setText(
                "Draw exact named export ROIs — no Structure solver required");
        compactStatus.setToolTipText(
                "Manual polygons are stored in original source coordinates and remain fixed across plane and alignment changes.");
    }

    private void resuggestBoundaryControls() {
        try {
            controller.resuggestTissueSupport();
            activatePointsTool();
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "Re-suggested the editable tissue crop from copied-image contrast."));
        } catch (final RuntimeException error) {
            showError("Tissue crop not re-suggested", messageOf(error));
        }
    }

    private void startTissueCropDrawing() {
        editTissueCrop.setSelected(false);
        canvas.setTissueSupportEditing(false);
        pointsTool.setSelected(true);
        canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
        canvas.startTissueSupportTrace();
        compactStatus.setText(
                "Draw crop — click around the tissue edge, then Finish polygon");
    }

    private void finishTissueCropDrawing() {
        final java.util.List<Point2D> points =
                canvas.tissueSupportTracePoints();
        if (points.size() < org.atlasalign.application
                .ReviewedTissueSupport.MINIMUM_CONTROLS_PER_COMPONENT) {
            showError("Tissue crop not drawn",
                    "Draw at least four boundary points before finishing.");
            return;
        }
        try {
            controller.replaceTissueSupportWithPolygon(points);
            canvas.cancelTissueSupportTrace();
            compactStatus.setText(
                    "Checking reviewer-drawn tissue crop…");
        } catch (final RuntimeException error) {
            showError("Tissue crop not drawn", messageOf(error));
        }
    }

    private void cancelTissueCropDrawing() {
        canvas.cancelTissueSupportTrace();
        compactStatus.setText("Crop drawing cancelled — review unchanged");
    }

    private void updateTissueCropDrawControls(final int pointCount) {
        final boolean tracing = canvas.tissueSupportTracing();
        finishTissueCrop.setEnabled(tracing
                && pointCount >= org.atlasalign.application
                        .ReviewedTissueSupport
                        .MINIMUM_CONTROLS_PER_COMPONENT);
        cancelTissueCropDraw.setEnabled(tracing);
        final String message = tracing
                ? pointCount + " polygon point"
                        + (pointCount == 1 ? "" : "s")
                        + " — click around the tissue edge"
                : "Automatic contrast or reviewer polygon";
        tissueCropDrawStatus.setText(ellipsize(message, 42));
        tissueCropDrawStatus.setToolTipText(message);
    }

    private String compactStatusText(final ReviewViewModel model) {
        if (model.atlasPlaneError().isPresent()) {
            return "Atlas overlay unavailable — "
                    + model.atlasPlaneError().orElseThrow();
        }
        if (workflowStage == ReviewWorkflowStage.BORDER
                && model.boundaryWarp().loading()) {
            return "Checking manual outer-border pairs…";
        }
        if (workflowStage == ReviewWorkflowStage.BORDER
                && model.boundaryWarp().safetyReport().isPresent()
                && model.boundaryWarp().candidate().isEmpty()) {
            return hasAppliedBorder(model)
                    ? "New border changes not applied — keep the applied border to continue"
                    : "Border changes not applied — edit the pairs or adjust Setup";
        }
        if (workflowStage == ReviewWorkflowStage.BORDER
                && model.boundaryWarp().draft().isPresent()) {
            final long included = model.boundaryWarp().drafts().stream()
                    .flatMap(request -> request.matches().stream())
                    .filter(BoundaryFitMatch::included).count();
            final String prefix = model.boundaryWarp().candidate().isPresent()
                    ? "Border preview — not installed • "
                    : "Border warp — ";
            return prefix + "click or drag atlas points to tissue • "
                    + included + " included across visible sides";
        }
        if (workflowStage == ReviewWorkflowStage.BORDER
                && model.boundaryWarp().recheckSuggested()) {
            return "Plane changed — border warp retained; visually recheck its pairs";
        }
        if (workflowStage == ReviewWorkflowStage.BORDER) {
            return "Border is optional — prepare points or keep the current warp";
        }
        if (workflowStage == ReviewWorkflowStage.MATCH
                && model.boundaryFit().active()) {
            return "Match — choose an atlas number, then the same tissue-border position";
        }
        if (workflowStage == ReviewWorkflowStage.ACCEPT_EXPORT) {
            return "Review & Export — check your named ROIs against the tissue before exporting";
        }
        if (workflowStage == ReviewWorkflowStage.STRUCTURE) {
            final var snapshot = manualRoiSession.snapshot();
            return "Draw ROIs — " + snapshot.rois().size()
                    + " named • " + snapshot.exportableRois().size()
                    + " finished and selected for exact export";
        }
        if (workflowStage != ReviewWorkflowStage.BORDER
                && model.boundaryWarp().active()) {
            return model.boundaryWarp().candidate().isPresent()
                    ? "Border preview paused — not installed; return to Border and "
                            + "Apply border"
                    : "Border draft paused — return to Border to continue";
        }
        final var optional = model.reviewState().content().hemisphereWarp();
        if (optional.isEmpty()) {
            return "Place atlas — drag body • resize handles • Shift keeps proportions • rotate • Points starts local warp";
        }
        final ManualHemisphereWarp2D warp = optional.orElseThrow();
        return "Reviewer warp — left "
                + warp.manualControls(ManualHemisphereWarp2D.AtlasSide.LEFT)
                        .size()
                + " • right "
                + warp.manualControls(ManualHemisphereWarp2D.AtlasSide.RIGHT)
                        .size()
                + " • drag either side independently";
    }

    private void refreshManualRoiSummary() {
        if (latestModel == null
                || workflowStage != ReviewWorkflowStage.STRUCTURE) {
            return;
        }
        final String summary = compactStatusText(latestModel);
        compactStatus.setText(summary);
        compactStatus.setToolTipText(summary);
    }

    private void activatePointsTool() {
        if (latestModel != null && latestModel.boundaryWarp().active()) {
            controller.cancelBoundaryWarp();
        }
        panTool.setSelected(false);
        pointsTool.setSelected(true);
        canvas.setInteractionTool(ReviewCanvas.InteractionTool.POINTS);
    }

    private void restoreWorkflowInteractionToolAfterPan() {
        switch (workflowStage) {
            case SETUP_AND_PLANE -> activatePlacementForStage();
            case MATCH -> {
                final boolean hasWarp = latestModel != null
                        && (latestModel.reviewState().content()
                                .hemisphereWarp().isPresent()
                        || latestModel.reviewState().content()
                                .localWarp().isPresent());
                if (hasWarp) {
                    pointsTool.setSelected(true);
                    canvas.setInteractionTool(
                            ReviewCanvas.InteractionTool.POINTS);
                } else {
                    transformTool.setSelected(true);
                    canvas.setInteractionTool(
                            ReviewCanvas.InteractionTool.TRANSFORM);
                }
            }
            case BORDER -> {
                borderTool.setSelected(true);
                canvas.setInteractionTool(
                        ReviewCanvas.InteractionTool.BORDER);
            }
            case INTERIOR, STRUCTURE, ACCEPT_EXPORT -> {
                pointsTool.setSelected(true);
                canvas.setInteractionTool(
                        ReviewCanvas.InteractionTool.POINTS);
            }
        }
    }

    private void renderSelectedTarget(final ReviewViewModel model) {
        final boolean previousUpdating = updating;
        updating = true;
        try {
            if (model.selectedAtlasRegion().isEmpty()) {
                targetNone.setSelected(true);
                setGuideComboValue(setupGuideSearch, "All structures");
                setGuideComboValue(guideSearch, "All structures");
            } else if (model.selectedAtlasRegion().orElseThrow()
                    .acronym().equals("DG-sg")) {
                targetDgSg.setSelected(true);
            } else if (model.selectedAtlasRegion().orElseThrow()
                    .acronym().equals("cc")) {
                targetCc.setSelected(true);
            } else if (model.selectedAtlasRegion().orElseThrow()
                    .acronym().equals("HPF")) {
                targetHpf.setSelected(true);
            } else if (model.selectedAtlasRegion().orElseThrow()
                    .acronym().equals("VS")) {
                targetVs.setSelected(true);
            } else if (!model.selectedAtlasRegion().orElseThrow()
                    .acronym().equals("DG")) {
                targetNone.setSelected(true);
                otherGuideAcronym.setText(model.selectedAtlasRegion()
                        .orElseThrow().acronym());
            } else {
                targetDg.setSelected(true);
            }
            model.selectedAtlasRegion().ifPresent(region -> {
                final String display = region.acronym() + " — "
                        + region.name();
                setGuideComboValue(setupGuideSearch, display);
                setGuideComboValue(guideSearch, display);
            });
        } finally {
            updating = previousUpdating;
        }
        final String status = model.selectedAtlasRegion().map(region -> {
            if (model.atlasPlaneLoading()) {
                return "Selected " + region.displayName()
                        + "; waiting for the current verified atlas plane.";
            }
            final int count = model.selectedAtlasContour()
                    .map(SelectedAtlasContour::boundaryCount).orElse(0);
            if (count == 0) {
                return "Selected " + region.displayName()
                        + ", but it is not present on this atlas plane. "
                        + "Choose another level or clear the target.";
            }
            return "Selected " + region.displayName() + " (" + count
                    + " boundary pixels). Set N replaces the active side's guide group.";
        }).orElse(
                "No target selected; atlas points are unconstrained.");
        setWrappedLabelText(targetRegionStatus, wrappedHtml(status));
    }

    private static void setGuideComboValue(
            final JComboBox<String> combo,
            final String value) {
        if (!combo.isEditable()) {
            final int separator = value.indexOf(" — ");
            final String acronym = separator > 0
                    ? value.substring(0, separator).trim() : value;
            for (int index = 0; index < combo.getItemCount(); index++) {
                final String existing = combo.getItemAt(index);
                if (value.equals(existing)) {
                    combo.setSelectedIndex(index);
                    return;
                }
                if (existing.equals(acronym)
                        || existing.startsWith(acronym + " — ")) {
                    combo.removeItemAt(index);
                    combo.insertItemAt(value, index);
                    combo.setSelectedIndex(index);
                    return;
                }
            }
            combo.addItem(value);
        }
        combo.setSelectedItem(value);
        if (combo.isEditable()) {
            combo.getEditor().setItem(value);
        }
    }

    private void cancelAtlasPick() {
        canvas.cancelPendingAtlasPoint();
        setWrappedLabelText(landmarkCaptureStatus,
                wrappedHtml("Atlas pick cancelled."));
    }

    private void selectLandmark(final String identifier) {
        modelLandmark(identifier).ifPresentOrElse(landmark -> {
            landmarkId.setText(landmark.id());
            atlasX.setText(numberText(landmark.atlasPoint().x()));
            atlasY.setText(numberText(landmark.atlasPoint().y()));
            previewX.setText(numberText(landmark.previewPoint().x()));
            previewY.setText(numberText(landmark.previewPoint().y()));
            updating = true;
            try {
                landmarkFitRole.setSelected(
                        landmark.role() == LandmarkRole.FIT);
                landmarkCheckRole.setSelected(
                        landmark.role() == LandmarkRole.CHECK);
            } finally {
                updating = false;
            }
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "Selected " + landmark.id() + ". "
                    + "Drag either generic landmark marker to adjust it, "
                    + "change FIT/CHECK role, or remove it."));
        }, () -> setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                "Selected landmark is no longer active on this plane.")));
    }

    private void setSelectedLandmarkRole(final LandmarkRole role) {
        final String identifier = landmarkId.getText().trim();
        if (identifier.isEmpty() || modelLandmark(identifier).isEmpty()) {
            return;
        }
        try {
            controller.setLandmarkRole(identifier, role);
            setWrappedLabelText(landmarkCaptureStatus, wrappedHtml(
                    "Set " + identifier + " to " + role + "."));
        } catch (final RuntimeException error) {
            showError("Landmark role not changed", messageOf(error));
        }
    }

    private static String localWarpStatusText(
            final ReviewViewModel model,
            final int fitPairs,
            final int checkPairs) {
        final var diagnostics = model.reviewState().content().localWarp()
                .orElseThrow().diagnostics();
        return String.format(Locale.ROOT,
                "<b>MANUAL_LOCAL_WARP active</b> — %d FIT, %d CHECK; "
                + "FIT RMS %.2f px; CHECK RMS %s; minimum Jacobian %.3f; "
                + "maximum anisotropy %.3f. Global transform handles are "
                + "disabled until this warp is cleared or undone.",
                fitPairs, checkPairs, diagnostics.fitRms(),
                diagnostics.checkRms().isPresent()
                        ? String.format(Locale.ROOT, "%.2f px",
                        diagnostics.checkRms().getAsDouble())
                        : "unavailable",
                diagnostics.minimumJacobianDeterminant(),
                diagnostics.maximumAnisotropy());
    }

    private static String hemisphereWarpStatusText(
            final ReviewViewModel model) {
        final var warp = model.reviewState().content().hemisphereWarp()
                .orElseThrow();
        final var diagnostics = warp.diagnostics();
        return String.format(Locale.ROOT,
                "<b>REVIEWER_CONTROLLED_MANUAL_WARP active</b> — atlas-left %d controls; "
                + "atlas-right %d controls. Influence is locally supported, "
                + "the seam and opposite side stay fixed, and the same tissue-space "
                + "field remains active when the plane sliders change. Controls "
                + "are reviewer geometry, not automatic evidence.",
                diagnostics.atlasLeftControlCount(),
                diagnostics.atlasRightControlCount());
    }

    private static String outlineWarpStatusText(
            final ReviewViewModel model) {
        return model.reviewState().content().outlineWarp().map(warp -> {
            if (warp instanceof org.atlasalign.application.manual
                    .BoundaryAuthoritativeTransform2D exact) {
                return String.format(Locale.ROOT,
                        "<b>REVIEWED_OUTLINE_MAP active</b> — the reviewed full-section boundary is exact by construction; all copied atlas ROIs use the same piecewise-affine map. Hash %s… Manual refinements below are applied afterward. This is reviewer-controlled geometry, not automatic confidence.",
                        exact.contentSha256().substring(0, 12));
            }
            final var legacy = (org.atlasalign.application.manual
                    .ManualOutlineWarp2D) warp;
            final var diagnostics = legacy.diagnostics();
            return String.format(Locale.ROOT,
                    "<b>MANUAL_OUTLINE_WARP active</b> — four boundary anchors confirmed; "
                    + "all copied atlas ROIs use %s. Minimum Jacobian %.3f; "
                    + "maximum anisotropy %.3f; hash %s… Manual transforms below are applied after this warp.",
                    legacy.algorithmRevision(),
                    diagnostics.minimumJacobianDeterminant(),
                    diagnostics.maximumAnisotropy(),
                    diagnostics.contentSha256().substring(0, 12));
        }).orElse("Manual outline warp inactive.");
    }

    private java.util.Optional<LandmarkPair> modelLandmark(
            final String identifier) {
        return controller.state().content().activeLandmarks().stream()
                .filter(landmark -> landmark.anatomicalHandleMetadata()
                        .isEmpty())
                .filter(landmark -> landmark.id().equals(identifier))
                .findFirst();
    }

    private static double number(final JTextField field) {
        return Double.parseDouble(field.getText().trim());
    }

    private static String numberText(final double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String landmarkTableText(
            final ReviewViewModel model) {
        final var landmarks = model.reviewState().content()
                .activeLandmarks().stream()
                .filter(landmark -> landmark.anatomicalHandleMetadata()
                        .isEmpty())
                .toList();
        final var allLandmarks = model.reviewState().content().landmarks()
                .stream()
                .filter(landmark -> landmark.anatomicalHandleMetadata()
                        .isEmpty())
                .toList();
        if (landmarks.isEmpty() && allLandmarks.isEmpty()) {
            return "No active landmark pairs at this level.";
        }
        final var residuals = landmarks.stream()
                .map(landmark -> residualFor(model, landmark)).toList();
        final StringBuilder text = new StringBuilder();
        if (landmarks.isEmpty()) {
            text.append("No active pairs on this exact plane.\n");
        } else {
            text.append(
                "ID | Role | Atlas (x, y) | Tissue (x, y) | residual px\n");
        }
        for (int index = 0; index < landmarks.size(); index++) {
            final LandmarkPair landmark = landmarks.get(index);
            text.append(landmark.id())
                    .append(" | ")
                    .append(landmark.role())
                    .append(" | ")
                    .append(numberText(landmark.atlasPoint().x()))
                    .append(", ")
                    .append(numberText(landmark.atlasPoint().y()))
                    .append(" | ")
                    .append(numberText(landmark.previewPoint().x()))
                    .append(", ")
                    .append(numberText(landmark.previewPoint().y()))
                    .append(" | ")
                    .append(numberText(residuals.get(index)))
                    .append('\n');
        }
        final double fitRms = rms(landmarks.stream()
                .filter(landmark -> landmark.role() == LandmarkRole.FIT)
                .mapToDouble(landmark -> residualFor(model, landmark))
                .toArray());
        final double checkRms = rms(landmarks.stream()
                .filter(landmark -> landmark.role() == LandmarkRole.CHECK)
                .mapToDouble(landmark -> residualFor(model, landmark))
                .toArray());
        if (Double.isFinite(fitRms)) {
            text.append("FIT RMS: ").append(numberText(fitRms))
                    .append(" preview px");
        }
        if (Double.isFinite(checkRms)) {
            text.append("\nCHECK RMS: ").append(numberText(checkRms))
                    .append(" preview px (held out)");
        }
        final var inactive = allLandmarks.stream()
                .filter(pair -> !landmarks.contains(pair)).toList();
        if (!inactive.isEmpty()) {
            text.append("\n\nInactive pairs (different atlas plane):\n");
            for (final LandmarkPair pair : inactive) {
                text.append(pair.id()).append(" [").append(pair.role())
                        .append("] — level ")
                        .append(pair.coronalLevel()
                                .zeroBasedAnteriorPosteriorIndex())
                        .append(", sagittal ")
                        .append(numberText(pair.atlasPlaneTilt()
                                .sagittalDegrees()))
                        .append("°, horizontal ")
                        .append(numberText(pair.atlasPlaneTilt()
                                .horizontalDegrees())).append("°\n");
            }
        }
        return text.toString();
    }

    private static double residualFor(
            final ReviewViewModel model,
            final LandmarkPair landmark) {
        try {
            final Point2D mapped = model.reviewState().mapAtlasToPreview(
                    landmark.atlasPoint());
            return Math.hypot(mapped.x() - landmark.previewPoint().x(),
                    mapped.y() - landmark.previewPoint().y());
        } catch (final RuntimeException outsideReviewedDomain) {
            return Double.NaN;
        }
    }

    private static double rms(final double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (final double value : values) {
            sum += value * value;
        }
        return Math.sqrt(sum / values.length);
    }

    private static String confidenceText(
            final ReviewViewModel model) {
        final StringBuilder text = new StringBuilder()
                .append("Category: ")
                .append(model.confidence().category())
                .append('\n');
        for (final String reason : model.confidence().reasons()) {
            text.append("• ").append(reason).append('\n');
        }
        text.append('\n');
        for (final ConfidenceEvidence evidence
                : model.confidence().evidence()) {
            text.append(evidence.metric())
                    .append(": ")
                    .append(evidence.status());
            if (evidence.value().isPresent()) {
                text.append(" — ")
                        .append(String.format(
                                Locale.ROOT,
                                "%.4g",
                                evidence.value().getAsDouble()));
                if (!evidence.unit().isBlank()) {
                    text.append(' ').append(evidence.unit());
                }
            }
            text.append('\n')
                    .append("  ")
                    .append(evidence.explanation())
                    .append('\n');
        }
        final var genericLandmarks = model.reviewState().content()
                .activeLandmarks().stream()
                .filter(landmark -> landmark.anatomicalHandleMetadata()
                        .isEmpty())
                .toList();
        if (!genericLandmarks.isEmpty()) {
            text.append("\nActive landmark pairs:\n");
            for (final LandmarkPair landmark : genericLandmarks) {
                text.append("• ").append(landmark.id()).append('\n');
            }
        }
        return text.toString();
    }

    private static String automaticPlaneGuidance(
            final ReviewViewModel model) {
        final var basis = model.reviewState().basis();
        final var initialization = basis
                .automaticPlaneInitialization();
        if (initialization.predictionApplied()) {
            return wrappedHtml(
                    "The verified ensemble tilt was applied to the initial atlas reslice and baseline registration. Source pixels and the immutable DeepSlice prediction remain unchanged.");
        }
        if (basis.initialPlaneProposal()
                .flatMap(InitialPlaneProposal::prediction).isEmpty()) {
            return wrappedHtml(
                    "No verified automatic tilt prediction exists for this session. The atlas started from the explicit manual/fallback coronal plane. Reasons: "
                            + String.join(" ",
                            initialization.eligibility().reasons()));
        }
        return wrappedHtml(
                "The predicted tilt is review-only guidance for this case; the initial atlas plane remained coronal. Reasons: "
                        + String.join(" ",
                        initialization.eligibility().reasons()));
    }

    private static String automaticProvenanceText(
            final ReviewViewModel model) {
        final var basis = model.reviewState().basis();
        final var initialization = basis.automaticPlaneInitialization();
        final StringBuilder text = new StringBuilder()
                .append("Eligibility: ")
                .append(initialization.eligibility().status())
                .append('\n')
                .append("Predicted plane applied: ")
                .append(initialization.predictionApplied())
                .append('\n')
                .append("Initial atlas tilt: sagittal ")
                .append(numberText(initialization.appliedTilt()
                        .sagittalDegrees()))
                .append("°, horizontal ")
                .append(numberText(initialization.appliedTilt()
                        .horizontalDegrees()))
                .append("°\n");
        for (final String reason
                : initialization.eligibility().reasons()) {
            text.append("• ").append(reason).append('\n');
        }
        basis.deepSliceInputProvenance().ifPresentOrElse(input -> text
                .append("\nExact DeepSlice input\n")
                .append("Condition: ").append(input.condition())
                .append('\n')
                .append("Dimensions: ").append(input.width())
                .append('×').append(input.height()).append('\n')
                .append("Pixel SHA-256: ")
                .append(input.pixelsSha256()).append('\n')
                .append("Synthetic-mask SHA-256: ")
                .append(input.syntheticMaskSha256()).append('\n')
                .append("Synthetic fraction: ")
                .append(input.syntheticPixelFraction()).append('\n')
                .append("Observed-tissue fraction: ")
                .append(input.observedTissueFraction().isPresent()
                        ? input.observedTissueFraction().getAsDouble()
                        : "unavailable")
                .append('\n'), () -> text.append(
                "\nExact DeepSlice input identity unavailable or inference not attempted.\n"));
        basis.initialPlaneProposal()
                .flatMap(InitialPlaneProposal::prediction)
                .flatMap(org.atlasalign.application.DeepSlicePlanePrediction
                        ::verifiedRuntimeProvenance)
                .ifPresent(runtime -> text
                        .append("\nVerified runtime\n")
                        .append("Release: ")
                        .append(runtime.verifiedReleaseId()).append('\n')
                        .append("Protocol: ")
                        .append(runtime.protocolVersion()).append('\n')
                        .append("Manifest SHA-256: ")
                        .append(runtime.manifestSha256()).append('\n'));
        return text.toString();
    }

    private static String initialProposalText(
            final ReviewViewModel model) {
        if (model.reviewState().basis()
                .initialPlaneProposal().isEmpty()) {
            return "Initial proposal provenance unavailable";
        }
        final InitialPlaneProposal proposal = model.reviewState().basis()
                .initialPlaneProposal().orElseThrow();
        final var initialization = model.reviewState().basis()
                .automaticPlaneInitialization();
        if (proposal.source() == org.atlasalign.application.InitialPlaneSource
                .MANUAL_ONLY) {
            return "<html><table width='" + CONTROL_TEXT_WIDTH
                    + "'><tr><td><b>Registration mode: Manual-only</b>"
                    + "<br>Automatic proposal: not run — disabled for this session."
                    + "<br>Starting atlas plane: manual default, level "
                    + proposal.coronalLevel().zeroBasedAnteriorPosteriorIndex()
                    + ".<br>Automatic results were not requested or claimed."
                    + "<br>Reflection and laterality require explicit review."
                    + "</td></tr></table></html>";
        }
        final StringBuilder text = new StringBuilder(
                "<html><table width='" + CONTROL_TEXT_WIDTH
                        + "'><tr><td>Initial proposal: ")
                .append(proposal.source())
                .append(", level ")
                .append(proposal.coronalLevel()
                        .zeroBasedAnteriorPosteriorIndex());
        text.append("<br>Eligibility: ")
                .append(initialization.eligibility().status());
        proposal.prediction().ifPresent(prediction -> {
            text.append(String.format(
                    Locale.ROOT,
                    ", ensemble sagittal %.2f°, horizontal %.2f°",
                    prediction.sagittalTiltDegrees(),
                    prediction.horizontalTiltDegrees()));
            prediction.diagnostics().ifPresent(diagnostics -> {
                if (prediction.hasPrimarySecondaryCaution()) {
                    final var primary = diagnostics.primary().geometry();
                    final var secondary = diagnostics.secondary().geometry();
                    text.append(String.format(
                            Locale.ROOT,
                            "<br><b>Diagnostic caution:</b> primary level "
                                    + "%d, sagittal %.2f°, horizontal %.2f°; "
                                    + "secondary level %d, sagittal %.2f°, "
                                    + "horizontal %.2f°. This disagreement "
                                    + "does not alter the transform or overlay.",
                            primary.zeroBasedAnteriorPosteriorIndex(),
                            primary.sagittalTiltDegrees(),
                            primary.horizontalTiltDegrees(),
                            secondary.zeroBasedAnteriorPosteriorIndex(),
                            secondary.sagittalTiltDegrees(),
                            secondary.horizontalTiltDegrees()));
                }
            });
        });
        proposal.fallbackReason().ifPresent(reason -> text
                .append("<br>Fallback: ")
                .append(reason)
                .append(" — ")
                .append(proposal.fallbackMessage().orElse("")));
        final boolean hasPrediction = proposal.prediction().isPresent();
        return text.append(
                initialization.predictionApplied()
                        ? "<br>The ensemble tilt initialized the verified 3D atlas reslice and baseline registration."
                        : hasPrediction
                        ? "<br>The ensemble tilt remains diagnostic; the initial atlas plane is coronal."
                        : "<br>No verified automatic tilt prediction exists; the initial atlas plane is the explicit manual/fallback coronal plane."
                ).append(
                        "<br>Cutting-plane controls resample only the verified 3D atlas."
                        + "<br>Reflection and laterality require explicit review."
                        + "</td></tr></table></html>")
                .toString();
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
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (final Component component : components) {
            component.setMaximumSize(new Dimension(
                    Integer.MAX_VALUE,
                    Math.max(24, component.getPreferredSize().height)));
            if (component instanceof JComponent swingComponent) {
                swingComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            panel.add(component);
        }
        return panel;
    }

    private static JPanel statusSection(final String message) {
        final JLabel status = wrappedLabel(message);
        status.setToolTipText(message);
        return section("Current step", status);
    }

    /** Action labels retain their measured font width; status text alone truncates. */
    private static JPanel actionGrid(final AbstractButton... buttons) {
        final JPanel panel = new JPanel(new GridLayout(0, 1, 3, 3));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (final AbstractButton button : buttons) {
            final Insets insets = button.getInsets();
            final int width = button.getFontMetrics(button.getFont())
                    .stringWidth(button.getText())
                    + insets.left + insets.right + 18;
            final int height = Math.max(28,
                    button.getPreferredSize().height);
            button.setPreferredSize(new Dimension(width, height));
            button.setMinimumSize(new Dimension(width, height));
            panel.add(button);
        }
        return panel;
    }

    private static JPanel verticalPanel() {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private static JPanel scrollableVerticalPanel() {
        final JPanel panel = new ViewportWidthPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    /** A vertical inspector must always shrink to the viewport width. */
    private static final class ViewportWidthPanel extends JPanel
            implements Scrollable {

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(
                final Rectangle visibleRect,
                final int orientation,
                final int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(
                final Rectangle visibleRect,
                final int orientation,
                final int direction) {
            return Math.max(16, visibleRect.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static JLabel wrappedLabel(final String text) {
        return new JLabel(wrappedHtml(text));
    }

    /** Re-measures a dynamic HTML label after its text begins to wrap. */
    private static void setWrappedLabelText(
            final JLabel label,
            final String text) {
        label.setText(text);
        label.setPreferredSize(null);
        final Dimension preferred = label.getPreferredSize();
        label.setPreferredSize(preferred);
        label.setMaximumSize(new Dimension(
                Integer.MAX_VALUE, Math.max(24, preferred.height)));
    }

    private static String wrappedHtml(final String text) {
        return "<html><table width='" + CONTROL_TEXT_WIDTH
                + "'><tr><td>" + text + "</td></tr></table></html>";
    }

    private static JScrollPane verticalScroll(final JPanel panel) {
        final JScrollPane scroll = new JScrollPane(panel);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private static JScrollPane namedVerticalScroll(
            final JPanel panel,
            final String name) {
        final JScrollPane scroll = verticalScroll(panel);
        scroll.setName(name);
        return scroll;
    }

    private static JScrollPane textScroll(final JTextArea text) {
        // JTextArea's column-based default preferred width can otherwise make
        // a tab demand more space than the narrow control viewport even when
        // line wrapping is enabled.
        final Dimension natural = text.getPreferredSize();
        text.setColumns(22);
        text.setPreferredSize(new Dimension(
                CONTROL_TEXT_WIDTH, natural.height));
        text.setMinimumSize(new Dimension(0, natural.height));
        final JScrollPane scroll = new JScrollPane(text);
        scroll.setHorizontalScrollBarPolicy(
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(
                CONTROL_TEXT_WIDTH + 18, natural.height + 4));
        return scroll;
    }

    private static JPanel buttonRow(
            final Component... buttons) {
        // The minimum control pane is deliberately narrow (300 px). A
        // single vertical column keeps every button reachable at that width;
        // vertical scrolling is preferable to a clipped second column.
        final JPanel row = new JPanel(new GridLayout(0, 1, 4, 4));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (final Component button : buttons) {
            row.add(button);
        }
        return row;
    }

    private static String colorHex(final Color color) { return String.format(java.util.Locale.ROOT, "#%06x", color.getRGB() & 0xffffff); }
    private static <T extends JComponent> T displayOnly(final T control, final String name) {
        control.setName(name); control.putClientProperty("displayOnlyControl", true); return control;
    }

    private static JPanel compactToolbarRow() {
        // FlowLayout wraps controls, so its host must reserve every wrapped row.
        // A fixed 34-pixel height clipped the second row on supported small windows.
        final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1)) {
            @Override
            public Dimension getPreferredSize() {
                final java.awt.Container host = getParent() == null ? null
                        : getParent().getParent();
                final int available = host != null && host.getWidth() > 0
                        ? host.getWidth() : 800;
                final Insets insets = getInsets();
                final int rowWidth = Math.max(1, available - insets.left - insets.right - 12);
                int used = 0;
                int rowHeight = 0;
                int height = insets.top + insets.bottom + 2;
                for (final Component child : getComponents()) {
                    if (!child.isVisible()) { continue; }
                    final Dimension size = child.getPreferredSize();
                    final int gap = used == 0 ? 0 : 6;
                    if (used > 0 && used + gap + size.width > rowWidth) {
                        height += rowHeight + 1;
                        used = 0;
                        rowHeight = 0;
                    }
                    used += (used == 0 ? 0 : 6) + size.width;
                    rowHeight = Math.max(rowHeight, size.height);
                }
                return new Dimension(available, Math.max(34, height + rowHeight));
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }

            @Override
            public Dimension getMinimumSize() {
                return new Dimension(0, getPreferredSize().height);
            }
        };
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }

    private static JPanel compactRow(final Component... controls) {
        final JPanel row = new JPanel(new FlowLayout(
                FlowLayout.LEFT, 5, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        for (final Component control : controls) {
            if (control instanceof JComponent component) {
                final Dimension preferred = component.getPreferredSize();
                component.setPreferredSize(new Dimension(
                        preferred.width, Math.min(30,
                        Math.max(26, preferred.height))));
            }
            row.add(control);
        }
        return row;
    }

    private static <T extends JComponent> T compactWidth(
            final T component,
            final int width) {
        final Dimension preferred = component.getPreferredSize();
        component.setPreferredSize(new Dimension(
                width, Math.min(30, Math.max(26, preferred.height))));
        return component;
    }

    private static <T extends AbstractButton> T compactMeasured(
            final T button) {
        final Insets insets = button.getInsets();
        final int width = button.getFontMetrics(button.getFont())
                .stringWidth(button.getText())
                + insets.left + insets.right + 12;
        final int height = Math.min(30,
                Math.max(28, button.getPreferredSize().height));
        button.setPreferredSize(new Dimension(width, height));
        button.setMinimumSize(new Dimension(width, height));
        return button;
    }

    private static String ellipsize(
            final String text,
            final int maximumCharacters) {
        final String value = Objects.requireNonNull(text, "text").trim();
        if (value.length() <= maximumCharacters) {
            return value;
        }
        return value.substring(0, maximumCharacters - 1).stripTrailing()
                + "…";
    }

    private static JButton button(
            final String label,
            final Runnable action) {
        final JButton button = new JButton(label);
        button.addActionListener(event -> action.run());
        return button;
    }

    private static void addField(
            final JPanel panel,
            final GridBagConstraints constraints,
            final int row,
            final String label,
            final JTextField field) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.gridwidth = 1;
        constraints.weightx = 0;
        panel.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.gridwidth = 3;
        constraints.weightx = 1;
        panel.add(field, constraints);
    }

    private static String messageOf(final Throwable error) {
        return error.getMessage() == null
                || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private void setOrientation(
            final AtlasOrientation value) {
        if (!updating) {
            controller.setOrientation(value);
        }
    }

    private void setHemisphere(
            final ObservedAnatomicalHemisphere value) {
        final boolean enabled = switch (value) {
            case LEFT -> hemisphereLeft.isEnabled();
            case RIGHT -> hemisphereRight.isEnabled();
            case UNSURE -> hemisphereUnsure.isEnabled();
            case BOTH -> false;
        };
        if (!updating && enabled) {
            cancelBoundaryFitIfActive();
            controller.setObservedHemisphere(value);
            if (latestModel != null
                    && latestModel.reviewState().content()
                    .reviewSectionMode() == ReviewSectionMode.HALF
                    && (value == ObservedAnatomicalHemisphere.LEFT
                    || value == ObservedAnatomicalHemisphere.RIGHT)) {
                final boolean left = value
                        == ObservedAnatomicalHemisphere.LEFT;
                activeAtlasLeft.setSelected(left);
                activeAtlasRight.setSelected(!left);
                canvas.setActiveHemisphereSide(left
                        ? ManualHemisphereWarp2D.AtlasSide.LEFT
                        : ManualHemisphereWarp2D.AtlasSide.RIGHT);
            }
        }
    }

    private void selectOrientation(
            final AtlasOrientation value) {
        orientationUnconfirmed.setSelected(
                value == AtlasOrientation
                        .UNCONFIRMED_PROVISIONAL_DIRECT);
        orientationDirect.setSelected(
                value == AtlasOrientation
                        .CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT);
        orientationReflected.setSelected(
                value == AtlasOrientation
                        .CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT);
    }

    private void selectHemisphere(
            final ObservedAnatomicalHemisphere value) {
        hemisphereLeft.setSelected(
                value == ObservedAnatomicalHemisphere.LEFT);
        hemisphereRight.setSelected(
                value == ObservedAnatomicalHemisphere.RIGHT);
        hemisphereUnsure.setSelected(
                value == ObservedAnatomicalHemisphere.UNSURE);
    }

    private void setHemisphereEnabled(final ReviewViewModel model) {
        final SectionGeometry geometry = model.geometry();
        if (geometry == SectionGeometry.PARTIAL_OR_DAMAGED) {
            hemisphereLeft.setEnabled(true);
            hemisphereRight.setEnabled(true);
            hemisphereUnsure.setEnabled(true);
            return;
        }
        final boolean confirmed = model.orientation().confirmed();
        final boolean imageLeftIsAtlasLeft =
                !model.orientation().reflected();
        final boolean expectedLeft =
                (geometry == SectionGeometry.IMAGE_LEFT_HALF
                        && imageLeftIsAtlasLeft)
                || (geometry == SectionGeometry.IMAGE_RIGHT_HALF
                        && !imageLeftIsAtlasLeft);
        final boolean expectedRight =
                (geometry == SectionGeometry.IMAGE_RIGHT_HALF
                        && imageLeftIsAtlasLeft)
                || (geometry == SectionGeometry.IMAGE_LEFT_HALF
                        && !imageLeftIsAtlasLeft);
        hemisphereLeft.setEnabled(confirmed && expectedLeft);
        hemisphereRight.setEnabled(confirmed && expectedRight);
        hemisphereUnsure.setEnabled(confirmed
                && (expectedLeft || expectedRight));
    }

    private static void group(
            final AbstractButton... buttons) {
        final ButtonGroup group = new ButtonGroup();
        for (final AbstractButton button : buttons) {
            group.add(button);
        }
    }

    private static void setEnabledRecursively(
            final Component component, final boolean enabled) {
        if (component == null) {
            return;
        }
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container container) {
            for (final Component child : container.getComponents()) {
                setEnabledRecursively(child, enabled);
            }
        }
    }
}
