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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/** Runtime-only proof using two real offline TCP players. Never packaged into the product JAR. */
public final class FoliaTransferProbe extends JavaPlugin implements Listener {
    private final AtomicInteger checks = new AtomicInteger();
    private final AtomicBoolean started = new AtomicBoolean(), ended = new AtomicBoolean();
    private RegionItemSearchPlugin search;
    private PlayerInventoryEditor editor;
    private PlayerInventoryAccess access;
    private Player viewer, target, victim;
    private ItemStack[] viewerBaseline;
    private String viewerThread, targetThread;
    private volatile java.util.concurrent.CountDownLatch lifecycleObserved;
    private volatile boolean pendingDeath, pendingQuit;
    private java.nio.file.Path victimFile;

    @Override public void onEnable() {
        try {
            if (Bukkit.getPort() != 25598 || !"compat-world".equals(Bukkit.getWorlds().get(0).getName()))
                throw new IllegalStateException("This destructive fixture is restricted to isolated compat-world:25598");
            // Allow both concurrent login reservations plus the native player-list admission check.
            Bukkit.setMaxPlayers(8);
            search = (RegionItemSearchPlugin) Bukkit.getPluginManager().getPlugin("RegionItemSearch");
            if (search == null || !search.scheduler().isFolia()) throw new IllegalStateException("Real Folia is required");
            editor = (PlayerInventoryEditor) field(search, "inventoryEditor");
            access = (PlayerInventoryAccess) field(search, "inventoryAccess");
            Bukkit.getPluginManager().registerEvents(this, this);
            getLogger().info("FOLIA TRANSFER PROBE waiting for actual FoliaViewer and FoliaTarget TCP players");
            global(1800, () -> { if (!ended.get()) fail(new IllegalStateException("Real-player proof timed out")); });
        } catch (Throwable error) { fail(error); }
    }

    @EventHandler public void join(PlayerJoinEvent event) {
        if (!event.getPlayer().getName().equals("FoliaViewer") && !event.getPlayer().getName().equals("FoliaTarget")
                && !event.getPlayer().getName().equals("FoliaVictim")) return;
        global(5, this::startIfReady);
    }

    private void startIfReady() {
        viewer = Bukkit.getPlayerExact("FoliaViewer"); target = Bukkit.getPlayerExact("FoliaTarget");
        victim = Bukkit.getPlayerExact("FoliaVictim");
        if (viewer == null || target == null || victim == null || !started.compareAndSet(false, true)) return;
        getLogger().info("FOLIA TRANSFER PROBE real players " + viewer.getUniqueId() + " / " + target.getUniqueId());
        AtomicInteger prepared = new AtomicInteger();
        teleport(viewer, 0, () -> { if (prepared.incrementAndGet() == 3) begin(); });
        teleport(target, 4096, () -> { if (prepared.incrementAndGet() == 3) begin(); });
        teleport(victim, 8192, () -> { if (prepared.incrementAndGet() == 3) begin(); });
    }

    private void teleport(Player player, int x, Runnable ready) {
        on(player, () -> {
            player.setGameMode(GameMode.SURVIVAL);
            player.setAllowFlight(true); player.setFlying(true); player.setInvulnerable(true);
            player.addAttachment(this, "itemsearch.admin", true);
            player.addAttachment(this, "itemsearch.edit", true);
            player.teleportAsync(new Location(player.getWorld(), x + 0.5, 85, 0.5)).whenComplete((ok, error) -> {
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
            check(Bukkit.isOwnedByCurrentRegion(viewer), "viewer callback owns actual viewer entity");
            check(!Bukkit.isOwnedByCurrentRegion(target), "target is in a genuinely distinct Folia region");
            check(InventoryTransferView.owns(viewer) && !InventoryTransferView.owns(target), "adapter rejects the other region despite primary TickThread");
            fixtureViewer();
            on(target, () -> {
                targetThread = Thread.currentThread().getName();
                check(Bukkit.isOwnedByCurrentRegion(target) && !Bukkit.isOwnedByCurrentRegion(viewer), "target callback owns only target region");
                check(InventoryTransferView.owns(target) && !InventoryTransferView.owns(viewer), "target adapter rejects viewer region");
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
        access.load(viewer, target.getUniqueId(), PlayerScanner.Storage.INVENTORY, loaded -> {
            check(Bukkit.isOwnedByCurrentRegion(viewer), "online load callback is dispatched to viewer entity thread");
            ItemStack[] proposed = loaded.items(); proposed[0] = null;
            AtomicInteger guards = new AtomicInteger();
            access.saveTransfer(viewer, loaded, proposed, () -> {
                check(Bukkit.isOwnedByCurrentRegion(viewer), "final transaction guard executes on viewer entity thread");
                return guards.incrementAndGet() == 1;
            }, message -> fail(new AssertionError("Second-phase guard rejection unexpectedly saved")), reason -> {
                check(guards.get() == 2, "guard is rechecked after target mutation, before administrator completion");
                check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)), "rejected second phase preserves administrator baseline");
                on(target, () -> {
                    check(stack(target.getInventory().getItem(0), Material.DIAMOND, 4), "guard rejection conditionally restores target on target entity thread");
                    on(viewer, () -> pendingLifecycleCase(true));
                });
            });
        }, reason -> fail(new AssertionError("Online load failed: " + reason)));
    }

    /** Block only the viewer owner until another real region sees the lifecycle event after apply.
     * This makes the target-before-viewer race deterministic without relying on CPU timing. */
    private void pendingLifecycleCase(boolean death) {
        on(victim, () -> {
            check(!Bukkit.isOwnedByCurrentRegion(viewer), "lifecycle fixture uses a separate actual region owner");
            victim.setHealth(20); victim.getInventory().clear();
            victim.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
            victimFile = BukkitCompat.playerDataDirectory(victim.getWorld()).resolve(victim.getUniqueId() + ".dat");
            on(viewer, () -> access.load(viewer, victim.getUniqueId(), PlayerScanner.Storage.INVENTORY, loaded -> {
                ItemStack[] proposed = loaded.items(); proposed[0] = null;
                AtomicInteger guards = new AtomicInteger();
                lifecycleObserved = new java.util.concurrent.CountDownLatch(1);
                pendingDeath = death; pendingQuit = !death;
                access.saveTransfer(viewer, loaded, proposed, () -> {
                    check(Bukkit.isOwnedByCurrentRegion(viewer), "pending lifecycle guard executes on viewer owner");
                    guards.incrementAndGet(); return true;
                }, result -> fail(new AssertionError("Lifecycle interruption unexpectedly committed administrator side")), reason -> {
                    check(guards.get() == 1, "finished lifecycle attempt suppresses queued viewer confirmation");
                    check(Arrays.equals(viewerBaseline, BukkitCompat.storageContents(viewer)), "pending lifecycle rollback leaves administrator inventory untouched");
                    if (death) later(victim, 3, () -> {
                        check(!victim.isDead() && victim.getHealth() > 0, "pending transfer death is actually cancelled before native inventory clearing");
                        check(stack(victim.getInventory().getItem(0), Material.DIAMOND, 4), "cancelled pending death preserves original target inventory");
                        on(viewer, () -> pendingLifecycleCase(false));
                    });
                    else later(viewer, 5, () -> {
                        try {
                            check(!victim.isOnline(), "pending target quit retires the actual TCP connection");
                            java.util.Map<String,Object> saved = com.xinghan.itemsearch.nbt.NbtReader.readAuto(java.nio.file.Files.readAllBytes(victimFile), 16777216, 128);
                            boolean found = false;
                            for (Object entry : (java.util.List<?>) saved.get("Inventory")) {
                                java.util.Map<String,Object> item = (java.util.Map<String,Object>) entry;
                                if (((Number) item.get("Slot")).intValue() == 0) {
                                    com.xinghan.itemsearch.item.ItemData data = com.xinghan.itemsearch.item.ItemData.from(item, java.util.Collections.<String>emptySet());
                                    found = "DIAMOND".equals(data.material()) && data.count() == 4;
                                }
                            }
                            check(found, "normal native quit persistence saves restored target stack before scheduler retirement");
                            retirementCase();
                        } catch (Throwable error) { fail(error); }
                    });
                });
                later(victim, 1, () -> lifecycleAfterApply(death, 0));
                try { check(lifecycleObserved.await(2, java.util.concurrent.TimeUnit.SECONDS), "target lifecycle callback completes while viewer confirmation is held"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); fail(error); }
            }, reason -> fail(new AssertionError("Lifecycle load failed: " + reason))));
        });
    }

    private void lifecycleAfterApply(boolean death, int tries) {
        java.util.Map<?,?> attempts = (java.util.Map<?,?>) field(access, "onlinePending");
        Object attempt = attempts.get(victim.getUniqueId());
        if (attempt == null || !Boolean.TRUE.equals(field(attempt, "mutated"))) {
            if (tries > 20) { fail(new AssertionError("Target did not reach native apply phase")); lifecycleObserved.countDown(); return; }
            later(victim, 1, () -> lifecycleAfterApply(death, tries + 1)); return;
        }
        check(BukkitCompat.air(victim.getInventory().getItem(0)), "target was actually mutated before lifecycle interruption");
        if (death) victim.setHealth(0);
        else victim.kickPlayer("Isolated pending-target-quit fixture");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void pendingDeathObserved(PlayerDeathEvent event) {
        if (!pendingDeath || event.getEntity() != victim) return;
        try {
            check(Boolean.TRUE.equals(event.getClass().getMethod("isCancelled").invoke(event)), "real death event is cancelled for unconfirmed inventory transfer");
            check(stack(victim.getInventory().getItem(0), Material.DIAMOND, 4), "death LOWEST rollback restores stack before native drops/clearing");
            check(!((java.util.Map<?,?>) field(access, "onlinePending")).containsKey(victim.getUniqueId()), "death rollback retires pending transaction before viewer acknowledgment");
        } catch (Throwable error) { fail(error); }
        finally { pendingDeath = false; lifecycleObserved.countDown(); }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void pendingQuitObserved(PlayerQuitEvent event) {
        if (!pendingQuit || event.getPlayer() != victim) return;
        try {
            check(Bukkit.isOwnedByCurrentRegion(victim), "pending quit handler runs on departing target owner");
            check(stack(victim.getInventory().getItem(0), Material.DIAMOND, 4), "quit LOWEST rollback restores stack before native playerdata save");
            check(!((java.util.Map<?,?>) field(access, "onlinePending")).containsKey(victim.getUniqueId()), "quit rollback retires pending transaction before viewer acknowledgment");
        } catch (Throwable error) { fail(error); }
        finally { pendingQuit = false; lifecycleObserved.countDown(); }
    }

    private void retirementCase() {
        editor.open(viewer, target.getUniqueId(), PlayerScanner.Storage.INVENTORY);
        menu(54, 0, top -> {
            // Real detached cursor, as produced by native pickup. The server's ordinary close path must not return it.
            viewer.setItemOnCursor(top.getItem(27).clone()); top.setItem(27, null);
            check(stack(viewer.getItemOnCursor(), Material.DIAMOND, 4), "retirement fixture holds target clone on real menu cursor");
            on(target, () -> target.kickPlayer("Isolated Folia target-retirement fixture"));
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
                check(!search.isEnabled(), "main plugin is actually disabled from Folia global thread");
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
        check(Bukkit.isOwnedByCurrentRegion(viewer), "GUI action runs on actual viewer owning region");
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
        getLogger().info("FOLIA CHECK " + checks.incrementAndGet() + ": " + description);
    }
    private void on(Player player, Runnable task) { player.getScheduler().execute(this, safe(task), () -> fail(new IllegalStateException("Unexpected fixture player retirement " + player.getName())), 1L); }
    private void later(Player player, long ticks, Runnable task) { player.getScheduler().execute(this, safe(task), () -> fail(new IllegalStateException("Unexpected fixture player retirement " + player.getName())), ticks); }
    private void global(long ticks, Runnable task) { Bukkit.getGlobalRegionScheduler().runDelayed(this, ignored -> safe(task).run(), ticks); }
    private Runnable safe(Runnable task) { return () -> { if (!ended.get()) try { task.run(); } catch (Throwable error) { fail(error); } }; }
    private void finish() {
        if (!ended.compareAndSet(false, true)) return;
        getLogger().info("FOLIA TRANSFER PROBE PASSED " + checks.get() + " checks; actual TCP players, cross-owner regions; viewerThread=" + viewerThread + "; targetThread=" + targetThread);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, ignored -> Bukkit.shutdown(), 3L);
    }
    private void fail(Throwable error) {
        if (!ended.compareAndSet(false, true)) return;
        getLogger().log(Level.SEVERE, "FOLIA TRANSFER PROBE FAILED after " + checks.get() + " checks", error);
        Bukkit.getGlobalRegionScheduler().runDelayed(this, ignored -> Bukkit.shutdown(), 3L);
    }
}
