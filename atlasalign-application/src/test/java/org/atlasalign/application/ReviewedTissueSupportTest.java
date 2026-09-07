package org.atlasalign.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ReviewedTissueSupportTest {

    @Test
    void reviewerPolygonUsesExactVerticesAndPixelCentreMembership() {
        final List<Point2D> polygon = List.of(
                new Point2D(10, 8),
                new Point2D(62, 8),
                new Point2D(62, 44),
                new Point2D(10, 44));

        final ReviewedTissueSupport support =
                ReviewedTissueSupport.fromPolygon(80, 60, polygon);

        assertEquals(List.of(polygon), support.polygons());
        assertEquals(4, support.controls().size());
        assertTrue(support.contains(new Point2D(20, 20)));
        assertFalse(support.contains(new Point2D(5, 20)));
        assertEquals(support.supportMask().foregroundCount(),
                support.sourceMask().foregroundCount());
    }

    @Test
    void reviewerPolygonRejectsTooFewOrSelfIntersectingPoints() {
        assertThrows(IllegalArgumentException.class,
                () -> ReviewedTissueSupport.fromPolygon(80, 60, List.of(
                        new Point2D(10, 10),
                        new Point2D(50, 10),
                        new Point2D(30, 40))));
        assertThrows(IllegalArgumentException.class,
                () -> ReviewedTissueSupport.fromPolygon(80, 60, List.of(
                        new Point2D(10, 10),
                        new Point2D(60, 50),
                        new Point2D(10, 50),
                        new Point2D(60, 10))));
    }

    @Test
    void maskSuggestionIsDeterministicAndKeepsTwentyFourControls() {
        final BinaryMask mask = rectangleMask(32, 24, 4, 3, 20, 15);

        final ReviewedTissueSupport first =
                ReviewedTissueSupport.fromMask(mask);
        final ReviewedTissueSupport second =
                ReviewedTissueSupport.fromMask(mask);

        assertEquals(first, second);
        assertEquals(first.sha256(), second.sha256());
        assertEquals(1, first.polygons().size());
        assertEquals(24, first.controls().size());
        assertEquals(mask.foregroundCount(), first.supportMask().foregroundCount());
        assertTrue(first.contains(new Point2D(10, 10)));
        assertTrue(!first.contains(new Point2D(1, 1)));
    }

    @Test
    void componentAllocationRetainsFourNodesPerComponentAndCapsAtFortyEight() {
        final boolean[] values = new boolean[40 * 20];
        fill(values, 40, 2, 3, 8, 8);
        fill(values, 40, 22, 5, 10, 9);
        final ReviewedTissueSupport support = ReviewedTissueSupport.fromMask(
                BinaryMask.fromBooleans(40, 20, values), 4);

        assertEquals(2, support.polygons().size());
        assertEquals(8, support.controls().size());
        assertTrue(support.controls().stream().filter(
                control -> control.componentIndex() == 0).count() >= 4);
        assertTrue(support.controls().stream().filter(
                control -> control.componentIndex() == 1).count() >= 4);

        final ReviewedTissueSupport dense = ReviewedTissueSupport.fromMask(
                BinaryMask.fromBooleans(40, 20, values), 48);
        assertEquals(48, dense.controls().size());
    }

    @Test
    void maskSuggestionFillsInternalHolesWithoutSpendingComponentControls() {
        final boolean[] values = new boolean[48 * 36];
        fill(values, 48, 5, 4, 34, 26);
        clear(values, 48, 12, 10, 7, 6);
        clear(values, 48, 29, 18, 5, 7);

        final BinaryMask source = BinaryMask.fromBooleans(48, 36, values);
        final ReviewedTissueSupport support =
                ReviewedTissueSupport.fromMask(source);

        assertEquals(1, support.polygons().size());
        assertEquals(24, support.controls().size());
        assertTrue(support.contains(new Point2D(14, 13)),
                "internal mask holes are filled in the outer tissue support");
        assertTrue(support.supportMask().foregroundCount()
                        > source.foregroundCount(),
                "filled support must include pixels removed by segmentation holes");
    }

    @Test
    void maskSuggestionDiscardsSmallSegmentationDebrisBeforeComponentCap() {
        final int width = 80;
        final boolean[] values = new boolean[width * 50];
        fill(values, width, 10, 10, 50, 30);
        fill(values, width, 65, 20, 5, 5);
        for (int speck = 0; speck < 20; speck++) {
            values[2 * width + 2 + speck * 3] = true;
        }

        final ReviewedTissueSupport support = ReviewedTissueSupport.fromMask(
                BinaryMask.fromBooleans(width, 50, values));

        assertEquals(2, support.polygons().size());
        assertEquals(24, support.controls().size());
        assertTrue(support.contains(new Point2D(20, 20)));
        assertTrue(support.contains(new Point2D(67, 22)));
        assertTrue(!support.contains(new Point2D(2, 2)),
                "isolated contrast specks must not consume crop components");
    }

    @Test
    void maskSuggestionSeparatesBoundaryCyclesAtDiagonalPointContact() {
        final int width = 8;
        final boolean[] values = new boolean[width * 8];
        values[2 * width + 2] = true;
        values[2 * width + 1] = true;
        values[3 * width + 1] = true;
        values[4 * width + 1] = true;
        values[4 * width + 2] = true;
        values[4 * width + 3] = true;
        values[3 * width + 3] = true;

        final ReviewedTissueSupport support = ReviewedTissueSupport.fromMask(
                BinaryMask.fromBooleans(width, 8, values));

        assertEquals(1, support.polygons().size());
        assertEquals(24, support.controls().size());
        assertTrue(support.contains(new Point2D(2, 3)),
                "the pinched internal hole is filled without a figure-eight contour");
    }

    @Test
    void editedComponentsCannotOverlapAndCreateXorHoles() {
        final BinaryMask source = rectangleMask(40, 30, 2, 2, 30, 22);
        final List<Point2D> first = List.of(
                new Point2D(5, 5), new Point2D(20, 5),
                new Point2D(20, 18), new Point2D(5, 18));
        final List<Point2D> overlapping = List.of(
                new Point2D(15, 10), new Point2D(28, 10),
                new Point2D(28, 24), new Point2D(15, 24));

        final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new ReviewedTissueSupport(
                        source,
                        List.of(first, overlapping),
                        List.of(
                                controlsFor(first, "first"),
                                controlsFor(overlapping, "second"))
                                .stream()
                                .flatMap(List::stream)
                                .toList()));
        assertTrue(failure.getMessage().contains("overlap"));
    }

    @Test
    void moveInsertDeleteAreImmutableAndHashChanging() {
        final ReviewedTissueSupport original = ReviewedTissueSupport.fromMask(
                rectangleMask(32, 24, 4, 3, 20, 15));
        final String id = original.controls().get(3).id();
        final ReviewedTissueSupport moved = original.moveControl(
                id, new Point2D(4.25, 4.25));

        assertEquals(original.controls().get(3).origin(),
                moved.controls().get(3).origin());
        assertNotEquals(original.sha256(), moved.sha256());
        assertNotEquals(original.polygons(), moved.polygons());

        final ReviewedTissueSupport inserted = moved.insertControl(
                0, 0, "user-crop-1", new Point2D(4.5, 3));
        assertEquals(25, inserted.controls().size());
        final ReviewedTissueSupport deleted = inserted.deleteControl("user-crop-1");
        assertEquals(moved, deleted);
        assertThrows(IllegalArgumentException.class, () -> {
            ReviewedTissueSupport minimum = original;
            for (int index = 0; index < 21; index++) {
                minimum = minimum.deleteControl(
                        minimum.controls().get(0).id());
            }
        });
    }

    @Test
    void movingCropNodeChangesOnlyItsLocalPerimeterArc() {
        final ReviewedTissueSupport original = ReviewedTissueSupport.fromMask(
                rectangleMask(32, 24, 4, 3, 20, 15));
        final List<ReviewedTissueSupport.Control> controls = original.controls()
                .stream()
                .sorted(Comparator.comparingInt(
                        ReviewedTissueSupport.Control::vertexIndex))
                .toList();
        final ReviewedTissueSupport.Control selected = controls.get(6);
        final ReviewedTissueSupport.Control next = controls.get(7);
        final int gap = next.vertexIndex() - selected.vertexIndex();
        assertTrue(gap > 1, "the test contour must have an interpolated arc");
        final int arcVertex = selected.vertexIndex() + gap / 2;
        final ReviewedTissueSupport.Control previous = controls.get(5);
        final ReviewedTissueSupport moved = original.moveControl(
                selected.id(), new Point2D(
                        selected.point().x() + 2,
                        selected.point().y() + 1));

        assertNotEquals(original.polygon(0).get(arcVertex),
                moved.polygon(0).get(arcVertex));
        assertEquals(original.polygon(0).get(previous.vertexIndex()),
                moved.polygon(0).get(previous.vertexIndex()));
        assertEquals(original.polygon(0).get(next.vertexIndex()),
                moved.polygon(0).get(next.vertexIndex()));
        final int outsideArc = (next.vertexIndex() + 1)
                % original.polygon(0).size();
        assertEquals(original.polygon(0).get(outsideArc),
                moved.polygon(0).get(outsideArc));
        assertEquals(original.polygon(0).get(selected.vertexIndex()).x() + 2,
                moved.polygon(0).get(selected.vertexIndex()).x(),
                1.0e-9);
    }

    @Test
    void cachedMovedRasterMatchesReferencePathRaster() {
        final ReviewedTissueSupport original = ReviewedTissueSupport.fromMask(
                rectangleMask(32, 24, 4, 3, 20, 15));
        final ReviewedTissueSupport.Control selected = original.controls().get(3);
        final ReviewedTissueSupport moved = original.moveControl(
                selected.id(), new Point2D(
                        selected.point().x() + 2,
                        selected.point().y() + 1));
        final Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        final List<Point2D> polygon = moved.polygon(0);
        path.moveTo(polygon.get(0).x(), polygon.get(0).y());
        for (int index = 1; index < polygon.size(); index++) {
            path.lineTo(polygon.get(index).x(), polygon.get(index).y());
        }
        path.closePath();
        final boolean[] expected = new boolean[32 * 24];
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 32; x++) {
                expected[y * 32 + x] = path.contains(x, y);
            }
        }
        assertEquals(BinaryMask.fromBooleans(32, 24, expected),
                moved.supportMask());
    }

    @Test
    void fractionalScanlineIntervalsMatchReferencePathContainment() {
        final boolean[] values = new boolean[32 * 24];
        fill(values, 32, 3, 3, 12, 14);
        fill(values, 32, 20, 7, 8, 11);
        final ReviewedTissueSupport original =
                ReviewedTissueSupport.fromMask(
                        BinaryMask.fromBooleans(32, 24, values));
        final ReviewedTissueSupport.Control first = original.controls().stream()
                .filter(control -> control.componentIndex() == 0)
                .skip(3).findFirst().orElseThrow();
        final ReviewedTissueSupport firstMoved = original.moveControl(
                first.id(), new Point2D(
                        first.point().x() + 2.375,
                        first.point().y() + 1.625));
        final ReviewedTissueSupport.Control second = firstMoved.controls()
                .stream().filter(control -> control.componentIndex() == 1)
                .skip(2).findFirst().orElseThrow();
        final ReviewedTissueSupport moved = firstMoved.moveControl(
                second.id(), new Point2D(
                        second.point().x() - 1.125,
                        second.point().y() + 0.875));
        final Path2D.Double path = new Path2D.Double(Path2D.WIND_EVEN_ODD);
        for (final List<Point2D> polygon : moved.polygons()) {
            path.moveTo(polygon.get(0).x(), polygon.get(0).y());
            for (int index = 1; index < polygon.size(); index++) {
                path.lineTo(polygon.get(index).x(), polygon.get(index).y());
            }
            path.closePath();
        }

        for (double y = 0.125; y < 23.0; y += 0.375) {
            final List<ReviewedTissueSupport.HorizontalInterval> intervals =
                    moved.horizontalIntervals(y);
            for (double x = 0.125; x < 31.0; x += 0.25) {
                final double sampleX = x;
                final boolean intervalContains = intervals.stream().anyMatch(
                        interval -> sampleX >= interval.minimumXInclusive()
                                && sampleX
                                < interval.maximumXExclusive());
                assertEquals(path.contains(x, y), intervalContains,
                        "fractional support membership at " + x + "," + y);
            }
        }
    }

    @Test
    void integerScanlineIntervalsMatchCachedMultipolygonRaster() {
        final boolean[] values = new boolean[32 * 24];
        fill(values, 32, 3, 3, 12, 14);
        fill(values, 32, 20, 7, 8, 11);
        final ReviewedTissueSupport original =
                ReviewedTissueSupport.fromMask(
                        BinaryMask.fromBooleans(32, 24, values));
        final ReviewedTissueSupport.Control movedControl = original.controls()
                .stream().filter(control -> control.componentIndex() == 1)
                .skip(2).findFirst().orElseThrow();
        final ReviewedTissueSupport moved = original.moveControl(
                movedControl.id(), new Point2D(
                        movedControl.point().x() - 1.375,
                        movedControl.point().y() + 0.625));

        for (int y = 0; y < 24; y++) {
            final List<ReviewedTissueSupport.HorizontalInterval> intervals =
                    moved.horizontalIntervals(y);
            for (int x = 0; x < 32; x++) {
                final int sampleX = x;
                final boolean intervalContains = intervals.stream().anyMatch(
                        interval -> sampleX >= interval.minimumXInclusive()
                                && sampleX
                                < interval.maximumXExclusive());
                assertEquals(moved.contains(new Point2D(x, y)),
                        intervalContains,
                        "integer support membership at " + x + "," + y);
            }
        }
    }

    @Test
    void deletingMovedCropNodeRemovesItsInterpolatedField() {
        final ReviewedTissueSupport original = ReviewedTissueSupport.fromMask(
                rectangleMask(32, 24, 4, 3, 20, 15));
        final ReviewedTissueSupport.Control selected = original.controls().get(3);
        final ReviewedTissueSupport moved = original.moveControl(
                selected.id(), new Point2D(
                        selected.point().x() + 2,
                        selected.point().y() + 1));
        final ReviewedTissueSupport deleted = moved.deleteControl(selected.id());
        final List<Point2D> expected = new ArrayList<>(original.polygon(0));
        expected.remove(selected.vertexIndex());

        assertEquals(expected.size(), deleted.polygon(0).size());
        for (int index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).x(),
                    deleted.polygon(0).get(index).x(), 1.0e-9);
            assertEquals(expected.get(index).y(),
                    deleted.polygon(0).get(index).y(), 1.0e-9);
        }
        assertTrue(deleted.controls().stream()
                .noneMatch(control -> control.id().equals(selected.id())));
    }

    @Test
    void invalidCropGeometryFailsClosedBeforeItCanEnterHistory() {
        final BinaryMask source = rectangleMask(32, 24, 4, 3, 20, 15);
        final List<Point2D> bowTie = List.of(
                new Point2D(4, 3), new Point2D(20, 14),
                new Point2D(5, 15), new Point2D(19, 4));
        final List<ReviewedTissueSupport.Control> bowTieControls = controlsFor(
                bowTie, "bow");
        final IllegalArgumentException crossing = assertThrows(
                IllegalArgumentException.class,
                () -> new ReviewedTissueSupport(
                        source, List.of(bowTie), bowTieControls));
        assertTrue(crossing.getMessage().contains("self-intersects"));

        final List<Point2D> collinear = List.of(
                new Point2D(4, 3), new Point2D(10, 3),
                new Point2D(16, 3), new Point2D(22, 3));
        final IllegalArgumentException degenerate = assertThrows(
                IllegalArgumentException.class,
                () -> new ReviewedTissueSupport(
                        source, List.of(collinear), controlsFor(
                                collinear, "line")));
        assertTrue(degenerate.getMessage().contains("degenerate"));

        final List<Point2D> subpixel = List.of(
                new Point2D(10.1, 10.1), new Point2D(10.2, 10.1),
                new Point2D(10.2, 10.2), new Point2D(10.1, 10.2));
        final IllegalArgumentException emptyRaster = assertThrows(
                IllegalArgumentException.class,
                () -> new ReviewedTissueSupport(
                        source, List.of(subpixel), controlsFor(
                                subpixel, "tiny")));
        assertTrue(emptyRaster.getMessage().contains(
                "contains no preview pixels"));
    }

    @Test
    void cropReviewEditsAreAtomicAndAcceptedSnapshotCarriesSupport() {
        final AlignmentReviewBasis basis =
                ReviewTestFixtures.basis(SectionGeometry.FULL);
        final ReviewedTissueSupport support = ReviewedTissueSupport.fromMask(
                rectangleMask(100, 80, 10, 10, 70, 50));
        final AlignmentReviewSession session = new AlignmentReviewSession(basis);

        session.apply(new ReviewEdit.ReplaceReviewedTissueSupport(
                support, true, "Install copied-image tissue support"));
        final String controlId = support.controls().get(0).id();
        session.apply(new ReviewEdit.MoveReviewedTissueSupportControl(
                controlId, new Point2D(11, 10)));
        assertTrue(session.state().content().tissueClippingEnabled());
        assertTrue(session.undo());
        assertEquals(support, session.state().content().reviewedTissueSupport()
                .orElseThrow());
        assertTrue(session.redo());

        final AcceptedAlignmentSnapshot accepted = session.accept(
                ReviewTestFixtures.verifier(basis), true);
        assertEquals(basis.previewMapping(), accepted.previewMapping());
        assertEquals(session.state().content().reviewedTissueSupport(),
                accepted.reviewedTissueSupport());
        assertTrue(accepted.tissueClippingEnabled());
        assertEquals("MANUAL_REVIEW",
                accepted.outputMethodLabel());
    }

    private static BinaryMask rectangleMask(
            final int width,
            final int height,
            final int x,
            final int y,
            final int rectangleWidth,
            final int rectangleHeight) {
        final boolean[] values = new boolean[width * height];
        fill(values, width, x, y, rectangleWidth, rectangleHeight);
        return BinaryMask.fromBooleans(width, height, values);
    }

    private static List<ReviewedTissueSupport.Control> controlsFor(
            final List<Point2D> polygon,
            final String prefix) {
        final List<ReviewedTissueSupport.Control> controls = new ArrayList<>();
        for (int index = 0; index < polygon.size(); index++) {
            final Point2D point = polygon.get(index);
            controls.add(new ReviewedTissueSupport.Control(
                    prefix + index, 0, index, point, point));
        }
        return controls;
    }

    private static void fill(
            final boolean[] values,
            final int width,
            final int x,
            final int y,
            final int rectangleWidth,
            final int rectangleHeight) {
        final int height = values.length / width;
        for (int row = y; row < y + rectangleHeight; row++) {
            for (int column = x; column < x + rectangleWidth; column++) {
                if (row >= 0 && row < height && column >= 0
                        && column < width) {
                    values[row * width + column] = true;
                }
            }
        }
    }

    private static void clear(
            final boolean[] values,
            final int width,
            final int x,
            final int y,
            final int rectangleWidth,
            final int rectangleHeight) {
        final int height = values.length / width;
        for (int row = y; row < y + rectangleHeight; row++) {
            for (int column = x; column < x + rectangleWidth; column++) {
                if (row >= 0 && row < height && column >= 0
                        && column < width) {
                    values[row * width + column] = false;
                }
            }
        }
    }
}
