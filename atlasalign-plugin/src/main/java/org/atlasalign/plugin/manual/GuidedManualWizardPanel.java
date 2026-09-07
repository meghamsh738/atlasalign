package org.atlasalign.plugin.manual;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.ItemEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.AllenCoronalLevel;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewEdit;
import org.atlasalign.application.manual.AnatomicalSide;
import org.atlasalign.application.manual.ArtifactStatus;
import org.atlasalign.application.manual.AutomaticTissueOutlineProposer;
import org.atlasalign.application.manual.ContourCaptureStatus;
import org.atlasalign.application.manual.ContourCompleteness;
import org.atlasalign.application.manual.ContourSegment;
import org.atlasalign.application.manual.ContourTopology;
import org.atlasalign.application.manual.ContourVertex;
import org.atlasalign.application.manual.GuidedManualWorkflowContent;
import org.atlasalign.application.manual.GuidedManualWorkflowSession;
import org.atlasalign.application.manual.ManualAlignmentStage;
import org.atlasalign.application.manual.ManualContour;
import org.atlasalign.application.manual.ManualContourKind;
import org.atlasalign.application.manual.ManualContourVertexEdits;
import org.atlasalign.application.manual.ManualOutlineWarp2D;
import org.atlasalign.application.manual.ReviewedOutlineTransform2D;
import org.atlasalign.application.manual.ManualWorkflowEdit;
import org.atlasalign.application.manual.ManualWorkflowRevision;
import org.atlasalign.application.manual.SectionObservation;
import org.atlasalign.application.manual.SourceImageIdentity;
import org.atlasalign.application.manual.SourcePixelPoint;
import org.atlasalign.application.manual.VerifiedAtlasGuideIdentity;
import org.atlasalign.application.manual.VerifiedAtlasIdentity;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.plugin.review.ReviewController;
import org.atlasalign.plugin.review.ReviewViewModel;
import org.atlasalign.plugin.review.SelectedAtlasContour;
import org.atlasalign.plugin.review.SelectedAtlasRegion;

/**
 * Three-step guided-manual entry into the authoritative review tools.
 * The wizard records an explicit section observation, a reviewer-chosen plane,
 * and an optional bounded outline warp. It never ranks candidates or changes
 * automatic confidence.
 */
public final class GuidedManualWizardPanel extends JPanel {

    private static final int OUTLINE_PREVIEW_DEBOUNCE_MILLISECONDS = 150;

    private static final List<ManualAlignmentStage> VISIBLE_STAGES = List.of(
            ManualAlignmentStage.PREPARE_SECTION,
            ManualAlignmentStage.CHOOSE_ANATOMICAL_GUIDE,
            ManualAlignmentStage.DRAW_TISSUE_OUTLINE);
    private static final String[] STEP_LABELS = {
        "1 Section", "2 Plane", "3 Optional outline"
    };

    private final ReviewController reviewController;
    private final SourceImageIdentity sourceIdentity;
    private final VerifiedAtlasIdentity atlasIdentity;
    private final PreviewMapping previewMapping;
    private final Executor outlinePreviewExecutor;
    private final ManualGuideCatalog guideCatalog;
    private final GuidedManualWorkflowSession session =
            new GuidedManualWorkflowSession();
    private final GuidedManualCanvas canvas;
    private final CardLayout cards = new CardLayout();
    private final JPanel stageCards = new JPanel(cards);
    private final JButton[] stageButtons = new JButton[STEP_LABELS.length];
    private final JButton back = new JButton("Back");
    private final JButton next = new JButton("Next");
    private final JButton undo = new JButton("Undo");
    private final JButton redo = new JButton("Redo");
    private final JButton resetStep = new JButton("Reset this step");
    private final JButton resetAll = new JButton("Reset all guided inputs");
    private final JToggleButton drawTool = new JToggleButton(
            "Draw / edit", true);
    private final JToggleButton panViewportTool = new JToggleButton("Pan");
    private final JToggleButton markGapTool = new JToggleButton("Mark gap");
    private final JLabel status = wrapped("Ready.");
    private final JTextArea history = new JTextArea(7, 28);
    private final AtomicLong contourSequence = new AtomicLong();
    private final AtomicLong vertexSequence = new AtomicLong();

    private final JComboBox<org.atlasalign.application.manual.SectionGeometry>
            geometry = new JComboBox<>(
            org.atlasalign.application.manual.SectionGeometry.values());
    private final JRadioButton direct = new JRadioButton(
            "Direct: atlas left → image left", true);
    private final JRadioButton reflected = new JRadioButton(
            "Reflected: atlas left → image right");
    private final JComboBox<ObservedAnatomicalHemisphere> hemisphere =
            new JComboBox<>(ObservedAnatomicalHemisphere.values());
    private final JCheckBox lateralityConfirmed = new JCheckBox(
            "I confirmed anatomical laterality");
    private final JCheckBox reflectionConfirmed = new JCheckBox(
            "I made an explicit reflection decision");

    private final JComboBox<ManualContourKind> outlineKind =
            new JComboBox<>(new ManualContourKind[]{
                ManualContourKind.TISSUE_OUTLINE,
                ManualContourKind.VISIBLE_TISSUE_BOUNDARY,
                ManualContourKind.MIDLINE
            });
    private final JComboBox<AnatomicalSide> outlineSide =
            new JComboBox<>(AnatomicalSide.values());
    private final JComboBox<ContourCompleteness> outlineCompleteness =
            new JComboBox<>(ContourCompleteness.values());
    private final JComboBox<String> outlineContours = new JComboBox<>();
    private final JButton suggestOutline = new JButton(
            "Suggest outline from image contrast");
    private final JSpinner suggestedOutlineVertices = new JSpinner(
            new SpinnerNumberModel(48,
                    AutomaticTissueOutlineProposer.MINIMUM_GENERATED_VERTICES,
                    AutomaticTissueOutlineProposer
                            .DEFAULT_MAXIMUM_GENERATED_VERTICES,
                    8));
    private final JButton continueWithoutOutlineWarp = new JButton(
            "Use selected plane; keep size/position");
    private final JButton continueWithOutlineWarp = new JButton(
            "Use selected plane + reviewed outline");
    private final JLabel outlineSuggestionStatus = wrapped(
            "AtlasAlign can propose a full-section boundary from the existing copied-preview tissue mask. You must inspect and finish it.");

    private final JComboBox<ManualGuideOption> guide = new JComboBox<>();
    private final JSlider neutralAtlasLevel = new JSlider(
            0, org.atlasalign.application.AllenCoronalLevel.PLANE_COUNT - 1,
            org.atlasalign.application.AllenCoronalLevel.PLANE_COUNT / 2);
    private final JSlider neutralSagittalTilt = new JSlider(
            -(int) AtlasPlaneTilt.MAXIMUM_ABSOLUTE_DEGREES,
            (int) AtlasPlaneTilt.MAXIMUM_ABSOLUTE_DEGREES, 0);
    private final JSlider neutralHorizontalTilt = new JSlider(
            -(int) AtlasPlaneTilt.MAXIMUM_ABSOLUTE_DEGREES,
            (int) AtlasPlaneTilt.MAXIMUM_ABSOLUTE_DEGREES, 0);
    private final JLabel neutralAtlasLevelLabel = wrapped(
            "Neutral atlas reference level 264");
    private final JLabel guideStatus = wrapped(
            "Choose a guide from the verified Allen ontology.");
    private final JLabel neutralTiltLabel = wrapped(
            "Visual cutting plane: coronal");
    private final JLabel outlineWarpStatus = wrapped(
            "Complete one closed full-section outline to preview a shared deformation of every atlas ROI.");
    private final JLabel traceWarpStatus = wrapped(
            "The anatomical trace will be drawn against the same outline-warped atlas geometry.");

    private final JComboBox<ContourTopology> structureTopology =
            new JComboBox<>(ContourTopology.values());
    private final JComboBox<AnatomicalSide> structureSide =
            new JComboBox<>(AnatomicalSide.values());
    private final JComboBox<ContourCompleteness> structureCompleteness =
            new JComboBox<>(ContourCompleteness.values());
    private final JComboBox<String> structureContours = new JComboBox<>();


    private ReviewViewModel reviewModel;
    private Optional<AtlasCoronalPlane> neutralAtlasPlane = Optional.empty();
    private final AtomicLong neutralAtlasRequestSequence = new AtomicLong();
    private Optional<String> activeContourId = Optional.empty();
    private ManualGuideCatalog.ResolvedGuide resolvedGuide;
    private AtlasPlaneTilt neutralAtlasTilt = AtlasPlaneTilt.CORONAL;
    private Optional<ManualOutlineWarpPreview> outlineWarpPreview =
            Optional.empty();
    private Optional<OutlinePreviewKey> outlinePreviewKey = Optional.empty();
    private final AtomicLong outlinePreviewRequestSequence = new AtomicLong();
    private boolean outlinePreviewRunning;
    private Timer outlinePreviewDebounceTimer;
    private Optional<OutlinePreviewKey> failedOutlinePreviewKey = Optional.empty();
    private String failedOutlinePreviewMessage = "";
    private long outlinePreviewRevisionId = -1;
    private Runnable closeHandler = () -> { };
    private Runnable workflowAppliedHandler = () -> { };
    private boolean refreshingSelectors;
    private boolean synchronizingNeutralTiltControls;

    public GuidedManualWizardPanel(
            final ReviewController reviewController,
            final ReviewViewModel initialModel) {
        this(reviewController, initialModel, ForkJoinPool.commonPool());
    }

    GuidedManualWizardPanel(
            final ReviewController reviewController,
            final ReviewViewModel initialModel,
            final Executor outlinePreviewExecutor) {
        super(new BorderLayout(6, 6));
        this.reviewController = Objects.requireNonNull(
                reviewController, "reviewController");
        reviewModel = Objects.requireNonNull(initialModel, "initialModel");
        this.outlinePreviewExecutor = Objects.requireNonNull(
                outlinePreviewExecutor, "outlinePreviewExecutor");
        sourceIdentity = ManualWorkflowIdentities.source(
                initialModel.reviewState().basis());
        atlasIdentity = ManualWorkflowIdentities.atlas(
                initialModel.reviewState().basis());
        previewMapping = new PreviewMapping(
                sourceIdentity.width(), sourceIdentity.height(),
                initialModel.preview().width(), initialModel.preview().height());
        guideCatalog = new ManualGuideCatalog(
                reviewController::resolveExactAtlasRegion,
                atlasIdentity.identitySha256(), atlasIdentity.atlasVersion());
        guideCatalog.options().forEach(guide::addItem);
        configureTiltSlider(neutralSagittalTilt,
                "Negative: left edge anterior; positive: right edge anterior");
        configureTiltSlider(neutralHorizontalTilt,
                "Negative: ventral edge anterior; positive: dorsal edge anterior");
        canvas = new GuidedManualCanvas(
                initialModel.preview(), previewMapping);
        canvas.setListener(new CanvasListener());
        final ButtonGroup canvasTools = new ButtonGroup();
        canvasTools.add(drawTool);
        canvasTools.add(panViewportTool);
        canvasTools.add(markGapTool);
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        add(buildStageRail(), BorderLayout.NORTH);
        add(buildWorkspace(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        wireActions();
        updateModel(initialModel);
        refresh();
        requestNeutralAtlasLevel(neutralAtlasLevel.getValue());
    }

    public void setCloseHandler(final Runnable handler) {
        closeHandler = Objects.requireNonNull(handler, "handler");
    }

    public void setWorkflowAppliedHandler(final Runnable handler) {
        workflowAppliedHandler = Objects.requireNonNull(handler, "handler");
    }

    public void updateModel(final ReviewViewModel model) {
        reviewModel = Objects.requireNonNull(model, "model");
        refreshAtlasReference();
    }

    GuidedManualWorkflowSession workflowSession() {
        return session;
    }

    GuidedManualCanvas manualCanvas() {
        return canvas;
    }

    AtlasPlaneTilt neutralAtlasTilt() {
        return neutralAtlasTilt;
    }

    private JPanel buildStageRail() {
        final JPanel rail = new JPanel(new GridLayout(1, STEP_LABELS.length, 3, 0));
        for (int index = 0; index < STEP_LABELS.length; index++) {
            final int target = index;
            stageButtons[index] = new JButton(STEP_LABELS[index]);
            stageButtons[index].setMargin(new java.awt.Insets(3, 3, 3, 3));
            stageButtons[index].addActionListener(event -> {
                showStage(VISIBLE_STAGES.get(target));
            });
            rail.add(stageButtons[index]);
        }
        return rail;
    }

    private Component buildWorkspace() {
        for (final ManualAlignmentStage stage : VISIBLE_STAGES) {
            stageCards.add(verticalScroll(buildStage(stage)), stage.name());
        }
        stageCards.setPreferredSize(new Dimension(330, 620));
        stageCards.setMinimumSize(new Dimension(290, 360));
        final JPanel right = new JPanel(new BorderLayout(4, 4));
        right.add(stageCards, BorderLayout.CENTER);
        history.setEditable(false);
        history.setLineWrap(true);
        history.setWrapStyleWord(true);
        right.add(section("Visible revision history",
                verticalScroll(history)), BorderLayout.SOUTH);
        final JPanel canvasHost = new JPanel(new BorderLayout(0, 4));
        final JPanel canvasToolbar = new JPanel(new GridLayout(1, 5, 4, 0));
        canvasToolbar.add(drawTool);
        canvasToolbar.add(panViewportTool);
        canvasToolbar.add(button("Tissue Fit", canvas::fitTissue));
        canvasToolbar.add(button("Atlas Fit", canvas::fitAtlas));
        canvasToolbar.add(button("Fit both", canvas::fitBoth));
        canvasHost.add(canvasToolbar, BorderLayout.NORTH);
        canvasHost.add(canvas, BorderLayout.CENTER);
        final JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, canvasHost, right);
        split.setResizeWeight(1);
        split.setContinuousLayout(true);
        return split;
    }

    private JPanel buildStage(final ManualAlignmentStage stage) {
        return switch (stage) {
            case PREPARE_SECTION -> prepareStage();
            case DRAW_TISSUE_OUTLINE -> outlineStage();
            case CHOOSE_ANATOMICAL_GUIDE -> guideStage();
            case DRAW_STRUCTURE_ON_TISSUE,
                    PREVIEW_CANDIDATES,
                    REVIEW_CANDIDATES,
                    REFINE_SELECTED_CANDIDATE,
                    REVIEW_AND_ACCEPT -> throw new IllegalArgumentException(
                            "Legacy candidate-search stages are not part of the guided manual flow");
        };
    }

    private JPanel prepareStage() {
        final ButtonGroup orientation = new ButtonGroup();
        orientation.add(direct);
        orientation.add(reflected);
        return section("1. Is this a full or half section?",
                wrapped("Choose what is visible in this image. Image-left/right describes the displayed crop only; anatomical left/right is confirmed separately below."),
                button("Full section — both sides visible", () ->
                        chooseSectionGeometry(
                                org.atlasalign.application.manual
                                        .SectionGeometry.FULL)),
                buttonRow(
                        button("Half — image left", () ->
                                chooseSectionGeometry(
                                        org.atlasalign.application.manual
                                                .SectionGeometry
                                                .IMAGE_LEFT_HALF)),
                        button("Half — image right", () ->
                                chooseSectionGeometry(
                                        org.atlasalign.application.manual
                                                .SectionGeometry
                                                .IMAGE_RIGHT_HALF))),
                new JLabel("Section geometry"), geometry,
                wrapped("Atlas orientation / explicit reflection"), direct,
                reflected,
                new JLabel("Observed anatomical hemisphere"), hemisphere,
                lateralityConfirmed, reflectionConfirmed,
                button("Save section observation", this::saveObservation),
                wrapped("Zoom and pan remain display-only and never enter alignment history."));
    }

    private void chooseSectionGeometry(
            final org.atlasalign.application.manual.SectionGeometry next) {
        geometry.setSelectedItem(next);
        hemisphere.setSelectedItem(next
                == org.atlasalign.application.manual.SectionGeometry.FULL
                ? ObservedAnatomicalHemisphere.BOTH
                : ObservedAnatomicalHemisphere.UNSURE);
        lateralityConfirmed.setSelected(false);
        reflectionConfirmed.setSelected(false);
        status.setText(wrappedText(next
                == org.atlasalign.application.manual.SectionGeometry.FULL
                ? "Full section selected. Confirm orientation and laterality, then save."
                : "Half section selected. Choose the observed anatomical hemisphere, confirm orientation and laterality, then save."));
    }

    private JPanel outlineStage() {
        return section("3. Optional outline and direct refinement",
                wrapped("The level and two tilts selected in step 2 stay unchanged. For a full section, a finished valid boundary becomes the exact outer edge of the copied atlas; every atlas ROI uses that same reviewed deformation. Half sections skip this whole-outline step."),
                continueWithoutOutlineWarp,
                new JLabel("Suggested outline edit points"),
                suggestedOutlineVertices,
                suggestOutline,
                outlineSuggestionStatus,
                wrapped("The suggestion uses only the copied preview. Choose fewer edit points for a simpler boundary, then drag, insert, or delete vertices to correct tears or debris and click Finish contour. It never edits source pixels."),
                new JLabel("Contour type"), outlineKind,
                new JLabel("Anatomical side"), outlineSide,
                new JLabel("Visibility / damage"), outlineCompleteness,
                buttonRow(
                        button("New contour", this::newOutlineContour),
                        button("Finish contour", this::finishActiveContour)),
                new JLabel("Existing contours"), outlineContours,
                buttonRow(
                        button("Select", () -> selectContour(outlineContours)),
                        button("Remove", this::removeActiveContour)),
                button("Fit tissue view", canvas::fitTissue),
                outlineWarpStatus,
                wrapped("AtlasAlign checks that both boundaries are simple and that the exact piecewise-affine map has positive, non-overlapping triangles. Invalid boundaries fail closed with a plain explanation. Excluded gaps are not guessed across in this milestone. Fine left/right anatomical adjustment happens next in manual review."),
                continueWithOutlineWarp,
                wrapped("The reviewed source outline and transform hash remain in the audit. Source pixels are never edited."));
    }

    private JPanel guideStage() {
        return section("2. Choose visual plane and anatomical guide",
                wrapped("Browse until the atlas anatomy looks approximately like the tissue. Adjust the cutting angle visually; no Allen index or angle entry is required."),
                neutralAtlasLevelLabel,
                neutralAtlasLevel,
                buttonRow(
                        button("Anterior −1", () -> adjustNeutralLevel(-1)),
                        button("Posterior +1", () -> adjustNeutralLevel(1))),
                neutralTiltLabel,
                new JLabel("Left–right AP tilt (degrees)"),
                neutralSagittalTilt,
                buttonRow(
                        button("Left edge anterior", () ->
                                adjustNeutralTilt(-1, 0)),
                        button("Right edge anterior", () ->
                                adjustNeutralTilt(1, 0))),
                new JLabel("Dorsal–ventral AP tilt (degrees)"),
                neutralHorizontalTilt,
                buttonRow(
                        button("Dorsal edge anterior", () ->
                                adjustNeutralTilt(0, 1)),
                        button("Ventral edge anterior", () ->
                                adjustNeutralTilt(0, -1))),
                button("Reset visual tilt", this::resetNeutralTilt),
                new JLabel("Supported guide"), guide,
                button("Use selected verified guide", this::chooseGuide),
                guideStatus,
                wrapped("This becomes the manual starting plane. Next, either keep its current placement or optionally fit a reviewed whole-section outline. No candidate search or confidence change is performed."));
    }

    private JPanel buildFooter() {
        final JPanel footer = new JPanel(new BorderLayout(6, 3));
        final JPanel actions = new JPanel(new GridLayout(1, 6, 4, 0));
        actions.add(back);
        actions.add(next);
        actions.add(undo);
        actions.add(redo);
        actions.add(resetStep);
        actions.add(resetAll);
        footer.add(actions, BorderLayout.NORTH);
        footer.add(status, BorderLayout.CENTER);
        return footer;
    }

    private void wireActions() {
        back.addActionListener(event -> {
            showPreviousVisibleStage();
            refresh();
        });
        next.addActionListener(event -> advance());
        undo.addActionListener(event -> {
            if (!session.undo()) {
                showStatus("Nothing to undo.", false);
            }
            refresh();
        });
        redo.addActionListener(event -> redo());
        resetStep.addActionListener(event -> applySafely(
                session::resetCurrentStep, "Reset current step"));
        resetAll.addActionListener(event -> {
            final int choice = GraphicsEnvironment.isHeadless()
                    ? JOptionPane.OK_OPTION
                    : JOptionPane.showConfirmDialog(
                            this,
                            "Reset all guided-manual inputs? The previous branch remains in visible history and can be restored.",
                            "Reset guided inputs",
                            JOptionPane.OK_CANCEL_OPTION,
                            JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.OK_OPTION) {
                applySafely(session::resetAll, "Reset all guided inputs");
            }
        });
        drawTool.addActionListener(event -> {
            canvas.setPanTool(false);
            canvas.setGapTool(false);
            canvas.setDrawingEnabled(isDrawingStage());
        });
        panViewportTool.addActionListener(event -> {
            canvas.setDrawingEnabled(false);
            canvas.setGapTool(false);
            canvas.setPanTool(true);
        });
        markGapTool.addActionListener(event -> {
            canvas.setDrawingEnabled(false);
            canvas.setPanTool(false);
            canvas.setGapTool(markGapTool.isSelected());
            showStatus("Click a contour segment to mark or unmark it as a tear/non-matching gap.", false);
        });
        outlineContours.addItemListener(event -> {
            if (!refreshingSelectors
                    && event.getStateChange() == ItemEvent.SELECTED) {
                selectContour(outlineContours);
            }
        });
        neutralAtlasLevel.addChangeListener(event -> {
            if (!neutralAtlasLevel.getValueIsAdjusting()) {
                requestNeutralAtlasLevel(neutralAtlasLevel.getValue());
            }
        });
        neutralSagittalTilt.addChangeListener(event ->
                neutralTiltSliderChanged());
        neutralHorizontalTilt.addChangeListener(event ->
                neutralTiltSliderChanged());
        suggestOutline.addActionListener(
                event -> suggestOutlineFromImageContrast());
        continueWithoutOutlineWarp.addActionListener(event ->
                applyVisualStartingPlaneSafely(false));
        continueWithOutlineWarp.addActionListener(event ->
                applyVisualStartingPlaneSafely(true));
    }

    private void saveObservation() {
        final SectionObservation observation = new SectionObservation(
                Objects.requireNonNull((org.atlasalign.application.manual.SectionGeometry)
                        geometry.getSelectedItem()),
                reflected.isSelected()
                        ? AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_RIGHT
                        : AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                Objects.requireNonNull((ObservedAnatomicalHemisphere)
                        hemisphere.getSelectedItem()),
                lateralityConfirmed.isSelected(),
                reflectionConfirmed.isSelected());
        applySafely(() -> session.apply(
                new ManualWorkflowEdit.SetSectionObservation(observation)),
                "Saved section observation");
    }

    private void newOutlineContour() {
        if (session.content().sectionObservation().isEmpty()) {
            showStage(ManualAlignmentStage.PREPARE_SECTION);
            showStatus("Save the section observation before drawing the outline.",
                    true);
            return;
        }
        final ManualContourKind kind = Objects.requireNonNull(
                (ManualContourKind) outlineKind.getSelectedItem());
        final ContourTopology topology = kind == ManualContourKind.TISSUE_OUTLINE
                ? ContourTopology.CLOSED : ContourTopology.OPEN;
        newContour(kind, topology,
                Objects.requireNonNull((AnatomicalSide)
                        outlineSide.getSelectedItem()),
                Objects.requireNonNull((ContourCompleteness)
                        outlineCompleteness.getSelectedItem()),
                Optional.empty());
    }

    private void suggestOutlineFromImageContrast() {
        try {
            final GuidedManualWorkflowContent content = session.content();
            final SectionObservation observation = content.sectionObservation()
                    .orElseThrow(() -> new IllegalStateException(
                            "Save the section observation before suggesting an outline"));
            if (observation.geometry()
                    != org.atlasalign.application.manual.SectionGeometry.FULL) {
                throw new IllegalStateException(
                        "Automatic outline suggestion currently requires a complete FULL section; draw the visible boundary and midline manually for half or partial tissue");
            }
            if (content.contours().values().stream().anyMatch(contour ->
                    contour.kind() == ManualContourKind.TISSUE_OUTLINE)) {
                throw new IllegalStateException(
                        "A tissue outline already exists. Refine it or remove it before creating another suggestion");
            }
            final var segmentation = reviewModel.reviewState().basis()
                    .segmentation().orElseThrow(() ->
                            new IllegalStateException(
                                    "No verified copied-preview tissue mask is available; draw the outline manually"));
            final String id = nextContourId("auto-mask-outline");
            final ManualContour proposal = new AutomaticTissueOutlineProposer()
                    .propose(id, segmentation, previewMapping,
                            sourceIdentity, reviewModel.preview().pixels(),
                            reviewModel.reviewState().basis()
                                    .previewDimensions(),
                            ((Number) suggestedOutlineVertices.getValue())
                                    .intValue());
            session.apply(new ManualWorkflowEdit
                    .InsertAutomaticTissueOutline(proposal));
            activeContourId = Optional.of(id);
            pendingContour = Optional.empty();
            drawTool.setSelected(true);
            panViewportTool.setSelected(false);
            showStatus("Suggested " + id
                    + " from copied-preview contrast. Inspect and refine the cyan boundary, then click Finish contour.",
                    false);
            refresh();
        } catch (final RuntimeException error) {
            showError(error);
        }
    }

    private String nextContourId(final String prefix) {
        String id;
        do {
            id = prefix + "-" + contourSequence.incrementAndGet();
        } while (session.content().contours().containsKey(id));
        return id;
    }

    private void newStructureContour() {
        if (session.content().sectionObservation().isEmpty()) {
            showStage(ManualAlignmentStage.PREPARE_SECTION);
            showStatus("Save the section observation before tracing anatomy.",
                    true);
            return;
        }
        final VerifiedAtlasGuideIdentity identity = session.content()
                .selectedGuide().orElseThrow(() -> new IllegalStateException(
                        "Choose and save a verified anatomical guide first"));
        newContour(ManualContourKind.ANATOMICAL_STRUCTURE,
                Objects.requireNonNull((ContourTopology)
                        structureTopology.getSelectedItem()),
                Objects.requireNonNull((AnatomicalSide)
                        structureSide.getSelectedItem()),
                Objects.requireNonNull((ContourCompleteness)
                        structureCompleteness.getSelectedItem()),
                Optional.of(identity));
    }

    private void newContour(
            final ManualContourKind kind,
            final ContourTopology topology,
            final AnatomicalSide side,
            final ContourCompleteness completeness,
            final Optional<VerifiedAtlasGuideIdentity> guideIdentity) {
        final String id = nextContourId(
                kind.name().toLowerCase(java.util.Locale.ROOT));
        activeContourId = Optional.of(id);
        pendingContour = Optional.of(new PendingContour(
                id, kind, topology, side, completeness, guideIdentity));
        // A draft is materialized on the first tissue click because the pure
        // contract intentionally rejects zero-vertex contours.
        showStatus("Click the tissue pane to start " + id + ".", false);
        refresh();
    }

    private Optional<PendingContour> pendingContour = Optional.empty();

    private void chooseGuide() {
        final ManualGuideOption option = Objects.requireNonNull(
                (ManualGuideOption) guide.getSelectedItem());
        resolvedGuide = guideCatalog.resolve(option).orElseThrow(
                () -> new IllegalStateException(
                        "The verified atlas has no exact guide "
                                + option.acronym()));
        applySafely(() -> session.apply(
                new ManualWorkflowEdit.ChooseAnatomicalGuide(
                        resolvedGuide.identity())),
                "Selected guide " + option.acronym());
        refreshAtlasReference();
    }

    private void adjustNeutralLevel(final int delta) {
        neutralAtlasLevel.setValue(Math.max(neutralAtlasLevel.getMinimum(),
                Math.min(neutralAtlasLevel.getMaximum(),
                        neutralAtlasLevel.getValue() + delta)));
    }

    private void adjustNeutralTilt(
            final double sagittalDelta,
            final double horizontalDelta) {
        setNeutralTilt(new AtlasPlaneTilt(
                clampTilt(neutralAtlasTilt.sagittalDegrees()
                        + sagittalDelta),
                clampTilt(neutralAtlasTilt.horizontalDegrees()
                        + horizontalDelta)));
    }

    private static double clampTilt(final double value) {
        return Math.max(-AtlasPlaneTilt.MAXIMUM_ABSOLUTE_DEGREES,
                Math.min(AtlasPlaneTilt.MAXIMUM_ABSOLUTE_DEGREES, value));
    }

    private void resetNeutralTilt() {
        setNeutralTilt(AtlasPlaneTilt.CORONAL);
    }

    private void setNeutralTilt(final AtlasPlaneTilt value) {
        neutralAtlasTilt = Objects.requireNonNull(value, "value");
        synchronizingNeutralTiltControls = true;
        try {
            neutralSagittalTilt.setValue((int) Math.round(
                    value.sagittalDegrees()));
            neutralHorizontalTilt.setValue((int) Math.round(
                    value.horizontalDegrees()));
        } finally {
            synchronizingNeutralTiltControls = false;
        }
        requestNeutralAtlasLevel(neutralAtlasLevel.getValue());
    }

    private void neutralTiltSliderChanged() {
        if (synchronizingNeutralTiltControls) {
            return;
        }
        neutralTiltLabel.setText(wrappedText(String.format(
                java.util.Locale.ROOT,
                "Visual cutting plane: sagittal %.1f°, horizontal %.1f°",
                (double) neutralSagittalTilt.getValue(),
                (double) neutralHorizontalTilt.getValue())));
        if (neutralSagittalTilt.getValueIsAdjusting()
                || neutralHorizontalTilt.getValueIsAdjusting()) {
            return;
        }
        final AtlasPlaneTilt selected = new AtlasPlaneTilt(
                neutralSagittalTilt.getValue(),
                neutralHorizontalTilt.getValue());
        if (!selected.equals(neutralAtlasTilt)) {
            setNeutralTilt(selected);
        }
    }

    private void requestNeutralAtlasLevel(final int level) {
        clearOutlineWarpPreview(
                "Loading the selected atlas plane before rebuilding the outline warp…");
        final long request = neutralAtlasRequestSequence.incrementAndGet();
        neutralAtlasPlane = Optional.empty();
        neutralAtlasLevelLabel.setText(wrappedText(
                "Neutral atlas reference level " + level + " — "
                + (level * 25) + " µm from anterior origin (not bregma)"));
        neutralTiltLabel.setText(wrappedText(String.format(
                java.util.Locale.ROOT,
                "Visual cutting plane: sagittal %.1f°, horizontal %.1f°",
                neutralAtlasTilt.sagittalDegrees(),
                neutralAtlasTilt.horizontalDegrees())));
        guideStatus.setText(wrappedText(
                "Loading contour-only neutral atlas reference…"));
        refreshAtlasReference();
        reviewController.loadNeutralAtlasReference(
                level, neutralAtlasTilt, loaded -> {
            if (request != neutralAtlasRequestSequence.get()) {
                return;
            }
            neutralAtlasPlane = Optional.of(loaded);
            refreshAtlasReference();
        }, error -> {
            if (request != neutralAtlasRequestSequence.get()) {
                return;
            }
            neutralAtlasPlane = Optional.empty();
            guideStatus.setText(wrappedText(
                    "Neutral atlas reference unavailable: " + error));
            refreshAtlasReference();
        });
    }

    private void finishActiveContour() {
        final ManualContour contour = activeContour().orElseThrow(
                () -> new IllegalStateException("Select a contour first"));
        final ManualContour completed = contour.withCaptureStatus(
                ContourCaptureStatus.COMPLETE);
        applySafely(() -> session.apply(
                new ManualWorkflowEdit.UpsertContour(completed)),
                "Completed contour " + contour.id());
    }

    private void removeActiveContour() {
        final String id = activeContourId.orElseThrow(
                () -> new IllegalStateException("Select a contour first"));
        if (!session.content().contours().containsKey(id)) {
            pendingContour = Optional.empty();
            activeContourId = Optional.empty();
            refresh();
            return;
        }
        applySafely(() -> session.apply(
                new ManualWorkflowEdit.RemoveContour(id)),
                "Removed contour " + id);
        activeContourId = Optional.empty();
    }

    private void toggleLastGap() {
        final ManualContour contour = activeContour().orElseThrow(
                () -> new IllegalStateException("Select a contour first"));
        if (contour.vertices().size() < 2) {
            throw new IllegalStateException(
                    "A contour needs two vertices before a gap can be marked");
        }
        final List<ContourVertex> vertices = contour.vertices();
        final ContourSegment segment = new ContourSegment(
                vertices.get(vertices.size() - 2).id(),
                vertices.get(vertices.size() - 1).id());
        final Set<ContourSegment> gaps = new LinkedHashSet<>(
                contour.excludedGapSegments());
        if (!gaps.remove(segment)) {
            gaps.add(segment);
        }
        final ManualContour changed = copyContour(contour, vertices, gaps,
                contour.captureStatus());
        applySafely(() -> session.apply(
                new ManualWorkflowEdit.UpsertContour(changed)),
                "Toggled missing segment on " + contour.id());
    }

    private void activateGapTool() {
        markGapTool.setSelected(true);
        canvas.setDrawingEnabled(false);
        canvas.setPanTool(false);
        canvas.setGapTool(true);
        showStatus("Click each contour segment that belongs to the tear or non-matching fissure; marked segments turn dashed red.", false);
    }

    private void applyVisualStartingPlaneSafely(
            final boolean useOutlineWarp) {
        try {
            applyVisualStartingPlane(useOutlineWarp);
        } catch (final RuntimeException error) {
            showError(error);
        }
    }

    /**
     * Hands the reviewer-selected plane directly to the existing refinement
     * surface. No candidate ranking, score, or confidence value is created.
     */
    private void applyVisualStartingPlane(
            final boolean useOutlineWarp) {
        final GuidedManualWorkflowContent content = session.content();
        final SectionObservation observation = content.sectionObservation()
                .orElseThrow(() -> new IllegalStateException(
                        "Save the section type, orientation, and laterality first"));
        final VerifiedAtlasGuideIdentity selectedGuide = content
                .selectedGuide().orElseThrow(() ->
                        new IllegalStateException(
                                "Choose and save a verified anatomical guide first"));
        final AtlasCoronalPlane plane = neutralAtlasPlane.orElseThrow(() ->
                new IllegalStateException(
                        "Wait for the selected verified atlas plane to load"));
        if (plane.zeroBasedAnteriorPosteriorIndex()
                != neutralAtlasLevel.getValue()) {
            throw new IllegalStateException(
                    "Wait for the exact selected atlas plane to load");
        }

        final Optional<ReviewedOutlineTransform2D> outlineWarp;
        final AffineTransform2D atlasToPreview;
        final boolean anchorsConfirmed;
        final String method;
        if (useOutlineWarp) {
            if (observation.geometry()
                    != org.atlasalign.application.manual.SectionGeometry.FULL
                    || observation.observedHemisphere()
                            != ObservedAnatomicalHemisphere.BOTH) {
                throw new IllegalStateException(
                        "Whole-outline warping is available only for a complete FULL bilateral section");
            }
            final ManualOutlineWarpPreview preview = outlineWarpPreview
                    .orElseThrow(() -> new IllegalStateException(
                            "Finish one valid closed outline and wait for its exact map"));
            outlineWarp = Optional.of(preview.outlineTransform());
            atlasToPreview = preview.atlasToPreviewAffine();
            anchorsConfirmed = true;
            method = "manual-visual-starting-plane+boundary-authoritative-outline-v1"
                    + ";outlineFitMethod="
                    + preview.outlineFitMethod()
                    + ";outlineWarpSha256=" + preview.contentSha256();
        } else {
            outlineWarp = Optional.empty();
            atlasToPreview = currentPlacementForOrientation(
                    observation.orientation());
            anchorsConfirmed = false;
            method = "manual-visual-starting-plane-current-placement-v1";
        }
        final ReviewEdit.ApplyGuidedManualStartingPlane edit =
                new ReviewEdit.ApplyGuidedManualStartingPlane(
                        new AllenCoronalLevel(neutralAtlasLevel.getValue()),
                        neutralAtlasTilt, observation.orientation(),
                        observation.observedHemisphere(), atlasToPreview,
                        outlineWarp, anchorsConfirmed, method,
                        useOutlineWarp
                                ? completedFullOutlineIdentifiers()
                                : List.of(),
                        sourceIdentity.pixelSha256(),
                        atlasIdentity.identitySha256());
        reviewController.applyGuidedManualStartingPlane(edit);
        reviewController.selectAtlasRegionExactAcronym(
                selectedGuide.acronym());
        workflowAppliedHandler.run();
        closeHandler.run();
    }

    /**
     * Keeps the current scale, translation, rotation, and visual bounds while
     * rebasing atlas handedness to the explicit wizard choice. Reflection is
     * always reviewer-selected and is never inferred from the source image.
     */
    private AffineTransform2D currentPlacementForOrientation(
            final AtlasOrientation chosenOrientation) {
        final var state = reviewModel.reviewState();
        final AffineTransform2D current =
                state.preOutlineAtlasToPreview();
        if (state.content().orientation().reflected()
                == chosenOrientation.reflected()) {
            return current;
        }
        final AffineTransform2D atlasReflection = new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                -1, 0, state.basis().atlas().atlasPlaneWidth() - 1.0,
                0, 1, 0);
        return atlasReflection.andThen(current);
    }

    private List<String> completedFullOutlineIdentifiers() {
        return session.content().contours().values().stream()
                .filter(contour -> contour.kind()
                        == ManualContourKind.TISSUE_OUTLINE)
                .filter(ManualContour::eligibleForPreview)
                .map(ManualContour::id)
                .sorted()
                .toList();
    }

    private void advance() {
        final ManualAlignmentStage stage = session.displayedStage();
        try {
            validateStage(stage);
            if (!session.content().completedStages().contains(stage)) {
                session.apply(new ManualWorkflowEdit.CompleteStage(stage));
            }
            final int current = VISIBLE_STAGES.indexOf(stage);
            if (current >= 0 && current + 1 < VISIBLE_STAGES.size()) {
                session.showStage(VISIBLE_STAGES.get(current + 1));
            }
            refresh();
        } catch (final RuntimeException error) {
            showError(error);
        }
    }

    private void showPreviousVisibleStage() {
        final int current = VISIBLE_STAGES.indexOf(
                session.displayedStage());
        if (current > 0) {
            session.showStage(VISIBLE_STAGES.get(current - 1));
        }
    }

    private void validateStage(final ManualAlignmentStage stage) {
        final GuidedManualWorkflowContent content = session.content();
        switch (stage) {
            case PREPARE_SECTION -> content.sectionObservation().orElseThrow(
                    () -> new IllegalStateException(
                            "Save an explicit section observation first"));
            case DRAW_TISSUE_OUTLINE -> {
                content.requireGeometrySpecificTissueContours();
            }
            case CHOOSE_ANATOMICAL_GUIDE -> content.selectedGuide()
                    .orElseThrow(() -> new IllegalStateException(
                            "Choose and save a verified guide first"));
            case DRAW_STRUCTURE_ON_TISSUE,
                    PREVIEW_CANDIDATES,
                    REVIEW_CANDIDATES,
                    REFINE_SELECTED_CANDIDATE,
                    REVIEW_AND_ACCEPT -> throw new IllegalArgumentException(
                            "Legacy candidate-search stages are not part of the guided manual flow");
        }
    }

    private void redo() {
        final List<ManualWorkflowRevision> choices = session.redoChoices();
        if (choices.isEmpty()) {
            showStatus("Nothing to redo.", false);
            return;
        }
        final ManualWorkflowRevision chosen;
        if (choices.size() == 1) {
            chosen = choices.get(0);
        } else if (GraphicsEnvironment.isHeadless()) {
            chosen = choices.get(choices.size() - 1);
        } else {
            final Object result = JOptionPane.showInputDialog(
                    this,
                    "Choose a preserved redo branch:",
                    "Redo branch",
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    choices.stream().map(this::revisionLabel).toArray(),
                    revisionLabel(choices.get(choices.size() - 1)));
            if (result == null) {
                return;
            }
            final String label = result.toString();
            chosen = choices.stream().filter(value -> revisionLabel(value)
                    .equals(label)).findFirst().orElseThrow();
        }
        session.redo(chosen.id());
        refresh();
    }

    private void showStage(final ManualAlignmentStage stage) {
        if (!VISIBLE_STAGES.contains(stage)) {
            throw new IllegalArgumentException(
                    "Legacy candidate-search stages are not part of the guided manual flow");
        }
        session.showStage(stage);
        refresh();
    }

    private void selectContour(final JComboBox<String> selector) {
        final Object selected = selector.getSelectedItem();
        if (selected != null) {
            activeContourId = Optional.of(selected.toString());
            pendingContour = Optional.empty();
            refresh();
        }
    }

    private Optional<ManualContour> activeContour() {
        return activeContourId.map(session.content().contours()::get)
                .filter(Objects::nonNull);
    }

    private void refresh() {
        final ManualAlignmentStage stage = session.displayedStage();
        cards.show(stageCards, stage.name());
        final GuidedManualWorkflowContent content = session.content();
        for (int index = 0; index < stageButtons.length; index++) {
            final ManualAlignmentStage item = VISIBLE_STAGES.get(index);
            final boolean current = item == stage;
            final ArtifactStatus artifact = content.artifactStatuses().get(item);
            stageButtons[index].setText(STEP_LABELS[index]
                    + (artifact == ArtifactStatus.STALE ? " ⚠" : ""));
            stageButtons[index].setEnabled(!current);
        }
        final int visibleIndex = VISIBLE_STAGES.indexOf(stage);
        back.setEnabled(visibleIndex > 0);
        next.setEnabled(visibleIndex >= 0
                && visibleIndex + 1 < VISIBLE_STAGES.size());
        undo.setEnabled(session.currentRevision().parentId().isPresent());
        redo.setEnabled(!session.redoChoices().isEmpty());
        resetStep.setEnabled(content.artifactStatuses().containsKey(stage)
                || content.completedStages().contains(stage));
        resetAll.setEnabled(!content.equals(
                GuidedManualWorkflowContent.empty()));
        refreshOutlineSuggestion(content);
        final boolean drawingStage = isDrawingStage();
        final boolean drawingPrerequisitesMet = !drawingStage
                || content.sectionObservation().isPresent();
        drawTool.setEnabled(drawingStage && drawingPrerequisitesMet);
        final boolean outlineGapEditing = stage
                == ManualAlignmentStage.DRAW_TISSUE_OUTLINE
                && activeContour().isPresent();
        markGapTool.setEnabled(outlineGapEditing);
        if (!outlineGapEditing) {
            markGapTool.setSelected(false);
        }
        canvas.setDrawingEnabled(drawingStage && drawingPrerequisitesMet
                && drawTool.isSelected());
        canvas.setPanTool(panViewportTool.isSelected());
        canvas.setGapTool(outlineGapEditing && markGapTool.isSelected());
        canvas.setContours(content.contours(), activeContourId);
        refreshContourSelectors(content);
        syncSelectedGuide(content);
        refreshAtlasReference();
        final boolean fullSection = content.sectionObservation()
                .map(value -> value.geometry()
                        == org.atlasalign.application.manual.SectionGeometry.FULL)
                .orElse(false);
        continueWithoutOutlineWarp.setEnabled(
                content.sectionObservation().isPresent()
                        && content.selectedGuide().isPresent()
                        && neutralAtlasPlane.isPresent());
        continueWithOutlineWarp.setEnabled(fullSection
                && outlineWarpPreview.isPresent()
                && !outlinePreviewRunning);
        canvas.setEditableOutlineAnchor(Optional.empty());
        refreshHistory();
        revalidate();
        repaint();
    }

    private boolean isDrawingStage() {
        final ManualAlignmentStage stage = session.displayedStage();
        return stage == ManualAlignmentStage.DRAW_TISSUE_OUTLINE
                || stage == ManualAlignmentStage.DRAW_STRUCTURE_ON_TISSUE;
    }

    private void refreshContourSelectors(
            final GuidedManualWorkflowContent content) {
        final Object outlineSelected = outlineContours.getSelectedItem();
        final Object structureSelected = structureContours.getSelectedItem();
        refreshingSelectors = true;
        try {
            outlineContours.removeAllItems();
            structureContours.removeAllItems();
            content.contours().values().stream()
                    .sorted(Comparator.comparing(ManualContour::id))
                    .forEach(contour -> {
                        if (contour.kind()
                                == ManualContourKind.ANATOMICAL_STRUCTURE) {
                            structureContours.addItem(contour.id());
                        } else {
                            outlineContours.addItem(contour.id());
                        }
                    });
            restoreSelection(outlineContours, outlineSelected);
            restoreSelection(structureContours, structureSelected);
        } finally {
            refreshingSelectors = false;
        }
    }

    private void refreshOutlineSuggestion(
            final GuidedManualWorkflowContent content) {
        final boolean hasSegmentation = reviewModel.reviewState().basis()
                .segmentation().isPresent();
        suggestOutline.setEnabled(hasSegmentation);
        final Optional<ManualContour> proposed = content.contours().values()
                .stream()
                .filter(contour -> contour.automaticProposalProvenance()
                        .isPresent())
                .findFirst();
        if (proposed.isPresent()) {
            final ManualContour contour = proposed.orElseThrow();
            final var provenance = contour.automaticProposalProvenance()
                    .orElseThrow();
            outlineSuggestionStatus.setText(wrappedText(
                    "Contrast proposal " + contour.id() + ": "
                            + contour.vertices().size() + " vertices; "
                            + provenance.segmentationMethod() + ", "
                            + provenance.segmentationPolarity()
                            + (provenance.reviewerModified()
                                    ? "; reviewer-refined"
                                    : "; not yet manually changed")
                            + (contour.eligibleForPreview()
                                    ? "; explicitly finished."
                                    : "; draft — inspect and click Finish contour.")));
        } else if (!hasSegmentation) {
            outlineSuggestionStatus.setText(wrappedText(
                    "No copied-preview tissue mask is available. Draw the outline manually."));
        } else {
            outlineSuggestionStatus.setText(wrappedText(
                    "Ready to propose a boundary from the existing copied-preview tissue mask."));
        }
    }

    private void syncSelectedGuide(
            final GuidedManualWorkflowContent content) {
        if (content.selectedGuide().isEmpty()) {
            resolvedGuide = null;
            guideStatus.setText(wrappedText(
                    "Choose a guide from the verified Allen ontology."));
            return;
        }
        final VerifiedAtlasGuideIdentity identity =
                content.selectedGuide().orElseThrow();
        final ManualGuideOption option = guideCatalog.options().stream()
                .filter(candidate -> candidate.acronym().equals(
                        identity.acronym()))
                .findFirst().orElse(null);
        if (option == null) {
            resolvedGuide = null;
            guideStatus.setText(wrappedText(
                    "The historical guide is not supported by this Phase 5 build."));
            return;
        }
        final Optional<ManualGuideCatalog.ResolvedGuide> resolved =
                guideCatalog.resolve(option).filter(candidate ->
                        candidate.identity().equals(identity));
        resolvedGuide = resolved.orElse(null);
        guide.setSelectedItem(option);
        if (resolvedGuide == null) {
            guideStatus.setText(wrappedText(
                    "The historical guide no longer matches the verified atlas identity."));
        }
    }

    private static void restoreSelection(
            final JComboBox<String> combo,
            final Object wanted) {
        if (wanted != null) {
            combo.setSelectedItem(wanted);
        }
    }

    private void refreshAtlasReference() {
        if (canvas == null || reviewModel == null) {
            return;
        }
        canvas.setNeutralGuidePreviewPoints(List.of());
        canvas.setCandidateOverlay(Optional.empty());
        final Optional<AtlasCoronalPlane> plane = neutralAtlasPlane;
        Optional<SelectedAtlasContour> contour = Optional.empty();
        if (resolvedGuide == null && plane.isPresent()) {
            guideStatus.setText(wrappedText(
                    "Neutral reference loaded. Choose a verified guide; "
                    + "this browser shows no automatic result or overlay."));
        }
        if (resolvedGuide != null && plane.isPresent()) {
            contour = Optional.of(SelectedAtlasContour.from(
                    plane.orElseThrow(), resolvedGuide.region()));
            final Optional<SectionObservation> observation = session.content()
                    .sectionObservation();
            final boolean matchingOrientation = observation.isPresent()
                    && observation.orElseThrow().orientation().reflected()
                            == reviewModel.reviewState().content()
                                    .orientation().reflected();
            if (contour.orElseThrow().isPresent() && matchingOrientation) {
                final var preOutline = reviewModel.reviewState()
                        .preOutlineAtlasToPreview();
                canvas.setNeutralGuidePreviewPoints(
                        contour.orElseThrow().boundaryPoints().stream()
                                .map(preOutline::apply)
                                .toList());
            }
            guideStatus.setText(wrappedText(
                    resolvedGuide.option().displayName() + " — "
                    + resolvedGuide.option().drawingHint()
                    + (contour.orElseThrow().isPresent()
                    ? matchingOrientation
                            ? " The cyan reference is present on the atlas and as a faint display-only guide over the tissue. Finish the outline to replace it with the shared warped guide."
                            : " The cyan reference is present on the atlas, but the tissue preview is hidden until the wizard orientation matches the current review orientation."
                    : " This structure is absent on the current plane.")));
        }
        canvas.setAtlasReference(plane, contour);
        refreshOutlineWarpPreview(plane);
    }

    private void refreshOutlineWarpPreview(
            final Optional<AtlasCoronalPlane> plane) {
        final GuidedManualWorkflowContent content = session.content();
        if (plane.isEmpty() || resolvedGuide == null
                || content.sectionObservation().isEmpty()) {
            clearOutlineWarpPreview(
                    "Save the section observation, choose a plane and guide, then complete one full-section outline.");
            return;
        }
        final SectionObservation observation = content.sectionObservation()
                .orElseThrow();
        if (observation.geometry()
                != org.atlasalign.application.manual.SectionGeometry.FULL) {
            clearOutlineWarpPreview(
                    "Half/partial outline deformation is intentionally unavailable in this milestone; use the existing affine/manual tools.");
            return;
        }
        final List<ManualContour> outlines = content.contours().values()
                .stream()
                .filter(value -> value.kind()
                        == ManualContourKind.TISSUE_OUTLINE)
                .filter(ManualContour::eligibleForPreview)
                .toList();
        if (outlines.size() != 1) {
            clearOutlineWarpPreview(
                    "Finish exactly one closed, complete, undamaged full-section outline. The finished outline becomes the exact outer boundary; AtlasAlign does not guess across missing or ambiguous intervals.");
            return;
        }
        final ManualContour outline = outlines.get(0);
        final OutlinePreviewKey key = new OutlinePreviewKey(
                plane.orElseThrow().zeroBasedAnteriorPosteriorIndex(),
                neutralAtlasTilt, observation, outline,
                resolvedGuide.identity());
        if (outlinePreviewKey.equals(Optional.of(key))
                && outlinePreviewRunning) {
            outlineWarpStatus.setText(wrappedText(
                    "Building the exact reviewed-outline map… The selected level and tilts remain unchanged."));
            continueWithOutlineWarp.setEnabled(false);
            return;
        }
        if (outlinePreviewKey.equals(Optional.of(key))
                && outlineWarpPreview.isPresent()) {
            canvas.setOutlineWarpPreview(outlineWarpPreview);
            showOutlineWarpReady();
            return;
        }
        if (failedOutlinePreviewKey.equals(Optional.of(key))) {
            outlineWarpStatus.setText(wrappedText(
                    failedOutlinePreviewMessage));
            traceWarpStatus.setText(outlineWarpStatus.getText());
            continueWithOutlineWarp.setEnabled(false);
            return;
        }
        final ManualGuideOption rootOption = guideCatalog.options().stream()
                .filter(option -> option.acronym().equals("root"))
                .findFirst().orElseThrow();
        final SelectedAtlasRegion rootRegion = guideCatalog.resolve(rootOption)
                .orElseThrow().region();
        final AtlasCoronalPlane capturedPlane = plane.orElseThrow();
        final SelectedAtlasRegion capturedGuide = resolvedGuide.region();
        final List<ManualContour> capturedContours = List.copyOf(
                content.contours().values());
        final long token = outlinePreviewRequestSequence.incrementAndGet();
        outlinePreviewRevisionId = session.currentRevision().id();
        outlinePreviewRunning = true;
        outlinePreviewKey = Optional.of(key);
        failedOutlinePreviewKey = Optional.empty();
        failedOutlinePreviewMessage = "";
        outlineWarpPreview = Optional.empty();
        canvas.setOutlineWarpPreview(Optional.empty());
        outlineWarpStatus.setText(wrappedText(
                "Building the exact reviewed-outline map… The selected level and tilts remain unchanged."));
        continueWithOutlineWarp.setEnabled(false);
        if (outlinePreviewDebounceTimer != null) {
            outlinePreviewDebounceTimer.stop();
        }
        final Timer requested = new Timer(
                OUTLINE_PREVIEW_DEBOUNCE_MILLISECONDS, event -> {
                    if (token != outlinePreviewRequestSequence.get()
                            || !outlinePreviewKey.equals(Optional.of(key))) {
                        return;
                    }
                    outlinePreviewDebounceTimer = null;
                    CompletableFuture.supplyAsync(() ->
                            ManualCandidateMatcher.outlinePreview(
                                    capturedPlane, rootRegion, capturedGuide,
                                    capturedContours, observation,
                                    previewMapping), outlinePreviewExecutor)
                            .whenComplete((built, failure) ->
                                    SwingUtilities.invokeLater(() ->
                                            finishOutlinePreview(token, key,
                                                    built, failure)));
                });
        requested.setRepeats(false);
        outlinePreviewDebounceTimer = requested;
        requested.start();
    }

    private void finishOutlinePreview(
            final long token,
            final OutlinePreviewKey key,
            final ManualOutlineWarpPreview built,
            final Throwable failure) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "Outline-preview completion must run on the Swing EDT");
        }
        if (reviewController.isClosed()
                || token != outlinePreviewRequestSequence.get()
                || outlinePreviewRevisionId != session.currentRevision().id()
                || !outlinePreviewKey.equals(Optional.of(key))) {
            return;
        }
        outlinePreviewRunning = false;
        if (failure != null) {
            outlineWarpPreview = Optional.empty();
            canvas.setOutlineWarpPreview(Optional.empty());
            final Throwable cause = failure instanceof java.util.concurrent.CompletionException
                    && failure.getCause() != null
                    ? failure.getCause() : failure;
            failedOutlinePreviewKey = Optional.of(key);
            failedOutlinePreviewMessage = "Outline not applied: "
                    + plainGeometryFailure(cause);
            outlineWarpStatus.setText(wrappedText(
                    failedOutlinePreviewMessage));
            traceWarpStatus.setText(outlineWarpStatus.getText());
            refresh();
            return;
        }
        failedOutlinePreviewKey = Optional.empty();
        failedOutlinePreviewMessage = "";
        outlineWarpPreview = Optional.of(Objects.requireNonNull(built));
        canvas.setOutlineWarpPreview(outlineWarpPreview);
        showOutlineWarpReady();
        refresh();
    }

    private static String plainGeometryFailure(final Throwable failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank()
                ? "the reviewed boundary could not form a safe, non-overlapping map"
                : message;
    }

    private void showOutlineWarpReady() {
        final ManualOutlineWarpPreview preview =
                outlineWarpPreview.orElseThrow();
        final var currentReview = reviewController.state().content();
        outlineWarpStatus.setText(wrappedText(String.format(
                java.util.Locale.ROOT,
                "Exact reviewed-outline map ready. Selected plane: level %d, sagittal %.1f°, horizontal %.1f°. Current review is still level %d, sagittal %.1f°, horizontal %.1f°; drawing the outline changed none of these values. %s Purple follows the reviewed outer boundary by construction and the thin magenta structure guide uses the same map. Either button applies the selected plane explicitly; the outline option also carries this exact transform into manual left/right refinement.",
                neutralAtlasLevel.getValue(),
                neutralAtlasTilt.sagittalDegrees(),
                neutralAtlasTilt.horizontalDegrees(),
                currentReview.coronalLevel()
                        .zeroBasedAnteriorPosteriorIndex(),
                currentReview.atlasPlaneTilt().sagittalDegrees(),
                currentReview.atlasPlaneTilt().horizontalDegrees(),
                outlinePlacementChangeText(preview))));
        traceWarpStatus.setText(wrappedText(
                "Trace the visible structure against the thin magenta outline-warped guide. The right pane stays unwarped as the verified reference; the left pane shows the deformation that will be refined."));
    }

    private String outlinePlacementChangeText(
            final ManualOutlineWarpPreview preview) {
        final SectionObservation observation = session.content()
                .sectionObservation().orElseThrow();
        final AffineTransform2D current = currentPlacementForOrientation(
                observation.orientation());
        final AffineTransform2D proposed = preview.atlasToPreviewAffine();
        final double currentHorizontalScale = Math.hypot(
                current.m00(), current.m10());
        final double currentVerticalScale = Math.hypot(
                current.m01(), current.m11());
        final double horizontalChange = 100
                * (Math.hypot(proposed.m00(), proposed.m10())
                        / currentHorizontalScale - 1);
        final double verticalChange = 100
                * (Math.hypot(proposed.m01(), proposed.m11())
                        / currentVerticalScale - 1);
        final var atlas = reviewModel.reviewState().basis().atlas();
        final Point2D atlasCenter = new Point2D(
                (atlas.atlasPlaneWidth() - 1.0) / 2.0,
                (atlas.atlasPlaneHeight() - 1.0) / 2.0);
        final Point2D currentCenter = current.apply(atlasCenter);
        final Point2D proposedCenter = proposed.apply(atlasCenter);
        final double rotationChange = normalizeDegrees(
                Math.toDegrees(Math.atan2(proposed.m10(), proposed.m00())
                        - Math.atan2(current.m10(), current.m00())));
        return String.format(java.util.Locale.ROOT,
                "If you choose outline fit, global placement will change: horizontal size %+.1f%%, vertical size %+.1f%%, centre %+.1f px right / %+.1f px down, rotation %+.1f°.",
                horizontalChange, verticalChange,
                proposedCenter.x() - currentCenter.x(),
                proposedCenter.y() - currentCenter.y(), rotationChange);
    }

    private static double normalizeDegrees(final double degrees) {
        double normalized = degrees;
        while (normalized <= -180) {
            normalized += 360;
        }
        while (normalized > 180) {
            normalized -= 360;
        }
        return normalized;
    }

    private static void configureTiltSlider(
            final JSlider slider,
            final String tooltip) {
        slider.setMajorTickSpacing(15);
        slider.setMinorTickSpacing(1);
        slider.setPaintTicks(true);
        slider.setPaintLabels(true);
        slider.setToolTipText(tooltip);
    }

    private void clearOutlineWarpPreview(final String message) {
        outlinePreviewRequestSequence.incrementAndGet();
        if (outlinePreviewDebounceTimer != null) {
            outlinePreviewDebounceTimer.stop();
            outlinePreviewDebounceTimer = null;
        }
        outlinePreviewRunning = false;
        outlineWarpPreview = Optional.empty();
        outlinePreviewKey = Optional.empty();
        failedOutlinePreviewKey = Optional.empty();
        failedOutlinePreviewMessage = "";
        if (canvas != null) {
            canvas.setOutlineWarpPreview(Optional.empty());
        }
        outlineWarpStatus.setText(wrappedText(message));
        traceWarpStatus.setText(wrappedText(message));
    }

    private void refreshHistory() {
        final long current = session.currentRevision().id();
        final StringBuilder text = new StringBuilder();
        for (final ManualWorkflowRevision revision : session.visibleHistory()) {
            text.append(revision.id() == current ? "▶ " : "  ")
                    .append(revisionLabel(revision)).append('\n');
        }
        history.setText(text.toString());
        history.setCaretPosition(Math.max(0, history.getDocument().getLength()));
    }

    private String revisionLabel(final ManualWorkflowRevision revision) {
        return "r" + revision.id() + " ← "
                + (revision.parentId().isPresent()
                ? "r" + revision.parentId().orElseThrow() : "root")
                + " · " + revision.stage() + " · " + revision.description();
    }

    private ManualContour copyContour(
            final ManualContour original,
            final List<ContourVertex> vertices,
            final Set<ContourSegment> gaps,
            final ContourCaptureStatus capture) {
        final boolean geometryChanged = !vertices.equals(original.vertices())
                || !gaps.equals(original.excludedGapSegments());
        return new ManualContour(original.id(), original.kind(),
                original.topology(), capture, original.anatomicalSide(),
                original.completeness(), original.atlasGuide(),
                original.sourceIdentity(), vertices, gaps,
                geometryChanged
                        ? original.automaticProposalProvenance().map(
                                provenance -> provenance
                                        .markReviewerModified())
                        : original.automaticProposalProvenance());
    }

    private record OutlinePreviewKey(
            int level,
            AtlasPlaneTilt tilt,
            SectionObservation observation,
            ManualContour outline,
            VerifiedAtlasGuideIdentity guideIdentity) { }

    private void applySafely(
            final java.util.function.Supplier<?> action,
            final String success) {
        try {
            action.get();
            showStatus(success + ".", false);
            refresh();
        } catch (final RuntimeException error) {
            showError(error);
        }
    }

    private void showError(final RuntimeException error) {
        final String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        showStatus(message, true);
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(this, message,
                    "Guided manual workflow", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void showStatus(final String message, final boolean error) {
        status.setText(wrappedText(message));
        status.setForeground(error ? new Color(170, 20, 20) : Color.DARK_GRAY);
    }

    private static JPanel section(
            final String title,
            final Component... components) {
        final JPanel panel = new ViewportWidthPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder(title));
        for (final Component component : components) {
            component.setMaximumSize(new Dimension(
                    Integer.MAX_VALUE,
                    Math.max(component.getPreferredSize().height, 24)));
            if (component instanceof JComponent swingComponent) {
                swingComponent.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            panel.add(component);
        }
        return panel;
    }

    private static JScrollPane verticalScroll(final Component component) {
        final JScrollPane scroll = new JScrollPane(component,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private static JLabel wrapped(final String text) {
        final JLabel label = new WrappedLabel(wrappedText(text));
        label.setVerticalAlignment(SwingConstants.TOP);
        return label;
    }

    private static JLabel warning(final String text) {
        final JLabel label = wrapped(text);
        label.setForeground(new Color(150, 55, 0));
        return label;
    }

    private static String wrappedText(final String text) {
        return "<html><body style='width:270px'>" + escapeHtml(text)
                + "</body></html>";
    }

    private static String escapeHtml(final String text) {
        return Objects.requireNonNull(text, "text")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static JButton button(
            final String text,
            final Runnable action) {
        final JButton result = new JButton(text);
        result.addActionListener(event -> action.run());
        return result;
    }

    private static JPanel buttonRow(final Component... components) {
        final JPanel row = new JPanel(new GridLayout(1,
                Math.max(1, components.length), 4, 2));
        for (final Component component : components) {
            row.add(component);
        }
        return row;
    }

    /** BoxLayout content that always contracts to its vertical viewport. */
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

    /**
     * HTML label whose maximum height follows its current wrapped text.
     *
     * <p>The surrounding BoxLayout assigns maximum sizes when the stage is
     * created. Several status labels become substantially longer after an
     * outline or atlas plane is loaded. A normal JLabel therefore retained
     * the height of its short initial message and clipped the later scientific
     * placement report. Keeping the width stretchable while deriving the
     * height from the current preferred size lets the vertical scroller expose
     * the complete message without introducing horizontal scrolling.</p>
     */
    private static final class WrappedLabel extends JLabel {
        WrappedLabel(final String text) {
            super(text);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE,
                    getPreferredSize().height);
        }
    }

    private final class CanvasListener implements GuidedManualCanvas.Listener {
        @Override
        public void addVertex(final String contourId, final Point2D source) {
            try {
                final ManualContour existing = session.content().contours()
                        .get(contourId);
                final ManualContour changed;
                if (existing == null) {
                    final PendingContour pending = pendingContour
                            .filter(value -> value.id().equals(contourId))
                            .orElseThrow(() -> new IllegalStateException(
                                    "Start a new contour first"));
                    changed = pending.firstVertex(
                            vertex(source), sourceIdentity);
                    pendingContour = Optional.empty();
                } else {
                    final ContourVertex appended = vertex(source);
                    if (existing.topology() == ContourTopology.CLOSED) {
                        changed = ManualContourVertexEdits.insertAfter(
                                existing,
                                existing.vertices().get(
                                        existing.vertices().size() - 1).id(),
                                appended);
                    } else {
                        final List<ContourVertex> vertices = new ArrayList<>(
                                existing.vertices());
                        vertices.add(appended);
                        changed = copyContour(existing, vertices,
                                existing.excludedGapSegments(),
                                existing.captureStatus());
                    }
                }
                session.apply(new ManualWorkflowEdit.UpsertContour(changed));
                showStatus("Added one source-space contour vertex.", false);
                refresh();
            } catch (final RuntimeException error) {
                showError(error);
            }
        }

        @Override
        public void insertAfter(
                final String contourId,
                final String vertexId,
                final Point2D source) {
            editContour(contourId, contour -> ManualContourVertexEdits
                    .insertAfter(contour, vertexId, vertex(source)),
                    "Inserted one contour vertex");
        }

        @Override
        public void moveVertex(
                final String contourId,
                final String vertexId,
                final Point2D source) {
            editVertices(contourId, vertices -> {
                final int index = indexOf(vertices, vertexId);
                vertices.set(index, new ContourVertex(vertexId,
                        new SourcePixelPoint(source.x(), source.y())));
                return vertices;
            }, "Moved one contour vertex");
        }

        @Override
        public void deleteVertex(
                final String contourId,
                final String vertexId) {
            final ManualContour contour = session.content().contours()
                    .get(contourId);
            if (contour != null && contour.vertices().size() == 1) {
                applySafely(() -> session.apply(
                        new ManualWorkflowEdit.RemoveContour(contourId)),
                        "Removed contour " + contourId);
                activeContourId = Optional.empty();
                return;
            }
            editContour(contourId, current ->
                    ManualContourVertexEdits.delete(current, vertexId),
                    "Deleted one contour vertex");
        }

        @Override
        public void toggleGap(
                final String contourId,
                final ContourSegment segment) {
            editContour(contourId, contour -> {
                final Set<ContourSegment> gaps = new LinkedHashSet<>(
                        contour.excludedGapSegments());
                if (!gaps.remove(segment)) {
                    gaps.add(segment);
                }
                return copyContour(contour, contour.vertices(), gaps,
                        contour.captureStatus());
            }, "Toggled reviewed gap segment");
        }

        @Override
        public void correctOutlineAnchor(
                final String anchorName,
                final boolean tissueEndpoint,
                final int loopIndex) {
            showStatus(
                    "Boundary-anchor editing is retired; edit the reviewed outline vertices directly.",
                    true);
        }

        private void editContour(
                final String contourId,
                final java.util.function.UnaryOperator<ManualContour> edit,
                final String description) {
            try {
                final ManualContour contour = session.content().contours()
                        .get(contourId);
                if (contour == null) {
                    throw new IllegalArgumentException(
                            "Unknown contour " + contourId);
                }
                session.apply(new ManualWorkflowEdit.UpsertContour(
                        edit.apply(contour)));
                showStatus(description + ".", false);
                refresh();
            } catch (final RuntimeException error) {
                showError(error);
            }
        }

        private void editVertices(
                final String contourId,
                final java.util.function.UnaryOperator<List<ContourVertex>> edit,
                final String description) {
            try {
                final ManualContour contour = session.content().contours()
                        .get(contourId);
                if (contour == null) {
                    throw new IllegalArgumentException(
                            "Unknown contour " + contourId);
                }
                final List<ContourVertex> vertices = edit.apply(
                        new ArrayList<>(contour.vertices()));
                final Set<ContourSegment> gaps = retainedAdjacentGaps(
                        contour, vertices);
                final int completedMinimum = contour.topology()
                        == ContourTopology.CLOSED ? 3 : 2;
                final ContourCaptureStatus capture = vertices.size()
                        >= completedMinimum ? contour.captureStatus()
                        : ContourCaptureStatus.DRAFT;
                final ManualContour changed = copyContour(
                        contour, vertices, gaps, capture);
                session.apply(new ManualWorkflowEdit.UpsertContour(changed));
                showStatus(description + ".", false);
                refresh();
            } catch (final RuntimeException error) {
                showError(error);
            }
        }

        private ContourVertex vertex(final Point2D point) {
            return new ContourVertex("v" + vertexSequence.incrementAndGet(),
                    new SourcePixelPoint(point.x(), point.y()));
        }

        private int indexOf(
                final List<ContourVertex> vertices,
                final String id) {
            for (int index = 0; index < vertices.size(); index++) {
                if (vertices.get(index).id().equals(id)) {
                    return index;
                }
            }
            throw new IllegalArgumentException("Unknown vertex " + id);
        }

        private Set<ContourSegment> retainedAdjacentGaps(
                final ManualContour contour,
                final List<ContourVertex> vertices) {
            final Set<ContourSegment> validEdges = new LinkedHashSet<>();
            for (int index = 0; index + 1 < vertices.size(); index++) {
                validEdges.add(new ContourSegment(
                        vertices.get(index).id(), vertices.get(index + 1).id()));
            }
            if (contour.topology() == ContourTopology.CLOSED
                    && vertices.size() > 1) {
                validEdges.add(new ContourSegment(
                        vertices.get(vertices.size() - 1).id(),
                        vertices.get(0).id()));
            }
            final Set<ContourSegment> retained = new LinkedHashSet<>(
                    contour.excludedGapSegments());
            retained.retainAll(validEdges);
            return retained;
        }
    }

    private record PendingContour(
            String id,
            ManualContourKind kind,
            ContourTopology topology,
            AnatomicalSide side,
            ContourCompleteness completeness,
            Optional<VerifiedAtlasGuideIdentity> guide) {

        private PendingContour {
            guide = Objects.requireNonNull(guide, "guide");
        }

        private ManualContour firstVertex(
                final ContourVertex vertex,
                final SourceImageIdentity sourceIdentity) {
            return new ManualContour(id, kind, topology,
                    ContourCaptureStatus.DRAFT, side, completeness, guide,
                    sourceIdentity,
                    List.of(vertex), Set.of());
        }
    }
}
