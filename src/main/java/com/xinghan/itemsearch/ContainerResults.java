package com.xinghan.itemsearch;

import com.xinghan.itemsearch.Compat;
import com.xinghan.itemsearch.item.TextNormalizer;
import com.xinghan.itemsearch.scan.WorldScanner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Groups slot-level matches into one result per physical container. */
public final class ContainerResults {
    private ContainerResults() {}

    public static final class Container {
        private final WorldScanner.Hit representative;
        private final int firstHitIndex;
        private final long matchedItems;
        private final int matchedStacks;
        private final String label;
        public Container(WorldScanner.Hit representative, int firstHitIndex, long matchedItems, int matchedStacks, String label) {
            this.representative = representative;
            this.firstHitIndex = firstHitIndex;
            this.matchedItems = matchedItems;
            this.matchedStacks = matchedStacks;
            this.label = label;
        }
        public WorldScanner.Hit representative() { return representative; }
        public int firstHitIndex() { return firstHitIndex; }
        public long matchedItems() { return matchedItems; }
        public int matchedStacks() { return matchedStacks; }
        public String label() { return label; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Container)) return false;
            Container other = (Container) value;
            return java.util.Objects.equals(representative, other.representative) && firstHitIndex == other.firstHitIndex && matchedItems == other.matchedItems && matchedStacks == other.matchedStacks && java.util.Objects.equals(label, other.label);
        }
        @Override public int hashCode() { return java.util.Objects.hash(representative, firstHitIndex, matchedItems, matchedStacks, label); }
        @Override public String toString() { return "Container[" + "representative=" + representative + ", " + "firstHitIndex=" + firstHitIndex + ", " + "matchedItems=" + matchedItems + ", " + "matchedStacks=" + matchedStacks + ", " + "label=" + label + "]"; }
    }

    public static List<Container> group(List<WorldScanner.Hit> hits) {
        Objects.requireNonNull(hits, "hits");
        Map<Location, Accumulator> containers = new LinkedHashMap<>();
        for (int index = 0; index < hits.size(); index++) {
            WorldScanner.Hit hit = Objects.requireNonNull(hits.get(index), "hit");
            Location location = new Location(hit.sourceIdentity(), hit.world(), hit.x(), hit.y(), hit.z());
            Accumulator container = containers.get(location);
            if (container == null) {
                container = new Accumulator(hit, index);
                containers.put(location, container);
            }
            container.add(hit);
        }
        return Compat.listCopy(containers.values().stream().map(Accumulator::result).collect(java.util.stream.Collectors.toList()));
    }

    private static final class Location {
        private final String sourceIdentity;
        private final String world;
        private final int x;
        private final int y;
        private final int z;
        public Location(String sourceIdentity, String world, int x, int y, int z) {
            this.sourceIdentity = sourceIdentity;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }
        public String sourceIdentity() { return sourceIdentity; }
        public String world() { return world; }
        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Location)) return false;
            Location other = (Location) value;
            return java.util.Objects.equals(sourceIdentity, other.sourceIdentity) && java.util.Objects.equals(world, other.world) && x == other.x && y == other.y && z == other.z;
        }
        @Override public int hashCode() { return java.util.Objects.hash(sourceIdentity, world, x, y, z); }
        @Override public String toString() { return "Location[" + "sourceIdentity=" + sourceIdentity + ", " + "world=" + world + ", " + "x=" + x + ", " + "y=" + y + ", " + "z=" + z + "]"; }
    }

    private static final class Accumulator {
        private final WorldScanner.Hit representative;
        private final int firstHitIndex;
        private final String firstLabel;
        private long matchedItems;
        private int matchedStacks;
        private boolean differentLabels;

        Accumulator(WorldScanner.Hit representative, int firstHitIndex) {
            this.representative = representative;
            this.firstHitIndex = firstHitIndex;
            this.firstLabel = label(representative);
        }

        void add(WorldScanner.Hit hit) {
            matchedItems += hit.item().count();
            matchedStacks++;
            if (!firstLabel.equals(label(hit))) differentLabels = true;
        }

        Container result() {
            return new Container(representative, firstHitIndex, matchedItems, matchedStacks,
                    firstLabel + (differentLabels ? Language.plain("chat.multiple-items-suffix") : ""));
        }

        private static String label(WorldScanner.Hit hit) {
            String name = Compat.strip(TextNormalizer.plain(hit.item().name()));
            return Compat.isBlank(name) ? hit.item().material() : name;
        }
    }
}
