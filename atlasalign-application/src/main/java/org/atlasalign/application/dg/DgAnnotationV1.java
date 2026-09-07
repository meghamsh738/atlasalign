package org.atlasalign.application.dg;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.atlasalign.core.Point2D;

/**
 * Immutable source-pixel DG package captured without plane output.
 *
 * <p>The annotation is manual anatomical evidence. It is not independent
 * validation truth and can never by itself create automatic provenance.</p>
 */
public final class DgAnnotationV1 {

    public static final String SCHEMA = "atlasalign-phase5-dg-annotation-v1";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TOKEN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final String providerId;
    private final String sourceSha256;
    private final int sourceWidth;
    private final int sourceHeight;
    private final String sourceMappingId;
    private final AnatomicalSide anatomicalSide;
    private final DgVisibilityState visibilityState;
    private final List<Point2D> granuleCellLayerCentreline;
    private final Optional<Point2D> crest;
    private final Optional<Point2D> suprapyramidalBladeEndpoint;
    private final Optional<Point2D> infrapyramidalBladeEndpoint;
    private final Map<DgInputAnchor, Point2D> inputAnchors;
    private final Instant capturedAt;
    private final Instant lockedAt;
    private final Optional<String> revisionParentSha256;
    private final String annotationSha256;

    public DgAnnotationV1(
            final String providerId,
            final String sourceSha256,
            final int sourceWidth,
            final int sourceHeight,
            final String sourceMappingId,
            final AnatomicalSide anatomicalSide,
            final DgVisibilityState visibilityState,
            final List<Point2D> granuleCellLayerCentreline,
            final Optional<Point2D> crest,
            final Optional<Point2D> suprapyramidalBladeEndpoint,
            final Optional<Point2D> infrapyramidalBladeEndpoint,
            final Map<DgInputAnchor, Point2D> inputAnchors,
            final Instant capturedAt,
            final Instant lockedAt,
            final Optional<String> revisionParentSha256) {
        this.providerId = requireToken(providerId, "providerId");
        this.sourceSha256 = requireSha256(sourceSha256, "sourceSha256");
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException(
                    "DG source dimensions must be positive");
        }
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.sourceMappingId = requireToken(
                sourceMappingId, "sourceMappingId");
        this.anatomicalSide = Objects.requireNonNull(
                anatomicalSide, "anatomicalSide");
        this.visibilityState = Objects.requireNonNull(
                visibilityState, "visibilityState");
        this.granuleCellLayerCentreline = List.copyOf(
                Objects.requireNonNull(
                        granuleCellLayerCentreline,
                        "granuleCellLayerCentreline"));
        this.crest = checkedPoint(
                Objects.requireNonNull(crest, "crest"), "crest");
        this.suprapyramidalBladeEndpoint = checkedPoint(
                Objects.requireNonNull(
                        suprapyramidalBladeEndpoint,
                        "suprapyramidalBladeEndpoint"),
                "suprapyramidalBladeEndpoint");
        this.infrapyramidalBladeEndpoint = checkedPoint(
                Objects.requireNonNull(
                        infrapyramidalBladeEndpoint,
                        "infrapyramidalBladeEndpoint"),
                "infrapyramidalBladeEndpoint");
        final EnumMap<DgInputAnchor, Point2D> anchors =
                new EnumMap<>(DgInputAnchor.class);
        Objects.requireNonNull(inputAnchors, "inputAnchors")
                .forEach((name, point) -> anchors.put(
                        Objects.requireNonNull(name, "anchor name"),
                        requireInside(
                                Objects.requireNonNull(point, "anchor point"),
                                "anchor " + name)));
        this.inputAnchors = Map.copyOf(anchors);
        this.capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        this.lockedAt = Objects.requireNonNull(lockedAt, "lockedAt");
        this.revisionParentSha256 = Objects.requireNonNull(
                revisionParentSha256, "revisionParentSha256")
                .map(value -> requireSha256(value, "revisionParentSha256"));
        if (lockedAt.isBefore(capturedAt)) {
            throw new IllegalArgumentException(
                    "DG annotation cannot be locked before capture");
        }
        this.granuleCellLayerCentreline.forEach(
                point -> requireInside(point, "DG centreline point"));
        if (isAssessable()) {
            if (this.granuleCellLayerCentreline.size() < 2
                    || this.crest.isEmpty()
                    || this.suprapyramidalBladeEndpoint.isEmpty()
                    || this.infrapyramidalBladeEndpoint.isEmpty()) {
                throw new IllegalArgumentException(
                        "Assessable DG evidence requires a centreline, crest, and both blade endpoints");
            }
        } else if (!this.granuleCellLayerCentreline.isEmpty()
                || this.crest.isPresent()
                || this.suprapyramidalBladeEndpoint.isPresent()
                || this.infrapyramidalBladeEndpoint.isPresent()) {
            throw new IllegalArgumentException(
                    "Uncertain or invisible DG evidence cannot carry a rankable contour");
        }
        this.annotationSha256 = sha256(canonicalIdentityText());
    }

    public String providerId() {
        return providerId;
    }

    public String sourceSha256() {
        return sourceSha256;
    }

    public int sourceWidth() {
        return sourceWidth;
    }

    public int sourceHeight() {
        return sourceHeight;
    }

    public String sourceMappingId() {
        return sourceMappingId;
    }

    public AnatomicalSide anatomicalSide() {
        return anatomicalSide;
    }

    public DgVisibilityState visibilityState() {
        return visibilityState;
    }

    public List<Point2D> granuleCellLayerCentreline() {
        return granuleCellLayerCentreline;
    }

    public Optional<Point2D> crest() {
        return crest;
    }

    public Optional<Point2D> suprapyramidalBladeEndpoint() {
        return suprapyramidalBladeEndpoint;
    }

    public Optional<Point2D> infrapyramidalBladeEndpoint() {
        return infrapyramidalBladeEndpoint;
    }

    public Map<DgInputAnchor, Point2D> inputAnchors() {
        return inputAnchors;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public Instant lockedAt() {
        return lockedAt;
    }

    public Optional<String> revisionParentSha256() {
        return revisionParentSha256;
    }

    public boolean capturedWithoutPlaneOutput() {
        return true;
    }

    public boolean isAssessable() {
        return visibilityState == DgVisibilityState.VISIBLE_INTACT
                || visibilityState == DgVisibilityState.VISIBLE_DAMAGED;
    }

    public String annotationSha256() {
        return annotationSha256;
    }

    public String canonicalIdentityText() {
        final ArrayList<String> lines = new ArrayList<>();
        lines.add("schema=" + SCHEMA);
        lines.add("provider_id=" + providerId);
        lines.add("source_sha256=" + sourceSha256);
        lines.add("source_width=" + sourceWidth);
        lines.add("source_height=" + sourceHeight);
        lines.add("source_mapping_id=" + sourceMappingId);
        lines.add("anatomical_side=" + anatomicalSide.name());
        lines.add("visibility_state=" + visibilityState.name());
        lines.add("captured_without_plane_output=true");
        appendPoints(lines, "gcl", granuleCellLayerCentreline);
        appendOptional(lines, "crest", crest);
        appendOptional(lines, "suprapyramidal", suprapyramidalBladeEndpoint);
        appendOptional(lines, "infrapyramidal", infrapyramidalBladeEndpoint);
        inputAnchors.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(
                        Comparator.comparing(Enum::name)))
                .forEach(entry -> lines.add("anchor."
                        + entry.getKey().name() + "="
                        + pointText(entry.getValue())));
        lines.add("captured_at=" + capturedAt);
        lines.add("locked_at=" + lockedAt);
        lines.add("revision_parent_sha256="
                + revisionParentSha256.orElse("NONE"));
        return String.join("\n", lines) + "\n";
    }

    private Optional<Point2D> checkedPoint(
            final Optional<Point2D> point,
            final String label) {
        return point.map(value -> requireInside(value, label));
    }

    private Point2D requireInside(
            final Point2D point,
            final String label) {
        if (point.x() < 0 || point.x() > sourceWidth - 1
                || point.y() < 0 || point.y() > sourceHeight - 1) {
            throw new IllegalArgumentException(
                    label + " lies outside source-pixel centres");
        }
        return point;
    }

    private static void appendPoints(
            final List<String> lines,
            final String prefix,
            final List<Point2D> points) {
        for (int index = 0; index < points.size(); index++) {
            lines.add(prefix + "." + index + "=" + pointText(points.get(index)));
        }
    }

    private static void appendOptional(
            final List<String> lines,
            final String prefix,
            final Optional<Point2D> point) {
        lines.add(prefix + "=" + point.map(DgAnnotationV1::pointText)
                .orElse("NONE"));
    }

    private static String pointText(final Point2D point) {
        return String.format(Locale.ROOT, "%016x,%016x",
                Double.doubleToRawLongBits(point.x()),
                Double.doubleToRawLongBits(point.y()));
    }

    private static String requireToken(
            final String value,
            final String label) {
        final String checked = Objects.requireNonNull(value, label).trim();
        if (!TOKEN.matcher(checked).matches()) {
            throw new IllegalArgumentException(
                    label + " must be a stable single-line token");
        }
        return checked;
    }

    private static String requireSha256(
            final String value,
            final String label) {
        Objects.requireNonNull(value, label);
        if (!SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    label + " must be a lowercase SHA-256");
        }
        return value;
    }

    private static String sha256(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
