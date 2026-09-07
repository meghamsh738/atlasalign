package org.atlasalign.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;

/**
 * Immutable compactly supported local displacement in preview-pixel space.
 * Both input and output coordinates are pixel centers. The warp is intended
 * only for copied atlas geometry after the global alignment; it never reads or
 * changes source-image pixels.
 */
public final class ConstrainedLocalWarp2D {

    public static final String ALGORITHM_REVISION =
            "wendland-c2-preview-local-warp-v1";
    public static final double REGULARIZATION = 0.001;

    private static final int DOMAIN_SAMPLE_COUNT = 33;
    private static final double MINIMUM_HULL_AREA_FRACTION = 0.01;
    private static final double MAXIMUM_REQUESTED_DISPLACEMENT_FRACTION = 0.25;
    private static final double MINIMUM_JACOBIAN_DETERMINANT = 0.15;
    private static final double MINIMUM_SINGULAR_VALUE = 0.25;
    private static final double MAXIMUM_SINGULAR_VALUE = 4.0;
    private static final double MAXIMUM_ANISOTROPY = 4.0;
    private static final int MAXIMUM_INVERSE_ITERATIONS = 32;
    private static final int MAXIMUM_LINE_SEARCH_STEPS = 16;
    private static final double INVERSE_TOLERANCE = 1e-9;
    private static final double MINIMUM_SOLVER_PIVOT = 1e-14;

    private final List<Point2D> fitSourcePoints;
    private final List<Point2D> fitTargetPoints;
    private final double[] xWeights;
    private final double[] yWeights;
    private final List<Double> immutableXWeights;
    private final List<Double> immutableYWeights;
    private final double supportRadius;
    private final Diagnostics diagnostics;

    private ConstrainedLocalWarp2D(
            final List<Point2D> fitSourcePoints,
            final List<Point2D> fitTargetPoints,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius,
            final Diagnostics diagnostics) {
        this.fitSourcePoints = List.copyOf(fitSourcePoints);
        this.fitTargetPoints = List.copyOf(fitTargetPoints);
        this.xWeights = xWeights.clone();
        this.yWeights = yWeights.clone();
        this.immutableXWeights = immutableDoubles(this.xWeights);
        this.immutableYWeights = immutableDoubles(this.yWeights);
        this.supportRadius = supportRadius;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /** Fits without independent check landmarks. */
    public static ConstrainedLocalWarp2D fit(
            final List<Point2D> fitSourcePoints,
            final List<Point2D> fitTargetPoints,
            final int previewWidth,
            final int previewHeight) {
        return fit(fitSourcePoints, fitTargetPoints, List.of(), List.of(),
                previewWidth, previewHeight);
    }

    /**
     * Fits from ordered {@code FIT} pairs. Ordered {@code CHECK} pairs are
     * excluded from fitting and used only for safety sampling and check RMS.
     */
    public static ConstrainedLocalWarp2D fit(
            final List<Point2D> fitSourcePoints,
            final List<Point2D> fitTargetPoints,
            final List<Point2D> checkSourcePoints,
            final List<Point2D> checkTargetPoints,
            final int previewWidth,
            final int previewHeight) {
        if (previewWidth <= 0 || previewHeight <= 0) {
            throw new IllegalArgumentException(
                    "Preview width and height must be positive");
        }
        final List<Point2D> sources = checkedPoints(
                fitSourcePoints, "FIT source");
        final List<Point2D> targets = checkedPoints(
                fitTargetPoints, "FIT target");
        final List<Point2D> checkSources = checkedPoints(
                checkSourcePoints, "CHECK source");
        final List<Point2D> checkTargets = checkedPoints(
                checkTargetPoints, "CHECK target");
        if (sources.size() != targets.size() || sources.size() < 4) {
            throw new IllegalArgumentException(
                    "At least four FIT source/target pairs are required");
        }
        if (checkSources.size() != checkTargets.size()) {
            throw new IllegalArgumentException(
                    "CHECK source and target counts must match");
        }
        rejectDuplicateSources(sources);

        final double previewDiagonal = Math.hypot(
                (double) previewWidth, (double) previewHeight);
        final double previewArea = (double) previewWidth * previewHeight;
        final double hullArea = convexHullArea(sources);
        if (!Double.isFinite(hullArea)
                || hullArea < MINIMUM_HULL_AREA_FRACTION * previewArea) {
            throw new IllegalArgumentException(
                    "FIT sources must cover at least 1% of the preview area");
        }

        final double[] requestedX = new double[sources.size()];
        final double[] requestedY = new double[sources.size()];
        final double maximumRequest =
                MAXIMUM_REQUESTED_DISPLACEMENT_FRACTION * previewDiagonal;
        for (int index = 0; index < sources.size(); index++) {
            requestedX[index] = targets.get(index).x()
                    - sources.get(index).x();
            requestedY[index] = targets.get(index).y()
                    - sources.get(index).y();
            final double magnitude = Math.hypot(
                    requestedX[index], requestedY[index]);
            if (!Double.isFinite(magnitude) || magnitude > maximumRequest) {
                throw new IllegalArgumentException(
                        "Each requested FIT displacement must be at most 25% of the preview diagonal");
            }
        }

        final double supportRadius = supportRadius(
                sources, previewDiagonal);
        final double[][] system = kernelSystem(sources, supportRadius);
        final double[][] weights = solve(system, requestedX, requestedY);
        final double[] xWeights = weights[0];
        final double[] yWeights = weights[1];

        final String contentHash = contentHash(
                previewWidth, previewHeight, sources, targets,
                supportRadius, xWeights, yWeights);
        final MutableDiagnostics sampled = sampleSafetyDiagnostics(
                sources, checkSources, xWeights, yWeights, supportRadius,
                previewWidth, previewHeight);
        rejectUnsafeJacobian(sampled);

        final double fitRms = residualRms(
                sources, targets, sources, xWeights, yWeights, supportRadius);
        final OptionalDouble checkRms = checkSources.isEmpty()
                ? OptionalDouble.empty()
                : OptionalDouble.of(residualRms(
                        checkSources, checkTargets, sources,
                        xWeights, yWeights, supportRadius));
        requireFiniteReported(sampled, fitRms, checkRms);

        final Diagnostics diagnostics = new Diagnostics(
                sources.size(), supportRadius,
                sampled.minimumDeterminant,
                sampled.minimumSingularValue,
                sampled.maximumSingularValue,
                sampled.maximumAnisotropy,
                sampled.maximumDisplacement,
                fitRms, checkRms, contentHash);
        return new ConstrainedLocalWarp2D(
                sources, targets, xWeights, yWeights,
                supportRadius, diagnostics);
    }

    /** Applies the preview-pixel to preview-pixel forward warp. */
    public Point2D apply(final Point2D previewPixelCenter) {
        Objects.requireNonNull(previewPixelCenter, "previewPixelCenter");
        final double[] displacement = displacement(
                previewPixelCenter, fitSourcePoints,
                xWeights, yWeights, supportRadius);
        return new Point2D(
                previewPixelCenter.x() + displacement[0],
                previewPixelCenter.y() + displacement[1]);
    }

    /** Returns the analytic 2x2 Jacobian of the forward warp. */
    public Jacobian2D jacobian(final Point2D previewPixelCenter) {
        Objects.requireNonNull(previewPixelCenter, "previewPixelCenter");
        return jacobian(previewPixelCenter, fitSourcePoints,
                xWeights, yWeights, supportRadius);
    }

    /**
     * Inverts a point with bounded, damped Newton iterations. Fitting already
     * rejects unsafe sampled Jacobians; failure to converge still fails closed.
     */
    public Point2D inverse(final Point2D warpedPreviewPixelCenter) {
        Objects.requireNonNull(
                warpedPreviewPixelCenter, "warpedPreviewPixelCenter");
        Point2D estimate = warpedPreviewPixelCenter;
        double residualNorm = residualNorm(estimate, warpedPreviewPixelCenter);
        if (residualNorm <= INVERSE_TOLERANCE) {
            return estimate;
        }
        for (int iteration = 0;
                iteration < MAXIMUM_INVERSE_ITERATIONS; iteration++) {
            final Point2D mapped = apply(estimate);
            final double residualX = mapped.x()
                    - warpedPreviewPixelCenter.x();
            final double residualY = mapped.y()
                    - warpedPreviewPixelCenter.y();
            residualNorm = Math.hypot(residualX, residualY);
            if (!Double.isFinite(residualNorm)) {
                break;
            }
            if (residualNorm <= INVERSE_TOLERANCE) {
                return estimate;
            }
            final Jacobian2D jacobian = jacobian(estimate);
            final double determinant = jacobian.determinant();
            if (!Double.isFinite(determinant)
                    || Math.abs(determinant) <= MINIMUM_SOLVER_PIVOT) {
                break;
            }
            final double stepX = (jacobian.m11() * residualX
                    - jacobian.m01() * residualY) / determinant;
            final double stepY = (-jacobian.m10() * residualX
                    + jacobian.m00() * residualY) / determinant;
            if (!Double.isFinite(stepX) || !Double.isFinite(stepY)) {
                break;
            }

            boolean improved = false;
            double scale = 1.0;
            for (int lineSearch = 0;
                    lineSearch < MAXIMUM_LINE_SEARCH_STEPS; lineSearch++) {
                final Point2D candidate;
                try {
                    candidate = new Point2D(
                            estimate.x() - scale * stepX,
                            estimate.y() - scale * stepY);
                } catch (final IllegalArgumentException error) {
                    scale *= 0.5;
                    continue;
                }
                final double candidateResidual = residualNorm(
                        candidate, warpedPreviewPixelCenter);
                if (Double.isFinite(candidateResidual)
                        && candidateResidual < residualNorm) {
                    estimate = candidate;
                    improved = true;
                    break;
                }
                scale *= 0.5;
            }
            if (!improved) {
                break;
            }
        }
        if (residualNorm(estimate, warpedPreviewPixelCenter)
                <= INVERSE_TOLERANCE) {
            return estimate;
        }
        throw new IllegalArgumentException(
                "Local-warp inverse did not converge safely");
    }

    public List<Point2D> fitSourcePoints() {
        return fitSourcePoints;
    }

    public List<Point2D> fitTargetPoints() {
        return fitTargetPoints;
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

    public CoordinateSpace2D sourceSpace() {
        return CoordinateSpace2D.PREVIEW_PIXEL;
    }

    public CoordinateSpace2D destinationSpace() {
        return CoordinateSpace2D.PREVIEW_PIXEL;
    }

    public String algorithmRevision() {
        return ALGORITHM_REVISION;
    }

    public double regularization() {
        return REGULARIZATION;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConstrainedLocalWarp2D warp)) {
            return false;
        }
        return Double.doubleToLongBits(supportRadius)
                == Double.doubleToLongBits(warp.supportRadius)
                && fitSourcePoints.equals(warp.fitSourcePoints)
                && fitTargetPoints.equals(warp.fitTargetPoints)
                && immutableXWeights.equals(warp.immutableXWeights)
                && immutableYWeights.equals(warp.immutableYWeights)
                && diagnostics.equals(warp.diagnostics);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fitSourcePoints, fitTargetPoints,
                immutableXWeights, immutableYWeights,
                supportRadius, diagnostics);
    }

    private double residualNorm(
            final Point2D estimate,
            final Point2D target) {
        final Point2D mapped = apply(estimate);
        return Math.hypot(
                mapped.x() - target.x(), mapped.y() - target.y());
    }

    private static List<Point2D> checkedPoints(
            final List<Point2D> points,
            final String name) {
        final List<Point2D> checked = List.copyOf(
                Objects.requireNonNull(points, name + " points"));
        for (final Point2D point : checked) {
            Objects.requireNonNull(point, name + " point");
            if (!Double.isFinite(point.x()) || !Double.isFinite(point.y())) {
                throw new IllegalArgumentException(
                        name + " coordinates must be finite");
            }
        }
        return checked;
    }

    private static void rejectDuplicateSources(final List<Point2D> sources) {
        for (int first = 0; first < sources.size(); first++) {
            for (int second = first + 1; second < sources.size(); second++) {
                if (sources.get(first).x() == sources.get(second).x()
                        && sources.get(first).y() == sources.get(second).y()) {
                    throw new IllegalArgumentException(
                            "FIT source points must be distinct");
                }
            }
        }
    }

    private static double supportRadius(
            final List<Point2D> sources,
            final double previewDiagonal) {
        final double[] nearest = new double[sources.size()];
        for (int index = 0; index < sources.size(); index++) {
            double nearestDistance = Double.POSITIVE_INFINITY;
            for (int other = 0; other < sources.size(); other++) {
                if (index == other) {
                    continue;
                }
                nearestDistance = Math.min(nearestDistance, Math.hypot(
                        sources.get(index).x() - sources.get(other).x(),
                        sources.get(index).y() - sources.get(other).y()));
            }
            nearest[index] = nearestDistance;
        }
        Arrays.sort(nearest);
        final int middle = nearest.length / 2;
        final double median = nearest.length % 2 == 0
                ? (nearest[middle - 1] + nearest[middle]) * 0.5
                : nearest[middle];
        final double radius = Math.max(0.20 * previewDiagonal,
                Math.min(0.60 * previewDiagonal, 2.5 * median));
        if (!Double.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException(
                    "Local-warp support radius must be finite and positive");
        }
        return radius;
    }

    private static double[][] kernelSystem(
            final List<Point2D> sources,
            final double supportRadius) {
        final int count = sources.size();
        final double[][] system = new double[count][count];
        for (int row = 0; row < count; row++) {
            for (int column = 0; column < count; column++) {
                final double normalizedDistance = Math.hypot(
                        sources.get(row).x() - sources.get(column).x(),
                        sources.get(row).y() - sources.get(column).y())
                        / supportRadius;
                system[row][column] = wendlandC2(normalizedDistance)
                        + (row == column ? REGULARIZATION : 0);
                if (!Double.isFinite(system[row][column])) {
                    throw new IllegalArgumentException(
                            "Local-warp kernel system must be finite");
                }
            }
        }
        return system;
    }

    /** Deterministic partial-pivot elimination with both components as RHS. */
    private static double[][] solve(
            final double[][] input,
            final double[] requestedX,
            final double[] requestedY) {
        final int count = input.length;
        final double[][] matrix = new double[count][count];
        for (int row = 0; row < count; row++) {
            matrix[row] = input[row].clone();
        }
        final double[][] rightHandSide = new double[count][2];
        for (int row = 0; row < count; row++) {
            rightHandSide[row][0] = requestedX[row];
            rightHandSide[row][1] = requestedY[row];
        }

        for (int pivotColumn = 0;
                pivotColumn < count; pivotColumn++) {
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
                        "Local-warp kernel system is singular");
            }
            if (pivotRow != pivotColumn) {
                final double[] matrixSwap = matrix[pivotColumn];
                matrix[pivotColumn] = matrix[pivotRow];
                matrix[pivotRow] = matrixSwap;
                final double[] rightSwap = rightHandSide[pivotColumn];
                rightHandSide[pivotColumn] = rightHandSide[pivotRow];
                rightHandSide[pivotRow] = rightSwap;
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
                rightHandSide[row][0] -= factor
                        * rightHandSide[pivotColumn][0];
                rightHandSide[row][1] -= factor
                        * rightHandSide[pivotColumn][1];
            }
        }

        final double[][] result = new double[2][count];
        for (int row = count - 1; row >= 0; row--) {
            final double pivot = matrix[row][row];
            if (!Double.isFinite(pivot)
                    || Math.abs(pivot) <= MINIMUM_SOLVER_PIVOT) {
                throw new IllegalArgumentException(
                        "Local-warp kernel system is singular");
            }
            for (int component = 0; component < 2; component++) {
                double value = rightHandSide[row][component];
                for (int column = row + 1; column < count; column++) {
                    value -= matrix[row][column]
                            * result[component][column];
                }
                result[component][row] = value / pivot;
                if (!Double.isFinite(result[component][row])) {
                    throw new IllegalArgumentException(
                            "Local-warp weights must be finite");
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
        double displacementX = 0;
        double displacementY = 0;
        for (int index = 0; index < sources.size(); index++) {
            final double normalizedDistance = Math.hypot(
                    point.x() - sources.get(index).x(),
                    point.y() - sources.get(index).y()) / supportRadius;
            final double kernel = wendlandC2(normalizedDistance);
            displacementX += xWeights[index] * kernel;
            displacementY += yWeights[index] * kernel;
        }
        if (!Double.isFinite(displacementX)
                || !Double.isFinite(displacementY)) {
            throw new IllegalArgumentException(
                    "Local-warp displacement must be finite");
        }
        return new double[]{displacementX, displacementY};
    }

    private static Jacobian2D jacobian(
            final Point2D point,
            final List<Point2D> sources,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius) {
        double dDisplacementXDx = 0;
        double dDisplacementXDy = 0;
        double dDisplacementYDx = 0;
        double dDisplacementYDy = 0;
        final double inverseRadiusSquared =
                1.0 / (supportRadius * supportRadius);
        for (int index = 0; index < sources.size(); index++) {
            final double deltaX = point.x() - sources.get(index).x();
            final double deltaY = point.y() - sources.get(index).y();
            final double normalizedDistance = Math.hypot(deltaX, deltaY)
                    / supportRadius;
            if (!(normalizedDistance > 0 && normalizedDistance < 1)) {
                continue;
            }
            final double oneMinus = 1.0 - normalizedDistance;
            final double gradientFactor = -20.0 * oneMinus * oneMinus
                    * oneMinus * inverseRadiusSquared;
            final double gradientX = gradientFactor * deltaX;
            final double gradientY = gradientFactor * deltaY;
            dDisplacementXDx += xWeights[index] * gradientX;
            dDisplacementXDy += xWeights[index] * gradientY;
            dDisplacementYDx += yWeights[index] * gradientX;
            dDisplacementYDy += yWeights[index] * gradientY;
        }
        return new Jacobian2D(
                1.0 + dDisplacementXDx, dDisplacementXDy,
                dDisplacementYDx, 1.0 + dDisplacementYDy);
    }

    private static double wendlandC2(final double normalizedDistance) {
        if (!(normalizedDistance < 1.0)) {
            return 0;
        }
        if (normalizedDistance < 0 || !Double.isFinite(normalizedDistance)) {
            throw new IllegalArgumentException(
                    "Normalized kernel distance must be finite and nonnegative");
        }
        final double oneMinus = 1.0 - normalizedDistance;
        final double square = oneMinus * oneMinus;
        return square * square * (4.0 * normalizedDistance + 1.0);
    }

    private static MutableDiagnostics sampleSafetyDiagnostics(
            final List<Point2D> sources,
            final List<Point2D> checkSources,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius,
            final int previewWidth,
            final int previewHeight) {
        final MutableDiagnostics diagnostics = new MutableDiagnostics();
        for (int yIndex = 0; yIndex < DOMAIN_SAMPLE_COUNT; yIndex++) {
            final double y = (previewHeight - 1.0) * yIndex
                    / (DOMAIN_SAMPLE_COUNT - 1.0);
            for (int xIndex = 0;
                    xIndex < DOMAIN_SAMPLE_COUNT; xIndex++) {
                final double x = (previewWidth - 1.0) * xIndex
                        / (DOMAIN_SAMPLE_COUNT - 1.0);
                sample(new Point2D(x, y), sources, xWeights, yWeights,
                        supportRadius, diagnostics);
            }
        }
        for (final Point2D source : sources) {
            sample(source, sources, xWeights, yWeights,
                    supportRadius, diagnostics);
        }
        for (final Point2D checkSource : checkSources) {
            sample(checkSource, sources, xWeights, yWeights,
                    supportRadius, diagnostics);
        }
        return diagnostics;
    }

    private static void sample(
            final Point2D point,
            final List<Point2D> sources,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius,
            final MutableDiagnostics diagnostics) {
        final Jacobian2D jacobian = jacobian(
                point, sources, xWeights, yWeights, supportRadius);
        final SingularValues singularValues = singularValues(jacobian);
        diagnostics.minimumDeterminant = Math.min(
                diagnostics.minimumDeterminant, jacobian.determinant());
        diagnostics.minimumSingularValue = Math.min(
                diagnostics.minimumSingularValue, singularValues.minimum());
        diagnostics.maximumSingularValue = Math.max(
                diagnostics.maximumSingularValue, singularValues.maximum());
        diagnostics.maximumAnisotropy = Math.max(
                diagnostics.maximumAnisotropy, singularValues.anisotropy());
        final double[] displacement = displacement(
                point, sources, xWeights, yWeights, supportRadius);
        diagnostics.maximumDisplacement = Math.max(
                diagnostics.maximumDisplacement,
                Math.hypot(displacement[0], displacement[1]));
    }

    private static SingularValues singularValues(
            final Jacobian2D jacobian) {
        final double squaredFrobenius = jacobian.m00() * jacobian.m00()
                + jacobian.m01() * jacobian.m01()
                + jacobian.m10() * jacobian.m10()
                + jacobian.m11() * jacobian.m11();
        final double determinant = jacobian.determinant();
        final double discriminant = Math.max(0,
                squaredFrobenius * squaredFrobenius
                        - 4.0 * determinant * determinant);
        final double maximum = Math.sqrt(Math.max(0,
                0.5 * (squaredFrobenius + Math.sqrt(discriminant))));
        final double minimum = maximum == 0
                ? 0 : Math.abs(determinant) / maximum;
        final double anisotropy = minimum == 0
                ? Double.POSITIVE_INFINITY : maximum / minimum;
        return new SingularValues(minimum, maximum, anisotropy);
    }

    private static void rejectUnsafeJacobian(
            final MutableDiagnostics diagnostics) {
        if (!diagnostics.allFinite()
                || diagnostics.minimumDeterminant
                        < MINIMUM_JACOBIAN_DETERMINANT
                || diagnostics.minimumSingularValue < MINIMUM_SINGULAR_VALUE
                || diagnostics.maximumSingularValue > MAXIMUM_SINGULAR_VALUE
                || diagnostics.maximumAnisotropy > MAXIMUM_ANISOTROPY) {
            throw new IllegalArgumentException(
                    "Local warp failed sampled Jacobian safety gates");
        }
    }

    private static double residualRms(
            final List<Point2D> evaluationSources,
            final List<Point2D> evaluationTargets,
            final List<Point2D> fitSources,
            final double[] xWeights,
            final double[] yWeights,
            final double supportRadius) {
        double squaredError = 0;
        for (int index = 0; index < evaluationSources.size(); index++) {
            final Point2D source = evaluationSources.get(index);
            final double[] displacement = displacement(
                    source, fitSources, xWeights, yWeights, supportRadius);
            final double errorX = source.x() + displacement[0]
                    - evaluationTargets.get(index).x();
            final double errorY = source.y() + displacement[1]
                    - evaluationTargets.get(index).y();
            squaredError += errorX * errorX + errorY * errorY;
        }
        return Math.sqrt(squaredError / evaluationSources.size());
    }

    private static double convexHullArea(final List<Point2D> points) {
        final List<Point2D> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(Point2D::x)
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
        final int lowerSize = hull.size();
        for (int index = sorted.size() - 2; index >= 0; index--) {
            final Point2D point = sorted.get(index);
            while (hull.size() > lowerSize && cross(
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
        final Point2D origin = hull.get(0);
        for (int index = 1; index + 1 < hull.size(); index++) {
            twiceArea += cross(origin, hull.get(index), hull.get(index + 1));
        }
        return Math.abs(twiceArea) * 0.5;
    }

    private static double cross(
            final Point2D origin,
            final Point2D first,
            final Point2D second) {
        return (first.x() - origin.x()) * (second.y() - origin.y())
                - (first.y() - origin.y()) * (second.x() - origin.x());
    }

    private static String contentHash(
            final int previewWidth,
            final int previewHeight,
            final List<Point2D> sources,
            final List<Point2D> targets,
            final double supportRadius,
            final double[] xWeights,
            final double[] yWeights) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "SHA-256 is required by the JVM", error);
        }
        final byte[] algorithm = ALGORITHM_REVISION.getBytes(
                StandardCharsets.UTF_8);
        updateInt(digest, algorithm.length);
        digest.update(algorithm);
        updateDouble(digest, REGULARIZATION);
        updateInt(digest, previewWidth);
        updateInt(digest, previewHeight);
        updateDouble(digest, supportRadius);
        updateInt(digest, sources.size());
        for (int index = 0; index < sources.size(); index++) {
            updateDouble(digest, sources.get(index).x());
            updateDouble(digest, sources.get(index).y());
            updateDouble(digest, targets.get(index).x());
            updateDouble(digest, targets.get(index).y());
            updateDouble(digest, xWeights[index]);
            updateDouble(digest, yWeights[index]);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateInt(
            final MessageDigest digest,
            final int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void updateDouble(
            final MessageDigest digest,
            final double value) {
        final double canonical = value == 0 ? 0 : value;
        digest.update(ByteBuffer.allocate(Long.BYTES)
                .putLong(Double.doubleToLongBits(canonical)).array());
    }

    private static void requireFiniteReported(
            final MutableDiagnostics sampled,
            final double fitRms,
            final OptionalDouble checkRms) {
        if (!sampled.allFinite() || !Double.isFinite(fitRms)
                || (checkRms.isPresent()
                && !Double.isFinite(checkRms.getAsDouble()))) {
            throw new IllegalArgumentException(
                    "All local-warp diagnostics must be finite");
        }
    }

    private static List<Double> immutableDoubles(final double[] values) {
        final List<Double> result = new ArrayList<>(values.length);
        for (final double value : values) {
            result.add(value);
        }
        return List.copyOf(result);
    }

    /** Immutable safety and residual evidence for an accepted fit. */
    public record Diagnostics(
            int controlCount,
            double supportRadius,
            double minimumJacobianDeterminant,
            double minimumSingularValue,
            double maximumSingularValue,
            double maximumAnisotropy,
            double maximumDisplacement,
            double fitRms,
            OptionalDouble checkRms,
            String contentHash) {

        public Diagnostics {
            if (controlCount < 4) {
                throw new IllegalArgumentException(
                        "At least four controls are required");
            }
            checkRms = Objects.requireNonNull(checkRms, "checkRms");
            contentHash = Objects.requireNonNull(contentHash, "contentHash");
            if (!contentHash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "contentHash must be lowercase SHA-256 hex");
            }
            if (!allFinite(supportRadius, minimumJacobianDeterminant,
                    minimumSingularValue, maximumSingularValue,
                    maximumAnisotropy, maximumDisplacement, fitRms)
                    || (checkRms.isPresent()
                    && !Double.isFinite(checkRms.getAsDouble()))) {
                throw new IllegalArgumentException(
                        "Diagnostics must be finite");
            }
            if (supportRadius <= 0
                    || minimumJacobianDeterminant
                    < MINIMUM_JACOBIAN_DETERMINANT
                    || minimumSingularValue < MINIMUM_SINGULAR_VALUE
                    || maximumSingularValue > MAXIMUM_SINGULAR_VALUE
                    || maximumAnisotropy > MAXIMUM_ANISOTROPY
                    || maximumDisplacement < 0 || fitRms < 0
                    || (checkRms.isPresent()
                    && checkRms.getAsDouble() < 0)) {
                throw new IllegalArgumentException(
                        "Diagnostics must describe a safely accepted warp");
            }
        }

        /** Explicitly named alias for provenance and evidence call sites. */
        public String contentHashSha256() {
            return contentHash;
        }
    }

    /** Row-major 2x2 derivative of forward preview coordinates. */
    public record Jacobian2D(
            double m00,
            double m01,
            double m10,
            double m11) {

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

    private static final class MutableDiagnostics {
        private double minimumDeterminant = Double.POSITIVE_INFINITY;
        private double minimumSingularValue = Double.POSITIVE_INFINITY;
        private double maximumSingularValue;
        private double maximumAnisotropy;
        private double maximumDisplacement;

        private boolean allFinite() {
            return ConstrainedLocalWarp2D.allFinite(
                    minimumDeterminant, minimumSingularValue,
                    maximumSingularValue, maximumAnisotropy,
                    maximumDisplacement);
        }
    }

    private static boolean allFinite(final double... values) {
        for (final double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }
}
