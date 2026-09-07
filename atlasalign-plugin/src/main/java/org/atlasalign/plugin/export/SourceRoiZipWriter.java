package org.atlasalign.plugin.export;

import ij.gui.ShapeRoi;
import ij.io.RoiEncoder;
import java.awt.geom.Area;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes exact irregular footprints as source-coordinate Fiji ShapeROIs. */
public final class SourceRoiZipWriter {

    public void write(
            final Path destination,
            final List<SourceRegionFootprint> footprints) {
        final List<SourceRegionFootprint> checked = List.copyOf(
                Objects.requireNonNull(footprints, "footprints"));
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(
                    "ROI ZIP requires at least one source footprint");
        }
        write(destination, checked, checked.stream()
                .map(SourceRoiZipWriter::displayName)
                .map(name -> safeToken(name) + ".roi").toList());
    }

    /** Entry names may carry stable identity while embedded ROI names stay readable. */
    public void write(final Path destination,
            final List<SourceRegionFootprint> footprints,
            final List<String> entryNames) {
        final var checked = List.copyOf(footprints);
        final var names = List.copyOf(entryNames);
        if (checked.isEmpty() || names.size() != checked.size()) {
            throw new IllegalArgumentException("Each footprint requires one ZIP entry name");
        }
        final var unique = new java.util.HashSet<String>();
        for (final String name : names) {
            if (!name.endsWith(".roi") || name.contains("/") || name.contains("\\")
                    || !unique.add(name.toLowerCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("Invalid or colliding ROI ZIP entry: " + name);
            }
        }
        try (OutputStream output = Files.newOutputStream(destination, java.nio.file.StandardOpenOption.CREATE_NEW);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            for (int index = 0; index < checked.size(); index++) {
                final SourceRegionFootprint footprint = checked.get(index);
                final String name = displayName(footprint);
                final ShapeRoi roi = new ShapeRoi(sourceArea(footprint));
                roi.setName(name);
                zip.putNextEntry(new ZipEntry(names.get(index)));
                zip.write(RoiEncoder.saveAsByteArray(roi));
                zip.closeEntry();
            }
        } catch (final IOException error) {
            throw new IllegalStateException(
                    "Could not write source-coordinate Fiji ROI ZIP", error);
        }
    }

    /** Writes one explicitly named ROI, optionally translated to crop-local coordinates. */
    public void writeSingle(
            final Path destination,
            final String roiName,
            final SourceRegionFootprint footprint,
            final int sourceOffsetX,
            final int sourceOffsetY) {
        final String name = Objects.requireNonNull(roiName, "roiName").trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("ROI name must not be blank");
        }
        final Area area = sourceArea(Objects.requireNonNull(
                footprint, "footprint"));
        if (sourceOffsetX != 0 || sourceOffsetY != 0) {
            area.transform(AffineTransform.getTranslateInstance(
                    -sourceOffsetX, -sourceOffsetY));
        }
        try (OutputStream output = Files.newOutputStream(destination, java.nio.file.StandardOpenOption.CREATE_NEW);
                ZipOutputStream zip = new ZipOutputStream(output)) {
            final ShapeRoi roi = new ShapeRoi(area);
            roi.setName(name);
            zip.putNextEntry(new ZipEntry(safeToken(name) + ".roi"));
            zip.write(RoiEncoder.saveAsByteArray(roi));
            zip.closeEntry();
        } catch (final IOException error) {
            throw new IllegalStateException(
                    "Could not write Fiji ROI ZIP", error);
        }
    }

    private static String displayName(final SourceRegionFootprint footprint) {
        return footprint.selections().stream().map(ExportRegionSelection::acronym)
                .reduce((left, right) -> left + "+" + right).orElseThrow();
    }

    static Area sourceArea(final SourceRegionFootprint footprint) {
        final Area area = new Area();
        final var bounds = footprint.bounds();
        final var mask = footprint.cropMask();
        for (int y = 0; y < bounds.height(); y++) {
            final int rowStart = y * bounds.width();
            int x = mask.nextSetBit(rowStart);
            while (x >= rowStart
                    && x < rowStart + bounds.width()) {
                final int startX = x - rowStart;
                int end = mask.nextClearBit(x);
                end = Math.min(end, rowStart + bounds.width());
                area.add(new Area(new Rectangle2D.Double(
                        bounds.minimumX() + startX,
                        bounds.minimumY() + y,
                        end - x, 1)));
                x = mask.nextSetBit(end);
            }
        }
        return area;
    }

    static String safeToken(final String value) {
        final String sanitized = Objects.requireNonNull(value, "value")
                .trim().replaceAll("[^A-Za-z0-9._+-]+", "_")
                .replaceAll("^_+|_+$", "");
        return sanitized.isEmpty() ? "region" : sanitized;
    }
}
