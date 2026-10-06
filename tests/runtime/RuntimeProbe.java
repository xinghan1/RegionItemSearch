package com.xinghan.itemsearch.probe;

import com.xinghan.itemsearch.item.ItemData;
import com.xinghan.itemsearch.item.Query;
import com.xinghan.itemsearch.nbt.NbtReader;
import com.xinghan.itemsearch.scan.WorldScanner;
import com.xinghan.itemsearch.RegionItemSearchPlugin;
import com.xinghan.itemsearch.player.PlayerScanner;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.DataOutputStream;
import java.util.zip.GZIPOutputStream;

/** A separate test-only plugin. Never installed in a real server or shipped inside the release JAR. */
public final class RuntimeProbe extends JavaPlugin {
    private final Set<String> ignored = Set.of("unique_uuid", "created_time", "owner_uuid");
    private ItemStack prototype;
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, this::prepare, 20);
    }
    private void prepare() {
        try {
            var searchPlugin = (JavaPlugin) Bukkit.getPluginManager().getPlugin("RegionItemSearch");
            LanguageRuntimeProbe.run((RegionItemSearchPlugin) searchPlugin);
            verifyReaderConfiguration((RegionItemSearchPlugin) searchPlugin);
            InventoryTransferViewProbe.run(this);
            check(searchPlugin.getConfig().getInt("config-version") == 3, "configuration migrated to version 3");
            check(searchPlugin.getConfig().getLong("scan.delay-per-chunk-ms") == 0, "default artificial wait removed");
            check(searchPlugin.getConfig().getInt("scan.reader-threads") == 2, "two readers configured by default");
            var world = Bukkit.getWorlds().getFirst();
            ItemStack sword = new ItemStack(Material.NETHERITE_SWORD);
            var meta = sword.getItemMeta();
            meta.displayName(Component.text("亡灵之刃", NamedTextColor.DARK_RED));
            meta.lore(List.of(Component.text("诅咒封印"), Component.text("亡灵斩击")));
            meta.setCustomModelData(77);
            meta.setMaxStackSize(64);
            meta.addEnchant(Enchantment.SHARPNESS, 4, true);
            var pdc = meta.getPersistentDataContainer();
            pdc.set(new NamespacedKey("mmoitems", "item_type"), PersistentDataType.STRING, "SWORD");
            pdc.set(new NamespacedKey("mmoitems", "item_id"), PersistentDataType.STRING, "WLZR");
            pdc.set(new NamespacedKey("probe", "unique_uuid"), PersistentDataType.STRING, "template-uuid");
            sword.setItemMeta(meta);
            prototype = sword.clone();
            ItemData held = ItemData.from(NbtReader.readAuto(sword.serializeAsBytes(), 16777216, 128), ignored);
            check(held.name().equals("亡灵之刃"), "Paper serializeAsBytes name decoding");
            check(held.mmoType().equals("SWORD") && held.mmoId().equals("WLZR"), "Paper PDC identity decoding");

            var chestBlock = world.getBlockAt(8, 80, 8);
            chestBlock.setType(Material.CHEST);
            var chest = (Container) chestBlock.getState();
            ItemStack direct = sword.clone();
            direct.setAmount(3);
            direct.editMeta(m -> m.getPersistentDataContainer().set(new NamespacedKey("probe", "unique_uuid"), PersistentDataType.STRING, "different-uuid"));
            chest.getInventory().setItem(0, direct);
            ItemStack box = new ItemStack(Material.BLUE_SHULKER_BOX);
            var boxMeta = (BlockStateMeta) box.getItemMeta();
            var boxState = (ShulkerBox) boxMeta.getBlockState();
            boxState.getInventory().setItem(12, sword);
            boxMeta.setBlockState(boxState);
            box.setItemMeta(boxMeta);
            chest.getInventory().setItem(5, box);
            var barrelBlock = world.getBlockAt(-24, 80, -24);
            barrelBlock.setType(Material.BARREL);
            var barrel = (Container) barrelBlock.getState();
            ItemStack otherType = sword.clone();
            otherType.editMeta(m -> m.getPersistentDataContainer().set(new NamespacedKey("mmoitems", "item_type"), PersistentDataType.STRING, "AXE"));
            barrel.getInventory().setItem(4, otherType);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all flush");
            var sources = List.of(new WorldScanner.Source(world.getName(), world.getWorldFolder().toPath().resolve("region")));
            // Let chunk generation triggered by fixture creation settle before measuring the scanner.
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Set<String> loadedBefore = loadedChunkKeys();
                Bukkit.getScheduler().runTaskAsynchronously(this, () -> verify(held, sources, loadedBefore));
            }, 40);
        } catch (Exception error) { fail(error); }
    }
    private void verify(ItemData held, List<WorldScanner.Source> sources, Set<String> loadedBefore) {
        try {
            var scanner = new WorldScanner();
            for (int readerThreads : new int[]{2, 16, 24, 64}) {
                var limits = new WorldScanner.Settings(16777216, 128, 16, 100, 0, ignored, readerThreads);
                for (var mode : Query.Mode.values()) {
                    var query = new Query(mode, Query.Match.EXACT, Query.LoreMatch.ALL, held, List.of(), held.name());
                    var summary = scanner.scan(sources, query, limits, () -> false, p -> {});
                    int expected = (mode == Query.Mode.SAME || mode == Query.Mode.ID) ? 2 : 3;
                    check(summary.errors() == 0, mode + " no read errors: " + summary.warnings());
                    check(summary.matchedStacks() == expected, mode + " expected " + expected + " found " + summary.matchedStacks());
                    check(summary.hits().stream().anyMatch(h -> h.path().equals(List.of("CHEST[5]", "BLUE_SHULKER_BOX[12]"))), mode + " nested path");
                    if (mode == Query.Mode.SAME) check(summary.matchedItems() == 4, "same excludes count and configured UUID");
                    getLogger().info("PROBE PASS parallel readers=" + readerThreads + " " + mode + ": " + summary.matchedStacks() + " stacks / " + summary.matchedItems() + " units");
                }
            }
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    Set<String> loadedAfter = loadedChunkKeys();
                    Set<String> newlyLoaded = new HashSet<>(loadedAfter);
                    newlyLoaded.removeAll(loadedBefore);
                    check(newlyLoaded.isEmpty(), "scanner must not load chunks: " + newlyLoaded);
                    getLogger().info("PROBE PASS no newly loaded chunks: " + loadedBefore.size() + " -> " + loadedAfter.size());
                    var searchPlugin = (JavaPlugin) Bukkit.getPluginManager().getPlugin("RegionItemSearch");
                    searchPlugin.getConfig().set("scan.delay-per-chunk-ms", 0);
                    searchPlugin.getConfig().set("scan.reader-threads", 24);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "itemsearch id exact SWORD/WLZR");
                    awaitCommand(searchPlugin, false, () -> {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "itemsearch results 1");
                        getLogger().info("PROBE PASS command search/report/pagination");
                        verifyTeleport((RegionItemSearchPlugin) searchPlugin, () -> verifyPlayerCommand(searchPlugin, () -> InventoryEditorProbe.run(
                                (RegionItemSearchPlugin) searchPlugin, UUID.fromString("00000000-0000-0000-0000-000000000001"), () -> verifyMaterialCommands(searchPlugin, () -> {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "itemsearch name 亡灵");
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "itemsearch stop");
                            awaitCommand(searchPlugin, true, () -> {
                                getLogger().info("PROBE PASS stop command and partial report");
                                getLogger().info("PROBE ALL PASS");
                                Bukkit.shutdown();
                            });
                        }), this::fail)));
                    });
                } catch (Exception error) { fail(error); }
            });
        } catch (Exception error) { fail(error); }
    }
    private void verifyReaderConfiguration(RegionItemSearchPlugin plugin) throws Exception {
        Object original = plugin.getConfig().get("scan.reader-threads");
        var bounded = RegionItemSearchPlugin.class.getDeclaredMethod("bounded", String.class, int.class, int.class, int.class);
        bounded.setAccessible(true);
        try {
            for (int configured : new int[]{16, 24, 64, 96}) {
                plugin.getConfig().set("scan.reader-threads", configured);
                int actual = (int) bounded.invoke(plugin, "scan.reader-threads", 2, 1, WorldScanner.MAX_READER_THREADS);
                check(actual == Math.min(configured, 64), "configured reader count must use the expanded limit: " + configured);
            }
        } finally { plugin.getConfig().set("scan.reader-threads", original); }
        getLogger().info("PROBE PASS configured 16/24/64 readers and upper bound 64");
    }
    private void verifyTeleport(RegionItemSearchPlugin plugin, Runnable done) {
        try {
            List<Component> chat = new ArrayList<>();
            AtomicReference<org.bukkit.Location> teleported = new AtomicReference<>();
            UUID actorId = UUID.randomUUID();
            var world = Bukkit.getWorlds().getFirst();
            // Only the player's transport is simulated; chunk read and landing checks use the real server.
            var actor = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(getClassLoader(),
                    new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> actorId;
                        case "getName" -> "ProbeAdmin";
                        case "isOnline", "hasPermission", "isOp" -> true;
                        case "getWorld" -> world;
                        case "getLocation" -> new org.bukkit.Location(world, 8.5, 81, 8.5);
                        case "teleportAsync" -> { teleported.set(((org.bukkit.Location) args[0]).clone()); yield CompletableFuture.completedFuture(true); }
                        case "sendMessage" -> { if (args != null) for (Object arg : args) if (arg instanceof Component c) chat.add(c); yield null; }
                        case "toString" -> "ProbeAdmin";
                        default -> method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? 0 : null;
                    });
            UUID token = (UUID) privateField(plugin, "lastResultId");
            plugin.onCommand(actor, plugin.getCommand("itemsearch"), "itemsearch", new String[]{"results", token.toString(), "1"});
            check(((List<?>) privateField(plugin, "lastContainers")).size() == 1, "two stack hits merge into one container row");
            var plainChat = chat.stream().map(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()::serialize).toList();
            check(plainChat.stream().anyMatch(line -> line.contains(" ×4")), "container row shows summed quantity");
            check(plainChat.stream().noneMatch(line -> line.contains("路径：") || line.contains("外层槽位")), "container chat hides slot/path data");
            check(plainChat.stream().noneMatch(line -> line.contains("[ItemSearch]")), "chat prefix removed");
            chat.forEach(this::checkChatPalette);
            String click = chat.stream().flatMap(c -> clicks(c).stream()).filter(s -> s.startsWith("/itemsearch tp ")).findFirst().orElseThrow();
            var command = click.split(" ");
            plugin.onCommand(actor, plugin.getCommand("itemsearch"), "itemsearch", Arrays.copyOfRange(command, 1, command.length));
            int[] attempts = {0};
            org.bukkit.scheduler.BukkitTask[] poll = {null};
            poll[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    if (teleported.get() == null) { check(++attempts[0] < 15, "teleport callback timeout: " + chat); return; }
                    poll[0].cancel();
                    var location = teleported.get();
                    check(location.getWorld() == world && location.getX() == 8.5 && location.getY() == 81 && location.getZ() == 8.5, "container landing location");
                    plugin.onCommand(actor, plugin.getCommand("itemsearch"), "itemsearch", new String[]{"tp", UUID.randomUUID().toString(), "1"});
                    check(teleported.get() == location, "stale click cannot issue a different teleport");
                    getLogger().info("PROBE PASS coordinate chat command, async chunk read, safe landing, stale token (player transport simulated)");
                    done.run();
                } catch (Exception error) { poll[0].cancel(); fail(error); }
            }, 10, 10);
        } catch (Exception error) { fail(error); }
    }
    private List<String> clicks(Component component) {
        List<String> result = new ArrayList<>();
        if (component.clickEvent() != null) result.add(component.clickEvent().value());
        component.children().forEach(c -> result.addAll(clicks(c)));
        return result;
    }
    private Object privateField(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    private void verifyPlayerCommand(JavaPlugin plugin, Runnable done) {
        try {
            UUID containerToken = (UUID) privateField(plugin, "lastResultId");
            UUID offline = UUID.fromString("00000000-0000-0000-0000-000000000001");
            Map<String, Object> inv = new java.util.LinkedHashMap<>(NbtReader.readAuto(prototype.serializeAsBytes(), 16777216, 128));
            inv.put("Slot", (byte) 0);
            Map<String, Object> ender = new java.util.LinkedHashMap<>(inv);
            ender.put("Slot", (byte) 7);
            Path directory = Bukkit.getWorlds().getFirst().getWorldFolder().toPath().resolve("playerdata");
            Files.createDirectories(directory);
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(directory.resolve(offline + ".dat"))))) {
                out.writeByte(10); out.writeUTF("");
                compound(out, Map.of("DataVersion", Bukkit.getUnsafe().getDataVersion(),
                        "Inventory", List.of(inv), "EnderItems", List.of(ender),
                        "Pos", List.of(123.25d, 80.0d, -456.5d), "ProbeSentinel", "keep-other-player-data"));
            }
            Files.writeString(Path.of("usercache.json"), "[{\"uuid\":\"" + offline + "\",\"name\":\"ProbeOffline\"}]");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "itemsearch player id exact SWORD/WLZR");
            Object controller = privateField(plugin, "playerSearch");
            int[] attempts = {0};
            org.bukkit.scheduler.BukkitTask[] poll = {null};
            poll[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
                try {
                    if (privateField(controller, "active") != null) { check(++attempts[0] < 30, "player search timeout"); return; }
                    poll[0].cancel();
                    var summary = (PlayerScanner.Summary) privateField(controller, "last");
                    check(summary != null && summary.errors() == 0 && summary.matchedStacks() == 2 && summary.matchedItems() == 2, "player command counts");
                    check(summary.hits().stream().anyMatch(h -> h.storage() == PlayerScanner.Storage.INVENTORY && h.slot() == 0), "backpack slot");
                    check(summary.hits().stream().anyMatch(h -> h.storage() == PlayerScanner.Storage.ENDER_CHEST && h.slot() == 7), "ender slot");
                    check(containerToken.equals(privateField(plugin, "lastResultId")), "player results do not replace container results");
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "itemsearch player results 1");
                    verifyPlayerChat(plugin, offline);
                    getLogger().info("PROBE PASS offline player command, ender/backpack paths, separate result state");
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "itemsearch player name 亡灵");
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "itemsearch player stop");
                    Bukkit.getScheduler().runTaskLater(this, done, 20);
                } catch (Exception error) { poll[0].cancel(); fail(error); }
            }, 20, 20);
        } catch (Exception error) { fail(error); }
    }
    private void verifyPlayerChat(JavaPlugin plugin, UUID player) {
        List<Component> chat = new ArrayList<>();
        UUID actorId = UUID.randomUUID();
        var actor = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(getClassLoader(),
                new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> actorId;
                    case "getName" -> "ChatProbeAdmin";
                    case "isOnline", "hasPermission", "isOp" -> true;
                    case "sendMessage" -> { if (args != null) for (Object arg : args) if (arg instanceof Component c) chat.add(c); yield null; }
                    case "toString" -> "ChatProbeAdmin";
                    default -> method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? 0 : null;
                });
        plugin.onCommand(actor, plugin.getCommand("itemsearch"), "itemsearch", new String[]{"player", "results", "1"});
        var plain = chat.stream().map(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()::serialize).toList();
        check(chat.size() == 8, "two player hits occupy three lines each plus header/footer");
        check(plain.stream().noneMatch(line -> line.contains("[ItemSearch]") || line.contains("UUID：")
                || line.contains(player.toString()) || line.contains("路径：")), "player chat shows one name, without UUID duplication or path line");
        check(plain.stream().filter(line -> line.contains("ProbeOffline")).count() == 2, "one identity per player item row");
        List<String> clickValues = chat.stream().flatMap(c -> clicks(c).stream()).toList();
        check(java.util.Collections.frequency(clickValues, player.toString()) == 2, "name click copies canonical UUID for each result");
        check(java.util.Collections.frequency(clickValues, "/itemsearch player inventory " + player) == 2
                && java.util.Collections.frequency(clickValues, "/itemsearch player ender " + player) == 2,
                "gray chat retains both inventory editing actions");
        chat.forEach(this::checkChatPalette);
        getLogger().info("PROBE PASS compact gray player chat, single identity, no prefix, UUID copy and editing actions");
    }
    private void checkChatPalette(Component component) {
        check(component.color() == null || Set.of(NamedTextColor.GRAY, NamedTextColor.DARK_GRAY, NamedTextColor.WHITE)
                .contains(component.color()), "chat contains only gray, dark gray and white");
        component.children().forEach(this::checkChatPalette);
        var hover = component.hoverEvent();
        if (hover != null && hover.action() == net.kyori.adventure.text.event.HoverEvent.Action.SHOW_TEXT
                && hover.value() instanceof Component text) checkChatPalette(text);
    }
    private int type(Object value) {
        if (value instanceof Byte) return 1;
        if (value instanceof Short) return 2;
        if (value instanceof Integer) return 3;
        if (value instanceof Long) return 4;
        if (value instanceof Float) return 5;
        if (value instanceof Double) return 6;
        if (value instanceof byte[]) return 7;
        if (value instanceof String) return 8;
        if (value instanceof List<?>) return 9;
        if (value instanceof Map<?, ?>) return 10;
        if (value instanceof int[]) return 11;
        if (value instanceof long[]) return 12;
        throw new IllegalArgumentException("unsupported fixture tag " + value);
    }
    private void compound(DataOutputStream out, Map<?, ?> map) throws Exception {
        for (var entry : map.entrySet()) { out.writeByte(type(entry.getValue())); out.writeUTF(entry.getKey().toString()); payload(out, entry.getValue()); }
        out.writeByte(0);
    }
    private void payload(DataOutputStream out, Object value) throws Exception {
        switch (type(value)) {
            case 1 -> out.writeByte(((Number) value).byteValue());
            case 2 -> out.writeShort(((Number) value).shortValue());
            case 3 -> out.writeInt(((Number) value).intValue());
            case 4 -> out.writeLong(((Number) value).longValue());
            case 5 -> out.writeFloat(((Number) value).floatValue());
            case 6 -> out.writeDouble(((Number) value).doubleValue());
            case 7 -> { byte[] array = (byte[]) value; out.writeInt(array.length); out.write(array); }
            case 8 -> out.writeUTF((String) value);
            case 9 -> { List<?> list = (List<?>) value; out.writeByte(list.isEmpty() ? 0 : type(list.getFirst())); out.writeInt(list.size()); for (Object entry : list) payload(out, entry); }
            case 10 -> compound(out, (Map<?, ?>) value);
            case 11 -> { int[] array = (int[]) value; out.writeInt(array.length); for (int entry : array) out.writeInt(entry); }
            case 12 -> { long[] array = (long[]) value; out.writeInt(array.length); for (long entry : array) out.writeLong(entry); }
        }
    }
    private void awaitCommand(JavaPlugin searchPlugin, boolean stopped, Runnable done) {
        int[] attempts = {0};
        org.bukkit.scheduler.BukkitTask[] timer = {null};
        timer[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                var active = searchPlugin.getClass().getDeclaredField("active");
                active.setAccessible(true);
                if (active.get(searchPlugin) != null) {
                    check(++attempts[0] < 30, "command timeout");
                    return;
                }
                timer[0].cancel();
                var result = searchPlugin.getClass().getDeclaredField("lastResult");
                result.setAccessible(true);
                var summary = (WorldScanner.Summary) result.get(searchPlugin);
                check(summary != null && summary.cancelled() == stopped, "command cancellation state");
                if (!stopped) check(summary.matchedStacks() == 2 && summary.matchedItems() == 4 && summary.errors() == 0, "command result counts");
                var report = searchPlugin.getClass().getDeclaredField("lastReport");
                report.setAccessible(true);
                check(java.nio.file.Files.isRegularFile((java.nio.file.Path) report.get(searchPlugin)), "report persisted");
                done.run();
            } catch (Exception error) { timer[0].cancel(); fail(error); }
        }, 20, 20);
    }
    private void verifyMaterialCommands(JavaPlugin plugin, Runnable done) {
        try {
            var hand = (org.bukkit.inventory.PlayerInventory) java.lang.reflect.Proxy.newProxyInstance(getClassLoader(),
                    new Class<?>[]{org.bukkit.inventory.PlayerInventory.class}, (proxy, method, args) ->
                            method.getName().equals("getItemInMainHand") ? prototype.clone() : null);
            UUID actorId = UUID.randomUUID();
            var actor = (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(getClassLoader(),
                    new Class<?>[]{org.bukkit.entity.Player.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getUniqueId" -> actorId;
                        case "getName" -> "MaterialProbeAdmin";
                        case "isOnline", "hasPermission", "isOp" -> true;
                        case "getInventory" -> hand;
                        case "toString" -> "MaterialProbeAdmin";
                        default -> method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? 0 : null;
                    });
            plugin.onCommand(actor, plugin.getCommand("itemsearch"), "itemsearch", new String[]{"material"});
            awaitMaterial(plugin, false, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "itemsearch material exact NETHERITE_SWORD");
                awaitMaterial(plugin, false, () -> {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "itemsearch player material exact minecraft:netherite_sword");
                    awaitMaterial(plugin, true, () -> {
                        plugin.onCommand(actor, plugin.getCommand("itemsearch"), "itemsearch", new String[]{"player", "material"});
                        awaitMaterial(plugin, true, done);
                    });
                });
            });
        } catch (Exception error) { fail(error); }
    }
    private void awaitMaterial(JavaPlugin plugin, boolean players, Runnable done) {
        int[] attempts = {0};
        org.bukkit.scheduler.BukkitTask[] timer = {null};
        timer[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                Object owner = players ? privateField(plugin, "playerSearch") : plugin;
                if (privateField(owner, "active") != null) { check(++attempts[0] < 30, "material command timeout"); return; }
                timer[0].cancel();
                if (players) {
                    var summary = (PlayerScanner.Summary) privateField(owner, "last");
                    check(summary != null && summary.errors() == 0 && summary.matchedStacks() == 1 && summary.matchedItems() == 2,
                            "material player query matches updated inventory despite custom metadata");
                } else {
                    var summary = (WorldScanner.Summary) privateField(owner, "lastResult");
                    check(summary != null && summary.errors() == 0 && summary.matchedStacks() == 3 && summary.matchedItems() == 5,
                            "material container query ignores MMO type and custom metadata");
                }
                getLogger().info("PROBE PASS " + (players ? "player" : "container") + " MATERIAL command and quantity");
                done.run();
            } catch (Exception error) { timer[0].cancel(); fail(error); }
        }, 10, 10);
    }
    private Set<String> loadedChunkKeys() {
        return Bukkit.getWorlds().stream().flatMap(w -> Arrays.stream(w.getLoadedChunks())
                .map(c -> w.getUID() + "/" + c.getX() + "/" + c.getZ())).collect(Collectors.toSet());
    }
    private void check(boolean condition, String description) { if (!condition) throw new IllegalStateException(description); }
    private void fail(Exception error) {
        getLogger().log(java.util.logging.Level.SEVERE, "PROBE FAILED", error);
        Bukkit.getScheduler().runTask(this, Bukkit::shutdown);
    }
}
