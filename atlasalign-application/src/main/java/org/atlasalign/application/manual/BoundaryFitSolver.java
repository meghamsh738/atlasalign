package org.atlasalign.application.manual;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;

/**
 * Deterministic coarse fit of reviewer-completed atlas/tissue border pairs.
 * It produces preview geometry only and never automatic evidence.
 */
public final class BoundaryFitSolver {

    public static final String SOLVER_REVISION =
            "boundary-assist-guided-manual-v4";
    public static final int MINIMUM_INCLUDED_MATCHES = 4;

    /** Fits only the completed correspondences supplied by the reviewer. */
    public BoundaryFitCandidate solve(final BoundaryFitRequest request) {
        final BoundaryFitRequest checked = Objects.requireNonNull(
                request, "request");
        requireBoundaries(checked);
        final List<BoundaryFitMatch> matches = checked.matches();
        final AffineTransform2D correction = fitCorrection(
                checked, matches);
        return new BoundaryFitCandidate(
                checked.withMatches(matches), matches, correction,
                rootMeanSquareResidual(matches, correction),
                SOLVER_REVISION, inputHash(checked, matches));
    }

    /**
     * Produces a progressive ghost from every available included pair.
     * One point translates, two points fit a similarity, and three or more
     * use the selected full model. This preview is deliberately not an
     * applyable or audited candidate.
     */
    public BoundaryFitPreview preview(final BoundaryFitRequest request) {
        final BoundaryFitRequest checked = Objects.requireNonNull(
                request, "request");
        requireBoundaries(checked);
        final List<BoundaryFitMatch> included = checked.matches().stream()
                .filter(BoundaryFitMatch::included).toList();
        if (included.isEmpty()) {
            throw failure(BoundaryFitFailureKind.INSUFFICIENT_COVERAGE,
                    "Match one atlas point to the tissue border to start the preview.",
                    "No completed boundary-fit matches are included");
        }
        final BoundaryFitPreviewKind kind;
        final AffineTransform2D correction;
        if (included.size() == 1) {
            kind = BoundaryFitPreviewKind.TRANSLATION;
            final BoundaryFitMatch match = included.get(0);
            correction = previewToPreview(1, 0,
                    match.tissuePreviewPoint().x()
                            - match.atlasPreviewPoint().x(),
                    0, 1,
                    match.tissuePreviewPoint().y()
                            - match.atlasPreviewPoint().y());
        } else {
            kind = included.size() >= 3
                    && checked.model() == BoundaryFitModel.ORTHOGONAL_XY
                    ? BoundaryFitPreviewKind.ORTHOGONAL_XY
                    : BoundaryFitPreviewKind.SIMILARITY;
            correction = fitCorrectionWithoutCoverage(
                    checked, included, kind);
        }
        return new BoundaryFitPreview(
                checked.withMatches(included), included, correction, kind,
                rootMeanSquareResidual(included, correction),
                SOLVER_REVISION, inputHash(checked, included));
    }

    private static void requireBoundaries(final BoundaryFitRequest request) {
        if (request.atlasBoundary().size() < MINIMUM_INCLUDED_MATCHES
                || request.tissueBoundary().size()
                < MINIMUM_INCLUDED_MATCHES) {
            throw failure(BoundaryFitFailureKind.INSUFFICIENT_BOUNDARY,
                    "There is not enough usable outer border to match. Place the atlas manually or edit the tissue crop first.",
                    "Guided fit requires at least four atlas and tissue boundary samples");
        }
    }

    private static AffineTransform2D fitCorrection(
            final BoundaryFitRequest request,
            final List<BoundaryFitMatch> matches) {
        final List<BoundaryFitMatch> included = matches.stream()
                .filter(BoundaryFitMatch::included).toList();
        requireCoverage(request, included);
        return fitCorrectionWithoutCoverage(request, included,
                request.model() == BoundaryFitModel.SIMILARITY
                        ? BoundaryFitPreviewKind.SIMILARITY
                        : BoundaryFitPreviewKind.ORTHOGONAL_XY);
    }

    private static AffineTransform2D fitCorrectionWithoutCoverage(
            final BoundaryFitRequest request,
            final List<BoundaryFitMatch> included,
            final BoundaryFitPreviewKind kind) {
        final AffineTransform2D current =
                request.currentOrientedAtlasToPreview();
        final AffineTransform2D inverse = current.inverse();
        final List<BoundaryFitMatch> canonical = included.stream()
                .map(match -> new BoundaryFitMatch(
                        match.id(), inverse.apply(
                                match.atlasPreviewPoint()),
                        match.tissuePreviewPoint(), match.origin(), true))
                .toList();
        final AffineTransform2D absolute = kind
                == BoundaryFitPreviewKind.SIMILARITY
                ? fitSimilarity(canonical)
                : fitOrthogonalAxes(canonical, 0);
        return inverse.andThen(absolute);
    }

    private static AffineTransform2D fitSimilarity(
            final List<BoundaryFitMatch> matches) {
        final Point2D sourceCentre = centroid(matches.stream()
                .map(BoundaryFitMatch::atlasPreviewPoint).toList());
        final Point2D targetCentre = centroid(matches.stream()
                .map(BoundaryFitMatch::tissuePreviewPoint).toList());
        double dot = 0;
        double cross = 0;
        double denominator = 0;
        for (final BoundaryFitMatch match : matches) {
            final double sx = match.atlasPreviewPoint().x()
                    - sourceCentre.x();
            final double sy = match.atlasPreviewPoint().y()
                    - sourceCentre.y();
            final double tx = match.tissuePreviewPoint().x()
                    - targetCentre.x();
            final double ty = match.tissuePreviewPoint().y()
                    - targetCentre.y();
            dot += sx * tx + sy * ty;
            cross += sx * ty - sy * tx;
            denominator += sx * sx + sy * sy;
        }
        if (denominator < 1e-12 || Math.hypot(dot, cross) < 1e-12) {
            throw failure(BoundaryFitFailureKind.INSUFFICIENT_COVERAGE,
                    "Use at least four matches spread around the visible tissue border.",
                    "Similarity fit is rank deficient");
        }
        final double scale = Math.hypot(dot, cross) / denominator;
        final double cosine = dot / Math.hypot(dot, cross);
        final double sine = cross / Math.hypot(dot, cross);
        final double m00 = scale * cosine;
        final double m01 = -scale * sine;
        final double m10 = scale * sine;
        final double m11 = scale * cosine;
        return atlasToPreview(m00, m01,
                targetCentre.x() - m00 * sourceCentre.x()
                        - m01 * sourceCentre.y(),
                m10, m11,
                targetCentre.y() - m10 * sourceCentre.x()
                        - m11 * sourceCentre.y());
    }

    private static AffineTransform2D fitOrthogonalAxes(
            final List<BoundaryFitMatch> matches,
            final double sourceAxisRadians) {
        final Point2D sourceCentre = centroid(matches.stream()
                .map(BoundaryFitMatch::atlasPreviewPoint).toList());
        final Point2D targetCentre = centroid(matches.stream()
                .map(BoundaryFitMatch::tissuePreviewPoint).toList());
        final double sourceCosine = Math.cos(sourceAxisRadians);
        final double sourceSine = Math.sin(sourceAxisRadians);
        double bestError = Double.POSITIVE_INFINITY;
        AffineTransform2D best = null;
        final double similarityAngle = sourceAxisRadians
                + rotationRadians(fitSimilarity(matches));
        for (int sample = -80; sample <= 80; sample++) {
            final double angle = similarityAngle
                    + Math.toRadians(sample * 0.25);
            final double cosine = Math.cos(angle);
            final double sine = Math.sin(angle);
            double numeratorX = 0;
            double denominatorX = 0;
            double numeratorY = 0;
            double denominatorY = 0;
            for (final BoundaryFitMatch match : matches) {
                final double dx = match.atlasPreviewPoint().x()
                        - sourceCentre.x();
                final double dy = match.atlasPreviewPoint().y()
                        - sourceCentre.y();
                final double u = sourceCosine * dx + sourceSine * dy;
                final double v = -sourceSine * dx + sourceCosine * dy;
                final double tx = match.tissuePreviewPoint().x()
                        - targetCentre.x();
                final double ty = match.tissuePreviewPoint().y()
                        - targetCentre.y();
                final double targetU = cosine * tx + sine * ty;
                final double targetV = -sine * tx + cosine * ty;
                numeratorX += u * targetU;
                denominatorX += u * u;
                numeratorY += v * targetV;
                denominatorY += v * v;
            }
            if (denominatorX < 1e-12 || denominatorY < 1e-12) {
                continue;
            }
            final double scaleX = numeratorX / denominatorX;
            final double scaleY = numeratorY / denominatorY;
            if (scaleX <= 0 || scaleY <= 0) {
                continue;
            }
            final double m00 = cosine * scaleX * sourceCosine
                    + sine * scaleY * sourceSine;
            final double m01 = cosine * scaleX * sourceSine
                    - sine * scaleY * sourceCosine;
            final double m10 = sine * scaleX * sourceCosine
                    - cosine * scaleY * sourceSine;
            final double m11 = sine * scaleX * sourceSine
                    + cosine * scaleY * sourceCosine;
            final AffineTransform2D candidate = atlasToPreview(
                    m00, m01,
                    targetCentre.x() - m00 * sourceCentre.x()
                            - m01 * sourceCentre.y(),
                    m10, m11,
                    targetCentre.y() - m10 * sourceCentre.x()
                            - m11 * sourceCentre.y());
            final double error = squaredError(matches, candidate);
            if (error < bestError) {
                bestError = error;
                best = candidate;
            }
        }
        if (best == null) {
            throw failure(BoundaryFitFailureKind.INSUFFICIENT_COVERAGE,
                    "The matches do not span enough width and height for separate resizing. Spread them farther apart or use proportional fitting.",
                    "Orthogonal-axis fit is rank deficient");
        }
        return best;
    }

    private static void requireCoverage(
            final BoundaryFitRequest request,
            final List<BoundaryFitMatch> matches) {
        if (matches.size() < MINIMUM_INCLUDED_MATCHES) {
            throw failure(BoundaryFitFailureKind.INSUFFICIENT_COVERAGE,
                    "Use at least four included matches spread around the visible tissue border.",
                    "Only " + matches.size()
                            + " boundary-fit matches are included");
        }
        final double diagonal = Math.hypot(
                request.previewWidth(), request.previewHeight());
        final double sourceSpan = maximumDistance(matches.stream()
                .map(BoundaryFitMatch::atlasPreviewPoint).toList());
        final double targetSpan = maximumDistance(matches.stream()
                .map(BoundaryFitMatch::tissuePreviewPoint).toList());
        if (sourceSpan < 0.12 * diagonal
                || targetSpan < 0.12 * diagonal
                || perpendicularSpan(matches.stream()
                        .map(BoundaryFitMatch::atlasPreviewPoint).toList())
                        < 0.025 * diagonal
                || perpendicularSpan(matches.stream()
                        .map(BoundaryFitMatch::tissuePreviewPoint).toList())
                        < 0.025 * diagonal) {
            throw failure(BoundaryFitFailureKind.INSUFFICIENT_COVERAGE,
                    "Spread the included matches around more than one edge of the visible border before fitting.",
                    "Boundary matches do not provide two-axis preview coverage");
        }
    }

    /** Maximum point distance from the line through the farthest pair. */
    private static double perpendicularSpan(final List<Point2D> points) {
        Point2D first = null;
        Point2D second = null;
        double longest = 0;
        for (int left = 0; left < points.size(); left++) {
            for (int right = left + 1; right < points.size(); right++) {
                final double candidate = distance(
                        points.get(left), points.get(right));
                if (candidate > longest) {
                    longest = candidate;
                    first = points.get(left);
                    second = points.get(right);
                }
            }
        }
        if (first == null || second == null || longest < 1e-12) {
            return 0;
        }
        double maximum = 0;
        for (final Point2D point : points) {
            final double cross = Math.abs(
                    (second.x() - first.x()) * (point.y() - first.y())
                    - (second.y() - first.y()) * (point.x() - first.x()));
            maximum = Math.max(maximum, cross / longest);
        }
        return maximum;
    }

    private static double rootMeanSquareResidual(
            final List<BoundaryFitMatch> matches,
            final AffineTransform2D correction) {
        final List<BoundaryFitMatch> included = matches.stream()
                .filter(BoundaryFitMatch::included).toList();
        return Math.sqrt(squaredError(included, correction)
                / included.size());
    }

    private static double squaredError(
            final List<BoundaryFitMatch> matches,
            final AffineTransform2D transform) {
        double error = 0;
        for (final BoundaryFitMatch match : matches) {
            final double residual = distance(transform.apply(
                    match.atlasPreviewPoint()),
                    match.tissuePreviewPoint());
            error += residual * residual;
        }
        return error;
    }

    private static Point2D centroid(final List<Point2D> points) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("Cannot average no points");
        }
        double x = 0;
        double y = 0;
        for (final Point2D point : points) {
            x += point.x();
            y += point.y();
        }
        return new Point2D(x / points.size(), y / points.size());
    }

    private static double maximumDistance(final List<Point2D> points) {
        double maximum = 0;
        for (int first = 0; first < points.size(); first++) {
            for (int second = first + 1; second < points.size(); second++) {
                maximum = Math.max(maximum,
                        distance(points.get(first), points.get(second)));
            }
        }
        return maximum;
    }

    private static double rotationRadians(
            final AffineTransform2D transform) {
        return Math.atan2(transform.m10(), transform.m00());
    }

    private static double distance(
            final Point2D first,
            final Point2D second) {
        return Math.hypot(first.x() - second.x(),
                first.y() - second.y());
    }

    private static AffineTransform2D atlasToPreview(
            final double m00,
            final double m01,
            final double m02,
            final double m10,
            final double m11,
            final double m12) {
        return new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                m00, m01, m02, m10, m11, m12);
    }

    private static AffineTransform2D previewToPreview(
            final double m00,
            final double m01,
            final double m02,
            final double m10,
            final double m11,
            final double m12) {
        return new AffineTransform2D(
                CoordinateSpace2D.PREVIEW_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                m00, m01, m02, m10, m11, m12);
    }

    private static String inputHash(
            final BoundaryFitRequest request,
            final List<BoundaryFitMatch> matches) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, request.model().name());
            update(digest, request.sectionMode().name());
            update(digest, request.planeHash());
            update(digest, request.placementHash());
            update(digest, request.tissueSupportHash());
            update(digest, request.sourceHash());
            update(digest, request.atlasHash());
            update(digest, request.contentRevision());
            update(digest, request.currentOrientedAtlasToPreview().m00());
            update(digest, request.currentOrientedAtlasToPreview().m01());
            update(digest, request.currentOrientedAtlasToPreview().m02());
            update(digest, request.currentOrientedAtlasToPreview().m10());
            update(digest, request.currentOrientedAtlasToPreview().m11());
            update(digest, request.currentOrientedAtlasToPreview().m12());
            for (final BoundaryFitMatch match : matches) {
                update(digest, match.id());
                update(digest, match.atlasPreviewPoint().x());
                update(digest, match.atlasPreviewPoint().y());
                update(digest, match.tissuePreviewPoint().x());
                update(digest, match.tissuePreviewPoint().y());
                update(digest, match.origin().name());
                update(digest, match.included() ? 1L : 0L);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(
            final MessageDigest digest,
            final String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void update(
            final MessageDigest digest,
            final double value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(
                Double.doubleToLongBits(value)).array());
    }

    private static void update(
            final MessageDigest digest,
            final long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static BoundaryFitException failure(
            final BoundaryFitFailureKind kind,
            final String reviewerMessage,
            final String technicalMessage) {
        return new BoundaryFitException(kind, reviewerMessage,
                technicalMessage);
    }
}
