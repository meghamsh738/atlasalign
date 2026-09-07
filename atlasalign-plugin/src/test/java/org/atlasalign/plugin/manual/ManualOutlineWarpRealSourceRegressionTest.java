package org.atlasalign.plugin.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ij.IJ;
import ij.ImagePlus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import org.atlasalign.application.ObservedAnatomicalHemisphere;
import org.atlasalign.application.ReviewPreviewDimensions;
import org.atlasalign.application.SafeImageIntakeService;
import org.atlasalign.application.TissueSegmenter;
import org.atlasalign.application.manual.AutomaticTissueOutlineProposer;
import org.atlasalign.application.manual.ContourCaptureStatus;
import org.atlasalign.application.manual.ManualHemisphereWarp2D;
import org.atlasalign.application.manual.ManualWarpControl;
import org.atlasalign.application.manual.ManualWarpControlOrigin;
import org.atlasalign.application.manual.SectionGeometry;
import org.atlasalign.application.manual.SectionObservation;
import org.atlasalign.application.manual.SourceImageIdentity;
import org.atlasalign.application.AtlasOrientation;
import org.atlasalign.atlas.AtlasRepository;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.plugin.review.VerifiedAtlasPlaneSource;
import org.atlasalign.core.Point2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Source-only regression for the verified automatic outline and atlas warp.
 * It never opens coordinate truth and never mutates the source or atlas.
 */
@EnabledIfEnvironmentVariable(
        named = "ATLASALIGN_REAL_OUTLINE_WARP", matches = "1")
class ManualOutlineWarpRealSourceRegressionTest {

    private static final String PINNED_S102_FILE_SHA256 =
            "ce29c7afb9ec78c504e0dac99a6da38206d9bdb047b84886c2de4a2da8f46a3e";

    @Test
    void automaticS102OutlineProducesSafeBoundaryPinnedWarpAtLevel264()
            throws Exception {
        final Path input = Path.of(System.getProperty(
                "atlasalign.validation.geometryInput")).toAbsolutePath();
        final Path atlasCache = Path.of(System.getProperty(
                "atlasalign.atlas.cache")).toAbsolutePath();
        assertEquals(PINNED_S102_FILE_SHA256, sha256(input));

        final ImagePlus source = IJ.openImage(input.toString());
        assertNotNull(source);
        try {
            final ImagePlusSourceImage readOnly =
                    new ImagePlusSourceImage(source);
            final var before = readOnly.snapshot();
            final var safe = new SafeImageIntakeService()
                    .preparePreview(readOnly, 1, 2_048);
            final var mapping = safe.preview().mapping();
            final var segmentation = new TissueSegmenter().segment(
                    mapping.previewWidth(), mapping.previewHeight(),
                    safe.preview().pixels());
            final SourceImageIdentity sourceIdentity = new SourceImageIdentity(
                    before.pixelSha256(), before.metadata(),
                    "pixel-centre-v1:source=" + mapping.sourceWidth() + "x"
                            + mapping.sourceHeight() + ";preview="
                            + mapping.previewWidth() + "x"
                            + mapping.previewHeight());
            final var outline = new AutomaticTissueOutlineProposer().propose(
                    "real-outline-warp-regression", segmentation, mapping,
                    sourceIdentity, safe.preview().pixels(),
                    ReviewPreviewDimensions.capture(
                            mapping.previewWidth(), mapping.previewHeight(),
                            safe.preview().pixels()))
                    .withCaptureStatus(ContourCaptureStatus.COMPLETE);

            final var verifiedAtlas = new AtlasRepository()
                    .openAllenMouse25um(atlasCache);
            final var planes = new VerifiedAtlasPlaneSource(verifiedAtlas);
            final var root = planes.resolveExactAcronym("root").orElseThrow();
            final var dg = planes.resolveExactAcronym("DG-sg").orElseThrow();
            final SectionObservation observation = new SectionObservation(
                    SectionGeometry.FULL,
                    AtlasOrientation.CONFIRMED_ATLAS_LEFT_TO_IMAGE_LEFT,
                    ObservedAnatomicalHemisphere.BOTH, true, true);
            final var preview = ManualCandidateMatcher.outlinePreview(
                    planes.load(264), root, dg, List.of(outline),
                    observation, mapping);

            final var diagnostics = preview.outlineTransform().diagnostics();
            assertTrue(diagnostics.maximumBoundaryErrorPixels() <= 0.25,
                    "The reviewed source boundary must be authoritative");
            assertTrue(!preview.mappedRootPreviewPaths().isEmpty());
            assertTrue(!preview.mappedGuidePreviewPaths().isEmpty());
            final var explicitReplay =
                    ManualCandidateMatcher.outlinePreview(
                            planes.load(264), root, dg, List.of(outline),
                            observation, mapping);
            assertEquals(preview.contentSha256(),
                    explicitReplay.contentSha256(),
                    "The exact boundary map must replay identically");

            final List<Point2D> candidates = preview
                    .mappedGuidePreviewPaths().stream()
                    .flatMap(List::stream)
                    .filter(point -> preview.outlineTransform()
                            .containsTissueHemispherePoint(true, point))
                    .toList();
            assertTrue(candidates.size() >= 6);
            final List<ManualWarpControl> controls = new ArrayList<>();
            for (int index = 0; index < 6; index++) {
                final Point2D point = candidates.get(
                        index * (candidates.size() - 1) / 5);
                controls.add(new ManualWarpControl(
                        "real-dg-sg-left-" + index,
                        ManualHemisphereWarp2D.AtlasSide.LEFT,
                        ManualWarpControlOrigin.VERIFIED_STRUCTURE_BOUNDARY,
                        "real-dg-sg-left", "DG-sg", point, point));
            }
            final var localWarp = ManualHemisphereWarp2D.fit(
                    controls, observation.orientation(),
                    preview.outlineTransform());
            final List<Point2D> seam = localWarp.imageMidlinePath();
            assertEquals(preview.outlineTransform().hemisphereMidlinePath(),
                    seam);
            for (int index = 0; index < seam.size(); index++) {
                final Point2D point = seam.get(index);
                assertEquals(point, localWarp.apply(point),
                        "The real-fixture seam must remain exact identity");
                assertTrue(preview.outlineTransform()
                        .containsTissuePoint(point));
                if (index + 1 < seam.size()) {
                    final Point2D next = seam.get(index + 1);
                    final Point2D midpoint = new Point2D(
                            (point.x() + next.x()) / 2.0,
                            (point.y() + next.y()) / 2.0);
                    assertEquals(midpoint, localWarp.apply(midpoint),
                            "Every curved seam segment must remain exact identity");
                }
            }
            for (final Point2D point : preview.outlineTransform()
                    .tissueBoundary()) {
                assertEquals(point, localWarp.apply(point),
                        "The real-fixture reviewed boundary must remain exact identity");
            }
            assertEquals(before, readOnly.snapshot());
            assertEquals(PINNED_S102_FILE_SHA256, sha256(input));
            System.out.println("REAL_OUTLINE_WARP level=264 diagnostics="
                    + diagnostics);
        } finally {
            source.close();
        }
    }

    private static String sha256(final Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(path)));
    }

}
