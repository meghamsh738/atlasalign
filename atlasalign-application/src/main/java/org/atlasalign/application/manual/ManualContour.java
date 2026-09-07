package org.atlasalign.application.manual;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable reviewer contour in full-resolution SOURCE_PIXEL coordinates. */
public record ManualContour(
        String id,
        ManualContourKind kind,
        ContourTopology topology,
        ContourCaptureStatus captureStatus,
        AnatomicalSide anatomicalSide,
        ContourCompleteness completeness,
        Optional<VerifiedAtlasGuideIdentity> atlasGuide,
        SourceImageIdentity sourceIdentity,
        List<ContourVertex> vertices,
        Set<ContourSegment> excludedGapSegments,
        Optional<AutomaticTissueOutlineProvenance> automaticProposalProvenance) {

    /** Compatibility constructor for reviewer-drawn contours. */
    public ManualContour(
            final String id,
            final ManualContourKind kind,
            final ContourTopology topology,
            final ContourCaptureStatus captureStatus,
            final AnatomicalSide anatomicalSide,
            final ContourCompleteness completeness,
            final Optional<VerifiedAtlasGuideIdentity> atlasGuide,
            final SourceImageIdentity sourceIdentity,
            final List<ContourVertex> vertices,
            final Set<ContourSegment> excludedGapSegments) {
        this(id, kind, topology, captureStatus, anatomicalSide, completeness,
                atlasGuide, sourceIdentity, vertices, excludedGapSegments,
                Optional.empty());
    }

    public ManualContour {
        id = Objects.requireNonNull(id, "id").trim();
        kind = Objects.requireNonNull(kind, "kind");
        topology = Objects.requireNonNull(topology, "topology");
        captureStatus = Objects.requireNonNull(captureStatus, "captureStatus");
        anatomicalSide = Objects.requireNonNull(anatomicalSide, "anatomicalSide");
        completeness = Objects.requireNonNull(completeness, "completeness");
        atlasGuide = Objects.requireNonNull(atlasGuide, "atlasGuide");
        sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        vertices = List.copyOf(Objects.requireNonNull(vertices, "vertices"));
        excludedGapSegments = Set.copyOf(Objects.requireNonNull(
                excludedGapSegments, "excludedGapSegments"));
        automaticProposalProvenance = Objects.requireNonNull(
                automaticProposalProvenance, "automaticProposalProvenance");
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Contour id must not be blank");
        }
        final int minimum = captureStatus == ContourCaptureStatus.DRAFT
                ? 1 : topology == ContourTopology.CLOSED ? 3 : 2;
        if (vertices.size() < minimum) {
            throw new IllegalArgumentException(
                    topology + " contours require at least " + minimum + " vertices");
        }
        if (kind == ManualContourKind.ANATOMICAL_STRUCTURE
                != atlasGuide.isPresent()) {
            throw new IllegalArgumentException(
                    "Only anatomical-structure contours require an exact atlas guide");
        }
        if (kind == ManualContourKind.TISSUE_OUTLINE
                && topology != ContourTopology.CLOSED) {
            throw new IllegalArgumentException("A tissue outline must be closed");
        }
        if ((kind == ManualContourKind.VISIBLE_TISSUE_BOUNDARY
                || kind == ManualContourKind.MIDLINE)
                && topology != ContourTopology.OPEN) {
            throw new IllegalArgumentException(kind + " must be open");
        }
        if (automaticProposalProvenance.isPresent()) {
            final AutomaticTissueOutlineProvenance provenance =
                    automaticProposalProvenance.orElseThrow();
            if (kind != ManualContourKind.TISSUE_OUTLINE) {
                throw new IllegalArgumentException(
                        "Automatic outline provenance is valid only for a tissue outline");
            }
            if (!sourceIdentity.equals(provenance.sourceIdentity())) {
                throw new IllegalArgumentException(
                        "Automatic outline provenance must match the exact source identity");
            }
        }
        validateVertices(sourceIdentity, vertices);
        validateExcludedSegments(topology, vertices, excludedGapSegments);
    }

    /** Only completed contours are eligible for any later preview/search input. */
    public boolean eligibleForPreview() {
        return captureStatus == ContourCaptureStatus.COMPLETE;
    }

    /** Changes only draft/complete state and preserves proposal provenance. */
    public ManualContour withCaptureStatus(
            final ContourCaptureStatus updatedCaptureStatus) {
        return new ManualContour(
                id, kind, topology,
                Objects.requireNonNull(updatedCaptureStatus,
                        "updatedCaptureStatus"),
                anatomicalSide, completeness, atlasGuide, sourceIdentity,
                vertices, excludedGapSegments, automaticProposalProvenance);
    }

    private static void validateVertices(
            final SourceImageIdentity source,
            final List<ContourVertex> vertices) {
        final Set<String> ids = new HashSet<>();
        for (final ContourVertex vertex : vertices) {
            Objects.requireNonNull(vertex, "vertices must not contain null");
            if (!ids.add(vertex.id())) {
                throw new IllegalArgumentException("Vertex IDs must be unique");
            }
            final SourcePixelPoint point = vertex.point();
            // Pixel centres span [0, width - 1] x [0, height - 1].
            if (point.x() < 0.0 || point.x() > source.width() - 1.0
                    || point.y() < 0.0 || point.y() > source.height() - 1.0) {
                throw new IllegalArgumentException(
                        "Contour point lies outside source pixel-centre bounds");
            }
        }
    }

    private static void validateExcludedSegments(
            final ContourTopology topology,
            final List<ContourVertex> vertices,
            final Set<ContourSegment> excluded) {
        final Set<ContourSegment> edges = new HashSet<>();
        for (int i = 0; i + 1 < vertices.size(); i++) {
            edges.add(new ContourSegment(vertices.get(i).id(), vertices.get(i + 1).id()));
        }
        if (topology == ContourTopology.CLOSED && vertices.size() > 1) {
            edges.add(new ContourSegment(
                    vertices.get(vertices.size() - 1).id(), vertices.get(0).id()));
        }
        if (!edges.containsAll(excluded)) {
            throw new IllegalArgumentException(
                    "Excluded gaps must identify directed adjacent contour segments");
        }
    }
}
