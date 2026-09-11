package org.atlasalign.plugin.export;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.IMetadata;
import loci.formats.out.OMETiffWriter;
import loci.formats.out.TiffWriter;
import ome.units.quantity.Length;
import ome.units.quantity.Time;
import ome.xml.model.MapPair;
import ome.xml.model.enums.DimensionOrder;
import ome.xml.model.enums.PixelType;
import ome.xml.model.primitives.NonNegativeInteger;
import ome.xml.model.primitives.PositiveInteger;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.application.export.SourcePixelReader;
import org.atlasalign.core.CalibrationFieldStatus;
import org.atlasalign.core.CalibrationMetadata;
import org.atlasalign.core.SourceImageMetadata;
import org.atlasalign.core.StackPlaneLabel;

/** Lossless, plane-wise OME-TIFF writing through Bio-Formats 8.5.0. */
public final class BioFormatsOmeTiffExporter {

    public static final String REQUIRED_VERSION = "8.5.0";
    // Leave room for LZW expansion, IFDs, and OME-XML instead of assuming the
    // uncompressed payload can safely approach the 4 GiB classic-TIFF limit.
    private static final long CLASSIC_TIFF_SAFE_PAYLOAD_LIMIT =
            3_000_000_000L;
    private static final int FULL_MASK_BUFFER_TARGET_BYTES =
            16 * 1024 * 1024;
    private final int fullMaskBufferTargetBytes;

    public BioFormatsOmeTiffExporter() {
        this(FULL_MASK_BUFFER_TARGET_BYTES);
    }

    BioFormatsOmeTiffExporter(final int fullMaskBufferTargetBytes) {
        if (fullMaskBufferTargetBytes <= 0) {
            throw new IllegalArgumentException(
                    "Full-mask write buffer must be positive");
        }
        this.fullMaskBufferTargetBytes = fullMaskBufferTargetBytes;
    }

    public record WriteReport(
            String bioFormatsVersion,
            boolean bigTiff,
            List<String> warnings) {
        public WriteReport {
            bioFormatsVersion = Objects.requireNonNull(
                    bioFormatsVersion, "bioFormatsVersion");
            warnings = List.copyOf(Objects.requireNonNull(
                    warnings, "warnings"));
        }
    }

    public String verifyRuntimeVersion() {
        final String version = Objects.requireNonNullElse(
                FormatTools.VERSION, "unknown");
        if (!REQUIRED_VERSION.equals(version)) {
            throw new IllegalStateException(
                    "ROI export requires Fiji Bio-Formats "
                            + REQUIRED_VERSION + ", but this runtime reports "
                            + version);
        }
        return version;
    }

    /** Historical overload: exports every source C/Z/T plane. */
    public WriteReport writeSourceCrop(
            final Path destination,
            final String imageName,
            final SourcePixelReader reader,
            final SourceRegionFootprint footprint,
            final BooleanSupplier cancelled,
            final DoubleConsumer progress) {
        return writeCrop(destination, imageName, reader, footprint,
                null, false, cancelled, progress);
    }

    /** Exports the chosen source channels at one source Z/T as output C/1/1. */
    public WriteReport writeSourceCrop(
            final Path destination,
            final String imageName,
            final SourcePixelReader reader,
            final SourceRegionFootprint footprint,
            final ExportSelection selection,
            final BooleanSupplier cancelled,
            final DoubleConsumer progress) {
        return writeCrop(destination, imageName, reader, footprint,
                Objects.requireNonNull(selection, "selection"), false,
                cancelled, progress);
    }

    /**
     * Historical overload: writes a derived C/Z/T-preserving crop whose ROI
     * pixels are exact source values and non-ROI pixels are positive zero.
     */
    public WriteReport writeMaskedSourceCrop(
            final Path destination,
            final String imageName,
            final SourcePixelReader reader,
            final SourceRegionFootprint footprint,
            final BooleanSupplier cancelled,
            final DoubleConsumer progress) {
        return writeCrop(destination, imageName, reader, footprint,
                null, true, cancelled, progress);
    }

    /** Exports a derived masked crop of the chosen source C/Z/T plane. */
    public WriteReport writeMaskedSourceCrop(
            final Path destination,
            final String imageName,
            final SourcePixelReader reader,
            final SourceRegionFootprint footprint,
            final ExportSelection selection,
            final BooleanSupplier cancelled,
            final DoubleConsumer progress) {
        return writeCrop(destination, imageName, reader, footprint,
                Objects.requireNonNull(selection, "selection"), true,
                cancelled, progress);
    }

    private WriteReport writeCrop(
            final Path destination,
            final String imageName,
            final SourcePixelReader reader,
            final SourceRegionFootprint footprint,
            final ExportSelection selection,
            final boolean masked,
            final BooleanSupplier cancelled,
            final DoubleConsumer progress) {
        final String version = verifyRuntimeVersion();
        final Path output = Objects.requireNonNull(
                destination, "destination").toAbsolutePath().normalize();
        final SourcePixelReader source = Objects.requireNonNull(
                reader, "reader");
        final SourceRegionFootprint region = Objects.requireNonNull(
                footprint, "footprint");
        final SourceImageMetadata metadata = source.snapshot().metadata();
        requireMatchingSourceDimensions(metadata, region);
        if (selection != null) {
            selection.validateAgainst(metadata);
        }
        Objects.requireNonNull(progress, "progress");
        checkCancelled(cancelled);
        final List<Integer> channels = selection == null
                ? java.util.stream.IntStream.rangeClosed(1, metadata.channels())
                        .boxed().toList()
                : selection.channels();
        final int firstSlice = selection == null ? 1 : selection.slice();
        final int lastSlice = selection == null ? metadata.slices() : firstSlice;
        final int firstFrame = selection == null ? 1 : selection.frame();
        final int lastFrame = selection == null ? metadata.frames() : firstFrame;
        final long planes = Math.multiplyExact((long) channels.size(),
                Math.multiplyExact((long) lastSlice - firstSlice + 1,
                        (long) lastFrame - firstFrame + 1));
        final long estimated = Math.multiplyExact(
                Math.multiplyExact((long) region.bounds().pixelCount(),
                        bytesPerPixel(metadata.bitDepth())), planes);
        final boolean bigTiff = requiresBigTiff(estimated);
        final List<String> warnings = new ArrayList<>();
        final IMetadata ome = sourceMetadata(
                Objects.requireNonNull(imageName, "imageName"), metadata,
                region, warnings, masked ? "masked-source-crop" : "raw-source-crop",
                masked, selection);
        final BitSet mask = masked ? region.cropMask() : null;

        try (OMETiffWriter writer = configuredWriter(output, ome, bigTiff)) {
            final int totalPlanes = Math.toIntExact(planes);
            int planeIndex = 0;
            for (int frame = firstFrame; frame <= lastFrame; frame++) {
                for (int slice = firstSlice; slice <= lastSlice; slice++) {
                    for (final int channel : channels) {
                        checkCancelled(cancelled);
                        final SourcePixelReader.PixelBlock block =
                                source.readPlane(channel, slice, frame,
                                        region.bounds());
                        if (block.bitDepth() != metadata.bitDepth()) {
                            throw new IllegalStateException(
                                    "Source pixel type changed during export");
                        }
                        requireMatchingBlockDimensions(block, region.bounds());
                        writer.saveBytes(planeIndex, littleEndianBytes(
                                masked ? maskedBlock(block, mask) : block));
                        planeIndex++;
                        progress.accept((double) planeIndex / totalPlanes);
                    }
                }
            }
        } catch (final FormatException | IOException error) {
            throw new IllegalStateException(
                    "Could not write " + (masked ? "derived masked" : "lossless")
                            + " OME-TIFF " + output.getFileName(), error);
        }
        return new WriteReport(version, bigTiff, warnings);
    }

    public WriteReport writeMask(
            final Path destination,
            final String imageName,
            final SourceRegionFootprint footprint) {
        return writeMask(destination, imageName, footprint, null);
    }

    public WriteReport writeMask(
            final Path destination,
            final String imageName,
            final SourceRegionFootprint footprint,
            final SourceImageMetadata sourceMetadata) {
        final String version = verifyRuntimeVersion();
        final SourceRegionFootprint region = Objects.requireNonNull(
                footprint, "footprint");
        if (sourceMetadata != null) {
            requireMatchingSourceDimensions(sourceMetadata, region);
        }
        final List<String> warnings = new ArrayList<>();
        final IMetadata ome = maskMetadata(
                Objects.requireNonNull(imageName, "imageName"),
                region.bounds().width(), region.bounds().height(),
                region.bounds().minimumX(), region.bounds().minimumY(),
                "crop-local", sourceMetadata, warnings);
        final Path output = Objects.requireNonNull(
                destination, "destination").toAbsolutePath().normalize();
        final boolean bigTiff = requiresBigTiff(
                region.bounds().pixelCount());
        try (OMETiffWriter writer = configuredWriter(
                output, ome, bigTiff)) {
            writer.saveBytes(0, region.maskBytes());
        } catch (final FormatException | IOException error) {
            throw new IllegalStateException(
                    "Could not write source-space region mask "
                            + output.getFileName(), error);
        }
        return new WriteReport(version, bigTiff, warnings);
    }

    public WriteReport writeFullSourceMask(
            final Path destination,
            final String imageName,
            final SourceRegionFootprint footprint,
            final SourceImageMetadata sourceMetadata,
            final BooleanSupplier cancelled) {
        final String version = verifyRuntimeVersion();
        final SourceRegionFootprint region = Objects.requireNonNull(
                footprint, "footprint");
        final SourceImageMetadata source = Objects.requireNonNull(
                sourceMetadata, "sourceMetadata");
        requireMatchingSourceDimensions(source, region);
        checkCancelled(cancelled);
        final List<String> warnings = new ArrayList<>();
        final IMetadata ome = maskMetadata(
                Objects.requireNonNull(imageName, "imageName"),
                region.sourceWidth(), region.sourceHeight(), 0, 0,
                "full-source", source, warnings);
        final long estimated = Math.multiplyExact(
                (long) region.sourceWidth(), region.sourceHeight());
        final boolean bigTiff = requiresBigTiff(estimated);
        final Path output = Objects.requireNonNull(
                destination, "destination").toAbsolutePath().normalize();
        try (OMETiffWriter writer = configuredWriter(
                output, ome, bigTiff, false)) {
            final int rowsPerWrite = Math.max(1, Math.min(
                    region.sourceHeight(),
                    fullMaskBufferTargetBytes / region.sourceWidth()));
            for (int sourceY = 0;
                    sourceY < region.sourceHeight();
                    sourceY += rowsPerWrite) {
                checkCancelled(cancelled);
                final int rowCount = Math.min(rowsPerWrite,
                        region.sourceHeight() - sourceY);
                writer.saveBytes(0,
                        region.fullSourceMaskRows(sourceY, rowCount),
                        0, sourceY, region.sourceWidth(), rowCount);
            }
        } catch (final FormatException | IOException error) {
            throw new IllegalStateException(
                    "Could not write full-source region mask "
                            + output.getFileName(), error);
        }
        return new WriteReport(version, bigTiff, warnings);
    }

    private static OMETiffWriter configuredWriter(
            final Path destination,
            final IMetadata metadata,
            final boolean bigTiff)
            throws FormatException, IOException {
        return configuredWriter(destination, metadata, bigTiff, true);
    }

    private static OMETiffWriter configuredWriter(
            final Path destination,
            final IMetadata metadata,
            final boolean bigTiff,
            final boolean sequential)
            throws FormatException, IOException {
        final OMETiffWriter writer = new OMETiffWriter();
        writer.setMetadataRetrieve(metadata);
        writer.setWriteSequentially(sequential);
        writer.setCompression(TiffWriter.COMPRESSION_LZW);
        writer.setBigTiff(bigTiff);
        writer.setId(destination.toString());
        return writer;
    }

    private static IMetadata sourceMetadata(
            final String imageName,
            final SourceImageMetadata source,
            final SourceRegionFootprint footprint,
            final List<String> warnings,
            final String artifactKind,
            final boolean derived,
            final ExportSelection selection) {
        final IMetadata metadata = MetadataTools.createOMEXMLMetadata();
        final int channelCount = selection == null
                ? source.channels() : selection.channels().size();
        populatePixels(metadata, imageName, footprint.bounds().width(),
                footprint.bounds().height(), channelCount,
                selection == null ? source.slices() : 1,
                selection == null ? source.frames() : 1,
                pixelType(source.bitDepth()));
        for (int channel = 0; channel < channelCount; channel++) {
            final int sourceChannel = selection == null
                    ? channel : selection.channels().get(channel) - 1;
            metadata.setChannelID("Channel:0:" + channel, 0, channel);
            metadata.setChannelName(source.channelLabels().get(sourceChannel),
                    0, channel);
            metadata.setChannelSamplesPerPixel(new PositiveInteger(1),
                    0, channel);
        }
        final CalibrationMetadata calibration = source.calibration();
        applySpatialCalibration(metadata, calibration, warnings);
        applyTimeCalibration(metadata, calibration, warnings);
        final List<MapPair> values = new ArrayList<>();
        values.add(new MapPair("atlasalign.sourceOriginX",
                Integer.toString(footprint.bounds().minimumX())));
        values.add(new MapPair("atlasalign.sourceOriginY",
                Integer.toString(footprint.bounds().minimumY())));
        values.add(new MapPair("atlasalign.dimensionOrder", "XYCZT"));
        values.add(new MapPair("atlasalign.artifactKind", artifactKind));
        values.add(new MapPair("atlasalign.derived",
                Boolean.toString(derived)));
        if (derived) {
            values.add(new MapPair("atlasalign.outsideRegionPixels",
                    "numeric-positive-zero"));
            values.add(new MapPair("atlasalign.insideRegionPixels",
                    "exact-source-values"));
        }
        if (selection != null) {
            addSelectionMetadata(metadata, source, selection, values);
        }
        final int planeCount = selection == null
                ? source.stackPlaneLabels().size() : channelCount;
        for (int index = 0; index < planeCount; index++) {
            final int sourcePlane = selection == null ? index
                    : sourcePlaneIndex(source, selection.channels().get(index),
                            selection.slice(), selection.frame());
            final StackPlaneLabel label = source.stackPlaneLabels().get(sourcePlane);
            values.add(new MapPair("imagej.planeLabel." + index
                    + ".present", Boolean.toString(label.present())));
            values.add(new MapPair("imagej.planeLabel." + index
                    + ".value", label.value()));
        }
        metadata.setMapAnnotationID("Annotation:0", 0);
        metadata.setMapAnnotationNamespace(
                "urn:atlasalign:source-crop-metadata:v1", 0);
        metadata.setMapAnnotationValue(values, 0);
        metadata.setImageAnnotationRef("Annotation:0", 0, 0);
        return metadata;
    }

    private static void addSelectionMetadata(
            final IMetadata metadata,
            final SourceImageMetadata source,
            final ExportSelection selection,
            final List<MapPair> values) {
        values.add(new MapPair("atlasalign.exportScope", "selected-source-plane"));
        values.add(new MapPair("atlasalign.sourceIndexBase", "1"));
        values.add(new MapPair("atlasalign.outputIndexBase", "0"));
        values.add(new MapPair("atlasalign.sourceSizeC", Integer.toString(source.channels())));
        values.add(new MapPair("atlasalign.sourceSizeZ", Integer.toString(source.slices())));
        values.add(new MapPair("atlasalign.sourceSizeT", Integer.toString(source.frames())));
        values.add(new MapPair("atlasalign.sourceChannels", selection.channels().stream()
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(","))));
        values.add(new MapPair("atlasalign.sourceSlice", Integer.toString(selection.slice())));
        values.add(new MapPair("atlasalign.sourceFrame", Integer.toString(selection.frame())));
        final Length spacingZ = metadata.getPixelsPhysicalSizeZ(0);
        final Time interval = metadata.getPixelsTimeIncrement(0);
        for (int outputPlane = 0; outputPlane < selection.channels().size(); outputPlane++) {
            final int sourceChannel = selection.channels().get(outputPlane);
            final String prefix = "atlasalign.outputPlane." + outputPlane + ".";
            values.add(new MapPair(prefix + "sourceChannel", Integer.toString(sourceChannel)));
            values.add(new MapPair(prefix + "sourceSlice", Integer.toString(selection.slice())));
            values.add(new MapPair(prefix + "sourceFrame", Integer.toString(selection.frame())));
            values.add(new MapPair(prefix + "sourceStackIndex", Integer.toString(
                    sourcePlaneIndex(source, sourceChannel,
                            selection.slice(), selection.frame()) + 1)));
            if (spacingZ != null) {
                metadata.setPlanePositionZ(new Length(
                        (selection.slice() - 1) * spacingZ.value().doubleValue(),
                        spacingZ.unit()), 0, outputPlane);
            }
            if (interval != null) {
                metadata.setPlaneDeltaT(new Time(
                        (selection.frame() - 1) * interval.value().doubleValue(),
                        interval.unit()), 0, outputPlane);
            }
        }
    }

    /** ImageJ source stack order, with a zero-based returned index. */
    private static int sourcePlaneIndex(final SourceImageMetadata source,
            final int channel, final int slice, final int frame) {
        return Math.toIntExact(((long) (frame - 1) * source.slices() + slice - 1)
                * source.channels() + channel - 1);
    }

    private static IMetadata maskMetadata(
            final String imageName,
            final int width,
            final int height,
            final int sourceOriginX,
            final int sourceOriginY,
            final String extent,
            final SourceImageMetadata source,
            final List<String> warnings) {
        final IMetadata metadata = MetadataTools.createOMEXMLMetadata();
        populatePixels(metadata, imageName, width, height,
                1, 1, 1, PixelType.UINT8);
        metadata.setChannelID("Channel:0:0", 0, 0);
        metadata.setChannelName("AtlasAlign binary mask", 0, 0);
        metadata.setChannelSamplesPerPixel(new PositiveInteger(1), 0, 0);
        if (source != null) {
            applyMaskSpatialCalibration(
                    metadata, source.calibration(), warnings);
        }
        final List<MapPair> values = List.of(
                new MapPair("atlasalign.sourceOriginX",
                        Integer.toString(sourceOriginX)),
                new MapPair("atlasalign.sourceOriginY",
                        Integer.toString(sourceOriginY)),
                new MapPair("atlasalign.maskExtent", extent),
                new MapPair("atlasalign.insideMaskValue", "255"),
                new MapPair("atlasalign.outsideMaskValue", "0"),
                new MapPair("atlasalign.derived", "true"));
        metadata.setMapAnnotationID("Annotation:0", 0);
        metadata.setMapAnnotationNamespace(
                "urn:atlasalign:source-mask-metadata:v1", 0);
        metadata.setMapAnnotationValue(values, 0);
        metadata.setImageAnnotationRef("Annotation:0", 0, 0);
        return metadata;
    }

    private static void applyMaskSpatialCalibration(
            final IMetadata metadata,
            final CalibrationMetadata calibration,
            final List<String> warnings) {
        if (calibration.spatialUnitStatus()
                != CalibrationFieldStatus.VALID) {
            warnings.add("Spatial calibration unit is undefined; physical mask pixel sizes were omitted from OME metadata.");
            return;
        }
        final Length x = calibration.pixelWidthStatus()
                == CalibrationFieldStatus.VALID
                ? FormatTools.getPhysicalSizeX(
                        calibration.pixelWidth(), calibration
                                .canonicalSpatialUnit().orElseThrow())
                : null;
        final Length y = calibration.pixelHeightStatus()
                == CalibrationFieldStatus.VALID
                ? FormatTools.getPhysicalSizeY(
                        calibration.pixelHeight(), calibration
                                .canonicalSpatialUnit().orElseThrow())
                : null;
        if (x != null) {
            metadata.setPixelsPhysicalSizeX(x, 0);
        } else {
            warnings.add("Pixel width is invalid or unsupported; mask PhysicalSizeX was omitted.");
        }
        if (y != null) {
            metadata.setPixelsPhysicalSizeY(y, 0);
        } else {
            warnings.add("Pixel height is invalid or unsupported; mask PhysicalSizeY was omitted.");
        }
    }

    private static void requireMatchingSourceDimensions(
            final SourceImageMetadata source,
            final SourceRegionFootprint footprint) {
        if (source.width() != footprint.sourceWidth()
                || source.height() != footprint.sourceHeight()) {
            throw new IllegalArgumentException(
                    "Source metadata dimensions do not match the region footprint");
        }
    }

    private static void requireMatchingBlockDimensions(
            final SourcePixelReader.PixelBlock block,
            final SourcePixelReader.Bounds bounds) {
        if (block.width() != bounds.width()
                || block.height() != bounds.height()) {
            throw new IllegalStateException(
                    "Source pixel block dimensions changed during export");
        }
    }

    private static void populatePixels(
            final IMetadata metadata,
            final String imageName,
            final int sizeX,
            final int sizeY,
            final int sizeC,
            final int sizeZ,
            final int sizeT,
            final PixelType pixelType) {
        metadata.setImageID("Image:0", 0);
        metadata.setImageName(imageName, 0);
        metadata.setPixelsID("Pixels:0", 0);
        metadata.setPixelsDimensionOrder(DimensionOrder.XYCZT, 0);
        metadata.setPixelsType(pixelType, 0);
        metadata.setPixelsBigEndian(false, 0);
        metadata.setPixelsInterleaved(false, 0);
        metadata.setPixelsSizeX(new PositiveInteger(sizeX), 0);
        metadata.setPixelsSizeY(new PositiveInteger(sizeY), 0);
        metadata.setPixelsSizeC(new PositiveInteger(sizeC), 0);
        metadata.setPixelsSizeZ(new PositiveInteger(sizeZ), 0);
        metadata.setPixelsSizeT(new PositiveInteger(sizeT), 0);
        int plane = 0;
        for (int frame = 0; frame < sizeT; frame++) {
            for (int slice = 0; slice < sizeZ; slice++) {
                for (int channel = 0; channel < sizeC; channel++) {
                    metadata.setPlaneTheC(
                            new NonNegativeInteger(channel), 0, plane);
                    metadata.setPlaneTheZ(
                            new NonNegativeInteger(slice), 0, plane);
                    metadata.setPlaneTheT(
                            new NonNegativeInteger(frame), 0, plane);
                    plane++;
                }
            }
        }
    }

    private static void applySpatialCalibration(
            final IMetadata metadata,
            final CalibrationMetadata calibration,
            final List<String> warnings) {
        if (calibration.spatialUnitStatus()
                != CalibrationFieldStatus.VALID) {
            warnings.add("Spatial calibration unit is undefined; physical pixel sizes were omitted from OME metadata.");
            return;
        }
        final Length x = calibration.pixelWidthStatus()
                == CalibrationFieldStatus.VALID
                ? FormatTools.getPhysicalSizeX(
                        calibration.pixelWidth(), calibration
                                .canonicalSpatialUnit().orElseThrow())
                : null;
        final Length y = calibration.pixelHeightStatus()
                == CalibrationFieldStatus.VALID
                ? FormatTools.getPhysicalSizeY(
                        calibration.pixelHeight(), calibration
                                .canonicalSpatialUnit().orElseThrow())
                : null;
        final Length z = calibration.pixelDepthStatus()
                == CalibrationFieldStatus.VALID
                ? FormatTools.getPhysicalSizeZ(
                        calibration.pixelDepth(), calibration
                                .canonicalSpatialUnit().orElseThrow())
                : null;
        if (x != null) {
            metadata.setPixelsPhysicalSizeX(x, 0);
        } else {
            warnings.add("Pixel width is invalid or uses an unsupported unit; PhysicalSizeX was omitted.");
        }
        if (y != null) {
            metadata.setPixelsPhysicalSizeY(y, 0);
        } else {
            warnings.add("Pixel height is invalid or uses an unsupported unit; PhysicalSizeY was omitted.");
        }
        if (z != null) {
            metadata.setPixelsPhysicalSizeZ(z, 0);
        } else {
            warnings.add("Z spacing is invalid or uses an unsupported unit; PhysicalSizeZ was omitted.");
        }
    }

    private static void applyTimeCalibration(
            final IMetadata metadata,
            final CalibrationMetadata calibration,
            final List<String> warnings) {
        if (calibration.frameIntervalStatus()
                != CalibrationFieldStatus.VALID
                || calibration.timeUnitStatus()
                != CalibrationFieldStatus.VALID) {
            warnings.add("Time interval or unit is undefined; TimeIncrement was omitted from OME metadata.");
            return;
        }
        final Time time = FormatTools.getTime(
                calibration.frameInterval(),
                calibration.canonicalTimeUnit().orElseThrow());
        if (time == null) {
            warnings.add("Time unit is unsupported; TimeIncrement was omitted from OME metadata.");
            return;
        }
        metadata.setPixelsTimeIncrement(time, 0);
    }

    private static PixelType pixelType(final int bitDepth) {
        return switch (bitDepth) {
            case 8 -> PixelType.UINT8;
            case 16 -> PixelType.UINT16;
            case 32 -> PixelType.FLOAT;
            default -> throw new IllegalArgumentException(String.format(
                    Locale.ROOT,
                    "Unsupported source bit depth for OME export: %d",
                    bitDepth));
        };
    }

    private static int bytesPerPixel(final int bitDepth) {
        return switch (bitDepth) {
            case 8 -> 1;
            case 16 -> 2;
            case 32 -> 4;
            default -> throw new IllegalArgumentException(
                    "Unsupported source bit depth for OME export");
        };
    }

    static boolean requiresBigTiff(final long uncompressedPayloadBytes) {
        if (uncompressedPayloadBytes < 0) {
            throw new IllegalArgumentException(
                    "Uncompressed OME payload size must be non-negative");
        }
        return uncompressedPayloadBytes
                >= CLASSIC_TIFF_SAFE_PAYLOAD_LIMIT;
    }

    static byte[] littleEndianBytes(
            final SourcePixelReader.PixelBlock block) {
        if (block instanceof SourcePixelReader.ByteBlock bytes) {
            return bytes.pixels();
        }
        if (block instanceof SourcePixelReader.UnsignedShortBlock shorts) {
            final short[] pixels = shorts.pixels();
            final byte[] encoded = new byte[pixels.length * 2];
            for (int index = 0; index < pixels.length; index++) {
                final int value = pixels[index] & 0xffff;
                encoded[index * 2] = (byte) value;
                encoded[index * 2 + 1] = (byte) (value >>> 8);
            }
            return encoded;
        }
        if (block instanceof SourcePixelReader.FloatBlock floats) {
            final float[] pixels = floats.pixels();
            final byte[] encoded = new byte[pixels.length * 4];
            for (int index = 0; index < pixels.length; index++) {
                final int bits = Float.floatToRawIntBits(pixels[index]);
                encoded[index * 4] = (byte) bits;
                encoded[index * 4 + 1] = (byte) (bits >>> 8);
                encoded[index * 4 + 2] = (byte) (bits >>> 16);
                encoded[index * 4 + 3] = (byte) (bits >>> 24);
            }
            return encoded;
        }
        throw new IllegalArgumentException(
                "Unknown source pixel block type: " + block.getClass());
    }

    static SourcePixelReader.PixelBlock maskedBlock(
            final SourcePixelReader.PixelBlock block,
            final BitSet mask) {
        final BitSet checked = (BitSet) Objects.requireNonNull(
                mask, "mask").clone();
        if (checked.length() > block.pixelCount()) {
            throw new IllegalArgumentException(
                    "Mask exceeds the source crop pixel block");
        }
        if (block instanceof SourcePixelReader.ByteBlock bytes) {
            final byte[] pixels = bytes.pixels();
            zeroOutside(pixels.length, checked,
                    index -> pixels[index] = 0);
            return new SourcePixelReader.ByteBlock(
                    block.width(), block.height(), pixels);
        }
        if (block instanceof SourcePixelReader.UnsignedShortBlock shorts) {
            final short[] pixels = shorts.pixels();
            zeroOutside(pixels.length, checked,
                    index -> pixels[index] = 0);
            return new SourcePixelReader.UnsignedShortBlock(
                    block.width(), block.height(), pixels);
        }
        if (block instanceof SourcePixelReader.FloatBlock floats) {
            final float[] pixels = floats.pixels();
            zeroOutside(pixels.length, checked,
                    index -> pixels[index] = 0.0f);
            return new SourcePixelReader.FloatBlock(
                    block.width(), block.height(), pixels);
        }
        throw new IllegalArgumentException(
                "Unknown source pixel block type: " + block.getClass());
    }

    private static void zeroOutside(
            final int length,
            final BitSet inside,
            final java.util.function.IntConsumer zero) {
        for (int index = inside.nextClearBit(0);
                index >= 0 && index < length;
                index = inside.nextClearBit(index + 1)) {
            zero.accept(index);
        }
    }

    private static void checkCancelled(
            final BooleanSupplier cancellation) {
        if (Objects.requireNonNull(cancellation, "cancelled")
                .getAsBoolean()) {
            throw new ExportCancelledException();
        }
    }
}
