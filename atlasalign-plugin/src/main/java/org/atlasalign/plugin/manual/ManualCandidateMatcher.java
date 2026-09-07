package org.atlasalign.plugin.manual;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import org.atlasalign.application.AtlasPlaneTilt;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.manual.AnatomicalSide;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D;
import org.atlasalign.application.manual.ContourCaptureStatus;
import org.atlasalign.application.manual.ContourCompleteness;
import org.atlasalign.application.manual.ContourSegment;
import org.atlasalign.application.manual.ContourTopology;
import org.atlasalign.application.manual.ContourVertex;
import org.atlasalign.application.manual.ManualContour;
import org.atlasalign.application.manual.ManualContourKind;
import org.atlasalign.application.manual.ManualOutlineWarp2D;
import org.atlasalign.application.manual.MonotoneBoundary2D;
import org.atlasalign.application.manual.OutlineAffineFitter;
import org.atlasalign.application.manual.SectionGeometry;
import org.atlasalign.application.manual.SectionObservation;
import org.atlasalign.application.manual.SourcePixelPoint;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.plugin.review.SelectedAtlasContour;
import org.atlasalign.plugin.review.SelectedAtlasRegion;

/**
 * Transparent, correspondence-free shape matcher for manual candidate preview.
 * It never changes a source image, confidence category, or accepted plane.
 */
final class ManualCandidateMatcher {

    private static final int MAX_ATLAS_SAMPLES = 256;
    private static final int MANUAL_SAMPLES_PER_CONTOUR = 128;
    private static final int OUTLINE_WARP_LOOP_SAMPLES = 128;
    private static final double OUTLINE_WEIGHT = 0.4;
    private static final double GUIDE_WEIGHT = 0.6;

    private ManualCandidateMatcher() { }

    static ManualCandidateMatch match(
            final AtlasCoronalPlane plane,
            final AtlasPlaneTilt tilt,
            final SelectedAtlasRegion rootRegion,
            final SelectedAtlasRegion guideRegion,
            final Collection<ManualContour> manualContours,
            final SectionObservation observation,
            final int sourceWidth,
            final int sourceHeight) {
        return match(plane, tilt, rootRegion, guideRegion, manualContours,
                observation, new PreviewMapping(
                        sourceWidth, sourceHeight, sourceWidth, sourceHeight));
    }

    static ManualCandidateMatch match(
            final AtlasCoronalPlane plane,
            final AtlasPlaneTilt tilt,
            final SelectedAtlasRegion rootRegion,
            final SelectedAtlasRegion guideRegion,
            final Collection<ManualContour> manualContours,
            final SectionObservation observation,
            final PreviewMapping previewMapping) {
        return match(plane, tilt, rootRegion, guideRegion, manualContours,
                observation, previewMapping, Optional.empty());
    }

    /**
     * Rebuilds one candidate with reviewer-corrected semantic outline
     * correspondences. The ordered loops are regenerated deterministically
     * from the same copied atlas plane and captured contour; only the four
     * anchor indices differ.
     */
    static ManualCandidateMatch matchWithAnchors(
            final AtlasCoronalPlane plane,
            final AtlasPlaneTilt tilt,
            final SelectedAtlasRegion rootRegion,
            final SelectedAtlasRegion guideRegion,
            final Collection<ManualContour> manualContours,
            final SectionObservation observation,
            final PreviewMapping previewMapping,
            final List<ManualOutlineWarp2D.AnchorPair> anchors) {
        return match(plane, tilt, rootRegion, guideRegion, manualContours,
                observation, previewMapping,
                Optional.of(List.copyOf(Objects.requireNonNull(
                        anchors, "anchors"))));
    }

    private static ManualCandidateMatch match(
            final AtlasCoronalPlane plane,
            final AtlasPlaneTilt tilt,
            final SelectedAtlasRegion rootRegion,
            final SelectedAtlasRegion guideRegion,
            final Collection<ManualContour> manualContours,
            final SectionObservation observation,
            final PreviewMapping previewMapping,
            final Optional<List<ManualOutlineWarp2D.AnchorPair>> anchors) {
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(tilt, "tilt");
        Objects.requireNonNull(rootRegion, "rootRegion");
        Objects.requireNonNull(guideRegion, "guideRegion");
        Objects.requireNonNull(manualContours, "manualContours");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(previewMapping, "previewMapping");
        final SelectedAtlasContour root = SelectedAtlasContour.from(
                plane, rootRegion);
        final SelectedAtlasContour guide = SelectedAtlasContour.from(
                plane, guideRegion);
        if (!root.isPresent() || !guide.isPresent()) {
            throw new IllegalArgumentException(
                    "Candidate lacks the selected atlas anatomy");
        }
        final List<Point2D> tissueOutline = contourPoints(
                manualContours, false, guideRegion.acronym());
        final List<ManualContour> tissueGuides = validatedGuideContours(
                manualContours, guideRegion.acronym(), observation);
        if (tissueOutline.size() < 3 || tissueGuides.isEmpty()) {
            throw new IllegalArgumentException(
                    "Candidate matching requires completed tissue and guide contours");
        }

        final List<Point2D> visibleRoot = atlasPointsForObservedHemisphere(
                root.boundaryPoints(), plane.width(),
                observation.observedHemisphere());
        if (visibleRoot.isEmpty()) {
            throw new IllegalArgumentException(
                    "Candidate lacks anatomy on the observed hemisphere");
        }
        final OutlineMapping outlineMapping = outlineMapping(
                plane, root, guide, visibleRoot, tissueOutline,
                manualContours, observation, previewMapping, anchors);
        final AffineTransform2D mapping = outlineMapping.atlasToSource();
        final Function<Point2D, Point2D> atlasToPreview =
                outlineMapping::mapAtlasToPreview;
        final List<Point2D> mappedRoot = mapAndSample(
                visibleRoot, atlasToPreview);
        final List<Point2D> tissueOutlinePreview = tissueOutline.stream()
                .map(previewMapping::sourceToPreview).toList();
        final double diagonal = Math.hypot(
                previewMapping.previewWidth(), previewMapping.previewHeight());
        final double outlineMismatch = symmetricChamfer(
                tissueOutlinePreview, mappedRoot) / diagonal;
        double guideMismatchTotal = 0;
        final List<Point2D> guideOverlay = new ArrayList<>();
        for (final ManualContour tissueGuide : tissueGuides) {
            final List<Point2D> atlasGuide = atlasPointsForSide(
                    atlasPointsForObservedHemisphere(
                            guide.boundaryPoints(), plane.width(),
                            observation.observedHemisphere()),
                    plane.width(), tissueGuide.anatomicalSide());
            if (atlasGuide.isEmpty()) {
                throw new IllegalArgumentException(
                        "Candidate lacks the selected guide on anatomical side "
                                + tissueGuide.anatomicalSide());
            }
            final List<Point2D> mappedGuide = mapAndSample(
                    atlasGuide, atlasToPreview);
            guideMismatchTotal += directedChamfer(
                    resampleVisibleContour(tissueGuide).stream()
                            .map(previewMapping::sourceToPreview).toList(),
                    mappedGuide)
                    / diagonal;
            guideOverlay.addAll(atlasGuide);
        }
        final double guideMismatch = guideMismatchTotal
                / tissueGuides.size();
        final double combined = OUTLINE_WEIGHT * outlineMismatch
                + GUIDE_WEIGHT * guideMismatch;
        final int level = plane.zeroBasedAnteriorPosteriorIndex();
        final String id = "manual-candidate-L" + level
                + "-S" + signed(tilt.sagittalDegrees())
                + "-H" + signed(tilt.horizontalDegrees());
        return new ManualCandidateMatch(
                id, level, tilt, combined, outlineMismatch, guideMismatch,
                plane, root, guide, visibleRoot,
                List.copyOf(guideOverlay), mapping,
                outlineMapping.atlasToPreviewAffine(), previewMapping,
                outlineMapping.outlineWarp(), outlineMapping.anchorState(),
                outlineMapping.methodId());
    }

    /**
     * Builds the provisional full-outline deformation before guide traces
     * exist, so the reviewer sees the already-deformed atlas while tracing.
     */
    static ManualOutlineWarpPreview outlinePreview(
            final AtlasCoronalPlane plane,
            final SelectedAtlasRegion rootRegion,
            final SelectedAtlasRegion guideRegion,
            final Collection<ManualContour> manualContours,
            final SectionObservation observation,
            final PreviewMapping previewMapping) {
        return boundaryAuthoritativeOutlinePreview(plane, rootRegion,
                guideRegion, manualContours, observation, previewMapping);
    }

    static ManualOutlineWarpPreview outlinePreview(
            final AtlasCoronalPlane plane,
            final SelectedAtlasRegion rootRegion,
            final SelectedAtlasRegion guideRegion,
            final Collection<ManualContour> manualContours,
            final SectionObservation observation,
            final PreviewMapping previewMapping,
            final int samplesPerAnchorArc) {
        if (samplesPerAnchorArc <= 0) {
            throw new IllegalArgumentException(
                    "samplesPerAnchorArc must be positive");
        }
        return boundaryAuthoritativeOutlinePreview(plane, rootRegion,
                guideRegion, manualContours, observation, previewMapping);
    }

    private static ManualOutlineWarpPreview boundaryAuthoritativeOutlinePreview(
            final AtlasCoronalPlane plane,
            final SelectedAtlasRegion rootRegion,
            final SelectedAtlasRegion guideRegion,
            final Collection<ManualContour> manualContours,
            final SectionObservation observation,
            final PreviewMapping previewMapping) {
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(rootRegion, "rootRegion");
        Objects.requireNonNull(guideRegion, "guideRegion");
        Objects.requireNonNull(manualContours, "manualContours");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(previewMapping, "previewMapping");
        final SelectedAtlasContour root = SelectedAtlasContour.from(
                plane, rootRegion);
        final SelectedAtlasContour guide = SelectedAtlasContour.from(
                plane, guideRegion);
        if (!root.isPresent() || !guide.isPresent()) {
            throw new IllegalArgumentException(
                    "Current atlas plane lacks the root or selected guide anatomy");
        }
        if (observation.geometry() != SectionGeometry.FULL
                || observation.observedHemisphere()
                        != ObservedAnatomicalHemisphere.BOTH) {
            throw new IllegalArgumentException(
                    "Exact reviewed-outline warping requires a FULL bilateral section");
        }
        final ManualContour outline = requireExactFullOutline(manualContours);
        final List<List<Point2D>> rootPaths = root.orderedExteriorLoops();
        if (rootPaths.size() != 1) {
            throw new IllegalArgumentException(
                    "Exact reviewed-outline warping requires one connected atlas root exterior; found "
                            + rootPaths.size());
        }
        final List<Point2D> tissueSource = outline.vertices().stream()
                .map(vertex -> new Point2D(
                        vertex.point().x(), vertex.point().y()))
                .toList();
        final List<Point2D> atlasExterior = rootPaths.get(0);
        if (atlasExterior.size() < 4) {
            throw new IllegalArgumentException(
                    "The atlas root has no safe ordered exterior loop");
        }
        final OutlineAffineFitter.FitResult fit = OutlineAffineFitter.fit(
                root.boundaryPoints(), tissueSource,
                observation.orientation().reflected());
        final AffineTransform2D atlasToPreview = sourceToPreview(
                previewMapping, fit.atlasToSource());
        final List<Point2D> normalizedAtlas = normalizeClosedLoop(
                atlasExterior.stream().map(atlasToPreview::apply).toList());
        final List<Point2D> normalizedTissue = normalizeClosedLoop(
                tissueSource.stream().map(previewMapping::sourceToPreview)
                        .toList());
        final BoundaryAuthoritativeTransform2D transform =
                BoundaryAuthoritativeTransform2D.fitFull(
                        MonotoneBoundary2D.arcLengthIndexed(
                                "atlas-boundary-", normalizedAtlas),
                        MonotoneBoundary2D.arcLengthIndexed(
                                "tissue-boundary-", normalizedTissue),
                        previewMapping.previewWidth(),
                        previewMapping.previewHeight());
        final List<List<Point2D>> guidePaths =
                guide.orderedExteriorLoops();
        if (guidePaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "The selected atlas plane lacks a safe exterior ROI path");
        }
        return new ManualOutlineWarpPreview(
                plane, root, guide, rootPaths, guidePaths,
                fit.atlasToSource(), atlasToPreview, previewMapping,
                transform, fit.diagnostics().methodId()
                        + "+" + transform.algorithmRevision());
    }

    private static ManualContour requireExactFullOutline(
            final Collection<ManualContour> contours) {
        final List<ManualContour> outlines = contours.stream()
                .filter(contour -> contour.kind()
                        == ManualContourKind.TISSUE_OUTLINE)
                .toList();
        if (outlines.size() != 1) {
            throw new IllegalArgumentException(
                    "Exact outline warping requires exactly one tissue outline");
        }
        final ManualContour outline = outlines.get(0);
        if (outline.topology() != ContourTopology.CLOSED
                || outline.captureStatus() != ContourCaptureStatus.COMPLETE
                || outline.completeness() != ContourCompleteness.COMPLETE
                || !outline.eligibleForPreview()) {
            throw new IllegalArgumentException(
                    "The tissue outline must be closed, complete, and undamaged");
        }
        if (!outline.excludedGapSegments().isEmpty()) {
            throw new IllegalArgumentException(
                    "Exact outline warping does not guess across excluded gaps; remove the gap or keep the selected plane without outline warping");
        }
        if (outline.vertices().size() < 4) {
            throw new IllegalArgumentException(
                    "The tissue outline requires at least four vertices");
        }
        return outline;
    }

    /** Normalizes orientation and a stable dorsal cyclic start. */
    static List<Point2D> normalizeClosedLoop(final List<Point2D> values) {
        final List<Point2D> input = new ArrayList<>(List.copyOf(values));
        if (input.size() > 3 && input.get(0).equals(
                input.get(input.size() - 1))) {
            input.remove(input.size() - 1);
        }
        if (signedArea(input) < 0) {
            java.util.Collections.reverse(input);
        }
        final Bounds bounds = Bounds.of(input);
        int dorsal = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int index = 0; index < input.size(); index++) {
            final Point2D point = input.get(index);
            final double score = Math.pow(point.x() - bounds.centerX(), 2)
                    + Math.pow(point.y() - bounds.minimumY(), 2);
            if (score < best || score == best
                    && comparePoint(point, input.get(dorsal)) < 0) {
                best = score;
                dorsal = index;
            }
        }
        final List<Point2D> rotated = new ArrayList<>(input.size());
        for (int offset = 0; offset < input.size(); offset++) {
            rotated.add(input.get((dorsal + offset) % input.size()));
        }
        return List.copyOf(rotated);
    }

    private static double signedArea(final List<Point2D> loop) {
        double twiceArea = 0;
        for (int index = 0; index < loop.size(); index++) {
            final Point2D a = loop.get(index);
            final Point2D b = loop.get((index + 1) % loop.size());
            twiceArea += a.x() * b.y() - b.x() * a.y();
        }
        return 0.5 * twiceArea;
    }

    private static int comparePoint(final Point2D first, final Point2D second) {
        final int y = Double.compare(first.y(), second.y());
        return y != 0 ? y : Double.compare(first.x(), second.x());
    }

    /**
     * Returns the same ordered semantic anchor list with one endpoint index
     * replaced. ManualOutlineWarp2D.fit remains the authority for the four
     * distinct and unambiguous cyclic-order gates.
     */
    static List<ManualOutlineWarp2D.AnchorPair> replaceAnchorIndex(
            final List<ManualOutlineWarp2D.AnchorPair> anchors,
            final String anchorName,
            final boolean tissueEndpoint,
            final int loopIndex) {
        Objects.requireNonNull(anchorName, "anchorName");
        if (loopIndex < 0) {
            throw new IllegalArgumentException(
                    "Outline anchor index must be non-negative");
        }
        boolean found = false;
        final List<ManualOutlineWarp2D.AnchorPair> replaced =
                new ArrayList<>(anchors.size());
        for (final ManualOutlineWarp2D.AnchorPair anchor : anchors) {
            if (anchor.name().equals(anchorName)) {
                if (found) {
                    throw new IllegalArgumentException(
                            "Semantic outline anchor names must be unique");
                }
                found = true;
                replaced.add(new ManualOutlineWarp2D.AnchorPair(
                        anchor.name(),
                        tissueEndpoint ? anchor.atlasVertexIndex() : loopIndex,
                        tissueEndpoint ? loopIndex : anchor.tissueVertexIndex()));
            } else {
                replaced.add(anchor);
            }
        }
        if (!found) {
            throw new IllegalArgumentException(
                    "Unknown semantic outline anchor " + anchorName);
        }
        return List.copyOf(replaced);
    }

    /**
     * Validates guide evidence before any atlas planes are loaded. PARTIAL is
     * usable because the directed distance scores only the visible trace.
     * DAMAGED, UNCERTAIN, and side-unknown evidence fail closed.
     */
    static List<ManualContour> validatedGuideContours(
            final Collection<ManualContour> contours,
            final String guideAcronym,
            final SectionObservation observation) {
        Objects.requireNonNull(contours, "contours");
        Objects.requireNonNull(guideAcronym, "guideAcronym");
        Objects.requireNonNull(observation, "observation");
        final List<ManualContour> selected = contours.stream()
                .filter(contour -> contour.kind()
                        == ManualContourKind.ANATOMICAL_STRUCTURE)
                .filter(ManualContour::eligibleForPreview)
                .filter(contour -> contour.atlasGuide().orElseThrow()
                        .acronym().equals(guideAcronym))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException(
                    "Complete at least one contour for " + guideAcronym);
        }
        for (final ManualContour contour : selected) {
            if (contour.anatomicalSide() == AnatomicalSide.UNSURE) {
                throw new IllegalArgumentException(
                        "Choose the anatomical side for contour "
                                + contour.id() + " before searching");
            }
            if (contour.completeness() == ContourCompleteness.DAMAGED
                    || contour.completeness()
                            == ContourCompleteness.UNCERTAIN) {
                throw new IllegalArgumentException(
                        "Contour " + contour.id()
                                + " is damaged or uncertain; remove it or mark only its visible trace PARTIAL before searching");
            }
            requireSideCompatibleWithObservation(
                    contour.anatomicalSide(), observation);
        }
        return List.copyOf(selected);
    }

    private static void requireSideCompatibleWithObservation(
            final AnatomicalSide side,
            final SectionObservation observation) {
        switch (observation.observedHemisphere()) {
            case LEFT -> {
                if (side != AnatomicalSide.LEFT) {
                    throw new IllegalArgumentException(
                            "A left-hemisphere section requires LEFT anatomical guide contours");
                }
            }
            case RIGHT -> {
                if (side != AnatomicalSide.RIGHT) {
                    throw new IllegalArgumentException(
                            "A right-hemisphere section requires RIGHT anatomical guide contours");
                }
            }
            case BOTH -> { }
            case UNSURE -> throw new IllegalArgumentException(
                    "Confirm the observed anatomical hemisphere before searching");
        }
    }

    private static List<Point2D> atlasPointsForObservedHemisphere(
            final List<Point2D> points,
            final int atlasWidth,
            final ObservedAnatomicalHemisphere hemisphere) {
        final double midline = (atlasWidth - 1) / 2.0;
        return switch (hemisphere) {
            case BOTH, UNSURE -> List.copyOf(points);
            case LEFT -> points.stream()
                    .filter(point -> point.x() <= midline).toList();
            case RIGHT -> points.stream()
                    .filter(point -> point.x() >= midline).toList();
        };
    }

    private static List<Point2D> atlasPointsForSide(
            final List<Point2D> points,
            final int atlasWidth,
            final AnatomicalSide side) {
        final double midline = (atlasWidth - 1) / 2.0;
        return switch (side) {
            case BILATERAL -> List.copyOf(points);
            case LEFT -> points.stream()
                    .filter(point -> point.x() <= midline).toList();
            case RIGHT -> points.stream()
                    .filter(point -> point.x() >= midline).toList();
            case UNSURE -> throw new IllegalArgumentException(
                    "Anatomical side must be explicit");
        };
    }

    private static List<Point2D> contourPoints(
            final Collection<ManualContour> contours,
            final boolean anatomical,
            final String guideAcronym) {
        final List<Point2D> points = new ArrayList<>();
        for (final ManualContour contour : contours) {
            final boolean selected = anatomical
                    ? contour.kind() == ManualContourKind.ANATOMICAL_STRUCTURE
                            && contour.atlasGuide().orElseThrow().acronym()
                                    .equals(guideAcronym)
                    : contour.kind() == ManualContourKind.TISSUE_OUTLINE
                            || contour.kind()
                                    == ManualContourKind.VISIBLE_TISSUE_BOUNDARY;
            if (!selected || !contour.eligibleForPreview()) {
                continue;
            }
            if (contour.completeness() == ContourCompleteness.DAMAGED
                    || contour.completeness()
                            == ContourCompleteness.UNCERTAIN) {
                throw new IllegalArgumentException(
                        "Contour " + contour.id()
                                + " is damaged or uncertain and cannot influence candidate ranking");
            }
            points.addAll(resampleVisibleContour(contour));
        }
        return List.copyOf(points);
    }

    private static List<Point2D> resampleVisibleContour(
            final ManualContour contour) {
        final List<VisibleSegment> segments = new ArrayList<>();
        final List<ContourVertex> vertices = contour.vertices();
        final int segmentCount = contour.topology() == ContourTopology.CLOSED
                ? vertices.size() : vertices.size() - 1;
        double totalLength = 0;
        for (int index = 0; index < segmentCount; index++) {
            final ContourVertex from = vertices.get(index);
            final ContourVertex to = vertices.get(
                    (index + 1) % vertices.size());
            if (contour.excludedGapSegments().contains(
                    new ContourSegment(from.id(), to.id()))) {
                continue;
            }
            final double length = Math.hypot(
                    to.point().x() - from.point().x(),
                    to.point().y() - from.point().y());
            if (length > 0) {
                segments.add(new VisibleSegment(
                        from.point(), to.point(), length));
                totalLength += length;
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException(
                    "A candidate contour has no visible length");
        }
        final List<Point2D> sampled = new ArrayList<>(
                MANUAL_SAMPLES_PER_CONTOUR);
        for (int sample = 0; sample < MANUAL_SAMPLES_PER_CONTOUR; sample++) {
            double wanted = (sample + 0.5) * totalLength
                    / MANUAL_SAMPLES_PER_CONTOUR;
            final int before = sampled.size();
            for (final VisibleSegment segment : segments) {
                if (wanted <= segment.length()) {
                    final double fraction = wanted / segment.length();
                    sampled.add(new Point2D(
                            segment.from().x()
                                    + fraction * (segment.to().x()
                                            - segment.from().x()),
                            segment.from().y()
                                    + fraction * (segment.to().y()
                                            - segment.from().y())));
                    break;
                }
                wanted -= segment.length();
            }
            if (sampled.size() == before) {
                final SourcePixelPoint endpoint = segments
                        .get(segments.size() - 1).to();
                sampled.add(new Point2D(endpoint.x(), endpoint.y()));
            }
        }
        return List.copyOf(sampled);
    }

    private static List<Point2D> mapAndSample(
            final List<Point2D> points,
            final Function<Point2D, Point2D> mapping) {
        final int stride = Math.max(1,
                (int) Math.ceil(points.size() / (double) MAX_ATLAS_SAMPLES));
        final List<Point2D> sampled = new ArrayList<>();
        for (int index = 0; index < points.size(); index += stride) {
            sampled.add(mapping.apply(points.get(index)));
        }
        return List.copyOf(sampled);
    }

    private static OutlineMapping outlineMapping(
            final AtlasCoronalPlane plane,
            final SelectedAtlasContour root,
            final SelectedAtlasContour guide,
            final List<Point2D> visibleRoot,
            final List<Point2D> tissueOutline,
            final Collection<ManualContour> manualContours,
            final SectionObservation observation,
            final PreviewMapping previewMapping,
            final Optional<List<ManualOutlineWarp2D.AnchorPair>> anchors) {
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(guide, "guide");
        if (observation.geometry() == SectionGeometry.FULL) {
            return fullOutlineMapping(root, visibleRoot, manualContours,
                    observation, previewMapping, anchors);
        }
        final Bounds atlasBounds = Bounds.of(visibleRoot);
        final Bounds tissueBounds = Bounds.of(tissueOutline);
        final double scale = Math.min(
                tissueBounds.width() / atlasBounds.width(),
                tissueBounds.height() / atlasBounds.height());
        final double signedScaleX = observation.orientation().reflected()
                ? -scale : scale;
        final AffineTransform2D fallback = new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.SOURCE_PIXEL,
                signedScaleX, 0,
                tissueBounds.centerX()
                        - signedScaleX * atlasBounds.centerX(),
                0, scale,
                tissueBounds.centerY()
                        - scale * atlasBounds.centerY());
        return new OutlineMapping(
                fallback, sourceToPreview(previewMapping, fallback),
                Optional.empty(),
                OutlineWarpAnchorState.NOT_APPLICABLE_PARTIAL_AFFINE,
                "outline-bounds-similarity-v0-partial");
    }

    private static OutlineMapping fullOutlineMapping(
            final SelectedAtlasContour root,
            final List<Point2D> visibleRoot,
            final Collection<ManualContour> contours,
            final SectionObservation observation,
            final PreviewMapping previewMapping) {
        return fullOutlineMapping(root, visibleRoot, contours, observation,
                previewMapping, Optional.empty(), OptionalInt.empty());
    }

    private static OutlineMapping fullOutlineMapping(
            final SelectedAtlasContour root,
            final List<Point2D> visibleRoot,
            final Collection<ManualContour> contours,
            final SectionObservation observation,
            final PreviewMapping previewMapping,
            final Optional<List<ManualOutlineWarp2D.AnchorPair>> preferredAnchors) {
        return fullOutlineMapping(root, visibleRoot, contours, observation,
                previewMapping, preferredAnchors, OptionalInt.empty());
    }

    private static OutlineMapping fullOutlineMapping(
            final SelectedAtlasContour root,
            final List<Point2D> visibleRoot,
            final Collection<ManualContour> contours,
            final SectionObservation observation,
            final PreviewMapping previewMapping,
            final Optional<List<ManualOutlineWarp2D.AnchorPair>> preferredAnchors,
            final OptionalInt explicitSamplesPerAnchorArc) {
        if (observation.geometry() != SectionGeometry.FULL
                || observation.observedHemisphere()
                        != ObservedAnatomicalHemisphere.BOTH) {
            throw new IllegalArgumentException(
                    "A full-outline warp requires a FULL bilateral section observation");
        }
        final List<ManualContour> outlines = contours.stream()
                .filter(contour -> contour.kind()
                        == ManualContourKind.TISSUE_OUTLINE)
                .toList();
        if (outlines.size() != 1) {
            throw new IllegalArgumentException(
                    "A full-outline warp requires exactly one tissue outline");
        }
        final ManualContour outline = outlines.get(0);
        if (outline.topology() != ContourTopology.CLOSED
                || outline.captureStatus() != ContourCaptureStatus.COMPLETE
                || outline.completeness() != ContourCompleteness.COMPLETE
                || !outline.eligibleForPreview()) {
            throw new IllegalArgumentException(
                    "The full tissue outline must be closed, complete, and undamaged");
        }
        if (root.orderedExteriorLoop().size() < 4) {
            throw new IllegalArgumentException(
                    "The atlas root has no safe ordered exterior loop");
        }
        final DerivedTissueOutline derived = derivedTissueOutline(outline);
        final List<Point2D> tissueSource = derived.points();
        if (tissueSource.size() < 4) {
            throw new IllegalArgumentException(
                    "The full tissue outline requires at least four vertices");
        }
        final OutlineAffineFitter.FitResult fit = OutlineAffineFitter.fit(
                visibleRoot, tissueSource,
                observation.orientation().reflected());
        final AffineTransform2D atlasToPreview = sourceToPreview(
                previewMapping, fit.atlasToSource());
        final List<Point2D> atlasLoop = resampleClosedLoop(
                root.orderedExteriorLoop().stream()
                        .map(atlasToPreview::apply).toList(),
                OUTLINE_WARP_LOOP_SAMPLES);
        final List<Point2D> tissueLoop = resampleClosedLoop(
                tissueSource.stream().map(
                        previewMapping::sourceToPreview).toList(),
                OUTLINE_WARP_LOOP_SAMPLES);
        final List<ManualOutlineWarp2D.AnchorPair> anchors =
                preferredAnchors.orElseGet(() ->
                        provisionalSemanticAnchors(atlasLoop, tissueLoop));
        final ManualOutlineWarp2D warp = explicitSamplesPerAnchorArc.isPresent()
                ? ManualOutlineWarp2D.fit(
                        atlasLoop, tissueLoop, anchors,
                        previewMapping.previewWidth(),
                        previewMapping.previewHeight(),
                        explicitSamplesPerAnchorArc.getAsInt())
                : ManualOutlineWarp2D.fitStrict(
                        atlasLoop, tissueLoop, anchors,
                        previewMapping.previewWidth(),
                        previewMapping.previewHeight());
        return new OutlineMapping(
                fit.atlasToSource(), atlasToPreview, Optional.of(warp),
                OutlineWarpAnchorState.PROVISIONAL_UNCONFIRMED,
                fit.diagnostics().methodId() + derived.methodSuffix());
    }

    /**
     * Converts explicitly excluded reviewer segments into straight envelope
     * chords. Consecutive excluded segments therefore bridge a tear or a
     * non-homologous fissure instead of forcing the atlas through every draft
     * vertex inside that interval. The original contour and its gap flags
     * remain untouched in the guided-workflow audit history; the derived
     * points are also bound into the outline-warp content hash.
     */
    private static DerivedTissueOutline derivedTissueOutline(
            final ManualContour outline) {
        final List<ContourVertex> vertices = outline.vertices();
        final int count = vertices.size();
        final boolean[] excluded = new boolean[count];
        int excludedCount = 0;
        for (int index = 0; index < count; index++) {
            final ContourVertex from = vertices.get(index);
            final ContourVertex to = vertices.get((index + 1) % count);
            excluded[index] = outline.excludedGapSegments().contains(
                    new ContourSegment(from.id(), to.id()));
            if (excluded[index]) {
                excludedCount++;
            }
        }
        if (excludedCount == count) {
            throw new IllegalArgumentException(
                    "A full tissue outline cannot exclude every boundary segment");
        }
        final List<Point2D> points = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final boolean incomingExcluded = excluded[
                    Math.floorMod(index - 1, count)];
            final boolean outgoingExcluded = excluded[index];
            if (incomingExcluded && outgoingExcluded) {
                continue;
            }
            final SourcePixelPoint point = vertices.get(index).point();
            points.add(new Point2D(point.x(), point.y()));
        }
        if (points.size() < 4) {
            throw new IllegalArgumentException(
                    "Excluded outline intervals leave fewer than four envelope vertices");
        }
        return new DerivedTissueOutline(List.copyOf(points),
                excludedCount == 0 ? ""
                        : "+reviewer-excluded-gap-chord-v1");
    }

    private static AffineTransform2D sourceToPreview(
            final PreviewMapping previewMapping,
            final AffineTransform2D atlasToSource) {
        final double scaleX = previewMapping.scaleX();
        final double scaleY = previewMapping.scaleY();
        final AffineTransform2D sourceToPreview = new AffineTransform2D(
                CoordinateSpace2D.SOURCE_PIXEL,
                CoordinateSpace2D.PREVIEW_PIXEL,
                scaleX, 0, 0.5 * scaleX - 0.5,
                0, scaleY, 0.5 * scaleY - 0.5);
        return atlasToSource.andThen(sourceToPreview);
    }

    static List<ManualOutlineWarp2D.AnchorPair> provisionalSemanticAnchors(
            final List<Point2D> atlasLoop,
            final List<Point2D> tissueLoop) {
        final List<SemanticAnchor> atlas = semanticAnchorIndices(atlasLoop);
        final List<SemanticAnchor> tissue = semanticAnchorIndices(tissueLoop);
        final List<ManualOutlineWarp2D.AnchorPair> result = new ArrayList<>(4);
        for (int index = 0; index < atlas.size(); index++) {
            if (!atlas.get(index).name().equals(tissue.get(index).name())) {
                throw new IllegalArgumentException(
                        "Semantic outline-anchor order is inconsistent");
            }
            result.add(new ManualOutlineWarp2D.AnchorPair(
                    atlas.get(index).name(), atlas.get(index).index(),
                    tissue.get(index).index()));
        }
        return List.copyOf(result);
    }

    private static List<SemanticAnchor> semanticAnchorIndices(
            final List<Point2D> loop) {
        if (loop.size() < 4) {
            throw new IllegalArgumentException(
                    "Semantic anchors require a closed loop with at least four vertices");
        }
        final Bounds bounds = Bounds.of(loop);
        final List<AnchorTarget> targets = List.of(
                new AnchorTarget("dorsal-midline", bounds.centerX(),
                        bounds.minimumY()),
                new AnchorTarget("image-right", bounds.maximumX(),
                        bounds.centerY()),
                new AnchorTarget("ventral-midline", bounds.centerX(),
                        bounds.maximumY()),
                new AnchorTarget("image-left", bounds.minimumX(),
                        bounds.centerY()));
        final Set<Integer> used = new java.util.HashSet<>();
        final List<SemanticAnchor> anchors = new ArrayList<>(4);
        for (final AnchorTarget target : targets) {
            int best = -1;
            double bestSquared = Double.POSITIVE_INFINITY;
            for (int index = 0; index < loop.size(); index++) {
                if (used.contains(index)) {
                    continue;
                }
                final Point2D point = loop.get(index);
                final double squared = Math.pow(
                        point.x() - target.x(), 2)
                        + Math.pow(point.y() - target.y(), 2);
                if (squared < bestSquared) {
                    best = index;
                    bestSquared = squared;
                }
            }
            if (best < 0 || !used.add(best)) {
                throw new IllegalArgumentException(
                        "Could not propose four distinct semantic outline anchors");
            }
            anchors.add(new SemanticAnchor(target.name(), best));
        }
        return List.copyOf(anchors);
    }

    private static List<Point2D> resampleClosedLoop(
            final List<Point2D> loop,
            final int sampleCount) {
        if (loop.size() < 4 || sampleCount < 4) {
            throw new IllegalArgumentException(
                    "Closed-loop resampling requires at least four points");
        }
        double length = 0;
        final double[] cumulative = new double[loop.size() + 1];
        for (int index = 0; index < loop.size(); index++) {
            length += distance(loop.get(index),
                    loop.get((index + 1) % loop.size()));
            cumulative[index + 1] = length;
        }
        if (!Double.isFinite(length) || length <= 0) {
            throw new IllegalArgumentException(
                    "Closed outline must have positive finite perimeter");
        }
        final List<Point2D> sampled = new ArrayList<>(sampleCount);
        int edge = 0;
        for (int sample = 0; sample < sampleCount; sample++) {
            final double wanted = sample * length / sampleCount;
            while (edge + 1 < cumulative.length
                    && cumulative[edge + 1] < wanted) {
                edge++;
            }
            final Point2D from = loop.get(edge % loop.size());
            final Point2D to = loop.get((edge + 1) % loop.size());
            final double edgeLength = cumulative[edge + 1]
                    - cumulative[edge];
            final double fraction = edgeLength == 0 ? 0
                    : (wanted - cumulative[edge]) / edgeLength;
            sampled.add(new Point2D(
                    from.x() + fraction * (to.x() - from.x()),
                    from.y() + fraction * (to.y() - from.y())));
        }
        return List.copyOf(sampled);
    }

    private static double distance(final Point2D first, final Point2D second) {
        return Math.hypot(first.x() - second.x(), first.y() - second.y());
    }

    private static double symmetricChamfer(
            final List<Point2D> first,
            final List<Point2D> second) {
        return 0.5 * (directedChamfer(first, second)
                + directedChamfer(second, first));
    }

    private static double directedChamfer(
            final List<Point2D> requested,
            final List<Point2D> reference) {
        double total = 0;
        for (final Point2D point : requested) {
            double nearestSquared = Double.POSITIVE_INFINITY;
            for (final Point2D candidate : reference) {
                final double dx = point.x() - candidate.x();
                final double dy = point.y() - candidate.y();
                nearestSquared = Math.min(
                        nearestSquared, dx * dx + dy * dy);
            }
            total += Math.sqrt(nearestSquared);
        }
        return total / requested.size();
    }

    private static String signed(final double value) {
        return String.format(java.util.Locale.ROOT, "%+.1f", value);
    }

    private record Bounds(
            double minimumX,
            double minimumY,
            double maximumX,
            double maximumY) {

        static Bounds of(final List<Point2D> points) {
            if (points.isEmpty()) {
                throw new IllegalArgumentException("Bounds require points");
            }
            double minimumX = Double.POSITIVE_INFINITY;
            double minimumY = Double.POSITIVE_INFINITY;
            double maximumX = Double.NEGATIVE_INFINITY;
            double maximumY = Double.NEGATIVE_INFINITY;
            for (final Point2D point : points) {
                minimumX = Math.min(minimumX, point.x());
                minimumY = Math.min(minimumY, point.y());
                maximumX = Math.max(maximumX, point.x());
                maximumY = Math.max(maximumY, point.y());
            }
            if (maximumX <= minimumX || maximumY <= minimumY) {
                throw new IllegalArgumentException(
                        "Contour bounds must span two dimensions");
            }
            return new Bounds(minimumX, minimumY, maximumX, maximumY);
        }

        double width() {
            return maximumX - minimumX;
        }

        double height() {
            return maximumY - minimumY;
        }

        double centerX() {
            return (minimumX + maximumX) / 2;
        }

        double centerY() {
            return (minimumY + maximumY) / 2;
        }
    }

    private record VisibleSegment(
            SourcePixelPoint from,
            SourcePixelPoint to,
            double length) {

        private VisibleSegment {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (!Double.isFinite(length) || length <= 0) {
                throw new IllegalArgumentException(
                        "Visible segment length must be positive");
            }
        }
    }

    private record DerivedTissueOutline(
            List<Point2D> points,
            String methodSuffix) {

        private DerivedTissueOutline {
            points = List.copyOf(Objects.requireNonNull(points, "points"));
            methodSuffix = Objects.requireNonNull(
                    methodSuffix, "methodSuffix");
        }
    }

    private record OutlineMapping(
            AffineTransform2D atlasToSource,
            AffineTransform2D atlasToPreviewAffine,
            Optional<ManualOutlineWarp2D> outlineWarp,
            OutlineWarpAnchorState anchorState,
            String methodId) {

        private OutlineMapping {
            Objects.requireNonNull(atlasToSource, "atlasToSource");
            Objects.requireNonNull(
                    atlasToPreviewAffine, "atlasToPreviewAffine");
            Objects.requireNonNull(outlineWarp, "outlineWarp");
            Objects.requireNonNull(anchorState, "anchorState");
            Objects.requireNonNull(methodId, "methodId");
        }

        Point2D mapAtlasToPreview(final Point2D point) {
            final Point2D global = atlasToPreviewAffine.apply(point);
            return outlineWarp.map(value -> value.apply(global))
                    .orElse(global);
        }
    }

    private record AnchorTarget(String name, double x, double y) { }

    private record SemanticAnchor(String name, int index) { }
}
