package org.atlasalign.plugin.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import org.atlasalign.application.export.SourcePixelReader;
import org.atlasalign.application.roi.ManualRoiFootprint;
import org.atlasalign.application.roi.ReviewerRoi;
import org.atlasalign.core.SourceImageSnapshot;

/**
 * Atomic exact-polygon export. Manual reviewer geometry is rasterized directly
 * in untouched level-0 source coordinates and never enters an atlas warp.
 */
public final class ManualRoiExportService {

    public static final String SCHEMA =
            "atlasalign-manual-roi-batch-export-v1";

    public record Result(
            Path publishedDirectory,
            List<String> fileNames,
            List<String> warnings) {
        public Result {
            publishedDirectory = Objects.requireNonNull(
                    publishedDirectory, "publishedDirectory");
            fileNames = List.copyOf(fileNames);
            warnings = List.copyOf(warnings);
        }
    }

    /**
     * Optional whole-slide identity for a section cropped from a larger
     * source. The review stays section-local while the master Fiji ROI ZIP
     * can be written in original whole-slide coordinates.
     */
    public record ParentSourceContext(
            String sourceName,
            String pixelSha256,
            int sourceWidth,
            int sourceHeight,
            int sectionOffsetX,
            int sectionOffsetY) {
        public ParentSourceContext {
            sourceName = requireText(sourceName, "parent sourceName");
            pixelSha256 = requireText(pixelSha256, "parent pixelSha256");
            if (!pixelSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "Parent pixel SHA-256 must be lowercase hexadecimal");
            }
            if (sourceWidth <= 0 || sourceHeight <= 0
                    || sectionOffsetX < 0 || sectionOffsetY < 0) {
                throw new IllegalArgumentException(
                        "Parent source dimensions and section origin are invalid");
            }
        }
    }

    private record OutputArtifact(
            String kind,
            String sectionId,
            String roiName,
            String relativePath,
            long sizeBytes,
            String sha256) {
    }

    private final SourcePixelReader source;
    private final SourceImageSnapshot verifiedSource;
    private final int previewChannel;
    private final int previewSlice;
    private final int previewFrame;
    private final Optional<ParentSourceContext> parentSource;
    private final BioFormatsOmeTiffExporter ome =
            new BioFormatsOmeTiffExporter();
    private final SourceRoiZipWriter roiZip = new SourceRoiZipWriter();
    private final ObjectMapper json = new ObjectMapper();

    public ManualRoiExportService(
            final SourcePixelReader source,
            final SourceImageSnapshot verifiedSource,
            final int previewChannel,
            final int previewSlice,
            final int previewFrame) {
        this(source, verifiedSource, previewChannel, previewSlice,
                previewFrame, Optional.empty());
    }

    public ManualRoiExportService(
            final SourcePixelReader source,
            final SourceImageSnapshot verifiedSource,
            final int previewChannel,
            final int previewSlice,
            final int previewFrame,
            final Optional<ParentSourceContext> parentSource) {
        this.source = Objects.requireNonNull(source, "source");
        this.verifiedSource = Objects.requireNonNull(
                verifiedSource, "verifiedSource");
        this.previewChannel = previewChannel;
        this.previewSlice = previewSlice;
        this.previewFrame = previewFrame;
        this.parentSource = Objects.requireNonNull(
                parentSource, "parentSource");
        this.parentSource.ifPresent(parent -> {
            if ((long) parent.sectionOffsetX()
                    + verifiedSource.metadata().width()
                    > parent.sourceWidth()
                    || (long) parent.sectionOffsetY()
                    + verifiedSource.metadata().height()
                    > parent.sourceHeight()) {
                throw new IllegalArgumentException(
                        "Section bounds exceed the parent whole-slide source");
            }
        });
        requireSourceUnchanged();
        requireIndex(previewChannel, verifiedSource.metadata().channels(),
                "channel");
        requireIndex(previewSlice, verifiedSource.metadata().slices(),
                "slice");
        requireIndex(previewFrame, verifiedSource.metadata().frames(),
                "frame");
    }

    public SourceImageSnapshot verifiedSource() {
        return verifiedSource;
    }

    public Result export(
            final Path selectedFolder,
            final String sourceName,
            final String sectionId,
            final List<ReviewerRoi> requestedRois,
            final boolean includeUnion,
            final BooleanSupplier cancelled,
            final BiConsumer<String, Double> progress) {
        final Path parent = Objects.requireNonNull(
                selectedFolder, "selectedFolder")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(parent) || !Files.isWritable(parent)) {
            throw new IllegalArgumentException(
                    "Choose an existing writable export folder");
        }
        final String originalName = requireText(sourceName, "sourceName");
        final String checkedSection = requireText(sectionId, "sectionId");
        final List<ReviewerRoi> rois = List.copyOf(
                Objects.requireNonNull(requestedRois, "requestedRois"));
        if (rois.isEmpty()) {
            throw new IllegalArgumentException(
                    "Select at least one finished reviewer ROI to export");
        }
        if (rois.stream().anyMatch(roi -> !roi.finished())) {
            throw new IllegalArgumentException(
                    "Finish every selected reviewer ROI before export");
        }
        final List<String> roiTokens = roiTokens(rois);
        final String sectionToken = SourceRoiZipWriter.safeToken(checkedSection);
        if (sectionToken.equals(".") || sectionToken.equals("..")) {
            throw new IllegalArgumentException("Section name must identify a folder, not a relative path");
        }
        final BooleanSupplier cancellation = Objects.requireNonNull(
                cancelled, "cancelled");
        final BiConsumer<String, Double> reporter = Objects.requireNonNull(
                progress, "progress");
        checkCancelled(cancellation);
        requireSourceUnchanged();
        ome.verifyRuntimeVersion();

        reporter.accept("Rasterizing exact source-pixel ROI masks", 0.02);
        final List<ManualRoiFootprint> manual = rois.stream()
                .map(roi -> ManualRoiFootprint.rasterize(roi,
                        verifiedSource.metadata().width(),
                        verifiedSource.metadata().height()))
                .toList();
        final List<SourceRegionFootprint> footprints = new ArrayList<>();
        for (int index = 0; index < rois.size(); index++) {
            footprints.add(toSourceFootprint(
                    rois.get(index), manual.get(index), index + 1));
        }

        final String sourceBase = safeSourceBase(originalName);
        final String exportBase = sourceBase
                + "__atlasalign_manual_rois";
        final Path destination = nextAvailableDirectory(parent, exportBase);
        final Path temporary = parent.resolve("."
                + destination.getFileName() + ".tmp-" + UUID.randomUUID());
        preflightPaths(sourceBase, sectionToken, roiTokens, includeUnion,
                destination, temporary);
        final List<OutputArtifact> artifacts = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        try {
            Files.createDirectory(temporary);
            final Path sectionDirectory = temporary.resolve(
                    sectionToken);
            Files.createDirectory(sectionDirectory);
            for (int index = 0; index < rois.size(); index++) {
                checkCancelled(cancellation);
                final ReviewerRoi roi = rois.get(index);
                final SourceRegionFootprint footprint = footprints.get(index);
                final String stem = sectionToken + "__" + roiTokens.get(index);
                final double start = 0.06 + 0.76 * index / rois.size();
                final double span = 0.76 / rois.size();

                final Path raw = sectionDirectory.resolve(
                        stem + "__source-crop.ome.tif");
                final Path mask = sectionDirectory.resolve(
                        stem + "__mask.ome.tif");
                final Path masked = sectionDirectory.resolve(
                        stem + "__masked.ome.tif");
                final Path qc = sectionDirectory.resolve(
                        stem + "__qc-preview.png");
                final Path localRoi = sectionDirectory.resolve(
                        stem + "__crop-local-roi.zip");

                warnings.addAll(ome.writeSourceCrop(raw, roi.name(),
                        source, footprint, cancellation,
                        fraction -> reporter.accept(
                                "Writing " + roi.name()
                                        + " original-pixel crop",
                                start + span * 0.36 * fraction)).warnings());
                warnings.addAll(ome.writeMask(mask,
                        roi.name() + " exact mask", footprint,
                        verifiedSource.metadata()).warnings());
                warnings.addAll(ome.writeMaskedSourceCrop(masked,
                        roi.name() + " masked crop", source, footprint,
                        cancellation, fraction -> reporter.accept(
                                "Writing " + roi.name()
                                        + " masked source crop",
                                start + span * (0.42
                                        + 0.36 * fraction))).warnings());
                writeQcPreview(qc, footprint);
                roiZip.writeSingle(localRoi, roi.name(), footprint,
                        footprint.bounds().minimumX(),
                        footprint.bounds().minimumY());

                addArtifact(artifacts, "raw_source_crop", checkedSection,
                        roi.name(), temporary, raw);
                addArtifact(artifacts, "tight_binary_mask", checkedSection,
                        roi.name(), temporary, mask);
                addArtifact(artifacts, "masked_source_crop", checkedSection,
                        roi.name(), temporary, masked);
                addArtifact(artifacts, "qc_preview", checkedSection,
                        roi.name(), temporary, qc);
                addArtifact(artifacts, "crop_local_roi_zip", checkedSection,
                        roi.name(), temporary, localRoi);
            }

            reporter.accept("Writing master source-coordinate ROI ZIP", 0.84);
            final Path masterRois = temporary.resolve(sourceBase
                    + (parentSource.isPresent()
                            ? "__manual-rois-section-coordinates.zip"
                            : "__manual-rois-source-coordinates.zip"));
            roiZip.write(masterRois, footprints, roiTokens.stream()
                    .map(token -> token + ".roi").toList());
            addArtifact(artifacts, parentSource.isPresent()
                            ? "master_section_roi_zip"
                            : "master_source_roi_zip",
                    checkedSection, "all", temporary, masterRois);
            if (parentSource.isPresent()) {
                final ParentSourceContext wholeSlide =
                        parentSource.orElseThrow();
                final List<SourceRegionFootprint> slideFootprints = footprints
                        .stream().map(footprint -> translateToParent(
                                footprint, wholeSlide)).toList();
                final String parentBase = safeSourceBase(
                        wholeSlide.sourceName());
                final Path slideRois = temporary.resolve(parentBase
                        + "__manual-rois-whole-slide-coordinates.zip");
                roiZip.write(slideRois, slideFootprints, roiTokens.stream()
                        .map(token -> token + ".roi").toList());
                addArtifact(artifacts, "master_whole_slide_roi_zip",
                        checkedSection, "all", temporary, slideRois);
            }

            if (includeUnion && footprints.size() > 1) {
                final SourceRegionFootprint union = union(footprints,
                        checkedSection + " union");
                final Path unionMask = sectionDirectory.resolve(
                        SourceRoiZipWriter.safeToken(checkedSection)
                                + "__union-mask.ome.tif");
                warnings.addAll(ome.writeMask(unionMask,
                        checkedSection + " union mask", union,
                        verifiedSource.metadata()).warnings());
                addArtifact(artifacts, "section_union_mask", checkedSection,
                        "union", temporary, unionMask);
            }

            checkCancelled(cancellation);
            requireSourceUnchanged();
            reporter.accept("Writing exact-geometry manifest and CSV", 0.92);
            final Path manifest = temporary.resolve(
                    sourceBase + "__manual-roi-export.json");
            writeManifest(manifest, originalName, checkedSection, rois,
                    manual, artifacts, warnings);
            addArtifact(artifacts, "manifest", checkedSection, "all",
                    temporary, manifest);
            final Path csv = temporary.resolve(
                    sourceBase + "__manual-roi-index.csv");
            writeCsv(csv, checkedSection, rois, manual,
                    parentSource.orElse(null));
            addArtifact(artifacts, "csv_index", checkedSection, "all",
                    temporary, csv);

            requireSourceUnchanged();
            publishAtomically(temporary, destination);
            reporter.accept("Manual ROI export complete", 1.0);
            return new Result(destination,
                    artifacts.stream().map(OutputArtifact::relativePath)
                            .toList(),
                    warnings.stream().distinct().toList());
        } catch (final RuntimeException | IOException error) {
            deleteTemporaryTree(temporary);
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    "Could not export the manual reviewer ROIs", error);
        }
    }

    private void preflightPaths(final String sourceBase, final String sectionToken,
            final List<String> tokens, final boolean includeUnion,
            final Path destination, final Path temporary) {
        final List<String> paths = new ArrayList<>();
        paths.add(destination.getFileName().toString());
        paths.add(temporary.getFileName().toString());
        paths.add(sectionToken);
        paths.add(sourceBase + (parentSource.isPresent()
                ? "__manual-rois-section-coordinates.zip" : "__manual-rois-source-coordinates.zip"));
        parentSource.ifPresent(parent -> paths.add(safeSourceBase(parent.sourceName())
                + "__manual-rois-whole-slide-coordinates.zip"));
        paths.add(sourceBase + "__manual-roi-export.json");
        paths.add(sourceBase + "__manual-roi-index.csv");
        for (final String token : tokens) {
            for (final String suffix : List.of("__source-crop.ome.tif", "__mask.ome.tif",
                    "__masked.ome.tif", "__qc-preview.png", "__crop-local-roi.zip")) {
                paths.add(sectionToken + "/" + sectionToken + "__" + token + suffix);
            }
        }
        if (includeUnion && tokens.size() > 1) {
            paths.add(sectionToken + "/" + sectionToken + "__union-mask.ome.tif");
        }
        final Set<String> unique = new java.util.HashSet<>();
        for (final String name : paths) {
            final Path path = Path.of(name);
            if (path.isAbsolute() || !path.normalize().equals(path)
                    || !unique.add(name.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Invalid or colliding export path: " + name);
            }
            for (final Path component : path) {
                if (component.toString().length() > 240) {
                    throw new IllegalArgumentException("Export name is too long; shorten the source or section name");
                }
            }
        }
    }

    static List<String> roiTokens(final List<ReviewerRoi> rois) {
        final Set<String> ids = new java.util.HashSet<>();
        final Set<String> tokens = new java.util.HashSet<>();
        final List<String> result = new ArrayList<>();
        for (final ReviewerRoi roi : rois) {
            if (!ids.add(roi.id())) {
                throw new IllegalArgumentException("Duplicate ROI identity: " + roi.id());
            }
            final String readable = SourceRoiZipWriter.safeToken(roi.name());
            final String digest;
            try {
                digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(roi.id().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (final NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
            final String token = readable.substring(0, Math.min(64, readable.length()))
                    + "__roi-" + digest.substring(0, 16);
            if (!tokens.add(token.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Colliding ROI output identity");
            }
            result.add(token);
        }
        return List.copyOf(result);
    }

    private SourceRegionFootprint toSourceFootprint(
            final ReviewerRoi roi,
            final ManualRoiFootprint manual,
            final int ordinal) {
        final var bounds = manual.bounds();
        final ExportRegionSelection displayIdentity =
                new ExportRegionSelection(ordinal, roi.name(), roi.name(),
                        false, Set.of(ordinal));
        return new SourceRegionFootprint(List.of(displayIdentity),
                manual.sourceWidth(), manual.sourceHeight(),
                new SourcePixelReader.Bounds(bounds.minimumX(),
                        bounds.minimumY(), bounds.width(), bounds.height()),
                manual.cropMask());
    }

    private static SourceRegionFootprint translateToParent(
            final SourceRegionFootprint footprint,
            final ParentSourceContext parent) {
        final SourcePixelReader.Bounds bounds = footprint.bounds();
        return new SourceRegionFootprint(footprint.selections(),
                parent.sourceWidth(), parent.sourceHeight(),
                new SourcePixelReader.Bounds(
                        Math.addExact(bounds.minimumX(),
                                parent.sectionOffsetX()),
                        Math.addExact(bounds.minimumY(),
                                parent.sectionOffsetY()),
                        bounds.width(), bounds.height()),
                footprint.cropMask());
    }

    private static SourceRegionFootprint union(
            final List<SourceRegionFootprint> footprints,
            final String name) {
        final int sourceWidth = footprints.get(0).sourceWidth();
        final int sourceHeight = footprints.get(0).sourceHeight();
        final BitSet full = new BitSet(
                Math.multiplyExact(sourceWidth, sourceHeight));
        for (final SourceRegionFootprint footprint : footprints) {
            final var bounds = footprint.bounds();
            final BitSet crop = footprint.cropMask();
            for (int bit = crop.nextSetBit(0);
                    bit >= 0; bit = crop.nextSetBit(bit + 1)) {
                final int x = bounds.minimumX() + bit % bounds.width();
                final int y = bounds.minimumY() + bit / bounds.width();
                full.set(y * sourceWidth + x);
            }
        }
        int minX = sourceWidth;
        int minY = sourceHeight;
        int maxX = -1;
        int maxY = -1;
        for (int bit = full.nextSetBit(0);
                bit >= 0; bit = full.nextSetBit(bit + 1)) {
            final int x = bit % sourceWidth;
            final int y = bit / sourceWidth;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        final SourcePixelReader.Bounds bounds =
                new SourcePixelReader.Bounds(minX, minY,
                        maxX - minX + 1, maxY - minY + 1);
        final BitSet crop = new BitSet(bounds.pixelCount());
        for (int bit = full.nextSetBit(0);
                bit >= 0; bit = full.nextSetBit(bit + 1)) {
            final int x = bit % sourceWidth;
            final int y = bit / sourceWidth;
            crop.set((y - minY) * bounds.width() + x - minX);
        }
        final ExportRegionSelection label = new ExportRegionSelection(
                1, name, name, false, Set.of(1));
        return new SourceRegionFootprint(List.of(label), sourceWidth,
                sourceHeight, bounds, crop);
    }

    private void writeQcPreview(
            final Path file,
            final SourceRegionFootprint footprint) throws IOException {
        final SourcePixelReader.PixelBlock block = source.readPlane(
                previewChannel, previewSlice, previewFrame,
                footprint.bounds());
        final double[] values = values(block);
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (final double value : values) {
            if (Double.isFinite(value)) {
                minimum = Math.min(minimum, value);
                maximum = Math.max(maximum, value);
            }
        }
        if (!Double.isFinite(minimum) || maximum <= minimum) {
            minimum = 0;
            maximum = 1;
        }
        final BitSet mask = footprint.cropMask();
        final BufferedImage image = new BufferedImage(
                footprint.bounds().width(), footprint.bounds().height(),
                BufferedImage.TYPE_INT_RGB);
        for (int index = 0; index < values.length; index++) {
            final double normalized = Math.max(0, Math.min(1,
                    (values[index] - minimum) / (maximum - minimum)));
            final double brightness = mask.get(index)
                    ? normalized : normalized * 0.20;
            final int gray = (int) Math.round(255 * brightness);
            image.setRGB(index % image.getWidth(), index / image.getWidth(),
                    new Color(gray, gray, gray).getRGB());
        }
        final Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(0, 235, 235));
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    final int index = y * image.getWidth() + x;
                    if (mask.get(index) && (x == 0 || y == 0
                            || x + 1 == image.getWidth()
                            || y + 1 == image.getHeight()
                            || !mask.get(index - 1)
                            || !mask.get(index + 1)
                            || !mask.get(index - image.getWidth())
                            || !mask.get(index + image.getWidth()))) {
                        graphics.fillRect(x, y, 1, 1);
                    }
                }
            }
        } finally {
            graphics.dispose();
        }
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("No PNG writer is available");
        }
    }

    private static double[] values(final SourcePixelReader.PixelBlock block) {
        final double[] values = new double[block.pixelCount()];
        if (block instanceof SourcePixelReader.ByteBlock bytes) {
            final byte[] pixels = bytes.pixels();
            for (int index = 0; index < pixels.length; index++) {
                values[index] = Byte.toUnsignedInt(pixels[index]);
            }
        } else if (block instanceof SourcePixelReader.UnsignedShortBlock shorts) {
            final short[] pixels = shorts.pixels();
            for (int index = 0; index < pixels.length; index++) {
                values[index] = Short.toUnsignedInt(pixels[index]);
            }
        } else if (block instanceof SourcePixelReader.FloatBlock floats) {
            final float[] pixels = floats.pixels();
            for (int index = 0; index < pixels.length; index++) {
                values[index] = pixels[index];
            }
        } else {
            throw new IllegalArgumentException("Unsupported source pixel type");
        }
        return values;
    }

    private void writeManifest(
            final Path file,
            final String sourceName,
            final String sectionId,
            final List<ReviewerRoi> rois,
            final List<ManualRoiFootprint> footprints,
            final List<OutputArtifact> artifacts,
            final List<String> warnings) throws IOException {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", SCHEMA);
        root.put("createdAt", Instant.now().toString());
        root.put("pluginVersion", SourceSpaceExportService.PLUGIN_VERSION);
        root.put("source", Map.of(
                "name", sourceName,
                "pixelSha256", verifiedSource.pixelSha256(),
                "width", verifiedSource.metadata().width(),
                "height", verifiedSource.metadata().height(),
                "channels", verifiedSource.metadata().channels(),
                "slices", verifiedSource.metadata().slices(),
                "frames", verifiedSource.metadata().frames(),
                "bitDepth", verifiedSource.metadata().bitDepth()));
        parentSource.ifPresent(parent -> root.put("wholeSlideSource", Map.of(
                "name", parent.sourceName(),
                "pixelSha256", parent.pixelSha256(),
                "width", parent.sourceWidth(),
                "height", parent.sourceHeight(),
                "sectionOffsetX", parent.sectionOffsetX(),
                "sectionOffsetY", parent.sectionOffsetY())));
        final List<Map<String, Object>> roiRows = new ArrayList<>();
        for (int index = 0; index < rois.size(); index++) {
            final ReviewerRoi roi = rois.get(index);
            final ManualRoiFootprint footprint = footprints.get(index);
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", roi.id());
            row.put("name", roi.name());
            row.put("side", roi.side().name());
            row.put("pixelCount", footprint.pixelCount());
            row.put("bounds", footprint.bounds());
            parentSource.ifPresent(parent -> row.put("wholeSlideBounds",
                    Map.of(
                            "minimumX", Math.addExact(
                                    footprint.bounds().minimumX(),
                                    parent.sectionOffsetX()),
                            "minimumY", Math.addExact(
                                    footprint.bounds().minimumY(),
                                    parent.sectionOffsetY()),
                            "width", footprint.bounds().width(),
                            "height", footprint.bounds().height())));
            row.put("guideLink", roi.guideLink().orElse(null));
            row.put("parts", roi.parts());
            roiRows.add(row);
        }
        root.put("sections", List.of(Map.of(
                "id", sectionId,
                "rois", roiRows)));
        root.put("outputs", artifacts);
        root.put("warnings", warnings.stream().distinct().toList());
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
    }

    private static void writeCsv(
            final Path file,
            final String sectionId,
            final List<ReviewerRoi> rois,
            final List<ManualRoiFootprint> footprints,
            final ParentSourceContext parent) throws IOException {
        final StringBuilder csv = new StringBuilder(
                "section_id,roi_id,roi_name,side,pixel_count,section_min_x,"
                + "section_min_y,whole_slide_min_x,whole_slide_min_y,width,height\n");
        for (int index = 0; index < rois.size(); index++) {
            final ReviewerRoi roi = rois.get(index);
            final ManualRoiFootprint footprint = footprints.get(index);
            csv.append(csv(sectionId)).append(',')
                    .append(csv(roi.id())).append(',')
                    .append(csv(roi.name())).append(',')
                    .append(roi.side().name()).append(',')
                    .append(footprint.pixelCount()).append(',')
                    .append(footprint.bounds().minimumX()).append(',')
                    .append(footprint.bounds().minimumY()).append(',')
                    .append(parent == null
                            ? footprint.bounds().minimumX()
                            : Math.addExact(footprint.bounds().minimumX(),
                                    parent.sectionOffsetX())).append(',')
                    .append(parent == null
                            ? footprint.bounds().minimumY()
                            : Math.addExact(footprint.bounds().minimumY(),
                                    parent.sectionOffsetY())).append(',')
                    .append(footprint.bounds().width()).append(',')
                    .append(footprint.bounds().height()).append('\n');
        }
        Files.writeString(file, csv.toString());
    }

    private static String csv(final String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static void addArtifact(
            final List<OutputArtifact> artifacts,
            final String kind,
            final String sectionId,
            final String roiName,
            final Path root,
            final Path file) throws IOException {
        artifacts.add(new OutputArtifact(kind, sectionId, roiName,
                root.relativize(file).toString(), Files.size(file),
                sha256(file)));
    }

    private void requireSourceUnchanged() {
        if (!verifiedSource.equals(source.snapshot())) {
            throw new IllegalStateException(
                    "Source pixels or metadata changed; no manual ROI export was published");
        }
    }

    private static Path nextAvailableDirectory(
            final Path parent,
            final String baseName) {
        final Path base = parent.resolve(baseName);
        if (!Files.exists(base)) {
            return base;
        }
        for (int suffix = 2; suffix < 1_000_000; suffix++) {
            final Path candidate = parent.resolve(String.format(
                    java.util.Locale.ROOT, "%s_%03d", baseName, suffix));
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "No unused export directory name is available");
    }

    private static void publishAtomically(
            final Path temporary,
            final Path destination) {
        try {
            Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException unsupported) {
            throw new IllegalStateException(
                    "The selected folder does not support atomic publication; no files were published",
                    unsupported);
        } catch (final IOException error) {
            throw new IllegalStateException(
                    "Could not publish the checked manual ROI export", error);
        }
    }

    private static void deleteTemporaryTree(final Path temporary) {
        if (!Files.exists(temporary)) {
            return;
        }
        try (var paths = Files.walk(temporary)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException ignored) {
                    // Best-effort cleanup of an unpublished scoped tree.
                }
            });
        } catch (final IOException ignored) {
            // The caller still receives the original export failure.
        }
    }

    private static String sha256(final Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "Java runtime has no SHA-256 provider", impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void checkCancelled(
            final BooleanSupplier cancellation) {
        if (cancellation.getAsBoolean()) {
            throw new ExportCancelledException();
        }
    }

    private static void requireIndex(
            final int index,
            final int maximum,
            final String name) {
        if (index < 1 || index > maximum) {
            throw new IllegalArgumentException(
                    "Preview " + name + " is outside the source image");
        }
    }

    private static String safeSourceBase(final String sourceName) {
        return SourceRoiZipWriter.safeToken(
                sourceName.replaceFirst("(?i)\\.[^.]+$", ""));
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
}
