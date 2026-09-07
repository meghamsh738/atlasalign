package org.atlasalign.application.manual;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.atlasalign.core.Point2D;

/**
 * Deterministic manual exterior-border solver. It replaces only one side's
 * outer-border group and reuses the shared, locally supported warp field.
 */
public final class BoundaryWarpSolver {

    public static final String SOLVER_REVISION =
            "manual-outer-border-compact-pwa-v2-installed-baseline";
    public static final String GROUP_ID = "outer-boundary";
    public static final int MINIMUM_INCLUDED_MATCHES = 4;
    public static final int MAXIMUM_MATCHES = 48;
    public static final int DEFAULT_SUGGESTED_MATCHES = 24;
    public static final double MINIMUM_SAFE_STEP_FRACTION = 1.0 / 1024.0;
    private static final int SAFE_STEP_BISECTION_ITERATIONS = 16;

    /** Suggests editable local pairs from the current coarse placement. */
    public BoundaryWarpRequest suggest(final BoundaryWarpRequest request) {
        return suggest(request, DEFAULT_SUGGESTED_MATCHES);
    }

    /** Suggests the requested deterministic density without solving it. */
    public BoundaryWarpRequest suggest(
            final BoundaryWarpRequest request,
            final int requestedCount) {
        final BoundaryWarpRequest checked = Objects.requireNonNull(
                request, "request");
        requireBoundarySamples(checked);
        if (requestedCount < MINIMUM_INCLUDED_MATCHES
                || requestedCount > MAXIMUM_MATCHES) {
            throw new IllegalArgumentException(
                    "Boundary density must be from 4 to 48");
        }
        final double diagonal = Math.hypot(
                checked.previewWidth(), checked.previewHeight());
        final Point2D centre = centroid(checked.atlasBoundary().stream()
                .map(BoundaryFitSample::point).toList());
        final List<BoundaryFitSample> uniqueTissue = uniqueAngularOrder(
                checked.tissueBoundary());
        final int actualCount = Math.min(requestedCount, Math.min(
                checked.atlasBoundary().size(), uniqueTissue.size()));
        final List<BoundaryFitSample> atlasAnchors = evenlySpaced(
                checked.atlasBoundary(), actualCount).stream()
                .sorted(Comparator.comparingDouble(sample -> angle(
                        sample.point(), centre))).toList();
        final List<Suggested> proposals = monotonicSuggestions(
                atlasAnchors, uniqueTissue, diagonal, centre);
        final List<BoundaryFitMatch> matches = new ArrayList<>();
        for (int index = 0; index < proposals.size(); index++) {
            final Suggested value = proposals.get(index);
            matches.add(new BoundaryFitMatch(String.format(Locale.ROOT,
                    "border-%s-suggested-%02d",
                    checked.targetSide().name().toLowerCase(Locale.ROOT),
                    index + 1),
                    value.atlas().point(), value.tissue().point(),
                    BoundaryFitMatchOrigin.AUTO_SUGGESTED,
                    value.reliable()));
        }
        return checked.withMatches(matches);
    }

    /**
     * Assigns unique tissue endpoints in one cyclic direction. Trying both
     * directions and several locally plausible starting samples retains the
     * current-placement seed while preventing crossed or duplicated pairs.
     */
    private static List<Suggested> monotonicSuggestions(
            final List<BoundaryFitSample> atlas,
            final List<BoundaryFitSample> tissue,
            final double diagonal,
            final Point2D atlasCentre) {
        final int anchorCount = atlas.size();
        final int tissueCount = tissue.size();
        if (anchorCount == 0 || tissueCount < anchorCount) {
            throw new IllegalArgumentException(
                    "Tissue and atlas boundaries cannot support the requested density");
        }
        final double[][] scores = new double[anchorCount][tissueCount];
        final double[][] normals = new double[anchorCount][tissueCount];
        for (int anchor = 0; anchor < anchorCount; anchor++) {
            for (int endpoint = 0; endpoint < tissueCount; endpoint++) {
                final double normal = dot(atlas.get(anchor).normal(),
                        tissue.get(endpoint).normal());
                normals[anchor][endpoint] = normal;
                scores[anchor][endpoint] = distance(
                        atlas.get(anchor).point(),
                        tissue.get(endpoint).point())
                        + 0.05 * diagonal * Math.max(0, 0.5 - normal);
            }
        }
        final List<Integer> starts = new ArrayList<>(tissueCount);
        for (int index = 0; index < tissueCount; index++) {
            starts.add(index);
        }
        starts.sort(Comparator.comparingDouble(
                (Integer index) -> scores[0][index])
                .thenComparingInt(Integer::intValue));
        final int startLimit = Math.min(16, starts.size());
        MonotonicSelection best = null;
        for (int startChoice = 0; startChoice < startLimit; startChoice++) {
            final int start = starts.get(startChoice);
            for (final int direction : new int[]{1, -1}) {
                final MonotonicSelection candidate = monotonicSelection(
                        scores, start, direction);
                if (best == null || candidate.totalScore()
                        < best.totalScore() - 1e-12) {
                    best = candidate;
                }
            }
        }
        final MonotonicSelection selected = Objects.requireNonNull(best);
        final List<Suggested> proposals = new ArrayList<>(anchorCount);
        for (int index = 0; index < anchorCount; index++) {
            final int tissueIndex = selected.tissueIndices().get(index);
            final double score = scores[index][tissueIndex];
            final double normal = normals[index][tissueIndex];
            proposals.add(new Suggested(atlas.get(index),
                    tissue.get(tissueIndex), score,
                    angle(atlas.get(index).point(), atlasCentre),
                    score <= 0.35 * diagonal && normal >= -0.25));
        }
        return List.copyOf(proposals);
    }

    private static MonotonicSelection monotonicSelection(
            final double[][] scores,
            final int start,
            final int direction) {
        final int anchorCount = scores.length;
        final int tissueCount = scores[0].length;
        double[] previous = new double[tissueCount];
        java.util.Arrays.fill(previous, Double.POSITIVE_INFINITY);
        previous[0] = scores[0][start];
        final int[][] predecessor = new int[anchorCount][tissueCount];
        for (final int[] row : predecessor) {
            java.util.Arrays.fill(row, -1);
        }
        for (int anchor = 1; anchor < anchorCount; anchor++) {
            final double[] current = new double[tissueCount];
            java.util.Arrays.fill(current, Double.POSITIVE_INFINITY);
            double prefixCost = Double.POSITIVE_INFINITY;
            int prefixRank = -1;
            for (int rank = 0; rank < tissueCount; rank++) {
                if (rank > 0 && prefixRank >= 0) {
                    final int tissueIndex = cyclicIndex(
                            start, direction, rank, tissueCount);
                    current[rank] = prefixCost
                            + scores[anchor][tissueIndex];
                    predecessor[anchor][rank] = prefixRank;
                }
                if (previous[rank] < prefixCost) {
                    prefixCost = previous[rank];
                    prefixRank = rank;
                }
            }
            previous = current;
        }
        int finalRank = -1;
        double total = Double.POSITIVE_INFINITY;
        for (int rank = 0; rank < tissueCount; rank++) {
            if (previous[rank] < total) {
                total = previous[rank];
                finalRank = rank;
            }
        }
        if (finalRank < 0) {
            throw new IllegalStateException(
                    "No monotonic boundary assignment was found");
        }
        final int[] ranks = new int[anchorCount];
        ranks[anchorCount - 1] = finalRank;
        for (int anchor = anchorCount - 1; anchor > 0; anchor--) {
            ranks[anchor - 1] = predecessor[anchor][ranks[anchor]];
        }
        final List<Integer> tissueIndices = new ArrayList<>(anchorCount);
        for (final int rank : ranks) {
            tissueIndices.add(cyclicIndex(
                    start, direction, rank, tissueCount));
        }
        return new MonotonicSelection(tissueIndices, total);
    }

    private static int cyclicIndex(
            final int start,
            final int direction,
            final int rank,
            final int size) {
        return Math.floorMod(start + direction * rank, size);
    }

    private static List<BoundaryFitSample> uniqueAngularOrder(
            final List<BoundaryFitSample> samples) {
        final Point2D centre = centroid(samples.stream()
                .map(BoundaryFitSample::point).toList());
        final Set<Point2D> seen = new HashSet<>();
        final List<BoundaryFitSample> ordered = new ArrayList<>();
        samples.stream().sorted(Comparator.comparingDouble(sample ->
                angle(sample.point(), centre))).forEach(sample -> {
                    if (seen.add(sample.point())) {
                        ordered.add(sample);
                    }
                });
        return List.copyOf(ordered);
    }

    private static double angle(
            final Point2D point,
            final Point2D centre) {
        double value = Math.atan2(point.y() - centre.y(),
                point.x() - centre.x());
        if (value < 0) {
            value += 2 * Math.PI;
        }
        return value;
    }

    private static double dot(
            final Point2D first,
            final Point2D second) {
        return first.x() * second.x() + first.y() * second.y();
    }

    /** Produces a locally supported ghost from the current finite pairs. */
    public BoundaryWarpPreview preview(final BoundaryWarpRequest request) {
        final BoundaryWarpRequest checked = Objects.requireNonNull(
                request, "request");
        requireBoundarySamples(checked);
        final List<BoundaryFitMatch> included = checked.matches().stream()
                .filter(BoundaryFitMatch::included).toList();
        if (included.isEmpty()) {
            throw new ManualWarpException(
                    ManualWarpFailureKind.TOO_FEW_CONTROLS,
                    "Match one atlas border point to show a provisional warp.",
                    "No included outer-border pairs");
        }
        return new BoundaryWarpPreview(checked,
                new BoundaryWarpPreviewField(checked, included),
                inputHash(checked, included));
    }

    /** Fits and fully audits the current included manual pairs. */
    public BoundaryWarpCandidate solve(final BoundaryWarpRequest request) {
        final BoundaryWarpRequest checked = Objects.requireNonNull(
                request, "request");
        requireBoundarySamples(checked);
        final List<BoundaryFitMatch> included = checked.matches().stream()
                .filter(BoundaryFitMatch::included).toList();
        if (included.size() < MINIMUM_INCLUDED_MATCHES) {
            throw new ManualWarpException(
                    ManualWarpFailureKind.TOO_FEW_CONTROLS,
                    "Add at least four well-spaced atlas-to-tissue border pairs before applying.",
                    "Manual outer-border pair count=" + included.size());
        }
        requireCoverage(checked, included);
        requireSafeBaseline(checked);
        return solveAt(checked, checked.matches(), 1.0);
    }

    /**
     * Returns the largest deterministic audited fraction of a finite request.
     * The candidate retains the reviewer's requested endpoints separately
     * from the endpoints used by the safe field.
     */
    public BoundaryWarpCandidate solveSafestStep(
            final BoundaryWarpRequest request) {
        final BoundaryWarpRequest checked = Objects.requireNonNull(
                request, "request");
        requireBoundarySamples(checked);
        final List<BoundaryFitMatch> included = checked.matches().stream()
                .filter(BoundaryFitMatch::included).toList();
        if (included.size() < MINIMUM_INCLUDED_MATCHES) {
            throw new ManualWarpException(
                    ManualWarpFailureKind.TOO_FEW_CONTROLS,
                    "Add at least four well-spaced atlas-to-tissue border pairs before applying.",
                    "Manual outer-border pair count=" + included.size());
        }
        requireCoverage(checked, included);
        requireSafeBaseline(checked);
        try {
            return solveAt(checked, checked.matches(), 1.0);
        } catch (final RuntimeException fullRequestFailure) {
            BoundaryWarpCandidate best = null;
            double lower = 0;
            double upper = 1;
            for (int iteration = 0;
                    iteration < SAFE_STEP_BISECTION_ITERATIONS;
                    iteration++) {
                final double fraction = (lower + upper) * 0.5;
                try {
                    best = solveAt(checked,
                            interpolateMatches(checked, fraction),
                            fraction);
                    lower = fraction;
                } catch (final RuntimeException unsafe) {
                    upper = fraction;
                }
            }
            if (best != null && best.safeStepFraction()
                    >= MINIMUM_SAFE_STEP_FRACTION) {
                return best.withLimitingSafetyReport(
                        safetyReport(fullRequestFailure,
                                checked.targetSide()));
            }
            throw fullRequestFailure;
        }
    }

    private static ManualWarpSafetyReport safetyReport(
            final RuntimeException failure,
            final ManualHemisphereWarp2D.AtlasSide side) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ManualWarpException manual) {
                if (manual.safetyReport().isPresent()) {
                    return manual.safetyReport().orElseThrow();
                }
                final ManualWarpSafetyGate gate = switch (manual.kind()) {
                    case FOLD_RISK -> ManualWarpSafetyGate.DETERMINANT;
                    case EXCESSIVE_STRETCH ->
                            ManualWarpSafetyGate.ANISOTROPY;
                    case EXCESSIVE_DISPLACEMENT ->
                            ManualWarpSafetyGate.DISPLACEMENT;
                    case OUTSIDE_WORKSPACE ->
                            ManualWarpSafetyGate.WORKSPACE;
                    case CONTROL_LIMIT ->
                            ManualWarpSafetyGate.CONTROL_CAPACITY;
                    case INVALID_CONTROL ->
                            ManualWarpSafetyGate.CONTROL_TOPOLOGY;
                    default -> ManualWarpSafetyGate.UNKNOWN;
                };
                return ManualWarpSafetyReport.unmeasured(gate, side, null,
                        manual.technicalDetail());
            }
            current = current.getCause();
        }
        return ManualWarpSafetyReport.unmeasured(
                ManualWarpSafetyGate.UNKNOWN, side, null,
                failure.getMessage());
    }

    private BoundaryWarpCandidate solveAt(
            final BoundaryWarpRequest checked,
            final List<BoundaryFitMatch> auditedMatches,
            final double safeStepFraction) {
        final List<BoundaryFitMatch> included = auditedMatches.stream()
                .filter(BoundaryFitMatch::included).toList();
        final List<ManualWarpControl> retained = checked.priorControls()
                .stream().filter(control ->
                        control.atlasSide() != checked.targetSide()
                                || !control.groupId().equals(GROUP_ID))
                .toList();
        final List<ManualWarpControl> replacement = new ArrayList<>();
        final List<ManualWarpControl> previewDeltaControls =
                new ArrayList<>();
        for (final BoundaryFitMatch match : included) {
            final BoundaryWarpBaselinePoint baseline = checked.baselineFor(
                    match.id());
            replacement.add(new ManualWarpControl(baseline.controlId(),
                    checked.targetSide(),
                    ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR,
                    GROUP_ID, baseline.sourcePoint(),
                    match.tissuePreviewPoint()));
            previewDeltaControls.add(new ManualWarpControl(
                    "preview-" + baseline.controlId(), checked.targetSide(),
                    ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR,
                    GROUP_ID, match.atlasPreviewPoint(),
                    match.tissuePreviewPoint()));
        }
        final List<ManualWarpControl> combined = new ArrayList<>(retained);
        combined.addAll(replacement);
        requireCapacity(combined, checked.targetSide());
        final ManualHemisphereWarp2D validated = ManualHemisphereWarp2D.fit(
                combined, checked.orientation(), checked.sectionMode(),
                checked.mappedAtlasMidline(), checked.previewWidth(),
                checked.previewHeight());
        final ManualHemisphereWarp2D delta = ManualHemisphereWarp2D.fit(
                previewDeltaControls, checked.orientation(),
                checked.sectionMode(), checked.mappedAtlasMidline(),
                checked.previewWidth(), checked.previewHeight());
        for (final ManualWarpControl control : replacement) {
            final Point2D mapped = validated.apply(
                    checked.targetSide(), control.sourcePoint());
            if (distance(mapped, control.targetPoint()) > 1e-6) {
                throw new ManualWarpException(
                        ManualWarpFailureKind.INVALID_CONTROL,
                        "The proposed border pairs could not be reproduced exactly. Move the pairs farther apart and try again.",
                        "Outer-boundary endpoint interpolation error="
                                + distance(mapped, control.targetPoint()));
            }
        }
        return new BoundaryWarpCandidate(checked, checked.matches(),
                auditedMatches, safeStepFraction, replacement, validated,
                delta, SOLVER_REVISION,
                inputHash(checked, included));
    }

    private void requireSafeBaseline(final BoundaryWarpRequest request) {
        try {
            solveAtBaseline(request);
        } catch (final RuntimeException topologyFailure) {
            final ManualWarpSafetyReport report =
                    ManualWarpSafetyReport.unmeasured(
                            ManualWarpSafetyGate.CONTROL_TOPOLOGY,
                            request.targetSide(), null,
                            "The requested density or exclusions changed the outer-boundary control topology before any endpoint movement.");
            final String technicalDetail = topologyFailure
                    instanceof ManualWarpException manual
                    ? manual.technicalDetail() : topologyFailure.getMessage();
            final ManualWarpException rejected = new ManualWarpException(
                    ManualWarpFailureKind.INVALID_CONTROL,
                    request.priorWarp().isPresent()
                            ? "The edited Border points cannot preserve the applied warp. Keep the applied border and continue, or edit the pairs and calculate again."
                            : "These Border points cannot form a valid starting mesh. Edit the pairs or adjust Setup, then calculate again.",
                    technicalDetail, report);
            rejected.initCause(topologyFailure);
            throw rejected;
        }
    }

    private void solveAtBaseline(final BoundaryWarpRequest request) {
        final List<BoundaryFitMatch> baselineMatches = interpolateMatches(
                request, 0);
        final List<BoundaryFitMatch> included = baselineMatches.stream()
                .filter(BoundaryFitMatch::included).toList();
        final List<ManualWarpControl> retained = request.priorControls()
                .stream().filter(control ->
                        control.atlasSide() != request.targetSide()
                                || !control.groupId().equals(GROUP_ID))
                .toList();
        final List<ManualWarpControl> combined = new ArrayList<>(retained);
        for (final BoundaryFitMatch match : included) {
            final BoundaryWarpBaselinePoint baseline = request.baselineFor(
                    match.id());
            combined.add(new ManualWarpControl(baseline.controlId(),
                    request.targetSide(),
                    ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR,
                    GROUP_ID, baseline.sourcePoint(),
                    baseline.targetPoint()));
        }
        requireCapacity(combined, request.targetSide());
        final ManualHemisphereWarp2D fitted = ManualHemisphereWarp2D.fit(
                combined, request.orientation(), request.sectionMode(),
                request.mappedAtlasMidline(), request.previewWidth(),
                request.previewHeight());
        for (final BoundaryFitMatch match : included) {
            final BoundaryWarpBaselinePoint baseline = request.baselineFor(
                    match.id());
            final Point2D mapped = fitted.apply(request.targetSide(),
                    baseline.sourcePoint());
            if (distance(mapped, baseline.targetPoint()) > 1e-6) {
                throw new ManualWarpException(
                        ManualWarpFailureKind.INVALID_CONTROL,
                        "The changed Border topology cannot reproduce its installed baseline exactly.",
                        "Baseline endpoint interpolation error="
                                + distance(mapped,
                                        baseline.targetPoint()));
            }
        }
    }

    private static List<BoundaryFitMatch> interpolateMatches(
            final BoundaryWarpRequest request,
            final double fraction) {
        return request.matches().stream().map(match -> {
            if (!match.included()) {
                return match;
            }
            final Point2D baseline = request.baselineFor(
                    match.id()).targetPoint();
            final Point2D tissue = match.tissuePreviewPoint();
            return match.withTissuePoint(new Point2D(
                    baseline.x() + fraction * (tissue.x() - baseline.x()),
                    baseline.y() + fraction * (tissue.y() - baseline.y())));
        }).toList();
    }

    private static void requireBoundarySamples(
            final BoundaryWarpRequest request) {
        if (request.atlasBoundary().size() < MINIMUM_INCLUDED_MATCHES
                || request.tissueBoundary().size()
                < MINIMUM_INCLUDED_MATCHES) {
            throw new ManualWarpException(
                    ManualWarpFailureKind.INVALID_CONTROL,
                    "There is not enough usable outer border on this side. Reposition the atlas or edit the tissue crop first.",
                    "Insufficient atlas or tissue exterior samples");
        }
    }

    private static void requireCoverage(
            final BoundaryWarpRequest request,
            final List<BoundaryFitMatch> matches) {
        final double diagonal = Math.hypot(
                request.previewWidth(), request.previewHeight());
        double atlasSpan = 0;
        double tissueSpan = 0;
        double maximumTriangleAreaTwice = 0;
        for (int first = 0; first < matches.size(); first++) {
            for (int second = first + 1; second < matches.size(); second++) {
                atlasSpan = Math.max(atlasSpan, distance(
                        matches.get(first).atlasPreviewPoint(),
                        matches.get(second).atlasPreviewPoint()));
                tissueSpan = Math.max(tissueSpan, distance(
                        matches.get(first).tissuePreviewPoint(),
                        matches.get(second).tissuePreviewPoint()));
                for (int third = second + 1;
                        third < matches.size(); third++) {
                    maximumTriangleAreaTwice = Math.max(
                            maximumTriangleAreaTwice, Math.abs(cross(
                                    matches.get(first).atlasPreviewPoint(),
                                    matches.get(second).atlasPreviewPoint(),
                                    matches.get(third).atlasPreviewPoint())));
                }
            }
        }
        if (atlasSpan < 0.08 * diagonal || tissueSpan < 0.08 * diagonal
                || maximumTriangleAreaTwice < 0.00035 * diagonal * diagonal) {
            throw new ManualWarpException(
                    ManualWarpFailureKind.INVALID_CONTROL,
                    "Spread the border pairs farther around the tissue edge; clustered pairs cannot define a safe warp.",
                    "Insufficient manual border-pair coverage: atlasSpan="
                            + atlasSpan + ", tissueSpan=" + tissueSpan
                            + ", area2=" + maximumTriangleAreaTwice);
        }
    }

    private static void requireCapacity(
            final List<ManualWarpControl> controls,
            final ManualHemisphereWarp2D.AtlasSide side) {
        final long count = controls.stream().filter(control ->
                control.atlasSide() == side).count();
        final long boundary = controls.stream().filter(control ->
                control.atlasSide() == side && control.origin()
                        == ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR)
                .count();
        if (count > ManualHemisphereWarp2D.MAXIMUM_CONTROLS_PER_SIDE) {
            throw new ManualWarpException(
                    ManualWarpFailureKind.CONTROL_LIMIT,
                    "This side already has too many points for a border group. Reduce a grid or structure group, or reset the side.",
                    "Manual outer-border capacity exceeded: " + count);
        }
        if (boundary
                > ManualHemisphereWarp2D.MAXIMUM_BOUNDARY_CONTROLS_PER_SIDE) {
            throw new ManualWarpException(
                    ManualWarpFailureKind.CONTROL_LIMIT,
                    "This side supports at most 48 outer-border points. Choose a lower density or restart the border.",
                    "Manual outer-border count=" + boundary);
        }
    }

    private static List<BoundaryFitSample> evenlySpaced(
            final List<BoundaryFitSample> samples,
            final int count) {
        final List<BoundaryFitSample> selected = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            selected.add(samples.get((int) ((2L * index + 1)
                    * samples.size() / (2L * count))));
        }
        return List.copyOf(selected);
    }

    private static String inputHash(
            final BoundaryWarpRequest request,
            final List<BoundaryFitMatch> included) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, SOLVER_REVISION);
            update(digest, request.contentRevision());
            update(digest, request.targetSide().name());
            update(digest, request.orientation().name());
            update(digest, request.sectionMode().name());
            update(digest, request.precondition().planeSha256());
            update(digest, request.precondition().previewDomainSha256());
            update(digest, request.precondition().priorWarpSha256()
                    .orElse(""));
            for (final BoundaryFitMatch match : included) {
                final BoundaryWarpBaselinePoint baseline =
                        request.baselineFor(match.id());
                update(digest, match.id());
                update(digest, baseline.controlId());
                update(digest, baseline.sourcePoint().x());
                update(digest, baseline.sourcePoint().y());
                update(digest, baseline.targetPoint().x());
                update(digest, baseline.targetPoint().y());
                update(digest, match.atlasPreviewPoint().x());
                update(digest, match.atlasPreviewPoint().y());
                update(digest, match.tissuePreviewPoint().x());
                update(digest, match.tissuePreviewPoint().y());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable",
                    impossible);
        }
    }

    private static Point2D centroid(final List<Point2D> points) {
        double x = 0;
        double y = 0;
        for (final Point2D point : points) {
            x += point.x();
            y += point.y();
        }
        return new Point2D(x / points.size(), y / points.size());
    }

    private static double cross(final Point2D first,
            final Point2D second, final Point2D third) {
        return (second.x() - first.x()) * (third.y() - first.y())
                - (second.y() - first.y()) * (third.x() - first.x());
    }

    private static double distance(final Point2D first,
            final Point2D second) {
        return Math.hypot(first.x() - second.x(),
                first.y() - second.y());
    }

    private static void update(final MessageDigest digest,
            final String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES)
                .putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void update(final MessageDigest digest,
            final long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static void update(final MessageDigest digest,
            final double value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(
                Double.doubleToLongBits(value == 0 ? 0 : value)).array());
    }

    private record Suggested(
            BoundaryFitSample atlas,
            BoundaryFitSample tissue,
            double score,
            double angle,
            boolean reliable) {
    }

    private record MonotonicSelection(
            List<Integer> tissueIndices,
            double totalScore) {

        private MonotonicSelection {
            tissueIndices = List.copyOf(tissueIndices);
        }
    }
}
