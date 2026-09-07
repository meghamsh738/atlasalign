package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CoordinateSpace2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class BoundaryFitSolverTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void exactlyRecoversSimilarityAndIgnoresExcludedOutlier() {
        final List<Point2D> source = ellipse(
                new Point2D(180, 130), 95, 70, Math.toRadians(12), 10);
        final AffineTransform2D expected = similarity(
                1.08, Math.toRadians(7), 11, -6);
        final List<BoundaryFitMatch> matches = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            matches.add(match(index, source.get(index),
                    expected.apply(source.get(index)), true));
        }
        matches.add(new BoundaryFitMatch("excluded-outlier",
                new Point2D(80, 70), new Point2D(390, 290),
                BoundaryFitMatchOrigin.USER_PLACED, false));

        final BoundaryFitCandidate candidate = new BoundaryFitSolver()
                .solve(request(BoundaryFitModel.SIMILARITY,
                        Math.toRadians(12), source,
                        source.stream().map(expected::apply).toList(),
                        matches));

        assertEquals(10, candidate.includedMatchCount());
        assertEquals(0, candidate.rootMeanSquareResidual(), 1e-9);
        for (final Point2D point : source) {
            assertPoint(expected.apply(point),
                    candidate.previewCorrection().apply(point), 1e-9);
        }
    }

    @Test
    void exactlyRecoversPerpendicularIndependentAxes() {
        final double sourceAxis = Math.toRadians(18);
        final double targetAxis = Math.toRadians(25);
        final List<Point2D> canonical = ellipse(
                new Point2D(170, 120), 90, 55, 0, 12);
        final AffineTransform2D current = atlasToPreview(
                Math.cos(sourceAxis), -Math.sin(sourceAxis), 0,
                Math.sin(sourceAxis), Math.cos(sourceAxis), 0);
        final List<Point2D> source = canonical.stream()
                .map(current::apply).toList();
        final List<Point2D> target = orthogonalTargets(
                canonical, new Point2D(170, 120), 0,
                new Point2D(190, 112), targetAxis, 1.16, 0.86);
        final List<BoundaryFitMatch> matches = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            matches.add(match(index, source.get(index),
                    target.get(index), true));
        }

        final BoundaryFitCandidate candidate = new BoundaryFitSolver()
                .solve(request(BoundaryFitModel.ORTHOGONAL_XY,
                        sourceAxis, source, target, matches, current));

        assertTrue(candidate.rootMeanSquareResidual() < 1e-9);
        for (int index = 0; index < source.size(); index++) {
            assertPoint(target.get(index), candidate.previewCorrection()
                    .apply(source.get(index)), 1e-8);
        }
        final Point2D x = vector(candidate.previewCorrection(),
                new Point2D(Math.cos(sourceAxis), Math.sin(sourceAxis)));
        final Point2D y = vector(candidate.previewCorrection(),
                new Point2D(-Math.sin(sourceAxis), Math.cos(sourceAxis)));
        assertEquals(0, x.x() * y.x() + x.y() * y.y(), 1e-9,
                "the fitted atlas axes must remain perpendicular");
    }

    @Test
    void canonicalFitRemovesShearFromTheCurrentDisplayedPlacement() {
        final List<Point2D> canonical = ellipse(
                new Point2D(180, 130), 95, 70, 0, 12);
        final AffineTransform2D current = atlasToPreview(
                1.0, 0.16, 24,
                0.04, 0.94, 18);
        final double angle = Math.toRadians(6);
        final double scale = 1.04;
        final AffineTransform2D expected = atlasToPreview(
                scale * Math.cos(angle), -scale * Math.sin(angle), 15,
                scale * Math.sin(angle), scale * Math.cos(angle), -7);
        final List<Point2D> displayed = canonical.stream()
                .map(current::apply).toList();
        final List<Point2D> target = canonical.stream()
                .map(expected::apply).toList();
        final List<BoundaryFitMatch> matches = new ArrayList<>();
        for (int index = 0; index < canonical.size(); index++) {
            matches.add(match(index, displayed.get(index),
                    target.get(index), true));
        }

        final BoundaryFitCandidate candidate = new BoundaryFitSolver()
                .solve(request(BoundaryFitModel.SIMILARITY,
                        Math.atan2(current.m10(), current.m00()),
                        displayed, target, matches, current));
        final AffineTransform2D finalPlacement = current.andThen(
                candidate.previewCorrection());

        for (final Point2D point : canonical) {
            assertPoint(expected.apply(point),
                    finalPlacement.apply(point), 1e-8);
        }
        assertEquals(0,
                finalPlacement.m00() * finalPlacement.m01()
                        + finalPlacement.m10() * finalPlacement.m11(),
                1e-8,
                "guided matching must replace inherited shear rather than preserve it");
    }

    @Test
    void reflectedCanonicalFitRemovesShearAndRetainsChosenParity() {
        final List<Point2D> rawAtlas = ellipse(
                new Point2D(180, 130), 95, 70, 0, 12);
        final AffineTransform2D reflection = atlasReflection(420);
        final List<Point2D> orientedAtlas = rawAtlas.stream()
                .map(reflection::apply).toList();
        final AffineTransform2D currentOriented = atlasToPreview(
                1.0, 0.16, 24,
                0.04, 0.94, 18);
        final double angle = Math.toRadians(6);
        final double scale = 1.04;
        final AffineTransform2D expectedOriented = atlasToPreview(
                scale * Math.cos(angle), -scale * Math.sin(angle), 15,
                scale * Math.sin(angle), scale * Math.cos(angle), -7);
        final List<Point2D> displayed = orientedAtlas.stream()
                .map(currentOriented::apply).toList();
        final List<Point2D> target = orientedAtlas.stream()
                .map(expectedOriented::apply).toList();
        final List<BoundaryFitMatch> matches = new ArrayList<>();
        for (int index = 0; index < rawAtlas.size(); index++) {
            matches.add(match(index, displayed.get(index),
                    target.get(index), true));
        }

        final BoundaryFitCandidate candidate = new BoundaryFitSolver()
                .solve(request(BoundaryFitModel.SIMILARITY,
                        Math.atan2(currentOriented.m10(),
                                currentOriented.m00()),
                        displayed, target, matches, currentOriented));
        final AffineTransform2D finalRawPlacement = reflection
                .andThen(currentOriented)
                .andThen(candidate.previewCorrection());
        final AffineTransform2D expectedRawPlacement = reflection
                .andThen(expectedOriented);

        assertTrue(candidate.previewCorrection().determinant() > 0,
                "the fitted correction must not consume the explicit reflection");
        assertTrue(finalRawPlacement.determinant() < 0,
                "the final atlas placement must retain reflected parity");
        for (final Point2D point : rawAtlas) {
            assertPoint(expectedRawPlacement.apply(point),
                    finalRawPlacement.apply(point), 1e-8);
        }
        assertEquals(0,
                finalRawPlacement.m00() * finalRawPlacement.m01()
                        + finalRawPlacement.m10()
                        * finalRawPlacement.m11(),
                1e-8,
                "the reflected final atlas axes must remain perpendicular");
    }

    @Test
    void manualFitIsDeterministic() {
        final List<Point2D> source = irregularBoundary();
        final AffineTransform2D expected = similarity(
                1.03, Math.toRadians(3), 6, -4);
        final List<Point2D> target = source.stream()
                .map(expected::apply).toList();
        final List<BoundaryFitMatch> matches = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            matches.add(match(index, source.get(index),
                    target.get(index), true));
        }
        final BoundaryFitRequest request = request(
                BoundaryFitModel.SIMILARITY, 0,
                source, target, matches);
        final BoundaryFitSolver solver = new BoundaryFitSolver();

        final BoundaryFitCandidate first = solver.solve(request);
        final BoundaryFitCandidate second = solver.solve(request);

        assertEquals(first, second);
        assertEquals(matches.size(), first.includedMatchCount());
        assertTrue(first.rootMeanSquareResidual() < 1e-9);
    }

    @Test
    void rejectsTooFewOrClusteredMatchesWithoutATransform() {
        final List<Point2D> source = irregularBoundary();
        final List<BoundaryFitMatch> matches = List.of(
                match(0, source.get(0), source.get(0), true),
                match(1, source.get(1), source.get(1), true),
                match(2, source.get(2), source.get(2), true));

        final BoundaryFitException error = assertThrows(
                BoundaryFitException.class, () -> new BoundaryFitSolver()
                        .solve(request(BoundaryFitModel.SIMILARITY, 0,
                                source, source, matches)));

        assertEquals(BoundaryFitFailureKind.INSUFFICIENT_COVERAGE,
                error.kind());
    }

    @Test
    void rejectsFourPairsConfinedToOneStraightEdge() {
        final List<Point2D> edge = List.of(
                new Point2D(70, 110), new Point2D(145, 110),
                new Point2D(220, 110), new Point2D(295, 110));
        final List<BoundaryFitMatch> matches = new ArrayList<>();
        for (int index = 0; index < edge.size(); index++) {
            matches.add(match(index, edge.get(index),
                    new Point2D(edge.get(index).x() + 5, 116), true));
        }

        final BoundaryFitException error = assertThrows(
                BoundaryFitException.class, () -> new BoundaryFitSolver()
                        .solve(request(BoundaryFitModel.SIMILARITY, 0,
                                edge, edge, matches)));

        assertEquals(BoundaryFitFailureKind.INSUFFICIENT_COVERAGE,
                error.kind());
    }

    @Test
    void fitsOnlyReviewerCompletedPairsWhenTornEdgesAreAlsoPresent() {
        final List<Point2D> source = ellipse(
                new Point2D(180, 130), 95, 70, 0, 32);
        final List<BoundaryFitSample> atlas = radialSamples(
                source, new Point2D(180, 130), false);
        final List<Point2D> correctPoints = source.stream()
                .map(point -> new Point2D(point.x() + 6, point.y()))
                .toList();
        final List<BoundaryFitSample> tissue = new ArrayList<>(
                radialSamples(correctPoints, new Point2D(186, 130), false));
        final List<Point2D> tornPoints = source.stream()
                .map(point -> new Point2D(point.x() + 1, point.y()))
                .toList();
        tissue.addAll(radialSamples(
                tornPoints, new Point2D(181, 130), true));
        final List<BoundaryFitMatch> matches = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            matches.add(match(index, source.get(index),
                    correctPoints.get(index), true));
        }
        final BoundaryFitRequest request = new BoundaryFitRequest(
                4, BoundaryFitModel.SIMILARITY, ReviewSectionMode.FULL,
                Optional.empty(), atlas, tissue, matches,
                identityAtlasToPreview(), 0,
                420, 320, HASH, HASH, HASH, HASH, HASH);

        final BoundaryFitCandidate candidate = new BoundaryFitSolver()
                .solve(request);

        assertEquals(6, candidate.previewCorrection().m02(), 1e-6);
        assertEquals(0, candidate.previewCorrection().m12(), 1e-6);
        assertEquals(matches, candidate.matches());
    }

    @Test
    void excludedTornPairsDoNotPullTheManualFit() {
        final List<Point2D> source = irregularBoundary();
        final AffineTransform2D expected = similarity(
                1.02, Math.toRadians(2), 5, -3);
        final List<Point2D> tissue = new ArrayList<>(source.stream()
                .map(expected::apply).toList());
        for (int index = 18; index <= 31; index++) {
            final Point2D point = tissue.get(index);
            tissue.set(index, new Point2D(
                    point.x() + 65, point.y() - 45));
        }
        final List<BoundaryFitMatch> matches = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            matches.add(match(index, source.get(index), tissue.get(index),
                    index < 18 || index > 31));
        }

        final BoundaryFitCandidate candidate = new BoundaryFitSolver()
                .solve(request(BoundaryFitModel.SIMILARITY, 0,
                        source, tissue, matches));

        assertPoint(expected.apply(new Point2D(190, 145)),
                candidate.previewCorrection().apply(
                        new Point2D(190, 145)), 8);
    }

    @Test
    void leavesAbsoluteResizeSafetyToTheControllerAudit() {
        final List<Point2D> source = ellipse(
                new Point2D(180, 130), 95, 70, 0, 8);
        final AffineTransform2D unsafe = similarity(2, 0, 0, 0);
        final List<BoundaryFitMatch> matches = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            matches.add(match(index, source.get(index),
                    unsafe.apply(source.get(index)), true));
        }

        final BoundaryFitCandidate candidate = new BoundaryFitSolver()
                .solve(request(BoundaryFitModel.SIMILARITY, 0,
                        source, source.stream().map(unsafe::apply).toList(),
                        matches));

        assertPoint(unsafe.apply(new Point2D(180, 130)),
                candidate.previewCorrection().apply(
                        new Point2D(180, 130)), 1e-9);
    }

    @Test
    void progressivePreviewUsesTranslationThenSimilarityThenSelectedModel() {
        final List<Point2D> source = ellipse(
                new Point2D(180, 130), 95, 70, 0, 8);
        final AffineTransform2D expected = similarity(
                1.06, Math.toRadians(5), 8, -4);
        final List<Point2D> target = source.stream()
                .map(expected::apply).toList();
        final List<BoundaryFitMatch> matches = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            matches.add(match(index, source.get(index),
                    target.get(index), true));
        }
        final BoundaryFitSolver solver = new BoundaryFitSolver();

        final BoundaryFitPreview one = solver.preview(request(
                BoundaryFitModel.SIMILARITY, 0, source, target,
                matches.subList(0, 1)));
        final BoundaryFitPreview two = solver.preview(request(
                BoundaryFitModel.SIMILARITY, 0, source, target,
                matches.subList(0, 2)));
        final BoundaryFitPreview three = solver.preview(request(
                BoundaryFitModel.ORTHOGONAL_XY, 0, source, target,
                matches.subList(0, 3)));

        assertEquals(BoundaryFitPreviewKind.TRANSLATION, one.kind());
        assertPoint(target.get(0), one.previewCorrection()
                .apply(source.get(0)), 1e-9);
        assertEquals(BoundaryFitPreviewKind.SIMILARITY, two.kind());
        assertPoint(target.get(0), two.previewCorrection()
                .apply(source.get(0)), 1e-9);
        assertPoint(target.get(1), two.previewCorrection()
                .apply(source.get(1)), 1e-9);
        assertEquals(BoundaryFitPreviewKind.ORTHOGONAL_XY, three.kind());
        assertTrue(Double.isFinite(three.rootMeanSquareResidual()));
    }

    private static BoundaryFitRequest request(
            final BoundaryFitModel model,
            final double sourceAxis,
            final List<Point2D> source,
            final List<Point2D> target,
            final List<BoundaryFitMatch> matches) {
        return request(model, sourceAxis, source, target, matches,
                identityAtlasToPreview());
    }

    private static BoundaryFitRequest request(
            final BoundaryFitModel model,
            final double sourceAxis,
            final List<Point2D> source,
            final List<Point2D> target,
            final List<BoundaryFitMatch> matches,
            final AffineTransform2D currentOrientedAtlasToPreview) {
        return new BoundaryFitRequest(4, model, ReviewSectionMode.FULL,
                Optional.empty(), samples(source), samples(target), matches,
                currentOrientedAtlasToPreview, sourceAxis, 420, 320,
                HASH, HASH, HASH, HASH, HASH);
    }

    private static AffineTransform2D identityAtlasToPreview() {
        return atlasToPreview(1, 0, 0, 0, 1, 0);
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

    private static AffineTransform2D atlasReflection(final double width) {
        return new AffineTransform2D(
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                CoordinateSpace2D.ATLAS_PLANE_PIXEL,
                -1, 0, width - 1,
                0, 1, 0);
    }

    private static List<BoundaryFitSample> samples(
            final List<Point2D> points) {
        final Point2D centre = new Point2D(points.stream()
                .mapToDouble(Point2D::x).average().orElseThrow(),
                points.stream().mapToDouble(Point2D::y)
                        .average().orElseThrow());
        return points.stream().map(point -> new BoundaryFitSample(point,
                new Point2D(point.x() - centre.x(),
                        point.y() - centre.y()))).toList();
    }

    private static List<BoundaryFitSample> radialSamples(
            final List<Point2D> points,
            final Point2D centre,
            final boolean reverse) {
        final double direction = reverse ? -1 : 1;
        return points.stream().map(point -> new BoundaryFitSample(point,
                new Point2D(direction * (point.x() - centre.x()),
                        direction * (point.y() - centre.y())))).toList();
    }

    private static BoundaryFitMatch match(
            final int index,
            final Point2D source,
            final Point2D target,
            final boolean included) {
        return new BoundaryFitMatch("match-" + index, source, target,
                BoundaryFitMatchOrigin.USER_PLACED, included);
    }

    private static List<Point2D> ellipse(
            final Point2D centre,
            final double radiusX,
            final double radiusY,
            final double angle,
            final int count) {
        final List<Point2D> result = new ArrayList<>();
        final double cosine = Math.cos(angle);
        final double sine = Math.sin(angle);
        for (int index = 0; index < count; index++) {
            final double theta = Math.PI * 2 * index / count;
            final double x = radiusX * Math.cos(theta);
            final double y = radiusY * Math.sin(theta);
            result.add(new Point2D(
                    centre.x() + cosine * x - sine * y,
                    centre.y() + sine * x + cosine * y));
        }
        return result;
    }

    private static List<Point2D> irregularBoundary() {
        final List<Point2D> result = new ArrayList<>();
        for (int index = 0; index < 96; index++) {
            final double angle = Math.PI * 2 * index / 96;
            final double radiusX = 102 + 9 * Math.sin(3 * angle);
            final double radiusY = 72 + 6 * Math.cos(5 * angle);
            result.add(new Point2D(190 + radiusX * Math.cos(angle),
                    145 + radiusY * Math.sin(angle)));
        }
        return result;
    }

    private static List<Point2D> orthogonalTargets(
            final List<Point2D> source,
            final Point2D sourceCentre,
            final double sourceAxis,
            final Point2D targetCentre,
            final double targetAxis,
            final double scaleX,
            final double scaleY) {
        final double sourceCosine = Math.cos(sourceAxis);
        final double sourceSine = Math.sin(sourceAxis);
        final double targetCosine = Math.cos(targetAxis);
        final double targetSine = Math.sin(targetAxis);
        return source.stream().map(point -> {
            final double dx = point.x() - sourceCentre.x();
            final double dy = point.y() - sourceCentre.y();
            final double u = sourceCosine * dx + sourceSine * dy;
            final double v = -sourceSine * dx + sourceCosine * dy;
            return new Point2D(
                    targetCentre.x() + targetCosine * scaleX * u
                            - targetSine * scaleY * v,
                    targetCentre.y() + targetSine * scaleX * u
                            + targetCosine * scaleY * v);
        }).toList();
    }

    private static AffineTransform2D similarity(
            final double scale,
            final double angle,
            final double translateX,
            final double translateY) {
        final double cosine = Math.cos(angle);
        final double sine = Math.sin(angle);
        return new AffineTransform2D(
                org.atlasalign.core.CoordinateSpace2D.PREVIEW_PIXEL,
                org.atlasalign.core.CoordinateSpace2D.PREVIEW_PIXEL,
                scale * cosine, -scale * sine, translateX,
                scale * sine, scale * cosine, translateY);
    }

    private static Point2D vector(
            final AffineTransform2D transform,
            final Point2D vector) {
        return new Point2D(
                transform.m00() * vector.x()
                        + transform.m01() * vector.y(),
                transform.m10() * vector.x()
                        + transform.m11() * vector.y());
    }

    private static void assertPoint(
            final Point2D expected,
            final Point2D actual,
            final double tolerance) {
        assertEquals(expected.x(), actual.x(), tolerance);
        assertEquals(expected.y(), actual.y(), tolerance);
    }
}
