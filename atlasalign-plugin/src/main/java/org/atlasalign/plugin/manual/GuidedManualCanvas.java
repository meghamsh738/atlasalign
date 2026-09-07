package org.atlasalign.plugin.manual;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.atlasalign.application.manual.ContourSegment;
import org.atlasalign.application.manual.ContourVertex;
import org.atlasalign.application.manual.ManualContour;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.plugin.review.PreviewDisplayWindow;
import org.atlasalign.plugin.review.ReviewPreview;
import org.atlasalign.plugin.review.ScreenMapping;
import org.atlasalign.plugin.review.SelectedAtlasContour;

/**
 * Source-preserving contour editor and neutral atlas-reference browser.
 * Viewport state is deliberately local and never enters scientific history.
 */
public final class GuidedManualCanvas extends JComponent {

    private static final Color ACTIVE_GUIDE_COLOR =
            new Color(255, 24, 205, 245);
    private static final Color ACTIVE_GUIDE_HALO =
            new Color(0, 0, 0, 185);
    private static final float ACTIVE_GUIDE_WIDTH = 1.15f;
    private static final float ACTIVE_GUIDE_HALO_WIDTH = 3.15f;

    private static final int GAP = 12;
    private static final int LABEL_HEIGHT = 24;
    private static final double MAX_ZOOM = 16;
    private static final double HIT_RADIUS = 9;
    private static final int DENSE_AUTOMATIC_VERTEX_THRESHOLD = 64;
    private static final double FULL_VERTEX_HANDLE_ZOOM = 2.0;
    /** Small trackpad/wheel steps; toolbar zoom remains deliberately coarser. */
    private static final double ZOOM_FACTOR = 1.15;

    private final ReviewPreview preview;
    private final PreviewMapping sourceToPreview;
    private final BufferedImage tissueImage;
    private Listener listener = Listener.NOOP;
    private Map<String, ManualContour> contours = Map.of();
    private Optional<String> activeContourId = Optional.empty();
    private Optional<AtlasCoronalPlane> atlasPlane = Optional.empty();
    private Optional<SelectedAtlasContour> selectedAtlasContour =
            Optional.empty();
    private List<Point2D> neutralGuidePreviewPoints = List.of();
    private Optional<ManualOutlineWarpPreview> outlineWarpPreview =
            Optional.empty();
    private Optional<ManualCandidateMatch> candidateOverlay = Optional.empty();
    private Optional<String> editableOutlineAnchorName = Optional.empty();
    private List<Point2D> warpedAtlasBoundaryPreviewPoints = List.of();
    private Object warpedBoundaryOwner;
    private boolean drawingEnabled;
    private boolean panTool;
    private boolean gapTool;
    private boolean spaceDown;
    private double tissueZoom = 1;
    private double tissuePanX;
    private double tissuePanY;
    private double atlasZoom = 1;
    private double atlasPanX;
    private double atlasPanY;
    private Point panOrigin;
    private Point lastPan;
    private DraggedVertex draggedVertex;

    public GuidedManualCanvas(
            final ReviewPreview preview,
            final PreviewMapping sourceToPreview) {
        this.preview = Objects.requireNonNull(preview, "preview");
        this.sourceToPreview = Objects.requireNonNull(
                sourceToPreview, "sourceToPreview");
        if (sourceToPreview.previewWidth() != preview.width()
                || sourceToPreview.previewHeight() != preview.height()) {
            throw new IllegalArgumentException(
                    "Preview mapping must match the copied review preview");
        }
        tissueImage = previewImage(preview);
        setOpaque(true);
        setBackground(Color.BLACK);
        setFocusable(true);
        setPreferredSize(new Dimension(900, 620));
        setToolTipText("Draw/edit: click to add, drag a vertex, Shift-click a segment to insert, or right-click a vertex to delete. Mark gap: click a tear or non-matching fissure segment. Mouse wheel zooms; Space-drag or middle-drag pans.");
        final MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(final MouseEvent event) {
                handlePress(event);
            }
            @Override public void mouseDragged(final MouseEvent event) {
                handleDrag(event);
            }
            @Override public void mouseReleased(final MouseEvent event) {
                handleRelease(event);
            }
            @Override public void mouseWheelMoved(final MouseWheelEvent event) {
                handleWheel(event);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        bindKey(KeyEvent.VK_SPACE, false, "space-down", () -> spaceDown = true);
        bindKey(KeyEvent.VK_SPACE, true, "space-up", () -> spaceDown = false);
        bindKey(KeyEvent.VK_ESCAPE, false, "cancel-drag", () -> {
            draggedVertex = null;
            panOrigin = null;
            lastPan = null;
            repaint();
        });
    }

    public void setListener(final Listener value) {
        listener = Objects.requireNonNull(value, "value");
    }

    /** Activates click-nearest-segment missing-evidence marking. */
    public void setGapTool(final boolean value) {
        gapTool = value;
    }

    public void setContours(
            final Map<String, ManualContour> value,
            final Optional<String> activeId) {
        contours = Map.copyOf(Objects.requireNonNull(value, "value"));
        activeContourId = Objects.requireNonNull(activeId, "activeId");
        repaint();
    }

    public void setAtlasReference(
            final Optional<AtlasCoronalPlane> plane,
            final Optional<SelectedAtlasContour> selected) {
        atlasPlane = Objects.requireNonNull(plane, "plane");
        selectedAtlasContour = Objects.requireNonNull(selected, "selected");
        if (selected.isPresent() && plane.isEmpty()) {
            throw new IllegalArgumentException(
                    "A selected atlas contour requires its copied plane");
        }
        repaint();
    }

    /**
     * Shows a display-only, pre-outline guide over the tissue copy. The
     * caller supplies points already mapped into preview-pixel space so this
     * viewport state cannot enter review history or scientific transforms.
     */
    public void setNeutralGuidePreviewPoints(final List<Point2D> points) {
        neutralGuidePreviewPoints = List.copyOf(
                Objects.requireNonNull(points, "points"));
        repaint();
    }

    int neutralGuidePreviewPointCount() {
        return neutralGuidePreviewPoints.size();
    }

    /** Shows one exploratory atlas candidate over the read-only tissue copy. */
    public void setCandidateOverlay(
            final Optional<ManualCandidateMatch> candidate) {
        candidateOverlay = Objects.requireNonNull(candidate, "candidate");
        refreshWarpedBoundaryCache();
        repaint();
    }

    /**
     * Shows the provisional full-outline deformation before a DG or other
     * anatomical trace has been drawn. This remains display-only.
     */
    public void setOutlineWarpPreview(
            final Optional<ManualOutlineWarpPreview> previewValue) {
        outlineWarpPreview = Objects.requireNonNull(
                previewValue, "previewValue");
        refreshWarpedBoundaryCache();
        repaint();
    }

    /**
     * Enables correction of one semantic D/R/V/L correspondence. A tissue
     * click snaps to the immutable resampled tissue loop; an atlas click
     * snaps to the ordered verified-atlas exterior loop. This is transient
     * candidate context and never edits the captured contour or atlas plane.
     */
    public void setEditableOutlineAnchor(
            final Optional<String> anchorName) {
        editableOutlineAnchorName = Objects.requireNonNull(
                anchorName, "anchorName");
        repaint();
    }

    public void setDrawingEnabled(final boolean value) {
        drawingEnabled = value;
    }

    public void setPanTool(final boolean value) {
        panTool = value;
        draggedVertex = null;
    }

    public void fitTissue() {
        tissueZoom = 1;
        tissuePanX = 0;
        tissuePanY = 0;
        repaint();
    }

    public void fitAtlas() {
        atlasZoom = 1;
        atlasPanX = 0;
        atlasPanY = 0;
        repaint();
    }

    public void fitBoth() {
        fitTissue();
        fitAtlas();
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
        super.paintComponent(graphics);
        final Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            final int paneWidth = paneWidth();
            drawTissue(canvas, paneWidth);
            drawAtlas(canvas, paneWidth + GAP, paneWidth);
            drawLabel(canvas, candidateOverlay.isPresent()
                            ? "Tissue — candidate warp + manual contours"
                            : outlineWarpPreview.isPresent()
                                    ? "Tissue — outline-warped atlas + contours"
                                    : !neutralGuidePreviewPoints.isEmpty()
                                            ? "Tissue — display-only guide + manual contours"
                                    : "Tissue — manual contours",
                    0, paneWidth);
            drawLabel(canvas, candidateOverlay.isPresent()
                            ? "Unwarped atlas reference + candidate anchors"
                            : outlineWarpPreview.isPresent()
                                    ? "Unwarped atlas reference"
                                    : "Neutral atlas reference — no ranking",
                    paneWidth + GAP, paneWidth);
        } finally {
            canvas.dispose();
        }
    }

    private void drawTissue(final Graphics2D canvas, final int paneWidth) {
        final ScreenMapping mapping = tissueMapping();
        final AffineTransform transform = imageToScreen(mapping);
        canvas.drawImage(tissueImage, transform, null);
        canvas.setClip(0, LABEL_HEIGHT, paneWidth,
                Math.max(1, getHeight() - LABEL_HEIGHT));
        candidateOverlay.ifPresent(candidate ->
                drawCandidateOverlay(canvas, mapping, candidate));
        if (candidateOverlay.isEmpty()) {
            outlineWarpPreview.ifPresent(previewValue ->
                    drawOutlineWarpPreview(canvas, mapping, previewValue));
        }
        if (candidateOverlay.isEmpty() && outlineWarpPreview.isEmpty()
                && !neutralGuidePreviewPoints.isEmpty()) {
            drawPreviewContour(canvas, mapping,
                    neutralGuidePreviewPoints,
                    new Color(0, 235, 235, 185), 1.6f);
        }
        for (final ManualContour contour : contours.values()) {
            drawManualContour(canvas, mapping, contour,
                    activeContourId.equals(Optional.of(contour.id())));
        }
        canvas.setClip(null);
    }

    private void drawCandidateOverlay(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final ManualCandidateMatch candidate) {
        drawWarpedAtlasBoundaries(canvas, mapping);
        drawCandidateContour(canvas, mapping, candidate,
                candidate.rootOverlayPoints(),
                new Color(190, 85, 255, 175), 1.2f);
        drawCandidateContour(canvas, mapping, candidate,
                candidate.guideOverlayPoints(),
                ACTIVE_GUIDE_COLOR, ACTIVE_GUIDE_WIDTH);
        drawOutlineWarpAnchorsOnTissue(canvas, mapping, candidate);
    }

    private void drawOutlineWarpPreview(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final ManualOutlineWarpPreview previewValue) {
        drawWarpedAtlasBoundaries(canvas, mapping);
        drawPreviewPaths(canvas, mapping,
                previewValue.mappedRootPreviewPaths(),
                new Color(190, 85, 255, 175), 1.2f);
        drawHighlightedPreviewPaths(canvas, mapping,
                previewValue.mappedGuidePreviewPaths());
    }

    private void drawWarpedAtlasBoundaries(
            final Graphics2D canvas,
            final ScreenMapping mapping) {
        canvas.setColor(new Color(105, 165, 255, 150));
        for (final Point2D previewPoint : warpedAtlasBoundaryPreviewPoints) {
            final Point2D screen = mapping.previewToScreen(previewPoint);
            canvas.fillRect((int) Math.round(screen.x()),
                    (int) Math.round(screen.y()), 1, 1);
        }
    }

    private void refreshWarpedBoundaryCache() {
        if (candidateOverlay.isPresent()) {
            final ManualCandidateMatch candidate = candidateOverlay
                    .orElseThrow();
            if (warpedBoundaryOwner == candidate) {
                return;
            }
            warpedAtlasBoundaryPreviewPoints = warpedBoundaryPoints(
                    candidate.plane(), candidate::mapAtlasToPreview);
            warpedBoundaryOwner = candidate;
            return;
        }
        if (outlineWarpPreview.isPresent()) {
            final ManualOutlineWarpPreview previewValue = outlineWarpPreview
                    .orElseThrow();
            if (warpedBoundaryOwner == previewValue) {
                return;
            }
            warpedAtlasBoundaryPreviewPoints = warpedBoundaryPoints(
                    previewValue.plane(), previewValue::mapAtlasToPreview);
            warpedBoundaryOwner = previewValue;
            return;
        }
        warpedAtlasBoundaryPreviewPoints = List.of();
        warpedBoundaryOwner = null;
    }

    private static List<Point2D> warpedBoundaryPoints(
            final AtlasCoronalPlane plane,
            final Function<Point2D, Point2D> mapping) {
        final int[] labels = plane.annotationId();
        final java.util.ArrayList<Point2D> result = new java.util.ArrayList<>();
        for (int y = 0; y < plane.height(); y++) {
            for (int x = 0; x < plane.width(); x++) {
                if (isBoundary(labels, plane.width(), plane.height(), x, y)) {
                    result.add(mapping.apply(new Point2D(x, y)));
                }
            }
        }
        return List.copyOf(result);
    }

    private void drawPreviewContour(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final List<Point2D> previewPoints,
            final Color color,
            final float width) {
        canvas.setColor(color);
        canvas.setStroke(new BasicStroke(width));
        for (final Point2D previewPoint : previewPoints) {
            final Point2D screen = mapping.previewToScreen(previewPoint);
            canvas.fillOval((int) Math.round(screen.x()) - 1,
                    (int) Math.round(screen.y()) - 1, 3, 3);
        }
    }

    private void drawPreviewPaths(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final List<List<Point2D>> paths,
            final Color color,
            final float width) {
        canvas.setColor(color);
        canvas.setStroke(new BasicStroke(width));
        for (final List<Point2D> path : paths) {
            for (int index = 0; index < path.size(); index++) {
                final Point2D first = mapping.previewToScreen(path.get(index));
                final Point2D second = mapping.previewToScreen(
                        path.get((index + 1) % path.size()));
                canvas.drawLine((int) Math.round(first.x()),
                        (int) Math.round(first.y()),
                        (int) Math.round(second.x()),
                        (int) Math.round(second.y()));
            }
        }
    }

    /**
     * Keeps the active anatomy readable over either dark or bright tissue.
     * Both strokes are screen-space widths, so zooming never turns the guide
     * into the opaque filled band that previously obscured the DG layers.
     */
    private void drawHighlightedPreviewPaths(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final List<List<Point2D>> paths) {
        drawPreviewPaths(canvas, mapping, paths,
                ACTIVE_GUIDE_HALO, ACTIVE_GUIDE_HALO_WIDTH);
        drawPreviewPaths(canvas, mapping, paths,
                ACTIVE_GUIDE_COLOR, ACTIVE_GUIDE_WIDTH);
    }

    private void drawOutlineWarpAnchorsOnTissue(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final ManualCandidateMatch candidate) {
        candidate.outlineWarp().ifPresent(warp -> {
            for (int index = 0; index < warp.anchors().size(); index++) {
                final var anchor = warp.anchors().get(index);
                final Point2D sourcePreview = warp.atlasLoop().get(
                        anchor.atlasVertexIndex());
                final Point2D targetPreview = warp.tissueLoop().get(
                        anchor.tissueVertexIndex());
                final Point2D source = sourcePointToScreen(mapping,
                        sourceToPreview.previewToSource(sourcePreview));
                final Point2D target = sourcePointToScreen(mapping,
                        sourceToPreview.previewToSource(targetPreview));
                final Color color = outlineAnchorColor(index);
                canvas.setColor(new Color(
                        color.getRed(), color.getGreen(), color.getBlue(), 135));
                canvas.setStroke(new BasicStroke(
                        1.2f, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND, 1, new float[] {4, 4}, 0));
                canvas.drawLine((int) Math.round(source.x()),
                        (int) Math.round(source.y()),
                        (int) Math.round(target.x()),
                        (int) Math.round(target.y()));
                canvas.setColor(color);
                canvas.fillOval((int) Math.round(target.x()) - 5,
                        (int) Math.round(target.y()) - 5, 10, 10);
                if (editableOutlineAnchorName.equals(
                        Optional.of(anchor.name()))) {
                    canvas.drawOval((int) Math.round(target.x()) - 8,
                            (int) Math.round(target.y()) - 8, 16, 16);
                }
                canvas.setColor(Color.BLACK);
                canvas.drawOval((int) Math.round(target.x()) - 5,
                        (int) Math.round(target.y()) - 5, 10, 10);
                canvas.setColor(color);
                canvas.drawString(anchorLabel(anchor.name()),
                        (int) Math.round(target.x()) + 7,
                        (int) Math.round(target.y()) - 7);
            }
        });
    }

    private void drawOutlineAnchorPair(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final Point2D sourcePreview,
            final Point2D targetPreview,
            final String name,
            final int index) {
        final Point2D source = mapping.previewToScreen(sourcePreview);
        final Point2D target = mapping.previewToScreen(targetPreview);
        final Color color = outlineAnchorColor(index);
        canvas.setColor(new Color(
                color.getRed(), color.getGreen(), color.getBlue(), 135));
        canvas.setStroke(new BasicStroke(
                1.2f, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND, 1, new float[] {4, 4}, 0));
        canvas.drawLine((int) Math.round(source.x()),
                (int) Math.round(source.y()),
                (int) Math.round(target.x()),
                (int) Math.round(target.y()));
        canvas.setColor(color);
        canvas.fillOval((int) Math.round(target.x()) - 5,
                (int) Math.round(target.y()) - 5, 10, 10);
        if (editableOutlineAnchorName.equals(Optional.of(name))) {
            canvas.drawOval((int) Math.round(target.x()) - 8,
                    (int) Math.round(target.y()) - 8, 16, 16);
        }
        canvas.setColor(Color.BLACK);
        canvas.drawOval((int) Math.round(target.x()) - 5,
                (int) Math.round(target.y()) - 5, 10, 10);
        canvas.setColor(color);
        canvas.drawString(anchorLabel(name),
                (int) Math.round(target.x()) + 7,
                (int) Math.round(target.y()) - 7);
    }

    private void drawCandidateContour(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final ManualCandidateMatch candidate,
            final List<Point2D> atlasPoints,
            final Color color,
            final float width) {
        canvas.setColor(color);
        canvas.setStroke(new BasicStroke(width));
        for (final Point2D atlasPoint : atlasPoints) {
            final Point2D source = candidate.mapAtlasToSource(atlasPoint);
            final Point2D screen = sourcePointToScreen(mapping, source);
            canvas.fillOval((int) Math.round(screen.x()) - 1,
                    (int) Math.round(screen.y()) - 1, 3, 3);
        }
    }

    private void drawAtlas(
            final Graphics2D canvas,
            final int paneX,
            final int paneWidth) {
        canvas.setColor(new Color(7, 7, 9));
        canvas.fillRect(paneX, LABEL_HEIGHT, paneWidth,
                Math.max(1, getHeight() - LABEL_HEIGHT));
        if (atlasPlane.isEmpty()) {
            canvas.setColor(Color.LIGHT_GRAY);
            canvas.drawString("Atlas plane is loading or unavailable",
                    paneX + 18, LABEL_HEIGHT + 30);
            return;
        }
        final AtlasCoronalPlane plane = atlasPlane.orElseThrow();
        final ScreenMapping mapping = atlasMapping(plane);
        final int[] labels = plane.annotationId();
        canvas.setClip(paneX, LABEL_HEIGHT, paneWidth,
                Math.max(1, getHeight() - LABEL_HEIGHT));
        canvas.setStroke(new BasicStroke(1));
        canvas.setColor(new Color(130, 130, 140, 150));
        for (int y = 0; y < plane.height(); y++) {
            for (int x = 0; x < plane.width(); x++) {
                if (!isBoundary(labels, plane.width(), plane.height(), x, y)) {
                    continue;
                }
                final Point2D screen = mapping.previewToScreen(
                        new Point2D(x, y));
                canvas.fillRect((int) Math.round(screen.x()),
                        (int) Math.round(screen.y()), 1, 1);
            }
        }
        selectedAtlasContour.ifPresent(contour -> {
            drawHighlightedPreviewPaths(canvas, mapping,
                    contour.orderedExteriorLoops());
        });
        candidateOverlay.ifPresent(candidate ->
                drawOutlineWarpAnchorsOnAtlas(canvas, mapping, candidate));
        canvas.setClip(null);
    }

    private void drawOutlineWarpAnchorsOnAtlas(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final ManualCandidateMatch candidate) {
        candidate.outlineWarp().ifPresent(warp -> {
            for (int index = 0; index < warp.anchors().size(); index++) {
                final var anchor = warp.anchors().get(index);
                final Point2D atlasPoint = candidate.atlasToPreviewAffine()
                        .inverse().apply(warp.atlasLoop().get(
                                anchor.atlasVertexIndex()));
                final Point2D screen = mapping.previewToScreen(
                        atlasPoint);
                drawAtlasAnchor(canvas, screen, anchor.name(), index);
            }
        });
    }

    private void drawAtlasAnchor(
            final Graphics2D canvas,
            final Point2D screen,
            final String name,
            final int index) {
        final Color color = outlineAnchorColor(index);
        canvas.setColor(color);
        canvas.fillOval((int) Math.round(screen.x()) - 5,
                (int) Math.round(screen.y()) - 5, 10, 10);
        if (editableOutlineAnchorName.equals(Optional.of(name))) {
            canvas.drawOval((int) Math.round(screen.x()) - 8,
                    (int) Math.round(screen.y()) - 8, 16, 16);
        }
        canvas.setColor(Color.BLACK);
        canvas.drawOval((int) Math.round(screen.x()) - 5,
                (int) Math.round(screen.y()) - 5, 10, 10);
        canvas.setColor(color);
        canvas.drawString(anchorLabel(name),
                (int) Math.round(screen.x()) + 7,
                (int) Math.round(screen.y()) - 7);
    }

    private static Color outlineAnchorColor(final int index) {
        return switch (Math.floorMod(index, 4)) {
            case 0 -> new Color(255, 90, 90);
            case 1 -> new Color(255, 190, 30);
            case 2 -> new Color(100, 220, 255);
            default -> new Color(190, 120, 255);
        };
    }

    private static String anchorLabel(final String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)
                .replace('_', '-')) {
            case "dorsal-midline" -> "D";
            case "image-right" -> "R";
            case "ventral-midline" -> "V";
            case "image-left" -> "L";
            default -> name;
        };
    }

    private void drawManualContour(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final ManualContour contour,
            final boolean active) {
        final List<ContourVertex> vertices = contour.vertices();
        final Color base = active
                ? new Color(0, 235, 235) : new Color(245, 190, 30);
        canvas.setStroke(new BasicStroke(active ? 2.5f : 1.5f));
        for (int index = 0; index + 1 < vertices.size(); index++) {
            drawSegment(canvas, mapping, contour,
                    vertices.get(index), vertices.get(index + 1), base);
        }
        if (contour.topology()
                == org.atlasalign.application.manual.ContourTopology.CLOSED
                && vertices.size() > 2) {
            drawSegment(canvas, mapping, contour,
                    vertices.get(vertices.size() - 1), vertices.get(0), base);
        }
        for (final ContourVertex vertex : vertices) {
            final Point2D screen = vertexToScreen(mapping, contour, vertex);
            if (usesCompactVertexHandles(contour)
                    && !isDraggedVertex(contour, vertex)) {
                canvas.setColor(base);
                canvas.fillOval((int) Math.round(screen.x()) - 1,
                        (int) Math.round(screen.y()) - 1, 3, 3);
            } else {
                canvas.setColor(Color.BLACK);
                canvas.fillOval((int) Math.round(screen.x()) - 5,
                        (int) Math.round(screen.y()) - 5, 11, 11);
                canvas.setColor(base);
                canvas.drawOval((int) Math.round(screen.x()) - 4,
                        (int) Math.round(screen.y()) - 4, 8, 8);
            }
        }
    }

    boolean usesCompactVertexHandles(final ManualContour contour) {
        return usesCompactVertexHandles(
                contour.automaticProposalProvenance().isPresent(),
                contour.vertices().size(), tissueZoom);
    }

    static boolean usesCompactVertexHandles(
            final boolean automaticProposal,
            final int vertexCount,
            final double zoom) {
        return automaticProposal
                && vertexCount > DENSE_AUTOMATIC_VERTEX_THRESHOLD
                && zoom < FULL_VERTEX_HANDLE_ZOOM;
    }

    private boolean isDraggedVertex(
            final ManualContour contour,
            final ContourVertex vertex) {
        return draggedVertex != null
                && draggedVertex.contourId().equals(contour.id())
                && draggedVertex.vertexId().equals(vertex.id());
    }

    private void drawSegment(
            final Graphics2D canvas,
            final ScreenMapping mapping,
            final ManualContour contour,
            final ContourVertex from,
            final ContourVertex to,
            final Color base) {
        final boolean gap = contour.excludedGapSegments().contains(
                new ContourSegment(from.id(), to.id()));
        canvas.setColor(gap ? new Color(255, 75, 75) : base);
        final Point2D first = vertexToScreen(mapping, contour, from);
        final Point2D second = vertexToScreen(mapping, contour, to);
        canvas.setStroke(gap
                ? new BasicStroke(2, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER, 10, new float[]{5, 5}, 0)
                : new BasicStroke(2));
        canvas.drawLine((int) Math.round(first.x()),
                (int) Math.round(first.y()),
                (int) Math.round(second.x()),
                (int) Math.round(second.y()));
    }

    private void handlePress(final MouseEvent event) {
        requestFocusInWindow();
        if (isPanGesture(event)) {
            panOrigin = event.getPoint();
            lastPan = event.getPoint();
            return;
        }
        if (handleOutlineAnchorPress(event)) {
            return;
        }
        if (gapTool && isTissuePane(event.getPoint())
                && SwingUtilities.isLeftMouseButton(event)
                && activeContourId.isPresent()) {
            final ManualContour contour = activeContour().orElse(null);
            if (contour != null) {
                nearestSegment(contour, tissueMapping(), event.getPoint())
                        .ifPresent(segment -> listener.toggleGap(
                                contour.id(), segment));
            }
            return;
        }
        if (!drawingEnabled || !isTissuePane(event.getPoint())
                || activeContourId.isEmpty()) {
            return;
        }
        final ManualContour contour = activeContour().orElse(null);
        final ScreenMapping mapping = tissueMapping();
        final Optional<ContourVertex> hit = contour == null
                ? Optional.empty()
                : nearestVertex(contour, mapping, event.getPoint());
        if (SwingUtilities.isRightMouseButton(event)) {
            if (contour != null) {
                hit.ifPresent(vertex -> listener.deleteVertex(
                        contour.id(), vertex.id()));
            }
            return;
        }
        if (!SwingUtilities.isLeftMouseButton(event)) {
            return;
        }
        if (hit.isPresent()) {
            draggedVertex = new DraggedVertex(
                    contour.id(), hit.orElseThrow().id(),
                    event.getPoint(), event.getPoint());
            return;
        }
        final Point2D source = screenToSource(mapping, event.getPoint());
        if (!insideSource(source)) {
            return;
        }
        if (event.isShiftDown() && contour != null) {
            nearestSegment(contour, mapping, event.getPoint()).ifPresent(
                    segment -> listener.insertAfter(contour.id(),
                            segment.fromVertexId(), source));
        } else {
            listener.addVertex(activeContourId.orElseThrow(), source);
        }
    }

    private boolean handleOutlineAnchorPress(final MouseEvent event) {
        if (editableOutlineAnchorName.isEmpty()
                || !SwingUtilities.isLeftMouseButton(event)
                || event.getY() < LABEL_HEIGHT) {
            return false;
        }
        final Optional<org.atlasalign.application.manual.ManualOutlineWarp2D>
                warp = candidateOverlay.flatMap(
                        ManualCandidateMatch::outlineWarp);
        if (warp.isEmpty()) {
            return false;
        }
        final boolean tissueEndpoint = isTissuePane(event.getPoint());
        final boolean atlasEndpoint = event.getX() >= paneWidth() + GAP
                && event.getX() < getWidth();
        if (!tissueEndpoint && !atlasEndpoint) {
            return false;
        }
        final Point2D requestedPreview;
        final List<Point2D> loop;
        if (tissueEndpoint) {
            requestedPreview = tissueMapping().screenToPreview(
                    new Point2D(event.getX(), event.getY()));
            loop = warp.orElseThrow().tissueLoop();
        } else {
            if (atlasPlane.isEmpty()) {
                return false;
            }
            final Point2D atlasPoint = atlasMapping(
                    atlasPlane.orElseThrow()).screenToPreview(
                            new Point2D(event.getX(), event.getY()));
            requestedPreview = activeAtlasToPreviewAffine().apply(atlasPoint);
            loop = warp.orElseThrow().atlasLoop();
        }
        listener.correctOutlineAnchor(
                editableOutlineAnchorName.orElseThrow(), tissueEndpoint,
                nearestLoopIndex(loop, requestedPreview));
        return true;
    }

    private org.atlasalign.core.AffineTransform2D
            activeAtlasToPreviewAffine() {
        if (candidateOverlay.isPresent()) {
            return candidateOverlay.orElseThrow().atlasToPreviewAffine();
        }
        return outlineWarpPreview.orElseThrow().atlasToPreviewAffine();
    }

    private static int nearestLoopIndex(
            final List<Point2D> loop,
            final Point2D requested) {
        if (loop.isEmpty()) {
            throw new IllegalArgumentException(
                    "Outline anchor loop must not be empty");
        }
        int best = 0;
        double bestSquared = Double.POSITIVE_INFINITY;
        for (int index = 0; index < loop.size(); index++) {
            final Point2D point = loop.get(index);
            final double dx = point.x() - requested.x();
            final double dy = point.y() - requested.y();
            final double squared = dx * dx + dy * dy;
            if (squared < bestSquared) {
                best = index;
                bestSquared = squared;
            }
        }
        return best;
    }

    private void handleDrag(final MouseEvent event) {
        if (panOrigin != null && lastPan != null) {
            final double dx = event.getX() - lastPan.x;
            final double dy = event.getY() - lastPan.y;
            if (panOrigin.x < paneWidth()) {
                tissuePanX += dx;
                tissuePanY += dy;
            } else {
                atlasPanX += dx;
                atlasPanY += dy;
            }
            lastPan = event.getPoint();
            repaint();
            return;
        }
        if (draggedVertex != null) {
            draggedVertex = draggedVertex.withLast(event.getPoint());
            repaint();
        }
    }

    private void handleRelease(final MouseEvent event) {
        panOrigin = null;
        lastPan = null;
        if (draggedVertex == null) {
            return;
        }
        final DraggedVertex drag = draggedVertex;
        draggedVertex = null;
        final Point2D source = screenToSource(tissueMapping(), drag.last());
        if (insideSource(source)
                && drag.origin().distance(drag.last()) >= 1) {
            listener.moveVertex(drag.contourId(), drag.vertexId(), source);
        }
        repaint();
    }

    private void handleWheel(final MouseWheelEvent event) {
        final boolean tissue = event.getX() < paneWidth();
        if (!tissue && atlasPlane.isEmpty()) {
            return;
        }
        final double factor = event.getPreciseWheelRotation() < 0
                ? ZOOM_FACTOR : 1 / ZOOM_FACTOR;
        final double oldZoom = tissue ? tissueZoom : atlasZoom;
        final double newZoom = Math.max(1,
                Math.min(MAX_ZOOM, oldZoom * factor));
        if (newZoom == oldZoom) {
            return;
        }
        final ScreenMapping before = tissue
                ? tissueMapping() : atlasMapping(atlasPlane.orElseThrow());
        final Point2D anchor = new Point2D(event.getX(), event.getY());
        final Point2D under = before.screenToPreview(anchor);
        final ScreenMapping fit = tissue
                ? baseTissueMapping() : baseAtlasMapping(atlasPlane.orElseThrow());
        final double offsetX = anchor.x() + 0.5
                - (under.x() + 0.5) * fit.scale() * newZoom;
        final double offsetY = anchor.y() + 0.5
                - (under.y() + 0.5) * fit.scale() * newZoom;
        if (tissue) {
            tissueZoom = newZoom;
            tissuePanX = offsetX - fit.offsetX();
            tissuePanY = offsetY - fit.offsetY();
        } else {
            atlasZoom = newZoom;
            atlasPanX = offsetX - fit.offsetX();
            atlasPanY = offsetY - fit.offsetY();
        }
        repaint();
    }

    private Optional<ContourVertex> nearestVertex(
            final ManualContour contour,
            final ScreenMapping mapping,
            final Point requested) {
        ContourVertex best = null;
        double bestSquared = HIT_RADIUS * HIT_RADIUS;
        for (final ContourVertex vertex : contour.vertices()) {
            final Point2D screen = sourceToScreen(mapping, vertex.point());
            final double dx = requested.x - screen.x();
            final double dy = requested.y - screen.y();
            final double squared = dx * dx + dy * dy;
            if (squared <= bestSquared) {
                best = vertex;
                bestSquared = squared;
            }
        }
        return Optional.ofNullable(best);
    }

    private Point2D vertexToScreen(
            final ScreenMapping mapping,
            final ManualContour contour,
            final ContourVertex vertex) {
        if (draggedVertex != null
                && draggedVertex.contourId().equals(contour.id())
                && draggedVertex.vertexId().equals(vertex.id())) {
            return new Point2D(
                    draggedVertex.last().x, draggedVertex.last().y);
        }
        return sourceToScreen(mapping, vertex.point());
    }

    private Optional<ContourSegment> nearestSegment(
            final ManualContour contour,
            final ScreenMapping mapping,
            final Point requested) {
        ContourSegment best = null;
        double bestDistance = HIT_RADIUS;
        final List<ContourVertex> vertices = contour.vertices();
        final int segments = contour.topology()
                == org.atlasalign.application.manual.ContourTopology.CLOSED
                ? vertices.size() : Math.max(0, vertices.size() - 1);
        for (int index = 0; index < segments; index++) {
            final ContourVertex from = vertices.get(index);
            final ContourVertex to = vertices.get((index + 1) % vertices.size());
            final double distance = pointToSegmentDistance(requested,
                    sourceToScreen(mapping, from.point()),
                    sourceToScreen(mapping, to.point()));
            if (distance <= bestDistance) {
                best = new ContourSegment(from.id(), to.id());
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<ManualContour> activeContour() {
        return activeContourId.map(contours::get).filter(Objects::nonNull);
    }

    private ScreenMapping tissueMapping() {
        return withViewport(baseTissueMapping(), tissueZoom,
                tissuePanX, tissuePanY);
    }

    private ScreenMapping atlasMapping(final AtlasCoronalPlane plane) {
        return withViewport(baseAtlasMapping(plane), atlasZoom,
                atlasPanX, atlasPanY);
    }

    private ScreenMapping baseTissueMapping() {
        return paneFit(preview.width(), preview.height(), 0);
    }

    private ScreenMapping baseAtlasMapping(final AtlasCoronalPlane plane) {
        return paneFit(plane.width(), plane.height(), paneWidth() + GAP);
    }

    private ScreenMapping paneFit(
            final int width,
            final int height,
            final int paneX) {
        final int paneHeight = Math.max(1, getHeight() - LABEL_HEIGHT);
        final ScreenMapping relative = ScreenMapping.fit(
                width, height, paneWidth(), paneHeight);
        return new ScreenMapping(width, height, paneWidth(), paneHeight,
                relative.scale(), paneX + relative.offsetX(),
                LABEL_HEIGHT + relative.offsetY());
    }

    private static ScreenMapping withViewport(
            final ScreenMapping fit,
            final double zoom,
            final double panX,
            final double panY) {
        return new ScreenMapping(fit.previewWidth(), fit.previewHeight(),
                fit.screenWidth(), fit.screenHeight(), fit.scale() * zoom,
                fit.offsetX() + panX, fit.offsetY() + panY);
    }

    private Point2D sourceToScreen(
            final ScreenMapping mapping,
            final org.atlasalign.application.manual.SourcePixelPoint source) {
        return mapping.previewToScreen(sourceToPreview.sourceToPreview(
                new Point2D(source.x(), source.y())));
    }

    private Point2D sourcePointToScreen(
            final ScreenMapping mapping,
            final Point2D source) {
        return mapping.previewToScreen(sourceToPreview.sourceToPreview(source));
    }

    private Point2D screenToSource(
            final ScreenMapping mapping,
            final Point screen) {
        return sourceToPreview.previewToSource(mapping.screenToPreview(
                new Point2D(screen.x, screen.y)));
    }

    private boolean insideSource(final Point2D point) {
        return point.x() >= 0 && point.x() <= sourceToPreview.sourceWidth() - 1.0
                && point.y() >= 0
                && point.y() <= sourceToPreview.sourceHeight() - 1.0;
    }

    private boolean isTissuePane(final Point point) {
        return point.x >= 0 && point.x < paneWidth()
                && point.y >= LABEL_HEIGHT && point.y < getHeight();
    }

    private boolean isPanGesture(final MouseEvent event) {
        return panTool || spaceDown || SwingUtilities.isMiddleMouseButton(event);
    }

    private int paneWidth() {
        return Math.max(1, (Math.max(1, getWidth()) - GAP) / 2);
    }

    private void drawLabel(
            final Graphics2D canvas,
            final String text,
            final int x,
            final int width) {
        canvas.setColor(new Color(26, 26, 30));
        canvas.fillRect(x, 0, width, LABEL_HEIGHT);
        canvas.setColor(new Color(232, 232, 232));
        canvas.drawString(text, x + 8, 16);
    }

    private void bindKey(
            final int key,
            final boolean release,
            final String name,
            final Runnable action) {
        getInputMap(WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(key, 0, release), name);
        getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(final ActionEvent event) {
                action.run();
            }
        });
    }

    private static BufferedImage previewImage(final ReviewPreview preview) {
        final float[] pixels = preview.pixels();
        final PreviewDisplayWindow window = preview.displayWindow();
        final BufferedImage image = new BufferedImage(
                preview.width(), preview.height(), BufferedImage.TYPE_BYTE_GRAY);
        final byte[] output = ((DataBufferByte) image.getRaster()
                .getDataBuffer()).getData();
        for (int index = 0; index < pixels.length; index++) {
            output[index] = (byte) Math.max(0, Math.min(255,
                    Math.round((pixels[index] - window.lower())
                            / window.range() * 255)));
        }
        return image;
    }

    private static AffineTransform imageToScreen(final ScreenMapping mapping) {
        final AffineTransform transform = AffineTransform.getTranslateInstance(
                mapping.offsetX() - 0.5, mapping.offsetY() - 0.5);
        transform.scale(mapping.scale(), mapping.scale());
        transform.translate(0.5, 0.5);
        return transform;
    }

    private static boolean isBoundary(
            final int[] labels,
            final int width,
            final int height,
            final int x,
            final int y) {
        final int value = labels[y * width + x];
        if (value == 0) {
            return false;
        }
        return x == 0 || labels[y * width + x - 1] != value
                || x + 1 == width || labels[y * width + x + 1] != value
                || y == 0 || labels[(y - 1) * width + x] != value
                || y + 1 == height || labels[(y + 1) * width + x] != value;
    }

    private static double pointToSegmentDistance(
            final Point point,
            final Point2D first,
            final Point2D second) {
        final double dx = second.x() - first.x();
        final double dy = second.y() - first.y();
        final double lengthSquared = dx * dx + dy * dy;
        final double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1,
                ((point.x - first.x()) * dx + (point.y - first.y()) * dy)
                        / lengthSquared));
        return Math.hypot(point.x - (first.x() + t * dx),
                point.y - (first.y() + t * dy));
    }

    private record DraggedVertex(
            String contourId,
            String vertexId,
            Point origin,
            Point last) {
        private DraggedVertex withLast(final Point value) {
            return new DraggedVertex(contourId, vertexId, origin, value);
        }
    }

    public interface Listener {
        Listener NOOP = new Listener() { };
        default void addVertex(final String contourId, final Point2D source) { }
        default void insertAfter(
                final String contourId,
                final String vertexId,
                final Point2D source) { }
        default void moveVertex(
                final String contourId,
                final String vertexId,
                final Point2D source) { }
        default void deleteVertex(
                final String contourId,
                final String vertexId) { }
        default void toggleGap(
                final String contourId,
                final ContourSegment segment) { }
        default void correctOutlineAnchor(
                final String anchorName,
                final boolean tissueEndpoint,
                final int loopIndex) { }
    }
}
