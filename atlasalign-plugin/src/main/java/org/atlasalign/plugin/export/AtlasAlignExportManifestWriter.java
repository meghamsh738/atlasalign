package org.atlasalign.plugin.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.atlasalign.application.AcceptedAlignmentSnapshot;
import org.atlasalign.application.AtlasAssetVerification;
import org.atlasalign.core.AffineTransform2D;
import org.atlasalign.core.CalibrationMetadata;

/** Deterministic, explicit provenance manifest for one atomic export. */
final class AtlasAlignExportManifestWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    record Artifact(
            String kind,
            String fileName,
            long sizeBytes,
            String sha256,
            SourceRegionFootprint footprint) {
        Artifact {
            kind = requireText(kind, "kind");
            fileName = requireText(fileName, "fileName");
            sha256 = requireSha256(sha256);
            if (sizeBytes <= 0) {
                throw new IllegalArgumentException(
                        "Export artifact must be non-empty");
            }
        }
    }

    void write(
            final Path destination,
            final String sourceName,
            final AcceptedAlignmentSnapshot accepted,
            final String pluginVersion,
            final String bioFormatsVersion,
            final String annotationPlaneSha256,
            final List<String> warnings,
            final List<Artifact> artifacts) {
        final AcceptedAlignmentSnapshot snapshot = Objects.requireNonNull(
                accepted, "accepted");
        final ObjectNode root = MAPPER.createObjectNode();
        root.put("schema", "atlasalign-source-space-export-v1");
        root.put("pluginVersion", requireText(pluginVersion,
                "pluginVersion"));
        root.put("bioFormatsVersion", requireText(bioFormatsVersion,
                "bioFormatsVersion"));

        final ObjectNode source = root.putObject("source");
        source.put("name", requireText(sourceName, "sourceName"));
        source.put("pixelSha256", snapshot.verifiedSource().pixelSha256());
        source.put("width", snapshot.verifiedSource().metadata().width());
        source.put("height", snapshot.verifiedSource().metadata().height());
        source.put("channels", snapshot.verifiedSource().metadata().channels());
        source.put("slices", snapshot.verifiedSource().metadata().slices());
        source.put("frames", snapshot.verifiedSource().metadata().frames());
        source.put("bitDepth", snapshot.verifiedSource().metadata().bitDepth());
        final ArrayNode channelLabels = source.putArray("channelLabels");
        snapshot.verifiedSource().metadata().channelLabels()
                .forEach(channelLabels::add);
        final ArrayNode planeLabels = source.putArray("stackPlaneLabels");
        snapshot.verifiedSource().metadata().stackPlaneLabels().forEach(
                label -> {
                    final ObjectNode item = planeLabels.addObject();
                    item.put("present", label.present());
                    item.put("value", label.value());
                });
        appendCalibration(source.putObject("calibration"),
                snapshot.verifiedSource().metadata().calibration());
        final ObjectNode footprintApplication = root.putObject(
                "footprintApplication");
        footprintApplication.put("coordinateSpace", "SOURCE_PIXEL");
        footprintApplication.put("axes", "XY");
        footprintApplication.put(
                "reusedUnchangedAcrossAllChannelsSlicesFrames", true);

        final ObjectNode atlas = root.putObject("atlas");
        atlas.put("id", snapshot.verifiedAtlas().atlasId());
        atlas.put("version", snapshot.verifiedAtlas().atlasVersion());
        atlas.put("identitySha256",
                snapshot.verifiedAtlas().identitySha256());
        atlas.put("annotationPlaneSha256",
                requireSha256(annotationPlaneSha256));
        final ArrayNode assets = atlas.putArray("assets");
        snapshot.verifiedAtlas().assets().stream()
                .sorted(java.util.Comparator.comparing(
                        AtlasAssetVerification::role))
                .forEach(asset -> {
                    final ObjectNode item = assets.addObject();
                    item.put("role", asset.role());
                    item.put("sizeBytes", asset.sizeBytes());
                    item.put("sha256", asset.sha256());
                });

        final ObjectNode alignment = root.putObject("acceptedAlignment");
        alignment.put("method", snapshot.outputMethodLabel());
        alignment.put("contentRevision", snapshot.contentRevision());
        alignment.put("acceptanceAuditSequence",
                snapshot.acceptanceAuditSequence());
        alignment.put("coronalLevel",
                snapshot.coronalLevel().zeroBasedAnteriorPosteriorIndex());
        alignment.put("sagittalTiltDegrees",
                snapshot.atlasPlaneTilt().sagittalDegrees());
        alignment.put("horizontalTiltDegrees",
                snapshot.atlasPlaneTilt().horizontalDegrees());
        alignment.put("orientation", snapshot.orientation().name());
        alignment.put("laterality", snapshot.observedHemisphere().name());
        alignment.put("sectionMode", snapshot.reviewSectionMode().name());
        alignment.put("halfAtlasCoverage",
                snapshot.halfAtlasCoverage().name());
        final ArrayNode includedSides = alignment.putArray(
                "includedAtlasSides");
        for (final org.atlasalign.application.manual.ManualHemisphereWarp2D
                .AtlasSide side : org.atlasalign.application.manual
                .ManualHemisphereWarp2D.AtlasSide.values()) {
            if (snapshot.includesAtlasSide(side)) {
                includedSides.add(side.name());
            }
        }
        alignment.put("workflowMode", snapshot.workflowMode().name());
        alignment.put("joinedManualPlacementApplied",
                snapshot.joinedManualPlacementApplied());
        alignment.put("tissueClippingEnabled",
                snapshot.tissueClippingEnabled());
        alignment.put("reviewedTissueSupportSha256",
                snapshot.reviewedTissueSupport()
                        .map(support -> support.sha256()).orElse(""));
        final ObjectNode mapping = alignment.putObject("previewMapping");
        mapping.put("sourceWidth", snapshot.previewMapping().sourceWidth());
        mapping.put("sourceHeight", snapshot.previewMapping().sourceHeight());
        mapping.put("previewWidth", snapshot.previewMapping().previewWidth());
        mapping.put("previewHeight", snapshot.previewMapping().previewHeight());
        appendAffine(alignment.putObject("preOutlineAtlasToPreview"),
                snapshot.preOutlineAtlasToPreview());
        appendAffine(alignment.putObject("postOutlinePreviewAdjustment"),
                snapshot.postOutlinePreviewAdjustment());
        final ObjectNode outline = alignment.putObject("outlineWarp");
        outline.put("present", snapshot.outlineWarp().isPresent());
        snapshot.outlineWarp().ifPresent(warp -> {
            outline.put("algorithmRevision", warp.algorithmRevision());
            outline.put("contentSha256", warp.contentSha256());
        });
        final ObjectNode hemisphere = alignment.putObject("hemisphereWarp");
        hemisphere.put("present", snapshot.hemisphereWarp().isPresent());
        snapshot.hemisphereWarp().ifPresent(warp -> {
            hemisphere.put("algorithmRevision", warp.algorithmRevision());
            hemisphere.put("pixelCenterConvention",
                    org.atlasalign.application.manual.ManualHemisphereWarp2D
                            .PIXEL_CENTER_CONVENTION);
            hemisphere.put("contentSha256",
                    warp.diagnostics().contentSha256());
            hemisphere.put("previewWidth", warp.previewWidth());
            hemisphere.put("previewHeight", warp.previewHeight());
            hemisphere.put("seamWidth", warp.seamWidth());
            hemisphere.put("outlineContentSha256",
                    warp.outlineContentSha256());
            hemisphere.put("atlasLeftControls",
                    warp.diagnostics().atlasLeftControlCount());
            hemisphere.put("atlasRightControls",
                    warp.diagnostics().atlasRightControlCount());
            final ObjectNode midline = hemisphere.putObject("imageMidline");
            appendPoint(midline.putObject("dorsal"),
                    warp.imageMidline().dorsal());
            appendPoint(midline.putObject("ventral"),
                    warp.imageMidline().ventral());
            final ArrayNode midlinePath = hemisphere.putArray(
                    "imageMidlinePath");
            warp.imageMidlinePath().forEach(point ->
                    appendPoint(midlinePath.addObject(), point));
            final ArrayNode controls = hemisphere.putArray("controls");
            warp.controls().forEach(control -> {
                final ObjectNode item = controls.addObject();
                item.put("id", control.id());
                item.put("atlasSide", control.atlasSide().name());
                item.put("origin", control.origin().name());
                item.put("groupId", control.groupId());
                item.put("structureAcronym",
                        control.structureAcronym());
                appendPoint(item.putObject("sourcePoint"),
                        control.sourcePoint());
                appendPoint(item.putObject("targetPoint"),
                        control.targetPoint());
            });
        });
        final ObjectNode local = alignment.putObject("genericLocalWarp");
        local.put("present", snapshot.localWarp().isPresent());
        snapshot.localWarp().ifPresent(warp -> {
            local.put("algorithmRevision", warp.algorithmRevision());
            local.put("contentSha256",
                    warp.diagnostics().contentHashSha256());
        });
        final ObjectNode placements = alignment.putObject(
                "manualSidePlacement");
        appendAffine(placements.putObject("atlasLeft"),
                snapshot.manualSidePlacement().atlasLeft());
        appendAffine(placements.putObject("atlasRight"),
                snapshot.manualSidePlacement().atlasRight());

        final ArrayNode warningArray = root.putArray("warnings");
        List.copyOf(Objects.requireNonNull(warnings, "warnings"))
                .forEach(warningArray::add);
        final ArrayNode outputs = root.putArray("outputs");
        List.copyOf(Objects.requireNonNull(artifacts, "artifacts"))
                .forEach(artifact -> appendArtifact(outputs, artifact));
        try {
            MAPPER.writeValue(destination.toFile(), root);
        } catch (final IOException error) {
            throw new IllegalStateException(
                    "Could not write AtlasAlign export manifest", error);
        }
    }

    private static void appendCalibration(
            final ObjectNode node,
            final CalibrationMetadata calibration) {
        appendCalibrationValue(node.putObject("pixelWidth"),
                calibration.pixelWidth(),
                calibration.pixelWidthStatus().name());
        appendCalibrationValue(node.putObject("pixelHeight"),
                calibration.pixelHeight(),
                calibration.pixelHeightStatus().name());
        appendCalibrationValue(node.putObject("pixelDepth"),
                calibration.pixelDepth(),
                calibration.pixelDepthStatus().name());
        appendCalibrationValue(node.putObject("frameInterval"),
                calibration.frameInterval(),
                calibration.frameIntervalStatus().name());
        node.put("spatialUnit", calibration.spatialUnit());
        node.put("spatialUnitStatus",
                calibration.spatialUnitStatus().name());
        node.put("timeUnit", calibration.timeUnit());
        node.put("timeUnitStatus", calibration.timeUnitStatus().name());
    }

    private static void appendCalibrationValue(
            final ObjectNode node,
            final double value,
            final String status) {
        node.put("value", Double.toString(value));
        node.put("rawBitsHex", String.format(java.util.Locale.ROOT,
                "%016x", Double.doubleToRawLongBits(value)));
        node.put("status", status);
    }

    private static void appendAffine(
            final ObjectNode node,
            final AffineTransform2D transform) {
        node.put("sourceSpace", transform.sourceSpace().name());
        node.put("destinationSpace", transform.destinationSpace().name());
        node.put("m00", transform.m00());
        node.put("m01", transform.m01());
        node.put("m02", transform.m02());
        node.put("m10", transform.m10());
        node.put("m11", transform.m11());
        node.put("m12", transform.m12());
    }

    private static void appendPoint(
            final ObjectNode node,
            final org.atlasalign.core.Point2D point) {
        node.put("x", point.x());
        node.put("y", point.y());
    }

    private static void appendArtifact(
            final ArrayNode outputs,
            final Artifact artifact) {
        final ObjectNode item = outputs.addObject();
        item.put("kind", artifact.kind());
        item.put("fileName", artifact.fileName());
        item.put("sizeBytes", artifact.sizeBytes());
        item.put("sha256", artifact.sha256());
        if (artifact.footprint() == null) {
            return;
        }
        final SourceRegionFootprint footprint = artifact.footprint();
        final ObjectNode bounds = item.putObject("sourceBounds");
        bounds.put("minimumX", footprint.bounds().minimumX());
        bounds.put("minimumY", footprint.bounds().minimumY());
        bounds.put("width", footprint.bounds().width());
        bounds.put("height", footprint.bounds().height());
        item.put("includedSourcePixels", footprint.pixelCount());
        final boolean fullSource = "mask_full".equals(artifact.kind());
        final int rasterWidth = fullSource
                ? footprint.sourceWidth() : footprint.bounds().width();
        final int rasterHeight = fullSource
                ? footprint.sourceHeight() : footprint.bounds().height();
        item.put("rasterWidth", rasterWidth);
        item.put("rasterHeight", rasterHeight);
        item.put("sourceOriginX", fullSource
                ? 0 : footprint.bounds().minimumX());
        item.put("sourceOriginY", fullSource
                ? 0 : footprint.bounds().minimumY());
        item.put("footprintAppliedAcrossAllSourceCztPlanes",
                "masked_crop".equals(artifact.kind()));
        appendArtifactSemantics(item, artifact.kind());
        final ArrayNode regions = item.putArray("regions");
        footprint.selections().forEach(selection -> {
            final ObjectNode region = regions.addObject();
            region.put("rootRegionId", selection.rootRegionId());
            region.put("acronym", selection.acronym());
            region.put("name", selection.name());
            region.put("includeDescendants",
                    selection.includeDescendants());
            final ArrayNode ids = region.putArray("includedRegionIds");
            selection.includedRegionIds().stream().sorted()
                    .forEach(ids::add);
        });
    }

    private static void appendArtifactSemantics(
            final ObjectNode item,
            final String kind) {
        switch (kind) {
            case "crop" -> {
                item.put("rasterExtent", "tight-source-bounds");
                item.put("derived", false);
                item.put("footprintRole",
                        "bounds only; irregular footprint retained separately");
                item.put("insidePixelSemantics",
                        "direct source values");
                item.put("outsidePixelSemantics",
                        "unmasked rectangular source values retained");
            }
            case "mask" -> {
                item.put("rasterExtent", "crop-local-mask");
                item.put("derived", true);
                item.put("footprintRole",
                        "crop-local two-dimensional footprint definition");
                item.put("insidePixelSemantics", "unsigned 8-bit 255");
                item.put("outsidePixelSemantics", "unsigned 8-bit 0");
            }
            case "mask_full" -> {
                item.put("rasterExtent", "full-source-mask");
                item.put("derived", true);
                item.put("footprintRole",
                        "source-sized two-dimensional footprint definition");
                item.put("insidePixelSemantics", "unsigned 8-bit 255");
                item.put("outsidePixelSemantics", "unsigned 8-bit 0");
            }
            case "masked_crop" -> {
                item.put("rasterExtent", "tight-source-bounds");
                item.put("derived", true);
                item.put("footprintRole",
                        "applied unchanged to every source C/Z/T plane");
                item.put("insidePixelSemantics",
                        "exact source values and raw float bits");
                item.put("outsidePixelSemantics",
                        "numeric positive zero on every C/Z/T plane");
            }
            default -> throw new IllegalArgumentException(
                    "Unknown raster artifact kind: " + kind);
        }
    }

    private static String requireText(
            final String value,
            final String field) {
        final String checked = Objects.requireNonNull(value, field).trim();
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return checked;
    }

    private static String requireSha256(final String value) {
        final String checked = Objects.requireNonNull(value, "sha256");
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Expected lowercase SHA-256 hex");
        }
        return checked;
    }
}
