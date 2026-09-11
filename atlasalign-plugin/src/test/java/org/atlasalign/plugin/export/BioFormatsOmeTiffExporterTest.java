package org.atlasalign.plugin.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ij.ImagePlus;
import ij.ImageStack;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import loci.formats.MetadataTools;
import loci.formats.in.OMETiffReader;
import loci.formats.meta.IMetadata;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.export.SourcePixelReader;
import org.atlasalign.io.imagej.ImagePlusSourcePixelReader;
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
    void selectedRawCropsPreserveNativePixelsAndSourceState() throws Exception {
        assertSelectedCropRoundTrip(false);
    }

    @Test
    void selectedMaskedCropsPreserveNativeInsidePixelsAndSourceState() throws Exception {
        assertSelectedCropRoundTrip(true);
    }

    private void assertSelectedCropRoundTrip(final boolean masked) throws Exception {
        for (final int bitDepth : new int[]{8, 16, 32}) {
            final ImagePlus image = distinctHyperstack(bitDepth);
            final var source = new ImagePlusSourcePixelReader(image);
            final var before = source.snapshot();
            final var colorModel = image.getProcessor().getColorModel();
            final var calibration = image.getCalibration();
            final var selection = new ExportSelection(List.of(1, 3), 3, 2);
            final var footprint = ExportTestFixtures.irregularFootprint(
                    ExportTestFixtures.region(1, "R1"));
            final var progress = new ArrayList<Double>();
            final Path output = temporaryDirectory.resolve(
                    "selected-" + masked + "-" + bitDepth + ".ome.tif");

            if (masked) {
                exporter.writeMaskedSourceCrop(output, "selected", source,
                        footprint, selection, () -> false, progress::add);
            } else {
                exporter.writeSourceCrop(output, "selected", source,
                        footprint, selection, () -> false, progress::add);
            }

            final IMetadata metadata = MetadataTools.createOMEXMLMetadata();
            try (OMETiffReader reader = new OMETiffReader()) {
                reader.setMetadataStore(metadata);
                reader.setId(output.toString());
                assertEquals(4, reader.getSizeX());
                assertEquals(3, reader.getSizeY());
                assertEquals(2, reader.getSizeC());
                assertEquals(1, reader.getSizeZ());
                assertEquals(1, reader.getSizeT());
                assertEquals(2, reader.getImageCount());
                assertEquals(bitDepth, reader.getBitsPerPixel());
                assertEquals("source-plane-0", metadata.getChannelName(0, 0));
                assertEquals("source-plane-2", metadata.getChannelName(0, 1));
                assertEquals(0.4, metadata.getPixelsPhysicalSizeX(0).value().doubleValue());
                assertEquals(0.7, metadata.getPixelsPhysicalSizeY(0).value().doubleValue());
                assertEquals(2.5, metadata.getPixelsPhysicalSizeZ(0).value().doubleValue());
                assertEquals(4.0, metadata.getPixelsTimeIncrement(0).value().doubleValue());
                final Map<String, String> values = metadata.getMapAnnotationValue(0)
                        .stream().collect(Collectors.toMap(
                                pair -> pair.getName(), pair -> pair.getValue()));
                assertEquals("2", values.get("atlasalign.sourceOriginX"));
                assertEquals("1", values.get("atlasalign.sourceOriginY"));
                assertEquals("selected-source-plane", values.get("atlasalign.exportScope"));
                assertEquals("1", values.get("atlasalign.sourceIndexBase"));
                assertEquals("0", values.get("atlasalign.outputIndexBase"));
                assertEquals("4", values.get("atlasalign.sourceSizeC"));
                assertEquals("3", values.get("atlasalign.sourceSizeZ"));
                assertEquals("2", values.get("atlasalign.sourceSizeT"));
                assertEquals("1,3", values.get("atlasalign.sourceChannels"));
                assertEquals("3", values.get("atlasalign.sourceSlice"));
                assertEquals("2", values.get("atlasalign.sourceFrame"));
                assertEquals("false", values.get("imagej.planeLabel.0.present"));
                assertEquals("", values.get("imagej.planeLabel.0.value"));
                assertEquals("true", values.get("imagej.planeLabel.1.present"));
                assertEquals("", values.get("imagej.planeLabel.1.value"));
                for (int plane = 0; plane < 2; plane++) {
                    final int sourceChannel = selection.channels().get(plane);
                    assertEquals(plane, metadata.getPlaneTheC(0, plane).getValue());
                    assertEquals(0, metadata.getPlaneTheZ(0, plane).getValue());
                    assertEquals(0, metadata.getPlaneTheT(0, plane).getValue());
                    assertEquals(5.0, metadata.getPlanePositionZ(0, plane).value().doubleValue());
                    assertEquals(4.0, metadata.getPlaneDeltaT(0, plane).value().doubleValue());
                    final String prefix = "atlasalign.outputPlane." + plane + ".";
                    assertEquals(Integer.toString(sourceChannel), values.get(prefix + "sourceChannel"));
                    assertEquals("3", values.get(prefix + "sourceSlice"));
                    assertEquals("2", values.get(prefix + "sourceFrame"));
                    assertEquals(Integer.toString(21 + 2 * plane), values.get(prefix + "sourceStackIndex"));
                    final Object sourcePixels = image.getStack().getPixels(
                            image.getStackIndex(sourceChannel, 3, 2));
                    final ByteBuffer actual = ByteBuffer.wrap(reader.openBytes(plane))
                            .order(reader.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                    for (int y = 0; y < 3; y++) {
                        for (int x = 0; x < 4; x++) {
                            final int cropIndex = y * 4 + x;
                            final int sourceIndex = (y + 1) * 8 + x + 2;
                            final boolean inside = !masked || footprint.cropMask().get(cropIndex);
                            if (bitDepth == 8) {
                                assertEquals(inside ? ((byte[]) sourcePixels)[sourceIndex] : (byte) 0,
                                        actual.get());
                            } else if (bitDepth == 16) {
                                assertEquals(inside ? ((short[]) sourcePixels)[sourceIndex] & 0xffff : 0,
                                        actual.getShort() & 0xffff);
                            } else {
                                assertEquals(inside ? Float.floatToRawIntBits(
                                        ((float[]) sourcePixels)[sourceIndex]) : 0, actual.getInt());
                            }
                        }
                    }
                }
            }
            assertEquals(List.of(0.5, 1.0), progress);
            assertEquals(before, source.snapshot());
            assertEquals(2, image.getC());
            assertEquals(1, image.getZ());
            assertEquals(1, image.getT());
            assertEquals(3.0, image.getDisplayRangeMin());
            assertEquals(99.0, image.getDisplayRangeMax());
            assertSame(colorModel, image.getProcessor().getColorModel());
            assertSame(calibration, image.getCalibration());
        }
    }

    private static ImagePlus distinctHyperstack(final int bitDepth) {
        final ImageStack stack = new ImageStack(8, 6);
        for (int plane = 0; plane < 24; plane++) {
            final Object pixels;
            if (bitDepth == 8) {
                final byte[] values = new byte[48];
                for (int index = 0; index < values.length; index++) {
                    values[index] = (byte) (plane * 7 + index);
                }
                pixels = values;
            } else if (bitDepth == 16) {
                final short[] values = new short[48];
                for (int index = 0; index < values.length; index++) {
                    values[index] = (short) (40_000 + plane * 97 + index);
                }
                pixels = values;
            } else {
                final float[] values = new float[48];
                for (int index = 0; index < values.length; index++) {
                    values[index] = Float.intBitsToFloat(0x3f000000 + plane * 1_024 + index);
                }
                values[10] = Float.intBitsToFloat(0x7fc01234 + plane);
                values[13] = -0.0f;
                pixels = values;
            }
            stack.addSlice(plane == 20 ? null : plane == 22 ? "" : "source-plane-" + plane, pixels);
        }
        final ImagePlus image = new ImagePlus("C4-Z3-T2", stack);
        image.setDimensions(4, 3, 2);
        image.setOpenAsHyperStack(true);
        image.setPosition(2, 1, 1);
        image.setDisplayRange(3, 99);
        image.getCalibration().pixelWidth = 0.4;
        image.getCalibration().pixelHeight = 0.7;
        image.getCalibration().pixelDepth = 2.5;
        image.getCalibration().frameInterval = 4;
        image.getCalibration().setUnit("µm");
        image.getCalibration().setTimeUnit("s");
        return image;
    }

    @Test
    void exportSelectionIsImmutableAndRejectsInvalidIndicesBeforeOutputCreation() {
        final var source = new ExportTestFixtures.MemoryReader(
                ExportTestFixtures.sourceSnapshot(8, 4, 3, 2));
        final var input = new ArrayList<>(List.of(1, 3));
        final var selection = new ExportSelection(input, 3, 2);
        input.clear();
        assertEquals(List.of(1, 3), selection.channels());
        assertThrows(UnsupportedOperationException.class, () -> selection.channels().add(4));
        assertThrows(IllegalArgumentException.class, () -> new ExportSelection(List.of(), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExportSelection(List.of(0), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExportSelection(List.of(2, 2), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExportSelection(List.of(3, 1), 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExportSelection(List.of(1), 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExportSelection(List.of(1), 1, -1));
        assertEquals(new ExportSelection(List.of(1, 2, 3, 4), 3, 2),
                ExportSelection.allChannels(source.snapshot().metadata(), 3, 2));
        final var footprint = ExportTestFixtures.fullFootprint(ExportTestFixtures.region(1, "R1"));
        for (final var invalid : List.of(new ExportSelection(List.of(5), 1, 1),
                new ExportSelection(List.of(1), 4, 1), new ExportSelection(List.of(1), 1, 3))) {
            final Path output = temporaryDirectory.resolve("invalid.ome.tif");
            assertThrows(IllegalArgumentException.class, () -> exporter.writeSourceCrop(
                    output, "invalid", source, footprint, invalid, () -> false, ignored -> {}));
            assertThrows(IllegalArgumentException.class, () -> exporter.writeMaskedSourceCrop(
                    output, "invalid", source, footprint, invalid, () -> false, ignored -> {}));
            assertFalse(java.nio.file.Files.exists(output));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ExportSelection.allChannels(source.snapshot().metadata(), 4, 2));
    }

    @Test
    void selectsBigTiffBeforeClassicTiffOverheadCanCrossFourGib() {
        assertFalse(BioFormatsOmeTiffExporter.requiresBigTiff(
                2_999_999_999L));
        assertTrue(BioFormatsOmeTiffExporter.requiresBigTiff(
                3_000_000_000L));
    }
}
