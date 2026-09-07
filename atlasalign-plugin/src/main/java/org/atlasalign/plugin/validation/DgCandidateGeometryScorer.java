package org.atlasalign.plugin.validation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import org.atlasalign.application.c01.C01SearchContext;
import org.atlasalign.application.dg.AnatomicalSide;
import org.atlasalign.application.dg.DgCurveDistance;
import org.atlasalign.application.dg.DgCurveResampler;
import org.atlasalign.application.dg.DgInputAnchor;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;

/**
 * Maps candidate atlas geometry into source-pixel centres and computes frozen
 * side-preserving D01 distances on newly allocated point lists.
 */
public final class DgCandidateGeometryScorer {

    public DgCandidateGeometryEvidence score(
            final Map<AnatomicalSide, List<Point2D>> sourceDgBySide,
            final Map<AnatomicalSide, List<Point2D>> atlasDgBySide,
            final Map<DgInputAnchor, Point2D> sourceInputAnchors,
            final Map<DgInputAnchor, Point2D> atlasInputAnchors,
            final C01SearchContext context,
            final PreviewMapping previewMapping) {
        Objects.requireNonNull(sourceDgBySide, "sourceDgBySide");
        Objects.requireNonNull(atlasDgBySide, "atlasDgBySide");
        Objects.requireNonNull(sourceInputAnchors, "sourceInputAnchors");
        Objects.requireNonNull(atlasInputAnchors, "atlasInputAnchors");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(previewMapping, "previewMapping");

        final DgCurveResampler resampler = new DgCurveResampler();
        final DgCurveDistance distance = new DgCurveDistance();
        final EnumMap<AnatomicalSide, OptionalDouble> bySide =
                new EnumMap<>(AnatomicalSide.class);
        for (final AnatomicalSide side : AnatomicalSide.values()) {
            final List<Point2D> source = sourceDgBySide.get(side);
            final List<Point2D> atlas = atlasDgBySide.get(side);
            if (source == null || atlas == null
                    || source.size() < 2 || atlas.size() < 2) {
                bySide.put(side, OptionalDouble.empty());
                continue;
            }
            final List<Point2D> sourceResampled = resampler.resample(source);
            final List<Point2D> atlasInSource = atlas.stream()
                    .map(point -> previewMapping.previewToSource(
                            context.fixedAtlasToFeature().apply(point)))
                    .toList();
            bySide.put(side, OptionalDouble.of(
                    distance.symmetricCentrelineDistance(
                            sourceResampled,
                            resampler.resample(atlasInSource))));
        }
        final OptionalDouble bilateral;
        if (bySide.values().stream().allMatch(OptionalDouble::isPresent)) {
            bilateral = OptionalDouble.of(bySide.values().stream()
                    .mapToDouble(OptionalDouble::getAsDouble)
                    .average().orElseThrow());
        } else {
            bilateral = OptionalDouble.empty();
        }

        double anchorTotal = 0;
        int anchorCount = 0;
        for (final DgInputAnchor anchor : DgInputAnchor.values()) {
            final Point2D source = sourceInputAnchors.get(anchor);
            final Point2D atlas = atlasInputAnchors.get(anchor);
            if (source == null || atlas == null) {
                continue;
            }
            final Point2D atlasInSource = previewMapping.previewToSource(
                    context.fixedAtlasToFeature().apply(atlas));
            anchorTotal += Math.hypot(
                    source.x() - atlasInSource.x(),
                    source.y() - atlasInSource.y());
            anchorCount++;
        }
        final OptionalDouble anchorDistance = anchorCount == 0
                ? OptionalDouble.empty()
                : OptionalDouble.of(anchorTotal / anchorCount);
        return new DgCandidateGeometryEvidence(
                bySide, bilateral, anchorDistance);
    }
}
