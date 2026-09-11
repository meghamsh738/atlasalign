package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.application.ReviewSectionMode;
import org.atlasalign.application.manual.ManualHemisphereWarp2D.AtlasSide;
import org.atlasalign.application.manual.ManualHemisphereWarp2D.SideSnapshot;
import org.atlasalign.application.manual.ManualHemisphereWarp2D.Snapshot;
import org.atlasalign.application.manual.ManualHemisphereWarp2D.TriangleIndices;
import org.atlasalign.application.manual.ManualHemisphereWarp2D.VertexSnapshot;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class ManualHemisphereWarpSnapshotTest {

    private static final int WIDTH = 400;
    private static final int HEIGHT = 300;
    private static final AtlasOrientation ORIENTATION =
            AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT;

    @Test
    void exactSolvedPatchesRoundTripFullHalfAndDisjoinedWithoutRebuildingTopology() {
        for (final ReviewSectionMode mode : ReviewSectionMode.values()) {
            final List<ManualWarpControl> controls = new ArrayList<>(controls(AtlasSide.LEFT, 0, 2));
            if (mode != ReviewSectionMode.HALF) {
                // Disjoined fields deliberately overlap, exercising side-specific lookup.
                controls.addAll(controls(AtlasSide.RIGHT, mode == ReviewSectionMode.DISJOINED ? 0 : 190, -2));
            }
            final var original = ManualHemisphereWarp2D.fit(controls, ORIENTATION, mode, WIDTH, HEIGHT);
            assertExactRoundTrip(original);
        }
    }

    @Test
    void absentOffscreenHalfMeshStaysAbsentAndIdentity() {
        final var midline = new ManualHemisphereWarp2D.MidlineSegment(
                new Point2D(500, 0), new Point2D(500, HEIGHT - 1));
        final var original = ManualHemisphereWarp2D.fit(controls(AtlasSide.LEFT, 0, 2),
                ORIENTATION, ReviewSectionMode.HALF, midline, WIDTH, HEIGHT);
        assertEquals(1, original.snapshot().sides().size());
        final var restored = assertExactRoundTrip(original);
        assertFalse(restored.hasControls(AtlasSide.RIGHT));
        final Point2D point = new Point2D(350.5, 150.25);
        assertEquals(point, restored.apply(AtlasSide.RIGHT, point));
        assertEquals(point, restored.inverse(AtlasSide.RIGHT, point));
    }

    @Test
    void boundaryAuthoritativeFullRetainsItsSolvedOutlineAndHarmonicField() {
        final var atlasPoints = List.of(new Point2D(200, 50), new Point2D(300, 100),
                new Point2D(300, 200), new Point2D(200, 250),
                new Point2D(100, 200), new Point2D(100, 100));
        final var tissuePoints = atlasPoints.stream().map(point -> new Point2D(
                0.98 * point.x() + 0.04 * point.y() + 5,
                0.01 * point.x() + 0.96 * point.y() + 3)).toList();
        final var outline = BoundaryAuthoritativeTransform2D.fitFull(
                MonotoneBoundary2D.indexed("atlas-", atlasPoints),
                MonotoneBoundary2D.indexed("tissue-", tissuePoints), WIDTH, HEIGHT);
        final var controls = new ArrayList<ManualWarpControl>();
        for (final Point2D atlasPoint : List.of(new Point2D(230, 120), new Point2D(270, 120),
                new Point2D(230, 180), new Point2D(270, 180))) {
            final Point2D point = outline.apply(atlasPoint);
            controls.add(new ManualWarpControl("exact-" + controls.size(), AtlasSide.RIGHT,
                    ManualWarpControlOrigin.USER_PLACED_INTERIOR, "exact", point,
                    new Point2D(point.x() + 1.5, point.y() - 0.7)));
        }
        final var original = ManualHemisphereWarp2D.fit(controls, ORIENTATION, outline);
        assertNotNull(original.snapshot().outline());
        final var restored = assertExactRoundTrip(original);
        assertEquals(outline.snapshot(), restored.snapshot().outline());
        assertEquals(outline.hemisphereMidlinePath(), restored.imageMidlinePath());
    }

    @Test
    void refinementOfInstalledBorderFieldPersistsItsActualSubdividedMesh() {
        final var boundaryControls = List.of(
                boundaryControl("b1", 60, 60, -2, -1), boundaryControl("b2", 150, 60, 1, -1),
                boundaryControl("b3", 60, 230, -2, 1), boundaryControl("b4", 150, 230, 1, 1));
        final var baseline = ManualHemisphereWarp2D.fit(boundaryControls, ORIENTATION,
                ReviewSectionMode.HALF, WIDTH, HEIGHT);
        final var combined = new ArrayList<>(boundaryControls);
        for (final Point2D point : List.of(new Point2D(80, 90), new Point2D(125, 90),
                new Point2D(80, 190), new Point2D(125, 190))) {
            combined.add(new ManualWarpControl("refine-" + combined.size(), AtlasSide.LEFT,
                    ManualWarpControlOrigin.REGULAR_INTERIOR_GRID, "refine", point,
                    baseline.apply(AtlasSide.LEFT, point)));
        }
        final var original = ManualHemisphereWarp2D.fit(combined, ORIENTATION,
                ReviewSectionMode.HALF, WIDTH, HEIGHT);
        assertNotEquals(baseline.diagnostics().meshTriangleCount(), original.diagnostics().meshTriangleCount());
        assertExactRoundTrip(original);
    }

    @Test
    void snapshotListsAreDefensiveAndMalformedMetadataIsRejected() {
        final Snapshot saved = previewWarp().snapshot();
        final var vertices = new ArrayList<>(saved.sides().get(0).vertices());
        final SideSnapshot copied = replace(saved.sides().get(0), "vertices", vertices);
        vertices.clear();
        assertFalse(copied.vertices().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> copied.vertices().clear());
        assertThrows(UnsupportedOperationException.class, () -> saved.sides().clear());
        assertThrows(IllegalArgumentException.class, () -> replace(saved, "formatVersion", 2));
        assertThrows(IllegalArgumentException.class, () -> replace(saved, "sides", List.of()));
        assertThrows(NullPointerException.class, () -> replace(saved, "sides", null));
        assertThrows(IllegalArgumentException.class, () -> replace(saved, "previewWidth", WIDTH + 1));
        assertThrows(IllegalArgumentException.class, () -> replace(saved, "diagnostics",
                replace(saved.diagnostics(), "algorithmRevision", "unsupported-solver")));
        assertThrows(IllegalArgumentException.class, () -> replace(saved.sides().get(0), "triangles", List.of()));
        assertThrows(IllegalArgumentException.class, () -> replace(saved.sides().get(0), "triangles",
                List.of(new TriangleIndices(0, 1, saved.sides().get(0).vertices().size()))));
        assertThrows(IllegalArgumentException.class, () -> new VertexSnapshot(new Point2D(1, 1), Double.NaN, 0, false));
        assertThrows(IllegalArgumentException.class, () -> replace(saved.sides().get(0), "vertices",
                java.util.Collections.nCopies(ManualHemisphereWarp2D.MAXIMUM_SNAPSHOT_VERTICES + 1,
                        saved.sides().get(0).vertices().get(0))));
    }

    @Test
    void corruptionFailsClosedAndResigningCannotBypassBoundaryOrTopologyChecks() throws Exception {
        final var original = previewWarp();
        final Snapshot saved = original.snapshot();
        final SideSnapshot side = saved.sides().get(0);
        final var vertices = new ArrayList<>(side.vertices());
        final VertexSnapshot boundary = vertices.get(0);
        assertTrue(boundary.fixed());
        vertices.set(0, new VertexSnapshot(boundary.source(), 2, 0, true));
        final var sides = new ArrayList<>(saved.sides());
        sides.set(0, replace(side, "vertices", vertices));
        final Snapshot movedBoundary = replace(saved, "sides", sides);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ManualHemisphereWarp2D.restore(movedBoundary)).getMessage().contains("hash"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ManualHemisphereWarp2D.restore(resign(movedBoundary))).getMessage().contains("fixed"));

        final var triangles = new ArrayList<>(side.triangles());
        final TriangleIndices first = triangles.get(0);
        triangles.set(0, new TriangleIndices(first.a(), first.c(), first.b()));
        sides.set(0, replace(side, "triangles", triangles));
        final Snapshot reversed = replace(saved, "sides", sides);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ManualHemisphereWarp2D.restore(resign(reversed))).getMessage().contains("positive area"));
        assertEquals(saved, original.snapshot());
        assertExactRoundTrip(original);
    }

    private static ManualHemisphereWarp2D assertExactRoundTrip(final ManualHemisphereWarp2D original) {
        final Snapshot saved = original.snapshot();
        final long topologyBuilds = ManualHemisphereWarp2D.topologyBuildCountForTests();
        final var restored = ManualHemisphereWarp2D.restore(saved);
        assertEquals(topologyBuilds, ManualHemisphereWarp2D.topologyBuildCountForTests());
        assertEquals(saved, restored.snapshot());
        assertEquals(saved, original.snapshot());
        assertEquals(original, restored);
        assertEquals(original.hashCode(), restored.hashCode());
        assertEquals(original.diagnostics(), restored.diagnostics());
        assertEquals(original.controls(), restored.controls());
        assertEquals(original.reviewSectionMode(), restored.reviewSectionMode());
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 11; x++) {
                final Point2D point = new Point2D(1.25 + x * 37.3, 0.375 + y * 29.7);
                assertEquals(original.apply(point), restored.apply(point));
                assertEquals(original.inverse(point), restored.inverse(point));
                assertEquals(original.jacobian(point), restored.jacobian(point));
                for (final AtlasSide side : AtlasSide.values()) {
                    final Point2D mapped = original.apply(side, point);
                    assertEquals(mapped, restored.apply(side, point));
                    assertEquals(original.inverse(side, mapped), restored.inverse(side, mapped));
                }
            }
        }
        final List<Point2D> path = List.of(new Point2D(30.3, 50.7), new Point2D(360.8, 250.3));
        assertEquals(original.mapPath(path, false), restored.mapPath(path, false));
        for (final AtlasSide side : AtlasSide.values()) {
            assertEquals(original.mapPath(side, path, false), restored.mapPath(side, path, false));
        }
        return restored;
    }

    private static ManualHemisphereWarp2D previewWarp() {
        return ManualHemisphereWarp2D.fit(controls(AtlasSide.LEFT, 0, 2), ORIENTATION,
                ReviewSectionMode.HALF, WIDTH, HEIGHT);
    }

    private static List<ManualWarpControl> controls(final AtlasSide side, final double offsetX, final double dx) {
        final var result = new ArrayList<ManualWarpControl>();
        for (final Point2D point : List.of(new Point2D(60 + offsetX, 70), new Point2D(130 + offsetX, 70),
                new Point2D(60 + offsetX, 220), new Point2D(130 + offsetX, 220))) {
            result.add(new ManualWarpControl(side + "-" + result.size(), side,
                    ManualWarpControlOrigin.REGULAR_INTERIOR_GRID, "grid", point,
                    new Point2D(point.x() + dx, point.y() + 0.5)));
        }
        return List.copyOf(result);
    }

    private static ManualWarpControl boundaryControl(final String id, final double x, final double y,
            final double dx, final double dy) {
        return new ManualWarpControl(id, AtlasSide.LEFT, ManualWarpControlOrigin.ATLAS_TISSUE_BOUNDARY_PAIR,
                "border", new Point2D(x, y), new Point2D(x + dx, y + dy));
    }

    // Model edits to persisted record fields without mutating the live transform.
    private static <T extends Record> T replace(final T record, final String name, final Object value) {
        try {
            final var components = record.getClass().getRecordComponents();
            final Class<?>[] types = new Class<?>[components.length];
            final Object[] values = new Object[components.length];
            for (int index = 0; index < components.length; index++) {
                types[index] = components[index].getType();
                values[index] = components[index].getName().equals(name)
                        ? value : components[index].getAccessor().invoke(record);
            }
            @SuppressWarnings("unchecked")
            final T result = (T) record.getClass().getDeclaredConstructor(types).newInstance(values);
            return result;
        } catch (final InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new AssertionError(error.getCause());
        } catch (final ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static Snapshot resign(final Snapshot saved) throws Exception {
        final var hash = ManualHemisphereWarp2D.class.getDeclaredMethod("snapshotHash", Snapshot.class);
        hash.setAccessible(true);
        return replace(saved, "snapshotSha256", hash.invoke(null, saved));
    }
}
