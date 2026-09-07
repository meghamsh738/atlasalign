package org.atlasalign.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ij.IJ;
import ij.ImagePlus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import org.atlasalign.application.SafeImageIntakeService;
import org.atlasalign.application.ReviewPreviewDimensions;
import org.atlasalign.application.TissueGeometryClassifier;
import org.atlasalign.application.TissueMaskEnvelope;
import org.atlasalign.application.TissueSegmenter;
import org.atlasalign.io.imagej.ImagePlusSourceImage;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.application.manual.AutomaticTissueOutlineProposer;
import org.atlasalign.application.manual.ContourCaptureStatus;
import org.atlasalign.application.manual.SourceImageIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(
        named = "ATLASALIGN_AD_HOC_GEOMETRY", matches = "1")
class TissueGeometryAdHocDiagnosticTest {

    private static final String PINNED_S102_FILE_SHA256 =
            "ce29c7afb9ec78c504e0dac99a6da38206d9bdb047b84886c2de4a2da8f46a3e";

    @Test
    void printsSourceOnlyGeometryWithoutCoordinateAccess() throws Exception {
        final Path input = Path.of(System.getProperty(
                "atlasalign.validation.geometryInput")).toAbsolutePath();
        assertEquals(PINNED_S102_FILE_SHA256,
                sha256(Files.readAllBytes(input)),
                "This opt-in regression accepts only the approved pinned s102 file");
        final ImagePlus source = IJ.openImage(input.toString());
        assertNotNull(source);
        try {
            final ImagePlusSourceImage readOnly =
                    new ImagePlusSourceImage(source);
            final var before = readOnly.snapshot();
            final var safe = new SafeImageIntakeService()
                    .preparePreview(readOnly, 1, 2_048);
            final var segmentation = new TissueSegmenter().segment(
                    safe.preview().mapping().previewWidth(),
                    safe.preview().mapping().previewHeight(),
                    safe.preview().pixels());
            final var geometry = new TissueGeometryClassifier()
                    .classify(segmentation.mask());
            final var envelopeMask = new TissueMaskEnvelope()
                    .fillInteriorHoles(segmentation.mask());
            final var envelopeGeometry = new TissueGeometryClassifier()
                    .classify(envelopeMask);
            assertEquals(before, readOnly.snapshot());
            System.out.println("SOURCE_ONLY_GEOMETRY"
                    + " aspect=" + geometry.widthToHeightRatio()
                    + " occupancy="
                    + geometry.foregroundToBoundsFraction()
                    + " overlap=" + geometry.bilateralMirroredOverlap()
                    + " balance=" + geometry.hemisphereBalance()
                    + " leftEdge=" + geometry.imageLeftEdgeDispersion()
                    + " rightEdge=" + geometry.imageRightEdgeDispersion()
                    + " components=" + geometry.connectedComponentCount()
                    + " classification=" + geometry.geometry());
            System.out.println("AtlasAlign envelope diagnostic: aspect="
                    + envelopeGeometry.widthToHeightRatio()
                    + " occupancy="
                    + envelopeGeometry.foregroundToBoundsFraction()
                    + " overlap="
                    + envelopeGeometry.bilateralMirroredOverlap()
                    + " balance="
                    + envelopeGeometry.hemisphereBalance()
                    + " leftEdge="
                    + envelopeGeometry.imageLeftEdgeDispersion()
                    + " rightEdge="
                    + envelopeGeometry.imageRightEdgeDispersion()
                    + " components="
                    + envelopeGeometry.connectedComponentCount()
                    + " addedPixels="
                    + (envelopeMask.foregroundCount()
                            - segmentation.mask().foregroundCount())
                    + " addedPreviewFraction="
                    + (double) (envelopeMask.foregroundCount()
                            - segmentation.mask().foregroundCount())
                            / (safe.preview().mapping().previewWidth()
                                    * safe.preview().mapping().previewHeight())
                    + " classification="
                    + envelopeGeometry.geometry());
            printOutlineTopology(envelopeMask);
            printOutlineTopology(largestFourConnected(envelopeMask));
            final var mapping = safe.preview().mapping();
            final var proposal = new AutomaticTissueOutlineProposer().propose(
                    "real-mask-regression", segmentation, mapping,
                    new SourceImageIdentity(
                            before.pixelSha256(), before.metadata(),
                            "pixel-centre-v1:source="
                                    + mapping.sourceWidth() + "x"
                                    + mapping.sourceHeight() + ";preview="
                                    + mapping.previewWidth() + "x"
                                    + mapping.previewHeight()),
                    safe.preview().pixels(), ReviewPreviewDimensions.capture(
                            mapping.previewWidth(), mapping.previewHeight(),
                            safe.preview().pixels()));
            assertEquals(ContourCaptureStatus.DRAFT,
                    proposal.captureStatus());
            final var provenance = proposal.automaticProposalProvenance()
                    .orElseThrow();
            System.out.println("AtlasAlign real-mask outline proposal: vertices="
                    + proposal.vertices().size() + " discardedComponents="
                    + provenance.discardedDiagonalOnlyComponentCount()
                    + " discardedPixels="
                    + provenance.discardedDiagonalOnlyPixelCount()
                    + " largestDiscarded="
                    + provenance
                            .largestDiscardedDiagonalOnlyComponentPixelCount()
                    + " largestDiscarded8cSpan="
                    + provenance
                            .largestDiscardedDiagonalOnlyEightConnectedSpanPixels()
                    + " includedCornerBackgroundComponents="
                    + provenance.includedCornerBackgroundComponentCount()
                    + " includedCornerBackgroundPixels="
                    + provenance.includedCornerBackgroundPixelCount()
                    + " largestIncludedCornerBackground="
                    + provenance
                            .largestIncludedCornerBackgroundComponentPixelCount()
                    + " largestIncludedCornerBackground8cSpan="
                    + provenance
                            .largestIncludedCornerBackgroundEightConnectedSpanPixels()
                    + " outlinedEnvelopeSha256="
                    + provenance.outlinedEnvelopeSha256());
            assertEquals(before, readOnly.snapshot());
            assertEquals(PINNED_S102_FILE_SHA256,
                    sha256(Files.readAllBytes(input)));
        } finally {
            source.close();
        }
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void printOutlineTopology(final BinaryMask mask) {
        final List<Integer> fourConnectedSizes = componentSizes(mask, false);
        final List<Integer> eightConnectedSizes = componentSizes(mask, true);
        int diagonalCheckerboards = 0;
        final List<String> checkerboardLocations = new ArrayList<>();
        for (int y = 0; y + 1 < mask.height(); y++) {
            for (int x = 0; x + 1 < mask.width(); x++) {
                final boolean a = mask.contains(x, y);
                final boolean b = mask.contains(x + 1, y);
                final boolean c = mask.contains(x, y + 1);
                final boolean d = mask.contains(x + 1, y + 1);
                if ((a && d && !b && !c) || (b && c && !a && !d)) {
                    diagonalCheckerboards++;
                    if (checkerboardLocations.size() < 30) {
                        checkerboardLocations.add(x + ":" + y + ":"
                                + (a && d ? "AD" : "BC"));
                    }
                }
            }
        }
        System.out.println("AtlasAlign outline topology: dimensions="
                + mask.width() + "x" + mask.height()
                + " foreground=" + mask.foregroundCount()
                + " fourConnectedSizes=" + fourConnectedSizes
                + " eightConnectedSizes=" + eightConnectedSizes
                + " diagonalCheckerboards=" + diagonalCheckerboards
                + " checkerboards=" + checkerboardLocations);
    }

    private static List<Integer> componentSizes(
            final BinaryMask mask, final boolean diagonal) {
        final BitSet visited = new BitSet(mask.width() * mask.height());
        final List<Integer> sizes = new ArrayList<>();
        for (int seed = 0; seed < mask.width() * mask.height(); seed++) {
            final int sx = seed % mask.width();
            final int sy = seed / mask.width();
            if (visited.get(seed) || !mask.contains(sx, sy)) {
                continue;
            }
            int size = 0;
            final ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(seed);
            visited.set(seed);
            while (!queue.isEmpty()) {
                final int current = queue.removeFirst();
                size++;
                final int x = current % mask.width();
                final int y = current / mask.width();
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0
                                || !diagonal && dx != 0 && dy != 0) {
                            continue;
                        }
                        final int nx = x + dx;
                        final int ny = y + dy;
                        if (nx < 0 || nx >= mask.width()
                                || ny < 0 || ny >= mask.height()
                                || !mask.contains(nx, ny)) {
                            continue;
                        }
                        final int next = ny * mask.width() + nx;
                        if (!visited.get(next)) {
                            visited.set(next);
                            queue.addLast(next);
                        }
                    }
                }
            }
            sizes.add(size);
        }
        sizes.sort(Comparator.reverseOrder());
        return sizes;
    }

    private static BinaryMask largestFourConnected(final BinaryMask mask) {
        final BitSet visited = new BitSet(mask.width() * mask.height());
        BitSet largest = new BitSet();
        for (int seed = 0; seed < mask.width() * mask.height(); seed++) {
            final int sx = seed % mask.width();
            final int sy = seed / mask.width();
            if (visited.get(seed) || !mask.contains(sx, sy)) {
                continue;
            }
            final BitSet component = new BitSet();
            final ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(seed);
            visited.set(seed);
            while (!queue.isEmpty()) {
                final int current = queue.removeFirst();
                component.set(current);
                final int x = current % mask.width();
                final int y = current / mask.width();
                final int[] next = {current - 1, current + 1,
                        current - mask.width(), current + mask.width()};
                for (int index = 0; index < next.length; index++) {
                    if (index == 0 && x == 0
                            || index == 1 && x + 1 == mask.width()
                            || index == 2 && y == 0
                            || index == 3 && y + 1 == mask.height()) {
                        continue;
                    }
                    final int candidate = next[index];
                    final int nx = candidate % mask.width();
                    final int ny = candidate / mask.width();
                    if (!visited.get(candidate) && mask.contains(nx, ny)) {
                        visited.set(candidate);
                        queue.addLast(candidate);
                    }
                }
            }
            if (component.cardinality() > largest.cardinality()) {
                largest = component;
            }
        }
        return BinaryMask.fromBitSet(mask.width(), mask.height(), largest);
    }
}
