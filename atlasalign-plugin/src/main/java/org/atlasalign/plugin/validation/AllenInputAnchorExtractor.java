package org.atlasalign.plugin.validation;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.EnumMap;
import org.atlasalign.application.dg.DgInputAnchor;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.atlas.AtlasOntology;
import org.atlasalign.atlas.AtlasRegion;
import org.atlasalign.core.Point2D;

/**
 * Derives the four preregistered non-DG input anchors from verified Allen
 * annotation labels.
 *
 * <p>All returned coordinates are atlas-plane pixel centres. The rules are
 * deliberately simple and deterministic: the dorsal corpus-callosum point is
 * the dorsal-most labelled pixel in the column nearest the midline; lateral-
 * ventricle apices are the dorsal-most labelled pixels on their anatomical
 * sides; and the third-ventricle point is the ventral-most labelled pixel
 * nearest the midline. Missing labels produce unavailable anchors rather than
 * invented coordinates.</p>
 */
public final class AllenInputAnchorExtractor {

    static final String CORPUS_CALLOSUM_ACRONYM = "cc";
    static final String LATERAL_VENTRICLE_ACRONYM = "VL";
    static final String THIRD_VENTRICLE_ACRONYM = "V3";

    public Map<DgInputAnchor, Point2D> extract(
            final AtlasCoronalPlane plane,
            final AtlasOntology ontology) {
        Objects.requireNonNull(ontology, "ontology");
        return extractForRegionIds(
                plane,
                identifiers(ontology, CORPUS_CALLOSUM_ACRONYM),
                identifiers(ontology, LATERAL_VENTRICLE_ACRONYM),
                identifiers(ontology, THIRD_VENTRICLE_ACRONYM));
    }

    Map<DgInputAnchor, Point2D> extractForRegionIds(
            final AtlasCoronalPlane plane,
            final Set<Integer> corpusCallosumIds,
            final Set<Integer> lateralVentricleIds,
            final Set<Integer> thirdVentricleIds) {
        Objects.requireNonNull(plane, "plane");
        requireIdentifiers(corpusCallosumIds, "corpus-callosum");
        requireIdentifiers(lateralVentricleIds, "lateral-ventricle");
        requireIdentifiers(thirdVentricleIds, "third-ventricle");
        final int width = plane.width();
        final int height = plane.height();
        final int[] labels = plane.annotationId();
        final double midline = (width - 1) / 2.0;
        final EnumMap<DgInputAnchor, Point2D> result =
                new EnumMap<>(DgInputAnchor.class);

        select(labels, width, height, corpusCallosumIds, x -> true,
                (candidate, current) -> compareMidlineThenDorsal(
                        candidate, current, midline))
                .ifPresent(point -> result.put(
                        DgInputAnchor.DORSAL_CORPUS_CALLOSUM_MIDLINE, point));
        select(labels, width, height, lateralVentricleIds,
                x -> x < width / 2.0,
                (candidate, current) -> compareDorsalThenMidline(
                        candidate, current, midline))
                .ifPresent(point -> result.put(
                        DgInputAnchor.LEFT_LATERAL_VENTRICLE_APEX, point));
        select(labels, width, height, lateralVentricleIds,
                x -> x >= width / 2.0,
                (candidate, current) -> compareDorsalThenMidline(
                        candidate, current, midline))
                .ifPresent(point -> result.put(
                        DgInputAnchor.RIGHT_LATERAL_VENTRICLE_APEX, point));
        select(labels, width, height, thirdVentricleIds, x -> true,
                (candidate, current) -> compareVentralThenMidline(
                        candidate, current, midline))
                .ifPresent(point -> result.put(
                        DgInputAnchor.VENTRAL_MIDLINE_OR_THIRD_VENTRICLE,
                        point));
        return Map.copyOf(result);
    }

    private static Set<Integer> identifiers(
            final AtlasOntology ontology,
            final String acronym) {
        final AtlasRegion region = ontology.search(acronym, 20).stream()
                .filter(candidate -> candidate.acronym().equals(acronym))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Verified atlas ontology has no exact "
                                + acronym + " region"));
        final Set<Integer> result = new HashSet<>();
        result.add(region.id());
        ontology.descendants(region.id()).forEach(
                descendant -> result.add(descendant.id()));
        return Set.copyOf(result);
    }

    private static void requireIdentifiers(
            final Set<Integer> identifiers,
            final String label) {
        if (Objects.requireNonNull(identifiers, label + " IDs").isEmpty()) {
            throw new IllegalArgumentException(
                    label + " region identifier set must not be empty");
        }
    }

    private static Optional<Point2D> select(
            final int[] labels,
            final int width,
            final int height,
            final Set<Integer> identifiers,
            final XPredicate side,
            final PointComparator comparator) {
        Point2D best = null;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!side.test(x)
                        || !identifiers.contains(labels[y * width + x])) {
                    continue;
                }
                final Point2D candidate = new Point2D(x, y);
                if (best == null || comparator.compare(candidate, best) < 0) {
                    best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static int compareMidlineThenDorsal(
            final Point2D first,
            final Point2D second,
            final double midline) {
        int compared = Double.compare(
                Math.abs(first.x() - midline),
                Math.abs(second.x() - midline));
        if (compared == 0) {
            compared = Double.compare(first.y(), second.y());
        }
        if (compared == 0) {
            compared = Double.compare(first.x(), second.x());
        }
        return compared;
    }

    private static int compareDorsalThenMidline(
            final Point2D first,
            final Point2D second,
            final double midline) {
        int compared = Double.compare(first.y(), second.y());
        if (compared == 0) {
            compared = Double.compare(
                    Math.abs(first.x() - midline),
                    Math.abs(second.x() - midline));
        }
        if (compared == 0) {
            compared = Double.compare(first.x(), second.x());
        }
        return compared;
    }

    private static int compareVentralThenMidline(
            final Point2D first,
            final Point2D second,
            final double midline) {
        int compared = Double.compare(second.y(), first.y());
        if (compared == 0) {
            compared = Double.compare(
                    Math.abs(first.x() - midline),
                    Math.abs(second.x() - midline));
        }
        if (compared == 0) {
            compared = Double.compare(first.x(), second.x());
        }
        return compared;
    }

    @FunctionalInterface
    private interface XPredicate {
        boolean test(int x);
    }

    @FunctionalInterface
    private interface PointComparator {
        int compare(Point2D first, Point2D second);
    }
}
