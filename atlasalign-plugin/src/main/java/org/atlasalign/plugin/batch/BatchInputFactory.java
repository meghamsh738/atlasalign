package org.atlasalign.plugin.batch;

import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.FloatPolygon;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.SourceImageSnapshot;
import org.atlasalign.io.imagej.ImagePlusSourceImage;

/** Deterministic conversion of Fiji inputs into a verified review queue. */
public final class BatchInputFactory {

    public List<BatchReviewItem> markedSections(
            final ImagePlus source,
            final List<Roi> markers,
            final int firstCoronalLevel,
            final int levelStep) {
        final ImagePlus checked = requireSupported(source);
        final List<Roi> copiedMarkers = List.copyOf(Objects.requireNonNull(
                markers, "markers"));
        if (copiedMarkers.isEmpty()) {
            throw new IllegalArgumentException(
                    "Add one area ROI per tissue section to Fiji ROI Manager first");
        }
        final SourceImageSnapshot snapshot = new ImagePlusSourceImage(
                checked).snapshot();
        final List<BatchReviewItem> result = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        int ordinal = 1;
        for (final Roi marker : copiedMarkers) {
            if (marker == null || !marker.isArea()) {
                continue;
            }
            final Rectangle bounds = clippedBounds(marker.getBounds(),
                    checked.getWidth(), checked.getHeight());
            if (bounds.width < 2 || bounds.height < 2) {
                continue;
            }
            final String requested = marker.getName() == null
                    || marker.getName().isBlank()
                    ? "Section " + ordinal : marker.getName().trim();
            final String name = uniqueName(requested, names);
            final int level = clampLevel(firstCoronalLevel
                    + levelStep * (ordinal - 1));
            final BatchSection section = new BatchSection(
                    String.format(Locale.ROOT, "section-%03d", ordinal),
                    name, sourceName(checked), snapshot.pixelSha256(),
                    checked.getWidth(), checked.getHeight(), bounds.x,
                    bounds.y, bounds.width, bounds.height,
                    markerVertices(marker, bounds), level,
                    BatchReviewStatus.PENDING, "");
            result.add(new BatchReviewItem(checked, snapshot, section));
            ordinal++;
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException(
                    "ROI Manager contains no usable area markers");
        }
        return List.copyOf(result);
    }

    public List<BatchReviewItem> openImages(
            final List<ImagePlus> images,
            final int firstCoronalLevel,
            final int levelStep) {
        final List<ImagePlus> sources = List.copyOf(
                Objects.requireNonNull(images, "images"));
        if (sources.isEmpty()) {
            throw new IllegalArgumentException(
                    "Open at least one image in Fiji first");
        }
        final List<BatchReviewItem> result = new ArrayList<>();
        final Set<String> names = new HashSet<>();
        int ordinal = 1;
        for (final ImagePlus image : sources) {
            final ImagePlus source = requireSupported(image);
            final SourceImageSnapshot snapshot = new ImagePlusSourceImage(
                    source).snapshot();
            final String name = uniqueName(sourceName(source), names);
            final int level = clampLevel(firstCoronalLevel
                    + levelStep * (ordinal - 1));
            final BatchSection section = new BatchSection(
                    String.format(Locale.ROOT, "image-%03d", ordinal),
                    name, sourceName(source), snapshot.pixelSha256(),
                    source.getWidth(), source.getHeight(), 0, 0,
                    source.getWidth(), source.getHeight(),
                    rectangleVertices(0, 0, source.getWidth(),
                            source.getHeight()),
                    level, BatchReviewStatus.PENDING, "");
            result.add(new BatchReviewItem(source, snapshot, section));
            ordinal++;
        }
        return List.copyOf(result);
    }

    private static ImagePlus requireSupported(final ImagePlus source) {
        final ImagePlus checked = Objects.requireNonNull(source, "source");
        if (checked.getStackSize() <= 0) {
            throw new IllegalArgumentException("The source has no pixels");
        }
        if (checked.getBitDepth() != 8 && checked.getBitDepth() != 16
                && checked.getBitDepth() != 32) {
            throw new IllegalArgumentException(
                    "Batch review supports 8-bit, unsigned 16-bit, and 32-bit float images; split RGB first");
        }
        return checked;
    }

    private static Rectangle clippedBounds(
            final Rectangle value,
            final int width,
            final int height) {
        final int minimumX = Math.max(0, value.x);
        final int minimumY = Math.max(0, value.y);
        final int maximumX = Math.min(width, value.x + value.width);
        final int maximumY = Math.min(height, value.y + value.height);
        return new Rectangle(minimumX, minimumY,
                Math.max(0, maximumX - minimumX),
                Math.max(0, maximumY - minimumY));
    }

    private static List<Point2D> markerVertices(
            final Roi roi,
            final Rectangle fallback) {
        final FloatPolygon polygon = roi.getFloatPolygon();
        if (polygon == null || polygon.npoints < 3) {
            return rectangleVertices(fallback.x, fallback.y,
                    fallback.width, fallback.height);
        }
        final List<Point2D> result = new ArrayList<>(polygon.npoints);
        for (int index = 0; index < polygon.npoints; index++) {
            result.add(new Point2D(polygon.xpoints[index],
                    polygon.ypoints[index]));
        }
        return List.copyOf(result);
    }

    private static List<Point2D> rectangleVertices(
            final int x,
            final int y,
            final int width,
            final int height) {
        return List.of(new Point2D(x, y),
                new Point2D(x + width, y),
                new Point2D(x + width, y + height),
                new Point2D(x, y + height));
    }

    private static String uniqueName(
            final String requested,
            final Set<String> names) {
        String candidate = requested;
        int suffix = 2;
        while (!names.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = requested + " " + suffix++;
        }
        return candidate;
    }

    private static int clampLevel(final int value) {
        return Math.max(0, Math.min(527, value));
    }

    private static String sourceName(final ImagePlus source) {
        return source.getTitle() == null || source.getTitle().isBlank()
                ? "Untitled image" : source.getTitle();
    }
}
