package org.atlasalign.plugin.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.atlasalign.application.ManualWarpPrecondition;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualWarpControl;
import org.atlasalign.core.Point2D;

/**
 * Transient, solver-free requested Structure shape. Pair identities and amber
 * starts are immutable for the lifetime of the draft and are never persisted.
 */
public record StructureAdjustmentDraft(
        long contentRevision,
        ManualWarpPrecondition precondition,
        ManualHemisphereWarp2D.AtlasSide atlasSide,
        String structureAcronym,
        String contourHash,
        List<ManualWarpControl> baselineControls,
        List<ManualWarpControl> requestedControls,
        Map<String, Integer> componentByControlId,
        List<StructureOutlinePath> outlinePaths,
        List<StructureAdjustmentUnit> units,
        Map<String, Point2D> manualOffsetsByControlId,
        Point2D bladeGapAxis,
        double initialBladeSeparation,
        int thicknessPercent,
        int bladeGapPercent,
        int pendingEditCount,
        String inputHash) {

    public StructureAdjustmentDraft {
        precondition = Objects.requireNonNull(precondition, "precondition");
        atlasSide = Objects.requireNonNull(atlasSide, "atlasSide");
        structureAcronym = requireText(structureAcronym,
                "structureAcronym");
        contourHash = requireText(contourHash, "contourHash");
        baselineControls = List.copyOf(Objects.requireNonNull(
                baselineControls, "baselineControls"));
        requestedControls = List.copyOf(Objects.requireNonNull(
                requestedControls, "requestedControls"));
        componentByControlId = Map.copyOf(new LinkedHashMap<>(
                Objects.requireNonNull(componentByControlId,
                        "componentByControlId")));
        outlinePaths = List.copyOf(Objects.requireNonNull(
                outlinePaths, "outlinePaths"));
        units = List.copyOf(Objects.requireNonNull(units, "units"));
        manualOffsetsByControlId = Map.copyOf(new LinkedHashMap<>(
                Objects.requireNonNull(manualOffsetsByControlId,
                        "manualOffsetsByControlId")));
        bladeGapAxis = Objects.requireNonNull(bladeGapAxis,
                "bladeGapAxis");
        if (baselineControls.size() < 4 || baselineControls.size()
                > ManualHemisphereWarp2D.MAXIMUM_CONTROLS_PER_GROUP
                || baselineControls.size() != requestedControls.size()) {
            throw new IllegalArgumentException(
                    "A Structure draft requires 4 to 64 controls");
        }
        final List<String> baselineIds = baselineControls.stream()
                .map(ManualWarpControl::id).toList();
        final List<String> requestedIds = requestedControls.stream()
                .map(ManualWarpControl::id).toList();
        if (!baselineIds.equals(requestedIds)
                || !componentByControlId.keySet().containsAll(baselineIds)
                || !manualOffsetsByControlId.keySet().equals(
                        new java.util.LinkedHashSet<>(baselineIds))) {
            throw new IllegalArgumentException(
                    "Structure draft controls, assignments, and offsets must match");
        }
        final List<String> outlinedIds = outlinePaths.stream()
                .flatMap(path -> path.controlIds().stream()).toList();
        if (outlinedIds.size() != baselineIds.size()
                || outlinedIds.stream().distinct().count()
                        != outlinedIds.size()
                || !new java.util.HashSet<>(outlinedIds).equals(
                        new java.util.HashSet<>(baselineIds))) {
            throw new IllegalArgumentException(
                    "Every Structure dot must appear exactly once in its editable outline");
        }
        final List<String> unitControlIds = units.stream()
                .flatMap(unit -> unit.controlIds().stream()).toList();
        if (unitControlIds.size() != baselineIds.size()
                || unitControlIds.stream().distinct().count()
                        != unitControlIds.size()
                || !new java.util.HashSet<>(unitControlIds).equals(
                        new java.util.HashSet<>(baselineIds))) {
            throw new IllegalArgumentException(
                    "Every Structure dot must belong to exactly one stable unit");
        }
        for (final ManualWarpControl control : requestedControls) {
            if (control.atlasSide() != atlasSide
                    || !control.structureAcronym().equalsIgnoreCase(
                            structureAcronym)) {
                throw new IllegalArgumentException(
                        "Structure controls must target one acronym and side");
            }
        }
        if (thicknessPercent < 75 || thicknessPercent > 300
                || bladeGapPercent < -50 || bladeGapPercent > 100
                || pendingEditCount < 0
                || !Double.isFinite(initialBladeSeparation)
                || initialBladeSeparation < 0) {
            throw new IllegalArgumentException(
                    "Structure draft values are outside supported ranges");
        }
        final String calculated = hash(contentRevision, atlasSide,
                structureAcronym, contourHash, baselineControls,
                requestedControls, componentByControlId, outlinePaths, units,
                manualOffsetsByControlId, bladeGapAxis,
                initialBladeSeparation, thicknessPercent, bladeGapPercent,
                pendingEditCount);
        inputHash = inputHash == null || inputHash.isBlank()
                ? calculated : inputHash;
        if (!inputHash.equals(calculated)) {
            throw new IllegalArgumentException(
                    "Structure draft hash does not match its requested geometry");
        }
    }

    public boolean hasTwoPrincipalComponents() {
        return initialBladeSeparation > 1e-9
                && componentByControlId.values().stream()
                        .anyMatch(component -> component == 0)
                && componentByControlId.values().stream()
                        .anyMatch(component -> component == 1);
    }

    public StructureAdjustmentUnit unitForControl(final String controlId) {
        return units.stream().filter(unit -> unit.controlIds().contains(
                controlId)).findFirst().orElseThrow(() ->
                        new IllegalArgumentException(
                                "Unknown Structure control: " + controlId));
    }

    public StructureAdjustmentUnit unit(final String unitId) {
        return units.stream().filter(unit -> unit.id().equals(unitId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Unknown Structure unit: " + unitId));
    }

    private static String hash(
            final long revision,
            final ManualHemisphereWarp2D.AtlasSide side,
            final String acronym,
            final String contour,
            final List<ManualWarpControl> baseline,
            final List<ManualWarpControl> requested,
            final Map<String, Integer> assignments,
            final List<StructureOutlinePath> outlinePaths,
            final List<StructureAdjustmentUnit> units,
            final Map<String, Point2D> offsets,
            final Point2D gapAxis,
            final double gapDistance,
            final int thickness,
            final int gap,
            final int pending) {
        final StringBuilder canonical = new StringBuilder(
                "structure-adjustment-draft-v3\n")
                .append(revision).append('\n').append(side).append('\n')
                .append(acronym).append('\n').append(contour).append('\n')
                .append(thickness).append('\n').append(gap).append('\n')
                .append(pending).append('\n')
                .append(gapAxis.x()).append(',').append(gapAxis.y())
                .append('|').append(gapDistance).append('\n');
        final Map<String, ManualWarpControl> starts = baseline.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ManualWarpControl::id,
                        java.util.function.Function.identity()));
        for (final ManualWarpControl control : requested) {
            final ManualWarpControl start = starts.get(control.id());
            final Point2D offset = offsets.get(control.id());
            canonical.append(control.id()).append('|')
                    .append(start.targetPoint().x()).append(',')
                    .append(start.targetPoint().y()).append('|')
                    .append(control.targetPoint().x()).append(',')
                    .append(control.targetPoint().y()).append('|')
                    .append(offset.x()).append(',').append(offset.y())
                    .append('|').append(assignments.get(control.id()))
                    .append('\n');
        }
        for (final StructureOutlinePath path : outlinePaths) {
            canonical.append("outline|").append(path.id()).append('|')
                    .append(path.closed()).append('|')
                    .append(String.join(",", path.controlIds()))
                    .append('\n');
        }
        for (final StructureAdjustmentUnit unit : units) {
            canonical.append(unit.id()).append('|')
                    .append(String.join(",", unit.controlIds())).append('|')
                    .append(unit.componentIndex()).append('|')
                    .append(unit.componentId()).append('|')
                    .append(unit.midpoint().x()).append(',')
                    .append(unit.midpoint().y()).append('|')
                    .append(unit.separationAxis().x()).append(',')
                    .append(unit.separationAxis().y()).append('|')
                    .append(unit.initialDistance()).append('|')
                    .append(unit.displayLabel()).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(
                    "SHA-256").digest(canonical.toString().getBytes(
                            StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable",
                    unavailable);
        }
    }

    private static String requireText(
            final String value,
            final String name) {
        final String checked = Objects.requireNonNull(value, name).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return checked;
    }
}
