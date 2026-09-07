package org.atlasalign.plugin.review;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.atlasalign.application.AlignmentReviewState;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ManualWarpPrecondition;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.application.ReviewedTissueSupport;
import org.atlasalign.application.manual.BoundaryFitAnchor;
import org.atlasalign.application.manual.BoundaryFitDraft;
import org.atlasalign.application.manual.BoundaryFitMatch;
import org.atlasalign.application.manual.BoundaryFitModel;
import org.atlasalign.application.manual.BoundaryFitRequest;
import org.atlasalign.application.manual.BoundaryFitSample;
import org.atlasalign.application.manual.BoundaryFitSolver;
import org.atlasalign.application.manual.BoundaryWarpRequest;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.Point2D;

/** Builds one immutable off-thread boundary-fit request from verified state. */
final class BoundaryFitRequestFactory {

    private static final int MAXIMUM_SAMPLES_PER_BOUNDARY = 2_048;
    private static final int FULL_ANCHORS_PER_SIDE = 4;
    private static final int SINGLE_SIDE_ANCHORS = 6;

    private BoundaryFitRequestFactory() {
    }

    static BoundaryFitRequest create(
            final AlignmentReviewState state,
            final AtlasCoronalPlane plane,
            final BoundaryFitModel model,
            final ManualHemisphereWarp2D.AtlasSide activeSide,
            final List<BoundaryFitMatch> matches) {
        final var content = state.content();
        if (content.hemisphereWarp().isPresent()
                || content.localWarp().isPresent()) {
            throw new IllegalStateException(
                    "Clear warp or Undo local changes before fitting the whole atlas.");
        }
        if (content.outlineWarp().isPresent()) {
            throw new IllegalStateException(
                    "Assisted boundary placement is unavailable for legacy outline snapshots.");
        }
        if (!content.orientation().confirmed()) {
            throw new IllegalStateException(
                    "Confirm Direct or Reflected orientation before matching border points.");
        }
        final ReviewedTissueSupport support = content
                .reviewedTissueSupport().orElseThrow(() ->
                new IllegalStateException(
                        "Re-suggest or edit the tissue crop before fitting its border."));
        final ReviewSectionMode sectionMode = content.reviewSectionMode();
        final Optional<ManualHemisphereWarp2D.AtlasSide> targetSide;
        if (sectionMode == ReviewSectionMode.DISJOINED) {
            targetSide = Optional.of(activeSide);
        } else {
            targetSide = Optional.empty();
        }
        final Optional<ManualHemisphereWarp2D.AtlasSide> sampleSide;
        if (sectionMode == ReviewSectionMode.HALF) {
            sampleSide = Optional.of(sideForObserved(
                    content.observedHemisphere()));
        } else if (sectionMode == ReviewSectionMode.DISJOINED) {
            sampleSide = Optional.of(activeSide);
        } else {
            sampleSide = Optional.empty();
        }
        final List<BoundaryFitSample> atlas = atlasExterior(
                state, plane, sampleSide);
        List<BoundaryFitSample> tissue = tissueExterior(support);
        if (sectionMode == ReviewSectionMode.HALF) {
            tissue = excludeProbableHalfCutEdge(tissue,
                    content.observedHemisphere(), content.orientation());
        }
        /*
         * The fitted source coordinates live after the reviewer's explicit
         * Direct/Reflected choice. Keeping reflection outside this positive-
         * determinant placement lets the absolute fit replace proposal shear
         * without mistaking the chosen parity for a fitted reflection.
         */
        AffineTransform2D currentOrientedAtlasToPreview = state.basis()
                .proposal().affine()
                .andThen(content.manualPreviewAdjustment())
                .andThen(content.postOutlinePreviewAdjustment());
        if (targetSide.isPresent()) {
            currentOrientedAtlasToPreview =
                    currentOrientedAtlasToPreview.andThen(
                    content.manualSidePlacement().transform(
                            targetSide.orElseThrow()));
        }
        final double axisRadians = Math.atan2(
                currentOrientedAtlasToPreview.m10(),
                currentOrientedAtlasToPreview.m00());
        return new BoundaryFitRequest(
                state.contentRevision(), model, sectionMode, targetSide,
                atlas, tissue, matches, currentOrientedAtlasToPreview,
                axisRadians, support.width(), support.height(),
                ManualWarpPrecondition.planeSha256(
                        state.basis(), content),
                placementSha256(content.manualPreviewAdjustment(),
                        content.manualSidePlacement().atlasLeft(),
                        content.manualSidePlacement().atlasRight()),
                support.contentSha256(),
                state.basis().sourceSnapshot().pixelSha256(),
                state.basis().atlas().identitySha256());
    }

    /**
     * Creates numbered atlas-only anchors for guided reviewer matching.
     * No tissue correspondence is inferred automatically.
     */
    static BoundaryFitDraft createGuidedDraft(
            final AlignmentReviewState state,
            final AtlasCoronalPlane plane,
            final BoundaryFitModel model,
            final ManualHemisphereWarp2D.AtlasSide activeSide) {
        final BoundaryFitRequest request = create(
                state, plane, model, activeSide, List.of());
        final List<BoundaryFitSample> anchorSamples = new ArrayList<>();
        if (request.sectionMode() == ReviewSectionMode.FULL) {
            anchorSamples.addAll(evenlySpacedAvailable(
                    atlasExterior(state, plane, Optional.of(
                            ManualHemisphereWarp2D.AtlasSide.LEFT)),
                    FULL_ANCHORS_PER_SIDE));
            anchorSamples.addAll(evenlySpacedAvailable(
                    atlasExterior(state, plane, Optional.of(
                            ManualHemisphereWarp2D.AtlasSide.RIGHT)),
                    FULL_ANCHORS_PER_SIDE));
        } else {
            anchorSamples.addAll(evenlySpacedAvailable(
                    request.atlasBoundary(), SINGLE_SIDE_ANCHORS));
        }
        if (anchorSamples.size() < BoundaryFitSolver.MINIMUM_INCLUDED_MATCHES) {
            anchorSamples.clear();
            anchorSamples.addAll(evenlySpacedAvailable(
                    request.atlasBoundary(), Math.min(
                            SINGLE_SIDE_ANCHORS,
                            request.atlasBoundary().size())));
        }
        if (anchorSamples.size() < BoundaryFitSolver.MINIMUM_INCLUDED_MATCHES) {
            throw new IllegalStateException(
                    "The current atlas plane has fewer than four usable exterior points. Choose a nearby plane or place it manually.");
        }
        final List<BoundaryFitAnchor> anchors = new ArrayList<>();
        for (int index = 0; index < anchorSamples.size(); index++) {
            final Point2D previewPoint = anchorSamples.get(index).point();
            final Point2D planePoint = rawAtlasPlanePoint(
                    state, request.targetSide(), previewPoint);
            anchors.add(new BoundaryFitAnchor(String.format(
                    java.util.Locale.ROOT, "boundary-anchor-%02d",
                    index + 1), index + 1,
                    directionalLabel(planePoint, plane.height()),
                    planePoint, previewPoint, Optional.empty(), true));
        }
        return new BoundaryFitDraft(request, anchors,
                Optional.of(anchors.get(0).id()));
    }

    /**
     * Recovers the verified raw atlas-plane coordinate used by the separate
     * atlas reference pane. The coarse solver deliberately works after the
     * reviewer's Direct/Reflected choice, but pane drawing and hit-testing
     * must stay in the unreflected pixels of the verified atlas plane.
     */
    static Point2D rawAtlasPlanePoint(
            final AlignmentReviewState state,
            final Optional<ManualHemisphereWarp2D.AtlasSide> targetSide,
            final Point2D previewPoint) {
        final var content = state.content();
        if (content.outlineWarp().isPresent()
                || content.hemisphereWarp().isPresent()
                || content.localWarp().isPresent()) {
            throw new IllegalStateException(
                    "Raw boundary-fit coordinates require an undeformed atlas");
        }
        Point2D current = java.util.Objects.requireNonNull(
                previewPoint, "previewPoint");
        if (targetSide.isPresent()) {
            current = content.manualSidePlacement().transform(
                    targetSide.orElseThrow()).inverse().apply(current);
        }
        current = content.postOutlinePreviewAdjustment().inverse()
                .apply(current);
        return state.preOutlineAtlasToPreview().inverse().apply(current);
    }

    /** Maps a raw atlas-pane point through the explicitly selected parity. */
    static Point2D mappedAtlasPreviewPoint(
            final AlignmentReviewState state,
            final Optional<ManualHemisphereWarp2D.AtlasSide> targetSide,
            final Point2D atlasPlanePoint) {
        final var content = state.content();
        if (content.outlineWarp().isPresent()
                || content.hemisphereWarp().isPresent()
                || content.localWarp().isPresent()) {
            throw new IllegalStateException(
                    "Boundary-fit pane mapping requires an undeformed atlas");
        }
        Point2D current = state.preOutlineAtlasToPreview().apply(
                java.util.Objects.requireNonNull(
                        atlasPlanePoint, "atlasPlanePoint"));
        current = content.postOutlinePreviewAdjustment().apply(current);
        if (targetSide.isPresent()) {
            current = content.manualSidePlacement().apply(
                    targetSide.orElseThrow(), current);
        }
        return current;
    }

    private static String directionalLabel(
            final Point2D planePoint,
            final int planeHeight) {
        final double fraction = Math.max(0, Math.min(0.999999,
                planePoint.y() / Math.max(1.0, planeHeight)));
        final String[] labels = {
            "dorsal", "dorsolateral", "upper lateral",
            "lower lateral", "ventrolateral", "ventral"
        };
        return labels[Math.min(labels.length - 1,
                (int) Math.floor(fraction * labels.length))];
    }

    private static List<BoundaryFitSample> evenlySpacedAvailable(
            final List<BoundaryFitSample> samples,
            final int count) {
        if (samples.isEmpty() || count <= 0) {
            return List.of();
        }
        final int actualCount = Math.min(count, samples.size());
        final List<BoundaryFitSample> selected = new ArrayList<>(actualCount);
        for (int index = 0; index < actualCount; index++) {
            selected.add(samples.get((int) ((2L * index + 1)
                    * samples.size() / (2L * actualCount))));
        }
        return List.copyOf(selected);
    }

    /**
     * Builds an empty manual-border draft from the currently displayed atlas
     * exterior. Unlike coarse fitting, an existing hemisphere field is
     * allowed because applying the draft replaces only its border group.
     */
    static BoundaryWarpRequest createBoundaryWarp(
            final AlignmentReviewState state,
            final AtlasCoronalPlane plane,
            final ManualHemisphereWarp2D.AtlasSide activeSide,
            final List<BoundaryFitMatch> matches) {
        final var content = state.content();
        if (content.localWarp().isPresent()) {
            throw new IllegalStateException(
                    "Clear the legacy generic local warp before pairing atlas and tissue borders.");
        }
        if (content.outlineWarp().isPresent()) {
            throw new IllegalStateException(
                    "Manual border pairing is unavailable for legacy exact-outline snapshots.");
        }
        if (!content.orientation().confirmed()) {
            throw new IllegalStateException(
                    "Confirm Direct or Reflected orientation before pairing borders.");
        }
        final ReviewedTissueSupport support = content
                .reviewedTissueSupport().orElseThrow(() ->
                new IllegalStateException(
                        "Re-suggest or edit the tissue crop before pairing its border."));
        final ReviewSectionMode sectionMode = content.reviewSectionMode();
        final List<BoundaryFitSample> atlas = atlasExterior(
                state, plane, Optional.of(activeSide), true);
        List<BoundaryFitSample> tissue = tissueExterior(support);
        if (sectionMode == ReviewSectionMode.HALF) {
            tissue = excludeProbableHalfCutEdge(tissue,
                    content.observedHemisphere(), content.orientation());
        }
        final double middle =
                (state.basis().atlas().atlasPlaneWidth() - 1.0) * 0.5;
        final double lastY =
                state.basis().atlas().atlasPlaneHeight() - 1.0;
        final ManualHemisphereWarp2D.MidlineSegment midline =
                new ManualHemisphereWarp2D.MidlineSegment(
                        state.mapAtlasBeforeHemisphereWarp(
                                new Point2D(middle, 0)),
                        state.mapAtlasBeforeHemisphereWarp(
                                new Point2D(middle, lastY)));
        return new BoundaryWarpRequest(
                state.contentRevision(), activeSide, content.orientation(),
                sectionMode, atlas, tissue, matches,
                content.hemisphereWarp().map(
                        ManualHemisphereWarp2D::controls).orElse(List.of()),
                content.hemisphereWarp(), midline,
                support.width(), support.height(),
                ManualWarpPrecondition.capture(state),
                state.basis().sourceSnapshot().pixelSha256(),
                state.basis().atlas().identitySha256());
    }

    private static ManualHemisphereWarp2D.AtlasSide sideForObserved(
            final ObservedAnatomicalHemisphere observed) {
        return switch (observed) {
            case LEFT -> ManualHemisphereWarp2D.AtlasSide.LEFT;
            case RIGHT -> ManualHemisphereWarp2D.AtlasSide.RIGHT;
            case BOTH, UNSURE -> throw new IllegalStateException(
                    "Choose the visible anatomical left or right side before fitting a Half section.");
        };
    }

    private static List<BoundaryFitSample> atlasExterior(
            final AlignmentReviewState state,
            final AtlasCoronalPlane plane,
            final Optional<ManualHemisphereWarp2D.AtlasSide> selectedSide) {
        return atlasExterior(state, plane, selectedSide, false);
    }

    private static List<BoundaryFitSample> atlasExterior(
            final AlignmentReviewState state,
            final AtlasCoronalPlane plane,
            final Optional<ManualHemisphereWarp2D.AtlasSide> selectedSide,
            final boolean includeHemisphereWarp) {
        final int width = plane.width();
        final int height = plane.height();
        final int[] labels = plane.annotationId();
        final boolean[] exteriorBackground = exteriorBackground(
                width, height, labels);
        final boolean[] medialSeam = medialSeamBoundary(
                width, height, labels);
        final double middle = (width - 1.0) * 0.5;
        final List<RawSample> raw = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (labels[y * width + x] == 0) {
                    continue;
                }
                if (medialSeam[y * width + x]) {
                    continue;
                }
                final ManualHemisphereWarp2D.AtlasSide side = x < middle
                        ? ManualHemisphereWarp2D.AtlasSide.LEFT
                        : ManualHemisphereWarp2D.AtlasSide.RIGHT;
                if (selectedSide.isPresent()
                        && selectedSide.orElseThrow() != side) {
                    continue;
                }
                double normalX = 0;
                double normalY = 0;
                if (isExterior(width, height, exteriorBackground,
                        x - 1, y)) {
                    normalX -= 1;
                }
                if (isExterior(width, height, exteriorBackground,
                        x + 1, y)) {
                    normalX += 1;
                }
                if (isExterior(width, height, exteriorBackground,
                        x, y - 1)) {
                    normalY -= 1;
                }
                if (isExterior(width, height, exteriorBackground,
                        x, y + 1)) {
                    normalY += 1;
                }
                if (normalX == 0 && normalY == 0) {
                    continue;
                }
                raw.add(new RawSample(new Point2D(x, y),
                        new Point2D(normalX, normalY)));
            }
        }
        final List<BoundaryFitSample> mapped = new ArrayList<>();
        for (final RawSample sample : downsample(raw,
                MAXIMUM_SAMPLES_PER_BOUNDARY)) {
            final ManualHemisphereWarp2D.AtlasSide sampleSide =
                    sample.point().x() < middle
                            ? ManualHemisphereWarp2D.AtlasSide.LEFT
                            : ManualHemisphereWarp2D.AtlasSide.RIGHT;
            final Point2D point = includeHemisphereWarp
                    ? state.mapAtlasToPreview(sampleSide, sample.point())
                    : state.mapAtlasBeforeHemisphereWarp(sample.point());
            final Point2D mappedX = includeHemisphereWarp
                    ? state.mapAtlasToPreview(sampleSide,
                    new Point2D(sample.point().x() + 1,
                            sample.point().y()))
                    : state.mapAtlasBeforeHemisphereWarp(
                    new Point2D(sample.point().x() + 1,
                            sample.point().y()));
            final Point2D mappedY = includeHemisphereWarp
                    ? state.mapAtlasToPreview(sampleSide,
                    new Point2D(sample.point().x(),
                            sample.point().y() + 1))
                    : state.mapAtlasBeforeHemisphereWarp(
                    new Point2D(sample.point().x(),
                            sample.point().y() + 1));
            final double m00 = mappedX.x() - point.x();
            final double m10 = mappedX.y() - point.y();
            final double m01 = mappedY.x() - point.x();
            final double m11 = mappedY.y() - point.y();
            final double determinant = m00 * m11 - m01 * m10;
            if (!Double.isFinite(determinant)
                    || Math.abs(determinant) < 1e-12) {
                continue;
            }
            mapped.add(new BoundaryFitSample(point, new Point2D(
                    (m11 * sample.normal().x()
                            - m10 * sample.normal().y()) / determinant,
                    (-m01 * sample.normal().x()
                            + m00 * sample.normal().y()) / determinant)));
        }
        return sortAroundCentroid(mapped);
    }

    /**
     * Marks the two tissue-facing banks of any zero-label cleft that crosses
     * the raw atlas midline. Such a cleft is the interhemispheric seam even
     * when its background is connected to an image border, and therefore is
     * never an exterior fitting target.
     */
    private static boolean[] medialSeamBoundary(
            final int width,
            final int height,
            final int[] labels) {
        final boolean[] seam = new boolean[labels.length];
        final int leftMiddle = Math.max(0, (width - 1) / 2);
        final int rightMiddle = Math.min(width - 1, width / 2);
        for (int y = 0; y < height; y++) {
            int left = leftMiddle;
            while (left >= 0 && labels[y * width + left] == 0) {
                left--;
            }
            int right = rightMiddle;
            while (right < width && labels[y * width + right] == 0) {
                right++;
            }
            if (left < 0 || right >= width || right <= left + 1) {
                continue;
            }
            boolean allBackground = true;
            for (int x = left + 1; x < right; x++) {
                if (labels[y * width + x] != 0) {
                    allBackground = false;
                    break;
                }
            }
            if (allBackground) {
                seam[y * width + left] = true;
                seam[y * width + right] = true;
            }
        }
        return seam;
    }

    private static boolean[] exteriorBackground(
            final int width,
            final int height,
            final int[] labels) {
        final boolean[] exterior = new boolean[labels.length];
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int x = 0; x < width; x++) {
            enqueueBackground(x, 0, width, labels, exterior, queue);
            enqueueBackground(x, height - 1, width, labels, exterior, queue);
        }
        for (int y = 0; y < height; y++) {
            enqueueBackground(0, y, width, labels, exterior, queue);
            enqueueBackground(width - 1, y, width, labels, exterior, queue);
        }
        while (!queue.isEmpty()) {
            final int index = queue.removeFirst();
            final int x = index % width;
            final int y = index / width;
            if (x > 0) {
                enqueueBackground(x - 1, y, width,
                        labels, exterior, queue);
            }
            if (x + 1 < width) {
                enqueueBackground(x + 1, y, width,
                        labels, exterior, queue);
            }
            if (y > 0) {
                enqueueBackground(x, y - 1, width,
                        labels, exterior, queue);
            }
            if (y + 1 < height) {
                enqueueBackground(x, y + 1, width,
                        labels, exterior, queue);
            }
        }
        return exterior;
    }

    private static void enqueueBackground(
            final int x,
            final int y,
            final int width,
            final int[] labels,
            final boolean[] exterior,
            final ArrayDeque<Integer> queue) {
        final int index = y * width + x;
        if (labels[index] == 0 && !exterior[index]) {
            exterior[index] = true;
            queue.addLast(index);
        }
    }

    private static boolean isExterior(
            final int width,
            final int height,
            final boolean[] exterior,
            final int x,
            final int y) {
        return x < 0 || x >= width || y < 0 || y >= height
                || exterior[y * width + x];
    }

    private static List<BoundaryFitSample> tissueExterior(
            final ReviewedTissueSupport support) {
        final List<BoundaryFitSample> samples = new ArrayList<>();
        for (final List<Point2D> polygon : support.polygons()) {
            final double orientation = Math.signum(
                    signedAreaTwice(polygon));
            for (int index = 0; index < polygon.size(); index++) {
                final Point2D previous = polygon.get(
                        (index + polygon.size() - 1) % polygon.size());
                final Point2D current = polygon.get(index);
                final Point2D next = polygon.get(
                        (index + 1) % polygon.size());
                final double tangentX = next.x() - previous.x();
                final double tangentY = next.y() - previous.y();
                if (Math.hypot(tangentX, tangentY) >= 1e-12) {
                    samples.add(new BoundaryFitSample(current,
                            orientation > 0
                                    ? new Point2D(tangentY, -tangentX)
                                    : new Point2D(-tangentY, tangentX)));
                }
            }
        }
        return downsample(samples, MAXIMUM_SAMPLES_PER_BOUNDARY);
    }

    private static double signedAreaTwice(
            final List<Point2D> polygon) {
        double area = 0;
        for (int index = 0; index < polygon.size(); index++) {
            final Point2D first = polygon.get(index);
            final Point2D second = polygon.get(
                    (index + 1) % polygon.size());
            area += first.x() * second.y() - second.x() * first.y();
        }
        return area;
    }

    private static List<BoundaryFitSample> excludeProbableHalfCutEdge(
            final List<BoundaryFitSample> samples,
            final ObservedAnatomicalHemisphere observed,
            final AtlasOrientation orientation) {
        final boolean visibleOnImageLeft = (observed
                == ObservedAnatomicalHemisphere.LEFT)
                != orientation.reflected();
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (final BoundaryFitSample sample : samples) {
            minimum = Math.min(minimum, sample.point().x());
            maximum = Math.max(maximum, sample.point().x());
        }
        final double lower = minimum + 0.12 * (maximum - minimum);
        final double upper = minimum + 0.88 * (maximum - minimum);
        final List<BoundaryFitSample> filtered = samples.stream()
                .filter(sample -> visibleOnImageLeft
                        ? sample.point().x() <= upper
                        : sample.point().x() >= lower)
                .toList();
        return filtered.size() >= 4 ? filtered : samples;
    }

    private static <T> List<T> downsample(
            final List<T> values,
            final int maximum) {
        if (values.size() <= maximum) {
            return List.copyOf(values);
        }
        final List<T> sampled = new ArrayList<>(maximum);
        for (int index = 0; index < maximum; index++) {
            sampled.add(values.get((int) ((long) index
                    * values.size() / maximum)));
        }
        return List.copyOf(sampled);
    }

    private static List<BoundaryFitSample> sortAroundCentroid(
            final List<BoundaryFitSample> samples) {
        double x = 0;
        double y = 0;
        for (final BoundaryFitSample sample : samples) {
            x += sample.point().x();
            y += sample.point().y();
        }
        final double centreX = x / Math.max(1, samples.size());
        final double centreY = y / Math.max(1, samples.size());
        return samples.stream().sorted(Comparator.comparingDouble(sample ->
                Math.atan2(sample.point().y() - centreY,
                        sample.point().x() - centreX))).toList();
    }

    private static String placementSha256(
            final AffineTransform2D joined,
            final AffineTransform2D left,
            final AffineTransform2D right) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, joined);
            update(digest, left);
            update(digest, right);
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(
            final MessageDigest digest,
            final AffineTransform2D transform) {
        for (final double value : new double[]{
                transform.m00(), transform.m01(), transform.m02(),
                transform.m10(), transform.m11(), transform.m12()}) {
            digest.update(ByteBuffer.allocate(Long.BYTES).putLong(
                    Double.doubleToLongBits(value)).array());
        }
    }

    private record RawSample(Point2D point, Point2D normal) {
    }
}
