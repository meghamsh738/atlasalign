package org.atlasalign.plugin.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
