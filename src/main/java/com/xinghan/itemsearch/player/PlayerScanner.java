package com.xinghan.itemsearch.player;

import com.xinghan.itemsearch.Compat;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.xinghan.itemsearch.Language;
import com.xinghan.itemsearch.item.ItemData;
import com.xinghan.itemsearch.item.Query;
import com.xinghan.itemsearch.nbt.NbtReader;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/** Read-only player-data scan. Online inventories arrive as main-thread snapshots. */
public final class PlayerScanner {
    private static final int MAX_WARNINGS = 32;
    private static final int MAX_USERCACHE_BYTES = 16 * 1024 * 1024;

    public static final class Source {
        private final String world;
        private final Path playerDataDirectory;
        public Source(String world, Path playerDataDirectory) {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(playerDataDirectory, "playerDataDirectory");
            this.world = world;
            this.playerDataDirectory = playerDataDirectory;
        }
        public String world() { return world; }
        public Path playerDataDirectory() { return playerDataDirectory; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Source)) return false;
            Source other = (Source) value;
            return java.util.Objects.equals(world, other.world) && java.util.Objects.equals(playerDataDirectory, other.playerDataDirectory);
        }
        @Override public int hashCode() { return java.util.Objects.hash(world, playerDataDirectory); }
        @Override public String toString() { return "Source[" + "world=" + world + ", " + "playerDataDirectory=" + playerDataDirectory + "]"; }
    }

    public static final class Online {
        private final UUID uuid;
        private final String name;
        private final List<Map<String, Object>> inventory;
        private final List<Map<String, Object>> ender;
        public Online(UUID uuid, String name, List<Map<String, Object>> inventory, List<Map<String, Object>> ender) {
            Objects.requireNonNull(uuid, "uuid");
            name = name == null || Compat.isBlank(name) ? uuid.toString() : name;
            inventory = Compat.listCopy(Objects.requireNonNull(inventory, "inventory"));
            ender = Compat.listCopy(Objects.requireNonNull(ender, "ender"));
            this.uuid = uuid;
            this.name = name;
            this.inventory = inventory;
            this.ender = ender;
        }
        public UUID uuid() { return uuid; }
        public String name() { return name; }
        public List<Map<String, Object>> inventory() { return inventory; }
        public List<Map<String, Object>> ender() { return ender; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Online)) return false;
            Online other = (Online) value;
            return java.util.Objects.equals(uuid, other.uuid) && java.util.Objects.equals(name, other.name) && java.util.Objects.equals(inventory, other.inventory) && java.util.Objects.equals(ender, other.ender);
        }
        @Override public int hashCode() { return java.util.Objects.hash(uuid, name, inventory, ender); }
        @Override public String toString() { return "Online[" + "uuid=" + uuid + ", " + "name=" + name + ", " + "inventory=" + inventory + ", " + "ender=" + ender + "]"; }
    }

    public enum Storage { INVENTORY, ENDER_CHEST }

    public static final class Hit {
        private final UUID player;
        private final String playerName;
        private final String sourceWorld;
        private final boolean online;
        private final Storage storage;
        private final int slot;
        private final ItemData item;
        private final List<String> path;
        public Hit(UUID player, String playerName, String sourceWorld, boolean online, Storage storage, int slot, ItemData item, List<String> path) {
            path = Compat.listCopy(path);
            this.player = player;
            this.playerName = playerName;
            this.sourceWorld = sourceWorld;
            this.online = online;
            this.storage = storage;
            this.slot = slot;
            this.item = item;
            this.path = path;
        }
        public UUID player() { return player; }
        public String playerName() { return playerName; }
        public String sourceWorld() { return sourceWorld; }
        public boolean online() { return online; }
        public Storage storage() { return storage; }
        public int slot() { return slot; }
        public ItemData item() { return item; }
        public List<String> path() { return path; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Hit)) return false;
            Hit other = (Hit) value;
            return java.util.Objects.equals(player, other.player) && java.util.Objects.equals(playerName, other.playerName) && java.util.Objects.equals(sourceWorld, other.sourceWorld) && online == other.online && java.util.Objects.equals(storage, other.storage) && slot == other.slot && java.util.Objects.equals(item, other.item) && java.util.Objects.equals(path, other.path);
        }
        @Override public int hashCode() { return java.util.Objects.hash(player, playerName, sourceWorld, online, storage, slot, item, path); }
        @Override public String toString() { return "Hit[" + "player=" + player + ", " + "playerName=" + playerName + ", " + "sourceWorld=" + sourceWorld + ", " + "online=" + online + ", " + "storage=" + storage + ", " + "slot=" + slot + ", " + "item=" + item + ", " + "path=" + path + "]"; }
    }

    public static final class Settings {
        private final int maxPlayerBytes;
        private final int maxNbtDepth;
        private final int maxContainerDepth;
        private final int maxResults;
        private final Set<String> ignoredKeys;
        public Settings(int maxPlayerBytes, int maxNbtDepth, int maxContainerDepth, int maxResults, Set<String> ignoredKeys) {
            if (maxPlayerBytes < 4 || maxNbtDepth < 1 || maxNbtDepth > 512
                    || maxContainerDepth < 0 || maxContainerDepth > 512 || maxResults < 1) {
                throw new IllegalArgumentException(Language.tr("player-scan.invalid-limits"));
            }
            ignoredKeys = Compat.setCopy(Objects.requireNonNull(ignoredKeys, "ignoredKeys"));
            this.maxPlayerBytes = maxPlayerBytes;
            this.maxNbtDepth = maxNbtDepth;
            this.maxContainerDepth = maxContainerDepth;
            this.maxResults = maxResults;
            this.ignoredKeys = ignoredKeys;
        }
        public int maxPlayerBytes() { return maxPlayerBytes; }
        public int maxNbtDepth() { return maxNbtDepth; }
        public int maxContainerDepth() { return maxContainerDepth; }
        public int maxResults() { return maxResults; }
        public Set<String> ignoredKeys() { return ignoredKeys; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Settings)) return false;
            Settings other = (Settings) value;
            return maxPlayerBytes == other.maxPlayerBytes && maxNbtDepth == other.maxNbtDepth && maxContainerDepth == other.maxContainerDepth && maxResults == other.maxResults && java.util.Objects.equals(ignoredKeys, other.ignoredKeys);
        }
        @Override public int hashCode() { return java.util.Objects.hash(maxPlayerBytes, maxNbtDepth, maxContainerDepth, maxResults, ignoredKeys); }
        @Override public String toString() { return "Settings[" + "maxPlayerBytes=" + maxPlayerBytes + ", " + "maxNbtDepth=" + maxNbtDepth + ", " + "maxContainerDepth=" + maxContainerDepth + ", " + "maxResults=" + maxResults + ", " + "ignoredKeys=" + ignoredKeys + "]"; }
    }

    public static final class Summary {
        private final long files;
        private final long players;
        private final long onlinePlayers;
        private final long offlinePlayers;
        private final long items;
        private final long matchedStacks;
        private final long matchedItems;
        private final long errors;
        private final boolean cancelled;
        private final boolean truncated;
        private final List<Hit> hits;
        private final List<String> warnings;
        public Summary(long files, long players, long onlinePlayers, long offlinePlayers, long items, long matchedStacks, long matchedItems, long errors, boolean cancelled, boolean truncated, List<Hit> hits, List<String> warnings) {
            hits = Compat.listCopy(hits);
            warnings = Compat.listCopy(warnings);
            this.files = files;
            this.players = players;
            this.onlinePlayers = onlinePlayers;
            this.offlinePlayers = offlinePlayers;
            this.items = items;
            this.matchedStacks = matchedStacks;
            this.matchedItems = matchedItems;
            this.errors = errors;
            this.cancelled = cancelled;
            this.truncated = truncated;
            this.hits = hits;
            this.warnings = warnings;
        }
        public long files() { return files; }
        public long players() { return players; }
        public long onlinePlayers() { return onlinePlayers; }
        public long offlinePlayers() { return offlinePlayers; }
        public long items() { return items; }
        public long matchedStacks() { return matchedStacks; }
        public long matchedItems() { return matchedItems; }
        public long errors() { return errors; }
        public boolean cancelled() { return cancelled; }
        public boolean truncated() { return truncated; }
        public List<Hit> hits() { return hits; }
        public List<String> warnings() { return warnings; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Summary)) return false;
            Summary other = (Summary) value;
            return files == other.files && players == other.players && onlinePlayers == other.onlinePlayers && offlinePlayers == other.offlinePlayers && items == other.items && matchedStacks == other.matchedStacks && matchedItems == other.matchedItems && errors == other.errors && cancelled == other.cancelled && truncated == other.truncated && java.util.Objects.equals(hits, other.hits) && java.util.Objects.equals(warnings, other.warnings);
        }
        @Override public int hashCode() { return java.util.Objects.hash(files, players, onlinePlayers, offlinePlayers, items, matchedStacks, matchedItems, errors, cancelled, truncated, hits, warnings); }
        @Override public String toString() { return "Summary[" + "files=" + files + ", " + "players=" + players + ", " + "onlinePlayers=" + onlinePlayers + ", " + "offlinePlayers=" + offlinePlayers + ", " + "items=" + items + ", " + "matchedStacks=" + matchedStacks + ", " + "matchedItems=" + matchedItems + ", " + "errors=" + errors + ", " + "cancelled=" + cancelled + ", " + "truncated=" + truncated + ", " + "hits=" + hits + ", " + "warnings=" + warnings + "]"; }
    }

    public Summary scan(List<Source> sources, List<Online> online, Path usercache, Query query,
                        Settings settings, BooleanSupplier cancel, Consumer<String> progress) throws IOException {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(online, "online");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(cancel, "cancel");
        Objects.requireNonNull(progress, "progress");
        State state = new State(query, settings, cancel, progress);
        if (state.shouldStop()) return state.summary();
        progress.accept(Language.tr("player-scan.started"));
        Map<UUID, String> names = readNames(usercache, state);
        Set<UUID> onlineIds = new HashSet<>();
        for (Online player : online) onlineIds.add(player.uuid());
        Set<UUID> scannedOnline = new HashSet<>();
        for (Online player : online) {
            if (state.shouldStop()) return state.summary();
            if (!scannedOnline.add(player.uuid())) continue;
            state.players++;
            state.onlinePlayers++;
            Owner owner = new Owner(player.uuid(), player.name(), Language.tr("player-scan.online-source"), true);
            scanStorage(player.inventory(), Storage.INVENTORY, owner, state);
            scanStorage(player.ender(), Storage.ENDER_CHEST, owner, state);
            state.reportProgress();
        }

        Map<UUID, List<Candidate>> snapshots = discover(sources, onlineIds, state);
        List<UUID> offlineIds = new ArrayList<>(snapshots.keySet());
        offlineIds.sort(Comparator.comparing(UUID::toString));
        for (UUID uuid : offlineIds) {
            if (state.shouldStop()) break;
            List<Candidate> candidates = snapshots.get(uuid);
            candidates.sort(Comparator.comparing(Candidate::modified).reversed()
                    .thenComparing(candidate -> candidate.file().toString()));
            for (Candidate candidate : candidates) {
                if (state.shouldStop()) break;
                state.files++;
                try {
                    Map<String, Object> raw = readPlayer(candidate.file(), state);
                    state.itemDataVersion = raw.get("DataVersion") instanceof Number ? ((Number) raw.get("DataVersion")).intValue() : 0;
                    validateStorage(raw.get("Inventory"), "Inventory");
                    validateStorage(raw.get("EnderItems"), "EnderItems");
                    state.players++;
                    state.offlinePlayers++;
                    Owner owner = new Owner(uuid, names.getOrDefault(uuid, uuid.toString()), candidate.world(), false);
                    scanStorage(raw.get("Inventory"), Storage.INVENTORY, owner, state);
                    scanStorage(raw.get("EnderItems"), Storage.ENDER_CHEST, owner, state);
                    break; // One newest valid offline snapshot per UUID, across all worlds.
                } catch (ScanCancelled stopped) {
                    state.cancelled = true;
                    break;
                } catch (IOException | RuntimeException invalid) {
                    state.error(Language.tr("player-scan.file-failed", "world", candidate.world(), "file", candidate.file().getFileName(), "reason", concise(invalid)));
                }
                state.reportProgress();
            }
            state.reportProgress();
        }
        state.shouldStop();
        return state.summary();
    }

    private static Map<UUID, List<Candidate>> discover(List<Source> sources, Set<UUID> onlineIds,
                                                       State state) {
        Map<UUID, List<Candidate>> result = new HashMap<>();
        Set<Path> seen = new HashSet<>();
        for (Source source : sources) {
            if (state.shouldStop()) break;
            Path directory = source.playerDataDirectory().toAbsolutePath().normalize();
            // Paper normally creates playerdata only in its primary world.
            if (!Files.exists(directory)) continue;
            try {
                if (!Files.isDirectory(directory)) throw new IOException(Language.tr("player-scan.not-directory"));
                if (!seen.add(directory.toRealPath())) continue;
                try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                    for (Path file : entries) {
                        if (state.shouldStop()) break;
                        String filename = file.getFileName().toString();
                        if (!filename.toLowerCase(Locale.ROOT).endsWith(".dat")) continue;
                        UUID uuid = canonicalUuid(filename.substring(0, filename.length() - 4));
                        if (uuid == null || onlineIds.contains(uuid)) continue;
                        try {
                            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                            if (attributes.isRegularFile()) {
                                result.computeIfAbsent(uuid, ignored -> new ArrayList<>())
                                        .add(new Candidate(source.world(), file, attributes.lastModifiedTime()));
                            }
                        } catch (IOException invalid) {
                            state.error(Language.tr("player-scan.file-failed", "world", source.world(), "file", filename, "reason", concise(invalid)));
                        }
                        state.reportProgress();
                    }
                }
            } catch (IOException invalid) {
                state.error(Language.tr("player-scan.directory-failed", "world", source.world(), "reason", concise(invalid)));
            }
        }
        return result;
    }

    private static Map<String, Object> readPlayer(Path file, State state) throws IOException {
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class);
        if (!before.isRegularFile() || before.size() > state.settings.maxPlayerBytes()) {
            throw new IOException(Language.tr("player-scan.invalid-file-size"));
        }
        Map<String, Object> data;
        try (InputStream encoded = new CheckedInputStream(Files.newInputStream(file), state,
                state.settings.maxPlayerBytes()); GZIPInputStream gzip = new GZIPInputStream(encoded);
             InputStream decoded = new CheckedInputStream(gzip, state, state.settings.maxPlayerBytes())) {
            data = NbtReader.read(decoded, state.settings.maxPlayerBytes(), state.settings.maxNbtDepth());
        }
        if (state.shouldStop()) throw new ScanCancelled();
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException(Language.tr("player-scan.snapshot-changed"));
        }
        return data;
    }

    private static Map<UUID, String> readNames(Path usercache, State state) {
        Map<UUID, String> result = new HashMap<>();
        if (usercache == null || !Files.exists(usercache) || state.shouldStop()) return result;
        try {
            if (!Files.isRegularFile(usercache) || Files.size(usercache) > MAX_USERCACHE_BYTES) {
                throw new IOException(Language.tr("player-scan.invalid-cache-size"));
            }
            try (InputStream encoded = new CheckedInputStream(Files.newInputStream(usercache), state, MAX_USERCACHE_BYTES);
                 Reader reader = new java.io.InputStreamReader(encoded, StandardCharsets.UTF_8)) {
                JsonElement json = JsonParser.parseReader(reader);
                if (!json.isJsonArray()) throw new IOException(Language.tr("player-scan.invalid-cache-root"));
                for (JsonElement entry : json.getAsJsonArray()) {
                    if (state.shouldStop()) break;
                    if (!entry.isJsonObject()) continue;
                    com.google.gson.JsonObject object = entry.getAsJsonObject();
                    if (!object.has("uuid") || !object.has("name")
                            || !object.get("uuid").isJsonPrimitive() || !object.get("name").isJsonPrimitive()) continue;
                    UUID uuid = canonicalUuid(object.get("uuid").getAsString());
                    String name = object.get("name").getAsString();
                    if (uuid != null && !Compat.isBlank(name)) result.put(uuid, name);
                }
            }
        } catch (ScanCancelled stopped) {
            state.cancelled = true;
        } catch (IOException | RuntimeException invalid) {
            if (!state.cancelled) state.error(Language.tr("player-scan.cache-failed", "reason", concise(invalid)));
        }
        return result;
    }

    private static void validateStorage(Object value, String name) throws IOException {
        if (value != null && !(value instanceof List<?>)) throw new IOException(Language.tr("player-scan.invalid-storage-list", "storage", name));
    }

    private static void scanStorage(Object value, Storage storage, Owner owner, State state) {
        if (!(value instanceof List<?>)) return;
        List<?> items = (List<?>) value;
        for (Object entry : items) {
            if (state.shouldStop()) return;
            Map<String, Object> item = map(entry);
            Integer slot = item == null ? null : slot(item);
            if (slot == null || !validSlot(slot, storage)) {
                state.error(Language.tr("player-scan.invalid-storage-slot", "player", owner.name(), "storage", storage));
                continue;
            }
            visitItem(item, owner, storage, slot, Compat.list(storage + "[" + slot + "]"), 0, state);
        }
    }

    private static boolean validSlot(int slot, Storage storage) {
        return storage == Storage.ENDER_CHEST ? slot >= 0 && slot < 27
                : slot >= 0 && slot < 36 || slot >= 100 && slot <= 103 || slot == 150;
    }

    private static void visitItem(Map<String, Object> raw, Owner owner, Storage storage, int outerSlot,
                                  List<String> path, int depth, State state) {
        if (state.shouldStop()) return;
        if (!com.xinghan.itemsearch.item.LegacyItems.validId(raw.get("id"))) {
            state.error(Language.tr("player-scan.invalid-item-id", "player", owner.name()));
            return;
        }
        ItemData item = ItemData.from(raw, state.settings.ignoredKeys(), state.itemDataVersion);
        if (item.material().equals("AIR") || item.count() < 1) return;
        state.items++;
        if (state.query.matches(item)) {
            state.hits.add(new Hit(owner.uuid(), owner.name(), owner.world(), owner.online(), storage, outerSlot, item, path));
            state.matchedStacks++;
            state.matchedItems += item.count();
            if (state.hits.size() >= state.settings.maxResults()) { state.truncated = true; return; }
        }
        Nested nested = nestedItems(raw, owner, state);
        if (nested == null || nested.items().isEmpty()) return;
        if (depth >= state.settings.maxContainerDepth()) {
            state.error(Language.tr("player-scan.nested-depth-limit", "player", owner.name()));
            return;
        }
        for (Object value : nested.items()) {
            if (state.shouldStop()) return;
            Map<String, Object> entry = map(value);
            Integer childSlot = entry == null ? null : slot(entry);
            Map<String, Object> child = entry == null ? null : nested.component() ? map(entry.get("item")) : entry;
            if (childSlot == null || childSlot > 255 || child == null) {
                state.error(Language.tr("player-scan.invalid-nested-slot", "player", owner.name()));
                continue;
            }
            List<String> childPath = new ArrayList<>(path);
            childPath.add(item.material() + "[" + childSlot + "]");
            visitItem(child, owner, storage, outerSlot, childPath, depth + 1, state);
        }
    }

    private static Nested nestedItems(Map<String, Object> raw, Owner owner, State state) {
        Map<String, Object> components = map(raw.get("components"));
        if (components != null) {
            String key = components.containsKey("minecraft:container") ? "minecraft:container" : "container";
            if (components.containsKey(key)) {
                Object value = components.get(key);
                Map<String, Object> wrapper = map(value);
                if (wrapper != null) value = wrapper.get("items");
                if (value instanceof List<?>) return new Nested((List<?>) value, true);
                state.error(Language.tr("player-scan.invalid-container-component", "player", owner.name()));
                return null;
            }
        }
        Map<String, Object> tag = map(raw.get("tag"));
        Map<String, Object> blockEntity = tag == null ? null : map(tag.get("BlockEntityTag"));
        if (blockEntity == null || !blockEntity.containsKey("Items")) return null;
        if (blockEntity.get("Items") instanceof List<?>) return new Nested((List<?>) blockEntity.get("Items"), false);
        state.error(Language.tr("player-scan.invalid-legacy-container-items", "player", owner.name()));
        return null;
    }

    private static Integer slot(Map<String, Object> raw) {
        Object value = raw.containsKey("Slot") ? raw.get("Slot") : raw.get("slot");
        if (value instanceof Byte) return Byte.toUnsignedInt((Byte) value);
        if (!(value instanceof Number)) return null;
        Number number = (Number) value;
        long slot = number.longValue();
        return slot >= 0 && slot <= 255 && number.doubleValue() == slot ? (int) slot : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    private static UUID canonicalUuid(String text) {
        if (text == null) return null;
        try {
            UUID uuid = UUID.fromString(text);
            return uuid.toString().equalsIgnoreCase(text) ? uuid : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static String concise(Exception invalid) {
        return invalid.getMessage() == null || Compat.isBlank(invalid.getMessage())
                ? invalid.getClass().getSimpleName() : invalid.getMessage();
    }

    private static final class Owner {
        private final UUID uuid;
        private final String name;
        private final String world;
        private final boolean online;
        public Owner(UUID uuid, String name, String world, boolean online) {
            this.uuid = uuid;
            this.name = name;
            this.world = world;
            this.online = online;
        }
        public UUID uuid() { return uuid; }
        public String name() { return name; }
        public String world() { return world; }
        public boolean online() { return online; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Owner)) return false;
            Owner other = (Owner) value;
            return java.util.Objects.equals(uuid, other.uuid) && java.util.Objects.equals(name, other.name) && java.util.Objects.equals(world, other.world) && online == other.online;
        }
        @Override public int hashCode() { return java.util.Objects.hash(uuid, name, world, online); }
        @Override public String toString() { return "Owner[" + "uuid=" + uuid + ", " + "name=" + name + ", " + "world=" + world + ", " + "online=" + online + "]"; }
    }
    private static final class Candidate {
        private final String world;
        private final Path file;
        private final FileTime modified;
        public Candidate(String world, Path file, FileTime modified) {
            this.world = world;
            this.file = file;
            this.modified = modified;
        }
        public String world() { return world; }
        public Path file() { return file; }
        public FileTime modified() { return modified; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Candidate)) return false;
            Candidate other = (Candidate) value;
            return java.util.Objects.equals(world, other.world) && java.util.Objects.equals(file, other.file) && java.util.Objects.equals(modified, other.modified);
        }
        @Override public int hashCode() { return java.util.Objects.hash(world, file, modified); }
        @Override public String toString() { return "Candidate[" + "world=" + world + ", " + "file=" + file + ", " + "modified=" + modified + "]"; }
    }
    private static final class Nested {
        private final List<?> items;
        private final boolean component;
        public Nested(List<?> items, boolean component) {
            this.items = items;
            this.component = component;
        }
        public List<?> items() { return items; }
        public boolean component() { return component; }

        @Override public boolean equals(Object value) {
            if (this == value) return true;
            if (!(value instanceof Nested)) return false;
            Nested other = (Nested) value;
            return java.util.Objects.equals(items, other.items) && component == other.component;
        }
        @Override public int hashCode() { return java.util.Objects.hash(items, component); }
        @Override public String toString() { return "Nested[" + "items=" + items + ", " + "component=" + component + "]"; }
    }

    private static final class State {
        final Query query;
        final Settings settings;
        final BooleanSupplier cancellation;
        final Consumer<String> progress;
        final List<Hit> hits = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        long files, players, onlinePlayers, offlinePlayers, items, matchedStacks, matchedItems, errors;
        long lastProgress = System.nanoTime();
        boolean cancelled, truncated;
        int itemDataVersion = Integer.MAX_VALUE;

        State(Query query, Settings settings, BooleanSupplier cancellation, Consumer<String> progress) {
            this.query = query;
            this.settings = settings;
            this.cancellation = cancellation;
            this.progress = progress;
        }

        boolean shouldStop() {
            if (cancellation.getAsBoolean() || Thread.currentThread().isInterrupted()) cancelled = true;
            return cancelled || truncated;
        }

        void error(String message) {
            errors++;
            if (warnings.size() < MAX_WARNINGS) warnings.add(message);
        }

        void reportProgress() {
            long now = System.nanoTime();
            if (now - lastProgress >= 1_000_000_000L) {
                lastProgress = now;
                progress.accept(Language.tr("player-scan.progress", "players", players, "files", files, "stacks", matchedStacks));
            }
        }

        Summary summary() {
            return new Summary(files, players, onlinePlayers, offlinePlayers, items, matchedStacks,
                    matchedItems, errors, cancelled, truncated, hits, warnings);
        }
    }

    private static final class ScanCancelled extends IOException {}

    private static final class CheckedInputStream extends FilterInputStream {
        private final State state;
        private long remaining;
        CheckedInputStream(InputStream input, State state, long maximum) {
            super(input);
            this.state = state;
            remaining = maximum;
        }
        @Override public int read() throws IOException {
            if (state.shouldStop()) throw new ScanCancelled();
            int value = in.read();
            if (value != -1 && remaining-- <= 0) throw new IOException(Language.tr("player-scan.read-size-limit"));
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            if (state.shouldStop()) throw new ScanCancelled();
            if (remaining == 0) return read() == -1 ? -1 : 0;
            int count = in.read(bytes, offset, (int) Math.min(length, remaining));
            if (count > 0) remaining -= count;
            return count;
        }
    }
}
