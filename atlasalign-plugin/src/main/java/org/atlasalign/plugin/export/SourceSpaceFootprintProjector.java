package org.atlasalign.plugin.export;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import org.atlasalign.application.AcceptedAlignmentSnapshot;
import org.atlasalign.application.AtlasMembershipProjection;
import org.atlasalign.application.ReviewedTissueSupport;
import org.atlasalign.application.export.SourcePixelReader;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.core.PreviewMapping;
import org.atlasalign.core.Point2D;

/**
 * Nearest-neighbour projection of accepted atlas labels into source pixels.
 */
public final class SourceSpaceFootprintProjector {

    public List<SourceRegionFootprint> project(
            final AcceptedAlignmentSnapshot accepted,
            final AtlasCoronalPlane annotationPlane,
            final List<ExportRegionSelection> selections,
            final BooleanSupplier cancelled,
            final DoubleConsumer progress) {
        final AcceptedAlignmentSnapshot snapshot = Objects.requireNonNull(
                accepted, "accepted");
        final AtlasCoronalPlane plane = Objects.requireNonNull(
                annotationPlane, "annotationPlane");
        final List<ExportRegionSelection> requested = List.copyOf(
                Objects.requireNonNull(selections, "selections"));
        if (requested.isEmpty()) {
            throw new IllegalArgumentException(
                    "Select at least one atlas region to export");
        }
        final BooleanSupplier cancellation = Objects.requireNonNull(
                cancelled, "cancelled");
        final DoubleConsumer reporter = Objects.requireNonNull(
                progress, "progress");
        requireAcceptedPlane(snapshot, plane);

        final int sourceWidth = snapshot.previewMapping().sourceWidth();
        final int sourceHeight = snapshot.previewMapping().sourceHeight();
        final int pixels = Math.multiplyExact(sourceWidth, sourceHeight);
        final List<BitSet> sourceMasks = requested.stream()
                .map(ignored -> new BitSet(pixels)).toList();
        final ReviewedTissueSupport support = snapshot.tissueClippingEnabled()
                ? snapshot.reviewedTissueSupport().orElseThrow()
                : null;
        final int[] labels = plane.annotationId();

        final PreviewMapping mapping = snapshot.previewMapping();
        final double scaleX = mapping.scaleX();
        final double scaleY = mapping.scaleY();
        for (int y = 0; y < sourceHeight; y++) {
            if (cancellation.getAsBoolean()) {
                throw new ExportCancelledException();
            }
            reporter.accept((double) y / sourceHeight);
            final double previewY = (y + 0.5) * scaleY - 0.5;
            if (support == null) {
                projectSourceInterval(snapshot, sourceWidth, plane, requested,
                        sourceMasks, labels, y, previewY, 0, sourceWidth,
                        scaleX);
                continue;
            }
            final List<ReviewedTissueSupport.HorizontalInterval> intervals =
                    support.horizontalIntervals(previewY);
            for (final ReviewedTissueSupport.HorizontalInterval interval
                    : intervals) {
                final int minimumX = firstSourcePixelAtOrAfter(
                        Math.max(0.0, interval.minimumXInclusive()),
                        scaleX, sourceWidth);
                final int maximumXExclusive = firstSourcePixelAtOrAfter(
                        Math.min(Math.nextUp(mapping.previewWidth() - 1.0),
                                interval.maximumXExclusive()),
                        scaleX, sourceWidth);
                projectSourceInterval(snapshot, sourceWidth, plane, requested,
                        sourceMasks, labels, y, previewY, minimumX,
                        maximumXExclusive, scaleX);
            }
        }
        reporter.accept(1.0);

        final List<SourceRegionFootprint> result = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            result.add(toTightFootprint(
                    List.of(requested.get(index)), sourceWidth, sourceHeight,
                    sourceMasks.get(index)));
        }
        return List.copyOf(result);
    }

    private static void projectSourceInterval(
            final AtlasMembershipProjection projection,
            final int sourceWidth,
            final AtlasCoronalPlane plane,
            final List<ExportRegionSelection> requested,
            final List<BitSet> sourceMasks,
            final int[] labels,
            final int sourceY,
            final double previewY,
            final int minimumX,
            final int maximumXExclusive,
            final double scaleX) {
        for (int x = minimumX; x < maximumXExclusive; x++) {
            final double previewX = (x + 0.5) * scaleX - 0.5;
            final Point2D previewPoint = new Point2D(previewX, previewY);
            final List<Point2D> atlasPoints;
            try {
                atlasPoints = projection.mapPreviewToAtlasCandidates(
                        previewPoint);
            } catch (final IllegalArgumentException outsideField) {
                continue;
            }
            final int sourceIndex = sourceY * sourceWidth + x;
            for (final Point2D atlasPoint : atlasPoints) {
                if (!projection.includesAtlasPoint(atlasPoint)) {
                    continue;
                }
                final int atlasX = (int) Math.round(atlasPoint.x());
                final int atlasY = (int) Math.round(atlasPoint.y());
                if (atlasX < 0 || atlasX >= plane.width()
                        || atlasY < 0 || atlasY >= plane.height()) {
                    continue;
                }
                final int annotationId = labels[
                        atlasY * plane.width() + atlasX];
                if (annotationId == 0) {
                    continue;
                }
                for (int selection = 0;
                        selection < requested.size(); selection++) {
                    if (requested.get(selection).contains(annotationId)) {
                        sourceMasks.get(selection).set(sourceIndex);
                    }
                }
            }
        }
    }

    /** First source X whose mapped preview center is at or right of X. */
    private static int firstSourcePixelAtOrAfter(
            final double previewX,
            final double scaleX,
            final int sourceWidth) {
        int candidate = (int) Math.ceil(
                (previewX + 0.5) / scaleX - 0.5);
        candidate = Math.max(0, Math.min(sourceWidth, candidate));
        while (candidate > 0
                && (candidate - 1 + 0.5) * scaleX - 0.5 >= previewX) {
            candidate--;
        }
        while (candidate < sourceWidth
                && (candidate + 0.5) * scaleX - 0.5 < previewX) {
            candidate++;
        }
        return candidate;
    }

    public SourceRegionFootprint union(
            final List<SourceRegionFootprint> footprints) {
        final List<SourceRegionFootprint> checked = List.copyOf(
                Objects.requireNonNull(footprints, "footprints"));
        if (checked.isEmpty()) {
            throw new IllegalArgumentException(
                    "A combined export requires at least one footprint");
        }
        final int width = checked.get(0).sourceWidth();
        final int height = checked.get(0).sourceHeight();
        final BitSet full = new BitSet(Math.multiplyExact(width, height));
        final List<ExportRegionSelection> selections = new ArrayList<>();
        for (final SourceRegionFootprint footprint : checked) {
            if (footprint.sourceWidth() != width
                    || footprint.sourceHeight() != height) {
                throw new IllegalArgumentException(
                        "Combined footprints must share a source image");
            }
            selections.addAll(footprint.selections());
            final SourcePixelReader.Bounds bounds = footprint.bounds();
            final BitSet crop = footprint.cropMask();
            for (int bit = crop.nextSetBit(0);
                    bit >= 0;
                    bit = crop.nextSetBit(bit + 1)) {
                final int x = bit % bounds.width() + bounds.minimumX();
                final int y = bit / bounds.width() + bounds.minimumY();
                full.set(y * width + x);
            }
        }
        return toTightFootprint(selections, width, height, full);
    }

    private static SourceRegionFootprint toTightFootprint(
            final List<ExportRegionSelection> selections,
            final int sourceWidth,
            final int sourceHeight,
            final BitSet fullMask) {
        if (fullMask.isEmpty()) {
            throw new IllegalArgumentException(
                    "The accepted alignment places the selected region outside the source image");
        }
        int minimumX = sourceWidth;
        int minimumY = sourceHeight;
        int maximumX = -1;
        int maximumY = -1;
        for (int bit = fullMask.nextSetBit(0);
                bit >= 0;
                bit = fullMask.nextSetBit(bit + 1)) {
            final int x = bit % sourceWidth;
            final int y = bit / sourceWidth;
            minimumX = Math.min(minimumX, x);
            minimumY = Math.min(minimumY, y);
            maximumX = Math.max(maximumX, x);
            maximumY = Math.max(maximumY, y);
        }
        final SourcePixelReader.Bounds bounds = new SourcePixelReader.Bounds(
                minimumX, minimumY,
                maximumX - minimumX + 1,
                maximumY - minimumY + 1);
        final BitSet crop = new BitSet(bounds.pixelCount());
        for (int bit = fullMask.nextSetBit(0);
                bit >= 0;
                bit = fullMask.nextSetBit(bit + 1)) {
            final int x = bit % sourceWidth;
            final int y = bit / sourceWidth;
            crop.set((y - minimumY) * bounds.width() + x - minimumX);
        }
        return new SourceRegionFootprint(
                selections, sourceWidth, sourceHeight, bounds, crop);
    }

    private static void requireAcceptedPlane(
            final AcceptedAlignmentSnapshot accepted,
            final AtlasCoronalPlane plane) {
        if (plane.zeroBasedAnteriorPosteriorIndex()
                != accepted.coronalLevel()
                        .zeroBasedAnteriorPosteriorIndex()
                || Double.compare(plane.geometry().sagittalDegrees(),
                        accepted.atlasPlaneTilt().sagittalDegrees()) != 0
                || Double.compare(plane.geometry().horizontalDegrees(),
                        accepted.atlasPlaneTilt().horizontalDegrees()) != 0
                || plane.width() != accepted.verifiedAtlas().atlasPlaneWidth()
                || plane.height()
                != accepted.verifiedAtlas().atlasPlaneHeight()) {
            throw new IllegalArgumentException(
                    "Annotation plane does not match the accepted level and tilt");
        }
    }
}
