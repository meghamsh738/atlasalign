package org.atlasalign.application.manual;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ManualWarpPrecondition;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.core.Point2D;

/** Immutable request for one manual outer-border deformation preview. */
public record BoundaryWarpRequest(
        long contentRevision,
        ManualHemisphereWarp2D.AtlasSide targetSide,
        AtlasOrientation orientation,
        ReviewSectionMode sectionMode,
        List<BoundaryFitSample> atlasBoundary,
        List<BoundaryFitSample> tissueBoundary,
        List<BoundaryFitMatch> matches,
        List<BoundaryWarpBaselinePoint> baselinePoints,
        List<ManualWarpControl> priorControls,
        Optional<ManualHemisphereWarp2D> priorWarp,
        ManualHemisphereWarp2D.MidlineSegment mappedAtlasMidline,
        int previewWidth,
        int previewHeight,
        ManualWarpPrecondition precondition,
        String sourceHash,
        String atlasHash) {

    public BoundaryWarpRequest {
        if (contentRevision < 0 || previewWidth <= 1 || previewHeight <= 1) {
            throw new IllegalArgumentException(
                    "Manual boundary-warp request geometry is invalid");
        }
        targetSide = Objects.requireNonNull(targetSide, "targetSide");
        orientation = Objects.requireNonNull(orientation, "orientation");
        if (!orientation.confirmed()) {
            throw new IllegalArgumentException(
                    "Manual boundary warp requires confirmed orientation");
        }
        sectionMode = Objects.requireNonNull(sectionMode, "sectionMode");
        atlasBoundary = List.copyOf(Objects.requireNonNull(
                atlasBoundary, "atlasBoundary"));
        tissueBoundary = List.copyOf(Objects.requireNonNull(
                tissueBoundary, "tissueBoundary"));
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        baselinePoints = List.copyOf(Objects.requireNonNull(
                baselinePoints, "baselinePoints"));
        priorControls = List.copyOf(Objects.requireNonNull(
                priorControls, "priorControls"));
        priorWarp = Objects.requireNonNull(priorWarp, "priorWarp");
        mappedAtlasMidline = Objects.requireNonNull(
                mappedAtlasMidline, "mappedAtlasMidline");
        precondition = Objects.requireNonNull(precondition, "precondition");
        sourceHash = requireHash(sourceHash, "sourceHash");
        atlasHash = requireHash(atlasHash, "atlasHash");
        final HashSet<String> identifiers = new HashSet<>();
        if (matches.size() > 48 || matches.stream().anyMatch(match ->
                match == null || !identifiers.add(match.id()))) {
            throw new IllegalArgumentException(
                    "Manual boundary pairs require unique ids and have a maximum of 48 per side");
        }
        final HashSet<String> baselineMatchIds = new HashSet<>();
        final HashSet<String> baselineControlIds = new HashSet<>();
        if (baselinePoints.size() != matches.size()
                || baselinePoints.stream().anyMatch(point -> point == null
                        || !baselineMatchIds.add(point.matchId())
                        || !baselineControlIds.add(point.controlId()))
                || !baselineMatchIds.equals(identifiers)) {
            throw new IllegalArgumentException(
                    "Every boundary match requires one unique immutable baseline control");
        }
        final boolean priorMismatch = priorWarp.isPresent()
                ? !priorWarp.orElseThrow().controls().equals(priorControls)
                : !priorControls.isEmpty();
        if (priorMismatch) {
            throw new IllegalArgumentException(
                    "Prior manual-warp controls must exactly match the prior field");
        }
    }

    /** Compatibility constructor that derives an installed-field baseline. */
    public BoundaryWarpRequest(
            final long contentRevision,
            final ManualHemisphereWarp2D.AtlasSide targetSide,
            final AtlasOrientation orientation,
            final ReviewSectionMode sectionMode,
            final List<BoundaryFitSample> atlasBoundary,
            final List<BoundaryFitSample> tissueBoundary,
            final List<BoundaryFitMatch> matches,
            final List<ManualWarpControl> priorControls,
            final Optional<ManualHemisphereWarp2D> priorWarp,
            final ManualHemisphereWarp2D.MidlineSegment mappedAtlasMidline,
            final int previewWidth,
            final int previewHeight,
            final ManualWarpPrecondition precondition,
            final String sourceHash,
            final String atlasHash) {
        this(contentRevision, targetSide, orientation, sectionMode,
                atlasBoundary, tissueBoundary, matches,
                deriveBaseline(targetSide, matches, priorControls, priorWarp),
                priorControls, priorWarp, mappedAtlasMidline,
                previewWidth, previewHeight, precondition, sourceHash,
                atlasHash);
    }

    public BoundaryWarpRequest withMatches(
            final List<BoundaryFitMatch> nextMatches) {
        final List<BoundaryFitMatch> checkedMatches = List.copyOf(
                Objects.requireNonNull(nextMatches, "nextMatches"));
        final List<BoundaryWarpBaselinePoint> nextBaseline =
                matches.isEmpty()
                        ? deriveBaseline(targetSide, checkedMatches,
                                priorControls, priorWarp)
                        : rebaseBaseline(matches, baselinePoints,
                                checkedMatches, targetSide, priorWarp);
        return withMatchesAndBaseline(checkedMatches, nextBaseline);
    }

    /**
     * Replaces editable pairs while carrying an explicit, already audited
     * installed baseline. This is used after a partial safe step so the next
     * interpolation starts from the exact controls that were just installed,
     * not from resampled atlas exterior points.
     */
    public BoundaryWarpRequest withMatchesAndBaseline(
            final List<BoundaryFitMatch> nextMatches,
            final List<BoundaryWarpBaselinePoint> nextBaselinePoints) {
        return new BoundaryWarpRequest(contentRevision, targetSide,
                orientation, sectionMode, atlasBoundary, tissueBoundary,
                nextMatches, nextBaselinePoints,
                priorControls, priorWarp, mappedAtlasMidline,
                previewWidth, previewHeight, precondition, sourceHash,
                atlasHash);
    }

    public BoundaryWarpBaselinePoint baselineFor(final String matchId) {
        return baselinePoints.stream().filter(point -> point.matchId()
                .equals(matchId)).findFirst().orElseThrow(() ->
                new IllegalArgumentException(
                        "Unknown boundary baseline: " + matchId));
    }

    private static List<BoundaryWarpBaselinePoint> deriveBaseline(
            final ManualHemisphereWarp2D.AtlasSide side,
            final List<BoundaryFitMatch> matches,
            final List<ManualWarpControl> priorControls,
            final Optional<ManualHemisphereWarp2D> priorWarp) {
        final List<BoundaryFitMatch> checkedMatches = List.copyOf(
                Objects.requireNonNull(matches, "matches"));
        final List<ManualWarpControl> installed = Objects.requireNonNull(
                priorControls, "priorControls").stream()
                .filter(control -> control.atlasSide() == side
                        && control.groupId().equals(
                                BoundaryWarpSolver.GROUP_ID))
                .sorted(Comparator.comparing(ManualWarpControl::id))
                .toList();
        final long includedCount = checkedMatches.stream()
                .filter(BoundaryFitMatch::included).count();
        final boolean reuseInstalled = installed.size() == includedCount;
        final List<BoundaryWarpBaselinePoint> result = new ArrayList<>(
                checkedMatches.size());
        int installedIndex = 0;
        for (int index = 0; index < checkedMatches.size(); index++) {
            final BoundaryFitMatch match = checkedMatches.get(index);
            if (reuseInstalled && match.included()) {
                final ManualWarpControl control = installed.get(
                        installedIndex++);
                result.add(new BoundaryWarpBaselinePoint(match.id(),
                        control.id(), control.sourcePoint(),
                        control.targetPoint()));
            } else {
                result.add(derivedPoint(side, match, index, priorWarp));
            }
        }
        return List.copyOf(result);
    }

    private static List<BoundaryWarpBaselinePoint> rebaseBaseline(
            final List<BoundaryFitMatch> priorMatches,
            final List<BoundaryWarpBaselinePoint> priorBaseline,
            final List<BoundaryFitMatch> nextMatches,
            final ManualHemisphereWarp2D.AtlasSide side,
            final Optional<ManualHemisphereWarp2D> priorWarp) {
        final Map<String, BoundaryFitMatch> matchesById = new HashMap<>();
        priorMatches.forEach(match -> matchesById.put(match.id(), match));
        final Map<String, BoundaryWarpBaselinePoint> baselineById =
                new HashMap<>();
        priorBaseline.forEach(point -> baselineById.put(
                point.matchId(), point));
        final List<BoundaryWarpBaselinePoint> result = new ArrayList<>(
                nextMatches.size());
        for (int index = 0; index < nextMatches.size(); index++) {
            final BoundaryFitMatch next = nextMatches.get(index);
            final BoundaryFitMatch prior = matchesById.get(next.id());
            final BoundaryWarpBaselinePoint baseline = baselineById.get(
                    next.id());
            if (prior != null && baseline != null
                    && prior.atlasPreviewPoint().equals(
                            next.atlasPreviewPoint())) {
                result.add(baseline);
            } else {
                result.add(derivedPoint(side, next, index, priorWarp));
            }
        }
        return List.copyOf(result);
    }

    private static BoundaryWarpBaselinePoint derivedPoint(
            final ManualHemisphereWarp2D.AtlasSide side,
            final BoundaryFitMatch match,
            final int index,
            final Optional<ManualHemisphereWarp2D> priorWarp) {
        final Point2D target = match.atlasPreviewPoint();
        final Point2D source = priorWarp
                .filter(warp -> warp.hasControls(side))
                .map(warp -> warp.inverse(side, target))
                .orElse(target);
        final Point2D installedTarget = priorWarp
                .filter(warp -> warp.hasControls(side))
                .map(warp -> warp.apply(side, source))
                .orElse(target);
        final String controlId = String.format(Locale.ROOT,
                "outer-boundary-%s-draft-%02d-%s",
                side.name().toLowerCase(Locale.ROOT), index + 1,
                Integer.toUnsignedString(match.id().hashCode(), 16));
        return new BoundaryWarpBaselinePoint(match.id(), controlId,
                source, installedTarget);
    }

    private static String requireHash(final String value,
            final String label) {
        final String checked = Objects.requireNonNull(value, label);
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    label + " must be a lowercase SHA-256");
        }
        return checked;
    }
}
