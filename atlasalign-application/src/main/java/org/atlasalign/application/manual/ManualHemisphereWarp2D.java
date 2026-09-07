package org.atlasalign.application.manual;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;

/**
 * Immutable reviewer-controlled, hemisphere-local deformation in preview
 * pixel space.
 *
 * <p>Controls retain their atlas-left/atlas-right meaning. Confirmed atlas
 * orientation maps that anatomical side to an image side. The production
 * field is a deterministic piecewise-affine map on a hidden reduced lattice.
 * Reviewer controls may lie on the suggested tissue boundary or in the
 * interior; their influence has compact local support. The preview edge,
 * anatomical seam, and opposite side remain identity. This class never
 * infers reflection, laterality, atlas level,
 * or an automatic result, and it never reads or changes source pixels.</p>
 */
public final class ManualHemisphereWarp2D {

    public static final String ALGORITHM_REVISION =
            "reviewer-controlled-side-local-compact-pwa-v4-border-preserving-refinement";
    public static final String PIXEL_CENTER_CONVENTION =
            "top-left-pixel-center-is-(0,0)";
    public static final int MINIMUM_CONTROLS_PER_SIDE = 4;
    /**
     * Dense reviewer groups deliberately match Fiji-style polygon editing:
     * one regular interior grid or one anatomy outline may expose up to 64
     * independently draggable vertices.
     */
    public static final int MAXIMUM_CONTROLS_PER_GROUP = 64;
    public static final int MAXIMUM_BOUNDARY_CONTROLS_PER_SIDE = 48;
    /** Four dense refinement groups may coexist on one anatomical side. */
    public static final int MAXIMUM_REFINEMENT_CONTROLS_PER_SIDE = 256;
    public static final int MAXIMUM_CONTROLS_PER_SIDE = 304;
    /** Default regular-interior density; structure groups may use four. */
    public static final int DEFAULT_CONTROLS_PER_SIDE = 24;
    public static final int MAXIMUM_MESH_TRIANGLES = 5000;

    private static final double MINIMUM_LOCAL_SUPPORT_FRACTION = 0.08;
    private static final double MAXIMUM_LOCAL_SUPPORT_FRACTION = 0.14;

    private static final double MAXIMUM_SAMPLED_DISPLACEMENT_FRACTION = 0.25;
    private static final double MINIMUM_JACOBIAN_DETERMINANT = 0.20;
    private static final double MINIMUM_SINGULAR_VALUE = 0.30;
    private static final double MAXIMUM_SINGULAR_VALUE = 3.0;
    private static final double MAXIMUM_ANISOTROPY = 3.0;
    private static final double ROUND_TRIP_TOLERANCE = 1e-6;
    private static final double SIDE_EPSILON = 1e-9;
    private static final int TOPOLOGY_CACHE_CAPACITY = 12;
    private static final Map<String, MeshTopology> TOPOLOGY_CACHE =
            new LinkedHashMap<>(16, 0.75f, true);
    private static long topologyBuildCount;

    private final AtlasOrientation orientation;
    private final ReviewSectionMode reviewSectionMode;
    private final MidlineSegment imageMidline;
    private final List<Point2D> imageMidlinePath;
    private final int previewWidth;
    private final int previewHeight;
    private final double seamWidth;
    private final Diagnostics diagnostics;
    private final MeshState meshState;

    private ManualHemisphereWarp2D(
            final AtlasOrientation orientation,
            final ReviewSectionMode reviewSectionMode,
            final MidlineSegment imageMidline,
            final List<Point2D> imageMidlinePath,
            final int previewWidth,
            final int previewHeight,
            final double seamWidth,
            final Diagnostics diagnostics,
            final MeshState meshState) {
        this.orientation = Objects.requireNonNull(orientation, "orientation");
        this.reviewSectionMode = Objects.requireNonNull(
                reviewSectionMode, "reviewSectionMode");
        this.imageMidline = Objects.requireNonNull(
                imageMidline, "imageMidline");
        this.imageMidlinePath = List.copyOf(Objects.requireNonNull(
                imageMidlinePath, "imageMidlinePath"));
        if (this.imageMidlinePath.size() < 2
                || !this.imageMidlinePath.get(0).equals(
                        imageMidline.dorsal())
                || !this.imageMidlinePath.get(
                        this.imageMidlinePath.size() - 1).equals(
                        imageMidline.ventral())) {
            throw new IllegalArgumentException(
                    "Mapped midline path must retain its dorsal/ventral endpoints");
        }
        this.previewWidth = previewWidth;
        this.previewHeight = previewHeight;
        this.seamWidth = seamWidth;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.meshState = Objects.requireNonNull(meshState, "meshState");
    }

    /**
     * Compatibility overload for review states that already contain an exact
     * outline map. New reviewer-controlled workflows use the preview-domain
     * overload and do not construct or require this map.
     *
     * <p>Controls are reviewer geometry only. They are not landmark evidence
     * and are deliberately kept in a separate typed value from
     * {@code LandmarkPair}.</p>
     */
    public static ManualHemisphereWarp2D fit(
            final List<ManualWarpControl> controls,
            final AtlasOrientation orientation,
            final BoundaryAuthoritativeTransform2D outline) {
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(outline, "outline");
        if (!orientation.confirmed()) {
            throw new IllegalArgumentException(
                    "Boundary-pinned hemisphere warp requires confirmed orientation");
        }
        if (outline.previewWidth() <= 1 || outline.previewHeight() <= 1) {
            throw new IllegalArgumentException(
                    "Reviewed outline preview dimensions must exceed one pixel");
        }
        final List<ManualWarpControl> requested = List.copyOf(
                Objects.requireNonNull(controls, "controls"));
        final EnumMap<AtlasSide, List<ManualWarpControl>> bySide =
                new EnumMap<>(AtlasSide.class);
        final Set<String> ids = new HashSet<>();
        for (final ManualWarpControl control : requested) {
            final ManualWarpControl checked = Objects.requireNonNull(
                    control, "control");
            if (!ids.add(checked.id())) {
                throw new IllegalArgumentException(
                        "Manual warp control IDs must be unique");
            }
            bySide.computeIfAbsent(checked.atlasSide(), ignored ->
                    new ArrayList<>()).add(checked);
        }
        for (final Map.Entry<AtlasSide, List<ManualWarpControl>> entry
                : bySide.entrySet()) {
            final int count = entry.getValue().size();
            if (count < MINIMUM_CONTROLS_PER_SIDE
                    || count > MAXIMUM_CONTROLS_PER_SIDE) {
                throw new IllegalArgumentException(
                        "Each active atlas side requires between "
                                + MINIMUM_CONTROLS_PER_SIDE + " and "
                                + MAXIMUM_CONTROLS_PER_SIDE + " controls");
            }
            requireTypedCapacity(entry.getKey(), entry.getValue());
        }

        final ReviewedOutlineTransform2D.MidlineEndpoints endpoints =
                outline.hemisphereMidline();
        final MidlineSegment midline = new MidlineSegment(
                endpoints.dorsal(), endpoints.ventral());
        final List<Point2D> midlinePath = outline.hemisphereMidlinePath();
        final double seamWidth = Math.max(1.0, Math.min(
                0.02 * Math.hypot(outline.previewWidth(),
                        outline.previewHeight()), 4.0));
        final double diagonal = Math.hypot(outline.previewWidth(),
                outline.previewHeight());
        final long solveStartNanos = System.nanoTime();
        final EnumMap<AtlasSide, MeshSide> meshes = new EnumMap<>(
                AtlasSide.class);
        for (final AtlasSide side : AtlasSide.values()) {
            final List<ManualWarpControl> sideControls = bySide.getOrDefault(
                    side, List.of());
            final ImageSide imageSide = imageSideFor(side, orientation);
            meshes.put(side, MeshSide.fit(side, imageSide, sideControls,
                    outline.tissueHemisphereBoundary(
                            imageSide == ImageSide.IMAGE_LEFT), midline,
                    outline.previewWidth(), outline.previewHeight(), diagonal,
                    outline.contentSha256()));
        }
        final MeshState state = new MeshState(outline, orientation, midline,
                midlinePath, meshes, requested);
        if (state.triangleCount() > MAXIMUM_MESH_TRIANGLES) {
            throw new IllegalArgumentException(
                    "Boundary-pinned hemisphere mesh exceeds the hard triangle cap: "
                            + state.triangleCount() + " > "
                            + MAXIMUM_MESH_TRIANGLES);
        }
        final MeshAudit audit = state.audit(diagonal);
        final long solveNanos = Math.max(0, System.nanoTime()
                - solveStartNanos);
        final Diagnostics diagnostics = new Diagnostics(
                ALGORITHM_REVISION, PIXEL_CENTER_CONVENTION, orientation,
                midline, outline.previewWidth(), outline.previewHeight(),
                seamWidth,
                bySide.getOrDefault(AtlasSide.LEFT, List.of()).size(),
                bySide.getOrDefault(AtlasSide.RIGHT, List.of()).size(),
                audit.minimumDeterminant(), audit.minimumSingularValue(),
                audit.maximumSingularValue(), audit.maximumAnisotropy(),
                audit.maximumDisplacement(), audit.maximumRoundTripError(),
                state.contentSha256(), state.triangleCount(),
                outline.contentSha256(), solveNanos);
        return new ManualHemisphereWarp2D(orientation, ReviewSectionMode.FULL,
                midline, midlinePath,
                outline.previewWidth(), outline.previewHeight(), seamWidth,
                diagnostics, state);
    }

    /**
     * Fits the normal reviewer-controlled field directly in immutable tissue
     * preview coordinates. No exact outline map is constructed or required.
     * Suggested boundary points are ordinary movable controls; only the
     * anatomical seam, preview edge, and opposite side are fixed identity.
     */
    public static ManualHemisphereWarp2D fit(
            final List<ManualWarpControl> controls,
            final AtlasOrientation orientation,
            final int previewWidth,
            final int previewHeight) {
        return fit(controls, orientation, ReviewSectionMode.FULL,
                previewWidth, previewHeight);
    }

    /** Fits the selected reviewer section contract in preview coordinates. */
    public static ManualHemisphereWarp2D fit(
            final List<ManualWarpControl> controls,
            final AtlasOrientation orientation,
            final ReviewSectionMode reviewSectionMode,
            final int previewWidth,
            final int previewHeight) {
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(reviewSectionMode, "reviewSectionMode");
        if (!orientation.confirmed()) {
            throw new IllegalArgumentException(
                    "Manual side-local warp requires confirmed orientation");
        }
        final double centreX = (previewWidth - 1.0) * 0.5;
        return fitPreviewDomain(controls, orientation, reviewSectionMode,
                new MidlineSegment(new Point2D(centreX, 0),
                        new Point2D(centreX, previewHeight - 1.0)),
                previewWidth, previewHeight);
    }

    /**
     * Fits the reviewer-controlled field around the anatomical midline after
     * the joined atlas has been translated, rotated, and uniformly scaled in
     * preview space. Full and Half reviews must use this overload after coarse
     * placement so their immutable seam follows the placed atlas rather than
     * the centre of the preview window. Disjoined domains retain the same
     * recorded line for deterministic side provenance but do not constrain
     * either independent side to it.
     */
    public static ManualHemisphereWarp2D fit(
            final List<ManualWarpControl> controls,
            final AtlasOrientation orientation,
            final ReviewSectionMode reviewSectionMode,
            final MidlineSegment mappedAtlasMidline,
            final int previewWidth,
            final int previewHeight) {
        Objects.requireNonNull(orientation, "orientation");
        Objects.requireNonNull(reviewSectionMode, "reviewSectionMode");
        if (!orientation.confirmed()) {
            throw new IllegalArgumentException(
                    "Manual side-local warp requires confirmed orientation");
        }
        return fitPreviewDomain(controls, orientation, reviewSectionMode,
                Objects.requireNonNull(mappedAtlasMidline,
                        "mappedAtlasMidline"),
                previewWidth, previewHeight);
    }

    /**
     * Fits one or two independently bounded atlas-side fields.
     *
     * @param controls one entry per fitted atlas side
     * @param orientation explicit confirmed atlas-to-image orientation
     * @param dorsalMidline confirmed post-outline dorsal midline anchor
     * @param ventralMidline confirmed post-outline ventral midline anchor
     */
    public static ManualHemisphereWarp2D fit(
            final List<SideControls> controls,
            final AtlasOrientation orientation,
            final Point2D dorsalMidline,
            final Point2D ventralMidline,
            final int previewWidth,
            final int previewHeight) {
        Objects.requireNonNull(orientation, "orientation");
        if (!orientation.confirmed()) {
            throw new IllegalArgumentException(
                    "Hemisphere warp requires an explicit confirmed orientation");
        }
        if (previewWidth <= 1 || previewHeight <= 1) {
            throw new IllegalArgumentException(
                    "Preview width and height must exceed one pixel");
        }
        final MidlineSegment midline = new MidlineSegment(
                Objects.requireNonNull(dorsalMidline, "dorsalMidline"),
                Objects.requireNonNull(ventralMidline, "ventralMidline"));
        final List<ManualWarpControl> typed = new ArrayList<>();
        for (final SideControls sideControls : Objects.requireNonNull(
                controls, "controls")) {
            final List<Point2D> sources = sideControls.sourcePoints();
            final List<Point2D> targets = sideControls.targetPoints();
            if (sources.size() != targets.size()) {
                throw new IllegalArgumentException(
                        "Legacy source and target control counts must match");
            }
            for (int index = 0; index < sources.size(); index++) {
                typed.add(new ManualWarpControl(
                        "legacy-" + sideControls.atlasSide() + "-" + index,
                        sideControls.atlasSide(),
                        ManualWarpControlOrigin.USER_PLACED_INTERIOR,
                        "legacy", "", sources.get(index), targets.get(index)));
            }
        }
        return fitPreviewDomain(typed, orientation, ReviewSectionMode.FULL,
                midline,
                previewWidth, previewHeight);
    }

    private static ManualHemisphereWarp2D fitPreviewDomain(
            final List<ManualWarpControl> controls,
            final AtlasOrientation orientation,
            final ReviewSectionMode reviewSectionMode,
            final MidlineSegment midline,
            final int previewWidth,
            final int previewHeight) {
        final EnumMap<AtlasSide, List<ManualWarpControl>> bySide =
                new EnumMap<>(AtlasSide.class);
        for (final ManualWarpControl control : controls) {
            bySide.computeIfAbsent(control.atlasSide(), ignored ->
                    new ArrayList<>()).add(control);
        }
        for (final Map.Entry<AtlasSide, List<ManualWarpControl>> entry
                : bySide.entrySet()) {
            if (entry.getValue().size() < MINIMUM_CONTROLS_PER_SIDE
                    || entry.getValue().size() > MAXIMUM_CONTROLS_PER_SIDE) {
                final int count = entry.getValue().size();
                final ManualWarpFailureKind kind = count
                        < MINIMUM_CONTROLS_PER_SIDE
                        ? ManualWarpFailureKind.TOO_FEW_CONTROLS
                        : ManualWarpFailureKind.CONTROL_LIMIT;
                throw new ManualWarpException(kind,
                        count < MINIMUM_CONTROLS_PER_SIDE
                                ? "Set at least 4 points on this side before warping."
                                : "This side supports at most 304 points: 48 border and 256 interior/structure points.",
                        "controlCount=" + count + "; side="
                                + entry.getKey());
            }
            requireTypedCapacity(entry.getKey(), entry.getValue());
        }
        final long start = System.nanoTime();
        final MeshState state = MeshState.previewDomain(
                controls, orientation, reviewSectionMode, midline,
                previewWidth, previewHeight);
        if (state.triangleCount() > MAXIMUM_MESH_TRIANGLES) {
            throw new IllegalArgumentException(
                    "Hemisphere mesh exceeds the hard triangle cap");
        }
        final MeshAudit audit = state.audit(Math.hypot(previewWidth,
                previewHeight));
        final Diagnostics diagnostics = new Diagnostics(
                ALGORITHM_REVISION, PIXEL_CENTER_CONVENTION, orientation,
                midline, previewWidth, previewHeight, 1.0,
                bySide.getOrDefault(AtlasSide.LEFT, List.of()).size(),
                bySide.getOrDefault(AtlasSide.RIGHT, List.of()).size(),
                audit.minimumDeterminant(), audit.minimumSingularValue(),
                audit.maximumSingularValue(), audit.maximumAnisotropy(),
                audit.maximumDisplacement(), audit.maximumRoundTripError(),
                state.contentSha256(), state.triangleCount(), "",
                Math.max(0, System.nanoTime() - start));
        return new ManualHemisphereWarp2D(orientation, reviewSectionMode,
                midline,
                List.of(midline.dorsal(), midline.ventral()),
                previewWidth, previewHeight, 1.0, diagnostics, state);
    }

    private static void requireTypedCapacity(
            final AtlasSide side,
            final List<ManualWarpControl> controls) {
        final long boundary = controls.stream().filter(control ->
                control.origin()
                        == ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR)
                .count();
        final long refinement = controls.size() - boundary;
        if (boundary > MAXIMUM_BOUNDARY_CONTROLS_PER_SIDE) {
            throw new ManualWarpException(
                    ManualWarpFailureKind.CONTROL_LIMIT,
                    "This side supports at most 48 outer-border points. Choose a lower Border density or restart the border.",
                    "boundaryControlCount=" + boundary + "; side=" + side);
        }
        if (refinement > MAXIMUM_REFINEMENT_CONTROLS_PER_SIDE) {
            throw new ManualWarpException(
                    ManualWarpFailureKind.CONTROL_LIMIT,
                    "This side supports at most 256 interior and structure points. Reduce a group or reset the side.",
                    "refinementControlCount=" + refinement + "; side="
                            + side);
        }
        final Map<String, Long> groups = controls.stream()
                .filter(control -> control.origin()
                        != ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR)
                .collect(java.util.stream.Collectors.groupingBy(
                        ManualWarpControl::groupId,
                        java.util.stream.Collectors.counting()));
        groups.forEach((group, count) -> {
            if (count > MAXIMUM_CONTROLS_PER_GROUP) {
                throw new ManualWarpException(
                        ManualWarpFailureKind.CONTROL_LIMIT,
                        "A refinement group supports at most 64 points. Choose a lower density or replace that group.",
                        "group=" + group + "; count=" + count
                                + "; side=" + side);
            }
        });
    }

    /** Convenience overload preserving ordered left and right point lists. */
    public static ManualHemisphereWarp2D fit(
            final List<Point2D> atlasLeftSources,
            final List<Point2D> atlasLeftTargets,
            final List<Point2D> atlasRightSources,
            final List<Point2D> atlasRightTargets,
            final AtlasOrientation orientation,
            final Point2D dorsalMidline,
            final Point2D ventralMidline,
            final int previewWidth,
            final int previewHeight) {
        final List<SideControls> controls = new ArrayList<>(2);
        if (!Objects.requireNonNull(
                atlasLeftSources, "atlasLeftSources").isEmpty()
                || !Objects.requireNonNull(
                atlasLeftTargets, "atlasLeftTargets").isEmpty()) {
            controls.add(new SideControls(AtlasSide.LEFT,
                    atlasLeftSources, atlasLeftTargets));
        }
        if (!Objects.requireNonNull(
                atlasRightSources, "atlasRightSources").isEmpty()
                || !Objects.requireNonNull(
                atlasRightTargets, "atlasRightTargets").isEmpty()) {
            controls.add(new SideControls(AtlasSide.RIGHT,
                    atlasRightSources, atlasRightTargets));
        }
        return fit(controls, orientation, dorsalMidline, ventralMidline,
                previewWidth, previewHeight);
    }

    /** Applies the complete preview-pixel to preview-pixel warp. */
    public Point2D apply(final Point2D previewPixelCenter) {
        final Point2D point = Objects.requireNonNull(
                previewPixelCenter, "previewPixelCenter");
        return meshState.apply(point);
    }

    /**
     * Applies a field while checking the caller's atlas-side identity. This
     * prevents a reflected rendering path from accidentally swapping sides.
     */
    public Point2D apply(
            final AtlasSide atlasSide,
            final Point2D previewPixelCenter) {
        Objects.requireNonNull(atlasSide, "atlasSide");
        final Point2D point = Objects.requireNonNull(
                previewPixelCenter, "previewPixelCenter");
        return meshState.apply(atlasSide, point);
    }

    /** Returns the analytic forward Jacobian. */
    public Jacobian2D jacobian(final Point2D previewPixelCenter) {
        final Point2D point = Objects.requireNonNull(
                previewPixelCenter, "previewPixelCenter");
        return meshState.jacobian(point);
    }

    /** Inverts one warped preview coordinate with bounded damped Newton steps. */
    public Point2D inverse(final Point2D warpedPreviewPixelCenter) {
        final Point2D target = Objects.requireNonNull(
                warpedPreviewPixelCenter, "warpedPreviewPixelCenter");
        return meshState.inverse(target);
    }

    /**
     * Inverts a field selected from the raw anatomical atlas side. Callers
     * that render atlas geometry should preserve the raw atlas X side and use
     * this overload rather than reclassifying a deformed preview coordinate.
     */
    public Point2D inverse(
            final AtlasSide atlasSide,
            final Point2D warpedPreviewPixelCenter) {
        Objects.requireNonNull(atlasSide, "atlasSide");
        final Point2D target = Objects.requireNonNull(
                warpedPreviewPixelCenter, "warpedPreviewPixelCenter");
        return meshState.inverse(atlasSide, target);
    }

    public CoordinateSpace2D sourceSpace() {
        return CoordinateSpace2D.PREVIEW_PIXEL;
    }

    public CoordinateSpace2D destinationSpace() {
        return CoordinateSpace2D.PREVIEW_PIXEL;
    }

    public String algorithmRevision() {
        return ALGORITHM_REVISION;
    }

    public AtlasOrientation orientation() {
        return orientation;
    }

    public ReviewSectionMode reviewSectionMode() {
        return reviewSectionMode;
    }

    public MidlineSegment imageMidline() {
        return imageMidline;
    }

    /** Active anatomical-side seam in final tissue/preview coordinates. */
    public List<Point2D> imageMidlinePath() {
        return imageMidlinePath;
    }

    public int previewWidth() {
        return previewWidth;
    }

    public int previewHeight() {
        return previewHeight;
    }

    public double seamWidth() {
        return seamWidth;
    }

    public boolean hasControls(final AtlasSide atlasSide) {
        return meshState.hasControls(Objects.requireNonNull(
                atlasSide, "atlasSide"));
    }

    /**
     * Immutable typed controls for one anatomical side. The return type is an
     * immutable list; it additionally retains the small {@code isPresent} /
     * {@code orElseThrow} compatibility surface used by pre-mesh callers.
     */
    public ControlView controls(final AtlasSide atlasSide) {
        final AtlasSide checked = Objects.requireNonNull(
                atlasSide, "atlasSide");
        return new ControlView(checked, meshState.controls(checked), null);
    }

    /** Returns all typed controls in deterministic anatomical-side order. */
    public List<ManualWarpControl> controls() {
        return meshState.controls();
    }

    /** Explicitly named alias for callers migrating from the old API. */
    public List<ManualWarpControl> manualControls(final AtlasSide atlasSide) {
        return List.copyOf(controls(atlasSide));
    }

    /** Returns per-side fit evidence without exposing mutable solver arrays. */
    public Optional<SideDiagnostics> sideDiagnostics(
            final AtlasSide atlasSide) {
        return meshState.sideDiagnostics(Objects.requireNonNull(
                atlasSide, "atlasSide"));
    }

    public Diagnostics diagnostics() {
        return diagnostics;
    }

    /** Legacy exact-outline hash, or blank for the normal preview-domain field. */
    public String outlineContentSha256() {
        return diagnostics.outlineContentSha256();
    }

    /** Maps a preview path while inserting vertices at crossed mesh edges. */
    public List<Point2D> mapPath(
            final List<Point2D> path,
            final boolean closed) {
        final List<Point2D> checked = List.copyOf(
                Objects.requireNonNull(path, "path"));
        final int minimum = closed ? 3 : 2;
        if (checked.size() < minimum) {
            throw new IllegalArgumentException(
                    "The path requires at least " + minimum + " points");
        }
        return meshState.mapPath(checked, closed);
    }

    /** Maps a path through exactly one raw anatomical atlas-side field. */
    public List<Point2D> mapPath(
            final AtlasSide atlasSide,
            final List<Point2D> path,
            final boolean closed) {
        Objects.requireNonNull(atlasSide, "atlasSide");
        final List<Point2D> checked = List.copyOf(
                Objects.requireNonNull(path, "path"));
        final int minimum = closed ? 3 : 2;
        if (checked.size() < minimum) {
            throw new IllegalArgumentException(
                    "The path requires at least " + minimum + " points");
        }
        return meshState.mapPath(atlasSide, checked, closed);
    }

    public ImageSide imageSide(final AtlasSide atlasSide) {
        return imageSideFor(Objects.requireNonNull(
                atlasSide, "atlasSide"), orientation);
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ManualHemisphereWarp2D warp)) {
            return false;
        }
        // Solve timing is diagnostic metadata and is intentionally not part
        // of deterministic transform identity.  The content hash covers the
        // immutable outline, controls, topology, and solved displacement;
        // using it here makes replay equality stable across runs.
        return diagnostics.contentSha256().equals(
                warp.diagnostics.contentSha256());
    }

    @Override
    public int hashCode() {
        return diagnostics.contentSha256().hashCode();
    }

    private static void updateString(
            final MessageDigest digest,
            final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updatePoint(
            final MessageDigest digest,
            final Point2D point) {
        updateDouble(digest, point.x());
        updateDouble(digest, point.y());
    }

    private static void updateInt(
            final MessageDigest digest,
            final int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(value).array());
    }

    private static void updateDouble(
            final MessageDigest digest,
            final double value) {
        final double canonical = value == 0 ? 0 : value;
        digest.update(ByteBuffer.allocate(Long.BYTES)
                .putLong(Double.doubleToLongBits(canonical)).array());
    }

    private static void requireDistinct(
            final List<Point2D> points,
            final String name) {
        final Set<Point2D> unique = new HashSet<>();
        for (final Point2D point : points) {
            if (!unique.add(Objects.requireNonNull(point, name + " point"))) {
                throw new IllegalArgumentException(
                        "Hemisphere " + name + " controls must be distinct");
            }
        }
    }

    private static void requireInsidePreview(
            final Point2D point,
            final int width,
            final int height,
            final String name) {
        if (point.x() < 0 || point.x() > width - 1.0
                || point.y() < 0 || point.y() > height - 1.0) {
            throw new IllegalArgumentException(
                    name + " must lie inside the preview");
        }
    }

    private static boolean onImageSide(
            final Point2D point,
            final ImageSide side,
            final MidlineSegment midline,
            final boolean allowMidline) {
        final double signed = midline.signedDistance(point);
        if (side == ImageSide.IMAGE_LEFT) {
            return allowMidline ? signed >= -SIDE_EPSILON
                    : signed > SIDE_EPSILON;
        }
        return allowMidline ? signed <= SIDE_EPSILON
                : signed < -SIDE_EPSILON;
    }

    private static ImageSide imageSideFor(
            final AtlasSide atlasSide,
            final AtlasOrientation orientation) {
        final boolean same = !orientation.reflected();
        if (atlasSide == AtlasSide.LEFT) {
            return same ? ImageSide.IMAGE_LEFT : ImageSide.IMAGE_RIGHT;
        }
        return same ? ImageSide.IMAGE_RIGHT : ImageSide.IMAGE_LEFT;
    }

    private static AtlasSide atlasSideFor(
            final ImageSide imageSide,
            final AtlasOrientation orientation) {
        if (!orientation.reflected()) {
            return imageSide == ImageSide.IMAGE_LEFT
                    ? AtlasSide.LEFT : AtlasSide.RIGHT;
        }
        return imageSide == ImageSide.IMAGE_LEFT
                ? AtlasSide.RIGHT : AtlasSide.LEFT;
    }

    private static boolean allFinite(final double... values) {
        for (final double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static synchronized MeshTopology cachedTopology(
            final String key,
            final Supplier<MeshTopology> builder) {
        final MeshTopology cached = TOPOLOGY_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        final MeshTopology built = Objects.requireNonNull(
                builder.get(), "builtTopology");
        TOPOLOGY_CACHE.put(key, built);
        topologyBuildCount++;
        while (TOPOLOGY_CACHE.size() > TOPOLOGY_CACHE_CAPACITY) {
            final String eldest = TOPOLOGY_CACHE.keySet().iterator().next();
            TOPOLOGY_CACHE.remove(eldest);
        }
        return built;
    }

    static synchronized void clearTopologyCacheForTests() {
        TOPOLOGY_CACHE.clear();
        topologyBuildCount = 0;
    }

    static synchronized long topologyBuildCountForTests() {
        return topologyBuildCount;
    }

    /** Anatomical atlas side, independent of screen reflection. */
    public enum AtlasSide {
        LEFT,
        RIGHT
    }

    /** Image/display side after applying explicit orientation. */
    public enum ImageSide {
        IMAGE_LEFT,
        IMAGE_RIGHT
    }

    /** Ordered controls for one anatomical atlas side. */
    public record SideControls(
            AtlasSide atlasSide,
            List<Point2D> sourcePoints,
            List<Point2D> targetPoints) {

        public SideControls {
            atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
            sourcePoints = List.copyOf(Objects.requireNonNull(
                    sourcePoints, "sourcePoints"));
            targetPoints = List.copyOf(Objects.requireNonNull(
                    targetPoints, "targetPoints"));
        }
    }

    /**
     * Immutable list view for one side's typed controls. {@code isPresent()}
     * and {@code orElseThrow()} are retained as source-compatible helpers for
     * the pre-Phase-5L integration while the primary API is a list.
     */
    public static final class ControlView
            extends java.util.AbstractList<ManualWarpControl> {
        private final AtlasSide atlasSide;
        private final List<ManualWarpControl> values;
        private final SideControls legacy;

        private ControlView(
                final AtlasSide atlasSide,
                final List<ManualWarpControl> values,
                final SideControls legacy) {
            this.atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
            this.values = List.copyOf(Objects.requireNonNull(
                    values, "values"));
            this.legacy = legacy;
        }

        @Override
        public ManualWarpControl get(final int index) {
            return values.get(index);
        }

        @Override
        public int size() {
            return values.size();
        }

        public boolean isPresent() {
            return !values.isEmpty();
        }

        public SideControls orElseThrow() {
            if (values.isEmpty()) {
                throw new java.util.NoSuchElementException(
                        "No controls for atlas side " + atlasSide);
            }
            if (legacy != null) {
                return legacy;
            }
            final List<Point2D> sources = values.stream()
                    .map(ManualWarpControl::sourcePoint).toList();
            final List<Point2D> targets = values.stream()
                    .map(ManualWarpControl::targetPoint).toList();
            return new SideControls(atlasSide, sources, targets);
        }
    }

    /** Public immutable residual and support evidence for one fitted side. */
    public record SideDiagnostics(
            AtlasSide atlasSide,
            ImageSide imageSide,
            int controlCount,
            double supportRadius,
            double maximumRequestedDisplacement,
            double controlRms,
            int meshTriangleCount) {

        public SideDiagnostics(
                final AtlasSide atlasSide,
                final ImageSide imageSide,
                final int controlCount,
                final double supportRadius,
                final double maximumRequestedDisplacement,
                final double controlRms) {
            this(atlasSide, imageSide, controlCount, supportRadius,
                    maximumRequestedDisplacement, controlRms, 0);
        }

        public SideDiagnostics {
            atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
            imageSide = Objects.requireNonNull(imageSide, "imageSide");
            if (controlCount < MINIMUM_CONTROLS_PER_SIDE
                    || !allFinite(supportRadius,
                    maximumRequestedDisplacement, controlRms)
                    || supportRadius <= 0
                    || maximumRequestedDisplacement < 0
                    || controlRms < 0 || meshTriangleCount < 0) {
                throw new IllegalArgumentException(
                        "Side diagnostics must describe a valid fitted side");
            }
        }
    }

    /**
     * Directed confirmed post-outline dorsal-to-ventral image midline.
     * Positive signed distance is image-left for the D-to-V direction.
     */
    public record MidlineSegment(Point2D dorsal, Point2D ventral) {

        public MidlineSegment {
            dorsal = Objects.requireNonNull(dorsal, "dorsal");
            ventral = Objects.requireNonNull(ventral, "ventral");
            if (dorsal.equals(ventral)) {
                throw new IllegalArgumentException(
                        "Dorsal and ventral midline anchors must be distinct");
            }
        }

        public double length() {
            return Math.hypot(ventral.x() - dorsal.x(),
                    ventral.y() - dorsal.y());
        }

        public double normalX() {
            return -(ventral.y() - dorsal.y()) / length();
        }

        public double normalY() {
            return (ventral.x() - dorsal.x()) / length();
        }

        /** Positive is image-left, negative is image-right. */
        public double signedDistance(final Point2D point) {
            final Point2D checked = Objects.requireNonNull(point, "point");
            return normalX() * (checked.x() - dorsal.x())
                    + normalY() * (checked.y() - dorsal.y());
        }

        public Optional<ImageSide> imageSide(final Point2D point) {
            final double distance = signedDistance(point);
            if (Math.abs(distance) <= SIDE_EPSILON) {
                return Optional.empty();
            }
            return Optional.of(distance > 0
                    ? ImageSide.IMAGE_LEFT : ImageSide.IMAGE_RIGHT);
        }

        public Point2D pointAt(final double fraction) {
            if (!Double.isFinite(fraction)) {
                throw new IllegalArgumentException(
                        "Midline fraction must be finite");
            }
            return new Point2D(
                    dorsal.x() + fraction * (ventral.x() - dorsal.x()),
                    dorsal.y() + fraction * (ventral.y() - dorsal.y()));
        }
    }

    /** Immutable safety and provenance evidence for an accepted warp. */
    public record Diagnostics(
            String algorithmRevision,
            String pixelCenterConvention,
            AtlasOrientation orientation,
            MidlineSegment imageMidline,
            int previewWidth,
            int previewHeight,
            double seamWidth,
            int atlasLeftControlCount,
            int atlasRightControlCount,
            double minimumJacobianDeterminant,
            double minimumSingularValue,
            double maximumSingularValue,
            double maximumAnisotropy,
            double maximumDisplacement,
            double maximumInverseRoundTripError,
            String contentSha256,
            int meshTriangleCount,
            String outlineContentSha256,
            long solveNanos) {

        public Diagnostics(
                final String algorithmRevision,
                final String pixelCenterConvention,
                final AtlasOrientation orientation,
                final MidlineSegment imageMidline,
                final int previewWidth,
                final int previewHeight,
                final double seamWidth,
                final int atlasLeftControlCount,
                final int atlasRightControlCount,
                final double minimumJacobianDeterminant,
                final double minimumSingularValue,
                final double maximumSingularValue,
                final double maximumAnisotropy,
                final double maximumDisplacement,
                final double maximumInverseRoundTripError,
                final String contentSha256) {
            this(algorithmRevision, pixelCenterConvention, orientation,
                    imageMidline, previewWidth, previewHeight, seamWidth,
                    atlasLeftControlCount, atlasRightControlCount,
                    minimumJacobianDeterminant, minimumSingularValue,
                    maximumSingularValue, maximumAnisotropy,
                    maximumDisplacement, maximumInverseRoundTripError,
                    contentSha256, 0, "", 0);
        }

        public Diagnostics {
            algorithmRevision = Objects.requireNonNull(
                    algorithmRevision, "algorithmRevision");
            pixelCenterConvention = Objects.requireNonNull(
                    pixelCenterConvention, "pixelCenterConvention");
            orientation = Objects.requireNonNull(orientation, "orientation");
            imageMidline = Objects.requireNonNull(
                    imageMidline, "imageMidline");
            contentSha256 = Objects.requireNonNull(
                    contentSha256, "contentSha256");
            outlineContentSha256 = Objects.requireNonNull(
                    outlineContentSha256, "outlineContentSha256");
            if (!contentSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "contentSha256 must be lowercase SHA-256 hex");
            }
            if (previewWidth <= 1 || previewHeight <= 1
                    || atlasLeftControlCount < 0
                    || atlasRightControlCount < 0
                    || atlasLeftControlCount + atlasRightControlCount
                    < MINIMUM_CONTROLS_PER_SIDE
                    || !allFinite(seamWidth,
                    minimumJacobianDeterminant, minimumSingularValue,
                    maximumSingularValue, maximumAnisotropy,
                    maximumDisplacement, maximumInverseRoundTripError)
                    || seamWidth <= 0
                    || minimumJacobianDeterminant
                    < MINIMUM_JACOBIAN_DETERMINANT
                    || minimumSingularValue < MINIMUM_SINGULAR_VALUE
                    || maximumSingularValue > MAXIMUM_SINGULAR_VALUE
                    || maximumAnisotropy > MAXIMUM_ANISOTROPY
                    || maximumDisplacement < 0
                    || maximumInverseRoundTripError < 0
                    || meshTriangleCount < 0
                    || solveNanos < 0
                    || (!outlineContentSha256.isEmpty()
                    && !outlineContentSha256.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException(
                        "Diagnostics must describe a safely accepted hemisphere warp");
            }
        }

        public String contentHash() {
            return contentSha256;
        }

        public double solveMillis() {
            return solveNanos / 1_000_000.0;
        }
    }

    /** Row-major derivative of forward preview coordinates. */
    public record Jacobian2D(
            double m00,
            double m01,
            double m10,
            double m11) {

        private static final Jacobian2D IDENTITY =
                new Jacobian2D(1, 0, 0, 1);

        public Jacobian2D {
            if (!allFinite(m00, m01, m10, m11)) {
                throw new IllegalArgumentException(
                        "Jacobian entries must be finite");
            }
        }

        public double determinant() {
            return m00 * m11 - m01 * m10;
        }
    }

    private record SingularValues(
            double minimum,
            double maximum,
            double anisotropy) {
    }

    private static SingularValues singularValues(
            final Jacobian2D jacobian) {
        final double trace = jacobian.m00() * jacobian.m00()
                + jacobian.m01() * jacobian.m01()
                + jacobian.m10() * jacobian.m10()
                + jacobian.m11() * jacobian.m11();
        final double determinant = jacobian.determinant();
        final double discriminant = Math.max(0,
                trace * trace - 4 * determinant * determinant);
        final double maximumSquared = 0.5
                * (trace + Math.sqrt(discriminant));
        final double minimumSquared = 0.5
                * (trace - Math.sqrt(discriminant));
        final double maximum = Math.sqrt(Math.max(0, maximumSquared));
        final double minimum = Math.sqrt(Math.max(0, minimumSquared));
        final double anisotropy = minimum > 0
                ? maximum / minimum : Double.POSITIVE_INFINITY;
        return new SingularValues(minimum, maximum, anisotropy);
    }

    /* ---------------------------------------------------------------------
     * Shared hidden piecewise-affine field implementation. The optional
     * exact-outline domain is retained only for review-state compatibility.
     * ------------------------------------------------------------------ */

    private static final double MESH_EPSILON = 1e-8;
    private static final double MESH_CONTAINMENT_EPSILON = 1e-7;
    private static final double MESH_MIN_TRIANGLE_AREA = 1e-10;

    private static final class MeshState {
        private final BoundaryAuthoritativeTransform2D outline;
        private final AtlasOrientation orientation;
        private final ReviewSectionMode reviewSectionMode;
        private final MidlineSegment midline;
        private final List<Point2D> midlinePath;
        private final int previewWidth;
        private final int previewHeight;
        private final EnumMap<AtlasSide, MeshSide> meshes;
        private final List<ManualWarpControl> allControls;
        private final String contentSha256;

        private MeshState(
                final BoundaryAuthoritativeTransform2D outline,
                final AtlasOrientation orientation,
                final MidlineSegment midline,
                final List<Point2D> midlinePath,
                final EnumMap<AtlasSide, MeshSide> meshes,
                final List<ManualWarpControl> allControls) {
            this.outline = Objects.requireNonNull(outline, "outline");
            this.orientation = Objects.requireNonNull(orientation,
                    "orientation");
            this.reviewSectionMode = ReviewSectionMode.FULL;
            this.midline = Objects.requireNonNull(midline, "midline");
            this.midlinePath = List.copyOf(Objects.requireNonNull(
                    midlinePath, "midlinePath"));
            this.previewWidth = outline.previewWidth();
            this.previewHeight = outline.previewHeight();
            this.meshes = new EnumMap<>(meshes);
            this.allControls = List.copyOf(allControls);
            this.contentSha256 = meshContentHash();
        }

        private MeshState(
                final int previewWidth,
                final int previewHeight,
                final AtlasOrientation orientation,
                final ReviewSectionMode reviewSectionMode,
                final MidlineSegment midline,
                final List<Point2D> midlinePath,
                final EnumMap<AtlasSide, MeshSide> meshes,
                final List<ManualWarpControl> allControls) {
            this.outline = null;
            this.orientation = Objects.requireNonNull(orientation,
                    "orientation");
            this.reviewSectionMode = Objects.requireNonNull(
                    reviewSectionMode, "reviewSectionMode");
            this.midline = Objects.requireNonNull(midline, "midline");
            this.midlinePath = List.copyOf(Objects.requireNonNull(
                    midlinePath, "midlinePath"));
            this.previewWidth = previewWidth;
            this.previewHeight = previewHeight;
            this.meshes = new EnumMap<>(meshes);
            this.allControls = List.copyOf(allControls);
            this.contentSha256 = meshContentHash();
        }

        private static MeshState previewDomain(
                final List<ManualWarpControl> controls,
                final AtlasOrientation orientation,
                final ReviewSectionMode reviewSectionMode,
                final MidlineSegment midline,
                final int width,
                final int height) {
            final double diagonal = Math.hypot(width, height);
            final EnumMap<AtlasSide, MeshSide> meshes = new EnumMap<>(
                    AtlasSide.class);
            for (final AtlasSide side : AtlasSide.values()) {
                final ImageSide imageSide = imageSideFor(side, orientation);
                final List<ManualWarpControl> sideControls = controls.stream()
                        .filter(control -> control.atlasSide() == side).toList();
                final Domain domain;
                try {
                    domain = reviewSectionMode.hasJoinedSeam()
                            ? Domain.preview(width, height, midline, imageSide)
                            : Domain.disjoined(width, height, midline);
                } catch (final IllegalArgumentException emptyDomain) {
                    if (!sideControls.isEmpty()) {
                        throw emptyDomain;
                    }
                    // A coarse Half placement may put the anatomical seam
                    // beyond the padded preview. The absent, uncontrolled raw
                    // side is exact identity and needs no interactive mesh.
                    continue;
                }
                meshes.put(side, MeshSide.fitLocallySupportedDomain(
                        side, imageSide, midline, sideControls, domain,
                        width, height, diagonal));
            }
            return new MeshState(width, height, orientation,
                    reviewSectionMode, midline,
                    List.of(midline.dorsal(), midline.ventral()), meshes,
                    controls);
        }

        private Point2D apply(final Point2D point) {
            if (!containsPoint(point)) {
                return point;
            }
            final MeshSide side = sourceMesh(point);
            return side == null ? point : side.apply(point);
        }

        private Point2D apply(
                final AtlasSide atlasSide,
                final Point2D point) {
            if (!containsPoint(point)) {
                return point;
            }
            final MeshSide side = meshes.get(atlasSide);
            if (side == null || !side.strictlyContains(point)) {
                return point;
            }
            return side.apply(point);
        }

        private Point2D inverse(final Point2D point) {
            if (!containsPoint(point)) {
                return point;
            }
            final MeshSide side = targetMesh(point);
            return side == null ? point : side.inverse(point);
        }

        private Point2D inverse(
                final AtlasSide atlasSide,
                final Point2D point) {
            if (!containsPoint(point)) {
                return point;
            }
            final MeshSide side = meshes.get(atlasSide);
            if (side == null || !side.strictlyContains(point)) {
                return point;
            }
            return side.inverse(point);
        }

        private Jacobian2D jacobian(final Point2D point) {
            if (!containsPoint(point) || onMidlineOrBoundary(point)) {
                return Jacobian2D.IDENTITY;
            }
            final MeshSide side = sourceMesh(point);
            return side == null ? Jacobian2D.IDENTITY : side.jacobian(point);
        }

        private MeshSide sourceMesh(final Point2D point) {
            for (final MeshSide mesh : meshes.values()) {
                if (mesh.onBoundary(point)) {
                    return null;
                }
            }
            for (final MeshSide mesh : meshes.values()) {
                if (mesh.strictlyContains(point)) {
                    return mesh;
                }
            }
            return null;
        }

        private MeshSide targetMesh(final Point2D point) {
            // Fixed outer/seam boundaries make each safe target field cover
            // the same exact side polygon as its source field.
            return sourceMesh(point);
        }

        private boolean onMidlineOrBoundary(final Point2D point) {
            if (reviewSectionMode.hasJoinedSeam()) {
                for (int index = 0; index + 1 < midlinePath.size(); index++) {
                    if (distancePointSegment(point, midlinePath.get(index),
                            midlinePath.get(index + 1)) <= MESH_EPSILON) {
                        return true;
                    }
                }
            }
            if (outline == null) {
                return false;
            }
            return outline.tissueBoundary().stream().anyMatch(boundary ->
                    distancePointSegment(point, boundary,
                            outline.tissueBoundary().get(
                                    (outline.tissueBoundary().indexOf(boundary)
                                            + 1)
                                    % outline.tissueBoundary().size()))
                            <= MESH_EPSILON);
        }

        private boolean hasControls(final AtlasSide side) {
            final MeshSide mesh = meshes.get(side);
            return mesh != null && !mesh.controls.isEmpty();
        }

        private List<ManualWarpControl> controls(final AtlasSide side) {
            final MeshSide mesh = meshes.get(side);
            return mesh == null ? List.of() : mesh.controls;
        }

        private List<ManualWarpControl> controls() {
            final List<ManualWarpControl> result = new ArrayList<>();
            for (final AtlasSide side : AtlasSide.values()) {
                result.addAll(controls(side));
            }
            return List.copyOf(result);
        }

        private Optional<SideDiagnostics> sideDiagnostics(
                final AtlasSide side) {
            final MeshSide mesh = meshes.get(side);
            if (mesh == null || mesh.controls.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(mesh.sideDiagnostics());
        }

        private int triangleCount() {
            return meshes.values().stream()
                    .mapToInt(mesh -> mesh.triangles.size()).sum();
        }

        private String contentSha256() {
            return contentSha256;
        }

        private MeshAudit audit(final double diagonal) {
            double minimumDeterminant = Double.POSITIVE_INFINITY;
            double minimumSingular = Double.POSITIVE_INFINITY;
            double maximumSingular = 0;
            double maximumAnisotropy = 0;
            double maximumDisplacement = 0;
            double maximumRoundTrip = 0;
            for (final MeshSide mesh : meshes.values()) {
                final MeshAudit sideAudit = mesh.audit(diagonal);
                minimumDeterminant = Math.min(minimumDeterminant,
                        sideAudit.minimumDeterminant());
                minimumSingular = Math.min(minimumSingular,
                        sideAudit.minimumSingularValue());
                maximumSingular = Math.max(maximumSingular,
                        sideAudit.maximumSingularValue());
                maximumAnisotropy = Math.max(maximumAnisotropy,
                        sideAudit.maximumAnisotropy());
                maximumDisplacement = Math.max(maximumDisplacement,
                        sideAudit.maximumDisplacement());
                maximumRoundTrip = Math.max(maximumRoundTrip,
                        sideAudit.maximumRoundTripError());
            }
            if (!allFinite(minimumDeterminant, minimumSingular,
                    maximumSingular, maximumAnisotropy, maximumDisplacement,
                    maximumRoundTrip)
                    || minimumDeterminant < MINIMUM_JACOBIAN_DETERMINANT
                    || minimumSingular < MINIMUM_SINGULAR_VALUE
                    || maximumSingular > MAXIMUM_SINGULAR_VALUE
                    || maximumAnisotropy > MAXIMUM_ANISOTROPY
                    || maximumDisplacement
                    > MAXIMUM_SAMPLED_DISPLACEMENT_FRACTION * diagonal
                    || maximumRoundTrip > ROUND_TRIP_TOLERANCE) {
                throw new IllegalArgumentException(
                        "Hemisphere mesh Jacobian/singular-value/displacement safety gate failed");
            }
            return new MeshAudit(minimumDeterminant, minimumSingular,
                    maximumSingular, maximumAnisotropy, maximumDisplacement,
                    maximumRoundTrip);
        }

        private List<Point2D> mapPath(
                final List<Point2D> path,
                final boolean closed) {
            final List<Point2D> result = new ArrayList<>();
            final int edgeCount = closed ? path.size() : path.size() - 1;
            for (int edge = 0; edge < edgeCount; edge++) {
                final Point2D start = path.get(edge);
                final Point2D end = path.get((edge + 1) % path.size());
                final List<Double> parameters = new ArrayList<>();
                parameters.add(0.0);
                parameters.add(1.0);
                for (final MeshSide mesh : meshes.values()) {
                    for (final Edge edgeValue : mesh.edges) {
                        addSegmentIntersection(parameters, start, end,
                                mesh.vertices.get(edgeValue.a),
                                mesh.vertices.get(edgeValue.b));
                    }
                }
                parameters.sort(Double::compareTo);
                final List<Double> unique = uniqueParameters(parameters);
                for (int index = 0; index < unique.size(); index++) {
                    if (edge > 0 && index == 0) {
                        continue;
                    }
                    final Point2D source = interpolate(start, end,
                            unique.get(index));
                    addDistinct(result, apply(source));
                }
            }
            if (closed && result.size() > 1
                    && distance(result.get(0), result.get(result.size() - 1))
                    <= MESH_EPSILON) {
                result.remove(result.size() - 1);
            }
            return List.copyOf(result);
        }

        private List<Point2D> mapPath(
                final AtlasSide atlasSide,
                final List<Point2D> path,
                final boolean closed) {
            final MeshSide mesh = meshes.get(Objects.requireNonNull(
                    atlasSide, "atlasSide"));
            if (mesh == null) {
                return List.copyOf(path);
            }
            final List<Point2D> result = new ArrayList<>();
            final int edgeCount = closed ? path.size() : path.size() - 1;
            for (int edge = 0; edge < edgeCount; edge++) {
                final Point2D start = path.get(edge);
                final Point2D end = path.get((edge + 1) % path.size());
                final List<Double> parameters = new ArrayList<>();
                parameters.add(0.0);
                parameters.add(1.0);
                for (final Edge edgeValue : mesh.edges) {
                    addSegmentIntersection(parameters, start, end,
                            mesh.vertices.get(edgeValue.a),
                            mesh.vertices.get(edgeValue.b));
                }
                parameters.sort(Double::compareTo);
                final List<Double> unique = uniqueParameters(parameters);
                for (int index = 0; index < unique.size(); index++) {
                    if (edge > 0 && index == 0) {
                        continue;
                    }
                    final Point2D source = interpolate(start, end,
                            unique.get(index));
                    addDistinct(result, apply(atlasSide, source));
                }
            }
            if (closed && result.size() > 1
                    && distance(result.get(0), result.get(result.size() - 1))
                    <= MESH_EPSILON) {
                result.remove(result.size() - 1);
            }
            return List.copyOf(result);
        }

        private String meshContentHash() {
            final MessageDigest digest = sha256();
            updateString(digest, ALGORITHM_REVISION);
            updateString(digest, outline == null ? "legacy-preview-domain"
                    : outline.contentSha256());
            updateString(digest, orientation.name());
            updateString(digest, reviewSectionMode.name());
            updateInt(digest, midlinePath.size());
            for (final Point2D point : midlinePath) {
                updatePoint(digest, point);
            }
            for (final AtlasSide side : AtlasSide.values()) {
                updateString(digest, side.name());
                final MeshSide mesh = meshes.get(side);
                if (mesh == null) {
                    updateString(digest, "identity-no-preview-domain");
                } else {
                    mesh.updateHash(digest);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        private boolean containsPoint(final Point2D point) {
            if (outline != null) {
                return outline.containsTissuePoint(point);
            }
            for (final MeshSide mesh : meshes.values()) {
                if (mesh.strictlyContains(point) || mesh.onBoundary(point)) {
                    return true;
                }
            }
            return false;
        }

        private static void addDistinct(
                final List<Point2D> points,
                final Point2D point) {
            if (points.isEmpty() || distance(points.get(points.size() - 1),
                    point) > MESH_EPSILON) {
                points.add(point);
            }
        }
    }

    private static final class MeshSide {
        private final AtlasSide atlasSide;
        private final ImageSide imageSide;
        private final MidlineSegment midline;
        private final List<Point2D> polygon;
        private final List<Segment> boundarySegments;
        private final List<Point2D> vertices;
        private final List<Triangle> triangles;
        private final List<Edge> edges;
        private final List<ManualWarpControl> controls;
        private final boolean[] fixed;
        private final double[] displacementX;
        private final double[] displacementY;
        private final Map<PointKey, Point2D> sourceControlTargets;
        private final Map<PointKey, Point2D> targetControlSources;
        private final TriangleSpatialIndex sourceTriangleIndex;
        private final TriangleSpatialIndex targetTriangleIndex;
        private final boolean locallySupported;
        private final double maximumRequestedDisplacement;
        private final double previewDiagonal;

        private MeshSide(
                final AtlasSide atlasSide,
                final ImageSide imageSide,
                final MidlineSegment midline,
                final List<Point2D> polygon,
                final List<Segment> boundarySegments,
                final MeshTopology topology,
                final List<ManualWarpControl> controls,
                final double diagonal,
                final boolean locallySupported) {
            this(atlasSide, imageSide, midline, polygon, boundarySegments,
                    topology, controls, diagonal, locallySupported, null);
        }

        private MeshSide(
                final AtlasSide atlasSide,
                final ImageSide imageSide,
                final MidlineSegment midline,
                final List<Point2D> polygon,
                final List<Segment> boundarySegments,
                final MeshTopology topology,
                final List<ManualWarpControl> controls,
                final double diagonal,
                final boolean locallySupported,
                final MeshSide boundaryBaseline) {
            this.atlasSide = atlasSide;
            this.imageSide = imageSide;
            this.midline = midline;
            this.polygon = List.copyOf(polygon);
            this.boundarySegments = List.copyOf(boundarySegments);
            this.vertices = topology.vertices();
            this.triangles = topology.triangles();
            this.edges = topology.edges();
            this.controls = List.copyOf(controls);
            this.locallySupported = locallySupported;
            this.fixed = topology.fixed().clone();
            final Map<Integer, ManualWarpControl> controlsByVertex =
                    new HashMap<>();
            final Map<PointKey, Point2D> targetsBySource = new HashMap<>();
            final Map<PointKey, Point2D> sourcesByTarget = new HashMap<>();
            double maxRequested = 0;
            Point2D maxRequestedLocation = null;
            for (final ManualWarpControl control : controls) {
                targetsBySource.put(PointKey.of(control.sourcePoint()),
                        control.targetPoint());
                sourcesByTarget.put(PointKey.of(control.targetPoint()),
                        control.sourcePoint());
                final int vertex = findExactVertex(vertices,
                        control.sourcePoint());
                if (vertex >= 0) {
                    if (boundarySegments.stream().anyMatch(segment ->
                            distancePointSegment(control.sourcePoint(),
                                    segment.a(), segment.b())
                            <= MESH_EPSILON)) {
                        throw new IllegalArgumentException(
                                "Manual warp controls must be strictly interior and off the fixed boundary/seam");
                    }
                    if (controlsByVertex.put(vertex, control) != null) {
                        throw new IllegalArgumentException(
                                "Manual warp control sources must be unique");
                    }
                    if (!fixed[vertex]) {
                        throw new IllegalArgumentException(
                                "Cached manual-mesh topology omitted a control constraint");
                    }
                }
                final double requested = distance(control.sourcePoint(),
                        control.targetPoint());
                if (requested > maxRequested) {
                    maxRequested = requested;
                    maxRequestedLocation = control.targetPoint();
                }
            }
            if (maxRequested > MAXIMUM_SAMPLED_DISPLACEMENT_FRACTION * diagonal) {
                throw new ManualWarpException(
                        ManualWarpFailureKind.EXCESSIVE_DISPLACEMENT,
                        "That move is too far from the starting dot. Move it a shorter distance or add a nearby point.",
                        "maximumRequestedDisplacement=" + maxRequested
                                + "; previewDiagonal=" + diagonal,
                        ManualWarpSafetyReport.measured(
                                ManualWarpSafetyGate.DISPLACEMENT,
                                maxRequested,
                                MAXIMUM_SAMPLED_DISPLACEMENT_FRACTION
                                        * diagonal,
                                atlasSide, maxRequestedLocation,
                                "maximum requested control displacement"));
            }
            this.maximumRequestedDisplacement = maxRequested;
            this.previewDiagonal = diagonal;
            final double[] prescribedX = new double[vertices.size()];
            final double[] prescribedY = new double[vertices.size()];
            for (final Map.Entry<Integer, ManualWarpControl> entry
                    : controlsByVertex.entrySet()) {
                final int index = entry.getKey();
                prescribedX[index] = entry.getValue().targetPoint().x()
                        - entry.getValue().sourcePoint().x();
                prescribedY[index] = entry.getValue().targetPoint().y()
                        - entry.getValue().sourcePoint().y();
            }
            final double[][] solved = locallySupported
                    ? solveLocallySupported(vertices, fixed, controls,
                            boundarySegments, diagonal, boundaryBaseline)
                    : solveHarmonic(topology.harmonicSystem(),
                            prescribedX, prescribedY);
            this.displacementX = solved[0];
            this.displacementY = solved[1];
            this.sourceControlTargets = Map.copyOf(targetsBySource);
            this.targetControlSources = Map.copyOf(sourcesByTarget);
            this.sourceTriangleIndex = TriangleSpatialIndex.source(
                    vertices, triangles);
            this.targetTriangleIndex = TriangleSpatialIndex.target(
                    vertices, triangles, displacementX, displacementY);
        }

        private static MeshSide fit(
                final AtlasSide atlasSide,
                final ImageSide imageSide,
                final List<ManualWarpControl> controls,
                final List<Point2D> tissueSideBoundary,
                final MidlineSegment midline,
                final int width,
                final int height,
                final double diagonal,
                final String outlineContentSha256) {
            final Domain domain = Domain.exact(tissueSideBoundary, midline);
            return fitDomain(atlasSide, imageSide, midline, controls, domain,
                    width, height, diagonal,
                    topologyKey(outlineContentSha256, atlasSide, imageSide,
                            controls));
        }

        private static MeshSide fitDomain(
                final AtlasSide atlasSide,
                final ImageSide imageSide,
                final MidlineSegment midline,
                final List<ManualWarpControl> controls,
                final Domain domain,
                final int width,
                final int height,
                final double diagonal) {
            for (final ManualWarpControl control : controls) {
                validateControl(control, atlasSide, imageSide, domain,
                        width, height, diagonal);
            }
            return fitDomain(atlasSide, imageSide, midline, controls, domain,
                    width, height, diagonal, null);
        }

        private static MeshSide fitDomain(
                final AtlasSide atlasSide,
                final ImageSide imageSide,
                final MidlineSegment midline,
                final List<ManualWarpControl> controls,
                final Domain domain,
                final int width,
                final int height,
                final double diagonal,
                final String topologyKey) {
            for (final ManualWarpControl control : controls) {
                validateControl(control, atlasSide, imageSide, domain,
                        width, height, diagonal);
            }
            final Supplier<MeshTopology> builder = () ->
                    buildTopology(domain, controls, diagonal);
            final MeshTopology topology = topologyKey == null
                    ? builder.get() : cachedTopology(topologyKey, builder);
            return new MeshSide(atlasSide, imageSide, midline, domain.polygon,
                    domain.segments, topology, controls,
                    diagonal, false);
        }

        private static MeshSide fitLocallySupportedDomain(
                final AtlasSide atlasSide,
                final ImageSide imageSide,
                final MidlineSegment midline,
                final List<ManualWarpControl> controls,
                final Domain domain,
                final int width,
                final int height,
                final double diagonal) {
            for (final ManualWarpControl control : controls) {
                validateControl(control, atlasSide, imageSide, domain,
                        width, height, diagonal);
            }
            final List<ManualWarpControl> boundaryControls = controls.stream()
                    .filter(control -> control.origin()
                            == ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR)
                    .toList();
            if (!boundaryControls.isEmpty()
                    && boundaryControls.size() != controls.size()) {
                // Reconstruct exactly the installed boundary-only field. Splitting
                // its triangles retains every affine patch; re-Delaunay would
                // change the map even when every new control is unmoved.
                final MeshTopology baselineTopology = buildTopology(
                        domain, boundaryControls, diagonal);
                final MeshSide baseline = new MeshSide(atlasSide, imageSide,
                        midline, domain.polygon, domain.segments,
                        baselineTopology, boundaryControls, diagonal, true);
                final MeshTopology topology = refineTopology(
                        baselineTopology, controls);
                return new MeshSide(atlasSide, imageSide, midline,
                        domain.polygon, domain.segments, topology, controls,
                        diagonal, true, baseline);
            }
            final MeshTopology topology = buildTopology(
                    domain, controls, diagonal);
            return new MeshSide(atlasSide, imageSide, midline, domain.polygon,
                    domain.segments, topology, controls, diagonal, true);
        }

        /**
         * Compact positive-weight interpolation on the hidden lattice. A
         * control is exact at its own vertex, while vertices outside its
         * finite support radius receive zero displacement from it. This is
         * deliberately local: moving one dot cannot pull the whole side.
         */
        private static double[][] solveLocallySupported(
                final List<Point2D> vertices,
                final boolean[] fixed,
                final List<ManualWarpControl> controls,
                final List<Segment> boundarySegments,
                final double diagonal,
                final MeshSide boundaryBaseline) {
            final List<ManualWarpControl> boundaryControls = controls.stream()
                    .filter(control -> control.origin()
                            == ManualWarpControlOrigin
                                    .ATLAS_TISSUE_BOUNDARY_PAIR)
                    .toList();
            final List<ManualWarpControl> refinementControls = controls.stream()
                    .filter(control -> control.origin()
                            != ManualWarpControlOrigin
                                    .ATLAS_TISSUE_BOUNDARY_PAIR)
                    .toList();
            if (boundaryControls.isEmpty()
                    || refinementControls.isEmpty()) {
                return solveControlLayer(vertices, fixed, controls,
                        boundarySegments, diagonal);
            }

            final boolean[] domainFixed = new boolean[vertices.size()];
            final double[][] boundary = new double[2][vertices.size()];
            Objects.requireNonNull(boundaryBaseline,
                    "Mixed controls require the preserved boundary mesh");
            for (int index = 0; index < vertices.size(); index++) {
                final Point2D point = vertices.get(index);
                domainFixed[index] = boundarySegments.stream().anyMatch(
                        segment -> distancePointSegment(point,
                                segment.a(), segment.b()) <= MESH_EPSILON);
                final Point2D mapped = boundaryBaseline.apply(point);
                boundary[0][index] = mapped.x() - point.x();
                boundary[1][index] = mapped.y() - point.y();
            }

            final List<ManualWarpControl> residualControls =
                    new ArrayList<>(refinementControls.size());
            for (final ManualWarpControl control : refinementControls) {
                final int vertex = findExactVertex(
                        vertices, control.sourcePoint());
                if (vertex < 0) {
                    throw new IllegalArgumentException(
                            "Manual refinement control is not a mesh vertex");
                }
                final double requestedX = control.targetPoint().x()
                        - control.sourcePoint().x();
                final double requestedY = control.targetPoint().y()
                        - control.sourcePoint().y();
                residualControls.add(new ManualWarpControl(
                        control.id(), control.atlasSide(), control.origin(),
                        control.groupId(), control.structureAcronym(),
                        control.sourcePoint(), new Point2D(
                                control.sourcePoint().x() + requestedX
                                        - boundary[0][vertex],
                                control.sourcePoint().y() + requestedY
                                        - boundary[1][vertex])));
            }
            final boolean[] residualFixed = domainFixed.clone();
            markControlVerticesFixed(vertices, residualFixed,
                    boundaryControls);
            final double[][] residual = solveControlLayer(
                    vertices, residualFixed, residualControls,
                    boundarySegments, diagonal);
            for (int index = 0; index < vertices.size(); index++) {
                boundary[0][index] += residual[0][index];
                boundary[1][index] += residual[1][index];
            }
            return boundary;
        }

        private static void markControlVerticesFixed(
                final List<Point2D> vertices,
                final boolean[] fixed,
                final List<ManualWarpControl> controls) {
            for (final ManualWarpControl control : controls) {
                final int vertex = findExactVertex(
                        vertices, control.sourcePoint());
                if (vertex < 0) {
                    throw new IllegalArgumentException(
                            "Manual warp control is not a mesh vertex");
                }
                fixed[vertex] = true;
            }
        }

        private static double[][] solveControlLayer(
                final List<Point2D> vertices,
                final boolean[] fixed,
                final List<ManualWarpControl> controls,
                final List<Segment> boundarySegments,
                final double diagonal) {
            final double[] dx = new double[vertices.size()];
            final double[] dy = new double[vertices.size()];
            if (controls.isEmpty()) {
                return new double[][]{dx, dy};
            }
            final double radius = localSupportRadius(controls, diagonal);
            for (int vertex = 0; vertex < vertices.size(); vertex++) {
                final Point2D point = vertices.get(vertex);
                ManualWarpControl exact = null;
                for (final ManualWarpControl control : controls) {
                    if (distance(point, control.sourcePoint())
                            <= MESH_EPSILON) {
                        exact = control;
                        break;
                    }
                }
                if (exact != null) {
                    dx[vertex] = exact.targetPoint().x()
                            - exact.sourcePoint().x();
                    dy[vertex] = exact.targetPoint().y()
                            - exact.sourcePoint().y();
                    continue;
                }
                if (fixed[vertex]) {
                    continue;
                }
                double weightSum = 0;
                double weightedX = 0;
                double weightedY = 0;
                for (final ManualWarpControl control : controls) {
                    final double distance = distance(
                            point, control.sourcePoint());
                    final double normalized = distance / radius;
                    final double compact = wendlandC2(normalized);
                    final double weight = compact == 0 ? 0
                            : compact / Math.max(1e-6,
                                    normalized * normalized);
                    if (weight == 0) {
                        continue;
                    }
                    weightSum += weight;
                    weightedX += weight * (control.targetPoint().x()
                            - control.sourcePoint().x());
                    weightedY += weight * (control.targetPoint().y()
                            - control.sourcePoint().y());
                }
                if (weightSum > 0) {
                    // The unit identity weight makes influence taper to zero
                    // at the compact-support edge instead of creating a hard
                    // plateau when only one control is nearby.
                    final double scale = 1.0 + weightSum;
                    final double boundaryRamp = Math.max(4.0,
                            Math.min(radius * 0.35, diagonal * 0.04));
                    final double normalizedBoundaryDistance = Math.min(1.0,
                            nearestBoundaryDistance(point, boundarySegments)
                                    / boundaryRamp);
                    final double boundaryFade = normalizedBoundaryDistance
                            * normalizedBoundaryDistance
                            * (3.0 - 2.0
                                    * normalizedBoundaryDistance);
                    dx[vertex] = boundaryFade * weightedX / scale;
                    dy[vertex] = boundaryFade * weightedY / scale;
                }
            }
            return new double[][]{dx, dy};
        }

        private static double localSupportRadius(
                final List<ManualWarpControl> controls,
                final double diagonal) {
            final List<Double> nearest = new ArrayList<>();
            for (int first = 0; first < controls.size(); first++) {
                double best = Double.POSITIVE_INFINITY;
                for (int second = 0; second < controls.size(); second++) {
                    if (first == second) {
                        continue;
                    }
                    best = Math.min(best, distance(
                            controls.get(first).sourcePoint(),
                            controls.get(second).sourcePoint()));
                }
                if (Double.isFinite(best)) {
                    nearest.add(best);
                }
            }
            nearest.sort(Double::compareTo);
            final double spacing = nearest.isEmpty()
                    ? diagonal * MINIMUM_LOCAL_SUPPORT_FRACTION
                    : nearest.get(nearest.size() / 2);
            return Math.max(diagonal * MINIMUM_LOCAL_SUPPORT_FRACTION,
                    Math.min(diagonal * MAXIMUM_LOCAL_SUPPORT_FRACTION,
                            spacing * 1.75));
        }

        private static double wendlandC2(final double normalizedDistance) {
            if (!(normalizedDistance < 1.0)) {
                return 0;
            }
            final double remaining = 1.0 - Math.max(0,
                    normalizedDistance);
            return remaining * remaining * remaining * remaining
                    * (4.0 * normalizedDistance + 1.0);
        }

        private static MeshTopology buildTopology(
                final Domain domain,
                final List<ManualWarpControl> controls,
                final double diagonal) {
            final VertexStore store = new VertexStore();
            final List<Integer> boundaryIndices = new ArrayList<>();
            for (final Point2D point : domain.polygon) {
                boundaryIndices.add(store.add(point));
            }
            for (final ManualWarpControl control : controls) {
                store.add(control.sourcePoint());
            }
            addInteriorGridPoints(store, domain.polygon, domain.segments,
                    diagonal);
            final List<Integer> triangulationBoundary =
                    simplifyCollinearBoundary(boundaryIndices, store.points);
            final List<Triangle> triangles = triangulateReduced(
                    triangulationBoundary, store.points, domain.polygon,
                    domain.segments, controls);
            final List<Point2D> vertices = List.copyOf(store.points);
            final List<Edge> edges = uniqueEdges(triangles);
            final boolean[] fixed = new boolean[vertices.size()];
            for (int index = 0; index < vertices.size(); index++) {
                final Point2D point = vertices.get(index);
                fixed[index] = domain.segments.stream().anyMatch(segment ->
                        distancePointSegment(point, segment.a(), segment.b())
                        <= MESH_EPSILON);
            }
            for (final ManualWarpControl control : controls) {
                final int vertex = findExactVertex(vertices,
                        control.sourcePoint());
                if (vertex < 0 || fixed[vertex]) {
                    throw new IllegalArgumentException(
                            "Manual warp control is not a free interior topology vertex");
                }
                fixed[vertex] = true;
            }
            return new MeshTopology(vertices, triangles, edges, fixed,
                    HarmonicSystem.create(vertices, edges, fixed));
        }

        /** Subdivide only: old mesh edges remain edges or collinear edge chains. */
        private static MeshTopology refineTopology(
                final MeshTopology baseline,
                final List<ManualWarpControl> controls) {
            final List<Point2D> vertices = new ArrayList<>(baseline.vertices());
            final List<Triangle> triangles = new ArrayList<>(baseline.triangles());
            for (final ManualWarpControl control : controls) {
                if (findExactVertex(vertices, control.sourcePoint()) >= 0) {
                    continue;
                }
                final int inserted = vertices.size();
                vertices.add(control.sourcePoint());
                insertInteriorVertex(triangles, vertices, inserted);
            }
            final boolean[] fixed = Arrays.copyOf(baseline.fixed(), vertices.size());
            markControlVerticesFixed(vertices, fixed, controls);
            final List<Edge> edges = uniqueEdges(triangles);
            return new MeshTopology(vertices, triangles, edges, fixed,
                    HarmonicSystem.create(vertices, edges, fixed));
        }

        private static List<Integer> simplifyCollinearBoundary(
                final List<Integer> boundaryIndices,
                final List<Point2D> points) {
            final List<Integer> result = new ArrayList<>(boundaryIndices);
            boolean changed;
            do {
                changed = false;
                for (int index = 0; index < result.size(); index++) {
                    final int previous = result.get(
                            (index - 1 + result.size()) % result.size());
                    final int current = result.get(index);
                    final int next = result.get((index + 1) % result.size());
                    if (Math.abs(cross(points.get(previous),
                            points.get(current), points.get(next)))
                            <= MESH_MIN_TRIANGLE_AREA
                            && distancePointSegment(points.get(current),
                                    points.get(previous), points.get(next))
                            <= MESH_CONTAINMENT_EPSILON) {
                        result.remove(index);
                        changed = true;
                        break;
                    }
                }
            } while (changed && result.size() > 3);
            if (result.size() < 3) {
                throw new IllegalArgumentException(
                        "Reviewed side boundary is collinear");
            }
            return List.copyOf(result);
        }

        private static String topologyKey(
                final String outlineContentSha256,
                final AtlasSide atlasSide,
                final ImageSide imageSide,
                final List<ManualWarpControl> controls) {
            final MessageDigest digest = sha256();
            updateString(digest, ALGORITHM_REVISION);
            updateString(digest, outlineContentSha256);
            updateString(digest, atlasSide.name());
            updateString(digest, imageSide.name());
            updateInt(digest, controls.size());
            for (final ManualWarpControl control : controls) {
                updatePoint(digest, control.sourcePoint());
            }
            return HexFormat.of().formatHex(digest.digest());
        }

        private static void validateControl(
                final ManualWarpControl control,
                final AtlasSide atlasSide,
                final ImageSide imageSide,
                final Domain domain,
                final int width,
                final int height,
                final double diagonal) {
            if (control.atlasSide() != atlasSide) {
                throw new IllegalArgumentException(
                        "Control side does not match the fitted mesh side");
            }
            if (!strictlyInside(domain.polygon, control.sourcePoint())
                    || !strictlyInside(domain.polygon,
                    control.targetPoint())) {
                throw new ManualWarpException(
                        ManualWarpFailureKind.OUTSIDE_WORKSPACE,
                        "That point left the editable image area or crossed the joined midline. Move it back inside, or choose Disjoined for separated tissue halves.",
                        "source=" + control.sourcePoint() + "; target="
                                + control.targetPoint() + "; side="
                                + atlasSide,
                        ManualWarpSafetyReport.unmeasured(
                                ManualWarpSafetyGate.WORKSPACE,
                                atlasSide, control.targetPoint(),
                                "control target is outside the editable side domain"));
            }
            final double displacement = distance(control.sourcePoint(),
                    control.targetPoint());
            if (displacement > MAXIMUM_SAMPLED_DISPLACEMENT_FRACTION * diagonal) {
                throw new ManualWarpException(
                        ManualWarpFailureKind.EXCESSIVE_DISPLACEMENT,
                        "That move is too far from the starting dot. Move it a shorter distance or add a nearby point.",
                        "displacement=" + displacement
                                + "; previewDiagonal=" + diagonal,
                        ManualWarpSafetyReport.measured(
                                ManualWarpSafetyGate.DISPLACEMENT,
                                displacement,
                                MAXIMUM_SAMPLED_DISPLACEMENT_FRACTION
                                        * diagonal,
                                atlasSide, control.targetPoint(),
                                "control displacement"));
            }
        }

        private Point2D apply(final Point2D point) {
            final Point2D exact = sourceControlTargets.get(
                    PointKey.of(point));
            if (exact != null) {
                return exact;
            }
            if (onBoundary(point)) {
                return point;
            }
            final Triangle triangle = locateSource(point);
            return triangle == null ? point : mapBarycentric(point, triangle);
        }

        private Point2D inverse(final Point2D point) {
            final Point2D exact = targetControlSources.get(
                    PointKey.of(point));
            if (exact != null) {
                return exact;
            }
            if (onBoundary(point)) {
                return point;
            }
            final Triangle triangle = locateTarget(point);
            if (triangle == null) {
                return point;
            }
            return inverseBarycentric(point, triangle);
        }

        private Jacobian2D jacobian(final Point2D point) {
            final Triangle triangle = locateSource(point);
            return triangle == null || onBoundary(point)
                    ? Jacobian2D.IDENTITY : jacobian(triangle);
        }

        private Jacobian2D jacobian(final Triangle triangle) {
            final Point2D p0 = vertices.get(triangle.a());
            final Point2D p1 = vertices.get(triangle.b());
            final Point2D p2 = vertices.get(triangle.c());
            final Point2D q0 = displaced(p0, triangle.a());
            final Point2D q1 = displaced(p1, triangle.b());
            final Point2D q2 = displaced(p2, triangle.c());
            final double bx0 = p1.x() - p0.x();
            final double by0 = p1.y() - p0.y();
            final double bx1 = p2.x() - p0.x();
            final double by1 = p2.y() - p0.y();
            final double determinant = bx0 * by1 - bx1 * by0;
            if (Math.abs(determinant) <= MESH_MIN_TRIANGLE_AREA) {
                throw new IllegalArgumentException(
                        "Mesh source triangle is degenerate: " + triangle
                                + " [" + p0 + ", " + p1 + ", " + p2
                                + "], doubleArea=" + determinant);
            }
            final double ax0 = q1.x() - q0.x();
            final double ay0 = q1.y() - q0.y();
            final double ax1 = q2.x() - q0.x();
            final double ay1 = q2.y() - q0.y();
            return new Jacobian2D(
                    (ax0 * by1 - ax1 * by0) / determinant,
                    (-ax0 * bx1 + ax1 * bx0) / determinant,
                    (ay0 * by1 - ay1 * by0) / determinant,
                    (-ay0 * bx1 + ay1 * bx0) / determinant);
        }

        private SideDiagnostics sideDiagnostics() {
            final MeshAudit audit = audit(previewDiagonal);
            return new SideDiagnostics(atlasSide, imageSide,
                    controls.size(), Math.max(1.0,
                    Math.sqrt(triangles.size())),
                    maximumRequestedDisplacement,
                    controlResidual(), triangles.size());
        }

        private double controlResidual() {
            double squared = 0;
            for (final ManualWarpControl control : controls) {
                final Point2D mapped = apply(control.sourcePoint());
                squared += squaredDistance(mapped, control.targetPoint());
            }
            return controls.isEmpty() ? 0
                    : Math.sqrt(squared / controls.size());
        }

        private MeshAudit audit(final double diagonal) {
            double minimumDeterminant = Double.POSITIVE_INFINITY;
            double minimumSingular = Double.POSITIVE_INFINITY;
            double maximumSingular = 0;
            double maximumAnisotropy = 0;
            double maximumDisplacement = 0;
            Point2D maximumDisplacementLocation = null;
            for (int index = 0; index < vertices.size(); index++) {
                final Point2D source = vertices.get(index);
                final Point2D mapped = displaced(source, index);
                if (!containsInclusive(polygon, mapped)) {
                    throw new ManualWarpException(
                            ManualWarpFailureKind.OUTSIDE_WORKSPACE,
                            "That move left the editable image area or crossed the joined midline. Move it back inside, or choose Disjoined for separated tissue halves.",
                            "mappedVertex=" + mapped + "; side="
                                    + atlasSide,
                            ManualWarpSafetyReport.unmeasured(
                                    ManualWarpSafetyGate.WORKSPACE,
                                    atlasSide, mapped,
                                    "mapped mesh vertex left the editable side domain"));
                }
                final double displacement = distance(source, mapped);
                if (displacement > maximumDisplacement) {
                    maximumDisplacement = displacement;
                    maximumDisplacementLocation = mapped;
                }
            }
            for (final ManualWarpControl control : controls) {
                if (!strictlyInside(polygon, control.targetPoint())) {
                    throw new ManualWarpException(
                            ManualWarpFailureKind.OUTSIDE_WORKSPACE,
                            "That point left the editable image area or crossed the joined midline. Move it back inside, or choose Disjoined for separated tissue halves.",
                            "target=" + control.targetPoint() + "; side="
                                    + atlasSide,
                            ManualWarpSafetyReport.unmeasured(
                                    ManualWarpSafetyGate.WORKSPACE,
                                    atlasSide, control.targetPoint(),
                                    "control target left the editable side domain"));
                }
                final double displacement = distance(control.sourcePoint(),
                        control.targetPoint());
                if (displacement > maximumDisplacement) {
                    maximumDisplacement = displacement;
                    maximumDisplacementLocation = control.targetPoint();
                }
            }
            for (final Triangle triangle : triangles) {
                final Jacobian2D jacobian = jacobian(triangle);
                final SingularValues values = singularValues(jacobian);
                if (!Double.isFinite(jacobian.determinant())
                        || jacobian.determinant() < MINIMUM_JACOBIAN_DETERMINANT
                        || values.minimum < MINIMUM_SINGULAR_VALUE
                        || values.maximum > MAXIMUM_SINGULAR_VALUE
                        || values.anisotropy > MAXIMUM_ANISOTROPY) {
                    final Point2D location = new Point2D(
                            (vertices.get(triangle.a()).x()
                                    + vertices.get(triangle.b()).x()
                                    + vertices.get(triangle.c()).x()) / 3.0,
                            (vertices.get(triangle.a()).y()
                                    + vertices.get(triangle.b()).y()
                                    + vertices.get(triangle.c()).y()) / 3.0);
                    final ManualWarpSafetyReport report;
                    if (!Double.isFinite(jacobian.determinant())
                            || !Double.isFinite(values.minimum)
                            || !Double.isFinite(values.maximum)
                            || !Double.isFinite(values.anisotropy)) {
                        report = ManualWarpSafetyReport.unmeasured(
                                ManualWarpSafetyGate.FINITE_GEOMETRY,
                                atlasSide, location,
                                "non-finite triangle Jacobian");
                    } else if (jacobian.determinant()
                            < MINIMUM_JACOBIAN_DETERMINANT) {
                        report = ManualWarpSafetyReport.measured(
                                ManualWarpSafetyGate.DETERMINANT,
                                jacobian.determinant(),
                                MINIMUM_JACOBIAN_DETERMINANT,
                                atlasSide, location,
                                "minimum allowed determinant");
                    } else if (values.minimum < MINIMUM_SINGULAR_VALUE) {
                        report = ManualWarpSafetyReport.measured(
                                ManualWarpSafetyGate.MINIMUM_SINGULAR_VALUE,
                                values.minimum, MINIMUM_SINGULAR_VALUE,
                                atlasSide, location,
                                "minimum allowed singular value");
                    } else if (values.maximum > MAXIMUM_SINGULAR_VALUE) {
                        report = ManualWarpSafetyReport.measured(
                                ManualWarpSafetyGate.MAXIMUM_SINGULAR_VALUE,
                                values.maximum, MAXIMUM_SINGULAR_VALUE,
                                atlasSide, location,
                                "maximum allowed singular value");
                    } else {
                        report = ManualWarpSafetyReport.measured(
                                ManualWarpSafetyGate.ANISOTROPY,
                                values.anisotropy, MAXIMUM_ANISOTROPY,
                                atlasSide, location,
                                "maximum allowed anisotropy");
                    }
                    throw new ManualWarpException(
                            jacobian.determinant() < MINIMUM_JACOBIAN_DETERMINANT
                                    ? ManualWarpFailureKind.FOLD_RISK
                                    : ManualWarpFailureKind.EXCESSIVE_STRETCH,
                            "That move would fold or sharply distort the atlas. The dot stayed at its last safe position; try a smaller move or add a nearby point.",
                            "Hemisphere mesh Jacobian/singular-value safety gate failed: det="
                                    + jacobian.determinant() + ", smin="
                                    + values.minimum + ", smax="
                                    + values.maximum + ", anisotropy="
                                    + values.anisotropy + ", side="
                                    + atlasSide + ", triangle=" + triangle
                                    + ", source=[" + vertices.get(triangle.a())
                                    + ", " + vertices.get(triangle.b())
                                    + ", " + vertices.get(triangle.c())
                                    + "]", report);
                }
                final Point2D q0 = displaced(vertices.get(triangle.a()),
                        triangle.a());
                final Point2D q1 = displaced(vertices.get(triangle.b()),
                        triangle.b());
                final Point2D q2 = displaced(vertices.get(triangle.c()),
                        triangle.c());
                if (!triangleContained(q0, q1, q2)) {
                    throw new ManualWarpException(
                            ManualWarpFailureKind.OUTSIDE_WORKSPACE,
                            "That move left the editable image area or crossed the joined midline. Move it back inside, or choose Disjoined for separated tissue halves.",
                            "Target triangle left the preview-domain cage: "
                                    + triangle,
                            ManualWarpSafetyReport.unmeasured(
                                    ManualWarpSafetyGate.WORKSPACE,
                                    atlasSide,
                                    new Point2D((q0.x() + q1.x() + q2.x())
                                            / 3.0,
                                            (q0.y() + q1.y() + q2.y())
                                                    / 3.0),
                                    "target triangle left the preview-domain cage"));
                }
                minimumDeterminant = Math.min(minimumDeterminant,
                        jacobian.determinant());
                minimumSingular = Math.min(minimumSingular, values.minimum);
                maximumSingular = Math.max(maximumSingular, values.maximum);
                maximumAnisotropy = Math.max(maximumAnisotropy,
                        values.anisotropy);
            }
            auditCoverageAndOverlap();
            double maximumRoundTrip = 0;
            Point2D maximumRoundTripLocation = null;
            for (final Triangle triangle : triangles) {
                final Point2D[] samples = new Point2D[]{
                    vertices.get(triangle.a()), vertices.get(triangle.b()),
                    vertices.get(triangle.c()),
                    new Point2D((vertices.get(triangle.a()).x()
                            + vertices.get(triangle.b()).x()
                            + vertices.get(triangle.c()).x()) / 3.0,
                            (vertices.get(triangle.a()).y()
                            + vertices.get(triangle.b()).y()
                            + vertices.get(triangle.c()).y()) / 3.0)};
                for (final Point2D sample : samples) {
                    final Point2D mapped = apply(sample);
                    final Point2D recovered = inverse(mapped);
                    final double roundTrip = distance(sample, recovered);
                    if (roundTrip > maximumRoundTrip) {
                        maximumRoundTrip = roundTrip;
                        maximumRoundTripLocation = sample;
                    }
                }
            }
            if (!allFinite(minimumDeterminant, minimumSingular,
                    maximumSingular, maximumAnisotropy, maximumDisplacement,
                    maximumRoundTrip)
                    || maximumDisplacement
                    > MAXIMUM_SAMPLED_DISPLACEMENT_FRACTION * diagonal
                    || maximumRoundTrip > ROUND_TRIP_TOLERANCE) {
                final ManualWarpSafetyReport report;
                if (!allFinite(minimumDeterminant, minimumSingular,
                        maximumSingular, maximumAnisotropy,
                        maximumDisplacement, maximumRoundTrip)) {
                    report = ManualWarpSafetyReport.unmeasured(
                            ManualWarpSafetyGate.FINITE_GEOMETRY,
                            atlasSide, maximumRoundTripLocation != null
                                    ? maximumRoundTripLocation
                                    : maximumDisplacementLocation,
                            "non-finite complete-field audit metric");
                } else if (maximumDisplacement
                        > MAXIMUM_SAMPLED_DISPLACEMENT_FRACTION * diagonal) {
                    report = ManualWarpSafetyReport.measured(
                            ManualWarpSafetyGate.DISPLACEMENT,
                            maximumDisplacement,
                            MAXIMUM_SAMPLED_DISPLACEMENT_FRACTION * diagonal,
                            atlasSide, maximumDisplacementLocation,
                            "maximum complete-field displacement");
                } else {
                    report = ManualWarpSafetyReport.measured(
                            ManualWarpSafetyGate.ROUND_TRIP,
                            maximumRoundTrip, ROUND_TRIP_TOLERANCE,
                            atlasSide, maximumRoundTripLocation,
                            "maximum inverse round-trip error");
                }
                throw new ManualWarpException(
                        ManualWarpFailureKind.EXCESSIVE_DISPLACEMENT,
                        "That move is too far from its starting dot. Move it a shorter distance or add a nearby point.",
                        "Hemisphere mesh displacement or inverse safety gate failed: maximumDisplacement="
                                + maximumDisplacement + "; roundTrip="
                                + maximumRoundTrip, report);
            }
            return new MeshAudit(minimumDeterminant, minimumSingular,
                    maximumSingular, maximumAnisotropy, maximumDisplacement,
                    maximumRoundTrip);
        }

        private boolean triangleContained(
                final Point2D a,
                final Point2D b,
                final Point2D c) {
            if (!containsInclusive(polygon, a)
                    || !containsInclusive(polygon, b)
                    || !containsInclusive(polygon, c)) {
                return false;
            }
            return !triangleCrossesBoundary(a, b, c, boundarySegments);
        }

        /**
         * Requires exact side-domain coverage and rejects positive-area
         * overlap between non-adjacent target triangles. Vertex sampling is
         * insufficient for a concave reviewed outline because a long edge can
         * leave and re-enter the polygon between samples.
         */
        private void auditCoverageAndOverlap() {
            final double domainArea = polygonArea(polygon);
            double sourceArea = 0;
            double targetArea = 0;
            double largestLocalAreaChange = Double.NEGATIVE_INFINITY;
            Point2D coverageFailureLocation = null;
            final List<TargetTriangle> targetTriangles = new ArrayList<>(
                    triangles.size());
            for (final Triangle triangle : triangles) {
                final Point2D sourceA = vertices.get(triangle.a());
                final Point2D sourceB = vertices.get(triangle.b());
                final Point2D sourceC = vertices.get(triangle.c());
                final double sourceTriangleArea = 0.5 * Math.abs(cross(
                        sourceA, sourceB, sourceC));
                sourceArea += sourceTriangleArea;
                final Point2D targetA = displaced(sourceA, triangle.a());
                final Point2D targetB = displaced(sourceB, triangle.b());
                final Point2D targetC = displaced(sourceC, triangle.c());
                final double targetTriangleArea = 0.5 * Math.abs(cross(
                        targetA, targetB, targetC));
                targetArea += targetTriangleArea;
                final double localAreaChange = Math.abs(
                        targetTriangleArea - sourceTriangleArea);
                if (localAreaChange > largestLocalAreaChange) {
                    largestLocalAreaChange = localAreaChange;
                    coverageFailureLocation = new Point2D(
                            (targetA.x() + targetB.x() + targetC.x()) / 3.0,
                            (targetA.y() + targetB.y() + targetC.y()) / 3.0);
                }
                targetTriangles.add(new TargetTriangle(
                        triangle, targetA, targetB, targetC));
            }
            final double areaTolerance = Math.max(1e-6,
                    domainArea * 1e-8);
            if (Math.abs(sourceArea - domainArea) > areaTolerance
                    || Math.abs(targetArea - domainArea) > areaTolerance) {
                throw new ManualWarpException(
                        ManualWarpFailureKind.FOLD_RISK,
                        "That move would leave a gap or overlap in the atlas mesh. Try a smaller move or add a nearby point.",
                        "Hemisphere mesh does not exactly cover and preserve the reviewed side area",
                        ManualWarpSafetyReport.unmeasured(
                                ManualWarpSafetyGate.CONTROL_TOPOLOGY,
                                atlasSide, coverageFailureLocation,
                                "source or target mesh coverage changed"));
            }
            for (int first = 0; first < targetTriangles.size(); first++) {
                for (int second = first + 1;
                        second < targetTriangles.size(); second++) {
                    final TargetTriangle a = targetTriangles.get(first);
                    final TargetTriangle b = targetTriangles.get(second);
                    if (shareEdge(a.source(), b.source())) {
                        continue;
                    }
                    if (positiveAreaOverlap(a, b)) {
                        throw new ManualWarpException(
                                ManualWarpFailureKind.FOLD_RISK,
                                "That move would overlap distant atlas mesh regions. Try a smaller move or add a nearby point.",
                                "Non-adjacent warped mesh triangles overlap",
                                ManualWarpSafetyReport.unmeasured(
                                        ManualWarpSafetyGate.CONTROL_TOPOLOGY,
                                        atlasSide,
                                        new Point2D((a.a().x() + a.b().x()
                                                + a.c().x() + b.a().x()
                                                + b.b().x() + b.c().x())
                                                / 6.0,
                                                (a.a().y() + a.b().y()
                                                + a.c().y() + b.a().y()
                                                + b.b().y() + b.c().y())
                                                / 6.0),
                                        "non-adjacent target triangles overlap"));
                    }
                }
            }
        }

        private static boolean shareEdge(
                final Triangle first,
                final Triangle second) {
            int shared = 0;
            for (final int vertex : new int[]{
                    first.a(), first.b(), first.c()}) {
                if (vertex == second.a() || vertex == second.b()
                        || vertex == second.c()) {
                    shared++;
                }
            }
            return shared >= 2;
        }

        private static boolean positiveAreaOverlap(
                final TargetTriangle first,
                final TargetTriangle second) {
            if (!boundingBoxesOverlap(first, second)) {
                return false;
            }
            final Point2D[] a = first.points();
            final Point2D[] b = second.points();
            for (int firstEdge = 0; firstEdge < 3; firstEdge++) {
                for (int secondEdge = 0; secondEdge < 3; secondEdge++) {
                    if (properSegmentIntersection(
                            a[firstEdge], a[(firstEdge + 1) % 3],
                            b[secondEdge], b[(secondEdge + 1) % 3])) {
                        return true;
                    }
                }
            }
            for (final Point2D point : a) {
                if (strictlyInsideTriangle(b[0], b[1], b[2], point)) {
                    return true;
                }
            }
            for (final Point2D point : b) {
                if (strictlyInsideTriangle(a[0], a[1], a[2], point)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean boundingBoxesOverlap(
                final TargetTriangle first,
                final TargetTriangle second) {
            return first.maximumX() > second.minimumX()
                    + MESH_CONTAINMENT_EPSILON
                    && second.maximumX() > first.minimumX()
                    + MESH_CONTAINMENT_EPSILON
                    && first.maximumY() > second.minimumY()
                    + MESH_CONTAINMENT_EPSILON
                    && second.maximumY() > first.minimumY()
                    + MESH_CONTAINMENT_EPSILON;
        }

        private record TargetTriangle(
                Triangle source,
                Point2D a,
                Point2D b,
                Point2D c) {

            private Point2D[] points() {
                return new Point2D[]{a, b, c};
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

        private boolean onBoundary(final Point2D point) {
            return boundarySegments.stream().anyMatch(segment ->
                    distancePointSegment(point, segment.a(), segment.b())
                    <= MESH_EPSILON);
        }

        private boolean strictlyContains(final Point2D point) {
            return strictlyInside(polygon, point);
        }

        private Triangle locateSource(final Point2D point) {
            return sourceTriangleIndex.locate(point);
        }

        private Triangle locateTarget(final Point2D point) {
            return targetTriangleIndex.locate(point);
        }

        private Point2D mapBarycentric(
                final Point2D point,
                final Triangle triangle) {
            final Point2D a = vertices.get(triangle.a());
            final Point2D b = vertices.get(triangle.b());
            final Point2D c = vertices.get(triangle.c());
            final double[] bary = barycentric(point, a, b, c);
            return new Point2D(
                    point.x() + bary[0] * displacementX[triangle.a()]
                            + bary[1] * displacementX[triangle.b()]
                            + bary[2] * displacementX[triangle.c()],
                    point.y() + bary[0] * displacementY[triangle.a()]
                            + bary[1] * displacementY[triangle.b()]
                            + bary[2] * displacementY[triangle.c()]);
        }

        private Point2D inverseBarycentric(
                final Point2D point,
                final Triangle triangle) {
            final Point2D a = displaced(vertices.get(triangle.a()),
                    triangle.a());
            final Point2D b = displaced(vertices.get(triangle.b()),
                    triangle.b());
            final Point2D c = displaced(vertices.get(triangle.c()),
                    triangle.c());
            final double[] bary = barycentric(point, a, b, c);
            return new Point2D(
                    bary[0] * vertices.get(triangle.a()).x()
                            + bary[1] * vertices.get(triangle.b()).x()
                            + bary[2] * vertices.get(triangle.c()).x(),
                    bary[0] * vertices.get(triangle.a()).y()
                            + bary[1] * vertices.get(triangle.b()).y()
                            + bary[2] * vertices.get(triangle.c()).y());
        }

        private Point2D displaced(final Point2D point, final int index) {
            return new Point2D(point.x() + displacementX[index],
                    point.y() + displacementY[index]);
        }

        private void updateHash(final MessageDigest digest) {
            updateString(digest, atlasSide.name());
            updateString(digest, imageSide.name());
            updateInt(digest, vertices.size());
            for (int index = 0; index < vertices.size(); index++) {
                updatePoint(digest, vertices.get(index));
                updateDouble(digest, displacementX[index]);
                updateDouble(digest, displacementY[index]);
            }
            updateInt(digest, triangles.size());
            for (final Triangle triangle : triangles) {
                updateInt(digest, triangle.a());
                updateInt(digest, triangle.b());
                updateInt(digest, triangle.c());
            }
            for (final ManualWarpControl control : controls) {
                updateString(digest, control.id());
                updateString(digest, control.groupId());
                updateString(digest, control.structureAcronym());
                updateString(digest, control.origin().name());
            }
        }

        private static int findExactVertex(
                final List<Point2D> points,
                final Point2D target) {
            for (int index = 0; index < points.size(); index++) {
                if (distance(points.get(index), target) <= MESH_EPSILON) {
                    return index;
                }
            }
            return -1;
        }
    }

    private record Domain(
            List<Point2D> polygon,
            List<Segment> segments,
            MidlineSegment midline) {

        private static Domain preview(
                final int width,
                final int height,
                final MidlineSegment midline,
                final ImageSide imageSide) {
            final double direction = imageSide == ImageSide.IMAGE_LEFT ? 1 : -1;
            final double padding = previewPadding(width, height);
            final List<Point2D> corners = List.of(
                    new Point2D(-padding, -padding),
                    new Point2D(width - 1.0 + padding, -padding),
                    new Point2D(width - 1.0 + padding,
                            height - 1.0 + padding),
                    new Point2D(-padding, height - 1.0 + padding));
            final List<Point2D> polygon = clipToMidlineHalfPlane(
                    corners, midline, direction);
            if (polygon.size() < 3) {
                throw new IllegalArgumentException(
                        "The placed atlas midline leaves no editable preview domain on this side");
            }
            final List<Segment> segments = new ArrayList<>();
            for (int index = 0; index + 1 < polygon.size(); index++) {
                segments.add(new Segment(polygon.get(index),
                        polygon.get(index + 1)));
            }
            segments.add(new Segment(polygon.get(polygon.size() - 1),
                    polygon.get(0)));
            return new Domain(List.copyOf(polygon), List.copyOf(segments),
                    midline);
        }

        /** Clips the padded preview rectangle by the placed anatomical seam. */
        private static List<Point2D> clipToMidlineHalfPlane(
                final List<Point2D> rectangle,
                final MidlineSegment midline,
                final double direction) {
            final List<Point2D> clipped = new ArrayList<>();
            Point2D previous = rectangle.get(rectangle.size() - 1);
            double previousDistance = direction
                    * midline.signedDistance(previous);
            boolean previousInside = previousDistance
                    >= -MESH_CONTAINMENT_EPSILON;
            for (final Point2D current : rectangle) {
                final double currentDistance = direction
                        * midline.signedDistance(current);
                final boolean currentInside = currentDistance
                        >= -MESH_CONTAINMENT_EPSILON;
                if (currentInside != previousInside) {
                    final double fraction = previousDistance
                            / (previousDistance - currentDistance);
                    clipped.add(new Point2D(
                            previous.x() + fraction
                                    * (current.x() - previous.x()),
                            previous.y() + fraction
                                    * (current.y() - previous.y())));
                }
                if (currentInside) {
                    clipped.add(current);
                }
                previous = current;
                previousDistance = currentDistance;
                previousInside = currentInside;
            }
            final List<Point2D> unique = new ArrayList<>();
            for (final Point2D point : clipped) {
                if (unique.isEmpty()
                        || distance(unique.get(unique.size() - 1), point)
                                > MESH_EPSILON) {
                    unique.add(point);
                }
            }
            if (unique.size() > 1
                    && distance(unique.get(0),
                            unique.get(unique.size() - 1)) <= MESH_EPSILON) {
                unique.remove(unique.size() - 1);
            }
            return List.copyOf(unique);
        }

        /** Independent side cage with no shared midline constraint. */
        private static Domain disjoined(
                final int width,
                final int height,
                final MidlineSegment midline) {
            final double padding = previewPadding(width, height);
            final List<Point2D> polygon = List.of(
                    new Point2D(-padding, -padding),
                    new Point2D(width - 1.0 + padding, -padding),
                    new Point2D(width - 1.0 + padding,
                            height - 1.0 + padding),
                    new Point2D(-padding, height - 1.0 + padding));
            final List<Segment> segments = new ArrayList<>();
            for (int index = 0; index < polygon.size(); index++) {
                segments.add(new Segment(polygon.get(index),
                        polygon.get((index + 1) % polygon.size())));
            }
            return new Domain(polygon, List.copyOf(segments), midline);
        }

        private static double previewPadding(
                final int width,
                final int height) {
            return Math.max(8.0, Math.min(32.0,
                    Math.hypot(width, height) * 0.04));
        }

        private static Domain exact(
                final List<Point2D> sideBoundary,
                final MidlineSegment midline) {
            final List<Point2D> polygon = List.copyOf(
                    Objects.requireNonNull(sideBoundary,
                            "tissueSideBoundary"));
            if (polygon.size() < 3) {
                throw new IllegalArgumentException(
                        "Reviewed tissue side boundary must have at least three vertices");
            }
            final List<Segment> segments = new ArrayList<>();
            for (int index = 0; index < polygon.size(); index++) {
                segments.add(new Segment(polygon.get(index),
                        polygon.get((index + 1) % polygon.size())));
            }
            return new Domain(polygon, List.copyOf(segments),
                    midline);
        }

    }

    /** Immutable target-independent mesh and harmonic-system factorization. */
    private record MeshTopology(
            List<Point2D> vertices,
            List<Triangle> triangles,
            List<Edge> edges,
            boolean[] fixed,
            HarmonicSystem harmonicSystem) {

        private MeshTopology {
            vertices = List.copyOf(vertices);
            triangles = List.copyOf(triangles);
            edges = List.copyOf(edges);
            fixed = fixed.clone();
            harmonicSystem = Objects.requireNonNull(
                    harmonicSystem, "harmonicSystem");
        }

        @Override
        public boolean[] fixed() {
            return fixed.clone();
        }
    }

    private static final class VertexStore {
        private final List<Point2D> points = new ArrayList<>();
        private final Map<PointKey, Integer> byPoint = new HashMap<>();

        private int add(final Point2D point) {
            final PointKey key = PointKey.of(point);
            final Integer existing = byPoint.get(key);
            if (existing != null) {
                return existing;
            }
            final int index = points.size();
            points.add(point);
            byPoint.put(key, index);
            return index;
        }

    }

    private record PointKey(long x, long y) {
        private static PointKey of(final Point2D point) {
            return new PointKey(Double.doubleToLongBits(point.x()),
                    Double.doubleToLongBits(point.y()));
        }
    }

    /**
     * Immutable uniform-grid lookup for piecewise-affine source or target
     * triangles. Export may invert millions of source-pixel centres, so a
     * linear scan of every hidden triangle is not an acceptable query path.
     */
    private static final class TriangleSpatialIndex {
        private static final int MAXIMUM_GRID_AXIS = 128;

        private final List<Triangle> triangles;
        private final List<TriangleGeometry> geometry;
        private final int columns;
        private final int rows;
        private final double minimumX;
        private final double minimumY;
        private final double maximumX;
        private final double maximumY;
        private final double cellWidth;
        private final double cellHeight;
        private final int[][] candidates;

        private TriangleSpatialIndex(
                final List<Triangle> triangles,
                final List<TriangleGeometry> geometry) {
            this.triangles = List.copyOf(triangles);
            this.geometry = List.copyOf(geometry);
            if (this.triangles.isEmpty()
                    || this.triangles.size() != this.geometry.size()) {
                throw new IllegalArgumentException(
                        "Triangle index requires matching non-empty geometry");
            }
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (final TriangleGeometry triangle : this.geometry) {
                minX = Math.min(minX, triangle.minimumX());
                minY = Math.min(minY, triangle.minimumY());
                maxX = Math.max(maxX, triangle.maximumX());
                maxY = Math.max(maxY, triangle.maximumY());
            }
            this.minimumX = minX;
            this.minimumY = minY;
            this.maximumX = maxX;
            this.maximumY = maxY;
            final double spanX = Math.max(MESH_EPSILON, maxX - minX);
            final double spanY = Math.max(MESH_EPSILON, maxY - minY);
            final double aspect = spanX / spanY;
            this.columns = Math.max(1, Math.min(MAXIMUM_GRID_AXIS,
                    (int) Math.ceil(Math.sqrt(
                            this.triangles.size() * aspect))));
            this.rows = Math.max(1, Math.min(MAXIMUM_GRID_AXIS,
                    (int) Math.ceil((double) this.triangles.size()
                            / columns)));
            this.cellWidth = spanX / columns;
            this.cellHeight = spanY / rows;
            final List<List<Integer>> mutableCells = new ArrayList<>(
                    Math.multiplyExact(columns, rows));
            for (int cell = 0; cell < columns * rows; cell++) {
                mutableCells.add(new ArrayList<>());
            }
            for (int index = 0; index < this.geometry.size(); index++) {
                final TriangleGeometry triangle = this.geometry.get(index);
                final int firstColumn = column(
                        triangle.minimumX() - MESH_CONTAINMENT_EPSILON);
                final int lastColumn = column(
                        triangle.maximumX() + MESH_CONTAINMENT_EPSILON);
                final int firstRow = row(
                        triangle.minimumY() - MESH_CONTAINMENT_EPSILON);
                final int lastRow = row(
                        triangle.maximumY() + MESH_CONTAINMENT_EPSILON);
                for (int row = firstRow; row <= lastRow; row++) {
                    for (int column = firstColumn;
                            column <= lastColumn; column++) {
                        mutableCells.get(row * columns + column).add(index);
                    }
                }
            }
            this.candidates = new int[mutableCells.size()][];
            for (int cell = 0; cell < mutableCells.size(); cell++) {
                this.candidates[cell] = mutableCells.get(cell).stream()
                        .mapToInt(Integer::intValue).toArray();
            }
        }

        private static TriangleSpatialIndex source(
                final List<Point2D> vertices,
                final List<Triangle> triangles) {
            return new TriangleSpatialIndex(triangles,
                    geometry(vertices, triangles, null, null));
        }

        private static TriangleSpatialIndex target(
                final List<Point2D> vertices,
                final List<Triangle> triangles,
                final double[] displacementX,
                final double[] displacementY) {
            return new TriangleSpatialIndex(triangles,
                    geometry(vertices, triangles,
                            Objects.requireNonNull(
                                    displacementX, "displacementX"),
                            Objects.requireNonNull(
                                    displacementY, "displacementY")));
        }

        private Triangle locate(final Point2D point) {
            if (point.x() < minimumX - MESH_CONTAINMENT_EPSILON
                    || point.x() > maximumX + MESH_CONTAINMENT_EPSILON
                    || point.y() < minimumY - MESH_CONTAINMENT_EPSILON
                    || point.y() > maximumY + MESH_CONTAINMENT_EPSILON) {
                return null;
            }
            final int[] nearby = candidates[
                    row(point.y()) * columns + column(point.x())];
            for (final int index : nearby) {
                final TriangleGeometry triangle = geometry.get(index);
                if (containsTriangle(triangle.a(), triangle.b(),
                        triangle.c(), point)) {
                    return triangles.get(index);
                }
            }
            return null;
        }

        private int column(final double x) {
            return Math.max(0, Math.min(columns - 1,
                    (int) Math.floor((x - minimumX) / cellWidth)));
        }

        private int row(final double y) {
            return Math.max(0, Math.min(rows - 1,
                    (int) Math.floor((y - minimumY) / cellHeight)));
        }

        private static List<TriangleGeometry> geometry(
                final List<Point2D> vertices,
                final List<Triangle> triangles,
                final double[] displacementX,
                final double[] displacementY) {
            final List<TriangleGeometry> result = new ArrayList<>(
                    triangles.size());
            for (final Triangle triangle : triangles) {
                result.add(new TriangleGeometry(
                        displaced(vertices.get(triangle.a()), triangle.a(),
                                displacementX, displacementY),
                        displaced(vertices.get(triangle.b()), triangle.b(),
                                displacementX, displacementY),
                        displaced(vertices.get(triangle.c()), triangle.c(),
                                displacementX, displacementY)));
            }
            return List.copyOf(result);
        }

        private static Point2D displaced(
                final Point2D point,
                final int index,
                final double[] displacementX,
                final double[] displacementY) {
            return displacementX == null ? point : new Point2D(
                    point.x() + displacementX[index],
                    point.y() + displacementY[index]);
        }
    }

    private record TriangleGeometry(Point2D a, Point2D b, Point2D c) {
        private TriangleGeometry {
            Objects.requireNonNull(a, "a");
            Objects.requireNonNull(b, "b");
            Objects.requireNonNull(c, "c");
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

    private record Triangle(int a, int b, int c) {
    }

    private record Edge(int a, int b) {
        private Edge {
            if (a == b) {
                throw new IllegalArgumentException("Mesh edge endpoints must differ");
            }
        }
    }

    private record Segment(Point2D a, Point2D b) {
    }

    private record MeshAudit(
            double minimumDeterminant,
            double minimumSingularValue,
            double maximumSingularValue,
            double maximumAnisotropy,
            double maximumDisplacement,
            double maximumRoundTripError) {
    }

    private static void addInteriorGridPoints(
            final VertexStore store,
            final List<Point2D> polygon,
            final List<Segment> boundarySegments,
            final double diagonal) {
        double minimumX = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        double area = 0;
        for (int index = 0; index < polygon.size(); index++) {
            final Point2D point = polygon.get(index);
            final Point2D next = polygon.get((index + 1) % polygon.size());
            minimumX = Math.min(minimumX, point.x());
            maximumX = Math.max(maximumX, point.x());
            minimumY = Math.min(minimumY, point.y());
            maximumY = Math.max(maximumY, point.y());
            area += point.x() * next.y() - next.x() * point.y();
        }
        area = Math.abs(area) * 0.5;
        // Approximately 700 triangles per active side before clipping keeps
        // the common two-sided mesh in the requested 1,000–3,000 range while
        // bounding memory and solve time for large reviewed outlines.
        final double spacing = Math.max(2.0,
                Math.sqrt(Math.max(1.0, area) / 350.0));
        final double startX = minimumX + 0.5 * spacing;
        final double startY = minimumY + 0.5 * spacing;
        for (double y = startY; y < maximumY; y += spacing) {
            for (double x = startX; x < maximumX; x += spacing) {
                final Point2D candidate = new Point2D(x, y);
                if (!strictlyInside(polygon, candidate)) {
                    continue;
                }
                final double boundaryDistance = nearestBoundaryDistance(
                        candidate, boundarySegments);
                if (boundaryDistance < 0.35 * spacing) {
                    continue;
                }
                boolean tooClose = false;
                for (final Point2D existing : store.points) {
                    if (distance(candidate, existing) < 0.45 * spacing) {
                        tooClose = true;
                        break;
                    }
                }
                if (tooClose) {
                    continue;
                }
                store.add(candidate);
            }
        }
    }

    private static List<Triangle> triangulateReduced(
            final List<Integer> boundaryIndices,
            final List<Point2D> points,
            final List<Point2D> polygon,
            final List<Segment> boundarySegments,
            final List<ManualWarpControl> controls) {
        if (points.size() < 3) {
            throw new IllegalArgumentException(
                    "Reduced mesh requires at least three vertices");
        }
        final List<Triangle> result = earClipBoundary(
                boundaryIndices, points);
        final Set<Integer> boundaryVertices = new HashSet<>(boundaryIndices);
        for (int inserted = 0; inserted < points.size(); inserted++) {
            final Point2D point = points.get(inserted);
            final boolean onReviewedBoundary = boundarySegments.stream()
                    .anyMatch(segment -> distancePointSegment(point,
                            segment.a(), segment.b()) <= MESH_EPSILON);
            if (!boundaryVertices.contains(inserted)
                    && !onReviewedBoundary) {
                insertInteriorVertex(result, points, inserted);
            }
        }
        legalizeInteriorEdges(result, points, boundaryIndices);
        final boolean controlsConnected = containsAllControls(
                result, points, controls);
        final boolean boundaryConnected = containsAllBoundaryEdges(
                result, boundaryIndices, points);
        final boolean crossesBoundary = result.stream().anyMatch(triangle ->
                        triangleCrossesBoundary(
                                points.get(triangle.a()),
                                points.get(triangle.b()),
                                points.get(triangle.c()), boundarySegments));
        if (result.isEmpty() || !controlsConnected
                || !boundaryConnected || crossesBoundary) {
            throw new IllegalArgumentException(
                    "Reduced constrained triangulation failed safely: controls="
                            + controlsConnected + ", boundary="
                            + boundaryConnected + ", crossing="
                            + crossesBoundary + ", triangles="
                            + result.size() + ", missingBoundary="
                            + missingBoundaryEdge(result, boundaryIndices,
                                    points));
        }
        return List.copyOf(result);
    }

    private static List<Triangle> earClipBoundary(
            final List<Integer> boundaryIndices,
            final List<Point2D> points) {
        final List<Integer> remaining = new ArrayList<>(boundaryIndices);
        final List<Triangle> triangles = new ArrayList<>();
        double signedDoubleArea = 0;
        for (int index = 0; index < remaining.size(); index++) {
            final Point2D point = points.get(remaining.get(index));
            final Point2D next = points.get(remaining.get(
                    (index + 1) % remaining.size()));
            signedDoubleArea += point.x() * next.y()
                    - next.x() * point.y();
        }
        final double orientation = Math.signum(signedDoubleArea);
        if (orientation == 0) {
            throw new IllegalArgumentException(
                    "Reviewed side boundary has zero area");
        }
        int guard = 0;
        while (remaining.size() > 3) {
            boolean clipped = false;
            for (int index = 0; index < remaining.size(); index++) {
                final int previous = remaining.get(
                        (index - 1 + remaining.size()) % remaining.size());
                final int current = remaining.get(index);
                final int next = remaining.get((index + 1)
                        % remaining.size());
                final Point2D a = points.get(previous);
                final Point2D b = points.get(current);
                final Point2D c = points.get(next);
                if (orientation * cross(a, b, c)
                        <= MESH_MIN_TRIANGLE_AREA) {
                    continue;
                }
                boolean containsVertex = false;
                for (final int candidate : remaining) {
                    if (candidate == previous || candidate == current
                            || candidate == next) {
                        continue;
                    }
                    if (strictlyInsideTriangle(a, b, c,
                            points.get(candidate))) {
                        containsVertex = true;
                        break;
                    }
                }
                if (containsVertex) {
                    continue;
                }
                triangles.add(orientedByGeometry(
                        previous, current, next, points));
                remaining.remove(index);
                clipped = true;
                break;
            }
            if (!clipped || ++guard > boundaryIndices.size()
                    * boundaryIndices.size()) {
                throw new IllegalArgumentException(
                        "Reduced constrained triangulation could not clip the reviewed concave boundary");
            }
        }
        triangles.add(orientedByGeometry(
                remaining.get(0), remaining.get(1), remaining.get(2),
                points));
        return triangles;
    }

    private static void insertInteriorVertex(
            final List<Triangle> triangles,
            final List<Point2D> points,
            final int inserted) {
        final Point2D point = points.get(inserted);
        Edge containingEdge = null;
        for (final Triangle triangle : triangles) {
            for (final Edge edge : List.of(
                    new Edge(triangle.a(), triangle.b()),
                    new Edge(triangle.b(), triangle.c()),
                    new Edge(triangle.c(), triangle.a()))) {
                if (distancePointSegment(point, points.get(edge.a()),
                        points.get(edge.b())) <= MESH_EPSILON) {
                    containingEdge = edge;
                    break;
                }
            }
            if (containingEdge != null) {
                break;
            }
        }
        if (containingEdge != null) {
            final Edge split = containingEdge;
            final List<Triangle> touching = triangles.stream()
                    .filter(triangle -> triangleHasEdge(triangle, split))
                    .toList();
            if (touching.isEmpty() || touching.size() > 2) {
                throw new IllegalArgumentException(
                        "Interior mesh point touches an invalid edge fan");
            }
            triangles.removeAll(touching);
            for (final Triangle triangle : touching) {
                final int opposite = oppositeVertex(triangle, split);
                addNonDegenerate(triangles, split.a(), inserted, opposite,
                        points);
                addNonDegenerate(triangles, inserted, split.b(), opposite,
                        points);
            }
            return;
        }
        for (int index = 0; index < triangles.size(); index++) {
            final Triangle triangle = triangles.get(index);
            if (!containsTriangle(points.get(triangle.a()),
                    points.get(triangle.b()), points.get(triangle.c()), point)) {
                continue;
            }
            triangles.remove(index);
            addNonDegenerate(triangles, triangle.a(), triangle.b(), inserted,
                    points);
            addNonDegenerate(triangles, triangle.b(), triangle.c(), inserted,
                    points);
            addNonDegenerate(triangles, triangle.c(), triangle.a(), inserted,
                    points);
            return;
        }
        throw new IllegalArgumentException(
                "Interior mesh point is outside the constrained triangulation");
    }

    private static boolean triangleHasEdge(
            final Triangle triangle,
            final Edge edge) {
        return (triangle.a() == edge.a() || triangle.b() == edge.a()
                || triangle.c() == edge.a())
                && (triangle.a() == edge.b() || triangle.b() == edge.b()
                || triangle.c() == edge.b());
    }

    private static int oppositeVertex(
            final Triangle triangle,
            final Edge edge) {
        for (final int vertex : new int[]{
                triangle.a(), triangle.b(), triangle.c()}) {
            if (vertex != edge.a() && vertex != edge.b()) {
                return vertex;
            }
        }
        throw new IllegalArgumentException("Mesh edge has no opposite vertex");
    }

    private static void addNonDegenerate(
            final List<Triangle> triangles,
            final int a,
            final int b,
            final int c,
            final List<Point2D> points) {
        if (Math.abs(cross(points.get(a), points.get(b), points.get(c)))
                <= MESH_MIN_TRIANGLE_AREA) {
            throw new IllegalArgumentException(
                    "Interior mesh insertion created a degenerate triangle");
        }
        triangles.add(orientedByGeometry(a, b, c, points));
    }

    private static Triangle orientedByGeometry(
            final int a,
            final int b,
            final int c,
            final List<Point2D> points) {
        return cross(points.get(a), points.get(b), points.get(c)) > 0
                ? new Triangle(a, b, c) : new Triangle(a, c, b);
    }

    /** Deterministic constrained-Delaunay flips remove insertion-order slivers. */
    private static void legalizeInteriorEdges(
            final List<Triangle> triangles,
            final List<Point2D> points,
            final List<Integer> boundaryIndices) {
        final Set<Long> constrained = new HashSet<>();
        for (int index = 0; index < boundaryIndices.size(); index++) {
            constrained.add(edgeKey(boundaryIndices.get(index),
                    boundaryIndices.get((index + 1)
                            % boundaryIndices.size())));
        }
        final int maximumFlips = Math.max(1000, triangles.size() * 20);
        for (int flip = 0; flip < maximumFlips; flip++) {
            final Map<Long, List<Integer>> incident = new TreeMap<>();
            for (int triangleIndex = 0;
                    triangleIndex < triangles.size(); triangleIndex++) {
                final Triangle triangle = triangles.get(triangleIndex);
                for (final Edge edge : List.of(
                        new Edge(triangle.a(), triangle.b()),
                        new Edge(triangle.b(), triangle.c()),
                        new Edge(triangle.c(), triangle.a()))) {
                    incident.computeIfAbsent(edgeKey(edge.a(), edge.b()),
                            ignored -> new ArrayList<>()).add(triangleIndex);
                }
            }
            boolean changed = false;
            for (final Map.Entry<Long, List<Integer>> entry
                    : incident.entrySet()) {
                if (constrained.contains(entry.getKey())
                        || entry.getValue().size() != 2) {
                    continue;
                }
                final int firstIndex = entry.getValue().get(0);
                final int secondIndex = entry.getValue().get(1);
                final Triangle first = triangles.get(firstIndex);
                final Triangle second = triangles.get(secondIndex);
                final Edge shared = sharedEdge(first, second);
                final int firstOpposite = oppositeVertex(first, shared);
                final int secondOpposite = oppositeVertex(second, shared);
                final Point2D u = points.get(shared.a());
                final Point2D v = points.get(shared.b());
                final Point2D a = points.get(firstOpposite);
                final Point2D b = points.get(secondOpposite);
                if (cross(u, v, a) * cross(u, v, b)
                        >= -MESH_MIN_TRIANGLE_AREA
                        || cross(a, b, u) * cross(a, b, v)
                        >= -MESH_MIN_TRIANGLE_AREA) {
                    continue;
                }
                if (!insideCircumcircle(u, v, a, b)) {
                    continue;
                }
                if (Math.abs(cross(a, b, u))
                        <= MESH_MIN_TRIANGLE_AREA
                        || Math.abs(cross(b, a, v))
                        <= MESH_MIN_TRIANGLE_AREA) {
                    continue;
                }
                triangles.set(firstIndex, orientedByGeometry(
                        firstOpposite, secondOpposite, shared.a(), points));
                triangles.set(secondIndex, orientedByGeometry(
                        secondOpposite, firstOpposite, shared.b(), points));
                changed = true;
                break;
            }
            if (!changed) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "Reduced constrained triangulation did not legalize safely");
    }

    private static Edge sharedEdge(
            final Triangle first,
            final Triangle second) {
        final List<Integer> shared = new ArrayList<>(2);
        for (final int vertex : new int[]{
                first.a(), first.b(), first.c()}) {
            if (vertex == second.a() || vertex == second.b()
                    || vertex == second.c()) {
                shared.add(vertex);
            }
        }
        if (shared.size() != 2) {
            throw new IllegalArgumentException(
                    "Adjacent mesh triangles must share exactly one edge");
        }
        return new Edge(Math.min(shared.get(0), shared.get(1)),
                Math.max(shared.get(0), shared.get(1)));
    }

    private static boolean containsAllControls(
            final List<Triangle> triangles,
            final List<Point2D> points,
            final List<ManualWarpControl> controls) {
        for (final ManualWarpControl control : controls) {
            final int vertex = MeshSide.findExactVertex(points,
                    control.sourcePoint());
            if (vertex < 0 || triangles.stream().noneMatch(triangle ->
                    triangle.a() == vertex || triangle.b() == vertex
                    || triangle.c() == vertex)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsAllBoundaryEdges(
            final List<Triangle> triangles,
            final List<Integer> boundaryIndices,
            final List<Point2D> points) {
        final Set<Long> edges = new HashSet<>();
        for (final Triangle triangle : triangles) {
            edges.add(edgeKey(triangle.a(), triangle.b()));
            edges.add(edgeKey(triangle.b(), triangle.c()));
            edges.add(edgeKey(triangle.c(), triangle.a()));
        }
        for (int index = 0; index < boundaryIndices.size(); index++) {
            final int first = boundaryIndices.get(index);
            final int second = boundaryIndices.get(
                    (index + 1) % boundaryIndices.size());
            if (!edges.contains(edgeKey(first, second))) {
                return false;
            }
        }
        return true;
    }

    private static String missingBoundaryEdge(
            final List<Triangle> triangles,
            final List<Integer> boundaryIndices,
            final List<Point2D> points) {
        final Set<Long> edges = new HashSet<>();
        for (final Triangle triangle : triangles) {
            edges.add(edgeKey(triangle.a(), triangle.b()));
            edges.add(edgeKey(triangle.b(), triangle.c()));
            edges.add(edgeKey(triangle.c(), triangle.a()));
        }
        for (int index = 0; index < boundaryIndices.size(); index++) {
            final int first = boundaryIndices.get(index);
            final int second = boundaryIndices.get(
                    (index + 1) % boundaryIndices.size());
            if (!edges.contains(edgeKey(first, second))) {
                return points.get(first) + " -> " + points.get(second);
            }
        }
        return "none";
    }

    private static boolean triangleCrossesBoundary(
            final Point2D a,
            final Point2D b,
            final Point2D c,
            final List<Segment> boundarySegments) {
        return segmentCrossesBoundary(a, b, boundarySegments)
                || segmentCrossesBoundary(b, c, boundarySegments)
                || segmentCrossesBoundary(c, a, boundarySegments);
    }

    private static boolean segmentCrossesBoundary(
            final Point2D first,
            final Point2D second,
            final List<Segment> boundarySegments) {
        for (final Segment boundary : boundarySegments) {
            if (distancePointSegment(first, boundary.a(), boundary.b())
                    <= MESH_CONTAINMENT_EPSILON
                    || distancePointSegment(second, boundary.a(), boundary.b())
                    <= MESH_CONTAINMENT_EPSILON) {
                continue;
            }
            if (properSegmentIntersection(first, second, boundary.a(),
                    boundary.b())) {
                return true;
            }
        }
        return false;
    }

    private static boolean properSegmentIntersection(
            final Point2D a,
            final Point2D b,
            final Point2D c,
            final Point2D d) {
        final double abC = cross(a, b, c);
        final double abD = cross(a, b, d);
        final double cdA = cross(c, d, a);
        final double cdB = cross(c, d, b);
        return ((abC > MESH_CONTAINMENT_EPSILON
                && abD < -MESH_CONTAINMENT_EPSILON)
                || (abC < -MESH_CONTAINMENT_EPSILON
                && abD > MESH_CONTAINMENT_EPSILON))
                && ((cdA > MESH_CONTAINMENT_EPSILON
                && cdB < -MESH_CONTAINMENT_EPSILON)
                || (cdA < -MESH_CONTAINMENT_EPSILON
                && cdB > MESH_CONTAINMENT_EPSILON));
    }

    private static boolean insideCircumcircle(
            final Point2D a,
            final Point2D b,
            final Point2D c,
            final Point2D point) {
        final double orientation = cross(a, b, c);
        if (Math.abs(orientation) <= MESH_MIN_TRIANGLE_AREA) {
            return false;
        }
        final double ax = a.x() - point.x();
        final double ay = a.y() - point.y();
        final double bx = b.x() - point.x();
        final double by = b.y() - point.y();
        final double cx = c.x() - point.x();
        final double cy = c.y() - point.y();
        final double determinant =
                (ax * ax + ay * ay) * (bx * cy - by * cx)
                - (bx * bx + by * by) * (ax * cy - ay * cx)
                + (cx * cx + cy * cy) * (ax * by - ay * bx);
        return orientation > 0
                ? determinant > MESH_CONTAINMENT_EPSILON
                : determinant < -MESH_CONTAINMENT_EPSILON;
    }

    private static List<Edge> uniqueEdges(final List<Triangle> triangles) {
        final Set<Long> seen = new HashSet<>();
        final List<Edge> result = new ArrayList<>();
        for (final Triangle triangle : triangles) {
            addEdge(result, seen, triangle.a(), triangle.b());
            addEdge(result, seen, triangle.b(), triangle.c());
            addEdge(result, seen, triangle.c(), triangle.a());
        }
        return List.copyOf(result);
    }

    private static long edgeKey(final int first, final int second) {
        final int a = Math.min(first, second);
        final int b = Math.max(first, second);
        return (((long) a) << 32) ^ (b & 0xffffffffL);
    }

    private static void addEdge(
            final List<Edge> edges,
            final Set<Long> seen,
            final int first,
            final int second) {
        final int a = Math.min(first, second);
        final int b = Math.max(first, second);
        final long key = edgeKey(first, second);
        if (seen.add(key)) {
            edges.add(new Edge(a, b));
        }
    }

    private static double[][] solveHarmonic(
            final HarmonicSystem system,
            final double[] prescribedX,
            final double[] prescribedY) {
        final int count = system.vertexCount();
        final int[] freeIndex = system.freeIndex();
        final List<List<Neighbor>> adjacency = system.adjacency();
        final double[] diagonal = system.diagonal();
        final int freeCount = diagonal.length;
        final double[] x = prescribedX.clone();
        final double[] y = prescribedY.clone();
        if (freeCount == 0) {
            return new double[][]{x, y};
        }
        final double[] rhsX = new double[freeCount];
        final double[] rhsY = new double[freeCount];
        for (int vertex = 0; vertex < count; vertex++) {
            final int free = freeIndex[vertex];
            if (free < 0) {
                continue;
            }
            for (final Neighbor neighbor : adjacency.get(vertex)) {
                if (freeIndex[neighbor.vertex()] < 0) {
                    rhsX[free] += neighbor.weight()
                            * prescribedX[neighbor.vertex()];
                    rhsY[free] += neighbor.weight()
                            * prescribedY[neighbor.vertex()];
                }
            }
        }
        final double[] solvedX = conjugateGradient(
                freeIndex, adjacency, diagonal, rhsX);
        final double[] solvedY = conjugateGradient(
                freeIndex, adjacency, diagonal, rhsY);
        for (int vertex = 0; vertex < count; vertex++) {
            if (freeIndex[vertex] >= 0) {
                x[vertex] = solvedX[freeIndex[vertex]];
                y[vertex] = solvedY[freeIndex[vertex]];
            }
        }
        return new double[][]{x, y};
    }

    private static double[] conjugateGradient(
            final int[] freeIndex,
            final List<List<Neighbor>> adjacency,
            final double[] diagonal,
            final double[] rhs) {
        final int count = rhs.length;
        final double[] result = new double[count];
        final double[] residual = rhs.clone();
        final double[] preconditioned = new double[count];
        final double[] direction = new double[count];
        for (int index = 0; index < count; index++) {
            preconditioned[index] = residual[index] / diagonal[index];
            direction[index] = preconditioned[index];
        }
        double rho = dot(residual, preconditioned);
        final double tolerance = 1e-11 * (1 + Math.sqrt(dot(rhs, rhs)));
        if (Math.sqrt(dot(residual, residual)) <= tolerance) {
            return result;
        }
        final int maximumIterations = Math.max(2000, count * 20);
        for (int iteration = 0; iteration < maximumIterations; iteration++) {
            final double[] product = new double[count];
            for (int vertex = 0; vertex < freeIndex.length; vertex++) {
                final int row = freeIndex[vertex];
                if (row < 0) {
                    continue;
                }
                product[row] += diagonal[row] * direction[row];
                for (final Neighbor neighbor : adjacency.get(vertex)) {
                    final int column = freeIndex[neighbor.vertex()];
                    if (column >= 0) {
                        product[row] -= neighbor.weight() * direction[column];
                    }
                }
            }
            final double denominator = dot(direction, product);
            if (!(denominator > 0) || !Double.isFinite(denominator)) {
                throw new IllegalArgumentException(
                        "Harmonic mesh solver became non-positive definite");
            }
            final double alpha = rho / denominator;
            for (int index = 0; index < count; index++) {
                result[index] += alpha * direction[index];
                residual[index] -= alpha * product[index];
            }
            if (Math.sqrt(dot(residual, residual)) <= tolerance) {
                return result;
            }
            for (int index = 0; index < count; index++) {
                preconditioned[index] = residual[index] / diagonal[index];
            }
            final double nextRho = dot(residual, preconditioned);
            final double beta = nextRho / rho;
            for (int index = 0; index < count; index++) {
                direction[index] = preconditioned[index]
                        + beta * direction[index];
            }
            rho = nextRho;
        }
        throw new IllegalArgumentException(
                "Harmonic mesh solver did not converge safely");
    }

    private record Neighbor(int vertex, double weight) {
    }

    /**
     * Cached graph, free-variable numbering, and Jacobi factorization for
     * repeated target drags with unchanged outline and control sources.
     */
    private record HarmonicSystem(
            int vertexCount,
            List<List<Neighbor>> adjacency,
            int[] freeIndex,
            double[] diagonal) {

        private HarmonicSystem {
            adjacency = adjacency.stream().map(List::copyOf).toList();
            freeIndex = freeIndex.clone();
            diagonal = diagonal.clone();
        }

        @Override
        public int[] freeIndex() {
            return freeIndex.clone();
        }

        @Override
        public double[] diagonal() {
            return diagonal.clone();
        }

        private static HarmonicSystem create(
                final List<Point2D> vertices,
                final List<Edge> edges,
                final boolean[] fixed) {
            final int count = vertices.size();
            final List<List<Neighbor>> adjacency = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                adjacency.add(new ArrayList<>());
            }
            for (final Edge edge : edges) {
                final double weight = 1.0 / Math.max(MESH_EPSILON,
                        distance(vertices.get(edge.a()),
                                vertices.get(edge.b())));
                adjacency.get(edge.a()).add(new Neighbor(edge.b(), weight));
                adjacency.get(edge.b()).add(new Neighbor(edge.a(), weight));
            }
            final int[] freeIndex = new int[count];
            Arrays.fill(freeIndex, -1);
            int freeCount = 0;
            for (int index = 0; index < count; index++) {
                if (!fixed[index]) {
                    freeIndex[index] = freeCount++;
                }
            }
            final double[] diagonal = new double[freeCount];
            for (int vertex = 0; vertex < count; vertex++) {
                final int free = freeIndex[vertex];
                if (free < 0) {
                    continue;
                }
                for (final Neighbor neighbor : adjacency.get(vertex)) {
                    diagonal[free] += neighbor.weight();
                }
                if (!(diagonal[free] > 0)
                        || !Double.isFinite(diagonal[free])) {
                    throw new IllegalArgumentException(
                            "Harmonic mesh graph has an isolated interior vertex");
                }
            }
            return new HarmonicSystem(count, adjacency, freeIndex, diagonal);
        }
    }

    private static double dot(
            final double[] first,
            final double[] second) {
        double result = 0;
        for (int index = 0; index < first.length; index++) {
            result += first[index] * second[index];
        }
        return result;
    }

    private static double cross(
            final Point2D a,
            final Point2D b,
            final Point2D c) {
        return (b.x() - a.x()) * (c.y() - a.y())
                - (b.y() - a.y()) * (c.x() - a.x());
    }

    private static boolean containsTriangle(
            final Point2D a,
            final Point2D b,
            final Point2D c,
            final Point2D point) {
        final double orientation = Math.signum(cross(a, b, c));
        if (orientation == 0) {
            return false;
        }
        return orientation * cross(a, b, point) >= -MESH_CONTAINMENT_EPSILON
                && orientation * cross(b, c, point)
                >= -MESH_CONTAINMENT_EPSILON
                && orientation * cross(c, a, point)
                >= -MESH_CONTAINMENT_EPSILON;
    }

    private static boolean strictlyInsideTriangle(
            final Point2D a,
            final Point2D b,
            final Point2D c,
            final Point2D point) {
        final double orientation = Math.signum(cross(a, b, c));
        return orientation != 0
                && orientation * cross(a, b, point)
                > MESH_CONTAINMENT_EPSILON
                && orientation * cross(b, c, point)
                > MESH_CONTAINMENT_EPSILON
                && orientation * cross(c, a, point)
                > MESH_CONTAINMENT_EPSILON;
    }

    private static double polygonArea(final List<Point2D> polygon) {
        double doubleArea = 0;
        for (int index = 0; index < polygon.size(); index++) {
            final Point2D point = polygon.get(index);
            final Point2D next = polygon.get((index + 1) % polygon.size());
            doubleArea += point.x() * next.y() - next.x() * point.y();
        }
        return 0.5 * Math.abs(doubleArea);
    }

    private static double[] barycentric(
            final Point2D point,
            final Point2D a,
            final Point2D b,
            final Point2D c) {
        final double denominator = cross(a, b, c);
        if (Math.abs(denominator) <= MESH_MIN_TRIANGLE_AREA) {
            throw new IllegalArgumentException("Mesh triangle is degenerate");
        }
        final double first = cross(point, b, c) / denominator;
        final double second = cross(a, point, c) / denominator;
        return new double[]{first, second, 1 - first - second};
    }

    private static double distance(
            final Point2D first,
            final Point2D second) {
        return Math.hypot(first.x() - second.x(), first.y() - second.y());
    }

    private static double distancePointSegment(
            final Point2D point,
            final Point2D start,
            final Point2D end) {
        final double dx = end.x() - start.x();
        final double dy = end.y() - start.y();
        final double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared <= MESH_MIN_TRIANGLE_AREA) {
            return distance(point, start);
        }
        final double parameter = Math.max(0, Math.min(1,
                ((point.x() - start.x()) * dx
                        + (point.y() - start.y()) * dy) / lengthSquared));
        return distance(point, new Point2D(start.x() + parameter * dx,
                start.y() + parameter * dy));
    }

    private static double nearestBoundaryDistance(
            final Point2D point,
            final List<Segment> segments) {
        double result = Double.POSITIVE_INFINITY;
        for (final Segment segment : segments) {
            result = Math.min(result, distancePointSegment(point,
                    segment.a(), segment.b()));
        }
        return result;
    }

    private static boolean containsInclusive(
            final List<Point2D> polygon,
            final Point2D point) {
        boolean inside = false;
        for (int index = 0, previous = polygon.size() - 1;
                index < polygon.size(); previous = index++) {
            final Point2D current = polygon.get(index);
            final Point2D prior = polygon.get(previous);
            if (distancePointSegment(point, prior, current)
                    <= MESH_CONTAINMENT_EPSILON) {
                return true;
            }
            final boolean intersects = (current.y() > point.y())
                    != (prior.y() > point.y());
            if (intersects) {
                final double x = (prior.x() - current.x())
                        * (point.y() - current.y())
                        / (prior.y() - current.y()) + current.x();
                if (point.x() < x) {
                    inside = !inside;
                }
            }
        }
        return inside;
    }

    private static boolean strictlyInside(
            final List<Point2D> polygon,
            final Point2D point) {
        if (!containsInclusive(polygon, point)) {
            return false;
        }
        for (int index = 0; index < polygon.size(); index++) {
            if (distancePointSegment(point, polygon.get(index),
                    polygon.get((index + 1) % polygon.size()))
                    <= MESH_EPSILON) {
                return false;
            }
        }
        return true;
    }

    private static Point2D interpolate(
            final Point2D first,
            final Point2D second,
            final double fraction) {
        return new Point2D(first.x() + fraction * (second.x() - first.x()),
                first.y() + fraction * (second.y() - first.y()));
    }

    private static double squaredDistance(
            final Point2D first,
            final Point2D second) {
        final double dx = first.x() - second.x();
        final double dy = first.y() - second.y();
        return dx * dx + dy * dy;
    }

    private static List<Double> uniqueParameters(
            final List<Double> values) {
        final List<Double> result = new ArrayList<>();
        for (final double value : values) {
            final double clamped = Math.max(0, Math.min(1, value));
            if (result.isEmpty() || Math.abs(result.get(result.size() - 1)
                    - clamped) > MESH_EPSILON) {
                result.add(clamped);
            }
        }
        return result;
    }

    private static void addSegmentIntersection(
            final List<Double> parameters,
            final Point2D start,
            final Point2D end,
            final Point2D edgeStart,
            final Point2D edgeEnd) {
        final double rx = end.x() - start.x();
        final double ry = end.y() - start.y();
        final double sx = edgeEnd.x() - edgeStart.x();
        final double sy = edgeEnd.y() - edgeStart.y();
        final double denominator = rx * sy - ry * sx;
        final double qx = edgeStart.x() - start.x();
        final double qy = edgeStart.y() - start.y();
        if (Math.abs(denominator) > MESH_EPSILON) {
            final double t = (qx * sy - qy * sx) / denominator;
            final double u = (qx * ry - qy * rx) / denominator;
            if (t >= -MESH_EPSILON && t <= 1 + MESH_EPSILON
                    && u >= -MESH_EPSILON && u <= 1 + MESH_EPSILON) {
                parameters.add(t);
            }
            return;
        }
        if (Math.abs(qx * ry - qy * rx) <= MESH_EPSILON) {
            final double lengthSquared = rx * rx + ry * ry;
            if (lengthSquared > MESH_MIN_TRIANGLE_AREA) {
                parameters.add(((edgeStart.x() - start.x()) * rx
                        + (edgeStart.y() - start.y()) * ry)
                        / lengthSquared);
                parameters.add(((edgeEnd.x() - start.x()) * rx
                        + (edgeEnd.y() - start.y()) * ry)
                        / lengthSquared);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by the JVM",
                    error);
        }
    }

}
