package org.atlasalign.application.manual;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;

/**
 * Reviewer-controlled deformation from one ordered atlas exterior to one
 * ordered tissue exterior in preview-pixel space.
 *
 * <p>Four explicitly named, cyclic anchor pairs establish correspondence.
 * Each anchor-to-anchor arc is then matched monotonically in the already
 * established preview geometry before a broad-support Wendland-C2
 * displacement is fitted. This avoids treating equal fractions of two
 * differently shaped boundary arcs as anatomical correspondences. This class
 * never infers reflection, anatomical laterality, an atlas plane, or an
 * automatic result.</p>
 */
public final class ManualOutlineWarp2D implements ReviewedOutlineTransform2D {

    public static final String ALGORITHM_REVISION =
            "manual-outline-adaptive-baseline-residual-monotonic-wendland-c2-preview-warp-v6";
    public static final String PIXEL_CENTER_CONVENTION =
            "top-left-pixel-center-is-(0,0)";
    public static final int REQUIRED_ANCHOR_COUNT = 4;
    // Boundary matching is geometric, not anatomical. Eight controls per
    // cardinal arc provide useful adherence without forcing every possibly
    // non-homologous contour vertex. Every fit still passes the same
    // displacement, topology, Jacobian, inverse, and round-trip gates.
    public static final int SAMPLES_PER_ANCHOR_ARC = 8;
    public static final int MINIMUM_SAMPLES_PER_ANCHOR_ARC = 3;
    public static final int MAXIMUM_SAMPLES_PER_ANCHOR_ARC = 10;
    public static final int ADAPTIVE_CORRESPONDENCES_PER_ANCHOR_ARC = 32;
    public static final int MAXIMUM_ADAPTIVE_CONTROL_COUNT = 64;
    public static final double STRICT_BOUNDARY_P95_MINIMUM_PIXELS = 2.0;
    public static final double STRICT_BOUNDARY_P95_DIAGONAL_FRACTION = 0.0025;
    public static final double STRICT_BOUNDARY_MAXIMUM_MINIMUM_PIXELS = 5.0;
    public static final double STRICT_BOUNDARY_MAXIMUM_DIAGONAL_FRACTION =
            0.0075;
    public static final double REGULARIZATION = 0.001;
    public static final double SUPPORT_RADIUS_FRACTION = 0.75;

    private static final int MATCH_CANDIDATES_PER_ANCHOR_ARC = 160;
    private static final double MATCH_PARAMETER_REGULARIZATION = 0.002;

    private static final int SAFETY_GRID_SIZE = 33;
    private static final int CROSSING_GRID_SIZE = 17;
    private static final double MINIMUM_HULL_AREA_FRACTION = 0.01;
    private static final double MAXIMUM_REQUESTED_DISPLACEMENT_FRACTION = 0.25;
    private static final double MAXIMUM_SAMPLED_DISPLACEMENT_FRACTION = 0.35;
    private static final double MAXIMUM_CONTROL_RESIDUAL_FRACTION = 0.01;
    private static final double MINIMUM_JACOBIAN_DETERMINANT = 0.15;
    private static final double MINIMUM_SINGULAR_VALUE = 0.25;
    private static final double MAXIMUM_SINGULAR_VALUE = 4.0;
    private static final double MAXIMUM_ANISOTROPY = 4.0;
    private static final int MAXIMUM_INVERSE_ITERATIONS = 48;
    private static final int MAXIMUM_LINE_SEARCH_STEPS = 20;
    private static final double INVERSE_TOLERANCE = 1e-8;
    private static final double ROUND_TRIP_TOLERANCE = 1e-6;
    private static final double MINIMUM_SOLVER_PIVOT = 1e-14;
    private static final double GEOMETRY_EPSILON = 1e-10;

    private final List<Point2D> atlasLoop;
    private final List<Point2D> tissueLoop;
    private final List<AnchorPair> anchors;
    private final List<Point2D> sourceControlPoints;
    private final List<Point2D> targetControlPoints;
    private final double[] xWeights;
    private final double[] yWeights;
    private final List<Double> immutableXWeights;
    private final List<Double> immutableYWeights;
    private final double supportRadius;
    private final Diagnostics diagnostics;

    private ManualOutlineWarp2D(
            final List<Point2D> atlasLoop,
            final List<Point2D> tissueLoop,
            final List<AnchorPair> anchors,
            final List<Point2D> sourceControlPoints,
            final List<Point2D> targetControlPoints,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius,
            final Diagnostics diagnostics) {
        this.atlasLoop = List.copyOf(atlasLoop);
        this.tissueLoop = List.copyOf(tissueLoop);
        this.anchors = List.copyOf(anchors);
        this.sourceControlPoints = List.copyOf(sourceControlPoints);
        this.targetControlPoints = List.copyOf(targetControlPoints);
        this.xWeights = xWeights.clone();
        this.yWeights = yWeights.clone();
        this.immutableXWeights = immutableDoubles(this.xWeights);
        this.immutableYWeights = immutableDoubles(this.yWeights);
        this.supportRadius = supportRadius;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public record Snapshot(List<Point2D> atlasLoop, List<Point2D> tissueLoop,
            List<AnchorPair> anchors, List<Point2D> sourceControlPoints,
            List<Point2D> targetControlPoints, List<Double> xWeights, List<Double> yWeights,
            double supportRadius, Diagnostics diagnostics) {
        public Snapshot {
            atlasLoop = List.copyOf(atlasLoop); tissueLoop = List.copyOf(tissueLoop);
            anchors = List.copyOf(anchors); sourceControlPoints = List.copyOf(sourceControlPoints);
            targetControlPoints = List.copyOf(targetControlPoints);
            xWeights = List.copyOf(xWeights); yWeights = List.copyOf(yWeights);
            Objects.requireNonNull(diagnostics, "diagnostics");
            final int count = sourceControlPoints.size();
            if (!ALGORITHM_REVISION.equals(diagnostics.algorithmRevision())
                    || !PIXEL_CENTER_CONVENTION.equals(diagnostics.pixelCenterConvention())
                    || atlasLoop.size() > 16384 || tissueLoop.size() > 16384
                    || count != diagnostics.controlPairCount()
                    || count > MAXIMUM_ADAPTIVE_CONTROL_COUNT || targetControlPoints.size() != count
                    || xWeights.size() != count || yWeights.size() != count
                    || !Double.isFinite(supportRadius) || supportRadius <= 0
                    || supportRadius != diagnostics.supportRadius()
                    || diagnostics.regularization() != REGULARIZATION
                    || xWeights.stream().anyMatch(value -> !Double.isFinite(value))
                    || yWeights.stream().anyMatch(value -> !Double.isFinite(value))) {
                throw new IllegalArgumentException("Unsupported or invalid stored outline-warp geometry");
            }
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(atlasLoop, tissueLoop, anchors, sourceControlPoints, targetControlPoints,
                immutableXWeights, immutableYWeights, supportRadius, diagnostics);
    }

    /** Restores recorded solved coefficients and validates them without refitting the outline. */
    public static ManualOutlineWarp2D restore(final Snapshot saved) {
        Objects.requireNonNull(saved, "saved");
        final ValidatedInput input = validatedInput(saved.atlasLoop(), saved.tissueLoop(), saved.anchors());
        final Diagnostics d = saved.diagnostics();
        if (input.atlasDirection() != d.atlasTraversalDirection()
                || input.tissueDirection() != d.tissueTraversalDirection()) {
            throw new IllegalArgumentException("Stored outline traversal does not match its geometry");
        }
        final double[] x = saved.xWeights().stream().mapToDouble(Double::doubleValue).toArray();
        final double[] y = saved.yWeights().stream().mapToDouble(Double::doubleValue).toArray();
        final String hash = contentHash(saved.atlasLoop(), saved.tissueLoop(), saved.anchors(),
                d.atlasTraversalDirection(), d.tissueTraversalDirection(), d.previewWidth(), d.previewHeight(),
                saved.sourceControlPoints(), saved.targetControlPoints(), saved.supportRadius(), x, y,
                d.strictBoundaryQualityEnforced(), d.seedSamplesPerAnchorArc(), d.denseCorrespondenceCount());
        if (!hash.equals(d.contentSha256())) {
            throw new IllegalArgumentException("Stored outline-warp geometry checksum does not match");
        }
        final double diagonal = Math.hypot(d.previewWidth(), d.previewHeight());
        requireControlCoverage(saved.sourceControlPoints(), d.previewWidth(), d.previewHeight());
        final MutableDiagnostics sampled = sampleSafety(saved.sourceControlPoints(), x, y,
                saved.supportRadius(), d.previewWidth(), d.previewHeight(), saved.atlasLoop());
        rejectUnsafeSampledGeometry(sampled, diagonal);
        final double[] residuals = controlResiduals(saved.sourceControlPoints(), saved.targetControlPoints(),
                x, y, saved.supportRadius());
        if (residuals[1] > MAXIMUM_CONTROL_RESIDUAL_FRACTION * diagonal) {
            throw new IllegalArgumentException("Stored outline control residual exceeds its safety limit");
        }
        final WarpFunction function = new WarpFunction(saved.sourceControlPoints(), x, y, saved.supportRadius());
        final List<Point2D> warpedLoop = saved.atlasLoop().stream().map(function::apply).toList();
        requireSimpleLoop(warpedLoop, "stored warped atlas exterior");
        requireNoGridCrossings(function, d.previewWidth(), d.previewHeight());
        requireInverseRoundTrips(function, d.previewWidth(), d.previewHeight(), saved.atlasLoop());
        if (d.strictBoundaryQualityEnforced()) {
            final BoundaryDistances distances = boundaryDistances(warpedLoop, saved.tissueLoop());
            if (distances.p95() > Math.max(STRICT_BOUNDARY_P95_MINIMUM_PIXELS,
                    STRICT_BOUNDARY_P95_DIAGONAL_FRACTION * diagonal)
                    || distances.maximum() > Math.max(STRICT_BOUNDARY_MAXIMUM_MINIMUM_PIXELS,
                    STRICT_BOUNDARY_MAXIMUM_DIAGONAL_FRACTION * diagonal)) {
                throw new IllegalArgumentException("Stored outline does not meet its strict boundary gate");
            }
        }
        return new ManualOutlineWarp2D(saved.atlasLoop(), saved.tissueLoop(), saved.anchors(),
                saved.sourceControlPoints(), saved.targetControlPoints(), x, y, saved.supportRadius(), d);
    }

    /**
     * Fits a warp between two simple closed loops in preview-pixel space.
     * Neither loop repeats its first vertex at the end.
     *
     * @param orderedAtlasLoop globally mapped atlas exterior
     * @param orderedTissueLoop reviewer-drawn tissue exterior
     * @param orderedAnchors four semantic pairs in matching cyclic order
     */
    public static ManualOutlineWarp2D fit(
            final List<Point2D> orderedAtlasLoop,
            final List<Point2D> orderedTissueLoop,
            final List<AnchorPair> orderedAnchors,
            final int previewWidth,
            final int previewHeight) {
        return fit(orderedAtlasLoop, orderedTissueLoop, orderedAnchors,
                previewWidth, previewHeight, SAMPLES_PER_ANCHOR_ARC);
    }

    /**
     * Fits the same bounded field with an explicit, auditable number of
     * boundary controls per semantic quarter. Source controls are equally
     * arc-spaced. Their target points are selected by a deterministic,
     * strictly monotonic geometric match within the corresponding semantic
     * arc. Increasing this value cannot bypass any displacement, topology,
     * Jacobian, inverse, or round-trip safety check.
     */
    public static ManualOutlineWarp2D fit(
            final List<Point2D> orderedAtlasLoop,
            final List<Point2D> orderedTissueLoop,
            final List<AnchorPair> orderedAnchors,
            final int previewWidth,
            final int previewHeight,
            final int samplesPerAnchorArc) {
        if (previewWidth <= 1 || previewHeight <= 1) {
            throw new IllegalArgumentException(
                    "Preview width and height must exceed one pixel");
        }
        if (samplesPerAnchorArc < MINIMUM_SAMPLES_PER_ANCHOR_ARC
                || samplesPerAnchorArc > MAXIMUM_SAMPLES_PER_ANCHOR_ARC) {
            throw new IllegalArgumentException(
                    "Outline controls per quarter must be between "
                            + MINIMUM_SAMPLES_PER_ANCHOR_ARC + " and "
                            + MAXIMUM_SAMPLES_PER_ANCHOR_ARC);
        }
        final ValidatedInput input = validatedInput(
                orderedAtlasLoop, orderedTissueLoop, orderedAnchors);
        final double diagonal = Math.hypot(
                (double) previewWidth, (double) previewHeight);
        final MatchedControls matched = matchAnchoredArcs(
                input.atlas(), input.tissue(),
                input.anchors().stream().mapToInt(
                        AnchorPair::atlasVertexIndex).toArray(),
                input.anchors().stream().mapToInt(
                        AnchorPair::tissueVertexIndex).toArray(),
                input.atlasDirection(), input.tissueDirection(),
                samplesPerAnchorArc, diagonal);
        return fitMatchedControls(
                input, previewWidth, previewHeight,
                matched.sources(), matched.targets(),
                false, samplesPerAnchorArc,
                matched.sources().size());
    }

    /**
     * Fits a strict reviewer-controlled outline warp by deterministically
     * enriching a dense monotonic correspondence table at the worst remaining
     * boundary residual. Each tentative enrichment must pass every ordinary
     * topology and distortion gate. The returned candidate is the safe fit
     * with the best boundary adherence, not necessarily the densest fit.
     *
     * <p>The frozen quality gate is p95 &le; max(2 preview pixels, 0.25% of
     * the preview diagonal) and maximum &le; max(5 preview pixels, 0.75% of
     * the preview diagonal). If no safe candidate meets both limits, this
     * method fails closed.</p>
     */
    public static ManualOutlineWarp2D fitStrict(
            final List<Point2D> orderedAtlasLoop,
            final List<Point2D> orderedTissueLoop,
            final List<AnchorPair> orderedAnchors,
            final int previewWidth,
            final int previewHeight) {
        if (previewWidth <= 1 || previewHeight <= 1) {
            throw new IllegalArgumentException(
                    "Preview width and height must exceed one pixel");
        }
        final ValidatedInput input = validatedInput(
                orderedAtlasLoop, orderedTissueLoop, orderedAnchors);
        final double diagonal = Math.hypot(
                (double) previewWidth, (double) previewHeight);
        final MatchedControls dense = matchAnchoredArcs(
                input.atlas(), input.tissue(),
                input.anchors().stream().mapToInt(
                        AnchorPair::atlasVertexIndex).toArray(),
                input.anchors().stream().mapToInt(
                        AnchorPair::tissueVertexIndex).toArray(),
                input.atlasDirection(), input.tissueDirection(),
                ADAPTIVE_CORRESPONDENCES_PER_ANCHOR_ARC, diagonal);
        ManualOutlineWarp2D best = null;
        IllegalArgumentException lastFailure = null;
        for (int seedPerArc = SAMPLES_PER_ANCHOR_ARC;
                seedPerArc >= MINIMUM_SAMPLES_PER_ANCHOR_ARC;
                seedPerArc--) {
            try {
                final ManualOutlineWarp2D candidate = adaptStrictControls(
                        input, previewWidth, previewHeight,
                        dense, seedPerArc);
                if (betterBoundaryAdherence(candidate, best)) {
                    best = candidate;
                }
            } catch (final IllegalArgumentException failure) {
                lastFailure = failure;
            }
        }
        if (best == null) {
            throw new IllegalArgumentException(
                    "No topology-safe adaptive manual outline warp exists",
                    lastFailure);
        }
        requireStrictBoundaryQuality(best.diagnostics(), diagonal);
        return best;
    }

    private static ManualOutlineWarp2D adaptStrictControls(
            final ValidatedInput input,
            final int previewWidth,
            final int previewHeight,
            final MatchedControls dense,
            final int seedPerArc) {
        final boolean[] selected = adaptiveSeedSelection(seedPerArc);
        ManualOutlineWarp2D current = fitSelectedDenseControls(
                input, previewWidth, previewHeight,
                dense, selected, seedPerArc);
        ManualOutlineWarp2D best = current;
        while (selectedCount(selected) < MAXIMUM_ADAPTIVE_CONTROL_COUNT) {
            final List<Integer> residualOrder = unselectedResidualOrder(
                    current, dense, selected);
            ManualOutlineWarp2D accepted = null;
            int acceptedIndex = -1;
            for (final int candidateIndex : residualOrder) {
                selected[candidateIndex] = true;
                try {
                    accepted = fitSelectedDenseControls(
                            input, previewWidth, previewHeight,
                            dense, selected, seedPerArc);
                    acceptedIndex = candidateIndex;
                    break;
                } catch (final IllegalArgumentException unsafe) {
                    selected[candidateIndex] = false;
                }
            }
            if (accepted == null) {
                break;
            }
            selected[acceptedIndex] = true;
            current = accepted;
            if (betterBoundaryAdherence(current, best)) {
                best = current;
            }
            if (strictBoundaryQualitySatisfied(best.diagnostics(),
                    Math.hypot(previewWidth, previewHeight))) {
                break;
            }
        }
        return best;
    }

    private static boolean[] adaptiveSeedSelection(final int seedPerArc) {
        final int densePerArc = ADAPTIVE_CORRESPONDENCES_PER_ANCHOR_ARC;
        final boolean[] selected = new boolean[
                REQUIRED_ANCHOR_COUNT * densePerArc];
        for (int arc = 0; arc < REQUIRED_ANCHOR_COUNT; arc++) {
            for (int sample = 0; sample < seedPerArc; sample++) {
                final int local = (int) Math.floor(
                        sample * (double) densePerArc / seedPerArc);
                selected[arc * densePerArc + local] = true;
            }
        }
        return selected;
    }

    private static ManualOutlineWarp2D fitSelectedDenseControls(
            final ValidatedInput input,
            final int previewWidth,
            final int previewHeight,
            final MatchedControls dense,
            final boolean[] selected,
            final int seedPerArc) {
        final List<Point2D> sources = new ArrayList<>();
        final List<Point2D> targets = new ArrayList<>();
        for (int index = 0; index < selected.length; index++) {
            if (selected[index]) {
                sources.add(dense.sources().get(index));
                targets.add(dense.targets().get(index));
            }
        }
        return fitMatchedControls(
                input, previewWidth, previewHeight, sources, targets,
                true, seedPerArc, dense.sources().size());
    }

    private static List<Integer> unselectedResidualOrder(
            final ManualOutlineWarp2D warp,
            final MatchedControls dense,
            final boolean[] selected) {
        final List<Integer> order = new ArrayList<>();
        for (int index = 0; index < selected.length; index++) {
            if (!selected[index]) {
                order.add(index);
            }
        }
        order.sort((first, second) -> {
            final double firstResidual = distance(
                    warp.apply(dense.sources().get(first)),
                    dense.targets().get(first));
            final double secondResidual = distance(
                    warp.apply(dense.sources().get(second)),
                    dense.targets().get(second));
            final int byResidual = Double.compare(
                    secondResidual, firstResidual);
            return byResidual != 0 ? byResidual
                    : Integer.compare(first, second);
        });
        return List.copyOf(order);
    }

    private static int selectedCount(final boolean[] selected) {
        int count = 0;
        for (final boolean value : selected) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static boolean betterBoundaryAdherence(
            final ManualOutlineWarp2D candidate,
            final ManualOutlineWarp2D currentBest) {
        if (currentBest == null) {
            return true;
        }
        final Diagnostics candidateDiagnostics = candidate.diagnostics();
        final Diagnostics bestDiagnostics = currentBest.diagnostics();
        final int byP95 = Double.compare(
                candidateDiagnostics.boundaryDistanceP95(),
                bestDiagnostics.boundaryDistanceP95());
        if (byP95 != 0) {
            return byP95 < 0;
        }
        final int byMaximum = Double.compare(
                candidateDiagnostics.maximumBoundaryDistance(),
                bestDiagnostics.maximumBoundaryDistance());
        if (byMaximum != 0) {
            return byMaximum < 0;
        }
        final int byMean = Double.compare(
                candidateDiagnostics.boundaryDistanceMean(),
                bestDiagnostics.boundaryDistanceMean());
        if (byMean != 0) {
            return byMean < 0;
        }
        return candidateDiagnostics.controlPairCount()
                < bestDiagnostics.controlPairCount();
    }

    private static boolean strictBoundaryQualitySatisfied(
            final Diagnostics diagnostics,
            final double diagonal) {
        return diagnostics.boundaryDistanceP95()
                        <= Math.max(STRICT_BOUNDARY_P95_MINIMUM_PIXELS,
                                STRICT_BOUNDARY_P95_DIAGONAL_FRACTION
                                        * diagonal)
                && diagnostics.maximumBoundaryDistance()
                        <= Math.max(
                                STRICT_BOUNDARY_MAXIMUM_MINIMUM_PIXELS,
                                STRICT_BOUNDARY_MAXIMUM_DIAGONAL_FRACTION
                                        * diagonal);
    }

    private static void requireStrictBoundaryQuality(
            final Diagnostics diagnostics,
            final double diagonal) {
        if (!strictBoundaryQualitySatisfied(diagnostics, diagonal)) {
            throw new IllegalArgumentException(
                    "No topology-safe adaptive warp met the strict boundary gate: "
                            + "p95=" + diagnostics.boundaryDistanceP95()
                            + ", allowed="
                            + Math.max(STRICT_BOUNDARY_P95_MINIMUM_PIXELS,
                                    STRICT_BOUNDARY_P95_DIAGONAL_FRACTION
                                            * diagonal)
                            + ", maximum="
                            + diagnostics.maximumBoundaryDistance()
                            + ", allowed maximum="
                            + Math.max(
                                    STRICT_BOUNDARY_MAXIMUM_MINIMUM_PIXELS,
                                    STRICT_BOUNDARY_MAXIMUM_DIAGONAL_FRACTION
                                            * diagonal));
        }
    }

    private static ManualOutlineWarp2D fitMatchedControls(
            final ValidatedInput input,
            final int previewWidth,
            final int previewHeight,
            final List<Point2D> sources,
            final List<Point2D> targets,
            final boolean strictBoundaryQualityEnforced,
            final int seedSamplesPerAnchorArc,
            final int denseCorrespondenceCount) {
        final List<Point2D> atlas = input.atlas();
        final List<Point2D> tissue = input.tissue();
        final List<AnchorPair> anchors = input.anchors();
        final int atlasDirection = input.atlasDirection();
        final int tissueDirection = input.tissueDirection();
        final double diagonal = Math.hypot(
                (double) previewWidth, (double) previewHeight);
        requireControlCoverage(sources, previewWidth, previewHeight);
        final double maximumRequest =
                MAXIMUM_REQUESTED_DISPLACEMENT_FRACTION * diagonal;
        final double[] requestedX = new double[sources.size()];
        final double[] requestedY = new double[sources.size()];
        double maximumRequestedDisplacement = 0;
        for (int index = 0; index < sources.size(); index++) {
            requestedX[index] = targets.get(index).x()
                    - sources.get(index).x();
            requestedY[index] = targets.get(index).y()
                    - sources.get(index).y();
            final double magnitude = Math.hypot(
                    requestedX[index], requestedY[index]);
            if (!Double.isFinite(magnitude) || magnitude > maximumRequest) {
                throw new IllegalArgumentException(
                        "Each outline displacement must be at most 25% of the preview diagonal");
            }
            maximumRequestedDisplacement = Math.max(
                    maximumRequestedDisplacement, magnitude);
        }

        final double supportRadius = SUPPORT_RADIUS_FRACTION * diagonal;
        final double[][] system = kernelSystem(sources, supportRadius);
        final double[][] solved = solve(system, requestedX, requestedY);
        final double[] xWeights = solved[0];
        final double[] yWeights = solved[1];
        final MutableDiagnostics sampled = sampleSafety(
                sources, xWeights, yWeights, supportRadius,
                previewWidth, previewHeight, atlas);
        rejectUnsafeSampledGeometry(sampled, diagonal);

        final double[] residuals = controlResiduals(
                sources, targets, xWeights, yWeights, supportRadius);
        if (residuals[1] > MAXIMUM_CONTROL_RESIDUAL_FRACTION * diagonal) {
            throw new IllegalArgumentException(
                    "Outline control residual exceeds 1% of the preview diagonal");
        }
        final WarpFunction function = new WarpFunction(
                sources, xWeights, yWeights, supportRadius);
        final List<Point2D> warpedAtlasLoop = atlas.stream()
                .map(function::apply).toList();
        requireSimpleLoop(warpedAtlasLoop, "warped atlas exterior");
        final BoundaryDistances boundaryDistances = boundaryDistances(
                warpedAtlasLoop, tissue);
        requireNoGridCrossings(function, previewWidth, previewHeight);
        final double maximumRoundTripError = requireInverseRoundTrips(
                function, previewWidth, previewHeight, atlas);

        final String hash = contentHash(
                atlas, tissue, anchors, atlasDirection, tissueDirection,
                previewWidth, previewHeight, sources, targets,
                supportRadius, xWeights, yWeights,
                strictBoundaryQualityEnforced,
                seedSamplesPerAnchorArc, denseCorrespondenceCount);
        final Diagnostics diagnostics = new Diagnostics(
                ALGORITHM_REVISION, PIXEL_CENTER_CONVENTION,
                previewWidth, previewHeight, sources.size(),
                strictBoundaryQualityEnforced,
                seedSamplesPerAnchorArc, denseCorrespondenceCount,
                atlasDirection, tissueDirection, supportRadius,
                REGULARIZATION, maximumRequestedDisplacement,
                sampled.maximumDisplacement, sampled.minimumDeterminant,
                sampled.minimumSingularValue,
                sampled.maximumSingularValue,
                sampled.maximumAnisotropy,
                residuals[0], residuals[1],
                boundaryDistances.mean(), boundaryDistances.p95(),
                boundaryDistances.maximum(),
                maximumRoundTripError, hash);
        return new ManualOutlineWarp2D(
                atlas, tissue, anchors, sources, targets,
                xWeights, yWeights, supportRadius, diagnostics);
    }

    /** Applies the forward preview-pixel to preview-pixel deformation. */
    public Point2D apply(final Point2D previewPixelCenter) {
        Objects.requireNonNull(previewPixelCenter, "previewPixelCenter");
        return new WarpFunction(sourceControlPoints, xWeights, yWeights,
                supportRadius).apply(previewPixelCenter);
    }

    /** Returns the analytic Jacobian of the forward deformation. */
    public Jacobian2D jacobian(final Point2D previewPixelCenter) {
        Objects.requireNonNull(previewPixelCenter, "previewPixelCenter");
        return jacobian(previewPixelCenter, sourceControlPoints,
                xWeights, yWeights, supportRadius);
    }

    /** Inverts one warped preview coordinate with bounded damped Newton steps. */
    public Point2D inverse(final Point2D warpedPreviewPixelCenter) {
        return inverse(Objects.requireNonNull(
                warpedPreviewPixelCenter, "warpedPreviewPixelCenter"),
                sourceControlPoints, xWeights, yWeights, supportRadius);
    }

    public CoordinateSpace2D sourceSpace() {
        return CoordinateSpace2D.PREVIEW_PIXEL;
    }

    public CoordinateSpace2D destinationSpace() {
        return CoordinateSpace2D.PREVIEW_PIXEL;
    }

    public String algorithmRevision() {
        return diagnostics.algorithmRevision();
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
    public String contentSha256() {
        return diagnostics.contentSha256();
    }

    @Override
    public MidlineEndpoints hemisphereMidline() {
        if (anchors.size() != REQUIRED_ANCHOR_COUNT) {
            throw new IllegalStateException(
                    "Legacy outline warp lacks its four semantic anchors");
        }
        final Point2D dorsal = apply(atlasLoop.get(
                anchors.get(0).atlasVertexIndex()));
        final Point2D ventral = apply(atlasLoop.get(
                anchors.get(2).atlasVertexIndex()));
        return new MidlineEndpoints(dorsal, ventral);
    }

    public List<Point2D> atlasLoop() {
        return atlasLoop;
    }

    public List<Point2D> tissueLoop() {
        return tissueLoop;
    }

    public List<AnchorPair> anchors() {
        return anchors;
    }

    public List<Point2D> sourceControlPoints() {
        return sourceControlPoints;
    }

    public List<Point2D> targetControlPoints() {
        return targetControlPoints;
    }

    public List<Double> xWeights() {
        return immutableXWeights;
    }

    public List<Double> yWeights() {
        return immutableYWeights;
    }

    public double supportRadius() {
        return supportRadius;
    }

    public Diagnostics diagnostics() {
        return diagnostics;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ManualOutlineWarp2D warp)) {
            return false;
        }
        return atlasLoop.equals(warp.atlasLoop)
                && tissueLoop.equals(warp.tissueLoop)
                && anchors.equals(warp.anchors)
                && sourceControlPoints.equals(warp.sourceControlPoints)
                && targetControlPoints.equals(warp.targetControlPoints)
                && immutableXWeights.equals(warp.immutableXWeights)
                && immutableYWeights.equals(warp.immutableYWeights)
                && Double.doubleToLongBits(supportRadius)
                == Double.doubleToLongBits(warp.supportRadius)
                && diagnostics.equals(warp.diagnostics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(atlasLoop, tissueLoop, anchors,
                sourceControlPoints, targetControlPoints,
                immutableXWeights, immutableYWeights,
                supportRadius, diagnostics);
    }

    private static List<Point2D> validatedSimpleLoop(
            final List<Point2D> points,
            final String name) {
        final List<Point2D> checked = List.copyOf(
                Objects.requireNonNull(points, name));
        if (checked.size() < REQUIRED_ANCHOR_COUNT) {
            throw new IllegalArgumentException(
                    name + " requires at least four vertices");
        }
        final Set<Point2D> unique = new HashSet<>();
        for (final Point2D point : checked) {
            Objects.requireNonNull(point, name + " point");
            if (!unique.add(point)) {
                throw new IllegalArgumentException(
                        name + " must not repeat vertices or its first endpoint");
            }
        }
        requireSimpleLoop(checked, name);
        return checked;
    }

    private static ValidatedInput validatedInput(
            final List<Point2D> orderedAtlasLoop,
            final List<Point2D> orderedTissueLoop,
            final List<AnchorPair> orderedAnchors) {
        final List<Point2D> atlas = validatedSimpleLoop(
                orderedAtlasLoop, "atlas exterior");
        final List<Point2D> tissue = validatedSimpleLoop(
                orderedTissueLoop, "tissue exterior");
        final List<AnchorPair> anchors = validatedAnchors(
                orderedAnchors, atlas.size(), tissue.size());
        final int atlasDirection = cyclicDirection(
                anchors.stream().mapToInt(
                        AnchorPair::atlasVertexIndex).toArray(),
                atlas.size(), "atlas anchor");
        final int tissueDirection = cyclicDirection(
                anchors.stream().mapToInt(
                        AnchorPair::tissueVertexIndex).toArray(),
                tissue.size(), "tissue anchor");
        return new ValidatedInput(
                atlas, tissue, anchors, atlasDirection, tissueDirection);
    }

    private static void requireSimpleLoop(
            final List<Point2D> loop,
            final String name) {
        if (Math.abs(signedArea(loop)) <= GEOMETRY_EPSILON) {
            throw new IllegalArgumentException(
                    name + " must enclose nonzero area");
        }
        final int count = loop.size();
        for (int first = 0; first < count; first++) {
            final Point2D firstA = loop.get(first);
            final Point2D firstB = loop.get((first + 1) % count);
            if (firstA.equals(firstB)) {
                throw new IllegalArgumentException(
                        name + " contains a zero-length edge");
            }
            for (int second = first + 1; second < count; second++) {
                if (adjacentEdges(first, second, count)) {
                    continue;
                }
                final Point2D secondA = loop.get(second);
                final Point2D secondB = loop.get((second + 1) % count);
                if (segmentsIntersect(firstA, firstB, secondA, secondB)) {
                    throw new IllegalArgumentException(
                            name + " must not self-intersect");
                }
            }
        }
    }

    private static List<AnchorPair> validatedAnchors(
            final List<AnchorPair> values,
            final int atlasSize,
            final int tissueSize) {
        final List<AnchorPair> checked = List.copyOf(
                Objects.requireNonNull(values, "orderedAnchors"));
        if (checked.size() != REQUIRED_ANCHOR_COUNT) {
            throw new IllegalArgumentException(
                    "Exactly four semantic outline anchors are required");
        }
        final Set<String> names = new HashSet<>();
        final Set<Integer> atlasIndices = new HashSet<>();
        final Set<Integer> tissueIndices = new HashSet<>();
        for (final AnchorPair anchor : checked) {
            Objects.requireNonNull(anchor, "anchor");
            if (!names.add(anchor.name())
                    || !atlasIndices.add(anchor.atlasVertexIndex())
                    || !tissueIndices.add(anchor.tissueVertexIndex())) {
                throw new IllegalArgumentException(
                        "Anchor names and loop indices must be unique");
            }
            if (anchor.atlasVertexIndex() >= atlasSize
                    || anchor.tissueVertexIndex() >= tissueSize) {
                throw new IllegalArgumentException(
                        "Anchor index lies outside its outline");
            }
        }
        return checked;
    }

    private static int cyclicDirection(
            final int[] indices,
            final int loopSize,
            final String name) {
        if (strictlyIncreasingCyclicOffsets(indices, loopSize, 1)) {
            return 1;
        }
        if (strictlyIncreasingCyclicOffsets(indices, loopSize, -1)) {
            return -1;
        }
        throw new IllegalArgumentException(
                name + " pairs must have one unambiguous cyclic order");
    }

    private static boolean strictlyIncreasingCyclicOffsets(
            final int[] indices,
            final int loopSize,
            final int direction) {
        int previous = -1;
        for (int index = 0; index < indices.length; index++) {
            final int offset = Math.floorMod(
                    direction * (indices[index] - indices[0]), loopSize);
            if (offset <= previous) {
                return false;
            }
            previous = offset;
        }
        return true;
    }

    private static MatchedControls matchAnchoredArcs(
            final List<Point2D> atlas,
            final List<Point2D> tissue,
            final int[] atlasAnchorIndices,
            final int[] tissueAnchorIndices,
            final int atlasDirection,
            final int tissueDirection,
            final int samplesPerAnchorArc,
            final double previewDiagonal) {
        final List<Point2D> sources = new ArrayList<>(
                REQUIRED_ANCHOR_COUNT * samplesPerAnchorArc);
        final List<Point2D> targets = new ArrayList<>(
                REQUIRED_ANCHOR_COUNT * samplesPerAnchorArc);
        for (int anchor = 0; anchor < REQUIRED_ANCHOR_COUNT; anchor++) {
            final int atlasStart = atlasAnchorIndices[anchor];
            final int atlasEnd = atlasAnchorIndices[
                    (anchor + 1) % REQUIRED_ANCHOR_COUNT];
            final int tissueStart = tissueAnchorIndices[anchor];
            final int tissueEnd = tissueAnchorIndices[
                    (anchor + 1) % REQUIRED_ANCHOR_COUNT];
            final List<Point2D> atlasArc = arcVertices(
                    atlas, atlasStart, atlasEnd, atlasDirection);
            final List<Point2D> tissueArc = arcVertices(
                    tissue, tissueStart, tissueEnd, tissueDirection);
            final double[] atlasCumulative = positiveCumulativeLengths(
                    atlasArc);
            final double[] tissueCumulative = positiveCumulativeLengths(
                    tissueArc);
            final List<Point2D> sourceArc = new ArrayList<>(
                    samplesPerAnchorArc + 1);
            for (int sample = 0;
                    sample <= samplesPerAnchorArc; sample++) {
                sourceArc.add(interpolateAt(
                        atlasArc, atlasCumulative,
                        atlasCumulative[atlasCumulative.length - 1]
                                * sample / samplesPerAnchorArc));
            }
            final List<Point2D> targetCandidates = new ArrayList<>(
                    MATCH_CANDIDATES_PER_ANCHOR_ARC + 1);
            for (int candidate = 0;
                    candidate <= MATCH_CANDIDATES_PER_ANCHOR_ARC;
                    candidate++) {
                targetCandidates.add(interpolateAt(
                        tissueArc, tissueCumulative,
                        tissueCumulative[tissueCumulative.length - 1]
                                * candidate
                                / MATCH_CANDIDATES_PER_ANCHOR_ARC));
            }
            final int[] matchedTargetIndices = monotonicTargetIndices(
                    sourceArc, targetCandidates,
                    samplesPerAnchorArc, previewDiagonal);
            for (int sample = 0;
                    sample < samplesPerAnchorArc; sample++) {
                sources.add(sourceArc.get(sample));
                targets.add(targetCandidates.get(
                        matchedTargetIndices[sample]));
            }
        }
        return new MatchedControls(
                List.copyOf(sources), List.copyOf(targets));
    }

    private static double[] positiveCumulativeLengths(
            final List<Point2D> arc) {
        final double[] cumulative = cumulativeLengths(arc);
        final double total = cumulative[cumulative.length - 1];
        if (!(total > 0) || !Double.isFinite(total)) {
            throw new IllegalArgumentException(
                    "Every anchor-to-anchor outline arc must have positive length");
        }
        return cumulative;
    }

    /**
     * Chooses one strictly ordered tissue-arc candidate per source control.
     * The semantic endpoints remain exact. Interior choices minimize shape
     * residual after applying the displacement interpolated between the two
     * endpoint anchors. Consequently a shared translation is not mistaken
     * for along-arc shape mismatch. Targets remain original tissue points,
     * and a small penalty discourages extreme arc-parameter drift.
     */
    private static int[] monotonicTargetIndices(
            final List<Point2D> sourceArc,
            final List<Point2D> targetCandidates,
            final int samplesPerAnchorArc,
            final double previewDiagonal) {
        final int finalCandidate = targetCandidates.size() - 1;
        final double[][] costs = new double[
                samplesPerAnchorArc + 1][targetCandidates.size()];
        final int[][] previous = new int[
                samplesPerAnchorArc + 1][targetCandidates.size()];
        for (int sample = 0; sample < costs.length; sample++) {
            Arrays.fill(costs[sample], Double.POSITIVE_INFINITY);
            Arrays.fill(previous[sample], -1);
        }
        costs[0][0] = 0;
        final double diagonalSquared = previewDiagonal * previewDiagonal;
        final Point2D sourceStart = sourceArc.get(0);
        final Point2D sourceEnd = sourceArc.get(samplesPerAnchorArc);
        final Point2D targetStart = targetCandidates.get(0);
        final Point2D targetEnd = targetCandidates.get(finalCandidate);
        for (int sample = 1;
                sample < samplesPerAnchorArc; sample++) {
            final int minimumCandidate = sample;
            final int maximumCandidate = finalCandidate
                    - (samplesPerAnchorArc - sample);
            double bestPreviousCost = Double.POSITIVE_INFINITY;
            int bestPreviousIndex = -1;
            for (int candidate = minimumCandidate;
                    candidate <= maximumCandidate; candidate++) {
                final int possiblePrevious = candidate - 1;
                if (costs[sample - 1][possiblePrevious]
                        < bestPreviousCost) {
                    bestPreviousCost = costs[sample - 1][possiblePrevious];
                    bestPreviousIndex = possiblePrevious;
                }
                if (!Double.isFinite(bestPreviousCost)) {
                    continue;
                }
                final double parameter = candidate
                        / (double) finalCandidate;
                final double expectedParameter = sample
                        / (double) samplesPerAnchorArc;
                final Point2D expectedFromAnchorBaseline =
                        addInterpolatedEndpointDisplacement(
                                sourceArc.get(sample), sourceStart, sourceEnd,
                                targetStart, targetEnd, expectedParameter);
                final double geometryCost = squaredDistance(
                        expectedFromAnchorBaseline,
                        targetCandidates.get(candidate))
                        / diagonalSquared;
                final double parameterDifference = parameter
                        - expectedParameter;
                costs[sample][candidate] = bestPreviousCost
                        + geometryCost
                        + MATCH_PARAMETER_REGULARIZATION
                                * parameterDifference
                                * parameterDifference;
                previous[sample][candidate] = bestPreviousIndex;
            }
        }
        double bestFinalCost = Double.POSITIVE_INFINITY;
        int bestFinalPrevious = -1;
        for (int candidate = samplesPerAnchorArc - 1;
                candidate < finalCandidate; candidate++) {
            if (costs[samplesPerAnchorArc - 1][candidate]
                    < bestFinalCost) {
                bestFinalCost = costs[
                        samplesPerAnchorArc - 1][candidate];
                bestFinalPrevious = candidate;
            }
        }
        if (!Double.isFinite(bestFinalCost) || bestFinalPrevious < 0) {
            throw new IllegalArgumentException(
                    "Outline arcs do not admit a monotonic geometric match");
        }
        costs[samplesPerAnchorArc][finalCandidate] = bestFinalCost;
        previous[samplesPerAnchorArc][finalCandidate] = bestFinalPrevious;
        final int[] result = new int[samplesPerAnchorArc + 1];
        result[samplesPerAnchorArc] = finalCandidate;
        for (int sample = samplesPerAnchorArc;
                sample > 0; sample--) {
            result[sample - 1] = previous[sample][result[sample]];
        }
        if (result[0] != 0) {
            throw new IllegalArgumentException(
                    "Monotonic outline match did not preserve its semantic anchor");
        }
        return result;
    }

    private static Point2D addInterpolatedEndpointDisplacement(
            final Point2D source,
            final Point2D sourceStart,
            final Point2D sourceEnd,
            final Point2D targetStart,
            final Point2D targetEnd,
            final double parameter) {
        final double startDeltaX = targetStart.x() - sourceStart.x();
        final double startDeltaY = targetStart.y() - sourceStart.y();
        final double endDeltaX = targetEnd.x() - sourceEnd.x();
        final double endDeltaY = targetEnd.y() - sourceEnd.y();
        return new Point2D(
                source.x() + startDeltaX
                        + parameter * (endDeltaX - startDeltaX),
                source.y() + startDeltaY
                        + parameter * (endDeltaY - startDeltaY));
    }

    private static List<Point2D> arcVertices(
            final List<Point2D> loop,
            final int start,
            final int end,
            final int direction) {
        final List<Point2D> arc = new ArrayList<>();
        int index = start;
        arc.add(loop.get(index));
        while (index != end) {
            index = Math.floorMod(index + direction, loop.size());
            arc.add(loop.get(index));
            if (arc.size() > loop.size() + 1) {
                throw new IllegalArgumentException(
                        "Anchor traversal did not terminate");
            }
        }
        return arc;
    }

    private static double[] cumulativeLengths(final List<Point2D> arc) {
        final double[] cumulative = new double[arc.size()];
        for (int index = 1; index < arc.size(); index++) {
            cumulative[index] = cumulative[index - 1] + Math.hypot(
                    arc.get(index).x() - arc.get(index - 1).x(),
                    arc.get(index).y() - arc.get(index - 1).y());
        }
        return cumulative;
    }

    private static Point2D interpolateAt(
            final List<Point2D> arc,
            final double[] cumulative,
            final double distance) {
        for (int index = 1; index < cumulative.length; index++) {
            if (distance <= cumulative[index]) {
                final double segment = cumulative[index]
                        - cumulative[index - 1];
                final double fraction = segment == 0 ? 0
                        : (distance - cumulative[index - 1]) / segment;
                return interpolate(arc.get(index - 1), arc.get(index),
                        fraction);
            }
        }
        return arc.get(arc.size() - 1);
    }

    private static Point2D interpolate(
            final Point2D from,
            final Point2D to,
            final double fraction) {
        return new Point2D(
                from.x() + fraction * (to.x() - from.x()),
                from.y() + fraction * (to.y() - from.y()));
    }

    private static void requireControlCoverage(
            final List<Point2D> sources,
            final int width,
            final int height) {
        if (convexHullArea(sources)
                < MINIMUM_HULL_AREA_FRACTION * width * (double) height) {
            throw new IllegalArgumentException(
                    "Outline controls must cover at least 1% of the preview area");
        }
    }

    private static double[][] kernelSystem(
            final List<Point2D> sources,
            final double supportRadius) {
        final double[][] system = new double[sources.size()][sources.size()];
        for (int row = 0; row < sources.size(); row++) {
            for (int column = 0; column < sources.size(); column++) {
                final double normalized = distance(
                        sources.get(row), sources.get(column)) / supportRadius;
                system[row][column] = wendlandC2(normalized)
                        + (row == column ? REGULARIZATION : 0);
            }
        }
        return system;
    }

    private static double[][] solve(
            final double[][] input,
            final double[] requestedX,
            final double[] requestedY) {
        final int count = input.length;
        final double[][] matrix = new double[count][count];
        for (int row = 0; row < count; row++) {
            matrix[row] = input[row].clone();
        }
        final double[][] right = new double[count][2];
        for (int row = 0; row < count; row++) {
            right[row][0] = requestedX[row];
            right[row][1] = requestedY[row];
        }
        for (int pivotColumn = 0; pivotColumn < count; pivotColumn++) {
            int pivotRow = pivotColumn;
            double pivotMagnitude = Math.abs(
                    matrix[pivotColumn][pivotColumn]);
            for (int row = pivotColumn + 1; row < count; row++) {
                final double candidate = Math.abs(matrix[row][pivotColumn]);
                if (candidate > pivotMagnitude) {
                    pivotMagnitude = candidate;
                    pivotRow = row;
                }
            }
            if (!Double.isFinite(pivotMagnitude)
                    || pivotMagnitude <= MINIMUM_SOLVER_PIVOT) {
                throw new IllegalArgumentException(
                        "Outline-warp kernel system is singular");
            }
            if (pivotRow != pivotColumn) {
                final double[] matrixSwap = matrix[pivotColumn];
                matrix[pivotColumn] = matrix[pivotRow];
                matrix[pivotRow] = matrixSwap;
                final double[] rightSwap = right[pivotColumn];
                right[pivotColumn] = right[pivotRow];
                right[pivotRow] = rightSwap;
            }
            final double pivot = matrix[pivotColumn][pivotColumn];
            for (int row = pivotColumn + 1; row < count; row++) {
                final double factor = matrix[row][pivotColumn] / pivot;
                matrix[row][pivotColumn] = 0;
                for (int column = pivotColumn + 1;
                        column < count; column++) {
                    matrix[row][column] -= factor
                            * matrix[pivotColumn][column];
                }
                right[row][0] -= factor * right[pivotColumn][0];
                right[row][1] -= factor * right[pivotColumn][1];
            }
        }
        final double[][] result = new double[2][count];
        for (int row = count - 1; row >= 0; row--) {
            final double pivot = matrix[row][row];
            if (!Double.isFinite(pivot)
                    || Math.abs(pivot) <= MINIMUM_SOLVER_PIVOT) {
                throw new IllegalArgumentException(
                        "Outline-warp kernel system is singular");
            }
            for (int component = 0; component < 2; component++) {
                double value = right[row][component];
                for (int column = row + 1; column < count; column++) {
                    value -= matrix[row][column] * result[component][column];
                }
                result[component][row] = value / pivot;
                if (!Double.isFinite(result[component][row])) {
                    throw new IllegalArgumentException(
                            "Outline-warp weights must be finite");
                }
            }
        }
        return result;
    }

    private static double[] displacement(
            final Point2D point,
            final List<Point2D> sources,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius) {
        double x = 0;
        double y = 0;
        for (int index = 0; index < sources.size(); index++) {
            final double kernel = wendlandC2(
                    distance(point, sources.get(index)) / supportRadius);
            x += xWeights[index] * kernel;
            y += yWeights[index] * kernel;
        }
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException(
                    "Outline-warp displacement must be finite");
        }
        return new double[]{x, y};
    }

    private static Jacobian2D jacobian(
            final Point2D point,
            final List<Point2D> sources,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius) {
        double dXdx = 0;
        double dXdy = 0;
        double dYdx = 0;
        double dYdy = 0;
        final double inverseRadiusSquared =
                1.0 / (supportRadius * supportRadius);
        for (int index = 0; index < sources.size(); index++) {
            final double deltaX = point.x() - sources.get(index).x();
            final double deltaY = point.y() - sources.get(index).y();
            final double normalized = Math.hypot(deltaX, deltaY)
                    / supportRadius;
            if (!(normalized > 0 && normalized < 1)) {
                continue;
            }
            final double oneMinus = 1 - normalized;
            final double gradientFactor = -20 * oneMinus * oneMinus
                    * oneMinus * inverseRadiusSquared;
            final double gradientX = gradientFactor * deltaX;
            final double gradientY = gradientFactor * deltaY;
            dXdx += xWeights[index] * gradientX;
            dXdy += xWeights[index] * gradientY;
            dYdx += yWeights[index] * gradientX;
            dYdy += yWeights[index] * gradientY;
        }
        return new Jacobian2D(
                1 + dXdx, dXdy, dYdx, 1 + dYdy);
    }

    private static double wendlandC2(final double normalizedDistance) {
        if (!(normalizedDistance < 1)) {
            return 0;
        }
        if (normalizedDistance < 0 || !Double.isFinite(normalizedDistance)) {
            throw new IllegalArgumentException(
                    "Normalized outline-kernel distance must be finite and nonnegative");
        }
        final double oneMinus = 1 - normalizedDistance;
        final double square = oneMinus * oneMinus;
        return square * square * (4 * normalizedDistance + 1);
    }

    private static MutableDiagnostics sampleSafety(
            final List<Point2D> sources,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius,
            final int width,
            final int height,
            final List<Point2D> sourceBoundary) {
        final MutableDiagnostics result = new MutableDiagnostics();
        for (int yIndex = 0; yIndex < SAFETY_GRID_SIZE; yIndex++) {
            final double y = (height - 1.0) * yIndex
                    / (SAFETY_GRID_SIZE - 1.0);
            for (int xIndex = 0; xIndex < SAFETY_GRID_SIZE; xIndex++) {
                final double x = (width - 1.0) * xIndex
                        / (SAFETY_GRID_SIZE - 1.0);
                samplePoint(new Point2D(x, y), sources,
                        xWeights, yWeights, supportRadius, result);
            }
        }
        for (final Point2D source : sources) {
            samplePoint(source, sources,
                    xWeights, yWeights, supportRadius, result);
        }
        for (int index = 0; index < sourceBoundary.size(); index++) {
            final Point2D from = sourceBoundary.get(index);
            final Point2D to = sourceBoundary.get(
                    (index + 1) % sourceBoundary.size());
            samplePoint(from, sources,
                    xWeights, yWeights, supportRadius, result);
            samplePoint(interpolate(from, to, 0.5), sources,
                    xWeights, yWeights, supportRadius, result);
        }
        return result;
    }

    private static void samplePoint(
            final Point2D point,
            final List<Point2D> sources,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius,
            final MutableDiagnostics result) {
        final Jacobian2D jacobian = jacobian(
                point, sources, xWeights, yWeights, supportRadius);
        final SingularValues singular = singularValues(jacobian);
        result.minimumDeterminant = Math.min(
                result.minimumDeterminant, jacobian.determinant());
        result.minimumSingularValue = Math.min(
                result.minimumSingularValue, singular.minimum());
        result.maximumSingularValue = Math.max(
                result.maximumSingularValue, singular.maximum());
        result.maximumAnisotropy = Math.max(
                result.maximumAnisotropy, singular.anisotropy());
        final double[] displacement = displacement(
                point, sources, xWeights, yWeights, supportRadius);
        result.maximumDisplacement = Math.max(
                result.maximumDisplacement,
                Math.hypot(displacement[0], displacement[1]));
    }

    private static void rejectUnsafeSampledGeometry(
            final MutableDiagnostics diagnostics,
            final double diagonal) {
        if (!diagnostics.allFinite()
                || diagnostics.minimumDeterminant
                        < MINIMUM_JACOBIAN_DETERMINANT
                || diagnostics.minimumSingularValue < MINIMUM_SINGULAR_VALUE
                || diagnostics.maximumSingularValue > MAXIMUM_SINGULAR_VALUE
                || diagnostics.maximumAnisotropy > MAXIMUM_ANISOTROPY
                || diagnostics.maximumDisplacement
                        > MAXIMUM_SAMPLED_DISPLACEMENT_FRACTION * diagonal) {
            throw new IllegalArgumentException(
                    "Manual outline warp failed sampled topology and distortion gates: "
                            + "minimum determinant="
                            + diagnostics.minimumDeterminant
                            + ", minimum singular value="
                            + diagnostics.minimumSingularValue
                            + ", maximum singular value="
                            + diagnostics.maximumSingularValue
                            + ", maximum anisotropy="
                            + diagnostics.maximumAnisotropy
                            + ", maximum displacement="
                            + diagnostics.maximumDisplacement
                            + ", allowed displacement="
                            + (MAXIMUM_SAMPLED_DISPLACEMENT_FRACTION
                                    * diagonal));
        }
    }

    private static SingularValues singularValues(final Jacobian2D jacobian) {
        final double squaredFrobenius = jacobian.m00() * jacobian.m00()
                + jacobian.m01() * jacobian.m01()
                + jacobian.m10() * jacobian.m10()
                + jacobian.m11() * jacobian.m11();
        final double determinant = jacobian.determinant();
        final double discriminant = Math.max(0,
                squaredFrobenius * squaredFrobenius
                        - 4 * determinant * determinant);
        final double maximum = Math.sqrt(Math.max(0,
                0.5 * (squaredFrobenius + Math.sqrt(discriminant))));
        final double minimum = maximum == 0 ? 0
                : Math.abs(determinant) / maximum;
        return new SingularValues(
                minimum, maximum,
                minimum == 0 ? Double.POSITIVE_INFINITY
                        : maximum / minimum);
    }

    private static double[] controlResiduals(
            final List<Point2D> sources,
            final List<Point2D> targets,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius) {
        double squared = 0;
        double maximum = 0;
        for (int index = 0; index < sources.size(); index++) {
            final double[] shift = displacement(
                    sources.get(index), sources,
                    xWeights, yWeights, supportRadius);
            final double residual = Math.hypot(
                    sources.get(index).x() + shift[0]
                            - targets.get(index).x(),
                    sources.get(index).y() + shift[1]
                            - targets.get(index).y());
            squared += residual * residual;
            maximum = Math.max(maximum, residual);
        }
        return new double[]{Math.sqrt(squared / sources.size()), maximum};
    }

    private static void requireNoGridCrossings(
            final WarpFunction function,
            final int width,
            final int height) {
        final Point2D[][] grid = new Point2D[
                CROSSING_GRID_SIZE][CROSSING_GRID_SIZE];
        for (int yIndex = 0; yIndex < CROSSING_GRID_SIZE; yIndex++) {
            final double y = (height - 1.0) * yIndex
                    / (CROSSING_GRID_SIZE - 1.0);
            for (int xIndex = 0;
                    xIndex < CROSSING_GRID_SIZE; xIndex++) {
                final double x = (width - 1.0) * xIndex
                        / (CROSSING_GRID_SIZE - 1.0);
                grid[yIndex][xIndex] = function.apply(new Point2D(x, y));
            }
        }
        for (int y = 0; y + 1 < CROSSING_GRID_SIZE; y++) {
            for (int x = 0; x + 1 < CROSSING_GRID_SIZE; x++) {
                final Point2D topLeft = grid[y][x];
                final Point2D topRight = grid[y][x + 1];
                final Point2D bottomLeft = grid[y + 1][x];
                final Point2D bottomRight = grid[y + 1][x + 1];
                if (cross(topLeft, topRight, bottomRight)
                        <= GEOMETRY_EPSILON
                        || cross(topLeft, bottomRight, bottomLeft)
                        <= GEOMETRY_EPSILON) {
                    throw new IllegalArgumentException(
                            "Warped preview grid contains a folded cell");
                }
            }
        }
        final List<Segment> segments = new ArrayList<>();
        for (int y = 0; y < CROSSING_GRID_SIZE; y++) {
            for (int x = 0; x + 1 < CROSSING_GRID_SIZE; x++) {
                segments.add(new Segment(grid[y][x], grid[y][x + 1]));
            }
        }
        for (int x = 0; x < CROSSING_GRID_SIZE; x++) {
            for (int y = 0; y + 1 < CROSSING_GRID_SIZE; y++) {
                segments.add(new Segment(grid[y][x], grid[y + 1][x]));
            }
        }
        for (int first = 0; first < segments.size(); first++) {
            for (int second = first + 1; second < segments.size(); second++) {
                if (segments.get(first).sharesEndpoint(segments.get(second))) {
                    continue;
                }
                if (segmentsIntersect(
                        segments.get(first).from(),
                        segments.get(first).to(),
                        segments.get(second).from(),
                        segments.get(second).to())) {
                    throw new IllegalArgumentException(
                            "Warped preview grid contains crossing lines");
                }
            }
        }
    }

    private static double requireInverseRoundTrips(
            final WarpFunction function,
            final int width,
            final int height,
            final List<Point2D> sourceBoundary) {
        double maximum = 0;
        for (int yIndex = 0; yIndex < CROSSING_GRID_SIZE; yIndex++) {
            final double y = (height - 1.0) * yIndex
                    / (CROSSING_GRID_SIZE - 1.0);
            for (int xIndex = 0;
                    xIndex < CROSSING_GRID_SIZE; xIndex++) {
                final double x = (width - 1.0) * xIndex
                        / (CROSSING_GRID_SIZE - 1.0);
                final Point2D source = new Point2D(x, y);
                final Point2D recovered = inverse(
                        function.apply(source), function.sources(),
                        function.xWeights(), function.yWeights(),
                        function.supportRadius());
                final double error = distance(source, recovered);
                maximum = Math.max(maximum, error);
                if (!Double.isFinite(error)
                        || error > ROUND_TRIP_TOLERANCE) {
                    throw new IllegalArgumentException(
                            "Manual outline warp failed inverse round-trip safety");
                }
            }
        }
        for (int index = 0; index < sourceBoundary.size(); index++) {
            final Point2D from = sourceBoundary.get(index);
            final Point2D to = sourceBoundary.get(
                    (index + 1) % sourceBoundary.size());
            maximum = requireInverseRoundTripAt(
                    function, from, maximum);
            maximum = requireInverseRoundTripAt(
                    function, interpolate(from, to, 0.5), maximum);
        }
        return maximum;
    }

    private static double requireInverseRoundTripAt(
            final WarpFunction function,
            final Point2D source,
            final double previousMaximum) {
        final Point2D recovered = inverse(
                function.apply(source), function.sources(),
                function.xWeights(), function.yWeights(),
                function.supportRadius());
        final double error = distance(source, recovered);
        if (!Double.isFinite(error) || error > ROUND_TRIP_TOLERANCE) {
            throw new IllegalArgumentException(
                    "Manual outline warp failed inverse round-trip safety");
        }
        return Math.max(previousMaximum, error);
    }

    private static Point2D inverse(
            final Point2D target,
            final List<Point2D> sources,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius) {
        Point2D estimate = target;
        double residual = inverseResidual(
                estimate, target, sources,
                xWeights, yWeights, supportRadius);
        for (int iteration = 0;
                iteration < MAXIMUM_INVERSE_ITERATIONS
                && residual > INVERSE_TOLERANCE; iteration++) {
            final double[] shift = displacement(
                    estimate, sources, xWeights, yWeights, supportRadius);
            final double residualX = estimate.x() + shift[0] - target.x();
            final double residualY = estimate.y() + shift[1] - target.y();
            final Jacobian2D jacobian = jacobian(
                    estimate, sources, xWeights, yWeights, supportRadius);
            final double determinant = jacobian.determinant();
            if (!Double.isFinite(determinant)
                    || Math.abs(determinant) <= MINIMUM_SOLVER_PIVOT) {
                break;
            }
            final double stepX = (jacobian.m11() * residualX
                    - jacobian.m01() * residualY) / determinant;
            final double stepY = (-jacobian.m10() * residualX
                    + jacobian.m00() * residualY) / determinant;
            boolean improved = false;
            double stepScale = 1;
            for (int lineSearch = 0;
                    lineSearch < MAXIMUM_LINE_SEARCH_STEPS; lineSearch++) {
                final Point2D candidate = new Point2D(
                        estimate.x() - stepScale * stepX,
                        estimate.y() - stepScale * stepY);
                final double candidateResidual = inverseResidual(
                        candidate, target, sources,
                        xWeights, yWeights, supportRadius);
                if (Double.isFinite(candidateResidual)
                        && candidateResidual < residual) {
                    estimate = candidate;
                    residual = candidateResidual;
                    improved = true;
                    break;
                }
                stepScale *= 0.5;
            }
            if (!improved) {
                break;
            }
        }
        if (inverseResidual(estimate, target, sources,
                xWeights, yWeights, supportRadius) <= INVERSE_TOLERANCE) {
            return estimate;
        }
        throw new IllegalArgumentException(
                "Manual outline-warp inverse did not converge safely");
    }

    private static double inverseResidual(
            final Point2D estimate,
            final Point2D target,
            final List<Point2D> sources,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius) {
        final double[] shift = displacement(
                estimate, sources, xWeights, yWeights, supportRadius);
        return Math.hypot(
                estimate.x() + shift[0] - target.x(),
                estimate.y() + shift[1] - target.y());
    }

    private static double convexHullArea(final List<Point2D> points) {
        final List<Point2D> sorted = new ArrayList<>(points);
        sorted.sort(java.util.Comparator.comparingDouble(Point2D::x)
                .thenComparingDouble(Point2D::y));
        final List<Point2D> hull = new ArrayList<>();
        for (final Point2D point : sorted) {
            while (hull.size() >= 2 && cross(
                    hull.get(hull.size() - 2),
                    hull.get(hull.size() - 1), point) <= 0) {
                hull.remove(hull.size() - 1);
            }
            hull.add(point);
        }
        final int lower = hull.size();
        for (int index = sorted.size() - 2; index >= 0; index--) {
            final Point2D point = sorted.get(index);
            while (hull.size() > lower && cross(
                    hull.get(hull.size() - 2),
                    hull.get(hull.size() - 1), point) <= 0) {
                hull.remove(hull.size() - 1);
            }
            hull.add(point);
        }
        if (hull.size() <= 3) {
            return 0;
        }
        hull.remove(hull.size() - 1);
        double twiceArea = 0;
        for (int index = 0; index < hull.size(); index++) {
            final Point2D current = hull.get(index);
            final Point2D next = hull.get((index + 1) % hull.size());
            twiceArea += current.x() * next.y() - next.x() * current.y();
        }
        return Math.abs(twiceArea) * 0.5;
    }

    private static double signedArea(final List<Point2D> loop) {
        double twiceArea = 0;
        for (int index = 0; index < loop.size(); index++) {
            final Point2D current = loop.get(index);
            final Point2D next = loop.get((index + 1) % loop.size());
            twiceArea += current.x() * next.y() - next.x() * current.y();
        }
        return twiceArea * 0.5;
    }

    private static boolean adjacentEdges(
            final int first,
            final int second,
            final int count) {
        return first == second
                || (first + 1) % count == second
                || (second + 1) % count == first;
    }

    private static boolean segmentsIntersect(
            final Point2D firstA,
            final Point2D firstB,
            final Point2D secondA,
            final Point2D secondB) {
        final double firstSecondA = cross(firstA, firstB, secondA);
        final double firstSecondB = cross(firstA, firstB, secondB);
        final double secondFirstA = cross(secondA, secondB, firstA);
        final double secondFirstB = cross(secondA, secondB, firstB);
        if (oppositeSigns(firstSecondA, firstSecondB)
                && oppositeSigns(secondFirstA, secondFirstB)) {
            return true;
        }
        return nearZero(firstSecondA) && onSegment(firstA, firstB, secondA)
                || nearZero(firstSecondB)
                        && onSegment(firstA, firstB, secondB)
                || nearZero(secondFirstA)
                        && onSegment(secondA, secondB, firstA)
                || nearZero(secondFirstB)
                        && onSegment(secondA, secondB, firstB);
    }

    private static boolean oppositeSigns(
            final double first,
            final double second) {
        return first > GEOMETRY_EPSILON && second < -GEOMETRY_EPSILON
                || first < -GEOMETRY_EPSILON
                        && second > GEOMETRY_EPSILON;
    }

    private static boolean nearZero(final double value) {
        return Math.abs(value) <= GEOMETRY_EPSILON;
    }

    private static boolean onSegment(
            final Point2D from,
            final Point2D to,
            final Point2D point) {
        return point.x() >= Math.min(from.x(), to.x()) - GEOMETRY_EPSILON
                && point.x() <= Math.max(from.x(), to.x())
                        + GEOMETRY_EPSILON
                && point.y() >= Math.min(from.y(), to.y())
                        - GEOMETRY_EPSILON
                && point.y() <= Math.max(from.y(), to.y())
                        + GEOMETRY_EPSILON;
    }

    private static double cross(
            final Point2D origin,
            final Point2D first,
            final Point2D second) {
        return (first.x() - origin.x()) * (second.y() - origin.y())
                - (first.y() - origin.y()) * (second.x() - origin.x());
    }

    private static double distance(
            final Point2D first,
            final Point2D second) {
        return Math.hypot(
                first.x() - second.x(), first.y() - second.y());
    }

    private static double squaredDistance(
            final Point2D first,
            final Point2D second) {
        final double deltaX = first.x() - second.x();
        final double deltaY = first.y() - second.y();
        return deltaX * deltaX + deltaY * deltaY;
    }

    private static BoundaryDistances boundaryDistances(
            final List<Point2D> first,
            final List<Point2D> second) {
        final double[] values = new double[first.size() + second.size()];
        int index = 0;
        for (final Point2D point : first) {
            values[index++] = distanceToLoop(point, second);
        }
        for (final Point2D point : second) {
            values[index++] = distanceToLoop(point, first);
        }
        Arrays.sort(values);
        double total = 0;
        for (final double value : values) {
            total += value;
        }
        final int p95Index = Math.min(values.length - 1,
                (int) Math.ceil(0.95 * values.length) - 1);
        return new BoundaryDistances(
                total / values.length,
                values[p95Index], values[values.length - 1]);
    }

    private static double distanceToLoop(
            final Point2D point,
            final List<Point2D> loop) {
        double minimumSquared = Double.POSITIVE_INFINITY;
        for (int index = 0; index < loop.size(); index++) {
            minimumSquared = Math.min(minimumSquared,
                    squaredDistanceToSegment(
                            point, loop.get(index),
                            loop.get((index + 1) % loop.size())));
        }
        return Math.sqrt(minimumSquared);
    }

    private static double squaredDistanceToSegment(
            final Point2D point,
            final Point2D from,
            final Point2D to) {
        final double deltaX = to.x() - from.x();
        final double deltaY = to.y() - from.y();
        final double denominator = deltaX * deltaX + deltaY * deltaY;
        final double parameter;
        if (denominator == 0) {
            parameter = 0;
        } else {
            parameter = Math.max(0, Math.min(1,
                    ((point.x() - from.x()) * deltaX
                            + (point.y() - from.y()) * deltaY)
                            / denominator));
        }
        final double nearestX = from.x() + parameter * deltaX;
        final double nearestY = from.y() + parameter * deltaY;
        final double differenceX = point.x() - nearestX;
        final double differenceY = point.y() - nearestY;
        return differenceX * differenceX + differenceY * differenceY;
    }

    private static String contentHash(
            final List<Point2D> atlas,
            final List<Point2D> tissue,
            final List<AnchorPair> anchors,
            final int atlasDirection,
            final int tissueDirection,
            final int width,
            final int height,
            final List<Point2D> sources,
            final List<Point2D> targets,
            final double supportRadius,
            final double[] xWeights,
            final double[] yWeights,
            final boolean strictBoundaryQualityEnforced,
            final int seedSamplesPerAnchorArc,
            final int denseCorrespondenceCount) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateText(digest, ALGORITHM_REVISION);
            updateText(digest, PIXEL_CENTER_CONVENTION);
            updateInt(digest, MATCH_CANDIDATES_PER_ANCHOR_ARC);
            updateDouble(digest, MATCH_PARAMETER_REGULARIZATION);
            updateInt(digest, width);
            updateInt(digest, height);
            updateInt(digest, strictBoundaryQualityEnforced ? 1 : 0);
            updateInt(digest, seedSamplesPerAnchorArc);
            updateInt(digest, denseCorrespondenceCount);
            updateInt(digest, MAXIMUM_ADAPTIVE_CONTROL_COUNT);
            updateDouble(digest, STRICT_BOUNDARY_P95_MINIMUM_PIXELS);
            updateDouble(digest, STRICT_BOUNDARY_P95_DIAGONAL_FRACTION);
            updateDouble(digest,
                    STRICT_BOUNDARY_MAXIMUM_MINIMUM_PIXELS);
            updateDouble(digest,
                    STRICT_BOUNDARY_MAXIMUM_DIAGONAL_FRACTION);
            updatePoints(digest, atlas);
            updatePoints(digest, tissue);
            updateInt(digest, anchors.size());
            for (final AnchorPair anchor : anchors) {
                updateText(digest, anchor.name());
                updateInt(digest, anchor.atlasVertexIndex());
                updateInt(digest, anchor.tissueVertexIndex());
            }
            updateInt(digest, atlasDirection);
            updateInt(digest, tissueDirection);
            updatePoints(digest, sources);
            updatePoints(digest, targets);
            updateDouble(digest, supportRadius);
            updateDouble(digest, REGULARIZATION);
            for (final double weight : xWeights) {
                updateDouble(digest, weight);
            }
            for (final double weight : yWeights) {
                updateDouble(digest, weight);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "SHA-256 is required by the Java runtime", error);
        }
    }

    private static void updatePoints(
            final MessageDigest digest,
            final List<Point2D> points) {
        updateInt(digest, points.size());
        for (final Point2D point : points) {
            updateDouble(digest, point.x());
            updateDouble(digest, point.y());
        }
    }

    private static void updateText(
            final MessageDigest digest,
            final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
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
        digest.update(ByteBuffer.allocate(Double.BYTES)
                .putLong(Double.doubleToLongBits(value)).array());
    }

    private static List<Double> immutableDoubles(final double[] values) {
        return Arrays.stream(values).boxed().toList();
    }

    /** One reviewer-confirmed semantic correspondence between loop vertices. */
    public record AnchorPair(
            String name,
            int atlasVertexIndex,
            int tissueVertexIndex) {

        public AnchorPair {
            name = Objects.requireNonNull(name, "name").trim();
            if (name.isEmpty() || atlasVertexIndex < 0
                    || tissueVertexIndex < 0) {
                throw new IllegalArgumentException(
                        "Anchor name and vertex indices must be valid");
            }
        }
    }

    /** Analytic forward-warp Jacobian in preview-pixel coordinates. */
    public record Jacobian2D(
            double m00,
            double m01,
            double m10,
            double m11) {

        public Jacobian2D {
            if (!Double.isFinite(m00) || !Double.isFinite(m01)
                    || !Double.isFinite(m10) || !Double.isFinite(m11)) {
                throw new IllegalArgumentException(
                        "Outline-warp Jacobian must be finite");
            }
        }

        public double determinant() {
            return m00 * m11 - m01 * m10;
        }
    }

    /** Safety and reproducibility evidence, never an accuracy score. */
    public record Diagnostics(
            String algorithmRevision,
            String pixelCenterConvention,
            int previewWidth,
            int previewHeight,
            int controlPairCount,
            boolean strictBoundaryQualityEnforced,
            int seedSamplesPerAnchorArc,
            int denseCorrespondenceCount,
            int atlasTraversalDirection,
            int tissueTraversalDirection,
            double supportRadius,
            double regularization,
            double maximumRequestedDisplacement,
            double maximumSampledDisplacement,
            double minimumJacobianDeterminant,
            double minimumSingularValue,
            double maximumSingularValue,
            double maximumAnisotropy,
            double controlResidualRms,
            double maximumControlResidual,
            double boundaryDistanceMean,
            double boundaryDistanceP95,
            double maximumBoundaryDistance,
            double maximumInverseRoundTripError,
            String contentSha256) {

        public Diagnostics {
            algorithmRevision = requireText(
                    algorithmRevision, "algorithmRevision");
            pixelCenterConvention = requireText(
                    pixelCenterConvention, "pixelCenterConvention");
            contentSha256 = requireText(contentSha256, "contentSha256");
            if (previewWidth <= 1 || previewHeight <= 1
                    || controlPairCount
                            < REQUIRED_ANCHOR_COUNT
                                    * MINIMUM_SAMPLES_PER_ANCHOR_ARC
                    || controlPairCount > MAXIMUM_ADAPTIVE_CONTROL_COUNT
                    || seedSamplesPerAnchorArc
                            < MINIMUM_SAMPLES_PER_ANCHOR_ARC
                    || seedSamplesPerAnchorArc
                            > MAXIMUM_SAMPLES_PER_ANCHOR_ARC
                    || denseCorrespondenceCount < controlPairCount
                    || strictBoundaryQualityEnforced
                            && denseCorrespondenceCount
                                    != REQUIRED_ANCHOR_COUNT
                                            * ADAPTIVE_CORRESPONDENCES_PER_ANCHOR_ARC
                    || !strictBoundaryQualityEnforced
                            && (controlPairCount
                                    != REQUIRED_ANCHOR_COUNT
                                            * seedSamplesPerAnchorArc
                                    || denseCorrespondenceCount
                                            != controlPairCount)
                    || Math.abs(atlasTraversalDirection) != 1
                    || Math.abs(tissueTraversalDirection) != 1
                    || !allFinite(supportRadius, regularization,
                            maximumRequestedDisplacement,
                            maximumSampledDisplacement,
                            minimumJacobianDeterminant,
                            minimumSingularValue, maximumSingularValue,
                            maximumAnisotropy, controlResidualRms,
                            maximumControlResidual,
                            boundaryDistanceMean, boundaryDistanceP95,
                            maximumBoundaryDistance,
                            maximumInverseRoundTripError)
                    || supportRadius <= 0 || regularization < 0
                    || maximumRequestedDisplacement < 0
                    || maximumSampledDisplacement < 0
                    || minimumJacobianDeterminant <= 0
                    || minimumSingularValue <= 0
                    || maximumSingularValue < minimumSingularValue
                    || maximumAnisotropy < 1
                    || controlResidualRms < 0
                    || maximumControlResidual < controlResidualRms
                    || boundaryDistanceMean < 0
                    || boundaryDistanceP95 < 0
                    || maximumBoundaryDistance < boundaryDistanceP95
                    || maximumInverseRoundTripError < 0
                    || contentSha256.length() != 64) {
                throw new IllegalArgumentException(
                        "Outline-warp diagnostics must be finite and valid");
            }
        }
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

    private static boolean allFinite(final double... values) {
        for (final double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private record Segment(Point2D from, Point2D to) {
        private boolean sharesEndpoint(final Segment other) {
            return from.equals(other.from) || from.equals(other.to)
                    || to.equals(other.from) || to.equals(other.to);
        }
    }

    private record SingularValues(
            double minimum,
            double maximum,
            double anisotropy) { }

    private record MatchedControls(
            List<Point2D> sources,
            List<Point2D> targets) { }

    private record ValidatedInput(
            List<Point2D> atlas,
            List<Point2D> tissue,
            List<AnchorPair> anchors,
            int atlasDirection,
            int tissueDirection) { }

    private record BoundaryDistances(
            double mean,
            double p95,
            double maximum) { }

    private record WarpFunction(
            List<Point2D> sources,
            double[] xWeights,
            double[] yWeights,
            double supportRadius) {

        private Point2D apply(final Point2D point) {
            final double[] shift = displacement(
                    point, sources, xWeights, yWeights, supportRadius);
            return new Point2D(
                    point.x() + shift[0], point.y() + shift[1]);
        }
    }

    private static final class MutableDiagnostics {
        private double minimumDeterminant = Double.POSITIVE_INFINITY;
        private double minimumSingularValue = Double.POSITIVE_INFINITY;
        private double maximumSingularValue = Double.NEGATIVE_INFINITY;
        private double maximumAnisotropy = Double.NEGATIVE_INFINITY;
        private double maximumDisplacement;

        private boolean allFinite() {
            return ManualOutlineWarp2D.allFinite(
                    minimumDeterminant, minimumSingularValue,
                    maximumSingularValue, maximumAnisotropy,
                    maximumDisplacement);
        }
    }
}
