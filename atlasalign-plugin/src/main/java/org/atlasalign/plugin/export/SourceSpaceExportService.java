package org.atlasalign.plugin.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.zip.ZipFile;
import loci.formats.FormatException;
import loci.formats.in.OMETiffReader;
import org.atlasalign.application.AcceptedAlignmentSnapshot;
import org.atlasalign.application.ReviewAcceptanceVerification;
import org.atlasalign.application.ReviewAcceptanceVerifier;
import org.atlasalign.application.export.SourcePixelReader;
import org.atlasalign.application.export.ExportSelection;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.plugin.export.AtlasAlignExportManifestWriter.Artifact;
import org.atlasalign.plugin.review.AtlasPlaneRequest;
import org.atlasalign.plugin.review.AtlasPlaneSource;

/** Complete fail-closed, atomic Phase 6 source-space export transaction. */
public final class SourceSpaceExportService {

    public static final String PLUGIN_VERSION = "0.1.0-beta.3";

    public record Result(
            Path publishedDirectory,
            List<String> warnings,
            List<String> fileNames) {
        public Result {
            publishedDirectory = Objects.requireNonNull(
                    publishedDirectory, "publishedDirectory");
            warnings = List.copyOf(Objects.requireNonNull(
                    warnings, "warnings"));
            fileNames = List.copyOf(Objects.requireNonNull(
                    fileNames, "fileNames"));
        }
    }

    private final SourcePixelReader source;
    private final AtlasPlaneSource atlasPlanes;
    private final ReviewAcceptanceVerifier liveVerifier;
    private final Supplier<Optional<AcceptedAlignmentSnapshot>>
            currentAcceptance;
    private final SourceSpaceFootprintProjector projector =
            new SourceSpaceFootprintProjector();
    private final BioFormatsOmeTiffExporter ome =
            new BioFormatsOmeTiffExporter();
    private final SourceRoiZipWriter roiZip = new SourceRoiZipWriter();
    private final AtlasAlignExportManifestWriter manifest =
            new AtlasAlignExportManifestWriter();

    public SourceSpaceExportService(
            final SourcePixelReader source,
            final AtlasPlaneSource atlasPlanes,
            final ReviewAcceptanceVerifier liveVerifier,
            final Supplier<Optional<AcceptedAlignmentSnapshot>>
                    currentAcceptance) {
        this.source = Objects.requireNonNull(source, "source");
        this.atlasPlanes = Objects.requireNonNull(atlasPlanes, "atlasPlanes");
        this.liveVerifier = Objects.requireNonNull(
                liveVerifier, "liveVerifier");
        this.currentAcceptance = Objects.requireNonNull(
                currentAcceptance, "currentAcceptance");
    }

    public Result export(
            final Path selectedFolder,
            final String sourceName,
            final AcceptedAlignmentSnapshot expectedAcceptance,
            final List<ExportRegionSelection> selections,
            final boolean includeCombinedUnion,
            final BooleanSupplier cancelled,
            final BiConsumer<String, Double> progress) {
        return export(selectedFolder, sourceName, expectedAcceptance,
                selections, SourceSpaceExportOptions.canonicalOnly(
                        includeCombinedUnion), cancelled, progress);
    }

    public Result export(
            final Path selectedFolder,
            final String sourceName,
            final AcceptedAlignmentSnapshot expectedAcceptance,
            final List<ExportRegionSelection> selections,
            final SourceSpaceExportOptions exportOptions,
            final BooleanSupplier cancelled,
            final BiConsumer<String, Double> progress) {
        return exportInternal(selectedFolder, sourceName, expectedAcceptance, selections,
                exportOptions, Optional.empty(), cancelled, progress);
    }

    public Result export(final Path selectedFolder, final String sourceName,
            final AcceptedAlignmentSnapshot expectedAcceptance,
            final List<ExportRegionSelection> selections,
            final SourceSpaceExportOptions exportOptions, final ExportSelection selection,
            final BooleanSupplier cancelled, final BiConsumer<String, Double> progress) {
        Objects.requireNonNull(selection, "selection").validateAgainst(expectedAcceptance.verifiedSource().metadata());
        return exportInternal(selectedFolder, sourceName, expectedAcceptance, selections,
                exportOptions, Optional.of(selection), cancelled, progress);
    }

    private Result exportInternal(final Path selectedFolder, final String sourceName,
            final AcceptedAlignmentSnapshot expectedAcceptance,
            final List<ExportRegionSelection> selections,
            final SourceSpaceExportOptions exportOptions, final Optional<ExportSelection> selection,
            final BooleanSupplier cancelled, final BiConsumer<String, Double> progress) {
        final Path parent = Objects.requireNonNull(
                selectedFolder, "selectedFolder")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(parent) || !Files.isWritable(parent)) {
            throw new IllegalArgumentException(
                    "Choose an existing writable export folder");
        }
        final String originalName = requireText(sourceName, "sourceName");
        final AcceptedAlignmentSnapshot accepted = Objects.requireNonNull(
                expectedAcceptance, "expectedAcceptance");
        final List<ExportRegionSelection> requested = List.copyOf(
                Objects.requireNonNull(selections, "selections"));
        final SourceSpaceExportOptions options = Objects.requireNonNull(
                exportOptions, "exportOptions");
        if (requested.isEmpty()) {
            throw new IllegalArgumentException(
                    "Select at least one Allen region before exporting");
        }
        final BooleanSupplier cancellation = Objects.requireNonNull(
                cancelled, "cancelled");
        final BiConsumer<String, Double> reporter = Objects.requireNonNull(
                progress, "progress");
        checkCancelled(cancellation);
        ome.verifyRuntimeVersion();
        verifyLiveIdentities(accepted);

        reporter.accept("Loading verified annotation plane", 0.02);
        final AtlasCoronalPlane plane = loadAcceptedPlane(accepted);
        final String planeHash = annotationPlaneSha256(plane);
        final List<SourceRegionFootprint> individual = projector.project(
                accepted, plane, requested, cancellation,
                value -> reporter.accept("Mapping atlas labels to source pixels",
                        0.03 + 0.37 * value));
        final List<SourceRegionFootprint> outputs = new ArrayList<>(individual);
        if (options.includeCombinedUnion() && individual.size() > 1) {
            outputs.add(projector.union(individual));
        }

        final String sourceBase = sourceBaseName(originalName);
        final Path finalDirectory = nextAvailableDirectory(parent,
                sourceBase + "__atlasalign_exports");
        final Path temporaryDirectory = parent.resolve("."
                + finalDirectory.getFileName() + ".tmp-" + UUID.randomUUID());
        final List<String> warnings = new ArrayList<>();
        final List<Artifact> artifacts = new ArrayList<>();
        final List<Path> created = new ArrayList<>();
        try {
            Files.createDirectory(temporaryDirectory);
            int outputIndex = 0;
            for (final SourceRegionFootprint footprint : outputs) {
                checkCancelled(cancellation);
                final boolean combined = footprint.selections().size() > 1;
                final String regionToken = footprint.selections().stream()
                        .map(ExportRegionSelection::acronym)
                        .reduce((left, right) -> left + "+" + right)
                        .map(SourceRoiZipWriter::safeToken).orElseThrow();
                final String stem = sourceBase + "__" + regionToken
                        + (combined ? "__combined" : "");
                final Path crop = temporaryDirectory.resolve(
                        stem + (combined ? ".ome.tif"
                                : "__crop.ome.tif"));
                final Path mask = temporaryDirectory.resolve(
                        stem + "__mask.ome.tif");
                final Path fullMask = temporaryDirectory.resolve(
                        stem + "__mask-full.ome.tif");
                final Path masked = temporaryDirectory.resolve(
                        stem + "__masked.ome.tif");
                final double baseProgress = 0.42
                        + 0.42 * outputIndex / outputs.size();
                final double span = 0.42 / outputs.size();
                final var cropReport = ScopedCropWriter.write(ome, false,
                        crop, stem, source, footprint, selection, cancellation,
                        value -> reporter.accept(
                                "Writing " + regionToken + " source crop",
                                baseProgress + span * 0.45 * value));
                warnings.addAll(cropReport.warnings());
                final var maskReport = ome.writeMask(
                        mask, stem + " mask", footprint,
                        accepted.verifiedSource().metadata());
                warnings.addAll(maskReport.warnings());
                created.add(crop);
                created.add(mask);
                artifacts.add(artifact("crop", crop, footprint));
                artifacts.add(artifact("mask", mask, footprint));
                validateOme(crop, footprint.bounds().width(),
                        footprint.bounds().height(),
                        selection.map(value -> value.channels().size()).orElse(accepted.verifiedSource().metadata().channels()),
                        selection.isPresent() ? 1 : accepted.verifiedSource().metadata().slices(),
                        selection.isPresent() ? 1 : accepted.verifiedSource().metadata().frames(),
                        accepted.verifiedSource().metadata().bitDepth());
                validateOme(mask, footprint.bounds().width(),
                        footprint.bounds().height(), 1, 1, 1, 8);
                if (options.includeFullSourceMask()) {
                    checkCancelled(cancellation);
                    reporter.accept(
                            "Writing " + regionToken + " full-size mask",
                            baseProgress + span * 0.52);
                    final var fullMaskReport = ome.writeFullSourceMask(
                            fullMask, stem + " full-size mask", footprint,
                            accepted.verifiedSource().metadata(),
                            cancellation);
                    warnings.addAll(fullMaskReport.warnings());
                    created.add(fullMask);
                    artifacts.add(artifact(
                            "mask_full", fullMask, footprint));
                    validateOme(fullMask, footprint.sourceWidth(),
                            footprint.sourceHeight(), 1, 1, 1, 8);
                }
                if (options.includeMaskedSourceCrop()) {
                    checkCancelled(cancellation);
                    final var maskedReport = ScopedCropWriter.write(ome, true,
                            masked, stem + " masked image", source,
                            footprint, selection, cancellation,
                            value -> reporter.accept(
                                    "Writing " + regionToken
                                            + " masked image",
                                    baseProgress + span
                                            * (0.60 + 0.35 * value)));
                    warnings.addAll(maskedReport.warnings());
                    created.add(masked);
                    artifacts.add(artifact(
                            "masked_crop", masked, footprint));
                    validateOme(masked, footprint.bounds().width(),
                            footprint.bounds().height(),
                            selection.map(value -> value.channels().size()).orElse(accepted.verifiedSource().metadata().channels()),
                            selection.isPresent() ? 1 : accepted.verifiedSource().metadata().slices(),
                            selection.isPresent() ? 1 : accepted.verifiedSource().metadata().frames(),
                            accepted.verifiedSource().metadata().bitDepth());
                }
                outputIndex++;
            }

            reporter.accept("Writing source-coordinate Fiji ROIs", 0.86);
            final Path rois = temporaryDirectory.resolve(
                    sourceBase + "__atlasalign-rois.zip");
            // The ROI ZIP contains one entry for every explicit top-level
            // selection.  The optional combined union is a derived crop/mask,
            // not another ontology selection.
            if (selection.isPresent()) {
                roiZip.write(rois, individual, selection.orElseThrow().slice(), selection.orElseThrow().frame());
            } else {
                roiZip.write(rois, individual);
            }
            created.add(rois);
            validateRoiZip(rois, individual.size());
            artifacts.add(artifact("roi_zip", rois, null));

            checkCancelled(cancellation);
            verifyLiveIdentities(accepted);
            final AtlasCoronalPlane reloaded = loadAcceptedPlane(accepted);
            if (!planeHash.equals(annotationPlaneSha256(reloaded))) {
                throw new IllegalStateException(
                        "The verified annotation plane changed during export; no files were published");
            }
            reporter.accept("Finalizing checksums and manifest", 0.92);
            final Path manifestPath = temporaryDirectory.resolve(
                    sourceBase + "__atlasalign-export.json");
            manifest.write(manifestPath, originalName, accepted,
                    PLUGIN_VERSION, ome.verifyRuntimeVersion(), planeHash,
                    distinct(warnings), artifacts, selection);
            created.add(manifestPath);
            validateManifest(manifestPath);
            final Artifact manifestArtifact = artifact(
                    "manifest", manifestPath, null);
            if (manifestArtifact.sizeBytes() <= 0) {
                throw new IllegalStateException(
                        "Export manifest validation failed");
            }
            checkCancelled(cancellation);
            verifyLiveIdentities(accepted);
            publishAtomically(temporaryDirectory, finalDirectory);
            reporter.accept("Export complete", 1.0);
            return new Result(finalDirectory, distinct(warnings),
                    created.stream().map(path -> path.getFileName().toString())
                            .toList());
        } catch (final RuntimeException | IOException error) {
            deleteTemporaryTree(temporaryDirectory);
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    "Could not prepare the atomic export", error);
        }
    }

    private void verifyLiveIdentities(
            final AcceptedAlignmentSnapshot expected) {
        final AcceptedAlignmentSnapshot current = currentAcceptance.get()
                .orElseThrow(() -> new IllegalStateException(
                        "Alignment acceptance was revoked or invalidated; re-accept before exporting"));
        if (!current.equals(expected)
                || current.contentRevision() != expected.contentRevision()
                || current.acceptanceAuditSequence()
                != expected.acceptanceAuditSequence()) {
            throw new IllegalStateException(
                    "The accepted alignment changed; re-open Export after accepting the current revision");
        }
        final ReviewAcceptanceVerification live = Objects.requireNonNull(
                liveVerifier.verify(), "verification returned null");
        if (!live.currentSourceSnapshot().equals(expected.verifiedSource())) {
            throw new IllegalStateException(
                    "Source pixels or metadata changed; no export was published");
        }
        if (!live.currentAtlas().equals(expected.verifiedAtlas())) {
            throw new IllegalStateException(
                    "Verified atlas assets changed; no export was published");
        }
        if (!source.snapshot().equals(expected.verifiedSource())) {
            throw new IllegalStateException(
                    "Source pixel reader no longer matches the accepted image");
        }
    }

    private AtlasCoronalPlane loadAcceptedPlane(
            final AcceptedAlignmentSnapshot accepted) {
        return atlasPlanes.load(new AtlasPlaneRequest(
                accepted.coronalLevel(), accepted.atlasPlaneTilt(), false));
    }

    static Path nextAvailableDirectory(
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
                "No unused deterministic export directory name is available");
    }

    private static void publishAtomically(
            final Path temporary,
            final Path destination) {
        try {
            Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException unsupported) {
            throw new IllegalStateException(
                    "The selected folder does not support atomic directory publication; no files were published",
                    unsupported);
        } catch (final IOException error) {
            throw new IllegalStateException(
                    "Could not publish the checked export directory", error);
        }
    }

    private static Artifact artifact(
            final String kind,
            final Path file,
            final SourceRegionFootprint footprint) {
        try {
            return new Artifact(kind, file.getFileName().toString(),
                    Files.size(file), sha256(file), footprint);
        } catch (final IOException error) {
            throw new IllegalStateException(
                    "Could not checksum export artifact " + file, error);
        }
    }

    private static void validateOme(
            final Path file,
            final int width,
            final int height,
            final int channels,
            final int slices,
            final int frames,
            final int bitDepth) {
        try (OMETiffReader reader = new OMETiffReader()) {
            reader.setId(file.toString());
            if (reader.getSizeX() != width || reader.getSizeY() != height
                    || reader.getSizeC() != channels
                    || reader.getSizeZ() != slices
                    || reader.getSizeT() != frames
                    || reader.getBitsPerPixel() != bitDepth
                    || reader.getImageCount()
                    != Math.multiplyExact(channels,
                            Math.multiplyExact(slices, frames))) {
                throw new IllegalStateException(
                        "OME-TIFF validation did not reproduce the requested dimensions or pixel type");
            }
            reader.openBytes(0);
            if (reader.getImageCount() > 1) {
                reader.openBytes(reader.getImageCount() - 1);
            }
        } catch (final FormatException | IOException error) {
            throw new IllegalStateException(
                    "Could not validate written OME-TIFF "
                            + file.getFileName(), error);
        }
    }

    private static void validateRoiZip(
            final Path file,
            final int expectedEntries) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            if (zip.size() != expectedEntries
                    || zip.stream().anyMatch(entry -> entry.getSize() == 0
                    || !entry.getName().endsWith(".roi"))) {
                throw new IllegalStateException(
                        "Fiji ROI ZIP validation failed");
            }
        } catch (final IOException error) {
            throw new IllegalStateException(
                    "Could not validate Fiji ROI ZIP", error);
        }
    }

    private static void validateManifest(final Path file) {
        try {
            final var root = new ObjectMapper().readTree(file.toFile());
            if (!"atlasalign-source-space-export-v1".equals(
                    root.path("schema").asText())
                    || !root.path("outputs").isArray()
                    || root.path("outputs").isEmpty()) {
                throw new IllegalStateException(
                        "Export manifest validation failed");
            }
        } catch (final IOException error) {
            throw new IllegalStateException(
                    "Could not validate export manifest", error);
        }
    }

    private static String annotationPlaneSha256(
            final AtlasCoronalPlane plane) {
        final MessageDigest digest = sha256Digest();
        final ByteBuffer buffer = ByteBuffer.allocate(4)
                .order(ByteOrder.BIG_ENDIAN);
        for (final int annotation : plane.annotationId()) {
            buffer.clear();
            buffer.putInt(annotation);
            digest.update(buffer.array());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(final Path file) throws IOException {
        final MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "Java runtime has no SHA-256 provider", impossible);
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
                    // Best-effort cleanup of an unpublished scoped temp tree.
                }
            });
        } catch (final IOException ignored) {
            // The unpublished directory name is retained in the thrown error.
        }
    }

    private static List<String> distinct(final List<String> warnings) {
        return warnings.stream().distinct().toList();
    }

    private static String sourceBaseName(final String sourceName) {
        final String plain = sourceName.replaceFirst("(?i)\\.[^.]+$", "");
        return SourceRoiZipWriter.safeToken(plain);
    }

    private static void checkCancelled(
            final BooleanSupplier cancellation) {
        if (cancellation.getAsBoolean()) {
            throw new ExportCancelledException();
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
}
