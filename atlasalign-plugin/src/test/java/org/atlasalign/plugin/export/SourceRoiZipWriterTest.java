package org.atlasalign.plugin.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ij.io.RoiDecoder;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.zip.ZipFile;
import org.atlasalign.application.export.SourcePixelReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceRoiZipWriterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void entryCollisionIsRejectedBeforeCreatingOrTruncatingDestination() throws Exception {
        final BitSet mask = new BitSet(1);
        mask.set(0);
        final var footprint = new SourceRegionFootprint(
                List.of(ExportTestFixtures.region(1, "DG")), 12, 9,
                new SourcePixelReader.Bounds(3, 2, 1, 1), mask);
        final Path destination = temporaryDirectory.resolve("regions.zip");
        final var writer = new SourceRoiZipWriter();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> writer.write(destination, List.of(footprint, footprint),
                        List.of("DG.roi", "dg.roi")));
        assertFalse(java.nio.file.Files.exists(destination));
        java.nio.file.Files.writeString(destination, "existing result");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> writer.write(destination, List.of(footprint)));
        assertEquals("existing result", java.nio.file.Files.readString(destination));
    }

    @Test
    void roundTripsIrregularFootprintInSourceCoordinates() throws Exception {
        final BitSet mask = new BitSet(12);
        mask.set(0);
        mask.set(1);
        mask.set(5);
        mask.set(10);
        final SourceRegionFootprint footprint = new SourceRegionFootprint(
                List.of(ExportTestFixtures.region(1, "DG")),
                12, 9, new SourcePixelReader.Bounds(3, 2, 4, 3), mask);
        final Path destination = temporaryDirectory.resolve("regions.zip");

        new SourceRoiZipWriter().write(destination, List.of(footprint));

        try (ZipFile zip = new ZipFile(destination.toFile())) {
            assertEquals(1, zip.size());
            final var entry = zip.getEntry("DG.roi");
            final byte[] encoded;
            try (var input = zip.getInputStream(entry)) {
                encoded = input.readAllBytes();
            }
            final var roi = new RoiDecoder(encoded, entry.getName()).getRoi();
            assertEquals("DG", roi.getName());
            assertEquals(0, roi.getCPosition());
            assertEquals(0, roi.getZPosition());
            assertEquals(0, roi.getTPosition());
            assertEquals(new java.awt.Rectangle(3, 2, 3, 3),
                    roi.getBounds());
            assertTrue(roi.contains(3, 2));
            assertTrue(roi.contains(4, 2));
            assertTrue(roi.contains(4, 3));
            assertTrue(roi.contains(5, 4));
            assertFalse(roi.contains(6, 2));
            assertFalse(roi.contains(3, 4));
        }
    }
    @Test
    void pinnedSourceAndCropLocalRoisRoundTripPlanePositionsAndCoordinates() throws Exception {
        final var footprint = ExportTestFixtures.irregularFootprint(
                ExportTestFixtures.region(1, "DG"));
        final var writer = new SourceRoiZipWriter();
        final Path sourceZip = temporaryDirectory.resolve("source.zip");
        final Path namedSourceZip = temporaryDirectory.resolve("named-source.zip");
        final Path sourceSingleZip = temporaryDirectory.resolve("source-single.zip");
        final Path cropZip = temporaryDirectory.resolve("crop.zip");
        final Path legacySingleZip = temporaryDirectory.resolve("legacy-single.zip");
        writer.write(sourceZip, List.of(footprint), 3, 2);
        writer.write(namedSourceZip, List.of(footprint), List.of("identity-1.roi"), 3, 2);
        writer.writeSingle(sourceSingleZip, "source ROI", footprint, 0, 0, 3, 2);
        writer.writeSingle(cropZip, "crop ROI", footprint, 2, 1, 1, 1);
        writer.writeSingle(legacySingleZip, "legacy ROI", footprint, 2, 1);
        for (final Path path : List.of(sourceZip, namedSourceZip, sourceSingleZip,
                cropZip, legacySingleZip)) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                final var entry = zip.entries().nextElement();
                final byte[] encoded;
                try (var input = zip.getInputStream(entry)) {
                    encoded = input.readAllBytes();
                }
                final var roi = new RoiDecoder(encoded, entry.getName()).getRoi();
                final boolean cropLocal = path.equals(cropZip) || path.equals(legacySingleZip);
                assertEquals(cropLocal ? new java.awt.Rectangle(0, 0, 4, 3)
                        : new java.awt.Rectangle(2, 1, 4, 3), roi.getBounds());
                assertEquals(0, roi.getCPosition());
                assertEquals(path.equals(legacySingleZip) ? 0 : cropLocal ? 1 : 3,
                        roi.getZPosition());
                assertEquals(path.equals(legacySingleZip) ? 0 : cropLocal ? 1 : 2,
                        roi.getTPosition());
                for (int y = 0; y < 3; y++) {
                    for (int x = 0; x < 4; x++) {
                        assertEquals(footprint.cropMask().get(y * 4 + x),
                                roi.contains(x + (cropLocal ? 0 : 2), y + (cropLocal ? 0 : 1)));
                    }
                }
            }
        }
    }

    @Test
    void rejectsInvalidPinnedPlaneBeforeCreatingZip() {
        final var footprint = ExportTestFixtures.irregularFootprint(
                ExportTestFixtures.region(1, "DG"));
        final var writer = new SourceRoiZipWriter();
        final Path output = temporaryDirectory.resolve("invalid.zip");
        assertThrows(IllegalArgumentException.class,
                () -> writer.write(output, List.of(footprint), 0, 1));
        assertThrows(IllegalArgumentException.class,
                () -> writer.write(output, List.of(footprint), List.of("DG.roi"), 1, -1));
        assertThrows(IllegalArgumentException.class,
                () -> writer.writeSingle(output, "ROI", footprint, 0, 0, 0, 0));
        assertFalse(java.nio.file.Files.exists(output));
    }

}
