package org.atlasalign.plugin.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import loci.formats.MetadataTools;
import loci.formats.in.OMETiffReader;
import loci.formats.meta.IMetadata;
import org.atlasalign.application.export.SourcePixelReader;
import org.atlasalign.core.CalibrationMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BioFormatsOmeTiffExporterTest {

    @TempDir
    Path temporaryDirectory;

    private final BioFormatsOmeTiffExporter exporter =
            new BioFormatsOmeTiffExporter();

    @Test
    void writesEightBitCztPlanesWithExactDirectIndexedValues()
            throws Exception {
        final var snapshot = ExportTestFixtures.sourceSnapshot(8, 2, 2, 2);
        final var source = new ExportTestFixtures.MemoryReader(snapshot);
        final var footprint = ExportTestFixtures.fullFootprint(
                ExportTestFixtures.region(1, "R1"));
        final Path output = temporaryDirectory.resolve("uint8.ome.tif");

        final var report = exporter.writeSourceCrop(
                output, "uint8", source, footprint,
                () -> false, ignored -> {
                });

        assertEquals("8.5.0", report.bioFormatsVersion());
        assertFalse(report.bigTiff());
        try (OMETiffReader reader = new OMETiffReader()) {
            reader.setId(output.toString());
            assertEquals(2, reader.getSizeC());
            assertEquals(2, reader.getSizeZ());
            assertEquals(2, reader.getSizeT());
            assertEquals(8, reader.getImageCount());
            for (int plane = 0; plane < reader.getImageCount(); plane++) {
                final byte[] actual = reader.openBytes(plane);
                final SourcePixelReader.ByteBlock expected =
                        (SourcePixelReader.ByteBlock) source.readPlane(
                                plane % 2 + 1,
                                plane / 2 % 2 + 1,
                                plane / 4 + 1,
                                footprint.bounds());
                assertArrayEquals(expected.pixels(), actual);
            }
        }
    }

    @Test
    void preservesUnsignedSixteenBitSamples() throws Exception {
        final var snapshot = ExportTestFixtures.sourceSnapshot(16, 2, 2, 2);
        final var source = new ExportTestFixtures.MemoryReader(snapshot);
        final var footprint = ExportTestFixtures.fullFootprint(
                ExportTestFixtures.region(1, "R1"));
        final Path output = temporaryDirectory.resolve("uint16.ome.tif");

        exporter.writeSourceCrop(output, "uint16", source, footprint,
                () -> false, ignored -> {
                });

        try (OMETiffReader reader = new OMETiffReader()) {
            reader.setId(output.toString());
            assertEquals(8, reader.getImageCount());
            for (int plane = 0; plane < reader.getImageCount(); plane++) {
                final byte[] bytes = reader.openBytes(plane);
                final short[] expected = ((SourcePixelReader
                        .UnsignedShortBlock) source.readPlane(
                                plane % 2 + 1,
                                plane / 2 % 2 + 1,
                                plane / 4 + 1,
                                footprint.bounds())).pixels();
                final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(
                        reader.isLittleEndian()
                                ? ByteOrder.LITTLE_ENDIAN
                                : ByteOrder.BIG_ENDIAN);
                for (final short value : expected) {
                    assertEquals(value & 0xffff,
                            buffer.getShort() & 0xffff);
                }
            }
        }
    }

    @Test
    void preservesEveryRawFloatBitIncludingNanPayloadAndNegativeZero()
            throws Exception {
        final var snapshot = ExportTestFixtures.sourceSnapshot(32, 2, 2, 2);
        final var source = new ExportTestFixtures.MemoryReader(snapshot);
        final var footprint = ExportTestFixtures.fullFootprint(
                ExportTestFixtures.region(1, "R1"));
        final Path output = temporaryDirectory.resolve("float.ome.tif");

        exporter.writeSourceCrop(output, "float", source, footprint,
                () -> false, ignored -> {
                });

        try (OMETiffReader reader = new OMETiffReader()) {
            reader.setId(output.toString());
            assertEquals(8, reader.getImageCount());
            for (int plane = 0; plane < reader.getImageCount(); plane++) {
                final byte[] bytes = reader.openBytes(plane);
                final float[] expected = ((SourcePixelReader.FloatBlock)
                        source.readPlane(
                                plane % 2 + 1,
                                plane / 2 % 2 + 1,
                                plane / 4 + 1,
                                footprint.bounds())).pixels();
                final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(
                        reader.isLittleEndian()
                                ? ByteOrder.LITTLE_ENDIAN
                                : ByteOrder.BIG_ENDIAN);
                for (final float value : expected) {
                    assertEquals(Float.floatToRawIntBits(value),
                            buffer.getInt());
                }
            }
        }
    }

    @Test
    void writesSinglePlaneBinaryMaskWithoutChangingCropGeometry()
            throws Exception {
        final var footprint = ExportTestFixtures.fullFootprint(
                ExportTestFixtures.region(1, "R1"));
        final Path output = temporaryDirectory.resolve("mask.ome.tif");

        exporter.writeMask(output, "mask", footprint);

        try (OMETiffReader reader = new OMETiffReader()) {
            reader.setId(output.toString());
            assertEquals(1, reader.getImageCount());
            assertEquals(8, reader.getBitsPerPixel());
            assertArrayEquals(footprint.maskBytes(), reader.openBytes(0));
        }
    }

    @Test
    void maskedCropPreservesEveryInsideByteAndZerosEveryOutsideByteAcrossCzt()
            throws Exception {
        for (final int bitDepth : new int[]{8, 16, 32}) {
            final var snapshot = ExportTestFixtures.sourceSnapshot(
                    bitDepth, 2, 2, 2);
            final var source = new ExportTestFixtures.MemoryReader(snapshot);
            final var footprint = ExportTestFixtures.irregularFootprint(
                    ExportTestFixtures.region(1, "R1"));
            final Path output = temporaryDirectory.resolve(
                    "masked-" + bitDepth + ".ome.tif");

            exporter.writeMaskedSourceCrop(output, "masked", source,
                    footprint, () -> false, ignored -> {
                    });

            final int bytesPerPixel = bitDepth / 8;
            try (OMETiffReader reader = new OMETiffReader()) {
                reader.setId(output.toString());
                assertEquals(2, reader.getSizeC());
                assertEquals(2, reader.getSizeZ());
                assertEquals(2, reader.getSizeT());
                assertEquals(bitDepth, reader.getBitsPerPixel());
                for (int plane = 0;
                        plane < reader.getImageCount(); plane++) {
                    final byte[] expected = BioFormatsOmeTiffExporter
                            .littleEndianBytes(source.readPlane(
                                    plane % 2 + 1,
                                    plane / 2 % 2 + 1,
                                    plane / 4 + 1,
                                    footprint.bounds()));
                    for (int pixel = 0;
                            pixel < footprint.bounds().pixelCount(); pixel++) {
                        if (!footprint.cropMask().get(pixel)) {
                            Arrays.fill(expected, pixel * bytesPerPixel,
                                    (pixel + 1) * bytesPerPixel, (byte) 0);
                        }
                    }
                    assertArrayEquals(expected, reader.openBytes(plane),
                            "masked plane " + plane + " at "
                                    + bitDepth + " bits");
                }
            }
            assertEquals(snapshot, source.snapshot());
        }
    }

    @Test
    void fullSourceMaskUsesSourceDimensionsAndExactSourceCoordinates()
            throws Exception {
        final var snapshot = ExportTestFixtures.sourceSnapshot(8, 1, 1, 1);
        final var footprint = ExportTestFixtures.irregularFootprint(
                ExportTestFixtures.region(1, "R1"));
        final Path output = temporaryDirectory.resolve("mask-full.ome.tif");

        final BioFormatsOmeTiffExporter rowStreamingExporter =
                new BioFormatsOmeTiffExporter(
                        ExportTestFixtures.SOURCE_WIDTH);
        rowStreamingExporter.writeFullSourceMask(
                output, "full mask", footprint,
                snapshot.metadata(), () -> false);

        final IMetadata metadata = MetadataTools.createOMEXMLMetadata();
        try (OMETiffReader reader = new OMETiffReader()) {
            reader.setMetadataStore(metadata);
            reader.setId(output.toString());
            assertEquals(ExportTestFixtures.SOURCE_WIDTH, reader.getSizeX());
            assertEquals(ExportTestFixtures.SOURCE_HEIGHT, reader.getSizeY());
            assertEquals(1, reader.getImageCount());
            assertArrayEquals(footprint.fullSourceMaskBytes(),
                    reader.openBytes(0));
            assertEquals(0.65, metadata.getPixelsPhysicalSizeX(0)
                    .value().doubleValue(), 1.0e-12);
            assertEquals(0.65, metadata.getPixelsPhysicalSizeY(0)
                    .value().doubleValue(), 1.0e-12);
            final Map<String, String> values = metadata
                    .getMapAnnotationValue(0).stream().collect(
                            Collectors.toMap(
                                    pair -> pair.getName(),
                                    pair -> pair.getValue()));
            assertEquals("0", values.get("atlasalign.sourceOriginX"));
            assertEquals("0", values.get("atlasalign.sourceOriginY"));
            assertEquals("full-source",
                    values.get("atlasalign.maskExtent"));
        }
    }

    @Test
    void cropLocalMaskCarriesCalibrationAndSourceOriginMetadata()
            throws Exception {
        final var snapshot = ExportTestFixtures.sourceSnapshot(8, 1, 1, 1);
        final var footprint = ExportTestFixtures.irregularFootprint(
                ExportTestFixtures.region(1, "R1"));
        final Path output = temporaryDirectory.resolve(
                "mask-calibrated.ome.tif");

        exporter.writeMask(output, "mask", footprint,
                snapshot.metadata());

        final IMetadata metadata = MetadataTools.createOMEXMLMetadata();
        try (OMETiffReader reader = new OMETiffReader()) {
            reader.setMetadataStore(metadata);
            reader.setId(output.toString());
            assertNotNull(metadata.getPixelsPhysicalSizeX(0));
            assertNotNull(metadata.getPixelsPhysicalSizeY(0));
            assertEquals(0.65, metadata.getPixelsPhysicalSizeX(0)
                    .value().doubleValue(), 1.0e-12);
            assertEquals(0.65, metadata.getPixelsPhysicalSizeY(0)
                    .value().doubleValue(), 1.0e-12);
            final Map<String, String> values = metadata
                    .getMapAnnotationValue(0).stream().collect(
                            Collectors.toMap(
                                    pair -> pair.getName(),
                                    pair -> pair.getValue()));
            assertEquals("2", values.get("atlasalign.sourceOriginX"));
            assertEquals("1", values.get("atlasalign.sourceOriginY"));
            assertEquals("crop-local",
                    values.get("atlasalign.maskExtent"));
        }
    }

    @Test
    void omitsInvalidCalibrationFieldsWithoutGuessing() throws Exception {
        final var snapshot = ExportTestFixtures.sourceSnapshot(
                8, 1, 1, 1,
                new CalibrationMetadata(
                        Double.NaN, -3.0, Double.POSITIVE_INFINITY,
                        0.0, "pixel", ""));
        final var source = new ExportTestFixtures.MemoryReader(snapshot);
        final var footprint = ExportTestFixtures.fullFootprint(
                ExportTestFixtures.region(1, "R1"));
        final Path output = temporaryDirectory.resolve(
                "undefined-calibration.ome.tif");

        final var report = exporter.writeSourceCrop(
                output, "undefined calibration", source, footprint,
                () -> false, ignored -> {
                });

        assertTrue(report.warnings().stream().anyMatch(
                warning -> warning.contains("Spatial calibration unit")));
        assertTrue(report.warnings().stream().anyMatch(
                warning -> warning.contains("Time interval")));
        final IMetadata metadata = MetadataTools.createOMEXMLMetadata();
        try (OMETiffReader reader = new OMETiffReader()) {
            reader.setMetadataStore(metadata);
            reader.setId(output.toString());
            assertNull(metadata.getPixelsPhysicalSizeX(0));
            assertNull(metadata.getPixelsPhysicalSizeY(0));
            assertNull(metadata.getPixelsPhysicalSizeZ(0));
            assertNull(metadata.getPixelsTimeIncrement(0));
        }
    }

    @Test
    void malformedNonblankUnitsAreOmittedWithoutBioFormatsFallback()
            throws Exception {
        final var snapshot = ExportTestFixtures.sourceSnapshot(
                8, 1, 1, 1,
                new CalibrationMetadata(
                        1.0, 1.0, 1.0, 2.0,
                        "furlong", "fortnight"));
        final var source = new ExportTestFixtures.MemoryReader(snapshot);
        final var footprint = ExportTestFixtures.fullFootprint(
                ExportTestFixtures.region(1, "R1"));
        final Path output = temporaryDirectory.resolve(
                "unsupported-units.ome.tif");

        final var report = exporter.writeSourceCrop(
                output, "unsupported units", source, footprint,
                () -> false, ignored -> {
                });

        assertTrue(report.warnings().stream().anyMatch(
                warning -> warning.contains("Spatial calibration unit")));
        assertTrue(report.warnings().stream().anyMatch(
                warning -> warning.contains("Time interval")));
        final IMetadata metadata = MetadataTools.createOMEXMLMetadata();
        try (OMETiffReader reader = new OMETiffReader()) {
            reader.setMetadataStore(metadata);
            reader.setId(output.toString());
            assertNull(metadata.getPixelsPhysicalSizeX(0));
            assertNull(metadata.getPixelsTimeIncrement(0));
        }
    }

    @Test
    void selectsBigTiffBeforeClassicTiffOverheadCanCrossFourGib() {
        assertFalse(BioFormatsOmeTiffExporter.requiresBigTiff(
                2_999_999_999L));
        assertTrue(BioFormatsOmeTiffExporter.requiresBigTiff(
                3_000_000_000L));
    }
}
