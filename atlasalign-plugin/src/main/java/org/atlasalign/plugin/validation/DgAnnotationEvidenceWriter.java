package org.atlasalign.plugin.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.atlasalign.application.dg.DgAnnotationV1;
import org.atlasalign.application.dg.DgInputAnchor;
import org.atlasalign.core.Point2D;

/** Create-only serializer for a source-only, pre-output DG annotation. */
public final class DgAnnotationEvidenceWriter {

    private DgAnnotationEvidenceWriter() {
    }

    public static void writeCreateOnly(
            final Path output,
            final DgAnnotationV1 annotation,
            final double entrySeconds,
            final int preResultRevisionCount) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(annotation, "annotation");
        if (!Double.isFinite(entrySeconds) || entrySeconds < 0
                || preResultRevisionCount < 0) {
            throw new IllegalArgumentException(
                    "DG timing and revision evidence is invalid");
        }
        final StringBuilder json = new StringBuilder(2_048);
        json.append('{')
                .append("\"anatomical_side\":\"")
                .append(annotation.anatomicalSide().name()).append("\",")
                .append("\"annotation_sha256\":\"")
                .append(annotation.annotationSha256()).append("\",")
                .append("\"captured_at\":\"")
                .append(annotation.capturedAt()).append("\",")
                .append("\"captured_without_plane_output\":true,")
                .append("\"crest\":");
        appendOptionalPoint(json, annotation.crest());
        json.append(",\"entry_seconds\":")
                .append(String.format(Locale.ROOT, "%.9f", entrySeconds))
                .append(",\"granule_cell_layer_centreline\":[");
        for (int index = 0;
                index < annotation.granuleCellLayerCentreline().size();
                index++) {
            if (index > 0) {
                json.append(',');
            }
            appendPoint(json, annotation.granuleCellLayerCentreline().get(index));
        }
        json.append("],\"infrapyramidal_blade_endpoint\":");
        appendOptionalPoint(json, annotation.infrapyramidalBladeEndpoint());
        json.append(",\"input_anchors\":{");
        int anchorIndex = 0;
        for (final Map.Entry<DgInputAnchor, Point2D> entry
                : annotation.inputAnchors().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(
                                Comparator.comparing(Enum::name)))
                        .toList()) {
            if (anchorIndex++ > 0) {
                json.append(',');
            }
            json.append('\"').append(entry.getKey().name()).append("\":");
            appendPoint(json, entry.getValue());
        }
        json.append("},\"locked_at\":\"")
                .append(annotation.lockedAt()).append("\",")
                .append("\"pre_result_revision_count\":")
                .append(preResultRevisionCount).append(',')
                .append("\"provider_id\":\"")
                .append(annotation.providerId()).append("\",")
                .append("\"revision_parent_sha256\":");
        annotation.revisionParentSha256()
                .ifPresentOrElse(
                        value -> json.append('\"').append(value).append('\"'),
                        () -> json.append("null"));
        json.append(",\"schema\":\"")
                .append(DgAnnotationV1.SCHEMA).append("\",")
                .append("\"source_height\":")
                .append(annotation.sourceHeight()).append(',')
                .append("\"source_mapping_id\":\"")
                .append(annotation.sourceMappingId()).append("\",")
                .append("\"source_sha256\":\"")
                .append(annotation.sourceSha256()).append("\",")
                .append("\"source_width\":")
                .append(annotation.sourceWidth()).append(',')
                .append("\"suprapyramidal_blade_endpoint\":");
        appendOptionalPoint(json, annotation.suprapyramidalBladeEndpoint());
        json.append(",\"visibility_state\":\"")
                .append(annotation.visibilityState().name()).append("\"}\n");
        try {
            final Path absolute = output.toAbsolutePath().normalize();
            Files.createDirectories(absolute.getParent());
            Files.writeString(
                    absolute,
                    json.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(absolute, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ));
            } catch (UnsupportedOperationException ignored) {
                absolute.toFile().setReadOnly();
            }
        } catch (IOException error) {
            throw new IllegalStateException(
                    "Could not create locked DG annotation evidence", error);
        }
    }

    private static void appendOptionalPoint(
            final StringBuilder json,
            final Optional<Point2D> point) {
        point.ifPresentOrElse(
                value -> appendPoint(json, value),
                () -> json.append("null"));
    }

    private static void appendPoint(
            final StringBuilder json,
            final Point2D point) {
        json.append("{\"x\":")
                .append(Double.toString(point.x()))
                .append(",\"y\":")
                .append(Double.toString(point.y()))
                .append('}');
    }
}
