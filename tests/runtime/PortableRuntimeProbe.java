package com.xinghan.itemsearch.probe;

import com.xinghan.itemsearch.BukkitCompat;
import com.xinghan.itemsearch.RegionItemSearchPlugin;
import com.xinghan.itemsearch.item.ItemData;
import com.xinghan.itemsearch.item.Query;
import com.xinghan.itemsearch.nbt.NbtReader;
import com.xinghan.itemsearch.nbt.NbtWriter;
import com.xinghan.itemsearch.player.PlayerDataStore;
import com.xinghan.itemsearch.player.PlayerScanner;
import com.xinghan.itemsearch.scan.WorldScanner;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

/** Test-only plugin: Java 8 source, Bukkit 1.8 surface, and no unrelocated Adventure linkage. */
public final class PortableRuntimeProbe extends JavaPlugin {
    private static final int MAX_BYTES = 16777216;
    private static final int MAX_DEPTH = 128;
    private static final Set<String> IGNORED = Collections.unmodifiableSet(
            new java.util.HashSet<String>(Arrays.asList("unique_uuid", "created_time", "owner_uuid")));
    private RegionItemSearchPlugin search;
    private Path fixtures;
    private int checks;

    @Override public void onEnable() {
        Object plugin = Bukkit.getPluginManager().getPlugin("RegionItemSearch");
        if (!(plugin instanceof RegionItemSearchPlugin)) {
            fail(new IllegalStateException("RegionItemSearch must be enabled before the probe"));
            return;
        }
        search = (RegionItemSearchPlugin) plugin;
        search.scheduler().globalLater(20L, () -> {
            try { runCore(); runMenus(() -> { try { finish(); } catch (Exception error) { fail(error); } }); }
            catch (Throwable error) { fail(error); }
        });
    }

    private void runCore() throws Exception {
        fixtures = search.getDataFolder().toPath().resolve("portable-probe").resolve(UUID.randomUUID().toString());
        Files.createDirectories(fixtures);
        getLogger().info("PORTABLE PROBE server=" + Bukkit.getVersion() + "; java=" + System.getProperty("java.version")
                + "; folia=" + search.scheduler().isFolia() + "; fixtures=" + fixtures);
        check(search.isEnabled(), "main plugin starts against this server API");
        check(Files.isRegularFile(search.getDataFolder().toPath().resolve("lang.yml")), "language file is available after startup");
        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.setDisplayName("\u00a74Portable Blade");
        meta.setLore(Arrays.asList("\u00a77Sealed power", "\u00a7fUndead strike"));
        Enchantment sharpness = Enchantment.getByName("DAMAGE_ALL");
        if (sharpness == null) sharpness = Enchantment.getByName("SHARPNESS");
        if (sharpness == null) throw new IllegalStateException("Sharpness enchantment was not registered");
        meta.addEnchant(sharpness, 4, true);
        sword.setItemMeta(meta);
        Map<String, Object> raw = read(BukkitCompat.serializeItem(sword));
        Map<String, Object> custom = custom(raw);
        custom.put("MMOITEMS_ITEM_TYPE", "SWORD");
        custom.put("MMOITEMS_ITEM_ID", "PORTABLE_BLADE");
        custom.put("probe_payload", map("nested", map("enabled", (byte) 1, "long", 1234567890123L),
                "bytes", new byte[]{1, -2, 3}, "ints", new int[]{11, 22}, "name", "arbitrary-custom-data"));
        custom.put("unique_uuid", "template-only-uuid");
        ItemStack decorated = BukkitCompat.deserializeItem(gzip(NbtWriter.write(raw, MAX_BYTES, MAX_DEPTH)));
        Map<String, Object> encoded = read(BukkitCompat.serializeItem(decorated));
        ItemData decoded = ItemData.from(encoded, IGNORED);
        check("Portable Blade".equals(decoded.name()), "native item codec preserves custom name");
        check(decoded.lore().equals(Arrays.asList("Sealed power", "Undead strike")), "native item codec preserves lore");
        check(decorated.getEnchantmentLevel(sharpness) == 4, "native item codec preserves enchantment level");
        check("SWORD".equals(decoded.mmoType()) && "PORTABLE_BLADE".equals(decoded.mmoId()), "native item codec preserves MMOItems identity");
        check(nbtEqual(custom.get("probe_payload"), custom(encoded).get("probe_payload")), "native item codec preserves nested arbitrary NBT and typed arrays");
        ItemStack second = BukkitCompat.deserializeItem(BukkitCompat.serializeItem(decorated));
        check(second.equals(decorated), "native item encode/decode is stable across a second round trip");
        repeatedLargeDecode(decorated);
        check(query(Query.Mode.SAME, decoded).matches(ItemData.from(read(BukkitCompat.serializeItem(second)), IGNORED)), "same fingerprint survives native round trip");
        check(query(Query.Mode.MATERIAL, decoded).matches(ItemData.from(read(BukkitCompat.serializeItem(new ItemStack(Material.DIAMOND_SWORD))), IGNORED)), "material query ignores display and custom metadata");
        Map<String, Object> durable = map("id", "minecraft:diamond_sword", "Count", (byte) 1, "Damage", (short) 47);
        check("DIAMOND_SWORD".equals(ItemData.from(durable, IGNORED).material()), "legacy sword durability does not change material identity");
        ItemStack ordinary = new ItemStack(Material.GOLDEN_APPLE);
        Material enchantedType = Material.matchMaterial("ENCHANTED_GOLDEN_APPLE");
        ItemStack enchanted = enchantedType == null ? new ItemStack(Material.GOLDEN_APPLE, 1, (short) 1) : new ItemStack(enchantedType);
        ItemData apple = ItemData.from(read(BukkitCompat.serializeItem(ordinary)), IGNORED);
        ItemData enchantedApple = ItemData.from(read(BukkitCompat.serializeItem(enchanted)), IGNORED);
        check("GOLDEN_APPLE".equals(apple.material()), "native golden apple normalizes to modern material ID");
        check("ENCHANTED_GOLDEN_APPLE".equals(enchantedApple.material()), "legacy golden apple variant normalizes to distinct material ID");
        check(!query(Query.Mode.MATERIAL, apple).matches(enchantedApple), "exact material search separates enchanted golden apple variant");
        runRegion(encoded, decoded);
        runPlayerStore(encoded, decoded);
    }

    private void runRegion(Map<String, Object> sword, ItemData template) throws Exception {
        Path directory = fixtures.resolve("region");
        Files.createDirectories(directory);
        Map<String, Object> first = stack(sword, 2, 3);
        Map<String, Object> nested = map("id", "minecraft:blue_shulker_box", "Count", (byte) 1, "Slot", (byte) 5,
                "tag", map("BlockEntityTag", map("Items", Collections.singletonList(stack(sword, 1, 12)))));
        Map<String, Object> modern = map("xPos", 0, "zPos", 0, "block_entities", Collections.singletonList(
                map("id", "minecraft:chest", "x", 12, "y", 64, "z", 12, "Items", Arrays.asList(first, nested))));
        Map<String, Object> legacy = map("Level", map("xPos", 1, "zPos", 0, "TileEntities", Collections.singletonList(
                map("id", "Chest", "x", 35, "y", 65, "z", 2, "Items", Collections.singletonList(stack(sword, 1, 0))))));
        writeRegion(directory.resolve("r.0.0.mca"), Arrays.asList(modern, legacy));
        WorldScanner.Summary summary = new WorldScanner().scan(Collections.singletonList(new WorldScanner.Source("probe-fixture", directory)),
                query(Query.Mode.ID, template), new WorldScanner.Settings(MAX_BYTES, MAX_DEPTH, 16, 100, 0L, IGNORED, 2),
                () -> false, progress -> { });
        check(summary.errors() == 0, "offline region fixture scan has no reader errors: " + summary.warnings());
        check(summary.matchedStacks() == 3 && summary.matchedItems() == 4, "modern and legacy block entities and nested shulker stacks are scanned");
        boolean nestedHit = false;
        for (WorldScanner.Hit hit : summary.hits()) if (hit.path().size() == 2 && hit.outerSlot() == 5) nestedHit = true;
        check(nestedHit, "nested shulker result retains outer slot and inner path");
    }

    private void repeatedLargeDecode(ItemStack decorated) throws Exception {
        Map<String, Object> stress = read(BukkitCompat.serializeItem(decorated));
        byte[] payload = new byte[1024 * 1024];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        custom(stress).put("probe_budget_payload", payload);
        byte[] encoded = gzip(NbtWriter.write(stress, MAX_BYTES, MAX_DEPTH));
        for (int attempt = 1; attempt <= 80; attempt++) {
            ItemStack restored = BukkitCompat.deserializeItem(encoded);
            Object actual = custom(read(BukkitCompat.serializeItem(restored))).get("probe_budget_payload");
            if (!(actual instanceof byte[]) || !Arrays.equals(payload, (byte[]) actual))
                throw new IllegalStateException("Large NBT payload changed at decode " + attempt);
            if (attempt % 16 == 0) check(true, "independent native NBT budget: " + attempt + " consecutive 1 MiB decodes preserve exact bytes");
        }
    }

    private void runPlayerStore(Map<String, Object> sword, ItemData template) throws Exception {
        Path players = fixtures.resolve("playerdata"), backups = fixtures.resolve("backups");
        Files.createDirectories(players);
        UUID uuid = UUID.randomUUID();
        Path file = players.resolve(uuid + ".dat");
        Map<String, Object> root = map("Inventory", Collections.singletonList(stack(sword, 2, 0)),
                "EnderItems", Collections.singletonList(stack(sword, 1, 7)), "Health", 17.5f,
                "Pos", Arrays.asList(123.25d, 80.0d, -456.5d), "ProbeSentinel", "keep-other-player-data",
                "ProbeTypedArray", new int[]{7, 11, 13});
        byte[] original = gzip(NbtWriter.write(root, MAX_BYTES, MAX_DEPTH));
        Files.write(file, original);
        PlayerScanner.Summary scanned = new PlayerScanner().scan(Collections.singletonList(new PlayerScanner.Source("probe-fixture", players)),
                Collections.<PlayerScanner.Online>emptyList(), fixtures.resolve("no-usercache.json"), query(Query.Mode.ID, template),
                new PlayerScanner.Settings(MAX_BYTES, MAX_DEPTH, 16, 100, IGNORED), () -> false, progress -> { });
        check(scanned.errors() == 0 && scanned.offlinePlayers() == 1 && scanned.matchedStacks() == 2 && scanned.matchedItems() == 3,
                "offline player inventory and ender chest are scanned without loading a player");
        PlayerScanner.Online online = new PlayerScanner.Online(uuid, "PortableProbe", Collections.singletonList(stack(sword, 1, 4)), Collections.<Map<String, Object>>emptyList());
        PlayerScanner.Summary current = new PlayerScanner().scan(Collections.singletonList(new PlayerScanner.Source("probe-fixture", players)),
                Collections.singletonList(online), fixtures.resolve("no-usercache.json"), query(Query.Mode.ID, template),
                new PlayerScanner.Settings(MAX_BYTES, MAX_DEPTH, 16, 100, IGNORED), () -> false, progress -> { });
        check(current.onlinePlayers() == 1 && current.offlinePlayers() == 0 && current.matchedItems() == 1, "online snapshot overrides stale disk inventory");
        PlayerDataStore store = new PlayerDataStore();
        PlayerDataStore.Snapshot snapshot = store.load(players, uuid, MAX_BYTES, MAX_DEPTH);
        Path backup = store.save(snapshot, "Inventory", Collections.singletonList(stack(sword, 1, 9)), backups, MAX_BYTES, MAX_DEPTH);
        check(Arrays.equals(original, Files.readAllBytes(backup)), "offline save backup preserves original compressed bytes exactly");
        PlayerDataStore.Snapshot saved = store.load(players, uuid, MAX_BYTES, MAX_DEPTH);
        Map<String, Object> savedRoot = saved.root();
        check(nbtEqual(root.get("EnderItems"), savedRoot.get("EnderItems")), "backpack save preserves ender chest");
        for (String key : Arrays.asList("Health", "Pos", "ProbeSentinel", "ProbeTypedArray"))
            check(nbtEqual(root.get(key), savedRoot.get(key)), "offline save preserves unrelated field " + key);
        List<?> inventory = (List<?>) savedRoot.get("Inventory");
        Map<?, ?> changedItem = (Map<?, ?>) inventory.get(0);
        check(((Number) changedItem.get("Slot")).intValue() == 9 && ItemData.from(cast(changedItem), IGNORED).count() == 1,
                "offline save applies intended slot and amount");
        byte[] currentBytes = Files.readAllBytes(file);
        expectFailure(() -> store.save(snapshot, "Inventory", Collections.<Map<String, Object>>emptyList(), backups, MAX_BYTES, MAX_DEPTH),
                "stale snapshot compare-and-swap refuses overwrite");
        check(Arrays.equals(currentBytes, Files.readAllBytes(file)), "stale save refusal leaves exact current player bytes intact");
        try (PlayerDataStore.PreparedSave prepared = store.prepare(saved, "EnderItems", Collections.<Map<String, Object>>emptyList(), backups, MAX_BYTES, MAX_DEPTH)) {
            check(Arrays.equals(currentBytes, Files.readAllBytes(file)), "prepare does not mutate the live player file");
        }
        check(Arrays.equals(currentBytes, Files.readAllBytes(file)), "cancelled preparation leaves target file unchanged");
    }

    private void runMenus(Runnable done) throws Exception {
        if (search.scheduler().isFolia()) {
            getLogger().info("PORTABLE PROBE SKIP entity/native-menu/editor: no connected player; only global startup, codec and disk checks run on Folia");
            done.run();
            return;
        }
        getLogger().info("PORTABLE PROBE SKIP native-menu/editor: this probe covers codec/scanner/player-file core only; real connected-player CommonTransferProbe supplies menu/editor evidence");
        done.run();
    }

    private void finish() throws IOException {
        String marker = "PORTABLE PROBE ALL PASS checks=" + checks + " server=" + Bukkit.getVersion()
                + " folia=" + search.scheduler().isFolia() + " scope=CORE_ONLY native-menu/editor=SKIPPED\n";
        Files.write(fixtures.resolve("PASS.txt"), marker.getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(getDataFolder().toPath());
        Files.write(getDataFolder().toPath().resolve("portable-pass.txt"), marker.getBytes(StandardCharsets.UTF_8));
        getLogger().info(marker.trim());
        Bukkit.shutdown();
    }

    private void fail(Throwable error) {
        getLogger().log(Level.SEVERE, "PORTABLE PROBE FAILED", error);
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.write(getDataFolder().toPath().resolve("portable-failed.txt"), String.valueOf(error).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) { }
        Bukkit.shutdown();
    }

    private void check(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
        checks++;
        getLogger().info("PORTABLE PROBE PASS " + description);
    }
    private interface Checked { void run() throws Exception; }
    private void expectFailure(Checked action, String description) throws Exception {
        try { action.run(); } catch (IOException expected) { check(true, description + ": " + expected.getMessage()); return; }
        throw new IllegalStateException(description);
    }
    private static Query query(Query.Mode mode, ItemData template) {
        return new Query(mode, Query.Match.EXACT, Query.LoreMatch.ALL, template, Collections.<String>emptyList(), template.material());
    }
    private static Map<String, Object> read(byte[] bytes) throws IOException { return NbtReader.readAuto(bytes, MAX_BYTES, MAX_DEPTH); }
    @SuppressWarnings("unchecked") private static Map<String, Object> cast(Map<?, ?> map) { return (Map<String, Object>) map; }
    private static Map<String, Object> custom(Map<String, Object> item) {
        if (item.get("components") instanceof Map<?, ?>) {
            Map<String, Object> components = cast((Map<?, ?>) item.get("components"));
            Object data = components.get("minecraft:custom_data");
            if (!(data instanceof Map<?, ?>)) { data = new LinkedHashMap<String, Object>(); components.put("minecraft:custom_data", data); }
            return cast((Map<?, ?>) data);
        }
        Object tag = item.get("tag");
        if (!(tag instanceof Map<?, ?>)) { tag = new LinkedHashMap<String, Object>(); item.put("tag", tag); }
        return cast((Map<?, ?>) tag);
    }
    private static Map<String, Object> stack(Map<String, Object> source, int count, int slot) throws IOException {
        Map<String, Object> result = read(NbtWriter.write(source, MAX_BYTES, MAX_DEPTH));
        if (result.containsKey("count")) result.put("count", count); else result.put("Count", (byte) count);
        result.put("Slot", (byte) slot);
        return result;
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static boolean nbtEqual(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Map<?, ?> && b instanceof Map<?, ?>) {
            Map<?, ?> left = (Map<?, ?>) a, right = (Map<?, ?>) b;
            if (!left.keySet().equals(right.keySet())) return false;
            for (Object key : left.keySet()) if (!nbtEqual(left.get(key), right.get(key))) return false;
            return true;
        }
        if (a instanceof List<?> && b instanceof List<?>) {
            List<?> left = (List<?>) a, right = (List<?>) b;
            if (left.size() != right.size()) return false;
            for (int i = 0; i < left.size(); i++) if (!nbtEqual(left.get(i), right.get(i))) return false;
            return true;
        }
        if (a instanceof byte[] && b instanceof byte[]) return Arrays.equals((byte[]) a, (byte[]) b);
        if (a instanceof int[] && b instanceof int[]) return Arrays.equals((int[]) a, (int[]) b);
        if (a instanceof long[] && b instanceof long[]) return Arrays.equals((long[]) a, (long[]) b);
        return a.equals(b);
    }
    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(raw); }
        return out.toByteArray();
    }
    private static void writeRegion(Path file, List<Map<String, Object>> chunks) throws IOException {
        List<byte[]> entries = new ArrayList<byte[]>();
        int sectors = 2;
        for (Map<String, Object> chunk : chunks) {
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(compressed)) { deflater.write(NbtWriter.write(chunk, MAX_BYTES, MAX_DEPTH)); }
            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(entry)) { data.writeInt(compressed.size() + 1); data.writeByte(2); data.write(compressed.toByteArray()); }
            byte[] bytes = entry.toByteArray(); entries.add(bytes); sectors += (bytes.length + 4095) / 4096;
        }
        byte[] region = new byte[sectors * 4096];
        int offset = 2;
        for (int i = 0; i < entries.size(); i++) {
            byte[] bytes = entries.get(i); int count = (bytes.length + 4095) / 4096;
            int location = (offset << 8) | count;
            region[i * 4] = (byte) (location >>> 24); region[i * 4 + 1] = (byte) (location >>> 16);
            region[i * 4 + 2] = (byte) (location >>> 8); region[i * 4 + 3] = (byte) location;
            System.arraycopy(bytes, 0, region, offset * 4096, bytes.length); offset += count;
        }
        Files.write(file, region);
    }
}
