package org.atlasalign.plugin.review;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.roi.ReviewerRoi;
import org.atlasalign.application.roi.ReviewerRoiPart;
import org.atlasalign.application.roi.ReviewerRoiSession;
import org.atlasalign.application.roi.ReviewerRoiVertex;
import org.atlasalign.application.roi.RoiPartOperation;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;

/** Display-only rendering and gestures for exact source-coordinate ROIs. */
final class ManualRoiCanvasLayer {

    enum CaptureMode {
        POLYGON,
        FREEHAND
    }

    interface Listener {
        Listener NOOP = new Listener() { };

        default void select(final String roiId) { }

        default void addVertex(final Point2D sourcePoint) { }

        default void addFreehandVertices(
                final List<Point2D> sourcePoints) { }

        default void moveVertex(
                final String roiId,
                final String partId,
                final String vertexId,
                final Point2D sourcePoint) { }

        default void insertAfter(
                final String roiId,
                final String partId,
                final String vertexId,
                final Point2D sourcePoint) { }

        default void deleteVertex(
                final String roiId,
                final String partId,
                final String vertexId) { }

        default void translate(
                final String roiId,
                final double sourceDx,
                final double sourceDy) { }
    }

    private static final double HANDLE_RADIUS = 10;
    private static final double SEGMENT_RADIUS = 8;
    private static final double FREEHAND_SAMPLE_PIXELS = 4;
    private static final Color HANDLE_HALO = new Color(4, 10, 14, 245);
    private static final Color HANDLE_LIGHT = new Color(245, 255, 255, 250);
    private static final Color CYAN = new Color(0, 235, 235, 245);
    private static final Color SUBTRACT = new Color(255, 70, 70, 230);
    private static final Color[] FINISHED_COLORS = {
        new Color(75, 205, 255, 225),
        new Color(130, 230, 120, 225),
        new Color(255, 205, 65, 225),
        new Color(190, 125, 255, 225),
        new Color(255, 130, 95, 225)
    };

    private ReviewerRoiSession.Snapshot snapshot;
    private PreviewMapping sourceToPreview;
    private Listener listener = Listener.NOOP;
    private boolean visible = true;
    private boolean enabled;
    private boolean previewSelected;
    private CaptureMode captureMode = CaptureMode.POLYGON;
    private Drag drag;

    void setListener(final Listener value) {
        listener = Objects.requireNonNull(value, "value");
    }

    void setState(
            final ReviewerRoiSession.Snapshot value,
            final PreviewMapping mapping) {
        snapshot = Objects.requireNonNull(value, "value");
        sourceToPreview = Objects.requireNonNull(mapping, "mapping");
        if (mapping.sourceWidth() != value.sourceWidth()
                || mapping.sourceHeight() != value.sourceHeight()) {
            throw new IllegalArgumentException(
                    "Manual ROI source and preview mapping must match");
        }
    }

    void setEnabled(final boolean value) {
        enabled = value;
        if (!enabled) {
            drag = null;
        }
    }

    boolean enabled() {
        return enabled;
    }

    void setVisible(final boolean value) {
        visible = value;
        if (!visible) {
            drag = null;
        }
    }

    boolean visible() {
        return visible;
    }

    void setPreviewSelected(final boolean value) {
        previewSelected = value;
    }

    boolean previewSelected() {
        return previewSelected;
    }

    void setCaptureMode(final CaptureMode value) {
        captureMode = Objects.requireNonNull(value, "value");
        drag = null;
    }

    CaptureMode captureMode() {
        return captureMode;
    }

    boolean cancelGesture() {
        final boolean active = drag != null;
        drag = null;
        return active;
    }

    boolean press(
            final MouseEvent event,
            final ScreenMapping mapping) {
        if (!visible || !enabled || snapshot == null
                || sourceToPreview == null) {
            return false;
        }
        final Point point = event.getPoint();
        final Optional<VertexHit> vertex = nearestVertex(mapping, point);
        if (event.getButton() == MouseEvent.BUTTON3) {
            vertex.ifPresent(hit -> listener.deleteVertex(
                    hit.roi().id(), hit.part().id(), hit.vertex().id()));
            return vertex.isPresent();
        }
        if (event.getButton() != MouseEvent.BUTTON1) {
            return false;
        }
        if (vertex.isPresent()) {
            final VertexHit hit = vertex.orElseThrow();
            listener.select(hit.roi().id());
            drag = Drag.vertex(hit.roi().id(), hit.part().id(),
                    hit.vertex().id(), hit.vertex().sourcePoint(), point);
            return true;
        }
        final Point2D source = screenToSource(mapping, point);
        if (!insideSource(source)) {
            return false;
        }
        if (event.isShiftDown()) {
            final Optional<SegmentHit> segment = nearestActiveSegment(
                    mapping, point);
            if (segment.isPresent()) {
                final SegmentHit hit = segment.orElseThrow();
                listener.insertAfter(hit.roiId(), hit.partId(),
                        hit.afterVertexId(), source);
                return true;
            }
        }
        final ReviewerRoi active = snapshot.activeRoi().orElse(null);
        final ReviewerRoiPart activePart = snapshot.activePart().orElse(null);
        if (activePart != null && !activePart.finished()) {
            if (captureMode == CaptureMode.FREEHAND) {
                drag = Drag.freehand(point, source);
            } else {
                listener.addVertex(source);
            }
            return true;
        }
        final Optional<ReviewerRoi> body = roiAt(mapping, point);
        if (body.isPresent()) {
            final ReviewerRoi selected = body.orElseThrow();
            listener.select(selected.id());
            drag = Drag.translate(selected.id(), source, point);
            return true;
        }
        return active != null;
    }

    boolean drag(
            final MouseEvent event,
            final ScreenMapping mapping) {
        if (!visible || !enabled || drag == null) {
            return false;
        }
        final Point point = event.getPoint();
        final Point2D source = clampToSource(screenToSource(mapping, point));
        if (drag.kind == DragKind.FREEHAND) {
            if (drag.lastScreen.distance(point) >= FREEHAND_SAMPLE_PIXELS) {
                drag.freehandPoints.add(source);
                drag.lastScreen = point;
            }
        } else {
            drag.currentSource = source;
            drag.lastScreen = point;
        }
        return true;
    }

    boolean release(
            final MouseEvent event,
            final ScreenMapping mapping) {
        if (!visible || !enabled || drag == null) {
            return false;
        }
        drag(event, mapping);
        final Drag committed = drag;
        drag = null;
        if (committed.kind == DragKind.VERTEX) {
            if (committed.startScreen.distance(committed.lastScreen) > 1) {
                listener.moveVertex(committed.roiId,
                        committed.partId, committed.vertexId,
                        committed.currentSource);
            }
        } else if (committed.kind == DragKind.TRANSLATE) {
            final double dx = committed.currentSource.x()
                    - committed.startSource.x();
            final double dy = committed.currentSource.y()
                    - committed.startSource.y();
            if (Math.hypot(dx, dy) > 1e-9) {
                listener.translate(committed.roiId, dx, dy);
            }
        } else if (committed.kind == DragKind.FREEHAND) {
            listener.addFreehandVertices(
                    deduplicate(committed.freehandPoints));
        }
        return true;
    }

    void paint(
            final Graphics2D graphics,
            final ScreenMapping mapping,
            final int paneTop,
            final int paneWidth,
            final int paneHeight) {
        if (!visible || snapshot == null || sourceToPreview == null) {
            return;
        }
        final var oldClip = graphics.getClip();
        graphics.clip(new Rectangle2D.Double(
                0, paneTop, paneWidth, paneHeight));
        if (previewSelected) {
            final Area outside = new Area(new Rectangle2D.Double(
                    0, paneTop, paneWidth, paneHeight));
            final Area selected = new Area();
            for (final ReviewerRoi roi : snapshot.rois()) {
                if (roi.finished() && roi.selectedForExport()) {
                    selected.add(roiArea(roi, mapping, false));
                }
            }
            outside.subtract(selected);
            graphics.setColor(new Color(0, 0, 0, 204));
            graphics.fill(outside);
        }

        int colorIndex = 0;
        for (final ReviewerRoi roi : snapshot.rois()) {
            if (!roi.visible()) {
                colorIndex++;
                continue;
            }
            final boolean active = snapshot.activeRoiId()
                    .equals(Optional.of(roi.id()));
            final Color color = active ? CYAN
                    : FINISHED_COLORS[colorIndex % FINISHED_COLORS.length];
            if (roi.finished()) {
                graphics.setColor(new Color(color.getRed(), color.getGreen(),
                        color.getBlue(), active ? 40 : 30));
                graphics.fill(roiArea(roi, mapping, true));
            }
            for (final ReviewerRoiPart part : roi.parts()) {
                final Path2D path = path(part, mapping, active);
                graphics.setColor(part.operation()
                        == RoiPartOperation.SUBTRACT ? SUBTRACT : color);
                graphics.setStroke(part.operation()
                        == RoiPartOperation.SUBTRACT
                        ? new BasicStroke(active ? 1.5f : 1.2f,
                                BasicStroke.CAP_BUTT,
                                BasicStroke.JOIN_ROUND, 10,
                                new float[] {6, 4}, 0)
                        : new BasicStroke(active ? 1.5f : 1.2f));
                graphics.draw(path);
                if (active && enabled) {
                    drawHandles(graphics, roi, part, mapping);
                }
            }
            colorIndex++;
        }
        if (drag != null && drag.kind == DragKind.FREEHAND
                && drag.freehandPoints.size() > 1) {
            graphics.setColor(CYAN);
            graphics.setStroke(new BasicStroke(2.2f));
            final Path2D stroke = new Path2D.Double();
            boolean first = true;
            for (final Point2D source : drag.freehandPoints) {
                final Point2D screen = sourceToScreen(mapping, source);
                if (first) {
                    stroke.moveTo(screen.x(), screen.y());
                    first = false;
                } else {
                    stroke.lineTo(screen.x(), screen.y());
                }
            }
            graphics.draw(stroke);
        }
        graphics.setClip(oldClip);
    }

    private void drawHandles(
            final Graphics2D graphics,
            final ReviewerRoi roi,
            final ReviewerRoiPart part,
            final ScreenMapping mapping) {
        for (final ReviewerRoiVertex vertex : part.vertices()) {
            final Point2D source = liveSourcePoint(
                    roi.id(), part.id(), vertex);
            final Point2D screen = sourceToScreen(mapping, source);
            // The visible handle remains screen-sized while its independent
            // 10-screen-pixel hit radius stays easy to acquire.
            final boolean moving = drag != null && drag.kind == DragKind.VERTEX
                    && drag.roiId.equals(roi.id()) && drag.partId.equals(part.id())
                    && drag.vertexId.equals(vertex.id());
            final int x = (int) Math.round(screen.x());
            final int y = (int) Math.round(screen.y());
            if (moving) {
                graphics.setColor(HANDLE_HALO);
                graphics.fillOval(x - 6, y - 6, 13, 13);
                graphics.setColor(HANDLE_LIGHT);
                graphics.fillOval(x - 4, y - 4, 9, 9);
                graphics.setColor(CYAN);
                graphics.fillOval(x - 3, y - 3, 7, 7);
            } else {
                graphics.setColor(HANDLE_HALO);
                graphics.fillOval(x - 4, y - 4, 9, 9);
                graphics.setColor(HANDLE_LIGHT);
                graphics.fillOval(x - 3, y - 3, 7, 7);
                graphics.setColor(CYAN);
                graphics.fillOval(x - 2, y - 2, 5, 5);
            }
        }
    }

    private Area roiArea(
            final ReviewerRoi roi,
            final ScreenMapping mapping,
            final boolean live) {
        final Area area = new Area();
        for (final ReviewerRoiPart part : roi.parts()) {
            if (!part.finished()) {
                continue;
            }
            final Area contribution = new Area(path(part, mapping,
                    live && snapshot.activeRoiId()
                            .equals(Optional.of(roi.id()))));
            if (part.operation() == RoiPartOperation.ADD) {
                area.add(contribution);
            } else {
                area.subtract(contribution);
            }
        }
        return area;
    }

    private Path2D path(
            final ReviewerRoiPart part,
            final ScreenMapping mapping,
            final boolean live) {
        final Path2D path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        boolean first = true;
        for (final ReviewerRoiVertex vertex : part.vertices()) {
            final Point2D source = live
                    ? liveSourcePoint(snapshot.activeRoiId().orElse(""),
                            part.id(), vertex)
                    : vertex.sourcePoint();
            final Point2D screen = sourceToScreen(mapping, source);
            if (first) {
                path.moveTo(screen.x(), screen.y());
                first = false;
            } else {
                path.lineTo(screen.x(), screen.y());
            }
        }
        if (part.finished() && part.vertices().size() >= 3) {
            path.closePath();
        }
        return path;
    }

    private Point2D liveSourcePoint(
            final String roiId,
            final String partId,
            final ReviewerRoiVertex vertex) {
        if (drag == null || !drag.roiId.equals(roiId)) {
            return vertex.sourcePoint();
        }
        if (drag.kind == DragKind.VERTEX
                && drag.partId.equals(partId)
                && drag.vertexId.equals(vertex.id())) {
            return drag.currentSource;
        }
        if (drag.kind == DragKind.TRANSLATE) {
            return new Point2D(vertex.sourcePoint().x()
                    + drag.currentSource.x() - drag.startSource.x(),
                    vertex.sourcePoint().y()
                    + drag.currentSource.y() - drag.startSource.y());
        }
        return vertex.sourcePoint();
    }

    private Optional<VertexHit> nearestVertex(
            final ScreenMapping mapping,
            final Point point) {
        VertexHit best = null;
        double distance = HANDLE_RADIUS;
        final List<ReviewerRoi> ordered = new ArrayList<>(snapshot.rois());
        java.util.Collections.reverse(ordered);
        for (final ReviewerRoi roi : ordered) {
            if (!roi.visible()) {
                continue;
            }
            for (final ReviewerRoiPart part : roi.parts()) {
                for (final ReviewerRoiVertex vertex : part.vertices()) {
                    final double candidate = point.distance(
                            awt(sourceToScreen(mapping,
                                    vertex.sourcePoint())));
                    if (candidate <= distance) {
                        distance = candidate;
                        best = new VertexHit(roi, part, vertex);
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<SegmentHit> nearestActiveSegment(
            final ScreenMapping mapping,
            final Point point) {
        final ReviewerRoi roi = snapshot.activeRoi().orElse(null);
        if (roi == null) {
            return Optional.empty();
        }
        SegmentHit best = null;
        double distance = SEGMENT_RADIUS;
        for (final ReviewerRoiPart part : roi.parts()) {
            final List<ReviewerRoiVertex> vertices = part.vertices();
            final int edges = part.finished() ? vertices.size()
                    : Math.max(0, vertices.size() - 1);
            for (int index = 0; index < edges; index++) {
                final ReviewerRoiVertex first = vertices.get(index);
                final ReviewerRoiVertex second = vertices.get(
                        (index + 1) % vertices.size());
                final Point2D a = sourceToScreen(mapping,
                        first.sourcePoint());
                final Point2D b = sourceToScreen(mapping,
                        second.sourcePoint());
                final double candidate = Line2D.ptSegDist(a.x(), a.y(),
                        b.x(), b.y(), point.x, point.y);
                if (candidate <= distance) {
                    distance = candidate;
                    best = new SegmentHit(roi.id(), part.id(), first.id());
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private Optional<ReviewerRoi> roiAt(
            final ScreenMapping mapping,
            final Point point) {
        final List<ReviewerRoi> ordered = new ArrayList<>(snapshot.rois());
        java.util.Collections.reverse(ordered);
        return ordered.stream().filter(ReviewerRoi::visible)
                .filter(ReviewerRoi::finished)
                .filter(roi -> roiArea(roi, mapping, false)
                        .contains(point)).findFirst();
    }

    private Point2D screenToSource(
            final ScreenMapping mapping,
            final Point point) {
        return sourceToPreview.previewToSource(mapping.screenToPreview(
                new Point2D(point.x, point.y)));
    }

    private Point2D sourceToScreen(
            final ScreenMapping mapping,
            final Point2D source) {
        return mapping.previewToScreen(
                sourceToPreview.sourceToPreview(source));
    }

    private boolean insideSource(final Point2D source) {
        return source.x() >= 0 && source.y() >= 0
                && source.x() <= snapshot.sourceWidth() - 1.0
                && source.y() <= snapshot.sourceHeight() - 1.0;
    }

    private Point2D clampToSource(final Point2D source) {
        return new Point2D(
                Math.max(0, Math.min(snapshot.sourceWidth() - 1.0,
                        source.x())),
                Math.max(0, Math.min(snapshot.sourceHeight() - 1.0,
                        source.y())));
    }

    private static Point awt(final Point2D point) {
        return new Point((int) Math.round(point.x()),
                (int) Math.round(point.y()));
    }

    private static List<Point2D> deduplicate(
            final List<Point2D> input) {
        final List<Point2D> result = new ArrayList<>();
        for (final Point2D point : input) {
            if (result.isEmpty() || !result.get(result.size() - 1)
                    .equals(point)) {
                result.add(point);
            }
        }
        return List.copyOf(result);
    }

    private record VertexHit(
            ReviewerRoi roi,
            ReviewerRoiPart part,
            ReviewerRoiVertex vertex) {
    }

    private record SegmentHit(
            String roiId,
            String partId,
            String afterVertexId) {
    }

    private enum DragKind {
        VERTEX,
        TRANSLATE,
        FREEHAND
    }

    private static final class Drag {
        private final DragKind kind;
        private final String roiId;
        private final String partId;
        private final String vertexId;
        private final Point2D startSource;
        private Point2D currentSource;
        private final Point startScreen;
        private Point lastScreen;
        private final List<Point2D> freehandPoints;

        private Drag(
                final DragKind kind,
                final String roiId,
                final String partId,
                final String vertexId,
                final Point2D startSource,
                final Point currentScreen,
                final List<Point2D> freehandPoints) {
            this.kind = kind;
            this.roiId = roiId;
            this.partId = partId;
            this.vertexId = vertexId;
            this.startSource = startSource;
            this.currentSource = startSource;
            this.startScreen = currentScreen;
            this.lastScreen = currentScreen;
            this.freehandPoints = freehandPoints;
        }

        static Drag vertex(
                final String roiId,
                final String partId,
                final String vertexId,
                final Point2D source,
                final Point screen) {
            return new Drag(DragKind.VERTEX, roiId, partId, vertexId,
                    source, screen, new ArrayList<>());
        }

        static Drag translate(
                final String roiId,
                final Point2D source,
                final Point screen) {
            return new Drag(DragKind.TRANSLATE, roiId, "", "",
                    source, screen, new ArrayList<>());
        }

        static Drag freehand(
                final Point screen,
                final Point2D source) {
            final ArrayList<Point2D> points = new ArrayList<>();
            points.add(source);
            return new Drag(DragKind.FREEHAND, "", "", "",
                    source, screen, points);
        }
    }
}
