package org.atlasalign.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D;
import org.atlasalign.application.manual.ReviewedOutlineTransform2D;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;

/**
 * Derived immutable review state for one content revision.
 */
public record AlignmentReviewState(
        AlignmentReviewBasis basis,
        AlignmentReviewContent content,
        long contentRevision) implements AtlasMembershipProjection {

    private static final double MINIMUM_SINGULAR_VALUE = 0.02;
    private static final double MAXIMUM_SINGULAR_VALUE = 50;
    private static final double MAXIMUM_ANISOTROPY = 5;

    public AlignmentReviewState {
        basis = Objects.requireNonNull(basis, "basis");
        content = Objects.requireNonNull(content, "content");
        if (contentRevision < 0) {
            throw new IllegalArgumentException(
                    "Content revision must not be negative");
        }
        requireLateralityConsistency(basis, content);
        requireAnatomicalHandleSideConsistency(basis, content);
        requirePlausible(preOutlineTransform(basis, content));
        requirePlausible(content.postOutlinePreviewAdjustment());
        requireOutlineWarpConsistency(basis, content);
        requireManualSidePlacementAxes(basis, content);
        requireHemisphereWarpConsistency(basis, content);
        requireLocalWarpConsistency(basis, content);
        requireTissueSupportConsistency(basis, content);
    }

    public AffineTransform2D effectiveAtlasToPreview() {
        return preOutlineTransform(basis, content);
    }

    /** Exact affine portion that runs before any outline deformation. */
    public AffineTransform2D preOutlineAtlasToPreview() {
        return preOutlineTransform(basis, content);
    }

    /**
     * Maps one atlas pixel centre through the reviewed global transform and,
     * when present, the explicitly reviewer-fitted atlas-only local warp.
     */
    public Point2D mapAtlasToPreview(final Point2D atlasPoint) {
        final Point2D globallyMapped = preOutlineAtlasToPreview().apply(
                Objects.requireNonNull(atlasPoint, "atlasPoint"));
        final Point2D outlineMapped = content.outlineWarp()
                .map(warp -> warp.apply(globallyMapped))
                .orElse(globallyMapped);
        final Point2D postOutlineMapped =
                content.postOutlinePreviewAdjustment().apply(outlineMapped);
        final Optional<ManualHemisphereWarp2D.AtlasSide> rawSide =
                atlasSide(basis, atlasPoint);
        final Point2D placed = rawSide.map(side -> content
                .manualSidePlacement().apply(side, postOutlineMapped))
                .orElse(postOutlineMapped);
        final Point2D hemisphereMapped = content.hemisphereWarp()
                .map(warp -> rawSide.map(side -> warp.apply(side, placed))
                        .orElse(placed))
                .orElse(placed);
        return content.localWarp()
                .map(warp -> warp.apply(hemisphereMapped))
                .orElse(hemisphereMapped);
    }

    /**
     * Maps a point whose immutable raw-atlas side is already known. This is
     * required at the Disjoined seam, where a point exactly on the geometric
     * midline must still inherit the active half's independent placement.
     */
    public Point2D mapAtlasToPreview(
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final Point2D atlasPoint) {
        final ManualHemisphereWarp2D.AtlasSide checkedSide = Objects
                .requireNonNull(atlasSide, "atlasSide");
        final Point2D global = preOutlineAtlasToPreview().apply(
                Objects.requireNonNull(atlasPoint, "atlasPoint"));
        final Point2D outlined = content.outlineWarp()
                .map(warp -> warp.apply(global)).orElse(global);
        final Point2D postOutline = content
                .postOutlinePreviewAdjustment().apply(outlined);
        final Point2D placed = content.manualSidePlacement()
                .apply(checkedSide, postOutline);
        final Point2D hemisphere = content.hemisphereWarp()
                .map(warp -> warp.apply(checkedSide, placed))
                .orElse(placed);
        return content.localWarp()
                .map(warp -> warp.apply(hemisphere))
                .orElse(hemisphere);
    }

    /**
     * Maps through the immutable plane and reviewed global placement, plus a
     * compatibility outline map when present. This tissue/preview coordinate
     * is the source domain for every reviewer-controlled side-local point and
     * never includes the side field itself.
     */
    public Point2D mapAtlasBeforeHemisphereWarp(final Point2D atlasPoint) {
        return mapBeforeHemisphereWarp(
                basis, content,
                Objects.requireNonNull(atlasPoint, "atlasPoint"));
    }

    /** Maps through the shared global stages before disjoined half placement. */
    public Point2D mapAtlasBeforeSidePlacement(final Point2D atlasPoint) {
        final Point2D global = preOutlineAtlasToPreview().apply(
                Objects.requireNonNull(atlasPoint, "atlasPoint"));
        final Point2D outline = content.outlineWarp()
                .map(warp -> warp.apply(global)).orElse(global);
        return content.postOutlinePreviewAdjustment().apply(outline);
    }

    /**
     * Maps a connected atlas vector path through both piecewise-affine meshes,
     * inserting every required mesh-edge split before rendering. No ROI is
     * clipped or independently deformed.
     */
    public List<Point2D> mapAtlasPathToPreview(
            final List<Point2D> atlasPath,
            final boolean closed) {
        if (content.reviewSectionMode() == ReviewSectionMode.DISJOINED) {
            throw new IllegalArgumentException(
                    "Disjoined atlas paths require an explicit raw anatomical side so separated halves cannot be reconnected implicitly");
        }
        final List<Point2D> checked = List.copyOf(
                Objects.requireNonNull(atlasPath, "atlasPath"));
        final List<Point2D> globallyMapped = checked.stream()
                .map(preOutlineAtlasToPreview()::apply).toList();
        final List<Point2D> outlined = content.outlineWarp()
                .map(warp -> warp instanceof BoundaryAuthoritativeTransform2D
                        ? ((BoundaryAuthoritativeTransform2D) warp)
                                .mapPath(globallyMapped, closed)
                        : globallyMapped.stream().map(warp::apply).toList())
                .orElse(globallyMapped);
        final List<Point2D> postOutline = outlined.stream()
                .map(content.postOutlinePreviewAdjustment()::apply).toList();
        final List<Point2D> sideMapped = content.hemisphereWarp()
                .map(warp -> postOutline.size() < 2
                        ? postOutline.stream().map(warp::apply).toList()
                        : warp.mapPath(postOutline, closed))
                .orElse(postOutline);
        return content.localWarp()
                .map(warp -> sideMapped.stream().map(warp::apply).toList())
                .orElse(sideMapped);
    }

    /**
     * Maps a path whose immutable raw-atlas side is already known. This is
     * required for disjoined review because the two preview-domain cages may
     * overlap after independent placement.
     */
    public List<Point2D> mapAtlasPathToPreview(
            final ManualHemisphereWarp2D.AtlasSide atlasSide,
            final List<Point2D> atlasPath,
            final boolean closed) {
        Objects.requireNonNull(atlasSide, "atlasSide");
        final List<Point2D> checked = List.copyOf(
                Objects.requireNonNull(atlasPath, "atlasPath"));
        final List<Point2D> globallyMapped = checked.stream()
                .map(preOutlineAtlasToPreview()::apply).toList();
        final List<Point2D> outlined = content.outlineWarp()
                .map(warp -> warp instanceof BoundaryAuthoritativeTransform2D
                        ? ((BoundaryAuthoritativeTransform2D) warp)
                                .mapPath(globallyMapped, closed)
                        : globallyMapped.stream().map(warp::apply).toList())
                .orElse(globallyMapped);
        final List<Point2D> postOutline = outlined.stream()
                .map(content.postOutlinePreviewAdjustment()::apply).toList();
        final List<Point2D> placed = postOutline.stream()
                .map(point -> content.manualSidePlacement().apply(
                        atlasSide, point)).toList();
        final List<Point2D> sideMapped = content.hemisphereWarp()
                .map(warp -> placed.size() < 2
                        ? placed.stream()
                                .map(point -> warp.apply(atlasSide, point))
                                .toList()
                        : warp.mapPath(atlasSide, placed, closed))
                .orElse(placed);
        return content.localWarp()
                .map(warp -> sideMapped.stream().map(warp::apply).toList())
                .orElse(sideMapped);
    }

    /**
     * Reverses {@link #mapAtlasToPreview(Point2D)}. Local inversion is
     * topology checked and fails closed if it cannot converge safely.
     */
    public Point2D mapPreviewToAtlas(final Point2D previewPoint) {
        final Point2D reviewed = Objects.requireNonNull(
                previewPoint, "previewPoint");
        final Point2D hemisphereMapped = content.localWarp()
                .map(warp -> warp.inverse(reviewed))
                .orElse(reviewed);
        if (content.hemisphereWarp().isPresent()
                || !content.manualSidePlacement().isIdentity()) {
            return inverseSideChain(hemisphereMapped);
        }
        final Point2D postOutlineMapped = hemisphereMapped;
        final Point2D outlineMapped = content
                .postOutlinePreviewAdjustment().inverse()
                .apply(postOutlineMapped);
        final Point2D globallyMapped = content.outlineWarp()
                .map(warp -> warp.inverse(outlineMapped))
                .orElse(outlineMapped);
        return preOutlineAtlasToPreview().inverse().apply(globallyMapped);
    }

    /** Live equivalent of accepted export inversion, including Disjoined. */
    @Override
    public List<Point2D> mapPreviewToAtlasCandidates(
            final Point2D previewPoint) {
        final Point2D reviewed = Objects.requireNonNull(
                previewPoint, "previewPoint");
        if (content.reviewSectionMode() != ReviewSectionMode.DISJOINED
                || (content.hemisphereWarp().isEmpty()
                && content.manualSidePlacement().isIdentity())) {
            return List.of(mapPreviewToAtlas(reviewed));
        }
        final Point2D hemisphereMapped = content.localWarp()
                .map(warp -> warp.inverse(reviewed)).orElse(reviewed);
        return inverseSideCandidates(hemisphereMapped).stream()
                .map(InverseCandidate::atlasPoint).toList();
    }

    /** Uses the same Half/remnant side-membership rule as accepted export. */
    @Override
    public boolean includesAtlasPoint(final Point2D atlasPoint) {
        final Point2D checked = Objects.requireNonNull(
                atlasPoint, "atlasPoint");
        if (content.reviewSectionMode() != ReviewSectionMode.HALF) {
            return true;
        }
        final Optional<ManualHemisphereWarp2D.AtlasSide> side =
                atlasSide(basis, checked);
        if (side.isEmpty()) {
            return false;
        }
        if (content.halfAtlasCoverage().includesOppositeRemnant()) {
            return true;
        }
        final ManualHemisphereWarp2D.AtlasSide visible = switch (
                content.observedHemisphere()) {
            case LEFT -> ManualHemisphereWarp2D.AtlasSide.LEFT;
            case RIGHT -> ManualHemisphereWarp2D.AtlasSide.RIGHT;
            case BOTH, UNSURE -> throw new IllegalStateException(
                    "Half preview requires confirmed visible laterality");
        };
        return side.orElseThrow() == visible;
    }

    public ObservedAnatomicalHemisphere observedAnatomicalHemisphere() {
        return content.observedHemisphere();
    }

    private static void requireTissueSupportConsistency(
            final AlignmentReviewBasis basis,
            final AlignmentReviewContent content) {
        content.reviewedTissueSupport().ifPresent(support -> {
            if (support.width() != basis.previewDimensions().width()
                    || support.height() != basis.previewDimensions().height()) {
                throw new IllegalArgumentException(
                        "Reviewed tissue support must use the active preview dimensions");
            }
        });
        if (content.tissueClippingEnabled()
                && content.reviewedTissueSupport().isEmpty()) {
            throw new IllegalArgumentException(
                    "Tissue clipping requires a reviewed tissue support");
        }
    }

    public List<LandmarkResidual> activeLandmarkResiduals() {
        return content.activeLandmarks().stream()
                .map(landmark -> {
                    final Point2D mapped = mapAtlasToPreview(
                            landmark.atlasPoint());
                    final double deltaX =
                            mapped.x() - landmark.previewPoint().x();
                    final double deltaY =
                            mapped.y() - landmark.previewPoint().y();
                    return new LandmarkResidual(
                            landmark.id(),
                            Math.hypot(deltaX, deltaY));
                })
                .toList();
    }

    public List<LandmarkResidual> activeFitLandmarkResiduals() {
        return residuals(content.activeFitLandmarks());
    }

    public List<LandmarkResidual> activeCheckLandmarkResiduals() {
        return residuals(content.activeCheckLandmarks());
    }

    public double activeLandmarkRmsPixels() {
        return rms(activeLandmarkResiduals());
    }

    public double activeFitLandmarkRmsPixels() {
        return rms(activeFitLandmarkResiduals());
    }

    public double activeCheckLandmarkRmsPixels() {
        return rms(activeCheckLandmarkResiduals());
    }

    private List<LandmarkResidual> residuals(
            final List<LandmarkPair> landmarks) {
        return landmarks.stream().map(landmark -> {
            final Point2D mapped = mapAtlasToPreview(
                    landmark.atlasPoint());
            return new LandmarkResidual(landmark.id(), Math.hypot(
                    mapped.x() - landmark.previewPoint().x(),
                    mapped.y() - landmark.previewPoint().y()));
        }).toList();
    }

    private static double rms(final List<LandmarkResidual> residuals) {
        if (residuals.isEmpty()) {
            return Double.NaN;
        }
        double squared = 0;
        for (final LandmarkResidual residual : residuals) {
            squared += residual.distancePreviewPixels()
                    * residual.distancePreviewPixels();
        }
        return Math.sqrt(squared / residuals.size());
    }

    static AffineTransform2D preOutlineTransform(
            final AlignmentReviewBasis basis,
            final AlignmentReviewContent content) {
        return atlasOrientation(basis, content.orientation())
                .andThen(basis.proposal().affine())
                .andThen(content.manualPreviewAdjustment());
    }

    static void requirePerpendicularAtlasAxes(
            final AffineTransform2D atlasToPreview,
            final String context) {
        final AffineTransform2D checked = Objects.requireNonNull(
                atlasToPreview, "atlasToPreview");
        final double firstScale = Math.hypot(
                checked.m00(), checked.m10());
        final double secondScale = Math.hypot(
                checked.m01(), checked.m11());
        final double dot = checked.m00() * checked.m01()
                + checked.m10() * checked.m11();
        if (!Double.isFinite(firstScale) || !Double.isFinite(secondScale)
                || firstScale <= 0 || secondScale <= 0
                || Math.abs(dot) > 1e-8 * firstScale * secondScale) {
            throw new IllegalArgumentException(
                    Objects.requireNonNull(context, "context")
                            + " must retain perpendicular atlas axes (no shear)");
        }
    }

    /**
     * Requires a candidate to retain the reference transform's affine shear.
     *
     * <p>The automatic proposal is a general affine and may legitimately
     * contain shear. Coarse placement runs after that proposal and after any
     * already-reviewed placement, so requiring the complete candidate to have
     * perpendicular columns would reject even a translation. Write the
     * reference linear map as
     * {@code Q D H}, where {@code Q} is orthogonal, {@code D} is diagonal, and
     * {@code H = [[1, h], [0, 1]]} contains the inherited shear. Removing the
     * same {@code H} from the candidate must leave perpendicular axes.</p>
     */
    public static void requirePreservedAtlasAxisShear(
            final AffineTransform2D referenceAtlasToPreview,
            final AffineTransform2D candidateAtlasToPreview,
            final String context) {
        final AffineTransform2D reference = Objects.requireNonNull(
                referenceAtlasToPreview, "referenceAtlasToPreview");
        final AffineTransform2D candidate = Objects.requireNonNull(
                candidateAtlasToPreview, "candidateAtlasToPreview");
        final String checkedContext = Objects.requireNonNull(
                context, "context");
        final double referenceFirstLength = Math.hypot(
                reference.m00(), reference.m10());
        final double referenceSecondLength = Math.hypot(
                reference.m01(), reference.m11());
        if (!wellConditionedAxisPair(
                referenceFirstLength, referenceSecondLength)) {
            throw new IllegalArgumentException(
                    checkedContext + " has ill-conditioned reference atlas axes");
        }
        final double inheritedShear = (reference.m00() * reference.m01()
                + reference.m10() * reference.m11())
                / (referenceFirstLength * referenceFirstLength);
        final double referenceCorrectedSecondX = reference.m01()
                - inheritedShear * reference.m00();
        final double referenceCorrectedSecondY = reference.m11()
                - inheritedShear * reference.m10();
        final double referenceCorrectedSecondLength = Math.hypot(
                referenceCorrectedSecondX, referenceCorrectedSecondY);
        final double candidateFirstLength = Math.hypot(
                candidate.m00(), candidate.m10());
        final double candidateCorrectedSecondX = candidate.m01()
                - inheritedShear * candidate.m00();
        final double candidateCorrectedSecondY = candidate.m11()
                - inheritedShear * candidate.m10();
        final double candidateCorrectedSecondLength = Math.hypot(
                candidateCorrectedSecondX, candidateCorrectedSecondY);
        if (!Double.isFinite(inheritedShear)
                || !wellConditionedAxisPair(referenceFirstLength,
                        referenceCorrectedSecondLength)
                || !wellConditionedAxisPair(candidateFirstLength,
                        candidateCorrectedSecondLength)) {
            throw new IllegalArgumentException(
                    checkedContext
                            + " has ill-conditioned shear-corrected atlas axes");
        }
        final double normalizedDot = (candidate.m00()
                * candidateCorrectedSecondX + candidate.m10()
                * candidateCorrectedSecondY)
                / (candidateFirstLength * candidateCorrectedSecondLength);
        if (!Double.isFinite(normalizedDot)
                || Math.abs(normalizedDot) > 1e-8) {
            throw new IllegalArgumentException(
                    checkedContext
                            + " must preserve the inherited atlas-axis shear (no shear change); normalized corrected dot="
                            + normalizedDot);
        }
    }

    private static boolean wellConditionedAxisPair(
            final double firstLength,
            final double secondLength) {
        if (!Double.isFinite(firstLength) || !Double.isFinite(secondLength)
                || firstLength <= 0 || secondLength <= 0) {
            return false;
        }
        return Math.min(firstLength, secondLength)
                > 1e-12 * Math.max(firstLength, secondLength);
    }

    private static void requireManualSidePlacementAxes(
            final AlignmentReviewBasis basis,
            final AlignmentReviewContent content) {
        if (content.reviewSectionMode() != ReviewSectionMode.DISJOINED
                || content.manualSidePlacement().isIdentity()) {
            return;
        }
        if (content.outlineWarp().isPresent()) {
            throw new IllegalArgumentException(
                    "Disjoined side placement cannot be composed with a legacy nonlinear outline map");
        }
        final AffineTransform2D shared = preOutlineTransform(basis, content)
                .andThen(content.postOutlinePreviewAdjustment());
        for (final ManualHemisphereWarp2D.AtlasSide side
                : ManualHemisphereWarp2D.AtlasSide.values()) {
            final AffineTransform2D sidePlacement = content
                    .manualSidePlacement().transform(side);
            if (!isIdentityPreviewTransform(sidePlacement)) {
                requirePreservedAtlasAxisShear(
                        shared, shared.andThen(sidePlacement),
                        "Disjoined " + side + " placement");
            }
        }
    }

    private static boolean isIdentityPreviewTransform(
            final AffineTransform2D transform) {
        return transform.sourceSpace() == CoordinateSpace2D.PREVIEW_PIXEL
                && transform.destinationSpace()
                == CoordinateSpace2D.PREVIEW_PIXEL
                && transform.m00() == 1 && transform.m01() == 0
                && transform.m02() == 0 && transform.m10() == 0
                && transform.m11() == 1 && transform.m12() == 0;
    }

    /** Maps through every accepted stage except the optional generic warp. */
    private static Point2D mapBeforeLocalWarp(
            final AlignmentReviewBasis basis,
            final AlignmentReviewContent content,
            final Point2D atlasPoint) {
        final Point2D global = preOutlineTransform(basis, content)
                .apply(atlasPoint);
        final Point2D outline = content.outlineWarp()
                .map(warp -> warp.apply(global))
                .orElse(global);
        final Point2D postOutline = content.postOutlinePreviewAdjustment()
                .apply(outline);
        final Optional<ManualHemisphereWarp2D.AtlasSide> rawSide =
                atlasSide(basis, atlasPoint);
        final Point2D placed = rawSide.map(side -> content
                .manualSidePlacement().apply(side, postOutline))
                .orElse(postOutline);
        return content.hemisphereWarp()
                .map(warp -> rawSide.map(side -> warp.apply(side, placed))
                        .orElse(placed))
                .orElse(placed);
    }

    /** Maps through the immutable/global/outline stages before side-local work. */
    private static Point2D mapBeforeHemisphereWarp(
            final AlignmentReviewBasis basis,
            final AlignmentReviewContent content,
            final Point2D atlasPoint) {
        final Point2D global = preOutlineTransform(basis, content)
                .apply(atlasPoint);
        final Point2D outline = content.outlineWarp()
                .map(warp -> warp.apply(global))
                .orElse(global);
        final Point2D postOutline = content.postOutlinePreviewAdjustment()
                .apply(outline);
        return atlasSide(basis, atlasPoint).map(side -> content
                .manualSidePlacement().apply(side, postOutline))
                .orElse(postOutline);
    }

    private static AffineTransform2D atlasOrientation(
            final AlignmentReviewBasis basis,
            final AtlasOrientation orientation) {
        return orientation.reflected()
                ? new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        -1, 0,
                        basis.atlas().atlasPlaneWidth() - 1.0,
                        0, 1, 0)
                : new AffineTransform2D(
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                        1, 0, 0,
                        0, 1, 0);
    }

    /** Raw atlas X is authoritative for anatomical side classification. */
    private static Optional<ManualHemisphereWarp2D.AtlasSide> atlasSide(
            final AlignmentReviewBasis basis,
            final Point2D atlasPoint) {
        final double centre = (basis.atlas().atlasPlaneWidth() - 1.0) * 0.5;
        if (Double.doubleToLongBits(atlasPoint.x())
                == Double.doubleToLongBits(centre)) {
            return Optional.empty();
        }
        return Optional.of(atlasPoint.x() < centre
                ? ManualHemisphereWarp2D.AtlasSide.LEFT
                : ManualHemisphereWarp2D.AtlasSide.RIGHT);
    }

    private Point2D inverseSideChain(final Point2D previewPoint) {
        return inverseSideCandidates(previewPoint).get(0).atlasPoint();
    }

    private List<InverseCandidate> inverseSideCandidates(
            final Point2D previewPoint) {
        final Optional<ManualHemisphereWarp2D> warp = content
                .hemisphereWarp();
        final List<InverseCandidate> candidates = new java.util.ArrayList<>();
        final List<Optional<ManualHemisphereWarp2D.AtlasSide>> choices =
                List.of(Optional.empty(),
                        Optional.of(ManualHemisphereWarp2D.AtlasSide.LEFT),
                        Optional.of(ManualHemisphereWarp2D.AtlasSide.RIGHT));
        for (final Optional<ManualHemisphereWarp2D.AtlasSide> choice
                : choices) {
            final Point2D beforePlacement;
            try {
                final Point2D beforeHemisphere = choice
                        .map(side -> warp.filter(field ->
                                        field.hasControls(side))
                                .map(field -> field.inverse(
                                        side, previewPoint))
                                .orElse(previewPoint))
                        .orElse(previewPoint);
                beforePlacement = choice.map(side -> content
                        .manualSidePlacement().inverse(
                                side, beforeHemisphere))
                        .orElse(beforeHemisphere);
            } catch (final IllegalArgumentException unsafeInverse) {
                continue;
            }
            final Point2D atlas = reverseBeforeHemisphere(beforePlacement);
            final Optional<ManualHemisphereWarp2D.AtlasSide> rawSide =
                    atlasSide(basis, atlas);
            final boolean validChoice = choice.isPresent()
                    ? rawSide.equals(choice)
                    : rawSide.isEmpty()
                    || !sideHasEffect(rawSide.orElseThrow(), warp);
            if (!validChoice) {
                continue;
            }
            final Point2D replay = mapAtlasToPreview(atlas);
            final double residual = Math.hypot(
                    replay.x() - previewPoint.x(),
                    replay.y() - previewPoint.y());
            if (Double.isFinite(residual) && residual <= 1e-6
                    && candidates.stream().noneMatch(existing ->
                            Math.hypot(existing.atlasPoint().x() - atlas.x(),
                                    existing.atlasPoint().y() - atlas.y())
                            <= 1e-9)) {
                candidates.add(new InverseCandidate(atlas, residual));
            }
        }
        candidates.sort(java.util.Comparator
                .comparingDouble(InverseCandidate::residual)
                .thenComparingDouble(candidate ->
                        candidate.atlasPoint().x())
                .thenComparingDouble(candidate ->
                        candidate.atlasPoint().y()));
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "Hemisphere-warp inverse could not preserve raw atlas-side semantics");
        }
        return List.copyOf(candidates);
    }

    private record InverseCandidate(Point2D atlasPoint, double residual) {
    }

    private boolean sideHasEffect(
            final ManualHemisphereWarp2D.AtlasSide side,
            final Optional<ManualHemisphereWarp2D> warp) {
        final AffineTransform2D identity = new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                1, 0, 0, 0, 1, 0);
        return !content.manualSidePlacement().transform(side).equals(identity)
                || warp.map(field -> field.hasControls(side)).orElse(false);
    }

    private Point2D reverseBeforeHemisphere(final Point2D previewPoint) {
        final Point2D outlined = content.postOutlinePreviewAdjustment()
                .inverse().apply(previewPoint);
        final Point2D global = content.outlineWarp()
                .map(warp -> warp.inverse(outlined)).orElse(outlined);
        return preOutlineAtlasToPreview().inverse().apply(global);
    }

    private static void requireAnatomicalHandleSideConsistency(
            final AlignmentReviewBasis basis,
            final AlignmentReviewContent content) {
        final double centre = (basis.atlas().atlasPlaneWidth() - 1.0) * 0.5;
        for (final LandmarkPair pair : content.landmarks()) {
            if (pair.anatomicalHandleMetadata().isEmpty()) {
                continue;
            }
            final AtlasAnatomicalSide declared = pair
                    .anatomicalHandleMetadata().orElseThrow().atlasSide();
            final AtlasAnatomicalSide expected;
            if (Double.doubleToLongBits(pair.atlasPoint().x())
                    == Double.doubleToLongBits(centre)) {
                expected = AtlasAnatomicalSide.MIDLINE;
            } else {
                expected = pair.atlasPoint().x() < centre
                        ? AtlasAnatomicalSide.ATLAS_LEFT
                        : AtlasAnatomicalSide.ATLAS_RIGHT;
            }
            if (declared != expected) {
                throw new IllegalArgumentException(
                        "Typed anatomical handle side does not match raw atlas X relative to the verified atlas centre");
            }
        }
    }

    private static void requireLateralityConsistency(
            final AlignmentReviewBasis basis,
            final AlignmentReviewContent content) {
        final SectionGeometry geometry =
                basis.proposal().geometry().geometry();
        final ObservedAnatomicalHemisphere expectedHalf =
                expectedHalfHemisphere(
                        geometry, content.orientation());
        final ReviewSectionMode inferredMode =
                geometry == SectionGeometry.IMAGE_LEFT_HALF
                        || geometry == SectionGeometry.IMAGE_RIGHT_HALF
                        ? ReviewSectionMode.HALF : ReviewSectionMode.FULL;
        if (content.reviewSectionMode() != inferredMode) {
            if (content.reviewSectionMode() != ReviewSectionMode.HALF) {
                if (content.observedHemisphere()
                        != ObservedAnatomicalHemisphere.BOTH) {
                    throw new IllegalArgumentException(
                            "Reviewer-selected bilateral mode requires both anatomical hemispheres");
                }
                return;
            }
            final boolean claimsBoth = content.observedHemisphere()
                    == ObservedAnatomicalHemisphere.BOTH;
            final boolean unconfirmedClaimsLaterality =
                    !content.orientation().confirmed()
                    && content.observedHemisphere()
                    != ObservedAnatomicalHemisphere.UNSURE;
            final boolean confirmedAutomaticHalfMismatch =
                    expectedHalf != null
                    && content.orientation().confirmed()
                    && content.observedHemisphere()
                    != ObservedAnatomicalHemisphere.UNSURE
                    && content.observedHemisphere() != expectedHalf;
            if (claimsBoth || unconfirmedClaimsLaterality
                    || confirmedAutomaticHalfMismatch) {
                throw new IllegalArgumentException(
                        "Observed hemisphere is inconsistent with reviewer-selected Half mode and orientation");
            }
            return;
        }
        final boolean bilateralGeometry =
                geometry == SectionGeometry.FULL
                || geometry
                == SectionGeometry.BILATERAL_REVIEW_REQUIRED;
        final boolean fullGeometryMismatch =
                bilateralGeometry
                && content.observedHemisphere()
                != ObservedAnatomicalHemisphere.BOTH;
        final boolean nonFullClaimsBoth =
                !bilateralGeometry
                && content.observedHemisphere()
                == ObservedAnatomicalHemisphere.BOTH;
        final boolean unconfirmedHalfClaimsLaterality =
                (geometry == SectionGeometry.IMAGE_LEFT_HALF
                || geometry == SectionGeometry.IMAGE_RIGHT_HALF)
                && !content.orientation().confirmed()
                && content.observedHemisphere()
                != ObservedAnatomicalHemisphere.UNSURE;
        final boolean confirmedHalfMismatch =
                expectedHalf != null
                && content.orientation().confirmed()
                && content.observedHemisphere()
                != ObservedAnatomicalHemisphere.UNSURE
                && content.observedHemisphere() != expectedHalf;
        if (fullGeometryMismatch
                || nonFullClaimsBoth
                || unconfirmedHalfClaimsLaterality
                || confirmedHalfMismatch) {
            throw new IllegalArgumentException(
                    "Observed hemisphere is inconsistent with tissue geometry and orientation");
        }
    }

    private static ObservedAnatomicalHemisphere
            expectedHalfHemisphere(
            final SectionGeometry geometry,
            final AtlasOrientation orientation) {
        if (geometry != SectionGeometry.IMAGE_LEFT_HALF
                && geometry != SectionGeometry.IMAGE_RIGHT_HALF) {
            return null;
        }
        final boolean imageLeftIsAtlasLeft =
                !orientation.reflected();
        if (geometry == SectionGeometry.IMAGE_LEFT_HALF) {
            return imageLeftIsAtlasLeft
                    ? ObservedAnatomicalHemisphere.LEFT
                    : ObservedAnatomicalHemisphere.RIGHT;
        }
        return imageLeftIsAtlasLeft
                ? ObservedAnatomicalHemisphere.RIGHT
                : ObservedAnatomicalHemisphere.LEFT;
    }

    private static void requirePlausible(
            final AffineTransform2D transform) {
        final double first = transform.m00() * transform.m00()
                + transform.m10() * transform.m10();
        final double second = transform.m01() * transform.m01()
                + transform.m11() * transform.m11();
        final double determinant = Math.abs(transform.determinant());
        final double trace = first + second;
        final double discriminant = Math.sqrt(Math.max(
                0, trace * trace
                - 4 * determinant * determinant));
        final double maximum = Math.sqrt(
                (trace + discriminant) * 0.5);
        final double minimum = Math.sqrt(Math.max(
                0, (trace - discriminant) * 0.5));
        if (!Double.isFinite(minimum)
                || !Double.isFinite(maximum)
                || minimum < MINIMUM_SINGULAR_VALUE
                || maximum > MAXIMUM_SINGULAR_VALUE
                || maximum / minimum > MAXIMUM_ANISOTROPY) {
            throw new IllegalArgumentException(
                    "Reviewed transform is outside safe manual bounds");
        }
    }

    private static void requireOutlineWarpConsistency(
            final AlignmentReviewBasis basis,
            final AlignmentReviewContent content) {
        if (content.outlineWarp().isEmpty()) {
            return;
        }
        if (basis.proposal().geometry().geometry() != SectionGeometry.FULL
                || content.observedHemisphere()
                != ObservedAnatomicalHemisphere.BOTH
                || !content.orientation().confirmed()
                || !content.outlineAnchorsConfirmed()) {
            throw new IllegalArgumentException(
                    "An outline warp requires a complete FULL bilateral section and confirmed orientation and anchors");
        }
        final ReviewedOutlineTransform2D warp = content.outlineWarp()
                .orElseThrow();
        if (warp.sourceSpace() != CoordinateSpace2D.PREVIEW_PIXEL
                || warp.destinationSpace()
                != CoordinateSpace2D.PREVIEW_PIXEL
                || warp.previewWidth()
                != basis.previewDimensions().width()
                || warp.previewHeight()
                != basis.previewDimensions().height()) {
            throw new IllegalArgumentException(
                    "Outline warp must use the active review preview coordinate domain");
        }
    }

    /** Rejects stale, side-swapped, or externally assembled side warps. */
    private static void requireHemisphereWarpConsistency(
            final AlignmentReviewBasis basis,
            final AlignmentReviewContent content) {
        if (content.hemisphereWarp().isEmpty()) {
            return;
        }
        if (!content.orientation().confirmed()) {
            throw new IllegalArgumentException(
                    "A reviewer-controlled manual warp requires an explicit orientation");
        }
        final ManualHemisphereWarp2D warp = content.hemisphereWarp()
                .orElseThrow();
        if (warp.sourceSpace() != CoordinateSpace2D.PREVIEW_PIXEL
                || warp.destinationSpace() != CoordinateSpace2D.PREVIEW_PIXEL
                || warp.previewWidth() != basis.previewDimensions().width()
                || warp.previewHeight() != basis.previewDimensions().height()
                || warp.reviewSectionMode() != content.reviewSectionMode()
                || warp.orientation() != content.orientation()) {
            throw new IllegalArgumentException(
                    "Hemisphere warp identity does not match the active review");
        }
        final boolean legacyExact = !warp.outlineContentSha256().isBlank()
                && content.outlineWarp().orElse(null)
                instanceof BoundaryAuthoritativeTransform2D;
        final boolean seamMatches;
        if (legacyExact) {
            final BoundaryAuthoritativeTransform2D exact =
                    (BoundaryAuthoritativeTransform2D) content.outlineWarp()
                            .orElseThrow();
            final var endpoints = exact.hemisphereMidline();
            final ManualHemisphereWarp2D.MidlineSegment expectedMidline =
                    new ManualHemisphereWarp2D.MidlineSegment(
                    endpoints.dorsal(), endpoints.ventral());
            seamMatches = warp.imageMidline().equals(expectedMidline)
                    && warp.imageMidlinePath().equals(
                            exact.hemisphereMidlinePath());
        } else {
            final double centreX = (basis.atlas().atlasPlaneWidth() - 1.0)
                    * 0.5;
            final ManualHemisphereWarp2D.MidlineSegment mappedMidline =
                    new ManualHemisphereWarp2D.MidlineSegment(
                    mapBeforeHemisphereWarp(basis, content,
                            new Point2D(centreX, 0)),
                    mapBeforeHemisphereWarp(basis, content,
                            new Point2D(centreX,
                                    basis.atlas().atlasPlaneHeight() - 1.0)));
            final double legacyCentreX =
                    (basis.previewDimensions().width() - 1.0) * 0.5;
            final ManualHemisphereWarp2D.MidlineSegment legacyMidline =
                    new ManualHemisphereWarp2D.MidlineSegment(
                            new Point2D(legacyCentreX, 0),
                            new Point2D(legacyCentreX,
                                    basis.previewDimensions().height()
                                            - 1.0));
            seamMatches = (warp.imageMidline().equals(mappedMidline)
                    && warp.imageMidlinePath().equals(List.of(
                            mappedMidline.dorsal(),
                            mappedMidline.ventral())))
                    || (warp.imageMidline().equals(legacyMidline)
                    && warp.imageMidlinePath().equals(List.of(
                            legacyMidline.dorsal(),
                            legacyMidline.ventral())));
        }
        if (!seamMatches) {
            throw new IllegalArgumentException(
                    "Manual warp preview seam is stale for the current review domain");
        }
        if (warp.controls().isEmpty()
                || warp.controls().stream().anyMatch(control ->
                        control == null)) {
            throw new IllegalArgumentException(
                    "Reviewer-controlled manual warp requires immutable typed manual controls");
        }
    }

    /** Midline fixed by the confirmed dorsal/ventral outline anchors. */
    private static ManualHemisphereWarp2D.MidlineSegment hemisphereMidline(
            final AlignmentReviewContent content) {
        final ReviewedOutlineTransform2D outline = content.outlineWarp()
                .orElseThrow();
        final var midline = outline.hemisphereMidline();
        final Point2D dorsal = content.postOutlinePreviewAdjustment().apply(
                midline.dorsal());
        final Point2D ventral = content.postOutlinePreviewAdjustment().apply(
                midline.ventral());
        return new ManualHemisphereWarp2D.MidlineSegment(dorsal, ventral);
    }

    /** Rejects stale or externally assembled local-warp/content pairings. */
    private static void requireLocalWarpConsistency(
            final AlignmentReviewBasis basis,
            final AlignmentReviewContent content) {
        if (content.localWarp().isEmpty()) {
            return;
        }
        final ConstrainedLocalWarp2D warp = content.localWarp().orElseThrow();
        final List<LandmarkPair> fit = content.activeGenericFitLandmarks();
        if (fit.size() < 4 || fit.size() != warp.fitSourcePoints().size()
                || fit.size() != warp.fitTargetPoints().size()) {
            throw new IllegalArgumentException(
                    "Local warp does not match the active exact-plane FIT landmarks");
        }
        for (int index = 0; index < fit.size(); index++) {
            if (!mapBeforeLocalWarp(
                    basis, content, fit.get(index).atlasPoint()).equals(
                    warp.fitSourcePoints().get(index))
                    || !fit.get(index).previewPoint().equals(
                    warp.fitTargetPoints().get(index))) {
                throw new IllegalArgumentException(
                        "Local warp controls are stale for the current global transform or landmarks");
            }
        }
    }

    public record LandmarkResidual(
            String landmarkId,
            double distancePreviewPixels) {

        public LandmarkResidual {
            landmarkId = Objects.requireNonNull(
                    landmarkId, "landmarkId");
            if (!Double.isFinite(distancePreviewPixels)
                    || distancePreviewPixels < 0) {
                throw new IllegalArgumentException(
                        "Landmark residual must be finite and non-negative");
            }
        }
    }
}
