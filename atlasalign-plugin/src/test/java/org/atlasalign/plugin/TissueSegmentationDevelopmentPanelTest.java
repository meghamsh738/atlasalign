package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ij.IJ;
import ij.ImagePlus;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.atlasalign.application.SafeImageIntakeService;
import org.atlasalign.application.SectionGeometry;
import org.atlasalign.application.TissueGeometryClassifier;
import org.atlasalign.application.TissueMaskEnvelope;
import org.atlasalign.application.TissueSegmentationCandidateDiagnostic;
import org.atlasalign.application.TissueSegmentationException;
import org.atlasalign.application.TissueSegmentationResult;
import org.atlasalign.application.TissueSegmenter;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Opt-in, development-only audit of the exact production tissue-mask path.
 */
@EnabledIfEnvironmentVariable(
        named = "ATLASALIGN_TISSUE_DEVELOPMENT", matches = "1")
class TissueSegmentationDevelopmentPanelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, SectionGeometry> ADJUDICATED_GEOMETRY =
            Map.ofEntries(
                    Map.entry(
                            "641_2002_2568_NM01_s004_10x_A.png",
                            SectionGeometry.PARTIAL_OR_DAMAGED),
                    Map.entry(
                            "641_2002_2568_NM01_s049_10x_A.png",
                            SectionGeometry.FULL),
                    Map.entry(
                            "641_2002_2568_NM01_s094_10x_A.png",
                            SectionGeometry.FULL),
                    Map.entry(
                            "641_2002_2568_NM01_s144_10x_A.png",
                            SectionGeometry.FULL),
                    Map.entry(
                            "641_2002_2568_NM01_s199_10x_A.png",
                            SectionGeometry.BILATERAL_REVIEW_REQUIRED),
                    Map.entry(
                            "6517_Pitx3_tTA_lacZ_Xgal_s002.png",
                            SectionGeometry.PARTIAL_OR_DAMAGED),
                    Map.entry(
                            "6517_Pitx3_tTA_lacZ_Xgal_s026.png",
                            SectionGeometry.FULL),
                    Map.entry(
                            "6517_Pitx3_tTA_lacZ_Xgal_s059.png",
                            SectionGeometry.PARTIAL_OR_DAMAGED),
                    Map.entry(
                            "6517_Pitx3_tTA_lacZ_Xgal_s083.png",
                            SectionGeometry.FULL),
                    Map.entry(
                            "6517_Pitx3_tTA_lacZ_Xgal_s116.png",
                            SectionGeometry.BILATERAL_REVIEW_REQUIRED),
                    Map.entry(
                            "CamKII_317_8_tTA_lacZ_Xgal_s123.png",
                            SectionGeometry.PARTIAL_OR_DAMAGED),
                    Map.entry(
                            "CamKII_317_8_tTA_lacZ_Xgal_s218.png",
                            SectionGeometry.FULL),
                    Map.entry(
                            "CamKII_317_8_tTA_lacZ_Xgal_s314.png",
                            SectionGeometry.PARTIAL_OR_DAMAGED),
                    Map.entry(
                            "CamKII_317_8_tTA_lacZ_Xgal_s420.png",
                            SectionGeometry.PARTIAL_OR_DAMAGED),
                    Map.entry(
                            "CamKII_317_8_tTA_lacZ_Xgal_s524.png",
                            SectionGeometry.PARTIAL_OR_DAMAGED));

    @Test
    void recordsDevelopmentPanelWithoutInferenceOrAtlasLoading()
            throws Exception {
        final Path manifestPath = requiredPath(
                "atlasalign.validation.developmentManifest");
        final Path outputPath = outputPath(
                "atlasalign.validation.output");
        final JsonNode manifest = MAPPER.readTree(manifestPath.toFile());
        assertTrue(manifest.path("developmentOnly").asBoolean(false),
                "Refusing a manifest that is not marked development-only");
        final JsonNode panel = manifest.path("panel");
        assertTrue(panel.isArray()
                        && panel.size() == ADJUDICATED_GEOMETRY.size(),
                "Development panel must contain the 15 adjudicated cases");
        final Path overlayDirectory = outputPath.getParent()
                .resolve("mask-overlays");
        Files.createDirectory(overlayDirectory);

        final ObjectNode result = MAPPER.createObjectNode();
        result.put("protocol",
                "phase5-tissue-segmentation-development-v1");
        result.put("manifestPath", manifestPath.toString());
        result.put("truthAccessed", false);
        result.put("adjudicationProtocol",
                "manual-full-section-coverage-review-v1");
        final ArrayNode cases = result.putArray("cases");

        int passed = 0;
        final Set<String> seen = new HashSet<>();
        for (final JsonNode entry : panel) {
            final String image = requiredText(entry, "filename");
            assertTrue(seen.add(image),
                    "Development panel contains a duplicate: " + image);
            final SectionGeometry expected = ADJUDICATED_GEOMETRY.get(image);
            assertNotNull(expected,
                    "Development case was not visually adjudicated: " + image);
            final String relativeInput = requiredText(
                    entry.path("variants").path("worker_minmax"), "path");
            final Path input = manifestPath.getParent()
                    .resolve(relativeInput).normalize().toRealPath();
            assertTrue(input.startsWith(manifestPath.getParent().toRealPath()),
                    "Development input escaped the run directory");

            final ImagePlus source = IJ.openImage(input.toString());
            assertNotNull(source, "ImageJ could not open " + input);
            final ObjectNode record = cases.addObject();
            record.put("dataset", requiredText(entry, "dataset"));
            record.put("image", image);
            record.put("inputPath", input.toString());
            try {
                final ImagePlusSourceImage readOnly =
                        new ImagePlusSourceImage(source);
                final SourceImageSnapshot before = readOnly.snapshot();
                final var safe = new SafeImageIntakeService()
                        .preparePreview(readOnly, 1, 2_048);
                record.put("previewWidth",
                        safe.preview().mapping().previewWidth());
                record.put("previewHeight",
                        safe.preview().mapping().previewHeight());
                try {
                    final TissueSegmentationResult segmentation =
                            new TissueSegmenter().segment(
                                    safe.preview().mapping().previewWidth(),
                                    safe.preview().mapping().previewHeight(),
                                    safe.preview().pixels());
                    final var geometry = new TissueGeometryClassifier()
                            .classify(segmentation.mask());
                    final BinaryMask envelopeMask =
                            new TissueMaskEnvelope().fillInteriorHoles(
                                    segmentation.mask());
                    final var envelopeGeometry =
                            new TissueGeometryClassifier().classify(
                                    envelopeMask);
                    final boolean matched =
                            expected == geometry.geometry();
                    record.put("status", matched
                            ? "PASS" : "FAIL_GEOMETRY_MISMATCH");
                    record.put("method", segmentation.method().name());
                    record.put("polarity", segmentation.polarity().name());
                    record.put("foregroundFraction",
                            segmentation.foregroundFraction());
                    record.put("largestComponentFraction",
                            segmentation.largestComponentFraction());
                    record.put("borderForegroundFraction",
                            segmentation.borderForegroundFraction());
                    record.put("candidateScore",
                            segmentation.candidateScore());
                    record.put("sectionClassification",
                            geometry.geometry().name());
                    record.put("adjudicatedClassification",
                            expected.name());
                    record.put("adjudicationMatched", matched);
                    record.put("tissueAspectRatio",
                            geometry.widthToHeightRatio());
                    record.put("foregroundToBoundsFraction",
                            geometry.foregroundToBoundsFraction());
                    record.put("imageLeftEdgeDispersion",
                            geometry.imageLeftEdgeDispersion());
                    record.put("imageRightEdgeDispersion",
                            geometry.imageRightEdgeDispersion());
                    record.put("bilateralMirroredOverlap",
                            geometry.bilateralMirroredOverlap());
                    record.put("hemisphereBalance",
                            geometry.hemisphereBalance());
                    record.put("connectedComponentCount",
                            geometry.connectedComponentCount());
                    record.put("substantialComponentCount",
                            geometry.substantialComponentCount());
                    record.put("envelopeClassification",
                            envelopeGeometry.geometry().name());
                    record.put("envelopeAddedPixelCount",
                            envelopeMask.foregroundCount()
                                    - segmentation.mask().foregroundCount());
                    record.put("envelopeAddedPreviewFraction",
                            (double) (envelopeMask.foregroundCount()
                                    - segmentation.mask().foregroundCount())
                                    / (safe.preview().mapping().previewWidth()
                                            * safe.preview().mapping()
                                                    .previewHeight()));
                    record.put("envelopeAspectRatio",
                            envelopeGeometry.widthToHeightRatio());
                    record.put("envelopeForegroundToBoundsFraction",
                            envelopeGeometry.foregroundToBoundsFraction());
                    record.put("envelopeImageLeftEdgeDispersion",
                            envelopeGeometry.imageLeftEdgeDispersion());
                    record.put("envelopeImageRightEdgeDispersion",
                            envelopeGeometry.imageRightEdgeDispersion());
                    record.put("envelopeBilateralMirroredOverlap",
                            envelopeGeometry.bilateralMirroredOverlap());
                    record.put("envelopeHemisphereBalance",
                            envelopeGeometry.hemisphereBalance());
                    record.put("envelopeConnectedComponentCount",
                            envelopeGeometry.connectedComponentCount());
                    final Path overlay = overlayDirectory.resolve(
                            Path.of(image).getFileName().toString()
                                    + ".mask.png");
                    writeOverlay(
                            overlay,
                            safe.preview().mapping().previewWidth(),
                            safe.preview().mapping().previewHeight(),
                            safe.preview().pixels(),
                            segmentation.mask());
                    record.put("maskOverlayPath", overlay.toString());
                    appendCandidates(
                            record.putArray("candidates"),
                            segmentation.candidates());
                    appendAuditNotes(
                            record.putArray("auditNotes"),
                            segmentation.auditNotes());
                    if (matched) {
                        passed++;
                    }
                } catch (final TissueSegmentationException error) {
                    record.put("status", "FAIL_SEGMENTATION");
                    record.put("failure", error.getMessage());
                    appendCandidates(
                            record.putArray("candidates"),
                            error.candidates());
                    appendAuditNotes(
                            record.putArray("auditNotes"),
                            error.auditNotes());
                }
                final SourceImageSnapshot after = readOnly.snapshot();
                assertEquals(before, after,
                        "Development segmentation modified " + image);
                record.put("sourceIntegrityVerified", true);
                record.put("sourcePixelSha256", before.pixelSha256());
            } finally {
                source.close();
            }
        }
        assertEquals(ADJUDICATED_GEOMETRY.keySet(), seen,
                "Development panel differs from adjudicated set");

        result.put("caseCount", panel.size());
        result.put("passCount", passed);
        result.put("failureCount", panel.size() - passed);
        Files.writeString(
                outputPath,
                MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(result) + System.lineSeparator(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        assertEquals(panel.size(), passed,
                "Development geometry differs from the frozen adjudication; "
                        + "see the durable output for every case");
    }

    private static void writeOverlay(
            final Path path,
            final int width,
            final int height,
            final float[] pixels,
            final BinaryMask mask) throws Exception {
        float minimum = Float.POSITIVE_INFINITY;
        float maximum = Float.NEGATIVE_INFINITY;
        for (final float value : pixels) {
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        final BufferedImage image = new BufferedImage(
                width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int index = y * width + x;
                final int gray = Math.max(0, Math.min(255,
                        Math.round(255 * (pixels[index] - minimum)
                                / (maximum - minimum))));
                final Color color;
                if (isBoundary(mask, x, y)) {
                    color = Color.YELLOW;
                } else if (mask.contains(x, y)) {
                    color = new Color(
                            (255 + gray) / 2,
                            gray / 2,
                            gray / 2);
                } else {
                    color = new Color(gray, gray, gray);
                }
                image.setRGB(x, y, color.getRGB());
            }
        }
        assertTrue(ImageIO.write(image, "PNG", path.toFile()),
                "PNG overlay writer is unavailable");
    }

    private static boolean isBoundary(
            final BinaryMask mask,
            final int x,
            final int y) {
        if (!mask.contains(x, y)) {
            return false;
        }
        for (int offsetY = -1; offsetY <= 1; offsetY++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                if (!mask.contains(x + offsetX, y + offsetY)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void appendCandidates(
            final ArrayNode output,
            final java.util.List<
                    TissueSegmentationCandidateDiagnostic> candidates) {
        for (final TissueSegmentationCandidateDiagnostic candidate
                : candidates) {
            final ObjectNode record = output.addObject();
            record.put("method", candidate.method().name());
            record.put("polarity", candidate.polarity().name());
            record.put("threshold", candidate.threshold());
            candidate.companionThreshold().ifPresentOrElse(
                    value -> record.put("companionThreshold", value),
                    () -> record.putNull("companionThreshold"));
            record.put("histogramLowerBound",
                    candidate.histogramLowerBound());
            record.put("histogramUpperBound",
                    candidate.histogramUpperBound());
            record.put("percentileWindowFallback",
                    candidate.percentileWindowFallback());
            record.put("foregroundFraction",
                    candidate.foregroundFraction());
            record.put("largestComponentFraction",
                    candidate.largestComponentFraction());
            record.put("borderForegroundFraction",
                    candidate.borderForegroundFraction());
            candidate.exteriorBackgroundFraction().ifPresentOrElse(
                    value -> record.put(
                            "exteriorBackgroundFraction", value),
                    () -> record.putNull(
                            "exteriorBackgroundFraction"));
            candidate.exteriorBackgroundBorderFraction().ifPresentOrElse(
                    value -> record.put(
                            "exteriorBackgroundBorderFraction", value),
                    () -> record.putNull(
                            "exteriorBackgroundBorderFraction"));
            record.put("score", candidate.score());
            record.put("rejectionReason",
                    candidate.rejectionReason().orElse(""));
        }
    }

    private static void appendAuditNotes(
            final ArrayNode output,
            final java.util.List<String> notes) {
        notes.forEach(output::add);
    }

    private static Path requiredPath(final String property) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing system property: " + property);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static Path outputPath(final String property) throws Exception {
        final Path output = requiredPath(property);
        final Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IllegalArgumentException(
                    "Output parent does not exist: " + output);
        }
        if (Files.exists(output)) {
            throw new IllegalArgumentException(
                    "Refusing to overwrite: " + output);
        }
        return output;
    }

    private static String requiredText(
            final JsonNode node,
            final String field) {
        final JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(
                    "Missing text field: " + field);
        }
        return value.textValue();
    }
}
