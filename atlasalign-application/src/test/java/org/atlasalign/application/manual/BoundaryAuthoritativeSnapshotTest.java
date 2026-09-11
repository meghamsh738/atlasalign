package org.atlasalign.application.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D.MeshTriangle;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D.Snapshot;
import org.atlasalign.application.manual.BoundaryAuthoritativeTransform2D.Triangle2D;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class BoundaryAuthoritativeSnapshotTest {

    @Test
    void exactRefinedTrianglesBoundaryCorrespondenceAndDiagnosticsRoundTrip() {
        final var original = transform();
        final Snapshot saved = original.snapshot();
        final var restored = BoundaryAuthoritativeTransform2D.restore(saved);
        assertEquals(saved, original.snapshot());
        assertEquals(saved, restored.snapshot());
        assertEquals(original.contentSha256(), restored.contentSha256());
        assertEquals(original.triangles(), restored.triangles());
        assertEquals(original.diagnostics(), restored.diagnostics());
        assertEquals(original.atlasInputBoundary().vertices(), restored.atlasInputBoundary().vertices());
        assertEquals(original.tissueInputBoundary().vertices(), restored.tissueInputBoundary().vertices());
        assertEquals(original.commonBoundaryParameters(), restored.commonBoundaryParameters());
        assertEquals(original.hemisphereMidlinePath(), restored.hemisphereMidlinePath());
        assertEquals(original.tissueHemisphereBoundary(true), restored.tissueHemisphereBoundary(true));
        assertEquals(original.tissueHemisphereBoundary(false), restored.tissueHemisphereBoundary(false));
        for (final MeshTriangle triangle : original.triangles()) {
            for (final Point2D source : List.of(triangle.source().a(), triangle.source().b(),
                    triangle.source().c(), triangle.source().centroid())) {
                final Point2D mapped = original.apply(source);
                assertEquals(mapped, restored.apply(source));
                assertEquals(original.inverse(mapped), restored.inverse(mapped));
            }
        }
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 22; x++) {
                final Point2D point = new Point2D(x * 10.25 + 0.125, y * 10.25 + 0.375);
                assertEquals(original.containsAtlasPoint(point), restored.containsAtlasPoint(point));
                assertEquals(original.containsTissuePoint(point), restored.containsTissuePoint(point));
                if (original.containsAtlasPoint(point)) {
                    assertEquals(original.apply(point), restored.apply(point));
                }
                if (original.containsTissuePoint(point)) {
                    assertEquals(original.inverse(point), restored.inverse(point));
                }
            }
        }
        final var path = List.of(new Point2D(45, 45), new Point2D(150, 118));
        assertEquals(original.mapPath(path, false), restored.mapPath(path, false));
    }

    @Test
    void clockwiseInputsAndUnequalBoundarySamplesKeepTheirRecordedOrder() {
        final var atlas = MonotoneBoundary2D.indexed("atlas-", List.of(
                new Point2D(20, 20), new Point2D(20, 140), new Point2D(180, 140), new Point2D(180, 20)));
        final var tissue = new MonotoneBoundary2D(List.of(
                new MonotoneBoundary2D.Vertex("t0", new Point2D(30, 25), 0, true),
                new MonotoneBoundary2D.Vertex("t1", new Point2D(25, 150), 0.25, false),
                new MonotoneBoundary2D.Vertex("t2", new Point2D(190, 150), 0.50, true),
                new MonotoneBoundary2D.Vertex("t3", new Point2D(205, 85), 0.625, false),
                new MonotoneBoundary2D.Vertex("t4", new Point2D(190, 25), 0.75, false)));
        final var original = BoundaryAuthoritativeTransform2D.fitFull(atlas, tissue, 256, 192);
        final var restored = BoundaryAuthoritativeTransform2D.restore(original.snapshot());
        assertEquals(original.snapshot(), restored.snapshot());
        for (final MeshTriangle triangle : original.triangles()) {
            assertEquals(original.apply(triangle.source().centroid()), restored.apply(triangle.source().centroid()));
        }
    }

    @Test
    void snapshotListsAreImmutableAndMalformedVersionCountsOrIndicesFailFast() {
        final Snapshot saved = transform().snapshot();
        final var triangles = new ArrayList<>(saved.triangles());
        final Snapshot copied = replace(saved, "triangles", triangles);
        triangles.clear();
        assertFalse(copied.triangles().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> copied.triangles().clear());
        assertThrows(UnsupportedOperationException.class, () -> copied.atlasInputVertices().clear());
        assertThrows(IllegalArgumentException.class, () -> replace(saved, "formatVersion", 2));
        assertThrows(IllegalArgumentException.class, () -> replace(saved, "triangles", List.of()));
        assertThrows(NullPointerException.class, () -> replace(saved, "triangles", null));
        assertThrows(IllegalArgumentException.class, () -> replace(saved, "triangles",
                java.util.Collections.nCopies(BoundaryAuthoritativeTransform2D.MAXIMUM_SNAPSHOT_TRIANGLES + 1,
                        saved.triangles().get(0))));
        assertThrows(IllegalArgumentException.class, () -> replace(saved, "diagnostics",
                replace(saved.diagnostics(), "algorithmRevision", "unsupported-solver")));
        final var changed = new ArrayList<>(saved.triangles());
        changed.set(0, replace(changed.get(0), "sourceCanonicalTriangleIndex",
                saved.diagnostics().atlasCanonicalTriangleCount()));
        assertThrows(IllegalArgumentException.class, () -> replace(saved, "triangles", changed));
        final var parameters = new ArrayList<>(saved.commonBoundaryParameters());
        parameters.set(0, Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> replace(saved, "commonBoundaryParameters", parameters));
    }

    @Test
    void modifiedGeometryAndDiagnosticsFailWithoutChangingTheOriginal() {
        final var original = transform();
        final Snapshot saved = original.snapshot();
        final var triangles = new ArrayList<>(saved.triangles());
        final MeshTriangle first = triangles.get(0);
        final Triangle2D source = first.source();
        triangles.set(0, replace(first, "source", new Triangle2D(source.a(), source.c(), source.b())));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BoundaryAuthoritativeTransform2D.restore(replace(saved, "triangles", triangles)))
                .getMessage().contains("positive area"));
        final Triangle2D target = first.target();
        triangles.set(0, replace(first, "target", new Triangle2D(target.a(), target.b(),
                new Point2D(target.c().x() + 0.001, target.c().y() + 0.001))));
        assertThrows(IllegalArgumentException.class,
                () -> BoundaryAuthoritativeTransform2D.restore(replace(saved, "triangles", triangles)));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> BoundaryAuthoritativeTransform2D.restore(replace(saved, "diagnostics",
                        replace(saved.diagnostics(), "maximumBoundaryErrorPixels", 0.123))))
                .getMessage().contains("diagnostics"));
        assertEquals(saved, original.snapshot());
        assertEquals(saved, BoundaryAuthoritativeTransform2D.restore(saved).snapshot());
    }

    private static BoundaryAuthoritativeTransform2D transform() {
        final var atlas = MonotoneBoundary2D.indexed("atlas-", List.of(
                new Point2D(20, 20), new Point2D(180, 20), new Point2D(150, 62), new Point2D(190, 105),
                new Point2D(180, 145), new Point2D(20, 145), new Point2D(48, 105), new Point2D(10, 62)));
        final var tissue = MonotoneBoundary2D.indexed("tissue-", List.of(
                new Point2D(18, 20), new Point2D(190, 28), new Point2D(173, 72), new Point2D(210, 122),
                new Point2D(181, 154), new Point2D(22, 145), new Point2D(48, 93), new Point2D(12, 63)));
        return BoundaryAuthoritativeTransform2D.fitFull(atlas, tissue, 256, 192);
    }

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
}
