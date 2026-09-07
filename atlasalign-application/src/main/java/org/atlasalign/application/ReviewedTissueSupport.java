package org.atlasalign.application;

import java.awt.geom.Path2D;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.Point2D;

/**
 * Immutable reviewer-selected tissue support in preview pixel coordinates.
 *
 * <p>The support is a presentation/cropping footprint derived from the copied
 * preview mask. It is deliberately separate from {@code ManualWarpControl}:
 * moving a crop control changes only this footprint and never becomes fit
 * evidence or a local atlas-warp control.</p>
 *
 * <p>Polygons use geometric pixel-edge coordinates. Consequently a boundary
 * point may be at {@code width} or {@code height}, while the interior sampled
 * pixel centres remain in {@code [0,width-1] x [0,height-1]}.</p>
 */
public final class ReviewedTissueSupport {

    public static final int DEFAULT_CONTROL_COUNT = 24;
    public static final int MINIMUM_CONTROLS_PER_COMPONENT = 4;
    public static final int MAXIMUM_CONTROLS = 48;
    private static final int MAXIMUM_COMPONENTS =
            MAXIMUM_CONTROLS / MINIMUM_CONTROLS_PER_COMPONENT;
    private static final int MINIMUM_COMPONENT_PIXELS = 4;
    private static final double MINIMUM_COMPONENT_AREA_FRACTION = 0.001;

    /** Immutable editable crop node; it is not a warp or evidence handle. */
    public record Control(
            String id,
            int componentIndex,
            int vertexIndex,
            Point2D origin,
            Point2D point) {

        public Control {
            id = requireText(id, "id");
            if (componentIndex < 0 || vertexIndex < 0) {
                throw new IllegalArgumentException(
                        "Crop-control component and vertex indices must be non-negative");
            }
            origin = Objects.requireNonNull(origin, "origin");
            point = Objects.requireNonNull(point, "point");
        }

        /** Alias used by canvas code that treats a crop node as a position. */
        public Point2D position() {
            return point;
        }

        public boolean moved() {
            return !origin.equals(point);
        }
    }

    private record Edge(int startX, int startY, int endX, int endY) {
        private Vertex start() {
            return new Vertex(startX, startY);
        }

        private Vertex end() {
            return new Vertex(endX, endY);
        }
    }

    private record Vertex(int x, int y) {
    }

    private final int width;
    private final int height;
    private final BinaryMask sourceMask;
    private final String sourceMaskSha256;
    private final List<List<Point2D>> polygons;
    private final List<Control> controls;
    private final Path2D.Double path;
    private final BinaryMask rasterizedSupport;
    private final String sha256;

    /** One exact even-odd interior interval on a horizontal preview scanline. */
    public record HorizontalInterval(
            double minimumXInclusive,
            double maximumXExclusive) {

        public HorizontalInterval {
            if (!Double.isFinite(minimumXInclusive)
                    || !Double.isFinite(maximumXExclusive)
                    || maximumXExclusive < minimumXInclusive) {
                throw new IllegalArgumentException(
                        "Support scanline interval must be finite and ordered");
            }
        }
    }

    /**
     * Builds a deterministic 24-node support from a copied-image mask.
     */
    public static ReviewedTissueSupport fromMask(final BinaryMask mask) {
        return fromMask(mask, DEFAULT_CONTROL_COUNT);
    }

    /**
     * Builds a deterministic support with the requested total node density.
     * Four nodes are retained for every connected boundary component and the
     * resulting total is capped at {@link #MAXIMUM_CONTROLS}.
     */
    public static ReviewedTissueSupport fromMask(
            final BinaryMask mask,
            final int requestedControlCount) {
        Objects.requireNonNull(mask, "mask");
        requireControlCount(requestedControlCount);
        final List<List<Point2D>> loops = boundaryLoops(mask);
        final int target = targetControlCount(
                loops.size(), requestedControlCount);
        final List<Integer> allocation = allocateControls(loops, target);
        final List<List<Point2D>> sampled = new ArrayList<>();
        final List<Control> generated = new ArrayList<>();
        for (int component = 0; component < loops.size(); component++) {
            final List<Point2D> polygon = densifyLoop(
                    loops.get(component), allocation.get(component));
            sampled.add(polygon);
            final List<Integer> selectedVertices = selectVertices(
                    polygon, allocation.get(component));
            for (int controlIndex = 0;
                    controlIndex < selectedVertices.size(); controlIndex++) {
                final int vertex = selectedVertices.get(controlIndex);
                final Point2D point = polygon.get(vertex);
                generated.add(new Control(
                        controlId(component, controlIndex), component, vertex,
                        point, point));
            }
        }
        return new ReviewedTissueSupport(
                mask, sampled, generated, maskSha256(mask));
    }

    /**
     * Builds one reviewer-authored support component from an explicit polygon.
     * Every supplied vertex remains an editable crop control. The polygon is
     * interpreted in preview pixel coordinates and rasterized with the same
     * pixel-centre convention as automatically suggested supports.
     */
    public static ReviewedTissueSupport fromPolygon(
            final int width,
            final int height,
            final List<Point2D> requestedPolygon) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Tissue-crop preview dimensions must be positive");
        }
        final List<Point2D> polygon = new ArrayList<>(List.copyOf(
                Objects.requireNonNull(requestedPolygon,
                        "requestedPolygon")));
        if (polygon.size() > 1
                && polygon.get(0).equals(polygon.get(polygon.size() - 1))) {
            polygon.remove(polygon.size() - 1);
        }
        if (polygon.size() < MINIMUM_CONTROLS_PER_COMPONENT) {
            throw new IllegalArgumentException(
                    "Draw at least " + MINIMUM_CONTROLS_PER_COMPONENT
                            + " boundary points before finishing the crop");
        }
        if (polygon.size() > MAXIMUM_CONTROLS) {
            throw new IllegalArgumentException(
                    "A manual tissue crop allows at most "
                            + MAXIMUM_CONTROLS + " boundary points");
        }
        final List<List<Point2D>> polygons = List.of(List.copyOf(polygon));
        final BinaryMask mask = rasterize(width, height, polygons);
        final List<Control> controls = new ArrayList<>(polygon.size());
        for (int vertex = 0; vertex < polygon.size(); vertex++) {
            final Point2D point = polygon.get(vertex);
            controls.add(new Control(
                    "manual-tissue-crop-" + (vertex + 1),
                    0, vertex, point, point));
        }
        return new ReviewedTissueSupport(
                mask, polygons, controls, maskSha256(mask));
    }

    /**
     * Creates a support from explicit polygons and crop nodes. This is useful
     * for replaying a reviewed edit; all values are copied and validated.
     */
    public ReviewedTissueSupport(
            final BinaryMask sourceMask,
            final List<List<Point2D>> polygons,
            final List<Control> controls) {
        this(sourceMask, polygons, controls, maskSha256(sourceMask));
    }

    private ReviewedTissueSupport(
            final BinaryMask sourceMask,
            final List<List<Point2D>> polygons,
            final List<Control> controls,
            final String sourceMaskSha256) {
        this.sourceMask = Objects.requireNonNull(sourceMask, "sourceMask");
        this.width = sourceMask.width();
        this.height = sourceMask.height();
        this.sourceMaskSha256 = requireSha256(
                sourceMaskSha256, "sourceMaskSha256");
        this.polygons = copyPolygons(polygons);
        this.controls = List.copyOf(Objects.requireNonNull(
                controls, "controls"));
        validate(this.width, this.height, this.polygons, this.controls);
        this.path = buildPath(this.polygons);
        this.rasterizedSupport = rasterize(
                this.width, this.height, this.polygons);
        if (this.rasterizedSupport.isEmpty()) {
            throw new IllegalArgumentException(
                    "Reviewed tissue support contains no preview pixels; "
                            + "enlarge or move the outline before continuing");
        }
        this.sha256 = computeSha256(
                this.width, this.height, this.sourceMaskSha256,
                this.polygons, this.controls);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** The copied-image mask from which this support was proposed. */
    public BinaryMask sourceMask() {
        return BinaryMask.fromBitSet(
                sourceMask.width(), sourceMask.height(),
                sourceMask.copyBits());
    }

    public String sourceMaskSha256() {
        return sourceMaskSha256;
    }

    /** Closed polygon loops in deterministic component order. */
    public List<List<Point2D>> polygons() {
        return polygons;
    }

    public List<Point2D> polygon(final int componentIndex) {
        return polygons.get(requireComponentIndex(componentIndex));
    }

    public List<Control> controls() {
        return controls;
    }

    public Control control(final String controlId) {
        final String id = requireText(controlId, "controlId");
        return controls.stream()
                .filter(control -> control.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown crop-support control: " + id));
    }

    /** Stable SHA-256 identity of the mask provenance and editable geometry. */
    public String sha256() {
        return sha256;
    }

    public String contentSha256() {
        return sha256;
    }

    public String supportSha256() {
        return sha256;
    }

    @Override
    public boolean equals(final Object other) {
        return this == other
                || other instanceof ReviewedTissueSupport support
                && sha256.equals(support.sha256);
    }

    @Override
    public int hashCode() {
        return sha256.hashCode();
    }

    /** True when a preview pixel centre lies inside the current support. */
    public boolean contains(final Point2D point) {
        Objects.requireNonNull(point, "point");
        if (point.x() < 0 || point.x() > width - 1.0
                || point.y() < 0 || point.y() > height - 1.0) {
            return false;
        }
        if (point.x() == Math.rint(point.x())
                && point.y() == Math.rint(point.y())) {
            return rasterizedSupport.contains(
                    (int) point.x(), (int) point.y());
        }
        return path.contains(point.x(), point.y());
    }

    public boolean contains(final int x, final int y) {
        return rasterizedSupport.contains(x, y);
    }

    /**
     * Returns the exact even-odd interior intervals at one preview-space Y.
     *
     * <p>This is the scanline equivalent of {@link #contains(Point2D)} for
     * the support's straight polygon edges. It is useful when many regularly
     * spaced sample points share the same Y coordinate: callers can test the
     * returned intervals instead of repeating a complete {@link Path2D}
     * crossing calculation for every X. Left edges are included and right
     * edges are excluded, matching the pixel-centre rasterizer and Java2D's
     * half-open edge rule.</p>
     */
    public List<HorizontalInterval> horizontalIntervals(final double y) {
        if (!Double.isFinite(y)) {
            throw new IllegalArgumentException(
                    "Support scanline Y must be finite");
        }
        if (y < 0 || y > height - 1.0) {
            return List.of();
        }
        final List<Double> intersections = new ArrayList<>();
        for (final List<Point2D> polygon : polygons) {
            for (int index = 0; index < polygon.size(); index++) {
                final Point2D first = polygon.get(index);
                final Point2D second = polygon.get(
                        (index + 1) % polygon.size());
                if ((first.y() <= y && second.y() > y)
                        || (second.y() <= y && first.y() > y)) {
                    final double fraction = (y - first.y())
                            / (second.y() - first.y());
                    intersections.add(first.x()
                            + fraction * (second.x() - first.x()));
                }
            }
        }
        intersections.sort(Double::compareTo);
        final List<HorizontalInterval> intervals = new ArrayList<>(
                intersections.size() / 2);
        for (int pair = 0; pair + 1 < intersections.size(); pair += 2) {
            intervals.add(new HorizontalInterval(
                    intersections.get(pair), intersections.get(pair + 1)));
        }
        return List.copyOf(intervals);
    }

    /** Returns a defensive copy of the cached support raster. */
    public BinaryMask supportMask() {
        return BinaryMask.fromBitSet(
                width, height, rasterizedSupport.copyBits());
    }

    /** Moves one node while retaining its original proposal position. */
    public ReviewedTissueSupport moveControl(
            final String controlId,
            final Point2D destination) {
        final Control prior = control(controlId);
        requireInsidePreview(destination, width, height, "destination");
        final List<List<Point2D>> updatedPolygons = mutablePolygons();
        final List<Control> updatedControls = new ArrayList<>();
        for (final Control control : controls) {
            updatedControls.add(control.id().equals(prior.id())
                    ? new Control(control.id(), control.componentIndex(),
                            control.vertexIndex(), control.origin(), destination)
                    : control);
        }
        final List<Control> priorComponentControls = controls.stream()
                .filter(control -> control.componentIndex()
                        == prior.componentIndex())
                .sorted(Comparator.comparingInt(Control::vertexIndex))
                .toList();
        final List<Control> updatedComponentControls = updatedControls.stream()
                .filter(control -> control.componentIndex()
                        == prior.componentIndex())
                .sorted(Comparator.comparingInt(Control::vertexIndex))
                .toList();
        final List<Point2D> currentPolygon = polygons.get(
                prior.componentIndex());
        final List<Point2D> baseline = new ArrayList<>();
        for (int vertex = 0; vertex < currentPolygon.size(); vertex++) {
            final Point2D currentPoint = currentPolygon.get(vertex);
            final Point2D priorDisplacement = interpolatedDisplacement(
                    vertex, currentPolygon.size(), priorComponentControls);
            baseline.add(new Point2D(
                    currentPoint.x() - priorDisplacement.x(),
                    currentPoint.y() - priorDisplacement.y()));
        }
        for (int vertex = 0; vertex < baseline.size(); vertex++) {
            final Point2D base = baseline.get(vertex);
            final Point2D displacement = interpolatedDisplacement(
                    vertex, baseline.size(), updatedComponentControls);
            updatedPolygons.get(prior.componentIndex()).set(vertex,
                    new Point2D(
                            base.x() + displacement.x(),
                            base.y() + displacement.y()));
        }
        final List<Control> synchronizedControls = new ArrayList<>();
        for (final Control control : updatedControls) {
            synchronizedControls.add(control.componentIndex()
                    == prior.componentIndex()
                    ? new Control(control.id(), control.componentIndex(),
                            control.vertexIndex(), control.origin(),
                            updatedPolygons.get(control.componentIndex())
                                    .get(control.vertexIndex()))
                    : control);
        }
        return new ReviewedTissueSupport(
                sourceMask, updatedPolygons, synchronizedControls,
                sourceMaskSha256);
    }

    /**
     * Returns the current piecewise-linear crop displacement at one dense
     * polygon vertex. The field is local: only the perimeter arc between a
     * control and its adjacent controls can change when that control moves.
     */
    private static Point2D interpolatedDisplacement(
            final int vertex,
            final int polygonSize,
            final List<Control> componentControls) {
        if (componentControls.isEmpty()) {
            return new Point2D(0, 0);
        }
        for (final Control control : componentControls) {
            if (control.vertexIndex() == vertex) {
                return displacement(control);
            }
        }
        Control previous = componentControls.get(componentControls.size() - 1);
        Control next = componentControls.get(0);
        for (int index = 0; index < componentControls.size(); index++) {
            final Control candidate = componentControls.get(index);
            if (candidate.vertexIndex() > vertex) {
                next = candidate;
                previous = index == 0
                        ? componentControls.get(componentControls.size() - 1)
                        : componentControls.get(index - 1);
                break;
            }
        }
        final int span = (next.vertexIndex() - previous.vertexIndex()
                + polygonSize) % polygonSize;
        if (span <= 0) {
            return displacement(previous);
        }
        final int distance = (vertex - previous.vertexIndex()
                + polygonSize) % polygonSize;
        final double fraction = Math.max(0,
                Math.min(1, (double) distance / span));
        final Point2D previousDisplacement = displacement(previous);
        final Point2D nextDisplacement = displacement(next);
        return new Point2D(
                previousDisplacement.x()
                        + fraction * (nextDisplacement.x()
                                - previousDisplacement.x()),
                previousDisplacement.y()
                        + fraction * (nextDisplacement.y()
                                - previousDisplacement.y()));
    }

    private static Point2D displacement(final Control control) {
        return new Point2D(
                control.point().x() - control.origin().x(),
                control.point().y() - control.origin().y());
    }

    /**
     * Inserts one crop node after an existing polygon vertex. The supplied id
     * makes replay and audit serialization deterministic.
     */
    public ReviewedTissueSupport insertControl(
            final int componentIndex,
            final int afterVertexIndex,
            final String controlId,
            final Point2D point) {
        final int component = requireComponentIndex(componentIndex);
        final List<Point2D> polygon = polygons.get(component);
        if (afterVertexIndex < 0 || afterVertexIndex >= polygon.size()) {
            throw new IllegalArgumentException(
                    "afterVertexIndex is outside the selected support polygon");
        }
        final String id = requireText(controlId, "controlId");
        if (controls.stream().anyMatch(control -> control.id().equals(id))) {
            throw new IllegalArgumentException(
                    "Crop-support control id already exists: " + id);
        }
        if (controls.size() >= MAXIMUM_CONTROLS) {
            throw new IllegalArgumentException(
                    "Reviewed tissue support allows at most "
                            + MAXIMUM_CONTROLS + " crop controls");
        }
        requireInsidePreview(point, width, height, "point");
        final List<List<Point2D>> updatedPolygons = mutablePolygons();
        updatedPolygons.get(component).add(afterVertexIndex + 1, point);
        final List<Control> updatedControls = new ArrayList<>();
        for (final Control control : controls) {
            if (control.componentIndex() != component
                    || control.vertexIndex() <= afterVertexIndex) {
                updatedControls.add(control);
            } else {
                updatedControls.add(new Control(
                        control.id(), control.componentIndex(),
                        control.vertexIndex() + 1, control.origin(),
                        control.point()));
            }
        }
        updatedControls.add(new Control(
                id, component, afterVertexIndex + 1, point, point));
        updatedControls.sort(Comparator
                .comparingInt(Control::componentIndex)
                .thenComparingInt(Control::vertexIndex));
        return new ReviewedTissueSupport(
                sourceMask, updatedPolygons, updatedControls,
                sourceMaskSha256);
    }

    /** Inserts a node with a deterministic id derived from the edge. */
    public ReviewedTissueSupport insertControl(
            final int componentIndex,
            final int afterVertexIndex,
            final Point2D point) {
        final String id = controlId(componentIndex, afterVertexIndex + 1)
                + "-insert";
        return insertControl(componentIndex, afterVertexIndex, id, point);
    }

    /** Deletes a node while preserving the minimum four nodes per component. */
    public ReviewedTissueSupport deleteControl(final String controlId) {
        final Control removed = control(controlId);
        final long componentCount = controls.stream()
                .filter(control -> control.componentIndex()
                        == removed.componentIndex())
                .count();
        if (componentCount <= MINIMUM_CONTROLS_PER_COMPONENT) {
            throw new IllegalArgumentException(
                    "Each tissue-support component needs at least "
                            + MINIMUM_CONTROLS_PER_COMPONENT + " crop controls");
        }
        final List<List<Point2D>> updatedPolygons = mutablePolygons();
        final List<Point2D> currentPolygon = polygons.get(
                removed.componentIndex());
        final List<Control> priorComponentControls = controls.stream()
                .filter(control -> control.componentIndex()
                        == removed.componentIndex())
                .sorted(Comparator.comparingInt(Control::vertexIndex))
                .toList();
        final List<Point2D> baseline = new ArrayList<>();
        for (int vertex = 0; vertex < currentPolygon.size(); vertex++) {
            final Point2D currentPoint = currentPolygon.get(vertex);
            final Point2D priorDisplacement = interpolatedDisplacement(
                    vertex, currentPolygon.size(), priorComponentControls);
            baseline.add(new Point2D(
                    currentPoint.x() - priorDisplacement.x(),
                    currentPoint.y() - priorDisplacement.y()));
        }
        baseline.remove(removed.vertexIndex());
        updatedPolygons.get(removed.componentIndex()).clear();
        updatedPolygons.get(removed.componentIndex()).addAll(baseline);

        final List<Control> retainedControls = controls.stream()
                .filter(control -> !control.id().equals(removed.id()))
                .map(control -> control.componentIndex()
                        == removed.componentIndex()
                        && control.vertexIndex() > removed.vertexIndex()
                        ? new Control(control.id(), control.componentIndex(),
                                control.vertexIndex() - 1,
                                control.origin(), control.point())
                        : control)
                .sorted(Comparator.comparingInt(Control::componentIndex)
                        .thenComparingInt(Control::vertexIndex))
                .toList();
        for (int vertex = 0; vertex < baseline.size(); vertex++) {
            final Point2D base = baseline.get(vertex);
            final Point2D displacement = interpolatedDisplacement(
                    vertex, baseline.size(), retainedControls.stream()
                            .filter(control -> control.componentIndex()
                                    == removed.componentIndex())
                            .toList());
            updatedPolygons.get(removed.componentIndex()).set(vertex,
                    new Point2D(
                            base.x() + displacement.x(),
                            base.y() + displacement.y()));
        }

        final List<Control> updatedControls = new ArrayList<>();
        for (final Control control : retainedControls) {
            updatedControls.add(control.componentIndex()
                    == removed.componentIndex()
                    ? new Control(control.id(), control.componentIndex(),
                            control.vertexIndex(), control.origin(),
                            updatedPolygons.get(control.componentIndex())
                                    .get(control.vertexIndex()))
                    : control);
        }
        return new ReviewedTissueSupport(
                sourceMask, updatedPolygons, updatedControls,
                sourceMaskSha256);
    }

    private static Path2D.Double buildPath(
            final List<List<Point2D>> polygons) {
        final Path2D.Double result = new Path2D.Double(
                Path2D.WIND_EVEN_ODD);
        for (final List<Point2D> polygon : polygons) {
            if (polygon.isEmpty()) {
                continue;
            }
            final Point2D first = polygon.get(0);
            result.moveTo(first.x(), first.y());
            for (int index = 1; index < polygon.size(); index++) {
                final Point2D point = polygon.get(index);
                result.lineTo(point.x(), point.y());
            }
            result.closePath();
        }
        return result;
    }

    /**
     * Rasterizes all valid polygons with a scanline even-odd fill. This is
     * linear in image rows and boundary edges and avoids a Path2D containment
     * query for every pixel. Pixel-centre semantics intentionally match the
     * half-open edge rule used by the canvas and Java2D.
     */
    private static BinaryMask rasterize(
            final int width,
            final int height,
            final List<List<Point2D>> polygons) {
        final boolean[] values = new boolean[Math.multiplyExact(width, height)];
        for (int y = 0; y < height; y++) {
            final List<Double> intersections = new ArrayList<>();
            for (final List<Point2D> polygon : polygons) {
                for (int index = 0; index < polygon.size(); index++) {
                    final Point2D first = polygon.get(index);
                    final Point2D second = polygon.get(
                            (index + 1) % polygon.size());
                    if ((first.y() <= y && second.y() > y)
                            || (second.y() <= y && first.y() > y)) {
                        final double fraction = (y - first.y())
                                / (second.y() - first.y());
                        intersections.add(first.x()
                                + fraction * (second.x() - first.x()));
                    }
                }
            }
            intersections.sort(Double::compareTo);
            for (int pair = 0; pair + 1 < intersections.size(); pair += 2) {
                final double left = intersections.get(pair);
                final double right = intersections.get(pair + 1);
                final int startX = Math.max(0, (int) Math.ceil(left));
                final int endX = Math.min(width - 1,
                        (int) Math.ceil(right) - 1);
                for (int x = startX; x <= endX; x++) {
                    values[y * width + x] = true;
                }
            }
        }
        return BinaryMask.fromBooleans(width, height, values);
    }

    private List<List<Point2D>> mutablePolygons() {
        final List<List<Point2D>> result = new ArrayList<>();
        for (final List<Point2D> polygon : polygons) {
            result.add(new ArrayList<>(polygon));
        }
        return result;
    }

    private int requireComponentIndex(final int index) {
        if (index < 0 || index >= polygons.size()) {
            throw new IllegalArgumentException(
                    "Support component index is outside the polygon list");
        }
        return index;
    }

    private static List<List<Point2D>> copyPolygons(
            final List<List<Point2D>> source) {
        Objects.requireNonNull(source, "polygons");
        final List<List<Point2D>> result = new ArrayList<>();
        for (final List<Point2D> polygon : source) {
            result.add(List.copyOf(Objects.requireNonNull(
                    polygon, "polygon")));
        }
        return List.copyOf(result);
    }

    private static void validate(
            final int width,
            final int height,
            final List<List<Point2D>> polygons,
            final List<Control> controls) {
        if (polygons.isEmpty()) {
            throw new IllegalArgumentException(
                    "Reviewed tissue support is empty; re-suggest the tissue crop first");
        }
        if (polygons.size() > MAXIMUM_CONTROLS
                / MINIMUM_CONTROLS_PER_COMPONENT) {
            throw new IllegalArgumentException(
                    "Reviewed tissue support has too many boundary components");
        }
        final LinkedHashSet<String> ids = new LinkedHashSet<>();
        final Map<Integer, List<Control>> byComponent = new LinkedHashMap<>();
        for (int component = 0; component < polygons.size(); component++) {
            final List<Point2D> polygon = polygons.get(component);
            if (polygon.size() < 3) {
                throw new IllegalArgumentException(
                        "Support polygons require at least three vertices");
            }
            for (final Point2D point : polygon) {
                Objects.requireNonNull(point, "polygon point");
                if (point.x() < 0 || point.x() > width
                        || point.y() < 0 || point.y() > height) {
                    throw new IllegalArgumentException(
                            "Support polygon point is outside preview bounds");
                }
            }
            validatePolygonGeometry(polygon, component);
            byComponent.put(component, new ArrayList<>());
        }
        validateComponentSeparation(polygons);
        if (controls.size() > MAXIMUM_CONTROLS) {
            throw new IllegalArgumentException(
                    "Reviewed tissue support allows at most "
                            + MAXIMUM_CONTROLS + " crop controls");
        }
        for (final Control control : controls) {
            Objects.requireNonNull(control, "control");
            if (!ids.add(control.id())) {
                throw new IllegalArgumentException(
                        "Crop-support control ids must be unique");
            }
            if (control.componentIndex() >= polygons.size()) {
                throw new IllegalArgumentException(
                        "Crop-support control references an unknown component");
            }
            final List<Point2D> polygon = polygons.get(
                    control.componentIndex());
            if (control.vertexIndex() >= polygon.size()
                    || !polygon.get(control.vertexIndex()).equals(
                    control.point())) {
                throw new IllegalArgumentException(
                        "Crop-support control must identify its polygon vertex");
            }
            requireInsidePreview(
                    control.origin(), width, height, "control origin");
            requireInsidePreview(
                    control.point(), width, height, "control point");
            byComponent.get(control.componentIndex()).add(control);
        }
        for (int component = 0; component < polygons.size(); component++) {
            if (byComponent.get(component).size()
                    < MINIMUM_CONTROLS_PER_COMPONENT) {
                throw new IllegalArgumentException(
                        "Each support component needs at least "
                                + MINIMUM_CONTROLS_PER_COMPONENT
                                + " crop controls");
            }
        }
    }

    private static void validatePolygonGeometry(
            final List<Point2D> polygon,
            final int component) {
        final int size = polygon.size();
        double twiceArea = 0;
        for (int index = 0; index < size; index++) {
            final Point2D first = polygon.get(index);
            final Point2D second = polygon.get((index + 1) % size);
            if (first.equals(second)) {
                throw new IllegalArgumentException(
                        "Tissue-crop component " + component
                                + " has a zero-length edge; move the crop node"
                                + " away from its neighbour");
            }
            twiceArea += first.x() * second.y()
                    - second.x() * first.y();
        }
        final double scale = Math.max(1, maximumCoordinateMagnitude(polygon));
        if (Math.abs(twiceArea) <= 1.0e-12 * scale * scale) {
            throw new IllegalArgumentException(
                    "Tissue-crop component " + component
                            + " is degenerate; keep a non-collinear outline");
        }

        /*
         * Use a spatial grid instead of an O(n^2) all-edge scan. Normal
         * contrast contours contain thousands of short pixel edges; only
         * edges sharing a grid cell can intersect. The pair set prevents a
         * long edited edge from being compared repeatedly in neighbouring
         * cells.
         */
        final int gridSide = Math.max(1,
                (int) Math.ceil(Math.sqrt(size)));
        final double maximum = Math.max(1, maximumCoordinateMagnitude(polygon));
        final double cellSize = maximum / gridSide;
        final Map<Long, List<Integer>> cells = new HashMap<>();
        final HashSet<Long> checkedPairs = new HashSet<>();
        for (int firstIndex = 0; firstIndex < size; firstIndex++) {
            final Point2D first = polygon.get(firstIndex);
            final Point2D second = polygon.get((firstIndex + 1) % size);
            final double minimumX = Math.min(first.x(), second.x());
            final double maximumX = Math.max(first.x(), second.x());
            final double minimumY = Math.min(first.y(), second.y());
            final double maximumY = Math.max(first.y(), second.y());
            final int minimumCellX = cellIndex(minimumX, cellSize, gridSide);
            final int maximumCellX = cellIndex(maximumX, cellSize, gridSide);
            final int minimumCellY = cellIndex(minimumY, cellSize, gridSide);
            final int maximumCellY = cellIndex(maximumY, cellSize, gridSide);
            for (int cellY = minimumCellY; cellY <= maximumCellY; cellY++) {
                for (int cellX = minimumCellX;
                        cellX <= maximumCellX; cellX++) {
                    final long cellKey = (((long) cellY) << 32)
                            ^ (cellX & 0xffffffffL);
                    final List<Integer> occupants = cells.computeIfAbsent(
                            cellKey, ignored -> new ArrayList<>());
                    for (final int secondIndex : occupants) {
                        final int lower = Math.min(firstIndex, secondIndex);
                        final int upper = Math.max(firstIndex, secondIndex);
                        final long pairKey = (((long) lower) << 32)
                                ^ (upper & 0xffffffffL);
                        if (!checkedPairs.add(pairKey)) {
                            continue;
                        }
                        final boolean adjacent = (firstIndex + 1) % size
                                == secondIndex
                                || (secondIndex + 1) % size
                                == firstIndex;
                        if (!segmentsIntersect(
                                polygon.get(firstIndex),
                                polygon.get((firstIndex + 1) % size),
                                polygon.get(secondIndex),
                                polygon.get((secondIndex + 1) % size))) {
                            continue;
                        }
                        if (adjacent && onlySharedEndpoint(
                                firstIndex, secondIndex, polygon)) {
                            continue;
                        }
                        throw new IllegalArgumentException(
                                "Tissue-crop component " + component
                                        + " self-intersects near boundary edges "
                                        + firstIndex + " and " + secondIndex
                                        + "; move the crop node back inside the outline");
                    }
                    occupants.add(firstIndex);
                }
            }
        }
    }

    /**
     * Keeps the even-odd rasterizer from turning overlapping components into
     * XOR holes. Components are allowed to meet at a single boundary point
     * (which can occur for diagonally adjacent source pixels), but any proper
     * boundary crossing, positive-length boundary overlap, or strict nesting
     * is rejected before the support can enter review history.
     */
    private static void validateComponentSeparation(
            final List<List<Point2D>> polygons) {
        for (int first = 0; first < polygons.size(); first++) {
            for (int second = first + 1; second < polygons.size(); second++) {
                if (polygonsOverlap(polygons.get(first), polygons.get(second))) {
                    throw new IllegalArgumentException(
                            "Tissue-crop components " + first + " and "
                                    + second
                                    + " overlap; move the crop outline so "
                                    + "components remain separate");
                }
            }
        }
    }

    private static boolean polygonsOverlap(
            final List<Point2D> first,
            final List<Point2D> second) {
        for (int firstIndex = 0; firstIndex < first.size(); firstIndex++) {
            final Point2D firstStart = first.get(firstIndex);
            final Point2D firstEnd = first.get(
                    (firstIndex + 1) % first.size());
            for (int secondIndex = 0; secondIndex < second.size(); secondIndex++) {
                final Point2D secondStart = second.get(secondIndex);
                final Point2D secondEnd = second.get(
                        (secondIndex + 1) % second.size());
                if (properSegmentsCross(
                        firstStart, firstEnd, secondStart, secondEnd)
                        || collinearSegmentsOverlap(
                                firstStart, firstEnd, secondStart, secondEnd)) {
                    return true;
                }
            }
        }
        return first.stream().anyMatch(
                point -> pointStrictlyInside(point, second))
                || second.stream().anyMatch(
                        point -> pointStrictlyInside(point, first));
    }

    private static boolean properSegmentsCross(
            final Point2D firstStart,
            final Point2D firstEnd,
            final Point2D secondStart,
            final Point2D secondEnd) {
        final int firstStartSide = orientation(
                firstStart, firstEnd, secondStart);
        final int firstEndSide = orientation(
                firstStart, firstEnd, secondEnd);
        final int secondStartSide = orientation(
                secondStart, secondEnd, firstStart);
        final int secondEndSide = orientation(
                secondStart, secondEnd, firstEnd);
        return firstStartSide != 0 && firstEndSide != 0
                && firstStartSide != firstEndSide
                && secondStartSide != 0 && secondEndSide != 0
                && secondStartSide != secondEndSide;
    }

    private static boolean collinearSegmentsOverlap(
            final Point2D firstStart,
            final Point2D firstEnd,
            final Point2D secondStart,
            final Point2D secondEnd) {
        if (orientation(firstStart, firstEnd, secondStart) != 0
                || orientation(firstStart, firstEnd, secondEnd) != 0) {
            return false;
        }
        final boolean useX = Math.abs(firstEnd.x() - firstStart.x())
                >= Math.abs(firstEnd.y() - firstStart.y());
        final double firstMinimum = useX
                ? Math.min(firstStart.x(), firstEnd.x())
                : Math.min(firstStart.y(), firstEnd.y());
        final double firstMaximum = useX
                ? Math.max(firstStart.x(), firstEnd.x())
                : Math.max(firstStart.y(), firstEnd.y());
        final double secondMinimum = useX
                ? Math.min(secondStart.x(), secondEnd.x())
                : Math.min(secondStart.y(), secondEnd.y());
        final double secondMaximum = useX
                ? Math.max(secondStart.x(), secondEnd.x())
                : Math.max(secondStart.y(), secondEnd.y());
        return Math.min(firstMaximum, secondMaximum)
                - Math.max(firstMinimum, secondMinimum) > 1.0e-9;
    }

    private static boolean pointStrictlyInside(
            final Point2D point,
            final List<Point2D> polygon) {
        boolean inside = false;
        for (int index = 0; index < polygon.size(); index++) {
            final Point2D first = polygon.get(index);
            final Point2D second = polygon.get((index + 1) % polygon.size());
            if (pointOnSegment(point, first, second)) {
                return false;
            }
            final boolean crosses = (first.y() > point.y())
                    != (second.y() > point.y());
            if (crosses) {
                final double xAtPointY = first.x()
                        + (point.y() - first.y())
                                * (second.x() - first.x())
                                / (second.y() - first.y());
                if (xAtPointY > point.x()) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    private static boolean pointOnSegment(
            final Point2D point,
            final Point2D start,
            final Point2D end) {
        return orientation(start, end, point) == 0
                && point.x() >= Math.min(start.x(), end.x()) - 1.0e-9
                && point.x() <= Math.max(start.x(), end.x()) + 1.0e-9
                && point.y() >= Math.min(start.y(), end.y()) - 1.0e-9
                && point.y() <= Math.max(start.y(), end.y()) + 1.0e-9;
    }

    private static int cellIndex(
            final double coordinate,
            final double cellSize,
            final int gridSide) {
        if (cellSize <= 0 || !Double.isFinite(cellSize)) {
            return 0;
        }
        return Math.max(0, Math.min(gridSide - 1,
                (int) Math.floor(coordinate / cellSize)));
    }

    private static double maximumCoordinateMagnitude(
            final List<Point2D> polygon) {
        double maximum = 1;
        for (final Point2D point : polygon) {
            maximum = Math.max(maximum,
                    Math.max(Math.abs(point.x()), Math.abs(point.y())));
        }
        return maximum;
    }

    private static boolean onlySharedEndpoint(
            final int firstIndex,
            final int secondIndex,
            final List<Point2D> polygon) {
        final int size = polygon.size();
        final Point2D firstStart = polygon.get(firstIndex);
        final Point2D firstEnd = polygon.get((firstIndex + 1) % size);
        final Point2D secondStart = polygon.get(secondIndex);
        final Point2D secondEnd = polygon.get((secondIndex + 1) % size);
        final Point2D shared = (firstIndex + 1) % size == secondIndex
                ? firstEnd : firstStart;
        return !strictlyOnSegment(firstStart, secondStart, secondEnd, shared)
                && !strictlyOnSegment(firstEnd, secondStart, secondEnd, shared)
                && !strictlyOnSegment(secondStart, firstStart, firstEnd, shared)
                && !strictlyOnSegment(secondEnd, firstStart, firstEnd, shared);
    }

    private static boolean strictlyOnSegment(
            final Point2D point,
            final Point2D start,
            final Point2D end,
            final Point2D excluded) {
        return !point.equals(excluded)
                && orientation(start, end, point) == 0
                && point.x() >= Math.min(start.x(), end.x()) - 1.0e-9
                && point.x() <= Math.max(start.x(), end.x()) + 1.0e-9
                && point.y() >= Math.min(start.y(), end.y()) - 1.0e-9
                && point.y() <= Math.max(start.y(), end.y()) + 1.0e-9;
    }

    private static boolean segmentsIntersect(
            final Point2D firstStart,
            final Point2D firstEnd,
            final Point2D secondStart,
            final Point2D secondEnd) {
        final int firstOrientation = orientation(
                firstStart, firstEnd, secondStart);
        final int secondOrientation = orientation(
                firstStart, firstEnd, secondEnd);
        final int thirdOrientation = orientation(
                secondStart, secondEnd, firstStart);
        final int fourthOrientation = orientation(
                secondStart, secondEnd, firstEnd);
        if (firstOrientation != secondOrientation
                && thirdOrientation != fourthOrientation) {
            return true;
        }
        return firstOrientation == 0 && onSegment(
                secondStart, firstStart, firstEnd)
                || secondOrientation == 0 && onSegment(
                secondEnd, firstStart, firstEnd)
                || thirdOrientation == 0 && onSegment(
                firstStart, secondStart, secondEnd)
                || fourthOrientation == 0 && onSegment(
                firstEnd, secondStart, secondEnd);
    }

    private static int orientation(
            final Point2D first,
            final Point2D second,
            final Point2D third) {
        final double cross = (second.x() - first.x())
                * (third.y() - first.y())
                - (second.y() - first.y())
                * (third.x() - first.x());
        if (Math.abs(cross) <= 1.0e-9) {
            return 0;
        }
        return cross < 0 ? -1 : 1;
    }

    private static boolean onSegment(
            final Point2D point,
            final Point2D start,
            final Point2D end) {
        return point.x() >= Math.min(start.x(), end.x()) - 1.0e-9
                && point.x() <= Math.max(start.x(), end.x()) + 1.0e-9
                && point.y() >= Math.min(start.y(), end.y()) - 1.0e-9
                && point.y() <= Math.max(start.y(), end.y()) + 1.0e-9;
    }

    private static void requireInsidePreview(
            final Point2D point,
            final int width,
            final int height,
            final String name) {
        Objects.requireNonNull(point, name);
        if (point.x() < 0 || point.x() > width
                || point.y() < 0 || point.y() > height) {
            throw new IllegalArgumentException(
                    name + " must lie inside the preview support domain");
        }
    }

    private static int targetControlCount(
            final int componentCount,
            final int requested) {
        if (componentCount == 0) {
            return 0;
        }
        final int minimum = Math.multiplyExact(
                componentCount, MINIMUM_CONTROLS_PER_COMPONENT);
        return Math.min(MAXIMUM_CONTROLS, Math.max(requested, minimum));
    }

    private static List<Integer> allocateControls(
            final List<List<Point2D>> loops,
            final int target) {
        if (loops.isEmpty()) {
            return List.of();
        }
        final int[] result = new int[loops.size()];
        final double[] lengths = new double[loops.size()];
        int assigned = 0;
        double totalLength = 0;
        for (int index = 0; index < loops.size(); index++) {
            result[index] = MINIMUM_CONTROLS_PER_COMPONENT;
            assigned += result[index];
            lengths[index] = perimeter(loops.get(index));
            totalLength += lengths[index];
        }
        int remaining = target - assigned;
        if (remaining > 0 && totalLength > 0) {
            final double[] fractions = new double[loops.size()];
            for (int index = 0; index < loops.size(); index++) {
                final double exact = remaining * lengths[index] / totalLength;
                final int extra = (int) Math.floor(exact);
                result[index] += extra;
                assigned += extra;
                fractions[index] = exact - extra;
            }
            int leftover = target - assigned;
            while (leftover-- > 0) {
                int best = 0;
                for (int index = 1; index < fractions.length; index++) {
                    if (fractions[index] > fractions[best]) {
                        best = index;
                    }
                }
                result[best]++;
                fractions[best] = -1;
            }
        }
        final List<Integer> boxed = new ArrayList<>();
        for (final int count : result) {
            boxed.add(count);
        }
        return List.copyOf(boxed);
    }

    private static double perimeter(final List<Point2D> polygon) {
        double result = 0;
        for (int index = 0; index < polygon.size(); index++) {
            final Point2D first = polygon.get(index);
            final Point2D second = polygon.get((index + 1) % polygon.size());
            result += Math.hypot(
                    second.x() - first.x(), second.y() - first.y());
        }
        return result;
    }

    /**
     * Adds vertices along existing straight boundary edges without cutting
     * corners. This keeps the rasterized suggestion identical to the source
     * mask even for tiny components whose raw contour has fewer than 24
     * vertices.
     */
    private static List<Point2D> densifyLoop(
            final List<Point2D> loop,
            final int minimumVertexCount) {
        if (loop.size() >= minimumVertexCount) {
            return List.copyOf(loop);
        }
        final int edgeCount = loop.size();
        final int[] subdivisions = new int[edgeCount];
        final double[] lengths = new double[edgeCount];
        int assigned = 0;
        double totalLength = 0;
        for (int index = 0; index < edgeCount; index++) {
            subdivisions[index] = 1;
            assigned++;
            final Point2D first = loop.get(index);
            final Point2D second = loop.get((index + 1) % edgeCount);
            lengths[index] = Math.hypot(
                    second.x() - first.x(), second.y() - first.y());
            totalLength += lengths[index];
        }
        int remaining = minimumVertexCount - assigned;
        final double[] fractions = new double[edgeCount];
        if (totalLength > 0) {
            for (int index = 0; index < edgeCount; index++) {
                final double exact = remaining * lengths[index] / totalLength;
                final int extra = (int) Math.floor(exact);
                subdivisions[index] += extra;
                assigned += extra;
                fractions[index] = exact - extra;
            }
        }
        while (assigned < minimumVertexCount) {
            int best = 0;
            for (int index = 1; index < edgeCount; index++) {
                if (fractions[index] > fractions[best]) {
                    best = index;
                }
            }
            subdivisions[best]++;
            fractions[best] = -1;
            assigned++;
        }
        final List<Point2D> result = new ArrayList<>();
        for (int index = 0; index < edgeCount; index++) {
            final Point2D first = loop.get(index);
            final Point2D second = loop.get((index + 1) % edgeCount);
            final int parts = subdivisions[index];
            for (int part = 0; part < parts; part++) {
                final double fraction = (double) part / parts;
                result.add(new Point2D(
                        first.x() + fraction * (second.x() - first.x()),
                        first.y() + fraction * (second.y() - first.y())));
            }
        }
        return List.copyOf(result);
    }

    /** Selects existing boundary vertices by perimeter distance. */
    private static List<Integer> selectVertices(
            final List<Point2D> loop,
            final int count) {
        if (loop.size() <= count) {
            final List<Integer> result = new ArrayList<>();
            for (int index = 0; index < loop.size(); index++) {
                result.add(index);
            }
            return List.copyOf(result);
        }
        final double total = perimeter(loop);
        final List<Integer> result = new ArrayList<>();
        int previous = -1;
        for (int sample = 0; sample < count; sample++) {
            final double target = total * sample / count;
            double traversed = 0;
            int selected = 0;
            for (int index = 0; index < loop.size(); index++) {
                final Point2D first = loop.get(index);
                final Point2D second = loop.get((index + 1) % loop.size());
                final double segment = Math.hypot(
                        second.x() - first.x(), second.y() - first.y());
                if (traversed + segment * 0.5 >= target) {
                    selected = index;
                    break;
                }
                traversed += segment;
                selected = index;
            }
            if (selected == previous) {
                selected = (selected + 1) % loop.size();
            }
            result.add(selected);
            previous = selected;
        }
        return List.copyOf(result);
    }

    private static List<List<Point2D>> boundaryLoops(
            final BinaryMask mask) {
        if (mask.isEmpty()) {
            return List.of();
        }
        final int width = mask.width();
        final int height = mask.height();
        final int[] labels = new int[Math.multiplyExact(width, height)];
        java.util.Arrays.fill(labels, -1);
        int componentCount = 0;
        final List<Integer> componentSizes = new ArrayList<>();
        final int[] offsetsX = {-1, 1, 0, 0};
        final int[] offsetsY = {0, 0, -1, 1};
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int index = y * width + x;
                if (!mask.contains(x, y) || labels[index] >= 0) {
                    continue;
                }
                final ArrayDeque<Integer> queue = new ArrayDeque<>();
                queue.add(index);
                labels[index] = componentCount;
                int componentSize = 0;
                while (!queue.isEmpty()) {
                    final int current = queue.removeFirst();
                    componentSize++;
                    final int currentX = current % width;
                    final int currentY = current / width;
                    for (int direction = 0; direction < 4; direction++) {
                        final int nextX = currentX + offsetsX[direction];
                        final int nextY = currentY + offsetsY[direction];
                        if (nextX < 0 || nextX >= width
                                || nextY < 0 || nextY >= height) {
                            continue;
                        }
                        final int next = nextY * width + nextX;
                        if (mask.contains(nextX, nextY)
                                && labels[next] < 0) {
                            labels[next] = componentCount;
                            queue.add(next);
                        }
                    }
                }
                componentSizes.add(componentSize);
                componentCount++;
            }
        }
        final int largestComponent = componentSizes.stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        int largestComponentIndex = 0;
        for (int component = 1; component < componentCount; component++) {
            if (componentSizes.get(component)
                    > componentSizes.get(largestComponentIndex)) {
                largestComponentIndex = component;
            }
        }
        final int minimumRetainedSize = Math.max(
                MINIMUM_COMPONENT_PIXELS,
                (int) Math.ceil(largestComponent
                        * MINIMUM_COMPONENT_AREA_FRACTION));
        final List<Integer> retainedComponents = new ArrayList<>();
        for (int component = 0; component < componentCount; component++) {
            if (componentSizes.get(component) >= minimumRetainedSize) {
                retainedComponents.add(component);
            }
        }
        retainedComponents.sort(Comparator
                .<Integer>comparingInt(componentSizes::get)
                .reversed()
                .thenComparingInt(Integer::intValue));
        if (retainedComponents.size() > MAXIMUM_COMPONENTS) {
            retainedComponents.subList(
                    MAXIMUM_COMPONENTS,
                    retainedComponents.size()).clear();
        }
        if (retainedComponents.isEmpty() && componentCount > 0) {
            retainedComponents.add(largestComponentIndex);
        }
        final List<List<Point2D>> result = new ArrayList<>();
        for (final int component : retainedComponents) {
            final List<Edge> edges = new ArrayList<>();
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (labels[y * width + x] != component) {
                        continue;
                    }
                    if (x == 0 || labels[y * width + x - 1] != component) {
                        edges.add(new Edge(x, y + 1, x, y));
                    }
                    if (x + 1 == width
                            || labels[y * width + x + 1] != component) {
                        edges.add(new Edge(x + 1, y, x + 1, y + 1));
                    }
                    if (y == 0 || labels[(y - 1) * width + x] != component) {
                        edges.add(new Edge(x, y, x + 1, y));
                    }
                    if (y + 1 == height
                            || labels[(y + 1) * width + x] != component) {
                        edges.add(new Edge(x + 1, y + 1, x, y + 1));
                    }
                }
            }
            edges.sort(Comparator
                    .comparingInt(Edge::startY)
                    .thenComparingInt(Edge::startX)
                    .thenComparingInt(Edge::endY)
                    .thenComparingInt(Edge::endX));
            final Map<Vertex, List<Edge>> outgoing = new LinkedHashMap<>();
            for (final Edge edge : edges) {
                outgoing.computeIfAbsent(edge.start(), ignored ->
                        new ArrayList<>()).add(edge);
            }
            final LinkedHashSet<Edge> unused = new LinkedHashSet<>(edges);
            while (!unused.isEmpty()) {
                final Edge first = unused.iterator().next();
                final Vertex start = first.start();
                Edge edge = first;
                final List<Point2D> loop = new ArrayList<>();
                int guard = 0;
                boolean closed = false;
                do {
                    if (!unused.remove(edge)) {
                        break;
                    }
                    loop.add(new Point2D(edge.startX(), edge.startY()));
                    if (edge.end().equals(start)) {
                        closed = true;
                        break;
                    }
                    final List<Edge> choices = outgoing.get(edge.end());
                    final Edge next = choices == null
                            ? null
                            : nextBoundaryEdge(edge, choices, unused);
                    if (next == null) {
                        break;
                    }
                    edge = next;
                    guard++;
                } while (guard <= edges.size());
                if (loop.size() >= 3 && closed) {
                    result.addAll(splitRepeatedBoundaryVertices(loop));
                }
            }
        }
        /*
         * A connected foreground component can have multiple boundary
         * loops: the positive-area loop is the filled outer support and the
         * negative-area loops are segmentation holes. New reviewer supports
         * intentionally fill those internal holes so they neither become
         * crop components nor consume the finite crop-control budget.
         */
        final List<List<Point2D>> outerLoops = result.stream()
                .filter(loop -> signedAreaTwice(loop) > 0)
                .collect(java.util.stream.Collectors.toCollection(
                        ArrayList::new));
        final List<List<Point2D>> retained;
        if (outerLoops.isEmpty()) {
            retained = new ArrayList<>();
            result.stream()
                    .max(Comparator.comparingDouble(
                            loop -> Math.abs(signedAreaTwice(loop))))
                    .ifPresent(retained::add);
        } else {
            retained = outerLoops;
        }
        retained.sort(Comparator
                .comparingDouble(ReviewedTissueSupport::minimumY)
                .thenComparingDouble(ReviewedTissueSupport::minimumX)
                .thenComparingInt(List::size));
        return List.copyOf(retained);
    }

    /**
     * Pixel unions can have a boundary that touches itself at one vertex when
     * an internal hole closes diagonally against the outer contour. Split that
     * traced figure eight into its simple constituent cycles before choosing
     * the positive-area outer support.
     */
    private static List<List<Point2D>> splitRepeatedBoundaryVertices(
            final List<Point2D> tracedLoop) {
        final ArrayDeque<List<Point2D>> pending = new ArrayDeque<>();
        final List<List<Point2D>> result = new ArrayList<>();
        pending.add(List.copyOf(tracedLoop));
        while (!pending.isEmpty()) {
            final List<Point2D> loop = pending.removeFirst();
            final Map<Point2D, Integer> seen = new LinkedHashMap<>();
            boolean split = false;
            for (int second = 0; second < loop.size(); second++) {
                final Integer first = seen.putIfAbsent(
                        loop.get(second), second);
                if (first == null) {
                    continue;
                }
                final List<Point2D> enclosed = new ArrayList<>(
                        loop.subList(first, second));
                final List<Point2D> remainder = new ArrayList<>(
                        loop.subList(0, first + 1));
                remainder.addAll(loop.subList(second + 1, loop.size()));
                if (enclosed.size() < 3 || remainder.size() < 3) {
                    continue;
                }
                pending.addFirst(List.copyOf(remainder));
                pending.addFirst(List.copyOf(enclosed));
                split = true;
                break;
            }
            if (!split) {
                result.add(List.copyOf(loop));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Follows the pixel union with foreground on the right. At a diagonal
     * point contact two unused edges can leave the same vertex; choosing the
     * rightmost continuation keeps the outer and hole cycles separate instead
     * of splicing them into a self-touching figure-eight polygon.
     */
    private static Edge nextBoundaryEdge(
            final Edge incoming,
            final List<Edge> choices,
            final LinkedHashSet<Edge> unused) {
        final int incomingDirection = boundaryDirection(incoming);
        return choices.stream()
                .filter(unused::contains)
                .min(Comparator
                        .comparingInt((Edge candidate) -> turnPriority(
                                incomingDirection,
                                boundaryDirection(candidate)))
                        .thenComparingInt(Edge::endY)
                        .thenComparingInt(Edge::endX))
                .orElse(null);
    }

    private static int boundaryDirection(final Edge edge) {
        if (edge.endX() > edge.startX()) {
            return 0; // east
        }
        if (edge.endY() > edge.startY()) {
            return 1; // south
        }
        if (edge.endX() < edge.startX()) {
            return 2; // west
        }
        return 3; // north
    }

    private static int turnPriority(
            final int incomingDirection,
            final int outgoingDirection) {
        final int clockwiseQuarterTurns =
                (outgoingDirection - incomingDirection + 4) % 4;
        return switch (clockwiseQuarterTurns) {
            case 1 -> 0; // right
            case 0 -> 1; // straight
            case 3 -> 2; // left
            default -> 3; // reverse (only as a fail-closed last resort)
        };
    }

    private static double signedAreaTwice(final List<Point2D> polygon) {
        double result = 0;
        for (int index = 0; index < polygon.size(); index++) {
            final Point2D first = polygon.get(index);
            final Point2D second = polygon.get((index + 1) % polygon.size());
            result += first.x() * second.y() - second.x() * first.y();
        }
        return result;
    }

    private static double minimumX(final List<Point2D> polygon) {
        return polygon.stream().mapToDouble(Point2D::x).min().orElse(0);
    }

    private static double minimumY(final List<Point2D> polygon) {
        return polygon.stream().mapToDouble(Point2D::y).min().orElse(0);
    }

    private static String controlId(
            final int component,
            final int vertex) {
        return "tissue-support-c" + component + "-v" + vertex;
    }

    private static void requireControlCount(final int count) {
        if (count < MINIMUM_CONTROLS_PER_COMPONENT || count > MAXIMUM_CONTROLS) {
            throw new IllegalArgumentException(
                    "Crop-support density must be between "
                            + MINIMUM_CONTROLS_PER_COMPONENT + " and "
                            + MAXIMUM_CONTROLS);
        }
    }

    private static String maskSha256(final BinaryMask mask) {
        final MessageDigest digest = sha256Digest();
        updateInt(digest, mask.width());
        updateInt(digest, mask.height());
        final byte[] bits = mask.copyBits().toByteArray();
        updateInt(digest, bits.length);
        digest.update(bits);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String computeSha256(
            final int width,
            final int height,
            final String sourceMaskSha256,
            final List<List<Point2D>> polygons,
            final List<Control> controls) {
        final MessageDigest digest = sha256Digest();
        updateInt(digest, width);
        updateInt(digest, height);
        updateText(digest, sourceMaskSha256);
        updateInt(digest, polygons.size());
        for (final List<Point2D> polygon : polygons) {
            updateInt(digest, polygon.size());
            for (final Point2D point : polygon) {
                updateDouble(digest, point.x());
                updateDouble(digest, point.y());
            }
        }
        updateInt(digest, controls.size());
        for (final Control control : controls) {
            updateText(digest, control.id());
            updateInt(digest, control.componentIndex());
            updateInt(digest, control.vertexIndex());
            updateDouble(digest, control.origin().x());
            updateDouble(digest, control.origin().y());
            updateDouble(digest, control.point().x());
            updateDouble(digest, control.point().y());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static void updateInt(final MessageDigest digest, final int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateDouble(
            final MessageDigest digest,
            final double value) {
        final long bits = Double.doubleToLongBits(value);
        for (int shift = 56; shift >= 0; shift -= 8) {
            digest.update((byte) (bits >>> shift));
        }
    }

    private static void updateText(
            final MessageDigest digest,
            final String text) {
        final byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static String requireText(
            final String value,
            final String name) {
        final String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }

    private static String requireSha256(
            final String value,
            final String name) {
        final String checked = requireText(value, name);
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be a lowercase SHA-256 value");
        }
        return checked;
    }
}
