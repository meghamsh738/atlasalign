package org.atlasalign.atlas;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, validated Allen structure hierarchy with deterministic search.
 */
public final class AtlasOntology {

    private final Map<Integer, AtlasRegion> byId;

    AtlasOntology(final Collection<AtlasRegion> regions) {
        Objects.requireNonNull(regions, "regions");
        final Map<Integer, AtlasRegion> indexed = new HashMap<>();
        for (final AtlasRegion region : regions) {
            if (indexed.put(region.id(), region) != null) {
                throw new AtlasCacheException(
                        "Ontology contains duplicate region ID " + region.id());
            }
        }
        if (indexed.isEmpty()) {
            throw new AtlasCacheException("Ontology contains no regions");
        }
        final long roots = indexed.values().stream()
                .filter(region -> region.parentId() == null)
                .count();
        if (roots != 1) {
            throw new AtlasCacheException(
                    "Ontology must contain exactly one root");
        }
        final AtlasRegion root = indexed.values().stream()
                .filter(region -> region.parentId() == null)
                .findFirst()
                .orElseThrow();
        for (final AtlasRegion region : indexed.values()) {
            if (region.parentId() != null
                    && !indexed.containsKey(region.parentId())) {
                throw new AtlasCacheException(
                        "Ontology region " + region.id()
                                + " references a missing parent");
            }
            for (final int childId : region.childIds()) {
                final AtlasRegion child = indexed.get(childId);
                if (child == null || !Objects.equals(
                        child.parentId(), region.id())) {
                    throw new AtlasCacheException(
                            "Ontology parent/child relationship is inconsistent");
                }
            }
        }
        final Set<Integer> reachable = new HashSet<>();
        final ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(root.id());
        while (!pending.isEmpty()) {
            final int currentId = pending.removeFirst();
            if (!reachable.add(currentId)) {
                throw new AtlasCacheException(
                        "Ontology contains a hierarchy cycle at region "
                                + currentId);
            }
            pending.addAll(indexed.get(currentId).childIds());
        }
        if (reachable.size() != indexed.size()) {
            throw new AtlasCacheException(
                    "Ontology contains regions unreachable from its root");
        }
        this.byId = Map.copyOf(indexed);
    }

    public List<AtlasRegion> regions() {
        return byId.values().stream().sorted(Comparator.comparingInt(AtlasRegion::id)).toList();
    }

    public int size() {
        return byId.size();
    }

    public AtlasRegion region(final int id) {
        final AtlasRegion region = byId.get(id);
        if (region == null) {
            throw new IllegalArgumentException("Unknown atlas region ID: " + id);
        }
        return region;
    }

    public List<AtlasRegion> search(final String query, final int limit) {
        final String normalized = Objects.requireNonNull(query, "query")
                .trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Region search query is blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("Search limit must be positive");
        }
        return byId.values().stream()
                .filter(region -> matches(region, normalized))
                .sorted(Comparator
                        .comparingInt((AtlasRegion region) ->
                                rank(region, normalized))
                        .thenComparing(AtlasRegion::acronym)
                        .thenComparingInt(AtlasRegion::id))
                .limit(limit)
                .toList();
    }

    public List<AtlasRegion> descendants(final int id) {
        final AtlasRegion root = region(id);
        final List<AtlasRegion> descendants = new ArrayList<>();
        final ArrayDeque<Integer> pending =
                new ArrayDeque<>(root.childIds());
        final Set<Integer> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            final int childId = pending.removeFirst();
            if (!visited.add(childId)) {
                throw new AtlasCacheException(
                        "Ontology contains a child cycle at region " + childId);
            }
            final AtlasRegion child = region(childId);
            descendants.add(child);
            pending.addAll(child.childIds());
        }
        return List.copyOf(descendants);
    }

    private static boolean matches(
            final AtlasRegion region,
            final String query) {
        return region.acronym().toLowerCase(Locale.ROOT).contains(query)
                || region.name().toLowerCase(Locale.ROOT).contains(query);
    }

    private static int rank(
            final AtlasRegion region,
            final String query) {
        final String acronym =
                region.acronym().toLowerCase(Locale.ROOT);
        final String name = region.name().toLowerCase(Locale.ROOT);
        if (acronym.equals(query)) {
            return 0;
        }
        if (name.equals(query)) {
            return 1;
        }
        if (acronym.startsWith(query)) {
            return 2;
        }
        if (name.startsWith(query)) {
            return 3;
        }
        return 4;
    }
}
