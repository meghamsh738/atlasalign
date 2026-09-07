package org.atlasalign.application.manual;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;

/**
 * Exact-boundary, reviewer-controlled map between two simple FULL-section
 * polygons in preview-pixel space.
 *
 * <p>Each polygon is triangulated independently. Both triangulations are
 * embedded into the same strictly convex canonical polygon using their shared
 * monotone boundary order. Intersections of source and target canonical
 * triangles form a deterministic common refinement. Every common cell is
 * triangulated and materialized as one affine source-to-target triangle.
 * Consequently the reviewed target boundary and domain containment are
 * representation invariants rather than soft residual scores.</p>
 *
 * <p>This class is pure geometry. It never changes an atlas plane, tilt,
 * reflection, laterality, source image, or confidence state.</p>
 */
public final class BoundaryAuthoritativeTransform2D
        implements ReviewedOutlineTransform2D {

    public static final String ALGORITHM_REVISION =
            "boundary-authoritative-numerically-robust-common-refinement-pwa-v2";
    public static final String PIXEL_CENTER_CONVENTION =
            "top-left-pixel-center-is-(0,0)";
    public static final double MAXIMUM_BOUNDARY_ERROR_PIXELS = 0.25;

    private static final double PARAMETER_EPSILON = 1e-13;
    private static final double GEOMETRY_RELATIVE_EPSILON = 1e-12;
    private static final double POINT_LOCATION_RELATIVE_EPSILON = 1e-10;
    /** Unit-disk canonical calculations must not inherit preview-pixel scale. */
    private static final double CANONICAL_AREA_EPSILON =
            64.0 * Math.ulp(1.0);
    private static final double CANONICAL_POINT_EPSILON = 1e-12;

    private final MonotoneBoundary2D atlasInputBoundary;
    private final MonotoneBoundary2D tissueInputBoundary;
    private final List<Double> commonBoundaryParameters;
    private final List<Point2D> atlasBoundary;
    private final List<Point2D> tissueBoundary;
    private final List<MeshTriangle> triangles;
    private final Diagnostics diagnostics;
    private final TriangleSpatialIndex sourceTriangleIndex;
    private final TriangleSpatialIndex targetTriangleIndex;
    private volatile MidlineGeometry cachedMidlineGeometry;

    private BoundaryAuthoritativeTransform2D(
            final MonotoneBoundary2D atlasInputBoundary,
            final MonotoneBoundary2D tissueInputBoundary,
            final List<Double> commonBoundaryParameters,
            final List<Point2D> atlasBoundary,
            final List<Point2D> tissueBoundary,
            final List<MeshTriangle> triangles,
            final Diagnostics diagnostics) {
        this.atlasInputBoundary = atlasInputBoundary;
        this.tissueInputBoundary = tissueInputBoundary;
        this.commonBoundaryParameters = List.copyOf(commonBoundaryParameters);
        this.atlasBoundary = List.copyOf(atlasBoundary);
        this.tissueBoundary = List.copyOf(tissueBoundary);
        this.triangles = List.copyOf(triangles);
        this.diagnostics = diagnostics;
        final double locationEpsilon = locationEpsilon();
        this.sourceTriangleIndex = TriangleSpatialIndex.build(
                this.triangles, true, locationEpsilon);
        this.targetTriangleIndex = TriangleSpatialIndex.build(
                this.triangles, false, locationEpsilon);
    }

    /** Builds one exact map for one atlas and one reviewed FULL-section loop. */
    public static BoundaryAuthoritativeTransform2D fitFull(
            final MonotoneBoundary2D atlasBoundary,
            final MonotoneBoundary2D tissueBoundary,
            final int previewWidth,
            final int previewHeight) {
        Objects.requireNonNull(atlasBoundary, "atlasBoundary");
        Objects.requireNonNull(tissueBoundary, "tissueBoundary");
        if (previewWidth <= 1 || previewHeight <= 1) {
            throw new IllegalArgumentException(
                    "Preview width and height must exceed one pixel");
        }

        validateSimpleBoundary(atlasBoundary, "atlas boundary");
        validateSimpleBoundary(tissueBoundary, "tissue boundary");
        final double atlasArea = signedArea(points(atlasBoundary));
        final double tissueArea = signedArea(points(tissueBoundary));
        if (Math.signum(atlasArea) != Math.signum(tissueArea)) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.INCONSISTENT_ORIENTATION,
                    "atlas/tissue boundary",
                    "Atlas and tissue boundary traversal orientations must agree"));
        }

        final List<Double> parameters = commonParameters(
                atlasBoundary, tissueBoundary);
        List<Point2D> atlas = refine(atlasBoundary, parameters);
        List<Point2D> tissue = refine(tissueBoundary, parameters);
        if (atlasArea < 0) {
            atlas = reverseKeepingFirst(atlas);
            tissue = reverseKeepingFirst(tissue);
        }
        final double diagonal = Math.hypot(previewWidth, previewHeight);
        final double areaEpsilon = GEOMETRY_RELATIVE_EPSILON
                * Math.max(1.0, diagonal * diagonal);

        final List<Point2D> canonicalBoundary = regularCanonicalBoundary(
                parameters);
        final List<IndexedTriangle> atlasTriangulation = triangulate(
                atlas, "atlas boundary", areaEpsilon);
        final List<IndexedTriangle> tissueTriangulation = triangulate(
                tissue, "tissue boundary", areaEpsilon);
        final List<MeshTriangle> mesh = commonRefinement(
                atlas, tissue, canonicalBoundary,
                atlasTriangulation, tissueTriangulation,
                CANONICAL_AREA_EPSILON);
        if (mesh.isEmpty()) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.TRIANGULATION_FAILURE,
                    "atlas/tissue boundary",
                    "The common canonical refinement produced no positive-area triangles"));
        }

        final BoundaryAuthoritativeTransform2D provisional =
                new BoundaryAuthoritativeTransform2D(
                        atlasBoundary, tissueBoundary, parameters,
                        atlas, tissue, mesh,
                        new Diagnostics(ALGORITHM_REVISION,
                                PIXEL_CENTER_CONVENTION,
                                previewWidth, previewHeight,
                                parameters.size(), atlasTriangulation.size(),
                                tissueTriangulation.size(), mesh.size(),
                                Double.NaN, Double.NaN, Double.NaN,
                                Double.NaN, Double.NaN, Double.NaN,
                                Double.NaN, "pending"));
        final Audit audit = provisional.audit(diagonal, areaEpsilon);
        final String hash = contentHash(
                atlasBoundary, tissueBoundary, parameters,
                atlas, tissue, mesh, previewWidth, previewHeight);
        final Diagnostics diagnostics = new Diagnostics(
                ALGORITHM_REVISION, PIXEL_CENTER_CONVENTION,
                previewWidth, previewHeight,
                parameters.size(), atlasTriangulation.size(),
                tissueTriangulation.size(), mesh.size(),
                audit.maximumBoundaryError(),
                audit.minimumSourceDoubleArea(),
                audit.minimumTargetDoubleArea(),
                audit.sourceMeshArea(), audit.atlasPolygonArea(),
                audit.targetMeshArea(), audit.tissuePolygonArea(), hash);
        return new BoundaryAuthoritativeTransform2D(
                atlasBoundary, tissueBoundary, parameters,
                atlas, tissue, mesh, diagnostics);
    }

    /**
     * Entry point that explicitly rejects holes or disconnected components.
     * A future section type must define its own topology rather than silently
     * selecting or filling a component here.
     */
    public static BoundaryAuthoritativeTransform2D fitFullComponents(
            final List<MonotoneBoundary2D> atlasComponents,
            final List<MonotoneBoundary2D> tissueComponents,
            final int previewWidth,
            final int previewHeight) {
        final List<MonotoneBoundary2D> atlas = List.copyOf(
                Objects.requireNonNull(atlasComponents, "atlasComponents"));
        final List<MonotoneBoundary2D> tissue = List.copyOf(
                Objects.requireNonNull(tissueComponents, "tissueComponents"));
        if (atlas.size() != 1 || tissue.size() != 1) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.UNSUPPORTED_TOPOLOGY,
                    "atlas/tissue boundary",
                    "FULL-section boundary-authoritative mapping requires exactly one outer component and no holes"));
        }
        return fitFull(atlas.get(0), tissue.get(0),
                previewWidth, previewHeight);
    }

    public CoordinateSpace2D sourceSpace() {
        return CoordinateSpace2D.PREVIEW_PIXEL;
    }

    public CoordinateSpace2D destinationSpace() {
        return CoordinateSpace2D.PREVIEW_PIXEL;
    }

    public Point2D apply(final Point2D atlasPreviewPoint) {
        final Point2D point = Objects.requireNonNull(
                atlasPreviewPoint, "atlasPreviewPoint");
        final MeshTriangle triangle = locateSourceTriangle(point);
        if (triangle == null) {
            throw failure(BoundaryGeometryFailure.local(
                    BoundaryGeometryFailure.Kind.OUTSIDE_DOMAIN,
                    "atlas boundary", -1 + 1, -1 + 1, point,
                    "Atlas coordinate lies outside the accepted atlas boundary"));
        }
        final Point2D mapped = triangle.map(point);
        if (!containsInclusive(tissueBoundary, mapped, locationEpsilon())) {
            throw failure(BoundaryGeometryFailure.local(
                    BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                    "tissue boundary", 0, 0, mapped,
                    "A mapped atlas coordinate escaped the reviewed tissue domain"));
        }
        return mapped;
    }

    @Override
    public Point2D inverse(final Point2D tissuePreviewPoint) {
        final Point2D point = Objects.requireNonNull(
                tissuePreviewPoint, "tissuePreviewPoint");
        final MeshTriangle triangle = locateTargetTriangle(point);
        if (triangle == null) {
            throw failure(BoundaryGeometryFailure.local(
                    BoundaryGeometryFailure.Kind.OUTSIDE_DOMAIN,
                    "tissue boundary", 0, 0, point,
                    "Tissue coordinate lies outside the reviewed tissue boundary"));
        }
        final Point2D mapped = mapBarycentric(
                point, triangle.target(), triangle.source());
        if (!containsInclusive(atlasBoundary, mapped, locationEpsilon())) {
            throw failure(BoundaryGeometryFailure.local(
                    BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                    "atlas boundary", 0, 0, mapped,
                    "An inverse-mapped coordinate escaped the atlas domain"));
        }
        return mapped;
    }

    @Override
    public int previewWidth() {
        return diagnostics.previewWidth();
    }

    @Override
    public int previewHeight() {
        return diagnostics.previewHeight();
    }

    @Override
    public String algorithmRevision() {
        return diagnostics.algorithmRevision();
    }

    @Override
    public String contentSha256() {
        return diagnostics.contentSha256();
    }

    @Override
    public MidlineEndpoints hemisphereMidline() {
        final List<Point2D> path = hemisphereMidlinePath();
        return new MidlineEndpoints(path.get(0), path.get(path.size() - 1));
    }

    /** Exact image of the atlas centreline through the authoritative PWA. */
    public List<Point2D> hemisphereMidlinePath() {
        return midlineGeometry().mappedPath();
    }

    /** Atlas-domain dorsal/ventral endpoints of the mapped centreline. */
    public MidlineEndpoints atlasHemisphereMidline() {
        return midlineGeometry().atlasEndpoints();
    }

    /** Exact final-tissue boundary of one image-side domain. */
    public List<Point2D> tissueHemisphereBoundary(
            final boolean imageLeft) {
        return imageLeft ? midlineGeometry().imageLeftBoundary()
                : midlineGeometry().imageRightBoundary();
    }

    /** True only for a point contained by the exact requested side domain. */
    public boolean containsTissueHemispherePoint(
            final boolean imageLeft,
            final Point2D point) {
        return containsInclusive(tissueHemisphereBoundary(imageLeft),
                Objects.requireNonNull(point, "point"), locationEpsilon());
    }

    private MidlineGeometry midlineGeometry() {
        MidlineGeometry geometry = cachedMidlineGeometry;
        if (geometry != null) {
            return geometry;
        }
        synchronized (this) {
            geometry = cachedMidlineGeometry;
            if (geometry == null) {
                geometry = buildMidlineGeometry();
                cachedMidlineGeometry = geometry;
            }
            return geometry;
        }
    }

    private MidlineGeometry buildMidlineGeometry() {
        final MidlineEndpoints atlasEndpoints = verticalInteriorChord(
                atlasBoundary, "atlas boundary");
        final List<Point2D> mappedPath = mapSegmentThroughMesh(
                atlasEndpoints.dorsal(), atlasEndpoints.ventral());
        final PairedBoundary paired = insertPairedBoundaryEndpoints(
                atlasEndpoints);
        final int dorsal = nearestBoundaryVertex(
                paired.atlas(), atlasEndpoints.dorsal());
        final int ventral = nearestBoundaryVertex(
                paired.atlas(), atlasEndpoints.ventral());
        final BoundaryArcPair forward = new BoundaryArcPair(
                boundaryPath(paired.atlas(), dorsal, ventral, 1),
                boundaryPath(paired.tissue(), dorsal, ventral, 1));
        final BoundaryArcPair backward = new BoundaryArcPair(
                boundaryPath(paired.atlas(), dorsal, ventral, -1),
                boundaryPath(paired.tissue(), dorsal, ventral, -1));
        final double centerX = Bounds.of(atlasBoundary).centerX();
        final BoundaryArcPair left = chooseAtlasSideArc(
                forward, backward, centerX, true);
        final BoundaryArcPair right = left == forward ? backward : forward;
        return new MidlineGeometry(atlasEndpoints, mappedPath,
                closeWithMappedSeam(left.tissue(), mappedPath),
                closeWithMappedSeam(right.tissue(), mappedPath));
    }

    private MidlineEndpoints verticalInteriorChord(
            final List<Point2D> polygon,
            final String boundaryName) {
        final Bounds bounds = Bounds.of(polygon);
        final double centerX = bounds.centerX();
        final double epsilon = locationEpsilon();
        final List<Point2D> intersections = new ArrayList<>();
        for (int index = 0; index < polygon.size(); index++) {
            final Point2D first = polygon.get(index);
            final Point2D second = polygon.get((index + 1) % polygon.size());
            final double dx = second.x() - first.x();
            if (Math.abs(dx) <= epsilon) {
                if (Math.abs(first.x() - centerX) <= epsilon) {
                    throw failure(BoundaryGeometryFailure.global(
                            BoundaryGeometryFailure.Kind.UNSUPPORTED_TOPOLOGY,
                            boundaryName,
                            "The boundary overlaps the atlas centreline; local side refinement cannot be separated safely"));
                }
                continue;
            }
            final double parameter = (centerX - first.x()) / dx;
            if (parameter < -epsilon || parameter > 1.0 + epsilon) {
                continue;
            }
            final double clamped = Math.max(0.0, Math.min(1.0, parameter));
            intersections.add(new Point2D(centerX,
                    first.y() + clamped * (second.y() - first.y())));
        }
        intersections.sort(Comparator
                .comparingDouble(Point2D::y)
                .thenComparingDouble(Point2D::x));
        final List<Point2D> distinct = new ArrayList<>();
        for (final Point2D point : intersections) {
            if (distinct.isEmpty()
                    || distance(distinct.get(distinct.size() - 1), point)
                            > epsilon) {
                distinct.add(point);
            }
        }
        if (distinct.size() < 2) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.UNSUPPORTED_TOPOLOGY,
                    boundaryName,
                    "The boundary does not intersect the atlas centreline at two distinct points"));
        }
        final Point2D dorsal = distinct.get(0);
        final Point2D ventral = distinct.get(distinct.size() - 1);
        for (int index = 0; index + 1 < distinct.size(); index++) {
            final Point2D first = distinct.get(index);
            final Point2D second = distinct.get(index + 1);
            if (distance(first, second) <= epsilon) {
                continue;
            }
            final Point2D midpoint = interpolate(first, second, 0.5);
            if (!containsInclusive(polygon, midpoint, epsilon)) {
                throw failure(BoundaryGeometryFailure.global(
                        BoundaryGeometryFailure.Kind.UNSUPPORTED_TOPOLOGY,
                        boundaryName,
                        "The atlas centreline is disconnected inside the boundary; local side refinement cannot be separated safely"));
            }
        }
        return new MidlineEndpoints(dorsal, ventral);
    }

    private List<Point2D> mapSegmentThroughMesh(
            final Point2D start,
            final Point2D end) {
        final List<MappedInterval> intervals = new ArrayList<>();
        final double epsilon = locationEpsilon();
        for (final MeshTriangle triangle : triangles) {
            final List<Double> parameters = new ArrayList<>();
            if (triangle.source().contains(start, epsilon)) {
                parameters.add(0.0);
            }
            if (triangle.source().contains(end, epsilon)) {
                parameters.add(1.0);
            }
            addSegmentEdgeIntersections(parameters, start, end,
                    triangle.source().a(), triangle.source().b());
            addSegmentEdgeIntersections(parameters, start, end,
                    triangle.source().b(), triangle.source().c());
            addSegmentEdgeIntersections(parameters, start, end,
                    triangle.source().c(), triangle.source().a());
            parameters.sort(Double::compare);
            final List<Double> unique = uniqueClampedParameters(parameters);
            if (unique.size() < 2) {
                continue;
            }
            final double first = unique.get(0);
            final double last = unique.get(unique.size() - 1);
            if (last - first <= PARAMETER_EPSILON
                    || !triangle.source().contains(interpolate(
                            start, end, 0.5 * (first + last)), epsilon)) {
                continue;
            }
            intervals.add(new MappedInterval(first, last, triangle));
        }
        intervals.sort(Comparator
                .comparingDouble(MappedInterval::start)
                .thenComparingDouble(MappedInterval::end)
                .thenComparingInt(value -> value.triangle().id()));
        final List<Point2D> mapped = new ArrayList<>();
        double covered = 0.0;
        while (covered < 1.0 - PARAMETER_EPSILON) {
            MappedInterval selected = null;
            for (final MappedInterval candidate : intervals) {
                if (candidate.start() > covered + PARAMETER_EPSILON) {
                    break;
                }
                if (candidate.end() <= covered + PARAMETER_EPSILON) {
                    continue;
                }
                if (selected == null
                        || candidate.end() > selected.end()
                                + PARAMETER_EPSILON
                        || Math.abs(candidate.end() - selected.end())
                                <= PARAMETER_EPSILON
                                && candidate.triangle().id()
                                < selected.triangle().id()) {
                    selected = candidate;
                }
            }
            if (selected == null) {
                throw failure(BoundaryGeometryFailure.global(
                        BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                        "atlas midline",
                        "The exact mesh does not continuously cover the atlas centreline"));
            }
            final Point2D mappedStart = selected.triangle().map(
                    interpolate(start, end, covered));
            if (!mapped.isEmpty() && distance(
                    mapped.get(mapped.size() - 1), mappedStart) > epsilon) {
                throw failure(BoundaryGeometryFailure.global(
                        BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                        "atlas midline",
                        "Adjacent exact cells disagree on the mapped atlas centreline"));
            }
            addDistinct(mapped, mappedStart);
            covered = Math.min(1.0, selected.end());
            addDistinct(mapped, selected.triangle().map(
                    interpolate(start, end, covered)));
        }
        if (mapped.size() < 2) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                    "atlas midline",
                    "The mapped atlas centreline is degenerate"));
        }
        return List.copyOf(mapped);
    }

    private static List<Double> uniqueClampedParameters(
            final List<Double> parameters) {
        final List<Double> unique = new ArrayList<>();
        for (final double parameter : parameters) {
            final double clamped = Math.max(0.0, Math.min(1.0, parameter));
            if (unique.isEmpty() || Math.abs(clamped
                    - unique.get(unique.size() - 1)) > PARAMETER_EPSILON) {
                unique.add(clamped);
            }
        }
        return List.copyOf(unique);
    }

    private PairedBoundary insertPairedBoundaryEndpoints(
            final MidlineEndpoints endpoints) {
        final List<Point2D> atlas = new ArrayList<>(atlasBoundary);
        final List<Point2D> tissue = new ArrayList<>(tissueBoundary);
        insertPairedBoundaryPoint(atlas, tissue, endpoints.dorsal());
        insertPairedBoundaryPoint(atlas, tissue, endpoints.ventral());
        return new PairedBoundary(List.copyOf(atlas), List.copyOf(tissue));
    }

    private void insertPairedBoundaryPoint(
            final List<Point2D> atlas,
            final List<Point2D> tissue,
            final Point2D target) {
        final double epsilon = locationEpsilon();
        for (final Point2D point : atlas) {
            if (distance(point, target) <= epsilon) {
                return;
            }
        }
        for (int index = 0; index < atlas.size(); index++) {
            final Point2D first = atlas.get(index);
            final Point2D second = atlas.get((index + 1) % atlas.size());
            if (distanceToSegment(target, first, second) > epsilon) {
                continue;
            }
            final double dx = second.x() - first.x();
            final double dy = second.y() - first.y();
            final double lengthSquared = dx * dx + dy * dy;
            final double parameter = lengthSquared == 0 ? 0 : Math.max(0,
                    Math.min(1, ((target.x() - first.x()) * dx
                            + (target.y() - first.y()) * dy)
                            / lengthSquared));
            final Point2D tissuePoint = interpolate(tissue.get(index),
                    tissue.get((index + 1) % tissue.size()), parameter);
            atlas.add(index + 1, target);
            tissue.add(index + 1, tissuePoint);
            return;
        }
        throw failure(BoundaryGeometryFailure.global(
                BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                "atlas midline",
                "An atlas midline endpoint is not on the exact atlas boundary"));
    }

    private int nearestBoundaryVertex(
            final List<Point2D> boundary,
            final Point2D target) {
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < boundary.size(); index++) {
            final double candidate = distance(boundary.get(index), target);
            if (candidate < bestDistance) {
                best = index;
                bestDistance = candidate;
            }
        }
        if (best < 0 || bestDistance > locationEpsilon()) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                    "atlas midline",
                    "An atlas midline endpoint was lost from the paired boundary"));
        }
        return best;
    }

    private static List<Point2D> boundaryPath(
            final List<Point2D> points,
            final int from,
            final int to,
            final int step) {
        final List<Point2D> result = new ArrayList<>();
        int index = from;
        result.add(points.get(index));
        while (index != to) {
            index = (index + step + points.size()) % points.size();
            result.add(points.get(index));
            if (result.size() > points.size() + 1) {
                throw new IllegalArgumentException(
                        "Boundary traversal did not reach the atlas midline endpoint");
            }
        }
        return List.copyOf(result);
    }

    private BoundaryArcPair chooseAtlasSideArc(
            final BoundaryArcPair first,
            final BoundaryArcPair second,
            final double centerX,
            final boolean imageLeft) {
        final double firstScore = atlasSideArcScore(
                first.atlas(), centerX, imageLeft);
        final double secondScore = atlasSideArcScore(
                second.atlas(), centerX, imageLeft);
        if (!Double.isFinite(firstScore) && !Double.isFinite(secondScore)) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.UNSUPPORTED_TOPOLOGY,
                    "atlas boundary",
                    "The atlas boundary does not separate into valid image-side arcs"));
        }
        return firstScore >= secondScore ? first : second;
    }

    private double atlasSideArcScore(
            final List<Point2D> arc,
            final double centerX,
            final boolean imageLeft) {
        double score = 0;
        for (int index = 1; index + 1 < arc.size(); index++) {
            final double offset = imageLeft
                    ? centerX - arc.get(index).x()
                    : arc.get(index).x() - centerX;
            if (offset < -locationEpsilon()) {
                return Double.NEGATIVE_INFINITY;
            }
            score += Math.max(0.0, offset);
        }
        return score;
    }

    private List<Point2D> closeWithMappedSeam(
            final List<Point2D> tissueArc,
            final List<Point2D> mappedPath) {
        final List<Point2D> polygon = new ArrayList<>(tissueArc);
        for (int index = mappedPath.size() - 2; index > 0; index--) {
            addDistinct(polygon, mappedPath.get(index));
        }
        if (polygon.size() < 3
                || Math.abs(signedArea(polygon)) <= locationEpsilon()) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.UNSUPPORTED_TOPOLOGY,
                    "mapped atlas midline",
                    "The mapped atlas centreline does not define two non-degenerate tissue sides"));
        }
        return List.copyOf(polygon);
    }

    public boolean containsAtlasPoint(final Point2D point) {
        return containsInclusive(atlasBoundary,
                Objects.requireNonNull(point, "point"), locationEpsilon());
    }

    public boolean containsTissuePoint(final Point2D point) {
        return containsInclusive(tissueBoundary,
                Objects.requireNonNull(point, "point"), locationEpsilon());
    }

    /**
     * Maps an atlas ROI path after splitting every segment at every crossed
     * source-mesh edge. The returned vertices therefore represent every
     * piecewise-affine bend exactly rather than connecting sparse ROI vertices
     * with a false straight chord.
     */
    public List<Point2D> mapPath(
            final List<Point2D> atlasPath, final boolean closed) {
        final List<Point2D> path = List.copyOf(
                Objects.requireNonNull(atlasPath, "atlasPath"));
        final int minimum = closed ? 3 : 2;
        if (path.size() < minimum) {
            throw new IllegalArgumentException(
                    "The atlas path requires at least " + minimum + " points");
        }
        final List<Point2D> result = new ArrayList<>();
        final int edgeCount = closed ? path.size() : path.size() - 1;
        for (int edge = 0; edge < edgeCount; edge++) {
            final Point2D start = path.get(edge);
            final Point2D end = path.get((edge + 1) % path.size());
            if (!containsAtlasPoint(start) || !containsAtlasPoint(end)) {
                final Point2D outside = !containsAtlasPoint(start)
                        ? start : end;
                throw failure(BoundaryGeometryFailure.local(
                        BoundaryGeometryFailure.Kind.OUTSIDE_DOMAIN,
                        "atlas ROI path", edge, edge, outside,
                        "Every accepted atlas ROI segment endpoint must lie inside the atlas domain"));
            }
            final List<Double> splitParameters = segmentSplitParameters(
                    start, end);
            for (int index = 0; index < splitParameters.size(); index++) {
                if (edge > 0 && index == 0) {
                    continue;
                }
                final double parameter = splitParameters.get(index);
                final Point2D source = interpolate(start, end, parameter);
                addDistinct(result, apply(source));
            }
        }
        if (closed && result.size() > 1
                && distance(result.get(0), result.get(result.size() - 1))
                        <= locationEpsilon()) {
            result.remove(result.size() - 1);
        }
        return List.copyOf(result);
    }

    public MonotoneBoundary2D atlasInputBoundary() {
        return atlasInputBoundary;
    }

    public MonotoneBoundary2D tissueInputBoundary() {
        return tissueInputBoundary;
    }

    public List<Double> commonBoundaryParameters() {
        return commonBoundaryParameters;
    }

    public List<Point2D> atlasBoundary() {
        return atlasBoundary;
    }

    public List<Point2D> tissueBoundary() {
        return tissueBoundary;
    }

    public List<MeshTriangle> triangles() {
        return triangles;
    }

    public Diagnostics diagnostics() {
        return diagnostics;
    }

    private MeshTriangle locateSourceTriangle(final Point2D point) {
        return sourceTriangleIndex.locate(
                triangles, point, locationEpsilon());
    }

    private MeshTriangle locateTargetTriangle(final Point2D point) {
        return targetTriangleIndex.locate(
                triangles, point, locationEpsilon());
    }

    private double locationEpsilon() {
        return POINT_LOCATION_RELATIVE_EPSILON * Math.max(1.0,
                Math.hypot(diagnostics.previewWidth(),
                        diagnostics.previewHeight()));
    }

    private List<Double> segmentSplitParameters(
            final Point2D start, final Point2D end) {
        final List<Double> parameters = new ArrayList<>();
        parameters.add(0.0);
        parameters.add(1.0);
        for (final MeshTriangle triangle : triangles) {
            addSegmentEdgeIntersections(parameters, start, end,
                    triangle.source().a(), triangle.source().b());
            addSegmentEdgeIntersections(parameters, start, end,
                    triangle.source().b(), triangle.source().c());
            addSegmentEdgeIntersections(parameters, start, end,
                    triangle.source().c(), triangle.source().a());
        }
        parameters.sort(Double::compare);
        final List<Double> unique = new ArrayList<>();
        for (final double parameter : parameters) {
            final double clamped = Math.max(0, Math.min(1, parameter));
            if (unique.isEmpty()
                    || Math.abs(clamped - unique.get(unique.size() - 1))
                            > PARAMETER_EPSILON) {
                unique.add(clamped);
            }
        }
        return List.copyOf(unique);
    }

    private static void addSegmentEdgeIntersections(
            final List<Double> parameters,
            final Point2D pathStart,
            final Point2D pathEnd,
            final Point2D edgeStart,
            final Point2D edgeEnd) {
        final double rx = pathEnd.x() - pathStart.x();
        final double ry = pathEnd.y() - pathStart.y();
        final double sx = edgeEnd.x() - edgeStart.x();
        final double sy = edgeEnd.y() - edgeStart.y();
        final double denominator = rx * sy - ry * sx;
        final double qx = edgeStart.x() - pathStart.x();
        final double qy = edgeStart.y() - pathStart.y();
        if (Math.abs(denominator) > 1e-14) {
            final double t = (qx * sy - qy * sx) / denominator;
            final double u = (qx * ry - qy * rx) / denominator;
            if (t >= -PARAMETER_EPSILON && t <= 1 + PARAMETER_EPSILON
                    && u >= -PARAMETER_EPSILON
                    && u <= 1 + PARAMETER_EPSILON) {
                parameters.add(t);
            }
            return;
        }
        if (Math.abs(qx * ry - qy * rx) > 1e-10) {
            return;
        }
        final double lengthSquared = rx * rx + ry * ry;
        if (!(lengthSquared > 0)) {
            return;
        }
        parameters.add(((edgeStart.x() - pathStart.x()) * rx
                + (edgeStart.y() - pathStart.y()) * ry) / lengthSquared);
        parameters.add(((edgeEnd.x() - pathStart.x()) * rx
                + (edgeEnd.y() - pathStart.y()) * ry) / lengthSquared);
    }

    private Audit audit(
            final double diagonal,
            final double areaEpsilon) {
        double minimumSourceArea = Double.POSITIVE_INFINITY;
        double minimumTargetArea = Double.POSITIVE_INFINITY;
        double sourceMeshArea = 0;
        double targetMeshArea = 0;
        for (int index = 0; index < triangles.size(); index++) {
            final MeshTriangle triangle = triangles.get(index);
            final double sourceArea = triangle.source().signedDoubleArea();
            final double targetArea = triangle.target().signedDoubleArea();
            if (robustOrientation(triangle.source().a(),
                            triangle.source().b(), triangle.source().c()) <= 0
                    || robustOrientation(triangle.target().a(),
                            triangle.target().b(),
                            triangle.target().c()) <= 0) {
                throw failure(BoundaryGeometryFailure.local(
                        BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                        "common-refined mesh", index, index,
                        triangle.target().centroid(),
                        "Every common-refined source and target triangle must retain positive orientation"));
            }
            minimumSourceArea = Math.min(minimumSourceArea, sourceArea);
            minimumTargetArea = Math.min(minimumTargetArea, targetArea);
            sourceMeshArea += 0.5 * sourceArea;
            targetMeshArea += 0.5 * targetArea;
            for (final Point2D point : List.of(
                    triangle.source().a(), triangle.source().b(),
                    triangle.source().c(), triangle.source().centroid())) {
                if (!containsInclusive(atlasBoundary, point,
                        POINT_LOCATION_RELATIVE_EPSILON
                                * Math.max(1.0, diagonal))) {
                    throw failure(BoundaryGeometryFailure.local(
                            BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                            "atlas boundary", index, index, point,
                            "A common-refined source triangle lies outside the atlas domain"));
                }
            }
            for (final Point2D point : List.of(
                    triangle.target().a(), triangle.target().b(),
                    triangle.target().c(), triangle.target().centroid())) {
                if (!containsInclusive(tissueBoundary, point,
                        POINT_LOCATION_RELATIVE_EPSILON
                                * Math.max(1.0, diagonal))) {
                    throw failure(BoundaryGeometryFailure.local(
                            BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                            "tissue boundary", index, index, point,
                            "A common-refined target triangle lies outside the reviewed tissue domain"));
                }
            }
        }

        final double coordinateEpsilon = POINT_LOCATION_RELATIVE_EPSILON
                * Math.max(1.0, diagonal);
        requireNoTriangleOverlap(true, coordinateEpsilon, areaEpsilon);
        requireNoTriangleOverlap(false, coordinateEpsilon, areaEpsilon);
        final double atlasArea = Math.abs(signedArea(atlasBoundary));
        final double tissueArea = Math.abs(signedArea(tissueBoundary));
        final double sourceAreaTolerance = Math.max(areaEpsilon,
                1e-9 * Math.max(1.0, atlasArea));
        final double targetAreaTolerance = Math.max(areaEpsilon,
                1e-9 * Math.max(1.0, tissueArea));
        if (Math.abs(sourceMeshArea - atlasArea) > sourceAreaTolerance) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                    "atlas boundary",
                    "The common-refined source mesh does not exactly cover the atlas polygon: meshArea="
                            + sourceMeshArea + ", polygonArea=" + atlasArea
                            + ", triangles=" + triangles.size()
                            + ", absoluteDifference="
                            + Math.abs(sourceMeshArea - atlasArea)
                            + ", tolerance=" + sourceAreaTolerance));
        }
        if (Math.abs(targetMeshArea - tissueArea) > targetAreaTolerance) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                    "tissue boundary",
                    "The common-refined target mesh does not exactly cover the reviewed tissue polygon: meshArea="
                            + targetMeshArea + ", polygonArea=" + tissueArea
                            + ", triangles=" + triangles.size()
                            + ", absoluteDifference="
                            + Math.abs(targetMeshArea - tissueArea)
                            + ", tolerance=" + targetAreaTolerance));
        }

        double maximumBoundaryError = 0;
        for (int index = 0; index < atlasBoundary.size(); index++) {
            final int next = (index + 1) % atlasBoundary.size();
            maximumBoundaryError = Math.max(maximumBoundaryError,
                    distance(applyWithoutAudit(atlasBoundary.get(index)),
                            tissueBoundary.get(index)));
            maximumBoundaryError = Math.max(maximumBoundaryError,
                    distance(applyWithoutAudit(interpolate(
                                    atlasBoundary.get(index),
                                    atlasBoundary.get(next), 0.5)),
                            interpolate(tissueBoundary.get(index),
                                    tissueBoundary.get(next), 0.5)));
        }
        if (maximumBoundaryError > MAXIMUM_BOUNDARY_ERROR_PIXELS) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                    "tissue boundary",
                    "Boundary-authoritative mapping exceeded the 0.25 preview-pixel boundary tolerance"));
        }
        return new Audit(maximumBoundaryError,
                minimumSourceArea, minimumTargetArea,
                sourceMeshArea, atlasArea, targetMeshArea, tissueArea);
    }

    private Point2D applyWithoutAudit(final Point2D point) {
        final MeshTriangle triangle = locateSourceTriangle(point);
        if (triangle == null) {
            throw failure(BoundaryGeometryFailure.local(
                    BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                    "atlas boundary", 0, 0, point,
                    "The common-refined mesh does not cover its atlas boundary"));
        }
        return triangle.map(point);
    }

    private void requireNoTriangleOverlap(
            final boolean source,
            final double coordinateEpsilon,
            final double areaEpsilon) {
        if (triangles.size() < 2) {
            return;
        }
        final List<Integer> ordered = new ArrayList<>(triangles.size());
        for (int index = 0; index < triangles.size(); index++) {
            ordered.add(index);
        }
        ordered.sort(Comparator
                .comparingDouble((Integer index) -> triangleFor(
                        index, source).minimumX())
                .thenComparingDouble(index -> triangleFor(
                        index, source).minimumY())
                .thenComparingInt(Integer::intValue));

        double minimumY = Double.POSITIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < triangles.size(); index++) {
            final Triangle2D triangle = triangleFor(index, source);
            minimumY = Math.min(minimumY,
                    triangle.minimumY() - coordinateEpsilon);
            maximumY = Math.max(maximumY,
                    triangle.maximumY() + coordinateEpsilon);
        }
        final int binCount = Math.min(4_096, Math.max(1,
                (int) Math.ceil(Math.sqrt(triangles.size()))));
        final double ySpan = maximumY - minimumY;
        final double binHeight = ySpan > 0 ? ySpan / binCount : 1.0;
        final List<LinkedHashSet<Integer>> activeByY =
                new ArrayList<>(binCount);
        for (int bin = 0; bin < binCount; bin++) {
            activeByY.add(new LinkedHashSet<>());
        }
        final PriorityQueue<Integer> activeByMaximumX =
                new PriorityQueue<>(Comparator
                        .comparingDouble((Integer index) -> triangleFor(
                                index, source).maximumX())
                        .thenComparingInt(Integer::intValue));
        final int[] seenGeneration = new int[triangles.size()];
        int generation = 0;
        for (final int second : ordered) {
            final Triangle2D b = triangleFor(second, source);
            while (!activeByMaximumX.isEmpty()
                    && triangleFor(activeByMaximumX.peek(), source)
                            .maximumX() + coordinateEpsilon < b.minimumX()) {
                final int expired = activeByMaximumX.remove();
                final BinRange expiredBins = binRange(
                        triangleFor(expired, source), minimumY, binHeight,
                        binCount, coordinateEpsilon);
                for (int bin = expiredBins.minimum();
                        bin <= expiredBins.maximum(); bin++) {
                    activeByY.get(bin).remove(expired);
                }
            }

            generation++;
            final List<Integer> candidates = new ArrayList<>();
            final BinRange candidateBins = binRange(
                    b, minimumY, binHeight, binCount, coordinateEpsilon);
            for (int bin = candidateBins.minimum();
                    bin <= candidateBins.maximum(); bin++) {
                for (final int first : activeByY.get(bin)) {
                    if (seenGeneration[first] != generation) {
                        seenGeneration[first] = generation;
                        candidates.add(first);
                    }
                }
            }
            candidates.sort(Integer::compareTo);
            for (final int first : candidates) {
                final Triangle2D a = triangleFor(first, source);
                if (a.maximumY() + coordinateEpsilon < b.minimumY()
                        || b.maximumY() + coordinateEpsilon < a.minimumY()
                        || !a.boundsOverlap(b, coordinateEpsilon)) {
                    continue;
                }
                final List<Point2D> intersection = clipConvexPolygon(
                        List.of(a.a(), a.b(), a.c()), b);
                if (Math.abs(signedArea(intersection)) > areaEpsilon) {
                    throw failure(BoundaryGeometryFailure.local(
                            BoundaryGeometryFailure.Kind.AUDIT_FAILURE,
                            source ? "common-refined source mesh"
                                    : "common-refined target mesh",
                            first, second,
                            intersection.get(0),
                            "Two common-refined triangles overlap"));
                }
            }
            for (int bin = candidateBins.minimum();
                    bin <= candidateBins.maximum(); bin++) {
                activeByY.get(bin).add(second);
            }
            activeByMaximumX.add(second);
        }
    }

    private static BinRange binRange(
            final Triangle2D triangle,
            final double minimumY,
            final double binHeight,
            final int binCount,
            final double coordinateEpsilon) {
        final int first = clampBin((int) Math.floor(
                (triangle.minimumY() - coordinateEpsilon - minimumY)
                        / binHeight), binCount);
        final int last = clampBin((int) Math.floor(
                (triangle.maximumY() + coordinateEpsilon - minimumY)
                        / binHeight), binCount);
        return new BinRange(Math.min(first, last), Math.max(first, last));
    }

    private static int clampBin(final int candidate, final int binCount) {
        return Math.max(0, Math.min(binCount - 1, candidate));
    }

    private record BinRange(int minimum, int maximum) { }

    private Triangle2D triangleFor(
            final int index, final boolean source) {
        return source ? triangles.get(index).source()
                : triangles.get(index).target();
    }

    private static List<MeshTriangle> commonRefinement(
            final List<Point2D> atlasBoundary,
            final List<Point2D> tissueBoundary,
            final List<Point2D> canonicalBoundary,
            final List<IndexedTriangle> atlasTriangulation,
            final List<IndexedTriangle> tissueTriangulation,
            final double canonicalAreaEpsilon) {
        final List<MeshTriangle> result = new ArrayList<>();
        int id = 0;
        for (int sourceIndex = 0;
                sourceIndex < atlasTriangulation.size(); sourceIndex++) {
            final IndexedTriangle sourceIndices =
                    atlasTriangulation.get(sourceIndex);
            final Triangle2D sourceCanonical = sourceIndices.points(
                    canonicalBoundary);
            final Triangle2D sourcePhysical = sourceIndices.points(
                    atlasBoundary);
            for (int targetIndex = 0;
                    targetIndex < tissueTriangulation.size(); targetIndex++) {
                final IndexedTriangle targetIndices =
                        tissueTriangulation.get(targetIndex);
                final Triangle2D targetCanonical = targetIndices.points(
                        canonicalBoundary);
                final Triangle2D targetPhysical = targetIndices.points(
                        tissueBoundary);
                final List<Point2D> intersection = clipConvexPolygon(
                        List.of(sourceCanonical.a(), sourceCanonical.b(),
                                sourceCanonical.c()), targetCanonical);
                if (intersection.size() < 3
                        || Math.abs(signedArea(intersection))
                                <= canonicalAreaEpsilon) {
                    continue;
                }
                final List<Point2D> canonical = ensureCounterClockwise(
                        cleanConvexPolygon(intersection,
                                CANONICAL_POINT_EPSILON,
                                canonicalAreaEpsilon));
                if (canonical.size() < 3) {
                    continue;
                }
                for (int triangle = 1;
                        triangle + 1 < canonical.size(); triangle++) {
                    final Triangle2D canonicalTriangle = new Triangle2D(
                            canonical.get(0), canonical.get(triangle),
                            canonical.get(triangle + 1));
                    if (!(canonicalTriangle.signedDoubleArea()
                            > canonicalAreaEpsilon)) {
                        continue;
                    }
                    final Triangle2D sourceTriangle = mapCanonicalTriangle(
                            canonicalTriangle, sourceCanonical, sourcePhysical);
                    final Triangle2D targetTriangle = mapCanonicalTriangle(
                            canonicalTriangle, targetCanonical, targetPhysical);
                    if (robustOrientation(sourceTriangle.a(),
                                    sourceTriangle.b(), sourceTriangle.c()) <= 0
                            || robustOrientation(targetTriangle.a(),
                                    targetTriangle.b(),
                                    targetTriangle.c()) <= 0) {
                        throw failure(BoundaryGeometryFailure.local(
                                BoundaryGeometryFailure.Kind
                                        .NUMERICALLY_UNRESOLVABLE,
                                "common-refined mesh", sourceIndex,
                                targetIndex, canonicalTriangle.centroid(),
                                "A positive canonical refinement cell reversed or collapsed in physical space: canonicalDoubleArea="
                                        + canonicalTriangle.signedDoubleArea()
                                        + ", sourceDoubleArea="
                                        + sourceTriangle.signedDoubleArea()
                                        + ", targetDoubleArea="
                                        + targetTriangle.signedDoubleArea()));
                    }
                    result.add(new MeshTriangle(id++, sourceIndex, targetIndex,
                            canonicalTriangle, sourceTriangle, targetTriangle));
                }
            }
        }
        return List.copyOf(result);
    }

    private static Triangle2D mapCanonicalTriangle(
            final Triangle2D common,
            final Triangle2D canonicalContainer,
            final Triangle2D physicalContainer) {
        return new Triangle2D(
                mapBarycentric(common.a(), canonicalContainer,
                        physicalContainer),
                mapBarycentric(common.b(), canonicalContainer,
                        physicalContainer),
                mapBarycentric(common.c(), canonicalContainer,
                        physicalContainer));
    }

    private static Point2D mapBarycentric(
            final Point2D point,
            final Triangle2D from,
            final Triangle2D to) {
        final double denominator = from.signedDoubleArea();
        if (!(Math.abs(denominator) > 0)) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.NUMERICALLY_UNRESOLVABLE,
                    "canonical mesh",
                    "A canonical triangle became degenerate"));
        }
        final double wa = cross(from.b(), from.c(), point) / denominator;
        final double wb = cross(from.c(), from.a(), point) / denominator;
        final double wc = 1.0 - wa - wb;
        return new Point2D(
                wa * to.a().x() + wb * to.b().x() + wc * to.c().x(),
                wa * to.a().y() + wb * to.b().y() + wc * to.c().y());
    }

    private static List<IndexedTriangle> triangulate(
            final List<Point2D> polygon,
            final String boundaryName,
            final double areaEpsilon) {
        final List<Integer> remaining = new ArrayList<>();
        for (int index = 0; index < polygon.size(); index++) {
            remaining.add(index);
        }
        final List<IndexedTriangle> triangles = new ArrayList<>();
        while (remaining.size() > 3) {
            boolean removed = false;
            for (int position = 0; position < remaining.size(); position++) {
                final int previous = remaining.get(Math.floorMod(
                        position - 1, remaining.size()));
                final int current = remaining.get(position);
                final int next = remaining.get((position + 1)
                        % remaining.size());
                if (!(cross(polygon.get(previous), polygon.get(current),
                        polygon.get(next)) > areaEpsilon)) {
                    continue;
                }
                if (!diagonalIsInternal(polygon, remaining,
                        previous, next, current, areaEpsilon)) {
                    continue;
                }
                final Triangle2D ear = new Triangle2D(
                        polygon.get(previous), polygon.get(current),
                        polygon.get(next));
                boolean containsOther = false;
                for (final int candidate : remaining) {
                    if (candidate == previous || candidate == current
                            || candidate == next) {
                        continue;
                    }
                    if (ear.contains(polygon.get(candidate), areaEpsilon)) {
                        containsOther = true;
                        break;
                    }
                }
                if (containsOther) {
                    continue;
                }
                triangles.add(new IndexedTriangle(
                        previous, current, next));
                remaining.remove(position);
                removed = true;
                break;
            }
            if (!removed) {
                throw failure(BoundaryGeometryFailure.global(
                        BoundaryGeometryFailure.Kind.TRIANGULATION_FAILURE,
                        boundaryName,
                        "The simple boundary could not be triangulated without a degenerate element"));
            }
        }
        final IndexedTriangle finalTriangle = new IndexedTriangle(
                remaining.get(0), remaining.get(1), remaining.get(2));
        if (!(finalTriangle.points(polygon).signedDoubleArea()
                > areaEpsilon)) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.TRIANGULATION_FAILURE,
                    boundaryName,
                    "The final boundary triangle is numerically degenerate"));
        }
        triangles.add(finalTriangle);
        return List.copyOf(triangles);
    }

    private static boolean diagonalIsInternal(
            final List<Point2D> polygon,
            final List<Integer> remaining,
            final int startIndex,
            final int endIndex,
            final int removedIndex,
            final double epsilon) {
        final Point2D start = polygon.get(startIndex);
        final Point2D end = polygon.get(endIndex);
        for (int position = 0; position < remaining.size(); position++) {
            final int edgeStartIndex = remaining.get(position);
            final int edgeEndIndex = remaining.get(
                    (position + 1) % remaining.size());
            if (edgeStartIndex == startIndex || edgeStartIndex == endIndex
                    || edgeEndIndex == startIndex
                    || edgeEndIndex == endIndex
                    || edgeStartIndex == removedIndex
                            && edgeEndIndex == endIndex
                    || edgeStartIndex == startIndex
                            && edgeEndIndex == removedIndex) {
                continue;
            }
            if (segmentsIntersectOrTouch(start, end,
                    polygon.get(edgeStartIndex), polygon.get(edgeEndIndex),
                    epsilon)) {
                return false;
            }
        }
        return true;
    }

    private static List<Point2D> clipConvexPolygon(
            final List<Point2D> subject,
            final Triangle2D clipTriangle) {
        List<Point2D> output = List.copyOf(subject);
        for (final Point2D[] edge : List.of(
                new Point2D[]{clipTriangle.a(), clipTriangle.b()},
                new Point2D[]{clipTriangle.b(), clipTriangle.c()},
                new Point2D[]{clipTriangle.c(), clipTriangle.a()})) {
            if (output.isEmpty()) {
                break;
            }
            final List<Point2D> input = output;
            final List<Point2D> clipped = new ArrayList<>();
            Point2D previous = input.get(input.size() - 1);
            double previousSide = cross(edge[0], edge[1], previous);
            for (final Point2D current : input) {
                final double currentSide = cross(edge[0], edge[1], current);
                final boolean currentInside = currentSide >= -1e-12;
                final boolean previousInside = previousSide >= -1e-12;
                if (currentInside != previousInside) {
                    final double denominator = previousSide - currentSide;
                    if (Math.abs(denominator) > 1e-18) {
                        final double fraction = previousSide / denominator;
                        clipped.add(interpolate(previous, current, fraction));
                    }
                }
                if (currentInside) {
                    clipped.add(current);
                }
                previous = current;
                previousSide = currentSide;
            }
            output = List.copyOf(clipped);
        }
        return output;
    }

    private static List<Point2D> cleanConvexPolygon(
            final List<Point2D> polygon,
            final double pointEpsilon,
            final double areaEpsilon) {
        final List<Point2D> unique = new ArrayList<>();
        for (final Point2D point : polygon) {
            if (unique.isEmpty()
                    || distance(point, unique.get(unique.size() - 1))
                            > pointEpsilon) {
                unique.add(point);
            }
        }
        if (unique.size() > 1
                && distance(unique.get(0), unique.get(unique.size() - 1))
                        <= pointEpsilon) {
            unique.remove(unique.size() - 1);
        }
        boolean changed = true;
        while (changed && unique.size() > 3) {
            changed = false;
            for (int index = 0; index < unique.size(); index++) {
                final Point2D previous = unique.get(Math.floorMod(
                        index - 1, unique.size()));
                final Point2D current = unique.get(index);
                final Point2D next = unique.get((index + 1) % unique.size());
                if (Math.abs(cross(previous, current, next)) <= areaEpsilon
                        && between(previous, current, next, pointEpsilon)) {
                    unique.remove(index);
                    changed = true;
                    break;
                }
            }
        }
        return List.copyOf(unique);
    }

    private static List<Point2D> ensureCounterClockwise(
            final List<Point2D> polygon) {
        if (signedArea(polygon) >= 0) {
            return polygon;
        }
        final List<Point2D> reversed = new ArrayList<>(polygon);
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static void validateSimpleBoundary(
        final MonotoneBoundary2D boundary,
            final String boundaryName) {
        final List<Point2D> points = points(boundary);
        // Report the most local defect before the duplicate coordinate can
        // also be encountered as a non-adjacent touching branch.
        for (int first = 0; first < points.size(); first++) {
            final int firstNext = (first + 1) % points.size();
            final Point2D a = points.get(first);
            final Point2D b = points.get(firstNext);
            if (a.equals(b)) {
                throw failure(BoundaryGeometryFailure.local(
                        BoundaryGeometryFailure.Kind.ZERO_LENGTH_EDGE,
                        boundaryName, first, firstNext, a,
                        "Two adjacent boundary vertices occupy the same pixel-centre coordinate"));
            }
        }
        for (int first = 0; first < points.size(); first++) {
            final int firstNext = (first + 1) % points.size();
            final Point2D a = points.get(first);
            final Point2D b = points.get(firstNext);
            for (int second = first + 1;
                    second < points.size(); second++) {
                final int secondNext = (second + 1) % points.size();
                if (first == second || firstNext == second
                        || secondNext == first) {
                    continue;
                }
                final Point2D c = points.get(second);
                final Point2D d = points.get(secondNext);
                final IntersectionKind intersection = classifyIntersection(
                        a, b, c, d);
                if (intersection == IntersectionKind.NONE) {
                    continue;
                }
                final BoundaryGeometryFailure.Kind kind =
                        intersection == IntersectionKind.PROPER
                                ? BoundaryGeometryFailure.Kind.SELF_CROSSING
                                : BoundaryGeometryFailure.Kind
                                        .SELF_TOUCHING_PINCH;
                throw failure(BoundaryGeometryFailure.local(
                        kind, boundaryName, first, second,
                        intersectionLocation(a, b, c, d),
                        intersection == IntersectionKind.PROPER
                                ? "Two non-adjacent boundary edges cross"
                                : "Two non-adjacent boundary branches touch or form a zero-width pinch"));
            }
        }
        final double area = signedArea(points);
        if (orientationSign(area) == 0) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.NON_POSITIVE_AREA,
                    boundaryName,
                    "The boundary must enclose non-zero area"));
        }
    }

    private static IntersectionKind classifyIntersection(
            final Point2D a,
            final Point2D b,
            final Point2D c,
            final Point2D d) {
        final int abc = robustOrientation(a, b, c);
        final int abd = robustOrientation(a, b, d);
        final int cda = robustOrientation(c, d, a);
        final int cdb = robustOrientation(c, d, b);
        if (abc * abd < 0 && cda * cdb < 0) {
            return IntersectionKind.PROPER;
        }
        if (abc == 0 && onSegment(a, b, c)
                || abd == 0 && onSegment(a, b, d)
                || cda == 0 && onSegment(c, d, a)
                || cdb == 0 && onSegment(c, d, b)) {
            return IntersectionKind.TOUCH;
        }
        return IntersectionKind.NONE;
    }

    private static int robustOrientation(
            final Point2D a, final Point2D b, final Point2D c) {
        final double first = (b.x() - a.x()) * (c.y() - a.y());
        final double second = (b.y() - a.y()) * (c.x() - a.x());
        final double determinant = first - second;
        final double error = 16 * Math.ulp(
                Math.max(1.0, Math.abs(first) + Math.abs(second)));
        if (Math.abs(determinant) > error) {
            return determinant > 0 ? 1 : -1;
        }
        final BigDecimal ax = BigDecimal.valueOf(a.x());
        final BigDecimal ay = BigDecimal.valueOf(a.y());
        final BigDecimal bx = BigDecimal.valueOf(b.x());
        final BigDecimal by = BigDecimal.valueOf(b.y());
        final BigDecimal cx = BigDecimal.valueOf(c.x());
        final BigDecimal cy = BigDecimal.valueOf(c.y());
        return bx.subtract(ax).multiply(cy.subtract(ay))
                .subtract(by.subtract(ay).multiply(cx.subtract(ax)))
                .signum();
    }

    private static Point2D intersectionLocation(
            final Point2D a,
            final Point2D b,
            final Point2D c,
            final Point2D d) {
        for (final Point2D point : List.of(a, b, c, d)) {
            if (onSegment(a, b, point) && onSegment(c, d, point)) {
                return point;
            }
        }
        final double rx = b.x() - a.x();
        final double ry = b.y() - a.y();
        final double sx = d.x() - c.x();
        final double sy = d.y() - c.y();
        final double denominator = rx * sy - ry * sx;
        if (Math.abs(denominator) <= 1e-18) {
            return new Point2D(0.25 * (a.x() + b.x() + c.x() + d.x()),
                    0.25 * (a.y() + b.y() + c.y() + d.y()));
        }
        final double t = ((c.x() - a.x()) * sy
                - (c.y() - a.y()) * sx) / denominator;
        return interpolate(a, b, t);
    }

    private static List<Double> commonParameters(
            final MonotoneBoundary2D atlas,
            final MonotoneBoundary2D tissue) {
        final List<Double> values = new ArrayList<>();
        atlas.vertices().forEach(vertex ->
                values.add(vertex.cyclicParameter()));
        tissue.vertices().forEach(vertex ->
                values.add(vertex.cyclicParameter()));
        values.sort(Double::compare);
        final List<Double> unique = new ArrayList<>();
        for (final double value : values) {
            if (unique.isEmpty()
                    || Math.abs(value - unique.get(unique.size() - 1))
                            > PARAMETER_EPSILON) {
                unique.add(value);
            }
        }
        if (unique.size() < 3 || unique.get(0) != 0.0) {
            throw failure(BoundaryGeometryFailure.global(
                    BoundaryGeometryFailure.Kind.NON_MONOTONE_PARAMETER,
                    "atlas/tissue boundary",
                    "The common cyclic boundary identity must begin at zero and contain at least three samples"));
        }
        return List.copyOf(unique);
    }

    private static List<Point2D> refine(
            final MonotoneBoundary2D boundary,
            final List<Double> parameters) {
        return parameters.stream().map(boundary::interpolate).toList();
    }

    private static List<Point2D> reverseKeepingFirst(
            final List<Point2D> values) {
        final List<Point2D> result = new ArrayList<>(values.size());
        result.add(values.get(0));
        for (int index = values.size() - 1; index > 0; index--) {
            result.add(values.get(index));
        }
        return List.copyOf(result);
    }

    private static List<Point2D> regularCanonicalBoundary(
            final List<Double> cyclicParameters) {
        final List<Point2D> result = new ArrayList<>(
                cyclicParameters.size());
        for (final double cyclicParameter : cyclicParameters) {
            final double angle = 2 * Math.PI * cyclicParameter;
            result.add(new Point2D(Math.cos(angle), Math.sin(angle)));
        }
        return List.copyOf(result);
    }

    private static List<Point2D> points(final MonotoneBoundary2D boundary) {
        return boundary.vertices().stream()
                .map(MonotoneBoundary2D.Vertex::point).toList();
    }

    private static boolean containsInclusive(
            final List<Point2D> polygon,
            final Point2D point,
            final double epsilon) {
        boolean inside = false;
        for (int index = 0, previous = polygon.size() - 1;
                index < polygon.size(); previous = index++) {
            final Point2D a = polygon.get(previous);
            final Point2D b = polygon.get(index);
            if (distanceToSegment(point, a, b) <= epsilon) {
                return true;
            }
            final boolean crosses = (a.y() > point.y()) != (b.y() > point.y())
                    && point.x() < (b.x() - a.x())
                            * (point.y() - a.y()) / (b.y() - a.y()) + a.x();
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean segmentsIntersectOrTouch(
            final Point2D a,
            final Point2D b,
            final Point2D c,
            final Point2D d,
            final double epsilon) {
        if (classifyIntersection(a, b, c, d) != IntersectionKind.NONE) {
            return true;
        }
        return distanceToSegment(a, c, d) <= epsilon
                || distanceToSegment(b, c, d) <= epsilon
                || distanceToSegment(c, a, b) <= epsilon
                || distanceToSegment(d, a, b) <= epsilon;
    }

    private static boolean onSegment(
            final Point2D a, final Point2D b, final Point2D point) {
        return point.x() >= Math.min(a.x(), b.x())
                && point.x() <= Math.max(a.x(), b.x())
                && point.y() >= Math.min(a.y(), b.y())
                && point.y() <= Math.max(a.y(), b.y());
    }

    private static boolean between(
            final Point2D a,
            final Point2D middle,
            final Point2D b,
            final double epsilon) {
        return middle.x() >= Math.min(a.x(), b.x()) - epsilon
                && middle.x() <= Math.max(a.x(), b.x()) + epsilon
                && middle.y() >= Math.min(a.y(), b.y()) - epsilon
                && middle.y() <= Math.max(a.y(), b.y()) + epsilon;
    }

    private static double distanceToSegment(
            final Point2D point,
            final Point2D start,
            final Point2D end) {
        final double dx = end.x() - start.x();
        final double dy = end.y() - start.y();
        final double squared = dx * dx + dy * dy;
        if (!(squared > 0)) {
            return distance(point, start);
        }
        final double parameter = Math.max(0, Math.min(1,
                ((point.x() - start.x()) * dx
                        + (point.y() - start.y()) * dy) / squared));
        return distance(point, new Point2D(
                start.x() + parameter * dx,
                start.y() + parameter * dy));
    }

    private static double signedArea(final List<Point2D> polygon) {
        if (polygon.size() < 3) {
            return 0;
        }
        double twiceArea = 0;
        for (int index = 0; index < polygon.size(); index++) {
            final Point2D current = polygon.get(index);
            final Point2D next = polygon.get((index + 1) % polygon.size());
            twiceArea += current.x() * next.y() - current.y() * next.x();
        }
        return 0.5 * twiceArea;
    }

    private static int orientationSign(final double signedArea) {
        if (signedArea > 0) {
            return 1;
        }
        if (signedArea < 0) {
            return -1;
        }
        return 0;
    }

    private static double cross(
            final Point2D a, final Point2D b, final Point2D c) {
        return (b.x() - a.x()) * (c.y() - a.y())
                - (b.y() - a.y()) * (c.x() - a.x());
    }

    private static Point2D interpolate(
            final Point2D start,
            final Point2D end,
            final double parameter) {
        return new Point2D(
                start.x() + parameter * (end.x() - start.x()),
                start.y() + parameter * (end.y() - start.y()));
    }

    private static double distance(final Point2D a, final Point2D b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    private static void addDistinct(
            final List<Point2D> values, final Point2D point) {
        if (values.isEmpty()
                || distance(values.get(values.size() - 1), point) > 1e-10) {
            values.add(point);
        }
    }

    private static String contentHash(
            final MonotoneBoundary2D atlasInput,
            final MonotoneBoundary2D tissueInput,
            final List<Double> parameters,
            final List<Point2D> atlasBoundary,
            final List<Point2D> tissueBoundary,
            final List<MeshTriangle> triangles,
            final int previewWidth,
            final int previewHeight) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ALGORITHM_REVISION.getBytes(StandardCharsets.UTF_8));
            digest.update(PIXEL_CENTER_CONVENTION.getBytes(
                    StandardCharsets.UTF_8));
            updateInt(digest, previewWidth);
            updateInt(digest, previewHeight);
            updateBoundary(digest, atlasInput);
            updateBoundary(digest, tissueInput);
            parameters.forEach(value -> updateDouble(digest, value));
            atlasBoundary.forEach(point -> updatePoint(digest, point));
            tissueBoundary.forEach(point -> updatePoint(digest, point));
            for (final MeshTriangle triangle : triangles) {
                updateInt(digest, triangle.id());
                updateInt(digest, triangle.sourceCanonicalTriangleIndex());
                updateInt(digest, triangle.targetCanonicalTriangleIndex());
                updateTriangle(digest, triangle.canonical());
                updateTriangle(digest, triangle.source());
                updateTriangle(digest, triangle.target());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(
                    "SHA-256 is required by the Java runtime", unavailable);
        }
    }

    private static void updateBoundary(
            final MessageDigest digest,
            final MonotoneBoundary2D boundary) {
        for (final MonotoneBoundary2D.Vertex vertex : boundary.vertices()) {
            digest.update(vertex.id().getBytes(StandardCharsets.UTF_8));
            updatePoint(digest, vertex.point());
            updateDouble(digest, vertex.cyclicParameter());
            digest.update((byte) (vertex.semanticBoundary() ? 1 : 0));
        }
    }

    private static void updateTriangle(
            final MessageDigest digest, final Triangle2D triangle) {
        updatePoint(digest, triangle.a());
        updatePoint(digest, triangle.b());
        updatePoint(digest, triangle.c());
    }

    private static void updatePoint(
            final MessageDigest digest, final Point2D point) {
        updateDouble(digest, point.x());
        updateDouble(digest, point.y());
    }

    private static void updateDouble(
            final MessageDigest digest, final double value) {
        digest.update(ByteBuffer.allocate(Double.BYTES)
                .putLong(Double.doubleToLongBits(value)).array());
    }

    private static void updateInt(
            final MessageDigest digest, final int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static BoundaryGeometryException failure(
            final BoundaryGeometryFailure failure) {
        return new BoundaryGeometryException(failure);
    }

    public record Triangle2D(Point2D a, Point2D b, Point2D c) {

        public Triangle2D {
            a = Objects.requireNonNull(a, "a");
            b = Objects.requireNonNull(b, "b");
            c = Objects.requireNonNull(c, "c");
        }

        public double signedDoubleArea() {
            return cross(a, b, c);
        }

        public Point2D centroid() {
            return new Point2D((a.x() + b.x() + c.x()) / 3,
                    (a.y() + b.y() + c.y()) / 3);
        }

        public boolean contains(final Point2D point, final double epsilon) {
            return cross(a, b, point) >= -epsilon
                    && cross(b, c, point) >= -epsilon
                    && cross(c, a, point) >= -epsilon;
        }

        private boolean boundsOverlap(
                final Triangle2D other, final double epsilon) {
            return maximumX() + epsilon >= other.minimumX()
                    && other.maximumX() + epsilon >= minimumX()
                    && maximumY() + epsilon >= other.minimumY()
                    && other.maximumY() + epsilon >= minimumY();
        }

        private double minimumX() {
            return Math.min(a.x(), Math.min(b.x(), c.x()));
        }

        private double maximumX() {
            return Math.max(a.x(), Math.max(b.x(), c.x()));
        }

        private double minimumY() {
            return Math.min(a.y(), Math.min(b.y(), c.y()));
        }

        private double maximumY() {
            return Math.max(a.y(), Math.max(b.y(), c.y()));
        }
    }

    public record MeshTriangle(
            int id,
            int sourceCanonicalTriangleIndex,
            int targetCanonicalTriangleIndex,
            Triangle2D canonical,
            Triangle2D source,
            Triangle2D target) {

        public MeshTriangle {
            if (id < 0 || sourceCanonicalTriangleIndex < 0
                    || targetCanonicalTriangleIndex < 0) {
                throw new IllegalArgumentException(
                        "Mesh triangle indices must be non-negative");
            }
            canonical = Objects.requireNonNull(canonical, "canonical");
            source = Objects.requireNonNull(source, "source");
            target = Objects.requireNonNull(target, "target");
        }

        public Point2D map(final Point2D point) {
            return mapBarycentric(Objects.requireNonNull(point, "point"),
                    source, target);
        }

        public double jacobianDeterminant() {
            return target.signedDoubleArea() / source.signedDoubleArea();
        }
    }

    public record Diagnostics(
            String algorithmRevision,
            String pixelCenterConvention,
            int previewWidth,
            int previewHeight,
            int commonBoundaryVertexCount,
            int atlasCanonicalTriangleCount,
            int tissueCanonicalTriangleCount,
            int commonRefinedTriangleCount,
            double maximumBoundaryErrorPixels,
            double minimumSourceSignedDoubleArea,
            double minimumTargetSignedDoubleArea,
            double sourceMeshArea,
            double atlasPolygonArea,
            double targetMeshArea,
            double tissuePolygonArea,
            String contentSha256) {

        public Diagnostics {
            algorithmRevision = requireText(
                    algorithmRevision, "algorithmRevision");
            pixelCenterConvention = requireText(
                    pixelCenterConvention, "pixelCenterConvention");
            contentSha256 = requireText(contentSha256, "contentSha256");
            if (previewWidth <= 1 || previewHeight <= 1
                    || commonBoundaryVertexCount < 3
                    || atlasCanonicalTriangleCount < 1
                    || tissueCanonicalTriangleCount < 1
                    || commonRefinedTriangleCount < 1) {
                throw new IllegalArgumentException(
                        "Boundary-authoritative diagnostic counts are invalid");
            }
        }

        private static String requireText(
                final String value, final String name) {
            final String checked = Objects.requireNonNull(value, name).trim();
            if (checked.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return checked;
        }
    }

    private record IndexedTriangle(int a, int b, int c) {
        private Triangle2D points(final List<Point2D> values) {
            return new Triangle2D(values.get(a), values.get(b), values.get(c));
        }
    }

    private record Audit(
            double maximumBoundaryError,
            double minimumSourceDoubleArea,
            double minimumTargetDoubleArea,
            double sourceMeshArea,
            double atlasPolygonArea,
            double targetMeshArea,
            double tissuePolygonArea) {
    }

    private record MappedInterval(
            double start,
            double end,
            MeshTriangle triangle) {
    }

    private record PairedBoundary(
            List<Point2D> atlas,
            List<Point2D> tissue) {
    }

    private record BoundaryArcPair(
            List<Point2D> atlas,
            List<Point2D> tissue) {
    }

    private record MidlineGeometry(
            MidlineEndpoints atlasEndpoints,
            List<Point2D> mappedPath,
            List<Point2D> imageLeftBoundary,
            List<Point2D> imageRightBoundary) {

        private MidlineGeometry {
            mappedPath = List.copyOf(mappedPath);
            imageLeftBoundary = List.copyOf(imageLeftBoundary);
            imageRightBoundary = List.copyOf(imageRightBoundary);
        }
    }

    /**
     * Compact deterministic point-location accelerator. Candidate triangle
     * indices remain in mesh order, preserving the former shared-edge choice
     * while avoiding a full scan of a large common-refinement mesh.
     */
    private static final class TriangleSpatialIndex {

        private static final int MAXIMUM_LEAF_TRIANGLES = 16;

        private final boolean source;
        private final int[] triangleOrder;
        private final double[] minimumX;
        private final double[] minimumY;
        private final double[] maximumX;
        private final double[] maximumY;
        private final int[] left;
        private final int[] right;
        private final int[] start;
        private final int[] end;
        private final int[] minimumTriangleIndex;
        private int nodeCount;

        private TriangleSpatialIndex(
                final boolean source, final int triangleCount) {
            this.source = source;
            triangleOrder = new int[triangleCount];
            final int maximumNodeCount = Math.max(1,
                    triangleCount / 4 + 3);
            minimumX = new double[maximumNodeCount];
            minimumY = new double[maximumNodeCount];
            maximumX = new double[maximumNodeCount];
            maximumY = new double[maximumNodeCount];
            left = new int[maximumNodeCount];
            right = new int[maximumNodeCount];
            start = new int[maximumNodeCount];
            end = new int[maximumNodeCount];
            minimumTriangleIndex = new int[maximumNodeCount];
        }

        private static TriangleSpatialIndex build(
                final List<MeshTriangle> triangles,
                final boolean source,
                final double epsilon) {
            final TriangleSpatialIndex index = new TriangleSpatialIndex(
                    source, triangles.size());
            for (int triangle = 0; triangle < triangles.size(); triangle++) {
                index.triangleOrder[triangle] = triangle;
            }
            index.buildNode(triangles, 0, triangles.size(), epsilon);
            return index;
        }

        private int buildNode(
                final List<MeshTriangle> triangles,
                final int first,
                final int afterLast,
                final double epsilon) {
            final int node = nodeCount++;
            int smallestTriangle = Integer.MAX_VALUE;
            double nodeMinimumX = Double.POSITIVE_INFINITY;
            double nodeMinimumY = Double.POSITIVE_INFINITY;
            double nodeMaximumX = Double.NEGATIVE_INFINITY;
            double nodeMaximumY = Double.NEGATIVE_INFINITY;
            for (int offset = first; offset < afterLast; offset++) {
                final int triangleIndex = triangleOrder[offset];
                final Triangle2D triangle = triangleFor(
                        triangles.get(triangleIndex), source);
                smallestTriangle = Math.min(smallestTriangle, triangleIndex);
                nodeMinimumX = Math.min(nodeMinimumX, triangle.minimumX());
                nodeMinimumY = Math.min(nodeMinimumY, triangle.minimumY());
                nodeMaximumX = Math.max(nodeMaximumX, triangle.maximumX());
                nodeMaximumY = Math.max(nodeMaximumY, triangle.maximumY());
            }
            minimumX[node] = nodeMinimumX - epsilon;
            minimumY[node] = nodeMinimumY - epsilon;
            maximumX[node] = nodeMaximumX + epsilon;
            maximumY[node] = nodeMaximumY + epsilon;
            minimumTriangleIndex[node] = smallestTriangle;
            left[node] = -1;
            right[node] = -1;
            start[node] = first;
            end[node] = afterLast;
            if (afterLast - first <= MAXIMUM_LEAF_TRIANGLES) {
                return node;
            }

            final boolean splitX = nodeMaximumX - nodeMinimumX
                    >= nodeMaximumY - nodeMinimumY;
            final int middle = (first + afterLast) >>> 1;
            selectMedian(triangles, first, afterLast, middle, splitX);
            left[node] = buildNode(triangles, first, middle, epsilon);
            right[node] = buildNode(triangles, middle, afterLast, epsilon);
            return node;
        }

        private void selectMedian(
                final List<MeshTriangle> triangles,
                final int first,
                final int afterLast,
                final int middle,
                final boolean xAxis) {
            int low = first;
            int high = afterLast - 1;
            while (low < high) {
                final int pivot = partition(triangles, low, high,
                        (low + high) >>> 1, xAxis);
                if (pivot == middle) {
                    return;
                }
                if (middle < pivot) {
                    high = pivot - 1;
                } else {
                    low = pivot + 1;
                }
            }
        }

        private int partition(
                final List<MeshTriangle> triangles,
                final int low,
                final int high,
                final int pivot,
                final boolean xAxis) {
            final int pivotTriangle = triangleOrder[pivot];
            swap(pivot, high);
            int destination = low;
            for (int offset = low; offset < high; offset++) {
                if (compare(triangles, triangleOrder[offset],
                        pivotTriangle, xAxis) < 0) {
                    swap(destination++, offset);
                }
            }
            swap(destination, high);
            return destination;
        }

        private int compare(
                final List<MeshTriangle> triangles,
                final int first,
                final int second,
                final boolean xAxis) {
            final double firstCoordinate = centroidCoordinate(
                    triangleFor(triangles.get(first), source), xAxis);
            final double secondCoordinate = centroidCoordinate(
                    triangleFor(triangles.get(second), source), xAxis);
            final int coordinateComparison = Double.compare(
                    firstCoordinate, secondCoordinate);
            return coordinateComparison != 0
                    ? coordinateComparison : Integer.compare(first, second);
        }

        private void swap(final int first, final int second) {
            final int value = triangleOrder[first];
            triangleOrder[first] = triangleOrder[second];
            triangleOrder[second] = value;
        }

        private MeshTriangle locate(
                final List<MeshTriangle> triangles,
                final Point2D point,
                final double epsilon) {
            final int triangleIndex = locateNode(
                    triangles, 0, point, epsilon, Integer.MAX_VALUE);
            return triangleIndex == Integer.MAX_VALUE
                    ? null : triangles.get(triangleIndex);
        }

        private int locateNode(
                final List<MeshTriangle> triangles,
                final int node,
                final Point2D point,
                final double epsilon,
                final int bestTriangle) {
            if (minimumTriangleIndex[node] >= bestTriangle
                    || point.x() < minimumX[node]
                    || point.x() > maximumX[node]
                    || point.y() < minimumY[node]
                    || point.y() > maximumY[node]) {
                return bestTriangle;
            }
            if (left[node] < 0) {
                int best = bestTriangle;
                for (int offset = start[node]; offset < end[node]; offset++) {
                    final int triangleIndex = triangleOrder[offset];
                    if (triangleIndex < best && triangleFor(
                            triangles.get(triangleIndex), source)
                            .contains(point, epsilon)) {
                        best = triangleIndex;
                    }
                }
                return best;
            }
            int firstChild = left[node];
            int secondChild = right[node];
            if (minimumTriangleIndex[secondChild]
                    < minimumTriangleIndex[firstChild]) {
                final int swap = firstChild;
                firstChild = secondChild;
                secondChild = swap;
            }
            final int firstBest = locateNode(triangles, firstChild,
                    point, epsilon, bestTriangle);
            return locateNode(triangles, secondChild,
                    point, epsilon, firstBest);
        }

        private static Triangle2D triangleFor(
                final MeshTriangle triangle, final boolean source) {
            return source ? triangle.source() : triangle.target();
        }

        private static double centroidCoordinate(
                final Triangle2D triangle, final boolean xAxis) {
            if (xAxis) {
                return (triangle.a().x() + triangle.b().x()
                        + triangle.c().x()) / 3.0;
            }
            return (triangle.a().y() + triangle.b().y()
                    + triangle.c().y()) / 3.0;
        }
    }

    private record Bounds(
            double minimumX,
            double minimumY,
            double maximumX,
            double maximumY) {

        private static Bounds of(final List<Point2D> points) {
            double minimumX = Double.POSITIVE_INFINITY;
            double minimumY = Double.POSITIVE_INFINITY;
            double maximumX = Double.NEGATIVE_INFINITY;
            double maximumY = Double.NEGATIVE_INFINITY;
            for (final Point2D point : points) {
                minimumX = Math.min(minimumX, point.x());
                minimumY = Math.min(minimumY, point.y());
                maximumX = Math.max(maximumX, point.x());
                maximumY = Math.max(maximumY, point.y());
            }
            return new Bounds(minimumX, minimumY, maximumX, maximumY);
        }

        private double centerX() {
            return 0.5 * (minimumX + maximumX);
        }
    }

    private enum IntersectionKind {
        NONE,
        PROPER,
        TOUCH
    }
}
