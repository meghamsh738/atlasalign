package org.atlasalign.application;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.OptionalDouble;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.MaskBounds;

/**
 * Classifies observed image geometry without claiming anatomical left/right.
 */
public final class TissueGeometryClassifier {

    private static final double MINIMUM_FULL_ASPECT = 1.28;
    private static final double MINIMUM_DENSE_OCCUPANCY = 0.62;
    private static final double MINIMUM_SPARSE_OCCUPANCY = 0.45;
    private static final double MINIMUM_OUTER_EDGE_DISPERSION = 0.035;
    private static final double MINIMUM_SPARSE_BILATERAL_OVERLAP = 0.70;
    private static final double MINIMUM_SPARSE_HEMISPHERE_BALANCE = 0.85;
    private static final double MINIMUM_HIGH_CONFIDENCE_OCCUPANCY =
            0.6538691251067112;
    private static final double MINIMUM_HIGH_CONFIDENCE_BILATERAL_OVERLAP =
            0.7901669791805024;
    private static final double MINIMUM_COMPACT_POSTERIOR_ASPECT = 1.20;
    private static final double MINIMUM_COMPACT_POSTERIOR_OCCUPANCY = 0.65;
    private static final double MINIMUM_COMPACT_POSTERIOR_OVERLAP = 0.80;
    private static final double MINIMUM_COMPACT_POSTERIOR_BALANCE = 0.95;
    private static final double MINIMUM_COMPACT_POSTERIOR_EDGE = 0.05;
    private static final double MINIMUM_VERY_COMPACT_POSTERIOR_ASPECT = 1.15;
    private static final double MAXIMUM_VERY_COMPACT_POSTERIOR_ASPECT = 1.20;
    private static final double MINIMUM_ENVELOPE_OCCUPANCY = 0.60;
    private static final double MINIMUM_ENVELOPE_OVERLAP = 0.735;
    private static final double MINIMUM_ENVELOPE_BALANCE = 0.88;
    private static final double MAXIMUM_ENVELOPE_ADDED_FRACTION = 0.06;

    public TissueGeometryResult classify(final BinaryMask mask) {
        if (mask == null || mask.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot classify an empty tissue mask");
        }
        final MaskBounds bounds = mask.bounds();
        final double aspect =
                (double) bounds.width() / bounds.height();
        final double occupancy = (double) mask.foregroundCount()
                / (bounds.width() * bounds.height());
        final EdgeMeasurements edges = edgeMeasurements(mask, bounds);
        final BilateralMeasurements bilateral =
                bilateralMeasurements(mask, bounds);
        final ComponentMeasurements components =
                componentMeasurements(mask);
        final TissueEnvelopeMeasurements envelope =
                envelopeMeasurements(mask);

        final SectionGeometry geometry;
        final OptionalDouble medialEdge;
        final boolean completeOuterContour =
                aspect >= MINIMUM_FULL_ASPECT
                && edges.leftDispersion()
                        >= MINIMUM_OUTER_EDGE_DISPERSION
                && edges.rightDispersion()
                        >= MINIMUM_OUTER_EDGE_DISPERSION;
        final boolean denseFullSection =
                completeOuterContour
                && occupancy >= MINIMUM_DENSE_OCCUPANCY;
        final boolean sparseButBilateralFullSection =
                completeOuterContour
                && occupancy >= MINIMUM_SPARSE_OCCUPANCY
                && bilateral.mirroredOverlap()
                        >= MINIMUM_SPARSE_BILATERAL_OVERLAP
                && bilateral.hemisphereBalance()
                        >= MINIMUM_SPARSE_HEMISPHERE_BALANCE;
        final boolean highConfidenceBilateralFullSection =
                isHighConfidenceBilateralFullSection(
                        aspect,
                        occupancy,
                        bilateral.mirroredOverlap(),
                        bilateral.hemisphereBalance(),
                        components.count());
        final boolean compactPosteriorFullSection =
                isCompactPosteriorFullSection(
                        aspect,
                        occupancy,
                        bilateral.mirroredOverlap(),
                        bilateral.hemisphereBalance(),
                        edges.leftDispersion(),
                        edges.rightDispersion(),
                        components.count());
        final boolean veryCompactPosteriorFullSection =
                isVeryCompactPosteriorFullSection(
                        aspect,
                        edges.leftDispersion(),
                        edges.rightDispersion(),
                        components.count(),
                        envelope.foregroundToBoundsFraction(),
                        envelope.bilateralMirroredOverlap(),
                        envelope.hemisphereBalance(),
                        envelope.addedPreviewFraction());
        if (denseFullSection
                && components.substantialCount() >= 2) {
            geometry = SectionGeometry.BILATERAL_REVIEW_REQUIRED;
            medialEdge = OptionalDouble.empty();
        } else if (denseFullSection
                || highConfidenceBilateralFullSection
                || compactPosteriorFullSection
                || veryCompactPosteriorFullSection) {
            geometry = SectionGeometry.FULL;
            medialEdge = OptionalDouble.empty();
        } else if (sparseButBilateralFullSection) {
            geometry = SectionGeometry.BILATERAL_REVIEW_REQUIRED;
            medialEdge = OptionalDouble.empty();
        } else if (aspect <= 1.28
                && edges.rightDispersion() <= 0.025
                && edges.leftDispersion()
                >= edges.rightDispersion() * 2 + 0.02) {
            geometry = SectionGeometry.IMAGE_LEFT_HALF;
            medialEdge = OptionalDouble.of(edges.meanRight());
        } else if (aspect <= 1.28
                && edges.leftDispersion() <= 0.025
                && edges.rightDispersion()
                >= edges.leftDispersion() * 2 + 0.02) {
            geometry = SectionGeometry.IMAGE_RIGHT_HALF;
            medialEdge = OptionalDouble.of(edges.meanLeft());
        } else {
            geometry = SectionGeometry.PARTIAL_OR_DAMAGED;
            medialEdge = OptionalDouble.empty();
        }
        return new TissueGeometryResult(
                geometry,
                bounds,
                aspect,
                occupancy,
                edges.leftDispersion(),
                edges.rightDispersion(),
                bilateral.mirroredOverlap(),
                bilateral.hemisphereBalance(),
                components.count(),
                components.substantialCount(),
                medialEdge);
    }

    static boolean isHighConfidenceBilateralFullSection(
            final double aspect,
            final double occupancy,
            final double mirroredOverlap,
            final double hemisphereBalance,
            final int connectedComponentCount) {
        return aspect >= MINIMUM_FULL_ASPECT
                && occupancy >= MINIMUM_HIGH_CONFIDENCE_OCCUPANCY
                && mirroredOverlap
                        >= MINIMUM_HIGH_CONFIDENCE_BILATERAL_OVERLAP
                && hemisphereBalance
                        >= MINIMUM_SPARSE_HEMISPHERE_BALANCE
                && connectedComponentCount == 1;
    }

    static boolean isCompactPosteriorFullSection(
            final double aspect,
            final double occupancy,
            final double mirroredOverlap,
            final double hemisphereBalance,
            final double leftEdgeDispersion,
            final double rightEdgeDispersion,
            final int connectedComponentCount) {
        return aspect >= MINIMUM_COMPACT_POSTERIOR_ASPECT
                && occupancy >= MINIMUM_COMPACT_POSTERIOR_OCCUPANCY
                && mirroredOverlap >= MINIMUM_COMPACT_POSTERIOR_OVERLAP
                && hemisphereBalance >= MINIMUM_COMPACT_POSTERIOR_BALANCE
                && leftEdgeDispersion >= MINIMUM_COMPACT_POSTERIOR_EDGE
                && rightEdgeDispersion >= MINIMUM_COMPACT_POSTERIOR_EDGE
                && connectedComponentCount == 1;
    }

    static boolean isVeryCompactPosteriorFullSection(
            final double rawAspect,
            final double rawLeftEdgeDispersion,
            final double rawRightEdgeDispersion,
            final int rawConnectedComponentCount,
            final double envelopeOccupancy,
            final double envelopeMirroredOverlap,
            final double envelopeHemisphereBalance,
            final double envelopeAddedPreviewFraction) {
        return rawAspect >= MINIMUM_VERY_COMPACT_POSTERIOR_ASPECT
                && rawAspect < MAXIMUM_VERY_COMPACT_POSTERIOR_ASPECT
                && rawLeftEdgeDispersion
                        >= MINIMUM_COMPACT_POSTERIOR_EDGE
                && rawRightEdgeDispersion
                        >= MINIMUM_COMPACT_POSTERIOR_EDGE
                && rawConnectedComponentCount == 1
                && envelopeOccupancy >= MINIMUM_ENVELOPE_OCCUPANCY
                && envelopeMirroredOverlap >= MINIMUM_ENVELOPE_OVERLAP
                && envelopeHemisphereBalance >= MINIMUM_ENVELOPE_BALANCE
                && envelopeAddedPreviewFraction
                        <= MAXIMUM_ENVELOPE_ADDED_FRACTION;
    }

    /**
     * Measures an interior-filled copy; the supplied mask is unchanged.
     *
     * @param mask selected production tissue mask
     * @return auditable envelope-only measurements
     */
    public TissueEnvelopeMeasurements envelopeMeasurements(
            final BinaryMask mask) {
        if (mask == null || mask.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot measure an empty tissue mask");
        }
        final BinaryMask envelope = new TissueMaskEnvelope()
                .fillInteriorHoles(mask);
        final MaskBounds bounds = envelope.bounds();
        final BilateralMeasurements bilateral =
                bilateralMeasurements(envelope, bounds);
        final double occupancy = (double) envelope.foregroundCount()
                / (bounds.width() * bounds.height());
        final double addedFraction =
                (double) (envelope.foregroundCount()
                        - mask.foregroundCount())
                        / (mask.width() * mask.height());
        return new TissueEnvelopeMeasurements(
                occupancy,
                bilateral.mirroredOverlap(),
                bilateral.hemisphereBalance(),
                addedFraction);
    }

    private static ComponentMeasurements componentMeasurements(
            final BinaryMask mask) {
        final BitSet remaining = mask.copyBits();
        final int total = mask.foregroundCount();
        final int[] queue = new int[total];
        int count = 0;
        int substantialCount = 0;
        for (int start = remaining.nextSetBit(0);
                start >= 0;
                start = remaining.nextSetBit(0)) {
            count++;
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            remaining.clear(start);
            while (head < tail) {
                final int current = queue[head++];
                final int x = current % mask.width();
                final int y = current / mask.width();
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    for (int offsetX = -1; offsetX <= 1; offsetX++) {
                        if (offsetX == 0 && offsetY == 0) {
                            continue;
                        }
                        final int neighborX = x + offsetX;
                        final int neighborY = y + offsetY;
                        if (neighborX < 0 || neighborX >= mask.width()
                                || neighborY < 0
                                || neighborY >= mask.height()) {
                            continue;
                        }
                        final int neighbor =
                                neighborY * mask.width() + neighborX;
                        if (remaining.get(neighbor)) {
                            remaining.clear(neighbor);
                            queue[tail++] = neighbor;
                        }
                    }
                }
            }
            if ((long) tail * 20 >= total) {
                substantialCount++;
            }
        }
        return new ComponentMeasurements(count, substantialCount);
    }

    private static BilateralMeasurements bilateralMeasurements(
            final BinaryMask mask,
            final MaskBounds bounds) {
        long intersection = 0;
        long union = 0;
        long imageLeft = 0;
        long imageRight = 0;
        final int centerTwice =
                bounds.minimumX() + bounds.maximumX();
        for (int y = bounds.minimumY(); y <= bounds.maximumY(); y++) {
            for (int x = bounds.minimumX(); x <= bounds.maximumX(); x++) {
                final boolean foreground = mask.contains(x, y);
                final int reflectedX = centerTwice - x;
                final boolean reflected =
                        mask.contains(reflectedX, y);
                if (foreground && reflected) {
                    intersection++;
                }
                if (foreground || reflected) {
                    union++;
                }
                if (foreground) {
                    final int twiceX = 2 * x;
                    if (twiceX <= centerTwice) {
                        imageLeft++;
                    }
                    if (twiceX >= centerTwice) {
                        imageRight++;
                    }
                }
            }
        }
        final double mirroredOverlap = union == 0
                ? 0 : (double) intersection / union;
        final long largerHemisphere =
                Math.max(imageLeft, imageRight);
        final double hemisphereBalance = largerHemisphere == 0
                ? 0 : (double) Math.min(imageLeft, imageRight)
                        / largerHemisphere;
        return new BilateralMeasurements(
                mirroredOverlap, hemisphereBalance);
    }

    private static EdgeMeasurements edgeMeasurements(
            final BinaryMask mask,
            final MaskBounds bounds) {
        final int margin =
                Math.max(1, (int) Math.floor(bounds.height() * 0.12));
        final int firstY = bounds.minimumY() + margin;
        final int lastY = bounds.maximumY() - margin;
        final List<Double> left = new ArrayList<>();
        final List<Double> right = new ArrayList<>();
        for (int y = firstY; y <= lastY; y++) {
            int minimum = -1;
            int maximum = -1;
            for (int x = bounds.minimumX(); x <= bounds.maximumX(); x++) {
                if (mask.contains(x, y)) {
                    if (minimum < 0) {
                        minimum = x;
                    }
                    maximum = x;
                }
            }
            if (minimum >= 0) {
                left.add((double) minimum);
                right.add((double) maximum);
            }
        }
        if (left.size() < 3) {
            return new EdgeMeasurements(1, 1, 0, 0);
        }
        final double meanLeft = mean(left);
        final double meanRight = mean(right);
        return new EdgeMeasurements(
                Math.min(1, standardDeviation(left, meanLeft)
                        / bounds.width()),
                Math.min(1, standardDeviation(right, meanRight)
                        / bounds.width()),
                meanLeft,
                meanRight);
    }

    private static double mean(final List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue)
                .average().orElseThrow();
    }

    private static double standardDeviation(
            final List<Double> values,
            final double mean) {
        final double variance = values.stream()
                .mapToDouble(value -> {
                    final double difference = value - mean;
                    return difference * difference;
                })
                .average()
                .orElseThrow();
        return Math.sqrt(variance);
    }

    private record EdgeMeasurements(
            double leftDispersion,
            double rightDispersion,
            double meanLeft,
            double meanRight) {
    }

    private record BilateralMeasurements(
            double mirroredOverlap,
            double hemisphereBalance) {
    }

    private record ComponentMeasurements(
            int count,
            int substantialCount) {
    }
}
