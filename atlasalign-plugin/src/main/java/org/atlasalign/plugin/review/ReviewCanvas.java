package org.atlasalign.plugin.review;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Function;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import org.atlasalign.application.AtlasAnatomicalSide;
import org.atlasalign.application.AtlasMembershipProjection;
import org.atlasalign.application.AlignmentReviewState;
import org.atlasalign.application.HalfAtlasCoverage;
import org.atlasalign.application.LandmarkPair;
import org.atlasalign.application.LandmarkRole;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.application.ReviewedTissueSupport;
import org.atlasalign.application.TissueMaskEnvelope;
import org.atlasalign.application.manual.BoundaryFitAnchor;
import org.atlasalign.application.manual.BoundaryFitCandidate;
import org.atlasalign.application.manual.BoundaryFitDraft;
import org.atlasalign.application.manual.BoundaryFitMatch;
import org.atlasalign.application.manual.BoundaryFitPreview;
import org.atlasalign.application.manual.BoundaryFitRequest;
import org.atlasalign.application.manual.BoundaryFitSample;
import org.atlasalign.application.manual.BoundaryWarpRequest;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualWarpControl;
import org.atlasalign.application.manual.ManualWarpControlOrigin;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;

/**
 * Read-only source/overlay and atlas panes with UI-only navigation and gesture
 * previews. Model edits are requested exactly once, when a gesture is
 * released, through {@link InteractionListener}.
 */
public final class ReviewCanvas extends JComponent {

    private static final int PREFERRED_WIDTH = 940;
    private static final int PREFERRED_HEIGHT = 650;
    private static final int PANE_GAP = 12;
    private static final int PANE_LABEL_HEIGHT = 24;
    private static final double MINIMUM_ZOOM = 0.25;
    private static final double MAXIMUM_ZOOM = 16;
    private static final double BUTTON_ZOOM_FACTOR = 1.25;
    private static final double HANDLE_HIT_RADIUS = 14;
    /** A compact visible ring with a forgiving, screen-space hit target. */
    static final double MANUAL_WARP_HIT_RADIUS = 7;
    static final int MANUAL_WARP_TARGET_DIAMETER = 9;
    static final int MANUAL_WARP_ORIGIN_DIAMETER = 7;
    static final int STRUCTURE_TARGET_DIAMETER = 15;
    static final int STRUCTURE_ORIGIN_DIAMETER = 9;
    private static final double ROTATION_HANDLE_DISTANCE = 28;
    private static final double REGION_SNAP_RADIUS_SCREEN = 18;
    /**
     * A selected anatomical target is a display aid, not a filled mask. Keep
     * it screen-thin at every viewport zoom so underlying tissue stays
     * visible while handles are moved.
     */
    static final float SELECTED_REGION_STROKE_WIDTH = 1.15f;
    static final Color SELECTED_REGION_COLOR = new Color(255, 24, 205, 245);
    static final Color DIM_REGION_COLOR = new Color(255, 120, 20, 190);
    static final float REVIEWED_BOUNDARY_STROKE_WIDTH = 1.1f;
    static final Color REVIEWED_BOUNDARY_COLOR = new Color(90, 220, 255, 245);

    private final ReviewController controller;
    private final ExecutorService renderExecutor;
    private volatile ReviewViewModel model;
    private volatile BufferedImage previewImage;
    private BufferedImage displayedChannelImage;
    private boolean channelDisplayEnabled;
    private boolean displayInspection;
    private String channelDisplayLabel;
    private volatile BufferedImage overlayImage;
    private volatile PlacementLayers placementOverlays;
    private volatile BufferedImage beforeOverlayImage;
    private volatile BufferedImage boundaryWarpGhostImage;
    private volatile BufferedImage exportedRoiPreviewImage;
    private volatile BufferedImage exportedRoiDimmingOverlay;
    private volatile BufferedImage atlasImage;
    private volatile RenderPaths renderPaths;
    private volatile RenderKey requestedRenderKey;
    /**
     * Bounded worker-owned cache. Each entry contains one anatomical side's
     * fully split tissue paths, so changing the left controls cannot force a
     * remap of the unchanged right-side paths.
     */
    private final Map<SidePathCacheKey, List<LineSegment>> sidePathCache =
            new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<SidePathCacheKey,
                                List<LineSegment>> eldest) {
                    return size() > 8;
                }
            };
    private SelectedAtlasContour selectedRegionContour;
    private int selectedRegionBoundaryCount;
    private InteractionTool interactionTool = InteractionTool.TRANSFORM;
    private InteractionListener interactionListener;
    private Gesture gesture;
    private Point dragOrigin;
    private Point tissueClickOrigin;
    private Point lastDragPoint;
    private double tissueZoom = 1;
    private double atlasZoom = 1;
    private double tissuePanX;
    private double tissuePanY;
    private double atlasPanX;
    private double atlasPanY;
    private boolean aspectRatioLocked = true;
    private boolean outerBoundariesOnly = true;
    private PlacementTool placementTool = PlacementTool.ALL;
    private boolean landmarkLabelsVisible;
    private boolean deformationGridVisible;
    private boolean displacementLinesVisible = true;
    private boolean tissueClippingEnabled;
    private boolean tissueSupportEditing;
    private boolean tissueSupportTracing;
    private final List<Point2D> tissueSupportTrace = new ArrayList<>();
    private Color selectedRegionColor = SELECTED_REGION_COLOR;
    private Color dimRegionColor = DIM_REGION_COLOR;
    private float regionStrokeWidth = 1.25f;
    private boolean singleTissuePane;
    private boolean boundaryFitEditingEnabled = true;
    private boolean boundaryWarpPreviewVisible;
    private boolean structureEditingEnabled;
    private boolean exportedRoiPreviewVisible;
    private final ManualRoiCanvasLayer manualRoiLayer =
            new ManualRoiCanvasLayer();
    private ComparisonMode comparisonMode = ComparisonMode.AFTER;
    private float overlayOpacity = 0.65f;
    private boolean spaceDown;
    private Point2D pendingAtlasPoint;
    private Point2D pendingBoundaryAtlasPoint;
    private String selectedBoundaryMatchId;
    private boolean manualWarpPointArmed;
    private ManualHemisphereWarp2D.AtlasSide activeHemisphereSide =
            ManualHemisphereWarp2D.AtlasSide.LEFT;
    private LandmarkCaptureListener landmarkCaptureListener =
            (atlasPoint, previewPoint) -> false;

    public ReviewCanvas(final ReviewController controller) {
        this.controller = Objects.requireNonNull(
                controller, "controller");
        renderExecutor = Executors.newSingleThreadExecutor(
                daemonThreadFactory("atlasalign-review-render"));
        interactionListener = new InteractionListener() {
            @Override
            public void translate(final double dx, final double dy) {
                controller.translate(dx, dy);
            }
        };
        setOpaque(true);
        setFocusable(true);
        setBackground(Color.BLACK);
        // Instructions live in the persistent toolbar/status areas. A Swing
        // tooltip here obscures the anatomy precisely while points are placed.
        setToolTipText(null);
        final MouseAdapter interaction = new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent event) {
                handleMousePressed(event);
            }

            @Override
            public void mouseDragged(final MouseEvent event) {
                handleMouseDragged(event);
            }

            @Override
            public void mouseReleased(final MouseEvent event) {
                handleMouseReleased(event);
            }

            @Override
            public void mouseWheelMoved(final MouseWheelEvent event) {
                handleMouseWheel(event);
            }
        };
        addMouseListener(interaction);
        addMouseMotionListener(interaction);
        addMouseWheelListener(interaction);
        // Add-one is armed from a Swing button, so focus can remain on that
        // button even after requestFocusInWindow(). Bind Escape for the whole
        // focused review window; otherwise an immediate Escape can be missed
        // and the next tissue click can create an unintended control.
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "cancel-canvas-gesture");
        getActionMap().put("cancel-canvas-gesture", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                cancelCurrentInteraction();
            }
        });
        getInputMap(WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, false),
                "canvas-space-down");
        getInputMap(WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true),
                "canvas-space-up");
        // A release can arrive after a toolbar or workflow control has taken
        // focus. Keep the window-wide release binding in addition to the
        // focused binding so Space-pan cannot remain latched while the
        // Transform cage is visible.
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0, true),
                "canvas-space-up");
        getActionMap().put("canvas-space-down", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                spaceDown = true;
            }
        });
        getActionMap().put("canvas-space-up", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                spaceDown = false;
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(final FocusEvent event) {
                spaceDown = false;
            }
        });
        bindNudge(KeyEvent.VK_LEFT, -1, 0); bindNudge(KeyEvent.VK_RIGHT, 1, 0);
        bindNudge(KeyEvent.VK_UP, 0, -1); bindNudge(KeyEvent.VK_DOWN, 0, 1);
    }

    private void bindNudge(final int key, final int dx, final int dy) {
        for (int modifiers : new int[]{0, KeyEvent.SHIFT_DOWN_MASK}) {
            final int step = modifiers == 0 ? 1 : 10;
            final String action = "nudge-" + key + "-" + step;
            getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, modifiers), action);
            getActionMap().put(action, new AbstractAction() {
                @Override public void actionPerformed(final ActionEvent event) {
                    if (interactionTool == InteractionTool.TRANSFORM) translateSourcePixels(dx * step, dy * step);
                }
            });
        }
    }

    public void translateSourcePixels(final double dx, final double dy) {
        if (!precisionEditingAvailable()) return;
        final var source = model.reviewState().basis().sourceSnapshot().metadata();
        interactionListener.translate(dx * model.preview().width() / source.width(), dy * model.preview().height() / source.height());
    }

    public boolean precisionEditingAvailable() {
        return model != null && !displayInspection && (!channelDisplayEnabled || displayedChannelImage != null)
                && manualInteractionEnabled() && !hasLocalDeformation() && !exactBoundaryMapActive()
                && !model.boundaryFit().active();
    }

    public Point2D transformPivot() {
        final Selection selection = baseSelection();
        if (selection == null) throw new IllegalStateException("Place the atlas before changing rotation or scale");
        return selection.center;
    }

    public void rotateDegrees(final double degrees) {
        if (!precisionEditingAvailable()) return;
        interactionListener.rotateRadians(Math.toRadians(degrees), transformPivot());
    }

    public void scalePercent(final double x, final double y) {
        if (!precisionEditingAvailable()) return;
        final Selection selection = baseSelection();
        interactionListener.scaleAxes(x / 100, (aspectRatioLocked ? x : y) / 100, selection.axisRadians, selection.center);
    }

    public void setInteractionListener(
            final InteractionListener listener) {
        interactionListener = Objects.requireNonNull(listener, "listener");
    }

    private boolean manualWarpEditingEnabled = true;

    public void setManualWarpEditingEnabled(final boolean enabled) {
        manualWarpEditingEnabled = enabled;
        if (!enabled) cancelTransientGesture();
        repaint();
    }

    public boolean manualWarpEditingEnabled() {
        return manualWarpEditingEnabled;
    }

    public void setInteractionTool(final InteractionTool tool) {
        final InteractionTool checked = Objects.requireNonNull(tool, "tool");
        // A real tool change ends temporary panning. Re-rendering the current
        // tool must preserve a physically held Space key, or a later pan drag
        // could accidentally become an atlas transform.
        if (interactionTool != checked) {
            spaceDown = false;
        }
        interactionTool = checked;
        cancelPendingAtlasPoint();
        cancelInteraction();
        if (model != null) {
            requestedRenderKey = renderKey(model, selectedRegionContour);
            scheduleCurrentRender();
        }
    }

    public InteractionTool interactionTool() {
        return interactionTool;
    }

    /** Display-only command-side selection; both supported sides stay visible. */
    public void setActiveHemisphereSide(
            final ManualHemisphereWarp2D.AtlasSide side) {
        activeHemisphereSide = Objects.requireNonNull(side, "side");
        cancelInteraction();
        if (model != null) {
            requestedRenderKey = renderKey(model, selectedRegionContour);
            scheduleCurrentRender();
        }
        repaint();
    }

    public ManualHemisphereWarp2D.AtlasSide activeHemisphereSide() {
        return activeHemisphereSide;
    }

    /** Arms one tissue click for the panel's Add-one control command. */
    public void armManualWarpPointPlacement() {
        setInteractionTool(InteractionTool.POINTS);
        manualWarpPointArmed = true;
        // The Add-one button otherwise retains keyboard focus, so an
        // immediate Escape never reaches this canvas's fail-closed cancel
        // binding. Give the armed interaction focus before any tissue click.
        requestFocusInWindow();
        repaint();
    }

    public boolean manualWarpPointPlacementArmed() {
        return manualWarpPointArmed;
    }

    public void setTissueSupportEditing(final boolean editing) {
        tissueSupportEditing = editing;
        cancelInteraction();
        repaint();
    }

    public boolean tissueSupportEditing() {
        return tissueSupportEditing;
    }

    /** Starts a display-only polygon trace; no review revision is created. */
    public void startTissueSupportTrace() {
        tissueSupportEditing = false;
        tissueSupportTracing = true;
        tissueSupportTrace.clear();
        cancelInteraction();
        interactionListener.tissueSupportTraceChanged(0);
        requestFocusInWindow();
        repaint();
    }

    /** Cancels a display-only polygon trace without changing review state. */
    public void cancelTissueSupportTrace() {
        if (!tissueSupportTracing && tissueSupportTrace.isEmpty()) {
            return;
        }
        tissueSupportTracing = false;
        tissueSupportTrace.clear();
        interactionListener.tissueSupportTraceChanged(0);
        repaint();
    }

    public boolean tissueSupportTracing() {
        return tissueSupportTracing;
    }

    public List<Point2D> tissueSupportTracePoints() {
        return List.copyOf(tissueSupportTrace);
    }

    /**
     * Cancels an active display gesture without creating a revision.
     *
     * @return true only when this canvas owned an interaction to cancel
     */
    boolean cancelCurrentInteraction() {
        final boolean active = gesture != null || dragOrigin != null
                || lastDragPoint != null || tissueClickOrigin != null
                || manualWarpPointArmed
                || pendingBoundaryAtlasPoint != null;
        if (tissueSupportTracing) {
            cancelTissueSupportTrace();
            return true;
        }
        if (active) {
            cancelInteraction();
        } else if (model != null && model.boundaryFit().active()) {
            controller.cancelBoundaryFit();
            return true;
        } else if (model != null && model.boundaryWarp().active()) {
            controller.cancelBoundaryWarp();
            return true;
        }
        return active;
    }

    /** Package-visible read-only view used by headless active-side UI tests. */
    List<ManualWarpControl> visibleManualWarpControls() {
        if (model == null) {
            return List.of();
        }
        return model.reviewState().content().hemisphereWarp()
                .map(warp -> warp.controls().stream()
                        .filter(control -> displayIncludesSide(model,
                                control.atlasSide()))
                        .toList())
                .orElse(List.of());
    }

    /** Display-only; does not touch the review session or audit history. */
    public void setLandmarkLabelsVisible(final boolean visible) {
        landmarkLabelsVisible = visible;
        repaint();
    }

    public boolean landmarkLabelsVisible() {
        return landmarkLabelsVisible;
    }

    /** Display-only; does not touch the review session or audit history. */
    public void setDeformationGridVisible(final boolean visible) {
        deformationGridVisible = visible;
        if (model != null) {
            requestedRenderKey = renderKey(model, selectedRegionContour);
        }
        scheduleCurrentRender();
        repaint();
    }

    public boolean deformationGridVisible() {
        return deformationGridVisible;
    }

    public void setDisplacementLinesVisible(final boolean visible) {
        displacementLinesVisible = visible;
        repaint();
    }

    public boolean displacementLinesVisible() {
        return displacementLinesVisible;
    }

    public void setTissueClippingEnabled(final boolean enabled) {
        if (tissueClippingEnabled == enabled) {
            return;
        }
        tissueClippingEnabled = enabled;
        if (model != null) {
            requestedRenderKey = renderKey(model, selectedRegionContour);
        }
        scheduleCurrentRender();
        repaint();
    }

    public boolean tissueClippingEnabled() {
        return tissueClippingEnabled;
    }

    public void setRegionStyle(
            final Color selected,
            final Color dim,
            final double thickness) {
        if (!Double.isFinite(thickness) || thickness < 0.5
                || thickness > 5.0) {
            throw new IllegalArgumentException(
                    "Atlas ROI thickness must be between 0.5 and 5.0 px");
        }
        selectedRegionColor = Objects.requireNonNull(selected, "selected");
        dimRegionColor = Objects.requireNonNull(dim, "dim");
        regionStrokeWidth = (float) thickness;
        if (model != null) {
            requestedRenderKey = renderKey(model, selectedRegionContour);
        }
        scheduleCurrentRender();
        repaint();
    }

    public Color selectedRegionColor() {
        return selectedRegionColor;
    }

    public Color dimRegionColor() {
        return dimRegionColor;
    }

    public double regionStrokeWidth() {
        return regionStrokeWidth;
    }

    /** Display-only opacity in the closed interval [0, 1]. */
    public void setOverlayOpacity(final double opacity) {
        if (!Double.isFinite(opacity) || opacity < 0 || opacity > 1) {
            throw new IllegalArgumentException(
                    "Overlay opacity must be between 0 and 1");
        }
        overlayOpacity = (float) opacity;
        repaint();
    }

    public double overlayOpacity() {
        return overlayOpacity;
    }

    public void setSingleTissuePane(final boolean single) {
        singleTissuePane = single;
        fitBothViews();
        repaint();
    }

    /** Enables the modal matcher only while the Match workflow stage is open. */
    public void setBoundaryFitEditingEnabled(final boolean enabled) {
        if (boundaryFitEditingEnabled == enabled) {
            return;
        }
        boundaryFitEditingEnabled = enabled;
        cancelInteraction();
        repaint();
    }

    /**
     * Shows the transient requested/audited Border ghost only while the
     * Border stage is open. Other stages therefore display installed geometry
     * exclusively while the draft remains recoverable in controller state.
     */
    public void setBoundaryWarpPreviewVisible(final boolean visible) {
        if (boundaryWarpPreviewVisible == visible) {
            return;
        }
        boundaryWarpPreviewVisible = visible;
        if (model != null) {
            requestedRenderKey = renderKey(model, selectedRegionContour);
            scheduleCurrentRender();
        }
        repaint();
    }

    boolean boundaryWarpPreviewVisibleForTests() {
        return boundaryWarpPreviewVisible;
    }

    /** Display/interaction mode for one selected acronym-specific group. */
    public void setStructureEditingEnabled(final boolean enabled) {
        if (structureEditingEnabled == enabled) {
            return;
        }
        structureEditingEnabled = enabled;
        cancelInteraction();
        repaint();
    }

    public boolean structureEditingEnabled() {
        return structureEditingEnabled;
    }

    /** Activates exact source-coordinate reviewer ROI drawing on this canvas. */
    public void setManualRoiEditingEnabled(final boolean enabled) {
        manualRoiLayer.setEnabled(enabled);
        if (enabled) {
            cancelInteraction();
        }
        repaint();
    }

    public boolean manualRoiEditingEnabled() {
        return manualRoiLayer.enabled();
    }

    /** Keeps completed reviewer ROIs visible while another stage is active. */
    public void setManualRoiVisible(final boolean visible) {
        manualRoiLayer.setVisible(visible);
        repaint();
    }

    public boolean manualRoiVisible() {
        return manualRoiLayer.visible();
    }

    public void setManualRoiState(
            final ReviewerRoiSession.Snapshot snapshot,
            final PreviewMapping sourceToPreview) {
        manualRoiLayer.setState(snapshot, sourceToPreview);
        repaint();
    }

    public void setManualRoiListener(
            final ManualRoiCanvasLayer.Listener listener) {
        manualRoiLayer.setListener(listener);
    }

    public void setManualRoiCaptureMode(final boolean freehand) {
        manualRoiLayer.setCaptureMode(freehand
                ? ManualRoiCanvasLayer.CaptureMode.FREEHAND
                : ManualRoiCanvasLayer.CaptureMode.POLYGON);
        repaint();
    }

    public void setManualRoiPreviewVisible(final boolean visible) {
        manualRoiLayer.setPreviewSelected(visible);
        repaint();
    }

    public boolean manualRoiPreviewVisible() {
        return manualRoiLayer.previewSelected();
    }

    /** Display-only export membership preview; no files or revisions. */
    public void setExportedRoiPreviewVisible(final boolean visible) {
        if (exportedRoiPreviewVisible == visible) {
            return;
        }
        exportedRoiPreviewVisible = visible;
        if (model != null) {
            requestedRenderKey = renderKey(model, selectedRegionContour);
            scheduleCurrentRender();
        }
        repaint();
    }

    boolean exportedRoiPreviewVisibleForTests() {
        return exportedRoiPreviewVisible;
    }

    BufferedImage exportedRoiPreviewImageForTests() {
        return exportedRoiPreviewImage;
    }

    BufferedImage basePreviewImageForTests() {
        return previewImage;
    }

    public boolean singleTissuePane() {
        return singleTissuePane;
    }

    public void setComparisonMode(final ComparisonMode mode) {
        comparisonMode = Objects.requireNonNull(mode, "mode");
        repaint();
    }

    public ComparisonMode comparisonMode() {
        return comparisonMode;
    }

    public boolean aspectRatioLocked() { return aspectRatioLocked; }
    public void setAspectRatioLocked(final boolean locked) { aspectRatioLocked = locked; }
    public boolean outerBoundariesOnly() { return outerBoundariesOnly; }
    public void setOuterBoundariesOnly(final boolean outer) {
        if (outerBoundariesOnly == outer) return;
        outerBoundariesOnly = outer;
        if (model != null) { requestedRenderKey = renderKey(model, selectedRegionContour); scheduleCurrentRender(); }
        repaint();
    }
    public PlacementTool placementTool() { return placementTool; }
    public void setPlacementTool(final PlacementTool tool) { placementTool = Objects.requireNonNull(tool); cancelCurrentInteraction(); repaint(); }

    public org.atlasalign.plugin.project.ReviewUiState.Viewport viewport() {
        return new org.atlasalign.plugin.project.ReviewUiState.Viewport(tissueZoom, tissuePanX, tissuePanY,
                atlasZoom, atlasPanX, atlasPanY);
    }

    public void restoreViewport(final org.atlasalign.plugin.project.ReviewUiState.Viewport viewport) {
        tissueZoom = viewport.tissueZoom(); tissuePanX = viewport.tissuePanX(); tissuePanY = viewport.tissuePanY();
        atlasZoom = viewport.atlasZoom(); atlasPanX = viewport.atlasPanX(); atlasPanY = viewport.atlasPanY();
        repaint();
    }

    public void requestChannelDisplay(final String label, final boolean inspection) {
        channelDisplayEnabled = true; displayedChannelImage = null; channelDisplayLabel = label;
        displayInspection = inspection; cancelCurrentInteraction(); repaint();
    }

    public void setChannelDisplay(final BufferedImage image, final String label) {
        if (image != null && model != null && (image.getWidth() != model.preview().width()
                || image.getHeight() != model.preview().height())) throw new IllegalArgumentException("Display image dimensions differ from review geometry");
        displayedChannelImage = image; channelDisplayLabel = label; repaint();
    }

    public void fitTissueView() {
        tissueZoom = 1;
        tissuePanX = 0;
        tissuePanY = 0;
        repaint();
    }

    public void fitAtlasView() {
        atlasZoom = 1;
        atlasPanX = 0;
        atlasPanY = 0;
        repaint();
    }

    public void fitBothViews() {
        fitTissueView();
        fitAtlasView();
    }

    public void zoomTissueIn() {
        zoomPane(true, BUTTON_ZOOM_FACTOR, paneCenter(true));
    }

    public void zoomTissueOut() {
        zoomPane(true, 1 / BUTTON_ZOOM_FACTOR, paneCenter(true));
    }

    public void zoomAtlasIn() {
        zoomPane(false, BUTTON_ZOOM_FACTOR, paneCenter(false));
    }

    public void zoomAtlasOut() {
        zoomPane(false, 1 / BUTTON_ZOOM_FACTOR, paneCenter(false));
    }

    public void setLandmarkCaptureListener(
            final LandmarkCaptureListener listener) {
        landmarkCaptureListener = Objects.requireNonNull(
                listener, "listener");
    }

    public void cancelPendingAtlasPoint() {
        pendingAtlasPoint = null;
        tissueClickOrigin = null;
        repaint();
    }

    public void setModel(final ReviewViewModel nextModel) {
        final Integer previousLevel = model == null ? null
                : model.coronalLevel();
        final org.atlasalign.application.AtlasPlaneTilt previousTilt =
                model == null ? null
                        : model.reviewState().content().atlasPlaneTilt();
        final org.atlasalign.application.AtlasOrientation previousOrientation =
                model == null ? null : model.orientation();
        final org.atlasalign.application.ObservedAnatomicalHemisphere
                previousObservedHemisphere = model == null ? null
                        : model.observedHemisphere();
        final org.atlasalign.application.SectionGeometry previousGeometry =
                model == null ? null : model.geometry();
        final var previousTarget = model == null ? null
                : model.selectedAtlasRegion();
        final ReviewPreview previousPreview = model == null ? null : model.preview();
        model = Objects.requireNonNull(nextModel, "nextModel");
        if (nextModel.boundaryFit().draft().isEmpty()
                && nextModel.boundaryWarp().draft().isEmpty()) {
            selectedBoundaryMatchId = null;
            pendingBoundaryAtlasPoint = null;
        } else if (nextModel.boundaryFit().draft().isPresent()) {
            selectedBoundaryMatchId = nextModel.boundaryFit().draft()
                    .orElseThrow().activeAnchorId().orElse(
                            selectedBoundaryMatchId);
        }
        if (previousLevel != null
                && (previousLevel.intValue() != nextModel.coronalLevel()
                || !previousTilt.equals(nextModel.reviewState().content()
                        .atlasPlaneTilt())
                || !previousOrientation.equals(nextModel.orientation())
                || previousObservedHemisphere
                        != nextModel.observedHemisphere()
                || previousGeometry != nextModel.geometry()
                || !previousTarget.equals(nextModel.selectedAtlasRegion()))) {
            cancelPendingAtlasPoint();
            cancelInteraction();
        }
        if (previewImage == null || previousPreview != nextModel.preview()) {
            previewImage = previewImage(nextModel.preview());
        }
        selectedRegionContour = nextModel.selectedAtlasContour()
                .orElse(null);
        selectedRegionBoundaryCount = selectedRegionContour == null
                ? 0 : selectedRegionContour.boundaryCount();
        final RenderKey key = renderKey(nextModel, selectedRegionContour);
        requestedRenderKey = key;
        exportedRoiPreviewImage = null; exportedRoiDimmingOverlay = null;
        // Keep no geometry from a different revision visible while the new
        // immutable render request is being prepared off the EDT.
        overlayImage = null;
        placementOverlays = null;
        beforeOverlayImage = null;
        boundaryWarpGhostImage = null;
        final var content = nextModel.reviewState().content();
        atlasImage = nextModel.atlasPlane()
                .filter(ignored -> content.outlineWarp().isEmpty()
                        && content.hemisphereWarp().isEmpty()
                        && content.localWarp().isEmpty())
                .map(plane -> atlasImage(nextModel, plane,
                        selectedRegionContour, activeHemisphereSide))
                .orElse(null);
        renderPaths = null;
        scheduleRender(nextModel, selectedRegionContour, key);
        repaint();
    }

    private void scheduleCurrentRender() {
        final ReviewViewModel current = model;
        final RenderKey key = requestedRenderKey;
        if (current != null && key != null) {
            scheduleRender(current, selectedRegionContour, key);
        }
    }

    private void scheduleRender(
            final ReviewViewModel requestModel,
            final SelectedAtlasContour contour,
            final RenderKey key) {
        renderExecutor.execute(() -> {
            if (!key.equals(requestedRenderKey)) return;
            try {
                final AtlasCoronalPlane plane = requestModel.atlasPlane()
                        .orElse(null);
                // All geometry-producing work belongs to this bounded worker.
                // In particular, the warped overlay is rendered from the same
                // immutable path cache consumed by paintComponent; repaint
                // never scans annotation pixels or invokes a map operation.
                final RenderPaths nextPaths = buildRenderPaths(
                        requestModel, contour, key.gridVisible(),
                        key.tissueClippingEnabled()
                                && !key.placementPreview(),
                        key.outerBoundariesOnly() ? null : sidePathCache, key.outerBoundariesOnly());
                if (!key.equals(requestedRenderKey)) return;
                final BufferedImage nextOverlay = plane == null ? null
                        : renderOverlayImage(requestModel, nextPaths, contour,
                                new Color(key.dimRegionArgb(), true),
                                Float.intBitsToFloat(key.strokeWidthBits()));
                final PlacementLayers nextPlacementOverlays = plane == null
                        || !key.placementPreview() ? null
                        : renderPlacementLayers(nextPaths,
                                new Color(key.dimRegionArgb(), true),
                                Float.intBitsToFloat(
                                        key.strokeWidthBits()),
                                contour != null,
                                key.reviewSectionMode());
                final BufferedImage nextBeforeOverlay = plane == null ? null
                        : renderBoundaryImage(requestModel,
                                nextPaths.beforeTissueBoundarySegments(),
                                new Color(185, 185, 185, 180));
                final BufferedImage nextAtlas = plane == null ? null
                        : atlasImage(requestModel, plane, contour,
                                key.activeHemisphereSide());
                final BufferedImage nextBoundaryGhost = plane == null
                        || !key.boundaryWarpPreviewVisible()
                        ? null : renderBoundaryWarpGhost(
                                requestModel, nextPaths,
                                new Color(key.dimRegionArgb(), true),
                                Float.intBitsToFloat(
                                        key.strokeWidthBits()));
                final BufferedImage nextDimming = plane == null || !key.exportedRoiPreviewVisible()
                        ? null : exportedRoiDimmingOverlay(requestModel, plane);
                final BufferedImage nextExportedRoiPreview = nextDimming == null ? null
                        : dimPreview(previewImage(requestModel.preview()), nextDimming);
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (!key.equals(requestedRenderKey)) {
                        return;
                    }
                    overlayImage = nextOverlay;
                    placementOverlays = nextPlacementOverlays;
                    beforeOverlayImage = nextBeforeOverlay;
                    atlasImage = nextAtlas;
                    boundaryWarpGhostImage = nextBoundaryGhost;
                    exportedRoiPreviewImage = nextExportedRoiPreview; exportedRoiDimmingOverlay = nextDimming;
                    renderPaths = nextPaths;
                    repaint();
                });
            } catch (final RuntimeException renderFailure) {
                // A path that is outside a reviewed source domain is stale or
                // invalid geometry, not a reason to terminate the render
                // executor. Fail closed and let a newer model request replace
                // this empty cache.
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (!key.equals(requestedRenderKey)) {
                        return;
                    }
                    // Keep a previously installed cache when this was a
                    // display-only rerender; setModel already clears it for
                    // a new review revision. Otherwise expose an explicit
                    // empty cache rather than stale geometry.
                    if (overlayImage == null && renderPaths == null) {
                        renderPaths = emptyRenderPaths();
                    }
                    repaint();
                });
            }
        });
    }

    private RenderKey renderKey(
            final ReviewViewModel value,
            final SelectedAtlasContour contour) {
        final var content = value.reviewState().content();
        final String warpHash = content.hemisphereWarp()
                .map(warp -> warp.diagnostics().contentSha256())
                .orElse("");
        final String outlineHash = content.outlineWarp()
                .map(warp -> warp.getClass().getName() + ":" + warp.hashCode())
                .orElse("");
        final String tissueSupportHash = content.reviewedTissueSupport()
                .map(ReviewedTissueSupport::contentSha256)
                .orElse("");
        final String selection = value.selectedAtlasRegion()
                .map(region -> region.acronym() + ":"
                        + region.includedRegionIds().stream().sorted()
                                .map(String::valueOf)
                                .collect(java.util.stream.Collectors
                                        .joining(","))
                        + ":" + (contour == null ? ""
                                : contour.boundarySha256()))
                .orElse("");
        final var plane = value.atlasPlane().orElse(null);
        final String boundaryWarpDraftHash = value.boundaryWarp().drafts()
                .stream().map(request -> request.contentRevision() + ":"
                        + request.targetSide() + ":"
                        + request.matches().hashCode())
                .collect(java.util.stream.Collectors.joining("|"));
        final String structureDraftHash = value.structureAdjustment()
                .draft().map(StructureAdjustmentDraft::inputHash).orElse("")
                + value.structureAdjustment().candidate()
                        .map(candidate -> ":"
                                + candidate.retainedFractionByUnitId()
                                        .hashCode()
                                + ":" + candidate.limitingReports()
                                        .hashCode()
                                + ":" + candidate.auditedWarp().diagnostics()
                                        .contentSha256())
                        .orElse("");
        return new RenderKey(value.reviewState().contentRevision(),
                value.coronalLevel(), content.atlasPlaneTilt(),
                content.orientation(), content.reviewSectionMode(),
                content.observedHemisphere(), content.halfAtlasCoverage(),
                activeHemisphereSide,
                content.manualSidePlacement().hashCode(),
                warpHash, outlineHash, tissueSupportHash, selection,
                value.reviewState().basis().sourceSnapshot().pixelSha256(),
                value.reviewState().basis().atlas().identitySha256(),
                plane == null ? 0 : plane.width(),
                plane == null ? 0 : plane.height(),
                plane == null ? 0 : System.identityHashCode(plane),
                boundaryWarpDraftHash, structureDraftHash,
                deformationGridVisible, tissueClippingEnabled,
                boundaryWarpPreviewVisible, exportedRoiPreviewVisible,
                interactionTool == InteractionTool.TRANSFORM
                        && !hasLocalDeformation(),
                outerBoundariesOnly,
                dimRegionColor.getRGB(),
                Float.floatToIntBits(regionStrokeWidth));
    }

    private static RenderPaths buildRenderPaths(
            final ReviewViewModel requestModel,
            final SelectedAtlasContour contour,
            final boolean gridVisible) {
        return buildRenderPaths(requestModel, contour, gridVisible,
                false, null);
    }

    private static RenderPaths buildRenderPaths(
            final ReviewViewModel requestModel,
            final SelectedAtlasContour contour,
            final boolean gridVisible,
            final boolean clipToTissue,
            final Map<SidePathCacheKey, List<LineSegment>> pathCache) {
        return buildRenderPaths(requestModel, contour, gridVisible, clipToTissue, pathCache, false);
    }

    private static RenderPaths buildRenderPaths(final ReviewViewModel requestModel, final SelectedAtlasContour contour,
            final boolean gridVisible, final boolean clipToTissue,
            final Map<SidePathCacheKey, List<LineSegment>> pathCache, final boolean outerOnly) {
        final List<LineSegment> atlasSegments = contour == null
                || !contour.isPresent()
                ? List.of() : contourSegments(contour);
        final List<LineSegment> tissueSegments = new ArrayList<>();
        final List<SidedLineSegment> sidedTissueSegments = new ArrayList<>();
        final List<LineSegment> beforeTissueSegments = new ArrayList<>();
        final AtlasCoronalPlane plane = requestModel.atlasPlane()
                .orElse(null);
        final List<LineSegment> atlasBoundarySegments = plane == null
                ? List.of() : annotationBoundarySegments(plane, outerOnly);
        final List<LineSegment> tissueBoundarySegments = new ArrayList<>();
        final List<SidedLineSegment> sidedTissueBoundarySegments =
                new ArrayList<>();
        final List<LineSegment> beforeTissueBoundarySegments =
                new ArrayList<>();
        final List<LineSegment> reviewedBoundarySegments = new ArrayList<>();
        final var state = requestModel.reviewState();
        final var content = state.content();
        if (content.reviewedTissueSupport().isPresent()) {
            for (final List<Point2D> polygon : content
                    .reviewedTissueSupport().orElseThrow().polygons()) {
                for (int index = 0; index < polygon.size(); index++) {
                    reviewedBoundarySegments.add(new LineSegment(
                            polygon.get(index),
                            polygon.get((index + 1) % polygon.size())));
                }
            }
        } else if (content.outlineWarp().orElse(null)
                instanceof org.atlasalign.application.manual
                        .BoundaryAuthoritativeTransform2D exact) {
            final List<Point2D> boundary = exact.tissueBoundary();
            for (int index = 0; index < boundary.size(); index++) {
                reviewedBoundarySegments.add(new LineSegment(
                        boundary.get(index),
                        boundary.get((index + 1) % boundary.size())));
            }
        } else {
            state.basis().segmentation().ifPresent(segmentation ->
                    appendWarpedSupportBoundary(state,
                            contrastBoundarySegments(segmentation.mask()),
                            reviewedBoundarySegments));
        }
        for (final LineSegment segment : atlasSegments) {
            // Let the immutable review state split connected paths at both
            // the exact reviewed outline and local mesh edges.  This keeps
            // thin ROI outlines continuous without clipping or doing the
            // old per-point map chain during repaint.
            appendMappedBoundarySegments(requestModel, List.of(segment),
                    state, tissueSegments, sidedTissueSegments, null);
            if (beforeComparisonIncludesSegment(requestModel, segment)) {
                beforeTissueSegments.add(new LineSegment(
                        state.mapAtlasBeforeHemisphereWarp(segment.first()),
                        state.mapAtlasBeforeHemisphereWarp(segment.second())));
            }
        }
        // The complete annotation boundary is cached once for the current
        // revision. It is used for all structures, including the selected
        // region and the opposite side, so every dim orange contour follows
        // the same side-local path mapping. Optional tissue clipping is
        // applied to these side-tagged mapped segments below.
        appendMappedBoundarySegments(requestModel, atlasBoundarySegments,
                state, tissueBoundarySegments,
                sidedTissueBoundarySegments, pathCache);
        for (final LineSegment segment : atlasBoundarySegments) {
            if (beforeComparisonIncludesSegment(requestModel, segment)) {
                beforeTissueBoundarySegments.add(new LineSegment(
                        state.mapAtlasBeforeHemisphereWarp(segment.first()),
                        state.mapAtlasBeforeHemisphereWarp(segment.second())));
            }
        }
        final List<LineSegment> grid = gridVisible
                && (content.localWarp().isPresent()
                || content.hemisphereWarp().isPresent())
                ? buildGridSegments(requestModel) : List.of();
        final List<StructureComponentPath> structureComponents =
                buildStructureComponentPaths(requestModel, contour);
        final List<LineSegment> structureCandidateSegments =
                buildStructureCandidateSegments(requestModel, contour);
        final List<LineSegment> structureRequestedSegments =
                buildStructureRequestedSegments(requestModel);
        final List<LineSegment> clippedTissueSegments = clipToTissue
                ? clipSegmentsToTissueSupport(requestModel,
                        sidedTissueSegments)
                : List.copyOf(tissueSegments);
        final List<LineSegment> clippedTissueBoundarySegments = clipToTissue
                ? clipSegmentsToTissueSupport(requestModel,
                        sidedTissueBoundarySegments)
                : List.copyOf(tissueBoundarySegments);
        final List<LineSegment> leftPlacementBoundarySegments =
                sidedTissueBoundarySegments.stream()
                        .filter(segment -> segment.atlasSide().orElse(null)
                                == ManualHemisphereWarp2D.AtlasSide.LEFT)
                        .map(SidedLineSegment::segment).toList();
        final List<LineSegment> rightPlacementBoundarySegments =
                sidedTissueBoundarySegments.stream()
                        .filter(segment -> segment.atlasSide().orElse(null)
                        == ManualHemisphereWarp2D.AtlasSide.RIGHT)
                        .map(SidedLineSegment::segment).toList();
        final List<LineSegment> leftPlacementContourSegments =
                sidedTissueSegments.stream()
                        .filter(segment -> segment.atlasSide().orElse(null)
                                == ManualHemisphereWarp2D.AtlasSide.LEFT)
                        .map(SidedLineSegment::segment).toList();
        final List<LineSegment> rightPlacementContourSegments =
                sidedTissueSegments.stream()
                        .filter(segment -> segment.atlasSide().orElse(null)
                                == ManualHemisphereWarp2D.AtlasSide.RIGHT)
                        .map(SidedLineSegment::segment).toList();
        return new RenderPaths(clippedTissueSegments,
                List.copyOf(beforeTissueSegments),
                atlasReferenceSegments(requestModel, atlasSegments),
                List.copyOf(reviewedBoundarySegments),
                List.copyOf(atlasBoundarySegments),
                clippedTissueBoundarySegments,
                leftPlacementBoundarySegments,
                rightPlacementBoundarySegments,
                leftPlacementContourSegments,
                rightPlacementContourSegments,
                List.copyOf(beforeTissueBoundarySegments),
                structureComponents, structureCandidateSegments,
                structureRequestedSegments, grid);
    }

    private static List<LineSegment> buildStructureCandidateSegments(
            final ReviewViewModel model,
            final SelectedAtlasContour contour) {
        if (contour == null || !contour.isPresent()
                || model.structureAdjustment().candidate().isEmpty()
                || model.structureAdjustment().draft().isEmpty()) {
            return List.of();
        }
        final StructureAdjustmentDraft draft = model.structureAdjustment()
                .draft().orElseThrow();
        final ManualHemisphereWarp2D candidate = model
                .structureAdjustment().candidate().orElseThrow()
                .auditedWarp();
        final AtlasAnatomicalSide anatomicalSide = draft.atlasSide()
                == ManualHemisphereWarp2D.AtlasSide.LEFT
                ? AtlasAnatomicalSide.ATLAS_LEFT
                : AtlasAnatomicalSide.ATLAS_RIGHT;
        final List<LineSegment> result = new ArrayList<>();
        for (final SelectedAtlasContour.ExteriorComponent component
                : contour.principalExteriorComponents(anatomicalSide)) {
            final List<Point2D> mapped = component.loop().stream()
                    .map(model.reviewState()::mapAtlasBeforeHemisphereWarp)
                    .map(point -> candidate.apply(draft.atlasSide(), point))
                    .map(point -> model.reviewState().content().localWarp()
                            .map(warp -> warp.apply(point)).orElse(point))
                    .toList();
            result.addAll(closedLoopSegments(mapped));
        }
        return List.copyOf(result);
    }

    private static List<LineSegment> buildStructureRequestedSegments(
            final ReviewViewModel model) {
        final StructureAdjustmentDraft draft = model.structureAdjustment()
                .draft().orElse(null);
        if (draft == null) {
            return List.of();
        }
        final Map<String, ManualWarpControl> controls = draft
                .requestedControls().stream().collect(
                        java.util.stream.Collectors.toMap(
                                ManualWarpControl::id,
                                Function.identity()));
        return structureOutlineSegments(draft, id -> controls.get(id)
                .targetPoint());
    }

    private static List<StructureComponentPath>
            buildStructureComponentPaths(
            final ReviewViewModel model,
            final SelectedAtlasContour contour) {
        if (contour == null || !contour.isPresent()) {
            return List.of();
        }
        final List<StructureComponentPath> result = new ArrayList<>();
        for (final ManualHemisphereWarp2D.AtlasSide side
                : ManualHemisphereWarp2D.AtlasSide.values()) {
            final AtlasAnatomicalSide anatomicalSide = side
                    == ManualHemisphereWarp2D.AtlasSide.LEFT
                    ? AtlasAnatomicalSide.ATLAS_LEFT
                    : AtlasAnatomicalSide.ATLAS_RIGHT;
            final List<SelectedAtlasContour.ExteriorComponent> components =
                    contour.exteriorComponents(anatomicalSide);
            for (int componentIndex = 0;
                    componentIndex < components.size(); componentIndex++) {
                final SelectedAtlasContour.ExteriorComponent component =
                        components.get(componentIndex);
                final List<LineSegment> atlasSegments =
                        closedLoopSegments(component.loop());
                final List<LineSegment> tissueSegments = new ArrayList<>();
                appendMappedBoundarySegments(model, atlasSegments,
                        model.reviewState(), tissueSegments,
                        new ArrayList<>(), null);
                if (tissueSegments.isEmpty()) {
                    continue;
                }
                final List<Point2D> sourceLoop = component.loop().stream()
                        .map(model.reviewState()
                                ::mapAtlasBeforeHemisphereWarp)
                        .toList();
                result.add(new StructureComponentPath(component.id(), side,
                        componentIndex < 2, component.perimeter(),
                        tissueSegments, sourceLoop,
                        segmentCentroid(tissueSegments)));
            }
        }
        return List.copyOf(result);
    }

    private static List<LineSegment> closedLoopSegments(
            final List<Point2D> loop) {
        final List<LineSegment> result = new ArrayList<>(loop.size());
        for (int index = 0; index < loop.size(); index++) {
            result.add(new LineSegment(loop.get(index),
                    loop.get((index + 1) % loop.size())));
        }
        return List.copyOf(result);
    }

    private static Point2D segmentCentroid(
            final List<LineSegment> segments) {
        double x = 0;
        double y = 0;
        int count = 0;
        for (final LineSegment segment : segments) {
            x += segment.first().x() + segment.second().x();
            y += segment.first().y() + segment.second().y();
            count += 2;
        }
        return new Point2D(x / count, y / count);
    }

    private static boolean beforeComparisonIncludesSegment(
            final ReviewViewModel model,
            final LineSegment segment) {
        final ReviewSectionMode mode = model.reviewState().content()
                .reviewSectionMode();
        if (mode == ReviewSectionMode.FULL) {
            return true;
        }
        final Optional<ManualHemisphereWarp2D.AtlasSide> side =
                atlasSideForSegment(segment, model.reviewState().basis()
                        .atlas().atlasPlaneWidth());
        return side.isPresent()
                && displayIncludesSide(model, side.orElseThrow());
    }

    private static List<LineSegment> atlasReferenceSegments(
            final ReviewViewModel model,
            final List<LineSegment> segments) {
        final ReviewSectionMode mode = model.reviewState().content()
                .reviewSectionMode();
        if (mode == ReviewSectionMode.FULL) {
            return List.copyOf(segments);
        }
        final int planeWidth = model.reviewState().basis().atlas()
                .atlasPlaneWidth();
        final ManualHemisphereWarp2D.AtlasSide disjoinedReference =
                mode == ReviewSectionMode.DISJOINED
                        ? model.boundaryFit().draft()
                                .flatMap(draft -> draft.request().targetSide())
                                .orElse(ManualHemisphereWarp2D.AtlasSide.LEFT)
                        : null;
        return segments.stream().filter(segment -> {
            final Optional<ManualHemisphereWarp2D.AtlasSide> side =
                    atlasSideForSegment(segment, planeWidth);
            if (side.isEmpty()) {
                return false;
            }
            return mode == ReviewSectionMode.DISJOINED
                    ? side.orElseThrow() == disjoinedReference
                    : displayIncludesSide(model, side.orElseThrow());
        }).toList();
    }

    /**
     * Builds a vector boundary from the immutable atlas labels. The scan is
     * performed only on the render worker; the resulting segments are mapped
     * and cached before Swing paints them.
     */
    private static List<LineSegment> annotationBoundarySegments(
            final AtlasCoronalPlane plane) {
        return annotationBoundarySegments(plane, false);
    }

    private static List<LineSegment> annotationBoundarySegments(final AtlasCoronalPlane plane, final boolean outerOnly) {
        final int width = plane.width();
        final int height = plane.height();
        final int[] labels = plane.annotationId();
        if (outerOnly) for (int index = 0; index < labels.length; index++) labels[index] = labels[index] == 0 ? 0 : 1;
        final List<LineSegment> result = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int value = labels[y * width + x];
                if (value == 0) {
                    continue;
                }
                if (x == 0 || labels[y * width + x - 1] != value) {
                    result.add(new LineSegment(
                            new Point2D(x, y), new Point2D(x, y + 1)));
                }
                if (x + 1 == width
                        || labels[y * width + x + 1] != value) {
                    result.add(new LineSegment(
                            new Point2D(x + 1, y),
                            new Point2D(x + 1, y + 1)));
                }
                if (y == 0 || labels[(y - 1) * width + x] != value) {
                    result.add(new LineSegment(
                            new Point2D(x, y), new Point2D(x + 1, y)));
                }
                if (y + 1 == height
                        || labels[(y + 1) * width + x] != value) {
                    result.add(new LineSegment(
                            new Point2D(x, y + 1),
                            new Point2D(x + 1, y + 1)));
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Traces the copied-preview contrast mask on pixel-cell edges. Sparse
     * editable boundary controls are deliberately not connected: their
     * sampling order is not display topology, and joining them exposes long
     * chords that can be mistaken for warp-mesh geometry.
     */
    static List<LineSegment> contrastBoundarySegments(final BinaryMask mask) {
        Objects.requireNonNull(mask, "mask");
        final BinaryMask envelope = new TissueMaskEnvelope()
                .fillInteriorHoles(mask);
        final List<LineSegment> result = new ArrayList<>();
        for (int y = 0; y < envelope.height(); y++) {
            for (int x = 0; x < envelope.width(); x++) {
                if (!envelope.contains(x, y)) {
                    continue;
                }
                if (!envelope.contains(x - 1, y)) {
                    result.add(new LineSegment(
                            new Point2D(x, y), new Point2D(x, y + 1)));
                }
                if (!envelope.contains(x + 1, y)) {
                    result.add(new LineSegment(
                            new Point2D(x + 1, y),
                            new Point2D(x + 1, y + 1)));
                }
                if (!envelope.contains(x, y - 1)) {
                    result.add(new LineSegment(
                            new Point2D(x, y), new Point2D(x + 1, y)));
                }
                if (!envelope.contains(x, y + 1)) {
                    result.add(new LineSegment(
                            new Point2D(x, y + 1),
                            new Point2D(x + 1, y + 1)));
                }
            }
        }
        return List.copyOf(result);
    }

    private static void appendWarpedSupportBoundary(
            final AlignmentReviewState state,
            final List<LineSegment> support,
            final List<LineSegment> destination) {
        final Optional<ManualHemisphereWarp2D> optional = state.content()
                .hemisphereWarp();
        if (optional.isEmpty()) {
            destination.addAll(support);
            return;
        }
        final ManualHemisphereWarp2D warp = optional.orElseThrow();
        final double seam = (state.basis().previewDimensions().width() - 1.0)
                * 0.5;
        for (final LineSegment segment : support) {
            final double centreX = 0.5 * (segment.first().x()
                    + segment.second().x());
            ManualHemisphereWarp2D.AtlasSide selected = null;
            for (final ManualHemisphereWarp2D.AtlasSide side
                    : ManualHemisphereWarp2D.AtlasSide.values()) {
                final boolean imageLeft = warp.imageSide(side)
                        == ManualHemisphereWarp2D.ImageSide.IMAGE_LEFT;
                if ((imageLeft && centreX <= seam)
                        || (!imageLeft && centreX >= seam)) {
                    selected = side;
                    break;
                }
            }
            if (selected == null || !warp.hasControls(selected)) {
                destination.add(segment);
            } else {
                final List<Point2D> mapped = warp.mapPath(selected,
                        List.of(segment.first(), segment.second()), false);
                Point2D previous = null;
                for (final Point2D point : mapped) {
                    if (previous != null && !previous.equals(point)) {
                        destination.add(new LineSegment(previous, point));
                    }
                    previous = point;
                }
            }
        }
    }

    private static List<LineSegment> clipSegmentsToTissueSupport(
            final ReviewViewModel model,
            final List<SidedLineSegment> segments) {
        final ReviewedTissueSupport reviewed = model.reviewState().content()
                .reviewedTissueSupport().orElse(null);
        if (reviewed != null) {
            final BinaryMask support = reviewed.supportMask();
            final List<LineSegment> result = new ArrayList<>();
            for (final SidedLineSegment sided : segments) {
                final LineSegment segment = sided.segment();
                final Point2D middle = midpoint(
                        segment.first(), segment.second());
                if (support.contains(rounded(segment.first().x()),
                                rounded(segment.first().y()))
                        || support.contains(rounded(segment.second().x()),
                                rounded(segment.second().y()))
                        || support.contains(rounded(middle.x()),
                                rounded(middle.y()))) {
                    result.add(segment);
                }
            }
            return List.copyOf(result);
        }
        // Legacy snapshots may predate ReviewedTissueSupport. Preserve their
        // prior display semantics without allowing new reviews to confuse a
        // warp-deformed cage with the fixed accepted crop footprint.
        final BinaryMask support = model.reviewState().basis().segmentation()
                .map(segmentation -> new TissueMaskEnvelope()
                        .fillInteriorHoles(segmentation.mask()))
                .orElse(null);
        if (support == null) {
            return segments.stream().map(SidedLineSegment::segment).toList();
        }
        final List<LineSegment> result = new ArrayList<>();
        for (final SidedLineSegment sided : segments) {
            final LineSegment segment = sided.segment();
            final Point2D middle = midpoint(segment.first(), segment.second());
            if (containsWarpedTissueSupport(model, support,
                    segment.first(), sided.atlasSide())
                    || containsWarpedTissueSupport(model, support,
                            segment.second(), sided.atlasSide())
                    || containsWarpedTissueSupport(model, support, middle,
                            sided.atlasSide())) {
                result.add(segment);
            }
        }
        return List.copyOf(result);
    }

    private static boolean containsWarpedTissueSupport(
            final ReviewViewModel model,
            final BinaryMask support,
            final Point2D finalPoint,
            final Optional<ManualHemisphereWarp2D.AtlasSide> atlasSide) {
        final Optional<ManualHemisphereWarp2D> optional = model.reviewState()
                .content().hemisphereWarp();
        if (optional.isEmpty() || atlasSide.isEmpty()) {
            return support.contains(rounded(finalPoint.x()),
                    rounded(finalPoint.y()));
        }
        final ManualHemisphereWarp2D warp = optional.orElseThrow();
        final ManualHemisphereWarp2D.AtlasSide side = atlasSide.orElseThrow();
        try {
            final Point2D source = warp.hasControls(side)
                    ? warp.inverse(side, finalPoint) : finalPoint;
            return support.contains(rounded(source.x()),
                    rounded(source.y()));
        } catch (final IllegalArgumentException outsideDomain) {
            return false;
        }
    }

    /** Maps one segment; an invalid edge invalidates the whole render request. */
    private static void appendMappedSegments(
            final AlignmentReviewState state,
            final LineSegment segment,
            final List<LineSegment> destination) {
        final List<Point2D> mapped = state.mapAtlasPathToPreview(
                List.of(segment.first(), segment.second()), false);
        Point2D previous = null;
        for (final Point2D point : mapped) {
            if (previous != null && !previous.equals(point)) {
                destination.add(new LineSegment(previous, point));
            }
            previous = point;
        }
    }

    private static void appendMappedSegments(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final LineSegment segment,
            final List<LineSegment> destination) {
        final List<Point2D> mapped = state.mapAtlasPathToPreview(
                atlasSide,
                List.of(segment.first(), segment.second()), false);
        Point2D previous = null;
        for (final Point2D point : mapped) {
            if (previous != null && !previous.equals(point)) {
                destination.add(new LineSegment(previous, point));
            }
            previous = point;
        }
    }

    private static void appendMappedBoundarySegments(
            final ReviewViewModel requestModel,
            final List<LineSegment> atlasSegments,
            final AlignmentReviewState state,
            final List<LineSegment> destination,
            final List<SidedLineSegment> sidedDestination,
            final Map<SidePathCacheKey, List<LineSegment>> pathCache) {
        if (atlasSegments.isEmpty()) {
            return;
        }
        final List<LineSegment> left = new ArrayList<>();
        final List<LineSegment> right = new ArrayList<>();
        final List<LineSegment> seam = new ArrayList<>();
        // Side semantics are defined by the immutable atlas identity, not by
        // a tiny synthetic/display plane used by a fixture.
        final int planeWidth = requestModel.reviewState().basis().atlas()
                .atlasPlaneWidth();
        for (final LineSegment segment : atlasSegments) {
            final Optional<ManualHemisphereWarp2D.AtlasSide> side =
                    atlasSideForSegment(segment, planeWidth);
            if (side.isEmpty()) {
                if (state.content().reviewSectionMode()
                        == org.atlasalign.application.ReviewSectionMode
                                .DISJOINED) {
                    splitDisjoinedSegment(segment, planeWidth, left, right);
                } else {
                    seam.add(segment);
                }
            } else if (side.orElseThrow()
                    == ManualHemisphereWarp2D.AtlasSide.LEFT) {
                left.add(segment);
            } else {
                right.add(segment);
            }
        }
        if (pathCache == null) {
            if (displayIncludesSide(requestModel,
                    ManualHemisphereWarp2D.AtlasSide.LEFT)) {
                for (final LineSegment segment : left) {
                    appendMappedSideSegments(state,
                            ManualHemisphereWarp2D.AtlasSide.LEFT,
                            segment, destination, sidedDestination);
                }
            }
            if (displayIncludesSide(requestModel,
                    ManualHemisphereWarp2D.AtlasSide.RIGHT)) {
                for (final LineSegment segment : right) {
                    appendMappedSideSegments(state,
                            ManualHemisphereWarp2D.AtlasSide.RIGHT,
                            segment, destination, sidedDestination);
                }
            }
        } else {
            if (displayIncludesSide(requestModel,
                    ManualHemisphereWarp2D.AtlasSide.LEFT)) {
                appendCachedSide(requestModel, state,
                        ManualHemisphereWarp2D.AtlasSide.LEFT, left,
                        pathCache, destination, sidedDestination);
            }
            if (displayIncludesSide(requestModel,
                    ManualHemisphereWarp2D.AtlasSide.RIGHT)) {
                appendCachedSide(requestModel, state,
                        ManualHemisphereWarp2D.AtlasSide.RIGHT, right,
                        pathCache, destination, sidedDestination);
            }
        }
        // Edges exactly on/crossing the anatomical seam are shared immutable
        // geometry and are deliberately mapped as one unsided path.
        for (final LineSegment segment : state.content().reviewSectionMode()
                == ReviewSectionMode.FULL ? seam : List.<LineSegment>of()) {
            final int firstAdded = destination.size();
            appendMappedSegments(state, segment, destination);
            for (int index = firstAdded; index < destination.size(); index++) {
                sidedDestination.add(new SidedLineSegment(
                        Optional.empty(), destination.get(index)));
            }
        }
    }

    private static void appendMappedSideSegments(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide side,
            final LineSegment source,
            final List<LineSegment> destination,
            final List<SidedLineSegment> sidedDestination) {
        final int firstAdded = destination.size();
        appendMappedSegments(state, side, source, destination);
        for (int index = firstAdded; index < destination.size(); index++) {
            sidedDestination.add(new SidedLineSegment(
                    Optional.of(side), destination.get(index)));
        }
    }

    static void splitDisjoinedSegment(
            final LineSegment segment,
            final int planeWidth,
            final List<LineSegment> left,
            final List<LineSegment> right) {
        final double centre = (planeWidth - 1.0) * 0.5;
        final Point2D first = segment.first();
        final Point2D second = segment.second();
        final double firstOffset = first.x() - centre;
        final double secondOffset = second.x() - centre;
        if (Math.abs(firstOffset) <= 1e-9
                && Math.abs(secondOffset) <= 1e-9) {
            return;
        }
        if (Math.abs(firstOffset) <= 1e-9
                || Math.abs(secondOffset) <= 1e-9
                || firstOffset * secondOffset < 0) {
            // Disjoined is a hard raw-atlas split. A connector that touches
            // or crosses the canonical midline belongs to neither independent
            // half and must never be replayed through both side transforms.
            return;
        }
        final boolean useLeft = firstOffset < -1e-9
                || secondOffset < -1e-9;
        (useLeft ? left : right).add(segment);
    }

    private static boolean displayIncludesSide(
            final ReviewViewModel model,
            final ManualHemisphereWarp2D.AtlasSide side) {
        final var content = model.reviewState().content();
        if (content.reviewSectionMode() != ReviewSectionMode.HALF
                || content.halfAtlasCoverage().includesOppositeRemnant()) {
            return true;
        }
        return switch (content.observedHemisphere()) {
            case LEFT -> side == ManualHemisphereWarp2D.AtlasSide.LEFT;
            case RIGHT -> side == ManualHemisphereWarp2D.AtlasSide.RIGHT;
            case BOTH, UNSURE -> false;
        };
    }

    private static void appendCachedSide(
            final ReviewViewModel requestModel,
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide side,
            final List<LineSegment> source,
            final Map<SidePathCacheKey, List<LineSegment>> pathCache,
            final List<LineSegment> destination,
            final List<SidedLineSegment> sidedDestination) {
        if (source.isEmpty()) {
            return;
        }
        final SidePathCacheKey key = sidePathCacheKey(
                requestModel, side);
        List<LineSegment> mapped = pathCache.get(key);
        if (mapped == null) {
            final List<LineSegment> built = new ArrayList<>();
            for (final LineSegment segment : source) {
                appendMappedSegments(state, side, segment, built);
            }
            mapped = List.copyOf(built);
            pathCache.put(key, mapped);
        }
        destination.addAll(mapped);
        for (final LineSegment segment : mapped) {
            sidedDestination.add(new SidedLineSegment(
                    Optional.of(side), segment));
        }
    }

    private static Optional<ManualHemisphereWarp2D.AtlasSide>
            atlasSideForSegment(
                    final LineSegment segment,
                    final int planeWidth) {
        if (planeWidth <= 0) {
            return Optional.empty();
        }
        final double centre = (planeWidth - 1.0) * 0.5;
        final double first = segment.first().x() - centre;
        final double second = segment.second().x() - centre;
        if (Math.abs(first) <= 1e-9 || Math.abs(second) <= 1e-9
                || first * second < 0) {
            return Optional.empty();
        }
        return Optional.of(first < 0
                ? ManualHemisphereWarp2D.AtlasSide.LEFT
                : ManualHemisphereWarp2D.AtlasSide.RIGHT);
    }

    private static SidePathCacheKey sidePathCacheKey(
            final ReviewViewModel model,
            final ManualHemisphereWarp2D.AtlasSide side) {
        final var state = model.reviewState();
        final var content = state.content();
        final var plane = model.atlasPlane().orElse(null);
        final List<ManualWarpControl> controls = content.hemisphereWarp()
                .map(warp -> warp.manualControls(side)).orElse(List.of());
        return new SidePathCacheKey(
                model.coronalLevel(),
                plane == null ? 0 : plane.width(),
                plane == null ? 0 : plane.height(),
                content.atlasPlaneTilt(),
                content.orientation(),
                content.reviewSectionMode(),
                content.outlineWarp()
                        .map(warp -> warp.getClass().getName() + ":"
                                + warp.hashCode()).orElse(""),
                Integer.toString(state.preOutlineAtlasToPreview().hashCode()),
                Integer.toString(content.postOutlinePreviewAdjustment()
                        .hashCode()),
                Integer.toString(content.manualSidePlacement()
                        .transform(side).hashCode()),
                content.localWarp()
                        .map(warp -> warp.getClass().getName() + ":"
                                + warp.hashCode()).orElse(""),
                side, controls);
    }

    private static List<LineSegment> buildGridSegments(
            final ReviewViewModel requestModel) {
        final int width = requestModel.preview().width();
        final int height = requestModel.preview().height();
        final int divisions = 8;
        final int samples = 80;
        final List<LineSegment> result = new ArrayList<>();
        for (int line = 0; line <= divisions; line++) {
            final double fixedX = (width - 1.0) * line / divisions;
            final double fixedY = (height - 1.0) * line / divisions;
            Point2D previousVertical = null;
            Point2D previousHorizontal = null;
            for (int sample = 0; sample <= samples; sample++) {
                final double x = (width - 1.0) * sample / samples;
                final double y = (height - 1.0) * sample / samples;
                final Point2D vertical = applyLocalGridWarp(requestModel,
                        new Point2D(fixedX, y));
                final Point2D horizontal = applyLocalGridWarp(requestModel,
                        new Point2D(x, fixedY));
                if (previousVertical != null) {
                    result.add(new LineSegment(previousVertical, vertical));
                    result.add(new LineSegment(previousHorizontal, horizontal));
                }
                previousVertical = vertical;
                previousHorizontal = horizontal;
            }
        }
        return List.copyOf(result);
    }

    private static Point2D applyLocalGridWarp(
            final ReviewViewModel requestModel,
            final Point2D point) {
        return requestModel.reviewState().content().hemisphereWarp()
                .map(warp -> warp.apply(point))
                .orElseGet(() -> requestModel.reviewState().content()
                        .localWarp().map(warp -> warp.apply(point))
                        .orElse(point));
    }

    private static List<LineSegment> contourSegments(
            final SelectedAtlasContour contour) {
        final List<LineSegment> segments = new ArrayList<>();
        for (final Point2D point : contour.boundaryPoints()) {
            final int x = rounded(point.x());
            final int y = rounded(point.y());
            boolean connected = false;
            if (contour.isBoundary(x + 1, y)) {
                segments.add(new LineSegment(point, new Point2D(x + 1, y)));
                connected = true;
            }
            if (contour.isBoundary(x, y + 1)) {
                segments.add(new LineSegment(point, new Point2D(x, y + 1)));
                connected = true;
            }
            if (!contour.isBoundary(x + 1, y)
                    && !contour.isBoundary(x, y + 1)
                    && contour.isBoundary(x + 1, y + 1)) {
                segments.add(new LineSegment(point,
                        new Point2D(x + 1, y + 1)));
                connected = true;
            }
            if (!contour.isBoundary(x - 1, y)
                    && !contour.isBoundary(x, y + 1)
                    && contour.isBoundary(x - 1, y + 1)) {
                segments.add(new LineSegment(point,
                        new Point2D(x - 1, y + 1)));
                connected = true;
            }
            if (!connected) {
                segments.add(new LineSegment(point, point));
            }
        }
        return List.copyOf(segments);
    }

    private static ThreadFactory daemonThreadFactory(final String prefix) {
        return runnable -> {
            final Thread thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Source-preview mapping retained for drag and focused interaction tests.
     */
    public ScreenMapping screenMapping() {
        return sourceScreenMapping();
    }

    public ScreenMapping sourceScreenMapping() {
        if (model == null) {
            throw new IllegalStateException(
                    "Review canvas has no model");
        }
        return paneMapping(
                model.preview().width(), model.preview().height(), 0);
    }

    public ScreenMapping atlasScreenMapping() {
        final AtlasCoronalPlane plane = currentAtlasPlane();
        return paneMapping(plane.width(), plane.height(), atlasPaneX());
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT);
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
        super.paintComponent(graphics);
        if (model == null || previewImage == null) {
            return;
        }
        final Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            canvas.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            final ScreenMapping source = sourceScreenMapping();
            drawPaneLabel(canvas, channelDisplayEnabled ? channelDisplayLabel : manualRoiLayer.previewSelected()
                            ? "Manual ROI export preview — selected polygons bright • remaining image 20% • exact export uses source pixels"
                            : exportedRoiPreviewVisible
                            ? "Export preview — selected ROI bright • remaining tissue 20% • exact export uses source pixels"
                            : "Tissue + reviewer-controlled overlay",
                    0, sourcePaneWidth());
            canvas.drawImage(channelDisplayEnabled ? displayedChannelImage : exportedRoiPreviewVisible
                            && exportedRoiPreviewImage != null
                            ? exportedRoiPreviewImage : previewImage,
                    imageToScreen(source), null);
            if (displayInspection || channelDisplayEnabled && displayedChannelImage == null) {
                return;
            }
            if (channelDisplayEnabled && exportedRoiPreviewVisible && exportedRoiDimmingOverlay != null) {
                canvas.drawImage(exportedRoiDimmingOverlay, imageToScreen(source), null);
            }
            if (beforeOverlayImage != null
                    && comparisonMode != ComparisonMode.AFTER) {
                drawOverlayImage(canvas, beforeOverlayImage, source,
                        comparisonMode == ComparisonMode.COMPARE
                                ? overlayOpacity * 0.45f : overlayOpacity);
            }
            if (comparisonMode != ComparisonMode.BEFORE) {
                if (interactionTool == InteractionTool.TRANSFORM
                        && !hasLocalDeformation()
                        && placementOverlays != null) {
                    drawPlacementOverlays(canvas, placementOverlays, source,
                            overlayOpacity);
                } else if (overlayImage != null) {
                    drawOverlayImage(canvas, overlayImage, source,
                            overlayOpacity);
                }
            }
            if (boundaryFitEditingEnabled) {
                drawBoundaryFitGhost(canvas, source);
            }
            if (boundaryWarpPreviewVisible
                    && boundaryWarpGhostImage != null) {
                drawOverlayImage(canvas, boundaryWarpGhostImage, source,
                        Math.min(1f, overlayOpacity * 0.85f));
            }
            drawReviewedBoundaryOnTissue(canvas, source);
            drawTissueSupportTrace(canvas, source);
            drawTissueSupportControls(canvas, source);
            drawSelectedRegionOutlineOnTissue(canvas, source);
            drawStructureDraftGeometry(canvas, source);
            drawStructureGesturePreview(canvas, source);
            drawLocalWarpGrid(canvas, source);
            drawManualWarpControlsOnTissue(canvas, source);
            drawStructureSelection(canvas, source);
            drawSourceLandmarks(canvas, source);
            if (boundaryFitEditingEnabled
                    || interactionTool == InteractionTool.BORDER) {
                drawBoundaryFitMatches(canvas, source);
            }
            drawTransformSelection(canvas, source);
            manualRoiLayer.paint(canvas, source, PANE_LABEL_HEIGHT,
                    sourcePaneWidth(),
                    Math.max(1, getHeight() - PANE_LABEL_HEIGHT));

            if (!singleTissuePane && atlasImage != null) {
                final ScreenMapping atlas = atlasScreenMapping();
                drawPaneLabel(canvas, boundaryFitEditingEnabled
                                ? "Atlas reference — choose a numbered exterior point"
                                : "Atlas — click landmark point first",
                        atlasPaneX(), sourcePaneWidth());
                canvas.drawImage(atlasImage, imageToScreen(atlas), null);
                drawSelectedRegionOutlineOnAtlas(canvas, atlas);
                drawManualWarpControlsOnAtlas(canvas, atlas);
                drawAtlasLandmarks(canvas, atlas);
                drawPendingAtlasPoint(canvas, atlas);
                if (boundaryFitEditingEnabled) {
                    model.boundaryFit().draft().ifPresent(draft ->
                            drawBoundaryFitAtlasAnchors(
                                    canvas, atlas, draft));
                }
            } else if (!singleTissuePane) {
                drawPaneLabel(canvas, "Atlas — wait for verified plane",
                        atlasPaneX(), sourcePaneWidth());
            }
            drawStatus(canvas);
        } finally {
            canvas.dispose();
        }
    }

    private void drawOverlayImage(
            final Graphics2D canvas,
            final BufferedImage image,
            final ScreenMapping source,
            final float opacity) {
        final AffineTransform dragged = imageToScreen(source);
        // A Disjoined gesture is side-local. The selected vector contour is
        // previewed below point-by-point, while this combined dim-guide cache
        // stays put until the audited side placement is installed. Applying
        // one affine to this image would incorrectly drag the opposite half.
        if (!disjoinedTransformEnabled()) {
            dragged.concatenate(livePreviewTransform());
        }
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        final var previousComposite = canvas.getComposite();
        canvas.setComposite(AlphaComposite.SrcOver.derive(
                Math.max(0, Math.min(1, opacity))));
        canvas.drawImage(image, dragged, null);
        canvas.setComposite(previousComposite);
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    /**
     * Draws an atlas layer whose raster bounds retain mapped geometry outside
     * the preview. Applying the live transform before its preview-space origin
     * lets anatomy beyond the black canvas move back into view during a drag.
     */
    private void drawPlacementOverlays(
            final Graphics2D canvas,
            final PlacementLayers layers,
            final ScreenMapping source,
            final float opacity) {
        if (!disjoinedTransformEnabled()) {
            drawPlacementOverlay(canvas, layers.joined(), source, opacity,
                    true);
            return;
        }
        final boolean leftActive = activeHemisphereSide
                == ManualHemisphereWarp2D.AtlasSide.LEFT;
        drawPlacementOverlay(canvas,
                leftActive ? layers.right() : layers.left(),
                source, opacity, false);
        drawPlacementOverlay(canvas,
                leftActive ? layers.left() : layers.right(),
                source, opacity, true);
        drawDisjoinedSideLabel(canvas,
                ManualHemisphereWarp2D.AtlasSide.LEFT, source, leftActive);
        drawDisjoinedSideLabel(canvas,
                ManualHemisphereWarp2D.AtlasSide.RIGHT, source, !leftActive);
    }

    private void drawDisjoinedSideLabel(
            final Graphics2D canvas,
            final ManualHemisphereWarp2D.AtlasSide side,
            final ScreenMapping mapping,
            final boolean active) {
        final Selection selection = baseSelection(side);
        if (selection == null) {
            return;
        }
        double minimumX = Double.POSITIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        for (final Point2D corner : selection.corners()) {
            final Point2D screen = mapping.previewToScreen(corner);
            minimumX = Math.min(minimumX, screen.x());
            minimumY = Math.min(minimumY, screen.y());
        }
        canvas.setColor(active ? REVIEWED_BOUNDARY_COLOR
                : new Color(255, 160, 60, 175));
        canvas.drawString(side == ManualHemisphereWarp2D.AtlasSide.LEFT
                        ? "Atlas L" : "Atlas R",
                rounded(minimumX) + 7, rounded(minimumY) + 17);
    }

    private void drawPlacementOverlay(
            final Graphics2D canvas,
            final PlacementLayer layer,
            final ScreenMapping source,
            final float opacity,
            final boolean applyLiveTransform) {
        if (layer == null) {
            return;
        }
        final AffineTransform dragged = imageToScreen(source);
        if (applyLiveTransform) {
            dragged.concatenate(livePreviewTransform());
        }
        dragged.translate(layer.originX(), layer.originY());
        final var previousComposite = canvas.getComposite();
        canvas.setComposite(AlphaComposite.SrcOver.derive(
                Math.max(0, Math.min(1, opacity))));
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        canvas.drawImage(layer.image(), dragged, null);
        canvas.setComposite(previousComposite);
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    private void drawBoundaryFitGhost(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        if (model == null || renderPaths == null) {
            return;
        }
        final BoundaryFitCandidate candidate = model.boundaryFit()
                .candidate().orElse(null);
        final BoundaryFitPreview preview = model.boundaryFit()
                .preview().orElse(null);
        if (candidate == null && preview == null) {
            return;
        }
        final BoundaryFitRequest request = candidate != null
                ? candidate.request() : preview.request();
        final AffineTransform2D correction = candidate != null
                ? candidate.previewCorrection()
                : preview.previewCorrection();
        final List<LineSegment> boundary;
        if (request.sectionMode()
                != ReviewSectionMode.DISJOINED) {
            boundary = renderPaths.tissueBoundarySegments();
        } else if (request.targetSide().orElseThrow()
                == ManualHemisphereWarp2D.AtlasSide.LEFT) {
            boundary = renderPaths.leftPlacementBoundarySegments();
        } else {
            boundary = renderPaths.rightPlacementBoundarySegments();
        }
        drawCandidateSegments(canvas, boundary, mapping,
                correction, new Color(255, 178, 68, 170),
                Math.max(1.0f, regionStrokeWidth));
        final List<LineSegment> selected = request.sectionMode()
                != ReviewSectionMode.DISJOINED
                ? renderPaths.tissueContourSegments()
                : request.targetSide().orElseThrow()
                == ManualHemisphereWarp2D.AtlasSide.LEFT
                ? renderPaths.leftPlacementContourSegments()
                : renderPaths.rightPlacementContourSegments();
        drawCandidateSegments(canvas, selected, mapping,
                correction, new Color(selectedRegionColor.getRed(),
                        selectedRegionColor.getGreen(),
                        selectedRegionColor.getBlue(), 205),
                regionStrokeWidth);
    }

    private static void drawCandidateSegments(
            final Graphics2D canvas,
            final List<LineSegment> segments,
            final ScreenMapping mapping,
            final AffineTransform2D correction,
            final Color color,
            final float width) {
        final java.awt.Stroke priorStroke = canvas.getStroke();
        final Color priorColor = canvas.getColor();
        canvas.setStroke(new BasicStroke(width,
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        canvas.setColor(color);
        for (final LineSegment segment : segments) {
            final Point2D first = mapping.previewToScreen(
                    correction.apply(segment.first()));
            final Point2D second = mapping.previewToScreen(
                    correction.apply(segment.second()));
            canvas.drawLine(rounded(first.x()), rounded(first.y()),
                    rounded(second.x()), rounded(second.y()));
        }
        canvas.setStroke(priorStroke);
        canvas.setColor(priorColor);
    }

    private void drawBoundaryFitMatches(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        if (model == null) {
            return;
        }
        final java.awt.Stroke priorStroke = canvas.getStroke();
        final Color priorColor = canvas.getColor();
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        if (interactionTool == InteractionTool.BORDER) {
            final List<BoundaryWarpRequest> drafts = model.boundaryWarp()
                    .drafts();
            for (int index = drafts.size() - 1; index >= 0; index--) {
                drawBoundaryMatchList(canvas, mapping, drafts.get(index),
                        index > 0);
            }
        } else {
            model.boundaryFit().draft().ifPresent(draft -> {
                if (singleTissuePane) {
                    drawBoundaryFitAnchors(canvas, mapping, draft);
                } else {
                    drawBoundaryFitTissueAnchors(canvas, mapping, draft);
                }
            });
        }
        if (pendingBoundaryAtlasPoint != null) {
            boundaryFitRing(canvas,
                    mapping.previewToScreen(pendingBoundaryAtlasPoint),
                    new Color(255, 235, 90), true);
        }
        canvas.setStroke(priorStroke);
        canvas.setColor(priorColor);
    }

    private void drawBoundaryMatchList(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final BoundaryWarpRequest request,
            final boolean inactiveSide) {
        for (final BoundaryFitMatch match : request.matches()) {
            final Point2D atlasPoint = boundaryFitGesturePoint(
                    match, true);
            final Point2D tissuePoint = boundaryFitGesturePoint(
                    match, false);
            final Point2D atlasScreen = mapping.previewToScreen(atlasPoint);
            final Point2D tissueScreen = mapping.previewToScreen(tissuePoint);
            canvas.setStroke(match.included()
                    ? new BasicStroke(1.0f)
                    : new BasicStroke(1.0f, BasicStroke.CAP_ROUND,
                            BasicStroke.JOIN_ROUND, 10,
                            new float[]{4, 4}, 0));
            canvas.setColor(match.included()
                    ? new Color(255, 188, 70,
                            inactiveSide ? 90 : 190)
                    : new Color(160, 160, 160, 130));
            canvas.drawLine(rounded(atlasScreen.x()),
                    rounded(atlasScreen.y()), rounded(tissueScreen.x()),
                    rounded(tissueScreen.y()));
            final boolean selected = match.id().equals(
                    selectedBoundaryMatchId);
            boundaryFitRing(canvas, atlasScreen,
                    inactiveSide ? new Color(170, 125, 70)
                            : new Color(255, 177, 38), selected);
            boundaryFitEndpoint(canvas, tissueScreen,
                    inactiveSide ? new Color(80, 145, 160)
                            : new Color(80, 225, 255), selected,
                    !isBoundarySample(request.tissueBoundary(),
                            tissuePoint));
            if (!match.included()) {
                drawIgnoredCross(canvas, atlasScreen);
                drawIgnoredCross(canvas, tissueScreen);
            }
        }
    }

    private static void drawIgnoredCross(
            final Graphics2D canvas,
            final Point2D point) {
        final Color prior = canvas.getColor();
        final java.awt.Stroke priorStroke = canvas.getStroke();
        canvas.setColor(new Color(120, 120, 120, 210));
        canvas.setStroke(new BasicStroke(1.5f));
        final int x = rounded(point.x());
        final int y = rounded(point.y());
        canvas.drawLine(x - 5, y - 5, x + 5, y + 5);
        canvas.drawLine(x - 5, y + 5, x + 5, y - 5);
        canvas.setStroke(priorStroke);
        canvas.setColor(prior);
    }

    private void drawBoundaryFitAnchors(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final BoundaryFitDraft draft) {
        final String activeIdentifier = draft.activeAnchorId().orElse("");
        for (final BoundaryFitAnchor anchor : draft.anchors()) {
            final Point2D atlasPoint = boundaryFitAnchorGesturePoint(
                    anchor, true);
            final Point2D atlasScreen = mapping.previewToScreen(atlasPoint);
            final Optional<Point2D> tissuePoint = anchor
                    .tissuePreviewPoint().map(point ->
                            boundaryFitAnchorGesturePoint(anchor, false));
            final boolean selected = anchor.id().equals(activeIdentifier)
                    || anchor.id().equals(selectedBoundaryMatchId);
            final Color lineColor = anchor.included()
                    ? new Color(255, 188, 70, 190)
                    : new Color(160, 160, 160, 130);
            tissuePoint.ifPresent(point -> {
                final Point2D tissueScreen = mapping.previewToScreen(point);
                canvas.setStroke(anchor.included()
                        ? new BasicStroke(1.0f)
                        : new BasicStroke(1.0f, BasicStroke.CAP_ROUND,
                                BasicStroke.JOIN_ROUND, 10,
                                new float[]{4, 4}, 0));
                canvas.setColor(lineColor);
                canvas.drawLine(rounded(atlasScreen.x()),
                        rounded(atlasScreen.y()),
                        rounded(tissueScreen.x()),
                        rounded(tissueScreen.y()));
                boundaryFitEndpoint(canvas, tissueScreen,
                        anchor.included()
                                ? new Color(80, 225, 255)
                                : new Color(150, 150, 150),
                        selected, !isBoundarySample(
                                draft.request().tissueBoundary(), point));
            });
            boundaryFitRing(canvas, atlasScreen,
                    anchor.included()
                            ? new Color(255, 177, 38)
                            : new Color(150, 150, 150),
                    selected);
            canvas.setColor(selected
                    ? new Color(255, 238, 160)
                    : new Color(255, 205, 105));
            canvas.drawString(Integer.toString(anchor.ordinal()),
                    rounded(atlasScreen.x()) + 7,
                    rounded(atlasScreen.y()) - 6);
        }
    }

    private void drawBoundaryFitTissueAnchors(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final BoundaryFitDraft draft) {
        final String activeIdentifier = draft.activeAnchorId().orElse("");
        for (final BoundaryFitAnchor anchor : draft.anchors()) {
            if (anchor.tissuePreviewPoint().isEmpty()) {
                continue;
            }
            final Point2D point = boundaryFitAnchorGesturePoint(
                    anchor, false);
            final Point2D screen = mapping.previewToScreen(point);
            final boolean selected = anchor.id().equals(activeIdentifier)
                    || anchor.id().equals(selectedBoundaryMatchId);
            boundaryFitEndpoint(canvas, screen, anchor.included()
                    ? new Color(80, 225, 255)
                    : new Color(150, 150, 150), selected,
                    !isBoundarySample(draft.request().tissueBoundary(),
                            point));
            canvas.setColor(selected
                    ? new Color(210, 250, 255)
                    : new Color(130, 225, 240));
            canvas.drawString(Integer.toString(anchor.ordinal()),
                    rounded(screen.x()) + 7, rounded(screen.y()) - 6);
        }
    }

    private void drawBoundaryFitAtlasAnchors(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final BoundaryFitDraft draft) {
        final String activeIdentifier = draft.activeAnchorId().orElse("");
        for (final BoundaryFitAnchor anchor : draft.anchors()) {
            final Point2D point = gesture != null
                    && anchor.id().equals(gesture.landmarkId)
                    && gesture.kind == GestureKind.BOUNDARY_FIT_ATLAS
                    ? gesture.point : anchor.atlasPlanePoint();
            final Point2D screen = mapping.previewToScreen(point);
            final boolean selected = anchor.id().equals(activeIdentifier)
                    || anchor.id().equals(selectedBoundaryMatchId);
            boundaryFitRing(canvas, screen, anchor.included()
                    ? new Color(255, 177, 38)
                    : new Color(150, 150, 150), selected);
            canvas.setColor(selected
                    ? new Color(255, 238, 160)
                    : new Color(255, 205, 105));
            canvas.drawString(anchor.ordinal() + " "
                            + anchor.positionLabel(),
                    rounded(screen.x()) + 7, rounded(screen.y()) - 6);
        }
    }

    private Point2D boundaryFitGesturePoint(
            final BoundaryFitMatch match,
            final boolean atlasEndpoint) {
        if (gesture == null || !match.id().equals(gesture.landmarkId)) {
            return atlasEndpoint ? match.atlasPreviewPoint()
                    : match.tissuePreviewPoint();
        }
        if ((atlasEndpoint && (gesture.kind
                == GestureKind.BOUNDARY_FIT_ATLAS
                || gesture.kind == GestureKind.BOUNDARY_WARP_ATLAS))
                || (!atlasEndpoint && (gesture.kind
                == GestureKind.BOUNDARY_FIT_TISSUE
                || gesture.kind == GestureKind.BOUNDARY_WARP_TISSUE))) {
            return gesture.point;
        }
        return atlasEndpoint ? match.atlasPreviewPoint()
                : match.tissuePreviewPoint();
    }

    private Point2D boundaryFitAnchorGesturePoint(
            final BoundaryFitAnchor anchor,
            final boolean atlasEndpoint) {
        if (gesture == null || !anchor.id().equals(gesture.landmarkId)) {
            return atlasEndpoint ? anchor.atlasPreviewPoint()
                    : anchor.tissuePreviewPoint().orElseThrow();
        }
        if ((atlasEndpoint && gesture.kind
                == GestureKind.BOUNDARY_FIT_ATLAS)
                || (!atlasEndpoint && gesture.kind
                == GestureKind.BOUNDARY_FIT_TISSUE)) {
            return gesture.point;
        }
        return atlasEndpoint ? anchor.atlasPreviewPoint()
                : anchor.tissuePreviewPoint().orElseThrow();
    }

    private static void boundaryFitRing(
            final Graphics2D canvas,
            final Point2D point,
            final Color color,
            final boolean selected) {
        final int diameter = 9;
        final int x = rounded(point.x()) - diameter / 2;
        final int y = rounded(point.y()) - diameter / 2;
        if (selected) {
            canvas.setColor(new Color(255, 255, 255, 225));
            canvas.setStroke(new BasicStroke(3f));
            canvas.drawOval(x - 1, y - 1, diameter + 2, diameter + 2);
        }
        canvas.setColor(color);
        canvas.setStroke(new BasicStroke(1.5f));
        canvas.drawOval(x, y, diameter, diameter);
    }

    private static void boundaryFitEndpoint(
            final Graphics2D canvas,
            final Point2D point,
            final Color color,
            final boolean selected,
            final boolean freeEndpoint) {
        if (!freeEndpoint) {
            boundaryFitRing(canvas, point, color, selected);
            return;
        }
        final int x = rounded(point.x());
        final int y = rounded(point.y());
        final int radius = 5;
        if (selected) {
            canvas.setColor(new Color(255, 255, 255, 225));
            canvas.setStroke(new BasicStroke(3f));
            canvas.drawPolygon(
                    new int[]{x, x + radius + 2, x, x - radius - 2},
                    new int[]{y - radius - 2, y, y + radius + 2, y}, 4);
        }
        canvas.setColor(color);
        canvas.setStroke(new BasicStroke(1.5f));
        canvas.drawPolygon(
                new int[]{x, x + radius, x, x - radius},
                new int[]{y - radius, y, y + radius, y}, 4);
    }

    private static boolean isBoundarySample(
            final List<BoundaryFitSample> samples,
            final Point2D point) {
        return samples.stream().anyMatch(sample ->
                distance(sample.point(), point) <= 1e-9);
    }

    private void handleMousePressed(final MouseEvent event) {
        if (model == null) {
            return;
        }
        if (event.getButton() != MouseEvent.BUTTON1
                && event.getButton() != MouseEvent.BUTTON2
                && event.getButton() != MouseEvent.BUTTON3) {
            return;
        }
        requestFocusInWindow();
        final Point point = event.getPoint();
        if (event.getButton() == MouseEvent.BUTTON2
                || spaceDown || interactionTool == InteractionTool.PAN || displayInspection
                || channelDisplayEnabled && displayedChannelImage == null) {
            if (paneAt(point) != Pane.NONE) {
                dragOrigin = point;
                lastDragPoint = point;
                gesture = Gesture.pan(paneAt(point));
            }
            return;
        }
        if (manualRoiLayer.enabled()
                && paneAt(point) == Pane.TISSUE
                && manualRoiLayer.press(event, sourceScreenMapping())) {
            repaint();
            return;
        }
        if (!manualInteractionEnabled()) {
            return;
        }
        if (tissueSupportTracing) {
            if (paneAt(point) == Pane.TISSUE
                    && contains(sourceScreenMapping(), point)) {
                final Point2D previewPoint = sourceScreenMapping()
                        .screenToPreview(new Point2D(point.x, point.y));
                if (tissueSupportTrace.isEmpty()
                        || !tissueSupportTrace.get(
                                tissueSupportTrace.size() - 1)
                                .equals(previewPoint)) {
                    if (tissueSupportTrace.size()
                            < ReviewedTissueSupport.MAXIMUM_CONTROLS) {
                        tissueSupportTrace.add(previewPoint);
                        interactionListener.tissueSupportTraceChanged(
                                tissueSupportTrace.size());
                    }
                }
                repaint();
            }
            return;
        }
        if (interactionTool == InteractionTool.POINTS || interactionTool == InteractionTool.LANDMARKS) {
            if (!manualWarpEditingEnabled) return;
            if (interactionTool == InteractionTool.POINTS && tissueSupportEditing && paneAt(point) == Pane.TISSUE) {
                final ReviewedTissueSupport.Control crop =
                        tissueSupportControlAt(point);
                if (event.isAltDown() && crop != null) {
                    interactionListener.deleteTissueSupportControl(crop.id());
                    return;
                }
                if (event.isShiftDown()) {
                    final TissueSupportEdge edge = tissueSupportEdgeAt(point);
                    if (edge != null) {
                        interactionListener.insertTissueSupportControl(
                                edge.componentIndex(), edge.afterVertexIndex(),
                                sourceScreenMapping().screenToPreview(
                                        new Point2D(point.x, point.y)));
                    }
                    return;
                }
                if (crop != null) {
                    gesture = Gesture.tissueSupport(
                            crop.id(), crop.point());
                    dragOrigin = point;
                    return;
                }
            }
            final Gesture manual = interactionTool == InteractionTool.POINTS ? manualWarpGestureAt(point) : null;
            if (manual != null) {
                gesture = manual;
                dragOrigin = point;
                return;
            }
            final Gesture structure = interactionTool == InteractionTool.POINTS ? structureWarpGestureAt(point) : null;
            if (structure != null) {
                gesture = structure;
                dragOrigin = point;
                return;
            }
            if (interactionTool == InteractionTool.POINTS && manualWarpPointArmed && paneAt(point) == Pane.TISSUE
                    && contains(sourceScreenMapping(), point)) {
                manualWarpPointArmed = false;
                final Point2D current = sourceScreenMapping().screenToPreview(
                        new Point2D(point.x, point.y));
                gesture = Gesture.manualWarpPoint(current);
                dragOrigin = point;
                return;
            }
            final Gesture endpoint = endpointGestureAt(point);
            if (endpoint != null) {
                gesture = endpoint;
                dragOrigin = point;
                interactionListener.landmarkSelected(
                        endpoint.landmarkId);
                return;
            }
            if (atlasImage != null && contains(atlasScreenMapping(), point)) {
                final Point2D requested = atlasScreenMapping().screenToPreview(
                        new Point2D(point.x, point.y));
                pendingAtlasPoint = snapToSelectedRegionBoundary(requested);
                if (model.selectedAtlasRegion()
                        .isPresent() && pendingAtlasPoint == null) {
                    repaint();
                    return;
                }
                dragOrigin = null;
                tissueClickOrigin = null;
                repaint();
                return;
            }
            if (contains(sourceScreenMapping(), point)
                    && pendingAtlasPoint != null) {
                tissueClickOrigin = point;
                dragOrigin = null;
            }
            return;
        }
        if (interactionTool == InteractionTool.BORDER) {
            final BoundaryWarpRequest request = model.boundaryWarp()
                    .draft().orElse(null);
            if (request == null || model.boundaryWarp().loading()) {
                return;
            }
            final Gesture boundary = boundaryWarpGestureAt(point);
            if (boundary != null) {
                selectedBoundaryMatchId = boundary.landmarkId;
                boundaryWarpSideForMatch(boundary.landmarkId)
                        .ifPresent(interactionListener
                                ::boundaryWarpSideActivated);
                interactionListener.boundaryWarpMatchSelected(
                        boundary.landmarkId);
                if (event.isAltDown()) {
                    interactionListener.removeBoundaryWarpMatch(
                            boundary.landmarkId);
                    return;
                }
                gesture = boundary;
                dragOrigin = point;
                return;
            }
            if (paneAt(point) == Pane.TISSUE) {
                final Point2D previewPoint = sourceScreenMapping()
                        .screenToPreview(new Point2D(point.x, point.y));
                if (pendingBoundaryAtlasPoint == null) {
                    pendingBoundaryAtlasPoint = nearestBoundarySample(
                            request.atlasBoundary(), previewPoint, 16);
                } else {
                    final Point2D tissue = snapOrFreeBoundaryPoint(
                            request.tissueBoundary(), previewPoint, 16);
                    interactionListener.addBoundaryWarpMatch(
                            pendingBoundaryAtlasPoint, tissue);
                    pendingBoundaryAtlasPoint = null;
                }
                repaint();
            }
            return;
        }
        if (interactionTool == InteractionTool.TRANSFORM) {
            final BoundaryFitDraft draft = model.boundaryFit().draft()
                    .orElse(null);
            if (boundaryFitEditingEnabled && model.boundaryFit().active()) {
                // Guided matching is deliberately modal on the canvas. The
                // numbered endpoints are the only left-button geometry that
                // may be edited until the reviewer explicitly clicks the
                // toolbar's Transform/Cancel action. In particular, the
                // large placement body and resize handles must never steal a
                // near-boundary press or silently discard the draft.
                if (draft == null || model.boundaryFit().loading()) {
                    return;
                }
                final Gesture boundary = boundaryFitGestureAt(point);
                if (boundary != null) {
                    selectedBoundaryMatchId = boundary.landmarkId;
                    interactionListener.boundaryFitMatchSelected(
                            boundary.landmarkId);
                    gesture = boundary;
                    dragOrigin = point;
                    return;
                }
                if (paneAt(point) == Pane.TISSUE
                        && draft.activeAnchorId().isPresent()) {
                    final Point2D previewPoint = sourceScreenMapping()
                            .screenToPreview(new Point2D(point.x, point.y));
                    final Point2D tissue = snapOrFreeBoundaryPoint(
                            draft.request().tissueBoundary(),
                            previewPoint, 16);
                    interactionListener.setBoundaryFitTissuePoint(
                            draft.activeAnchorId().orElseThrow(), tissue);
                    repaint();
                    return;
                }
                return;
            }
            if (!boundaryFitEditingEnabled
                    && model.boundaryFit().active()) {
                interactionListener.cancelBoundaryFitForManualTransform();
            }
            final Gesture transform = transformGestureAt(point);
            if (transform != null) {
                gesture = transform;
                dragOrigin = point;
            }
        }
    }

    private void handleMouseDragged(final MouseEvent event) {
        if (manualRoiLayer.enabled()
                && manualRoiLayer.drag(event, sourceScreenMapping())) {
            repaint();
            return;
        }
        if (!manualInteractionEnabled()
                && (gesture == null || gesture.kind != GestureKind.PAN)) {
            return;
        }
        if (dragOrigin == null || gesture == null) {
            return;
        }
        final Point point = event.getPoint();
        if (gesture.kind == GestureKind.PAN) {
            final int dx = point.x - lastDragPoint.x;
            final int dy = point.y - lastDragPoint.y;
            if (gesture.pane == Pane.TISSUE) {
                tissuePanX += dx;
                tissuePanY += dy;
            } else {
                atlasPanX += dx;
                atlasPanY += dy;
            }
            clampPan(gesture.pane);
            lastDragPoint = point;
        } else {
            updateGesture(point, aspectRatioLocked || event.isShiftDown());
        }
        repaint();
    }

    private void handleMouseReleased(final MouseEvent event) {
        if (manualRoiLayer.enabled()
                && manualRoiLayer.release(event, sourceScreenMapping())) {
            repaint();
            return;
        }
        if (!manualInteractionEnabled()
                && (gesture == null || gesture.kind != GestureKind.PAN)) {
            return;
        }
        if (tissueClickOrigin != null) {
            final Point clickOrigin = tissueClickOrigin;
            tissueClickOrigin = null;
            if (clickOrigin.distance(event.getPoint()) <= 2
                    && contains(sourceScreenMapping(), event.getPoint())) {
                final Point2D previewPoint = sourceScreenMapping()
                        .screenToPreview(new Point2D(
                                event.getX(), event.getY()));
                final Point2D atlasPoint = pendingAtlasPoint;
                if (atlasPoint != null
                        && landmarkCaptureListener.capture(
                                atlasPoint, previewPoint)) {
                    pendingAtlasPoint = null;
                }
            }
            repaint();
            return;
        }
        if (dragOrigin == null || gesture == null) {
            return;
        }
        // A click on a marker is a selection, not an edit. Screen positions
        // are integral while landmark coordinates are not necessarily, so
        // converting an unchanged click back to image coordinates could
        // otherwise create a fractional, unintended landmark move.
        if (dragOrigin.distance(event.getPoint()) <= 2
                && gesture.kind == GestureKind.MANUAL_WARP_POINT) {
            final Gesture committed = gesture;
            dragOrigin = null;
            lastDragPoint = null;
            gesture = null;
            repaint();
            commitGesture(committed);
            return;
        }
        if (dragOrigin.distance(event.getPoint()) <= 2) {
            dragOrigin = null;
            lastDragPoint = null;
            gesture = null;
            repaint();
            return;
        }
        updateGesture(event.getPoint(), aspectRatioLocked || event.isShiftDown());
        final Gesture committed = gesture;
        dragOrigin = null;
        lastDragPoint = null;
        gesture = null;
        repaint();
        commitGesture(committed);
    }

    private boolean manualInteractionEnabled() {
        return model != null && model.reviewState().content()
                .workflowMode().permitsManualEdits();
    }

    private void handleMouseWheel(final MouseWheelEvent event) {
        if (model == null) {
            return;
        }
        final Pane pane = paneAt(event.getPoint());
        if (pane == Pane.NONE) {
            return;
        }
        final double factor = Math.pow(1.15,
                -event.getPreciseWheelRotation());
        zoomPane(pane == Pane.TISSUE, factor,
                new Point2D(event.getX(), event.getY()));
        event.consume();
    }

    private void zoomPane(
            final boolean tissue,
            final double requestedFactor,
            final Point2D anchor) {
        if (model == null || !Double.isFinite(requestedFactor)
                || requestedFactor <= 0) {
            return;
        }
        final double oldZoom = tissue ? tissueZoom : atlasZoom;
        final double newZoom = Math.max(MINIMUM_ZOOM,
                Math.min(MAXIMUM_ZOOM, oldZoom * requestedFactor));
        if (newZoom == oldZoom) {
            return;
        }
        final ScreenMapping before = tissue
                ? sourceScreenMapping() : atlasScreenMapping();
        final Point2D previewAnchor = before.screenToPreview(anchor);
        final double fitScale = before.scale() / oldZoom;
        final double nextScale = fitScale * newZoom;
        final double nextOffsetX = anchor.x() + 0.5
                - (previewAnchor.x() + 0.5) * nextScale;
        final double nextOffsetY = anchor.y() + 0.5
                - (previewAnchor.y() + 0.5) * nextScale;
        final ScreenMapping fit = basePaneMapping(
                before.previewWidth(), before.previewHeight(),
                tissue ? 0 : atlasPaneX());
        if (tissue) {
            tissueZoom = newZoom;
            tissuePanX = nextOffsetX - fit.offsetX();
            tissuePanY = nextOffsetY - fit.offsetY();
        } else {
            atlasZoom = newZoom;
            atlasPanX = nextOffsetX - fit.offsetX();
            atlasPanY = nextOffsetY - fit.offsetY();
        }
        repaint();
    }

    private Point2D paneCenter(final boolean tissue) {
        final double x = tissue ? sourcePaneWidth() * 0.5
                : atlasPaneX() + sourcePaneWidth() * 0.5;
        return new Point2D(x,
                PANE_LABEL_HEIGHT
                        + (getHeight() - PANE_LABEL_HEIGHT) * 0.5);
    }

    private Pane paneAt(final Point point) {
        if (point.y < PANE_LABEL_HEIGHT || point.y >= getHeight()) {
            return Pane.NONE;
        }
        if (point.x >= 0 && point.x < sourcePaneWidth()) {
            return Pane.TISSUE;
        }
        if (singleTissuePane) {
            return Pane.NONE;
        }
        if (point.x >= atlasPaneX()
                && point.x < atlasPaneX() + sourcePaneWidth()) {
            return Pane.ATLAS;
        }
        return Pane.NONE;
    }

    private Gesture transformGestureAt(final Point point) {
        if (hasLocalDeformation() || exactBoundaryMapActive()
                || boundaryFitEditingEnabled && model != null
                        && model.boundaryFit().active()) {
            return null;
        }
        Selection selection = selection();
        if (selection == null) {
            return null;
        }
        final ScreenMapping mapping = sourceScreenMapping();
        if (disjoinedTransformEnabled()
                && !selection.screenPolygon(mapping).contains(point)
                && !selectionHandleContains(selection, mapping, point)) {
            final ManualHemisphereWarp2D.AtlasSide other =
                    activeHemisphereSide
                            == ManualHemisphereWarp2D.AtlasSide.LEFT
                            ? ManualHemisphereWarp2D.AtlasSide.RIGHT
                            : ManualHemisphereWarp2D.AtlasSide.LEFT;
            final Selection otherSelection = baseSelection(other);
            if (otherSelection == null
                    || !otherSelection.screenPolygon(mapping)
                            .contains(point)) {
                return null;
            }
            activeHemisphereSide = other;
            interactionListener.manualWarpSideActivated(other);
            requestedRenderKey = renderKey(model, selectedRegionContour);
            scheduleCurrentRender();
            selection = otherSelection;
        }
        for (int index = 0; index < selection.corners.length; index++) {
            final Point2D screen = mapping.previewToScreen(
                    selection.corners[index]);
            if ((placementTool == PlacementTool.ALL || placementTool == PlacementTool.SCALE)
                    && distance(screen, point) <= HANDLE_HIT_RADIUS) {
                final Point2D pivot = selection.corners[(index + 2) % 4];
                final Point2D start = mapping.screenToPreview(
                        new Point2D(point.x, point.y));
                return Gesture.scale(pivot, start,
                        selection.axisRadians, ScaleHandle.CORNER);
            }
        }
        for (int index = 0; index < selection.edgeMidpoints.length; index++) {
            final Point2D screen = mapping.previewToScreen(
                    selection.edgeMidpoints[index]);
            if ((placementTool == PlacementTool.ALL || placementTool == PlacementTool.SCALE)
                    && distance(screen, point) <= HANDLE_HIT_RADIUS) {
                final Point2D pivot = selection.edgeMidpoints[
                        (index + 2) % 4];
                final Point2D start = mapping.screenToPreview(
                        new Point2D(point.x, point.y));
                final ScaleHandle handle = index % 2 == 0
                        ? ScaleHandle.VERTICAL_EDGE
                        : ScaleHandle.HORIZONTAL_EDGE;
                return Gesture.scale(pivot, start,
                        selection.axisRadians, handle);
            }
        }
        final Point2D rotationScreen = mapping.previewToScreen(
                selection.rotationHandle);
        if ((placementTool == PlacementTool.ALL || placementTool == PlacementTool.ROTATE)
                && distance(rotationScreen, point) <= HANDLE_HIT_RADIUS) {
            final Point2D start = mapping.screenToPreview(
                    new Point2D(point.x, point.y));
            return Gesture.rotate(selection.center,
                    angle(selection.center, start));
        }
        if ((placementTool == PlacementTool.ALL || placementTool == PlacementTool.MOVE)
                && (selection.screenPolygon(mapping).contains(point)
                || !disjoinedTransformEnabled()
                && contains(mapping, point))) {
            return Gesture.translate(mapping.screenToPreview(
                    new Point2D(point.x, point.y)));
        }
        return null;
    }

    private static boolean selectionHandleContains(
            final Selection selection,
            final ScreenMapping mapping,
            final Point point) {
        for (final Point2D corner : selection.corners) {
            if (distance(mapping.previewToScreen(corner), point)
                    <= HANDLE_HIT_RADIUS) {
                return true;
            }
        }
        for (final Point2D edge : selection.edgeMidpoints) {
            if (distance(mapping.previewToScreen(edge), point)
                    <= HANDLE_HIT_RADIUS) {
                return true;
            }
        }
        return distance(mapping.previewToScreen(selection.rotationHandle),
                point) <= HANDLE_HIT_RADIUS;
    }

    private Gesture endpointGestureAt(final Point point) {
        final ScreenMapping source = sourceScreenMapping();
        final ScreenMapping atlas = atlasImage == null
                ? null : atlasScreenMapping();
        for (final LandmarkPair landmark : activeGenericLandmarks()) {
            if (atlas != null && paneAt(point) == Pane.ATLAS
                    && distance(atlas.previewToScreen(
                            landmark.atlasPoint()), point)
                    <= HANDLE_HIT_RADIUS) {
                return Gesture.landmarkAtlas(
                        landmark.id(), Pane.ATLAS,
                        landmark.atlasPoint());
            }
            if (paneAt(point) == Pane.TISSUE) {
                if (distance(source.previewToScreen(
                        landmark.previewPoint()), point)
                        <= HANDLE_HIT_RADIUS) {
                    return Gesture.landmarkPreview(
                            landmark.id(), landmark.previewPoint());
                }
                if (distance(source.previewToScreen(
                        model.reviewState().mapAtlasToPreview(
                                landmark.atlasPoint())), point)
                        <= HANDLE_HIT_RADIUS) {
                    return Gesture.landmarkAtlas(
                            landmark.id(), Pane.TISSUE,
                            landmark.atlasPoint());
                }
            }
        }
        return null;
    }

    private Gesture manualWarpGestureAt(final Point point) {
        if (model == null || paneAt(point) != Pane.TISSUE) {
            return null;
        }
        final ScreenMapping source = sourceScreenMapping();
        if (structureEditingEnabled) {
            for (final ManualWarpControl control
                    : selectedPrincipalStructureControls()) {
                if (distance(source.previewToScreen(control.targetPoint()),
                        point) <= MANUAL_WARP_HIT_RADIUS + 4) {
                    return Gesture.manualWarp(
                            control.id(), control.targetPoint());
                }
            }
            return null;
        }
        if (model.reviewState().content().hemisphereWarp().isEmpty()) {
            return null;
        }
        final ManualHemisphereWarp2D warp = model.reviewState().content()
                .hemisphereWarp().orElseThrow();
        for (final ManualHemisphereWarp2D.AtlasSide side
                : manualWarpHitOrder()) {
            for (final ManualWarpControl control : warp.manualControls(side)) {
                if (structureEditingEnabled
                        && !isSelectedPrincipalStructureControl(control)) {
                    continue;
                }
                if (distance(source.previewToScreen(control.targetPoint()), point)
                        <= MANUAL_WARP_HIT_RADIUS) {
                    if (side != activeHemisphereSide) {
                        activeHemisphereSide = side;
                        interactionListener.manualWarpSideActivated(side);
                    }
                    return Gesture.manualWarp(
                            control.id(), control.targetPoint());
                }
            }
        }
        return null;
    }

    private boolean structureResizeHandleAt(final Point point) {
        final StructureGapGeometry gap = structureGapGeometry();
        if (gap != null && distance(sourceScreenMapping().previewToScreen(
                gap.midpoint()), point) <= HANDLE_HIT_RADIUS + 2) {
            return true;
        }
        final Selection selection = structureSelection();
        if (selection == null) {
            return false;
        }
        final ScreenMapping mapping = sourceScreenMapping();
        for (final Point2D corner : selection.corners) {
            if (distance(mapping.previewToScreen(corner), point)
                    <= HANDLE_HIT_RADIUS) {
                return true;
            }
        }
        for (final Point2D edge : selection.edgeMidpoints) {
            if (distance(mapping.previewToScreen(edge), point)
                    <= HANDLE_HIT_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private Gesture structureWarpGestureAt(final Point point) {
        // Dot-first refinement intentionally reserves group scaling and blade
        // separation for the explicit sliders in the inspector.
        return null;
    }

    private List<ManualWarpControl> selectedStructureControls() {
        if (model == null || model.selectedAtlasRegion().isEmpty()) {
            return List.of();
        }
        final String acronym = model.selectedAtlasRegion()
                .orElseThrow().acronym();
        final Optional<StructureAdjustmentDraft> draft = model
                .structureAdjustment().draft().filter(value ->
                        value.atlasSide() == activeHemisphereSide
                                && value.structureAcronym().equals(acronym));
        if (draft.isPresent()) {
            return draft.orElseThrow().requestedControls();
        }
        return model.reviewState().content().hemisphereWarp()
                .map(warp -> warp.manualControls(activeHemisphereSide)
                        .stream().filter(control -> (control.origin()
                                == ManualWarpControlOrigin.STRUCTURE_GUIDE
                                || control.origin()
                                == ManualWarpControlOrigin
                                        .VERIFIED_STRUCTURE_BOUNDARY)
                                && control.structureAcronym()
                                        .equalsIgnoreCase(acronym))
                        .toList())
                .orElse(List.of());
    }

    private List<StructureComponentPath> activeStructureComponents() {
        if (renderPaths == null) {
            return List.of();
        }
        return renderPaths.structureComponentPaths().stream()
                .filter(component -> component.side()
                        == activeHemisphereSide)
                .toList();
    }

    private List<StructureComponentPath> principalStructureComponents() {
        return activeStructureComponents().stream()
                .filter(StructureComponentPath::principal)
                .sorted(java.util.Comparator.comparingDouble(
                        StructureComponentPath::perimeter).reversed())
                .limit(2).toList();
    }

    private List<ManualWarpControl> selectedPrincipalStructureControls() {
        final List<ManualWarpControl> controls = selectedStructureControls();
        final StructureAdjustmentDraft draft = model == null ? null
                : model.structureAdjustment().draft().orElse(null);
        if (draft != null && draft.atlasSide() == activeHemisphereSide) {
            return controls.stream().filter(control -> draft
                    .componentByControlId().getOrDefault(
                            control.id(), -1) < 2).toList();
        }
        final List<StructureComponentPath> components =
                activeStructureComponents();
        if (components.isEmpty()) {
            return controls;
        }
        final List<ManualWarpControl> principal = controls.stream()
                .filter(control -> {
                    final StructureComponentPath assigned =
                            nearestStructureComponent(
                                    control.sourcePoint(), components);
                    return assigned != null && assigned.principal();
                }).toList();
        // Legacy guide groups were not component-seeded. Until the reviewer
        // replaces such a group, preserve its existing box/point editing when
        // component assignment cannot identify a usable four-point subset.
        return principal.size() >= 4 ? principal : controls;
    }

    private boolean isSelectedPrincipalStructureControl(
            final ManualWarpControl control) {
        if (!isSelectedStructureControl(control)) {
            return false;
        }
        return selectedPrincipalStructureControls().stream().anyMatch(
                selected -> selected.id().equals(control.id()));
    }

    private static StructureComponentPath nearestStructureComponent(
            final Point2D sourcePoint,
            final List<StructureComponentPath> components) {
        StructureComponentPath nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (final StructureComponentPath component : components) {
            final double candidate = pointToClosedLoopDistance(
                    sourcePoint, component.sourceLoop());
            if (candidate < best) {
                best = candidate;
                nearest = component;
            }
        }
        return nearest;
    }

    private static double pointToClosedLoopDistance(
            final Point2D point,
            final List<Point2D> loop) {
        double best = Double.POSITIVE_INFINITY;
        for (int index = 0; index < loop.size(); index++) {
            best = Math.min(best, pointToSegmentDistance(point,
                    loop.get(index), loop.get((index + 1) % loop.size())));
        }
        return best;
    }

    private StructureGapGeometry structureGapGeometry() {
        final List<StructureComponentPath> components =
                principalStructureComponents();
        if (components.size() != 2) {
            return null;
        }
        final StructureComponentPath first = components.get(0);
        final StructureComponentPath second = components.get(1);
        final double dx = second.tissueCentroid().x()
                - first.tissueCentroid().x();
        final double dy = second.tissueCentroid().y()
                - first.tissueCentroid().y();
        final double length = Math.hypot(dx, dy);
        if (!Double.isFinite(length) || length < 1e-6) {
            return null;
        }
        final List<String> ids = new ArrayList<>();
        final List<Point2D> targets = new ArrayList<>();
        final List<Integer> signs = new ArrayList<>();
        int firstCount = 0;
        int secondCount = 0;
        for (final ManualWarpControl control
                : selectedPrincipalStructureControls()) {
            final StructureComponentPath assigned = nearestStructureComponent(
                    control.sourcePoint(), components);
            if (assigned == null) {
                continue;
            }
            final int sign = assigned.id().equals(first.id()) ? -1 : 1;
            firstCount += sign < 0 ? 1 : 0;
            secondCount += sign > 0 ? 1 : 0;
            ids.add(control.id());
            targets.add(control.targetPoint());
            signs.add(sign);
        }
        if (firstCount < 2 || secondCount < 2) {
            return null;
        }
        return new StructureGapGeometry(first, second,
                new Point2D(dx / length, dy / length),
                midpoint(first.tissueCentroid(), second.tissueCentroid()),
                ids, targets, signs,
                Map.of(first.id(), -1, second.id(), 1));
    }

    private boolean isSelectedStructureControl(
            final ManualWarpControl control) {
        return model != null && model.selectedAtlasRegion().isPresent()
                && control.atlasSide() == activeHemisphereSide
                && (control.origin()
                        == ManualWarpControlOrigin.STRUCTURE_GUIDE
                        || control.origin()
                        == ManualWarpControlOrigin
                                .VERIFIED_STRUCTURE_BOUNDARY)
                && control.structureAcronym().equalsIgnoreCase(model
                        .selectedAtlasRegion().orElseThrow().acronym());
    }

    private Gesture boundaryFitGestureAt(final Point point) {
        if (model == null) {
            return null;
        }
        final BoundaryFitDraft draft = model.boundaryFit().draft()
                .orElse(null);
        if (draft == null) {
            return null;
        }
        final Pane pane = paneAt(point);
        if (pane == Pane.ATLAS && !singleTissuePane) {
            final ScreenMapping atlas = atlasScreenMapping();
            Gesture nearest = null;
            double best = 10;
            for (final BoundaryFitAnchor anchor : draft.anchors()) {
                final double candidate = distance(atlas.previewToScreen(
                        anchor.atlasPlanePoint()), point);
                if (candidate <= best) {
                    best = candidate;
                    nearest = Gesture.boundaryFitAtlas(
                            anchor.id(), Pane.ATLAS,
                            anchor.atlasPlanePoint());
                }
            }
            return nearest;
        }
        if (pane != Pane.TISSUE) {
            return null;
        }
        final ScreenMapping mapping = sourceScreenMapping();
        Gesture nearest = null;
        double best = 10;
        for (final BoundaryFitAnchor anchor : draft.anchors()) {
            if (singleTissuePane) {
                final double atlasDistance = distance(mapping.previewToScreen(
                        anchor.atlasPreviewPoint()), point);
                if (atlasDistance <= best) {
                    best = atlasDistance;
                    nearest = Gesture.boundaryFitAtlas(
                            anchor.id(), Pane.TISSUE,
                            anchor.atlasPreviewPoint());
                }
            }
            if (anchor.tissuePreviewPoint().isPresent()) {
                final Point2D tissue = anchor.tissuePreviewPoint()
                        .orElseThrow();
                final double tissueDistance = distance(
                        mapping.previewToScreen(tissue), point);
                if (tissueDistance <= best) {
                    best = tissueDistance;
                    nearest = Gesture.boundaryFitTissue(
                            anchor.id(), tissue);
                }
            }
        }
        return nearest;
    }

    private Gesture boundaryWarpGestureAt(final Point point) {
        if (model == null || paneAt(point) != Pane.TISSUE) {
            return null;
        }
        final ScreenMapping mapping = sourceScreenMapping();
        Gesture nearest = null;
        double best = 10;
        for (final BoundaryWarpRequest request
                : model.boundaryWarp().drafts()) {
            for (final BoundaryFitMatch match : request.matches()) {
                final double atlasDistance = distance(
                        mapping.previewToScreen(
                                match.atlasPreviewPoint()), point);
                if (atlasDistance <= best) {
                    best = atlasDistance;
                    nearest = Gesture.boundaryWarpAtlas(
                            match.id(), match.atlasPreviewPoint());
                }
                final double tissueDistance = distance(
                        mapping.previewToScreen(
                                match.tissuePreviewPoint()), point);
                if (tissueDistance <= best) {
                    best = tissueDistance;
                    nearest = Gesture.boundaryWarpTissue(
                            match.id(), match.tissuePreviewPoint());
                }
            }
        }
        return nearest;
    }

    private Optional<ManualHemisphereWarp2D.AtlasSide>
            boundaryWarpSideForMatch(final String identifier) {
        if (model == null) {
            return Optional.empty();
        }
        return model.boundaryWarp().drafts().stream()
                .filter(request -> request.matches().stream().anyMatch(
                        match -> match.id().equals(identifier)))
                .map(BoundaryWarpRequest::targetSide)
                .findFirst();
    }

    private Point2D nearestBoundarySample(
            final List<BoundaryFitSample> samples,
            final Point2D requested,
            final double screenTolerance) {
        Point2D nearest = null;
        double best = screenTolerance / sourceScreenMapping().scale();
        for (final BoundaryFitSample sample : samples) {
            final double candidate = distance(sample.point(), requested);
            if (candidate <= best) {
                best = candidate;
                nearest = sample.point();
            }
        }
        return nearest;
    }

    private Point2D snapOrFreeBoundaryPoint(
            final List<BoundaryFitSample> samples,
            final Point2D requested,
            final double screenTolerance) {
        final Point2D snapped = nearestBoundarySample(
                samples, requested, screenTolerance);
        return snapped == null ? requested : snapped;
    }

    private List<ManualHemisphereWarp2D.AtlasSide> manualWarpHitOrder() {
        final ManualHemisphereWarp2D.AtlasSide other =
                activeHemisphereSide == ManualHemisphereWarp2D.AtlasSide.LEFT
                        ? ManualHemisphereWarp2D.AtlasSide.RIGHT
                        : ManualHemisphereWarp2D.AtlasSide.LEFT;
        return List.of(activeHemisphereSide, other).stream()
                .filter(side -> displayIncludesSide(model, side)).toList();
    }

    private void updateGesture(
            final Point point,
            final boolean preserveAspectRatio) {
        if (gesture == null || gesture.kind == GestureKind.PAN) {
            return;
        }
        final Pane pointerPane = paneAt(point);
        if (gesture.kind == GestureKind.BOUNDARY_FIT_ATLAS
                && gesture.pane == Pane.ATLAS
                && pointerPane == Pane.TISSUE) {
            gesture.releasePane = Pane.TISSUE;
            gesture.point = sourceScreenMapping().screenToPreview(
                    new Point2D(point.x, point.y));
            return;
        }
        gesture.releasePane = gesture.pane;
        final ScreenMapping mapping = gesture.pane == Pane.ATLAS
                ? atlasScreenMapping() : sourceScreenMapping();
        final Point2D current = mapping.screenToPreview(
                new Point2D(point.x, point.y));
        switch (gesture.kind) {
            case TRANSLATE -> {
                final Point2D candidate = new Point2D(
                        current.x() - gesture.start.x(),
                        current.y() - gesture.start.y());
                if (interactionListener.canPreviewTranslate(
                        candidate.x(), candidate.y())) {
                    gesture.delta = candidate;
                }
            }
            case STRUCTURE_TRANSLATE -> gesture.delta = new Point2D(
                    current.x() - gesture.start.x(),
                    current.y() - gesture.start.y());
            case STRUCTURE_GAP -> gesture.value =
                    (current.x() - gesture.start.x())
                            * gesture.structureGapAxis.x()
                            + (current.y() - gesture.start.y())
                            * gesture.structureGapAxis.y();
            case SCALE -> {
                final double cosine = Math.cos(gesture.axisRadians);
                final double sine = Math.sin(gesture.axisRadians);
                final double dx = current.x() - gesture.pivot.x();
                final double dy = current.y() - gesture.pivot.y();
                final double currentX = cosine * dx + sine * dy;
                final double currentY = -sine * dx + cosine * dy;
                double scaleX;
                double scaleY;
                if (gesture.scaleHandle == ScaleHandle.CORNER) {
                    if (preserveAspectRatio) {
                        final double denominator = gesture.startAxisX
                                * gesture.startAxisX
                                + gesture.startAxisY * gesture.startAxisY;
                        final double factor = denominator <= 1e-18
                                ? Double.NaN
                                : (currentX * gesture.startAxisX
                                + currentY * gesture.startAxisY)
                                / denominator;
                        scaleX = factor;
                        scaleY = factor;
                    } else {
                        scaleX = currentX / gesture.startAxisX;
                        scaleY = currentY / gesture.startAxisY;
                    }
                } else if (gesture.scaleHandle
                        == ScaleHandle.HORIZONTAL_EDGE) {
                    final double factor = currentX / gesture.startAxisX;
                    scaleX = factor;
                    scaleY = preserveAspectRatio ? factor : 1;
                } else {
                    final double factor = currentY / gesture.startAxisY;
                    scaleX = preserveAspectRatio ? factor : 1;
                    scaleY = factor;
                }
                if (Double.isFinite(scaleX) && Double.isFinite(scaleY)
                        && scaleX > 0 && scaleY > 0
                        && interactionListener.canPreviewScaleAxes(
                                scaleX, scaleY, gesture.axisRadians,
                                gesture.pivot)) {
                    gesture.scaleX = scaleX;
                    gesture.scaleY = scaleY;
                }
            }
            case STRUCTURE_SCALE -> {
                final double currentX = current.x() - gesture.pivot.x();
                final double currentY = current.y() - gesture.pivot.y();
                double scaleX = 1;
                double scaleY = 1;
                if (gesture.scaleHandle == ScaleHandle.CORNER) {
                    if (preserveAspectRatio) {
                        final double denominator = gesture.startAxisX
                                * gesture.startAxisX
                                + gesture.startAxisY * gesture.startAxisY;
                        final double factor = denominator <= 1e-18
                                ? Double.NaN
                                : (currentX * gesture.startAxisX
                                + currentY * gesture.startAxisY)
                                / denominator;
                        scaleX = factor;
                        scaleY = factor;
                    } else {
                        scaleX = currentX / gesture.startAxisX;
                        scaleY = currentY / gesture.startAxisY;
                    }
                } else if (gesture.scaleHandle
                        == ScaleHandle.HORIZONTAL_EDGE) {
                    scaleX = currentX / gesture.startAxisX;
                } else {
                    scaleY = currentY / gesture.startAxisY;
                }
                if (Double.isFinite(scaleX) && Double.isFinite(scaleY)
                        && scaleX > 0 && scaleY > 0) {
                    gesture.scaleX = scaleX;
                    gesture.scaleY = scaleY;
                }
            }
            case ROTATE -> {
                final double candidate = normalizedAngle(
                        angle(gesture.pivot, current)
                                - gesture.startValue);
                if (interactionListener.canPreviewRotate(
                        candidate, gesture.pivot)) {
                    gesture.value = candidate;
                }
            }
            case LANDMARK_ATLAS -> {
                final Point2D requested = gesture.pane == Pane.ATLAS
                        ? current
                        : model.reviewState().mapPreviewToAtlas(current);
                final Point2D snapped = snapToSelectedRegionBoundary(
                        requested);
                gesture.point = snapped == null
                        ? gesture.originalPoint : snapped;
            }
            case LANDMARK_PREVIEW -> gesture.point = current;
            case MANUAL_WARP -> gesture.point = current;
            case MANUAL_WARP_POINT -> gesture.point = current;
            case TISSUE_SUPPORT -> gesture.point = current;
            case BOUNDARY_FIT_ATLAS, BOUNDARY_FIT_TISSUE,
                    BOUNDARY_WARP_ATLAS, BOUNDARY_WARP_TISSUE ->
                    gesture.point = current;
            default -> { }
        }
    }

    private void commitGesture(final Gesture committed) {
        switch (committed.kind) {
            case TRANSLATE -> {
                if (nonzero(committed.delta)) {
                    interactionListener.translate(
                            committed.delta.x(), committed.delta.y());
                }
            }
            case SCALE -> {
                if (Math.abs(committed.scaleX - 1) > 1e-12
                        || Math.abs(committed.scaleY - 1) > 1e-12) {
                    interactionListener.scaleAxes(
                            committed.scaleX, committed.scaleY,
                            committed.axisRadians, committed.pivot);
                }
            }
            case ROTATE -> {
                if (Math.abs(committed.value) > 1e-12) {
                    interactionListener.rotateRadians(
                            committed.value, committed.pivot);
                }
            }
            case LANDMARK_ATLAS -> {
                if (!committed.point.equals(committed.originalPoint)) {
                    interactionListener.moveLandmarkAtlasPoint(
                            committed.landmarkId, committed.point);
                }
            }
            case LANDMARK_PREVIEW -> {
                if (!committed.point.equals(committed.originalPoint)) {
                    interactionListener.moveLandmarkPreviewPoint(
                            committed.landmarkId, committed.point);
                }
            }
            case MANUAL_WARP -> {
                if (!committed.point.equals(committed.originalPoint)) {
                    if (structureEditingEnabled) {
                        interactionListener.transformStructureControls(
                                List.of(committed.landmarkId),
                                List.of(committed.point));
                    } else {
                        interactionListener.moveManualWarpControlAndRefit(
                                committed.landmarkId, committed.point);
                    }
                }
            }
            case STRUCTURE_TRANSLATE, STRUCTURE_SCALE, STRUCTURE_GAP -> {
                final List<Point2D> transformed = committed
                        .transformedStructureTargets();
                if (structureTargetsChanged(transformed,
                        committed.structureOriginalTargets)) {
                    interactionListener.transformStructureControls(
                            committed.structureControlIds, transformed);
                }
            }
            case MANUAL_WARP_POINT -> interactionListener
                    .manualWarpPointSelected(committed.point);
            case TISSUE_SUPPORT -> {
                if (!committed.point.equals(committed.originalPoint)) {
                    interactionListener.moveTissueSupportControl(
                            committed.landmarkId, committed.point);
                }
            }
            case BOUNDARY_FIT_ATLAS -> {
                commitBoundaryFitAtlasDrag(committed);
            }
            case BOUNDARY_FIT_TISSUE -> {
                if (!committed.point.equals(committed.originalPoint)) {
                    final BoundaryFitDraft draft = model == null ? null
                            : model.boundaryFit().draft().orElse(null);
                    final Point2D tissue = draft == null
                            ? committed.point : snapOrFreeBoundaryPoint(
                                    draft.request().tissueBoundary(),
                                    committed.point, 16);
                    interactionListener.moveBoundaryFitMatch(
                            committed.landmarkId,
                            ReviewController.BoundaryFitEndpoint.TISSUE,
                            tissue);
                }
            }
            case BOUNDARY_WARP_ATLAS -> {
                final BoundaryWarpRequest request = model == null ? null
                        : model.boundaryWarp().drafts().stream()
                                .filter(draft -> draft.matches().stream()
                                        .anyMatch(match -> match.id().equals(
                                                committed.landmarkId)))
                                .findFirst().orElse(null);
                final BoundaryFitMatch match = request == null ? null
                        : request.matches().stream().filter(candidate ->
                                candidate.id().equals(
                                        committed.landmarkId))
                                .findFirst().orElse(null);
                final Point2D tissue = request == null ? null
                        : snapOrFreeBoundaryPoint(request.tissueBoundary(),
                                committed.point, 18);
                if (match != null && !match.included()
                        && tissue != null) {
                    interactionListener.completeBoundaryWarpMatch(
                            committed.landmarkId, tissue);
                } else if (!committed.point.equals(
                        committed.originalPoint)) {
                    interactionListener.moveBoundaryWarpMatch(
                            committed.landmarkId,
                            ReviewController.BoundaryFitEndpoint.ATLAS,
                            committed.point);
                }
            }
            case BOUNDARY_WARP_TISSUE -> {
                if (!committed.point.equals(committed.originalPoint)) {
                    final BoundaryWarpRequest request = model == null ? null
                            : model.boundaryWarp().drafts().stream()
                                    .filter(draft -> draft.matches().stream()
                                            .anyMatch(match -> match.id()
                                                    .equals(committed
                                                            .landmarkId)))
                                    .findFirst().orElse(null);
                    final Point2D tissue = request == null
                            ? committed.point : snapOrFreeBoundaryPoint(
                                    request.tissueBoundary(),
                                    committed.point, 16);
                    interactionListener.moveBoundaryWarpMatch(
                            committed.landmarkId,
                            ReviewController.BoundaryFitEndpoint.TISSUE,
                            tissue);
                }
            }
            default -> { }
        }
    }

    private static boolean structureTargetsChanged(
            final List<Point2D> requested,
            final List<Point2D> installed) {
        if (requested.size() != installed.size()) {
            return true;
        }
        for (int index = 0; index < requested.size(); index++) {
            if (distance(requested.get(index), installed.get(index))
                    > 1e-9) {
                return true;
            }
        }
        return false;
    }

    /**
     * An unmatched numbered atlas anchor may be dragged directly onto the
     * cyan tissue contour. Once matched, dragging its amber endpoint keeps
     * the established behavior of sliding that endpoint along the atlas
     * exterior.
     */
    private void commitBoundaryFitAtlasDrag(final Gesture committed) {
        if (model == null) {
            return;
        }
        final BoundaryFitDraft draft = model.boundaryFit().draft()
                .orElse(null);
        if (draft == null) {
            return;
        }
        final BoundaryFitAnchor anchor = draft.anchors().stream()
                .filter(value -> value.id().equals(committed.landmarkId))
                .findFirst().orElse(null);
        if (anchor == null) {
            return;
        }
        final Point2D requestedPreview = committed.pane == Pane.ATLAS
                && committed.releasePane != Pane.TISSUE
                ? BoundaryFitRequestFactory.mappedAtlasPreviewPoint(
                        model.reviewState(), draft.request().targetSide(),
                        committed.point)
                : committed.point;
        if (anchor.tissuePreviewPoint().isEmpty()
                && (committed.pane != Pane.ATLAS
                        || committed.releasePane == Pane.TISSUE)) {
            final Point2D tissue = snapOrFreeBoundaryPoint(
                    draft.request().tissueBoundary(), requestedPreview, 18);
            interactionListener.setBoundaryFitTissuePoint(
                    committed.landmarkId, tissue);
            return;
        }
        if (!committed.point.equals(committed.originalPoint)) {
            interactionListener.moveBoundaryFitMatch(
                    committed.landmarkId,
                    ReviewController.BoundaryFitEndpoint.ATLAS,
                    requestedPreview);
        }
    }

    private void cancelInteraction() {
        manualRoiLayer.cancelGesture();
        gesture = null;
        dragOrigin = null;
        lastDragPoint = null;
        tissueClickOrigin = null;
        manualWarpPointArmed = false;
        pendingBoundaryAtlasPoint = null;
        repaint();
    }

    /** Discards only the in-progress mouse gesture; installed state is kept. */
    public void cancelTransientGesture() {
        cancelInteraction();
    }

    /** Keeps a panned image reachable without allowing a blank-only pane. */
    private void clampPan(final Pane pane) {
        if (pane == Pane.NONE || model == null) {
            return;
        }
        final boolean tissue = pane == Pane.TISSUE;
        final ScreenMapping mapping = tissue
                ? sourceScreenMapping() : atlasScreenMapping();
        final ScreenMapping fit = basePaneMapping(
                mapping.previewWidth(), mapping.previewHeight(),
                tissue ? 0 : atlasPaneX());
        final double paneLeft = tissue ? 0 : atlasPaneX();
        final double paneTop = PANE_LABEL_HEIGHT;
        final double paneWidth = sourcePaneWidth();
        final double paneHeight = Math.max(1,
                getHeight() - PANE_LABEL_HEIGHT);
        final double width = mapping.previewWidth() * mapping.scale();
        final double height = mapping.previewHeight() * mapping.scale();
        final double offsetX = width <= paneWidth
                ? fit.offsetX() : Math.max(paneLeft + paneWidth - width,
                        Math.min(paneLeft, mapping.offsetX()));
        final double offsetY = height <= paneHeight
                ? fit.offsetY() : Math.max(paneTop + paneHeight - height,
                        Math.min(paneTop, mapping.offsetY()));
        if (tissue) {
            tissuePanX = offsetX - fit.offsetX();
            tissuePanY = offsetY - fit.offsetY();
        } else {
            atlasPanX = offsetX - fit.offsetX();
            atlasPanY = offsetY - fit.offsetY();
        }
    }

    private AffineTransform livePreviewTransform() {
        final AffineTransform transform = new AffineTransform();
        if (gesture == null) {
            return transform;
        }
        switch (gesture.kind) {
            case TRANSLATE -> transform.translate(
                    gesture.delta.x(), gesture.delta.y());
            case SCALE -> {
                transform.translate(gesture.pivot.x(), gesture.pivot.y());
                transform.rotate(gesture.axisRadians);
                transform.scale(gesture.scaleX, gesture.scaleY);
                transform.rotate(-gesture.axisRadians);
                transform.translate(-gesture.pivot.x(), -gesture.pivot.y());
            }
            case ROTATE -> transform.rotate(
                    gesture.value, gesture.pivot.x(), gesture.pivot.y());
            default -> { }
        }
        return transform;
    }

    private Point2D livePreviewPoint(final Point2D point) {
        if (gesture != null && gesture.kind == GestureKind.MANUAL_WARP
                && model != null
                && model.reviewState().content().hemisphereWarp()
                        .isPresent()) {
            final ManualHemisphereWarp2D warp = model.reviewState().content()
                    .hemisphereWarp().orElseThrow();
            final boolean imageLeft = warp.imageSide(activeHemisphereSide)
                    == ManualHemisphereWarp2D.ImageSide.IMAGE_LEFT;
            final double signedSeamDistance = warp.imageMidline()
                    .signedDistance(point);
            if ((imageLeft && signedSeamDistance > 0)
                    || (!imageLeft && signedSeamDistance < 0)) {
                final double diagonal = Math.hypot(
                        model.preview().width(), model.preview().height());
                final double radius = diagonal * 0.16;
                final double normalized = distance(point,
                        gesture.originalPoint) / radius;
                if (normalized < 1) {
                    final double remaining = 1 - Math.max(0, normalized);
                    final double weight = remaining * remaining * remaining
                            * remaining * (4 * normalized + 1);
                    final double seamTaper = Math.min(1,
                            Math.abs(signedSeamDistance)
                                    / Math.max(2, diagonal * 0.025));
                    return new Point2D(
                            point.x() + weight * seamTaper
                                    * (gesture.point.x()
                                    - gesture.originalPoint.x()),
                            point.y() + weight * seamTaper
                                    * (gesture.point.y()
                                    - gesture.originalPoint.y()));
                }
            }
        }
        final java.awt.geom.Point2D.Double output =
                new java.awt.geom.Point2D.Double();
        if (isTransformGesture() && disjoinedTransformEnabled()
                && !insideSelection(baseSelection(), point)) {
            return point;
        }
        livePreviewTransform().transform(
                new java.awt.geom.Point2D.Double(point.x(), point.y()),
                output);
        return new Point2D(output.x, output.y);
    }

    private boolean isTransformGesture() {
        return gesture != null && switch (gesture.kind) {
            case TRANSLATE, SCALE, ROTATE -> true;
            default -> false;
        };
    }

    private static boolean insideSelection(
            final Selection selection,
            final Point2D point) {
        if (selection == null) {
            return false;
        }
        final Path2D.Double path = new Path2D.Double();
        path.moveTo(selection.corners[0].x(), selection.corners[0].y());
        for (int index = 1; index < selection.corners.length; index++) {
            path.lineTo(selection.corners[index].x(),
                    selection.corners[index].y());
        }
        path.closePath();
        return path.contains(point.x(), point.y())
                || path.intersects(point.x() - 0.5, point.y() - 0.5, 1, 1);
    }

    private void drawSourceLandmarks(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        canvas.setStroke(new BasicStroke(1.5f));
        for (final LandmarkPair landmark : activeGenericLandmarks()) {
            final Point2D atlasPoint = liveLandmarkAtlasPoint(landmark);
            final Point2D atlasPreview;
            try {
                atlasPreview = livePreviewPoint(
                        model.reviewState().mapAtlasToPreview(atlasPoint));
            } catch (final RuntimeException outsideReviewedDomain) {
                // An old generic pair may predate the exact reviewed outline.
                // Keep repaint fail-closed rather than allowing one stale
                // evidence point to tear down the Swing EDT.
                continue;
            }
            final Point2D atlasScreen = mapping.previewToScreen(
                    atlasPreview);
            final Point2D previewScreen = mapping.previewToScreen(
                    liveLandmarkPreviewPoint(landmark));
            final boolean check = landmark.role() == LandmarkRole.CHECK;
            canvas.setColor(check
                    ? new Color(210, 120, 255)
                    : new Color(255, 220, 20));
            canvas.drawLine(
                    rounded(atlasScreen.x()), rounded(atlasScreen.y()),
                    rounded(previewScreen.x()), rounded(previewScreen.y()));
            marker(canvas, atlasScreen, check
                    ? new Color(190, 90, 255)
                    : new Color(0, 255, 255));
            marker(canvas, previewScreen, check
                    ? new Color(255, 235, 80)
                    : new Color(255, 70, 70));
            if (landmarkLabelsVisible) {
                canvas.setColor(Color.WHITE);
                canvas.drawString(
                        landmark.id() + " [" + landmark.role() + "]",
                        rounded(previewScreen.x()) + 7,
                        rounded(previewScreen.y()) - 7);
            }
        }
    }

    private void drawAtlasLandmarks(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        for (final LandmarkPair landmark : activeGenericLandmarks()) {
            final Point2D point = mapping.previewToScreen(
                    liveLandmarkAtlasPoint(landmark));
            marker(canvas, point,
                    landmark.role() == LandmarkRole.CHECK
                            ? new Color(190, 90, 255)
                            : new Color(0, 255, 255));
            if (landmarkLabelsVisible) {
                canvas.setColor(Color.WHITE);
                canvas.drawString(
                        landmark.id() + " [" + landmark.role() + "]",
                        rounded(point.x()) + 7, rounded(point.y()) - 7);
            }
        }
    }

    private Point2D liveLandmarkAtlasPoint(final LandmarkPair landmark) {
        return gesture != null
                && gesture.kind == GestureKind.LANDMARK_ATLAS
                && landmark.id().equals(gesture.landmarkId)
                ? gesture.point : landmark.atlasPoint();
    }

    private Point2D liveLandmarkPreviewPoint(final LandmarkPair landmark) {
        return gesture != null
                && gesture.kind == GestureKind.LANDMARK_PREVIEW
                && landmark.id().equals(gesture.landmarkId)
                ? gesture.point : landmark.previewPoint();
    }

    /** Manual warp controls are geometry, never generic landmark evidence. */
    private List<LandmarkPair> activeGenericLandmarks() {
        return model.reviewState().content().activeLandmarks().stream()
                .filter(landmark -> landmark.anatomicalHandleMetadata()
                        .isEmpty())
                .toList();
    }

    private void drawManualWarpControlsOnTissue(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        if (!manualWarpEditingEnabled || model == null || interactionTool != InteractionTool.POINTS) {
            return;
        }
        if (structureEditingEnabled
                && model.structureAdjustment().draft().isPresent()) {
            drawStructureDraftControls(canvas, mapping);
            return;
        }
        if (model.reviewState().content().hemisphereWarp().isEmpty()) {
            return;
        }
        final ManualHemisphereWarp2D warp = model.reviewState().content()
                .hemisphereWarp().orElseThrow();
        for (final ManualHemisphereWarp2D.AtlasSide side
                : manualWarpDrawOrder()) {
            final boolean active = side == activeHemisphereSide;
            final Color originColor = active
                    ? new Color(255, 160, 60, 220)
                    : new Color(255, 160, 60, 105);
            final Color targetColor = active
                    ? selectedRegionColor
                    : withAlpha(selectedRegionColor, 115);
            for (final ManualWarpControl control : warp.manualControls(side)) {
                if (structureEditingEnabled
                        && !isSelectedStructureControl(control)) {
                    continue;
                }
                final boolean selectedStructure = structureEditingEnabled
                        && isSelectedPrincipalStructureControl(control);
                final boolean fixedStructureFragment = structureEditingEnabled
                        && isSelectedStructureControl(control)
                        && !selectedStructure;
                final Point2D source = mapping.previewToScreen(
                        control.sourcePoint());
                final Point2D target = mapping.previewToScreen(
                        liveManualWarpPoint(control));
                if (displacementLinesVisible && distance(source, target) > 0.5) {
                    final java.awt.Stroke previous = canvas.getStroke();
                    canvas.setStroke(new BasicStroke(1.0f,
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    canvas.setColor(fixedStructureFragment
                            ? new Color(255, 170, 55, 70) : active
                            ? new Color(255, 170, 55, 220)
                            : new Color(255, 170, 55, 100));
                    canvas.drawLine(rounded(source.x()), rounded(source.y()),
                            rounded(target.x()), rounded(target.y()));
                    canvas.setStroke(previous);
                }
                drawRing(canvas, source, selectedStructure
                                ? new Color(255, 160, 60, 145)
                                : fixedStructureFragment
                                ? new Color(255, 160, 60, 65)
                                : originColor,
                        MANUAL_WARP_ORIGIN_DIAMETER);
                drawRing(canvas, target, selectedStructure
                                ? REVIEWED_BOUNDARY_COLOR
                                : fixedStructureFragment
                                ? new Color(90, 220, 255, 70)
                                : targetColor,
                        MANUAL_WARP_TARGET_DIAMETER);
            }
        }
    }

    private void drawStructureDraftControls(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        final StructureAdjustmentDraft draft = model.structureAdjustment()
                .draft().orElseThrow();
        if (draft.atlasSide() != activeHemisphereSide) {
            return;
        }
        final Map<String, ManualWarpControl> validById = model
                .structureAdjustment().candidate()
                .map(candidate -> candidate.validControls().stream().collect(
                        java.util.stream.Collectors.toMap(
                                ManualWarpControl::id,
                                Function.identity())))
                .orElse(Map.of());
        final Map<String, ManualWarpControl> startsById =
                draft.baselineControls().stream().collect(
                        java.util.stream.Collectors.toMap(
                                ManualWarpControl::id,
                                Function.identity()));
        final java.util.Set<String> highlighted = model
                .structureAdjustment().highlightedUnitId()
                .map(draft::unit)
                .map(unit -> java.util.Set.copyOf(unit.controlIds()))
                .orElse(java.util.Set.of());
        final int requestedHandleSize = draft.requestedControls().size()
                >= 48 ? 9 : draft.requestedControls().size() >= 32 ? 11 : 15;
        for (final ManualWarpControl requested : draft.requestedControls()) {
            final ManualWarpControl start = startsById.get(requested.id());
            final ManualWarpControl valid = validById.get(requested.id());
            final Point2D startTarget = mapping.previewToScreen(
                    start.targetPoint());
            final Point2D requestedTarget = mapping.previewToScreen(
                    liveManualWarpPoint(requested));
            final java.awt.Stroke previous = canvas.getStroke();
            canvas.setStroke(new BasicStroke(1.0f));
            canvas.setColor(new Color(205, 205, 205, 155));
            canvas.drawLine(rounded(startTarget.x()),
                    rounded(startTarget.y()), rounded(requestedTarget.x()),
                    rounded(requestedTarget.y()));
            canvas.setStroke(previous);
            drawRing(canvas, startTarget, new Color(255, 160, 60, 205),
                    STRUCTURE_ORIGIN_DIAMETER);
            drawStructureVertexHandle(canvas, requestedTarget,
                    requestedHandleSize);
            if (valid != null) {
                final Point2D validTarget = mapping.previewToScreen(
                        valid.targetPoint());
                canvas.setColor(new Color(65, 220, 255, 245));
                canvas.fillOval(rounded(validTarget.x()) - 3,
                        rounded(validTarget.y()) - 3, 7, 7);
                if (distance(validTarget, requestedTarget) > 0.5) {
                    final java.awt.Stroke priorStroke = canvas.getStroke();
                    canvas.setStroke(new BasicStroke(1.4f,
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                            10, new float[]{4, 4}, 0));
                    canvas.drawLine(rounded(validTarget.x()),
                            rounded(validTarget.y()),
                            rounded(requestedTarget.x()),
                            rounded(requestedTarget.y()));
                    canvas.setStroke(priorStroke);
                }
            }
            if (highlighted.contains(requested.id())) {
                final java.awt.Stroke priorStroke = canvas.getStroke();
                canvas.setStroke(new BasicStroke(3.0f));
                drawRing(canvas, requestedTarget,
                        new Color(255, 170, 45, 245),
                        STRUCTURE_TARGET_DIAMETER + 11);
                canvas.setStroke(priorStroke);
            }
        }
    }

    /** A compact square handle mirrors Fiji's polygon ROI interaction. */
    private static void drawStructureVertexHandle(
            final Graphics2D canvas,
            final Point2D point,
            final int size) {
        final java.awt.Stroke priorStroke = canvas.getStroke();
        final Color priorColor = canvas.getColor();
        final int x = rounded(point.x());
        final int y = rounded(point.y());
        final int radius = size / 2;
        canvas.setStroke(new BasicStroke(3.0f));
        canvas.setColor(new Color(0, 0, 0, 210));
        canvas.drawRect(x - radius, y - radius, size, size);
        canvas.setStroke(new BasicStroke(1.4f));
        canvas.setColor(new Color(80, 225, 255, 250));
        canvas.drawRect(x - radius, y - radius, size, size);
        canvas.setStroke(priorStroke);
        canvas.setColor(priorColor);
    }

    private static void drawDashedRing(
            final Graphics2D canvas,
            final Point2D point,
            final Color color,
            final int diameter) {
        final java.awt.Stroke priorStroke = canvas.getStroke();
        final Color priorColor = canvas.getColor();
        canvas.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND, 10, new float[]{3, 3}, 0));
        canvas.setColor(color);
        final int radius = diameter / 2;
        canvas.drawOval(rounded(point.x()) - radius,
                rounded(point.y()) - radius, diameter, diameter);
        canvas.setStroke(priorStroke);
        canvas.setColor(priorColor);
    }

    private List<ManualHemisphereWarp2D.AtlasSide> manualWarpDrawOrder() {
        final ManualHemisphereWarp2D.AtlasSide other =
                activeHemisphereSide == ManualHemisphereWarp2D.AtlasSide.LEFT
                        ? ManualHemisphereWarp2D.AtlasSide.RIGHT
                        : ManualHemisphereWarp2D.AtlasSide.LEFT;
        return List.of(other, activeHemisphereSide).stream()
                .filter(side -> displayIncludesSide(model, side)).toList();
    }

    private static Color withAlpha(final Color color, final int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                alpha);
    }

    private void drawManualWarpControlsOnAtlas(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        // Manual controls are defined in final tissue coordinates. Showing
        // them in the atlas pane would require an inverse of the active mesh
        // and makes the source/target semantics ambiguous. The tissue pane is
        // the direct editing surface; keep only its handles visible.
    }

    private void drawTissueSupportControls(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        if (!tissueSupportEditing || model == null) {
            return;
        }
        model.reviewState().content().reviewedTissueSupport()
                .ifPresent(support -> support.controls().forEach(control ->
                        drawRing(canvas,
                                mapping.previewToScreen(
                                        liveTissueSupportPoint(control)),
                                REVIEWED_BOUNDARY_COLOR, 7)));
    }

    private void drawTissueSupportTrace(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        if (!tissueSupportTracing || tissueSupportTrace.isEmpty()) {
            return;
        }
        final Path2D.Double path = new Path2D.Double();
        final Point2D first = mapping.previewToScreen(
                tissueSupportTrace.get(0));
        path.moveTo(first.x(), first.y());
        for (int index = 1; index < tissueSupportTrace.size(); index++) {
            final Point2D point = mapping.previewToScreen(
                    tissueSupportTrace.get(index));
            path.lineTo(point.x(), point.y());
        }
        canvas.setColor(REVIEWED_BOUNDARY_COLOR);
        canvas.setStroke(new BasicStroke(1.8f));
        canvas.draw(path);
        if (tissueSupportTrace.size()
                >= ReviewedTissueSupport.MINIMUM_CONTROLS_PER_COMPONENT) {
            final Point2D last = mapping.previewToScreen(
                    tissueSupportTrace.get(tissueSupportTrace.size() - 1));
            canvas.setStroke(new BasicStroke(1.2f,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    10f, new float[]{5f, 4f}, 0f));
            canvas.drawLine(rounded(last.x()), rounded(last.y()),
                    rounded(first.x()), rounded(first.y()));
        }
        for (final Point2D point : tissueSupportTrace) {
            drawRing(canvas, mapping.previewToScreen(point),
                    REVIEWED_BOUNDARY_COLOR, 7);
        }
    }

    private Point2D liveTissueSupportPoint(
            final ReviewedTissueSupport.Control control) {
        return gesture != null
                && gesture.kind == GestureKind.TISSUE_SUPPORT
                && control.id().equals(gesture.landmarkId)
                ? gesture.point : control.point();
    }

    private ReviewedTissueSupport.Control tissueSupportControlAt(
            final Point point) {
        if (model == null) {
            return null;
        }
        final ScreenMapping mapping = sourceScreenMapping();
        return model.reviewState().content().reviewedTissueSupport()
                .flatMap(support -> support.controls().stream()
                        .filter(control -> distance(mapping.previewToScreen(
                                        control.point()), point)
                                <= MANUAL_WARP_HIT_RADIUS)
                        .findFirst())
                .orElse(null);
    }

    private TissueSupportEdge tissueSupportEdgeAt(final Point point) {
        if (model == null) {
            return null;
        }
        final ReviewedTissueSupport support = model.reviewState().content()
                .reviewedTissueSupport().orElse(null);
        if (support == null) {
            return null;
        }
        final ScreenMapping mapping = sourceScreenMapping();
        TissueSupportEdge best = null;
        double bestDistance = 10.0;
        for (int component = 0; component < support.polygons().size();
                component++) {
            final List<Point2D> polygon = support.polygon(component);
            for (int vertex = 0; vertex < polygon.size(); vertex++) {
                final Point2D first = mapping.previewToScreen(
                        polygon.get(vertex));
                final Point2D second = mapping.previewToScreen(
                        polygon.get((vertex + 1) % polygon.size()));
                final double candidate = pointToSegmentDistance(
                        new Point2D(point.x, point.y), first, second);
                if (candidate <= bestDistance) {
                    bestDistance = candidate;
                    best = new TissueSupportEdge(component, vertex);
                }
            }
        }
        return best;
    }

    private static double pointToSegmentDistance(
            final Point2D point,
            final Point2D first,
            final Point2D second) {
        final double dx = second.x() - first.x();
        final double dy = second.y() - first.y();
        final double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared == 0) {
            return distance(point, first);
        }
        final double fraction = Math.max(0, Math.min(1,
                ((point.x() - first.x()) * dx
                        + (point.y() - first.y()) * dy) / lengthSquared));
        return Math.hypot(point.x() - (first.x() + fraction * dx),
                point.y() - (first.y() + fraction * dy));
    }

    private Point2D liveManualWarpPoint(final ManualWarpControl control) {
        if (gesture != null && gesture.kind == GestureKind.MANUAL_WARP
                && control.id().equals(gesture.landmarkId)) {
            return gesture.point;
        }
        if (gesture != null && gesture.isStructureGroupGesture()
                && gesture.structureControlIds.contains(control.id())) {
            return gesture.transformedStructureControlPoint(
                    control.id(), control.targetPoint());
        }
        return control.targetPoint();
    }

    private static void drawRing(
            final Graphics2D canvas,
            final Point2D point,
            final Color color,
            final int diameter) {
        final int x = rounded(point.x());
        final int y = rounded(point.y());
        final int radius = diameter / 2;
        final java.awt.Stroke previous = canvas.getStroke();
        canvas.setColor(new Color(0, 0, 0, 190));
        canvas.setStroke(new BasicStroke(2.25f));
        canvas.drawOval(x - radius, y - radius, diameter, diameter);
        canvas.setColor(color);
        canvas.setStroke(new BasicStroke(1.25f));
        canvas.drawOval(x - radius, y - radius, diameter, diameter);
        canvas.setStroke(previous);
    }

    private void drawTransformSelection(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        if (interactionTool != InteractionTool.TRANSFORM
                || overlayImage == null
                || hasLocalDeformation() || exactBoundaryMapActive()
                || (model != null && model.boundaryFit().active())) {
            return;
        }
        final Selection selection = selection();
        if (selection == null) {
            return;
        }
        final Point2D[] corners = new Point2D[4];
        final Point2D[] edgeMidpoints = new Point2D[4];
        for (int index = 0; index < corners.length; index++) {
            corners[index] = mapping.previewToScreen(
                    selection.corners[index]);
            edgeMidpoints[index] = mapping.previewToScreen(
                    selection.edgeMidpoints[index]);
        }
        canvas.setStroke(new BasicStroke(1.5f));
        canvas.setColor(new Color(90, 210, 255));
        for (int index = 0; index < corners.length; index++) {
            final Point2D first = corners[index];
            final Point2D second = corners[(index + 1) % corners.length];
            canvas.drawLine(rounded(first.x()), rounded(first.y()),
                    rounded(second.x()), rounded(second.y()));
        }
        final Point2D center = mapping.previewToScreen(selection.center);
        final Point2D rotation = mapping.previewToScreen(
                selection.rotationHandle);
        if (placementTool == PlacementTool.ALL || placementTool == PlacementTool.ROTATE) {
            canvas.drawLine(rounded(center.x()), rounded(center.y()), rounded(rotation.x()), rounded(rotation.y()));
            handle(canvas, rotation, true);
        }
        if (placementTool == PlacementTool.ALL || placementTool == PlacementTool.SCALE) {
            for (final Point2D corner : corners) handle(canvas, corner, false);
            for (final Point2D edgeMidpoint : edgeMidpoints) handle(canvas, edgeMidpoint, false);
        }
    }

    private Selection selection() {
        final Selection base = baseSelection();
        if (base == null) {
            return null;
        }
        final Point2D[] corners = base.corners.clone();
        for (int index = 0; index < corners.length; index++) {
            corners[index] = livePreviewPoint(corners[index]);
        }
        return selectionFromCorners(corners);
    }

    private Selection structureSelection() {
        final List<ManualWarpControl> controls =
                selectedPrincipalStructureControls();
        if (!structureEditingEnabled || controls.size() < 4) {
            return null;
        }
        double minimumX = Double.POSITIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        for (final ManualWarpControl control : controls) {
            final Point2D point = control.targetPoint();
            minimumX = Math.min(minimumX, point.x());
            minimumY = Math.min(minimumY, point.y());
            maximumX = Math.max(maximumX, point.x());
            maximumY = Math.max(maximumY, point.y());
        }
        if (renderPaths != null) {
            for (final StructureComponentPath component
                    : principalStructureComponents()) {
                for (final LineSegment segment : component.tissueSegments()) {
                    for (final Point2D point : List.of(segment.first(),
                            segment.second())) {
                        minimumX = Math.min(minimumX, point.x());
                        minimumY = Math.min(minimumY, point.y());
                        maximumX = Math.max(maximumX, point.x());
                        maximumY = Math.max(maximumY, point.y());
                    }
                }
            }
        }
        if (!Double.isFinite(minimumX) || maximumX - minimumX < 1
                || maximumY - minimumY < 1) {
            return null;
        }
        return selectionFromCorners(new Point2D[]{
            new Point2D(minimumX, minimumY),
            new Point2D(maximumX, minimumY),
            new Point2D(maximumX, maximumY),
            new Point2D(minimumX, maximumY)
        });
    }

    private Point2D liveStructurePoint(final Point2D point) {
        if (gesture == null || !gesture.isStructureGroupGesture()) {
            return point;
        }
        if (gesture.kind == GestureKind.STRUCTURE_GAP) {
            return point;
        }
        if (gesture.kind == GestureKind.STRUCTURE_TRANSLATE) {
            return new Point2D(point.x() + gesture.delta.x(),
                    point.y() + gesture.delta.y());
        }
        final double dx = point.x() - gesture.pivot.x();
        final double dy = point.y() - gesture.pivot.y();
        return new Point2D(gesture.pivot.x() + dx * gesture.scaleX,
                gesture.pivot.y() + dy * gesture.scaleY);
    }

    private void drawStructureSelection(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        if (structureEditingEnabled) {
            // Structure is deliberately dot-first; sizing and gap changes
            // live in the two explicit inspector sliders.
            return;
        }
        final Selection selection = structureSelection();
        if (selection == null) {
            return;
        }
        final Point2D[] corners = new Point2D[4];
        for (int index = 0; index < corners.length; index++) {
            corners[index] = mapping.previewToScreen(liveStructurePoint(
                    selection.corners[index]));
        }
        canvas.setColor(REVIEWED_BOUNDARY_COLOR);
        canvas.setStroke(new BasicStroke(1.4f));
        for (int index = 0; index < corners.length; index++) {
            final Point2D first = corners[index];
            final Point2D second = corners[(index + 1) % corners.length];
            canvas.drawLine(rounded(first.x()), rounded(first.y()),
                    rounded(second.x()), rounded(second.y()));
            handle(canvas, first, false);
            handle(canvas, midpoint(first, second), false);
        }
        drawStructureGapControl(canvas, mapping);
    }

    private void drawStructureGapControl(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        final StructureGapGeometry gap = structureGapGeometry();
        if (gap == null) {
            return;
        }
        final Point2D firstPreview = gesture != null
                && gesture.kind == GestureKind.STRUCTURE_GAP
                ? gesture.transformStructurePoint(
                        gap.first().tissueCentroid(), -1)
                : gap.first().tissueCentroid();
        final Point2D secondPreview = gesture != null
                && gesture.kind == GestureKind.STRUCTURE_GAP
                ? gesture.transformStructurePoint(
                        gap.second().tissueCentroid(), 1)
                : gap.second().tissueCentroid();
        final Point2D first = mapping.previewToScreen(firstPreview);
        final Point2D second = mapping.previewToScreen(secondPreview);
        final Point2D middle = midpoint(first, second);
        final java.awt.Stroke prior = canvas.getStroke();
        canvas.setColor(REVIEWED_BOUNDARY_COLOR);
        canvas.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        canvas.drawLine(rounded(first.x()), rounded(first.y()),
                rounded(second.x()), rounded(second.y()));
        drawArrowHead(canvas, first, second);
        drawArrowHead(canvas, second, first);
        boundaryFitEndpoint(canvas, middle, REVIEWED_BOUNDARY_COLOR,
                false, true);
        canvas.setStroke(prior);
    }

    private static void drawArrowHead(
            final Graphics2D canvas,
            final Point2D tip,
            final Point2D from) {
        final double angle = Math.atan2(tip.y() - from.y(),
                tip.x() - from.x());
        final double length = 9;
        for (final double offset : new double[]{0.55, -0.55}) {
            canvas.drawLine(rounded(tip.x()), rounded(tip.y()),
                    rounded(tip.x() - length * Math.cos(angle + offset)),
                    rounded(tip.y() - length * Math.sin(angle + offset)));
        }
    }

    private void drawStructureGesturePreview(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        if (gesture == null || !gesture.isStructureGroupGesture()
                || renderPaths == null) {
            return;
        }
        final java.awt.Stroke prior = canvas.getStroke();
        canvas.setStroke(new BasicStroke(Math.max(1.8f,
                regionStrokeWidth + 0.5f), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        canvas.setColor(REVIEWED_BOUNDARY_COLOR);
        for (final StructureComponentPath component
                : principalStructureComponents()) {
            for (final LineSegment segment : component.tissueSegments()) {
                final Point2D first = mapping.previewToScreen(
                        gesture.transformStructurePoint(segment.first(),
                                gesture.structureComponentSigns.getOrDefault(
                                        component.id(), 0)));
                final Point2D second = mapping.previewToScreen(
                        gesture.transformStructurePoint(segment.second(),
                                gesture.structureComponentSigns.getOrDefault(
                                        component.id(), 0)));
                canvas.drawLine(rounded(first.x()), rounded(first.y()),
                        rounded(second.x()), rounded(second.y()));
            }
        }
        canvas.setStroke(prior);
    }

    private void drawStructureDraftGeometry(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        if (!structureEditingEnabled || renderPaths == null || model == null
                || model.structureAdjustment().draft().isEmpty()) {
            return;
        }
        final java.awt.Stroke prior = canvas.getStroke();
        final Color priorColor = canvas.getColor();
        canvas.setColor(REVIEWED_BOUNDARY_COLOR);
        canvas.setStroke(new BasicStroke(Math.max(1.8f,
                regionStrokeWidth + 0.6f), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        for (final LineSegment segment
                : renderPaths.structureCandidateSegments()) {
            final Point2D first = mapping.previewToScreen(segment.first());
            final Point2D second = mapping.previewToScreen(segment.second());
            canvas.drawLine(rounded(first.x()), rounded(first.y()),
                    rounded(second.x()), rounded(second.y()));
        }
        final boolean requestedPending = model.structureAdjustment().loading()
                || model.structureAdjustment().candidate().isEmpty()
                || model.structureAdjustment().candidate().stream()
                        .anyMatch(candidate -> !candidate.completesRequest());
        if (requestedPending) {
            canvas.setColor(new Color(80, 225, 255, 190));
            canvas.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND,
                    BasicStroke.JOIN_ROUND, 10, new float[]{6, 5}, 0));
            for (final LineSegment segment
                    : liveStructureRequestedSegments()) {
                final Point2D first = mapping.previewToScreen(
                        segment.first());
                final Point2D second = mapping.previewToScreen(
                        segment.second());
                canvas.drawLine(rounded(first.x()), rounded(first.y()),
                        rounded(second.x()), rounded(second.y()));
            }
        }
        canvas.setStroke(prior);
        canvas.setColor(priorColor);
    }

    /**
     * The requested contour follows a cyan vertex during the mouse gesture.
     * Verified exterior-loop order is independent of thickness-pair order,
     * so even a 64-dot draft stays a recognizable Fiji-style ROI outline.
     * No warp fit, atlas remap, or scientific state change occurs on drag.
     */
    private List<LineSegment> liveStructureRequestedSegments() {
        final StructureAdjustmentDraft draft = model.structureAdjustment()
                .draft().orElse(null);
        if (draft == null) {
            return List.of();
        }
        final Map<String, ManualWarpControl> controls = draft
                .requestedControls().stream().collect(
                        java.util.stream.Collectors.toMap(
                                ManualWarpControl::id,
                                Function.identity()));
        return structureOutlineSegments(draft, id ->
                liveManualWarpPoint(controls.get(id)));
    }

    private static List<LineSegment> structureOutlineSegments(
            final StructureAdjustmentDraft draft,
            final Function<String, Point2D> pointByControlId) {
        final List<LineSegment> result = new ArrayList<>();
        for (final StructureOutlinePath path : draft.outlinePaths()) {
            final List<Point2D> points = path.controlIds().stream()
                    .map(pointByControlId).toList();
            final int segmentCount = path.closed()
                    ? points.size() : points.size() - 1;
            for (int index = 0; index < segmentCount; index++) {
                result.add(new LineSegment(points.get(index),
                        points.get((index + 1) % points.size())));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Current corner and edge resize handles in screen coordinates. Corners
     * come first clockwise, followed by the matching edge midpoints. Keeping
     * this package-visible also lets UI geometry tests use the same computed
     * hit targets that the canvas renders.
     */
    List<Point2D> transformResizeHandleScreenPoints() {
        final Selection current = selection();
        if (current == null) {
            return List.of();
        }
        final ScreenMapping mapping = sourceScreenMapping();
        final List<Point2D> result = new ArrayList<>(8);
        for (final Point2D corner : current.corners) {
            result.add(mapping.previewToScreen(corner));
        }
        for (final Point2D edge : current.edgeMidpoints) {
            result.add(mapping.previewToScreen(edge));
        }
        return List.copyOf(result);
    }

    /** Package-visible rotation target for deterministic UI dispatch tests. */
    Optional<Point2D> transformRotationHandleScreenPoint() {
        final Selection current = selection();
        return current == null ? Optional.empty()
                : Optional.of(sourceScreenMapping().previewToScreen(
                        current.rotationHandle));
    }

    /** Package-visible selected-structure corner then side-handle geometry. */
    List<Point2D> structureResizeHandleScreenPoints() {
        final Selection current = structureSelection();
        if (current == null) {
            return List.of();
        }
        final ScreenMapping mapping = sourceScreenMapping();
        final List<Point2D> result = new ArrayList<>(8);
        for (final Point2D corner : current.corners) {
            result.add(mapping.previewToScreen(corner));
        }
        for (final Point2D edge : current.edgeMidpoints) {
            result.add(mapping.previewToScreen(edge));
        }
        return List.copyOf(result);
    }

    /** Package-visible double-arrow hit target for deterministic UI tests. */
    Optional<Point2D> structureGapHandleScreenPoint() {
        final StructureGapGeometry gap = structureGapGeometry();
        return gap == null ? Optional.empty()
                : Optional.of(sourceScreenMapping().previewToScreen(
                        gap.midpoint()));
    }

    /** Package-visible positive separation direction for UI geometry tests. */
    Optional<Point2D> structureGapAxisPreview() {
        final StructureGapGeometry gap = structureGapGeometry();
        return gap == null ? Optional.empty() : Optional.of(gap.axis());
    }

    private Selection baseSelection() {
        return baseSelection(activeHemisphereSide);
    }

    private Selection baseSelection(
            final ManualHemisphereWarp2D.AtlasSide requestedSide) {
        if (model == null || model.atlasPlane().isEmpty()) {
            return null;
        }
        final AtlasCoronalPlane plane = model.atlasPlane().orElseThrow();
        final ReviewSectionMode mode = model.reviewState().content()
                .reviewSectionMode();
        final ManualHemisphereWarp2D.AtlasSide boundedSide =
                mode == ReviewSectionMode.DISJOINED
                        ? requestedSide
                        : mode == ReviewSectionMode.HALF
                        ? visibleHalfSideOrNull(model) : null;
        final int[] bounds = mode == ReviewSectionMode.HALF
                && boundedSide == null ? null
                : annotationBounds(plane, boundedSide);
        if (bounds == null) {
            return null;
        }
        final Point2D[] atlasCorners = {
            new Point2D(bounds[0] - 0.5, bounds[1] - 0.5),
            new Point2D(bounds[2] + 0.5, bounds[1] - 0.5),
            new Point2D(bounds[2] + 0.5, bounds[3] + 0.5),
            new Point2D(bounds[0] - 0.5, bounds[3] + 0.5)
        };
        final Point2D[] corners = new Point2D[atlasCorners.length];
        for (int index = 0; index < atlasCorners.length; index++) {
            corners[index] = disjoinedTransformEnabled()
                    ? model.reviewState().mapAtlasToPreview(
                            requestedSide, atlasCorners[index])
                    : model.reviewState().mapAtlasToPreview(
                            atlasCorners[index]);
        }
        return selectionFromCorners(corners);
    }

    private static ManualHemisphereWarp2D.AtlasSide visibleHalfSideOrNull(
            final ReviewViewModel model) {
        return switch (model.reviewState().content().observedHemisphere()) {
            case LEFT -> ManualHemisphereWarp2D.AtlasSide.LEFT;
            case RIGHT -> ManualHemisphereWarp2D.AtlasSide.RIGHT;
            case BOTH, UNSURE -> null;
        };
    }

    private Selection selectionFromCorners(final Point2D[] corners) {
        final Point2D[] edgeMidpoints = new Point2D[4];
        for (int index = 0; index < edgeMidpoints.length; index++) {
            edgeMidpoints[index] = midpoint(
                    corners[index], corners[(index + 1) % 4]);
        }
        final Point2D center = midpoint(corners[0], corners[2]);
        final Point2D top = edgeMidpoints[0];
        final double vx = top.x() - center.x();
        final double vy = top.y() - center.y();
        final double length = Math.max(1e-9, Math.hypot(vx, vy));
        final double previewDistance = ROTATION_HANDLE_DISTANCE
                / sourceScreenMapping().scale();
        final Point2D rotationHandle = new Point2D(
                top.x() + vx / length * previewDistance,
                top.y() + vy / length * previewDistance);
        final double axisRadians = angle(corners[0], corners[1]);
        return new Selection(corners, edgeMidpoints, center,
                rotationHandle, axisRadians);
    }

    private static int[] annotationBounds(
            final AtlasCoronalPlane plane,
            final ManualHemisphereWarp2D.AtlasSide side) {
        int minimumX = plane.width();
        int minimumY = plane.height();
        int maximumX = -1;
        int maximumY = -1;
        final int[] labels = plane.annotationId();
        final double middle = (plane.width() - 1.0) * 0.5;
        for (int y = 0; y < plane.height(); y++) {
            for (int x = 0; x < plane.width(); x++) {
                final boolean requestedSide = side == null
                        || side == ManualHemisphereWarp2D.AtlasSide.LEFT
                                && x <= middle
                        || side == ManualHemisphereWarp2D.AtlasSide.RIGHT
                                && x >= middle;
                if (requestedSide && labels[y * plane.width() + x] != 0) {
                    minimumX = Math.min(minimumX, x);
                    minimumY = Math.min(minimumY, y);
                    maximumX = Math.max(maximumX, x);
                    maximumY = Math.max(maximumY, y);
                }
            }
        }
        return maximumX < 0 ? null
                : new int[] {minimumX, minimumY, maximumX, maximumY};
    }

    private void drawPendingAtlasPoint(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        if (pendingAtlasPoint == null) {
            return;
        }
        marker(canvas, mapping.previewToScreen(pendingAtlasPoint),
                new Color(255, 235, 40));
    }

    private void drawStatus(final Graphics2D canvas) {
        if (pendingBoundaryAtlasPoint != null) {
            status(canvas,
                    "Atlas border selected — Shift-click the matching cyan tissue border.",
                    new Color(255, 220, 80));
        } else if (boundaryWarpPreviewVisible
                && model.boundaryWarp().safetyReport().isPresent()
                && model.boundaryWarp().candidate().isEmpty()) {
            status(canvas,
                    "Border changes not applied — showing the current atlas; requested dots remain editable.",
                    new Color(255, 220, 80));
        } else if (model.boundaryWarp().loading()) {
            status(canvas, "Checking manual outer-border pairs…",
                    new Color(255, 220, 80));
        } else if (model.boundaryWarp().draft().isPresent()) {
            final long included = model.boundaryWarp().drafts().stream()
                    .flatMap(request -> request.matches().stream())
                    .filter(BoundaryFitMatch::included).count();
            status(canvas, "Border warp — click or drag points; "
                    + included + " included across visible sides.",
                    new Color(130, 230, 255));
        } else if (model.boundaryFit().loading()) {
            status(canvas, "Checking completed border matches…",
                    new Color(255, 220, 80));
        } else if (model.boundaryFit().draft().isPresent()) {
            final BoundaryFitDraft draft = model.boundaryFit().draft()
                    .orElseThrow();
            final String active = draft.activeAnchor()
                    .map(anchor -> Integer.toString(anchor.ordinal()))
                    .orElse("—");
            status(canvas,
                    "Border matching — click cyan border or a free torn-edge position; drag atlas dot "
                            + active + " onto it • "
                            + draft.includedCompletedCount()
                            + "/4 ready.",
                    new Color(130, 230, 255));
        } else if (pendingAtlasPoint != null) {
            status(canvas,
                    "Atlas point selected — click the matching tissue point.",
                    new Color(255, 235, 80));
        } else if (model.atlasPlaneLoading()) {
            status(canvas, "Loading verified atlas plane…",
                    new Color(255, 220, 80));
        } else if (model.atlasPlaneError().isPresent()) {
            status(canvas, "Atlas plane unavailable: "
                    + model.atlasPlaneError().orElseThrow(),
                    new Color(255, 100, 100));
        } else if (model.selectedAtlasRegion().isPresent()
                && selectedRegionBoundaryCount == 0) {
            status(canvas, "Selected target is not present on this atlas plane.",
                    new Color(255, 180, 80));
        } else if (model.selectedAtlasRegion().isPresent()
                && !manualWarpEditingEnabled) {
            status(canvas, "Guide: " + model.selectedAtlasRegion().orElseThrow().acronym()
                    + " — cyan points edit your ROI; atlas alignment stays fixed.", SELECTED_REGION_COLOR);
        } else if (model.selectedAtlasRegion().isPresent()
                && interactionTool == InteractionTool.POINTS) {
            status(canvas, "Target: " + model.selectedAtlasRegion()
                            .orElseThrow().displayName()
                            + " — atlas clicks snap to its thin magenta boundary.",
                    SELECTED_REGION_COLOR);
        } else if (interactionTool == InteractionTool.TRANSFORM
                && hasLocalDeformation()) {
            status(canvas,
                    "Local warp active — clear or undo it before global transform edits.",
                    new Color(130, 220, 255));
        } else if (interactionTool == InteractionTool.TRANSFORM) {
            status(canvas,
                    tissueClippingEnabled
                            ? "Place atlas — drag body or resize handles; Shift keeps proportions. Crop clipping resumes in Points."
                            : "Place atlas — drag body or resize handles; Shift keeps proportions; choose Points for local refinement.",
                    new Color(130, 220, 255));
        }
    }

    /** Draws a display-only grid so local bending is visible and auditable. */
    private void drawLocalWarpGrid(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        if (!manualWarpEditingEnabled || !deformationGridVisible) {
            return;
        }
        final RenderPaths paths = renderPaths;
        if (paths == null || paths.gridSegments().isEmpty()) {
            return;
        }
        drawSegments(canvas, paths.gridSegments(), mapping, false,
                new Color(70, 210, 255, 105), 1f, false);
    }

    private boolean hasLocalDeformation() {
        return model.reviewState().content().hemisphereWarp().isPresent()
                || model.reviewState().content().localWarp().isPresent();
    }

    private boolean disjoinedTransformEnabled() {
        return model != null && model.reviewState().content()
                .reviewSectionMode() == ReviewSectionMode.DISJOINED;
    }

    private boolean exactBoundaryMapActive() {
        return model != null && model.reviewState().content().outlineWarp()
                .orElse(null)
                instanceof org.atlasalign.application.manual
                        .BoundaryAuthoritativeTransform2D;
    }

    private void drawPaneLabel(
            final Graphics2D canvas,
            final String text,
            final int x,
            final int width) {
        canvas.setColor(new Color(26, 26, 30));
        canvas.fillRect(x, 0, width, PANE_LABEL_HEIGHT);
        canvas.setColor(new Color(232, 232, 232));
        canvas.drawString(text, x + 8, 16);
    }

    private static void status(
            final Graphics2D canvas,
            final String text,
            final Color color) {
        canvas.setColor(new Color(0, 0, 0, 190));
        canvas.fillRoundRect(12, PANE_LABEL_HEIGHT + 12,
                Math.max(300,
                        canvas.getFontMetrics().stringWidth(text) + 20),
                28, 8, 8);
        canvas.setColor(color);
        canvas.drawString(text, 22, PANE_LABEL_HEIGHT + 31);
    }

    private ScreenMapping paneMapping(
            final int imageWidth,
            final int imageHeight,
            final int paneX) {
        final ScreenMapping fit = basePaneMapping(
                imageWidth, imageHeight, paneX);
        final boolean tissue = paneX == 0;
        final double zoom = tissue ? tissueZoom : atlasZoom;
        return new ScreenMapping(
                imageWidth, imageHeight,
                fit.screenWidth(), fit.screenHeight(),
                fit.scale() * zoom,
                fit.offsetX() + (tissue ? tissuePanX : atlasPanX),
                fit.offsetY() + (tissue ? tissuePanY : atlasPanY));
    }

    private ScreenMapping basePaneMapping(
            final int imageWidth,
            final int imageHeight,
            final int paneX) {
        final int paneWidth = sourcePaneWidth();
        final int paneHeight = Math.max(1,
                getHeight() - PANE_LABEL_HEIGHT);
        final ScreenMapping relative = ScreenMapping.fit(
                imageWidth, imageHeight, paneWidth, paneHeight);
        return new ScreenMapping(
                imageWidth, imageHeight, paneWidth, paneHeight,
                relative.scale(), paneX + relative.offsetX(),
                PANE_LABEL_HEIGHT + relative.offsetY());
    }

    private int sourcePaneWidth() {
        if (singleTissuePane) {
            return Math.max(1, getWidth());
        }
        return Math.max(1, (Math.max(1, getWidth()) - PANE_GAP) / 2);
    }

    private int atlasPaneX() {
        return sourcePaneWidth() + PANE_GAP;
    }

    private AtlasCoronalPlane currentAtlasPlane() {
        if (model == null || model.atlasPlane().isEmpty()) {
            throw new IllegalStateException(
                    "Verified atlas plane is not available");
        }
        return model.atlasPlane().orElseThrow();
    }

    private static boolean contains(
            final ScreenMapping mapping,
            final Point point) {
        final double right = mapping.offsetX()
                + mapping.previewWidth() * mapping.scale();
        final double bottom = mapping.offsetY()
                + mapping.previewHeight() * mapping.scale();
        return point.x >= mapping.offsetX() && point.x < right
                && point.y >= mapping.offsetY() && point.y < bottom;
    }

    private static AffineTransform imageToScreen(
            final ScreenMapping mapping) {
        final AffineTransform transform = AffineTransform
                .getTranslateInstance(
                        mapping.offsetX() - 0.5,
                        mapping.offsetY() - 0.5);
        transform.scale(mapping.scale(), mapping.scale());
        transform.translate(0.5, 0.5);
        return transform;
    }

    private static void handle(
            final Graphics2D canvas,
            final Point2D point,
            final boolean circular) {
        final int x = rounded(point.x());
        final int y = rounded(point.y());
        canvas.setColor(Color.BLACK);
        if (circular) {
            canvas.fillOval(x - 6, y - 6, 13, 13);
            canvas.setColor(new Color(90, 210, 255));
            canvas.drawOval(x - 5, y - 5, 10, 10);
        } else {
            canvas.fillRect(x - 6, y - 6, 13, 13);
            canvas.setColor(new Color(90, 210, 255));
            canvas.drawRect(x - 5, y - 5, 10, 10);
        }
    }

    private static void marker(
            final Graphics2D canvas,
            final Point2D point,
            final Color color) {
        final int x = rounded(point.x());
        final int y = rounded(point.y());
        canvas.setColor(Color.BLACK);
        canvas.fillOval(x - 6, y - 6, 13, 13);
        canvas.setColor(color);
        canvas.drawOval(x - 5, y - 5, 10, 10);
        canvas.drawLine(x - 7, y, x + 7, y);
        canvas.drawLine(x, y - 7, x, y + 7);
    }

    static BufferedImage previewImage(final ReviewPreview preview) {
        final float[] pixels = preview.pixels();
        final PreviewDisplayWindow window = preview.displayWindow();
        final BufferedImage image = new BufferedImage(
                preview.width(), preview.height(),
                BufferedImage.TYPE_BYTE_GRAY);
        final byte[] output = ((DataBufferByte) image.getRaster()
                .getDataBuffer()).getData();
        for (int index = 0; index < pixels.length; index++) {
            output[index] = (byte) Math.max(0, Math.min(255,
                    Math.round((pixels[index] - window.lower())
                            / window.range() * 255)));
        }
        return image;
    }

    /**
     * Preview-resolution visualization of the exact export membership rule.
     * The source preview is display-only; exact export continues to read the
     * immutable original source pixels through the export service.
     */
    static BufferedImage exportedRoiPreviewImage(
            final ReviewViewModel model,
            final AtlasCoronalPlane plane) {
        final BufferedImage dimming = exportedRoiDimmingOverlay(model, plane);
        return dimming == null ? null : dimPreview(previewImage(model.preview()), dimming);
    }

    private static BufferedImage dimPreview(final BufferedImage source, final BufferedImage dimming) {
        final BufferedImage result = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        final byte[] input = ((DataBufferByte) source.getRaster().getDataBuffer()).getData();
        final byte[] output = ((DataBufferByte) result.getRaster().getDataBuffer()).getData();
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            final int index = y * source.getWidth() + x; final int intensity = Byte.toUnsignedInt(input[index]);
            output[index] = (byte) (dimming.getRGB(x, y) >>> 24 == 0 ? intensity : Math.round(intensity * .20f));
        }
        return result;
    }

    static BufferedImage exportedRoiDimmingOverlay(final ReviewViewModel model, final AtlasCoronalPlane plane) {
        final SelectedAtlasRegion selection = model.selectedAtlasRegion()
                .orElse(null);
        if (selection == null) {
            return null;
        }
        AlignmentReviewState projection = model.reviewState();
        final StructureAdjustmentCandidate candidate = model
                .structureAdjustment().candidate().orElse(null);
        if (candidate != null) {
            projection = new AlignmentReviewState(
                    projection.basis(),
                    projection.content().withHemisphereWarpForProjection(
                            Optional.of(candidate.auditedWarp())),
                    projection.contentRevision());
        }
        final ReviewedTissueSupport support = projection.content()
                .tissueClippingEnabled()
                ? projection.content().reviewedTissueSupport().orElseThrow()
                : null;
        final BufferedImage result = new BufferedImage(model.preview().width(), model.preview().height(), BufferedImage.TYPE_INT_ARGB);
        final int[] labels = plane.annotationId();
        for (int y = 0; y < result.getHeight(); y++) {
            for (int x = 0; x < result.getWidth(); x++) {
                boolean included = support == null || support.contains(x, y);
                if (included) {
                    included = selectedAtlasMembership(
                            projection, selection, plane, labels,
                            new Point2D(x, y));
                }
                result.setRGB(x, y, included ? 0 : 0xcc000000);
            }
        }
        return result;
    }

    private static boolean selectedAtlasMembership(
            final AtlasMembershipProjection projection,
            final SelectedAtlasRegion selection,
            final AtlasCoronalPlane plane,
            final int[] labels,
            final Point2D previewPoint) {
        final List<Point2D> candidates;
        try {
            candidates = projection.mapPreviewToAtlasCandidates(
                    previewPoint);
        } catch (final IllegalArgumentException outsideField) {
            return false;
        }
        for (final Point2D atlasPoint : candidates) {
            if (!projection.includesAtlasPoint(atlasPoint)) {
                continue;
            }
            final int atlasX = (int) Math.round(atlasPoint.x());
            final int atlasY = (int) Math.round(atlasPoint.y());
            if (atlasX < 0 || atlasX >= plane.width()
                    || atlasY < 0 || atlasY >= plane.height()) {
                continue;
            }
            if (selection.contains(labels[atlasY * plane.width()
                    + atlasX])) {
                return true;
            }
        }
        return false;
    }

    static BufferedImage overlayImage(
            final ReviewViewModel model,
            final AtlasCoronalPlane plane) {
        return overlayImage(model, plane,
                model.selectedAtlasContour().orElse(null));
    }

    static BufferedImage overlayImage(
            final ReviewViewModel model,
            final AtlasCoronalPlane plane,
            final boolean clipToTissue) {
        final SelectedAtlasContour contour = model.selectedAtlasContour()
                .orElse(null);
        final RenderPaths paths = buildRenderPaths(model, contour, false,
                clipToTissue, null);
        return renderOverlayImage(model, paths, contour,
                new Color(255, 120, 20, 230), 1.5f);
    }

    private static BufferedImage overlayImage(
            final ReviewViewModel model,
            final AtlasCoronalPlane plane,
            final SelectedAtlasContour selectedContour) {
        final RenderPaths paths = buildRenderPaths(
                model, selectedContour, false);
        return renderOverlayImage(model, paths, selectedContour,
                new Color(255, 120, 20, 230), 1.5f);
    }

    /**
     * Rasterizes only the already mapped, edge-split vector cache. This is
     * intentionally separate from path construction so paintComponent can
     * consume an immutable image without doing any coordinate mapping.
     */
    private static BufferedImage renderOverlayImage(
            final ReviewViewModel model,
            final RenderPaths paths,
            final SelectedAtlasContour selectedContour,
            final Color dimColor,
            final float strokeWidth) {
        final int alpha = selectedContour == null
                ? Math.max(210, dimColor.getAlpha())
                : Math.min(145, dimColor.getAlpha());
        final BufferedImage image = renderBoundaryImage(model,
                paths.tissueBoundarySegments(),
                new Color(dimColor.getRed(), dimColor.getGreen(),
                        dimColor.getBlue(), alpha), strokeWidth);
        return image;
    }

    /** Builds the nonlinear Border ghost entirely on the render worker. */
    private static BufferedImage renderBoundaryWarpGhost(
            final ReviewViewModel model,
            final RenderPaths paths,
            final Color dimColor,
            final float strokeWidth) {
        final List<BoundaryGhost> ghosts = new ArrayList<>();
        model.boundaryWarp().draft().ifPresent(request -> {
            model.boundaryWarp().candidate().ifPresentOrElse(candidate -> {
                if (!candidate.completesRequestedWarp()) {
                    model.boundaryWarp().preview().ifPresent(preview ->
                            ghosts.add(new BoundaryGhost(request,
                                    preview.field()::mapPath, false,
                                    BoundaryGhostKind.REQUESTED)));
                }
                ghosts.add(new BoundaryGhost(request,
                        candidate.previewDeltaWarp()::mapPath, false,
                        BoundaryGhostKind.AUDITED));
            }, () -> model.boundaryWarp().preview().ifPresent(preview ->
                    ghosts.add(new BoundaryGhost(request,
                            preview.field()::mapPath, false,
                            BoundaryGhostKind.REQUESTED))));
        });
        model.boundaryWarp().secondaryDraft().ifPresent(request -> {
            model.boundaryWarp().secondaryCandidate().ifPresentOrElse(
                    candidate -> {
                        if (!candidate.completesRequestedWarp()) {
                            model.boundaryWarp().secondaryPreview()
                                    .ifPresent(preview -> ghosts.add(
                                            new BoundaryGhost(request,
                                                    preview.field()::mapPath,
                                                    true,
                                                    BoundaryGhostKind
                                                            .REQUESTED)));
                        }
                        ghosts.add(new BoundaryGhost(request,
                                candidate.previewDeltaWarp()::mapPath, true,
                                BoundaryGhostKind.AUDITED));
                    },
                    () -> model.boundaryWarp().secondaryPreview()
                            .ifPresent(preview -> ghosts.add(
                                    new BoundaryGhost(request,
                                            preview.field()::mapPath,
                                            true,
                                            BoundaryGhostKind.REQUESTED))));
        });
        if (ghosts.isEmpty()) {
            return null;
        }
        final BufferedImage image = new BufferedImage(
                model.preview().width(), model.preview().height(),
                BufferedImage.TYPE_INT_ARGB);
        final Graphics2D canvas = image.createGraphics();
        try {
            canvas.setComposite(AlphaComposite.SrcOver);
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            for (final BoundaryGhost ghost : ghosts) {
                final ManualHemisphereWarp2D.AtlasSide side = ghost
                        .request().targetSide();
                final List<LineSegment> boundary = side
                        == ManualHemisphereWarp2D.AtlasSide.LEFT
                        ? paths.leftPlacementBoundarySegments()
                        : paths.rightPlacementBoundarySegments();
                final List<LineSegment> selected = side
                        == ManualHemisphereWarp2D.AtlasSide.LEFT
                        ? paths.leftPlacementContourSegments()
                        : paths.rightPlacementContourSegments();
                final boolean requested = ghost.kind()
                        == BoundaryGhostKind.REQUESTED;
                canvas.setStroke(requested
                        ? new BasicStroke(strokeWidth,
                                BasicStroke.CAP_ROUND,
                                BasicStroke.JOIN_ROUND, 10f,
                                new float[]{6f, 5f}, 0f)
                        : new BasicStroke(Math.max(1.25f, strokeWidth),
                                BasicStroke.CAP_ROUND,
                                BasicStroke.JOIN_ROUND));
                canvas.setColor(requested
                        ? new Color(255, 178, 68,
                                ghost.inactive() ? 75 : 185)
                        : new Color(80, 225, 255,
                                ghost.inactive() ? 90 : 220));
                drawImageSegments(canvas, mapGhostSegments(
                        ghost.mapper(), side, boundary));
                if (!selected.isEmpty()) {
                    canvas.setColor(requested
                            ? new Color(255, 118, 32,
                                    ghost.inactive() ? 75 : 200)
                            : new Color(100, 245, 255,
                                    ghost.inactive() ? 100 : 235));
                    drawImageSegments(canvas, mapGhostSegments(
                            ghost.mapper(), side, selected));
                }
            }
        } finally {
            canvas.dispose();
        }
        return image;
    }

    private record BoundaryGhost(
            BoundaryWarpRequest request,
            BoundaryGhostMapper mapper,
            boolean inactive,
            BoundaryGhostKind kind) {
    }

    private enum BoundaryGhostKind {
        REQUESTED,
        AUDITED
    }

    @FunctionalInterface
    private interface BoundaryGhostMapper {
        List<Point2D> mapPath(
                ManualHemisphereWarp2D.AtlasSide side,
                List<Point2D> path,
                boolean closed);
    }

    private static List<LineSegment> mapGhostSegments(
            final BoundaryGhostMapper mapper,
            final ManualHemisphereWarp2D.AtlasSide side,
            final List<LineSegment> segments) {
        final List<LineSegment> mapped = new ArrayList<>();
        for (final LineSegment segment : segments) {
            final List<Point2D> path = mapper.mapPath(side,
                    List.of(segment.first(), segment.second()), false);
            Point2D previous = null;
            for (final Point2D point : path) {
                if (previous != null && !previous.equals(point)) {
                    mapped.add(new LineSegment(previous, point));
                }
                previous = point;
            }
        }
        return List.copyOf(mapped);
    }

    private static void drawImageSegments(
            final Graphics2D canvas,
            final List<LineSegment> segments) {
        for (final LineSegment segment : segments) {
            canvas.draw(new java.awt.geom.Line2D.Double(
                    segment.first().x(), segment.first().y(),
                    segment.second().x(), segment.second().y()));
        }
    }

    private static BufferedImage renderBoundaryImage(
            final ReviewViewModel model,
            final List<LineSegment> segments,
            final Color color) {
        return renderBoundaryImage(model, segments, color, 1.5f);
    }

    private static BufferedImage renderBoundaryImage(
            final ReviewViewModel model,
            final List<LineSegment> segments,
            final Color color,
            final float strokeWidth) {
        final int width = model.preview().width();
        final int height = model.preview().height();
        final BufferedImage image = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D canvas = image.createGraphics();
        try {
            canvas.setComposite(AlphaComposite.Src);
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            canvas.setColor(color);
            canvas.setStroke(new BasicStroke(strokeWidth,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (final LineSegment segment
                    : segments) {
                final Point2D first = segment.first();
                final Point2D second = segment.second();
                canvas.drawLine(rounded(first.x()), rounded(first.y()),
                        rounded(second.x()), rounded(second.y()));
            }
        } finally {
            canvas.dispose();
        }
        return image;
    }

    static PlacementLayer renderPlacementLayer(
            final List<LineSegment> segments,
            final Color dimColor,
            final float strokeWidth,
            final boolean selectedContourPresent) {
        if (segments.isEmpty()) {
            return null;
        }
        double minimumX = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        for (final LineSegment segment : segments) {
            for (final Point2D point : List.of(
                    segment.first(), segment.second())) {
                if (!Double.isFinite(point.x())
                        || !Double.isFinite(point.y())) {
                    throw new IllegalArgumentException(
                            "Placement overlay contains a non-finite point");
                }
                minimumX = Math.min(minimumX, point.x());
                maximumX = Math.max(maximumX, point.x());
                minimumY = Math.min(minimumY, point.y());
                maximumY = Math.max(maximumY, point.y());
            }
        }
        final int padding = Math.max(4,
                (int) Math.ceil(strokeWidth + 2));
        final int originX = (int) Math.floor(minimumX) - padding;
        final int originY = (int) Math.floor(minimumY) - padding;
        final long width = (long) Math.ceil(maximumX) - originX
                + padding + 1L;
        final long height = (long) Math.ceil(maximumY) - originY
                + padding + 1L;
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
            throw new IllegalArgumentException(
                    "Placement overlay exceeds the bounded preview cache");
        }
        final BufferedImage image = new BufferedImage(
                (int) width, (int) height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D canvas = image.createGraphics();
        try {
            canvas.setComposite(AlphaComposite.Src);
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            final int alpha = selectedContourPresent
                    ? Math.min(145, dimColor.getAlpha())
                    : Math.max(210, dimColor.getAlpha());
            canvas.setColor(new Color(dimColor.getRed(),
                    dimColor.getGreen(), dimColor.getBlue(), alpha));
            canvas.setStroke(new BasicStroke(strokeWidth,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            canvas.translate(-originX, -originY);
            for (final LineSegment segment : segments) {
                canvas.draw(new java.awt.geom.Line2D.Double(
                        segment.first().x(), segment.first().y(),
                        segment.second().x(), segment.second().y()));
            }
        } finally {
            canvas.dispose();
        }
        return new PlacementLayer(image, originX, originY);
    }

    private static PlacementLayers renderPlacementLayers(
            final RenderPaths paths,
            final Color dimColor,
            final float strokeWidth,
            final boolean selectedContourPresent,
            final ReviewSectionMode mode) {
        if (mode != ReviewSectionMode.DISJOINED) {
            return new PlacementLayers(renderPlacementLayer(
                    paths.tissueBoundarySegments(), dimColor, strokeWidth,
                    selectedContourPresent), null, null);
        }
        return new PlacementLayers(null,
                renderPlacementLayer(paths.leftPlacementBoundarySegments(),
                        dimColor, strokeWidth, selectedContourPresent),
                renderPlacementLayer(paths.rightPlacementBoundarySegments(),
                        dimColor, strokeWidth, selectedContourPresent));
    }

    private static BufferedImage atlasImage(
            final ReviewViewModel model,
            final AtlasCoronalPlane plane,
            final SelectedAtlasContour selectedContour,
            final ManualHemisphereWarp2D.AtlasSide activeSide) {
        final BufferedImage image = new BufferedImage(
                plane.width(), plane.height(), BufferedImage.TYPE_INT_RGB);
        final int[] annotation = plane.annotationId();
        int maximum = 1;
        final int[] template = plane.hasTemplateIntensity()
                ? plane.templateIntensity() : null;
        if (template != null) {
            for (final int value : template) {
                maximum = Math.max(maximum, value);
            }
        }
        for (int y = 0; y < plane.height(); y++) {
            for (int x = 0; x < plane.width(); x++) {
                final int index = y * plane.width() + x;
                if (!atlasReferenceIncludesX(model, x, plane.width(),
                        activeSide)) {
                    image.setRGB(x, y, 0);
                    continue;
                }
                final int intensity = template == null ? 0
                        : Math.min(255,
                                template[index] * 255 / maximum);
                final boolean boundary = isBoundary(annotation,
                        plane.width(), plane.height(), x, y,
                        candidateX -> atlasReferenceIncludesX(model,
                                candidateX, plane.width(), activeSide));
                final int rgb = boundary
                        ? 255 << 16 | 120 << 8 | 20
                        : intensity << 16 | intensity << 8 | intensity;
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    static boolean atlasReferenceIncludesX(
            final ReviewViewModel model,
            final int x,
            final int width,
            final ManualHemisphereWarp2D.AtlasSide activeSide) {
        if (model.reviewState().content().reviewSectionMode()
                == ReviewSectionMode.FULL) {
            return true;
        }
        final double centre = (width - 1.0) * 0.5;
        if (Math.abs(x - centre) <= 1e-9) {
            return false;
        }
        final ManualHemisphereWarp2D.AtlasSide side = x < centre
                ? ManualHemisphereWarp2D.AtlasSide.LEFT
                : ManualHemisphereWarp2D.AtlasSide.RIGHT;
        return switch (model.reviewState().content().reviewSectionMode()) {
            case FULL -> true;
            case HALF -> displayIncludesSide(model, side);
            case DISJOINED -> side == activeSide;
        };
    }

    private Point2D snapToSelectedRegionBoundary(final Point2D requested) {
        if (selectedRegionContour == null) {
            return requested;
        }
        final double maximumDistance = REGION_SNAP_RADIUS_SCREEN
                / atlasScreenMapping().scale();
        return selectedRegionContour.nearestBoundary(
                requested, maximumDistance).orElse(null);
    }

    private void drawSelectedRegionOutlineOnTissue(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        final RenderPaths paths = renderPaths;
        if (paths == null) {
            return;
        }
        if (comparisonMode != ComparisonMode.AFTER) {
            drawSegments(canvas, paths.beforeTissueContourSegments(), mapping,
                    true, new Color(205, 205, 205, 190),
                    regionStrokeWidth, true);
        }
        if (comparisonMode != ComparisonMode.BEFORE) {
            if (structureEditingEnabled
                    && !paths.structureComponentPaths().isEmpty()) {
                for (final StructureComponentPath component
                        : paths.structureComponentPaths()) {
                    if (component.side() != activeHemisphereSide) {
                        continue;
                    }
                    drawSegments(canvas, component.tissueSegments(), mapping,
                            false, component.principal()
                                    ? selectedRegionColor
                                    : withAlpha(DIM_REGION_COLOR, 80),
                            component.principal() ? regionStrokeWidth
                                    : Math.max(0.8f,
                                            regionStrokeWidth - 0.25f),
                            component.principal());
                }
                return;
            }
            drawSegments(canvas, paths.tissueContourSegments(), mapping, true,
                    selectedRegionColor, regionStrokeWidth, true);
        }
    }

    private void drawReviewedBoundaryOnTissue(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        final RenderPaths paths = renderPaths;
        if (paths == null || paths.reviewedBoundarySegments().isEmpty()) {
            return;
        }
        drawSegments(canvas, paths.reviewedBoundarySegments(), mapping, true,
                REVIEWED_BOUNDARY_COLOR, REVIEWED_BOUNDARY_STROKE_WIDTH, true);
    }

    private void drawSelectedRegionOutlineOnAtlas(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        final RenderPaths paths = renderPaths;
        if (paths == null || paths.atlasContourSegments().isEmpty()) {
            return;
        }
        drawSegments(canvas, paths.atlasContourSegments(), mapping, false,
                selectedRegionColor, regionStrokeWidth, true);
    }

    private void drawSegments(
            final Graphics2D canvas,
            final List<LineSegment> segments,
            final ScreenMapping mapping,
            final boolean applyLivePreview,
            final Color color,
            final float strokeWidth,
            final boolean drawHalo) {
        final Object previousAntialiasing = canvas.getRenderingHint(
                RenderingHints.KEY_ANTIALIASING);
        final java.awt.Stroke previousStroke = canvas.getStroke();
        final Color previousColor = canvas.getColor();
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        if (drawHalo) {
            canvas.setStroke(new BasicStroke(Math.max(strokeWidth + 1.0f, 2.1f),
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            canvas.setColor(new Color(0, 0, 0, 180));
            for (final LineSegment segment : segments) {
                final Point2D first = applyLivePreview
                        ? livePreviewPoint(segment.first()) : segment.first();
                final Point2D second = applyLivePreview
                        ? livePreviewPoint(segment.second()) : segment.second();
                final Point2D mappedFirst = mapping.previewToScreen(first);
                final Point2D mappedSecond = mapping.previewToScreen(second);
                canvas.drawLine(rounded(mappedFirst.x()), rounded(mappedFirst.y()),
                        rounded(mappedSecond.x()), rounded(mappedSecond.y()));
            }
        }
        canvas.setStroke(new BasicStroke(strokeWidth,
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        canvas.setColor(color);
        for (final LineSegment segment : segments) {
            final Point2D first = applyLivePreview
                    ? livePreviewPoint(segment.first()) : segment.first();
            final Point2D second = applyLivePreview
                    ? livePreviewPoint(segment.second()) : segment.second();
            final Point2D mappedFirst = mapping.previewToScreen(first);
            final Point2D mappedSecond = mapping.previewToScreen(second);
            canvas.drawLine(rounded(mappedFirst.x()), rounded(mappedFirst.y()),
                    rounded(mappedSecond.x()), rounded(mappedSecond.y()));
        }
        canvas.setStroke(previousStroke);
        canvas.setColor(previousColor);
        if (previousAntialiasing != null) {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    previousAntialiasing);
        }
    }

    /**
     * Draws the selected raster boundary as connected, constant-screen-width
     * line work. Rendering the target after the scaled overlay image avoids
     * the old zoom-dependent 5-pixel dots that merged into an opaque halo.
     */
    static void drawSelectedRegionOutline(
            final Graphics2D canvas,
            final SelectedAtlasContour contour,
            final Function<Point2D, Point2D> atlasToScreen) {
        Objects.requireNonNull(canvas, "canvas");
        Objects.requireNonNull(contour, "contour");
        Objects.requireNonNull(atlasToScreen, "atlasToScreen");
        if (!contour.isPresent()) {
            return;
        }
        final Object previousAntialiasing = canvas.getRenderingHint(
                RenderingHints.KEY_ANTIALIASING);
        final java.awt.Stroke previousStroke = canvas.getStroke();
        final Color previousColor = canvas.getColor();
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        canvas.setStroke(new BasicStroke(SELECTED_REGION_STROKE_WIDTH,
                BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        canvas.setColor(SELECTED_REGION_COLOR);
        for (final Point2D point : contour.boundaryPoints()) {
            final int x = rounded(point.x());
            final int y = rounded(point.y());
            boolean connected = false;
            if (contour.isBoundary(x + 1, y)) {
                drawBoundarySegment(canvas, atlasToScreen,
                        point, new Point2D(x + 1, y));
                connected = true;
            }
            if (contour.isBoundary(x, y + 1)) {
                drawBoundarySegment(canvas, atlasToScreen,
                        point, new Point2D(x, y + 1));
                connected = true;
            }
            if (!contour.isBoundary(x + 1, y)
                    && !contour.isBoundary(x, y + 1)
                    && contour.isBoundary(x + 1, y + 1)) {
                drawBoundarySegment(canvas, atlasToScreen,
                        point, new Point2D(x + 1, y + 1));
                connected = true;
            }
            if (!contour.isBoundary(x - 1, y)
                    && !contour.isBoundary(x, y + 1)
                    && contour.isBoundary(x - 1, y + 1)) {
                drawBoundarySegment(canvas, atlasToScreen,
                        point, new Point2D(x - 1, y + 1));
                connected = true;
            }
            if (!connected) {
                final Point2D screen = atlasToScreen.apply(point);
                canvas.fillOval(rounded(screen.x()), rounded(screen.y()),
                        1, 1);
            }
        }
        canvas.setStroke(previousStroke);
        canvas.setColor(previousColor);
        if (previousAntialiasing != null) {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    previousAntialiasing);
        }
    }

    private static void drawBoundarySegment(
            final Graphics2D canvas,
            final Function<Point2D, Point2D> atlasToScreen,
            final Point2D first,
            final Point2D second) {
        final Point2D mappedFirst = atlasToScreen.apply(first);
        final Point2D mappedSecond = atlasToScreen.apply(second);
        canvas.drawLine(rounded(mappedFirst.x()), rounded(mappedFirst.y()),
                rounded(mappedSecond.x()), rounded(mappedSecond.y()));
    }

    private static boolean isBoundary(
            final int[] labels,
            final int width,
            final int height,
            final int x,
            final int y) {
        return isBoundary(labels, width, height, x, y,
                ignored -> true);
    }

    private static boolean isBoundary(
            final int[] labels,
            final int width,
            final int height,
            final int x,
            final int y,
            final java.util.function.IntPredicate includedX) {
        final int value = labels[y * width + x];
        return value != 0
                && (x > 0 && includedX.test(x - 1)
                && labels[y * width + x - 1] != value
                || x + 1 < width
                && includedX.test(x + 1)
                && labels[y * width + x + 1] != value
                || y > 0
                && labels[(y - 1) * width + x] != value
                || y + 1 < height
                && labels[(y + 1) * width + x] != value);
    }

    private static int rounded(final double value) {
        return (int) Math.round(value);
    }

    private static double distance(
            final Point2D first,
            final Point second) {
        return Math.hypot(first.x() - second.x,
                first.y() - second.y);
    }

    private static double distance(
            final Point2D first,
            final Point2D second) {
        return Math.hypot(first.x() - second.x(),
                first.y() - second.y());
    }

    private static Point2D midpoint(
            final Point2D first,
            final Point2D second) {
        return new Point2D((first.x() + second.x()) * 0.5,
                (first.y() + second.y()) * 0.5);
    }

    private static double angle(
            final Point2D center,
            final Point2D point) {
        return Math.atan2(point.y() - center.y(),
                point.x() - center.x());
    }

    private static double normalizedAngle(final double value) {
        return Math.atan2(Math.sin(value), Math.cos(value));
    }

    private static boolean nonzero(final Point2D point) {
        return point != null
                && (Math.abs(point.x()) > 1e-12
                || Math.abs(point.y()) > 1e-12);
    }

    public enum PlacementTool { ALL, MOVE, ROTATE, SCALE }

    public enum InteractionTool {
        TRANSFORM,
        BORDER,
        LANDMARKS,
        POINTS,
        PAN
    }

    /**
     * Controller boundary for one committed edit per completed gesture. All
     * values use preview pixel-center coordinates except atlas landmark points,
     * which use atlas-plane pixel-center coordinates.
     */
    public interface InteractionListener {

        default void translate(final double dx, final double dy) { }

        default void scaleUniform(
                final double factor,
                final Point2D pivot) { }

        default void scaleAxes(
                final double scaleX,
                final double scaleY,
                final double axisRadians,
                final Point2D pivot) { }

        default void rotateRadians(
                final double radians,
                final Point2D pivot) { }

        /** Read-only guard used to retain the last safe live translation. */
        default boolean canPreviewTranslate(
                final double dx, final double dy) {
            return true;
        }

        /** Read-only guard used to retain the last safe live scale. */
        default boolean canPreviewScale(
                final double factor, final Point2D pivot) {
            return true;
        }

        default boolean canPreviewScaleAxes(
                final double scaleX,
                final double scaleY,
                final double axisRadians,
                final Point2D pivot) {
            return true;
        }

        /** Read-only guard used to retain the last safe live rotation. */
        default boolean canPreviewRotate(
                final double radians, final Point2D pivot) {
            return true;
        }

        default void moveLandmarkAtlasPoint(
                final String id,
                final Point2D point) { }

        default void moveLandmarkPreviewPoint(
                final String id,
                final Point2D point) { }

        /** One completed tissue-endpoint drag creates one audited edit. */
        default void moveManualWarpControlAndRefit(
                final String id,
                final Point2D point) { }

        /** One structure box gesture installs one audited shared-field edit. */
        default void transformStructureControls(
                final List<String> controlIds,
                final List<Point2D> requestedTargets) { }

        /** Display-only command-side activation inferred from a clicked dot. */
        default void manualWarpSideActivated(
                final ManualHemisphereWarp2D.AtlasSide side) { }

        default void manualWarpPointSelected(final Point2D point) { }

        default void moveTissueSupportControl(
                final String id,
                final Point2D point) { }

        default void insertTissueSupportControl(
                final int componentIndex,
                final int afterVertexIndex,
                final Point2D point) { }

        default void deleteTissueSupportControl(final String id) { }

        /** Display-only feedback while the reviewer draws a crop polygon. */
        default void tissueSupportTraceChanged(final int pointCount) { }

        default void landmarkSelected(final String id) { }

        default void moveBoundaryFitMatch(
                final String id,
                final ReviewController.BoundaryFitEndpoint endpoint,
                final Point2D point) { }

        default void setBoundaryFitTissuePoint(
                final String id,
                final Point2D tissuePoint) { }

        default void clearBoundaryFitAnchor(final String id) { }

        default void boundaryFitMatchSelected(final String id) { }

        default void cancelBoundaryFitForManualTransform() { }

        default void moveBoundaryWarpMatch(
                final String id,
                final ReviewController.BoundaryFitEndpoint endpoint,
                final Point2D point) { }

        default void addBoundaryWarpMatch(
                final Point2D atlasPoint,
                final Point2D tissuePoint) { }

        default void completeBoundaryWarpMatch(
                final String id,
                final Point2D tissuePoint) { }

        default void removeBoundaryWarpMatch(final String id) { }

        default void boundaryWarpMatchSelected(final String id) { }

        default void boundaryWarpSideActivated(
                final ManualHemisphereWarp2D.AtlasSide side) { }
    }

    private enum Pane {
        TISSUE,
        ATLAS,
        NONE
    }

    private enum GestureKind {
        TRANSLATE,
        SCALE,
        ROTATE,
        PAN,
        LANDMARK_ATLAS,
        LANDMARK_PREVIEW,
        MANUAL_WARP,
        STRUCTURE_TRANSLATE,
        STRUCTURE_SCALE,
        STRUCTURE_GAP,
        MANUAL_WARP_POINT,
        TISSUE_SUPPORT,
        BOUNDARY_FIT_ATLAS,
        BOUNDARY_FIT_TISSUE,
        BOUNDARY_WARP_ATLAS,
        BOUNDARY_WARP_TISSUE
    }

    private enum ScaleHandle {
        CORNER,
        HORIZONTAL_EDGE,
        VERTICAL_EDGE
    }

    private static final class Gesture {
        private final GestureKind kind;
        private final Pane pane;
        private final Point2D start;
        private final Point2D pivot;
        private final double startValue;
        private final String landmarkId;
        private final Point2D originalPoint;
        private Pane releasePane;
        private Point2D delta = new Point2D(0, 0);
        private double value = 1;
        private double scaleX = 1;
        private double scaleY = 1;
        private double axisRadians;
        private double startAxisX;
        private double startAxisY;
        private ScaleHandle scaleHandle;
        private Point2D point;
        private List<String> structureControlIds = List.of();
        private List<Point2D> structureOriginalTargets = List.of();
        private List<Integer> structureControlSigns = List.of();
        private Map<String, Integer> structureComponentSigns = Map.of();
        private Point2D structureGapAxis = new Point2D(0, 0);

        private Gesture(
                final GestureKind kind,
                final Pane pane,
                final Point2D start,
                final Point2D pivot,
                final double startValue,
                final String landmarkId,
                final Point2D originalPoint) {
            this.kind = kind;
            this.pane = pane;
            this.start = start;
            this.pivot = pivot;
            this.startValue = startValue;
            this.landmarkId = landmarkId;
            this.originalPoint = originalPoint;
            releasePane = pane;
            point = originalPoint;
        }

        static Gesture translate(final Point2D start) {
            return new Gesture(GestureKind.TRANSLATE, Pane.TISSUE,
                    start, null, 0, null, null);
        }

        static Gesture scale(
                final Point2D pivot,
                final Point2D start,
                final double axisRadians,
                final ScaleHandle scaleHandle) {
            final Gesture gesture = new Gesture(
                    GestureKind.SCALE, Pane.TISSUE, start,
                    pivot, 0, null, null);
            gesture.axisRadians = axisRadians;
            gesture.scaleHandle = Objects.requireNonNull(
                    scaleHandle, "scaleHandle");
            final double cosine = Math.cos(axisRadians);
            final double sine = Math.sin(axisRadians);
            final double dx = start.x() - pivot.x();
            final double dy = start.y() - pivot.y();
            gesture.startAxisX = cosine * dx + sine * dy;
            gesture.startAxisY = -sine * dx + cosine * dy;
            return gesture;
        }

        static Gesture rotate(
                final Point2D pivot,
                final double startAngle) {
            final Gesture gesture = new Gesture(
                    GestureKind.ROTATE, Pane.TISSUE, null,
                    pivot, startAngle, null, null);
            gesture.value = 0;
            return gesture;
        }

        static Gesture pan(final Pane pane) {
            return new Gesture(GestureKind.PAN, pane,
                    null, null, 0, null, null);
        }

        static Gesture landmarkAtlas(
                final String id,
                final Pane pane,
                final Point2D point) {
            return new Gesture(GestureKind.LANDMARK_ATLAS, pane,
                    null, null, 0, id, point);
        }

        static Gesture landmarkPreview(
                final String id,
                final Point2D point) {
            return new Gesture(GestureKind.LANDMARK_PREVIEW, Pane.TISSUE,
                    null, null, 0, id, point);
        }

        static Gesture manualWarp(
                final String id,
                final Point2D point) {
            return new Gesture(GestureKind.MANUAL_WARP, Pane.TISSUE,
                    null, null, 0, id, point);
        }

        static Gesture structureTranslate(
                final Point2D start,
                final List<String> controlIds,
                final List<Point2D> targets) {
            final Gesture gesture = new Gesture(
                    GestureKind.STRUCTURE_TRANSLATE, Pane.TISSUE,
                    start, null, 0, null, null);
            gesture.structureControlIds = List.copyOf(controlIds);
            gesture.structureOriginalTargets = List.copyOf(targets);
            return gesture;
        }

        static Gesture structureScale(
                final Point2D pivot,
                final Point2D start,
                final ScaleHandle scaleHandle,
                final List<String> controlIds,
                final List<Point2D> targets) {
            final Gesture gesture = new Gesture(
                    GestureKind.STRUCTURE_SCALE, Pane.TISSUE,
                    start, pivot, 0, null, null);
            gesture.axisRadians = 0;
            gesture.scaleHandle = Objects.requireNonNull(
                    scaleHandle, "scaleHandle");
            gesture.startAxisX = start.x() - pivot.x();
            gesture.startAxisY = start.y() - pivot.y();
            gesture.structureControlIds = List.copyOf(controlIds);
            gesture.structureOriginalTargets = List.copyOf(targets);
            return gesture;
        }

        static Gesture structureGap(
                final Point2D start,
                final Point2D axis,
                final List<String> controlIds,
                final List<Point2D> targets,
                final List<Integer> controlSigns,
                final Map<String, Integer> componentSigns) {
            final Gesture gesture = new Gesture(
                    GestureKind.STRUCTURE_GAP, Pane.TISSUE,
                    start, null, 0, null, null);
            gesture.value = 0;
            gesture.structureGapAxis = Objects.requireNonNull(axis, "axis");
            gesture.structureControlIds = List.copyOf(controlIds);
            gesture.structureOriginalTargets = List.copyOf(targets);
            gesture.structureControlSigns = List.copyOf(controlSigns);
            gesture.structureComponentSigns = Map.copyOf(componentSigns);
            if (gesture.structureControlIds.size()
                    != gesture.structureOriginalTargets.size()
                    || gesture.structureControlIds.size()
                    != gesture.structureControlSigns.size()) {
                throw new IllegalArgumentException(
                        "Structure gap controls and component assignments must match");
            }
            return gesture;
        }

        boolean isStructureGroupGesture() {
            return kind == GestureKind.STRUCTURE_TRANSLATE
                    || kind == GestureKind.STRUCTURE_SCALE
                    || kind == GestureKind.STRUCTURE_GAP;
        }

        List<Point2D> transformedStructureTargets() {
            if (!isStructureGroupGesture()) {
                return structureOriginalTargets;
            }
            final List<Point2D> result = new ArrayList<>(
                    structureOriginalTargets.size());
            for (int index = 0; index < structureOriginalTargets.size();
                    index++) {
                final Point2D original = structureOriginalTargets.get(index);
                if (kind == GestureKind.STRUCTURE_TRANSLATE) {
                    result.add(new Point2D(original.x() + delta.x(),
                            original.y() + delta.y()));
                } else if (kind == GestureKind.STRUCTURE_GAP) {
                    result.add(transformStructurePoint(original,
                            structureControlSigns.get(index)));
                } else {
                    result.add(new Point2D(
                            pivot.x() + (original.x() - pivot.x()) * scaleX,
                            pivot.y() + (original.y() - pivot.y()) * scaleY));
                }
            }
            return List.copyOf(result);
        }

        Point2D transformedStructureControlPoint(
                final String controlId,
                final Point2D original) {
            final int index = structureControlIds.indexOf(controlId);
            if (kind == GestureKind.STRUCTURE_GAP && index >= 0) {
                return transformStructurePoint(original,
                        structureControlSigns.get(index));
            }
            return transformStructurePoint(original, 0);
        }

        Point2D transformStructurePoint(
                final Point2D original,
                final int componentSign) {
            if (kind == GestureKind.STRUCTURE_TRANSLATE) {
                return new Point2D(original.x() + delta.x(),
                        original.y() + delta.y());
            }
            if (kind == GestureKind.STRUCTURE_GAP) {
                final double offset = 0.5 * value * componentSign;
                return new Point2D(
                        original.x() + offset * structureGapAxis.x(),
                        original.y() + offset * structureGapAxis.y());
            }
            if (kind == GestureKind.STRUCTURE_SCALE) {
                return new Point2D(
                        pivot.x() + (original.x() - pivot.x()) * scaleX,
                        pivot.y() + (original.y() - pivot.y()) * scaleY);
            }
            return original;
        }

        static Gesture manualWarpPoint(final Point2D point) {
            return new Gesture(GestureKind.MANUAL_WARP_POINT, Pane.TISSUE,
                    null, null, 0, null, point);
        }

        static Gesture tissueSupport(
                final String id,
                final Point2D point) {
            return new Gesture(GestureKind.TISSUE_SUPPORT, Pane.TISSUE,
                    null, null, 0, id, point);
        }

        static Gesture boundaryFitAtlas(
                final String id,
                final Pane pane,
                final Point2D point) {
            return new Gesture(GestureKind.BOUNDARY_FIT_ATLAS,
                    pane, null, null, 0, id, point);
        }

        static Gesture boundaryFitTissue(
                final String id,
                final Point2D point) {
            return new Gesture(GestureKind.BOUNDARY_FIT_TISSUE,
                    Pane.TISSUE, null, null, 0, id, point);
        }

        static Gesture boundaryWarpAtlas(
                final String id,
                final Point2D point) {
            return new Gesture(GestureKind.BOUNDARY_WARP_ATLAS,
                    Pane.TISSUE, null, null, 0, id, point);
        }

        static Gesture boundaryWarpTissue(
                final String id,
                final Point2D point) {
            return new Gesture(GestureKind.BOUNDARY_WARP_TISSUE,
                    Pane.TISSUE, null, null, 0, id, point);
        }
    }

    private record TissueSupportEdge(
            int componentIndex,
            int afterVertexIndex) {
    }

    private record Selection(
            Point2D[] corners,
            Point2D[] edgeMidpoints,
            Point2D center,
            Point2D rotationHandle,
            double axisRadians) {

        Polygon screenPolygon(final ScreenMapping mapping) {
            final Polygon polygon = new Polygon();
            for (final Point2D corner : corners) {
                final Point2D screen = mapping.previewToScreen(corner);
                polygon.addPoint(rounded(screen.x()), rounded(screen.y()));
            }
            return polygon;
        }
    }

    static record PlacementLayer(
            BufferedImage image,
            int originX,
            int originY) {

        PlacementLayer {
            image = Objects.requireNonNull(image, "image");
        }
    }

    private record PlacementLayers(
            PlacementLayer joined,
            PlacementLayer left,
            PlacementLayer right) {
    }

    private record RenderKey(
            long contentRevision,
            int coronalLevel,
            org.atlasalign.application.AtlasPlaneTilt planeTilt,
            org.atlasalign.application.AtlasOrientation orientation,
            ReviewSectionMode reviewSectionMode,
            ObservedAnatomicalHemisphere observedHemisphere,
            HalfAtlasCoverage halfAtlasCoverage,
            ManualHemisphereWarp2D.AtlasSide activeHemisphereSide,
            int manualSidePlacementHash,
            String hemisphereWarpHash,
            String outlineWarpHash,
            String tissueSupportHash,
            String selectionKey,
            String sourceHash,
            String atlasHash,
            int planeWidth,
            int planeHeight,
            int planeIdentity,
            String boundaryWarpDraftHash,
            String structureDraftHash,
            boolean gridVisible,
            boolean tissueClippingEnabled,
            boolean boundaryWarpPreviewVisible,
            boolean exportedRoiPreviewVisible,
            boolean placementPreview,
            boolean outerBoundariesOnly,
            int dimRegionArgb,
            int strokeWidthBits) {
    }

    private record SidePathCacheKey(
            int coronalLevel,
            int planeWidth,
            int planeHeight,
            org.atlasalign.application.AtlasPlaneTilt planeTilt,
            org.atlasalign.application.AtlasOrientation orientation,
            ReviewSectionMode reviewSectionMode,
            String outlineHash,
            String preOutlineHash,
            String postOutlineHash,
            String sidePlacementHash,
            String genericWarpHash,
            ManualHemisphereWarp2D.AtlasSide side,
            List<ManualWarpControl> controls) {

        private SidePathCacheKey {
            planeTilt = Objects.requireNonNull(planeTilt, "planeTilt");
            orientation = Objects.requireNonNull(orientation, "orientation");
            reviewSectionMode = Objects.requireNonNull(
                    reviewSectionMode, "reviewSectionMode");
            outlineHash = Objects.requireNonNull(outlineHash,
                    "outlineHash");
            preOutlineHash = Objects.requireNonNull(preOutlineHash,
                    "preOutlineHash");
            postOutlineHash = Objects.requireNonNull(postOutlineHash,
                    "postOutlineHash");
            sidePlacementHash = Objects.requireNonNull(sidePlacementHash,
                    "sidePlacementHash");
            genericWarpHash = Objects.requireNonNull(genericWarpHash,
                    "genericWarpHash");
            side = Objects.requireNonNull(side, "side");
            controls = List.copyOf(Objects.requireNonNull(controls,
                    "controls"));
        }
    }

    static record LineSegment(Point2D first, Point2D second) {

        LineSegment {
            first = Objects.requireNonNull(first, "first");
            second = Objects.requireNonNull(second, "second");
        }
    }

    private record SidedLineSegment(
            Optional<ManualHemisphereWarp2D.AtlasSide> atlasSide,
            LineSegment segment) {

        private SidedLineSegment {
            atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
            segment = Objects.requireNonNull(segment, "segment");
        }
    }

    private record RenderPaths(
            List<LineSegment> tissueContourSegments,
            List<LineSegment> beforeTissueContourSegments,
            List<LineSegment> atlasContourSegments,
            List<LineSegment> reviewedBoundarySegments,
            List<LineSegment> atlasBoundarySegments,
            List<LineSegment> tissueBoundarySegments,
            List<LineSegment> leftPlacementBoundarySegments,
            List<LineSegment> rightPlacementBoundarySegments,
            List<LineSegment> leftPlacementContourSegments,
            List<LineSegment> rightPlacementContourSegments,
            List<LineSegment> beforeTissueBoundarySegments,
            List<StructureComponentPath> structureComponentPaths,
            List<LineSegment> structureCandidateSegments,
            List<LineSegment> structureRequestedSegments,
            List<LineSegment> gridSegments) {

        private RenderPaths {
            tissueContourSegments = List.copyOf(
                    Objects.requireNonNull(tissueContourSegments,
                            "tissueContourSegments"));
            beforeTissueContourSegments = List.copyOf(
                    Objects.requireNonNull(beforeTissueContourSegments,
                            "beforeTissueContourSegments"));
            atlasContourSegments = List.copyOf(
                    Objects.requireNonNull(atlasContourSegments,
                            "atlasContourSegments"));
            reviewedBoundarySegments = List.copyOf(
                    Objects.requireNonNull(reviewedBoundarySegments,
                            "reviewedBoundarySegments"));
            atlasBoundarySegments = List.copyOf(
                    Objects.requireNonNull(atlasBoundarySegments,
                            "atlasBoundarySegments"));
            tissueBoundarySegments = List.copyOf(
                    Objects.requireNonNull(tissueBoundarySegments,
                            "tissueBoundarySegments"));
            leftPlacementBoundarySegments = List.copyOf(
                    Objects.requireNonNull(leftPlacementBoundarySegments,
                            "leftPlacementBoundarySegments"));
            rightPlacementBoundarySegments = List.copyOf(
                    Objects.requireNonNull(rightPlacementBoundarySegments,
                            "rightPlacementBoundarySegments"));
            leftPlacementContourSegments = List.copyOf(
                    Objects.requireNonNull(leftPlacementContourSegments,
                            "leftPlacementContourSegments"));
            rightPlacementContourSegments = List.copyOf(
                    Objects.requireNonNull(rightPlacementContourSegments,
                            "rightPlacementContourSegments"));
            beforeTissueBoundarySegments = List.copyOf(
                    Objects.requireNonNull(beforeTissueBoundarySegments,
                            "beforeTissueBoundarySegments"));
            structureComponentPaths = List.copyOf(
                    Objects.requireNonNull(structureComponentPaths,
                            "structureComponentPaths"));
            structureCandidateSegments = List.copyOf(
                    Objects.requireNonNull(structureCandidateSegments,
                            "structureCandidateSegments"));
            structureRequestedSegments = List.copyOf(
                    Objects.requireNonNull(structureRequestedSegments,
                            "structureRequestedSegments"));
            gridSegments = List.copyOf(Objects.requireNonNull(
                    gridSegments, "gridSegments"));
        }
    }

    private record StructureComponentPath(
            String id,
            ManualHemisphereWarp2D.AtlasSide side,
            boolean principal,
            double perimeter,
            List<LineSegment> tissueSegments,
            List<Point2D> sourceLoop,
            Point2D tissueCentroid) {

        private StructureComponentPath {
            id = Objects.requireNonNull(id, "id");
            side = Objects.requireNonNull(side, "side");
            tissueSegments = List.copyOf(Objects.requireNonNull(
                    tissueSegments, "tissueSegments"));
            sourceLoop = List.copyOf(Objects.requireNonNull(
                    sourceLoop, "sourceLoop"));
            tissueCentroid = Objects.requireNonNull(
                    tissueCentroid, "tissueCentroid");
            if (id.isBlank() || !Double.isFinite(perimeter)
                    || perimeter <= 0 || tissueSegments.isEmpty()
                    || sourceLoop.size() < 3) {
                throw new IllegalArgumentException(
                        "Structure component render geometry is invalid");
            }
        }
    }

    private record StructureGapGeometry(
            StructureComponentPath first,
            StructureComponentPath second,
            Point2D axis,
            Point2D midpoint,
            List<String> controlIds,
            List<Point2D> controlTargets,
            List<Integer> controlSigns,
            Map<String, Integer> componentSigns) {

        private StructureGapGeometry {
            first = Objects.requireNonNull(first, "first");
            second = Objects.requireNonNull(second, "second");
            axis = Objects.requireNonNull(axis, "axis");
            midpoint = Objects.requireNonNull(midpoint, "midpoint");
            controlIds = List.copyOf(Objects.requireNonNull(
                    controlIds, "controlIds"));
            controlTargets = List.copyOf(Objects.requireNonNull(
                    controlTargets, "controlTargets"));
            controlSigns = List.copyOf(Objects.requireNonNull(
                    controlSigns, "controlSigns"));
            componentSigns = Map.copyOf(Objects.requireNonNull(
                    componentSigns, "componentSigns"));
        }
    }

    private static RenderPaths emptyRenderPaths() {
        return new RenderPaths(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of());
    }

    public enum ComparisonMode {
        BEFORE,
        AFTER,
        COMPARE
    }

    @FunctionalInterface
    public interface LandmarkCaptureListener {

        /** Returns true only after the pair was recorded successfully. */
        boolean capture(Point2D atlasPoint, Point2D previewPoint);
    }
}
