package org.atlasalign.plugin.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.atlasalign.application.AlignmentReviewState;
import org.atlasalign.application.ReviewedTissueSupport;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.Point2D;

/** Shared placed-atlas/tissue support decision for every local stage. */
record PlacedAtlasSideSupport(
        ManualHemisphereWarp2D.AtlasSide side,
        List<Point2D> distributedInteriorPoints) {

    static final int MINIMUM_REMNANT_SAMPLES = 4;

    PlacedAtlasSideSupport {
        side = Objects.requireNonNull(side, "side");
        distributedInteriorPoints = List.copyOf(Objects.requireNonNull(
                distributedInteriorPoints, "distributedInteriorPoints"));
    }

    boolean remnantEligible() {
        return distributedInteriorPoints.size() >= MINIMUM_REMNANT_SAMPLES;
    }

    static PlacedAtlasSideSupport evaluate(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide side,
            final int preferredCount) {
        for (final int candidate : new int[]{24, 16, 12, 8, 6, 4}) {
            if (candidate > preferredCount) {
                continue;
            }
            try {
                return new PlacedAtlasSideSupport(side,
                        interiorPoints(state, side, candidate));
            } catch (final IllegalArgumentException unsupported) {
                // Try the next supported density.
            }
        }
        return new PlacedAtlasSideSupport(side, List.of());
    }

    static ManualHemisphereWarp2D.AtlasSide visibleHalfSide(
            final AlignmentReviewState state) {
        return switch (state.content().observedHemisphere()) {
            case LEFT -> ManualHemisphereWarp2D.AtlasSide.LEFT;
            case RIGHT -> ManualHemisphereWarp2D.AtlasSide.RIGHT;
            case BOTH, UNSURE -> throw new IllegalStateException(
                    "Choose the visible anatomical left or right side for this Half section.");
        };
    }

    static List<Point2D> interiorPoints(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide side,
            final int count) {
        final ReviewedTissueSupport reviewed = state.content()
                .reviewedTissueSupport().orElse(null);
        final BinaryMask mask = reviewed == null
                ? state.basis().segmentation().orElseThrow(() ->
                        new IllegalStateException(
                                "No copied-preview tissue contrast mask is available"))
                        .mask()
                : reviewed.supportMask();
        final List<Point2D> candidates = new ArrayList<>();
        final int step = Math.max(2,
                Math.min(mask.width(), mask.height()) / 24);
        for (int y = step; y + step < mask.height(); y += step) {
            for (int x = step; x + step < mask.width(); x += step) {
                final Point2D point = new Point2D(x, y);
                if (mask.contains(x, y)
                        && containsPlacedAtlasSide(state, side, point)
                        && mask.contains(x - 1, y)
                        && mask.contains(x + 1, y)
                        && mask.contains(x, y - 1)
                        && mask.contains(x, y + 1)) {
                    candidates.add(point);
                }
            }
        }
        if (candidates.size() < count) {
            throw new IllegalArgumentException(
                    "The reviewed side is too narrow for " + count
                            + " safely interior controls");
        }
        final List<Point2D> selected = new ArrayList<>();
        selected.add(candidates.get(0));
        while (selected.size() < count) {
            Point2D best = null;
            double bestDistance = -1;
            for (final Point2D candidate : candidates) {
                if (selected.contains(candidate)) {
                    continue;
                }
                double nearest = Double.POSITIVE_INFINITY;
                for (final Point2D existing : selected) {
                    nearest = Math.min(nearest,
                            distance(candidate, existing));
                }
                if (nearest > bestDistance) {
                    bestDistance = nearest;
                    best = candidate;
                }
            }
            selected.add(Objects.requireNonNull(best));
        }
        return List.copyOf(selected);
    }

    static boolean containsPlacedAtlasSide(
            final AlignmentReviewState state,
            final ManualHemisphereWarp2D.AtlasSide side,
            final Point2D previewPoint) {
        try {
            final Point2D beforeSidePlacement = state.content()
                    .manualSidePlacement().inverse(side, previewPoint);
            final Point2D beforePostOutline = state.content()
                    .postOutlinePreviewAdjustment().inverse()
                    .apply(beforeSidePlacement);
            final Point2D beforeOutline = state.content().outlineWarp()
                    .map(warp -> warp.inverse(beforePostOutline))
                    .orElse(beforePostOutline);
            final Point2D atlas = state.preOutlineAtlasToPreview()
                    .inverse().apply(beforeOutline);
            final double width = state.basis().atlas().atlasPlaneWidth();
            final double height = state.basis().atlas().atlasPlaneHeight();
            final double middle = (width - 1.0) * 0.5;
            final boolean requestedRawSide = side
                    == ManualHemisphereWarp2D.AtlasSide.LEFT
                    ? atlas.x() < middle - 1e-6
                    : atlas.x() > middle + 1e-6;
            return requestedRawSide
                    && atlas.x() >= 0 && atlas.x() <= width - 1.0
                    && atlas.y() >= 0 && atlas.y() <= height - 1.0;
        } catch (final RuntimeException unsafeInverse) {
            return false;
        }
    }

    private static double distance(
            final Point2D first,
            final Point2D second) {
        return Math.hypot(first.x() - second.x(),
                first.y() - second.y());
    }
}
