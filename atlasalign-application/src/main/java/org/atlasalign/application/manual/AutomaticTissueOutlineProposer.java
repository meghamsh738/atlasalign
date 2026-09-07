package org.atlasalign.application.manual;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import org.atlasalign.application.ReviewPreviewDimensions;
import org.atlasalign.application.TissueMaskEnvelope;
import org.atlasalign.application.TissueSegmentationResult;
import org.atlasalign.application.VirtualHalfPayloadHashes;
import org.atlasalign.core.BinaryMask;
import org.atlasalign.core.Point2D;
import org.atlasalign.core.PreviewMapping;

/** Deterministic, source-read-only proposal of a full-section tissue outline. */
public final class AutomaticTissueOutlineProposer {

    public static final String ALGORITHM_REVISION =
            "largest-8c-fill-holes-resolve-microscopic-corner-contacts-v4";
    public static final int DEFAULT_MAXIMUM_GENERATED_VERTICES = 192;
    public static final int MINIMUM_GENERATED_VERTICES = 24;
    private static final int MINIMUM_COMPONENT_PIXELS = 9;
    private static final double MINIMUM_COMPONENT_FRACTION = 0.005;
    private static final double MAXIMUM_COMPONENT_FRACTION = 0.90;
    private static final int MAXIMUM_DISCARDED_COMPONENT_PIXELS = 8;
    private static final double MAXIMUM_DISCARDED_ENVELOPE_FRACTION = 0.0001;
    // Frozen above the observed s102 corner ambiguity, but below a long chain.
    private static final int MAXIMUM_DISCARDED_EIGHT_CONNECTED_SPAN_PIXELS = 8;
    private static final int MAXIMUM_INCLUDED_CORNER_COMPONENT_PIXELS = 4;
    private static final double MAXIMUM_INCLUDED_CORNER_FRACTION = 0.0001;
    private static final int MAXIMUM_INCLUDED_EIGHT_CONNECTED_SPAN_PIXELS = 4;

    /**
     * Creates a draft proposal that must be inspected and explicitly finished.
     * The supplied segmentation mask is never modified.
     */
    public ManualContour propose(
            final String contourId,
            final TissueSegmentationResult segmentation,
            final PreviewMapping mapping,
            final SourceImageIdentity sourceIdentity,
            final float[] copiedPreviewPixels,
            final ReviewPreviewDimensions immutableReviewPreviewIdentity) {
        return propose(contourId, segmentation, mapping, sourceIdentity,
                copiedPreviewPixels, immutableReviewPreviewIdentity,
                DEFAULT_MAXIMUM_GENERATED_VERTICES);
    }

    /**
     * Creates the same source-read-only proposal with a reviewer-selected
     * upper bound on editable vertices. Simplification remains deterministic
     * and topology preserving; the exact configured bound is retained in the
     * proposal provenance.
     */
    public ManualContour propose(
            final String contourId,
            final TissueSegmentationResult segmentation,
            final PreviewMapping mapping,
            final SourceImageIdentity sourceIdentity,
            final float[] copiedPreviewPixels,
            final ReviewPreviewDimensions immutableReviewPreviewIdentity,
            final int maximumGeneratedVertices) {
        Objects.requireNonNull(segmentation, "segmentation");
        Objects.requireNonNull(mapping, "mapping");
        Objects.requireNonNull(sourceIdentity, "sourceIdentity");
        Objects.requireNonNull(copiedPreviewPixels, "copiedPreviewPixels");
        Objects.requireNonNull(immutableReviewPreviewIdentity,
                "immutableReviewPreviewIdentity");
        if (maximumGeneratedVertices < MINIMUM_GENERATED_VERTICES
                || maximumGeneratedVertices
                        > DEFAULT_MAXIMUM_GENERATED_VERTICES) {
            throw new IllegalArgumentException(
                    "Automatic outline vertices must be between "
                            + MINIMUM_GENERATED_VERTICES + " and "
                            + DEFAULT_MAXIMUM_GENERATED_VERTICES);
        }
        validateDimensions(segmentation.mask(), mapping, sourceIdentity,
                copiedPreviewPixels, immutableReviewPreviewIdentity);
        final String copiedPreviewPixelSha256 =
                VirtualHalfPayloadHashes.pixelsSha256(copiedPreviewPixels);
        final String expectedPreviewPixelSha256 = immutableReviewPreviewIdentity
                .pixelsSha256().orElseThrow(() -> new IllegalArgumentException(
                        "The immutable review basis has no copied-preview pixel identity"));
        if (!expectedPreviewPixelSha256.equals(copiedPreviewPixelSha256)) {
            throw new IllegalArgumentException(
                    "Copied preview pixels do not match the immutable review-basis preview identity");
        }

        final BinaryMask input = segmentation.mask();
        final String inputHash = maskSha256(input);
        final BinaryMask component = largestEightConnectedComponent(input);
        validateComponent(component);
        final BinaryMask envelope = new TissueMaskEnvelope()
                .fillInteriorHoles(component);
        final String envelopeHash = maskSha256(envelope);
        final List<LatticePoint> rawLoop = largestSimpleExteriorLoop(envelope);
        final OutlineEnvelope outlineEnvelope = outlineEnvelope(
                envelope, rawLoop);
        final String outlinedEnvelopeHash = maskSha256(outlineEnvelope.mask());
        final String discardedPixelsHash = maskSha256(
                outlineEnvelope.discardedPixels());
        final String includedCornerBackgroundHash = maskSha256(
                outlineEnvelope.includedCornerBackgroundPixels());
        requireLoopRepresentsEnvelope(rawLoop, outlineEnvelope.mask());
        final List<LatticePoint> generatedLoop = resampleIfNeeded(
                rawLoop, maximumGeneratedVertices);
        if (!isSimple(generatedLoop)) {
            throw new IllegalArgumentException(
                    "The generated tissue outline is not a simple closed loop");
        }
        final List<ContourVertex> vertices = mapToSource(
                generatedLoop, mapping);
        final String loopHash = loopSha256(vertices);
        final AutomaticTissueOutlineProvenance provenance =
                new AutomaticTissueOutlineProvenance(
                        sourceIdentity, copiedPreviewPixelSha256,
                        segmentation.method(), segmentation.polarity(),
                        segmentation.threshold(), inputHash, envelopeHash,
                        outlinedEnvelopeHash, discardedPixelsHash,
                        includedCornerBackgroundHash,
                        maximumGeneratedVertices
                                == DEFAULT_MAXIMUM_GENERATED_VERTICES
                                ? ALGORITHM_REVISION
                                : ALGORITHM_REVISION + ";maxVertices="
                                        + maximumGeneratedVertices,
                        loopHash,
                        input.width(), input.height(),
                        component.foregroundCount(), envelope.foregroundCount(),
                        outlineEnvelope.mask().foregroundCount(),
                        outlineEnvelope.discardedComponentCount(),
                        outlineEnvelope.discardedPixelCount(),
                        outlineEnvelope.largestDiscardedComponentPixelCount(),
                        outlineEnvelope.largestDiscardedEightConnectedSpanPixels(),
                        outlineEnvelope.includedCornerBackgroundComponentCount(),
                        outlineEnvelope.includedCornerBackgroundPixelCount(),
                        outlineEnvelope
                                .largestIncludedCornerBackgroundComponentPixelCount(),
                        outlineEnvelope
                                .largestIncludedCornerBackgroundEightConnectedSpanPixels(),
                        rawLoop.size(), vertices.size(), false);
        return new ManualContour(
                contourId, ManualContourKind.TISSUE_OUTLINE,
                ContourTopology.CLOSED, ContourCaptureStatus.DRAFT,
                AnatomicalSide.BILATERAL, ContourCompleteness.COMPLETE,
                java.util.Optional.empty(), sourceIdentity, vertices, Set.of(),
                java.util.Optional.of(provenance));
    }

    private static void validateDimensions(
            final BinaryMask mask,
            final PreviewMapping mapping,
            final SourceImageIdentity sourceIdentity,
            final float[] copiedPreviewPixels,
            final ReviewPreviewDimensions immutableReviewPreviewIdentity) {
        if (mask.width() != mapping.previewWidth()
                || mask.height() != mapping.previewHeight()) {
            throw new IllegalArgumentException(
                    "Segmentation mask dimensions must match the preview mapping");
        }
        if (sourceIdentity.width() != mapping.sourceWidth()
                || sourceIdentity.height() != mapping.sourceHeight()) {
            throw new IllegalArgumentException(
                    "Source identity dimensions must match the preview mapping");
        }
        if (immutableReviewPreviewIdentity.width() != mapping.previewWidth()
                || immutableReviewPreviewIdentity.height()
                        != mapping.previewHeight()) {
            throw new IllegalArgumentException(
                    "Immutable review-basis preview dimensions must match the preview mapping");
        }
        if (copiedPreviewPixels.length != Math.multiplyExact(
                mapping.previewWidth(), mapping.previewHeight())) {
            throw new IllegalArgumentException(
                    "Copied preview pixel count must match the preview mapping");
        }
    }

    private static BinaryMask largestEightConnectedComponent(
            final BinaryMask mask) {
        if (mask.isEmpty()) {
            throw new IllegalArgumentException(
                    "An empty segmentation cannot propose a tissue outline");
        }
        final int width = mask.width();
        final int height = mask.height();
        final BitSet foreground = mask.copyBits();
        final BitSet visited = new BitSet(Math.multiplyExact(width, height));
        BitSet largest = new BitSet();
        final int[] queue = new int[Math.multiplyExact(width, height)];
        for (int seed = 0; seed < width * height; seed++) {
            if (!foreground.get(seed) || visited.get(seed)) {
                continue;
            }
            final BitSet component = new BitSet();
            int head = 0;
            int tail = 0;
            queue[tail++] = seed;
            visited.set(seed);
            while (head < tail) {
                final int current = queue[head++];
                component.set(current);
                final int x = current % width;
                final int y = current / width;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        final int nx = x + dx;
                        final int ny = y + dy;
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height
                                || !mask.contains(nx, ny)) {
                            continue;
                        }
                        final int next = ny * width + nx;
                        if (!visited.get(next)) {
                            visited.set(next);
                            queue[tail++] = next;
                        }
                    }
                }
            }
            // Strictly greater preserves the first row-major component on ties.
            if (component.cardinality() > largest.cardinality()) {
                largest = component;
            }
        }
        return BinaryMask.fromBitSet(width, height, largest);
    }

    private static void validateComponent(final BinaryMask component) {
        final int count = component.foregroundCount();
        final int total = Math.multiplyExact(component.width(), component.height());
        final double fraction = (double) count / total;
        if (count < MINIMUM_COMPONENT_PIXELS
                || fraction < MINIMUM_COMPONENT_FRACTION
                || fraction > MAXIMUM_COMPONENT_FRACTION) {
            throw new IllegalArgumentException(
                    "The largest tissue component has implausible geometry");
        }
        for (int x = 0; x < component.width(); x++) {
            if (component.contains(x, 0)
                    || component.contains(x, component.height() - 1)) {
                throw new IllegalArgumentException(
                        "The largest tissue component touches the preview border");
            }
        }
        for (int y = 1; y + 1 < component.height(); y++) {
            if (component.contains(0, y)
                    || component.contains(component.width() - 1, y)) {
                throw new IllegalArgumentException(
                        "The largest tissue component touches the preview border");
            }
        }
    }

    /**
     * Returns the unique Jordan-domain represented by the largest exterior
     * cycle of an already hole-filled 8-connected envelope. Microscopic
     * corner-only foreground specks are omitted. Microscopic background
     * pockets whose only exterior route is a zero-width diagonal corner are
     * included in the draft contour. Both operations are strictly capped and
     * recorded; every material lobe or positive-width gap fails closed.
     */
    private static OutlineEnvelope outlineEnvelope(
            final BinaryMask envelope,
            final List<LatticePoint> loop) {
        final int width = envelope.width();
        final int height = envelope.height();
        final BitSet interior = rasterizeInterior(width, height, loop);
        final BitSet outsideEnvelope = (BitSet) interior.clone();
        outsideEnvelope.andNot(envelope.copyBits());
        final List<Integer> includedCornerSizes = fourConnectedSizes(
                width, height, outsideEnvelope);
        final int includedCornerPixels = outsideEnvelope.cardinality();
        final int largestIncludedCorner = includedCornerSizes.stream()
                .mapToInt(Integer::intValue).max().orElse(0);
        final int largestIncludedEightConnectedSpan =
                largestEightConnectedSpan(width, height, outsideEnvelope);
        if (largestIncludedCorner > MAXIMUM_INCLUDED_CORNER_COMPONENT_PIXELS
                || includedCornerPixels > envelope.foregroundCount()
                        * MAXIMUM_INCLUDED_CORNER_FRACTION
                || largestIncludedEightConnectedSpan
                        > MAXIMUM_INCLUDED_EIGHT_CONNECTED_SPAN_PIXELS) {
            throw new IllegalArgumentException(
                    "The generated exterior loop would enclose a material background gap and cannot represent the tissue without inventing tissue"
                            + " (enclosedBackgroundPixels="
                            + includedCornerPixels + ", components="
                            + includedCornerSizes + ", largest8cSpan="
                            + largestIncludedEightConnectedSpan + ")");
        }
        final BitSet outlined = interior;
        final BitSet discarded = envelope.copyBits();
        discarded.andNot(outlined);
        final List<Integer> discardedComponentSizes = fourConnectedSizes(
                width, height, discarded);
        int discardedComponents = 0;
        int largestDiscarded = 0;
        for (final int candidateSize : discardedComponentSizes) {
            discardedComponents++;
            largestDiscarded = Math.max(
                    largestDiscarded, candidateSize);
        }
        final int discardedPixels = discarded.cardinality();
        final int largestDiscardedEightConnectedSpan =
                largestEightConnectedSpan(width, height, discarded);
        if (largestDiscarded > MAXIMUM_DISCARDED_COMPONENT_PIXELS
                || discardedPixels > envelope.foregroundCount()
                        * MAXIMUM_DISCARDED_ENVELOPE_FRACTION
                || largestDiscardedEightConnectedSpan
                        > MAXIMUM_DISCARDED_EIGHT_CONNECTED_SPAN_PIXELS) {
            throw new IllegalArgumentException(
                    "The selected 8-connected tissue envelope has a material diagonal-only lobe and cannot be represented by one simple outline without inventing tissue"
                            + " (discardedComponents=" + discardedComponents
                            + ", discardedPixels=" + discardedPixels
                            + ", largestDiscarded=" + largestDiscarded
                            + ", largest8cSpan="
                            + largestDiscardedEightConnectedSpan + ")");
        }
        return new OutlineEnvelope(
                BinaryMask.fromBitSet(width, height, outlined),
                BinaryMask.fromBitSet(width, height, discarded),
                BinaryMask.fromBitSet(width, height, outsideEnvelope),
                discardedComponents, discardedPixels, largestDiscarded,
                largestDiscardedEightConnectedSpan,
                includedCornerSizes.size(), includedCornerPixels,
                largestIncludedCorner, largestIncludedEightConnectedSpan);
    }

    private static int largestEightConnectedSpan(
            final int width,
            final int height,
            final BitSet foreground) {
        final int size = Math.multiplyExact(width, height);
        final BitSet visited = new BitSet(size);
        final int[] queue = new int[size];
        int largestSpan = 0;
        for (int seed = foreground.nextSetBit(0);
                seed >= 0;
                seed = foreground.nextSetBit(seed + 1)) {
            if (visited.get(seed)) {
                continue;
            }
            int minimumX = seed % width;
            int maximumX = minimumX;
            int minimumY = seed / width;
            int maximumY = minimumY;
            int head = 0;
            int tail = 0;
            queue[tail++] = seed;
            visited.set(seed);
            while (head < tail) {
                final int current = queue[head++];
                final int x = current % width;
                final int y = current / width;
                minimumX = Math.min(minimumX, x);
                maximumX = Math.max(maximumX, x);
                minimumY = Math.min(minimumY, y);
                maximumY = Math.max(maximumY, y);
                for (int deltaY = -1; deltaY <= 1; deltaY++) {
                    for (int deltaX = -1; deltaX <= 1; deltaX++) {
                        if (deltaX == 0 && deltaY == 0) {
                            continue;
                        }
                        final int nextX = x + deltaX;
                        final int nextY = y + deltaY;
                        if (nextX < 0 || nextX >= width
                                || nextY < 0 || nextY >= height) {
                            continue;
                        }
                        final int next = nextY * width + nextX;
                        if (foreground.get(next) && !visited.get(next)) {
                            visited.set(next);
                            queue[tail++] = next;
                        }
                    }
                }
            }
            largestSpan = Math.max(largestSpan, Math.max(
                    maximumX - minimumX + 1,
                    maximumY - minimumY + 1));
        }
        return largestSpan;
    }

    private static BitSet rasterizeInterior(
            final int width,
            final int height,
            final List<LatticePoint> loop) {
        final List<List<Integer>> rowIntersections = new ArrayList<>(height);
        for (int y = 0; y < height; y++) {
            rowIntersections.add(new ArrayList<>());
        }
        for (int index = 0; index < loop.size(); index++) {
            final LatticePoint from = loop.get(index);
            final LatticePoint to = loop.get((index + 1) % loop.size());
            if (from.x() != to.x()) {
                continue;
            }
            final int minimumY = Math.max(0, Math.min(from.y(), to.y()));
            final int maximumY = Math.min(
                    height, Math.max(from.y(), to.y()));
            for (int y = minimumY; y < maximumY; y++) {
                rowIntersections.get(y).add(from.x());
            }
        }
        final BitSet interior = new BitSet(Math.multiplyExact(width, height));
        for (int y = 0; y < height; y++) {
            final List<Integer> intersections = rowIntersections.get(y);
            intersections.sort(Integer::compareTo);
            if (intersections.size() % 2 != 0) {
                throw new IllegalArgumentException(
                        "The generated exterior loop has inconsistent scanline intersections");
            }
            for (int pair = 0; pair < intersections.size(); pair += 2) {
                final int start = Math.max(0, intersections.get(pair));
                final int end = Math.min(width, intersections.get(pair + 1));
                if (end > start) {
                    interior.set(y * width + start, y * width + end);
                }
            }
        }
        return interior;
    }

    private static List<Integer> fourConnectedSizes(
            final int width,
            final int height,
            final BitSet foreground) {
        final int size = Math.multiplyExact(width, height);
        final BitSet visited = new BitSet(size);
        final int[] queue = new int[size];
        final List<Integer> sizes = new ArrayList<>();
        for (int seed = foreground.nextSetBit(0);
                seed >= 0;
                seed = foreground.nextSetBit(seed + 1)) {
            if (visited.get(seed)) {
                continue;
            }
            int count = 0;
            int head = 0;
            int tail = 0;
            queue[tail++] = seed;
            visited.set(seed);
            while (head < tail) {
                final int current = queue[head++];
                count++;
                final int x = current % width;
                final int y = current / width;
                if (x > 0) {
                    tail = enqueueFourConnected(
                            current - 1, foreground, visited, queue, tail);
                }
                if (x + 1 < width) {
                    tail = enqueueFourConnected(
                            current + 1, foreground, visited, queue, tail);
                }
                if (y > 0) {
                    tail = enqueueFourConnected(
                            current - width, foreground, visited, queue, tail);
                }
                if (y + 1 < height) {
                    tail = enqueueFourConnected(
                            current + width, foreground, visited, queue, tail);
                }
            }
            sizes.add(count);
        }
        return List.copyOf(sizes);
    }

    private static int enqueueFourConnected(
            final int index,
            final BitSet foreground,
            final BitSet visited,
            final int[] queue,
            final int tail) {
        if (!foreground.get(index) || visited.get(index)) {
            return tail;
        }
        visited.set(index);
        queue[tail] = index;
        return tail + 1;
    }

    private static List<LatticePoint> largestSimpleExteriorLoop(
            final BinaryMask mask) {
        final Set<Edge> edges = exposedClockwiseEdges(mask);
        final Map<LatticePoint, List<Edge>> outgoing = new HashMap<>();
        for (final Edge edge : edges) {
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>())
                    .add(edge);
        }
        outgoing.values().forEach(list -> list.sort(Comparator
                .comparingInt((Edge edge) -> direction(edge))
                .thenComparingInt(edge -> edge.to().y())
                .thenComparingInt(edge -> edge.to().x())));
        final Set<Edge> unused = new LinkedHashSet<>(edges.stream()
                .sorted(Comparator
                        .comparingInt((Edge edge) -> edge.from().y())
                        .thenComparingInt(edge -> edge.from().x())
                        .thenComparingInt(AutomaticTissueOutlineProposer::direction))
                .toList());
        List<LatticePoint> best = List.of();
        double bestArea = 0.0;
        while (!unused.isEmpty()) {
            final Edge first = unused.iterator().next();
            final List<LatticePoint> loop = traceLoop(
                    first, outgoing, unused, edges.size());
            for (final List<LatticePoint> cycle
                    : splitAtRepeatedVertices(loop)) {
                final List<LatticePoint> simplified = simplifyCollinear(cycle);
                final double area = signedArea(simplified);
                if (area > bestArea && isSimple(simplified)) {
                    best = simplified;
                    bestArea = area;
                }
            }
        }
        if (best.size() < 3 || bestArea <= 0.0) {
            throw new IllegalArgumentException(
                    "No simple exterior tissue loop could be traced");
        }
        return best;
    }

    private static List<List<LatticePoint>> splitAtRepeatedVertices(
            final List<LatticePoint> loop) {
        if (loop.size() < 3) {
            return List.of();
        }
        final List<List<LatticePoint>> pending = new ArrayList<>();
        final List<List<LatticePoint>> result = new ArrayList<>();
        pending.add(loop);
        while (!pending.isEmpty()) {
            final List<LatticePoint> candidate = pending.remove(
                    pending.size() - 1);
            final Map<LatticePoint, Integer> firstIndex = new HashMap<>();
            int repeatStart = -1;
            int repeatEnd = -1;
            for (int index = 0; index < candidate.size(); index++) {
                final Integer previous = firstIndex.putIfAbsent(
                        candidate.get(index), index);
                if (previous != null) {
                    repeatStart = previous;
                    repeatEnd = index;
                    break;
                }
            }
            if (repeatStart < 0) {
                if (candidate.size() >= 3) {
                    result.add(List.copyOf(candidate));
                }
                continue;
            }
            final List<LatticePoint> first = new ArrayList<>(
                    candidate.subList(repeatStart, repeatEnd));
            final List<LatticePoint> second = new ArrayList<>(
                    candidate.size() - first.size());
            second.addAll(candidate.subList(repeatEnd, candidate.size()));
            second.addAll(candidate.subList(0, repeatStart));
            if (first.size() >= 3) {
                pending.add(first);
            }
            if (second.size() >= 3) {
                pending.add(second);
            }
        }
        return List.copyOf(result);
    }

    private static void requireLoopRepresentsEnvelope(
            final List<LatticePoint> loop,
            final BinaryMask envelope) {
        final double enclosedPixelArea = signedArea(loop);
        if (enclosedPixelArea != envelope.foregroundCount()) {
            throw new IllegalArgumentException(
                    "The selected 8-connected tissue envelope has multiple or self-touching exterior loops and cannot be represented by one simple outline");
        }
    }

    private static Set<Edge> exposedClockwiseEdges(final BinaryMask mask) {
        final Set<Edge> edges = new HashSet<>();
        for (int y = 0; y < mask.height(); y++) {
            for (int x = 0; x < mask.width(); x++) {
                if (!mask.contains(x, y)) {
                    continue;
                }
                if (!mask.contains(x, y - 1)) {
                    edges.add(new Edge(point(x, y), point(x + 1, y)));
                }
                if (!mask.contains(x + 1, y)) {
                    edges.add(new Edge(point(x + 1, y), point(x + 1, y + 1)));
                }
                if (!mask.contains(x, y + 1)) {
                    edges.add(new Edge(point(x + 1, y + 1), point(x, y + 1)));
                }
                if (!mask.contains(x - 1, y)) {
                    edges.add(new Edge(point(x, y + 1), point(x, y)));
                }
            }
        }
        return edges;
    }

    private static List<LatticePoint> traceLoop(
            final Edge first,
            final Map<LatticePoint, List<Edge>> outgoing,
            final Set<Edge> unused,
            final int maximumSteps) {
        final List<LatticePoint> loop = new ArrayList<>();
        Edge current = first;
        for (int step = 0; step <= maximumSteps; step++) {
            if (!unused.remove(current)) {
                return List.of();
            }
            loop.add(current.from());
            if (current.to().equals(first.from())) {
                return loop;
            }
            final Edge previous = current;
            current = outgoing.getOrDefault(previous.to(), List.of()).stream()
                    .filter(unused::contains)
                    .min(Comparator
                            .comparingInt((Edge candidate) -> turnPriority(
                                    direction(previous), direction(candidate)))
                            .thenComparingInt(AutomaticTissueOutlineProposer::direction))
                    .orElse(null);
            if (current == null) {
                return List.of();
            }
        }
        return List.of();
    }

    private static int turnPriority(
            final int incomingDirection,
            final int candidateDirection) {
        final int turn = (candidateDirection - incomingDirection + 4) % 4;
        return switch (turn) {
            case 1 -> 0; // right turn: retain the foreground on the right
            case 0 -> 1;
            case 3 -> 2;
            default -> 3;
        };
    }

    private static int direction(final Edge edge) {
        final int dx = edge.to().x() - edge.from().x();
        final int dy = edge.to().y() - edge.from().y();
        if (dx == 1) {
            return 0;
        }
        if (dy == 1) {
            return 1;
        }
        if (dx == -1) {
            return 2;
        }
        if (dy == -1) {
            return 3;
        }
        throw new IllegalArgumentException("Boundary edge must be axis aligned");
    }

    private static List<LatticePoint> simplifyCollinear(
            final List<LatticePoint> loop) {
        if (loop.size() < 3) {
            return List.of();
        }
        final List<LatticePoint> result = new ArrayList<>();
        for (int i = 0; i < loop.size(); i++) {
            final LatticePoint previous = loop.get(
                    (i + loop.size() - 1) % loop.size());
            final LatticePoint current = loop.get(i);
            final LatticePoint next = loop.get((i + 1) % loop.size());
            final long cross = (long) (current.x() - previous.x())
                    * (next.y() - current.y())
                    - (long) (current.y() - previous.y())
                    * (next.x() - current.x());
            if (cross != 0) {
                result.add(current);
            }
        }
        return List.copyOf(result);
    }

    private static List<LatticePoint> resampleIfNeeded(
            final List<LatticePoint> loop) {
        return resampleIfNeeded(loop, DEFAULT_MAXIMUM_GENERATED_VERTICES);
    }

    private static List<LatticePoint> resampleIfNeeded(
            final List<LatticePoint> loop,
            final int maximumGeneratedVertices) {
        if (loop.size() <= maximumGeneratedVertices) {
            return loop;
        }
        final List<SimplificationNode> nodes = new ArrayList<>(loop.size());
        for (int index = 0; index < loop.size(); index++) {
            nodes.add(new SimplificationNode(loop.get(index), index));
        }
        for (int index = 0; index < nodes.size(); index++) {
            final SimplificationNode node = nodes.get(index);
            node.previous = nodes.get((index + nodes.size() - 1) % nodes.size());
            node.next = nodes.get((index + 1) % nodes.size());
        }
        final PriorityQueue<SimplificationCandidate> candidates =
                new PriorityQueue<>(Comparator
                        .comparingLong(SimplificationCandidate::area)
                        .thenComparingInt(SimplificationCandidate::originalIndex));
        nodes.forEach(node -> candidates.add(candidate(node)));
        int remaining = nodes.size();
        int previousPassRemaining = remaining;
        while (remaining > maximumGeneratedVertices) {
            if (candidates.isEmpty()) {
                if (remaining == previousPassRemaining) {
                    throw new IllegalArgumentException(
                            "The tissue outline cannot be reduced safely without changing topology");
                }
                previousPassRemaining = remaining;
                nodes.stream().filter(node -> !node.removed)
                        .forEach(node -> candidates.add(candidate(node)));
            }
            final SimplificationCandidate candidate = candidates.remove();
            final SimplificationNode node = candidate.node();
            if (node.removed || node.version != candidate.version()) {
                continue;
            }
            if (!canRemove(node, nodes)) {
                // Geometry may change only around neighbors. This candidate
                // remains unsafe until one of those neighbors changes.
                continue;
            }
            node.removed = true;
            node.previous.next = node.next;
            node.next.previous = node.previous;
            remaining--;
            node.previous.version++;
            node.next.version++;
            candidates.add(candidate(node.previous));
            candidates.add(candidate(node.next));
        }
        final List<LatticePoint> sampled = new ArrayList<>(remaining);
        final SimplificationNode start = nodes.stream()
                .filter(node -> !node.removed)
                .min(Comparator.comparingInt(node -> node.originalIndex))
                .orElseThrow();
        SimplificationNode current = start;
        do {
            sampled.add(current.point);
            current = current.next;
        } while (current != start);
        if (!isSimple(sampled) || signedArea(sampled) <= 0.0) {
            throw new IllegalArgumentException(
                    "Deterministic outline reduction would change topology");
        }
        return List.copyOf(sampled);
    }

    private static SimplificationCandidate candidate(
            final SimplificationNode node) {
        return new SimplificationCandidate(
                node, triangleArea(node.previous.point, node.point,
                        node.next.point),
                node.originalIndex, node.version);
    }

    private static long triangleArea(
            final LatticePoint a,
            final LatticePoint b,
            final LatticePoint c) {
        return Math.abs(orientation(a, b, c));
    }

    private static boolean canRemove(
            final SimplificationNode removed,
            final List<SimplificationNode> nodes) {
        if (removed.previous == removed.next) {
            return false;
        }
        final LatticePoint start = removed.previous.point;
        final LatticePoint end = removed.next.point;
        for (final SimplificationNode node : nodes) {
            if (node.removed || node == removed
                    || node == removed.previous || node == removed.next
                    || node.next == removed || node.next == removed.previous
                    || node.next == removed.next) {
                continue;
            }
            if (segmentsIntersect(start, end, node.point, node.next.point)) {
                return false;
            }
        }
        return true;
    }

    private static List<ContourVertex> mapToSource(
            final List<LatticePoint> loop,
            final PreviewMapping mapping) {
        final List<ContourVertex> vertices = new ArrayList<>(loop.size());
        for (int index = 0; index < loop.size(); index++) {
            final LatticePoint point = loop.get(index);
            final Point2D source = mapping.previewToSource(
                    new Point2D(point.x() - 0.5, point.y() - 0.5));
            vertices.add(new ContourVertex(
                    String.format("auto-%04d", index),
                    new SourcePixelPoint(source.x(), source.y())));
        }
        return List.copyOf(vertices);
    }

    private static boolean isSimple(final List<LatticePoint> loop) {
        if (loop.size() < 3 || new HashSet<>(loop).size() != loop.size()) {
            return false;
        }
        for (int first = 0; first < loop.size(); first++) {
            final LatticePoint a = loop.get(first);
            final LatticePoint b = loop.get((first + 1) % loop.size());
            for (int second = first + 1; second < loop.size(); second++) {
                if (second == first || second == (first + 1) % loop.size()
                        || first == (second + 1) % loop.size()) {
                    continue;
                }
                final LatticePoint c = loop.get(second);
                final LatticePoint d = loop.get((second + 1) % loop.size());
                if (segmentsIntersect(a, b, c, d)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean segmentsIntersect(
            final LatticePoint a,
            final LatticePoint b,
            final LatticePoint c,
            final LatticePoint d) {
        final long abC = orientation(a, b, c);
        final long abD = orientation(a, b, d);
        final long cdA = orientation(c, d, a);
        final long cdB = orientation(c, d, b);
        return (abC == 0 && onSegment(a, b, c))
                || (abD == 0 && onSegment(a, b, d))
                || (cdA == 0 && onSegment(c, d, a))
                || (cdB == 0 && onSegment(c, d, b))
                || ((abC > 0) != (abD > 0) && (cdA > 0) != (cdB > 0));
    }

    private static long orientation(
            final LatticePoint a,
            final LatticePoint b,
            final LatticePoint c) {
        return (long) (b.x() - a.x()) * (c.y() - a.y())
                - (long) (b.y() - a.y()) * (c.x() - a.x());
    }

    private static boolean onSegment(
            final LatticePoint a,
            final LatticePoint b,
            final LatticePoint point) {
        return point.x() >= Math.min(a.x(), b.x())
                && point.x() <= Math.max(a.x(), b.x())
                && point.y() >= Math.min(a.y(), b.y())
                && point.y() <= Math.max(a.y(), b.y());
    }

    private static double signedArea(final List<LatticePoint> loop) {
        long twiceArea = 0;
        for (int i = 0; i < loop.size(); i++) {
            final LatticePoint current = loop.get(i);
            final LatticePoint next = loop.get((i + 1) % loop.size());
            twiceArea += (long) current.x() * next.y()
                    - (long) current.y() * next.x();
        }
        return twiceArea / 2.0;
    }

    private static String maskSha256(final BinaryMask mask) {
        final MessageDigest digest = digest();
        digest.update(intBytes(mask.width()));
        digest.update(intBytes(mask.height()));
        for (int y = 0; y < mask.height(); y++) {
            for (int x = 0; x < mask.width(); x++) {
                digest.update((byte) (mask.contains(x, y) ? 1 : 0));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String loopSha256(final List<ContourVertex> vertices) {
        final MessageDigest digest = digest();
        digest.update(intBytes(vertices.size()));
        for (final ContourVertex vertex : vertices) {
            digest.update(longBytes(Double.doubleToLongBits(vertex.point().x())));
            digest.update(longBytes(Double.doubleToLongBits(vertex.point().y())));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static byte[] intBytes(final int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static byte[] longBytes(final long value) {
        return ByteBuffer.allocate(Long.BYTES).putLong(value).array();
    }

    private static LatticePoint point(final int x, final int y) {
        return new LatticePoint(x, y);
    }

    private record LatticePoint(int x, int y) { }

    private record Edge(LatticePoint from, LatticePoint to) { }

    private record OutlineEnvelope(
            BinaryMask mask,
            BinaryMask discardedPixels,
            BinaryMask includedCornerBackgroundPixels,
            int discardedComponentCount,
            int discardedPixelCount,
            int largestDiscardedComponentPixelCount,
            int largestDiscardedEightConnectedSpanPixels,
            int includedCornerBackgroundComponentCount,
            int includedCornerBackgroundPixelCount,
            int largestIncludedCornerBackgroundComponentPixelCount,
            int largestIncludedCornerBackgroundEightConnectedSpanPixels) { }

    private static final class SimplificationNode {
        private final LatticePoint point;
        private final int originalIndex;
        private SimplificationNode previous;
        private SimplificationNode next;
        private int version;
        private boolean removed;

        private SimplificationNode(
                final LatticePoint point,
                final int originalIndex) {
            this.point = point;
            this.originalIndex = originalIndex;
        }
    }

    private record SimplificationCandidate(
            SimplificationNode node,
            long area,
            int originalIndex,
            int version) { }
}
