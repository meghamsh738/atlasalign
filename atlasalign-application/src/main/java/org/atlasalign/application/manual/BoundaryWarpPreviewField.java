package org.atlasalign.application.manual;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.core.Point2D;

/**
 * Lightweight, unapplied display field for progressive outer-border matching.
 * It deliberately carries no acceptance diagnostics: the fully audited
 * {@link ManualHemisphereWarp2D} is built only after four distributed pairs.
 */
public final class BoundaryWarpPreviewField {

    private static final double SUPPORT_FRACTION = 0.14;
    private static final double EXACT_EPSILON = 1e-9;

    private final ManualHemisphereWarp2D.AtlasSide targetSide;
    private final ReviewSectionMode sectionMode;
    private final ManualHemisphereWarp2D.MidlineSegment midline;
    private final List<BoundaryFitMatch> matches;
    private final double supportRadius;

    public BoundaryWarpPreviewField(
            final BoundaryWarpRequest request,
            final List<BoundaryFitMatch> includedMatches) {
        final BoundaryWarpRequest checked = Objects.requireNonNull(
                request, "request");
        targetSide = checked.targetSide();
        sectionMode = checked.sectionMode();
        midline = checked.mappedAtlasMidline();
        matches = List.copyOf(Objects.requireNonNull(
                includedMatches, "includedMatches"));
        if (matches.isEmpty() || matches.stream().anyMatch(match ->
                !match.included())) {
            throw new IllegalArgumentException(
                    "A preview field requires one or more included pairs");
        }
        supportRadius = SUPPORT_FRACTION * Math.hypot(
                checked.previewWidth(), checked.previewHeight());
    }

    public Point2D apply(
            final ManualHemisphereWarp2D.AtlasSide side,
            final Point2D point) {
        Objects.requireNonNull(side, "side");
        final Point2D checked = Objects.requireNonNull(point, "point");
        if (side != targetSide) {
            return checked;
        }
        for (final BoundaryFitMatch match : matches) {
            if (distance(checked, match.atlasPreviewPoint())
                    <= EXACT_EPSILON) {
                return match.tissuePreviewPoint();
            }
        }
        double sum = 0;
        double dx = 0;
        double dy = 0;
        for (final BoundaryFitMatch match : matches) {
            final double normalized = distance(
                    checked, match.atlasPreviewPoint()) / supportRadius;
            if (normalized >= 1) {
                continue;
            }
            final double remainder = 1 - normalized;
            final double weight = remainder * remainder * remainder
                    * remainder * (4 * normalized + 1);
            sum += weight;
            dx += weight * (match.tissuePreviewPoint().x()
                    - match.atlasPreviewPoint().x());
            dy += weight * (match.tissuePreviewPoint().y()
                    - match.atlasPreviewPoint().y());
        }
        if (sum <= 0) {
            return checked;
        }
        final double seamTaper = sectionMode == ReviewSectionMode.DISJOINED
                ? 1 : Math.min(1, distanceToMidline(checked) / supportRadius);
        // Preserve the compact Wendland falloff when only one control is
        // present. Normalizing by a sub-unit sum would otherwise cancel the
        // kernel and translate the whole support by the endpoint delta.
        final double normalization = Math.max(1, sum);
        return new Point2D(
                checked.x() + seamTaper * dx / normalization,
                checked.y() + seamTaper * dy / normalization);
    }

    public List<Point2D> mapPath(
            final ManualHemisphereWarp2D.AtlasSide side,
            final List<Point2D> path,
            final boolean closed) {
        final List<Point2D> checked = List.copyOf(Objects.requireNonNull(
                path, "path"));
        if (checked.isEmpty()) {
            return List.of();
        }
        final List<Point2D> mapped = new ArrayList<>();
        final int segmentCount = closed ? checked.size() : checked.size() - 1;
        for (int index = 0; index < segmentCount; index++) {
            final Point2D first = checked.get(index);
            final Point2D second = checked.get((index + 1) % checked.size());
            final int samples = Math.max(1, Math.min(64,
                    (int) Math.ceil(distance(first, second)
                            / Math.max(1, supportRadius * 0.25))));
            if (mapped.isEmpty()) {
                mapped.add(apply(side, first));
            }
            for (int sample = 1; sample <= samples; sample++) {
                final double fraction = sample / (double) samples;
                mapped.add(apply(side, new Point2D(
                        first.x() + fraction * (second.x() - first.x()),
                        first.y() + fraction * (second.y() - first.y()))));
            }
        }
        if (segmentCount == 0) {
            mapped.add(apply(side, checked.get(0)));
        }
        return List.copyOf(mapped);
    }

    private double distanceToMidline(final Point2D point) {
        final double dx = midline.ventral().x() - midline.dorsal().x();
        final double dy = midline.ventral().y() - midline.dorsal().y();
        return Math.abs(dx * (midline.dorsal().y() - point.y())
                - (midline.dorsal().x() - point.x()) * dy)
                / Math.max(1e-12, Math.hypot(dx, dy));
    }

    private static double distance(
            final Point2D first,
            final Point2D second) {
        return Math.hypot(first.x() - second.x(),
                first.y() - second.y());
    }
}
