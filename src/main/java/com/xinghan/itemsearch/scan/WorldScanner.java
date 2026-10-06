package com.xinghan.itemsearch.scan;

import com.xinghan.itemsearch.Compat;
import com.xinghan.itemsearch.Language;
import com.xinghan.itemsearch.item.ItemData;
import com.xinghan.itemsearch.item.Query;
import com.xinghan.itemsearch.region.RegionReader;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Reads saved region inventories; this class never accesses a Bukkit world or loads a chunk. */
public final class WorldScanner {
    public static final int MAX_READER_THREADS = 64;
    private static final int MAX_WARNINGS = 32;
    private static final AtomicInteger READER_POOLS = new AtomicInteger();
    private static final Set<String> CONTAINERS = Compat.set(
            "chest", "trapped_chest", "barrel", "hopper", "dispenser", "dropper",
            "furnace", "blast_furnace", "smoker", "brewing_stand", "chiseled_bookshelf", "crafter");

    public static final class Source {
        private final String world;
        private final Path regionDirectory;
        public Source(String world, Path regionDirectory) {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(regionDirectory, "regionDirectory");
            this.world = world;
            this.regionDirectory = regionDirectory;
        }
        public String world() { return world; }
        public Path regionDirectory() { return regionDirectory; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Source)) return false;
            Source other = (Source) value;
            return java.util.Objects.equals(world, other.world) && java.util.Objects.equals(regionDirectory, other.regionDirectory);
        }
        @Override public int hashCode() { return java.util.Objects.hash(world, regionDirectory); }
        @Override public String toString() { return "Source[" + "world=" + world + ", " + "regionDirectory=" + regionDirectory + "]"; }
    }

    public static final class Settings {
        private final int maxChunkBytes;
        private final int maxNbtDepth;
        private final int maxContainerDepth;
        private final int maxResults;
        private final long delayPerChunkMillis;
        private final Set<String> ignoredKeys;
        private final int readerThreads;
        public Settings(int maxChunkBytes, int maxNbtDepth, int maxContainerDepth, int maxResults, long delayPerChunkMillis, Set<String> ignoredKeys, int readerThreads) {
            if (maxChunkBytes < 4 || maxNbtDepth < 1 || maxNbtDepth > 512 || maxContainerDepth < 0
                    || maxResults < 1 || delayPerChunkMillis < 0 || readerThreads < 1 || readerThreads > MAX_READER_THREADS) {
                throw new IllegalArgumentException(Language.tr("scan.invalid-limits"));
            }
            ignoredKeys = Compat.setCopy(Objects.requireNonNull(ignoredKeys, "ignoredKeys"));
            this.maxChunkBytes = maxChunkBytes;
            this.maxNbtDepth = maxNbtDepth;
            this.maxContainerDepth = maxContainerDepth;
            this.maxResults = maxResults;
            this.delayPerChunkMillis = delayPerChunkMillis;
            this.ignoredKeys = ignoredKeys;
            this.readerThreads = readerThreads;
        }
        public int maxChunkBytes() { return maxChunkBytes; }
        public int maxNbtDepth() { return maxNbtDepth; }
        public int maxContainerDepth() { return maxContainerDepth; }
        public int maxResults() { return maxResults; }
        public long delayPerChunkMillis() { return delayPerChunkMillis; }
        public Set<String> ignoredKeys() { return ignoredKeys; }
        public int readerThreads() { return readerThreads; }
        public Settings(int maxChunkBytes, int maxNbtDepth, int maxContainerDepth,
                        int maxResults, long delayPerChunkMillis, Set<String> ignoredKeys) {
            this(maxChunkBytes, maxNbtDepth, maxContainerDepth, maxResults, delayPerChunkMillis, ignoredKeys, 1);
        }
        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Settings)) return false;
            Settings other = (Settings) value;
            return maxChunkBytes == other.maxChunkBytes && maxNbtDepth == other.maxNbtDepth && maxContainerDepth == other.maxContainerDepth && maxResults == other.maxResults && delayPerChunkMillis == other.delayPerChunkMillis && java.util.Objects.equals(ignoredKeys, other.ignoredKeys) && readerThreads == other.readerThreads;
        }
        @Override public int hashCode() { return java.util.Objects.hash(maxChunkBytes, maxNbtDepth, maxContainerDepth, maxResults, delayPerChunkMillis, ignoredKeys, readerThreads); }
        @Override public String toString() { return "Settings[" + "maxChunkBytes=" + maxChunkBytes + ", " + "maxNbtDepth=" + maxNbtDepth + ", " + "maxContainerDepth=" + maxContainerDepth + ", " + "maxResults=" + maxResults + ", " + "delayPerChunkMillis=" + delayPerChunkMillis + ", " + "ignoredKeys=" + ignoredKeys + ", " + "readerThreads=" + readerThreads + "]"; }
    }

    public static final class Hit {
        private final String world;
        private final String container;
        private final int x;
        private final int y;
        private final int z;
        private final int outerSlot;
        private final List<String> path;
        private final ItemData item;
        private final String sourceIdentity;
        public Hit(String world, String container, int x, int y, int z, int outerSlot, List<String> path, ItemData item, String sourceIdentity) {
            path = Compat.listCopy(path);
            sourceIdentity = Objects.requireNonNull(sourceIdentity, "sourceIdentity");
            this.world = world;
            this.container = container;
            this.x = x;
            this.y = y;
            this.z = z;
            this.outerSlot = outerSlot;
            this.path = path;
            this.item = item;
            this.sourceIdentity = sourceIdentity;
        }
        public String world() { return world; }
        public String container() { return container; }
        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public int outerSlot() { return outerSlot; }
        public List<String> path() { return path; }
        public ItemData item() { return item; }
        public String sourceIdentity() { return sourceIdentity; }
        public Hit(String world, String container, int x, int y, int z, int outerSlot,
                   List<String> path, ItemData item) {
            this(world, container, x, y, z, outerSlot, path, item, "");
        }
        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Hit)) return false;
            Hit other = (Hit) value;
            return java.util.Objects.equals(world, other.world) && java.util.Objects.equals(container, other.container) && x == other.x && y == other.y && z == other.z && outerSlot == other.outerSlot && java.util.Objects.equals(path, other.path) && java.util.Objects.equals(item, other.item) && java.util.Objects.equals(sourceIdentity, other.sourceIdentity);
        }
        @Override public int hashCode() { return java.util.Objects.hash(world, container, x, y, z, outerSlot, path, item, sourceIdentity); }
        @Override public String toString() { return "Hit[" + "world=" + world + ", " + "container=" + container + ", " + "x=" + x + ", " + "y=" + y + ", " + "z=" + z + ", " + "outerSlot=" + outerSlot + ", " + "path=" + path + ", " + "item=" + item + ", " + "sourceIdentity=" + sourceIdentity + "]"; }
    }

    public static final class Summary {
        private final long regions;
        private final long chunks;
        private final long containers;
        private final long items;
        private final long matchedStacks;
        private final long matchedItems;
        private final long errors;
        private final long lootTables;
        private final long depthSkipped;
        private final boolean cancelled;
        private final boolean truncated;
        private final List<Hit> hits;
        private final List<String> warnings;
        public Summary(long regions, long chunks, long containers, long items, long matchedStacks, long matchedItems, long errors, long lootTables, long depthSkipped, boolean cancelled, boolean truncated, List<Hit> hits, List<String> warnings) {
            hits = Compat.listCopy(hits);
            warnings = Compat.listCopy(warnings);
            this.regions = regions;
            this.chunks = chunks;
            this.containers = containers;
            this.items = items;
            this.matchedStacks = matchedStacks;
            this.matchedItems = matchedItems;
            this.errors = errors;
            this.lootTables = lootTables;
            this.depthSkipped = depthSkipped;
            this.cancelled = cancelled;
            this.truncated = truncated;
            this.hits = hits;
            this.warnings = warnings;
        }
        public long regions() { return regions; }
        public long chunks() { return chunks; }
        public long containers() { return containers; }
        public long items() { return items; }
        public long matchedStacks() { return matchedStacks; }
        public long matchedItems() { return matchedItems; }
        public long errors() { return errors; }
        public long lootTables() { return lootTables; }
        public long depthSkipped() { return depthSkipped; }
        public boolean cancelled() { return cancelled; }
        public boolean truncated() { return truncated; }
        public List<Hit> hits() { return hits; }
        public List<String> warnings() { return warnings; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Summary)) return false;
            Summary other = (Summary) value;
            return regions == other.regions && chunks == other.chunks && containers == other.containers && items == other.items && matchedStacks == other.matchedStacks && matchedItems == other.matchedItems && errors == other.errors && lootTables == other.lootTables && depthSkipped == other.depthSkipped && cancelled == other.cancelled && truncated == other.truncated && java.util.Objects.equals(hits, other.hits) && java.util.Objects.equals(warnings, other.warnings);
        }
        @Override public int hashCode() { return java.util.Objects.hash(regions, chunks, containers, items, matchedStacks, matchedItems, errors, lootTables, depthSkipped, cancelled, truncated, hits, warnings); }
        @Override public String toString() { return "Summary[" + "regions=" + regions + ", " + "chunks=" + chunks + ", " + "containers=" + containers + ", " + "items=" + items + ", " + "matchedStacks=" + matchedStacks + ", " + "matchedItems=" + matchedItems + ", " + "errors=" + errors + ", " + "lootTables=" + lootTables + ", " + "depthSkipped=" + depthSkipped + ", " + "cancelled=" + cancelled + ", " + "truncated=" + truncated + ", " + "hits=" + hits + ", " + "warnings=" + warnings + "]"; }
    }

    public Summary scan(List<Source> sources, Query query, Settings settings,
                        BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(progress, "progress");
        Control control = new Control(settings.maxResults(), cancelled);
        State state = new State(query, settings, control);
        if (settings.readerThreads() == 1) {
            enumerate(sources, state, progress, (world, directory, file) -> readRegion(world, directory, file, state, progress));
            return state.summary();
        }
        try (ParallelReaders readers = new ParallelReaders(state, progress)) {
            enumerate(sources, state, progress, readers::submit);
            readers.drain(true);
        }
        return state.summary();
    }

    private static void enumerate(List<Source> sources, State state, Consumer<String> progress, RegionTask task) {
        Set<Path> visitedDirectories = new HashSet<>();
        for (Source source : sources) {
            if (state.shouldStop()) break;
            Path directory = source.regionDirectory().toAbsolutePath().normalize();
            if (!visitedDirectories.add(directory)) continue;
            if (!Files.isDirectory(directory)) {
                state.warn(Language.tr("scan.region-unavailable", "world", source.world(), "directory", directory));
                continue;
            }
            progress.accept(Language.tr("scan.world-progress", "world", source.world(), "chunks", state.chunks));
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
                for (Path file : files) {
                    if (state.shouldStop()) break;
                    if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mca")
                            || !Files.isRegularFile(file)) continue;
                    task.scan(source.world(), directory, file);
                }
            } catch (IOException | DirectoryIteratorException ex) {
                state.error(Language.tr("scan.region-enumeration-failed", "world", source.world(), "reason", concise(ex)));
            }
        }
    }

    private static void readRegion(String world, Path directory, Path file, State state, Consumer<String> progress) {
        if (state.shouldStop()) return;
        state.regions++;
        RegionReader reader = new RegionReader(state.settings.maxChunkBytes(), state.settings.maxNbtDepth());
        try {
            reader.scan(file, state::shouldStop, chunk -> {
                if (state.shouldStop()) return;
                state.chunks++;
                try {
                    scanChunk(world, directory.toString(), chunk.data(), state);
                } catch (RuntimeException ex) {
                    state.error(Language.tr("scan.chunk-failed", "file", file.getFileName(), "x", chunk.chunkX(), "z", chunk.chunkZ(), "reason", concise(ex)));
                }
                if (progress != null) state.reportProgress(world, progress);
                state.throttle();
            }, (index, message) -> state.error(Language.tr("scan.chunk-index-failed", "file", file.getFileName(), "index", index, "reason", message)));
        } catch (IOException ex) {
            state.error(Language.tr("scan.region-read-failed", "world", world, "file", file.getFileName(), "reason", concise(ex)));
        }
    }

    // Kept package-private so the traversal can be tested without a server or disk fixtures.
    static Summary scanChunkForTest(String world, Map<String, Object> chunk, Query query,
                                    Settings settings, BooleanSupplier cancelled) {
        State state = new State(query, settings, new Control(settings.maxResults(), cancelled));
        state.chunks++;
        scanChunk(world, "", chunk, state);
        return state.summary();
    }

    private static void scanChunk(String world, String sourceIdentity, Map<String, Object> chunk, State state) {
        state.itemDataVersion = number(chunk.get("DataVersion"), 0);
        Map<String, Object> level = map(chunk.get("Level"));
        Map<String, Object> content = level == null ? chunk : level;
        Object entitiesValue = content.get("block_entities");
        if (entitiesValue == null) entitiesValue = content.get("TileEntities");
        if (entitiesValue == null && content != chunk) entitiesValue = chunk.get("block_entities");
        if (entitiesValue == null) return;
        if (!(entitiesValue instanceof List<?>)) {
            state.error(Language.tr("scan.invalid-block-entity-list", "world", world));
            return;
        }
        for (Object value : (List<?>) entitiesValue) {
            if (state.shouldStop()) return;
            Map<String, Object> entity = map(value);
            if (entity == null) {
                state.error(Language.tr("scan.invalid-block-entity", "world", world));
                continue;
            }
            String entityId = localId(string(entity.get("id")));
            if (entityId.equals("trap")) entityId = "dispenser";
            if (entityId.equals("cauldron")) entityId = "brewing_stand";
            Object itemsValue = entity.containsKey("Items") ? entity.get("Items") : entity.get("items");
            boolean knownContainer = CONTAINERS.contains(entityId) || entityId.endsWith("shulker_box");
            if (itemsValue == null && !knownContainer) continue;
            state.containers++;
            if (entity.containsKey("LootTable") || entity.containsKey("loot_table")) state.lootTables++;
            if (itemsValue == null) continue;
            if (!(itemsValue instanceof List<?>)) {
                state.error(Language.tr("scan.invalid-container-items", "world", world, "container", entityId));
                continue;
            }
            Integer x = integer(entity.get("x")), y = integer(entity.get("y")), z = integer(entity.get("z"));
            if (x == null || y == null || z == null) {
                state.error(Language.tr("scan.missing-container-coordinates", "world", world, "container", entityId));
                continue;
            }
            String block = blockAt(chunk, x, y, z);
            // Both chest types serialize as minecraft:chest; placed colored shulkers share an entity id.
            String container = (block != null && !block.equals("air") ? block : entityId)
                    .toUpperCase(Locale.ROOT);
            if (container.isEmpty()) container = "CONTAINER";
            Location location = new Location(world, container, x, y, z, sourceIdentity);
            for (Object entry : (List<?>) itemsValue) {
                if (state.shouldStop()) return;
                Map<String, Object> item = map(entry);
                if (item == null) {
                    state.error(Language.tr("scan.invalid-item-entry", "world", world, "x", x, "y", y, "z", z));
                    continue;
                }
                Integer slot = slot(item);
                if (slot == null) {
                    state.error(Language.tr("scan.invalid-item-slot", "world", world, "x", x, "y", y, "z", z));
                    continue;
                }
                visitItem(item, location, slot, Compat.list(container + "[" + slot + "]"), 0, state);
            }
        }
    }

    private static void visitItem(Map<String, Object> serialized, Location location, int outerSlot,
                                  List<String> path, int depth, State state) {
        if (state.shouldStop()) return;
        if (!com.xinghan.itemsearch.item.LegacyItems.validId(serialized.get("id"))) {
            state.error(Language.tr("scan.invalid-item-id", "world", location.world));
            return;
        }
        ItemData item = ItemData.from(serialized, state.settings.ignoredKeys(), state.itemDataVersion);
        if (item.material().isEmpty() || localId(item.material()).equals("air") || item.count() <= 0) return;
        state.items++;
        if (state.query.matches(item)) {
            if (!state.control.acceptHit()) return;
            state.hits.add(new Hit(location.world, location.container, location.x, location.y,
                    location.z, outerSlot, path, item, location.sourceIdentity));
            state.matchedStacks++;
            state.matchedItems += item.count();
            if (state.shouldStop()) return;
        }
        Nested nested = nestedItems(serialized, state);
        if (nested == null || nested.entries.isEmpty()) return;
        if (depth >= state.settings.maxContainerDepth()) {
            state.depthSkipped++;
            return;
        }
        String nestedContainer = localId(item.material()).toUpperCase(Locale.ROOT);
        for (Object value : nested.entries) {
            if (state.shouldStop()) return;
            Map<String, Object> entry = map(value);
            if (entry == null) {
                state.error(Language.tr("scan.invalid-nested-entry", "world", location.world));
                continue;
            }
            Integer childSlot = slot(entry);
            Map<String, Object> child = nested.component ? map(entry.get("item")) : entry;
            if (childSlot == null || child == null) {
                state.error(Language.tr("scan.invalid-nested-slot", "world", location.world));
                continue;
            }
            List<String> childPath = new ArrayList<>(path);
            childPath.add(nestedContainer + "[" + childSlot + "]");
            visitItem(child, location, outerSlot, childPath, depth + 1, state);
        }
    }

    private static Nested nestedItems(Map<String, Object> item, State state) {
        Map<String, Object> components = map(item.get("components"));
        if (components != null) {
            String key = components.containsKey("minecraft:container") ? "minecraft:container" : "container";
            if (components.containsKey(key)) {
                Object value = components.get(key);
                // Some export tools wrap the component list in {items:[...]}; normal MCA stores the list.
                Map<String, Object> wrapped = map(value);
                if (wrapped != null) value = wrapped.get("items");
                if (value instanceof List<?>) return new Nested((List<?>) value, true);
                state.error(Language.tr("scan.invalid-container-component"));
                return null;
            }
        }
        Map<String, Object> tag = map(item.get("tag"));
        Map<String, Object> blockEntity = tag == null ? null : map(tag.get("BlockEntityTag"));
        if (blockEntity == null || !blockEntity.containsKey("Items")) return null;
        if (blockEntity.get("Items") instanceof List<?>) return new Nested((List<?>) blockEntity.get("Items"), false);
        state.error(Language.tr("scan.invalid-legacy-container-items"));
        return null;
    }

    /** Resolves numeric Blocks/Add/Data and both generations of paletted block states. */
    static String blockAt(Map<String, Object> chunk, int x, int y, int z) {
        Map<String, Object> level = map(chunk.get("Level"));
        Map<String, Object> content = level == null ? chunk : level;
        Object sectionsValue = content.containsKey("sections") ? content.get("sections") : content.get("Sections");
        if (!(sectionsValue instanceof List<?>)) return null;
        List<?> sections = (List<?>) sectionsValue;
        int sectionY = Math.floorDiv(y, 16);
        int dataVersion = number(chunk.get("DataVersion"), Integer.MAX_VALUE);
        for (Object value : sections) {
            Map<String, Object> section = map(value);
            if (section == null || number(section.get("Y"), Integer.MIN_VALUE) != sectionY) continue;
            Object legacyBlocks = section.get("Blocks");
            if (legacyBlocks instanceof byte[]) {
                byte[] blocks = (byte[]) legacyBlocks;
                int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
                if (blocks.length != 4096) return null;
                int extra = nibble(section.get("Add"), index);
                int metadata = nibble(section.get("Data"), index);
                if (extra < 0 || metadata < 0) return null;
                int id = (blocks[index] & 255) | (extra << 8);
                String container = com.xinghan.itemsearch.item.LegacyItems.containerBlock(id);
                return id == 0 ? "air" : container;
            }
            Map<String, Object> states = map(section.get("block_states"));
            Object paletteValue = states == null ? section.get("Palette") : states.get("palette");
            if (!(paletteValue instanceof List<?>) || ((List<?>) paletteValue).isEmpty()) return null;
            List<?> palette = (List<?>) paletteValue;
            if (palette.size() == 1) return paletteName(palette.get(0));
            Object dataValue = states == null ? section.get("BlockStates") : states.get("data");
            if (!(dataValue instanceof long[])) return null;
            long[] data = (long[]) dataValue;
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            if (bits > 32) return null;
            int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            long mask = (1L << bits) - 1;
            long paletteIndex;
            if (states != null || dataVersion >= 2529) {
                int perLong = 64 / bits;
                int word = index / perLong;
                if (word >= data.length) return null;
                paletteIndex = (data[word] >>> ((index % perLong) * bits)) & mask;
            } else {
                long bitIndex = (long) index * bits;
                int word = (int) (bitIndex / 64), offset = (int) (bitIndex % 64);
                if (word >= data.length) return null;
                paletteIndex = data[word] >>> offset;
                if (offset + bits > 64) {
                    if (word + 1 >= data.length) return null;
                    paletteIndex |= data[word + 1] << (64 - offset);
                }
                paletteIndex &= mask;
            }
            return paletteIndex < palette.size() ? paletteName(palette.get((int) paletteIndex)) : null;
        }
        return null;
    }

    private static String paletteName(Object value) {
        Map<String, Object> entry = map(value);
        if (entry == null) return null;
        String name = localId(string(entry.get("Name")));
        return name.isEmpty() ? null : name;
    }

    private static int nibble(Object value, int index) {
        if (value == null) return 0;
        if (!(value instanceof byte[]) || ((byte[]) value).length != 2048) return -1;
        byte[] values = (byte[]) value;
        return (values[index >>> 1] >>> ((index & 1) * 4)) & 15;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static Integer integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static int number(Object value, int fallback) {
        Integer number = integer(value);
        return number == null ? fallback : number;
    }

    private static Integer slot(Map<String, Object> item) {
        Object value = item.containsKey("Slot") ? item.get("Slot") : item.get("slot");
        Integer result = value instanceof Byte ? Byte.toUnsignedInt((Byte) value) : integer(value);
        return result != null && result >= 0 ? result : null;
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static String localId(String id) {
        int colon = id.indexOf(':');
        return (colon < 0 ? id : id.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }

    private static String concise(Exception exception) {
        String message = exception.getMessage();
        return message == null || Compat.isBlank(message) ? exception.getClass().getSimpleName() : message;
    }

    private static final class Location {
        private final String world;
        private final String container;
        private final int x;
        private final int y;
        private final int z;
        private final String sourceIdentity;
        public Location(String world, String container, int x, int y, int z, String sourceIdentity) {
            this.world = world;
            this.container = container;
            this.x = x;
            this.y = y;
            this.z = z;
            this.sourceIdentity = sourceIdentity;
        }
        public String world() { return world; }
        public String container() { return container; }
        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public String sourceIdentity() { return sourceIdentity; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Location)) return false;
            Location other = (Location) value;
            return java.util.Objects.equals(world, other.world) && java.util.Objects.equals(container, other.container) && x == other.x && y == other.y && z == other.z && java.util.Objects.equals(sourceIdentity, other.sourceIdentity);
        }
        @Override public int hashCode() { return java.util.Objects.hash(world, container, x, y, z, sourceIdentity); }
        @Override public String toString() { return "Location[" + "world=" + world + ", " + "container=" + container + ", " + "x=" + x + ", " + "y=" + y + ", " + "z=" + z + ", " + "sourceIdentity=" + sourceIdentity + "]"; }
    }
    private static final class Nested {
        private final List<?> entries;
        private final boolean component;
        public Nested(List<?> entries, boolean component) {
            this.entries = entries;
            this.component = component;
        }
        public List<?> entries() { return entries; }
        public boolean component() { return component; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Nested)) return false;
            Nested other = (Nested) value;
            return java.util.Objects.equals(entries, other.entries) && component == other.component;
        }
        @Override public int hashCode() { return java.util.Objects.hash(entries, component); }
        @Override public String toString() { return "Nested[" + "entries=" + entries + ", " + "component=" + component + "]"; }
    }

    @FunctionalInterface
    private interface RegionTask {
        void scan(String world, Path directory, Path file);
    }

    /** Only the result budget and stop flags are shared; parsing and traversal use per-file state. */
    private static final class Control {
        final int maxResults;
        final BooleanSupplier cancellation;
        final Thread coordinator = Thread.currentThread();
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean truncated = new AtomicBoolean();
        final AtomicBoolean aborted = new AtomicBoolean();
        final Set<Thread> readers = ConcurrentHashMap.newKeySet();

        Control(int maxResults, BooleanSupplier cancellation) {
            this.maxResults = maxResults;
            this.cancellation = cancellation;
        }

        boolean shouldStop() {
            Thread current = Thread.currentThread();
            if (cancellation.getAsBoolean() || current == coordinator && current.isInterrupted()) cancelled.set(true);
            return cancelled.get() || truncated.get() || aborted.get() || current.isInterrupted();
        }

        boolean acceptHit() {
            if (shouldStop()) return false;
            int previous;
            do {
                previous = accepted.get();
                if (previous >= maxResults || cancelled.get() || aborted.get()) return false;
            } while (!accepted.compareAndSet(previous, previous + 1));
            if (previous + 1 == maxResults) truncated.set(true);
            return true;
        }

        void interruptReaders() {
            // An internal cap/cleanup interrupt must never turn into a user-cancelled summary.
            for (Thread reader : readers) reader.interrupt();
        }
    }

    /** Owns bounded submissions, progress callbacks and merging on the calling coordination thread. */
    private static final class ParallelReaders implements AutoCloseable {
        final State total;
        final Consumer<String> progress;
        final ThreadPoolExecutor executor;
        final ExecutorCompletionService<State> completed;
        final Map<Future<State>, State> pending = new LinkedHashMap<>();
        final int maxInFlight;
        String world = "";
        boolean interrupted;

        ParallelReaders(State total, Consumer<String> progress) {
            this.total = total;
            this.progress = progress;
            int threads = total.settings.readerThreads();
            maxInFlight = threads * 2;
            int pool = READER_POOLS.incrementAndGet();
            AtomicInteger threadIds = new AtomicInteger();
            executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(maxInFlight), task -> {
                Thread thread = new Thread(task, "RegionItemSearch-Reader-" + pool + "-" + threadIds.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
            completed = new ExecutorCompletionService<>(executor);
        }

        void submit(String world, Path directory, Path file) {
            if (total.shouldStop()) return;
            while (pending.size() >= maxInFlight && !total.shouldStop()) collect(true);
            if (total.shouldStop()) return;
            this.world = world;
            State local = new State(total.query, total.settings, total.control);
            Future<State> future = completed.submit(() -> {
                Thread thread = Thread.currentThread();
                total.control.readers.add(thread);
                try {
                    readRegion(world, directory, file, local, null);
                } catch (RuntimeException ex) {
                    local.error(Language.tr("scan.region-read-failed", "world", world, "file", file.getFileName(), "reason", concise(ex)));
                } finally {
                    total.control.readers.remove(thread);
                    Thread.interrupted();
                }
                return local;
            });
            pending.put(future, local);
        }

        void drain(boolean reportProgress) {
            executor.shutdown();
            while (!pending.isEmpty()) collect(reportProgress);
        }

        void collect(boolean reportProgress) {
            if (total.shouldStop()) total.control.interruptReaders();
            Future<State> future;
            try {
                future = completed.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                interrupted = true;
                total.control.cancelled.set(true);
                total.control.interruptReaders();
                return;
            }
            if (future != null) {
                State local = pending.remove(future);
                try {
                    future.get(); // Publishes every local field and accepted hit before merging.
                } catch (InterruptedException ex) {
                    interrupted = true;
                    total.control.cancelled.set(true);
                } catch (ExecutionException ex) {
                    local.error(Language.tr("scan.worker-failed", "reason", ex.getCause()));
                }
                total.merge(local);
            }
            if (reportProgress) total.reportProgress(world, progress, pending.values());
        }

        @Override public void close() {
            total.control.aborted.set(true);
            total.control.interruptReaders();
            // Do not cancel futures or discard queued tasks: partial hits must reach the coordinator.
            drain(false);
            for (;;) {
                try {
                    if (executor.awaitTermination(100, TimeUnit.MILLISECONDS)) break;
                } catch (InterruptedException ex) {
                    interrupted = true;
                    total.control.cancelled.set(true);
                    total.control.interruptReaders();
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static final class State {
        final Query query;
        final Settings settings;
        final Control control;
        final List<Hit> hits = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        volatile long regions, chunks, containers, items, matchedStacks, matchedItems, errors, lootTables, depthSkipped;
        long lastProgressNanos = System.nanoTime();
        int itemDataVersion;

        State(Query query, Settings settings, Control control) {
            this.query = query;
            this.settings = settings;
            this.control = control;
        }

        boolean shouldStop() {
            return control.shouldStop();
        }

        void throttle() {
            long remaining = settings.delayPerChunkMillis();
            while (remaining > 0 && !shouldStop()) {
                long delay = Math.min(remaining, 100);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    shouldStop();
                    return;
                }
                remaining -= delay;
            }
        }

        void reportProgress(String world, Consumer<String> progress) {
            reportProgress(world, progress, Compat.list());
        }

        void reportProgress(String world, Consumer<String> progress, Iterable<State> pending) {
            long now = System.nanoTime();
            if (now - lastProgressNanos >= 1_000_000_000L) {
                lastProgressNanos = now;
                long liveRegions = regions, liveChunks = chunks, liveContainers = containers, liveItems = items;
                long liveStacks = matchedStacks, liveUnits = matchedItems;
                for (State local : pending) {
                    liveRegions += local.regions;
                    liveChunks += local.chunks;
                    liveContainers += local.containers;
                    liveItems += local.items;
                    liveStacks += local.matchedStacks;
                    liveUnits += local.matchedItems;
                }
                progress.accept(Language.tr("scan.progress", "world", world, "regions", liveRegions, "chunks", liveChunks,
                        "containers", liveContainers, "items", liveItems, "stacks", liveStacks, "units", liveUnits));
            }
        }

        void merge(State local) {
            regions += local.regions;
            chunks += local.chunks;
            containers += local.containers;
            items += local.items;
            matchedStacks += local.matchedStacks;
            matchedItems += local.matchedItems;
            errors += local.errors;
            lootTables += local.lootTables;
            depthSkipped += local.depthSkipped;
            hits.addAll(local.hits);
            local.warnings.forEach(this::warn);
        }

        void error(String message) {
            errors++;
            warn(message);
        }

        void warn(String message) {
            if (warnings.size() < MAX_WARNINGS) {
                warnings.add(message.length() > 500 ? message.substring(0, 500) + "…" : message);
            }
        }

        Summary summary() {
            shouldStop();
            return new Summary(regions, chunks, containers, items, matchedStacks, matchedItems, errors,
                    lootTables, depthSkipped, control.cancelled.get(), control.truncated.get(), hits, warnings);
        }
    }
}
