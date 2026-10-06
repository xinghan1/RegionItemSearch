package com.xinghan.itemsearch.probe;

import com.xinghan.itemsearch.BukkitCompat;
import com.xinghan.itemsearch.InventoryTransferView;
import com.xinghan.itemsearch.PlayerInventoryAccess;
import com.xinghan.itemsearch.PlayerInventoryEditor;
import com.xinghan.itemsearch.RegionItemSearchPlugin;
import com.xinghan.itemsearch.player.PlayerScanner;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/** Runtime-only proof using two real offline TCP players. Never packaged into the product JAR. */
public final class CommonTransferProbe extends JavaPlugin implements Listener {
    private final AtomicInteger checks = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean(), ended = new AtomicBoolean();
    private RegionItemSearchPlugin search;
    private com.xinghan.itemsearch.SchedulerCompat scheduler;
    private PlayerInventoryEditor editor;
    private PlayerInventoryAccess access;
    private Player viewer, target;
    private ItemStack[] viewerBaseline;
    private String viewerThread, targetThread;

    @Override public void onEnable() {
        try {
            if (Bukkit.getPort() < 25594 || Bukkit.getPort() > 25599 || !"compat-world".equals(Bukkit.getWorlds().get(0).getName()))
                throw new IllegalStateException("This destructive fixture is restricted to isolated compat-world ports 25594..25599");
            // Allow both concurrent login reservations plus the native player-list admission check.
            try { Bukkit.getServer().getClass().getMethod("setMaxPlayers", int.class).invoke(Bukkit.getServer(), 8); } catch (ReflectiveOperationException old) { }
            Bukkit.setWhitelist(false);
            scheduler = new com.xinghan.itemsearch.SchedulerCompat(this);
            search = (RegionItemSearchPlugin) Bukkit.getPluginManager().getPlugin("RegionItemSearch");
            if (search == null) throw new IllegalStateException("RegionItemSearch is required");
            editor = (PlayerInventoryEditor) field(search, "inventoryEditor");
            access = (PlayerInventoryAccess) field(search, "inventoryAccess");
            Bukkit.getPluginManager().registerEvents(this, this);
            getLogger().info("COMMON TRANSFER PROBE waiting for actual CommonViewer and CommonTarget TCP players");
            global(1800, () -> { if (!ended.get()) fail(new IllegalStateException("Real-player proof timed out")); });
        } catch (Throwable error) { fail(error); }
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        if (!event.getPlayer().getName().equals("CommonViewer") && !event.getPlayer().getName().equals("CommonTarget")) return;
        global(5, this::startIfReady);
    }

    private void startIfReady() {
        viewer = Bukkit.getPlayerExact("CommonViewer"); target = Bukkit.getPlayerExact("CommonTarget");
        if (viewer == null || target == null || !started.compareAndSet(false, true)) return;
        getLogger().info("COMMON TRANSFER PROBE real players " + viewer.getUniqueId() + " / " + target.getUniqueId());
        AtomicInteger prepared = new AtomicInteger();
        teleport(viewer, 0, () -> { if (prepared.incrementAndGet() == 2) begin(); });
        teleport(target, 4096, () -> { if (prepared.incrementAndGet() == 2) begin(); });
    }

    private void teleport(Player player, int x, Runnable ready) {
        on(player, () -> {
            player.setGameMode(GameMode.SURVIVAL);
            player.setAllowFlight(true); player.setFlying(true);
            try { Player.class.getMethod("setInvulnerable", boolean.class).invoke(player, true); } catch (ReflectiveOperationException old) { }
            player.addAttachment(this, "itemsearch.admin", true);
            player.addAttachment(this, "itemsearch.edit", true);
            teleportCompat(player, new Location(player.getWorld(), x + 0.5, 85, 0.5)).whenComplete((ok, error) -> {
                if (error != null || !Boolean.TRUE.equals(ok)) { fail(new IllegalStateException("Cross-region teleport failed", error)); return; }
                later(player, 5, () -> {
                    check(player.getLocation().getBlockX() == x, "actual player is at isolated x=" + x);
                    ready.run();
                });
            });
        });
    }

    private void begin() {
        on(viewer, () -> {
            viewerThread = Thread.currentThread().getName();
            check(scheduler.owns(viewer), "viewer callback owns actual viewer entity");
            check(scheduler.isFolia() ? !scheduler.owns(target) : scheduler.owns(target), "target ownership predicate matches actual threading model");
            check(InventoryTransferView.owns(viewer) && (scheduler.isFolia() ? !InventoryTransferView.owns(target) : InventoryTransferView.owns(target)), "adapter correctly identifies inventory owner thread");
            fixtureViewer();
            on(target, () -> {
                targetThread = Thread.currentThread().getName();
                check(scheduler.owns(target) && (scheduler.isFolia() ? !scheduler.owns(viewer) : scheduler.owns(viewer)), "target callback runs on correct server owner thread");
                check(InventoryTransferView.owns(target) && (scheduler.isFolia() ? !InventoryTransferView.owns(viewer) : InventoryTransferView.owns(viewer)), "target adapter threading predicate matches server");
                fixtureTarget();
                on(viewer, this::cancelCase);
            });
        });
    }

    private void fixtureViewer() {
        viewer.closeInventory(); viewer.setItemOnCursor(null); viewer.getInventory().clear(); viewer.getEnderChest().clear();
        viewer.getInventory().setItem(0, new ItemStack(Material.STONE, 5));
        viewerBaseline = BukkitCompat.storageContents(viewer);
    }
    private void fixtureTarget() {
        target.getInventory().clear(); target.getEnderChest().clear();
        target.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        target.getInventory().setItem(1, new ItemStack(Material.EMERALD, 3));
    }

    private void cancelCase() {
        editor.open(viewer, target.getUniqueId(), PlayerScanner.Storage.INVENTORY);
        menu(54, 0, top -> {
            check(stack(top.getItem(27), Material.DIAMOND, 4), "real detached GUI displays target hotbar slot");
            click(27, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            check(BukkitCompat.air(top.getItem(27)), "SHIFT removes clone from target stage");
            check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)), "SHIFT leaves real administrator inventory untouched");
            check(stack(BukkitCompat.viewItem(viewer.getOpenInventory(), 54), Material.DIAMOND, 4), "raw bottom slot reads detached staged transfer");
            click(53, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            later(viewer, 4, () -> {
                check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)) && BukkitCompat.air(viewer.getItemOnCursor()), "cancel leaves administrator inventory and cursor unchanged");
                on(target, () -> {
                    check(stack(target.getInventory().getItem(0), Material.DIAMOND, 4), "cancel preserves real target inventory");
                    on(viewer, this::saveCase);
                });
            });
        });
    }

    private void saveCase() {
        editor.open(viewer, target.getUniqueId(), PlayerScanner.Storage.INVENTORY);
        menu(54, 0, top -> {
            click(27, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            click(49, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            waitClosed(0, () -> {
                check(stack(viewer.getInventory().getItem(9), Material.DIAMOND, 4), "cross-region save applies administrator stage on viewer owner thread");
                check(BukkitCompat.air(viewer.getItemOnCursor()), "successful transfer clears temporary cursor");
                on(target, () -> {
                    check(BukkitCompat.air(target.getInventory().getItem(0)), "cross-region save removes only intended target stack");
                    check(stack(target.getInventory().getItem(1), Material.EMERALD, 3), "cross-region save preserves untouched target slot");
                    fixtureTarget(); on(viewer, () -> { fixtureViewer(); depositCase(); });
                });
            });
        });
    }

    private void depositCase() {
        editor.open(viewer, target.getUniqueId(), PlayerScanner.Storage.INVENTORY);
        menu(54, 0, top -> {
            click(81, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY); // bottom hotbar slot 0
            check(stack(top.getItem(0), Material.STONE, 5), "SHIFT can deposit administrator stage into target storage");
            on(target, () -> {
                target.getInventory().setItem(8, new ItemStack(Material.GOLD_INGOT, 7));
                on(viewer, () -> {
                    click(49, ClickType.LEFT, InventoryAction.PICKUP_ALL);
                    waitClosed(0, () -> {
                        check(BukkitCompat.air(viewer.getInventory().getItem(0)), "deposit removes administrator real stack only after save");
                        on(target, () -> {
                            check(stack(target.getInventory().getItem(9), Material.STONE, 5), "deposit maps target storage GUI slot to actual slot 9");
                            check(stack(target.getInventory().getItem(8), Material.GOLD_INGOT, 7), "dirty-slot merge preserves concurrently changed untouched target slot");
                            fixtureTarget(); on(viewer, () -> { fixtureViewer(); conflictCase(); });
                        });
                    });
                });
            });
        });
    }

    private void conflictCase() {
        editor.open(viewer, target.getUniqueId(), PlayerScanner.Storage.INVENTORY);
        menu(54, 0, top -> {
            click(27, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            on(target, () -> {
                target.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 6));
                on(viewer, () -> {
                    click(49, ClickType.LEFT, InventoryAction.PICKUP_ALL);
                    later(viewer, 8, () -> {
                        check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)), "dirty target conflict does not apply administrator stage");
                        check(BukkitCompat.viewTop(viewer.getOpenInventory()) == top, "target conflict remains reviewable in editor");
                        click(53, ClickType.LEFT, InventoryAction.PICKUP_ALL);
                        later(viewer, 3, () -> on(target, () -> {
                            check(stack(target.getInventory().getItem(0), Material.DIAMOND, 6), "conflict preserves independently changed target stack");
                            fixtureTarget(); on(viewer, this::rollbackCase);
                        }));
                    });
                });
            });
        });
    }

    private void rollbackCase() {
        if (!scheduler.isFolia()) { guardedCancelCase(); return; }
        access.load(viewer, target.getUniqueId(), PlayerScanner.Storage.INVENTORY, loaded -> {
            check(scheduler.owns(viewer), "online load callback is dispatched to viewer entity thread");
            ItemStack[] proposed = loaded.items(); proposed[0] = null;
            AtomicInteger guards = new AtomicInteger();
            access.saveTransfer(viewer, loaded, proposed, () -> {
                check(scheduler.owns(viewer), "final transaction guard executes on viewer entity thread");
                return guards.incrementAndGet() == 1;
            }, message -> fail(new AssertionError("Second-phase guard rejection unexpectedly saved")), reason -> {
                check(guards.get() == 2, "guard is rechecked after target mutation, before administrator completion");
                check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)), "rejected second phase preserves administrator baseline");
                on(target, () -> {
                    check(stack(target.getInventory().getItem(0), Material.DIAMOND, 4), "guard rejection conditionally restores target on target entity thread");
                    on(viewer, this::offlineCase);
                });
            });
        }, reason -> fail(new AssertionError("Online load failed: " + reason)));
    }

    private java.util.concurrent.CompletableFuture<Boolean> teleportCompat(Player player, Location location) {
        try {
            java.lang.reflect.Method teleport = Player.class.getMethod("teleportAsync", Location.class);
            return (java.util.concurrent.CompletableFuture<Boolean>) teleport.invoke(player, location);
        } catch (NoSuchMethodException classic) { return java.util.concurrent.CompletableFuture.completedFuture(player.teleport(location)); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private void guardedCancelCase() {
        access.load(viewer, target.getUniqueId(), PlayerScanner.Storage.INVENTORY, loaded -> {
            ItemStack[] proposed = loaded.items(); proposed[0] = null;
            access.saveTransfer(viewer, loaded, proposed, () -> false,
                reason -> fail(new AssertionError("False guard unexpectedly committed")), reason -> {
                    check(stack(target.getInventory().getItem(0), Material.DIAMOND, 4), "false final guard prevents same-thread target mutation");
                    check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)), "false final guard preserves administrator inventory");
                    offlineCase();
                });
        }, reason -> fail(new AssertionError(reason)));
    }

    private void offlineCase() {
        try {
            java.util.UUID uuid = java.util.UUID.randomUUID();
            java.nio.file.Path file = BukkitCompat.playerDataDirectory(BukkitCompat.mainWorld()).resolve(uuid + ".dat");
            java.nio.file.Files.createDirectories(file.getParent());
            java.util.Map<String,Object> root = new java.util.LinkedHashMap<>();
            java.util.Map<String,Object> diamond = com.xinghan.itemsearch.nbt.NbtReader.readAuto(BukkitCompat.serializeItem(new ItemStack(Material.DIAMOND, 4)), 16777216, 128);
            diamond.put("Slot", (byte) 0);
            root.put("Inventory", java.util.Collections.singletonList(diamond));
            java.util.Map<String,Object> gold = com.xinghan.itemsearch.nbt.NbtReader.readAuto(BukkitCompat.serializeItem(new ItemStack(Material.GOLD_INGOT, 2)), 16777216, 128);
            gold.put("Slot", (byte) 0);
            root.put("EnderItems", java.util.Collections.singletonList(gold));
            root.put("Health", 20.0f); root.put("ProbeSentinel", "preserve-unrelated-player-data");
            root.put("Pos", Arrays.asList(100.5d, 85.0d, -11.25d));
            if (BukkitCompat.dataVersion() > 0) root.put("DataVersion", BukkitCompat.dataVersion());
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(bytes)) { gzip.write(com.xinghan.itemsearch.nbt.NbtWriter.write(root, 16777216, 128)); }
            final byte[] original = bytes.toByteArray();
            java.nio.file.Files.write(file, original);
            access.load(viewer, uuid, PlayerScanner.Storage.INVENTORY, loaded -> {
                check(!loaded.online() && stack(loaded.items()[0], Material.DIAMOND, 4), "actual offline access loads disk inventory with native item codec");
                ItemStack[] proposed = loaded.items(); proposed[0] = new ItemStack(Material.EMERALD, 2);
                access.saveTransfer(viewer, loaded, proposed, () -> true, result -> {
                    try {
                        check(scheduler.owns(viewer), "offline main commit callback runs on actual viewer owner thread");
                        java.util.Map<String,Object> saved = com.xinghan.itemsearch.nbt.NbtReader.readAuto(java.nio.file.Files.readAllBytes(file), 16777216, 128);
                        check("preserve-unrelated-player-data".equals(saved.get("ProbeSentinel")) && root.get("Pos").equals(saved.get("Pos")), "offline edit preserves unrelated saved player fields");
                        com.xinghan.itemsearch.player.PlayerDataStore.Snapshot snapshot = new com.xinghan.itemsearch.player.PlayerDataStore().load(file.getParent(), uuid, 16777216, 128);
                        java.util.List<java.util.Map<String,Object>> items = (java.util.List<java.util.Map<String,Object>>) snapshot.root().get("Inventory");
                        check(items.size() == 1 && "EMERALD".equals(com.xinghan.itemsearch.item.ItemData.from(items.get(0), java.util.Collections.<String>emptySet()).material()) && ((Number)items.get(0).get("Slot")).intValue() == 0, "offline save changes intended native material and slot");
                        boolean exactBackup = false;
                        try (java.nio.file.DirectoryStream<java.nio.file.Path> backups = java.nio.file.Files.newDirectoryStream(search.getDataFolder().toPath().resolve("player-backups"), uuid + "-*.dat")) {
                            for (java.nio.file.Path backup : backups) if (Arrays.equals(original, java.nio.file.Files.readAllBytes(backup))) exactBackup = true;
                        }
                        check(exactBackup, "offline transaction preserves exact compressed original bytes in backup");
                        staleOfflineCase(uuid, file);
                    } catch (Throwable error) { fail(error); }
                }, reason -> fail(new AssertionError("Offline save failed: " + reason)));
            }, reason -> fail(new AssertionError("Offline load failed: " + reason)));
        } catch (Throwable error) { fail(error); }
    }

    private void staleOfflineCase(java.util.UUID uuid, java.nio.file.Path file) {
        access.load(viewer, uuid, PlayerScanner.Storage.INVENTORY, loaded -> {
            try {
                byte[] external = java.nio.file.Files.readAllBytes(file);
                java.util.Map<String,Object> root = com.xinghan.itemsearch.nbt.NbtReader.readAuto(external, 16777216, 128);
                root.put("ProbeSentinel", "external-change-after-snapshot");
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(bytes)) { gzip.write(com.xinghan.itemsearch.nbt.NbtWriter.write(root, 16777216, 128)); }
                final byte[] changed = bytes.toByteArray(); java.nio.file.Files.write(file, changed);
                ItemStack[] proposed = loaded.items(); proposed[0] = null;
                access.saveTransfer(viewer, loaded, proposed, () -> true,
                    result -> fail(new AssertionError("Stale offline snapshot unexpectedly committed")), reason -> {
                        try {
                            check(Arrays.equals(changed, java.nio.file.Files.readAllBytes(file)), "offline full-file CAS rejects external playerdata changes");
                            check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)), "offline failure leaves actual administrator inventory unchanged");
                            offlineGuiCase(uuid, file);
                        } catch (Throwable error) { fail(error); }
                    });
            } catch (Throwable error) { fail(error); }
        }, reason -> fail(new AssertionError(reason)));
    }

    private void offlineGuiCase(java.util.UUID uuid, java.nio.file.Path file) {
        try {
            final byte[] before = java.nio.file.Files.readAllBytes(file);
            editor.open(viewer, uuid, PlayerScanner.Storage.INVENTORY);
            menu(54, 0, top -> {
                check(stack(top.getItem(27), Material.EMERALD, 2), "offline GUI renders actual disk item in correct hotbar slot");
                click(27, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
                check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)), "offline GUI transfer remains staged before save");
                click(53, ClickType.LEFT, InventoryAction.PICKUP_ALL);
                later(viewer, 3, () -> {
                    try {
                        check(Arrays.equals(before, java.nio.file.Files.readAllBytes(file)), "offline GUI cancel preserves entire original playerdata file");
                        check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)), "offline GUI cancel preserves administrator inventory");
                        editor.open(viewer, uuid, PlayerScanner.Storage.INVENTORY);
                        menu(54, 0, second -> {
                            click(27, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
                            click(49, ClickType.LEFT, InventoryAction.PICKUP_ALL);
                            waitClosed(0, () -> {
                                try {
                                    check(stack(viewer.getInventory().getItem(9), Material.EMERALD, 2), "offline GUI save completes target commit and administrator transfer");
                                    java.util.Map<String,Object> saved = com.xinghan.itemsearch.nbt.NbtReader.readAuto(java.nio.file.Files.readAllBytes(file), 16777216, 128);
                                    check(((java.util.List<?>) saved.get("Inventory")).isEmpty(), "offline GUI save removes transferred stack from disk inventory");
                                    check("external-change-after-snapshot".equals(saved.get("ProbeSentinel")), "offline GUI save preserves unrelated latest saved fields");
                                    viewerBaseline = BukkitCompat.storageContents(viewer);
                                    offlineEnderGuiCase(uuid, file);
                                } catch (Throwable error) { fail(error); }
                            });
                        });
                    } catch (Throwable error) { fail(error); }
                });
            });
        } catch (Throwable error) { fail(error); }
    }

    private void offlineEnderGuiCase(java.util.UUID uuid, java.nio.file.Path file) {
        editor.open(viewer, uuid, PlayerScanner.Storage.ENDER_CHEST);
        menu(36, 0, top -> {
            check(stack(top.getItem(0), Material.GOLD_INGOT, 2), "offline ender GUI preserves separate EnderItems storage");
            click(0, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
            check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)), "offline ender transfer is staged until explicit save");
            click(31, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            waitClosed(0, () -> {
                try {
                    check(stack(viewer.getInventory().getItem(10), Material.GOLD_INGOT, 2), "offline ender save transfers intact stack to administrator");
                    java.util.Map<String,Object> saved = com.xinghan.itemsearch.nbt.NbtReader.readAuto(java.nio.file.Files.readAllBytes(file), 16777216, 128);
                    check(((java.util.List<?>) saved.get("EnderItems")).isEmpty() && ((java.util.List<?>) saved.get("Inventory")).isEmpty(), "offline ender commit updates selected storage without restoring old inventory");
                    viewerBaseline = BukkitCompat.storageContents(viewer);
                    retirementCase();
                } catch (Throwable error) { fail(error); }
            });
        });
    }

    private void retirementCase() {
        editor.open(viewer, target.getUniqueId(), PlayerScanner.Storage.INVENTORY);
        menu(54, 0, top -> {
            // Real detached cursor, as produced by native pickup. The server's ordinary close path must not return it.
            viewer.setItemOnCursor(top.getItem(27).clone()); top.setItem(27, null);
            check(stack(viewer.getItemOnCursor(), Material.DIAMOND, 4), "retirement fixture holds target clone on real menu cursor");
            on(target, () -> target.kickPlayer("Isolated target-retirement fixture"));
            later(viewer, 10, () -> {
                check(!target.isOnline(), "actual target TCP player retires");
                check(BukkitCompat.viewTop(viewer.getOpenInventory()) != top, "target quit closes editor on viewer owner thread");
                check(BukkitCompat.air(viewer.getItemOnCursor()), "target quit clears temporary carried clone");
                check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)), "target quit does not leak clone into administrator inventory");
                disableCase();
            });
        });
    }

    private void disableCase() {
        viewer.getEnderChest().setItem(0, new ItemStack(Material.DIAMOND, 4));
        editor.open(viewer, viewer.getUniqueId(), PlayerScanner.Storage.ENDER_CHEST);
        menu(36, 0, top -> {
            viewer.setItemOnCursor(top.getItem(0).clone()); top.setItem(0, null);
            check(stack(viewer.getItemOnCursor(), Material.DIAMOND, 4), "disabled-cleanup fixture has real carried clone");
            global(1, () -> {
                Bukkit.getPluginManager().disablePlugin(search);
                check(!search.isEnabled(), "main plugin is actually disabled from correct server global thread");
                later(viewer, 3, () -> {
                    check(BukkitCompat.air(viewer.getItemOnCursor()), "native entity cleanup clears cursor after plugin disabled");
                    check(BukkitCompat.viewTop(viewer.getOpenInventory()) != top, "disabled cleanup closes clone menu despite disabled Bukkit scheduler wrapper");
                    check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)), "disabled cleanup preserves administrator real baseline");
                    check(stack(viewer.getEnderChest().getItem(0), Material.DIAMOND, 4), "disabled cleanup preserves source ender chest");
                    check(((java.util.Map<?, ?>) field(editor, "viewers")).isEmpty(), "disabled cleanup releases editor viewer sessions");
                    check(((java.util.Map<?, ?>) field(editor, "targets")).isEmpty(), "disabled cleanup releases editor target locks");
                    finish();
                });
            });
        });
    }

    private interface MenuAction { void run(Inventory top); }
    private void menu(int size, int attempts, MenuAction action) {
        if (attempts > 100) { fail(new IllegalStateException("Editor never opened actual native menu size " + size)); return; }
        Inventory top = BukkitCompat.viewTop(viewer.getOpenInventory());
        if (top.getSize() == size && top.getHolder() != null && top.getHolder().getClass().getName().contains("PlayerInventoryEditor$Menu")) action.run(top);
        else later(viewer, 1, () -> menu(size, attempts + 1, action));
    }
    private void waitClosed(int attempts, Runnable next) {
        if (attempts > 100) { fail(new IllegalStateException("Actual editor save never completed")); return; }
        Inventory top = BukkitCompat.viewTop(viewer.getOpenInventory());
        if (top.getHolder() != null && top.getHolder().getClass().getName().contains("PlayerInventoryEditor$Menu"))
            later(viewer, 1, () -> waitClosed(attempts + 1, next));
        else next.run();
    }
    private void click(int raw, ClickType click, InventoryAction action) {
        check(scheduler.owns(viewer), "GUI action runs on actual viewer owning region");
        InventoryClickEvent event = new InventoryClickEvent(viewer.getOpenInventory(), InventoryType.SlotType.CONTAINER, raw, click, action);
        Bukkit.getPluginManager().callEvent(event);
        check(event.isCancelled(), "editor owns staged SHIFT/control action raw=" + raw);
    }
    private static boolean stack(ItemStack item, Material material, int count) { return item != null && item.getType() == material && item.getAmount() == count; }
    private static Object field(Object value, String name) {
        try { Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        getLogger().info("COMMON CHECK " + checks.incrementAndGet() + ": " + description);
    }
    private void on(Player player, Runnable task) { scheduler.entity(player, safe(task), () -> fail(new IllegalStateException("Unexpected fixture player retirement " + player.getName()))); }
    private void later(Player player, long ticks, Runnable task) { scheduler.entityLater(player, ticks, safe(task), () -> fail(new IllegalStateException("Unexpected fixture player retirement " + player.getName()))); }
    private void global(long ticks, Runnable task) { scheduler.globalLater(ticks, safe(task)); }
    private Runnable safe(Runnable task) { return () -> { if (!ended.get()) try { task.run(); } catch (Throwable error) { fail(error); } }; }
    private void finish() {
        if (!ended.compareAndSet(false, true)) return;
        getLogger().info("COMMON TRANSFER PROBE PASSED " + checks.get() + " checks; actual TCP players, cross-owner regions; viewerThread=" + viewerThread + "; targetThread=" + targetThread);
        if (scheduler != null) scheduler.globalLater(3L, Bukkit::shutdown); else Bukkit.shutdown();
    }
    private void fail(Throwable error) {
        if (!ended.compareAndSet(false, true)) return;
        getLogger().log(Level.SEVERE, "COMMON TRANSFER PROBE FAILED after " + checks.get() + " checks", error);
        if (scheduler != null) scheduler.globalLater(3L, Bukkit::shutdown); else Bukkit.shutdown();
    }
}
