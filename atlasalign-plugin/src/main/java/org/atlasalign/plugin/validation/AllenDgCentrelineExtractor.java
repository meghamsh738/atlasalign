package org.atlasalign.plugin.validation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.atlasalign.application.dg.AnatomicalSide;
import org.atlasalign.atlas.AtlasCoronalPlane;
import org.atlasalign.atlas.AtlasOntology;
import org.atlasalign.atlas.AtlasRegion;
import org.atlasalign.core.Point2D;

/**
 * Derives side-preserving centreline candidates from verified Allen DG-sg
 * label pixels. This validation adapter reads copied plane arrays only.
 */
public final class AllenDgCentrelineExtractor {

    public static final String ALLEN_DG_LABEL_ACRONYM = "DG-sg";

    public Map<AnatomicalSide, List<Point2D>> extract(
            final AtlasCoronalPlane plane,
            final AtlasOntology ontology) {
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(ontology, "ontology");
        final AtlasRegion dg = ontology.search(
                        ALLEN_DG_LABEL_ACRONYM, 20).stream()
                .filter(region -> region.acronym().equals(
                        ALLEN_DG_LABEL_ACRONYM))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Verified atlas ontology has no exact DG-sg region"));
        final Set<Integer> identifiers = new HashSet<>();
        identifiers.add(dg.id());
        ontology.descendants(dg.id()).forEach(
                region -> identifiers.add(region.id()));
        return extractForRegionIds(plane, identifiers);
    }

    Map<AnatomicalSide, List<Point2D>> extractForRegionIds(
            final AtlasCoronalPlane plane,
            final Set<Integer> identifiers) {
        Objects.requireNonNull(plane, "plane");
        if (Objects.requireNonNull(identifiers, "identifiers").isEmpty()) {
            throw new IllegalArgumentException(
                    "DG region identifier set must not be empty");
        }
        final int width = plane.width();
        final int height = plane.height();
        final int[] annotations = plane.annotationId();
        final EnumMap<AnatomicalSide, List<Point2D>> result =
                new EnumMap<>(AnatomicalSide.class);
        for (final AnatomicalSide side : AnatomicalSide.values()) {
            final boolean[] mask = new boolean[annotations.length];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    final boolean inSide = side == AnatomicalSide.LEFT
                            ? x < width / 2.0 : x >= width / 2.0;
                    mask[y * width + x] = inSide
                            && identifiers.contains(annotations[y * width + x]);
                }
            }
            final boolean[] component = largestComponent(mask, width, height);
            if (count(component) < 2) {
                continue;
            }
            final boolean[] skeleton = zhangSuen(component, width, height);
            final List<Point2D> centreline = longestSkeletonPath(
                    skeleton, width, height);
            if (centreline.size() >= 2) {
                result.put(side, centreline);
            }
        }
        return Map.copyOf(result);
    }

    private static boolean[] largestComponent(
            final boolean[] source,
            final int width,
            final int height) {
        final boolean[] visited = new boolean[source.length];
        int[] largest = new int[0];
        for (int start = 0; start < source.length; start++) {
            if (!source[start] || visited[start]) {
                continue;
            }
            final int[] queue = new int[source.length];
            final int[] members = new int[source.length];
            int head = 0;
            int tail = 0;
            int size = 0;
            queue[tail++] = start;
            visited[start] = true;
            while (head < tail) {
                final int current = queue[head++];
                members[size++] = current;
                final int x = current % width;
                final int y = current / width;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        final int nx = x + dx;
                        final int ny = y + dy;
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                            continue;
                        }
                        final int next = ny * width + nx;
                        if (source[next] && !visited[next]) {
                            visited[next] = true;
                            queue[tail++] = next;
                        }
                    }
                }
            }
            if (size > largest.length) {
                largest = Arrays.copyOf(members, size);
            }
        }
        final boolean[] result = new boolean[source.length];
        for (final int index : largest) {
            result[index] = true;
        }
        return result;
    }

    private static boolean[] zhangSuen(
            final boolean[] source,
            final int width,
            final int height) {
        final boolean[] result = source.clone();
        boolean changed;
        do {
            changed = thinSubstep(result, width, height, true);
            changed |= thinSubstep(result, width, height, false);
        } while (changed);
        return result;
    }

    private static boolean thinSubstep(
            final boolean[] pixels,
            final int width,
            final int height,
            final boolean first) {
        final boolean[] remove = new boolean[pixels.length];
        boolean changed = false;
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                final int index = y * width + x;
                if (!pixels[index]) {
                    continue;
                }
                final boolean[] neighbor = {
                    pixels[(y - 1) * width + x],
                    pixels[(y - 1) * width + x + 1],
                    pixels[y * width + x + 1],
                    pixels[(y + 1) * width + x + 1],
                    pixels[(y + 1) * width + x],
                    pixels[(y + 1) * width + x - 1],
                    pixels[y * width + x - 1],
                    pixels[(y - 1) * width + x - 1]
                };
                int foreground = 0;
                int transitions = 0;
                for (int n = 0; n < neighbor.length; n++) {
                    foreground += neighbor[n] ? 1 : 0;
                    if (!neighbor[n]
                            && neighbor[(n + 1) % neighbor.length]) {
                        transitions++;
                    }
                }
                final boolean conditionA = first
                        ? !(neighbor[0] && neighbor[2] && neighbor[4])
                                && !(neighbor[2] && neighbor[4] && neighbor[6])
                        : !(neighbor[0] && neighbor[2] && neighbor[6])
                                && !(neighbor[0] && neighbor[4] && neighbor[6]);
                if (foreground >= 2 && foreground <= 6
                        && transitions == 1 && conditionA) {
                    remove[index] = true;
                    changed = true;
                }
            }
        }
        for (int index = 0; index < pixels.length; index++) {
            if (remove[index]) {
                pixels[index] = false;
            }
        }
        return changed;
    }

    private static List<Point2D> longestSkeletonPath(
            final boolean[] skeleton,
            final int width,
            final int height) {
        int start = -1;
        for (int index = 0; index < skeleton.length; index++) {
            if (skeleton[index]) {
                start = index;
                break;
            }
        }
        if (start < 0) {
            return List.of();
        }
        final BfsResult first = bfs(start, skeleton, width, height);
        final BfsResult second = bfs(
                first.farthestIndex(), skeleton, width, height);
        final ArrayDeque<Point2D> path = new ArrayDeque<>();
        int current = second.farthestIndex();
        while (current >= 0) {
            path.addFirst(new Point2D(current % width, current / width));
            if (current == first.farthestIndex()) {
                break;
            }
            current = second.parent()[current];
        }
        return List.copyOf(path);
    }

    private static BfsResult bfs(
            final int start,
            final boolean[] skeleton,
            final int width,
            final int height) {
        final int[] distance = new int[skeleton.length];
        final int[] parent = new int[skeleton.length];
        Arrays.fill(distance, -1);
        Arrays.fill(parent, -1);
        final int[] queue = new int[skeleton.length];
        int head = 0;
        int tail = 0;
        queue[tail++] = start;
        distance[start] = 0;
        int farthest = start;
        while (head < tail) {
            final int current = queue[head++];
            if (distance[current] > distance[farthest]
                    || distance[current] == distance[farthest]
                    && current < farthest) {
                farthest = current;
            }
            final int x = current % width;
            final int y = current / width;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    final int nx = x + dx;
                    final int ny = y + dy;
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                        continue;
                    }
                    final int next = ny * width + nx;
                    if (skeleton[next] && distance[next] < 0) {
                        distance[next] = distance[current] + 1;
                        parent[next] = current;
                        queue[tail++] = next;
                    }
                }
            }
        }
        return new BfsResult(farthest, parent);
    }

    private static int count(final boolean[] values) {
        int count = 0;
        for (final boolean value : values) {
            count += value ? 1 : 0;
        }
        return count;
    }

    private record BfsResult(int farthestIndex, int[] parent) {
    }
}
