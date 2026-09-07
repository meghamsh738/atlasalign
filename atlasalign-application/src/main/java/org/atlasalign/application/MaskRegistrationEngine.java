package org.atlasalign.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.SimilarityTransform2D;

/**
 * Reproducible moment-seeded similarity and affine mask registration.
 */
public final class MaskRegistrationEngine {

    private static final double MINIMUM_SCALE = 0.1;
    private static final double MAXIMUM_SCALE = 8.0;
    private static final double MAXIMUM_ROTATION = Math.toRadians(30);
    private static final int MAXIMUM_COMPLETE_BOUNDARY_POINTS =
            4_096;

    public BaselineRegistrationProposal register(
            final BinaryMask atlasPlaneMask,
            final BinaryMask previewMask,
            final AllenCoronalLevel coronalLevel,
            final TissueGeometryResult geometry) {
        requireNonEmpty(atlasPlaneMask, "atlasPlaneMask");
        requireNonEmpty(previewMask, "previewMask");
        Objects.requireNonNull(coronalLevel, "coronalLevel");
        Objects.requireNonNull(geometry, "geometry");

        final Moments source = moments(atlasPlaneMask);
        final Moments destination = moments(previewMask);
        final RegistrationObjective objective =
                RegistrationObjective.create(
                        atlasPlaneMask, previewMask);
        final SimilarityTransform2D seed = initialSimilarity(
                source, destination);
        final SimilarityTransform2D optimizedSimilarity =
                optimizeSimilarity(objective, seed);
        final double optimizedSimilarityDice = score(
                atlasPlaneMask,
                previewMask,
                optimizedSimilarity.asAffine());
        final SimilarityTransform2D similarity =
                optimizedSimilarity;
        final double similarityDice =
                optimizedSimilarityDice;
        final AffineTransform2D affineCandidate = optimizeAffine(
                objective, similarity.asAffine());
        final double candidateDice =
                score(atlasPlaneMask, previewMask, affineCandidate);
        final AffineTransform2D affine = candidateDice >= similarityDice
                ? affineCandidate : similarity.asAffine();
        final double affineDice = Math.max(
                candidateDice, similarityDice);
        return new BaselineRegistrationProposal(
                coronalLevel,
                geometry,
                similarity,
                affine,
                objective.mode(),
                similarityDice,
                affineDice);
    }

    private static SimilarityTransform2D initialSimilarity(
            final Moments source,
            final Moments destination) {
        final double scale = clamp(
                Math.sqrt(destination.trace() / source.trace()),
                MINIMUM_SCALE,
                MAXIMUM_SCALE);
        final double rotation = clamp(
                normalizeRotation(destination.angle() - source.angle()),
                -MAXIMUM_ROTATION,
                MAXIMUM_ROTATION);
        final double cosine = Math.cos(rotation);
        final double sine = Math.sin(rotation);
        final double translationX = destination.centroidX()
                - scale * (cosine * source.centroidX()
                - sine * source.centroidY());
        final double translationY = destination.centroidY()
                - scale * (sine * source.centroidX()
                + cosine * source.centroidY());
        return new SimilarityTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                scale,
                rotation,
                translationX,
                translationY);
    }

    private static SimilarityTransform2D optimizeSimilarity(
            final RegistrationObjective objective,
            final SimilarityTransform2D seed) {
        double scale = seed.scale();
        double rotation = seed.rotationRadians();
        double translationX = seed.translationX();
        double translationY = seed.translationY();
        double best = objective.score(seed.asAffine());
        double translationStep = 4;
        double rotationStep = Math.toRadians(4);
        double scaleStep = 0.04;
        for (int level = 0; level < 9; level++) {
            boolean improved;
            do {
                improved = false;
                for (int parameter = 0; parameter < 4; parameter++) {
                    for (final int direction : new int[]{-1, 1}) {
                        final double candidateScale = clamp(
                                scale + (parameter == 0
                                        ? direction * scaleStep : 0),
                                MINIMUM_SCALE,
                                MAXIMUM_SCALE);
                        final double candidateRotation = clamp(
                                rotation + (parameter == 1
                                        ? direction * rotationStep : 0),
                                -MAXIMUM_ROTATION,
                                MAXIMUM_ROTATION);
                        final double candidateX = translationX
                                + (parameter == 2
                                ? direction * translationStep : 0);
                        final double candidateY = translationY
                                + (parameter == 3
                                ? direction * translationStep : 0);
                        final SimilarityTransform2D candidate =
                                new SimilarityTransform2D(
                                        seed.sourceSpace(),
                                        seed.destinationSpace(),
                                        candidateScale,
                                        candidateRotation,
                                        candidateX,
                                        candidateY);
                        final double candidateScore =
                                objective.score(candidate.asAffine());
                        if (candidateScore > best + 1e-12) {
                            scale = candidateScale;
                            rotation = candidateRotation;
                            translationX = candidateX;
                            translationY = candidateY;
                            best = candidateScore;
                            improved = true;
                        }
                    }
                }
            } while (improved);
            translationStep *= 0.5;
            rotationStep *= 0.5;
            scaleStep *= 0.5;
        }
        return new SimilarityTransform2D(
                seed.sourceSpace(),
                seed.destinationSpace(),
                scale,
                rotation,
                translationX,
                translationY);
    }

    private static AffineTransform2D optimizeAffine(
            final RegistrationObjective objective,
            final AffineTransform2D seed) {
        final double[] values = {
            seed.m00(), seed.m01(), seed.m02(),
            seed.m10(), seed.m11(), seed.m12()
        };
        double best = objective.score(seed);
        double linearStep = 0.03;
        double translationStep = 2;
        for (int level = 0; level < 8; level++) {
            boolean improved;
            do {
                improved = false;
                for (int parameter = 0; parameter < values.length;
                        parameter++) {
                    final double step = parameter == 2 || parameter == 5
                            ? translationStep : linearStep;
                    for (final int direction : new int[]{-1, 1}) {
                        final double[] candidateValues = values.clone();
                        candidateValues[parameter] += direction * step;
                        final AffineTransform2D candidate =
                                affine(seed, candidateValues);
                        if (!plausible(candidate)) {
                            continue;
                        }
                        final double candidateScore =
                                objective.score(candidate);
                        if (candidateScore > best + 1e-12) {
                            System.arraycopy(
                                    candidateValues,
                                    0,
                                    values,
                                    0,
                                    values.length);
                            best = candidateScore;
                            improved = true;
                        }
                    }
                }
            } while (improved);
            linearStep *= 0.5;
            translationStep *= 0.5;
        }
        return affine(seed, values);
    }

    private static AffineTransform2D affine(
            final AffineTransform2D seed,
            final double[] values) {
        return new AffineTransform2D(
                seed.sourceSpace(),
                seed.destinationSpace(),
                values[0],
                values[1],
                values[2],
                values[3],
                values[4],
                values[5]);
    }

    private static boolean plausible(final AffineTransform2D transform) {
        final double determinant = transform.determinant();
        if (determinant <= MINIMUM_SCALE * MINIMUM_SCALE
                || determinant >= MAXIMUM_SCALE * MAXIMUM_SCALE) {
            return false;
        }
        final double first = transform.m00() * transform.m00()
                + transform.m10() * transform.m10();
        final double second = transform.m01() * transform.m01()
                + transform.m11() * transform.m11();
        final double trace = first + second;
        final double discriminant = Math.sqrt(Math.max(
                0, trace * trace - 4 * determinant * determinant));
        final double maximumSingular =
                Math.sqrt((trace + discriminant) * 0.5);
        final double minimumSingular =
                Math.sqrt((trace - discriminant) * 0.5);
        return minimumSingular >= MINIMUM_SCALE
                && maximumSingular <= MAXIMUM_SCALE
                && maximumSingular / minimumSingular <= 1.5;
    }

    private static double score(
            final BinaryMask source,
            final BinaryMask destination,
            final AffineTransform2D transform) {
        return MaskWarp.dice(
                MaskWarp.warp(
                        source,
                        destination.width(),
                        destination.height(),
                        transform),
                destination);
    }

    private static Moments moments(final BinaryMask mask) {
        double sumX = 0;
        double sumY = 0;
        final int count = mask.foregroundCount();
        for (int y = 0; y < mask.height(); y++) {
            for (int x = 0; x < mask.width(); x++) {
                if (mask.contains(x, y)) {
                    sumX += x;
                    sumY += y;
                }
            }
        }
        final double centroidX = sumX / count;
        final double centroidY = sumY / count;
        double xx = 0;
        double yy = 0;
        double xy = 0;
        for (int y = 0; y < mask.height(); y++) {
            for (int x = 0; x < mask.width(); x++) {
                if (mask.contains(x, y)) {
                    final double offsetX = x - centroidX;
                    final double offsetY = y - centroidY;
                    xx += offsetX * offsetX;
                    yy += offsetY * offsetY;
                    xy += offsetX * offsetY;
                }
            }
        }
        xx /= count;
        yy /= count;
        xy /= count;
        final double trace = xx + yy;
        if (trace <= 1e-12) {
            throw new IllegalArgumentException(
                    "Mask has insufficient spatial extent");
        }
        return new Moments(
                centroidX,
                centroidY,
                trace,
                0.5 * Math.atan2(2 * xy, xx - yy));
    }

    private static double normalizeRotation(final double angle) {
        double normalized = angle;
        while (normalized > Math.PI / 2) {
            normalized -= Math.PI;
        }
        while (normalized < -Math.PI / 2) {
            normalized += Math.PI;
        }
        return normalized;
    }

    private static double clamp(
            final double value,
            final double minimum,
            final double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static int pointsPerTask(
            final int pointCount,
            final int taskCount) {
        if (pointCount <= 0 || taskCount <= 0
                || taskCount > pointCount) {
            throw new IllegalArgumentException(
                    "Task partition requires 1 <= taskCount"
                            + " <= pointCount");
        }
        return pointCount / taskCount
                + (pointCount % taskCount == 0 ? 0 : 1);
    }

    private static void requireNonEmpty(
            final BinaryMask mask,
            final String name) {
        Objects.requireNonNull(mask, name);
        if (mask.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must contain foreground");
        }
    }

    private record Moments(
            double centroidX,
            double centroidY,
            double trace,
            double angle) {
    }

    private record RegistrationObjective(
            List<Point2D> sourceBoundary,
            List<Point2D> destinationBoundary,
            NearestPointIndex sourceIndex,
            NearestPointIndex destinationIndex,
            double[] pointDistances,
            RegistrationObjectiveMode mode) {

        static RegistrationObjective create(
                final BinaryMask source,
                final BinaryMask destination) {
            final BoundarySet fullSource = boundaries(source);
            final BoundarySet fullDestination =
                    boundaries(destination);
            final boolean useExterior =
                    usesExteriorBoundary(
                            fullSource, fullDestination);
            return new RegistrationObjective(
                    selectedBoundary(
                            fullSource,
                            fullDestination,
                            true),
                    selectedBoundary(
                            fullSource,
                            fullDestination,
                            false),
                    useExterior
                            ? RegistrationObjectiveMode
                                    .EXACT_FULL_RESOLUTION_EXTERIOR_BOUNDARY
                            : RegistrationObjectiveMode
                                    .EXACT_FULL_RESOLUTION_COMPLETE_BOUNDARY);
        }

        private RegistrationObjective(
                final List<Point2D> sourceBoundary,
                final List<Point2D> destinationBoundary,
                final RegistrationObjectiveMode mode) {
            this(
                    sourceBoundary,
                    destinationBoundary,
                    new NearestPointIndex(sourceBoundary),
                    new NearestPointIndex(destinationBoundary),
                    new double[Math.addExact(
                            sourceBoundary.size(),
                            destinationBoundary.size())],
                    mode);
        }

        double score(final AffineTransform2D transform) {
            final AffineTransform2D inverse = transform.inverse();
            final int sourceSize = sourceBoundary.size();
            final int taskCount = Math.min(
                    Math.max(
                            1,
                            Runtime.getRuntime()
                                    .availableProcessors()),
                    pointDistances.length);
            final int pointsPerTask = pointsPerTask(
                    pointDistances.length, taskCount);
            IntStream.range(0, taskCount)
                    .parallel()
                    .forEach(task -> {
                        final int first = Math.toIntExact(
                                (long) task * pointsPerTask);
                        final int afterLast = Math.toIntExact(
                                Math.min(
                                        (long) pointDistances.length,
                                        (long) first + pointsPerTask));
                        for (int index = first;
                                index < afterLast;
                                index++) {
                            if (index < sourceSize) {
                                final Point2D point =
                                        sourceBoundary.get(index);
                                final double transformedX =
                                        transform.m00() * point.x()
                                                + transform.m01()
                                                * point.y()
                                                + transform.m02();
                                final double transformedY =
                                        transform.m10() * point.x()
                                                + transform.m11()
                                                * point.y()
                                                + transform.m12();
                                pointDistances[index] =
                                        destinationIndex
                                                .nearestSquaredDistance(
                                                        transformedX,
                                                        transformedY);
                            } else {
                                final Point2D point =
                                        destinationBoundary.get(
                                                index - sourceSize);
                                final double transformedX =
                                        inverse.m00() * point.x()
                                                + inverse.m01()
                                                * point.y()
                                                + inverse.m02();
                                final double transformedY =
                                        inverse.m10() * point.x()
                                                + inverse.m11()
                                                * point.y()
                                                + inverse.m12();
                                pointDistances[index] =
                                        sourceIndex
                                                .nearestSquaredDistance(
                                                        transformedX,
                                                        transformedY);
                            }
                        }
                    });
            double squaredDistance = 0;
            for (final double pointDistance : pointDistances) {
                squaredDistance += pointDistance;
            }
            return -squaredDistance
                    / pointDistances.length;
        }

        /**
         * Returns exact sparse topology or an exact exterior outline.
         *
         * <p>Sparse internal voids remain useful shape evidence. When the
         * complete boundary becomes highly fragmented, however, those
         * unverified signal voids must not dominate gross tissue-outline
         * registration. A deterministic flood fill then selects the exterior
         * outline.</p>
         */
        private static BoundarySet boundaries(
                final BinaryMask mask) {
            final boolean[] exteriorBackground =
                    exteriorBackground(mask);
            final List<Point2D> complete = new ArrayList<>();
            final List<Point2D> exterior = new ArrayList<>();
            for (int y = 0; y < mask.height(); y++) {
                for (int x = 0; x < mask.width(); x++) {
                    if (!mask.contains(x, y)) {
                        continue;
                    }
                    final boolean adjacentBackground =
                            !mask.contains(x - 1, y)
                            || !mask.contains(x + 1, y)
                            || !mask.contains(x, y - 1)
                            || !mask.contains(x, y + 1);
                    if (adjacentBackground) {
                        complete.add(new Point2D(x, y));
                    }
                    final boolean adjacentExterior =
                            isExterior(
                                    exteriorBackground,
                                    mask.width(),
                                    mask.height(),
                                    x - 1,
                                    y)
                            || isExterior(
                                    exteriorBackground,
                                    mask.width(),
                                    mask.height(),
                                    x + 1,
                                    y)
                            || isExterior(
                                    exteriorBackground,
                                    mask.width(),
                                    mask.height(),
                                    x,
                                    y - 1)
                            || isExterior(
                                    exteriorBackground,
                                    mask.width(),
                                    mask.height(),
                                    x,
                                    y + 1);
                    if (adjacentExterior) {
                        exterior.add(new Point2D(x, y));
                    }
                }
            }
            if (complete.isEmpty() || exterior.isEmpty()) {
                throw new IllegalArgumentException(
                    "Registration masks need a measurable boundary");
            }
            return new BoundarySet(
                    List.copyOf(complete),
                    List.copyOf(exterior));
        }

        private static List<Point2D> selectedBoundary(
                final BoundarySet source,
                final BoundarySet destination,
                final boolean selectSource) {
            final BoundarySet selected =
                    selectSource ? source : destination;
            final boolean fragmented = usesExteriorBoundary(
                    source, destination);
            return fragmented
                    ? selected.exterior()
                    : selected.complete();
        }

        private static boolean usesExteriorBoundary(
                final BoundarySet source,
                final BoundarySet destination) {
            return source.complete().size()
                            > MAXIMUM_COMPLETE_BOUNDARY_POINTS
                    || destination.complete().size()
                            > MAXIMUM_COMPLETE_BOUNDARY_POINTS;
        }

        private static boolean[] exteriorBackground(
                final BinaryMask mask) {
            final int width = mask.width();
            final int height = mask.height();
            final boolean[] exterior = new boolean[
                    Math.multiplyExact(width, height)];
            final ArrayDeque<Integer> pending =
                    new ArrayDeque<>();
            for (int x = 0; x < width; x++) {
                enqueueBackground(
                        mask, exterior, pending, x, 0);
                enqueueBackground(
                        mask, exterior, pending, x, height - 1);
            }
            for (int y = 0; y < height; y++) {
                enqueueBackground(
                        mask, exterior, pending, 0, y);
                enqueueBackground(
                        mask, exterior, pending, width - 1, y);
            }
            while (!pending.isEmpty()) {
                final int index = pending.removeFirst();
                final int x = index % width;
                final int y = index / width;
                enqueueBackground(
                        mask, exterior, pending, x - 1, y);
                enqueueBackground(
                        mask, exterior, pending, x + 1, y);
                enqueueBackground(
                        mask, exterior, pending, x, y - 1);
                enqueueBackground(
                        mask, exterior, pending, x, y + 1);
            }
            return exterior;
        }

        private static void enqueueBackground(
                final BinaryMask mask,
                final boolean[] exterior,
                final ArrayDeque<Integer> pending,
                final int x,
                final int y) {
            if (x < 0 || x >= mask.width()
                    || y < 0 || y >= mask.height()
                    || mask.contains(x, y)) {
                return;
            }
            final int index = y * mask.width() + x;
            if (!exterior[index]) {
                exterior[index] = true;
                pending.addLast(index);
            }
        }

        private static boolean isExterior(
                final boolean[] exterior,
                final int width,
                final int height,
                final int x,
                final int y) {
            return x < 0 || x >= width
                    || y < 0 || y >= height
                    || exterior[y * width + x];
        }

        private record BoundarySet(
                List<Point2D> complete,
                List<Point2D> exterior) {
        }

    }
}
