package org.atlasalign.application.dg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;

class DgAnnotationV1Test {

    @Test
    void locksCompleteSourcePixelPackageWithStableManualProvenance() {
        final DgAnnotationV1 annotation = annotation();

        assertTrue(annotation.isAssessable());
        assertTrue(annotation.capturedWithoutPlaneOutput());
        assertEquals(AnatomicalSide.LEFT, annotation.anatomicalSide());
        assertEquals(2, annotation.granuleCellLayerCentreline().size());
        assertEquals(
                "c6923972fbd1ea0e47c293cceeecf8ae93e386ff4daa9fa461389eb5a9ef4a5a",
                annotation.annotationSha256());
        assertEquals(annotation.annotationSha256(), annotation().annotationSha256());
        assertFalse(annotation.canonicalIdentityText().contains("candidate"));
        assertFalse(annotation.canonicalIdentityText().contains("plane="));
    }

    @Test
    void rejectsOutOfBoundsOrIncompleteAssessableEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> create(List.of(new Point2D(-1, 2), new Point2D(4, 5)),
                        Optional.of(new Point2D(5, 5))));
        assertThrows(IllegalArgumentException.class,
                () -> create(List.of(new Point2D(2, 2), new Point2D(4, 5)),
                        Optional.empty()));
    }

    @Test
    void uncertainEvidenceIsExplicitlyUnassessableAndCarriesNoDgContour() {
        final DgAnnotationV1 uncertain = new DgAnnotationV1(
                "provider-b",
                "1".repeat(64),
                100,
                80,
                "SOURCE_PIXEL_CENTRE_V1",
                AnatomicalSide.RIGHT,
                DgVisibilityState.UNCERTAIN,
                List.of(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Instant.parse("2026-08-09T10:00:00Z"),
                Instant.parse("2026-08-09T10:00:04Z"),
                Optional.empty());

        assertFalse(uncertain.isAssessable());
    }

    private static DgAnnotationV1 annotation() {
        return create(
                List.of(new Point2D(10, 20), new Point2D(30, 40)),
                Optional.of(new Point2D(20, 30)));
    }

    private static DgAnnotationV1 create(
            final List<Point2D> centreline,
            final Optional<Point2D> crest) {
        return new DgAnnotationV1(
                "provider-a",
                "1".repeat(64),
                100,
                80,
                "SOURCE_PIXEL_CENTRE_V1",
                AnatomicalSide.LEFT,
                DgVisibilityState.VISIBLE_INTACT,
                centreline,
                crest,
                Optional.of(new Point2D(10, 20)),
                Optional.of(new Point2D(30, 40)),
                Map.of(
                        DgInputAnchor.DORSAL_CORPUS_CALLOSUM_MIDLINE,
                        new Point2D(50, 10)),
                Instant.parse("2026-08-09T10:00:00Z"),
                Instant.parse("2026-08-09T10:00:05Z"),
                Optional.empty());
    }
}
